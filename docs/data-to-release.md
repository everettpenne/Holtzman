# From photos on the phone to a new release

This is the whole loop, written so you can do it yourself without help: collect data on the phone, copy it to the Mac, retrain the denoiser, refit the look to the Pixel's, update the app, and ship it. Every step says what to type, what you should see, how long it takes, and how to tell if it worked. Nothing here needs Claude.

The loop, in order:

```
 1  collect on the phone ──► 2  pull to the Mac ──► 3  pair + tag ──► 4  prepare
                                                                          │
        ┌─────────────────────────────────────────────────────────────────┤
        ▼                                                                 ▼
 5  retrain the denoiser ─► 6  compare with the shipped one         7  refit the look to the Pixel
        │                          │ (ship only if better)                │ (change two constants)
        └──────────────┬───────────┴───────────────────────────────────────┘
                       ▼
              8  build, test, check on the phone ──► 9  release
```

You can do 5–6 and 7 separately: new Pixel pairs help the look, new bursts help the denoiser. A release can contain either or both.

## What lives where

| Thing | Where |
|---|---|
| The app's source (Kotlin) | `~/Code/HoltzmanCamera` (GitHub: everettpenne/Holtzman) |
| The dataset (never on GitHub: it has your home and people in it) | `~/Code/holtzman-dataset` |
| On the phone | `Documents/Holtzman/Dataset/` (scenes) and `Documents/Holtzman/Dataset/PixelDoubles/` (Pixel photos) |
| Python environment with TensorFlow | `~/Code/ml-venv` |
| Release signing key | `~/Code/holtzman-release/` (`holtzman-release.jks`, `.storepass`). **Back this up somewhere else.** If you lose it you can never update the installed app, only replace it. |
| Android tools (adb) | `~/Code/android-sdk/platform-tools/adb` |
| Notes and measurements | `docs/ix-pipeline.md` (a running log), `docs/changelog.txt` |

The scripts, all in `~/Code/HoltzmanCamera`:

| Script | What it does |
|---|---|
| `tools/dataset/pull.py` | copies the dataset off the phone and checks every file |
| `tools/dataset/pair.py` | matches Pixel photos to scenes |
| `tools/dataset/tag.py` | web page to tag scenes and write notes |
| `tools/dataset/prepare.sh` | converts and aligns new scenes so they can be trained on |
| `tools/denoiser/make_heldout.py` | chooses the scenes the denoiser is never trained on |
| `tools/denoiser/train.py`, `export.py` | train the denoiser, convert it for the phone |
| `tools/denoiser/compare.py` | is the new denoiser better than the shipped one? |
| `tools/denoiser/learning_curve.sh`, `plot_curve.py` | how much does more data help? |
| `tools/look/fit_look.py` | the look vs the Pixel's: the numbers to put in the code |
| `tools/release.sh` | tests, builds, verifies and publishes a release |

Run every command from `~/Code/HoltzmanCamera` unless it says otherwise (`cd ~/Code/HoltzmanCamera`).

---

## 0. One-time setup (check it once, then forget it)

Run these and make sure each prints what is shown:

```bash
~/Code/android-sdk/platform-tools/adb version        # Android Debug Bridge version ...
~/Code/ml-venv/bin/python3 -c "import tensorflow as tf; print(tf.__version__)"    # a version number
python3 -c "import numpy, PIL; print('numpy and Pillow ok')"                       # numpy and Pillow ok
gh auth status                                         # Logged in to github.com
ls ~/Code/holtzman-release/                            # holtzman-release.jks  .storepass
java -version                                          # any recent Java (Gradle needs it)
```

If `numpy` or `PIL` is missing: `python3 -m pip install numpy pillow`. If `gh` is not logged in: `gh auth login`.

On the phone: Developer options on, USB debugging on, and when you plug it in, unlock it and accept "Allow USB debugging". Check: `~/Code/android-sdk/platform-tools/adb devices` should list one device as `device` (not `unauthorized`).

---

## 1. Collect on the phone

**What to collect.** The dataset today is mostly bright, indoor, 1×, main lens: half of the handheld scenes are at ISO 100 or below. Shooting more of that will not help (retraining on five times as many such scenes did not move the held-out score). These are the gaps, with a realistic number of new captures each; do a first batch of about 100, rerun steps 2–7, and only carry on with the types that moved the numbers:

