#!/usr/bin/env python3
"""Learn the Pixel Camera app's colour treatment from the dataset's Pixel pairs.

    fit_colour.py build   [dataset]            register every pair and cache colour samples (slow part, run once)
    fit_colour.py analyze [dataset]            how the Pixel's colours differ from ours: lightness, chroma ("pop"), hue, by tone band
    fit_colour.py fit     [dataset]            fit an affine and a 17^3 3D LUT with scene-wise cross-validation, report the colour difference to the Pixel on pictures
                                              the fit never saw, write the LUT as a .cube (colour_fit/pixel_look.cube)
    fit_colour.py split   [dataset]            how much of the gap is tone and how much is colour, and what a per-scene exposure choice would be worth

Why registration: our photo and the Pixel's are two exposures of the same moment, taken a second or two apart, so they differ by a few pixels of shift and
a little rotation. Each pair is aligned with an affine ECC fit on local-contrast-normalised luma (which ignores tone and colour differences), both pictures
are blurred and sampled only where they are flat (so a leftover misalignment of an edge does not teach the fit anything), and the cross-validation holds out
whole scenes, never single pixels.

Source = the scene's holtzman.jpg (the finished photo of the app that captured it). Only scenes whose meta.json records the same app version and look
saturation are used together, because the older previews were rendered with older looks (--all to override).
Needs numpy scipy pillow opencv-python-headless.
"""
import argparse, json, os, sys
import numpy as np
import cv2
from PIL import Image, ImageOps

LONG = 700          # working size (long side) for registration and sampling
STRIDE = 3          # sample every n-th pixel of the blurred pictures
SIGMA = 2.5         # blur before sampling (px at the working size)


# ---------- colour maths ----------
def srgb_to_lin(c):
    c = c / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(l):
    l = np.clip(l, 0, 1)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * l ** (1 / 2.4) - 0.055) * 255.0


M = np.array([[0.4124564, 0.3575761, 0.1804375], [0.2126729, 0.7151522, 0.0721750], [0.0193339, 0.1191920, 0.9503041]])
WHITE = np.array([0.95047, 1.0, 1.08883])


def to_lab(rgb):
    xyz = srgb_to_lin(np.asarray(rgb, np.float64)) @ M.T / WHITE
    f = np.where(xyz > 216 / 24389, np.cbrt(xyz), (24389 / 27 * xyz + 16) / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def lch(lab):
    return lab[..., 0], np.hypot(lab[..., 1], lab[..., 2]), np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360


def de2000(l1, l2):
    """CIEDE2000 between two Lab arrays (N,3)."""
    L1, a1, b1 = l1[..., 0], l1[..., 1], l1[..., 2]; L2, a2, b2 = l2[..., 0], l2[..., 1], l2[..., 2]
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2); Cb = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cb ** 7 / (Cb ** 7 + 25.0 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360; h2p = np.degrees(np.arctan2(b2, a2p)) % 360
    dL = L2 - L1; dC = C2p - C1p
    dh = h2p - h1p; dh = np.where(dh > 180, dh - 360, np.where(dh < -180, dh + 360, dh)); dh = np.where(C1p * C2p == 0, 0, dh)
    dH = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh / 2))
    Lb = (L1 + L2) / 2; Cbp = (C1p + C2p) / 2
    hs = h1p + h2p; hb = np.where(np.abs(h1p - h2p) > 180, np.where(hs < 360, (hs + 360) / 2, (hs - 360) / 2), hs / 2); hb = np.where(C1p * C2p == 0, hs, hb)
    T = 1 - 0.17 * np.cos(np.radians(hb - 30)) + 0.24 * np.cos(np.radians(2 * hb)) + 0.32 * np.cos(np.radians(3 * hb + 6)) - 0.20 * np.cos(np.radians(4 * hb - 63))
    dth = 30 * np.exp(-(((hb - 275) / 25) ** 2)); Rc = 2 * np.sqrt(Cbp ** 7 / (Cbp ** 7 + 25.0 ** 7))
    Sl = 1 + 0.015 * (Lb - 50) ** 2 / np.sqrt(20 + (Lb - 50) ** 2); Sc = 1 + 0.045 * Cbp; Sh = 1 + 0.015 * Cbp * T
    Rt = -np.sin(np.radians(2 * dth)) * Rc
    return np.sqrt((dL / Sl) ** 2 + (dC / Sc) ** 2 + (dH / Sh) ** 2 + Rt * (dC / Sc) * (dH / Sh))


