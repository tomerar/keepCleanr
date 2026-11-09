# G1b: JPEG orientation platform check (preparation)

This test-only increment generates a four-color JPEG in memory and inserts a minimal EXIF orientation for each of the eight orientation values. The API 30/36 instrumented test checks output dimensions and corner positions from Android `ImageDecoder`; it helps prevent applying orientation twice in a later bounded JPEG decoder. No personal images or external network are involved.

Local `:core:gallery-android:assembleDebugAndroidTest :testing:architecture:test spotlessCheck` passed. The test has not yet run on an emulator or physical device. It does not implement JPEG support, source authorization, motion-photo rejection, incomplete-image handling, cache or visual-accuracy evaluation. The fixture's EXIF encoding and expected corner mappings were independently checked for all eight values using Pillow; that is not an Android decoding result. CI and review remain pending.
