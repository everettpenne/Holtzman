#!/usr/bin/env python3
"""Write the sweep's report: every candidate against the baseline (config A), scene by scene, with a 95% interval. python3 sweep_report.py [results_dir]"""
import glob, os, re, sys
import numpy as np
OUT = os.path.abspath(os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/Code/denoiser-sweeps/20261029'))
pat = re.compile(r'handheld (\S+) ISO (\d+) A=(\d) frames noise reduction vs the input: classical\s+([\d.]+)x\s+network\s+([\d.]+)x')
def scores(path): return {(m[1], m[3]): (int(m[2]), float(m[4]), float(m[5])) for m in map(pat.search, open(path)) if m}
def info(name):
    t = open(f'{OUT}/train_{name}.log').read() if os.path.exists(f'{OUT}/train_{name}.log') else ''
    m = re.search(r'arch (\S+) parameters (\d+) MACs per tile ([\d.]+) G loss (\S+)', t); last = re.findall(r'step (\d+)/\d+ loss ([\d.]+)  (\d+)s', t)
    return (m[1], int(m[2]), float(m[3]), m[4]) + ((int(last[-1][0]), float(last[-1][1]), int(last[-1][2]) / 60) if last else (0, 0, 0))
names = sorted(os.path.basename(f)[5:-4] for f in glob.glob(f'{OUT}/eval_*.log'))
done = {n: scores(f'{OUT}/eval_{n}.log') for n in names}; done = {n: s for n, s in done.items() if s}
if not done: sys.exit('nothing scored yet')
base = next((n for n in done if n.startswith('A_')), None)
rng = np.random.default_rng(0); lines = ['# Denoiser sweep report', '', f'Scored on the held-out scenes (heldout.txt). "noise reduction" = how many times less noise than the input (geometric mean over scene/frame-count entries; entries at the estimator floor, over 60x, are left out of every column). "vs baseline" = the geometric mean of this model\'s noise reduction divided by the baseline\'s, with a 95% interval from resampling scenes; the cost column is the multiply-adds per tile relative to the shipped network (3.27 G), a proxy for TPU time.', '']
lines += ['| config | architecture | loss | steps (min) | params | cost | final train loss | noise reduction | vs baseline | verdict |', '|---|---|---|---|---|---|---|---|---|---|']
for n in done:
    arch, params, macs, loss, st, tl, mins = info(n)
    keys = [k for k in done[n] if all(k in done[m] and done[m][k][2] < 60 for m in done)]
    nr = np.exp(np.mean([np.log(done[n][k][2]) for k in keys]))
    if base and n != base:
        r = np.array([np.log(done[n][k][2] / done[base][k][2]) for k in keys]); b = [np.mean(r[rng.integers(0, len(r), len(r))]) for _ in range(2000)]
        g, lo, hi = np.exp(r.mean()), np.exp(np.percentile(b, 2.5)), np.exp(np.percentile(b, 97.5))
        verdict = 'BETTER' if lo > 1.0 else 'worse' if hi < 1.0 else 'no clear difference'
        vs = f'{g:.3f} [{lo:.3f}, {hi:.3f}]'
    else: vs, verdict = '(baseline)', ''
    lines.append(f'| {n} | {arch} | {loss} | {st} ({mins:.0f}) | {params:,} | {macs / 3.27:.1f}x | {tl:.3f} | {nr:.3f} | {vs} | {verdict} |')
lines += ['', f'{len(keys)} scene/frame-count entries used. The training loss is not comparable between losses (mse and charb). A BETTER verdict means the held-out score is better beyond the scene-to-scene noise; it does not by itself mean the pictures look better: look at crops (sweep_visual.py) and measure the TPU time before shipping.']
open(f'{OUT}/sweep_report.md', 'w').write('\n'.join(lines) + '\n'); print('\n'.join(lines))
