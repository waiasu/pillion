# Pillion `test-base` workbench rules / 本家PR向け実験室

This file governs a curated experimental workbench and optional isolated upstream submissions. Curated updates are kept separate from upstream PR proposals.
`test-base` is a **diagnostic workbench, not a release branch**.
本家に提出する修正と、実験用のログ・ライブラリ変更を厳密に分離すること。

## Branch boundaries / ブランチの役割（厳守）

| Branch | Purpose | Allowed changes |
| --- | --- | --- |
| `main` | Clean upstream-based fork baseline | No test-only instrumentation or experimental patches |
| `test-base` | Curated, build-verified experimental improvements | Diagnostic workbench plus one independently documented change per squash commit |
| `test/<issue>` | One disposable experiment based on `test-base` | One candidate fix, targeted temporary diagnostic logs, focused tests |
| `pr/<issue>` | One upstream PR candidate based **directly on clean `main`** | Only the verified production fix and any directly necessary production tests |
| `xmax-pillion` | Independently maintained, stable XMAX-specific fork | **Never change** as part of upstream testing/PR preparation |

**Never merge `test-base` or `test/*` into `main`, `pr/*`, or `xmax-pillion`.**
Do not create PR branches from `test-base`, `test/*`, another `pr/*`, or `xmax-pillion`.
Do not merge unrelated, pending PR fixes into a new PR.
Do not cherry-pick an experimental commit containing both a fix and telemetry; extract the fix manually.

`test-base` currently includes two patched vendored `libadb-android` 3.1.1 synchronization fixes.
Before changing ADB code, **read `third_party/libadb-android/PATCHES.md` and preserve all licenses**.
Those vendored files are **not** part of unrelated upstream PRs.
Keep test infrastructure on `test-base` separate from the fixes being tested.

## Standard workflow / 毎回の手順

1. **Read this file first.** Identify the single bug and its observable symptom.
   Confirm the intended base branch before making any GitHub write.
2. **Create `test/<issue>` from the current `test-base`.** Never implement or test directly on `test-base`. Complete and validate each update before starting the next.
   Keep experiments for different bugs in separate branches.
3. **Implement the smallest candidate fix.** Add low-frequency, state-transition diagnostics on the
   test branch when needed to establish cause and effect. Keep logs distinguishable from production changes.
   Flight recorder entries may contain state breadcrumbs, **never pairing codes, tokens, credentials,
   raw screen frames, or other secrets**.
4. **Build and check the test branch.** Run:
   ```sh
   ./gradlew :composeApp:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug
   ```
   The existing GitHub Actions workflow also builds APKs on `test-base` and `test/**`.
   Inspect the actual result of every job: a non-blocking lint step is not evidence that lint passed.
5. **Field-test the debug APK and export diagnostics.** Check both the target behavior and regressions.
   For a screen-source change, test normal mirror, screen off / VD promotion, unlock / demotion,
   reconnection or VD frame gaps, and Stop. Confirm the relevant events in logs.
   An observed improvement is not proof of unrelated behavior; document remaining limitations.
6. **Curate the successful experiment.** Inspect the complete diff and actual CI/test results. If verified, squash the changes into exactly one focused commit on `test-base`, with Purpose, Problem, Changes, Verification, and Limitations in its message. Preserve the original branch for troubleshooting if needed; never force-push or rewrite the published `test-base` history. Start the next test branch only after this update is complete.
7. **Freeze a production-only change when explicitly preparing an upstream submission.** Review the diff between `test-base` and
   `test/<issue>`. Classify **each changed line** as production fix, necessary test, or
   diagnostic/experimental scaffolding. Only the first two categories may be ported.
8. **Create `pr/<issue>` from a current, clean `main`.** Verify the baseline against upstream;
   if it needs syncing, treat syncing as a separate explicit operation, not part of this PR.
   Manually apply **only** the verified production change. Do not merge/cherry-pick the test branch
   wholesale. Prefer one focused commit when practical.
9. **Audit the PR diff against `main` before submission.** Run equivalent checks:
   ```sh
   git diff --stat main...pr/<issue>
   git diff --name-status main...pr/<issue>
   git diff main...pr/<issue>
   git log --oneline main..pr/<issue>
   ```
   Review every file and hunk, not only the file count. The diff must be limited to this bug.
   If any unintended file or line appears, **remove it before opening the PR**.
10. **Validate the isolated PR change.** Build/test it when possible, or verify line-by-line that its
   production behavior matches the version already built and field-tested. Do not claim an unrun
   PR-branch build or upstream CI succeeded. Note the exact test scope and residual risks.
11. **Open an English upstream PR** from `waiasu:pr/<issue>` to `alexandrevega/pillion:main`.
    Describe the problem, root cause, minimal fix, physical-device testing, and limitations.
    If GitHub API permission prevents creation, give the GitHub compare/create link for the user;
    **verify the PR URL/state afterward, and never report it created before confirmation**.
12. **Clean up only when safe.** A finished `test/<issue>` branch may be deleted after its
    verified fix is preserved. Keep `test-base` and the `pr/<issue>` branch while its PR is open.
    Never delete a PR head branch prematurely. Never alter `xmax-pillion`.

## PR contamination checklist / 混入防止チェック

Before opening any upstream PR, verify **all** of the following:

- [ ] Head branch was created **directly from `main`**, not from the diagnostic workbench.
- [ ] Diff contains **only files and hunks necessary for the named bug**, with no other fixes.
- [ ] No `DiagnosticFlightRecorder`, diagnostic exporter, temporary log probes, sample log output,
      or flight-recorder initialization was ported.
- [ ] No `third_party/libadb-android/**` vendor code, synchronization patches, license bundles,
      or ADB test substitutions were ported **unless ADB itself is explicitly the PR topic**.
- [ ] No `AGENTS.md`, `.github/workflows/**`, Gradle dependency/build setup, app version,
      UI text, documentation, or XMAX-specific behavior was included incidentally.
      (If one of these is genuinely required for the bug, justify and review it explicitly.)
- [ ] No prior `test/*` work, other `pr/*` work, unrelated fixes, or unverified experiments slipped in.
- [ ] Expected files, commit list, and total line changes were checked with a `main...pr/<issue>` compare.
- [ ] Field test and build claims match actual evidence; no untested claim of general compatibility.
- [ ] `main`, `test-base`, and `xmax-pillion` remain untouched by the production PR preparation.

**If any checkbox fails, stop: clean the PR branch before publishing.**
Do not solve contamination by merging the experimental branch and deleting files afterward;
start over from clean `main` if necessary.

**Guiding rule: verify and squash one focused experiment at a time on `test-base`; submit narrowly to upstream only when requested.**
