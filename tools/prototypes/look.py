"""Renders a RAW mosaic from the merge harness (merged_N.raw, frame_N.raw) to a picture on the host, for looking at merge artefacts:
    python3 look.py <scene_folder> <name> [x0 y0 x1 y1]     e.g. merged_1 or frame_0
Plain pipeline (directional demosaic, the frame's own white balance and colour matrix, auto exposure, sRGB): not the app's finish, but enough to see blocks."""
import json, os, sys
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
from dm import directional, lin2srgb
d, name = sys.argv[1], sys.argv[2]
m = json.load(open(d + '/meta.json')); fr = m['frames'][0]; p = json.load(open(d + '/params.json'))
w, h = p['width'], p['height']
raw = np.fromfile(f'{d}/{name}.raw', dtype='<u2').reshape(h, w).astype(np.float64)
black = np.array(p['black']); white = p['white']
mos = raw.copy()
for i, (y, x) in enumerate(((0, 0), (0, 1), (1, 0), (1, 1))): mos[y::2, x::2] = (raw[y::2, x::2] - black[i]) / (white - black[i])
mos = np.clip(mos, 0, None)
# the Pixel 10a's sensor is GRBG (the DNG says so: CFAPattern 1 0 2 1); shifting one column over makes it the RGGB the demosaic expects
mos = mos[:, 1:-1]
if len(sys.argv) > 6: x0, y0, x1, y1 = map(int, sys.argv[3:7]); x0 -= x0 % 2; y0 -= y0 % 2; mos = mos[y0:y1, x0:x1]
rgb = np.clip(directional(mos, eps=0.02), 0, None)
g = fr['wbGains']; wb = np.array([g[0], (g[1] + g[2]) / 2, g[3]]); rgb = rgb * wb
ccm = np.array(fr['ccm']).reshape(3, 3); rgb = rgb @ ccm.T
lum = rgb[..., 1]
rgb = rgb * (0.9 / max(np.percentile(lum, 99.5), 1e-4))   # the brightest 0.5% just below white
out = lin2srgb(np.clip(rgb, 0, 1)).astype(np.uint8)
dst = f'{d}/look_{name}.png'; Image.fromarray(out).save(dst); print(dst)
