# KeepClear: Requirements

Status: **Draft for owner approval** · Date: 2026-10-31
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
| Verified copy | A file on a backup target whose SHA-256 was read back and matches the source ([ADR 0007](../adr/0007-verify-by-hash.md)). |
| Receipt | Human-readable record of a MOVE: target, count, bytes, verification level, date. |

---

## 1. Safety invariants (SAFETY) — apply to every feature, always MVP

These are non-negotiable. Each has a dedicated automated test.

| ID | Requirement |
|---|---|
| SAFETY-1 | THE SYSTEM SHALL NOT delete, move or overwrite any user file without an explicit user confirmation for that action in the current session. Scheduled or background deletion of user files is not allowed. |
| SAFETY-2 | THE SYSTEM SHALL route every user-file deletion through the Trash (ADR 0006). The only permanent deletions are: Trash purge after retention, "Empty now" with a second confirmation, and system-managed cache clearing (SAFETY-7). |
| SAFETY-3 | THE SYSTEM SHALL enforce a PROTECTED denylist at the single delete/move choke point (not only at scan time). Minimum list: `**/WhatsApp/Databases/**`, `**/WhatsApp/Backups/**`, `*.crypt*`, `msgstore*`, `wa.db*`, `**/.KeepClearTrash/**` (except by the trash module), the active backup target, `Android/data/<own package>`, and any path in user exclusions. Matching is canonical and case-insensitive on every volume and user root (SEC-6); messenger-specific PROTECTED paths are listed in §4a. IF a delete request targets a PROTECTED path, THEN THE SYSTEM SHALL refuse it, log it, and surface an internal error (a bug, never a user-facing choice). |
| SAFETY-4 | THE SYSTEM SHALL show, before every destructive confirmation, the exact file count and exact byte total of what will be affected, and where it will go (Trash / permanently). |
| SAFETY-5 | THE SYSTEM SHALL NOT delete an original as part of MOVE unless that exact file has a Verified copy (L3) on the target, and its size and modification time are unchanged since it was hashed. |
| SAFETY-6 | IF any item's state changed between scan and action (size, mtime, existence, ownership), THEN THE SYSTEM SHALL skip that item, report it as "changed, skipped", and never act on stale data. |
| SAFETY-7 | WHEN app caches are cleared by Android (system trim, Shizuku, `ACTION_CLEAR_APP_CACHE`), THE SYSTEM SHALL label the action "cleared by Android, cannot be restored, the app rebuilds it" before confirmation. |
| SAFETY-8 | THE SYSTEM SHALL keep a "keep at least one" invariant for duplicates: a duplicate group can never have all copies selected for deletion. |
| SAFETY-9 | THE SYSTEM SHALL provide a developer/QA **dry-run** mode in which every destructive operation is logged and simulated but not executed. |
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
| ONB-8 | v1 | S / both | THE SYSTEM SHALL offer an optional "Advanced access" sheet that detects Shizuku (installed / running / permission) and root, explains each in plain language, lists what each unlocks, and has a "Re-check" button. It SHALL warn that root-based setups can trip banking apps. | Pattern from AppManagerNG ([02](../research/02-reference-apps.md) §5). |
| ONB-9 | MVP | B / both | IF a permission is revoked while the app runs or a job is in progress, THEN THE SYSTEM SHALL pause affected jobs safely (no partial deletes), show what stopped and why, and resume after re-grant. | See [edge-cases.md](edge-cases.md) E-PERM-*. |

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
| CLS-5 | MVP | THE SYSTEM SHALL treat favourites (`IS_FAVORITE`), files in user albums the gallery marks as favourite, and files modified in the last 30 days as "keep" signals that downgrade SAFE→REVIEW and are never auto-selected. | — |
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
| SAFE-5 | MVP | B | THE SYSTEM SHALL offer "Let Android trim app caches" via `StorageManager.allocateBytes(getAllocatableBytes)`, reporting the bytes actually freed (measured before/after), labelled per SAFETY-7. | Test reports measured delta, never an estimate presented as fact. |
| SAFE-6 | MVP | F (foss) | WHERE all-files access is granted, THE SYSTEM SHALL offer "Clear apps' external caches" via `ACTION_CLEAR_APP_CACHE` (system dialog). | — |
| SAFE-7 | v1 | S/R | WHERE Shizuku or root is available, THE SYSTEM SHALL offer per-app internal cache clearing with per-app sizes. | — |
| SAFE-8 | MVP | B | THE SYSTEM SHALL detect **other apps' recycle bins** (gallery "Recently deleted" = MediaStore `IS_TRASHED`; OEM file manager bins such as ColorOS/OnePlus file manager; known paths from SD Maid SE trash DB) and show them as one "Already-deleted files" card with total size and a deep link or, where we have access, a SAFE action. | Owner's 3.8 GB case. Candidate path `/sdcard/.FileManagerRecycler` (from SD Maid SE `db_trash_files.json`, [03](../research/03-other-oss.md)) **[UNVERIFIED on device]**. |

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
| MOVE-3 | v1 | THE SYSTEM SHALL support an **SMB share** (PC/NAS on home Wi-Fi) as a target. | smbj (Apache-2.0); see [06](../research/06-backup-targets-and-verification.md). Adds INTERNET permission per ADR 0008. |
| MOVE-4 | Later | THE SYSTEM SHALL support WebDAV/Nextcloud (with `X-Hash`), SFTP, Immich (checksum API), and a PC companion over USB/ADB. | — |
| MOVE-5 | MVP | BEFORE copying, THE SYSTEM SHALL run a pre-flight: target free space ≥ selected bytes + 5%, file-system limits (FAT32 4 GiB max file size → block those files with explanation), filename sanitisation for target FS, case-collision and path-length checks, battery ≥ 20% or charging. | Each pre-flight failure has a specific, plain message and a fix. |
| MOVE-6 | MVP | THE SYSTEM SHALL copy each file to a temp name, compute SHA-256 during the copy, flush, rename to the final name, then read the target file back and compare SHA-256. | L3 verification ([ADR 0007](../adr/0007-verify-by-hash.md)). |
| MOVE-7 | MVP | THE SYSTEM SHALL preserve the source folder structure under `KeepClear/<device name>/<source>/<YYYY>/<MM>/` on the target (configurable), keep original filenames where legal, and record any rename in the manifest. | — |
| MOVE-8 | MVP | THE SYSTEM SHALL write `keepclear-manifest.jsonl` and `SHA256SUMS` to the target **after** all files in a batch verify. | `sha256sum -c SHA256SUMS` passes on a PC (documented in receipt). |
| MOVE-9 | MVP | WHEN verification finishes, THE SYSTEM SHALL show a **receipt**: target name, N files / X GB copied, N verified by SHA-256, N skipped with reasons, and only then a "Delete N originals from phone (X GB)" button. | Button absent or disabled if any selected file is not L3-verified; partial: "Delete the N verified files". |
| MOVE-10 | MVP | WHEN the user confirms deletion, THE SYSTEM SHALL re-check each original's size + mtime against the hashed values and target presence, then trash the originals (ADR 0006), linking each trash entry to its verified copy. | Changed files skipped (SAFETY-6). |
| MOVE-11 | MVP | THE SYSTEM SHALL run copy/verify as a resumable job (Room-persisted per-file state) that survives app kill, reboot, cable unplug and permission loss, resuming at the first unverified file. | Kill-and-resume test; unplug test on device. |
| MOVE-12 | MVP | WHILE a MOVE job runs, THE SYSTEM SHALL show a foreground notification with progress, ETA, pause and cancel, and SHALL use a `dataSync` foreground service (local targets) or user-initiated data transfer job (network targets, API 34+), handling the Android 15 6 h/24 h FGS limit by checkpointing. | — |
| MOVE-13 | MVP | THE SYSTEM SHALL keep a MOVE history (receipts) on the device, exportable as text. | — |
| MOVE-14 | MVP | IF the target cannot be read back (e.g. Google Drive via SAF, cloud providers that upload asynchronously), THEN THE SYSTEM SHALL allow copying but SHALL NOT offer deletion of originals, explaining why. | Detect by provider authority allowlist; unknown providers default to read-back attempt + warning. |
| MOVE-15 | v1 | THE SYSTEM SHALL be able to re-verify an existing backup (re-read target, compare to manifest) on demand. | — |
| MOVE-16 | MVP | THE SYSTEM SHALL tell the truth about Google Photos: "KeepClear can't see what's in Google Photos. If you use it, check there, or use its own Free up space." with a deep link. | No claims of Google Photos backup status ([06](../research/06-backup-targets-and-verification.md) §5). |

