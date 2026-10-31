# KeepClear: Edge cases

Status: **Draft for owner approval** · Date: 2026-10-31
Each case: **trigger → required behaviour → test**. IDs are referenced from [tasks.md](tasks.md). The global invariant behind all of them:

> **No-loss invariant.** At every instant, every user file KeepClear has touched is in exactly one of these places: its original path, the KeepClear/MediaStore trash (with a sidecar or MediaStore row), or a backup target where it is L3-verified *and* the original is still in trash. Property tests assert this after random kill points.

Test kinds: **U** = JVM unit/property with `FakeStorageGateway`, **I** = instrumented (emulator), **D** = manual on owner's device.

---

## 1. MOVE: copy and verify

| ID | Trigger | Behaviour | Test |
|---|---|---|---|
| E-MOVE-1 | Backup interrupted (app killed, OOM, reboot) mid-file | Temp file (`.kc-tmp-<uuid>`) on target is deleted on resume; file restarts from 0. Files already VERIFIED are not re-copied. Nothing on the phone was deleted (deletion only happens after the receipt). | U (kill at every state), I |
| E-MOVE-2 | USB cable / OTG drive unplugged mid-copy | IOException → job PAUSED("Drive disconnected"), notification "Reconnect the drive to continue". On reconnect the same SAF tree URI is re-validated (persisted permission + marker file `KeepClear/.keepclear-target-id`); if the user plugs a *different* drive, refuse to resume into it. | U, D |
| E-MOVE-3 | Target runs out of space mid-batch | Pre-flight should prevent it; if it still happens (other writers), stop at the current file, delete its temp, report "Drive full after N files (X GB). Free space on the drive or pick another." Already-verified files remain deletable. | U |
| E-MOVE-4 | **Phone** runs low on space during backup | Copy needs no phone space (stream), but Room/WAL and thumbnails do: keep a 100 MB reserve (LOW-2); if raw free space < reserve, pause and offer "Let Android trim caches" (SAFE-5). | U |
| E-MOVE-5 | Source file changed after hashing but before delete (edited, re-saved by Gallery) | At delete time size+mtime differ → SRC_CHANGED, skipped, re-queued; receipt shows "1 file changed since backup — backed up again or kept". | U |
| E-MOVE-6 | Source changed *during* copy (WhatsApp still downloading a video) | `stat` after copy differs from before → discard and retry later; files with mtime < 2 min ago are deferred to the end of the job. | U |
| E-MOVE-7 | Read-back hash mismatch (bad cable, flaky card, FUSE bug) | Re-copy up to 2×; still mismatching → FAILED("Copy on drive is damaged"), never deletable; suggest another drive. | U (corrupting fake) |
| E-MOVE-8 | File > 4 GiB to FAT32 target | Pre-flight detects the file system where possible (mount info via all-files on foss; otherwise unknown) and lists those files as "Can't copy to this drive (FAT32 limit). Reformat as exFAT or use another target." Unknown FS: attempt, catch `EFBIG`, mark FAILED with the same message. | U, D |
| E-MOVE-9 | Duplicate target names (two `IMG_0001.jpg` from different folders, or case-only differences on case-insensitive FS) | Name resolution at plan time: same rel path → append ` (2)`, ` (3)`; recorded in manifest `nameChanged`. Identical content already at target (same SHA-256) → reuse, mark VERIFIED without re-copy. | U |
| E-MOVE-10 | Target already contains a different file with the same name (previous backup, user files) | Never overwrite. Compare hash: equal → reuse; different → suffix. | U |
| E-MOVE-11 | Illegal characters / reserved names / trailing dots for FAT/exFAT/NTFS/SMB | Sanitize (`<>:"\|?*` and control chars → `_`, `CON`, `NUL`… → `_CON`), record original in manifest. | U (table) |
| E-MOVE-12 | Path too long (Windows SMB 260, FAT 255 per component) | Truncate the stem (keep extension, add short hash), record mapping. | U |
| E-MOVE-13 | Hebrew / emoji / NFD filenames | Normalize target names to NFC; compare names by NFC; keep original bytes in manifest. Test a file named `תמונה של אמא 🎂.jpg` and an NFD-decomposed name. | U, D |
| E-MOVE-14 | SAF provider reports success but data not durable (async cloud providers, e.g. Drive) | Provider not on read-back allowlist → copy allowed, delete disabled (MOVE-14), explained. | U |
| E-MOVE-15 | Android 15 `dataSync` FGS 6 h limit hit | `onTimeout` → checkpoint, stop FGS, notification "Paused by Android after 6 hours. Tap to continue." | I |
| E-MOVE-16 | Battery low / overheating | Pause below 15% unless charging; pause on `THERMAL_STATUS_SEVERE`; auto-resume when OK. | U (fake power), D |
| E-MOVE-17 | User revokes SAF grant to target mid-job | SecurityException → PAUSED("Access to the drive was removed") → re-pick same drive (marker check). | I |
| E-MOVE-18 | Target drive is also the source (user picks SD card that holds DCIM) | Pre-flight refuses: "This drive is where the photos are now." | U |
| E-MOVE-19 | Two MOVE jobs at once | Only one active job; second is queued. | U |
| E-MOVE-20 | Very many small files (10k JPEG) | Single sequential stream; manifest appended in batches of 500; progress by bytes and files. Benchmark target in NFR-3. | U (perf), D |
| E-MOVE-21 | Live Photos / motion photos / burst groups / RAW+JPEG pairs | Move all parts of a group together (same base name + known sidecars `.xmp`, `.dng`, `MVIMG_`/`PXL_*.MP` embedded). A group is deletable only if all parts verified. | U |
| E-MOVE-22 | The phone's clock is wrong / timezone change | Year/month buckets use `DATE_TAKEN` then EXIF then mtime; receipt shows the bucket source. | U |