# ---------- loading / registration ----------
def load(path, long=LONG):
    im = ImageOps.exif_transpose(Image.open(path)).convert('RGB'); w, h = im.size; s = long / max(w, h)
    return np.asarray(im.resize((round(w * s), round(h * s)), Image.BOX), np.uint8)


def feat(a):
    g = cv2.cvtColor(a, cv2.COLOR_RGB2GRAY).astype(np.float32)
    g = cv2.GaussianBlur(g, (0, 0), 1.5)
    return g / (cv2.GaussianBlur(g, (0, 0), 12) + 8)


def register(src, tgt):
    """Warp tgt onto src's grid. Returns (warped, valid mask, ecc correlation, scale) or None."""
    a, b = feat(src), feat(tgt); W = np.eye(2, 3, dtype=np.float32)
    try:
        cc, W = cv2.findTransformECC(a, b, W, cv2.MOTION_AFFINE, (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 200, 1e-6), None, 5)
    except cv2.error:
        return None
    flags = cv2.INTER_AREA + cv2.WARP_INVERSE_MAP; size = (src.shape[1], src.shape[0])
    out = cv2.warpAffine(tgt, W, size, flags=flags)
    ok = cv2.warpAffine(np.ones(tgt.shape[:2], np.uint8), W, size, flags=cv2.INTER_NEAREST + cv2.WARP_INVERSE_MAP)
    ok = cv2.erode(ok, np.ones((15, 15), np.uint8)).astype(bool)
    return out, ok, float(cc), float(np.sqrt(abs(np.linalg.det(W[:, :2]))))


def samples(src, tgt, ok):
    """Blurred, flat-region pixel pairs (n,3) uint8-range floats from a registered pair."""
    s = cv2.GaussianBlur(src.astype(np.float32), (0, 0), SIGMA); t = cv2.GaussianBlur(tgt.astype(np.float32), (0, 0), SIGMA)
    ys = cv2.cvtColor(s.astype(np.uint8), cv2.COLOR_RGB2GRAY).astype(np.float32); yt = cv2.cvtColor(t.astype(np.uint8), cv2.COLOR_RGB2GRAY).astype(np.float32)
    gs = np.hypot(cv2.Sobel(ys, cv2.CV_32F, 1, 0), cv2.Sobel(ys, cv2.CV_32F, 0, 1)); gt = np.hypot(cv2.Sobel(yt, cv2.CV_32F, 1, 0), cv2.Sobel(yt, cv2.CV_32F, 0, 1))
    q = ok & (np.arange(ok.shape[0])[:, None] % STRIDE == 0) & (np.arange(ok.shape[1])[None, :] % STRIDE == 0)
    # the flattest half of the picture (in both): a leftover misalignment of an edge cannot teach the fit anything there
    flat = q & (gs <= np.percentile(gs[ok], 50)) & (gt <= np.percentile(gt[ok], 50))
    return s[flat], t[flat]


def pairs_with_version(root, allv):
    pairs = json.load(open(os.path.join(root, 'pixel_pairs.json'))); out = []
    for px, sc, dt in pairs:
        hp = os.path.join(root, sc, 'holtzman.jpg'); pp = os.path.join(root, 'PixelDoubles', px)
        if not (os.path.isfile(hp) and os.path.isfile(pp)): continue
        meta = json.load(open(os.path.join(root, sc, 'meta.json')))
        out.append((px, sc, hp, pp, meta))
    if allv: return out
    from collections import Counter
    key = lambda m: (m.get('appVersion'), m.get('lookSaturation'))
    top = Counter(key(m) for *_, m in out).most_common(1)[0][0]
    return [p for p in out if key(p[4]) == top]


