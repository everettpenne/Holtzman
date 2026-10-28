# Ix computational pipeline: plan

Target: Google Pixel 10a (Tensor G4, `zumapro`, 7.3 GB RAM, Android 17, GrapheneOS). Facts below marked **(probe)** were read from the phone; **(unverified)** need a spike before anything depends on them.

## What the phone gave us (probe)

- Back camera: logical (2 lenses), RAW 4000x3000, MANUAL_SENSOR, BURST_CAPTURE, pipeline depth 7, **no reprocessing**, one RAW stream at a time. Night extension offered; no Ultra HDR output, no 10-bit video.
- Vulkan 1.1+ with compute; OpenGL ES 3.2. The Edge TPU services are present (`com.google.edgetpu_*`), but whether a third-party app may reach the NPU is **(unverified)**.

## Corrections to the first sketch

1. **Dual-pixel depth is out.** The RAW stream the HAL hands apps is the combined Bayer frame; sub-pixel parallax is not exposed through Camera2 **(unverified; the probe lists no such stream)**. Depth, if wanted, comes from the logical camera's two lenses or from a monocular network, later.
2. **"Short x4 + long x1" is not a plan.** The proven approach (HDR+) is N *equal*, deliberately underexposed frames, merged in the RAW domain, which cuts noise by about sqrt(N) and protects highlights. A bracket with one long frame brings motion blur and alignment failures. Start with N = 5..7 equal frames at a shared short exposure.
3. **The camera has no Bayer pre-processing to bypass.** What a third party can skip is the HAL's *tone and detail* stages (set NR/edge/tone-map OFF on the RAW stream). We still must apply the sensor's colour matrices, lens-shading map, black and white levels ourselves. The first sketch omitted all of this.
4. **Neural demosaic is the last thing to add, not the first.** Alignment and merge (classical, then learned) give most of the gain. A joint demosaic-denoise network (NAFNet-class) is worth it only if it beats merge + a good classical demosaic on real frames from this sensor, and **there are no ready weights**: it must be trained or fine-tuned on paired RAW data from this phone. Plan for that cost or skip it.
5. **Accelerator claims.** The NNAPI delegate is deprecated; LiteRT's GPU delegate (OpenCL / OpenGL, and a Vulkan path in newer releases) is the dependable route. A TPU/NPU path on Tensor G4 for third-party apps is **(unverified)**; the first spike measures what actually runs, and at what latency, before models are designed around it.
6. **Vulkan is optional.** The merge and the gain map are cheap enough on the CPU at block resolution. Move to GPU only for the full-resolution stages (merge, slicing, LUT), and prefer LiteRT's GPU buffers or OpenGL ES compute first; Vulkan only if profiling demands it.
7. **Ultra HDR is built and shipping in the app** (Android's gain-map API, from RAW vs JPEG). ISO 21496-1 metadata is already what that API writes; a hand-written container is only needed to keep the original JPEG bytes.
8. **Privacy stays a hard rule:** all inference on device, no INTERNET permission, models bundled (and their licences listed).

## Stages

| # | Stage | Where it runs | Status |
|---|---|---|---|
| 0 | Camera2 capture, RAW + JPEG, Night extension, Ultra HDR from one RAW | CPU | **done** |
| 1 | **Burst capture:** 6 equal RAW frames at the exposure auto had chosen, NR/edge off, per-frame results (exposure, gains, lens-shading, black level); each frame is copied off-heap at once because the camera lends only a few buffers | Camera2 | **done** (tested on the 10a) |
| 2 | **Align + merge (classical):** three-level tile alignment in whole 2x2 cells, then a noise-aware weighted mean in the RAW domain, in `:ix` (JVM-tested). Output: a merged DNG next to the unmerged reference DNG. **1.4 s on the 10a for 6 frames** (needs a non-debuggable build: a debuggable one ran 90x slower) | CPU | **first version done**; measured noise reduction on textured real patches 1.0x-1.8x (ideal 2.4x); needs a flat chart and a calibrated noise model |
| 3 | **Render:** black level, white balance, colour matrix, demosaic (classical), tone map, JPEG / Ultra HDR / DNG out | CPU/GPU | next |
| 4 | **Accelerator spike:** LiteRT `CompiledModel` on CPU / GPU / NPU with a 7-layer 32-channel conv net (15 GMAC, 1024x1024 RGGB-plane input, FP16 weights) | Tensor G4 | **first result below** |
| 5 | **Learned tone mapping:** small network predicting a bilateral grid, sliced at full resolution (cheap, high value) | GPU/NPU | after 3 |
| 6 | **Learned denoise / demosaic:** only if stages 2-3 leave visible noise and a trained model beats them | NPU/GPU | maybe |
| 7 | Learned alignment, depth, portrait | | later |

## Evaluation (not optional)

Each stage is judged on a fixed set of scenes shot with Holtzman and the stock camera: bright outdoor, indoor tungsten, low light, high contrast, moving subject, text. Measures: noise (std of a flat patch), detail (edge sharpness on a chart), highlight retention, colour against the stock JPEG, and processing time. A stage that does not beat the previous one on this set is not shipped.

## Accelerator spike, first result (Pixel 10a, LiteRT 2.3.2, non-debuggable build)

| Processor | Time per 1024x1024 pass | Output vs CPU |
|---|---|---|
| CPU (XNNPACK) | 654 ms | reference |
| **GPU** | **36 ms** (18x faster) | sum differs by 0.14%: FP16 rounding, as expected |
| NPU | 685 ms | **bit-identical to the CPU: the model did not run on the NPU; LiteRT registered the NPU accelerator but silently fell back** |

What this says: the **GPU is the dependable accelerator today**: a denoiser-sized network on a 12 MP frame (about 12 passes of this size) is roughly half a second, well within a shutter-to-saved budget. The **NPU is reachable by a third-party app** (the runtime registers, the Edge TPU libraries are public on this build), but this FP16 model was not taken by it. Likely causes, to be tested in this order: the NPU wants an int8-quantised model, and/or an ahead-of-time compile with the Tensor compiler plugin. Until an int8 model runs there (and is faster than the GPU), design for the GPU; the NPU stays an optimisation.

Practical notes: the LiteRT 2.3 libraries need Kotlin 2.4 (the app moved off 2.1.20; Glass is a separate build and unaffected), and add about 70 MB to the APK (GPU and NPU runtimes); the debug build must be non-debuggable for any of this to be fast (a debuggable build ran the merge 90x slower).

## Accelerator spike, second result: int8 model

The same network fully integer-quantised (int8 weights and activations, `tools/make_spike_model.py` with a representative dataset):

| Processor | fp16 model | int8 model |
|---|---|---|
| CPU | 652 ms | 148 ms |
| GPU | 38 ms | 37 ms (no gain: the GPU runs it as float either way) |
| NPU | 680 ms (= CPU output) | 151 ms (**output sum 11690.373, identical to the int8 CPU run: still the CPU**) |

Conclusion: int8 helps the CPU 4.4x but does **not** make LiteRT hand the model to the NPU. The runtime lists NPU as available, yet both models come back bit-identical to the CPU. The remaining route is an ahead-of-time compile for the Tensor chip (the vendor compiler plugin, run on a host, producing a model with the NPU partition baked in). Not done; until it is, the GPU (37 ms per pass) is the shipping accelerator and the NPU stays unproven.

## Measurements, one burst (Pixel 10a back camera, 6 frames, indoor, median scene level about 62 counts over black)

Method: green-plane samples of the reference DNG against the merged DNG; flat tiles are the flattest 10% of 32x32 tiles in the reference, residual standard deviation after a plane fit (so slow gradients are not counted as noise).

| Measure | Reference | Merged | Ratio |
|---|---|---|---|
| Noise, whole image (Immerkaer estimator, counts) | 10.9 | 5.6 | 1.94x |
| Noise, flat tiles (counts) | 4.39 | 2.13 | 2.06x (ideal for 6 frames: 2.45x) |
| Edge strength, gradient 99.5th percentile, block-averaged 8x8 so noise does not inflate it (JPEG luma) | 37.2 | 36.7 | edges kept |
| Same, unaveraged | 49.6 | 26.4 | **misleading**: the reference's figure is mostly noise |

Reading: the merge gets about 84% of the ideal noise reduction without softening real edges. Mean frames per cell was 5.94 of 6, so little was rejected; the gap to 2.45x is the alignment and the soft weighting, not over-rejection.

Noise model fitted on the same burst: variance = 1.5 x signal + 7.9 counts squared (read noise about 2.8). The earlier guess was 0.8 and 5. The new values are the defaults in `IxMergeParams`. Caveats, plainly: it is one capture at one ISO; the flattest-tile selection biases the fit slightly low; shot noise scales with ISO and the model does not yet. The burst log now prints the ISO so further captures can pin that scaling down.

Stage timings on the phone (non-debuggable build, 12 MP, 6 frames): merge 1.3 s, rendering two JPEGs 6.8 s, total 8.5 s. The render is the cost, and a GPU render pass is the obvious next target.

Not measured: the effect of the new noise defaults on a fresh burst (installed on the phone, not yet re-shot), moving subjects, low light.

## Why the NPU does not run it (diagnosed from the device log)

Two separate causes, found in order:

1. **The compiler plugin was never loaded.** Log: `Failed to apply compiler plugins: No compiler plugin found`, although `libLiteRtCompilerPlugin_google_tensor.so` was in the APK. LiteRT finds the plugin by listing a directory, and the app's native libraries were not extracted (`extractNativeLibs=false`, the Android default), so the directory held nothing. Fixed with `packaging { jniLibs { useLegacyPackaging = true } }` in `app/build.gradle.kts` (the app is larger on disk as a result). After that the plugin loads, partitions the whole network onto the NPU (10 of 10 ops, 1 partition) and starts compiling.
2. **The phone refuses the compile.** Log: `libedgetpu_client: Compile failed, error code 16, message: Current application should not be allowed to access EdgeTPU.` This comes from the Edge TPU service on the device, not from LiteRT or from the model, and LiteRT then falls back to the CPU without telling the app (hence the identical outputs). So the model is NPU-compatible; this app is not permitted to use the NPU. Whether the check is a package/signature allowlist or something GrapheneOS-specific is **not established**; the message does not say.

Consequence: an ahead-of-time compile on the Mac would not obviously help, since the refusal is about the app, not about where the model was compiled (not tried). The GPU stays the shipping accelerator. Also worth knowing: the app must not treat "NPU offered" as "NPU used"; check the output or the log.

### What others have found (searched 2026-10-27)

- LiteRT issue #11251 (https://github.com/google-ai-edge/LiteRT/issues/11251) reports exactly our log on a Pixel 10 Pro (Tensor G5, Android 17): the Edge TPU service answers "not in the EdgeTPU allowed list or signature mismatched" (error 16), `CompiledModel.create()` succeeds, and the model silently runs on XNNPACK. A LiteRT maintainer replied that on-device compilation on Google Tensor is experimental and not released, so access is limited; they suggested ahead-of-time (AOT) compilation instead. The reporter ran an AOT-compiled model on the TPU from the same, non-allowlisted app.
- So the refusal is a Google-side allowlist on on-device (JIT) compilation, not a GrapheneOS setting and not our model. AOT-compiled models are not subject to it.
- The route to AOT is the Google Tensor SDK Beta (sign-up form; Linux x86_64 with Bazel). Its documentation lists Tensor G5 and G6 only; the Pixel 10a's chip is not named there, so whether AOT works on it is unknown.
- GrapheneOS has a "Special app access" toggle for Google apps' direct Tensor access; reports say it does not gate ordinary apps, and one Pixel 10 Pro Fold user reported CPU-only local LLMs. Nothing found ties error 16 to GrapheneOS.

## GPU render pass (Pixel 10a, OpenGL ES 3.1 compute, `save/GpuRender.kt`)

The render stage now runs as two compute passes (counts to linear with shading; demosaic, white balance, colour matrix, tone, sRGB). The demosaic taps are generated from `IxRender.kernels`, so the CPU and GPU renderers use the same numbers. If the GPU path throws, the burst falls back to the CPU renderer.

| Stage, one 12 MP picture | CPU | GPU |
|---|---|---|
| Render | about 3.4 s | merged 163 ms, reference 103 ms (the first includes shader compile) |
| Whole burst, shutter to both JPEGs and DNGs saved | 8.5 s | 3.0 s (merge 1.3 s) |

Equivalence check, run on every burst: 32 rows of the merged picture rendered by the CPU renderer and compared with the GPU's: largest channel difference 1 level, none beyond 1 (rounding). This burst was at ISO 117, a bright scene, not the ISO ~860 one the noise model was fitted on; 5.999 of 6 frames contributed per cell, so at low ISO the model is lenient. Shot noise scaling with ISO is still not modelled.

## Noise calibration across ISO (Pixel 10a back camera, `NoiseCalibration`, two frames per ISO at equal brightness, dim indoor scene)

Photon-transfer fit per ISO (variance = shot x signal + read variance, counts squared, from about 11,400 tiles each; bins are medians so texture does not bias them):

| ISO | shot | shot / ISO | read variance |
|---|---|---|---|
| 99 | 0.184 | 0.00185 | 1.7 |
| 200 | 0.347 | 0.00174 | 0.7 |
| 399 | 0.672 | 0.00168 | 1.0 |
| 799 | 1.362 | 0.00170 | 4.5 |
| 1600 | 2.747 | 0.00172 | 11.8 |
| 3194 | 5.281 | 0.00165 | 52.9 |

Shot noise is linear in ISO (about 0.0017 per ISO unit). Read variance follows roughly 1 + 5.2e-6 x ISO^2. The earlier single-burst fit at about ISO 860 (shot 1.5, read variance 7.9) agrees with this model (1.46 and 4.8). The signal in these bins only reaches about 86 counts (the scene was dim), so the read variance at low ISO is the least certain figure. The merge now uses `mergeParamsForIso` from each burst's reference-frame ISO.

## GPU merge (Pixel 10a, OpenGL ES 3.1 compute, `save/GpuMerge.kt`)

Alignment and merge now run as compute shaders, with the same algorithm as `IxBurst.merge`: a three-level brightness pyramid per frame, a tile search that tests the same candidates with the same penalties and picks the same winner (candidates are scored in parallel, one thread each; a single thread then takes the cheapest, first of equals in the CPU's loop order), and a running weighted sum with the same noise-aware weight. The CPU merge remains the fallback if the device has no ES 3.1 context.

Equivalence, measured on a real 12 MP burst by running both on the same frames (`VERIFY_GPU`): largest difference between GPU and CPU merged samples **1 count; 0 of 12,000,000 samples differ by more than 1** (mean difference 0.00005 counts); mean frames per cell identical (5.952927 both). The remaining difference is float rounding in the weights.

| Stage | CPU | GPU |
|---|---|---|
| Merge, 6 frames, 12 MP | 1.3-1.4 s | about 0.39 s (uploading the frames and compiling shaders about 0.1 s, pyramids 20 ms, align 150 ms, accumulate 45 ms) |
| Render two pictures | 6.8 s (CPU) | about 0.23 s (plus exposure, context and shader set-up: 0.9 s as logged) |
| **Shutter to everything saved** | **8.5 s** | **1.6 s** |

A first parallelisation matters here: with one thread per tile the align pass took 406 ms for five frames (11,750 threads, each serial); one workgroup per tile took it to 150 ms with the identical result.

Not done: caching compiled shader programs between bursts, keeping the merged mosaic on the GPU for the render (it is read back and uploaded again), and GPU reference-frame selection (still the CPU's sampler, a few milliseconds).

## Side-by-side with the Pixel camera app (same phone, same still scene, 2026-10-27)

Method: the phone stayed put; one photo from the Google camera app (1x, default settings, `PXL_*.jpg`) and one from a Holtzman burst, compared by script (luma percentiles, saturation, 100% crops) and by eye. One dim indoor scene, so everything below is tuned on a single scene and needs more.

First comparison, Holtzman against Google:

| | Google | Holtzman |
|---|---|---|
| Darkest 1% of pixels (luma) | 2 | 24: blacks lifted, no black point |
| Median luma | 82 | 99 |
| Pixels above 235 | 0.5% | 5.7%: highlights blown |
| Mean saturation | 159 | 141 |
| Label edge gradient, 99th percentile (100% crop) | 33.6 | 14.1 |

**"Much blurrier" was mostly focus, not rendering.** The burst request turned autofocus off without setting a focus distance, so the lens went to the camera's default instead of staying where the preview had focused: the burst was sharp on the floor and soft on the puzzle label the preview had focused on. Fix: the burst now sets `LENS_FOCUS_DISTANCE` to the preview's (3.09 diopters, AF state PASSIVE_FOCUSED in the test). That alone took the label edge gradient from 14.1 to 22.8.

Then the finish (`IxLook`, GPU only for sharpening): black point 0.04, S-curve contrast 0.4, saturation 1.15, a tone curve quantile-matched to Google's picture (shadows and mids lifted a little, highlights compressed), exposure target 0.12 instead of 0.18, and a finishing pass that smooths chroma everywhere, smooths luma in shadows, and sharpens luma (strength 1.8, core 1.5/255) with the sharpening fading out in shadows. Result on the same scene:

| | Google | Holtzman now |
|---|---|---|
| Luma percentiles 1/5/25/50/75/95/99 | 2 / 9 / 47 / 82 / 127 / 183 / 206 | 6 / 11 / 40 / 73 / 121 / 195 / 228 |
| Label edge gradient 99th / 99.9th percentile | 33.6 / 61.7 | 31.6 / 65.4 |
| Pixel-scale noise, flat wall / dark doorway | 3.96 / 1.09 | 4.75 / 1.16 |
| Mean saturation | 159 | 167 |

Still different: highlights are brighter than Google's (99th percentile 228 against 206), midtone noise is slightly higher, and Google renders this room's light warmer (its wall is about 6% less blue than ours); colour is not tuned. Google also applies local tone mapping that a global curve only approximates. The tone curve and look were fitted on this one scene (exposure varies burst to burst, so repeat bursts land a few levels apart). The CPU fallback renderer has the black point, contrast, saturation and curve but not the sharpening and denoising.

## Demosaic, RAW denoise and sub-pixel alignment (2026-10-28)

All three were designed on a host first (`tools/prototypes/`: take a sharp photograph as ground truth, mosaic it, add noise from the measured model, process, compare), then ported. Nothing below has been run on the phone yet; the GPU ports were checked against the CPU engine on an emulator.

**Demosaic: shipped.** The Malvar-He-Cutler filter is replaced by Hamilton-Adams green (row and column interpolation, blended by the inverse squared gradient aggregated over neighbouring same-colour sites) followed by colour-difference interpolation of red and blue (diagonals weighted toward the direction along which green changes least). Against the original pictures, mean PSNR over five regions: 38.6 dB before, 40.5 dB after; with noise added (0.003 and 0.008 of white): 35.8 to 37.6 and 33.3 to 34.5. It wins on every region tried. The GPU version is a green-plane pass then a colour-difference pass; an emulator test checks it against the CPU renderer on a textured scene.

**Spatial denoise of the merged RAW mosaic: shipped** (`IxDenoise`, and inside `GpuMerge`). An edge-aware average over the neighbouring 2x2 cells, guided by the cell brightness and sized by the measured noise model and by how many frames the merge actually combined, averaging each sample only with samples of its own colour. Mean PSNR after demosaic, merged-frame noise at 6 frames:

| ISO | no denoise | strength 0.7 | 1.0 | 1.4 | 1.8 | 2.4 |
|---|---|---|---|---|---|---|
| 100 | 38.21 | 38.80 | 38.83 | 38.87 | not run | not run |
| 800 | 34.31 | 36.67 | 37.15 | 37.59 | 37.84 | 38.00 |
| 3200 | 29.10 | 33.39 | 34.42 | 35.33 | not run | not run |

The shipped strength is 1.6, between the 1.4 and 1.8 columns (not measured on its own). The gain flattens past about 2.4 at ISO 800; a moderate strength keeps detail margin, since PSNR does not fully see lost fine texture. An emulator test runs the GPU merge with denoise against the CPU engine on synthetic noisy frames: under 0.5% of samples differ by more than 2 counts.

**Sub-pixel alignment: tested, not shipped.** For the 1x merge it does not help: moving a frame by a fraction of a pixel means interpolating each colour from a lattice with 2-pixel spacing, which blurs more than the up-to-1-pixel misalignment of the whole-cell alignment does (about 0.8 px against 0.3 px of blur by my estimate). It only pays when the output has more pixels than the sensor (zoom), as super-resolution: every raw sample is splatted at its true position into per-colour planes at 2x. On the prototype this loses to demosaic-plus-upscale of a single frame (6 frames: 26.9 against 30.3 dB with noise; even 49 frames covering every phase: 30.2 against 33.7 dB), because the splat adds blur and nothing deconvolves it. Making it work would need at least 15 frames and a deconvolution step, and the RAW stream is not cropped by the zoom today (a burst at 2x gives the full field of view), so it is a larger project than the three-frame-class fixes. Parked.

## First run on the phone (Pixel 10a, Tensor G4 `zumapro`, 2026-10-28)

The shaders written without the phone (directional demosaic, RAW denoise, noise-aware finish) all built and ran on the Pixel's GPU with no fallback warnings. Findings:

- **Speed.** First version: render 1.0 s and 0.9 s per picture, burst 3.9 s. Cause: the finish measures the picture's noise by reading it back from GPU memory sample by sample, 0.71 s. Moved onto the GPU (a histogram of the noise filter's response, 24 KB read back): 5 ms. Now per picture 0.23 to 0.38 s (shader compile in the first), burst about 2.0 s including the denoise and finish (1.6 s before them). Pass times for one picture: upload 30, linear 6, green 24 to 49, tone 15 to 23, noise 5, 3x3 luma 7 to 9, finish 65 to 77, readback 65 ms.
- **Noise.** The finish's measured display noise on the merged picture was about a quarter of the reference frame's (1.0 against 4.0 levels of 255).
- **Orientation.** With the phone on its side the burst JPEG is upright landscape with EXIF 1, as it should be.
- **The camera's own noise profile agrees with our measurement.** Per frame, `SENSOR_NOISE_PROFILE` (S, O, normalised to the white level) times the 959-count range gives shot gains of 0.18 (ISO 99), 0.36, 0.70, 1.40, 2.8 and 5.6 (ISO 3194), against our fitted 0.184 to 5.28; read variances within about 1.5x. The dataset's `meta.json` records it per frame.
- **Bug found: out of memory on back-to-back bursts.** Direct buffers count against the 256 MB Java heap; the camera callbacks kept the previous burst's six 24 MB frames alive until the next burst replaced them, so the 40-frame clean reference died on its second burst. Fixed (buffers dropped on delivery, a failure is reported instead of ending the app, a partly written scene is marked `"complete": false`, and the app asks for a large heap). Re-run: 40 frames at one ISO and exposure, complete.
- **Focus.** With the preview's continuous autofocus the burst could be sharp on the far wall and soft on near objects (the factory scene: focus at 0.14 diopters, about 7 m, with objects at 0.5 to 1 m soft). The shutter now runs a fresh scan aimed at the centre of the frame and locks it (unless the user tapped). In that scene the centre of the frame is the far wall, so it still locked near infinity (0.005 diopters): nothing but a tap, or subject detection, would pick the headphones. Handheld scenes earlier in the day held 0.65 to 5.0 diopters with at most 0.07 drift across a burst.

## Local tone mapping, grain and an adaptive tone curve (2026-10-28, from the Pixel app's stack)

Ideas taken from the structure of Google's pipeline as seen in `pixel-camera-re/REPORT.md` (a multi-scale contrast stage, a separate film-grain stage after denoising, tone chosen per picture); implemented with our own methods.

- **Local tone mapping** (`localTone`, GPU): a 1/16-resolution map of the log brightness of each block (from the raw mosaic's own samples), smoothed with an edge-preserving bilateral filter so it follows regions and not detail, then used as a per-pixel gain that compresses the neighbourhood brightness about the scene key (lifts shadows, eases highlights, capped at +1.6 / -0.7 stops). Strength 0.35.
- **Film grain** (`grain`, GPU): a hashed noise, averaged over 2x2 so it is a pixel or two across, scaled by brightness (none in the deepest shadows, less in highlights), 1.2 levels of 255 at most, added at the end of the finish. It is in the JPEG only, never the DNG.
- **Adaptive tone curve** (`autoTone`, `IxAutoTone`): replaces the fixed curve. Two Pixel camera app pictures of different scenes (a dim room, a bright factory desk) land on almost the same luma anchors: median 82 and 92, 95th percentile 183 and 194, 99th 206 in both, darkest percent near black. The curve is built per picture from its own brightness distribution (a 256-bin histogram on the GPU) so its ranks land on those targets (1%: 4, 5%: 14, 25%: 48, 50%: 87, 95%: 188, 99%: 206 of 255), blended by strength and limited to slopes between 0.45 and 2 so flat or contrasty scenes do not band. The fixed curve fitted to the first scene alone had left the second scene flat (darkest 1% at 31 where Google's was 7).

Same-scene comparison on the phone (factory desk, Google camera app against Holtzman), luma percentiles 1/5/25/50/75/95/99:

| | Google | fixed curve + local tone 0.5 | adaptive curve + local tone 0.35 |
|---|---|---|---|
| luma | 7 / 20 / 49 / 92 / 165 / 194 / 206 | 31 / 41 / 61 / 91 / 136 / 162 / 178 | 13 / 20 / 45 / 84 / 144 / 186 / 209 |
| local contrast (25 px std) | 19.5 | 13.8 | 17.2 |
| pixels under 20 | 5.2% | 0.0% | 4.8% |
| mean saturation | 95 | 92 | 113 (then lowered the finish's saturation from 1.15 to 1.08) |

Two scenes is a thin basis for the targets: the 75th percentile, for example, differs between them (127 and 165) and is deliberately not anchored. More Google-app pairs would tighten them. The local tone mapping, grain and adaptive curve exist only on the GPU path; the CPU fallback renderer applies the fixed look without them.

## Faces (2026-10-28)

The camera reports faces (`STATISTICS_FACES`; the 10a offers off, simple and full modes), asked for in the preview and in every burst request.

- **Focus and metering:** when the shutter is pressed and a face with score 60 or more is in view, the focus scan and the metering go to the largest face (its box grown by 10%) instead of the middle of the frame; with no face the scan is aimed at the middle, as before; a tap on the screen still wins.
- **Tone:** the face's own brightness (every pixel in its box in the toned picture, before the adaptive curve) is measured, and if the curve would put it below 0.27 or above 0.72 of white the curve is bent there, fading out around it, to bring it to those limits. A face already between them is left exactly as the curve has it: skin tones are not evened out, because pushing every face to one brightness washes out dark skin. A very dark face can only be lifted as far as the curve's slope limit allows (it cannot be steeper than 2), so one under about 0.13 reaches the floor only partly.
- **Dataset:** each frame's face boxes are saved in `meta.json`.
- **Tested:** the choice of face, box growth and scaling, and the curve adjustment, with unit tests; a burst without a face on the phone (no change, 2.1 s). **Not yet tested with a real face in view**, so the focus-on-face path and the log lines `face found` and `face brightness ... curve ...` have not been seen.

## The burst as the default shot, and Ultra HDR from it (2026-10-28)

- **Default.** The computational burst is now the normal shot (the BURST chip turns it off for a quick single frame from the camera). A shot saves one file, `HOLTZ_<time>.jpg`; the RAW chip adds the merged `.dng`; the probe sheet has a toggle that also saves and renders the unmerged reference frame (`_ref.jpg`, `_ref.dng`) for judging the merge. Skipping the reference render saves about 0.15 s. Phone timing, one JPEG: render 0.35 s, whole shot 1.45 to 1.75 s (1.45 s without Ultra HDR, 1.75 s with it).
- **Ultra HDR from the merged frame.** The merged mosaic is the scene's linear light and the finished picture is its SDR rendering, so the gain map is built the same way as for single RAW+JPEG shots (`IxGainMap`: the brightness the tone curve compressed in bright skies and lamps) and attached to the bitmap; compressing it as a JPEG writes the Ultra HDR file. The HDR chip is now on by default and works with the burst. Checked on the phone: the gain map is attached, the saved file decodes with `hasGainmap()` true after the orientation tag is written, and the file has the `hdrgm` XMP, the multi-picture index and two JPEG streams (2.6 MB against 2.3 MB). It falls back to a plain JPEG on any failure, or when the scene has no highlight detail to recover. Not yet looked at on an HDR display or in another viewer.

## Shake-aware exposure (2026-10-28)

The merge aligns frames to each other but cannot undo blur inside a frame, so in dim light with a shaky hand a long exposure is worse than shorter, noisier frames (which the merge averages). The gyroscope gives the phone's turning rate (`ShakeMonitor`: root mean square over about a quarter of a second). A turn of `rate` rad/s moves the picture `rate x focal length in pixels` pixels a second (focal length 4.53 mm over the 1.6 um pitch is about 2,830 px), so the longest exposure for at most 1.5 px of blur is `1.5 / (rate x 2830)` seconds, kept between 4 ms and 250 ms. An ordinary burst whose automatic exposure is longer than that is cut to it and the ISO raised in proportion (capped at 3200, where the noise model was measured); if the ISO tops out, the exposure grows back just enough to keep the brightness: a shaky dark scene gets some blur rather than a dark picture. The merge's noise parameters follow automatically from the reference frame's ISO. Fixed-exposure captures (the dataset's clean reference and ISO ladder) are not touched, and the dataset's `meta.json` records the shake rate at the start.

Checked: the arithmetic with unit tests; on the phone the gyro reads (0.0035 rad/s sitting on a desk, which allows 150 ms, so nothing changed and no frame was altered). **Not checked in the hand**: whether 1.5 px is the right blur budget on a phone with optical stabilisation (which removes part of the shake the gyro sees), and how much the cut helps in a real dim, handheld scene. The budget is one constant (`maxBlurPx`) to tune from handheld bursts.

## Frame weighting and the frequency-domain merge (2026-10-28, host-tested, not yet seen on the phone)

Questions: would an HDR+-style frequency-domain merge (each tile's spectrum shrunk toward the reference by a Wiener gain) beat our spatial merge, and does dithering help? (`tools/prototypes/fm.py`.)

**Dithering: not needed.** The histogram of our pictures has no empty levels between 16 and 240 in any channel (Google's is the same); the grain already acts as dither. Revisit only if a smooth-sky scene bands.

**Merge test:** six frames of known pictures with noise from the measured ISO 800 model, merged, demosaiced, and scored (PSNR) against the noise-free picture:

| scene | plain mean | our merge before | frequency-domain merge | **flat-top weights (shipped)** |
|---|---|---|---|---|
| frames perfectly still | 33.04 | 32.05 | 32.10 | **33.03** |
| shaken by up to 1 px | 32.34 | 31.41 | 31.89 | **32.42** |
| shaken, plus a patch that moved 6 px | 29.35 (ghosts) | 31.14 | 31.56 | **31.57** |

The finding is not the one expected. The frequency-domain merge is worth less than a change to one line of our own: the old weight `exp(-0.5 d^2 / (tol^2 var/9 + 1))` with tolerance 3 is in effect a one-deviation rule, so ordinary noise alone already pulls a frame's weight down (average about 0.7) and costs a full dB on a still scene. The new weight is 1 up to 2.5 noise deviations of the cell-sum difference, falls smoothly to 0 at 4.5, and so gives pure noise full say while a real difference (a moving patch) is still rejected. It matches the plain mean on still frames, gains 1 dB over before in both still and shaken cases, and keeps the ghost rejection (which plain averaging loses). It beats the frequency-domain merge in all three, at none of its cost. A unit test (six still noisy frames must reduce noise by at least 2.3x; the ideal is 2.45x; the old rule fails it) pins this.

Changed in the CPU merge (`IxMergeParams.fullTrust`/`noTrust`, replacing `tolerance`) and in the GPU accumulate shader; the emulator test that compares the GPU merge with the CPU engine passes. On the phone the reported "mean frames per cell" (about 5.98 of 6) should now be near 6 and noise a little lower: to be checked. If real noise runs above the measured model, a frame's difference would read as larger than it is and the rule would reject more; the 2.5-deviation margin allows the noise to be about 1.5 times the model before that shows.

## Clipped highlights render white (2026-10-28, host and emulator tested)

On the factory pictures the ceiling lamps came out pinkish where the Pixel app's are white. Cause: a lamp clips the sensor (green first); white balance then multiplies red and blue by gains above 1 (1.7 and 1.9 here) while the clipped green stays where it clipped, so the blown area is magenta. Fix: where a pixel's raw signal is within 8% of the sensor's limit its true colour is unknown, so the white-balanced channels are eased toward the brightest of the three (full effect at the limit, none below 0.92). Tests: a fully clipped mosaic renders neutral (it renders magenta without the rule: the test fails then) and a saturated unclipped red stays red; the same on the GPU path on the emulator. Not yet seen on the phone: whether the lamps now match Google's, and whether the ramp (0.92 to 1.0) leaves a visible edge around a lamp.

**Not done, with reasons.** *Variance-stabilising transform before the spatial denoise:* the denoise already sets its tolerance from the signal-dependent variance of each cell (shot x signal + read), which is what the transform buys, so nothing to gain. *DNG colour matrices:* the as-shot white balance, not the matrix, is what differs from the Pixel app's warmer look (its wall is about 6% less blue); a DNG matrix keeps the camera's own white balance and would not change that, so it is not the fix; a deliberate warmth bias, tuned on more Google pairs, would be.

**Tone map halos: checked, none seen.** A ceiling lamp against a dark surround in a same-scene pair (Pixel app against Holtzman before the highlight rule) shows no halo or glow band around the lamp from the local tone mapping; the lamp itself was a flat, slightly pink white (the cause of the highlight rule above), against the Pixel app's brighter core with a bluish rim. One lamp in one picture, not a survey.

## The TPU is reachable through NNAPI (2026-10-28, measured on the phone)

LiteRT's own route to the TPU (compiling on the device through the Edge TPU app service) is refused for apps that are not on Google's list (error 16, above). NNAPI is a different door. The phone has an NNAPI driver for the TPU, `android.hardware.neuralnetworks.IDevice/google-edgetpu` (service `hal_neuralnetworks_darwinn`, running), next to the Edge TPU app service that refused us. The classic TensorFlow Lite runtime (2.16.1) still carries the NNAPI delegate; `tools/nnapi-probe` is a small app that runs our two test models (a 7-layer, 32-channel convolutional network over 1024 x 1024) through it:

| path | int8 model | fp16-weights model |
|---|---|---|
| CPU, 4 threads (classic TFLite) | 1247 ms | 3437 ms |
| NNAPI pinned to `google-edgetpu`, no CPU fallback | **7 ms** | **10 ms** |
| NNAPI default device choice | 7 ms | 10 ms |
| (LiteRT GPU, from before) | 37 ms | 38 ms |

Output digests: int8 CPU 11690.373, NNAPI 11686.154; fp16 CPU 11857.361, NNAPI 11852.181: close (0.04%) but not equal, which is what different arithmetic on different hardware looks like, and the timing is two orders of magnitude from the CPU's, so this is the TPU and not a silent fall-back (the LiteRT NPU attempt returned bit-identical output at CPU speed). About 5 times faster than the GPU for the same network, and the TPU is far kinder to the battery.

**Caveats.** NNAPI was deprecated in Android 15; it is present and working on this phone's Android 17, but it may go, and nothing guarantees other builds keep the TPU driver. One synthetic network of plain convolutions: a real model with other operations may be only partly supported (the driver's supported-operation list is the limit; the delegate pinned to one device leaves unsupported operations to the CPU). The timing is a warm run after one build/compile pass (first run about 110 ms, build 0.4 to 1 s). Whether the camera's own use of the TPU competes with ours is untested. This says nothing yet about what to run on it: a learned denoiser is the obvious candidate and needs a trained model.

## Real handheld bursts through the merge engine (2026-10-28, ten scenes from the dataset mode)

Ten handheld bursts (indoor, 6 frames each, ISO 29 to 434, exposures 4 to 11 ms, hand-shake 0.012 to 0.092 rad/s, tagged backlit/text/texture; no faces and no outdoor scenes) run through the Kotlin merge on the Mac (`./gradlew :ix:harness -Pdata=...`, `tools/prototypes/dng_to_raw.py`). Noise reduction of the merged mosaic against the reference frame (ideal for 6 frames is 2.45; the estimator is a median of integer filter responses, so the figures come in steps), and frames taken per cell:

| frame weights | noise reduction over the ten scenes | frames per cell |
|---|---|---|
| the old rule (full weight at 0, none at 3 noise deviations: the one-deviation behaviour) | 1.86 to 2.50, mean 2.0 | 5.64 to 5.97 |
| shipped (full to 2.5, none at 4.5) | 2.00 to 2.50, mean 2.3 | 5.82 to 5.99 |
| lenient (full to 3.5, none at 6) | 2.00 to 2.50, mean 2.3 | 5.89 to 6.00 |
| shipped weights, noise model from the camera's own per-frame profile | the same as our ISO model within one step | the same |

So the new weighting is confirmed on real handheld frames, not only synthetic ones: the old rule is about 15% worse on average. The camera's own noise profile (S, O per frame, converted by the sensor range) gives the same merge as our fitted ISO model, which validates the model; there is no reason to switch. The one scene where a lenient rule did visibly better (ISO 434, backlit, text) does the same with the camera's profile, so the cause is the scene (real misalignment or clipped highlights making the frames disagree), not a wrong noise level. Alignment on real hands: 42 to 94% of tiles move, mean shift 0.7 to 5.8 cells (2 sensor pixels each), at most 21.6% of tiles needing 12 or more cells of shift (the most-shaken scene); the engine's search range covers this. Merge time on the Mac, 12 MP, six frames: about 0.46 to 0.5 s.

**Shake-aware exposure on these scenes:** it would not have changed any of them. The exposures (4 to 11 ms) are all at or below the limit the gyro rate gives (about 5.8 ms at the worst shake, 0.092 rad/s, against a 5.0 ms exposure). It only matters in dim light, which the ten scenes do not cover.

## The blocky kitchen photo, and what it taught (2026-10-28)

A handheld photo of a wall of saturated red, orange and yellow panels (portrait, 14:00, after the new frame weights and the clipped-highlight rule) showed irregular rectangular colour patches and speckle in the deep red and on a pale blue bag. Findings so far:

- **Not the merge.** The same handheld frames (a dataset burst of a similar panel wall, ISO 29) merged by the old rule and by the shipped rule are both smooth, and so are the same frames with extra noise added to match ISO 3200 (`./gradlew :ix:harness -PisoNoise=3200 -Pdump=1`, `tools/prototypes/look.py`). The old rule, the shipped rule and a lenient rule all give uniform noise, not blocks.
- **Not a pixel grid.** The patches have no fixed period (autocorrelation of the chroma steps puts them at 55 to 90 px, spread), so not the 32 px alignment tile, the 16 px tone-map grid, the 8 px JPEG block or the 8 px gain-map cell. They read as blotchy low-frequency colour noise, which points at the denoise and finish stages working at a high ISO in deeply saturated colour (where the colour matrix's negative terms and clipping at zero turn channel noise into speckle), not at a geometry bug.
- **Same scene, switches off one at a time, static on a desk** (`--es dbg oldweights|noraw|noclip|noltm|noauto|nofinish`): all variants but one looked clean, so the problem needs the handheld conditions of the original shot. The one failure was real: **with local tone mapping off, the picture came out entirely black on the phone** (an unbound shader buffer; the emulator did not mind). Fixed, and that fallback path now works.
- To catch it next time: Settings in the probe sheet now has "Keep the raw burst with every photo" (a switch `--es dbg keepraw` does the same): each normal photo then leaves its six DNG frames and metadata in `Documents/Holtzman/Dataset/photo_<name>`, so a strange picture can be re-run frame for frame.
- Also learned: the Pixel 10a's sensor is **GRBG** (the DNG's CFAPattern is 1 0 2 1), not RGGB. The app reads the pattern from the camera, so the pictures are right; my host-side tools had assumed RGGB until corrected.

## Dataset mode: "the photos are not saving" (2026-10-28)

Inspected on the phone: nothing was failing. Between 14:10 and 14:16 the app had made exactly three saves, all complete (frames and `meta.json`): a normal photo that also kept its raw burst, and two data handheld bursts. No crash, no partial or hidden files, nothing in the log. What the user saw was a mode that gave no sign of working: DNG frames have no thumbnail in the Files app and are not in the gallery (they are in Documents, not Pictures), the "Saved" message vanished after 3.5 s, nothing counted what had been saved, and a debug switch left on from a test (`keepraw`) was making an ordinary photo create a 140 MB `photo_...` folder as well. Changes: every scene now gets a `preview.jpg` (rendered from its first frame, upright, a third of full size); the DATA chip counts saves this session and the message names the folder and stays 8 s; debug switches lapse ten minutes after they are set. Scenes saved before this have no preview.
