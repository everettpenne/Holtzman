#!/usr/bin/env python3
"""Learn scene-adaptive tone targets from the Pixel pairs.

    fit_tone.py build [dataset]     per paired scene: the Pixel photo's luma percentiles (what IxAutoTone.TARGETS holds as one fixed row) and scene features
                                    measured on the raw burst (what the app can know before it tones the picture)
    fit_tone.py fit   [dataset]     ridge regression of each percentile on the features, leave-one-scene-out, against the fixed-median baseline

Why: IxAutoTone bends every picture's brightness ranks onto ONE row of targets (the medians over the pairs). That throws away what the scene is: the Pixel
app's own pictures move a lot from scene to scene (a dim or low-contrast overcast scene is rendered much brighter and airier than a contrasty one), and the
fit_colour.py analysis found that the largest remaining difference to the Pixel is exactly this, per scene. Ranks are taken on the finished luma exactly as
IxAutoTone does (0.2126 R + 0.7152 G + 0.0722 B of the sRGB picture, 0..255), so a predicted row can replace TARGETS directly.

Features come from the burst's merged mosaic (aligned_0.raw, or the first DNG): block-averaged linear luma after black level, white balance and the camera matrix,
plus the capture's exposure and ISO. All of them exist on the phone before the tone curve is built.
Needs numpy pillow (no OpenCV).
"""
import argparse, json, os, sys
import numpy as np
from PIL import Image, ImageOps

RANKS = [1, 5, 25, 50, 75, 95, 99]
LUMA = np.array([.2126, .7152, .0722], np.float32)
BLOCK = 16
FEATURES = ['ev', 'lmean', 'lspread', 'dr', 'hi', 'lo', 'sky']


def pixel_percentiles(path):
    im = ImageOps.exif_transpose(Image.open(path)).convert('RGB'); w, h = im.size; s = 600 / max(w, h)
    a = np.asarray(im.resize((round(w * s), round(h * s)), Image.BOX), np.float32)
    return np.percentile(a @ LUMA, RANKS).tolist()


