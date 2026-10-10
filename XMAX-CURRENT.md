# XMAX Fork — Current Implementation

This file is the current-state reference for the XMAX / Pixel fork.

このファイルは XMAX / Pixel fork の「現在どうなっているか」を確認するための基準メモです。

The values and behavior below were checked against the actual `xmax-pillion` source after the `xmax-v34` baseline. The `xmax-v34` tag points to commit `4d994e3`; the later README commit did not change implementation code.

If this file and the source ever disagree, **the source code wins**.

---

## Target / 主な実機環境

- Yamaha XMAX with Garmin StreetCross / NaviLite-compatible dashboard
- Google Pixel 10a
- Yahoo! Car Navigation / Yahoo!カーナビ
- Android dedicated Virtual Display path

Other devices, bikes, and navigation apps may work, but this fork is primarily tuned and field-tested for the combination above.

---

## Android code-defined defaults / Android側の初期値

The authoritative Android defaults are in `AndroidSettingsStore.kt`.

| Setting | Current Android default |
|---|---:|
| JPEG quality | 80 |
| VD density | 240 dpi |
| Max FPS | 3 fps |
| VD scale | 2.0x |
| Left margin | 50 px |
| Bottom margin | 16 px |
| Margin color | Black |
| Zoom in tap | H 93% / V 47% |
| Zoom out tap | H 93% / V 74% |
| OCR | Off |
| Show OCR area | Off |
| OCR area 1 | L30 / T89 / R70 / B100 |
| OCR area 2 | L88 / T56 / R98 / B64 |
| Restart app on VD | Off |
| Fixed dash app | Off |

Max FPS is normalized to **whole values from 1 through 15 fps**.

VD scale choices are **1.0x through 3.0x in 0.2x steps**.

Important code-maintenance note: some common fallback/default values in `MirrorSettings.kt` and UI fallback expressions are older than the Android values above. On Android, `AndroidSettingsStore` is the persisted-settings authority. Do not use the stale common fallbacks as documentation for a clean Android install.

---

## Rendering / XMAX出力

- Final NaviLite image size is fixed at **480 × 234**.
- The usable image area is `(480 - leftMargin) × (234 - bottomMargin)`.
- The dedicated VD size is derived from that usable area and the selected 1.0x–3.0x scale.
- The full VD image is downscaled into the usable output region.
- Unused space is on the **left and bottom**, so the navigation image is effectively anchored to the **top-right** of the 480 × 234 output.
- Margin color is configurable; Black is the Android default.

Changing VD size/DPI while Yahoo! Car Navigation is already alive can produce partial rendering/DPI oddities. The existing Restart-on-VD path exists specifically to avoid carrying a live task through that transition when the option is enabled.

---

## PROMOTE / DEMOTE and lock behavior

Dedicated-dash mode promotes an app to the helper-owned VD when the phone transitions into the lock/screen-off path, and returns it to display 0 on the corresponding return/unlock path.

Important current behavior:

- A short settle window after PROMOTE ignores the helper-induced screen transition.
- A later physical screen-off while already promoted is treated as a request to return the app to the phone.
- Keyguard/SystemUI is treated as transient so it does not replace the last real dash target.
- Settings, SystemUI, permission-controller, launcher/default-home targets are prohibited promotion targets and fall back to Pillion.
- Optional fixed-app mode never falls through to an arbitrary foreground app; missing/prohibited/unavailable fixed targets fall back to Pillion.

These transitions are timing-sensitive and are considered stable behavior. Avoid broad rewrites unless a task specifically targets them.

---

## Restart app on VD

Restart-on-VD is optional and **off by code default**.

When enabled for a non-Pillion target:

1. The helper does **not** first relocate the live task to the VD.
2. It removes the existing task while it is still on its current/phone display.
3. It waits for task disappearance / cleanup.
4. It launches a fresh activity directly on the VD.
5. VD frame publication is gated while the app is being recreated.
6. The gate is released only after the fresh task/frame path is established.
7. Failure handling can fall back to the normal relocation path.

When Restart-on-VD is disabled, the established normal relocation path remains separate.

---

## Bluetooth / NaviLite reconnect

Unexpected RFCOMM/NaviLite loss does **not** tear down the surviving display infrastructure.

Current code behavior:

- Enter reconnect-wait and keep the service alive.
- Preserve the existing ScreenSource, MediaProjection side, helper, and VD.
- Do **not** PROMOTE/DEMOTE merely because transport disconnected.
- Rebuild a fresh RFCOMM + NaviLite/MirrorEngine transport session.
- Retry interval is currently **1500 ms**.
- Bluetooth ACL_CONNECTED is only a useful hint that can schedule a probe; reconnect does not depend on the ACL event.
- ACL_DISCONNECTED does not cancel the reconnect loop.
- Explicit Stop remains the normal full-teardown path.

Older development notes mentioned 5 s and later 2 s retry periods; those values are historical and are not the current code.

---

## XMAX stick input

