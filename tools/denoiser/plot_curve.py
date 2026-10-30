#!/usr/bin/env python3
"""Plot the denoiser's learning curve from the logs learning_curve.sh wrote.

    python3 tools/denoiser/plot_curve.py [results_folder=/tmp/lc] [out.png]

Reads eval_<fraction>.log and train_<fraction>.log for each fraction. For each model it takes the held-out scenes' "noise reduction vs the input" (how many times less
noise than the input the model's result has, estimated against the unseen frames) as a geometric mean over the scenes, with a 95% interval from resampling the scenes,
and draws the classical (non-learned) denoise as a line to beat. A few entries of a very clean scene hit the estimator's floor and read absurd values (millions of
times); anything over 60x is left out of every curve, the same entries for every model.
"""
import glob, os, re, sys
import numpy as np
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

d = sys.argv[1] if len(sys.argv) > 1 else '/tmp/lc'; out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(d, 'learning_curve.png')
pat = re.compile(r'handheld (\S+) ISO (\d+) A=(\d) frames noise reduction vs the input: classical\s+([\d.]+)x\s+network\s+([\d.]+)x')
runs = {}
for f in sorted(glob.glob(os.path.join(d, 'eval_*.log'))):
    frac = float(re.search(r'eval_([\d.]+)\.log', f)[1])
    rows = [(m[1], int(m[2]), int(m[3]), float(m[4]), float(m[5])) for m in map(pat.search, open(f)) if m]
    n = None
    t = os.path.join(d, f'train_{frac}.log')
    if os.path.isfile(t):
        m = re.search(r'handheld training scenes (\d+)', open(t).read()); n = int(m[1]) if m else None
    if rows and n: runs[frac] = (n, rows)
if not runs: sys.exit('no finished runs in ' + d)
fracs = sorted(runs)
keys = sorted({(r[0], r[2]) for _, rows in runs.values() for r in rows})
def table(frac):   # {(scene, A): (classical, network, iso)}
    return {(r[0], r[2]): (r[3], r[4], r[1]) for r in runs[frac][1]}
tabs = {f: table(f) for f in fracs}
ok = [k for k in keys if all(k in tabs[f] and tabs[f][k][0] < 60 and tabs[f][k][1] < 60 for f in fracs)]
print(f'{len(keys)} scene/frame-count entries, {len(ok)} used (the rest are at the estimator floor)')
iso = {k: tabs[fracs[0]][k][2] for k in ok}
rng = np.random.default_rng(0)
def gm(vals): return float(np.exp(np.mean(np.log(vals))))
def curve(sel, which):
    pts = []
    for f in fracs:
        v = np.array([tabs[f][k][which] for k in sel])
        boots = [gm(rng.choice(v, len(v))) for _ in range(1000)]
        pts.append((gm(v), *np.percentile(boots, [2.5, 97.5])))
    return np.array(pts)
groups = [('all held-out scenes', ok), ('ISO of 100 or less', [k for k in ok if iso[k] <= 100]), ('ISO above 100', [k for k in ok if iso[k] > 100])]
fig, ax = plt.subplots(1, 3, figsize=(15, 4.4), sharey=False)
xs = [runs[f][0] for f in fracs]
for a, (name, sel) in zip(ax, groups):
    if len(sel) < 2: a.set_title(f'{name}: too few'); continue
    net = curve(sel, 1); cls = gm(np.array([tabs[fracs[-1]][k][0] for k in sel]))
    a.plot(xs, net[:, 0], 'o-', color='#c98a00', label='learned denoiser'); a.fill_between(xs, net[:, 1], net[:, 2], color='#c98a00', alpha=.2)
    a.axhline(cls, color='grey', ls='--', label='classical denoise'); a.set_xscale('log'); a.set_xticks(xs); a.set_xticklabels([str(x) for x in xs])
    a.set_title(f'{name} ({len({k[0] for k in sel})} scenes)'); a.set_xlabel('handheld scenes trained on'); a.set_ylabel('noise reduction vs the input (x, higher is better)'); a.legend(); a.grid(alpha=.3)
plt.tight_layout(); plt.savefig(out, dpi=130)
print('saved', out)
print('\nscenes trained on  ' + '   '.join(f'{x:>5}' for x in xs))
for name, sel in groups:
    if len(sel) >= 2:
        net = curve(sel, 1)
        print(f'{name:<22}' + '   '.join(f'{p[0]:5.2f}' for p in net) + f'   (classical {gm(np.array([tabs[fracs[-1]][k][0] for k in sel])):.2f})')
if len(fracs) >= 2:
    net = curve(ok, 1)
    last, prev = net[-1], net[-2]
    gain_last = (last[0] / prev[0] - 1) * 100
    gain_first_to_last = (last[0] / net[0][0] - 1) * 100
    print(f'\nfrom {xs[0]} to {xs[-1]} scenes: {gain_first_to_last:+.1f}%;  the last step ({xs[-2]} to {xs[-1]}): {gain_last:+.1f}%  (95% interval of the last point: {last[1]:.2f} to {last[2]:.2f})')
