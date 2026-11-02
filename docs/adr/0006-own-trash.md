# ADR 0006: 30-day trash for every user-file deletion; honest space accounting

Status: Accepted (2026-10-31), amended 2026-11-02 (owner) · Date: 2026-10-31

## Context
The product promise: every deletion goes to a 30-day trash first. Research shows:
- SD Maid SE, WhatsAppCleaner and CleanSweep delete permanently. Clearo's "encrypted bin" is a plain copy whose failure still deletes the original ([02](../research/02-reference-apps.md)).
- `MediaStore.createTrashRequest` (API 30) renames the file in place to `.trashed-<expiry>-name`. **It frees no space** until expiry (30 days, AOSP default) or explicit delete. It applies only to MediaStore-indexed files ([04](../research/04-android-apis.md)).
- Non-indexed files (WhatsApp `.nomedia` folders, zips, leftovers, SAF-only trees) need our own mechanism.
- The owner is at 93% full: a trash that silently holds 60 GB for a month defeats the purpose.

## Decision
- **One logical trash, two physical backends**, one Room index (`TrashEntry`):
  1. **MediaStore trash** for indexed media (`createTrashRequest`, chunks ≤2,000; `MANAGE_MEDIA` skips the dialog when granted).
  2. **KeepClear trash folder** for everything else: a same-volume **rename/move** (never copy) into `.KeepClearTrash/` with `.nomedia` at the root of the same volume/SAF tree, plus a sidecar manifest (original path, size, mtime, SHA-256 when known, deleted-at, reason).
- Retention 30 days by default. Purge runs in a daily WorkManager job and on app open.
- **Honest accounting**: trash bytes are shown as "will be freed on <date>", never as "freed". Every trash screen has **Empty now** (per item, per group, all), which requires a second explicit confirmation.
- **Exceptions, stated in the UI**: (a) app caches cleared by Android (`allocateBytes`, Shizuku `freeStorageAndNotify`, `ACTION_CLEAR_APP_CACHE`) cannot be trashed; they are regenerable and labelled "cleared by Android, cannot be restored, the app rebuilds it". (b) Files already in another app's recycle bin are emptied by deep link to that app or, with all-files, moved into our trash.
- **MOVE**: originals deleted after verified backup also go to the trash. The trash entry links to the verified copy, and offers a prominent **"Release now (verified copy exists)"** button (owner decision Q1, [requirements §14](../spec/requirements.md)). Retention is 30 days for every category (Q2).

## Consequences
- Restore is instant (rename back). Undo works even after app restart.
- Space is not freed until purge or Empty now; UI must make this obvious.
- Trash folders survive app uninstall (they're in shared storage, not `Android/media/<pkg>`), so the uninstall flow must offer "Empty trash first".

## Alternatives
- Copy into app-private storage (Clearo): doubles space, slow, fails when full.
- Permanent delete with only a confirmation (SD Maid SE): violates the core promise.
- Only MediaStore trash: doesn't cover WhatsApp `.nomedia` / SAF files.

## Amended 2026-11-02 (owner)

The owner's design QA of 2026-11-02 changes requirements Q2 for **two kinds only**. They are deleted for good right
after the clean's confirmation instead of going to the 30-day trash:

1. **Items that rebuild themselves** (owner decision B1's regenerable kinds): thumbnails, temporary files, OS junk
   (`.DS_Store`, `Thumbs.db`, `desktop.ini`, `.directory`, `._*`) and WhatsApp statuses older than 24 h (rules
   `thumbnails`, `temp_files`, `os_junk`, `wa_status_old`).
2. **Items already in another app's bin**: MediaStore `IS_TRASHED` rows (deleted through Android's delete request,
   only while still trashed), `.trashed-<digits>-<name>` files and `<volume root>/.FileManagerRecycler/` (rule
   `other_app_bin`). This replaces exception (b) above ("moved into our trash").

Everything else keeps this ADR unchanged: duplicates, extracted archives, LOST.DIR, "Always treat as junk", REVIEW
deletions and MOVE originals go to the trash for 30 days.

How it stays safe:
- **Decided at the choke point, per item, from data.** `ActionExecutor` asks `PermanentKinds` for each plain trash op:
  one file, classified SAFE (not moved to REVIEW by a keep signal), no duplicate group, no witnesses, and the rule
  **and** a strict, lossless path test name the same kind. It then hands the op to the trash module as
  `DELETE_FOR_GOOD`; a plan that asks for that itself is rejected. The delete stays inside `TrashManager` (a plain
  file by path, never a folder or anything in a KeepClear trash) and `MediaConsentHandler` (Android's delete request).
- **Same guarantees as trashing:** PROTECTED paths, "kept" paths, the re-check of size/date/identity, witness checks,
  the last-copy check, the confirmation in the current session and test mode (dry run) apply unchanged.
- **Said plainly before and after:** the confirmation says which part is deleted for good and why ("they rebuild
  themselves" / "they were already deleted once, so they can't be put back"); the result shows the **measured** free
  space for that part (`StatFs` before and after, off the main thread) and "<size> in the trash, freed on <date>" with
  Undo and Empty now for the rest. Undo covers only the trashed part.
- **Honest accounting** is unchanged for everything in the trash.

Tests: `PermanentKindsTest`, `PermanentDeleteTest` (each kind deleted, each other kind trashed, PROTECTED refused, test
mode, changed items, no confirmation), `PermanentKillPointPropertyTest` (the no-loss invariant for every other kind
under random kill points), `MediaConsentHandlerTest`, `CleanPlannerTest`, app `DesignQaTest`. Threat model T8b (SEC-10).