---

## 8. Trash (TRASH)

**User story:** If I made a mistake, I want my files back, exactly where they were, for 30 days; and I want to know trash still takes space.

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| TRASH-1 | MVP | THE SYSTEM SHALL move deleted user files into Trash: MediaStore trash for indexed media, `.KeepClearTrash/` same-volume rename for everything else, with one unified Trash screen. | Both backends tested; same UI. |
| TRASH-2 | MVP | THE SYSTEM SHALL keep trashed items 30 days (configurable 7–60), then purge them in a daily job and on app open. | Clock-skew tolerant (use stored deadline + monotonic checks). |
| TRASH-3 | MVP | THE SYSTEM SHALL restore any item to its original path (or a "Restored" folder if the path is taken/unavailable), with original name and mtime. | Restore after app restart, after reboot. |
| TRASH-4 | MVP | THE SYSTEM SHALL show trash size as "Takes X GB until <date>" and offer **Empty now** (all / group / item) with a second confirmation. | Home totals never count trashed bytes as free. |
| TRASH-5 | MVP | WHEN an item in trash has a verified backup, THE SYSTEM SHALL show "Verified copy on <target>" and allow releasing it early with one confirmation. | — |
| TRASH-6 | MVP | IF a trashed file is missing or modified when purge or restore runs, THEN THE SYSTEM SHALL report it and drop the index entry without error loops. | — |
| TRASH-7 | v1 | WHEN the user starts uninstalling KeepClear (Settings entry) THE SYSTEM SHALL warn if trash is non-empty and offer restore-all or empty-all. | Note: we cannot intercept system uninstall; mention in About + notifications. |

