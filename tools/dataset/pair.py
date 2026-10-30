#!/usr/bin/env python3
"""Match the Pixel Camera app's photos to dataset scenes, and write pixel_pairs.json (which the tagging tool and the look fit read).

    python3 tools/dataset/pair.py [dataset_folder] [--max-gap 120] [--redo] [--dry-run]

Two kinds of Pixel photo are in PixelDoubles:
  * scene_<time>_<kind>.jpg, made by "Pixel photo first": named after their scene, so the pairing is exact;
  * PXL_<date>_<time>.jpg, taken by hand with the Pixel app: matched to the scene nearest in time (the PXL name is in UTC, the scene name in the phone's local
    time), if within --max-gap seconds. If two Pixel photos want the same scene, the closer one wins and the other is listed for you to look at, because it is
    probably a photo of something else.
Entries already in pixel_pairs.json are kept (so a pairing you corrected by hand stays), and only new photos are assigned; --redo starts over.
"""
import argparse, datetime as dt, json, os, re, sys


def parse_scene(name):
    m = re.match(r'scene_(\d{8})_(\d{6})_', name)
    return dt.datetime.strptime(m[1] + m[2], '%Y%m%d%H%M%S') if m else None


def parse_pxl(name):
    m = re.match(r'PXL_(\d{8})_(\d{6})\d*', name)
    if not m: return None
    utc = dt.datetime.strptime(m[1] + m[2], '%Y%m%d%H%M%S').replace(tzinfo=dt.timezone.utc)
    return utc.astimezone().replace(tzinfo=None)   # to this computer's local time, which is the phone's


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('dataset', nargs='?', default='~/Code/holtzman-dataset')
    ap.add_argument('--max-gap', type=float, default=120.0, help='most seconds between a Pixel photo and its scene')
    ap.add_argument('--redo', action='store_true'); ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    root = os.path.abspath(os.path.expanduser(a.dataset)); pdir = os.path.join(root, 'PixelDoubles')
    if not os.path.isdir(pdir): sys.exit(f'No PixelDoubles folder in {root}')
    scenes = sorted(d for d in os.listdir(root) if d.startswith('scene_') and os.path.isfile(os.path.join(root, d, 'meta.json')))
    out = os.path.join(root, 'pixel_pairs.json')
    pairs = [] if a.redo or not os.path.isfile(out) else json.load(open(out))
    known = {p[0] for p in pairs}
    pairs = [p for p in pairs if os.path.isfile(os.path.join(pdir, p[0])) and p[1] in scenes]    # drop entries whose files are gone
    known = {p[0] for p in pairs}
    new, review, unmatched = [], [], []
    for f in sorted(os.listdir(pdir)):
        if not f.lower().endswith('.jpg') or f in known: continue
        if f.startswith('scene_'):
            if f[:-4] in scenes: new.append([f, f[:-4], 0.0])
            else: unmatched.append((f, 'its scene is not in this folder'))
            continue
        t = parse_pxl(f)
        if t is None: unmatched.append((f, 'cannot read its time from the name')); continue
        best = min(((abs((t - parse_scene(s)).total_seconds()), s) for s in scenes if parse_scene(s)), default=None)
        if best is None or best[0] > a.max_gap: unmatched.append((f, f'no scene within {a.max_gap:.0f} s' + (f' (nearest {best[0]:.0f} s)' if best else ''))); continue
        new.append([f, best[1], round(best[0], 1)])
    # two photos for one scene: the closer wins
    taken = {p[1]: p for p in pairs}
    for p in sorted(new, key=lambda p: p[2]):
        if p[1] in taken: review.append((p[0], p[1], taken[p[1]][0], p[2]))
        else: taken[p[1]] = p; pairs.append(p)
    print(f'{len(scenes)} scenes, {len(os.listdir(pdir))} files in PixelDoubles. Paired now: {len(pairs)} ({len([p for p in new if [p[0], p[1]] in [[q[0], q[1]] for q in pairs]])} new).')
    for f, s, other, gap in review: print(f'  REVIEW  {f} also wants {s} (gap {gap:.0f} s) but {other} is closer: probably a photo of something else; not paired')
    for f, why in unmatched: print(f'  UNMATCHED  {f}: {why}')
    free = [s for s in scenes if s not in taken]
    print(f'{len(free)} scenes have no Pixel photo.')
    if a.dry_run: return
    pairs.sort(key=lambda p: p[0]); json.dump(pairs, open(out, 'w'))
    print('Wrote', out)


if __name__ == '__main__': main()
