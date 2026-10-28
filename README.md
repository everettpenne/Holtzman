# Holtzman

A camera for the [Glass](https://github.com/everettpenne/Glass) suite, for the Google Pixel 10a first. Not a fork: a new app on Camera2 and Glass, with the imaging engine **Ix** (`:ix`, Android-free, carried over from the fork experiment).

**Status: first slice.** Camera2 preview and still capture at the largest 4:3 size, JPEG plus DNG when the camera has RAW, saved through MediaStore to `Pictures/Holtzman` (pending until complete, deleted on failure), the other lens, and a **camera probe** that reports what the phone's camera HAL offers a third-party app. Nothing here has run on a real Pixel yet.

## The first thing to do on the Pixel 10a

The plan depends on facts only the phone can give. Install the debug build, open the probe (the chart icon, top left), tap **Save the report** (it goes to Downloads), and tap **Take a comparison pair** (one default photo, one with every processing stage at high quality). Then shoot the same scene with the stock camera. The report answers: is there RAW and Ultra HDR output, are there Night/HDR extensions, how deep can a burst go, is reprocessing offered, what are the lenses.

## Layout

```
ix/                  imaging engine contract + pass-through skeleton (JVM, 27 tests)
app/camera/          CameraController (Camera2), CameraSpec (pure decisions: sizes, orientation, naming)
app/save/            PhotoSaver (MediaStore, DngCreator)
app/probe/           what the camera says about itself, and how it is written down
app/ui/              Glass-themed Compose screens
```

## Build

```bash
./gradlew :ix:test :app:testDebugUnitTest   # JVM tests
./gradlew :app:installDebug                 # needs ../Glass beside this folder
```

JDK 21, Android SDK 36, Android 13+ (the Pixel 10a runs 16). Release signing comes from `HOLTZMAN_RELEASE_*` environment variables.

## Permissions

The camera. No location, no storage permission, no network, no microphone (video will ask in context).

## Licence

MIT. See [LICENSE](LICENSE).