---

## 9. Internationalisation & accessibility (I18N, A11Y)

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| I18N-1 | MVP | THE SYSTEM SHALL ship complete Hebrew and English UI; Hebrew follows system locale or an in-app per-app language setting (Android 13 per-app locales). | CI fails on missing keys in either language. |
| I18N-2 | MVP | THE SYSTEM SHALL render correctly in RTL: mirrored layout, auto-mirrored directional icons, and bidi-safe composition of numbers, units, file names and paths (wrap with Unicode isolates `U+2068…U+2069`). | Screenshot tests in he and en; filenames with mixed Hebrew/Latin/digits. |
| I18N-3 | MVP | THE SYSTEM SHALL format sizes, counts and dates with locale-aware formatters (decimal GB, `Formatter.formatShortFileSize` semantics documented), using plurals resources for counts. | Native-speaker review of Hebrew copy before v1. |
| I18N-4 | MVP | THE SYSTEM SHALL use gender-neutral Hebrew phrasing (imperative plural / infinitive) where possible. | Copy review checklist. |
| A11Y-1 | v1 | THE SYSTEM SHALL support TalkBack labels for all actions, 200% font scale without truncating numbers, and 48 dp touch targets. | Accessibility scanner clean. |

---

## 10. Privacy & trust (PRIV)

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| PRIV-1 | MVP | THE SYSTEM SHALL NOT include analytics, crash-reporting SDKs, ads, or Google Play Services dependencies. | CI dependency allowlist. |
| PRIV-2 | MVP | THE SYSTEM SHALL NOT declare the INTERNET permission in MVP; when network targets arrive, network code is confined to target modules and contacts only user-entered hosts. | Merged-manifest check; lint rule. |
| PRIV-3 | MVP | THE SYSTEM SHALL use no fear language, no fake numbers, no "boost/speed/battery/virus" claims; all numbers are measured. | Copy review checklist ([07](../research/07-competitor-ux.md) §4). |
| PRIV-4 | v1 | THE SYSTEM SHALL offer an optional local debug log export (user-initiated, redacted paths option) for bug reports. | — |

---

## 10a. Security (SEC)

