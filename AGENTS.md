# Repository instructions

This is the Yamaha XMAX / Pixel fork of Pillion.

Before changing XMAX-specific behavior, read:

- `XMAX-CURRENT.md` — current implementation, verified code behavior, defaults, and stability constraints.
- `CHANGELOG-XMAX.md` — fork-specific release history.
- `README.md` — fork overview followed by the preserved upstream README.

## Source-of-truth rules

- Treat the current `xmax-pillion` source code as the final authority when documentation and code disagree.
- Keep fork-specific documentation separate from upstream documentation. Do not rewrite or delete upstream `CHANGELOG.md`, `CONTRIBUTING.md`, `SAFETY.md`, `LICENSE.md`, or upstream `docs/` merely to describe XMAX changes.
- Do not remove license or attribution files. When adding third-party code, models, assets, or libraries that require attribution or license distribution, add the required license/notice files.
- Historical experiment notes are intentionally not kept on the current branch. Use Git history or the `xmax-v34` tag when old experiments are needed.

## Stability constraints

The XMAX fork has been validated primarily on Yamaha XMAX + Google Pixel 10a + Yahoo! Car Navigation.

Unless a task explicitly requires it:

- Do not change the established PROMOTE / DEMOTE state flow.
- Do not demote merely because Bluetooth/RFCOMM disconnects.
- Preserve the existing ScreenSource/helper/VD while transport reconnects.
- Keep OCR traffic isolated from the main NaviLite JPEG frame stream.
- Keep Restart-on-VD as a separate path from the normal relocation path.
- SecureCamera handling must restore only the physical panel and must not redesign PROMOTE / DEMOTE behavior.
- Keep XMAX center-press handling untouched; only UP/DOWN zoom requests are consumed.

## Build / validation

Windows local build:

```powershell
.\gradlew.bat assembleDebug
```

Linux / Codex cloud build:

```bash
./gradlew assembleDebug
```

For behavior changes, prefer this order:

1. Build successfully.
2. Verify the relevant code path and logs.
3. Bench-test when possible.
4. Treat on-bike XMAX testing as the final validation for power/lock, Bluetooth reconnect, stick input, camera wake, and rendering timing.

When a stable fork milestone is reached, update `CHANGELOG-XMAX.md` and `XMAX-CURRENT.md` as needed.
