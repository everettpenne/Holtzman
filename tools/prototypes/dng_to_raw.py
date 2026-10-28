"""Converts the dataset mode's DNG frames into the plain files the merge harness reads.

    python3 dng_to_raw.py /path/to/scene_folder ...

For each folder: frame_N.raw (tight little-endian 16-bit mosaic, 4000x3000) and params.json (black levels, white level, ISO, exposure, shake rate and the
camera's noise profile, all from the folder's meta.json). Then: ./gradlew :ix:harness -Pdata=/path/to/the/folder/holding/the/scenes
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from dngparse import parse
for d in sys.argv[1:]:
    m = json.load(open(d + '/meta.json')); fr = m['frames']; ref = fr[0]
    for i, f in enumerate(fr):
        a = parse(f"{d}/{f['file']}"); a.astype('<u2').tofile(f"{d}/frame_{i}.raw")
    json.dump({'width': ref['width'], 'height': ref['height'], 'frames': len(fr), 'black': ref['black'], 'white': ref['white'], 'iso': ref['iso'],
               'exposureNs': ref['exposureNs'], 'shake': m.get('shakeRateAtStart'), 'noiseProfile': ref.get('noiseProfile')}, open(d + '/params.json', 'w'))
    print(os.path.basename(d), 'converted')
