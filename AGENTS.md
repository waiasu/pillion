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

The stable `xmax-pillion` line includes v35 Auto DRIVE / NAV behavior (release commit `ea564aa`) and has been validated primarily on Yamaha XMAX + Google Pixel 10a + Yahoo! Car Navigation.

Unless a task explicitly requires it:

- Do not change the established PROMOTE / DEMOTE state flow.
- Do not demote merely because Bluetooth/RFCOMM disconnects.
- Preserve the existing ScreenSource/helper/VD while transport reconnects.
- Keep OCR traffic isolated from the main NaviLite JPEG frame stream.
- Keep Restart-on-VD as a separate path from the normal relocation path.
- SecureCamera handling must restore only the physical panel and must not redesign PROMOTE / DEMOTE behavior.
- Keep XMAX center-press handling untouched; only UP/DOWN zoom requests are consumed.
- Preserve v35 Auto DRIVE's **strict Yahoo! Navi gate** (OCR + Restart-on-VD + selected fixed Yahoo! Navi target), once-per-PROMOTE decision, two independent ~10-second Area 1 checks, and one-second DRIVE stick guard.
- Keep DRIVE tap/ROAD filtering and the optional output-only upward shift isolated from the NAV canvas, VD sizing, OCR crop coordinates, and PROMOTE/DEMOTE state flow.
- Do not overwrite existing installations' preferences with the v35 clean-install Yahoo! Navi preset; the preset applies only when the preference file is genuinely empty and Yahoo! Navi is installed.

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

When a stable fork milestone is reached, update `CHANGELOG-XMAX.md` and `XMAX-CURRENT.md` as needed, and keep the fork-specific README feature summary accurate. v35 is the current stable milestone; development on `test/v36` is separate and must not be documented as released until approved.

## Branch / merge workflow

- `xmax-pillion` is the stable release branch. Do not commit, push, or merge changes into it without explicit user approval.
- Begin each new development cycle on a fresh test branch created from the latest `xmax-pillion`. Do not reuse an older test branch after a release.
- Keep experiments, intermediate fixes, failed attempts, and their individual commits on the test branch.
- Before proposing a release, ensure rejected experiments are fully reverted from the **final code**, not merely omitted from a summary. Review the full diff against `xmax-pillion` and complete the applicable build, bench, and on-bike validation.
- Only after explicit approval, open a Pull Request from the test branch into `xmax-pillion` and use **Squash and merge** to integrate the completed changes as a single release commit.
- Do not use a regular merge or rebase merge unless explicitly requested. Never rewrite the stable branch's existing history just to tidy older commits.
- Do not automatically delete the test branch after merging. Retain it until the user approves cleanup; create a tag first if its detailed experimental history needs to remain accessible.
- After a release, start the next test branch from the updated `xmax-pillion`.

