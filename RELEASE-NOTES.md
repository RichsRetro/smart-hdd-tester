# Smart HDD Tester + Fake Card Detector 0.1.1 — testing build

This is a bug-fix testing release of the separate Fake Card Edition app.

## Changes in 0.1.1
- Full-test setup markers now use fixed 8, 16, 32, 64 GB and later checkpoints rather than fractions of device-reported capacity. A drive falsely reporting 1 TB will no longer make the initial probe jump to about 132 GB.
- Early setup failures now explain that the app checked only a tiny 512-byte marker, show the checkpoint, clarify that the full write/read pass had not started, and explain why it stopped.
- A failed marker readback is not reported as confirmed fake capacity unless the app finds an intact pattern belonging to a different logical address.
- The existing sequential write/read test and aliasing logic are otherwise unchanged.

## Safety and compatibility
- Android 9 (API 28) or newer is required.
- Debug/testing APK, not production signed.
- The destructive full and quick fake-card tests overwrite data on the selected USB card. Back up first.
- Tests USB storage only; internal NAND and the built-in SD slot are not accessed.
- Reader vendor/model/serial are metadata only.
- Reports are not saved to internal storage.
- The APK is signed with the same debug key as v0.1.0 and can update that build.

See `README.md`, `LICENSE`, and `THIRD_PARTY_NOTICES.md` for more information.
