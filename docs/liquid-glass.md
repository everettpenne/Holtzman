# Liquid Glass experiment (branch `liquid-glass`, 2026-11-02)

**Goal.** Draw the camera's controls the way iOS 26's Camera does: frosted, lensing glass with a lit rim, squircle corners, controls that swell under the finger. A setting (Settings > Camera > Liquid Glass) turns it off, so the flat look is kept to compare.

**What iOS 26's Camera does, and what here matches it.**
- Controls are glass, not flat grey fills: a blurred, saturated copy of what is behind, bent at the edge by a lens, with a thin bright rim lit from the upper left. Matched with the Glass kit (`GlassCircleButton`, `frostedGlass`, `glassSurface`, over the Backdrop library).
- Sheets and cards are larger glass panels with a soft shadow. Matched: the controls sheet, the dial cards, the first-run tip, the status pill.
- Controls swell and drift under the finger. Matched: `GlassCircleButton` does it.
- Squircle (continuous) corners. Matched where it can be: the Backdrop lens only works on circular rounded rectangles, so glass surfaces keep `RoundedCornerShape` with the iOS radii; the photo thumbnail, which is not glass, uses the Glass kit's `SquircleShape`.
- Not matched: iOS 26 has the viewfinder reach under the controls; here the 4:3 picture is letterboxed as before, so controls on the black bars have nothing to refract and read as dark discs with a rim.

**How it works.** The Glass kit samples a Compose layer, and the viewfinder is a `TextureView` that no layer can record. `LiquidCamera.kt` reads a 96 x 128 copy of the live picture back about ten times a second, draws it into a layer of its own at the viewfinder's place (under the real viewfinder, so never seen), and provides that layer as `LocalGlassBackdrop`. Every glass piece samples it. Cost: one small `getBitmap` per 100 ms, only while the setting is on and the camera is showing.

**Seen (Android emulator, 2026-11-02; not on the phone, which was disconnected):** the sheet frosting and bending the picture behind it, the tip card, the status pill and the zoom pill as glass with a lit rim and lens; buttons over the black bars as dark discs with a rim. **Not seen:** the flat/glass comparison on the phone, the top bar's flash and night buttons, the readout, the dial cards, the press swell on a real finger, performance (frame rate, heat) on the Pixel 10a.

## Round two: Apple's own guidance (2026-11-03)

Sources: coverage of the iOS 26 Camera ([MacRumors guide](https://www.macrumors.com/guide/ios-26-camera-app)), Apple's Liquid Glass guidance as summarised by [Create with Swift](https://www.createwithswift.com/exploring-a-new-visual-language-liquid-glass/) and [Wikipedia](https://en.wikipedia.org/wiki/Liquid_Glass). Apple's own pages are script-rendered and could not be read directly, so the rules below are second-hand and should be checked against developer.apple.com before leaning on them.

| What Apple does or advises | What the app does now |
|---|---|
| The bar behind the lens (zoom) buttons was removed | The glass pill is gone; only the selected zoom is a small glass bubble |
| The shutter has a glass ring, not a bright white one | A glass ring with a thin white edge around the white disc |
| The photo-review button is round | Round in glass mode |
| Tapping the active mode (Photo) opens the glass menu | Tapping the lit PHOTO or VIDEO opens the controls sheet |
| The area behind the controls is more transparent: more of the frame shows past the picture | The bars around the picture show the live frame as a soft dim glow (also gives the glass something to bend) |
| Avoid glass on glass | The sheet's buttons are flat translucent discs inside the glass sheet |
| Prefer the regular (frosted) material where text must stay legible | The readout and status pill use a darker, blurrier glass than the plain buttons |
| Sheets morph out of the button that opens them | The controls sheet grows from the six-dots corner with the kit's opening spring |
| Controls give a light haptic | Round controls tick on press |
| Reduce Transparency / Reduce Motion calm the material | High-contrast text or animations off returns the camera to the flat look |
| Tint only for primary or meaningful actions | Unchanged: yellow means on or manual, nothing else is tinted |

Not done: a top-right menu button (the six dots at the bottom stay), swiping up for settings (Apple's coverage does not clearly describe it), concentric-radius audit of every nested shape, and the viewfinder reaching under the controls (the picture is still a letterboxed 4:3).

## 2026-11-03: glass removed, solid cards kept
The glass was blotchy (a 72 x 96 copy of the viewfinder, stretched and lensed) and jittery (the copy updated in steps, about 8 times a second), and it cost GPU and CPU every frame. A neural accelerator (the TPU) cannot render it: it runs networks, not render effects. So the controls are solid dark cards in the iOS 26 shapes (round buttons that swell under the finger, rounded cards, a sheet that opens out of its button). The viewfinder read-back and the Backdrop sampling are gone. The setting is now "iOS 26 style controls" (on by default; off gives the flat 0.7.1 look).
