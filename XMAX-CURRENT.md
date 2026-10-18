# XMAX Fork — Current Implementation

This file is the current-state reference for the XMAX / Pixel fork.

このファイルは XMAX / Pixel fork の「現在どうなっているか」を確認するための基準メモです。

The implementation described here covers **stable v38 (2026-10-17)**, including v35 NAV/DRIVE behavior and v36 helper security/bootstrapping/Stop/stick-guard updates. The v35 release commit was `ea564aa`; the `xmax-v34` tag points to commit `4d994e3`. As always, source code takes precedence over these notes.

If this file and the source ever disagree, **the source code wins**.

---

## v38 maintenance — recovery from real sleep during PROMOTE settle (2026-10-18)

- PROMOTE retains the existing 3,000 ms guard against synthetic `SCREEN_OFF` events. A physical POWER press inside that window can nevertheless put Android to sleep and power down the VD while Pillion still thinks it is promoted.
- `CaptureService.schedulePostPromoteSleepRecoveryCheck()` schedules **one** check per PROMOTE at 3,150 ms. It only acts if the same PROMOTE is still current and dedicated VD is enabled. `PowerManager.isInteractive == false` then triggers the existing `returnToPhone("post-promote sleep recovery")` → DEMOTE → Helper `KEYCODE_WAKEUP` flow. `true` or `null` does not initiate recovery.
- Diagnostic flight recorder entry: `VD POST_PROMOTE_SLEEP_CHECK interactive=true/false/null`. The regular PROMOTE/DEMOTE transitions, Restart-on-VD, camera behavior, OCR, Bluetooth reconnect, and 3-second guard are otherwise unchanged.
- On-bike stress testing (2026-10-18): 22 PROMOTEs, 8 recovery DEMOTEs after detecting actual sleep and 14 interactive checks without intervention, including rapid POWER-button presses; user observed restored meter waiting screen and phone lock screen. XMAX fork version remains **v38**.

---

## v38 maintenance — unlock HOME and absent-task DEMOTE (2026-10-18)

- During an active dedicated VD session, unlocking the phone requests `HOME` via `CaptureService` → `SwitchableScreenSource` → `DashStreamScreenSource` → authenticated Helper. Helper uses `input keyevent 3` to return the VD to HOME. Duplicate unlock signals are suppressed within 1,500 ms. This is not a Start/Stop action and does not depend on Restart-on-VD.
- An experiment replacing the HOME key event with `am start` did not eliminate Pixel Launcher animations; the `--no-animation` variant failed. Final implementation restores the original HOME key event.
- DEMOTE first checks whether the remembered app has an existing task; only then does it call `relocateApp(component, 0)`. Without a task, it skips relocation, avoiding an unintended `am start` launch on display 0. PROMOTE, Restart-on-VD, Bluetooth reconnect and camera handling are unchanged.
- Version label remains **XMAX Custom Fork v38**. Bench/on-bike tests passed for the unlock-HOME and DEMOTE task-absence fixes before release; the final reverted HOME command matches the previously tested working version.

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
| NAV stick Zoom In | H 93% / V 47% |
| NAV stick Zoom Out | H 93% / V 74% |
| Stick tap input | Off |
| Auto DRIVE | Off |
| Auto DRIVE button tap | H 7% / V 54% |
| DRIVE stick Zoom In | H 93% / V 13% |
| DRIVE stick Zoom Out | H 93% / V 30% |
| DRIVE image shift | 5 px upward (clamped to bottom margin) |
| OCR | Off |
| Show OCR area | Off |
| OCR area 1 | L30 / T89 / R70 / B100 |
| OCR area 2 | L88 / T56 / R98 / B64 |
| Restart app on VD | Off |
| Fixed dash app | Off |

Max FPS is normalized to **whole values from 1 through 15 fps**.

VD scale choices are **1.0x through 3.0x in 0.2x steps**.

These are the **raw Android fallback defaults** before first-run initialization. In v35, if preferences are truly empty and Yahoo! Car Navigation is installed, `initializeFirstRun()` enables stick taps, OCR, Restart-on-VD, fixed-app mode targeting Yahoo! Navi, and Auto DRIVE. It does **not** automatically start casting. An upgrade or any existing saved preferences are left unchanged; a clean install without Yahoo! Navi keeps these toggles off.

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

## v38 — UI localization and text refinements

A v38 maintenance update folds the DPI, left-margin and bottom-margin sliders into a collapsed-by-default Display adjustment group, corrects Japanese `スティック機能`, and uses the compact English pairing input hint `Code / Port + Code`. This is a Settings/notification text change only, with no version increment or casting logic changes.


