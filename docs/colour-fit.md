# Fitting our colour to the Pixel Camera app (2026-11-02)

Tool: `tools/look/fit_colour.py` (`build`, `analyze`, `fit`, `split`). Data: the dataset's Pixel pairs; outputs go to `~/Code/holtzman-dataset/colour_fit/` (off GitHub: the contact sheets show people and places).

## Method
Each pair (our `holtzman.jpg`, the Pixel Camera app's photo of the same moment) is registered with an affine ECC fit on local-contrast-normalised luma, blurred, and sampled only in the flattest half of both pictures, so a leftover misalignment of an edge teaches the fit nothing. Fits are scored by CIEDE2000 on **held-out scenes** (5-fold, whole scenes held out). Only scenes rendered by one app version and look (0.5.1, saturation 0.9) are used together: 52 of 59 scenes with a full-size `holtzman.jpg` registered cleanly (cc >= 0.80). The 154 older pairs only have capture-time previews from older looks and are left out (`--all` includes them).

## What differs from the Pixel (52 scenes, flat-area pixels)
- Colour difference now: median dE 7.65, mean 9.16, p90 17.5. A dE of 2 to 3 is barely visible; 5 to 8 is plainly different.
- **Our chroma is higher than the Pixel's, not lower**: the Pixel's chroma is about 0.7x ours overall, 0.6x for orange, yellow and yellow-green, 0.7-0.8x violet and magenta, about 1.0x for blue. Skin tones are the clearest case: ours renders skin red and harsh, the Pixel's is paler and peach.
- **The Pixel is lighter in the midtones**: +7 to +9 L in the 35-65 band, about 0 at the ends (our shadows and highlights match, the middle is darker).
- Near-neutral colours match: no overall white-balance bias.
- The "pop" you see in the Pixel app is therefore not extra saturation in flat colour. It is brighter, cleaner mids and whites, deeper local shadows, and calmer skin and foliage colour.

## What a fit can and cannot do (held-out median dE)
| model | median | mean | p90 |
|---|---|---|---|
| no change | 7.65 | 9.16 | 17.5 |
| one lightness curve (tone) | 7.38 | 8.36 | 15.0 |
| chroma by lightness and hue (colour) | 6.38 | 8.04 | 16.0 |
| tone then colour | 6.22 | 7.21 | 13.0 |
| 17^3 3D LUT, smoothness 30 | 5.39 | 6.76 | 14.5 |
| oracle: each scene's own lightness shift | 5.11 | | |

- The 3D LUT is the best score but **should not ship as is**: on an overcast outdoor scene it brightens the sky and darkens the grass, which amplifies sky noise into a blotchy pattern (see `colour_fit/contact/*.jpg`, left ours, middle fitted, right Pixel). It does clean up the skin scene very well.
- The biggest remaining error is **per scene**: removing only each scene's lightness offset (not knowable by the app) takes the tone-then-colour model from 6.2 to 5.1. In two overcast, low-contrast scenes the Pixel is 21 to 22 lightness points brighter than we are (sky near white, ours grey-violet): that is exposure and local tone mapping (HDR+ style), not colour.

## Direction
1. Fix tone first, per scene: an exposure/tone target that depends on the scene (low-contrast overcast scenes need a much brighter sky and mids), and local tone mapping that keeps the sky bright while letting shadows stay deep. Fit it against the 213 pairs by statistics (they need no alignment, as `fit_look.py` does), not by a colour table.
2. Then a small, smooth colour stage: chroma by lightness and hue (orange/yellow/yellow-green about 0.6x, violet/magenta 0.7-0.8x) with skin protection, applied in lightness-chroma-hue space, not in RGB.
3. Only consider a 3D LUT as the final stage if it is smooth, monotone in lightness and checked for the sky artefact; keep `pixel_look.cube` as a reference, not as a shipped asset.
4. Open: only 52 scenes at one look; outdoor scenes with people and bright sun are thin.

## Shipped 2026-11-02 (local commits, not released)
- Scene-adaptive tone targets (`IxSceneTone`, blended 0.7 with the fixed row), shadow colour fade judged before the exposure gain, quantisation floor in the finish.
- Hue-dependent chroma (`IxLook.HUE_CHROMA`, `hueChroma`, switch `nohue`): multipliers 0.62-0.68 for orange/yellow/yellow-green, 0.8-0.88 violet/magenta, 1.0 blue. On the bench scene mean saturation fell from 0.191 to 0.144 with the blue handles unchanged. Not yet judged against the Pixel app on skin or outdoor scenes.
- Noise filtering off by default (finish denoise 0, TPU denoiser behind `tpu`).
