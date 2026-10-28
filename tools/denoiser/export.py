"""Exports the trained denoiser for the phone: python3 export.py [model.keras]

Writes denoiser_f32.tflite (float) and denoiser_int8.tflite (weights and activations in 8 bits, float at the edges) for one tile of TILE x TILE cells
(a cell is a 2x2 group of sensor samples) with the noise-level channel: input (1, TILE, TILE, 9), output (1, TILE, TILE, 4). The phone cuts the
12 MP mosaic (2000 x 1500 cells) into overlapping tiles and keeps each tile's centre. Then checks the int8 model against the float one on real patches.
"""
import sys, os
import numpy as np
import tensorflow as tf
from data import load_scenes, sample

TILE = 256
path = sys.argv[1] if len(sys.argv) > 1 else 'model.keras'
model = tf.keras.models.load_model(path, compile=False)
fixed = tf.keras.Input((TILE, TILE, 9), batch_size=1)
m = tf.keras.Model(fixed, model(fixed))

conv = tf.lite.TFLiteConverter.from_keras_model(m)
open('denoiser_f32.tflite', 'wb').write(conv.convert()); print('wrote denoiser_f32.tflite')

scenes = load_scenes(); rng = np.random.default_rng(5)
pool = [s for s in scenes['train'] if not s.static]
def rep():
    # real patches at the tile size: a TILE patch is four 64-cell patches side by side in each direction; simpler: pad a 64-cell patch by edge replication
    for _ in range(200):
        a = sample(pool[rng.integers(len(pool))], rng)[0]
        x = np.moveaxis(a, 0, -1)
        x = np.pad(x, ((0, TILE - x.shape[0]), (0, TILE - x.shape[1]), (0, 0)), mode='reflect')[None].astype(np.float32)
        yield [x]
q = tf.lite.TFLiteConverter.from_keras_model(m)
q.optimizations = [tf.lite.Optimize.DEFAULT]; q.representative_dataset = rep
q.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
open('denoiser_int8.tflite', 'wb').write(q.convert()); print('wrote denoiser_int8.tflite')

# how much does 8 bits cost? run both on validation patches (the same 64-cell patch padded to the tile) and compare to the target
def run(path, x):
    it = tf.lite.Interpreter(model_path=path); it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]['index'], x); it.invoke(); return it.get_tensor(it.get_output_details()[0]['index'])[0]
errs = {'f32': [], 'int8': []}; vs = []
for s in scenes['val']:
    for _ in range(10):
        a, r, m, sigB2, nA, nB, iso, ex = sample(s, rng, nA=3)
        x = np.pad(np.moveaxis(a, 0, -1), ((0, TILE - 64), (0, TILE - 64), (0, 0)), mode='reflect')[None].astype(np.float32)
        for k, f in (('f32', 'denoiser_f32.tflite'), ('int8', 'denoiser_int8.tflite')):
            out = np.moveaxis(run(f, x)[:64, :64], -1, 0); errs[k].append(float((m * (out - r) ** 2).sum() / m.sum()))
        vs.append(float(np.mean(sigB2 / np.mean(ex['sig'] ** 2))))
eb = float(np.mean(vs)); e32 = np.mean(errs['f32']) - eb; e8 = np.mean(errs['int8']) - eb
print(f'error against the clean picture (estimated, in units of the input noise variance): float {e32:.3f}, int8 {e8:.3f}: int8 is {e8 / e32:.2f}x the float error')
