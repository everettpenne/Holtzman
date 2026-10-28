"""The classical spatial denoise of the merged mosaic (the method IxDenoise implements in Kotlin and the GPU merge runs), in numpy, as the baseline to beat."""
import numpy as np

def cellsum(m): return m[0::2, 0::2] + m[0::2, 1::2] + m[1::2, 0::2] + m[1::2, 1::2]

def raw_denoise(m, shot, read2, neff, h=1.6, R=2):
    """m in counts above black. Bilateral on the cell grid guided by each cell's summed brightness; same-position samples only (no colour mixing)."""
    H, W = m.shape; ch, cw = H // 2, W // 2
    g = cellsum(m)
    var = (shot * np.maximum(g, 0) + 4 * read2) / neff
    den = np.zeros((ch, cw)); num = [np.zeros((ch, cw)) for _ in range(4)]
    gp = np.pad(g, R, mode='edge')
    subp = [np.pad(a, R, mode='edge') for a in (m[0::2, 0::2], m[0::2, 1::2], m[1::2, 0::2], m[1::2, 1::2])]
    for dy in range(-R, R + 1):
        for dx in range(-R, R + 1):
            q = gp[R + dy:R + dy + ch, R + dx:R + dx + cw]; d = q - g
            w = np.exp(-(d * d) / (2 * h * h * 2 * var + 1e-9)) * np.exp(-(dx * dx + dy * dy) / (2 * 1.5 ** 2))
            den += w
            for k in range(4): num[k] += w * subp[k][R + dy:R + dy + ch, R + dx:R + dx + cw]
    o = np.zeros_like(m)
    o[0::2, 0::2] = num[0] / den; o[0::2, 1::2] = num[1] / den; o[1::2, 0::2] = num[2] / den; o[1::2, 1::2] = num[3] / den
    return o
