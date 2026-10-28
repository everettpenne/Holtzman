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