- Android UI language selector supports Automatic, Japanese (`ja`), English (`en`), Korean (`ko`), Spanish (`es`) and Malay (`ms`), with persisted language selection.
- Shared Compose UI resources contain 183 matching entries per locale. Japanese is the primary translation reference; English was revised to match current behavior.
- Android platform notifications retain Japanese and English resources, with English fallback for Korean, Spanish and Malay.
- Japanese persistent notification action is now `ストップ`. Wireless-ADB pairing notification reply input label is fixed to English: `Code, or port number and code`.
- The in-app About label is XMAX Custom Fork v38. VD, OCR, Auto DRIVE, Helper, Bluetooth, rendering and camera logic are unchanged.
- User verified switching languages on a test build.

---

## v37 — dedicated VD-only startup

When Android NaviLite dedicated VD is ON, Start bypasses MediaProjection consent and the phone-mirror VirtualDisplay/ImageReader. The service runs as `connectedDevice`, and `SwitchableScreenSource` begins in STATUS (`Please lock the phone`). Existing PROMOTE uses the helper-owned trusted VD, and DEMOTE returns to STATUS. VD OFF and SDL/USB retain their original capture path. A shared non-blocking Start/Settings-entry preflight checks authenticated Helper first, then local ADB at 127.0.0.1:5555 only. If both are unavailable, disable VD in saved settings, synchronize Compose UI, and use normal MediaProjection consent for the same Start tap. Settings removes the Rerun setup link and shows Checking while probing. Both preflight and Helper bootstrap accept a live existing loopback connection: libadb-android connect() returns false when already connected. A Wi-Fi-only connection is not treated as local loopback. Preflight performs no Wi-Fi/mDNS discovery or Helper spawn; existing VD lifecycle and Bluetooth reconnect mechanisms remain intact. v37 bench and XMAX field tests passed.

### Regular mirroring screen-off cleanup (v37)

When dedicated VD is OFF, the phone-mirror service subscribes to `ACTION_SCREEN_OFF`; `MediaProjection.Callback.onStop` is an independent fallback for projection revocation. The first trigger ends the normal capture service via its ordinary cleanup (notification removal, engine/socket shutdown, wake-lock release, Idle UI) without changing the VD preference. A one-shot guard suppresses duplicate stop requests and callbacks caused by intentional teardown. On unlocking, the user can tap Start again for fresh MediaProjection consent. The dedicated VD receiver and its PROMOTE/DEMOTE/reconnect behavior are untouched.

## v37 Settings and Home presentation

- Settings groups: Appearance and Motorcycle unchanged; Display & Image contains shared Image quality and Max FPS, then a VD-only subsection with DPI, Left/Bottom margin, Margin color and Map layout size. VD Features contains VD On/Off, selected app targeting, Restart app on VD, stick taps, OCR and Auto DRIVE. Maintenance contains Save & Restart and diagnostic export. About shows XMAX Custom Fork v38 (updated in v38).
- The “experimental” VD heading and old Wi-Fi-until-restart guidance are replaced with current v37 copy; the Save & Restart caption distinguishes draft settings from immediate theme/VD enable.
- The Android NaviLite Home Idle state shows a mode title, emphasized one-line Start instructions, and muted behavior notes without numbered steps. VD ON: Start then lock, automatically resume when the bike reconnects or the phone is re-locked after use. VD OFF: use landscape orientation, Start and consent, keep the screen on (lock stops mirroring); the bike may connect later in either mode. Other head-unit guides and session states/actions remain untouched. The UI follows the remembered/persisted `dashEnabled` state, including preflight disable and setup completion.

## MediaProjection consent / Android 14+

The Android 14+ whole-screen capture selection is **already implemented** for the normal NaviLite / dedicated-dash path.

- On Android 14+ (`UPSIDE_DOWN_CAKE` and later), the non-SDL path calls `MediaProjectionConfig.createConfigForDefaultDisplay()`.
- That path requests consent for the default physical display and skips the app-vs-whole-screen picker used by the generic capture flow.
- SDL/USB intentionally keeps the existing generic `createScreenCaptureIntent()` consent flow.
- Pre-Android-14 behavior is unchanged.

This is current behavior, not a future item.

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
- Current NAV stick coordinates: Zoom In H93/V47, Zoom Out H93/V74.
- When DRIVE is active: Zoom In H93/V13, Zoom Out H93/V30, configurable independently.
- Stick input can be disabled using `Stick tap input` without disabling Auto DRIVE's own tap.
- **v36:** while Auto DRIVE is in `CHECKING`, stick UP/DOWN still receives the NaviLite protocol reply but does **not** inject any app tap. This prevents NAV-position taps on Yahoo Navi's differently arranged initial/DRIVE-selection screen. Once OCR confirms NAV, NAV tap positions work normally.
- After the Auto DRIVE tap, stick input is ignored for **1 second** to avoid tapping the wrong control while Yahoo! Navi changes mode.
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
- Area 2 contributes an extracted distance when recognized.
- In NAV mode, published ROAD text is **area 2 distance + two ideographic spaces + area 1**.
- In DRIVE mode, ROAD text is **`DRIVE MODE`** instead of the NAV OCR values.
- There is **no code-added ▲ prefix** in the current implementation.