def cmd_build(a):
    root = os.path.abspath(os.path.expanduser(a.dataset)); cache = os.path.join(root, 'colour_fit'); os.makedirs(cache, exist_ok=True)
    S, T, SC, tags = [], [], [], []; kept = 0
    for px, sc, hp, pp, meta in pairs_with_version(root, a.all):
        src = load(hp); tgt = load(pp)
        if src.shape != tgt.shape: tgt = cv2.resize(tgt, (src.shape[1], src.shape[0]), interpolation=cv2.INTER_AREA)
        r = register(src, tgt)
        if r is None or r[2] < 0.80 or abs(r[3] - 1) > 0.04:
            print(f'skip {sc}: registration {"failed" if r is None else f"cc={r[2]:.2f} scale={r[3]:.3f}"}'); continue
        s, t = samples(src, r[0], r[1])
        if len(s) < 2000: print(f'skip {sc}: only {len(s)} flat samples'); continue
        # at most 30 000 samples per scene so no scene dominates
        if len(s) > 30000:
            i = np.random.default_rng(0).choice(len(s), 30000, replace=False); s, t = s[i], t[i]
        S.append(s.astype(np.float32)); T.append(t.astype(np.float32)); SC.append(np.full(len(s), kept, np.int32)); tags.append((sc, meta.get('tags', []))); kept += 1
        print(f'ok   {sc}: cc={r[2]:.2f} samples={len(s)}')
    np.savez_compressed(os.path.join(cache, 'samples.npz'), S=np.concatenate(S), T=np.concatenate(T), scene=np.concatenate(SC))
    json.dump(tags, open(os.path.join(cache, 'scenes.json'), 'w'), indent=1)
    print(f'\n{kept} scenes, {sum(len(x) for x in S)} samples -> {cache}')


def load_cache(a):
    root = os.path.abspath(os.path.expanduser(a.dataset)); cache = os.path.join(root, 'colour_fit')
    z = np.load(os.path.join(cache, 'samples.npz')); return z['S'], z['T'], z['scene'], json.load(open(os.path.join(cache, 'scenes.json'))), cache


def cmd_analyze(a):
    S, T, sc, scenes, _ = load_cache(a)
    ls, lt = to_lab(S), to_lab(T); Ls, Cs, Hs = lch(ls); Lt, Ct, Ht = lch(lt)
    de = de2000(ls, lt)
    print(f'{len(scenes)} scenes, {len(S)} samples.  CIEDE2000, ours vs Pixel: median {np.median(de):.2f}, mean {de.mean():.2f}, 90th percentile {np.percentile(de, 90):.2f}')
    print('\nBy lightness band (ours): Pixel minus ours in lightness, and Pixel chroma / our chroma (where ours has some colour)')
    for lo, hi in [(0, 20), (20, 35), (35, 50), (50, 65), (65, 80), (80, 100)]:
        m = (Ls >= lo) & (Ls < hi)
        if m.sum() < 500: continue
        mc = m & (Cs > 8)
        print(f'  L {lo:3d}-{hi:3d}: n={m.sum():8d}  dL {np.median(Lt[m] - Ls[m]):+5.1f}   chroma x{np.median(Ct[mc] / Cs[mc]):.2f}   dE {np.median(de[m]):.1f}')
    print('\nBy hue (ours): chroma ratio and hue shift in degrees (Pixel minus ours), pixels with chroma > 12 and L 20-90')
    names = ['red', 'orange', 'yellow', 'yel-green', 'green', 'cyan-grn', 'cyan', 'azure', 'blue', 'violet', 'magenta', 'rose']
    for i in range(12):
        lo = i * 30 - 15; h = (Hs - lo) % 360
        m = (h < 30) & (Cs > 12) & (Ls > 20) & (Ls < 90)
        if m.sum() < 500: continue
        dh = ((Ht[m] - Hs[m] + 180) % 360) - 180
        print(f'  {names[i]:<10} centre {i * 30:3d}: n={m.sum():7d}  chroma x{np.median(Ct[m] / Cs[m]):.2f}   hue {np.median(dh):+5.1f}   dL {np.median(Lt[m] - Ls[m]):+5.1f}')
    neutral = (Cs < 6) & (Ls > 25) & (Ls < 85)
    print(f'\nNear-neutral ours (n={neutral.sum()}): Pixel a* {np.median(lt[neutral][:, 1] - ls[neutral][:, 1]):+.2f}, b* {np.median(lt[neutral][:, 2] - ls[neutral][:, 2]):+.2f}  (positive b* = Pixel warmer)')
    print('\nPer scene median dE and chroma ratio (Pixel / ours) -- the spread between scenes is how much a single fixed fit can explain:')
    rows = []
    for i, (name, tg) in enumerate(scenes):
        m = sc == i; mc = m & (Cs > 8)
        rows.append((np.median(de[m]), np.median(Ct[mc] / Cs[mc]) if mc.sum() > 100 else np.nan, name, tg))
    for d, r, name, tg in sorted(rows)[:3] + sorted(rows)[-3:]: print(f'  {name[6:]}  dE {d:.1f}  chroma x{r:.2f}  {",".join(tg)}')
    rs = np.array([r for _, r, _, _ in rows]); print(f'  chroma ratio across scenes: median {np.nanmedian(rs):.2f}, 10th-90th percentile {np.nanpercentile(rs, 10):.2f}-{np.nanpercentile(rs, 90):.2f}')


