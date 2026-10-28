"""Trains the raw denoiser: python3 train.py [steps]. Writes model.keras and a log. CPU is enough: the network is small."""
import sys, time, json
import numpy as np
import tensorflow as tf
from data import load_scenes, sample, P

STEPS = int(sys.argv[1]) if len(sys.argv) > 1 else 4000
BATCH = 24

def build(width=32, depth=6):
    inp = tf.keras.Input((None, None, 9))
    x = inp
    for i in range(depth):
        x = tf.keras.layers.Conv2D(width, 3, padding='same', activation='relu', name=f'conv{i}')(x)
    res = tf.keras.layers.Conv2D(4, 3, padding='same', name='residual')(x)   # the correction to add to the planes, in units of the local noise
    return tf.keras.Model(inp, res)

if __name__ == '__main__':
    scenes = load_scenes(); tr = scenes['train']; va = scenes['val']
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
    model = build(); print('parameters', model.count_params())
    sched = tf.keras.optimizers.schedules.CosineDecay(2e-3, STEPS, alpha=0.02)
    opt = tf.keras.optimizers.Adam(sched)
    @tf.function
    def step(x, y, w):
        with tf.GradientTape() as t:
            out = model(x, training=True)
            loss = tf.reduce_sum(w * tf.square(out - y)) / tf.maximum(tf.reduce_sum(w), 1.0)
        g = t.gradient(loss, model.trainable_variables); opt.apply_gradients(zip(g, model.trainable_variables)); return loss
    t0 = time.time(); avg = None
    for i in range(1, STEPS + 1):
        x, y, w = batch(BATCH)
        l = float(step(x, y, w)); avg = l if avg is None else 0.98 * avg + 0.02 * l
        if i % 100 == 0: print(f'step {i}/{STEPS} loss {avg:.4f}  {time.time() - t0:.0f}s', flush=True)
        if i % 1000 == 0: model.save('model.keras')
    model.save('model.keras'); print('saved model.keras')
