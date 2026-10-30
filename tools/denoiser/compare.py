#!/usr/bin/env python3
"""Is a newly trained denoiser better than the one the app ships?

    python3 tools/denoiser/compare.py OLD.keras NEW.keras [samples_per_scene=16]

Scores both models on the held-out scenes (heldout.txt in the dataset folder) with evaluate.py, then compares them scene by scene: for each scene and number of input
frames, the ratio of the new model's noise reduction to the old one's. It prints the geometric mean of that ratio with a 95% interval (resampling the scenes), overall and
for dim scenes (ISO above 100) separately, and a verdict:
  SHIP      the new model is better overall (the interval is above 1.00) and not worse for dim scenes;
  KEEP OLD  the new model is worse, or the same within the noise (the interval includes 1.00): the retrain did not help, so do not ship it.
The model the app ships is trained from tools/denoiser/model.keras (see docs/data-to-release.md for how to get the shipped one back from git if you overwrote it).
Takes about 4 minutes for each model.
"""
import os, re, subprocess, sys
import numpy as np

old, new = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2]); n = sys.argv[3] if len(sys.argv) > 3 else '16'
here = os.path.dirname(os.path.abspath(__file__))
py = os.path.expanduser('~/Code/ml-venv/bin/python3'); py = py if os.path.exists(py) else sys.executable
pat = re.compile(r'handheld (\S+) ISO (\d+) A=(\d) frames noise reduction vs the input: classical\s+([\d.]+)x\s+network\s+([\d.]+)x')
def score(model):
    env = dict(os.environ, HOLTZMAN_EVAL_N=n, HOLTZMAN_NO_LADDER='1')
    print('scoring', model, '…', flush=True)
    out = subprocess.run([py, 'evaluate.py', model], cwd=here, env=env, capture_output=True, text=True).stdout
    return {(m[1], m[3]): (int(m[2]), float(m[4]), float(m[5])) for m in map(pat.search, out.splitlines()) if m}
a, b = score(old), score(new)
keys = [k for k in a if k in b and a[k][2] < 60 and b[k][2] < 60 and a[k][1] < 60]
if len(keys) < 8: sys.exit(f'Only {len(keys)} usable scene/frame-count scores: is heldout.txt present and are the scenes prepared?')
rng = np.random.default_rng(0)
def summary(sel):
    r = np.array([np.log(b[k][2] / a[k][2]) for k in sel]); scenes = sorted({k[0] for k in sel})
    boots = [np.mean(r[rng.integers(0, len(r), len(r))]) for _ in range(2000)]
    return float(np.exp(r.mean())), float(np.exp(np.percentile(boots, 2.5))), float(np.exp(np.percentile(boots, 97.5))), len(scenes)
allk = keys; dim = [k for k in keys if a[k][0] > 100]; bright = [k for k in keys if a[k][0] <= 100]
print(f'\n{"":24s}new / old noise reduction (above 1.00 means the new model is better)')
res = {}
for name, sel in (('all held-out scenes', allk), ('dim (ISO above 100)', dim), ('bright (ISO 100 or less)', bright)):
    if len(sel) >= 4:
        g, lo, hi, ns = summary(sel); res[name] = (g, lo, hi)
        print(f'  {name:<24s} {g:5.3f}  [{lo:5.3f}, {hi:5.3f}]  ({ns} scenes)')
g, lo, hi = res['all held-out scenes']; dg = res.get('dim (ISO above 100)')
if lo > 1.0 and (dg is None or dg[1] > 0.97): print('\nVERDICT: SHIP: the new model is better overall and not worse on dim scenes.')
elif hi < 1.0: print('\nVERDICT: KEEP OLD: the new model is worse.')
else: print('\nVERDICT: KEEP OLD: no clear difference (the interval includes 1.00), so the retrain did not help; do not ship it.' if lo <= 1.0 <= hi else '\nVERDICT: KEEP OLD: better overall but worse on dim scenes.')