## 2. Deletion, trash, restore

| ID | Trigger | Behaviour | Test |
|---|---|---|---|
| E-DEL-1 | WhatsApp writes to a folder while we trash files in it | Per-file stat check (SAFETY-6); files modified < 2 min ago excluded; never trash directories as a unit in WhatsApp trees, only files. | U |
| E-DEL-2 | Any path under WhatsApp `Databases/`, `Backups/`, `*.crypt*` reaches the executor (bug) | Guard rejects, logs, test fails. This must be impossible from the UI. | U (fuzz random plans incl. protected paths) |
| E-DEL-3 | `createTrashRequest` dialog denied by user | Nothing changes; plan marked "cancelled by you"; no retry loop. | I |
| E-DEL-4 | Batch > 2,000 URIs (target 36 cap) | Chunk into ≤ 2,000; one dialog per chunk unless `MANAGE_MEDIA`; progress "batch 2 of 4". | U, I |
| E-DEL-5 | SAF provider doesn't support move (`FLAG_SUPPORTS_MOVE` absent) | Item shown as "can't be put in trash here"; no hard delete fallback. User may choose "Back up then delete" (MOVE) instead. | U, I |
| E-DEL-6 | Restore target path is occupied (user saved a new file with same name) | Restore as `name (restored).ext`; tell the user. | U |
| E-DEL-7 | Restore target folder no longer exists | Recreate folder (if allowed) else restore to `Download/KeepClear Restored/`. | U |
| E-DEL-8 | Trash index (Room) lost (app data cleared / reinstall) | On start, `TrashManager.recover()` scans `.KeepClearTrash/*/*.json` sidecars and MediaStore `IS_TRASHED` items owned by us, rebuilds index. | U, I |
| E-DEL-9 | User or another cleaner deletes `.KeepClearTrash` | Index entries whose files are missing are dropped with a one-time notice. | U |
| E-DEL-10 | Device clock jumped forward 1 year (purge too early) | Never purge items trashed < 24 h of elapsed realtime ago; purge job logs clock anomalies. | U |
| E-DEL-11 | Partial success (some items failed: locked, permission, vanished) | Result screen lists succeeded/failed with reasons; totals only count succeeded. | U |
| E-DEL-12 | App uninstalled with non-empty trash | Trash folder remains (shared storage) → survives; after reinstall, recover() finds it. About screen and Trash screen explain this. | D |
| E-DEL-13 | Item is in MediaStore *and* a `.nomedia` folder edge (indexed before `.nomedia` added) | Prefer MediaStore trash if a row exists and file is at that path; else FolderTrash. | U |
| E-DEL-14 | Trashing would cross volumes (SD card file, trash on internal) | Never cross volumes: each volume has its own `.KeepClearTrash`. | U |
| E-DEL-15 | Undo immediately after a big clean | Snackbar "Undo" restores the whole plan (all ops) via TrashManager. | U, I |

