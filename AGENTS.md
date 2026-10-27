# Test-base workbench rules

This branch starts at upstream-identical fork `main`. Do not merge the test instrumentation into `main` or `xmax-pillion`.
- Keep runtime behavior upstream-original aside from telemetry and the two patched libadb-android 3.1.1 synchronization fixes.
- Read `third_party/libadb-android/PATCHES.md` before changing ADB code. Preserve licenses.
- Flight recorder contains only state breadcrumbs, never pairing codes, tokens or raw screen frames.
- Develop candidate fixes on separate `test/*` branches made from this base.
- Create upstream PR branches from clean `main`, transplanting only the actual fix (no telemetry, ADB vendor or CI).
- Run `./gradlew :composeApp:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug` and field-test the diagnostic export.
