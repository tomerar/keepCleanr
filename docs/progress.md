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

### Independent review of `main` (9de88f4), 2026-11-01

M11 is paused until these three PRs are merged, in order. Each fix has a test that failed before it.

| PR | Status | Contents |
|---|---|---|
| A Data safety | ✅ merged ([#15](https://github.com/tomerar/keepCleanr/pull/15)); 1 Codex finding fixed (an unplugged drive during Check backup no longer crashes) | H1 second uncached backup check (unplug + replug, or reboot) before freeing, copy re-hashed at free time, unknown providers copy-only, fake-capacity drive test; H2 witnesses never in trash / removable / backup folders, duplicate groups + witnesses in plans, executor re-stat; H3 same-volume block from paths, backup folders PROTECTED, `home:`/Downloads/Media roots on-phone; M2 purge needs 30 days of stored uptime too, clock jumps refused and logged; Lows: restore checks size/SHA, newer sidecars surface as untrusted, canonical purge containment, MOVE pauses on unexpected errors, manifests streamed with caps. Room v4. ADR 0007 amended. |
| A2 Owner decisions on PR A | ✅ merged ([#16](https://github.com/tomerar/keepCleanr/pull/16)); 2 Codex findings fixed (MOVE frees never held back as last copy; last-copy plan keeps group sizes) | Changed file in the trash: marked "השתנה מאז שנמחק", restored only to `KeepClear/Restored-changed/` after a confirm. Last-copy guard: trashing a file that is the witness of a trashed duplicate needs "This is the last copy" (test fails without it). Manifest cap 16 MB approved. Google Drive postponed by owner (tasks.md M14). Room v5. |
| B Hardening | ✅ merged ([#17](https://github.com/tomerar/keepCleanr/pull/17)); 1 Codex finding fixed (obscured flag reset per gesture, so TalkBack/keyboard can still confirm) | Obscured touches rejected inside `SafeConfirmButton` (E-SEC-10 test); test builds are `io.github.tomerar.keepclear.test` ("KeepClear test" in the launcher); no paths in logcat (Konsist); plans confirmed by the user's tap (`Confirmation`, guard checks shown paths and ref = path; Konsist); wrapper file repaired (SHA-256 re-checked against services.gradle.org) + `WrapperPolicy`; `persist-credentials: false` + test. |
| C UX for non-technical users | 🔄 in review | Intro, one primary action on Home, measured results, free-after-backup flow, no jargon, Hebrew plurals and rewrites, colours, MOVE progress, review grid accessibility, bidi, Settings. Then a new test APK. |

### v1 milestones

| Milestone | Status | PR | Notes |
|---|---|---|---|
| M11 Phone-first polish | 🔄 in progress (after PR C) | | SAFE-9 app folders (per-app, no delete; `Android/data`/`obb` PROTECTED), SAFE-10 one app at a time (measured), TRASH-7 uninstall note on Trash + Settings, Check backup fault tests (missing / truncated / bit-flipped). Plain-language pass, Check backup, cache trim on Home and copy lint landed in PRs A–C. |

## Test build for the phone (no computer needed)

Every push to `main` builds a signed `fossDebug` test APK once CI is green (workflow **CI**, job **Test APK (fossDebug)**). It is an Actions artifact, not a GitHub Release, kept 30 days. Install from the phone:

1. In the phone's browser (signed in to GitHub), open `https://github.com/tomerar/keepCleanr/actions/workflows/ci.yml?query=branch%3Amain`.
2. Open the newest run with a green check.
3. Scroll to **Artifacts** and tap `keepclear-test-<number>-<commit>.apk`. It downloads as an `.apk`, not a zip.
4. Open the downloaded file. The first time, Android asks to allow installs from the browser: **Settings › Allow from this source**, then go back and tap **Install**.
5. A newer test APK installs over the old one (same test key; see `keystore/README.md`). A future release build can't: uninstall the test build first, after restoring or emptying KeepClear's trash.

To build one on demand: Actions › CI › **Run workflow** on `main`.

**The test APK starts in test mode** (SAFETY-9): a banner on every screen says "מצב בדיקה – שום קובץ לא יימחק או יועבר". Cleaning, deleting, freeing space, restoring, emptying the trash and clearing caches only show what *would* happen ("would have freed X GB (test mode)"). Copying to a USB drive still works, because it changes nothing on the phone. To run the destructive checks at home, go to Settings › Advanced › Test mode and confirm twice. Turning it back on takes one tap.

## Device checks waiting for the owner (D)

Run on the OnePlus 10T with the phone only (P-1). Steps that need a computer are marked *optional (developer)*. Record results in `docs/device-reports/`.

**Now (read-only, while travelling):** D0-1, D3-1, D3-2, D3-3, D5-1, D8-1 (look only; don't tap Delete). **When home (they delete or move files):** D6-x, D7-2, D10-x. D7-1 and D7-3 can be done any time but D7-3 clears caches.

| # | Milestone | Check | When | Status |
|---|---|---|---|---|
| D0-1 | M0 | Install the test APK; Home shows in Hebrew when the phone is in Hebrew; dark mode follows the system | Now | waiting for owner |
| D3-1 | M3 | Full scan with All files access: finishes in ≤ 60 s; used/free on Home within 1% of Settings › Storage | Now | waiting for owner |
| D3-2 | M3 | Permission flow: explainer appears before Android's dialog; "Selected photos" → partial banner; revoke media permission in Settings mid-scan → "incomplete" message, no crash | Now | waiting for owner |
| D3-3 | M3 | Parallel apps: if WhatsApp is cloned (Settings › Apps › Parallel apps), does KeepClear show the clone's media? *Optional (developer): `adb shell ls /storage/emulated/999` and `pm list users`.* | Now | waiting for owner |
| D5-1 | M5 | Scroll a 2,000-screenshot group: smooth, no visible jank | Now | waiting for owner |
| D8-1 | M8 | Review › Screenshots and AI images: are the groups right? Select a month and open the confirm list, then **Cancel** (deleting waits until home) | Now (read-only part) | waiting for owner |
| D7-1 | M7 | OnePlus File Manager bin: in the Files app, show hidden files; is there `.FileManagerRecycler` in internal storage? *Optional (developer): `adb shell ls -la /sdcard/`.* | Any time | waiting for owner |
| D7-3 | M7 | "Clear app caches": how many bytes does it actually free? Research predicts about 0 on a 93%-full phone ([09](research/09-accessibility-cache-cleaner.md) §1.2); the result decides SAFE-10's priority | Any time (clears caches only) | waiting for owner |
| D6-1 | M6 | Trash 50 test files, restore them; reinstall and check the trash is found again (restores to "KeepClear Restored") | Home | waiting for owner |
| D6-2 | M6 | Does OnePlus Gallery show anything for files in `.KeepClearTrash`? (should not, `.nomedia`) | Home | waiting for owner |
| D7-2 | M7 | Clean now: ≈ 8 GB found? Clean → Trash → Empty now → Settings › Storage shows the space back | Home | waiting for owner |
| D10-1 | M10 | Move one year of camera media (5–10 GB) to the exFAT USB-C drive; unplug mid-copy, plug back in, tap Continue; it finishes; a video over 4 GB is copied (exFAT detected) or the screen explains why not | Home | waiting for owner |
| D10-2 | M10 | The receipt says every file was checked. *Optional (developer): `sha256sum -c SHA256SUMS` on a PC.* In-app "Check backup" comes in M11 | Home | waiting for owner |
| D10-3 | M10 | After the receipt: **Check backup** (unplug the drive when asked, plug it back in), then "Free … from the phone"; then restore 10 of those files from the Trash | Home | waiting for owner (after PR A) |
| D10-4 | M10 | Notifications: deny on first backup → backup still runs, progress shows in the app; allow → progress notification with Pause/Stop works | Home | waiting for owner |

## Decided conservatively, please review

| # | Decision |
|---|---|
| C2 | "Restore all" asks first but shows no space warning: restoring is a rename on the same volume, so it never needs extra space (the dialog says so). |
| C4 | SAFE-9: `Android/obb` is PROTECTED like `Android/data` (game data; SAFE-9 names only data). |
| C3 | "Check backup" was unreachable in the app after PR A (AppScreens dropped `onCheck`); fixed in PR C with a test. Found while testing, not by review. |
| C1 | Last-copy guard (owner 1.3): when a trashed archive lists this file as an extracted witness (sizes differ, so no same-hash check is possible), the file is also treated as the last copy and needs the confirm. |

## Waiting for owner

| # | What | Why |
|---|---|---|
| W1 | Before installing the next test APK: in the **old** test app (named "KeepClear"), restore or empty the trash, then uninstall it. The new one is "KeepClear test" and installs alongside. | Test builds now have their own package so the public test key can't update a real install (PR B). |

## Blockers and decisions needed

None open. Decided by the owner on 2026-11-01 (recorded in requirements §14):

| # | Decision |
|---|---|
| B1 | Regenerable items (thumbnails, temp files, WhatsApp statuses > 24 h, OS junk) stay SAFE regardless of age; the 30-day rule applies to everything else. Implemented (rules v3, CLS-5). |
| B2 | ADR 0010 accepted for `foss`; TRASH-7 wording says trashed files stay in `.KeepClearTrash` and take space after uninstall. |
| Q4 | 256 GB+ USB-C drive, exFAT. Drive-format detection implemented: the 4 GB limit applies only to FAT32 or an unreadable format. |

Accepted on 2026-11-01: [ADR 0011](adr/0011-network-and-cloud-targets.md) with Google Drive first (Drive after a sign-in spike → SMB → Nextcloud → Dropbox; if the spike fails, SMB first and the owner is told; honest Google-account space check) and [ADR 0012](adr/0012-foss-accessibility-cache-cleaner.md) (no Accessibility cleaner in v1; guided per-app list instead).

## Notes for resuming

- Local builds in the sandbox use a Maven Central mirror via `~/.gradle/init.d/mirror.gradle.kts` and `keepclear.robolectricRepoUrl` in `~/.gradle/gradle.properties` (Central returns HTTP 429 here). Not part of the repo; CI uses normal repositories.
- Android SDK at `/opt/android-sdk` (platform `android-37.0`, build-tools 37.0.0); `local.properties` is git-ignored.
- Write `gradle/verification-metadata.xml` with `--no-configuration-cache` after deleting `.gradle/`, `build-logic/.gradle`, `build-logic/build` (otherwise the included build's plugin classpath isn't re-resolved and CI fails), then run `scripts/verification-extras.py`.
- MVP milestones M0–M10 are merged. Review PRs A–C (above) come first; M11 waits for them. Then per tasks.md (order updated 2026-11-01): M11 phone-first polish, then `play` backend, Apps, SMB, Google Drive, restore. Nothing is in progress; each milestone waits for the owner's OK.
- Lessons from the overnight run: always `git add -A` before committing new files; pure-JVM modules aren't linted for Android API levels (Konsist now forbids `readNBytes`/`skipNBytes`/`transferTo`); every PR gets a Codex review a minute after opening, so read the threads before merging.
- **Merge gate (since #5):** CI green on the head commit **and** every Codex review thread answered (fixed, or explained). PRs #2–#4 were merged before their review threads were read; all their findings were verified and fixed in #5, with replies on each thread.
- Never run two Gradle builds at once in this sandbox (15 GB RAM): the daemon gets killed. Stop daemons with `./gradlew --stop` and kill leftover `java` PIDs by number (pattern-based `pkill` also kills the shell).
- `git reset --hard` is blocked by the sandbox policy; after a squash-merge, `git merge origin/main` into the working branch and continue (the PR diff stays correct).
- After a milestone PR merges, the working branch `ccr-61f4d668-lr2wsd` is reset onto the new `main` for the next milestone.
