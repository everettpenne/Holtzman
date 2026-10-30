"""Trains the raw denoiser: python3 train.py [steps]. Writes model.keras and a log. CPU is enough: the network is small."""
import sys, time, json, os
import numpy as np
import tensorflow as tf
from data import load_scenes, sample, P

STEPS = int(sys.argv[1]) if len(sys.argv) > 1 else 4000
BATCH = 24
LOSS = os.environ.get('HOLTZMAN_LOSS', 'mse')
PROBE = bool(os.environ.get('HOLTZMAN_PROBE'))   # time 80 steps and print steps per second, then stop (the sweep uses it to size each run)

def build(arch=None, tile=None):
    """arch: "plain:DEPTH:WIDTH" (the shipped network is plain:6:32), or "unet:W0:W1:W2" (two downsamples). Input 9 channels, output the 4-channel residual, so any
    of them can replace the shipped model without touching the app."""
    arch = arch or os.environ.get('HOLTZMAN_ARCH', 'plain:6:32')
    kind, *a = arch.split(':'); a = [int(v) for v in a]
    L = tf.keras.layers
    inp = tf.keras.Input((tile, tile, 9))
    if kind == 'plain':
        depth, width = a; x = inp
        for i in range(depth): x = L.Conv2D(width, 3, padding='same', activation='relu', name=f'conv{i}')(x)
    else:
        w0, w1, w2 = a
        def block(x, w, n): 
            for _ in range(2): x = L.Conv2D(w, 3, padding='same', activation='relu', name=f'{n}_{_}')(x)
            return x
        e1 = block(inp, w0, 'e1'); e2 = block(L.AveragePooling2D(2)(e1), w1, 'e2'); b = block(L.AveragePooling2D(2)(e2), w2, 'b')
        # upsampling by a transposed convolution (nearest-neighbour upsampling exports as RESHAPE and TILE, which the phone's TPU does not take)
        d2 = block(L.Concatenate()([L.Conv2DTranspose(w1, 2, strides=2, padding='same', name='u2')(b), e2]), w1, 'd2')
        x = block(L.Concatenate()([L.Conv2DTranspose(w0, 2, strides=2, padding='same', name='u1')(d2), e1]), w0, 'd1')
    res = L.Conv2D(4, 3, padding='same', name='residual')(x)   # the correction to add to the planes, in units of the local noise
    return tf.keras.Model(inp, res)

def macs_per_tile(arch, tile=256):
    """Multiply-adds of the convolutions for one 256x256-cell tile (the phone's tile): the cost of the network on the TPU, to compare candidates."""
    m = build(arch, tile); total = 0
    for l in m.layers:
        if isinstance(l, tf.keras.layers.Conv2DTranspose):
            h, w = l.input.shape[1:3]; total += h * w * l.kernel_size[0] * l.kernel_size[1] * l.input.shape[-1] * l.filters   # each input cell makes a kernel-sized block
        elif isinstance(l, tf.keras.layers.Conv2D):
            h, w = l.output.shape[1:3]; total += h * w * l.kernel_size[0] * l.kernel_size[1] * l.input.shape[-1] * l.filters
    return total

if __name__ == '__main__':
    scenes = load_scenes(); tr = scenes['train']; va = scenes['val']
    print('handheld training scenes', sum(1 for s in tr if not s.static))
    print('train scenes', len(tr), [s.name for s in tr][:3], '... val', [s.name for s in va])
    rng = np.random.default_rng(0)
    # weight handheld bursts, the ladder and the clean reference so each kind is seen (the ladder and clean are single static scenes)
    pool = [s for s in tr if not s.static]; ladder = [s for s in tr if s.name == 'ladder']; clean = [s for s in tr if s.name == 'clean']
    def pick():
        r = rng.random()
        if r < 0.65 or not (ladder and clean): return pool[rng.integers(len(pool))]
        return ladder[0] if r < 0.8 else clean[0]
    import threading, queue
    def worker(seed, out):
        r = np.random.default_rng(seed)
        def pick_r():
            q = r.random()
            if q < 0.65 or not (ladder and clean): return pool[r.integers(len(pool))]
            return ladder[0] if q < 0.8 else clean[0]
        while True:
            xs, ys, ws = [], [], []
            for _ in range(BATCH):
                s = pick_r()
                if s.name == 'clean': split = list(r.permutation(30)); a, b, w, *_ = sample(s, r, nA=int(r.integers(1, 6)), split=split)   # train on frames 0..29 only
                else: a, b, w, *_ = sample(s, r)
                xs.append(np.moveaxis(a, 0, -1)); ys.append(np.moveaxis(b, 0, -1)); ws.append(np.moveaxis(w, 0, -1))
            out.put((np.stack(xs), np.stack(ys), np.stack(ws)))
    qout = queue.Queue(maxsize=8)
    for k in range(5): threading.Thread(target=worker, args=(100 + k, qout), daemon=True).start()
    def batch(n): return qout.get()
    tf.keras.utils.set_random_seed(0)
    model = build(); print('arch', os.environ.get('HOLTZMAN_ARCH', 'plain:6:32'), 'parameters', model.count_params(), 'MACs per tile %.2f G' % (macs_per_tile(os.environ.get('HOLTZMAN_ARCH', 'plain:6:32')) / 1e9), 'loss', os.environ.get('HOLTZMAN_LOSS', 'mse'))
    sched = tf.keras.optimizers.schedules.CosineDecay(2e-3, STEPS, alpha=0.02)
    opt = tf.keras.optimizers.Adam(sched)
    @tf.function
    def step(x, y, w):
        with tf.GradientTape() as t:
            out = model(x, training=True)
            d = out - y
            e = tf.sqrt(tf.square(d) + 0.01) if LOSS == 'charb' else tf.square(d)   # Charbonnier (a smooth L1, eps 0.1) or squared error
            loss = tf.reduce_sum(w * e) / tf.maximum(tf.reduce_sum(w), 1.0)
        g = t.gradient(loss, model.trainable_variables); opt.apply_gradients(zip(g, model.trainable_variables)); return loss
    t0 = time.time(); avg = None
    for i in range(1, STEPS + 1):
        x, y, w = batch(BATCH)
        l = float(step(x, y, w)); avg = l if avg is None else 0.98 * avg + 0.02 * l
        if i % 100 == 0: print(f'step {i}/{STEPS} loss {avg:.4f}  {time.time() - t0:.0f}s', flush=True)
        if PROBE and i == 80:
            t1 = time.time(); [step(*batch(BATCH)) for _ in range(60)]; print('STEPS_PER_SECOND %.3f' % (60 / (time.time() - t1))); sys.exit(0)
        if i % 1000 == 0: model.save(os.environ.get('HOLTZMAN_MODEL_OUT', 'model.keras'))
    out = os.environ.get('HOLTZMAN_MODEL_OUT', 'model.keras'); model.save(out); print('saved', out)
