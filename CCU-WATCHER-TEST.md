# CCU resident watcher experiment — test/ccu-watcher-v40

This test is intentionally isolated from the stable v39 VD/CaptureService flow.

- Home: resident Start/Stop immediately disables while a command is pending. A confirmed running observer displays Stop in red. The ordinary casting Start/Stop and Reset are unchanged.
- Foreground observer runs in the private `:ccu_watcher` process, with its own notification channel and Stop action. Android normally instantiates only one instance of a given Service component; duplicate Start also returns without another receiver or scan task. `START_STICKY` asks Android to restore it after a system process kill, not after force-stop or reboot.
- Matching: paired classic Bluetooth devices named `YCCU...` or containing `CCU`, same name predicate as the current RFCOMM transport. Reports `CCU探索中`, `CCU発見`, `CCU切断・再探索中`, or Bluetooth OFF.
- Probe: a classic Bluetooth discovery request at most every 45 seconds while no CCU ACL connection is known, skipping requests when Pillion CaptureService exists. Discovery/find and ACL connect/disconnect are logged separately; a classic discovery hit is **not** proof of an RFCOMM connection or availability for automatic casting. A paired CCU may not be discoverable outside pairing mode. This test cannot guarantee detecting ignition ON when there is no Bluetooth ACL event.
- This version **does not** establish RFCOMM, start/stop the VD, issue wake/sleep, or act on CCU detection. The Home subtitle labels it a detection-only test.
- For diagnostics: notification state and `logcat -s Pillion-CCU-Watcher`. Export Diagnostics includes `ccu-watcher.log` copied from the independent bounded file in private storage. Log contains event transitions, discovery attempts, failures and service start/stop.
- Runtime permissions: nearby devices (SCAN/CONNECT) and notifications for Android 13+, plus location permission on Android 11 and below for active classic discovery. Android 12+ explicitly declares `neverForLocation`; the observer never uses device detection to derive location.
- Caution: periodically probing Bluetooth consumes energy; discovery can interfere with transport, so current capture sessions suppress scans. Android can throttle or deny discovery in background/Doze. Field tests should distinguish an ACL event from an ACTION_FOUND result.
- No edits to `CaptureService`, `DashHelper`, transport, PROMOTE/DEMOTE or the v39 engine.
