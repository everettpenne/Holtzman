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
