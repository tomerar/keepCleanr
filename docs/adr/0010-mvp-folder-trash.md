# ADR 0010: MVP uses the folder trash for every file it can reach by path

Status: Proposed (needs owner confirmation; see docs/progress.md B2) · Date: 2026-10-31
Amends: [ADR 0006](0006-own-trash.md)

## Context
ADR 0006 planned two physical trash backends: MediaStore's trash for indexed media, and KeepClear's `.KeepClearTrash` folder for everything else. While implementing M6 for the MVP (`foss` flavor with All files access):
- `MediaStore.createTrashRequest` always goes through a system `PendingIntent` that the UI must launch (a dialog per ≤ 2,000 items unless `MANAGE_MEDIA` is granted), and the system purges on its own schedule ([04](../research/04-android-apis.md)). Whether OnePlus Gallery shows those items is unverified.
- With All files access, a same-volume rename into `.KeepClearTrash/` works for media and non-media alike, keeps exact paths, needs no dialog per batch, and is covered by our sidecar/HMAC/recovery design (SEC-1).
- One backend means one code path to test with kill-point property tests.

## Decision
- **MVP (`foss` with All files access):** every user file whose path KeepClear can rename goes to the folder trash, including camera media.
- **`play` flavor (M11) and items KeepClear only knows as MediaStore rows:** MediaStore trash backend, as in ADR 0006.
- Everything else in ADR 0006 stands: 30-day retention, honest space accounting, "Empty now" with a second confirmation, "Release now (verified copy exists)".

## Consequences
- Trashed media disappears from the gallery (the trash folder has `.nomedia`) and comes back on restore; MediaStore re-indexes it with a new id.
- Other galleries' "Recently deleted" won't list KeepClear's trash; KeepClear's Trash screen is the place to restore.
- The MediaStore backend and its instrumented tests move to M11.

## Alternatives
- Implement both backends in M6: more UI plumbing (IntentSender per batch) and a second code path before the MVP needs it.
