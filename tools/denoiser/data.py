"""Training data for the learned raw denoiser, from the camera's dataset mode (see the README).

A sample is a patch of a burst's frames, split into two independent sets: A (1 to 4 frames, averaged) is the input and B (the others, averaged) the target.
The noise of A and B is independent, so a network trained to turn A into B learns the clean picture ("noise2noise") with no clean ground truth. Handheld
bursts use the frames aligned to their reference (ix:alignDump); the ISO ladder (pairs at six ISOs) and the 40-frame clean reference are static.
"""
import json, os, glob
import numpy as np

ROOT = os.environ.get('HOLTZMAN_DS', '/tmp/ds')
P = 64                      # patch size in cells (a cell = a 2x2 group of samples): 128 x 128 sensor pixels
POS = ((0, 0), (0, 1), (1, 0), (1, 1))   # plane k holds mosaic samples at (row, column) offsets POS[k]; the Pixel 10a's pattern is GRBG

def noise_model(iso):
    """Shot gain and read variance (counts) of one frame: the model measured on the phone (docs/ix-pipeline.md)."""
    return 0.0017 * iso, 1.0 + 5.2e-6 * iso * iso

class Scene:
    def __init__(self, name, files, black, white, isos, static, groups=None):
        self.name, self.black, self.white, self.static = name, np.array(black, dtype=np.float32), float(white), static
        self.maps = [np.memmap(f, dtype='<u2', mode='r', shape=(3000, 4000)) for f in files]
        self.isos = isos            # one ISO per frame
        self.groups = groups        # for the ladder: lists of frame indices that share an ISO; otherwise None (all frames are one group)

    def planes(self, k, cy, cx, size=None):
        """Frame k's patch at cell (cy, cx) as (4, size, size) float32 counts above black (size defaults to P)."""
        n = size or P
        m = self.maps[k]
        out = np.empty((4, n, n), np.float32)
        for i, (y0, x0) in enumerate(POS):
            out[i] = m[2 * cy + y0: 2 * (cy + n) + y0: 2, 2 * cx + x0: 2 * (cx + n) + x0: 2].astype(np.float32) - self.black[i]
        return out

def load_scenes():
    scenes = {'train': [], 'val': []}
    VAL = {'20261028_144533', '20261028_142038', '20261028_124424'}
    for d in sorted(glob.glob(ROOT + '/scene_*_handheld')):
        if not os.path.exists(d + '/aligned_0.raw'): continue
        p = json.load(open(d + '/params.json')); stamp = os.path.basename(d)[6:21]
        s = Scene(stamp, [f'{d}/aligned_{i}.raw' for i in range(p['frames'])], p['black'], p['white'], [p['iso']] * p['frames'], False)
        scenes['val' if stamp in VAL else 'train'].append(s)
    for d in sorted(glob.glob(ROOT + '/scene_*_ladder')):
        m = json.load(open(d + '/meta.json')); fr = m['frames']; p = json.load(open(d + '/params.json'))
        s = Scene('ladder', [f'{d}/frame_{i}.raw' for i in range(len(fr))], p['black'], p['white'], [f['iso'] for f in fr], True, groups=[[i, i + 1] for i in range(0, len(fr), 2)])
        scenes['train'].append(s)
    for d in sorted(glob.glob(ROOT + '/scene_*_clean')):
        p = json.load(open(d + '/params.json'))
        scenes['train'].append(Scene('clean', [f'{d}/frame_{i}.raw' for i in range(p['frames'])], p['black'], p['white'], [p['iso']] * p['frames'], True))
    return scenes

MARGIN = 2   # cells of context around a patch for the 5x5 local mean

def box5(a):
    """5x5 mean of each plane of (4, n, n), valid region only: returns (4, n-4, n-4)."""
    c = np.cumsum(np.cumsum(np.pad(a, ((0, 0), (1, 0), (1, 0))), axis=1), axis=2)
    return (c[:, 5:, 5:] - c[:, :-5, 5:] - c[:, 5:, :-5] + c[:, :-5, :-5]) / 25.0

def features(A, sigma):
    """What the network sees, from the input planes A (4, n, n, counts normalised by the range) and the noise level sigma (n, n): the planes' fine detail in
    units of the local noise (the high-pass over the 5x5 mean, divided by sigma), the planes themselves, and the noise level: 9 channels at the centre
    (n - 4) x (n - 4). The phone builds the same features (TpuDenoiser)."""
    n = A.shape[1]
    hp = (A[:, 2:-2, 2:-2] - box5(A)) / (sigma[None, 2:-2, 2:-2] + 1e-6)
    return np.concatenate([np.clip(hp, -8, 8), A[:, 2:-2, 2:-2], 100.0 * sigma[None, 2:-2, 2:-2]], axis=0).astype(np.float32)

def sample(scene, rng, nA=None, split=None):
    """One training sample: (features (9,P,P), target residual (4,P,P) in noise units, weight (4,P,P), B's noise variance, |A|, |B|, ISO).
    The target is (B - A) / sigma: what has to be added to A, in units of A's noise, to reach B (whose noise is independent: the best guess is the clean picture)."""
    nf = len(scene.maps)
    if scene.groups is not None:       # the ladder: a pair at one ISO
        g = scene.groups[rng.integers(len(scene.groups))]; A_idx, B_idx = [g[0]], [g[1]]
        if rng.random() < 0.5: A_idx, B_idx = B_idx, A_idx
    else:
        idx = rng.permutation(nf) if split is None else np.array(split)
        n = nA if nA is not None else int(rng.integers(1, 5))
        # the target is the rest of the burst, but at most 8 frames (the 40-frame reference would otherwise be slow to average)
        A_idx, B_idx = list(idx[:n]), list(idx[n:n + min(8, max(2, len(idx) - n))])
    cy = int(rng.integers(0, 1500 - P - 2 * MARGIN)); cx = int(rng.integers(0, 2000 - P - 2 * MARGIN))
    big = P + 2 * MARGIN
    A_big = np.mean([scene.planes(k, cy, cx, big) for k in A_idx], axis=0)
    B = np.mean([scene.planes(k, cy, cx, big) for k in B_idx], axis=0)[:, MARGIN:-MARGIN, MARGIN:-MARGIN]
    iso = scene.isos[A_idx[0]]; shot, read2 = noise_model(iso)
    rng_c = scene.white - float(scene.black.mean())          # counts from black to white
    def var(counts, n): return (shot * np.maximum(counts, 0) + read2) / n
    A = A_big[:, MARGIN:-MARGIN, MARGIN:-MARGIN]
    varA = var(A, len(A_idx)); varB = var(B, len(B_idx))
    sigA_planes = np.sqrt(varA) / rng_c
    sigma_big = np.sqrt(var(A_big, len(A_idx))).mean(axis=0) / rng_c       # one noise level per cell, over the four planes
    feats = features(A_big / rng_c, sigma_big)
    sig = sigma_big[MARGIN:-MARGIN, MARGIN:-MARGIN]
    target = ((B - A) / rng_c) / (sig[None] + 1e-6)
    mask = (np.abs(A - B) < 5 * np.sqrt(varA + varB)).astype(np.float32)       # drop places where the two sets disagree (motion, misalignment)
    extras = {'A': (A / rng_c).astype(np.float32), 'sig': sig.astype(np.float32), 'range': rng_c, 'shot': shot, 'read2': read2}
    return feats, target.astype(np.float32), mask, float((varB / rng_c ** 2).mean()), len(A_idx), len(B_idx), iso, extras
