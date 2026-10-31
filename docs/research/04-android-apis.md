# 04 · Android platform APIs & limits for KeepClear

Status: research, verified 2026-10-31 against developer.android.com reference pages, source.android.com, and AOSP `main` source (android.googlesource.com). Anything not confirmed in an official source or in code is marked **[UNVERIFIED]**.

API levels: 26 = Android 8.0, 29 = 10, 30 = 11, 31 = 12, 33 = 13, 34 = 14, 35 = 15, 36 = 16 ("BAKLAVA"), 37 = 17. The reference docs already list Android 17 APIs, for example `QUERY_APP_USAGE` "Added in version 37.2" ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)).

---

## 0. TL;DR (10 facts that shape the design)

1. **System trash = 30 days, and it does not free space.** `createTrashRequest` (API 30) sets `IS_TRASHED`. MediaProvider renames the file in place to `.trashed-<expiry>-<name>` and deletes it only after `DATE_EXPIRES`, which defaults to 30 days, during idle maintenance. Until then the bytes stay on disk. ([MediaColumns.DATE_EXPIRES](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns), [FileUtils.java](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/util/FileUtils.java))
2. **2,000 URIs per request** for `createDelete/Trash/Write/FavoriteRequest` when the app targets API 36+. More throws `IllegalArgumentException`. ([MediaStore](https://developer.android.com/reference/android/provider/MediaStore))
3. **MANAGE_MEDIA (API 31) removes the confirmation dialog** from those requests, but only when the app also holds READ_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE. It grants no read or write access by itself. ([Manifest.permission.MANAGE_MEDIA](https://developer.android.com/reference/android/Manifest.permission))
4. **MANAGE_EXTERNAL_STORAGE ("All files access") reaches `Android/media`, which is where WhatsApp keeps its media, but not other apps' `Android/data` or `Android/obb`.** ([manage-all-files](https://developer.android.com/training/data-storage/manage-all-files))
5. **The SAF lockout of `Android/data` and `Android/obb` started in Android 11. Android 13 closed the loophole that got around it.** ([documents-files](https://developer.android.com/training/data-storage/shared/documents-files), [Esper](https://esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524))
6. **Any app can trim every app's cache with no special permission.** `StorageManager.allocateBytes(uuid, n)` makes the system run `freeStorage()`, which deletes other apps' caches above the reserved minimum. ([StorageManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/StorageManagerService.java))
7. **`ACTION_CLEAR_APP_CACHE` (API 30) requires MANAGE_EXTERNAL_STORAGE** and clears only *external* cache directories, after a user dialog. ([StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager))
8. **Usage history:** daily data is kept 10 days, weekly 4 weeks, monthly 6 months, yearly 2 years (AOSP). An "unused for 6 months" check therefore relies on coarse monthly or yearly buckets. ([UsageStatsDatabase.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usage/java/com/android/server/usage/UsageStatsDatabase.java))
9. **Android 15 (target 35+):** `dataSync` and `mediaProcessing` foreground services get 6 hours per 24 hours, tracked per type. The timer resets when the user brings the app to the foreground. ([FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout))
10. **Play rule:** new apps and updates must target API 36 from 2026-08-31 ([target-sdk](https://developer.android.com/google/play/requirements/target-sdk)). Updates that do not support 16 KB pages are blocked from 2027-02-01 ([page-sizes](https://developer.android.com/guide/practices/page-sizes)).

---

## 1. MediaStore (shared media index)

### 1.1 Batch request APIs (all API 30)

| API | What happens on approval | Notes |
|---|---|---|
| `createTrashRequest(resolver, uris, value)` | Sets `IS_TRASHED` (value=false untrashes) | Same prompt/result pattern as below |
| `createDeleteRequest(resolver, uris)` | Calls `ContentResolver.delete` — **permanent** | |
| `createWriteRequest(resolver, uris)` | Grants your app write access | Grant tied to requesting Activity lifecycle; not persistable; can hand to `JobInfo.Builder.setClipData` |
| `createFavoriteRequest(resolver, uris, value)` | Sets `IS_FAVORITE` | |

Source: [MediaStore reference](https://developer.android.com/reference/android/provider/MediaStore).

Shared behaviour (per reference):
- The method only returns a `PendingIntent`. Launch it with `startIntentSenderForResult` and check `RESULT_OK`. "The requested operation will have completely finished before this activity result is delivered."
- The prompt lists every item, including ones you can already write. Use `Context.checkUriPermission(..., FLAG_GRANT_WRITE_URI_PERMISSION)` to check access beforehand.
- Each URI must point to a specific MediaStore `_ID` (`content://media/...`). SAF and file URIs are not accepted.
- **Batch limit:** "if your app targets `BAKLAVA` and above, you can send a maximum of 2000 uris in each request". More throws `IllegalArgumentException`. Below target 36 there is no documented limit, but very large batches risk Binder transaction limits **[UNVERIFIED]**. KeepClear should chunk to ≤2000 regardless of target.
- Before Android 11, the per-file route was catching `RecoverableSecurityException` ([media guide](https://developer.android.com/training/data-storage/shared/media)).

### 1.2 Skipping the confirmation dialog

| Route | Dialog? | Source |
|---|---|---|
| Default (READ_MEDIA_* only) | **Yes**, each request | [media guide](https://developer.android.com/training/data-storage/shared/media) |
| **MANAGE_MEDIA** (API 31, `signature\|appop\|preinstalled`; user grants it under Settings › Special app access › Media management via `Settings.ACTION_REQUEST_MANAGE_MEDIA`; check with `MediaStore.canManageMedia()`) | **No** for trash/delete/write requests. `createWriteRequest` still prompts unless the app also holds `ACCESS_MEDIA_LOCATION` | [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission), [Settings](https://developer.android.com/reference/android/provider/Settings) |
| MANAGE_EXTERNAL_STORAGE | `create*Request` **still shows the dialog**: only MANAGE_MEDIA suppresses it. All-files apps do not need the requests, though. MediaProvider's `checkCallingPermissionGlobal()` returns true for "apps that have permission to manage external storage", so they can call `ContentResolver.delete()` / `update(IS_TRASHED=1)` or `File.delete()` directly | [MediaProvider.java](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/MediaProvider.java) (`checkCallingPermissionGlobal`, `sMutableColumns` includes `IS_TRASHED`) |
| OEM preinstalled gallery / system gallery (`MediaStore.isCurrentSystemGallery`, API 31) | Can set `IS_TRASHED=1` directly | [media guide](https://developer.android.com/training/data-storage/shared/media) |

- The MANAGE_MEDIA doc requires "READ_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE". On API 33+, READ_EXTERNAL_STORAGE "has no effect" ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)). For a target-36 app, the effective prerequisite is presumably READ_MEDIA_IMAGES/VIDEO **[UNVERIFIED: test on device]**. MediaProvider checks MANAGE_MEDIA through the app-op (`PermissionUtils.checkPermissionManageMedia`, [PermissionUtils.java](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/util/PermissionUtils.java)).
- Writing `IS_TRASHED=1` directly as an all-files app follows from the code above but has not been tested on OxygenOS **[UNVERIFIED on device]**.

### 1.3 Trash semantics (verified in code)

- `IS_TRASHED` (API 30): "Trashed items are retained until they expire as defined by `DATE_EXPIRES`." ([MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns))
- `DATE_EXPIRES` (API 29, read-only, seconds since epoch): "The default pending expiration is typically 7 days, and the default trashed expiration is typically 30 days. Expired media items are automatically deleted once their expiration time has passed, typically during the next device idle period." (same page)
- AOSP constants: `DEFAULT_DURATION_TRASHED = 30 * DAY_IN_MILLIS`, `DEFAULT_DURATION_PENDING = 7 days`, `DEFAULT_DURATION_EXTENDED = 7 days`, `PREFIX_TRASHED = "trashed"` ([FileUtils.java](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/util/FileUtils.java)).
- **On-disk effect:** the file is renamed in place, e.g. `.trashed-<epochSeconds>-IMG_1.jpg`. MediaProvider's own comments describe these names (`deleteOrExtendExpiredItems`, [MediaProvider.java](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/MediaProvider.java)). **Space is NOT reclaimed until expiry.**
- **Clock-safety rule:** idle maintenance deletes items only if they expired within the last week. Items that expired longer ago are *extended* by another 7 days "to avoid data loss with incorrect time zone data". Items on unmounted volumes are deleted when the volume is forgotten. (same file)
- Trashed rows are hidden from queries by default. Use `QUERY_ARG_MATCH_TRASHED` = `MATCH_INCLUDE` or `MATCH_ONLY` in the `Bundle` passed to `query`/`update`/`delete` (API 30). `QUERY_ARG_MATCH_PENDING` and `QUERY_ARG_MATCH_FAVORITE` work the same way ([MediaStore](https://developer.android.com/reference/android/provider/MediaStore)).
- Whether OnePlus Gallery or Google Photos show MediaStore-trashed items in their own "Recently deleted" views varies by OEM **[UNVERIFIED]**. Google Photos keeps its own cloud and app trash.

### 1.4 Useful columns

| Column | API | Notes |
|---|---|---|
| `IS_FAVORITE` | 30 | Set via `createFavoriteRequest`. API 36 adds `markIsFavoriteStatus()` with no dialog if the app has READ_MEDIA_* (target > 33) or MANAGE_EXTERNAL_STORAGE. **Use as a "never suggest deleting" signal.** |
| `OWNER_PACKAGE_NAME` | 29 | May be NULL. "From `UPSIDE_DOWN_CAKE` [34] onwards, visibility and query of this field will depend on package visibility": rows are restricted to packages you can see, so QUERY_ALL_PACKAGES or `<queries>` matters. `ACCESS_MEDIA_OWNER_PACKAGE_NAME_PERMISSION` constant added in API 35 (protection level **[UNVERIFIED]**, probably privileged). |
| `GENERATION_ADDED` / `GENERATION_MODIFIED` | 30 | Monotonic, so incremental rescans can use `WHERE generation_modified > lastGen`. Check `MediaStore.getVersion(volume)` first: if it changed, generations were reset and a full rescan is needed. `MediaStore.getGeneration(ctx, volume)` returns the current value. More reliable than `DATE_MODIFIED`. |
| `RELATIVE_PATH`, `VOLUME_NAME` | 29 | Organisational only. Do not build raw paths from them. |
| `_DATA` | 1 | Absolute path. Read-only for apps targeting 30+. Path I/O through FUSE is allowed on 11+. |
| `IS_PENDING` | 29 | Expires after 7 days. |
| `INFERRED_DATE`, `QUERY_ARG_MEDIA_STANDARD_SORT_ORDER` | 36 | DATE_TAKEN, falling back to DATE_MODIFIED. Useful for "older than" filters. |
| `OEM_METADATA` | 36 | JSON blob written by the OEM. Read requires `ACCESS_OEM_METADATA_PERMISSION`. |
| `IS_DOWNLOAD`, `XMP`, `DOCUMENT_ID` / `INSTANCE_ID` | 29/30 | XMP IDs help find edited copies. |

Source for all: [MediaStore.MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns), [MediaStore](https://developer.android.com/reference/android/provider/MediaStore).

### 1.5 MediaStore blind spots that matter for this user

- **`.nomedia` folders are not indexed** (`MEDIA_IGNORE_FILENAME`, [MediaStore](https://developer.android.com/reference/android/provider/MediaStore)). WhatsApp's `Sent` and `Private` folders are commonly `.nomedia` folders **[UNVERIFIED]**: WhatsAppCleaner skips `.nomedia` files while walking ([FileBackend.kt](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/data/FileBackend.kt)). Those files therefore cannot go through `createTrashRequest` and need file or SAF access.
- With only READ_MEDIA_*, an app sees images, video and audio. Non-media files (zip, apk, docs) in the `MediaStore.Files` table require MANAGE_EXTERNAL_STORAGE ([manage-all-files](https://developer.android.com/training/data-storage/manage-all-files)).
- Android 14 partial access: if the user picks "Select photos", queries return only the selected items. Check `READ_MEDIA_VISUAL_USER_SELECTED` (§4.1).

---

## 2. Scoped storage history and restricted folders

| Version | Change | Source |
|---|---|---|
| 10 (29) | Scoped storage introduced. Apps can opt out with `requestLegacyExternalStorage`. `DATE_EXPIRES`, `IS_PENDING`, `RELATIVE_PATH`, `OWNER_PACKAGE_NAME` added | [Android 11 storage](https://developer.android.com/about/versions/11/privacy/storage) |
| 11 (30) | Enforced: `requestLegacyExternalStorage` is ignored when targeting 30. Added batch requests (createTrash/Delete/Write/Favorite), direct file-path access through FUSE, MANAGE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES. SAF can no longer pick the storage root, `Download/`, or anything in `Android/data` / `Android/obb` | [Android 11 storage](https://developer.android.com/about/versions/11/privacy/storage), [documents-files](https://developer.android.com/training/data-storage/shared/documents-files) |
| 11 (30) | WRITE_EXTERNAL_STORAGE "has no effect" when targeting R+ | [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission) |
| 12 (31) | MANAGE_MEDIA, `getManageSpaceActivityIntent`, FUSE passthrough (kernel-dependent) | §1.2, §5, [FUSE passthrough](https://source.android.com/docs/core/storage/fuse-passthrough) |
| 13 (33) | READ_EXTERNAL_STORAGE "has no effect" and is replaced by READ_MEDIA_IMAGES/VIDEO/AUDIO. **Loophole closed:** the SAF trick of passing `EXTRA_INITIAL_URI = primary:Android/data` worked through 12L and fails on 13 | [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission), [Esper](https://esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524) |
| 14 (34) | Selected-photos access (READ_MEDIA_VISUAL_USER_SELECTED). OWNER_PACKAGE_NAME filtered by package visibility | [partial access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access) |

**Owner said "13 blocked third-party access to Android/data & Android/obb even via SAF". Partly right.** Official docs show the SAF block on `Android/data` and `Android/obb` arrived in **Android 11** (API 30) ([documents-files](https://developer.android.com/training/data-storage/shared/documents-files)). Android 13 closed the *workaround*, a DocumentsUI hole for subdirectories ([Esper](https://esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524)). Third-party reports say Samsung backported the fix to Android 12 (July 2023 patch), and a MaterialFiles issue reports the workaround still working on an Android 13 beta **[UNVERIFIED, secondary sources]**. Practical conclusion for the owner's Android 15 phone: no `Android/data` or `Android/obb` access without Shizuku/ADB or root.

### 2.1 `Android/media` (WhatsApp: `Android/media/com.whatsapp/WhatsApp/...`)

| Access route | Read | Delete | Source |
|---|---|---|---|
| MANAGE_EXTERNAL_STORAGE | ✅ ("The `/sdcard/Android/media` directory is part of shared storage") | ✅ via File API / MediaStore | [manage-all-files](https://developer.android.com/training/data-storage/manage-all-files) |
| SAF `ACTION_OPEN_DOCUMENT_TREE` on `Android/media/com.whatsapp/WhatsApp` | ✅ | ✅ (`DocumentsContract.deleteDocument`) | Not in the documented restricted list. WhatsAppCleaner's Play flavour does exactly this, with `EXTRA_INITIAL_URI = primary:Android/media/com.whatsapp/WhatsApp` ([MainActivity.kt](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/MainActivity.kt)) |
| READ_MEDIA_* + MediaStore | Only indexed media (not `.nomedia` dirs, not `.crypt14`) | Through `createTrashRequest` / `createDeleteRequest` (dialog) | §1 |

- Docs warn that iterating many files through SAF is slow ("your app's performance might be reduced", [documents-files](https://developer.android.com/training/data-storage/shared/documents-files)). Query children with `DocumentsContract.buildChildDocumentsUriUsingTree` and a projection, not `DocumentFile.listFiles()`. Per-call costs are **[UNVERIFIED]** benchmarks.
- `StorageStats.getDataBytes()` includes `getExternalMediaDirs()`, so the 42 GB in WhatsApp's `Android/media` counts as WhatsApp "data" in per-app stats ([StorageStats](https://developer.android.com/reference/android/app/usage/StorageStats)). Do not double-count it.

---

## 3. MANAGE_EXTERNAL_STORAGE ("All files access", API 30)

- Request it by declaring it in the manifest and sending users to `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` with data `package:<pkg>` (or the generic `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`). Guard against `ActivityNotFoundException`. Check with `Environment.isExternalStorageManager()`. Protection level `signature|appop|preinstalled`. ([Settings](https://developer.android.com/reference/android/provider/Settings), [manage-all-files](https://developer.android.com/training/data-storage/manage-all-files))
- **Grants:** read/write to all shared storage, including `Android/media`; the `MediaStore.Files` table; the root of SD cards and USB OTG; write to internal-storage directories *except* `/Android/data/`, `/sdcard/Android` and "most subdirectories of /sdcard/Android". Also MediaProvider performance optimisations for bulk file operations ([manage-all-files](https://developer.android.com/training/data-storage/manage-all-files), [scoped storage AOSP](https://source.android.com/docs/core/storage/scoped)).
- **Does not grant:** other apps' `Android/data` and `Android/obb`, or `/data/data`. Through SAF it gives only what SAF would allow without it.
- Also unlocks `getManageSpaceActivityIntent` (API 31, together with QUERY_ALL_PACKAGES), `ACTION_CLEAR_APP_CACHE` (API 30), and `getStorageVolumesIncludingSharedProfiles` (API 33).
- **Play policy** ([Play Help 10467955](https://support.google.com/googleplay/android-developer/answer/10467955)): permitted uses are file management, backup & restore, anti-virus, document management, on-device search, disk/folder encryption, and device migration. Listed invalid uses are "Media Files access" and "Any File selection activity where the user manually selects individual files". **"Cleaner" is not a listed category.** KeepClear would have to qualify as file manager or backup ("MOVE memories") **[policy risk]**. In effect since May 2021 ([manage-all-files](https://developer.android.com/training/data-storage/manage-all-files)), which matches the owner's claim.
- Test grant without the Settings UI: `adb shell appops set --uid PKG MANAGE_EXTERNAL_STORAGE allow` (same page).

---

## 4. Per-app storage, usage and cache APIs

### 4.1 StorageStatsManager (API 26)

| Method | Permission for others | Notes |
|---|---|---|
| `queryStatsForPackage(uuid, pkg, user)` | PACKAGE_USAGE_STATS | Slower with `sharedUserId`; prefer `queryStatsForUid`. "May take several seconds", so call it off the main thread |
| `queryStatsForUid(uuid, uid)` | PACKAGE_USAGE_STATS | Faster |
| `queryStatsForUser`, `queryExternalStatsForUser` | PACKAGE_USAGE_STATS (always) | Whole-user totals, including the external breakdown |
| `getFreeBytes` / `getTotalBytes` | none | Free space *including reclaimable cache*: for display only |

Source: [StorageStatsManager](https://developer.android.com/reference/android/app/usage/StorageStatsManager). PACKAGE_USAGE_STATS is `signature|privileged|development|appop|retailDemo`. Declare it, then send users to `Settings.ACTION_USAGE_ACCESS_SETTINGS` ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission), [Settings](https://developer.android.com/reference/android/provider/Settings)). Play has no declaration form for it **[UNVERIFIED: no policy page found]**.

`StorageStats` ([ref](https://developer.android.com/reference/android/app/usage/StorageStats)):
- `getAppBytes()`: APK, odex and native libs, plus OBB on primary storage.
- `getDataBytes()`: data dir, cache, code cache, external files, external cache, *and external media dirs*.
- `getCacheBytes()`: cache and code cache, plus external cache.
- `getExternalCacheBytes()` (API 31).
- `getAppBytesByDataType()` (API 35).

### 4.2 UsageStatsManager (API 21)

- Requires PACKAGE_USAGE_STATS. Since Android 11, `queryUsageStats` returns `null` while the user is locked ([UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)).
- `UsageStats.getLastTimeUsed()` / `getLastTimeVisible()` (the latter API 29): the "last used" time.
- **Retention (AOSP `UsageStatsDatabase.prune`):** yearly files kept 2 years, monthly 6 months, weekly 4 weeks, daily **10 days**. File caps `MAX_FILES_PER_INTERVAL_TYPE = {100, 50, 12, 10}` ([UsageStatsDatabase.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usage/java/com/android/server/usage/UsageStatsDatabase.java)). OEMs may change this **[UNVERIFIED for OxygenOS]**.
- **Accuracy consequences:**
  - "Unused 6 months" is answerable only from monthly or yearly aggregates. Queries beyond 10 days are expanded "to the nearest whole interval period", so precision degrades to about a month.
  - An app *never* opened since install has no row. Fall back to `PackageInfo.firstInstallTime` / `lastUpdateTime`.
  - Data is wiped on factory reset or new phone, so a recently migrated phone shows everything as "recent" **[inference]**.
- `isAppInactive(pkg)` (API 23) refers to hours or days, not months. `getAppStandbyBucket()` reports only the calling app's own bucket.
- Android 17 adds `queryAppUsageDuration` with permission `QUERY_APP_USAGE`, which is `internal|role`, so third-party apps cannot use it ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)).

### 4.3 StorageManager

| API | Level | Permission | Behaviour |
|---|---|---|---|
| `ACTION_MANAGE_STORAGE` (+`EXTRA_UUID`, `EXTRA_REQUESTED_BYTES` API 26) | 25 | none | Opens the system "free up space" UI. `RESULT_OK` if space was made |
| `ACTION_CLEAR_APP_CACHE` | 30 | **MANAGE_EXTERNAL_STORAGE** | Dialog. Clears **external** cache dirs of all apps. Results: `RESULT_OK`, `EIO`, `RESULT_CANCELED`. Start with `startActivityForResult` |
| `getAllocatableBytes(uuid)` | 26 | none | Usable space plus clearable cache, minus the low-storage reserve |
| `allocateBytes(uuid, bytes)` / `allocateBytes(fd, bytes)` | 26 | none | "This will cause the system to delete any cached files necessary to satisfy your request" |
| `getCacheQuotaBytes(uuid)` / `getCacheSizeBytes(uuid)` | 26 | own app only | Apps over quota have their cache cleared first |
| `setCacheBehaviorGroup/Tombstone` | 26 | own app | KeepClear's own thumbnail cache |
| `getManageSpaceActivityIntent(pkg, req)` | 31 | **MANAGE_EXTERNAL_STORAGE** (docs say + QUERY_ALL_PACKAGES) | Opens another app's `android:manageSpaceActivity` even if unexported. Returns null if the app has none |
| `getStorageVolumes()` | 24 | none | Lists SD cards and USB drives |

Source: [StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager), [manage-all-files](https://developer.android.com/training/data-storage/manage-all-files), [application element](https://developer.android.com/guide/topics/manifest/application-element).

**`allocateBytes` semantics (AOSP, verified):**
- `adjustAllocateFlags()` enforces `ALLOCATE_AGGRESSIVE` *only* when `FLAG_ALLOCATE_AGGRESSIVE` is passed. That flag is a hidden API, so a normal app gets the non-aggressive path, which needs no permission.
- `allocateBytes()` then calls `mPmInternal.freeStorage(volumeUuid, bytes + storageLowBytes, flags)`, which deletes other apps' cached files system-wide ([StorageManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/StorageManagerService.java)).
- Cache reserved by `getStorageCacheBytes` is not touched. An app actively using the camera gets `DEFY_HALF_RESERVED`.
- So a "trim caches" button can call `allocateBytes(UUID_DEFAULT, getAllocatableBytes(UUID_DEFAULT))`. This frees only as much as needed to make that many bytes allocatable. It does *not* wipe all caches, and apps under their quota are cleared last.
- Real-world yield on OxygenOS: **[UNVERIFIED: measure]**.

**PackageManager.freeStorage / `freeStorageAndNotify`** are hidden and require `CLEAR_APP_CACHE` (`signature|privileged`, [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)). Since Android O the ADB shell identity holds CLEAR_APP_CACHE. App Manager uses it through privileged/Shizuku mode and otherwise falls back to per-app `deleteApplicationCacheFilesAsUser` ([PackageManagerCompat.java](https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/compat/PackageManagerCompat.java)). SD Maid SE declares CLEAR_APP_CACHE in its manifest ([AndroidManifest.xml](https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/AndroidManifest.xml)). Without ADB, Shizuku or root, **there is no API to clear a specific app's internal cache**; the user must tap through App info › Storage › Clear cache.

**Owner's claim checked:** "Apps with MANAGE_EXTERNAL_STORAGE + QUERY_ALL_PACKAGES can use getManageSpaceActivityIntent()" is ✅ correct, for API 31+ ([manage-all-files](https://developer.android.com/training/data-storage/manage-all-files)). The reference page lists only MANAGE_EXTERNAL_STORAGE as required.

---

## 5. Package visibility & QUERY_ALL_PACKAGES (API 30)

- Since Android 11, `getInstalledPackages` and similar calls return only visible packages. Visibility comes from `<queries>` (by package, intent or provider), or from QUERY_ALL_PACKAGES (`normal` protection, but Play-restricted) ([package-visibility](https://developer.android.com/training/package-visibility), [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)).
- **Play policy** ([Play Help 10158779](https://support.google.com/googleplay/android-developer/answer/10158779)): eligible categories are "device search, antivirus apps, file managers, and browsers". Disallowed when "a less broad app-visibility method would work". Storage cleaners are not mentioned.
- **Cheaper alternative for "unused apps":** `<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>` exposes every launchable app. That covers user-installed apps, which are the ones that matter for "unused 6 months" **[inference; standard pattern]**. It also improves `OWNER_PACKAGE_NAME` results on 14+ (§1.4).
- Known WhatsApp packages (`com.whatsapp`, `com.whatsapp.w4b`) can be declared individually.

---

## 6. Android 14 / 15 / 16 / 17 changes relevant to KeepClear

### 6.1 Android 14 (API 34)

- **Selected Photos Access:** `READ_MEDIA_VISUAL_USER_SELECTED` (API 34) is auto-added to the manifest when the app requests READ_MEDIA_IMAGES or VIDEO. If the app does not request it, the dialog may report `PERMISSION_GRANTED` while access is limited ("false grant state"). A cleaner must detect partial access, show "KeepClear only sees N selected photos", and re-prompt ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission), [partial access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)). With MANAGE_EXTERNAL_STORAGE this does not apply **[inference]**.
- **FGS types mandatory:** `dataSync` needs FOREGROUND_SERVICE_DATA_SYNC (`normal`) ([Manifest.permission](https://developer.android.com/reference/android/Manifest.permission), [service types](https://developer.android.com/develop/background-work/services/fgs/service-types)).
- **User-initiated data transfer (UIDT) jobs:**
  - API: `JobInfo.Builder.setUserInitiated(true)` plus `RUN_USER_INITIATED_JOBS` (`normal`).
  - Can be scheduled only while the app is visible (or another allowed condition) and requires a notification via `setNotification`.
  - "In UPSIDE_DOWN_CAKE, user-initiated jobs can only be used for network data transfers", so they must call `setRequiredNetwork` / `setRequiredNetworkType`.
  - Not subject to quotas.
  - Sources: [JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder), [UIDT guide](https://developer.android.com/develop/background-work/background-tasks/uidt).
  - **Fits backing up to cloud or NAS. Does not fit a purely local scan or copy to USB.**
- Implicit intents reach only exported components. Background activity launches from a `PendingIntent` need an explicit opt-in ([14 behaviour](https://developer.android.com/about/versions/14/behavior-changes-14)).

### 6.2 Android 15 (API 35)

- **FGS timeout (target 35+):**
  - `dataSync` and the new `mediaProcessing` type each get 6 hours per 24 hours, shared across all services of that type.
  - At the limit, `Service.onTimeout(int,int)` fires and the app must `stopSelf()` within seconds or crash with `RemoteServiceException`.
  - A new start after exhaustion throws `ForegroundServiceStartNotAllowedException`.
  - The timer resets when the user brings the app to the foreground.
  - Source: [FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout).
  - `mediaProcessing` (FOREGROUND_SERVICE_MEDIA_PROCESSING, API 35) is for "time-consuming operations on media assets, like converting media" ([service types](https://developer.android.com/develop/background-work/services/fgs/service-types)). Video re-compression ("squeeze") fits there. Hashing and copying fit `dataSync` ("Local file processing", "Backup-and-restore").
- Target 35+ cannot start a dataSync FGS from `BOOT_COMPLETED` ([service types](https://developer.android.com/develop/background-work/services/fgs/service-types)).
- **Edge-to-edge enforced** for target 35. Opt-out attribute exists in 15 only ([16 behaviour](https://developer.android.com/about/versions/16/behavior-changes-16)).
- **16 KB pages:**
  - Any native `.so` must be 16 KB-aligned.
  - Play requires it for target 35+, and updates are blocked from 2027-02-01 ([page-sizes](https://developer.android.com/guide/practices/page-sizes)).
  - Pure Kotlin/Java has no impact. JNI hashing libraries (BLAKE3 or xxHash natives) must be checked.
- **Private space** (separate user profile): apps and their storage inside it are invisible to KeepClear in the main profile. Do not claim totals cover them ([15 all apps](https://developer.android.com/about/versions/15/behavior-changes-all)).
- **Package stopped state:** force-stopping KeepClear cancels its pending intents and alarms. Scheduled scans need re-registration on next launch (same page).
- BAL hardening and "safer intents" StrictMode ([15 targeting](https://developer.android.com/about/versions/15/behavior-changes-15)).

### 6.3 Android 16 (API 36, current Play target)

- Edge-to-edge opt-out (`windowOptOutEdgeToEdgeEnforcement`) disabled.
- **Predictive back:** with target 36 on a 16 device, `onBackPressed` is not called and `KEYCODE_BACK` is not dispatched. Use `OnBackPressedCallback` / Compose `BackHandler` or opt out via `android:enableOnBackInvokedCallback="false"`.
- Orientation and resizability locks ignored on screens ≥600dp.
- Sources: [16 targeting](https://developer.android.com/about/versions/16/behavior-changes-16).
- **JobScheduler quota changes (all apps):** jobs started while the app is in the top state, or running alongside an FGS, now count against runtime quota. The docs point long user-initiated transfers to UIDT ([16 all apps](https://developer.android.com/about/versions/16/behavior-changes-all)). This affects WorkManager.
- MediaStore additions: 2,000-URI cap (for target 36), `INFERRED_DATE`, `markIsFavoriteStatus`, `OEM_METADATA` (§1).

### 6.4 Android 17 (API 37). Docs are live as of 2026-10

- **`ACCESS_LOCAL_NETWORK` runtime permission** (NEARBY_DEVICES group) is mandatory for target 37 apps that talk to LAN devices. **This affects backup to a NAS/SMB/PC over Wi-Fi.** Use a system device picker or request the permission ([17 targeting](https://developer.android.com/about/versions/17/behavior-changes-17)).
- Orientation and resizability opt-out removed for target 37 on large screens. BAL hardening extended to `IntentSender` (same page).
- App memory limiter by device RAM (all apps). Kills appear as `REASON_OTHER` with "MemoryLimiter:AnonSwap" ([17 all apps](https://developer.android.com/about/versions/17/behavior-changes-all)). Keep hash and dup-index memory bounded, and stream instead of loading everything.
- Cross-profile loopback traffic blocked by default (same page).
- No storage/MediaStore-specific change was found on the 17 behaviour pages as of today **[checked; may be incomplete]**.

---

## 7. Background execution choices for long jobs

| Job | Duration risk | Recommended mechanism |
|---|---|---|
| Full scan (metadata only, MediaStore query + `File.walk`) | Seconds to minutes | In-app coroutine while visible. Expedited WorkManager if it continues briefly after leaving |
| Hashing 75 GB of camera files for duplicates | Possibly >10 min | `dataSync` FGS started from a user tap (full 6 h budget) with a resumable checkpoint (§8); or WorkManager chained chunks |
| Backup to cloud / NAS | Hours | **UIDT job** (API 34+, network required); `dataSync` FGS on 30–33 |
| Copy to USB-OTG / SD card | Hours | `dataSync` FGS (UIDT needs a network constraint on 14) |
| Video re-encode | Hours | `mediaProcessing` FGS (35+) / `dataSync` below |

- Always implement `onTimeout`.
- OEM killers: OxygenOS is known for aggressive background killing and for resetting "Don't optimize" battery settings ([dontkillmyapp OnePlus](https://dontkillmyapp.com/oneplus)). It is unclear whether OxygenOS 15 still does this **[UNVERIFIED]**. Ask the user to lock the app in Recents during long backups **[UNVERIFIED workaround]**.

---

## 8. File hashing & change detection

### 8.1 I/O path

- Android 11+ serves `/sdcard` through a FUSE daemon. Random I/O can be up to 2× slower than direct access, while sequential reads are comparable after tuning. `Android/data` and `Android/obb` bypass FUSE. MediaProvider has bulk-operation optimisations for MANAGE_EXTERNAL_STORAGE apps ([scoped storage AOSP](https://source.android.com/docs/core/storage/scoped)).
- Android 12 adds **FUSE passthrough**, but only on devices that *launched* with 12 on supported kernels ([FUSE passthrough](https://source.android.com/docs/core/storage/fuse-passthrough)). OnePlus 10T launched with Android 12 in 2022, so it likely has it **[UNVERIFIED]**.
- Opening through `ContentResolver.openFileDescriptor` gives a native FD. The `_DATA` docs recommend it "for better performance" ([MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns)).
- **Strategy (standard dedup practice):**
  1. Group by exact size.
  2. Hash only the first and last 64 KB of same-size files.
  3. Full-hash only the files that still collide.
  4. Byte-compare before declaring "identical" when deleting the only copy.

  For 10,472 camera files this avoids reading most of the 75 GB **[inference]**.
- Read with large buffers (≥1 MB) on `Dispatchers.IO`. Run 2–4 files in parallel; flash benefits from queue depth **[UNVERIFIED: benchmark]**.

### 8.2 Algorithms available on Android/JVM

| Algorithm | Availability | Notes |
|---|---|---|
| SHA-256, SHA-1, MD5 | `java.security.MessageDigest` (platform) | Provider on Android is Conscrypt (BoringSSL). ARMv8 SHA2 instructions should make SHA-256 roughly as fast as storage **[UNVERIFIED: benchmark on device]**. Zero dependencies |
| CRC32 / CRC32C | `java.util.zip.CRC32` (API 1), `CRC32C` (API 34 / Java 9 API availability **[UNVERIFIED]**) | Fast, but not collision-resistant. Use for the pre-filter only |
| xxHash64 / XXH3 / XXH128 | Not in platform. **Zero-Allocation-Hashing** (OpenHFT, Apache-2.0: xxHash, xxh3/xxh128, wyHash, …) or lz4-java's xxHash | Pure Java. GPL-3.0-compatible |
| BLAKE3 | Not in platform. Apache **commons-codec `Blake3`** (pure Java, since 1.16); native bindings need the 16 KB-page check | Pure-Java BLAKE3 is likely slower than hardware SHA-256 **[UNVERIFIED]** |

Sources: [commons-codec Blake3](https://commons.apache.org/proper/commons-codec/apidocs/org/apache/commons/codec/digest/Blake3.html), [Zero-Allocation-Hashing](https://github.com/OpenHFT/Zero-Allocation-Hashing).

**Recommendation:**
- Use XXH3-128 (ZAH) for duplicate grouping.
- Use **SHA-256 (platform) for backup verification**, which is "verify before delete" in the MOVE flow. It is standard, auditable, and comparable with `sha256sum` on a PC or NAS.
- Benchmark both on the OnePlus 10T before fixing the choice.

### 8.3 Detecting changes

- **MediaStore generations** (API 30) are the primary incremental mechanism (§1.4).
- `ContentObserver` on `MediaStore.*.EXTERNAL_CONTENT_URI` with `notifyForDescendants=true` works only while the process lives. `JobInfo.TriggerContentUri` (API 24) schedules work on change.
- `FileObserver(File, mask)` (API 29 constructor) is inotify, non-recursive (one watch per directory), and process-bound. On FUSE paths, changes made directly on the lower filesystem may not generate events **[UNVERIFIED]**. Use it only for a few hot dirs (`Download/`, WhatsApp `Media/`) while the app is open.
- Fallback: on app open, compare directory `lastModified` and size snapshots for non-indexed (`.nomedia`) folders.

---

## 9. Feature → API level matrix

| Feature | Min API | Permission / note |
|---|---|---|
| Per-app size (`StorageStatsManager`) | 26 | PACKAGE_USAGE_STATS (special access) |
| `getAllocatableBytes` / `allocateBytes` (system cache trim) | 26 | none |
| Last-used per app (`UsageStatsManager`) | 21 (29 for `lastTimeVisible`) | PACKAGE_USAGE_STATS |
| `ACTION_MANAGE_STORAGE` | 25 | none |
| MediaStore `IS_PENDING`, `DATE_EXPIRES`, `OWNER_PACKAGE_NAME`, `RELATIVE_PATH` | 29 | READ_MEDIA_* (33+) / READ_EXTERNAL_STORAGE |
| `createTrashRequest` / `createDeleteRequest` / `createWriteRequest` / `IS_TRASHED` / `IS_FAVORITE` / generations | **30** | dialog unless MANAGE_MEDIA |
| MANAGE_EXTERNAL_STORAGE, `ACTION_CLEAR_APP_CACHE`, QUERY_ALL_PACKAGES | **30** | Play declarations |
| MANAGE_MEDIA (dialog-free trash/delete), `getManageSpaceActivityIntent`, `getExternalCacheBytes`, `isCurrentSystemGallery` | 31 | special access / MANAGE_EXTERNAL_STORAGE |
| FUSE passthrough (perf) | 31 (device-launch dependent) | none |
| READ_MEDIA_IMAGES/VIDEO/AUDIO; photo picker max limit API | 33 | runtime |
| READ_MEDIA_VISUAL_USER_SELECTED; UIDT jobs; typed FGS permissions | 34 | normal / runtime |
| `mediaProcessing` FGS; FGS 6 h timeout; private space; 16 KB | 35 | — |
| 2,000-URI cap; `INFERRED_DATE`; `markIsFavoriteStatus`; predictive back mandatory | 36 | — |
| `ACCESS_LOCAL_NETWORK` for LAN backup | 37 (target) | runtime |

### 9.1 Recommendation: minSdk 30, targetSdk 36, compileSdk 37

- **minSdk 30 (Android 11):**
  - Every core primitive starts at 30: system trash, batch dialogs, MANAGE_EXTERNAL_STORAGE, generations, QUERY_ALL_PACKAGES, `ACTION_CLEAR_APP_CACHE`.
  - Going to 29 means a second storage model (legacy opt-out, no trash API, no all-files access). Going to 26 adds pre-scoped-storage behaviour on top of that. That cost is not worth it for a new app; the owner's device runs 15.
  - Runtime-gate the API 31+ features: MANAGE_MEDIA, `getManageSpaceActivityIntent`.
  - Android version share for 11+ was not verified **[UNVERIFIED]**.
- **targetSdk 36:** required by Play for new apps and updates since 2026-08-31 ([target-sdk](https://developer.android.com/google/play/requirements/target-sdk)). This brings the 2,000-URI cap, the FGS timeouts, edge-to-edge, and predictive back. F-Droid has no such rule, but keep one target.
- **compileSdk 37,** so Android 17 APIs (local-network permission) can be gated now. Move the target to 37 once LAN backup handles `ACCESS_LOCAL_NETWORK`.

---

## 10. OnePlus / OxygenOS 15 notes (all **[UNVERIFIED]** unless cited)

- Aggressive background killing and battery-optimisation resets have been documented historically ([dontkillmyapp OnePlus](https://dontkillmyapp.com/oneplus)). Current OxygenOS 15 behaviour is unverified.
- The OnePlus File Manager recycle bin (3.8 GB on the owner's phone) is an OEM-private hidden folder, not MediaStore trash. Its location and whether all-files access can read it are **[UNVERIFIED: inspect via ADB: `ls -la /sdcard/` for hidden dirs]**.
- Whether OnePlus Gallery is the "system gallery" (`isCurrentSystemGallery`), and whether its "Recently deleted" view shows MediaStore `IS_TRASHED` items, is **[UNVERIFIED: test]**.
- Whether the device has 16 KB pages: the OnePlus 10T (launched with 12) almost certainly uses 4 KB **[UNVERIFIED]**. This is irrelevant unless the app ships native code.

---

## Implications for KeepClear

### Adopt

- **minSdk 30 / targetSdk 36 / compileSdk 37** (§9.1).
- **Two-tier deletion:**
  - (a) Indexed media go through `MediaStore.createTrashRequest`, in chunks of ≤2,000. This gives a free, OS-managed 30-day trash that other gallery apps can see and restore.
  - (b) Non-indexed files (`.nomedia` WhatsApp folders, zips, leftovers) go to an in-app trash folder on the same volume, using a rename rather than a copy so it is instant.
  - **The UI must say "trash frees no space until emptied"** and offer "Empty now" (`createDeleteRequest`) for the user who is 93% full.
- **Offer MANAGE_MEDIA** (API 31+) as an optional "skip confirmations" toggle. Do not rely on it.
- **Permission ladder:**
  1. READ_MEDIA_* with partial-access detection.
  2. PACKAGE_USAGE_STATS for app sizes and last-used.
  3. SAF grant on `Android/media/com.whatsapp/WhatsApp`.
  4. Optional MANAGE_EXTERNAL_STORAGE, mainly for the F-Droid flavour; on Play only if a file-manager/backup positioning is accepted.
- **"Trim system caches" button** via `allocateBytes(UUID_DEFAULT, getAllocatableBytes(...))`. No permission needed; report actual freed bytes measured before and after.
- **`ACTION_CLEAR_APP_CACHE`** only in the all-files flavour. It clears external caches only.
- **MediaStore generations** for incremental rescans. Use size → partial hash → full hash for duplicates, SHA-256 for backup verification, and `IS_FAVORITE` as a protect signal.
- **Long jobs:**
  - UIDT for network backup (34+).
  - `dataSync` FGS started from user taps, with `onTimeout` handling and resumable checkpoints.
  - `mediaProcessing` for any video squeeze.
- **"Unused apps"** from monthly/yearly usage buckets plus `firstInstallTime`, shown as "not opened in ~6+ months" (month precision). Use launcher `<queries>` instead of QUERY_ALL_PACKAGES.

### Avoid

- Promising `Android/data` or `Android/obb` cleaning without Shizuku/ADB/root. It is impossible on 11+ (SAF) and 13+ (workaround).
- Claiming "freed X GB" immediately after a trash operation.
- Per-app internal cache clearing without ADB/Shizuku: there is no public API. Deep-link to App info instead.
- QUERY_ALL_PACKAGES on Play unless needed for owner-package names. Native hashing libraries without 16 KB alignment.
- `onBackPressed` and edge-to-edge opt-outs (dead at target 36).

### Open questions (need device tests on the OnePlus 10T / OxygenOS 15)

1. Does `update(IS_TRASHED=1)` as an all-files app work without a dialog? Does OnePlus Gallery show those items?
2. Effective MANAGE_MEDIA prerequisite on API 33+: are READ_MEDIA_* enough?
3. How many bytes does `allocateBytes` actually trim on this phone compared with Settings › Storage "Clear cache"?
4. Where is the OnePlus File Manager recycle bin, and is it readable with all-files access?
5. Which WhatsApp folders contain `.nomedia`, which decides MediaStore versus file/SAF deletion per folder?
6. SHA-256 vs XXH3 throughput, and SAF vs File API listing speed, on 10k+ files.
7. Will Play accept MANAGE_EXTERNAL_STORAGE for the "MOVE memories" backup positioning? Probably needs a separate Play vs F-Droid flavour, as WhatsAppCleaner does ([release manifest](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/release/AndroidManifest.xml)).
