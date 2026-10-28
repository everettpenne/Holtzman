"""Builds a small convolutional network (the size and shape of a RAW denoiser's trunk) as a .tflite file, to measure which processor on the Pixel's
Tensor chip runs it and how fast. The weights are random: this measures speed and support, not quality.

    ml-venv/bin/python tools/make_spike_model.py app/src/main/assets/spike_conv.tflite [app/src/main/assets/spike_conv_int8.tflite]

The second file is the same network fully integer-quantized (int8 weights and activations, float at the edges), which is the form the Tensor NPU
is meant to take. Calibrated on random inputs, so again speed and support, not accuracy.
"""
import sys
import numpy as np
import tensorflow as tf

H = W = 512
inp = tf.keras.Input(shape=(H, W, 4), batch_size=1, name="bayer_planes")   # the four Bayer colour planes of a 1024x1024 crop
x = inp
for i in range(7):
    x = tf.keras.layers.Conv2D(32, 3, padding="same", activation="relu", name=f"conv{i}")(x)
out = tf.keras.layers.Conv2D(4, 3, padding="same", name="out")(x)
model = tf.keras.Model(inp, out)
conv = tf.lite.TFLiteConverter.from_keras_model(model)
conv.target_spec.supported_types = [tf.float16]                       # half-precision weights: what the accelerators run best
open(sys.argv[1], "wb").write(conv.convert())
print("wrote", sys.argv[1], "params", model.count_params())

if len(sys.argv) > 2:
    def sample():
        rng = np.random.default_rng(0)
        for _ in range(8):
            yield [rng.random((1, H, W, 4), dtype=np.float32)]
    q = tf.lite.TFLiteConverter.from_keras_model(model)
    q.optimizations = [tf.lite.Optimize.DEFAULT]
    q.representative_dataset = sample
    q.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    open(sys.argv[2], "wb").write(q.convert())
    print("wrote", sys.argv[2])
