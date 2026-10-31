# ADR 0001: Native Kotlin + Jetpack Compose, not Flutter

Status: Accepted (2026-10-31) · Date: 2026-10-31

## Context
KeepClear's hard parts are platform-level: MediaStore trash/delete requests, SAF trees (`Android/media/com.whatsapp`), `StorageStatsManager`/`UsageStatsManager`, user-initiated data transfer jobs / foreground services, Shizuku AIDL user services, and fast file I/O with hashing. The UI is comparatively simple lists, cards and dialogs, but must be RTL-correct (Hebrew).

Every serious reference app is Kotlin/Java: SD Maid SE (Kotlin, Compose, Hilt, Room, WorkManager), CleanSweep (Kotlin/Compose/Hilt/Room), WhatsAppCleaner (Kotlin), AppManager (Java). Clearo is Flutter but drops into Kotlin for every storage operation, and its bin has a data-loss bug in exactly that seam ([02-reference-apps](../research/02-reference-apps.md)).

## Decision
Kotlin only. Jetpack Compose (Material 3) for UI, Hilt for DI, Room for state (jobs, trash index, hash cache), WorkManager + user-initiated jobs for long work, Coroutines/Flow throughout. No cross-platform layer. Android-only product.

## Consequences
- Direct use of every API in [04-android-apis](../research/04-android-apis.md) with no plugin glue.
- We can study and, with attribution, reuse GPL-3.0 Kotlin code from SD Maid SE / CleanSweep / WhatsAppCleaner (see ADR 0002).
- Compose has first-class RTL (`LayoutDirection`), mirrored icons via `autoMirrored`.
- No iOS version (iOS has no comparable storage model anyway).

## Alternatives
- **Flutter**: one UI codebase, but ~all value is in native code; adds a platform-channel seam where data-loss bugs hide; larger APK; F-Droid builds of Flutter apps are harder.
- **Kotlin + XML Views**: mature but slower to build and test; Compose is the current Android default.