## 3. Storage and devices

| ID | Trigger | Behaviour | Test |
|---|---|---|---|
| E-STO-1 | SD card removed during scan | Items on that volume disappear from results with a banner "SD card removed"; no errors for other volumes. | U |
| E-STO-2 | SD card removed while its trash has items | Entries marked "on removed SD card"; purge skips them; reappear when re-inserted (volume UUID). | U |
| E-STO-3 | Huge folders (100k+ entries, e.g. `.thumbnails`, WhatsApp Statuses) | Streaming list, paged insert, no full in-memory list; UI shows aggregate rows, not 100k cells. | U (perf) |
| E-STO-4 | Symlink loops / bind mounts (`/sdcard` vs `/storage/emulated/0`) | Canonicalize roots once; don't follow symlinks; dedupe by inode (File) or document id. | U |
| E-STO-5 | Hebrew / emoji / RTL-mark / zero-width chars in names | Display through bidi isolates; matching rules use NFC-normalized names. | U, screenshot |
| E-STO-6 | Files with no read permission / FUSE errors | Counted as "couldn't read N files"; never classified SAFE. | U |
| E-STO-7 | Private space / work profile (Android 15) | Out of scope; documented "KeepClear only sees your main profile". | Doc |
| E-STO-8 | Multiple users | Same as above. | Doc |
| E-LOW-1 | Phone has 40 MB free on launch | Minimal mode (LOW-2); app opens; first card is "Let Android trim caches"; no DB writes until space ≥ 100 MB. | U, I |
| E-LOW-2 | Space drops below 100 MB during a MOVE job | Job pauses after the current file; checkpoint written from the reserved budget; message "Phone almost full, paused". | U |
| E-LOW-3 | `ENOSPC` while writing a trash sidecar | Op aborted before the rename (LOW-3); file untouched. | U |
| E-LOW-4 | `SQLiteFullException` mid-scan | Scan stops, partial results shown as incomplete, no crash. | U |
| E-STO-9 | Owner's legacy `/sdcard/WhatsApp` *and* scoped `Android/media/com.whatsapp` both exist | Both shown; legacy is REVIEW "old folder from before 2022"; its `Databases/` is still PROTECTED. | U |
| E-STO-10 | WhatsApp Business (`com.whatsapp.w4b`) present | Same rules applied to its tree (MSG-1); its `Databases/`/`Backups/` PROTECTED (MSG-2). Items labelled "WhatsApp Business". | U (w4b fixture) |
| E-MSG-1 | Both WhatsApp and WhatsApp Business, plus a cloned WhatsApp under `/storage/emulated/999/` | Three separate groups; PROTECTED applies in all three; totals never double-count. | U (fixture with 3 roots) |
| E-MSG-2 | Cloned-app storage not readable | Info card, no error, no guessed numbers (MSG-3). | U |
| E-MSG-3 | Telegram media only in `Android/data` (no Shizuku) | Telegram card shows "Telegram keeps its files where Android doesn't let other apps look" + deep link; no numbers invented. | U |
| E-MSG-4 | Same photo in WhatsApp `Sent/` and Telegram `Pictures/Telegram` and Camera | Hash twins; Camera is canonical; the other two may be SAFE (WhatsApp Sent) or REVIEW (Telegram saved copy, user explicitly saved it). | U |

## 4. Permissions and lifecycle

| ID | Trigger | Behaviour | Test |
|---|---|---|---|
| E-PERM-1 | All-files / media permission revoked mid-scan | Scan stops cleanly; results so far marked incomplete; banner with re-grant action. | I |
| E-PERM-2 | Permission revoked mid-trash/executor | Current op fails safely (SecurityException), remaining ops not attempted, plan "stopped: permission removed". | U, I |
| E-PERM-3 | Partial media access (Android 14+) | Banner: "Seeing only N selected photos"; MOVE totals marked partial. | I |
| E-PERM-4 | Usage access denied | Apps tab shows cache trim (no permission) but not per-app sizes. | I |
| E-PERM-5 | SAF grant for WhatsApp lost after WhatsApp reinstall/update | Detect invalid URI → ask to re-grant with guide. | I |
| E-PERM-6 | Shizuku stops (reboot) (v1) | Tier drops to Basic/Full; Shizuku-only items greyed with "Start Shizuku". | I |
| E-LIFE-1 | Process death on any screen | State restored from Room/SavedState; no plan executes twice (plan id idempotency). | U, I |
| E-LIFE-2 | Configuration change (rotation, language switch to Hebrew mid-flow) | No lost selection; layout flips; plan unchanged. | UI test |
| E-LIFE-3 | OEM battery killer stops FGS (OnePlus) | Job resumable; if killed twice, show a one-time tip about battery optimisation. | D |

