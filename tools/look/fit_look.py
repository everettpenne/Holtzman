#!/usr/bin/env python3
"""Fit the app's look to the Pixel Camera app's, from the dataset's Pixel pairs.

    python3 tools/look/fit_look.py [dataset_folder] [--since 20261029_000000] [--capture-saturation 0.9]

Reads the Pixel photo of every scene in pixel_pairs.json (run tools/dataset/pair.py first) and, for the scenes with a Holtzman preview, the preview too. It prints
 1. the TONE TARGETS: the Pixel app's median brightness at the 1st, 5th, 25th, 50th, 95th and 99th percentile, as the line to paste into IxAutoTone.TARGETS, next to
    the values now in the source, with a bootstrap 95% interval of each median so you can tell a real difference from noise;
 2. how the targets differ by scene type (from the tags you gave the scenes), to show how much the Pixel's tone depends on the scene;
 3. SATURATION: how much more or less saturated the Pixel's pictures are than ours, by brightness band, and the saturation value to use;
 4. COLOUR BALANCE (red/green and blue/green) of ours against the Pixel's, for information.
Everything is a statistic of the whole picture (brightness percentiles, mean saturation), so the two photos need not line up pixel for pixel.

IMPORTANT for 3 and 4: the preview in each scene was rendered by the app version that captured it, with the look it had then. Builds from 0.5.1 record that saturation
(and the app version) in meta.json and it is used per scene; for older scenes pass --capture-saturation (the old 68 pairs were rendered with 1.08). Use --since to
leave out scenes captured under a look you no longer want to compare with. Part 1 uses the Pixel photos alone, so it does not depend on any of this.
Needs numpy and Pillow (python3 -m pip install numpy pillow).
"""
import argparse, json, os, re, sys
import numpy as np
from PIL import Image, ImageOps

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RANKS = [1, 5, 25, 50, 95, 99]
LUMA = np.array([.2126, .7152, .0722], np.float32)
BANDS = [(20, 60), (60, 110), (110, 160), (160, 210)]   # luma bands (of 255) for the saturation comparison


def load(path, size=(500, 666)):
    im = ImageOps.exif_transpose(Image.open(path)).convert('RGB')
    if im.width > im.height: size = (size[1], size[0])
    return np.asarray(im.resize(size, Image.BOX), np.float32)


def stats(a):
    y = a @ LUMA; mx = a.max(2); mn = a.min(2); sat = (mx - mn) / (mx + 1e-3)
    s = []
    for lo, hi in BANDS:
        m = (y > lo) & (y < hi); s.append(float(np.median(sat[m])) if m.sum() > 500 else np.nan)
    mid = (y > 40) & (y < 200)
    g = a[..., 1][mid].mean() if mid.sum() > 500 else np.nan
    return {'p': np.percentile(y, RANKS), 'sat': s, 'rg': a[..., 0][mid].mean() / g if mid.sum() > 500 else np.nan, 'bg': a[..., 2][mid].mean() / g if mid.sum() > 500 else np.nan}


def current_targets():
    src = open(os.path.join(REPO, 'ix/src/main/kotlin/com/everettpenne/ix/IxAutoTone.kt')).read()
    m = re.search(r'TARGETS: FloatArray = floatArrayOf\(([^)]*)\)', src)
    v = [float(x.strip().rstrip('f')) for x in m[1].split(',')]
    return v[1:7]   # the six ranked targets between the 0 and 255 end points


def current_saturation():
    src = open(os.path.join(REPO, 'app/src/main/java/com/everettpenne/holtzman/save/BurstRender.kt')).read()
    return float(re.search(r'saturation = ([0-9.]+)f', src)[1])


