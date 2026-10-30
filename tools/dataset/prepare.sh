#!/bin/zsh
# Get new scenes ready for training: convert the DNG frames to the plain files the tools read, and align each handheld burst to its sharpest frame.
#
#   tools/dataset/prepare.sh [dataset_folder]        (default ~/Code/holtzman-dataset)
#
# For every scene that has not been prepared yet (it is safe to run again; finished scenes are skipped):
#   handheld  ->  frame_N.raw + params.json (tools/prototypes/dng_to_raw.py), then aligned_N.raw (./gradlew :ix:alignDump), then frame_N.raw is deleted (the aligned
#                 frames replace them, saving about 150 MB a scene);
#   clean / ladder  ->  frame_N.raw + params.json (the training reads these as they are).
# Aligning is done in batches of 12 so a hiccup costs little. Needs: the ML venv (../ml-venv, for numpy), Java for gradle, about 300 MB free per handheld scene.
set -u
DS=${1:-$HOME/Code/holtzman-dataset}
REPO=${0:A:h:h:h}
PY=$HOME/Code/ml-venv/bin/python3; [ -x $PY ] || PY=python3
[ -d $DS ] || { echo "No dataset folder at $DS"; exit 1; }
cd $REPO

hand=(); static=()
for d in $DS/scene_*_handheld(N); do [ -f $d/meta.json ] && [ ! -f $d/aligned_0.raw ] && hand+=($d:t); done
for d in $DS/scene_*_clean(N) $DS/scene_*_ladder(N); do [ -f $d/meta.json ] && [ ! -f $d/params.json ] && static+=($d:t); done
echo "To prepare: ${#hand[@]} handheld, ${#static[@]} clean/ladder."
need=$(( ${#hand[@]} * 300 ))   # MB
free=$(df -m $DS | awk 'NR==2{print $4}')
[ $free -gt $((need + 2000)) ] || { echo "Not enough room: about ${need} MB needed (and 2 GB spare), ${free} MB free."; exit 1; }

for s in $static; do $PY tools/prototypes/dng_to_raw.py $DS/$s 2>/dev/null | tail -1 || echo "  could not convert $s"; [ -f $DS/$s/params.json ] || echo "  could not convert $s (its DNG frames are missing or damaged)"; done

i=1
while [ $i -le ${#hand[@]} ]; do
  batch=(${hand[$i,$((i+11))]})
  stage=$DS/.stage; rm -rf $stage; mkdir $stage
  for s in $batch; do
    # dng_to_raw writes frame_N.raw and params.json; a scene is only linked for alignment once that worked
    $PY tools/prototypes/dng_to_raw.py $DS/$s >/dev/null && ln -s $DS/$s $stage/$s || echo "  could not convert $s (skipped)"
  done
  ./gradlew :ix:alignDump -Pdata=$stage -q 2>&1 | grep -v '^$' | tail -12
  for s in $batch; do [ -f $DS/$s/aligned_0.raw ] && rm -f $DS/$s/frame_?.raw; done
  echo "batch done at $i of ${#hand[@]}"; i=$((i+12))
done
rm -rf $DS/.stage
echo "Finished. Prepared scenes are ready for tools/denoiser (see docs/data-to-release.md)."
