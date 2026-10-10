# XMAX Fork Changelog

This changelog records **fork-specific stable milestones** for the Yamaha XMAX / Pixel line.

このファイルは XMAX fork 固有の安定版・実運用上の変更だけを記録します。

The upstream Pillion changelog remains in `CHANGELOG.md` unchanged. Historical experiment-by-experiment notes before the Git baseline are intentionally not reconstructed here; use Git history and the `xmax-v34` tag when archaeology is needed.

## [Unreleased]

No stable fork release has been recorded after `xmax-v34` yet.

## [xmax-v34] - 2026-10-09

Initial Git baseline of the field-tested XMAX / Pixel fork.

Tag: `xmax-v34`  
Baseline commit: `4d994e3`

### Added

- Dedicated Android Virtual Display workflow for the XMAX NaviLite dashboard.
- Fixed 480 × 234 final output with configurable left/bottom margins, margin color, and 1.0x–3.0x VD scaling.
- App PROMOTE / DEMOTE handling around phone lock/screen state, including prohibited-system-app fallback.
- Optional fixed dash-app selection.
- Optional Restart-on-VD path that removes the existing task and launches a fresh activity on the VD with frame gating.
- XMAX UP/DOWN stick zoom integration with configurable percentage tap coordinates and temporary tap markers.
- Two-area OCR using bundled Tesseract data on an isolated helper side channel.
- OCR formatting for arrival time/distance and map-scale distance text.
- Diagnostic flight recorder and diagnostic export.
- Pixel SecureCamera detection with physical-panel restore only.
- Reset & Restart and Save & Restart flows with helper-assisted process relaunch.
- Start-mirroring duplicate/race suppression.
- RestartHelper fallback for cases where the main DashServer helper is unavailable.

### Changed / stabilized

- Unexpected Bluetooth/RFCOMM/NaviLite loss keeps the display/capture infrastructure alive and rebuilds only the transport/session.
- Reconnect no longer depends on receiving a Bluetooth ACL_CONNECTED event.
- Bluetooth disconnect does not trigger a normal DEMOTE.
- Current transport reconnect retry is 1500 ms.
- Helper prepare timeout for one-time setup is 20 s.
- Notification/service behavior was adjusted for long-running reconnect and explicit Stop handling.
- Yahoo! Car Navigation rendering stability can use the separate Restart-on-VD path rather than moving a live renderer through the display transition.

### Current code-verified values

For the detailed current source-of-truth summary, see `XMAX-CURRENT.md`.

Notable values at this baseline include:

- Android clean-install JPEG quality: 80
- VD density: 240 dpi
- Max FPS default: 3; selectable 1–15 whole fps
- VD scale default: 2.0x
- Left / bottom margin defaults: 50 px / 16 px
- Stick defaults: Zoom In 93/47, Zoom Out 93/74
- OCR area 1: 30/89/70/100
- OCR area 2: 88/56/98/64
- OCR area 1 cadence: 10 s
- OCR area 2 cadence: 1 s
- Reconnect retry: 1500 ms
- SecureCamera poll / settle: 250 ms / 500 ms

### Validation

Before the `xmax-v34` tag was created, the imported v34 tree completed a Windows Android build with:

```powershell
.\gradlew.bat assembleDebug
```

The fork is primarily validated in real use with Yamaha XMAX + Google Pixel 10a + Yahoo! Car Navigation.

### Historical note

Older root-level `RECONNECT_*_NOTES`, `STICK_*_NOTES`, `V25_CHANGES.txt`, and similar experiment files described intermediate test builds and contain values that no longer match the final v34 code. They are preserved by Git history / the `xmax-v34` tag rather than kept as current documentation.
