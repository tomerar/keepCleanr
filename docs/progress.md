# KeepClear: implementation progress

> **Latest test APK (after review PRs A–C):** [Actions run #57](https://github.com/tomerar/keepCleanr/actions/runs/41299825472), artifact **`keepclear-test-57-9e8ca8a.apk`** (from `main` at 9e8ca8a). It installs as a new app, "KeepClear test"; see "Waiting for owner" W1 for the old test install. Trial mode is on by default.

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
| C UX for non-technical users | ✅ merged ([#18](https://github.com/tomerar/keepCleanr/pull/18)); 2 Codex findings fixed (lazy trash rows, advisory drive check); smoke test goes through the intro | Intro, one primary action on Home, measured results, free-after-backup flow, no jargon, Hebrew plurals and rewrites, colours, MOVE progress, review grid accessibility, bidi, Settings. Then a new test APK. |

### v1 milestones

| Milestone | Status | PR | Notes |
|---|---|---|---|
| M11 Phone-first polish | ✅ merged ([#19](https://github.com/tomerar/keepCleanr/pull/19)); 1 Codex finding fixed (package-name case); fixed a MoveService start/stop race found by CI | | SAFE-9 app folders (per-app, no delete; `Android/data`/`obb` PROTECTED), SAFE-10 one app at a time (measured), TRASH-7 uninstall note on Trash + Settings, Check backup fault tests (missing / truncated / bit-flipped). Plain-language pass, Check backup, cache trim on Home and copy lint landed in PRs A–C. |
| M12a `play`: Android's trash for MediaStore items | ✅ merged ([#20](https://github.com/tomerar/keepCleanr/pull/20)); 3 Codex findings fixed (media item URIs instead of Files URIs, also caught by the emulator test; one Android request built at a time; only approved dialogs counted) | | MediaStore-only items (play, or no All files access) go to Android's own trash after Android's confirmation (`createTrashRequest`, batches of 2,000); re-checked through MediaStore first (size, mtime, trashed state); restore and "Delete these now" for Android's trash on the Trash screen (second confirmation + Android's dialog). Executor gained a consent step (`NeedsConsent`). |
| M12b-1 `SafGateway` + contract suite on a device | ✅ merged ([#22](https://github.com/tomerar/keepCleanr/pull/22)); 2 Codex findings fixed (a half-done two-step rename is undone; stream failures map to storage errors) | | `core:storage-saf`: StorageGateway over granted folders; never overwrites (a provider's "x (1)" copy is removed and reported), deletes only files and empty folders, moves via `moveDocument`/`renameDocument` in the order whose intermediate name is free. The M1 contract suite runs on the emulator against the fake USB drive. |
| M12b-2 `play`: WhatsApp's folder through Android's folder picker (ONB-6) + trash inside it | ✅ merged ([#23](https://github.com/tomerar/keepCleanr/pull/23)); 3 Codex findings fixed ("Empty now" for a granted folder's trash; grants loaded for the background purge; folder trash kept when All files access is turned on) | | Home card → explainer with the 2 steps → picker opens at WhatsApp's folder; only WhatsApp's own folder (with `Media/`) is kept, otherwise "try again". The folder is mounted at its real path (`SafTreeMount` + `MountedGateway`), so scan, rules, PROTECTED paths and the trash work unchanged; its trash is `<folder>/.KeepClearTrash`. Old-phone folders: later (only WhatsApp is accepted now). |
| M15a SMB target (network module) | ✅ merged ([#24](https://github.com/tomerar/keepCleanr/pull/24)); 1 Codex finding fixed (reconnect after the server drops the session or share; tested with `smbcontrol close-share`) | | `backup:target-smb` with smbj 0.15.0 (Apache-2.0) + BouncyCastle for NTLM's MD4 (research 06 §3.1): never overwrites (`FILE_CREATE`, rename without replace), read-back verification, unreachable server = paused (never delete), wrong password = refused. 9 integration tests against a real Samba server with signing required (`scripts/samba-test-server.sh`, also in CI). INTERNET is declared only in this module; a Konsist rule keeps network imports in `backup/target-*`. Not yet in the app (M15b). |
| M15b SMB set-up on the phone + MOVE to SMB | ✅ merged ([#25](https://github.com/tomerar/keepCleanr/pull/25)); 3 Codex findings fixed (Settings shows a new drive at once; Forget pauses a running backup; every network connection is closed after use) + release-build R8 rules for smbj | | Address/share/user/password on the phone (password encrypted with the Android Keystore), "Test connection", MOVE to the share with read-back, Settings "Network: only to <host>", PRIVACY.md + intro copy, `ACCESS_LOCAL_NETWORK` prep. |
| M17a Nextcloud/WebDAV target (engine) | ✅ merged ([#31](https://github.com/tomerar/keepCleanr/pull/31)); 2 Codex findings fixed (a length mismatch never leaves a stored file; upload writes have a deadline and close() aborts them) | | New module `backup:target-webdav`, JDK only (HTTP/1.1 over JDK sockets/TLS; Android's `HttpURLConnection` refuses PROPFIND/MKCOL/MOVE): never overwrites (PROPFIND check + `If-None-Match: *`, MOVE with `Overwrite: F`), HTTPS only (plain http only to the phone itself), redirects never followed, bounded answers, hrefs outside the folder ignored, XML without DTD/entities. Nextcloud: `X-Hash: sha256` → the server's `X-Hash-SHA256` verifies without a download (`TargetCaps.serverDigests`, `BackupTarget.serverDigest`); generic WebDAV reads back like SMB. Engine: uses the server digest when there is one, still re-hashes at free time; announces the size (`create(rel, size)`); a target error while the source changed counts as a source change. New shared `BackupTargetContract` (target-api fixtures) runs on the fake drive, WebDAV and Nextcloud modes. Fake server tests: wrong/garbage hash, truncating/bit-flipping server, 401, 507, dropped upload, short GET, hostile hrefs, XXE, oversized listing, redirect, TLS (untrusted, wrong name, trusted). ADR 0011 amendment proposed (no OkHttp for WebDAV). Not yet in the app (M17b: set-up screen, Login Flow v2, method per file). |
| M16-1 Restore from a backup (MOVE-17) | ✅ merged ([#26](https://github.com/tomerar/keepCleanr/pull/26)); 2 Codex findings fixed (a full phone fails one file cleanly; a retry never copies twice) + an SMB test race fixed (own share for the "share dropped" test) | | Move › "Restore from a backup": choose the drive, folder or network drive; pick the phone's backup if there are several; choose source folders and months (nothing pre-selected); files go back to where they were, or to `Pictures/KeepClear Restored/` when that place is taken, PROTECTED, the trash, or off the phone (`RestorePathValidator`, E-SEC-5). Never overwrites; a file already there with the same content is left alone. Each copy: SHA-256 while reading from the drive must match the manifest, and a read-back on the phone must match again before the file gets its name. Re-indexed for the gallery. New module `engine:restore` (Konsist: its only destructive calls are on its own temp file). Not yet: thumbnails in the list, and the original file date (restored files get today's date; photos keep their camera date inside the file). |
| M16-2 Keep this / always junk (CLS-7, SET-2) | ✅ merged ([#27](https://github.com/tomerar/keepCleanr/pull/27)); 2 Codex findings fixed (a file kept while a plan runs is skipped at the choke point; an older classification never overwrites a newer one) | | Preview › "This is important to me" (never offered again; PROTECTED at the choke point, re-read before every trash/move-original op) and "Always treat this folder as junk" (with a confirmation; only folders two levels deep, never DCIM, Pictures, Movies, Music, Documents or Android, C12). Settings › "Kept by you" lists both and can undo them. |
| M16-3 Swipe review (REV-5) | ✅ merged ([#28](https://github.com/tomerar/keepCleanr/pull/28)); 1 Codex finding fixed (the previous picture never shows while the next one loads) | | Duplicate/similar groups › "Review one by one": swipe toward Delete or Keep (mirrored in Hebrew), Skip, Undo. Nothing happens until the summary hands the chosen items to the usual editable confirmation; nothing is pre-selected. |
| M16-2 Kept by you + always junk (CLS-7, SET-2) | 🔄 in review | | Item screen: "This is important to me – never touch it" (the file becomes PROTECTED in every list **and at the delete choke point**) and "Always treat this folder as junk" (asks first; SAFE from then on, after the built-in PROTECTED list; recent files still need a look). Settings › Kept by you lists both with Remove. Choices are applied at once, without a new scan. |
| M16-3 Swipe review (REV-5) | 🔄 next PR | | Group screen › "Go through them one by one": one item at a time, swipe toward Delete or Keep (mirrored in Hebrew) or use the buttons, Skip, Undo. Nothing happens while swiping; the summary hands only the "Delete" choices to the usual editable confirmation (REV-3), then the 30-day trash. |
| M16-4 Space reminder (SET-3) | ✅ merged ([#29](https://github.com/tomerar/keepCleanr/pull/29)); no Codex findings | | Settings › "Remind me when space is low" (off by default): a daily check (WorkManager) posts at most one notification a week when less than 10% (or 2 GB) is free, with the last scan's "ready to clean" total. It only reminds: no background scan, no deletion. |
| M16a Cloned apps, Telegram, large screens (MSG-3, MSG-4, UI-2) | ✅ merged ([#30](https://github.com/tomerar/keepCleanr/pull/30)); 1 Codex finding fixed (readable clone storage holds its own trash) | | Cloned-app storage (`/storage/emulated/999`) scanned with the same rules when readable, "Cloned app" chip in the preview; a Home card when it exists but is hidden (C14, D16a-1). Telegram's folders were already covered; cache guidance stays on the Apps screen. Wide windows keep an 840 dp readable column (C13). |
| M13 Apps | ✅ merged ([#21](https://github.com/tomerar/keepCleanr/pull/21)); 1 Codex finding fixed (no uninstall in trial mode, SAFETY-9) | | Apps screen: usage access asked only there (ONB-7), apps by size, "not opened in about 6 months" (calendar months; apps installed less than 6 months ago never count), uninstall through Android's own window (`REQUEST_DELETE_PACKAGES`, documented in PRIVACY.md), App info for the rest; "Clear one app at a time" sorted by temporary-file size when usage access is on (SAFE-10). |

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

**Now (read-only, while travelling):** D0-1, D3-1, D3-2, D3-3, D5-1, D8-1 (look only; don't tap Delete), D13-1, D12-1. **With a computer or NAS at home:** D15-1. **When home (they delete or move files):** D6-x, D7-2, D10-x. D7-1 and D7-3 can be done any time but D7-3 clears caches.

| # | Milestone | Check | When | Status |
|---|---|---|---|---|
| D0-1 | M0 | Install the test APK; Home shows in Hebrew when the phone is in Hebrew; dark mode follows the system | Now | waiting for owner |
| D3-1 | M3 | Full scan with All files access: finishes in ≤ 60 s; used/free on Home within 1% of Settings › Storage | Now | waiting for owner |
| D3-2 | M3 | Permission flow: explainer appears before Android's dialog; "Selected photos" → partial banner; revoke media permission in Settings mid-scan → "incomplete" message, no crash | Now | waiting for owner |
| D3-3 | M3 | Parallel apps: if WhatsApp is cloned (Settings › Apps › Parallel apps), does KeepClear show the clone's media? *Optional (developer): `adb shell ls /storage/emulated/999` and `pm list users`.* | Now | waiting for owner |
| D5-1 | M5 | Scroll a 2,000-screenshot group: smooth, no visible jank | Now | waiting for owner |
| D8-1 | M8 | Review › Screenshots and AI images: are the groups right? Select a month and open the confirm list, then **Cancel** (deleting waits until home) | Now (read-only part) | waiting for owner |
| D12-1 | M12b | Needs the `play` build (W2): Home › "Photos and files from WhatsApp": does the picker open at WhatsApp's folder? After "Use this folder", does a new scan include sent photos and voice notes? Don't delete | Now (after W2) | waiting for owner |
| D16-1 | M16 | After D10-3 (a backup whose originals were freed and emptied from the trash): Move › Restore from a backup › choose the drive › choose one month › Restore. The photos come back in the gallery, in their old folder; a photo you had re-taken with the same name goes to Pictures › KeepClear Restored | Home | waiting for owner |
| D16a-1 | M16a | With All files access on OnePlus (Parallel apps) or Xiaomi (Dual apps) and a cloned WhatsApp: Scan. Either cloned WhatsApp media shows with a "Cloned app" label in the preview, or Home shows the "Cloned apps" card. Tell me which, and whether `/storage/emulated/999` is the real folder (Files app or any file manager) | Now (read-only) | waiting for owner |
| D17-1 | M17b | When the Nextcloud set-up screen exists: add your Nextcloud with an app password, Move one month there; the receipt says it was checked without downloading. Also try a wrong address name for the same server (certificate for another name): it must refuse | Home | waiting for M17b |
| D15-1 | M15 | Move › Use a network drive: type the computer's address, shared folder, user and password; Connect; back up a few photos; Check backup; then "Free … from the phone" for one of them and restore it from the Trash. Settings shows "Network: only to <address>" | Home (needs a computer or NAS that shares a folder) | waiting for owner |
| D13-1 | M13 | Home › Apps: turn on Usage access when asked. Are app sizes close to Settings › Apps? Is "Not opened in about 6 months" right for your 11 apps? Don't tap Uninstall | Now | waiting for owner |
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
| C5 | M12b: when a folder's access is taken back (or an SD card is out), its trash records are kept and never dropped as "file gone"; they come back when access does. |
| C6 | M12b: in a granted folder, untrusted trash items (sidecar signed by another install) restore inside that folder, to `<folder>/Download/KeepClear Restored/`, because nothing can move out of a granted folder. |
| C7 | M12b: the WhatsApp folder card shows only in the `play` build; in `foss`, All files access already covers WhatsApp. |
| C8 | M15: "Check backup" for a network drive reads every copy again over a brand-new connection instead of asking to unplug anything: all SMB reads come from the server, and the phone keeps no copy. The server may still answer from its own memory; making that durable is the server's job (research 06 §3.1). |
| C9 | M15: one network drive at a time; "Forget the network drive" in Settings removes it (and its sealed password). A job whose drive was forgotten pauses as "drive not connected" and never frees anything. |
| C10 | M16-1: restoring puts files back by path, so it needs All files access (the F-Droid/GitHub build). The Play build explains this and suggests copying from the drive with the Files app; a MediaStore-based restore for Play can come later. |
| C11 | M16-1: restoring runs in trial mode too, because it only adds files (nothing is deleted, moved or replaced). |
| C12 | M16-2: "Always treat this folder as junk" is offered only for folders at least two levels deep and never under DCIM, Pictures, Movies, Music, Documents or Android, nor for items that may be memories or chats. It never overrides the built-in PROTECTED list, and "kept" always wins over "junk". |
| C13 | M16a / UI-2: on wide windows (tablet, unfolded foldable, landscape) every screen keeps a readable 840 dp column in the middle; the two-pane list-detail layout from UI-2 is left for later. Phones are unchanged. |
| C14 | M16a / MSG-3: cloned-app storage is only looked for at `/storage/emulated/999` and only with All files access. Without that access KeepClear says nothing about clones rather than guess. Telegram's deep link to its storage settings is not added (no documented link); the Apps screen tells where to tap instead. |
| C15 | M17a: a Nextcloud copy whose server-computed SHA-256 matches counts as confirmed at once (no "Check backup" needed first), but every copy is still read back and re-hashed right before its original is freed. A missing or malformed `X-Hash-SHA256` falls back to read-back; a wrong one is a mismatch (copy redone, then failed). |
| C16 | M17a: WebDAV over plain http is refused except to the phone itself (`localhost`), even on home Wi-Fi; self-signed certificates are refused until certificate pinning exists. Names are treated as case-insensitive (some WebDAV servers are), and folders are deleted only when empty although WebDAV deletes folders with their contents. |
| C1 | Last-copy guard (owner 1.3): when a trashed archive lists this file as an extracted witness (sizes differ, so no same-hash check is possible), the file is also treated as the last copy and needs the confirm. |

## Waiting for owner

| # | What | Why |
|---|---|---|
| W2 | Play Console: create the app and an internal-testing track, upload a `play` build, and fill the `READ_MEDIA_*` declaration (tasks.md M12). Everything else in M12 is built and tested without it. | Needs your Play developer account. |
| W1 | Before installing the next test APK: in the **old** test app (named "KeepClear"), restore or empty the trash, then uninstall it. The new one is "KeepClear test" and installs alongside. | Test builds now have their own package so the public test key can't update a real install (PR B). |
| W3 | ADR 0011 amendment (M17a): WebDAV/Nextcloud uses a small built-in HTTP client instead of OkHttp, because Android's `HttpURLConnection` refuses WebDAV methods and no new dependency was wanted. OK, or should Nextcloud wait for OkHttp (shared with Dropbox)? | The module is not in the app yet; nothing ships until you decide. |
| W4 | M17b: a Nextcloud account (or any WebDAV server) to test against for real. Until then everything is tested against a fake server. | Needs your server or account. |

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