Historical notes describing a single 10 s OCR loop, a 2 s loop, older area-2 coordinates, or a decorative prefix are stale.

The Tesseract model/license is part of the fork. Keep `THIRD_PARTY_TESSERACT_LICENSE.txt` and any required third-party notices.

---

## Yahoo! Car Navigation Auto DRIVE (v35)

Auto DRIVE is optional. It is enabled in the first-run Yahoo! Navi preset, but the raw settings fallback is **Off**. The runtime gate requires all of the following: Auto DRIVE enabled, OCR enabled, Restart-on-VD enabled, and fixed dash-app mode enabled with `jp.co.yahoo.android.apps.navi` selected. Without these, ordinary NAV/fixed-app behavior remains unchanged.

- Each suitable Yahoo! Navi **PROMOTE** starts a fresh `CHECKING` decision. The behavior never modifies PROMOTE/DEMOTE or the selected VD task lifecycle.
- **v36:** physical UP/DOWN stick taps are withheld in `CHECKING` until the mode is identified. This can temporarily delay NAV zoom controls until OCR has confirmed NAV; the automatic DRIVE-switch tap uses a separate path and is not blocked.
- OCR Area 1 is checked independently at roughly **10-second intervals**, even if its crop has not changed. A valid formatted arrival/distance value confirms **NAV** and disables further Auto DRIVE decisions for that promotion.
- **Two empty Area 1 results** trigger a single virtual-display `TAP_PERCENT` at the configured DRIVE button position (Android fallback **H7/V54**). A failed tap is not endlessly retried in that promotion.
- The successful tap selects **DRIVE**. Subsequent UP/DOWN stick requests use the DRIVE-specific positions (defaults **H93/V13** and **H93/V30**) after a **1-second** settling interval. The XMAX center press remains untouched.
- An optional DRIVE-only upward output-canvas shift defaults to **5 px** and is clamped to the configured bottom margin. The VD size, OCR crops and NAV layout do not shift; DEMOTE removes the shift.
- ROAD output in DRIVE is the literal `DRIVE MODE`, suppressing NAV arrival/map-scale OCR presentation. OCR still runs on the isolated side channel.
- The DRIVE/NAV decision is re-armed on a new PROMOTE, not automatically on each Bluetooth reconnect. Diagnostic flight breadcrumbs record CHECKING, NAV confirmation, empty sample counts and DRIVE transition.

Settings UI groups NAV stick coordinates, DRIVE options and OCR-area controls in expandable sections. Only new installations with an empty preference set and Yahoo! Navi detected receive the Yahoo! preset; saved settings are not rewritten on upgrade.

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
- **v36:** `adb tcpip` waits at most **2 s** for the old wireless-ADB stream acknowledgement; an adbd restart can otherwise leave this read hanging. The remaining setup timeout/progress/Retry flow is unchanged.
- **v36:** DASH frame/control and OCR loopback sockets require a per-session authentication token; the separate temporary RestartHelper command channel is also authenticated. This is not a change to NaviLite Bluetooth/JPEG framing.
- Normal runtime helper/reconnect behavior is separate from the one-time setup coordinator.
- The helper is designed to survive normal transport reconnects.
- Restart fallback can launch the small `RestartHelper` through ADB when the main DashServer helper is unavailable.

---

## Start, Reset, Save & Restart

Start mirroring has an in-flight guard so repeated taps cannot start overlapping MediaProjection requests. The Compose fallback guard is 1.2 s when an external Android request-lifetime flag is not supplied.

Save & Restart and Reset & Restart share the same orderly shutdown/restart path.

- **v36:** the Stop-preserve helper DEMOTE is executed on a background IO dispatcher instead of the main thread to prevent Android `NetworkOnMainThreadException`.

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
- Current NAV stick taps are **93/47** and **93/74**; DRIVE has separate **93/13**, **93/30** coordinates and a **1-second** guard. While Auto DRIVE is `CHECKING`, stick taps are ignored (v36).
- Auto DRIVE raw default is **Off**, but the first-run Yahoo! Navi preset enables it alongside its prerequisites.
- Current OCR ROAD output has **no ▲ prefix**; DRIVE shows `DRIVE MODE`.
- `MirrorSettings.kt` still contains older fallback defaults; Android persisted defaults come from `AndroidSettingsStore.kt`.

These are documentation observations only; this cleanup intentionally does not change implementation code.

---

## Future ideas / 未実装メモ

Keep this section short. Ideas are not commitments.

- Revisit upstream changes when newer commits appear or the XMAX target environment changes. On **2026-10-13**, upstream `main` was reviewed through `29497f4ea3bccc5cd40c4647f8e4d8345eeddda3`; all changes considered necessary for this XMAX configuration were incorporated, adapted, or already covered. Unrelated upstream commits were intentionally not merged.

Do not turn speculative ideas into implementation requirements without a separate task and validation plan.
