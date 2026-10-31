# ADR 0006: 30-day trash for every user-file deletion; honest space accounting

Status: Proposed · Date: 2026-10-31

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
- **MOVE**: originals deleted after verified backup also go to the trash. The trash entry links to the verified copy, and offers "Release now: a verified copy is on <target>" (see open question Q1 in [requirements](../spec/requirements.md)).

## Consequences
- Restore is instant (rename back). Undo works even after app restart.
- Space is not freed until purge or Empty now; UI must make this obvious.
- Trash folders survive app uninstall (they're in shared storage, not `Android/media/<pkg>`), so the uninstall flow must offer "Empty trash first".

## Alternatives
- Copy into app-private storage (Clearo): doubles space, slow, fails when full.
- Permanent delete with only a confirmation (SD Maid SE): violates the core promise.
- Only MediaStore trash: doesn't cover WhatsApp `.nomedia` / SAF files.
