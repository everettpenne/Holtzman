#!/usr/bin/env python3
"""Overnight sweep of denoiser candidates: train each configuration, score it on the held-out scenes, and keep a running report.

    caffeinate -i -s python3 tools/denoiser/sweep.py [results_dir]        # about 7.5 hours as configured below

Every candidate has the shipped network's input (9 channels) and output (the 4-channel residual), so a winner replaces tools/denoiser/model.keras and the app's .tflite and
nothing else changes. The first configuration is the shipped recipe retrained on exactly this split (the model in the app has seen the held-out scenes, so it is not a fair baseline).
Each run trains a fixed number of steps (so candidates are compared on the same amount of data seen, except where the name says otherwise), then evaluate.py scores it.
Logs, models and sweep_report.md go in the results folder. Safe to stop and restart: finished configurations are skipped.
"""
import os, subprocess, sys, time, json
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/Code/denoiser-sweeps/20261029'))
PY = os.path.expanduser('~/Code/ml-venv/bin/python3')
# name, architecture, loss, steps. In the order they are most informative, so a stopped sweep still answers the main questions.
CONFIGS = [
    ('A_base_12k',     'plain:6:32',     'mse',   12000),   # the shipped recipe, fair baseline
    ('B_unet_mse',     'unet:32:64:96',  'mse',   12000),   # a much wider view of the picture, 1.8x the cost
    ('C_unet_l1',      'unet:32:64:96',  'charb', 12000),
    ('D_base_l1',      'plain:6:32',     'charb', 12000),   # the loss alone
    ('E_base_24k',     'plain:6:32',     'mse',   24000),   # training longer alone
    ('F_deep_12',      'plain:12:32',    'mse',   12000),   # twice as deep, 2.1x the cost
    ('G_wide_64',      'plain:6:64',     'mse',   10000),   # 4 x the weights, 3.8x the cost (fewer steps: it trains 2x slower)
    ('H_unet_big_l1',  'unet:48:96:144', 'charb', 10000),   # the big one, 4x the cost
]
os.makedirs(OUT, exist_ok=True)
env0 = {k: v for k, v in os.environ.items() if not k.startswith('HOLTZMAN_')}
env0.update(HOLTZMAN_EVAL_N='16', HOLTZMAN_NO_LADDER='1')
t_start = time.time()
def log(*a): print(time.strftime('%H:%M:%S'), *a, flush=True)
for name, arch, loss, steps in CONFIGS:
    model = f'{OUT}/{name}.keras'; ev = f'{OUT}/eval_{name}.log'
    if os.path.exists(ev) and 'handheld' in open(ev).read(): log('skip (done)', name); continue
    log('==', name, arch, loss, steps, 'steps')
    env = dict(env0, HOLTZMAN_ARCH=arch, HOLTZMAN_LOSS=loss, HOLTZMAN_MODEL_OUT=model)
    t0 = time.time()
    with open(f'{OUT}/train_{name}.log', 'w') as f: subprocess.run([PY, 'train.py', str(steps)], cwd=HERE, env=env, stdout=f, stderr=subprocess.STDOUT)
    log('trained', name, f'{(time.time() - t0) / 60:.0f} min')
    t0 = time.time()
    with open(ev, 'w') as f: subprocess.run([PY, 'evaluate.py', model], cwd=HERE, env=env0, stdout=f, stderr=subprocess.STDOUT)
    log('scored', name, f'{(time.time() - t0) / 60:.0f} min')
    subprocess.run([sys.executable, 'sweep_report.py', OUT], cwd=HERE)
log('all done in %.1f hours' % ((time.time() - t_start) / 3600))