# ---------- models ----------
N = 17   # LUT nodes per axis


def tri_weights(x):
    """x: (n,3) in 0..255. Returns sparse (n, N^3) trilinear interpolation matrix."""
    from scipy import sparse
    g = np.clip(np.asarray(x, np.float64) / 255.0, 0, 1) * (N - 1); i0 = np.minimum(np.floor(g).astype(int), N - 2); f = g - i0
    rows, cols, vals = [], [], []; n = len(x); r = np.arange(n)
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 2] if db else 1 - f[:, 2])
                rows.append(r); cols.append(((i0[:, 0] + dr) * N + (i0[:, 1] + dg)) * N + (i0[:, 2] + db)); vals.append(w)
    return sparse.csr_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(n, N ** 3))


def identity_lut():
    a = np.linspace(0, 255, N); R, G, B = np.meshgrid(a, a, a, indexing='ij'); return np.stack([R, G, B], -1).reshape(-1, 3)


def laplacian():
    from scipy import sparse
    d = sparse.diags([1, -2, 1], [0, 1, 2], shape=(N - 2, N)); I = sparse.identity(N)
    parts = [sparse.kron(sparse.kron(d, I), I), sparse.kron(sparse.kron(I, d), I), sparse.kron(sparse.kron(I, I), d)]
    return sparse.vstack(parts).tocsr()


def fit_lut(S, T, lam, mu=1e-3, irls=3, clip_w=None):
    """Residual 3D LUT (identity + delta) by robust regularised least squares. Returns (N^3,3) lut in 0..255."""
    from scipy import sparse
    from scipy.sparse.linalg import spsolve
    A = tri_weights(S); idl = identity_lut(); y = (T - A @ idl); Lp = laplacian(); reg = lam * (Lp.T @ Lp) + mu * sparse.identity(N ** 3)
    w = np.ones(len(S)) if clip_w is None else clip_w.copy(); delta = np.zeros((N ** 3, 3))
    for it in range(irls):
        Aw = A.multiply(w[:, None]).tocsr(); H = (A.T @ Aw + reg).tocsc()
        for c in range(3): delta[:, c] = spsolve(H, A.T @ (w * y[:, c]))
        r = np.linalg.norm(T - (A @ (idl + delta)), axis=1); w = np.minimum(1.0, 25.0 / np.maximum(r, 1e-6))
    return np.clip(idl + delta, 0, 255)


def apply_lut(lut, x):
    return tri_weights(x) @ lut


def fit_matrix_curves(S, T):
    """3x3 matrix on linear light followed by per-channel gamma-free curves is overkill here; keep a plain least-squares affine on display values."""
    X = np.c_[S, np.ones(len(S))]; W, *_ = np.linalg.lstsq(X, T, rcond=None); return W


