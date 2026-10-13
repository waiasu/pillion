# XMAX Fork Changelog

This changelog records **fork-specific stable milestones** for the Yamaha XMAX / Pixel line.

このファイルは XMAX fork 固有の安定版・実運用上の変更だけを記録します。

The upstream Pillion changelog remains in `CHANGELOG.md` unchanged. Historical experiment-by-experiment notes before the Git baseline are intentionally not reconstructed here; use Git history and the `xmax-v34` tag when archaeology is needed.

## [Unreleased]

No unreleased XMAX changes at the v36 release checkpoint.

## [v36] - 2026-10-13

Security, bootstrap reliability, Stop handling, and Auto DRIVE stick safety.

### Added / changed

- Added token authentication to local DASH frame/control and OCR side-channel connections, following the security principles in upstream `d256c58d1aa492085175afd57b0df903140792b1` while preserving XMAX protocol and reconnect behavior.
- Authenticated temporary `RestartHelper` commands.
- Moved helper DEMOTE on the Stop path off the Android main thread (IO dispatcher), fixing a potential `NetworkOnMainThreadException`.
- Bounded the `adb tcpip` stream acknowledgement wait to **2 seconds**, adapting upstream `d52f7572af4e17f32665fcd76cf5f1fd15f37914` and retaining the existing **20-second** overall helper prepare window and Retry setup flow.
- Disabled stick-generated taps while Auto DRIVE is in `CHECKING` (mode not yet known); existing NAV coordinates remain active after NAV confirmation, while DRIVE coordinates and the existing one-second post-switch guard remain unchanged.
- Updated the About link label to **XMAX Custom Fork v36** without changing Android package version metadata.

### Validation

- GitHub Actions Android debug build and unit tests passed for the v36 functional fixes.
- XMAX / Pixel 10a field use confirmed the authenticated connection/Stop flow and the CHECKING stick safety change; no regression reported before release.
- The full history of feature and fix commits is intentionally retained through a regular merge commit.

### Upstream review checkpoint

As of **2026-10-13**, upstream `alexandrevega/pillion` `main` was reviewed through commit `29497f4ea3bccc5cd40c4647f8e4d8345eeddda3` (2026-09-29). **All upstream changes judged relevant and necessary for this XMAX / Android configuration have been incorporated, adapted, or already addressed.** This does not mean every upstream commit was merged verbatim: unrelated iOS/build changes and differing strategies are intentionally not adopted. The GitHub behind count is a history comparison, not a count of unaddressed fixes.



## [v35] - 2026-10-12

Tested XMAX / Pixel fork milestone for Yahoo! Car Navigation NAV/DRIVE behavior.

Release commit: [`ea564aa`](https://github.com/waiasu/pillion/commit/ea564aa9b513f8553b002450ca34f15e6d0fd5db) (not a tag).

### Added

- Optional Auto DRIVE mode for the fixed Yahoo! Car Navigation target. After PROMOTE, two empty OCR Area 1 checks roughly 10 seconds apart trigger one simulated DRIVE button tap; valid Area 1 text confirms NAV and skips the automatic tap.
- Independent NAV and DRIVE UP/DOWN stick zoom coordinates, with a one-second input guard after the automatic DRIVE tap and the existing visual tap marker.
- Configurable upward shift of the encoded dashboard image for DRIVE mode only, default 5 px and limited by the bottom margin; NAV geometry and VD/OCR crop sizes remain unchanged.
- First-run settings preset when Yahoo! Car Navigation is installed and preferences are genuinely empty: stick taps, OCR, Restart-on-VD, fixed Yahoo! target, and Auto DRIVE enabled. Existing installations and devices without Yahoo! Navi are not silently migrated.
- An XMAX Custom Fork v35 link in the About section.

### Changed / stabilized

- Clear ROAD overlay text in DRIVE mode (`DRIVE MODE`) instead of showing navigation arrival/scale OCR output there.
- Added configurable Auto DRIVE tap position (default H7/V54) and DRIVE stick defaults: UP H93/V13, DOWN H93/V30. Existing NAV stick defaults remain UP H93/V47 and DOWN H93/V74.
- Grouped NAV stick, DRIVE and OCR-area settings behind expandable rows to keep Settings manageable.
- DRIVE state/shift reset on DEMOTE; transport reconnect continues to preserve the existing display/session behavior.
- XMAX v35 changes were built and exercised on the Yamaha XMAX + Pixel 10a setup.

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