## 5. Classification correctness

| ID | Trigger | Behaviour | Test |
|---|---|---|---|
| E-CLS-1 | A photo in WhatsApp `Sent/` whose "twin" in Camera is a different edit (same name, different bytes) | Not a duplicate (hash differs) → REVIEW. | U |
| E-CLS-2 | A zip partially extracted / extracted then edited | Partial → REVIEW with "N of M files found next to it". | U |
| E-CLS-3 | User-favourite photo older than 12 months | MOVE eligible only if user selects it; never pre-selected (SAFETY-10, CLS-5). | U |
| E-CLS-4 | `Pictures/Screenshots` contains photos of documents the user needs | Screenshots are REVIEW, never SAFE; grouped by month with preview. | U |
| E-CLS-5 | Folder named like a known junk folder but contains user data (e.g. user's own `temp/` with documents) | Junk rules match on known parents/patterns only; `*.tmp` only by extension; unknown folders → REVIEW. | U (negative fixtures) |
| E-CLS-6 | Call recordings | Always REVIEW ("may contain conversations you want to keep"), offer MOVE. | U |
| E-CLS-7 | `.trashed-*` files (another app's MediaStore trash) | SAFE "already in gallery bin", with expiry date shown; action = MediaStore delete with confirmation. | I |

## 6. UX / trust edge cases

| ID | Trigger | Behaviour |
|---|---|---|
| E-UX-1 | Nothing to clean | Say so honestly ("Your junk is under 50 MB. Not worth it.") — no fake numbers. |
| E-UX-2 | Trash would hold most of the freed space | Completion screen shows "Moved to trash: 14 GB. Space returns on 30 Nov or tap Empty now." |
| E-UX-3 | User taps "Empty now" on a MOVE item without a verified copy | Second confirmation is stronger: type-free but explicit "This is the only copy." |
| E-UX-4 | User picks Google Drive as target | Explain no read-back; copy allowed, delete disabled. |

---

## 7. Security (E-SEC) — see [threat-model.md](threat-model.md)

| ID | Trigger | Behaviour | Test |
|---|---|---|---|
| E-SEC-1 | Sidecar `originalPath` uses `../`, an absolute path elsewhere, or points into WhatsApp `Databases/` | `RestorePathValidator` rejects; entry shown as "can't restore to that location" with option to restore into `Download/KeepClear Restored/`. Nothing is written outside allowed roots. | U (fuzz) |
| E-SEC-2 | Sidecar MAC missing/invalid (reinstall, edited by another app) | Entry becomes *untrusted-recovered*; restore needs explicit confirmation showing the destination. | U |
| E-SEC-3 | Sidecar oversized (> 64 KiB), malformed, unknown version | Ignored with a log line; the trashed file is listed as "unknown item" for manual review. | U |
| E-SEC-4 | Archive with `../` / absolute / backslash / NUL entry names, or > 100k entries | Never SAFE (→ REVIEW "unusual archive"); nothing extracted. | U (crafted zips) |
| E-SEC-5 | Manifest on drive with paths escaping `KeepClear/` or invalid hashes | Lines rejected; restore offers only valid entries; MOVE deletion unaffected (uses Room). | U |
| E-SEC-6 | Marker file copied to a different drive | Resume refused (tree/volume identity differs). | U |
| E-SEC-7 | File swapped for a symlink or a different file between plan and action | Skipped (identity changed); symlinks moved as links, never followed. | U (fake gateway) |
| E-SEC-8 | Another app sends an intent to our service/receiver | Not exported → `SecurityException` in the sender; no action. | Robolectric |
| E-SEC-9 | PROTECTED path written as `whatsapp/DATABASES`, NFD, with zero-width chars, or under `/storage/emulated/999/` | Still PROTECTED. | U (table) |
| E-SEC-10 | Overlay app covers the confirm sheet | Touches ignored; hint "Something is covering the screen". | UI |
