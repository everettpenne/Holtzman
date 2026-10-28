"""Compares the trained denoiser with the classical spatial denoise (IxDenoise's method) and with doing nothing, on scenes the network never saw:
    python3 evaluate.py [model.keras]
Method: split a burst into independent sets A (input) and B (target, a different set of frames). The error of a result X against B equals its error against
the clean picture plus B's own noise variance, so (error vs B) minus (B's noise) estimates the error against the clean picture; the ratio of that between
two methods is the ratio of their true errors. Reported as a noise reduction factor: how many times lower the error is than the noisy input's."""
import sys, os
import numpy as np
import tensorflow as tf
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'prototypes'))
from data import load_scenes, sample
from classical import raw_denoise   # the classical method, in numpy

path = sys.argv[1] if len(sys.argv) > 1 else 'model.keras'
model = tf.keras.models.load_model(path, compile=False)
scenes = load_scenes(); rng = np.random.default_rng(123)

def planes_to_mosaic(pl):
    H, W = pl.shape[1] * 2, pl.shape[2] * 2; m = np.zeros((H, W), np.float32)
    for i, (y0, x0) in enumerate(((0, 0), (0, 1), (1, 0), (1, 1))): m[y0::2, x0::2] = pl[i]
    return m
def mosaic_to_planes(m): return np.stack([m[y0::2, x0::2] for (y0, x0) in ((0, 0), (0, 1), (1, 0), (1, 1))])

def run(scene, n, nA, label=''):
    se = {'input': 0.0, 'classical': 0.0, 'network': 0.0}; sw = 0.0; sb = 0.0
    for _ in range(n):
        f, r, m, sigB2, nAi, nBi, iso, ex = sample(scene, rng, nA=nA)
        net = np.moveaxis(model.predict(np.moveaxis(f, 0, -1)[None], verbose=0)[0], -1, 0)         # the network's correction, in noise units
        cm = raw_denoise(planes_to_mosaic(ex['A'] * ex['range']), ex['shot'], ex['read2'], float(nAi), 1.6) / ex['range']
        cl = (mosaic_to_planes(cm) - ex['A']) / (ex['sig'][None] + 1e-6)                           # the classical correction, in noise units
        for k, v in (('input', 0 * r), ('classical', cl), ('network', net)): se[k] += float((m * (v - r) ** 2).sum())
        sw += float(m.sum()); sb += (sigB2 / float(np.mean(ex['sig'] ** 2))) * float(m.sum())   # B's noise variance, in units of the input's noise variance
    e = {k: v / sw for k, v in se.items()}
    b2 = (sb / sw)                                          # B's own noise variance, in units of A's noise variance
    c = {k: max(v - b2, 1e-9) for k, v in e.items()}
    print(f'{label:34s} noise reduction vs the input: classical {c["input"] / c["classical"]:5.2f}x   network {c["input"] / c["network"]:5.2f}x   (network error is {c["classical"] / c["network"]:4.2f}x lower than classical)')

for s in scenes['val']:
    for nA in (3, 4):
        run(s, 24, nA, label=f'handheld {s.name} ISO {s.isos[0]} A={nA} frames')
lad = [s for s in scenes['train'] if s.name == 'ladder'][0]
all_groups = lad.groups
for g in all_groups:
    lad.groups = [g]; run(lad, 12, None, label=f'ladder ISO {lad.isos[g[0]]} (seen in training)')
lad.groups = all_groups
