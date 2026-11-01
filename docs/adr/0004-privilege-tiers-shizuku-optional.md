# ADR 0004: Privilege tiers; Shizuku and root strictly optional

Status: Accepted (2026-10-31); amended 2026-11-01 (Shizuku tier moved to Later) · Date: 2026-10-31

## Context
What KeepClear can see and delete depends on access level ([04-android-apis](../research/04-android-apis.md), [01-sdmaid-se](../research/01-sdmaid-se.md)):
- No special access: MediaStore (with READ_MEDIA_* / partial access), SAF folder grants (incl. `Android/media/com.whatsapp`), usage access for app sizes.
- All files access (foss only): all shared storage, `ACTION_CLEAR_APP_CACHE` for external caches.
- Shizuku (ADB identity): `Android/data`, `Android/obb`, per-app internal cache clearing, `freeStorageAndNotify`.
- Root: everything.

Non-technical users fear and fail at ADB setup. F-Droid marks apps that *require* Shizuku as NonFreeDep (Canta). SD Maid SE uses its own Shizuku fork (Porter) whose license is unverified.

## Decision
- Four tiers: **Basic** (none) → **Full files** (all-files, foss only) → **Shizuku** → **Root**. The app is fully useful at Basic; MOVE (the core value) needs only Basic.
- Shizuku via the official `dev.rikka.shizuku:api` (Apache-2.0) behind an `AccessGateway` interface; root via `libsu` or SD Maid SE–style `app_process` + AIDL **[decide in M-later]**. Neither is required for any MVP feature.
- Each feature declares its minimum tier; the UI shows "available with Shizuku" rather than hiding items.
- Do not use the Shizuku name/icon as our own; refer to it as a third-party app.

## Consequences
- MVP is shippable without Shizuku/root code. Shizuku arrives in v1 for internal caches and `Android/data`.
- More code paths to test; mitigated by one gateway interface + fakes.

## Alternatives
- Require Shizuku (Canta model): loses most users, NonFreeDep on F-Droid.
- Bundle Porter: unknown license/maintenance; revisit later.

## Amendment (owner, 2026-11-01)
The Shizuku tier (ONB-8, SAFE-7, SEC-9) moves from v1 to **Later**. "Clear app caches" for everyone uses only Basic-tier APIs: `StorageManager.allocateBytes` (SAFE-5) and, in `foss` with all-files access, `ACTION_CLEAR_APP_CACHE` (SAFE-6). The tier model itself stands.
