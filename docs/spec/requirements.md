# KeepClear: Requirements

Status: **Approved by owner 2026-10-31**, updated with owner decisions of 2026-11-01 (§0a, §14) · Date: 2026-11-01
Inputs: [research 01–07](../research/), owner's measured device data (OnePlus 10T, Android 15, 240 GB, 93% full).

## 0. How to read this

- Requirements use **EARS** wording:
  - *Ubiquitous*: THE SYSTEM SHALL …
  - *Event*: WHEN <trigger>, THE SYSTEM SHALL …
  - *State*: WHILE <state>, THE SYSTEM SHALL …
  - *Unwanted*: IF <condition>, THEN THE SYSTEM SHALL …
  - *Optional*: WHERE <feature/tier>, THE SYSTEM SHALL …
- IDs are stable: `<AREA>-<n>`. Tests reference IDs (see [tasks.md](tasks.md)).
- Priority: **MVP** (first usable release, owner's phone), **v1** (first public release), **Later**.
- Tier = minimum access level needed ([ADR 0004](../adr/0004-privilege-tiers-shizuku-optional.md)): **B**asic, **F**ull files (foss), **S**hizuku, **R**oot.
- Flavor ([ADR 0005](../adr/0005-flavors-play-foss.md)): `foss`, `play`, or both.

### Glossary

| Term | Meaning |
|---|---|
| Item | One cleanable unit shown to the user: a file, a folder, a group (e.g. "Screenshots, March 2023"), or an app cache. |
| SAFE | Regenerable or provably duplicate data. One tap (after one confirmation) sends it to trash. |
| REVIEW | Possibly wanted. Explained in plain language; the user decides item by item or group by group. |
| MOVE | Memories (camera, WhatsApp received photos/videos, voice notes). Only removed after backup → verify → delete. |
| PROTECTED | Never offered for deletion, never deletable by any code path (e.g. WhatsApp `Databases/`, `Backups/`, `*.crypt*`). |
| Trash | KeepClear's 30-day recoverable holding area ([ADR 0006](../adr/0006-own-trash.md)). |
| Verified copy | A file on a backup target whose content is proven identical to the source: its SHA-256 was read back from the drive ([ADR 0007](../adr/0007-verify-by-hash.md)), or, for a cloud target, the provider computed a checksum from the stored bytes that matches ours ([ADR 0011](../adr/0011-network-and-cloud-targets.md), proposed). |
| Receipt | Human-readable record of a MOVE: target, count, bytes, verification level, date. |

### 0a. Product principles (owner, 2026-11-01)

These shape every requirement and screen. A requirement that conflicts with them is a spec bug.

| ID | Principle |
|---|---|
| P-1 | **Phone-only.** Everything a user needs works with only the phone: setup, scanning, cleaning, backup, checking a backup, freeing space, restoring. No computer is ever required. Steps that use a computer (`adb`, `sha256sum -c` on a PC) are developer checks only; the user-facing equivalent is the in-app **"Check backup"** (MOVE-15). |
| P-2 | **Simple by default, Advanced on request.** The default path is automatic and uses no technical terms (no "SHA-256", "SAF", "manifest", "cache partition"; paths only on demand). An optional **Advanced** area (Settings › Advanced, and "Details" on a receipt) shows hashes, file names on the drive, logs, the drive's format and expert options. |
| P-3 | **After a verified backup, always ask.** KeepClear shows the receipt; space is freed only when the user taps "Free up space" and confirms. Never automatic, never scheduled, never pre-selected (SAFETY-1, SAFETY-10). |

---

## 1. Safety invariants (SAFETY) — apply to every feature, always MVP

These are non-negotiable. Each has a dedicated automated test.

| ID | Requirement |
|---|---|
| SAFETY-1 | THE SYSTEM SHALL NOT delete, move or overwrite any user file without an explicit user confirmation for that action in the current session. Scheduled or background deletion of user files is not allowed. |
| SAFETY-2 | THE SYSTEM SHALL route every user-file deletion through the Trash (ADR 0006). The only permanent deletions are: Trash purge after retention, "Empty now" with a second confirmation, and system-managed cache clearing (SAFETY-7). |
| SAFETY-3 | THE SYSTEM SHALL enforce a PROTECTED denylist at the single delete/move choke point (not only at scan time). Minimum list: `**/WhatsApp/Databases/**`, `**/WhatsApp/Backups/**`, `*.crypt*`, `msgstore*`, `wa.db*`, `**/.KeepClearTrash/**` (except by the trash module), the active backup target, `Android/data/<own package>`, and any path in user exclusions. Matching is canonical and case-insensitive on every volume and user root (SEC-6); messenger-specific PROTECTED paths are listed in §4a. IF a delete request targets a PROTECTED path, THEN THE SYSTEM SHALL refuse it, log it, and surface an internal error (a bug, never a user-facing choice). |
| SAFETY-4 | THE SYSTEM SHALL show, before every destructive confirmation, the exact file count and exact byte total of what will be affected, and where it will go (Trash / permanently). |
| SAFETY-5 | THE SYSTEM SHALL NOT delete an original as part of MOVE unless that exact file has a Verified copy (L3) on the target **confirmed by a second read that can't come from the phone's cache** (drive unplugged and replugged, or a reboot; ADR 0007 amendment), the copy hashes correctly again at free time, and the original's size and modification time are unchanged since it was hashed. |
| SAFETY-6 | IF any item's state changed between scan and action (size, mtime, existence, ownership), THEN THE SYSTEM SHALL skip that item, report it as "changed, skipped", and never act on stale data. |
| SAFETY-7 | WHEN app caches are cleared by Android (system trim, Shizuku, `ACTION_CLEAR_APP_CACHE`), THE SYSTEM SHALL label the action "cleared by Android, cannot be restored, the app rebuilds it" before confirmation. |
| SAFETY-8 | THE SYSTEM SHALL keep a "keep at least one" invariant for duplicates: a duplicate group can never have all copies selected for deletion. Every plan op that relies on another copy carries its group, the group size and the witness files; the executor re-checks the witnesses right before acting. A witness is never in KeepClear's trash, on a removable volume or in a backup folder (review H2). |
| SAFETY-9 | THE SYSTEM SHALL provide a **test mode** (dry-run) in which every destructive operation is logged and simulated but not executed: trashing, restoring, emptying the trash, the daily purge of expired items, and asking Android to clear caches. Results say what *would* have happened ("would have freed 3.1 GB (test mode)"). **Debug/test builds start in test mode**, with an always-visible banner ("Test mode – no file will be deleted or moved" / "מצב בדיקה – שום קובץ לא יימחק או יועבר"); turning it off requires Settings › Advanced and two confirmations. Release builds start with it off. Copying to a backup drive still works in test mode (it changes no file on the phone). |
| SAFETY-10 | THE SYSTEM SHALL never auto-select MOVE or REVIEW items; only SAFE items may be pre-selected. |

---

## 2. Onboarding & permissions (ONB)

**User story:** As a non-technical user, I want to understand what KeepClear needs and why, so I can grant only what I'm comfortable with and still get value.

| ID | Pri | Tier/Flavor | Requirement | Acceptance criteria |
|---|---|---|---|---|
| ONB-1 | MVP | B / both | WHEN the app is opened for the first time, THE SYSTEM SHALL show a 3-screen intro: what KeepClear does (SAFE / REVIEW / MOVE), the trash promise, and the privacy promise (no ads, no tracking, no network). | Skippable; shown once; reachable later from Settings › About. Copy in [ux-flows.md](ux-flows.md) §1. |
| ONB-2 | MVP | B / both | THE SYSTEM SHALL request each permission only at the moment a feature needs it, with a plain-language explanation screen before the system dialog ("why", "what we will never do", "what works without it"). | Unit/UI test: no permission dialog appears on first launch before the user taps a feature. |
| ONB-3 | MVP | B / both | THE SYSTEM SHALL work with zero permissions granted, showing what it can (device total/free space) and a clear path to grant more. | Denying all permissions never crashes or dead-ends; every locked card has a "what's needed" action. |
| ONB-4 | MVP | B / both | WHEN media access is partial (Android 14+ "Selected photos"), THE SYSTEM SHALL say so ("KeepClear sees only N photos you picked") and offer to change it. It SHALL NOT present partial results as complete. | Test with `READ_MEDIA_VISUAL_USER_SELECTED` only. |
| ONB-5 | MVP | F / foss | WHERE the build is `foss`, THE SYSTEM SHALL offer "All files access" with an explanation of what it adds (WhatsApp/old-phone folders, recycle bins, leftovers) and that `Android/data` stays closed anyway. | Settings intent `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`; status re-checked on resume. |
| ONB-6 | MVP | B / play | WHERE all-files access is unavailable, THE SYSTEM SHALL request a SAF folder grant for `Android/media/com.whatsapp/WhatsApp` with `EXTRA_INITIAL_URI` pre-set and a 2-step illustrated guide, and SHALL validate that the picked folder contains `Media/`. | Wrong folder → friendly retry, not silent failure. |
| ONB-7 | MVP | B / both | THE SYSTEM SHALL request Usage Access (`PACKAGE_USAGE_STATS`) only when the user opens Apps, explaining it is used to read app sizes and last-used dates on the device only. | — |
| ONB-8 | Later | S / both | THE SYSTEM SHALL offer an optional "Advanced access" sheet that detects Shizuku (installed / running / permission) and root, explains each in plain language, lists what each unlocks, and has a "Re-check" button. It SHALL warn that root-based setups can trip banking apps. | Pattern from AppManagerNG ([02](../research/02-reference-apps.md) §5). |
| ONB-9 | MVP | B / both | IF a permission is revoked while the app runs or a job is in progress, THEN THE SYSTEM SHALL pause affected jobs safely (no partial deletes), show what stopped and why, and resume after re-grant. | See [edge-cases.md](edge-cases.md) E-PERM-*. |
| ONB-10 | MVP | B / both | WHEN the user starts the first MOVE job on API 33+, THE SYSTEM SHALL explain and request `POST_NOTIFICATIONS` ("So you can see backup progress and get told when it's done or needs you"). IF it is denied, THEN THE SYSTEM SHALL still run the job (foreground services don't need the permission; Android shows them in Task Manager but not in the notification drawer, [docs](https://developer.android.com/develop/ui/views/notifications/notification-permission)), show progress in the app, warn once "You won't see progress outside KeepClear", and show any pause/finish result on next app open. | Instrumented test with permission denied: job completes, result shown on reopen. |

---

## 3. Scan & overview (SCAN)

**User story:** As a user with a full phone, I want to see where my space actually is, in plain categories with real numbers, so I know what's worth doing.

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| SCAN-1 | MVP | WHEN the user opens Home, THE SYSTEM SHALL show total, used and free space (`StorageStatsManager`/`StatFs`) and a breakdown into: Photos & videos (camera), WhatsApp, Apps, Junk (SAFE), Other. | Numbers match `StatFs` within 1%; "Other" = remainder, labelled honestly. |
| SCAN-2 | MVP | THE SYSTEM SHALL show the three action totals as the main Home content: "Clean now (SAFE): X GB", "Review: Y GB", "Move to backup: Z GB". | Totals equal the sum of their items; never include trashed bytes. |
| SCAN-3 | MVP | THE SYSTEM SHALL stream scan results progressively and allow cancel at any time without side effects. | Cancel leaves no temp files, no partial DB state visible. |
| SCAN-4 | MVP | THE SYSTEM SHALL complete a full scan of the owner-scale dataset (≈ 10.5k camera files + ≈ 40 GB WhatsApp media + apps) in ≤ 60 s on a 2022 flagship, excluding hashing. | Benchmark test on a synthetic tree of equal shape (file count/depth). Device check on OnePlus 10T. |
| SCAN-5 | MVP | THE SYSTEM SHALL use MediaStore generations (API 30) and a (path, size, mtime) cache for incremental rescans. | Second scan with no changes ≤ 10 s. |
| SCAN-6 | MVP | THE SYSTEM SHALL handle any Unicode filename (Hebrew, emoji, RTL marks, NFC/NFD), very deep trees, symlink loops, and folders with 100k+ entries without crashing or blocking the UI. | Fixture tests; see edge cases. |
| SCAN-7 | v1 | THE SYSTEM SHALL show a storage history (free space over time, bytes freed by KeepClear) computed only on the device. | — |

### 3a. Nearly-full device (LOW)

**User story:** My phone is so full that apps fail. KeepClear must still open, must not make it worse, and must point me to what frees space without needing any.

Free space is measured with `StorageManager.getAllocatableBytes(UUID_DEFAULT)` (what the system could give us after trimming caches) and `StatFs` (raw free).

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| LOW-1 | MVP | WHILE raw free space < 500 MB, THE SYSTEM SHALL run in **light mode**: thumbnail disk cache off (memory only), scan index writes batched and capped, no storage-history samples, and a banner "Your phone is almost full. KeepClear is using as little space as possible." | Fake `SpaceProbe` test: no thumbnail files written; DB growth ≤ 5 MB during scan. |
| LOW-2 | MVP | WHILE raw free space < 100 MB, THE SYSTEM SHALL run in **minimal mode**: only actions that free space without writing user data are offered first ("Let Android trim caches", "Empty other apps' bins", "Empty KeepClear trash"), scan runs without persisting results (in memory), and MOVE start is blocked until ≥ 100 MB is free ("We need 100 MB on the phone to keep track of the backup safely"). | UI test per mode; MOVE start disabled with explanation. |
| LOW-3 | MVP | THE SYSTEM SHALL write the trash index entry and sidecar (**write-ahead**) before moving any file into trash. IF that write fails (e.g. `SQLiteFullException`, `ENOSPC`), THEN THE SYSTEM SHALL abort that op before touching the file and report "Not enough space to do this safely". | Fault-injection test: ENOSPC on journal write → file untouched. |
| LOW-4 | MVP | THE SYSTEM SHALL never crash on `SQLiteFullException`/`ENOSPC`; it degrades to read-only with a clear message. | Fuzzed ENOSPC at random points; no crash, no-loss invariant holds. |
| LOW-5 | MVP | THE SYSTEM SHALL keep its own footprint small: app data (DB + caches) ≤ 50 MB for the owner-scale dataset, shown in Settings with "Clear KeepClear's cache". | Measured in M5 device check. |

---

## 4. Classification & explanations (CLS)

**User story:** For every item I want to know *what it is*, *why it's in this category*, and *what happens if I delete it*, in my language.

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| CLS-1 | MVP | THE SYSTEM SHALL classify every item into exactly one of SAFE, REVIEW, MOVE, PROTECTED by a deterministic, data-driven rule set (rules are data, not scattered `if`s). | Table-driven tests: each rule has positive and negative path fixtures (SD Maid SE pattern, [01](../research/01-sdmaid-se.md) §9). |
| CLS-2 | MVP | THE SYSTEM SHALL attach to every item: a title, a "Why is this here?" text, a "What happens if I delete it?" text, and a risk chip (e.g. "Regenerates automatically", "Copy exists in Camera", "Might be a memory"). | No item renders without all three texts in both he and en (CI string-completeness check). |
| CLS-3 | MVP | THE SYSTEM SHALL classify camera photos/videos (`DCIM/Camera`, `DCIM/*`, `Pictures/*` except Screenshots) as MOVE, never SAFE. | — |
| CLS-4 | MVP | THE SYSTEM SHALL classify WhatsApp media by folder: received images/video/voice notes/video notes → MOVE (grouped by year/month); `Sent/` → SAFE if a byte-identical copy exists elsewhere on the device, else REVIEW ("a copy of something you sent; the original may already be gone"); `Private/` → REVIEW; `.Statuses` → SAFE when older than 24 h; Stickers/Wallpaper/Profile Photos/Animated Gifs → REVIEW; Documents → REVIEW; `Databases/`, `Backups/` → PROTECTED. | Fixture tree mirroring [02](../research/02-reference-apps.md) §1 layout. |
| CLS-5 | MVP | THE SYSTEM SHALL treat favourites (`IS_FAVORITE`, files in user albums the gallery marks as favourite) as "keep" signals that always downgrade SAFE→REVIEW. Files modified in the last 30 days are also downgraded SAFE→REVIEW, **except regenerable items** (owner decision B1, 2026-11-01): `.thumbnails`, `*.tmp/*.temp`, WhatsApp `.Statuses` older than 24 h and OS junk (`.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini`, `.directory`) stay SAFE regardless of age. Everything else that is SAFE (extracted archives, duplicates, other apps' recycle bins, `LOST.DIR`) still needs review when recent. Keep signals are never auto-selected. | Table tests: recent thumbnail, temp file, old status and OS junk stay SAFE; recent zip, duplicate, bin item and `LOST.DIR` file become REVIEW; a favourite thumbnail and a hidden-character path are REVIEW. Rules v3 snapshot. |
| CLS-6 | MVP | THE SYSTEM SHALL explain unknown items honestly: "We don't know what this folder is. Here's what's inside: …" rather than guessing. Unknown → REVIEW. | — |
| CLS-7 | v1 | THE SYSTEM SHALL let the user reclassify an item ("This is important to me" → PROTECTED for that path; "Always treat as junk" → SAFE rule), stored as local exclusions/rules. | Exclusions honoured at choke point (SAFETY-3). |

---

## 4a. Messengers, business variants and app clones (MSG)

**User story:** My WhatsApp Business, cloned "Parallel apps" and Telegram also fill my phone; they must get the same care, and their chat databases must be just as untouchable.

Messenger knowledge is **data** (`core:rules/messengers.json`): one entry per app variant with package, roots (scoped + legacy), media folder map, PROTECTED globs, and a `verified` flag. Entries with `verified=false` ship disabled until a device test confirms the layout.

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| MSG-1 | MVP | THE SYSTEM SHALL apply the WhatsApp rules (CLS-4) to **WhatsApp Business** (`com.whatsapp.w4b`): roots `Android/media/com.whatsapp.w4b/WhatsApp Business/` and legacy `/sdcard/WhatsApp Business/`; media folders `Media/WhatsApp Business Images|Video|Audio|Documents|Voice Notes|Video Notes|Animated Gifs|Stickers|Profile Photos`, with `Sent/` and `Private/` subfolders and `.Statuses`. Folder names are **[UNVERIFIED]** until checked on a device. | Fixture tree for w4b; table tests mirror the WhatsApp ones. |
| MSG-2 | MVP | THE SYSTEM SHALL treat as PROTECTED, for every WhatsApp variant and root (scoped, legacy, any user root): `**/WhatsApp*/Databases/**`, `**/WhatsApp*/Backups/**`, `**/*.crypt*`, `**/msgstore*`, `**/wa.db*`, `**/axolotl.db*`, `**/chatsettings*.db*`. | E-SEC-9 and E-MSG-1 tests. |
| MSG-3 | v1 | THE SYSTEM SHALL detect cloned-app profiles ("Parallel apps" on OnePlus/OxygenOS, "Dual apps" on Xiaomi/HyperOS), which reportedly run as Android user 999 with storage at `/storage/emulated/999/` ([XDA](https://www.xda-developers.com/add-any-app-oxygenos-parallel-apps-space-oneplus-phones), [Xiaomi FAQ](https://www.mi.com/global/support/faq/details/KA-484739/), path **[UNVERIFIED]**). WHERE that storage is readable (all-files/Shizuku), THE SYSTEM SHALL apply the same messenger rules under it and label items "Cloned WhatsApp". WHERE it is not readable, THE SYSTEM SHALL say "KeepClear can't see your cloned apps' files" and link to the system clone settings. | Device test: `adb shell pm list users`, `ls /storage/emulated/999` on OnePlus and Xiaomi. Fixture with a `999` root. |
| MSG-4 | v1 | THE SYSTEM SHALL support **Telegram** (`org.telegram.messenger`, plus `org.telegram.messenger.web`, `org.thunderdog.challegram` as unverified entries): (a) media saved to shared storage (`Pictures/Telegram`, `Movies/Telegram`, `Music/Telegram`, `Download/Telegram`) → MOVE for images/video, REVIEW for documents/music; (b) legacy `/sdcard/Telegram/` → REVIEW "old Telegram folder"; (c) the main cache in `Android/data/org.telegram.messenger/files/Telegram/` (reported in [Telegram bug tracker](https://bugs.telegram.org/c/11028/63), **[UNVERIFIED]** on current versions) is invisible without Shizuku → explain and deep-link to Telegram's own *Settings › Data and Storage › Storage Usage*; WHERE Shizuku is available, its cache is SAFE ("Telegram re-downloads it from the cloud", except secret chats → REVIEW). | Fixtures for (a)(b); Shizuku path tested in M14. |
| MSG-5 | Later | THE SYSTEM SHALL add other messengers (Signal, Viber, Messenger, WeChat) as `verified=false` entries, enabled only after a device check. Signal keeps media in app-private storage, so only files the user saved to the gallery are visible **[UNVERIFIED]**. | — |
| MSG-6 | MVP | THE SYSTEM SHALL never treat a messenger's media folder as SAFE as a whole; only files proven redundant (hash twin, expired status) are SAFE. | CLS table tests. |

---
## 5. SAFE cleanup (SAFE)

**User story:** WHEN something is clearly junk, I want it gone in one tap, without a lecture, but still recoverable.

| ID | Pri | Tier | Requirement | Acceptance criteria |
|---|---|---|---|---|
| SAFE-1 | MVP | B | WHEN the user taps "Clean now", THE SYSTEM SHALL show one confirmation listing groups with count + bytes (editable: user can untick groups or items) and then move all selected items to Trash. | One dialog, one tap to confirm. Completion screen shows "X GB moved to trash, freed on <date> (or Empty now)". |
| SAFE-2 | MVP | B | THE SYSTEM SHALL detect as SAFE: `.thumbnails` folders, `LOST.DIR` contents, `*.tmp/*.temp`, OS junk (`.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini`), empty folders (outside protected roots), WhatsApp `.Statuses` > 24 h, APK files whose package is installed at same/newer version. | Seeded from SD Maid SE SystemCleaner filters ([01](../research/01-sdmaid-se.md) §7.1), with attribution. |
| SAFE-3 | MVP | B | THE SYSTEM SHALL detect **extracted archives**: a `.zip` (or `.rar/.7z` if listable) whose entries all exist, same size, in a sibling folder → the archive is SAFE ("the unpacked folder next to it has the same files"). Partial matches → REVIEW. | Fixture: full match, partial match, renamed folder. |
| SAFE-4 | MVP | B | THE SYSTEM SHALL detect exact duplicates where one copy is the canonical location (e.g. WhatsApp `Sent/` copy of a `DCIM/Camera` original) using size → full SHA-256, and mark the non-canonical copy SAFE. | Never partial-file hashes for SAFE ([02](../research/02-reference-apps.md): CleanSweep pitfall). |
| SAFE-5 | MVP | B | THE SYSTEM SHALL offer **"Clear app caches"** to everyone, on Home and in Clean now, as the main cache path: it asks Android to trim app caches via `StorageManager.allocateBytes(getAllocatableBytes)` and reports the bytes actually freed (measured before/after), labelled per SAFETY-7 in plain words ("Android clears data that apps can download again"). No Shizuku, root or Accessibility needed. Android keeps a cache reserve (2–10% of the disk), so on a nearly full phone this can free little or nothing ([09 §1.2](../research/09-accessibility-cache-cleaner.md)); the result then says so plainly and points to SAFE-10. | Test reports the measured delta, never an estimate presented as fact; a 0-byte result shows the plain explanation, never "done!". Plain-language label on the default path (P-2). |
| SAFE-6 | MVP | F (foss) | WHERE all-files access is granted, THE SYSTEM SHALL extend "Clear app caches" with Android's own one-dialog clear of apps' external caches (`ACTION_CLEAR_APP_CACHE`), as part of the same main path, not a separate expert option. | The `play` flavor shows only SAFE-5. |
| SAFE-7 | Later | S/R | WHERE Shizuku or root is available, THE SYSTEM SHALL offer per-app internal cache clearing with per-app sizes. Lowered to Later by the owner (2026-11-01): SAFE-5/6 are the path for everyone. An Accessibility-based per-app cleaner for `foss` is researched in [09](../research/09-accessibility-cache-cleaner.md) and proposed in [ADR 0012](../adr/0012-foss-accessibility-cache-cleaner.md) (accepted 2026-11-01: none in v1; ADR 0008 still forbids Accessibility). | — |
| SAFE-8 | MVP | B | THE SYSTEM SHALL detect **other apps' recycle bins** (gallery "Recently deleted" = MediaStore `IS_TRASHED`; OEM file manager bins such as ColorOS/OnePlus file manager; known paths from SD Maid SE trash DB) and show them as one "Already-deleted files" card with total size and a deep link or, where we have access, a SAFE action. | Owner's 3.8 GB case. Candidate path `/sdcard/.FileManagerRecycler` (from SD Maid SE `db_trash_files.json`, [03](../research/03-other-oss.md)) **[UNVERIFIED on device]**. |
| SAFE-9 | v1 | F (foss) / B | THE SYSTEM SHALL show large app content in `Android/data` and `Android/media` (e.g. Spotify downloads, Telegram media, Netflix downloads) as a REVIEW card per app with its size, explaining in plain words that it belongs to that app and is cleared **inside that app** ("Open Spotify › Settings › Storage › Remove all downloads"), with `[Open app]` and `[Open app info]`. KeepClear never deletes inside another app's `Android/data` folder. | Per-app copy for the top known apps (en/he), generic copy otherwise; the card has no delete action. |
| SAFE-10 | v1 | B | THE SYSTEM SHALL offer a guided **"Clear one app at a time"** list (no special permission): apps sorted by cache size where Android reports it (usage access), each with `[Open app info]`; the user taps Android's own "Clear cache" there, and on return KeepClear shows the space actually freed. KeepClear never taps anything for the user. | Measured before/after per app; works in both flavors; plain copy (en/he) from [09 §4.3](../research/09-accessibility-cache-cleaner.md). |

---

## 6. REVIEW (REV)

**User story:** For things that might matter, I want a clear explanation and fast tools to decide in bulk, so I don't have to inspect 2,324 screenshots one by one.

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| REV-1 | MVP | THE SYSTEM SHALL group REVIEW items into understandable buckets with count + bytes: Screenshots (by month), AI-generated images (ChatGPT/Gemini/etc. folders and filename patterns), Large videos (> 100 MB, sorted by size), Downloads (> 90 days), Old-phone leftovers (`MIUI/`, ShareMe, legacy `/sdcard/WhatsApp`, call recordings folders), WhatsApp Private/Documents/Stickers. | Owner's data: 2,324 screenshots, 526 ChatGPT images, 81 videos > 100 MB appear as separate buckets. |
| REV-2 | MVP | THE SYSTEM SHALL provide a grid preview with thumbnails, multi-select, select-by-month, and full-screen preview for images/videos/audio. | Thumbnails load lazily; 2k-item grid scrolls at 60 fps on target device. |
| REV-3 | MVP | WHEN the user confirms a REVIEW deletion, THE SYSTEM SHALL show an editable confirmation (thumbnails, count, bytes; user can remove items) and then move to Trash. | WhatsAppCleaner pattern ([02](../research/02-reference-apps.md) §1). |
| REV-4 | MVP | THE SYSTEM SHALL offer "Move instead" on any REVIEW media bucket (e.g. large videos → MOVE flow). | — |
| REV-5 | v1 | THE SYSTEM SHALL offer a swipe mode (keep ← / delete →, undo, skip) with deferred changes and a summary before commit. | CleanSweep / SD Maid SE Swiper pattern. |
| REV-6 | v1 | THE SYSTEM SHALL list apps unused for ~6 months (monthly usage buckets + `firstInstallTime`), with app size (code + data + cache), and offer system uninstall (`ACTION_DELETE`) or Android's archive where supported. Wording: "not opened in about 6 months" (month precision). | Owner's 11 apps / 2.8 GB. Requires Usage Access. |
| REV-7 | v1 | THE SYSTEM SHALL detect leftovers of uninstalled apps (CorpseFinder-style marker DB) and present them as REVIEW, never SAFE, in v1. | foss only for full detection. |
| REV-8 | Later | THE SYSTEM SHALL detect similar photos (dHash/pHash, default threshold "Balanced"), suggest the best shot, and never auto-select. | — |

---

## 7. MOVE: backup → verify → delete (MOVE)

**User story:** As someone whose phone is full of memories, I want to move old photos, videos and WhatsApp media to my own drive or computer, with proof that the copy is good, and only then free the space.

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| MOVE-1 | MVP | THE SYSTEM SHALL let the user choose *what* to move by source (Camera, WhatsApp received media, a REVIEW bucket) and by period (year/month range; default: everything older than 12 months), showing count + bytes live. | Default excludes the last 12 months and favourites (configurable). |
| MOVE-2 | MVP | THE SYSTEM SHALL support as MVP targets any SAF tree the user picks, with first-class flows for **USB-C drive (OTG)** and **SD card**. | Picker via `ACTION_OPEN_DOCUMENT_TREE`; persistable permission. |
| MOVE-3 | v1 | THE SYSTEM SHALL support a **network drive / NAS / shared folder on home Wi-Fi** (SMB) as a target, set up from the phone alone (no PC software). | smbj (Apache-2.0); see [06](../research/06-backup-targets-and-verification.md), [08](../research/08-cloud-targets.md). No server checksum: verified by read-back. Network code confined to its module ([ADR 0011](../adr/0011-network-and-cloud-targets.md), proposed). |
| MOVE-4 | v1 / Later | THE SYSTEM SHALL support cloud targets from the phone alone, each in its own network module ([ADR 0011](../adr/0011-network-and-cloud-targets.md), accepted). Order (owner, 2026-11-01): **Google Drive first** (REST API, server-computed SHA-256; after a sign-in spike without Play Services; if it fails, SMB goes first), then SMB (MOVE-3), **Nextcloud/WebDAV** (server `X-Hash`), **Dropbox** (server `content_hash`). **OneDrive** offers no checksum KeepClear can trust: Later. SFTP, Immich and a PC companion stay Later. Details: [08](../research/08-cloud-targets.md). | Sign-in works without Google Play Services (both flavors); a checksum the app itself sent never counts. |
| MOVE-5 | MVP | BEFORE copying, THE SYSTEM SHALL run a pre-flight: target free space ≥ selected bytes + 5%, file-system limits (FAT32 4 GiB max file size → block those files with explanation), filename sanitisation for target FS, case-collision and path-length checks, battery ≥ 20% or charging. | Each pre-flight failure has a specific, plain message and a fix. |
| MOVE-6 | MVP | THE SYSTEM SHALL copy each file to a temp name, compute SHA-256 during the copy, flush, rename to the final name, then read the target file back and compare SHA-256. | L3 verification ([ADR 0007](../adr/0007-verify-by-hash.md)). |
| MOVE-7 | MVP | THE SYSTEM SHALL preserve the source folder structure under `KeepClear/<device name>/<source>/<YYYY>/<MM>/` on the target (configurable), keep original filenames where legal, and record any rename in the manifest. | — |
| MOVE-8 | MVP | THE SYSTEM SHALL write `keepclear-manifest.jsonl` and `SHA256SUMS` to the target **after** all files in a batch verify. | In-app "Check backup" (MOVE-15) re-verifies it. `sha256sum -c SHA256SUMS` compatibility is a developer test, mentioned only under Advanced (P-1, P-2). |
| MOVE-9 | MVP | WHEN verification finishes, THE SYSTEM SHALL show a **receipt**: target name, N files / X GB copied, N verified by SHA-256, N skipped with reasons, and only then a "Delete N originals from phone (X GB)" button. | Button absent or disabled if any selected file is not L3-verified; partial: "Delete the N verified files". Receipt also warns the drive copy is not encrypted (Q12). Plain language on the default path (P-2): "every file was read back and matches"; hashes and the `SHA256SUMS` file only under "Details". Freeing is never automatic (P-3). |
| MOVE-10 | MVP | WHEN the user confirms deletion, THE SYSTEM SHALL re-check each original's size + mtime against the hashed values and target presence, then trash the originals (ADR 0006), linking each trash entry to its verified copy. | Changed files skipped (SAFETY-6). |
| MOVE-11 | MVP | THE SYSTEM SHALL run copy/verify as a resumable job (Room-persisted per-file state) that survives app kill, reboot, cable unplug and permission loss, resuming at the first unverified file. | Kill-and-resume test; unplug test on device. |
| MOVE-12 | MVP | WHILE a MOVE job runs, THE SYSTEM SHALL show a foreground notification with progress, ETA, pause and cancel, and SHALL use a `dataSync` foreground service (local targets) or user-initiated data transfer job (network targets, API 34+), handling the Android 15 6 h/24 h FGS limit by checkpointing. | — |
| MOVE-13 | MVP | THE SYSTEM SHALL keep a MOVE history (receipts) on the device, exportable as text. | — |
| MOVE-14 | MVP | IF the target cannot be read back (e.g. Google Drive via SAF, cloud providers that upload asynchronously), THEN THE SYSTEM SHALL allow copying but SHALL NOT offer deletion of originals, explaining why. | Detect by provider authority allowlist. **Unknown providers are copy-only** (review H1; was "read-back attempt + warning"). |
| MOVE-15 | v1 (first) | THE SYSTEM SHALL offer **"Check backup"** in the app: re-read every file of a backup on the drive (or ask a cloud provider for its checksums), compare with KeepClear's records, and show a plain result ("All 8,909 files are fine" / "3 files are missing or changed"). This is how users check a backup; no computer needed (P-1). | Fault tests: missing, truncated and bit-flipped copies are reported; no technical terms on the default result screen. |
| MOVE-20 | v1 | WHERE the target is a cloud provider, THE SYSTEM SHALL offer "Free up space" for a file only after the provider's server-side checksum for that exact uploaded file matches the checksum KeepClear computed from the original. Uploads are resumable and survive app kill (MOVE-11). A provider or file without a checksum computed by the server is copy-only (MOVE-14). | Fake-provider tests: missing, mismatched and client-echoed checksums never allow freeing. |
| MOVE-21 | v1 | WHEN the user picks Google Drive, THE SYSTEM SHALL show, before copying, how much space the selection needs and how much is free in the Google account, in plain words ("Needs 58 GB. Your Google account has 6.2 GB free; it's shared with Gmail and Photos."), and SHALL NOT start a job that can't fit; it offers a smaller selection instead. | Quota from Drive `about.storageQuota`; tests for "fits", "doesn't fit" and "unknown quota". |
| MOVE-16 | MVP | THE SYSTEM SHALL tell the truth about Google Photos: "KeepClear can't see what's in Google Photos. If you use it, check there, or use its own Free up space." with a deep link. | No claims of Google Photos backup status ([06](../research/06-backup-targets-and-verification.md) §5). |
| MOVE-17 | v1 | WHEN the user connects a target that holds a KeepClear backup and taps "Restore from backup", THE SYSTEM SHALL list backed-up items from the manifest (by source and month, with thumbnails read from the target), copy selected files back to their original relative location (validated by `RestorePathValidator`, SEC-1/SEC-3) or `Pictures/KeepClear Restored/`, verify each copy by SHA-256 against the manifest **and** the target file, never overwrite, and re-index them in MediaStore. | Round-trip test: MOVE → delete → restore → bytes identical; hostile manifest rejected (E-SEC-5). |
| MOVE-19 | Later | WHERE the user opts in, THE SYSTEM SHALL encrypt backups on the target with a user password (format and recovery to be specified; must stay verifiable with a documented tool). | — |
| MOVE-18 | MVP | THE SYSTEM SHALL state in every MOVE receipt how to get files back: "Your files are on <target> in `KeepClear/…`. To bring them back, open them with any file manager or computer. In-app restore is coming." (v1: "…or tap Restore from backup."). The manifest SHALL record each file's original path to make manual and in-app restore possible. | Receipt UI test; manifest schema test. |

---

## 8. Trash (TRASH)

**User story:** If I made a mistake, I want my files back, exactly where they were, for 30 days; and I want to know trash still takes space.

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| TRASH-1 | MVP | THE SYSTEM SHALL move deleted user files into Trash: MediaStore trash for indexed media, `.KeepClearTrash/` same-volume rename for everything else, with one unified Trash screen. | Both backends tested; same UI. |
| TRASH-2 | MVP | THE SYSTEM SHALL keep trashed items 30 days (configurable 7–60), then purge them in a daily job and on app open. The same 30-day default applies to every category (owner decision Q2). | Clock-skew tolerant: an entry is purged only when both the wall clock and the stored uptime (counted across reboots) say 30 days have passed; a run where the clock jumped is refused and logged (E-DEL-10, review M2). |
| TRASH-3 | MVP | THE SYSTEM SHALL restore any item to its original path (or a "Restored" folder if the path is taken/unavailable), with original name and mtime. | Restore after app restart, after reboot. |
| TRASH-4 | MVP | THE SYSTEM SHALL show trash size as "Takes X GB until <date>" and offer **Empty now** (all / group / item) with a second confirmation. | Home totals never count trashed bytes as free. |
| TRASH-5 | MVP | WHEN an item in trash has a verified backup, THE SYSTEM SHALL show "Verified copy on <target>" and SHALL show a prominent **"Release now (verified copy exists)"** button (owner decision Q1) that releases it early with one confirmation. | — |
| TRASH-6 | MVP | IF a trashed file is missing or modified when purge or restore runs, THEN THE SYSTEM SHALL report it and drop the index entry without error loops. | — |
| TRASH-7 | v1 | WHEN the trash holds items, THE SYSTEM SHALL show on the Trash screen and in Settings › "Before you uninstall" (foss, folder trash per [ADR 0010](../adr/0010-mvp-folder-trash.md)): "Files in KeepClear's trash are kept in a hidden folder, .KeepClearTrash, on this phone. If you uninstall KeepClear, they stay there and keep taking space (now 3.1 GB). Restore or empty the trash first." with `[Restore all]` and `[Empty trash]`. | KeepClear can't intercept a system uninstall, so the warning is always visible while the trash is non-empty; en/he copy with the measured size. |

---

## 9. Internationalisation & accessibility (I18N, A11Y)

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| I18N-1 | MVP | THE SYSTEM SHALL ship complete Hebrew and English UI; Hebrew follows system locale or an in-app per-app language setting (Android 13 per-app locales). | CI fails on missing keys in either language. |
| I18N-2 | MVP | THE SYSTEM SHALL render correctly in RTL: mirrored layout, auto-mirrored directional icons, and bidi-safe composition of numbers, units, file names and paths (wrap with Unicode isolates `U+2068…U+2069`). | Screenshot tests in he and en; filenames with mixed Hebrew/Latin/digits. |
| I18N-3 | MVP | THE SYSTEM SHALL format sizes, counts and dates with locale-aware formatters (decimal GB, `Formatter.formatShortFileSize` semantics documented), using plurals resources for counts. | Native-speaker review of Hebrew copy before v1. |
| I18N-4 | MVP | THE SYSTEM SHALL use gender-neutral Hebrew phrasing (imperative plural / infinitive) where possible. | Copy review checklist. |
| A11Y-1 | v1 | THE SYSTEM SHALL support TalkBack labels for all actions, 200% font scale without truncating numbers, and 48 dp touch targets. | Accessibility scanner clean. |
| UI-1 | MVP | THE SYSTEM SHALL support light and dark themes (follow system by default, manual override in Settings), with WCAG AA contrast (4.5:1 text) in both, and Material You dynamic colour on Android 12+ as an option that never lowers contrast of destructive/warning elements. | Screenshot tests light/dark; contrast check on theme tokens. |
| UI-2 | v1 | THE SYSTEM SHALL adapt to window size classes: compact (phone), medium (foldable unfolded, small tablet), expanded (tablet/landscape) using list-detail layouts on medium/expanded; no locked orientation; fold/unfold and multi-window keep selection and running plans (E-LIFE-2). | Screenshot tests at 360×800, 673×841 (foldable), 1280×800 dp; config-change UI test. |
| UI-3 | MVP | THE SYSTEM SHALL run screenshot tests for the matrix {en, he} × {light, dark} × {compact, medium} × {font 100%, 200%} on Home, SAFE confirm, Review grid, MOVE receipt and Trash. | CI screenshot job (Roborazzi/Paparazzi). |

---

## 10. Privacy & trust (PRIV)

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| PRIV-1 | MVP | THE SYSTEM SHALL NOT include analytics, crash-reporting SDKs, ads, or Google Play Services dependencies. | CI dependency allowlist. |
| PRIV-2 | MVP | THE SYSTEM SHALL NOT declare the INTERNET permission in MVP; when network targets arrive, network code is confined to target modules and contacts only user-entered hosts. | Merged-manifest check; lint rule. |
| PRIV-3 | MVP | THE SYSTEM SHALL use no fear language, no fake numbers, no "boost/speed/battery/virus" claims; all numbers are measured. | Copy review checklist ([07](../research/07-competitor-ux.md) §4). |
| PRIV-4 | v1 | THE SYSTEM SHALL offer an optional local debug log export (user-initiated, redacted paths option) for bug reports. | — |
| PRIV-5 | MVP | THE SYSTEM SHALL have a public privacy policy at `docs/PRIVACY.md` (URL: https://github.com/tomerar/keepCleanr/blob/main/docs/PRIVACY.md), linked from Settings › About and every store listing, in English with a Hebrew summary. | Link present in About (UI test). Play Console privacy-policy URL field set. |
| PRIV-6 | MVP | THE SYSTEM SHALL keep PRIVACY.md in sync with the manifest: every permission in the merged `foss` and `play` manifests SHALL appear in its permissions table. | CI check parses merged manifests and fails if a permission is missing from PRIVACY.md. |
| PRIV-7 | MVP | Play Data safety form SHALL declare "No data collected, no data shared", consistent with PRIVACY.md. | Release checklist item (ADR 0009). |

---

## 10a. Security (SEC)

Threats and rationale: [threat-model.md](threat-model.md).

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| SEC-1 | MVP | THE SYSTEM SHALL treat trash sidecar JSON as untrusted: Room is the source of truth; sidecars are read only by `recover()`, carry an HMAC (Keystore key), and every restore destination (trusted or not) passes `RestorePathValidator` (no `..`/separators/NUL in names, canonical destination inside the same volume, outside `.KeepClearTrash`, not PROTECTED, no symlinked ancestors, never overwrite). IF a sidecar's MAC is missing or invalid, THEN THE SYSTEM SHALL mark the entry *untrusted-recovered* and restore it only after explicit confirmation, by default into `Download/KeepClear Restored/`. | E-SEC-1…3 fuzz tests. |
| SEC-2 | MVP | THE SYSTEM SHALL never extract archives; archive inspection reads only the central directory, rejects entries that are absolute or contain `..`/NUL/drive letters, requires name + size + CRC-32 match for SAFE, and caps entries (100k) and directory size (4 MiB). | E-SEC-4. |
| SEC-3 | MVP | THE SYSTEM SHALL never base a deletion on a backup manifest; manifests and target markers are parsed strictly and every path is validated as relative and inside `KeepClear/`. | E-SEC-5, E-SEC-6. |
| SEC-4 | MVP | THE SYSTEM SHALL move/trash paths with no-follow semantics, refuse actions on symlinked directories, and re-check size, mtime and inode/generation immediately before each action. THE SYSTEM SHALL re-hash every MOVE original (SHA-256) immediately before trashing it and skip it if the hash differs from the verified copy (**strict mode, on by default for all files**, owner decision Q13; can be turned off in Settings with a warning). | E-SEC-7; strict-mode test: original modified with same size+mtime → skipped. |
| SEC-5 | MVP | THE SYSTEM SHALL export no component except the launcher activity, use explicit immutable PendingIntents, and never perform a destructive action from intent extras. Debug-only flags (dry-run) SHALL NOT exist in release builds. | E-SEC-8; merged-manifest CI check. |
| SEC-6 | MVP | THE SYSTEM SHALL match PROTECTED paths and exclusions on the canonical path, case-insensitively, after NFC normalisation and removal of zero-width/bidi control characters, on every volume and user root (`/storage/emulated/<n>/`, SD cards). | E-SEC-9 table test. |
| SEC-7 | MVP | THE SYSTEM SHALL ignore touches on destructive confirm buttons while the window is obscured by an overlay, and enable those buttons only 500 ms after the sheet appears. | E-SEC-10 UI test. |
| SEC-8 | MVP | THE SYSTEM SHALL build with Gradle dependency verification, pinned versions and SHA-pinned CI actions. | CI fails on an unverified artifact. |
| SEC-9 | Later | WHERE Shizuku/root helpers are used, THE SYSTEM SHALL accept binder calls only from its own UID and expose only narrow operations (no generic shell from UI input). | Unit test on the service. |

---

## 11. Settings & control (SET)

| ID | Pri | Requirement |
|---|---|---|
| SET-1 | MVP | THE SYSTEM SHALL let the user set trash retention, MOVE default age, language, and view granted permissions with a link to revoke each. |
| SET-2 | v1 | THE SYSTEM SHALL let the user manage exclusions (paths never touched). |
| SET-3 | v1 | THE SYSTEM MAY send an opt-in, local reminder ("Space is low: 8 GB of junk ready to clean") but SHALL NOT delete anything in the background. |

---

## 12. Non-functional (NFR)

| ID | Pri | Requirement |
|---|---|---|
| NFR-1 | MVP | Scan never blocks the main thread; jank-free UI on the target device. |
| NFR-2 | MVP | Peak memory during scan of 100k files < 200 MB (streaming, no full in-memory trees of file contents). |
| NFR-3 | MVP | MOVE throughput ≥ 90% of the raw target write speed measured by a 1 GB test file (copy+hash), excluding read-back. |
| NFR-4 | MVP | Every destructive code path has unit tests with a fake file system and an instrumented test on a real device/emulator. |
| NFR-5 | MVP | Crash-free: no data loss on process death at any point (property tests with random kill points in copy/trash/restore). |
| NFR-6 | v1 | Reproducible `foss` build; F-Droid-ready metadata. |
| NFR-7 | MVP | Releases follow [ADR 0009](../adr/0009-signing-and-release.md): one app signing key with documented custody, SemVer + monotonic versionCode, CHANGELOG + fastlane changelogs (en, he), published APK SHA-256 and certificate fingerprint. |

---

## 12a. Upgrades and migrations (MIG)

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| MIG-1 | MVP | THE SYSTEM SHALL export Room schemas to `core/database/schemas/` (committed), provide an explicit `Migration` for every schema version bump, and SHALL NOT use destructive fallback migrations. | `MigrationTestHelper` tests migrate from **every** released schema to the current one with seeded trash/MOVE rows and assert no row loss; lint/Konsist forbids `fallbackToDestructiveMigration`. |
| MIG-2 | MVP | THE SYSTEM SHALL version the trash sidecar format (`"v": 1`) and the backup manifest format (`"v": 1` header line). Readers SHALL accept every earlier version; an unknown newer version is treated as untrusted-recovered, read-only (SEC-1). | Golden-file tests for each version in `testing/fixtures/formats/`. |
| MIG-3 | MVP | THE SYSTEM SHALL version the rules data (`schemaVersion`, `rulesVersion`). A rules change that moves anything toward SAFE SHALL come with a CHANGELOG entry and updated classification snapshot tests; rules never move anything into or out of PROTECTED without a test proving the PROTECTED set only grows. | Snapshot test over the owner fixture; PROTECTED-monotonic test. |
| MIG-4 | MVP | WHEN the app is updated during a MOVE job, THE SYSTEM SHALL resume from Room state after the update: a non-exported receiver for `MY_PACKAGE_REPLACED` posts "Backup paused by an app update. Tap to continue."; file states (stored as stable strings) resume per the state machine. | Instrumented test: install a newer build mid-job → resume completes; no file re-trashed or lost. |
| MIG-5 | MVP | WHEN the app restarts (update, crash, reboot) with a confirmed but unfinished `ActionPlan`, THE SYSTEM SHALL NOT continue deleting automatically; remaining ops need a new confirmation (SAFETY-1 "current session"). Copy/verify may resume automatically; deletion may not. | Kill-during-plan test → remaining ops pending, UI asks again. |
| MIG-6 | v1 | THE SYSTEM SHALL keep restoring from older trash entries and older backups after an update, including entries created by any earlier released version. | Fixtures from each release kept in `testing/fixtures/releases/`. |

---

## 13. Owner's device as acceptance scenario (MVP)

WHEN KeepClear MVP runs on the owner's OnePlus 10T (data of 28.10.2026), THE SYSTEM SHALL, with Basic + All-files (foss):
1. Show ≈ 8 GB as SAFE (recycle bins 3.8 GB, Sent duplicates found by hash, caches trimmed by Android, statuses, thumbnails, extracted zips). Exact figure = measured.
2. Show REVIEW buckets: 2,324 screenshots, 526 ChatGPT images, 81 videos > 100 MB (14.85 GB), old-phone leftovers (MIUI/ShareMe 2.8 GB, `/sdcard/WhatsApp` 2.58 GB, call recordings), WhatsApp Private 1.26 GB.
3. Offer MOVE of camera media older than 12 months to a USB-C drive, verified by SHA-256, then trash originals with a receipt.
4. Never list `msgstore*.crypt14` or anything under `WhatsApp/Databases|Backups`.

---

## 14. Owner decisions (closed questions)

Decided by the owner on 2026-10-31; the spec and ADRs above reflect these answers.

| # | Question | Decision |
|---|---|---|
| Q1 | MOVE originals: trash or release immediately? | Trash for 30 days, with a prominent **"Release now (verified copy exists)"** button (TRASH-5, ADR 0006). |
| Q2 | Retention for regenerable SAFE junk? | **30 days for everything**, plus "Empty now" (TRASH-2/4). No per-category retention. |
| Q3 | Flavor order | `foss` first; `play` in v1 (M11); flavor scaffolding from M0 (ADR 0005). |
| Q4 | First backup target | USB-C drive / SD card first (MVP); network drive (SMB) and cloud in v1. **2026-11-01:** the owner will use a 256 GB+ USB-C drive formatted **exFAT**. KeepClear reads the drive's format where Android shows it (the mount table) and lifts the 4 GB limit only for drives known not to be FAT32; if the format can't be read, the safe FAT32 limit stays and the screen says so. |
| Q5 | Default MOVE age | Older than 12 months, favourites excluded (MOVE-1). |
| Q6 | Name and package | App name **KeepClear**, applicationId **`io.github.tomerar.keepclear`**. The owner will rename the repo to `keepclear`; repository links get updated after the rename. |
| Q7 | WhatsApp `Sent/` without on-device original | REVIEW (CLS-4). |
| Q8 | One or two applicationIds | **One applicationId for both flavors** (as SD Maid SE), same signing key (ADR 0005, ADR 0009). |
| Q9 | Signing key custody | Owner holds it: password manager + encrypted USB at home (ADR 0009, no secrets in the repo). |
| Q10 | Security/CoC contact | GitHub private vulnerability reporting (owner enables it in repo settings). |
| Q11 | Translations | Via PRs until v1; Hosted Weblate later ([TRANSLATING.md](../TRANSLATING.md)). |
| Q12 | Backup encryption | Not in v1. Optional encryption is **Later** (MOVE-19). The receipt warns that the drive is unencrypted (MOVE-9). |
| Q13 | Strict mode | **On by default for all files**: re-hash every MOVE original right before trashing it (SEC-4, ADR 0007). |

### Owner decisions of 2026-11-01

| # | Topic | Decision |
|---|---|---|
| B1 | Keep signals vs regenerable junk | Thumbnails, temp files, WhatsApp statuses > 24 h and OS junk stay SAFE regardless of age; the 30-day rule applies to everything else (CLS-5, rules v3). |
| B2 | Folder trash | [ADR 0010](../adr/0010-mvp-folder-trash.md) accepted for `foss`. The uninstall warning says trashed files stay in `.KeepClearTrash` and take space (TRASH-7). |
| Q4 | Drive | 256 GB+ USB-C, exFAT; format detection where possible (Q4 above). |
| P-1…3 | Product principles | Phone-only; simple by default with an Advanced area; always ask before freeing space (§0a). |
| T | Backup targets | USB/SD (done). [ADR 0011](../adr/0011-network-and-cloud-targets.md) **accepted** with Google Drive first: Drive (after the sign-in spike; honest space check, MOVE-21) → SMB → Nextcloud → Dropbox; OneDrive Later. If the spike fails, SMB first. Research [08](../research/08-cloud-targets.md). |
| C | App caches | Shizuku lowered to Later (ONB-8, SAFE-7, SEC-9). SAFE-5/6 are the main "Clear app caches" path for everyone, plus a guided per-app list (SAFE-10) because SAFE-5 can free little on a nearly full phone. No Accessibility cleaner in v1 ([ADR 0012](../adr/0012-foss-accessibility-cache-cleaner.md), accepted); the guided per-app list (SAFE-10) ships instead. `Android/data` content gets explanation cards (SAFE-9). |
| D | Testing on the phone | CI publishes a signed `fossDebug` test APK on every push to `main` (Actions artifact, no Release). Read-only device checks first; destructive ones when the owner is home. |