Threats and rationale: [threat-model.md](threat-model.md).

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| SEC-1 | MVP | THE SYSTEM SHALL treat trash sidecar JSON as untrusted: Room is the source of truth; sidecars are read only by `recover()`, carry an HMAC (Keystore key), and every restore destination (trusted or not) passes `RestorePathValidator` (no `..`/separators/NUL in names, canonical destination inside the same volume, outside `.KeepClearTrash`, not PROTECTED, no symlinked ancestors, never overwrite). IF a sidecar's MAC is missing or invalid, THEN THE SYSTEM SHALL mark the entry *untrusted-recovered* and restore it only after explicit confirmation, by default into `Download/KeepClear Restored/`. | E-SEC-1…3 fuzz tests. |
| SEC-2 | MVP | THE SYSTEM SHALL never extract archives; archive inspection reads only the central directory, rejects entries that are absolute or contain `..`/NUL/drive letters, requires name + size + CRC-32 match for SAFE, and caps entries (100k) and directory size (4 MiB). | E-SEC-4. |
| SEC-3 | MVP | THE SYSTEM SHALL never base a deletion on a backup manifest; manifests and target markers are parsed strictly and every path is validated as relative and inside `KeepClear/`. | E-SEC-5, E-SEC-6. |
| SEC-4 | MVP | THE SYSTEM SHALL move/trash paths with no-follow semantics, refuse actions on symlinked directories, and re-check size, mtime and inode/generation immediately before each action. WHERE strict mode is on, THE SYSTEM SHALL re-hash a MOVE original before trashing it. | E-SEC-7. |
| SEC-5 | MVP | THE SYSTEM SHALL export no component except the launcher activity, use explicit immutable PendingIntents, and never perform a destructive action from intent extras. Debug-only flags (dry-run) SHALL NOT exist in release builds. | E-SEC-8; merged-manifest CI check. |
| SEC-6 | MVP | THE SYSTEM SHALL match PROTECTED paths and exclusions on the canonical path, case-insensitively, after NFC normalisation and removal of zero-width/bidi control characters, on every volume and user root (`/storage/emulated/<n>/`, SD cards). | E-SEC-9 table test. |
| SEC-7 | MVP | THE SYSTEM SHALL ignore touches on destructive confirm buttons while the window is obscured by an overlay, and enable those buttons only 500 ms after the sheet appears. | E-SEC-10 UI test. |
| SEC-8 | MVP | THE SYSTEM SHALL build with Gradle dependency verification, pinned versions and SHA-pinned CI actions. | CI fails on an unverified artifact. |
| SEC-9 | v1 | WHERE Shizuku/root helpers are used, THE SYSTEM SHALL accept binder calls only from its own UID and expose only narrow operations (no generic shell from UI input). | Unit test on the service. |

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

---

## 13. Owner's device as acceptance scenario (MVP)

WHEN KeepClear MVP runs on the owner's OnePlus 10T (data of 28.10.2026), THE SYSTEM SHALL, with Basic + All-files (foss):
1. Show ≈ 8 GB as SAFE (recycle bins 3.8 GB, Sent duplicates found by hash, caches trimmed by Android, statuses, thumbnails, extracted zips). Exact figure = measured.
2. Show REVIEW buckets: 2,324 screenshots, 526 ChatGPT images, 81 videos > 100 MB (14.85 GB), old-phone leftovers (MIUI/ShareMe 2.8 GB, `/sdcard/WhatsApp` 2.58 GB, call recordings), WhatsApp Private 1.26 GB.
3. Offer MOVE of camera media older than 12 months to a USB-C drive, verified by SHA-256, then trash originals with a receipt.
4. Never list `msgstore*.crypt14` or anything under `WhatsApp/Databases|Backups`.

---

## 14. Open questions for the owner

| # | Question | Default if no answer |
|---|---|---|
| Q1 | MOVE originals: keep them in trash 30 days (space not freed for a month), or release immediately once L3-verified ("the backup *is* the undo")? | Trash, with a prominent one-tap "Release now (verified copy exists)". |
| Q2 | SAFE junk that's regenerable (thumbnails, statuses, temp): 30-day trash too, or a shorter 7-day retention? | 30 days, same as everything (simplest promise). |
| Q3 | MVP flavor: build `foss` (all-files) first for your phone, `play` backend (SAF/MediaStore-only) in v1? | Yes: foss first, play in v1; flavor scaffolding from day one. |
| Q4 | First backup target: USB-C drive/SD (MVP) then SMB on home PC (v1)? Do you have a USB-C drive ≥ 128 GB (exFAT) and/or a Windows PC with a shared folder? | USB-C drive first. |
| Q5 | Default MOVE age: older than 12 months? | 12 months, favourites excluded. |
| Q6 | App name/package: `KeepClear` with package `app.keepclear`? (repo is named `keepCleanr`.) | Ask before M0. |
| Q7 | WhatsApp `Sent/` copies without an on-device original: REVIEW (default) or SAFE ("WhatsApp already delivered it")? | REVIEW. |
