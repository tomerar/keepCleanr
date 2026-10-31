# KeepClear: implementation progress

Updated after every milestone so work can resume if a session stops. Plan: [spec/tasks.md](spec/tasks.md).

Legend: ✅ done (merged) · 🔄 in progress · ⏸ blocked · ⏭ skipped · ⬜ not started

| Milestone | Status | PR | Notes |
|---|---|---|---|
| M0 Skeleton + guard rails | ✅ merged | [#1](https://github.com/tomerar/keepCleanr/pull/1) | CI green incl. emulator smoke tests API 30 + 36. Found and fixed: JUnit 5 silently skipped non-Unit tests (Konsist rule added); verification metadata must be written from a fresh Gradle home. |
| M1 Storage abstraction + fake FS | ✅ merged | [#2](https://github.com/tomerar/keepCleanr/pull/2) | Contract suite + owner-shaped fixtures. |
| M2 Safety core | ✅ merged | [#3](https://github.com/tomerar/keepCleanr/pull/3) | 10k-plan property test, verified to fail when the guard is removed. |
| M3 Scanner + Room | 🔄 CI running | [#4](https://github.com/tomerar/keepCleanr/pull/4) | Local ciCheck green. Device check D3-1 waiting for owner. |
| M4 Classifier + rules | 🔄 implemented locally | | Oracle + snapshot tests green; waits for M3 merge. Raises decision B1. |
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
| D3-1 | M3 | Full scan of the OnePlus 10T with All files access: finishes in ≤ 60 s; used/free on Home within 1% of Settings › Storage | waiting for owner |
| D3-2 | M3 | Permission flow: explainer appears before Android's dialog; choose "Selected photos" → partial banner shows; revoke media permission in Settings mid-scan → "incomplete" message, no crash | waiting for owner |
| D3-3 | M3 | `adb shell ls /storage/emulated/999` and `pm list users`: is there a Parallel-apps user 999, and can KeepClear (all-files) read it? (MSG-3) | waiting for owner |

## Blockers and decisions needed

| # | Type | Question | What I did meanwhile |
|---|---|---|---|
| B1 | Decision (CLS-5) | Keep signals: today every SAFE item that is a favourite **or modified in the last 30 days** becomes REVIEW. That is the safe reading of CLS-5, but it means WhatsApp statuses older than 24 h are never SAFE (they are always < 30 days old), and other apps' recent recycle-bin items need review too. Should regenerable junk (thumbnails, statuses, temp files) and already-deleted bin items stay SAFE despite being recent? | Kept the conservative behaviour (nothing becomes SAFE that the spec doesn't clearly allow). Visible in `engine/classify/src/test/resources/owner-classification-snapshot.txt`. |

## Notes for resuming

- Local builds in the sandbox use a Maven Central mirror via `~/.gradle/init.d/mirror.gradle.kts` and `keepclear.robolectricRepoUrl` in `~/.gradle/gradle.properties` (Central returns HTTP 429 here). Not part of the repo; CI uses normal repositories.
- Android SDK at `/opt/android-sdk` (platform `android-37.0`, build-tools 37.0.0); `local.properties` is git-ignored.
- Write `gradle/verification-metadata.xml` with `--no-configuration-cache` after deleting `.gradle/`, `build-logic/.gradle`, `build-logic/build` (otherwise the included build's plugin classpath isn't re-resolved and CI fails), then run `scripts/verification-extras.py`.
- Local worktrees `/home/user/kc-m2`, `/home/user/kc-m3` hold later milestones (branches `m2-local`, `m3-local`); they are rebased onto `main` when their turn comes.
- After a milestone PR merges, the working branch `ccr-61f4d668-lr2wsd` is reset onto the new `main` for the next milestone.