| For | Capture | New scenes |
|---|---|---|
| Denoiser | handheld bursts in dim light (ISO 800–3200) | 60 |
| Denoiser | handheld bursts outdoors / bright | 30 |
| Denoiser | clean tripod references (40 frames each) | 10 |
| Look | Pixel pairs outdoors (sun, shade, overcast) | 60 |
| Look | Pixel pairs backlit and high contrast | 30 |
| Look | Pixel pairs dim and night | 40 |
| Look | Pixel pairs under mixed artificial light | 20 |
| Faces and skin (with the person's agreement) | pairs, both lenses, a range of skin tones, indoors and out | 40–60 |
| Moving subjects | bursts of people or pets moving; a handheld pan | 30 |
| Other lenses | pairs with the Pixel at 0.5×, 2×, 4× and on the front camera | 20 each |
| Calibration (once) | an ISO ladder and three flat white-wall captures (daylight, warm, cool) for each lens that is not the main one | 1 + 3 per lens |

About 400 in all: 7–8 hours of shooting. Tag each scene (step 3) with the lighting, the subject, and `outdoor`, `face`, `motion` or `tripod` where they apply.

**For a pair (a Pixel photo and a Holtzman series of the same scene):**
1. Holtzman: Controls (the six dots) → Settings → Dataset capture. Turn on **Pixel photo first**. Choose what to capture (Handheld burst is the usual one) and tick a lighting and subject tag if you like. Press **Start capturing**.
2. Aim at the scene. Press the shutter. The Pixel Camera app opens: set its zoom if you want one (it forgets between shots), take the photo, tap the ✓. Hold the phone still until Holtzman comes back and says "Saved … with its Pixel photo". Holtzman matches the Pixel photo's zoom for you (never below 1×; see `docs/ix-pipeline.md`).
3. Repeat. To stop: the dataset chip → Stop dataset capture.

**For a clean reference (true noise-free target for the denoiser):** put the phone on a tripod, kind = Clean reference, shoot a still scene. It takes 40 frames and is about 1 GB, so a few are enough.

Each handheld scene is about 150 MB. Keep an eye on the phone's storage.

---

## 2. Pull the data to the Mac

Plug the phone in, unlock it.

```bash
python3 tools/dataset/pull.py --dry-run          # says what it would copy, copies nothing
python3 tools/dataset/pull.py                    # copies the new files and verifies them
```

You should see: `Phone: N files in M folders …`, a progress line, `Verifying … all match.`, and `Done. N files copied (K new scenes) into ~/Code/holtzman-dataset`.

How it behaves: files already on the Mac with the same size are skipped, so it is safe to run as often as you like. An interrupted copy leaves no half files and the next run carries on. Every copied file is checked against the phone's own SHA-256; a bad copy is removed and reported. A scene with no `meta.json` (still being captured) is copied but never deleted.

**To also free the phone's storage** (only after you have seen "all match"):

```bash
python3 tools/dataset/pull.py --delete-from-phone
```

It re-checks every file of every finished scene against the phone, tells you how many scenes it would delete and how big they are, and asks you to type `yes`. A scene is deleted only if **every** one of its files is on the Mac with a matching checksum. It never deletes anything on the Mac. If the phone has no `sha256sum` it refuses to delete anything automatically.

If it says `No phone found`: unlock the phone, replug, accept the USB prompt, and run `adb devices`. If it says `Not enough room`: the Mac needs the size it printed plus 2 GB. Plan for roughly 60 GB for the next 400 captures; an external drive is easiest (`--dest /Volumes/Drive/holtzman-dataset`, and set the `HOLTZMAN_DS` environment variable to the same path when you train).

---

## 3. Pair the Pixel photos and tag the scenes

```bash
python3 tools/dataset/pair.py --dry-run          # look first
python3 tools/dataset/pair.py                    # write pixel_pairs.json
```

Photos made by "Pixel photo first" are named after their scene and pair exactly. Photos you took by hand in the Pixel app (`PXL_…`) are matched to the nearest scene in time, if within 2 minutes. If two photos want one scene, the closer one wins and the other is printed as `REVIEW`: it is probably a photo of something else. `UNMATCHED` lists photos with no scene. Existing pairs are kept, so a correction you made by hand stays; `--redo` starts over.

Then tag:

```bash
python3 tools/dataset/tag.py
```

It opens a page in your browser: the Holtzman preview beside the Pixel photo, tag chips, a note box. Click to tag; it saves as you go (only `tags` and `note` in each scene's `meta.json` are ever written). Click a picture to enlarge it. `j`/`k` move between scenes, `/` adds a tag, `n` jumps to the note. Use the tags **outdoor, face, motion, tripod, ultrawide, zoomed, backlit, dim** consistently: `fit_look.py` uses them to show how the Pixel treats different kinds of scene, and later work (face and motion rules) needs them. Stop with Ctrl-C.

The "untagged only" box and the "with Pixel photo" box filter the list.

---

## 4. Prepare the new scenes for training

```bash
tools/dataset/prepare.sh
```

For each new handheld scene it converts the DNGs and aligns the six frames to the sharpest one; for clean references and ISO ladders it converts them. It prints `To prepare: N handheld, M clean/ladder` and `batch done at …`. A few seconds per handheld scene. It needs about 300 MB free per handheld scene while it works (it deletes the intermediate files). Safe to run again: finished scenes are skipped. A scene it cannot convert is reported (`could not convert …`): check that all its DNGs came across.

**The held-out scenes.** The denoiser is scored on scenes it never trains on. The list is fixed in `~/Code/holtzman-dataset/heldout.txt` (15 scenes today, spread across ISO). Keep it fixed so scores from different runs can be compared. As the dataset grows, add to it:

```bash
python3 tools/denoiser/make_heldout.py --add 5     # moves 5 new scenes (spread over ISO) into the held-out list
```

Do this when you have added at least 25 new scenes, and favour getting **dim** scenes into it. After you add, older models are no longer scored on the same scenes as new ones: score the old model again with `compare.py` (below) rather than trusting an old number.

---

## 5. Retrain the denoiser

The denoiser is a small network (50,000 parameters) trained on your bursts: it learns to turn a noisier average of some frames into the cleaner average of the others. The pictures it learns from are the aligned frames; the Pixel photo is not used.

How much data is enough? Run the learning curve once in a while. It trains on 10, 25, 50, 75 and 100% of the scenes and scores each on the held-out ones, so you can see whether more data is still helping or you are done:

```bash
tools/denoiser/learning_curve.sh 6000            # about 1 to 1.5 hours; leave the Mac alone
python3 tools/denoiser/plot_curve.py /tmp/lc     # draws /tmp/lc/learning_curve.png and prints the numbers
```

If the line is flat between 75% and 100%, more scenes **of that kind** will not help: collect different scenes (look at the ISO breakdown the plot prints) instead.

The real training run:

```bash
cd tools/denoiser
HOLTZMAN_MODEL_OUT=/tmp/new.keras ~/Code/ml-venv/bin/python3 train.py 12000
```

About 30 minutes. It prints `handheld training scenes N`, then `step 100/12000 loss …`; the loss should go down from about 1.8 to 1.4–1.5 and wobble after that. It saves to `/tmp/new.keras` (and every 1,000 steps, so you can stop it early). It trains on every prepared scene except the held-out list.

---

## 6. Is the new denoiser better than the shipped one?

Never ship a model because it trained without errors. Score it against the one in the app:

```bash
cd ~/Code/HoltzmanCamera/tools/denoiser
python3 compare.py model.keras /tmp/new.keras
```

(`model.keras` is committed to git and is the model the app ships.) It takes about 8 minutes and prints the new model's noise reduction divided by the old one's, overall and for dim and bright scenes, with a 95% interval, and a verdict:

* `VERDICT: SHIP`: the new model is better overall (interval above 1.00) and not worse on dim scenes.
* `VERDICT: KEEP OLD`: worse, or no clear difference. Do not ship it; collect different data instead.

**A caution for the first comparison.** The model in the app today was trained on every scene except three, so it has already seen the 15 held-out scenes and has an advantage on them. If a new model wins anyway, believe it. If it only ties, that proves nothing: train a baseline on the same split (`HOLTZMAN_MODEL_OUT=/tmp/baseline.keras python3 train.py 12000`) and compare against that. Once you ship a model trained with the held-out list excluded, later comparisons are fair.

If the verdict is SHIP, convert it and put it in the app:

```bash
cd ~/Code/HoltzmanCamera/tools/denoiser
~/Code/ml-venv/bin/python3 export.py /tmp/new.keras       # writes denoiser_f32.tflite and denoiser_int8.tflite
```

Look at its last line: `error against the clean picture …: int8 is 1.03x the float error`. Anything under about 1.10 is fine; if it is higher, the quantised model lost accuracy: train longer or tell someone who can look at it. Then:

```bash
cp denoiser_int8.tflite ../../app/src/main/assets/denoiser_int8.tflite
cp /tmp/new.keras model.keras
```

The `.tflite` files in `tools/denoiser/` are build products; only the `assets/` copy goes in the app.

---

## 7. Refit the look to the Pixel's

The look has two parts you can fit from the Pixel pairs: the **tone targets** (how bright the Pixel makes the dark, middle and bright parts of a picture) and the **saturation**. Colour balance is shown for information.

```bash
python3 tools/look/fit_look.py --capture-saturation 1.08     # first time: the 68 old pairs were rendered with 1.08
```

(Scenes captured with a build from 0.5.1 on record their own saturation in `meta.json` and need no flag. For older scenes, pass the value the app had when they were shot; `--since 20261029_000000` leaves out scenes before a time.)

What it prints, and what to do:

1. **TONE TARGETS**: a table of the current values, the Pixel's medians, and a 95% interval. Rows marked `keep (inside the noise)` need nothing. If any say `CHANGE`, copy the printed `IxAutoTone.TARGETS = floatArrayOf(…)` line into `ix/src/main/kotlin/com/everettpenne/ix/IxAutoTone.kt` (the `TARGETS` line near the top). Also update the sentence above it that quotes the numbers.
2. **BY SCENE TYPE**: how the Pixel's tone differs by tag. If a type (backlit, dim, outdoor) differs from the all-scene median by much more than the spread printed in part 1, one fixed curve is not enough for it. That is a finding, not an instruction: write it in `docs/ix-pipeline.md` and bring it to whoever works on the scene-dependent tone next.
3. **SATURATION**: the value that matches the Pixel, with an interval. If it says `keep (inside the interval)`, do nothing. If `CHANGE to 0.xx`, edit `saturation = …f` in `LOOK` in `app/src/main/java/com/everettpenne/holtzman/save/BurstRender.kt`.
4. **COLOUR BALANCE**: information only (ours/Pixel ratios). Do not act on it unless it is far from 1.000 in more than one run.

How many pairs: with fewer than about 60 the numbers are not stable (it warns). 100–150 gives a stable fixed curve. The intervals tell you: when they are narrower than a few levels, you are done with that part.

After changing a constant:

```bash
./gradlew :ix:test :app:testDebugUnitTest -q      # must print nothing but the progress; any failure line means STOP and look
```

Add a line to `docs/ix-pipeline.md` saying what changed, from what, to what, using how many pairs.

---

## 8. Build, test, and check on the phone

```bash
./gradlew test -q                                 # all unit tests: silence means they pass
./gradlew :app:assembleDebug -q                   # a debug build to try on the phone
~/Code/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This installs the debug app (`com.everettpenne.holtzman.next`) beside your release one, so it never replaces it. On the phone, check:

* **Computational photo is on** (Controls → Settings). Take 3 photos of a dim scene.
* **The denoiser ran on the TPU**: `~/Code/android-sdk/platform-tools/adb logcat -d | grep HoltzmanBurst | grep -i denoise` shows `learned denoise on the TPU in NNN ms` (about 300 ms). If it says `TPU denoise failed, using the classical one`, the new model did not load: do not release; check that `app/src/main/assets/denoiser_int8.tflite` is the file you exported.
* **No shader problems**: `adb logcat -d | grep -i "unavailable"` prints nothing.
* **The look**: take a few paired captures (step 1) with the new build, pull and pair them, and run `python3 tools/look/fit_look.py --since <today's date and time>`. Every row should now say `keep`: that closes the loop.
* **Speed**: `adb logcat -d -s HoltzmanTiming | grep "processed and saved"`: about 1.6–2.2 seconds a shot. A big jump means something regressed.
* **A few real photos** against the same scenes from the previous release. Look at them.

Then add and commit your changes (the dataset is not in this repository, so nothing private goes up):

```bash
git add -A
git status                                         # read it: only code, the assets/ model, docs
git commit -m "Retrain the denoiser on N scenes; refit the tone targets to M pairs

- what changed and the numbers that justify it"
```

---

## 9. Release

Write the release notes first (a short file, e.g. `/tmp/notes.md`): what is new, what you tested and on what, and what you did not test. Be specific; earlier releases' notes on GitHub are a template.

Dry run first. This bumps the version, runs the tests, builds the signed APK and verifies it, and publishes nothing:

```bash
tools/release.sh 0.5.1
```

You should see `ok: last release v0.5.0, building 0.5.1`, `ok: all tests pass`, the package name and version, the signing certificate (`CN=Everett Penne, O=Holtzman`, digest `2f8d5ffb…` — if the digest is different, **stop**: the APK is signed with the wrong key and will not update the installed app), the APK size, and `Built and verified`. Install that APK on the phone and look at it:

```bash
~/Code/android-sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

When you are happy, undo the dry-run's version bump and publish:

```bash
git checkout app/build.gradle.kts
tools/release.sh 0.5.1 --publish --notes /tmp/notes.md
```

That commits `Version 0.5.1`, pushes to GitHub, tags `v0.5.1`, and creates the GitHub pre-release with the APK attached. It prints `Released v0.5.1, sha256 …`. The script refuses to start if you are not on `main`, if anything is uncommitted, if the version is not newer than the last tag, or if the signing key or `gh` login is missing.

Afterwards: open the release page, download the APK from GitHub on the phone (or `adb install -r`), and check it launches. Installing over the existing app keeps your settings and does not need a new permission.

**Version numbers**: `0.X.0` for a new feature, `0.X.Y` for a retrained model or a fixed constant. The `versionCode` (a whole number that must always go up) is bumped for you.

---

## If something goes wrong

* **Pulled the wrong thing or deleted too much from the phone**: it can't happen without `--delete-from-phone` and typing `yes`, and then only verified scenes go. The Mac copy is the master: back it up (Time Machine or an external drive) before a delete.
* **A new model is worse**: `git checkout tools/denoiser/model.keras app/src/main/assets/denoiser_int8.tflite` puts the shipped one back.
* **A release is bad**: on GitHub, mark it a draft or delete it, install the previous APK (`v0.5.0`'s is still attached to its release), and fix forward with the next version number. You cannot go back to a lower `versionCode` over an installed app without uninstalling it first (which clears its settings).
* **`release.sh` stopped halfway**: it never commits or pushes until the very end. `git status` shows only the version bump if it stopped earlier: `git checkout app/build.gradle.kts`.
* **Training is slow or the Mac runs out of memory**: close other apps; the data loads from disk as it goes. The 12,000-step run needs a few GB of RAM.
* **The training scripts say `heldout.txt` or scenes are missing**: run `tools/dataset/prepare.sh` first (step 4); a scene must be prepared (it has `aligned_0.raw`) to be used.
* **Lost the signing key**: you cannot publish an update that installs over the old app. Keep a copy of `~/Code/holtzman-release/` somewhere safe, and the passphrase.

## Holtzman's own photo and the blind A/B

Since app 0.5.1, every paired handheld dataset capture also runs the real pipeline and saves the finished picture as `holtzman.jpg` in the scene folder
(meta.json: `holtzmanFinal`, `holtzmanFinalMs`). `tools/dataset/pull.py` brings it along with the rest of the scene.

`python3 tools/dataset/ab.py [dataset]` (http://127.0.0.1:8766) shows, for every scene that has both `holtzman.jpg` and a Pixel photo, the two pictures with the sides shuffled
and unlabelled. Vote left / right / same (keys 1, 2, 3), optionally say what decided it; the answer is revealed afterwards. Click a picture for matching 100% crops.
Votes go to `<dataset>/ab_votes.jsonl`; `/summary` gives Holtzman's win rate with 95% intervals overall, by ISO and by tag, and the reasons given for Pixel wins, which is the work list.
Scenes captured before 0.5.1 have no `holtzman.jpg` and do not appear.

## Holtzman Studio (no terminal needed)

Double-click `Holtzman Studio.command` in the repo's top folder (or `python3 tools/studio.py`). A page opens at http://127.0.0.1:8770 with status cards (phone connected, dataset
counts, overnight training progress, unpublished version) and a button for every step above: copy from the phone, free up the phone, pair, prepare, tag, blind A/B, fit the look,
start the training and see its results, compare and export a model, build or publish a release. Each job's output streams into the panel at the bottom. Deleting from the phone,
changing the test set and publishing each ask first. It listens only on this computer and each action needs the page's secret token. The first time macOS may ask you to allow
the .command file: right-click it, Open.
