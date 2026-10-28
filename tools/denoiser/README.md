# Learned raw denoiser

Trains a small convolutional network (49,000 parameters, 6 layers of 32 channels) that cleans the merged RAW mosaic, and exports it for the phone's TPU.

**Data.** The camera's dataset mode (handheld bursts, the ISO ladder, the 40-frame clean reference). A burst's frames are aligned to the sharpest one (`./gradlew :ix:alignDump -Pdata=<folder>`, after `tools/prototypes/dng_to_raw.py`), then split into two independent sets: A (1 to 4 frames averaged) is the input, B (the others) the target. The two sets' noise is independent, so a network taught to turn A into B learns the clean picture ("noise2noise"): no clean ground truth is needed. Places where A and B disagree (motion, misalignment) are left out of the loss. Three scenes were held out and never trained on (ISO 407, 574, 1101).

**What the network sees.** Per 2x2 cell: each of the four colour planes' fine detail in units of the local noise (the plane minus its 5x5 mean, over the noise level, clipped to +-8), the planes themselves, and the noise level (100 x, from the sensor's measured noise model and the number of frames averaged): 9 channels. It answers with a correction in units of the noise level. Feeding raw counts did not train: the signal is hundreds of times the noise, and the network settled on doing nothing (loss flat at its starting value); putting the problem in noise units fixed that.

**Run.** `python3 train.py 12000` (about 28 minutes on the Mac's CPU), `python3 evaluate.py` (against the classical denoise on the held-out scenes), `python3 export.py` (writes `denoiser_f32.tflite` and `denoiser_int8.tflite`; copy the int8 one to `app/src/main/assets/`). `classical.py` is the baseline, in numpy.

Needs the venv at `../ml-venv` (TensorFlow), the dataset under `$HOLTZMAN_DS` (default `/tmp/ds`). Results: `docs/ix-pipeline.md`.