def score(P, T):
    d = de2000(to_lab(P), to_lab(T)); return float(np.median(d)), float(d.mean()), float(np.percentile(d, 90))


def cmd_fit(a):
    S, T, sc, scenes, cache = load_cache(a); K = 5; ns = len(scenes)
    rng = np.random.default_rng(1); order = rng.permutation(ns); fold_of = np.zeros(ns, int); fold_of[order] = np.arange(ns) % K
    sample_fold = fold_of[sc]
    print(f'{ns} scenes, {len(S)} samples, {K}-fold cross-validation by scene.  Baseline (no change): median dE {score(S, T)[0]:.2f}, mean {score(S, T)[1]:.2f}, p90 {score(S, T)[2]:.2f}')
    # affine on display values
    P = np.zeros_like(S)
    for k in range(K):
        tr, te = sample_fold != k, sample_fold == k; W = fit_matrix_curves(S[tr], T[tr]); P[te] = np.c_[S[te], np.ones(te.sum())] @ W
    print(f'3x3 matrix + offset            : median {score(P, T)[0]:.2f}, mean {score(P, T)[1]:.2f}, p90 {score(P, T)[2]:.2f}')
    best = None
    for lam in (a.lams if hasattr(a, 'lams') else (0.3, 1, 3, 10, 30)):
        P = np.zeros_like(S)
        for k in range(K):
            tr, te = sample_fold != k, sample_fold == k; lut = fit_lut(S[tr], T[tr], lam); P[te] = apply_lut(lut, S[te])
        r = score(P, T); print(f'3D LUT {N}^3, smoothness {lam:<5}  : median {r[0]:.2f}, mean {r[1]:.2f}, p90 {r[2]:.2f}')
        if best is None or r[1] < best[1][1]: best = (lam, r, P.copy())
    lam = best[0]; print(f'\nbest smoothness {lam}: held-out median dE {best[1][0]:.2f} (baseline {score(S, T)[0]:.2f}), mean {best[1][1]:.2f}')
    P = best[2]
    # what remains: per scene, and in hue/lightness terms
    ls, lt, lp = to_lab(S), to_lab(T), to_lab(P)
    Ls, Cs, Hs = lch(ls); Lt, Ct, Ht = lch(lt); Lp_, Cp, Hp = lch(lp)
    print('Held-out lightness and chroma now (Pixel minus fitted lightness; Pixel chroma / fitted):')
    for lo, hi in [(0, 20), (20, 35), (35, 50), (50, 65), (65, 80), (80, 100)]:
        m = (Lp_ >= lo) & (Lp_ < hi); mc = m & (Cp > 8)
        if m.sum() > 500: print(f'  L {lo:3d}-{hi:3d}: dL {np.median(Lt[m] - Lp_[m]):+5.1f}  chroma x{np.median(Ct[mc] / Cp[mc]):.2f}')
    final = fit_lut(S, T, lam); os.makedirs(os.path.join(cache), exist_ok=True)
    out = os.path.join(cache, 'pixel_look.cube'); write_cube(final, out, 'Holtzman to Pixel look (fit of %d scenes)' % ns); print('wrote', out)
    np.save(os.path.join(cache, 'pixel_look_lut.npy'), final); np.save(os.path.join(cache, 'cv_pred.npy'), P)


def write_cube(lut, path, title):
    n = N; L = lut.reshape(n, n, n, 3) / 255.0
    with open(path, 'w') as f:
        f.write(f'TITLE "{title}"\nLUT_3D_SIZE {n}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n')
        for b in range(n):
            for g in range(n):
                for r in range(n): f.write('%.6f %.6f %.6f\n' % tuple(L[r, g, b]))


