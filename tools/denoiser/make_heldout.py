#!/usr/bin/env python3
"""Choose (or grow) the held-out scenes: the handheld scenes the denoiser is never trained on, used only to score it.

    python3 tools/denoiser/make_heldout.py [dataset_folder] [--count 15]        # first time: pick them
    python3 tools/denoiser/make_heldout.py [dataset_folder] --add 5             # later: move 5 more new scenes into the held-out set

They are written to <dataset>/heldout.txt (one scene stamp per line) and tools/denoiser/data.py uses that file automatically. Keep the list FIXED between the runs you want
to compare: a model scored on a different set of scenes cannot be compared with another. The first pick spreads the scenes evenly over ISO, because dim, high-ISO scenes are
the ones that matter and the rarest. --add takes scenes you have not trained on yet, again spread over ISO, and appends them (old scenes stay held out), after which the
older models are no longer comparable with new ones: retrain, or score both on the OLD list (HOLTZMAN_VAL=<comma list>).
"""
import argparse, glob, json, os

ap = argparse.ArgumentParser(); ap.add_argument('dataset', nargs='?', default='~/Code/holtzman-dataset')
ap.add_argument('--count', type=int, default=15); ap.add_argument('--add', type=int, default=0)
a = ap.parse_args(); root = os.path.abspath(os.path.expanduser(a.dataset)); path = os.path.join(root, 'heldout.txt')
have = [l.strip() for l in open(path)] if os.path.isfile(path) else []
rows = []
for d in sorted(glob.glob(root + '/scene_*_handheld')):
    if not os.path.exists(d + '/aligned_0.raw'): continue   # not prepared yet
    stamp = os.path.basename(d)[6:21]
    if stamp in have: continue
    rows.append((json.load(open(d + '/params.json'))['iso'], stamp))
rows.sort()
want = a.add if a.add else (0 if have else a.count)
if not want: raise SystemExit(f'{len(have)} held-out scenes already ({path}). Use --add N to add more.')
if len(rows) < want * 2: raise SystemExit(f'Only {len(rows)} prepared scenes not already held out: need at least {want * 2} so there is something left to train on.')
pick = [rows[int((i + 0.5) * len(rows) / want)] for i in range(want)]
open(path, 'w').write('\n'.join(have + [s for _, s in pick]) + '\n')
print(f'{len(have) + len(pick)} held-out scenes in {path}; ISO of the new ones: {[i for i, _ in pick]}')
