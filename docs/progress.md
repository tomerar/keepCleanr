# KeepClear: implementation progress

Updated after every milestone so work can resume if a session stops. Plan: [spec/tasks.md](spec/tasks.md).

Legend: ✅ done (merged) · 🔄 in progress · ⏸ blocked · ⏭ skipped · ⬜ not started

| Milestone | Status | PR | Notes |
|---|---|---|---|
| M0 Skeleton + guard rails | ✅ merged | [#1](https://github.com/tomerar/keepCleanr/pull/1) | CI green incl. emulator smoke tests API 30 + 36. Found and fixed: JUnit 5 silently skipped non-Unit tests (Konsist rule added); verification metadata must be written from a fresh Gradle home. |
| M1 Storage abstraction + fake FS | 🔄 CI running | [#2](https://github.com/tomerar/keepCleanr/pull/2) | Local ciCheck green. |
| M2 Safety core | 🔄 implemented locally | | 28 tests incl. 10k-plan property test; waits for M1 merge. |
| M3 Scanner + Room | 🔄 in progress | | FileGateway (contract on real FS), scanner engine, Room index done; Android MediaStore source + permission UI next. |
| M4 Classifier + rules | ⬜ | | |
| M5 Home + Review browsing | ⬜ | | |
| M6 Trash + restore | ⬜ | | |
| M7 SAFE cleanup | ⬜ | | |
| M8 REVIEW actions | ⬜ | | |
| M9 MOVE engine | ⬜ | | |
| M10 SAF target + MOVE UI | ⬜ | | |

## Device checks waiting for the owner (D)

Run on the OnePlus 10T; record results in `docs/device-reports/`.

| # | Milestone | Check | Status |
|---|---|---|---|
| D0-1 | M0 | Install the `foss` debug APK; Home shows in Hebrew when the phone is in Hebrew; dark mode follows the system | waiting for owner |

## Blockers and decisions needed

_None yet._

## Notes for resuming

- Local builds in the sandbox use a Maven Central mirror via `~/.gradle/init.d/mirror.gradle.kts` and `keepclear.robolectricRepoUrl` in `~/.gradle/gradle.properties` (Central returns HTTP 429 here). Not part of the repo; CI uses normal repositories.
- Android SDK at `/opt/android-sdk` (platform `android-37.0`, build-tools 37.0.0); `local.properties` is git-ignored.
- Write `gradle/verification-metadata.xml` with `--no-configuration-cache` after deleting `.gradle/`, `build-logic/.gradle`, `build-logic/build` (otherwise the included build's plugin classpath isn't re-resolved and CI fails), then run `scripts/verification-extras.py`.
- Local worktrees `/home/user/kc-m2`, `/home/user/kc-m3` hold later milestones (branches `m2-local`, `m3-local`); they are rebased onto `main` when their turn comes.
- After a milestone PR merges, the working branch `ccr-61f4d668-lr2wsd` is reset onto the new `main` for the next milestone.