# ---------- tone versus colour ----------
def cmd_split(a):
    """How much of the gap is tone (lightness) and how much is colour (chroma by lightness and hue), each fitted alone and together, held-out by scene."""
    from scipy.ndimage import gaussian_filter1d, gaussian_filter
    S, T, sc, scenes, _ = load_cache(a); ns = len(scenes); K = 5
    rng = np.random.default_rng(1); order = rng.permutation(ns); fo = np.zeros(ns, int); fo[order] = np.arange(ns) % K; sf = fo[sc]
    ls, lt = to_lab(S), to_lab(T)

    def parts(l): return l[:, 0], np.hypot(l[:, 1], l[:, 2]), np.arctan2(l[:, 2], l[:, 1])
    def join(L, C, h): return np.stack([L, C * np.cos(h), C * np.sin(h)], 1)

    def fit_l(Ls, Lt, nb=40):
        e = np.linspace(0, 100, nb + 1); c = (e[:-1] + e[1:]) / 2; i = np.clip(np.digitize(Ls, e) - 1, 0, nb - 1)
        med = np.array([np.median(Lt[i == k]) if (i == k).sum() > 200 else np.nan for k in range(nb)]); ok = ~np.isnan(med)
        med = np.maximum.accumulate(gaussian_filter1d(np.interp(c, c[ok], med[ok]), 1.5))
        return lambda x: np.interp(x, c, med)

    def fit_c(Ls, Cs, Hs, Ct, nl=8, nh=24):
        li = np.clip((Ls / 100 * nl).astype(int), 0, nl - 1); hi = ((Hs % (2 * np.pi)) / (2 * np.pi) * nh).astype(int) % nh; g = np.ones((nl, nh))
        for x in range(nl):
            for y in range(nh):
                m = (li == x) & (hi == y) & (Cs > 6)
                if m.sum() > 300: g[x, y] = np.median(Ct[m] / Cs[m])
        g = gaussian_filter(g, (0.8, 0.8), mode=('nearest', 'wrap'))
        return lambda L, H: g[np.clip((L / 100 * nl).astype(int), 0, nl - 1), ((H % (2 * np.pi)) / (2 * np.pi) * nh).astype(int) % nh]

    def run(name, fn):
        P = np.zeros_like(ls)
        for k in range(K):
            tr, te = sf != k, sf == k; P[te] = fn(ls[tr], lt[tr], ls[te])
        d = de2000(P, lt); print(f'{name:<46} median {np.median(d):.2f}  mean {d.mean():.2f}  p90 {np.percentile(d, 90):.2f}')

    def m_l(trs, trt, te):
        f = fit_l(trs[:, 0], trt[:, 0]); L, C, H = parts(te); return join(f(L), C, H)

    def m_c(trs, trt, te):
        Ls, Cs, Hs = parts(trs); _, Ct, _ = parts(trt); g = fit_c(Ls, Cs, Hs, Ct); L, C, H = parts(te); return join(L, C * g(L, H), H)

    def m_lc(trs, trt, te):
        f = fit_l(trs[:, 0], trt[:, 0]); Ls, Cs, Hs = parts(trs); _, Ct, _ = parts(trt); g = fit_c(f(Ls), Cs, Hs, Ct); L, C, H = parts(te); L2 = f(L); return join(L2, C * g(L2, H), H)

    d0 = de2000(ls, lt); print(f'{ns} scenes. Colour difference (CIEDE2000) to the Pixel, held out by scene:\nno change                                      median {np.median(d0):.2f}  mean {d0.mean():.2f}  p90 {np.percentile(d0, 90):.2f}')
    run('tone: one lightness curve', m_l); run('colour: chroma by lightness and hue', m_c); run('tone then colour', m_lc)
    # what would a per-scene exposure correction be worth? (an oracle, not something the app can know)
    Lp = ls.copy()
    for i in range(ns):
        m = sc == i; Lp[m, 0] += np.median(lt[m, 0] - ls[m, 0])
    d1 = de2000(Lp, lt); print(f'oracle: each scene\'s own lightness shift      median {np.median(d1):.2f}  (what choosing the right exposure per scene alone would be worth)')


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('cmd', choices=['build', 'analyze', 'fit', 'split']); ap.add_argument('dataset', nargs='?', default='~/Code/holtzman-dataset')
    ap.add_argument('--all', action='store_true'); ap.add_argument('--out', default=None)
    a = ap.parse_args(); {'build': cmd_build, 'analyze': cmd_analyze, 'fit': cmd_fit, 'split': cmd_split}[a.cmd](a)


if __name__ == '__main__':
    main()
