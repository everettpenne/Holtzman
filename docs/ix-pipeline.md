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
