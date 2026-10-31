# ADR 0003: minSdk 30 (Android 11), targetSdk 36, compileSdk 37

Status: Proposed · Date: 2026-10-31

## Context
Every core primitive KeepClear relies on starts at API 30: `MediaStore.createTrashRequest`/`createDeleteRequest`, `IS_TRASHED`, MediaStore generations, `MANAGE_EXTERNAL_STORAGE`, `ACTION_CLEAR_APP_CACHE`, `QUERY_ALL_PACKAGES` ([04-android-apis §9](../research/04-android-apis.md)). Supporting API 29 or lower means a second storage model (legacy external storage, no system trash, no all-files access). Play requires targetSdk 36 since 2026-08-31. SD Maid SE uses minSdk 26 because it carries 10 years of legacy; we have none. The owner's device runs Android 15.

## Decision
- **minSdk 30**, **targetSdk 36**, **compileSdk 37**.
- API 31+ features are runtime-gated: `MANAGE_MEDIA` (dialog-free trash), `getManageSpaceActivityIntent`.
- API 34+: user-initiated data transfer jobs (network targets), typed FGS permissions, partial media access (`READ_MEDIA_VISUAL_USER_SELECTED`) handling.
- API 35+: handle `dataSync` FGS 6 h/24 h timeout via `onTimeout` + resumable checkpoints.
- Move target to 37 once LAN backup handles `ACCESS_LOCAL_NETWORK`.
- No native code in MVP (sidesteps the 16 KB page-size rule; any later native hash lib must be 16 KB-aligned).

## Consequences
- Drops Android 8–10 devices. Their share in late 2026 is **[UNVERIFIED]** but small; acceptable for a new app.
- One storage model to test (scoped storage).

## Alternatives
- minSdk 26 (SD Maid SE): more reach, two storage models, no system trash below 30.
- minSdk 29: still no trash request API, still legacy-storage edge cases.
