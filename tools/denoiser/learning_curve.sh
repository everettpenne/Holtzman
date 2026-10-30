#!/bin/zsh
# Learning curve for the raw denoiser: train on a fraction of the handheld scenes at a fixed number of steps, score on held-out scenes.
#   ./learning_curve.sh [steps]      results in /tmp/lc/ ; HOLTZMAN_VAL is the held-out list (see below)
STEPS=${1:-6000}
PY=/Users/everettpenne/Code/ml-venv/bin/python3
export HOLTZMAN_VAL=$(cat /tmp/val_list.txt) HOLTZMAN_EVAL_N=10 HOLTZMAN_NO_LADDER=1
for f in 0.1 0.25 0.5 0.75 1.0; do
  export HOLTZMAN_FRAC=$f HOLTZMAN_MODEL_OUT=/tmp/lc/model_$f.keras
  echo "== fraction $f" ; date
  $PY train.py $STEPS > /tmp/lc/train_$f.log 2>&1
  $PY evaluate.py /tmp/lc/model_$f.keras > /tmp/lc/eval_$f.log 2>&1
  echo "done $f"; date
done
echo ALLDONE
