# KeepClear: implementation progress

Updated after every milestone so work can resume if a session stops. Plan: [spec/tasks.md](spec/tasks.md).

Legend: ✅ done (merged) · 🔄 in progress · ⏸ blocked · ⏭ skipped · ⬜ not started

| Milestone | Status | PR | Notes |
|---|---|---|---|
| M0 Skeleton + guard rails | ✅ merged | [#1](https://github.com/tomerar/keepCleanr/pull/1) | CI green incl. emulator smoke tests API 30 + 36. Found and fixed: JUnit 5 silently skipped non-Unit tests (Konsist rule added); verification metadata must be written from a fresh Gradle home. |
| M1 Storage abstraction + fake FS | ✅ merged | [#2](https://github.com/tomerar/keepCleanr/pull/2) | Contract suite + owner-shaped fixtures. |
| M2 Safety core | ✅ merged | [#3](https://github.com/tomerar/keepCleanr/pull/3) | 10k-plan property test, verified to fail when the guard is removed. |
| M3 Scanner + Room | ✅ merged | [#4](https://github.com/tomerar/keepCleanr/pull/4) | Device checks D3-1…3 waiting for owner. |
| M4 Classifier + rules | ✅ merged | [#5](https://github.com/tomerar/keepCleanr/pull/5) | Also fixes 13 verified Codex review findings from #2–#5. Raises decision B1. |
| M5 Home + Review browsing | ✅ merged | [#6](https://github.com/tomerar/keepCleanr/pull/6) | 3 Codex findings fixed before merge (whole-run classification, file-name fallback, ASCII month keys). |
| M6 Trash + restore | ✅ merged | [#7](https://github.com/tomerar/keepCleanr/pull/7) | 3 Codex findings fixed (untrusted entries never auto-purge, fallback restore validated, vanished entries dropped). Also: `readNBytes` (API 33+) replaced by `Streams` + Konsist rule; device trash test moved off our PROTECTED `Android/data` folder. Raises decision B2 (ADR 0010). |
| M7 SAFE cleanup | ✅ merged | [#8](https://github.com/tomerar/keepCleanr/pull/8) | 3 Codex findings fixed: plans use the scan snapshot (SAFETY-6), MediaStore-only items are skipped instead of crashing, a copy that is itself SAFE never counts as a twin witness. |
| M8 REVIEW actions | ✅ merged | [#9](https://github.com/tomerar/keepCleanr/pull/9) | 2 Codex findings fixed: Undo survives deleting a whole group; confirm rows show thumbnail + folder. |
| M9 MOVE engine | ✅ merged | [#10](https://github.com/tomerar/keepCleanr/pull/10) | 4 Codex findings fixed: copy-only targets keep unverified copies, collision names checkpointed before rename, suffixes stay within 255 bytes, crash-safe manifest replace. Also fixed while integrating: reused files never deleted, unreadable manifests kept, ASCII folder names. |
| M10 SAF target + MOVE UI | ✅ merged | [#11](https://github.com/tomerar/keepCleanr/pull/11) | 4 Codex findings fixed (no fsync on pipes, selection kept across recreation, Continue for older jobs, progress notification removed); device tests fixed for a start/await race. |

## Device checks waiting for the owner (D)

Run on the OnePlus 10T; record results in `docs/device-reports/`.

| # | Milestone | Check | Status |
|---|---|---|---|
| D0-1 | M0 | Install the `foss` debug APK; Home shows in Hebrew when the phone is in Hebrew; dark mode follows the system | waiting for owner |
| D3-1 | M3 | Full scan of the OnePlus 10T with All files access: finishes in ≤ 60 s; used/free on Home within 1% of Settings › Storage | waiting for owner |
| D3-2 | M3 | Permission flow: explainer appears before Android's dialog; choose "Selected photos" → partial banner shows; revoke media permission in Settings mid-scan → "incomplete" message, no crash | waiting for owner |
| D5-1 | M5 | Scroll a 2,000-screenshot group: smooth (no visible jank) on the 10T | waiting for owner |
| D6-1 | M6 | Trash 50 test files, restore them; reinstall the app and check the trash is found again (restores to "KeepClear Restored") | waiting for owner |
| D6-2 | M6 | Does OnePlus Gallery show anything for files in `.KeepClearTrash`? (should not, `.nomedia`) | waiting for owner |
| D7-1 | M7 | Is the OnePlus File Manager bin at `/sdcard/.FileManagerRecycler`? (`adb shell ls -la /sdcard/`) | waiting for owner |
| D7-2 | M7 | Clean now on the real phone: ≈ 8 GB found? Clean → Trash → Empty now → Settings › Storage shows the space back | waiting for owner |
| D7-3 | M7 | "Let Android trim caches": how many bytes does it actually free on the 10T? | waiting for owner |
| D8-1 | M8 | Open Review › Screenshots and AI images on the 10T: are the groups right? Select a month, remove two items in the confirm list, delete, then Undo | waiting for owner |
| D10-1 | M10 | Move one year of camera media (5–10 GB) to a USB-C drive; unplug mid-copy, plug back in, tap Continue; it finishes | waiting for owner |
| D10-2 | M10 | On a PC: `sha256sum -c SHA256SUMS` in `KeepClear/<phone>/` passes; what file system is the drive (FAT32 or exFAT)? Files over 4 GB are blocked on every SAF drive for now | waiting for owner |
| D10-3 | M10 | "Free … from the phone" after the receipt; then restore 10 of those files from the Trash | waiting for owner |
| D10-4 | M10 | Notifications: deny on first backup → backup still runs, progress shows in the app; allow → progress notification with Pause/Stop works | waiting for owner |
| D3-3 | M3 | `adb shell ls /storage/emulated/999` and `pm list users`: is there a Parallel-apps user 999, and can KeepClear (all-files) read it? (MSG-3) | waiting for owner |

## Blockers and decisions needed

| # | Type | Question | What I did meanwhile |
|---|---|---|---|
| Q4 | Open question (requirements §14) | Which USB-C drive for backups (≥ 128 GB, exFAT preferred so videos over 4 GB fit)? Needed for device check D10-1. | Nothing blocked in code: every SAF drive is treated as FAT32 (files > 4 GB are blocked with a message, never truncated) until D10-2 confirms the format. |
| B2 | Decision (ADR 0010, amends ADR 0006) | MVP trash: put **every** file KeepClear can reach by path (including camera media) into its own `.KeepClearTrash` folder, instead of MediaStore's trash for indexed media. Simpler and fully crash-tested; trashed photos disappear from the gallery until restored, and other galleries' "Recently deleted" won't list them. MediaStore trash comes with the `play` flavor (M11). OK? | Implemented as proposed (ADR 0010 status "Proposed"). Still a 30-day trash; nothing permanent changed. |
| B1 | Decision (CLS-5) | Keep signals: today every SAFE item that is a favourite **or modified in the last 30 days** becomes REVIEW. That is the safe reading of CLS-5, but it means WhatsApp statuses older than 24 h are never SAFE (they are always < 30 days old), and other apps' recent recycle-bin items need review too. Should regenerable junk (thumbnails, statuses, temp files) and already-deleted bin items stay SAFE despite being recent? | Kept the conservative behaviour (nothing becomes SAFE that the spec doesn't clearly allow). Visible in `engine/classify/src/test/resources/owner-classification-snapshot.txt`. |

## Notes for resuming

- Local builds in the sandbox use a Maven Central mirror via `~/.gradle/init.d/mirror.gradle.kts` and `keepclear.robolectricRepoUrl` in `~/.gradle/gradle.properties` (Central returns HTTP 429 here). Not part of the repo; CI uses normal repositories.
- Android SDK at `/opt/android-sdk` (platform `android-37.0`, build-tools 37.0.0); `local.properties` is git-ignored.
- Write `gradle/verification-metadata.xml` with `--no-configuration-cache` after deleting `.gradle/`, `build-logic/.gradle`, `build-logic/build` (otherwise the included build's plugin classpath isn't re-resolved and CI fails), then run `scripts/verification-extras.py`.
- MVP milestones M0–M10 are merged. Next per tasks.md: M11+ (v1: `play` flavor with MediaStore trash, SMB target, restore from backup). Nothing is in progress.
- Lessons from the overnight run: always `git add -A` before committing new files; pure-JVM modules aren't linted for Android API levels (Konsist now forbids `readNBytes`/`skipNBytes`/`transferTo`); every PR gets a Codex review a minute after opening, so read the threads before merging.
- **Merge gate (since #5):** CI green on the head commit **and** every Codex review thread answered (fixed, or explained). PRs #2–#4 were merged before their review threads were read; all their findings were verified and fixed in #5, with replies on each thread.
- Never run two Gradle builds at once in this sandbox (15 GB RAM): the daemon gets killed. Stop daemons with `./gradlew --stop` and kill leftover `java` PIDs by number (pattern-based `pkill` also kills the shell).
- `git reset --hard` is blocked by the sandbox policy; after a squash-merge, `git merge origin/main` into the working branch and continue (the PR diff stays correct).
- After a milestone PR merges, the working branch `ccr-61f4d668-lr2wsd` is reset onto the new `main` for the next milestone.