Only the XMAX UP/DOWN NaviLite zoom requests are consumed.

- Service 51 / UP → configured Zoom In tap.
- Service 52 / DOWN → configured Zoom Out tap.
- Center press and other dash-side requests are deliberately ignored so the vehicle menu behavior remains untouched.
- Current Android default tap coordinates:
  - Zoom In: H 93% / V 47%
  - Zoom Out: H 93% / V 74%
- Tap percentages are relative to the usable navigation/VD logical space, not raw 480 × 234 output pixels.
- A temporary visual tap marker uses a 12 px radius and approximately 0.5 s worth of outgoing frames.

---

## OCR

OCR is optional and **off by code default**.

Current architecture:

- OCR is isolated on its own loopback side channel and does not alter the main NaviLite JPEG frame framing.
- OCR remains gated during initial connection.
- The gate opens only after the first real IMAGE_ACK plus a **5 s grace period**.
- Bundled English Tesseract data is used.
- OCR area 1 default: **L30 / T89 / R70 / B100**.
- OCR area 2 default: **L88 / T56 / R98 / B64**.
- Area 1 crop cadence: **10 s**.
- Area 2 crop cadence: **1 s**.
- Area 1 is cached between its slower updates while Area 2 continues at the faster cadence.
- Area 1 formatting is `hh:mm着・distance` when a valid time and trailing distance are parsed.
- Area 2 currently contributes the recognized distance text directly.
- Published ROAD text is currently: **area 2 + two ideographic spaces + area 1**.
- There is **no code-added ▲ prefix** in the current implementation.

Historical notes describing a single 10 s OCR loop, a 2 s loop, older area-2 coordinates, or a decorative prefix are stale.

The Tesseract model/license is part of the fork. Keep `THIRD_PARTY_TESSERACT_LICENSE.txt` and any required third-party notices.

---

## Pixel SecureCamera panel restore

The current camera assist is intentionally narrow.

- Helper watches Display 0 top activity every **250 ms**.
- It matches package `com.google.android.GoogleCamera` and a class name containing `SecureCameraActivity`.
- On first detection it waits **500 ms**, then rechecks.
- If SecureCamera is still top, pending physical-panel-OFF retries are cancelled and only the main physical panel is restored.
- This path does **not** redesign or suppress the normal PROMOTE / DEMOTE state machine.

Notification-triggered panel wake experiments and the older VD-fixed-camera experiment are not part of the current behavior.

---

## ADB / helper

- The one-time dedicated-dash setup uses the Android wireless-ADB bootstrap/pairing flow.
- Helper prepare timeout is **20 s**.
- Normal runtime helper/reconnect behavior is separate from the one-time setup coordinator.
- The helper is designed to survive normal transport reconnects.
- Restart fallback can launch the small `RestartHelper` through ADB when the main DashServer helper is unavailable.

---

## Start, Reset, Save & Restart

Start mirroring has an in-flight guard so repeated taps cannot start overlapping MediaProjection requests. The Compose fallback guard is 1.2 s when an external Android request-lifetime flag is not supplied.

Save & Restart and Reset & Restart share the same orderly shutdown/restart path.

Reset additionally clears only transient cache files. It intentionally preserves:

- settings/preferences
- permissions
- ADB identity/setup information
- diagnostic history

After service/helper cleanup and settle time, restart is delegated to the shell-side helper. If automatic restart cannot be scheduled, Pillion falls back to a manual-reopen notification/toast path and exits.

---

## Diagnostics

The fork contains:

- `DiagnosticFlightRecorder.kt` for low-volume operational breadcrumbs.
- `DiagnosticExporter.kt` for exporting a more complete diagnostic package and current saved settings.

High-frequency debug spam should not be added to the persistent flight recorder. Prefer event/state breadcrumbs that help reconstruct a failure.

---

## Known code/documentation mismatches

The following are intentionally recorded so future work does not accidentally revive old assumptions:

- Android max-FPS range is **1–15 whole fps**, not 0.5-step values.
- Current reconnect retry is **1500 ms**, not 2 s or 5 s.
- Bottom margin is represented in **pixels**; Android clean-install default is **16 px**.
- OCR area 1 is **10 s** and area 2 is **1 s** in the current helper.
- Current OCR area 2 default is **88 / 56 / 98 / 64**.
- Current default stick taps are **93/47** and **93/74**.
- Current OCR ROAD output has **no ▲ prefix**.
- `MirrorSettings.kt` still contains older fallback defaults; Android persisted defaults come from `AndroidSettingsStore.kt`.

These are documentation observations only; this cleanup intentionally does not change implementation code.

---

## Future ideas / 未実装メモ

Keep this section short. Ideas are not commitments.

- Android 14+ MediaProjection default-display configuration to force the full-screen capture choice where appropriate.
- Optional drive-mode profile for Yahoo! Car Navigation, if it proves useful in real riding.
- Review selected upstream security/compatibility changes after the current XMAX line remains stable.

Do not turn speculative ideas into implementation requirements without a separate task and validation plan.