def boot_ci(vals, n=2000, seed=0):
    r = np.random.default_rng(seed); vals = np.asarray(vals)
    meds = np.array([np.median(r.choice(vals, len(vals))) for _ in range(n)])
    return np.percentile(meds, [2.5, 97.5])


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('dataset', nargs='?', default='~/Code/holtzman-dataset')
    ap.add_argument('--since', default='', help='use our previews only from scenes at or after this time (YYYYMMDD_HHMMSS)')
    ap.add_argument('--capture-saturation', type=float, default=None, help='the saturation the app rendered previews with, for scenes that did not record it (the old pairs: 1.08)')
    ap.add_argument('--min-pairs', type=int, default=60)
    a = ap.parse_args()
    root = os.path.abspath(os.path.expanduser(a.dataset))
    pairs_file = os.path.join(root, 'pixel_pairs.json')
    if not os.path.isfile(pairs_file): sys.exit('No pixel_pairs.json: run tools/dataset/pair.py first.')
    pairs = json.load(open(pairs_file)); P, O, T, CAP = [], [], [], []
    for pix, scene, _ in pairs:
        pp = os.path.join(root, 'PixelDoubles', pix)
        if not os.path.isfile(pp): continue
        P.append(stats(load(pp)))
        meta = json.load(open(os.path.join(root, scene, 'meta.json'))) if os.path.isfile(os.path.join(root, scene, 'meta.json')) else {}
        T.append(set(meta.get('tags', [])))
        CAP.append(meta.get('lookSaturation', a.capture_saturation))   # recorded by the app since 0.5.1; older scenes need --capture-saturation
        prev = os.path.join(root, scene, 'preview.jpg'); stamp = scene[6:21]
        O.append(stats(load(prev)) if os.path.isfile(prev) and stamp >= a.since else None)
    n = len(P)
    if n < 5: sys.exit(f'Only {n} usable Pixel pairs.')
    print(f'{n} Pixel photos from {len(pairs)} pairs; {sum(o is not None for o in O)} with a Holtzman preview in scope.\n')
    if n < a.min_pairs: print(f'WARNING: fewer than {a.min_pairs} pairs: these numbers are not stable yet.\n')

    # 1. tone targets
    PP = np.array([p['p'] for p in P]); med = np.median(PP, 0); cur = current_targets()
    print('1. TONE TARGETS (Pixel median brightness of 255, and a 95% interval of that median)')
    print('   rank     now   Pixel   interval        verdict')
    change = False
    for k, r in enumerate(RANKS):
        lo, hi = boot_ci(PP[:, k])
        diff = med[k] - cur[k]; real = cur[k] < lo - 1 or cur[k] > hi + 1
        change |= real
        print(f'   {r:>3}%  {cur[k]:6.1f}  {med[k]:6.1f}  [{lo:5.1f},{hi:5.1f}]   ' + (f'CHANGE ({diff:+.1f})' if real else 'keep (inside the noise)'))
    print('   spread between scenes (std of each percentile): ' + ', '.join(f'{x:.0f}' for x in PP.std(0)))
    line = 'floatArrayOf(0f, ' + ', '.join(f'{x:.1f}f' for x in med) + ', 255f)'
    print('\n   IxAutoTone.TARGETS =', line, '' if change else '   (no real change from the current values)')

    # 2. by scene type
    tags = {}
    for t, p in zip(T, PP):
        for x in t: tags.setdefault(x, []).append(p)
    groups = {k: np.array(v) for k, v in tags.items() if len(v) >= 5}
    if groups:
        print('\n2. BY SCENE TYPE (median Pixel brightness at each rank; tags from the tagging tool)')
        print('   tag                 n   ' + '  '.join(f'{r:>5}%' for r in RANKS))
        for k, v in sorted(groups.items(), key=lambda kv: -len(kv[1])):
            print(f'   {k:<18} {len(v):3d}   ' + '  '.join(f'{x:6.1f}' for x in np.median(v, 0)))
        print('   If a row differs from the all-scene median by much more than the spread above, one fixed curve is not enough for it.')
    else: print('\n2. BY SCENE TYPE: tag at least 5 scenes with the same tag (tools/dataset/tag.py) to see how the Pixel treats different scenes.')

    # 3 and 4. saturation and colour balance
    both = [(p, o, c) for p, o, c in zip(P, O, CAP) if o is not None and c is not None]
    nocap = sum(1 for o, c in zip(O, CAP) if o is not None and c is None)
    cur_sat = current_saturation()
    if nocap: print(f'\n(note: {nocap} scenes have a preview but the saturation it was rendered with is not recorded: pass --capture-saturation S (the value the app had then) to include them)')
    if len(both) >= 5:
        r = np.array([[p['sat'][i] / o['sat'][i] for i in range(len(BANDS))] for p, o, c in both])
        per_scene = np.array([c * np.nanmedian(row) for (p, o, c), row in zip(both, r)])   # the saturation that would have matched the Pixel in each scene
        rec = float(np.nanmedian(per_scene)); lo, hi = boot_ci(per_scene[~np.isnan(per_scene)])
        print(f'\n3. SATURATION (Pixel / ours, median over {len(both)} scenes; bands of brightness {BANDS})')
        print('   Pixel / ours by band: ' + ', '.join(f'{np.nanmedian(r[:, i]):.2f}' for i in range(len(BANDS))))
        print(f'   the saturation that matches the Pixel: {rec:.2f}  (95% interval [{lo:.2f},{hi:.2f}]); the app has {cur_sat}' + ('  -> keep (inside the interval)' if lo <= cur_sat <= hi else f'  -> CHANGE to {rec:.2f}'))
        rg = np.nanmedian([o['rg'] / p['rg'] for p, o, c in both]); bg = np.nanmedian([o['bg'] / p['bg'] for p, o, c in both])
        print(f'\n4. COLOUR BALANCE (ours / Pixel): red/green {rg:.3f}, blue/green {bg:.3f}  (1.000 is identical; the spread between scenes is as large as an offset of 0.03, so this is for information and is not applied)')
        if not (lo <= cur_sat <= hi): print(f'\n   BurstRender.LOOK: saturation = {rec:.2f}f')
    else:
        print('\n3-4. SATURATION and COLOUR BALANCE: need at least 5 scenes with both a Pixel photo and a Holtzman preview whose saturation is known' + (f' (captured since {a.since})' if a.since else '') + '.')

    out = {'pairs': n, 'targets_pixel_median': med.tolist(), 'targets_now': cur, 'since': a.since}
    json.dump(out, open(os.path.join(root, 'look_fit.json'), 'w'), indent=1); print('\nSaved', os.path.join(root, 'look_fit.json'))


if __name__ == '__main__': main()