def raw_luma_blocks(scene_dir, meta):
    """Block-averaged linear luma (0..~1 of the sensor's range), from the first aligned frame, with black level, white balance and camera matrix applied."""
    f = meta['frames'][0]; w, h = f['width'], f['height']
    p = os.path.join(scene_dir, 'aligned_0.raw')
    if not os.path.isfile(p): return None
    m = np.fromfile(p, '<u2').reshape(h, w).astype(np.float32)
    black = np.array(f['black'], np.float32); white = float(f['white'])
    # the Pixel 10a sensor is GRBG: [0,0]=G (red row), [0,1]=R, [1,0]=B, [1,1]=G
    G1 = m[0::2, 0::2] - black[0]; R = m[0::2, 1::2] - black[1]; B = m[1::2, 0::2] - black[2]; G2 = m[1::2, 1::2] - black[3]
    wb = f['wbGains']; scale = max(white - float(black.mean()), 1.0)
    R = np.maximum(R, 0) * wb[0] / scale; G = np.maximum((G1 + G2) / 2, 0) * wb[1] / scale; B = np.maximum(B, 0) * wb[3] / scale
    ccm = np.array(f['ccm'], np.float32).reshape(3, 3); rgb = np.stack([R, G, B], -1) @ ccm.T
    y = np.maximum(rgb @ LUMA, 0)
    b = BLOCK // 2; hh, ww = (y.shape[0] // b) * b, (y.shape[1] // b) * b
    return y[:hh, :ww].reshape(hh // b, b, ww // b, b).mean((1, 3))


def features(y, meta):
    f = meta['frames'][0]; t = f['exposureNs'] / 1e9; iso = f['iso']; ap = f.get('aperture', 1.7) or 1.7
    mean = max(float(y.mean()), 1e-5); ly = np.log2(np.maximum(y, 1e-5)); p = np.percentile(y, [5, 25, 50, 75, 95, 99])
    top = y[: y.shape[0] // 3]
    return {
        'ev': float(np.log2(ap * ap / t) - np.log2(iso / 100.0) + np.log2(mean / 0.18)),   # how bright the scene really is (EV at ISO 100, metered)
        'lmean': float(np.log2(mean)),                                                      # how bright the raw picture is
        'lspread': float(ly.std()),                                                         # spread of log brightness: contrast of the scene
        'dr': float(np.log2(max(p[5], 1e-5) / max(p[0], 1e-5))),                            # 99th over 5th percentile, in stops
        'hi': float(np.log2(max(p[4], 1e-5) / max(p[2], 1e-5))),                            # how far the highlights sit above the middle
        'lo': float(np.log2(max(p[2], 1e-5) / max(p[1], 1e-5))),                            # how far the middle sits above the shadows
        'sky': float((top > np.percentile(y, 90)).mean()),                                  # bright mass in the top third of the picture (sky)
    }


def cmd_build(a):
    root = os.path.abspath(os.path.expanduser(a.dataset)); out = os.path.join(root, 'colour_fit'); os.makedirs(out, exist_ok=True)
    rows = []
    for px, sc, dt in json.load(open(os.path.join(root, 'pixel_pairs.json'))):
        pp = os.path.join(root, 'PixelDoubles', px); sd = os.path.join(root, sc); mp = os.path.join(sd, 'meta.json')
        if not (os.path.isfile(pp) and os.path.isfile(mp)): continue
        meta = json.load(open(mp)); y = raw_luma_blocks(sd, meta)
        if y is None: print('no aligned frame:', sc); continue
        rows.append({'scene': sc, 'tags': meta.get('tags', []), 'pixel': pixel_percentiles(pp), **features(y, meta)})
        print(f'{sc[6:]} pixel p50 {rows[-1]["pixel"][3]:5.1f}  ev {rows[-1]["ev"]:5.1f}  spread {rows[-1]["lspread"]:.2f}  dr {rows[-1]["dr"]:.1f}')
    json.dump(rows, open(os.path.join(out, 'tone_features.json'), 'w'))
    print(len(rows), 'scenes ->', out)


def ridge(X, Y, lam):
    mu, sd = X.mean(0), X.std(0) + 1e-9; Z = (X - mu) / sd; ym = Y.mean(0)
    W = np.linalg.solve(Z.T @ Z + lam * np.eye(Z.shape[1]), Z.T @ (Y - ym)); return mu, sd, W, ym


def predict(model, X):
    mu, sd, W, ym = model; return ((X - mu) / sd) @ W + ym


def cmd_fit(a):
    root = os.path.abspath(os.path.expanduser(a.dataset)); rows = json.load(open(os.path.join(root, 'colour_fit', 'tone_features.json')))
    use = [f for f in FEATURES if f not in (a.drop or [])]
    X = np.array([[r[f] for f in use] for r in rows]); Y = np.array([r['pixel'] for r in rows]); n = len(rows)
    base = np.median(Y, 0)
    print(f'{n} scenes. Pixel luma percentiles (of 255) over the scenes: ' + '  '.join(f'p{k}: {np.percentile(Y[:, i], 10):.0f}-{np.median(Y[:, i]):.0f}-{np.percentile(Y[:, i], 90):.0f}' for i, k in enumerate(RANKS)))
    print('\nleave-one-scene-out error of predicting each percentile (mean absolute error, luma of 255):')
    print('                       ' + ''.join(f'  p{k:<5}' for k in RANKS) + '   mean')
    e0 = np.abs(Y - base).mean(0); print(f'fixed medians (now)   ' + ''.join(f'{v:7.1f} ' for v in e0) + f'{e0.mean():7.1f}')
    best = None
    for lam in (0.1, 1, 3, 10, 30, 100):
        P = np.zeros_like(Y)
        for i in range(n):
            tr = np.arange(n) != i; P[i] = predict(ridge(X[tr], Y[tr], lam), X[i:i + 1])[0]
        e = np.abs(Y - P).mean(0); print(f'ridge lam={lam:<5}      ' + ''.join(f'{v:7.1f} ' for v in e) + f'{e.mean():7.1f}')
        if best is None or e.mean() < best[0]: best = (e.mean(), lam, P)
    _, lam, P = best; print(f'\nbest lam {lam}: mean error {best[0]:.1f} against {e0.mean():.1f} now ({100 * (1 - best[0] / e0.mean()):.0f}% lower)')
    mu, sd, W, ym = ridge(X, Y, lam)
    print('\nwhat moves each percentile (change per one standard deviation of the feature):')
    print(f'{"":10}' + ''.join(f'  p{k:<5}' for k in RANKS))
    for j, f in enumerate(use): print(f'{f:10}' + ''.join(f'{W[j, i] / 1:7.1f} ' for i in range(len(RANKS))) + f'   (sd {sd[j]:.2f})')
    # per tag group: error now and with the model
    tags = {}
    for i, r in enumerate(rows):
        for t in r['tags']: tags.setdefault(t, []).append(i)
    print('\nby scene tag (mean absolute error over the percentiles, now -> model):')
    for t, idx in sorted(tags.items(), key=lambda kv: -len(kv[1])):
        if len(idx) < 8: continue
        print(f'  {t:<14} n={len(idx):3d}  {np.abs(Y[idx] - base).mean():5.1f} -> {np.abs(Y[idx] - P[idx]).mean():5.1f}')
    json.dump({'features': use, 'ranks': RANKS, 'lam': lam, 'mu': mu.tolist(), 'sd': sd.tolist(), 'W': W.tolist(), 'base': ym.tolist(), 'cv_mae': best[0], 'fixed_mae': float(e0.mean())},
              open(os.path.join(root, 'colour_fit', 'tone_model.json'), 'w'), indent=1)


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('cmd', choices=['build', 'fit']); ap.add_argument('dataset', nargs='?', default='~/Code/holtzman-dataset')
    ap.add_argument('--drop', nargs='*'); a = ap.parse_args(); {'build': cmd_build, 'fit': cmd_fit}[a.cmd](a)


if __name__ == '__main__':
    main()
