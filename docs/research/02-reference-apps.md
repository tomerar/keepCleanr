# 02 — Reference apps: code study

Scope: WhatsAppCleaner, CleanSweep, Clearo, AppManager, AppManagerNG, Neo-Backup.
Method: shallow clones read locally (HEAD commits listed below), and distribution checked on the web (F-Droid API, IzzyOnDroid, Play) on 2026-10-31.
GitHub API metadata (stars, fork status) was blocked from this session, so star counts are omitted and marked **[UNVERIFIED]** where they matter.

| Repo | Branch @ commit (date) |
|---|---|
| VishnuSanal/WhatsAppCleaner | `main` @ 9ac2e8f (2026-07-30) |
| AZADAYAZ/CleanSweep | `main` @ 3040441 (2026-08-08) |
| MZarc/Clearo | `main` @ 7908e5b (2026-08-07), 1 commit in history |
| MuntashirAkon/AppManager | `master` @ 0d1d926 (2026-10-30) |
| SysAdminDoc/AppManagerNG | `main` @ 3a3e441 (2026-09-30) |
| NeoApplications/Neo-Backup | `main` @ c1a2d77 (2026-10-17) |

---

## 0. Summary matrix

| App | License (verified) | Stack | minSdk / target | Storage access | Trash? | Distribution |
|---|---|---|---|---|---|---|
| WhatsAppCleaner | GPL-3.0-or-later ([LICENSE](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/LICENSE)) | Kotlin, Compose, MVVM | 24 / 35 | File API + `MANAGE_EXTERNAL_STORAGE` (F-Droid/release); **SAF tree** (Play build) | No: permanent delete | [F-Droid](https://f-droid.org/packages/com.vishnu.whatsappcleaner/) (v1.4.1), [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.vishnu.whatsappcleaner), Play **[UNVERIFIED]** |
| CleanSweep | GPL-3.0 ([LICENSE](https://github.com/AZADAYAZ/CleanSweep/blob/main/LICENSE)) | Kotlin, Compose, Hilt, Room, Coil | 26 / 35 | `MANAGE_EXTERNAL_STORAGE` + MediaStore | No: permanent `ContentResolver.delete` | GitHub releases only (not on F-Droid/Izzy, per their APIs) |
| Clearo | **MIT** ([LICENSE](https://github.com/MZarc/Clearo/blob/main/LICENSE)) | Flutter/Dart + Kotlin MethodChannel | `flutter.minSdkVersion` | `MANAGE_EXTERNAL_STORAGE` + MediaStore + `READ_MEDIA_*` | Yes, 7-day app-private bin (**not encrypted in practice**, see §3) | Not on F-Droid/Izzy/Play (checked) |
| AppManager | GPL-3.0-or-later ([COPYING](https://github.com/MuntashirAkon/AppManager/blob/master/COPYING)) + bundled third-party licenses in `LICENSES/` | Java, multi-module, own privileged server | 21 / 36 | Root / ADB (own binder server); **no Shizuku** | n/a | [F-Droid](https://f-droid.org/packages/io.github.muntashirakon.AppManager/) (4.1.1), [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/io.github.muntashirakon.AppManager) |
| AppManagerNG | GPL-3.0-or-later ([COPYING](https://github.com/SysAdminDoc/AppManagerNG/blob/main/COPYING)) | Java (AppManager-derived) + Shizuku API 13.1.5 | 21 / 36 | Root / Shizuku / Wireless ADB / ADB-TCP / no-root | n/a | GitHub releases + Obtainium ([README](https://github.com/SysAdminDoc/AppManagerNG/blob/main/README.md)) |
| Neo-Backup | **AGPL-3.0** ([LICENSE.md](https://github.com/NeoApplications/Neo-Backup/blob/main/LICENSE.md)) | Kotlin, Compose | 26 / 36 | Root required; backup target chosen via SAF tree | n/a | [F-Droid](https://f-droid.org/packages/com.machiav3lli.backup/) (8.3.18), IzzyOnDroid |

---

## 1. WhatsAppCleaner (VishnuSanal)

### Facts
- **License:** GPL-3.0 (`LICENSE`), with a "version 3 or later" header on every source file enforced by Spotless ([spotless-header](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/spotless-header)).
- **Build variants:** `debug`, `release` (F-Droid/GitHub), and `play`. **Owner said "Play flavor"; actually `play` is a *build type*, not a product flavor.** It has `applicationIdSuffix = ".play"` and sets `BuildConfig.IS_PLAY=true` ([app/build.gradle.kts L44-57](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/build.gradle.kts)).
- **Permissions:**
  - [main manifest](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/AndroidManifest.xml): `READ/WRITE_EXTERNAL_STORAGE maxSdkVersion=29`, plus `requestLegacyExternalStorage=true`.
  - `MANAGE_EXTERNAL_STORAGE` is declared **only** in the [release](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/release/AndroidManifest.xml) and [debug](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/debug/AndroidManifest.xml) source sets. The comment there says "Google Play forbids MANAGE_EXTERNAL_STORAGE for this use case".
  - No INTERNET permission.
  - Repo ships a `CLAUDE.md` ([CLAUDE.md](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/CLAUDE.md)).

### Storage access (the most useful pattern for us)
[`Storage.kt`](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/data/Storage.kt) picks one of three modes:

| Mode | When | Backend |
|---|---|---|
| `FILE_LEGACY` | API ≤ 29 | `java.io.File` |
| `FILE_MANAGE` | API ≥ 30, non-Play | `File` with All Files Access |
| `SAF` | API ≥ 30, Play | `DocumentsContract` tree URI |

- One interface ([`StorageBackend.kt`](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/data/StorageBackend.kt)) has two implementations: [`FileBackend`](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/data/FileBackend.kt) and [`SafBackend`](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/data/SafBackend.kt). Folders are addressed by a path relative to the WhatsApp root.
- **SAF picker** ([MainActivity.kt ~L295](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/MainActivity.kt)):
  - Uses `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI = primary:Android/media/com.whatsapp/WhatsApp`, and requests a persistable read and write grant.
  - Validates the pick by checking that a `Media` child exists (`hasMediaFolder`).
  - Confirms that `Android/media/<pkg>` is still SAF-pickable (unlike `Android/data`).
- **SAF performance note (from the code comments):**
  - Each directory listing is one IPC to the documents provider (~50 ms), and the provider serializes them, so "Voice Notes" can take tens of seconds.
  - The fixes are an iterative DFS with `ensureActive()` cancellation, streaming partial results every 500 files (`PROGRESS_BATCH`), and computing the heaviest folders last (`directorySizeWeight`, [MainViewModel.kt](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/MainViewModel.kt)).
  - It uses a path-based trick: a relative path is appended to the tree's root document ID instead of walking (`childDocumentId`). This relies on ExternalStorageProvider's path-like document IDs, which is an implementation detail and not part of the contract.
- **Root resolution in FileBackend** ([L45-48](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/data/FileBackend.kt)):
  - It prefers `Android/media/com.whatsapp/WhatsApp` and falls back to `/sdcard/WhatsApp` only if the scoped folder is missing.
  - **Consequence for the owner's phone:** the legacy `/sdcard/WhatsApp` folder (2.58 GB, pre-2022) is **never shown** when both exist. KeepClear must treat the legacy folder as a separate "old-phone leftover" item.

### WhatsApp folder layout it knows
From [`ListDirectory.kt`](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/model/ListDirectory.kt), with `hasSent`/`hasPrivate` flags:

| UI name | Relative path | Sent/ & Private/ tabs |
|---|---|---|
| Images | `Media/WhatsApp Images` | yes |
| Videos | `Media/WhatsApp Video` | yes |
| Documents | `Media/WhatsApp Documents` | yes |
| Audios | `Media/WhatsApp Audio` | yes |
| GIFs | `Media/WhatsApp Animated Gifs` | yes |
| Statuses | `Media/.Statuses` | no |
| Voice Notes | `Media/WhatsApp Voice Notes` (nested per-date folders; walked recursively) | no |
| Video Notes | `Media/WhatsApp Video Notes` (recursive) | no |
| Wallpapers | `Media/WallPaper` | no |
| Stickers | `Media/WhatsApp Stickers` | no |
| Profile Photos | `Media/WhatsApp Profile Photos` | no |

- **Tabs:** Received is the folder root (non-recursive, so it skips the `Sent/` and `Private/` subdirectories). Sent is `<dir>/Sent` and Private is `<dir>/Private` ([MainViewModel `targetsFor`](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/MainViewModel.kt)).
- **Databases/Backups protection:** it is an **implicit allowlist**. Only the `Media/...` paths above are ever listed. `Databases/` (msgstore*.crypt14) and `Backups/` are never enumerated, so they cannot be selected. There is no explicit denylist or guard in `deleteFiles`. A path-based delete could still reach them if a URI were constructed wrongly. **KeepClear should add an explicit hard denylist** (`Databases`, `Backups`, `*.crypt*`) checked at the delete call site.
- `.nomedia` files are skipped everywhere.

### Preview and deletion flow
1. HomeScreen shows per-folder sizes, which stream in.
2. DetailsScreen has Received/Sent/Private tabs, sorting by name/size/date, a date-range filter (`DateRangePicker`), grid/list toggle, and multi-select.
3. `CleanupConfirmationDialog` ([Dialogs.kt L150](https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/main/java/com/vishnu/whatsappcleaner/ui/components/Dialogs.kt)) shows "N files (size) will be deleted" with a thumbnail grid, and the user can **drop individual items from the batch** before confirming. This is a good pattern.
4. `delete()`:
   - Calls `File.delete()` or `DocumentsContract.deleteDocument()`. **This is permanent: no trash, no undo.**
   - Then it re-measures the folder and re-walks it, because "a deletion can partially fail".
   - Failures are logged, and only a boolean is returned.
- Previews are thumbnails; tapping opens the file through a `FileProvider` (manifest).

### Learn from
- The SAF/File dual backend and Play/non-Play split. This is the exact pattern KeepClear needs if we ship on Play without All Files Access.
- Streaming partial results, ordering slow folders last, and cancelling the walk on navigation.
- The editable confirmation grid.
- **Gaps:** no "Sent = duplicate" reasoning, no age-based bulk selection beyond the date filter, no trash, no explicit database guard.

---

## 2. CleanSweep (AZADAYAZ)

### Facts
- **License:** GPL-3.0.
- **Repo identity:** the README badges and releases point to `LoopOtto/cleansweep` ([README](https://github.com/AZADAYAZ/CleanSweep/blob/main/README.md)). The relationship (rename, fork, or mirror) is **[UNVERIFIED]**; the GitHub API was blocked.
- **Stack:** Kotlin, Compose, Hilt, Room (hash caches), WorkManager (`ProactiveIndexingWorker`), and a foreground `DuplicateScanService`.
- **Permissions** ([AndroidManifest](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/AndroidManifest.xml)): `MANAGE_EXTERNAL_STORAGE`, legacy READ/WRITE, `FOREGROUND_SERVICE(_DATA_SYNC)`, `WAKE_LOCK`, `POST_NOTIFICATIONS`. No INTERNET (claims "100% offline").
- **Distribution:** not found on F-Droid or IzzyOnDroid (both APIs return 404 for `com.cleansweep`), and no Play link. GitHub releases only.

### Swipe UX
[SwiperScreen.kt](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/java/com/cleansweep/ui/screens/swiper/SwiperScreen.kt) and [SwiperViewModel.kt](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/java/com/cleansweep/ui/screens/swiper/SwiperViewModel.kt):
- Swipe gestures:
  - Right = keep, left = delete, down = a secondary action.
  - The threshold depends on sensitivity (LOW 60 dp, MEDIUM 80 dp, HIGH 140 dp; [L1284-1290](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/java/com/cleansweep/ui/screens/swiper/SwiperScreen.kt)).
  - The left and right border glow fades in as you approach the threshold. This is cheap, clear affordance.
- Bottom bar of favorite folders: one tap **moves** the item, which is relevant to KeepClear's MOVE concept.
- **Deferred commit:**
  - Swipes build `PendingChange`s, and `revertLastChange()` provides undo (L1405).
  - A `SummarySheet` lists toDelete / toKeep / moves, and nothing touches disk until `applyChanges()` (L836).
  - This is the right safety model for swipe UX.
- **Deletion** ([DirectMediaRepositoryImpl.deleteMedia ~L624](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/java/com/cleansweep/data/repository/DirectMediaRepositoryImpl.kt)):
  - Indexed items are removed with a direct `contentResolver.delete(MediaStore.Files … _ID IN (...))`; unindexed items use `File.delete()`.
  - **No `MediaStore.createTrashRequest`, no `IS_TRASHED`, and no in-app bin.** Deletion is permanent after the summary confirm.
  - The direct delete only works because of All Files Access. Without it, API 30+ needs `createDeleteRequest`/`createTrashRequest` user consent ([MediaStore docs](https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E,%20boolean))).

### Duplicate / similar algorithm
**Exact duplicates** ([DuplicateFinderUseCase.kt](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/java/com/cleansweep/domain/usecase/DuplicateFinderUseCase.kt)):
1. Group by byte size and drop singletons.
2. Hash the candidates:
   - Videos: SHA-256 of the **first 256 KB** only (`generatePartialFileHash`, L143).
   - Images: SHA-256 of the **decoded pixels of a 256-px `loadThumbnail`** (`generatePixelHash`, L177).
3. Hashes are cached in Room, keyed by path + mtime + size.

**Caveat: both "exact" checks are lossy.** Two videos with an identical 256 KB head and the same size, or two images whose 256-px thumbnails decode identically, are reported as exact duplicates. KeepClear's SAFE tier needs a **full-content hash** (or a full-hash verify step) before one-tap delete.

**Similar media** ([SimilarFinderUseCase.kt](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/java/com/cleansweep/domain/usecase/SimilarFinderUseCase.kt) and [PHashUtil.kt](https://github.com/AZADAYAZ/CleanSweep/blob/main/app/src/main/java/com/cleansweep/domain/util/PHashUtil.kt)):
- **dHash 9×8** with luminance `0.299R+0.587G+0.114B` and bit = right > left, producing a 64-bit hex value. Hamming distance uses `BigInteger.xor().bitCount()`.
- **Images** combine dHash Hamming distance with a **color-histogram gate**:
  - The histogram uses 4×4×4 bins on a 16×16 image, compared with normalized intersection.
  - Thresholds: STRICT ≤3 bits / hist ≤0.35; BALANCED ≤5 / 0.5; LOOSE ≤8 / 0.65 (L268-275).
- **Videos:** dHash of frames at 10% and 50% of duration (`getFrameAtTime`, `OPTION_CLOSEST_SYNC`), with thresholds 2/4/7.
- Audio also has a hash path (L381+).
- **Candidate pruning:**
  - Hashes are bucketed by their first 2 hex chars, and only the same or adjacent buckets (±1) are compared.
  - This is fast but **misses pairs whose differing bits fall in the top 8 bits**, an accuracy and speed trade-off.
  - BK-trees or multi-index hashing would be correct.
- Screenshots are detected by path or name (`isScreenshot`, L463).
- `SimilarityDenial` table: the user can mark "not similar" and it persists.
- Bulk actions "Keep oldest" and "Keep newest".

### Learn from
- Deferred-commit swipe with undo and a summary sheet.
- Strict/Balanced/Loose presets explained in plain language.
- Hash cache keyed by (path, mtime, size).
- The "not similar" memory.
- **Avoid:** lossy "exact" hashes, permanent delete, and All Files Access as the only path.

---

## 3. Clearo (MZarc)

### Facts
- **License:** MIT, © 2026 MZarc.
- **Maturity:** a single commit in history, so this is effectively a code drop.
- **Stack:** Flutter UI plus a native Kotlin `MethodChannel` (`com.meet.clearo/scan`, [MainActivity.kt](https://github.com/MZarc/Clearo/blob/main/android/app/src/main/kotlin/com/meet/clearo/MainActivity.kt)).
- **Packages:** `sqflite`, `encrypt`, `flutter_secure_storage`, `crypto`, `image`, `workmanager` ([pubspec.yaml](https://github.com/MZarc/Clearo/blob/main/pubspec.yaml)).
- **Permissions** ([manifest](https://github.com/MZarc/Clearo/blob/main/android/app/src/main/AndroidManifest.xml)): `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE ≤29`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES/VIDEO/AUDIO`.
- **Storage stats:** `StorageStatsManager.getTotalBytes/getFreeBytes(UUID_DEFAULT)` as a fallback.
- **Distribution:** not on F-Droid, IzzyOnDroid or Play (all 404 for `com.meet.clearo`). The README has a "pro_screen", so it may monetize later.
- **Language:** README says "Kotlin + Flutter". Correct, but the UI and business logic are Dart, so little is reusable for a Kotlin app.

### dHash implementation
- **Native path** ([`computeDHashNative`, MainActivity.kt L222](https://github.com/MZarc/Clearo/blob/main/android/app/src/main/kotlin/com/meet/clearo/MainActivity.kt)):
  - A bounds-decode, then an `inSampleSize` power-of-two downsample and RGB_565 decode, then `createScaledBitmap(9,8, filter=true)`.
  - Luminance uses the same formula as CleanSweep, but the bit is **left > right** (the opposite convention; harmless if consistent).
  - Output is 64-bit `Long` hex.
- **Dart fallback** [`image_phash.dart`](https://github.com/MZarc/Clearo/blob/main/lib/hashing/image_phash.dart) is called by [`hash_worker.dart` L81-85](https://github.com/MZarc/Clearo/blob/main/lib/engine/hash_worker.dart). It decodes the **full image bytes** in Dart, which is slow and memory-heavy.
- **Threshold:** `findSimilarImages({maxDistance = 3})` ([duplicate_finder.dart](https://github.com/MZarc/Clearo/blob/main/lib/engine/duplicate_finder.dart)).
  - This is strict, roughly CleanSweep's STRICT.
  - Comparison is O(N²) in an isolate, with greedy non-transitive grouping (the first image claims all neighbors).
  - No histogram or EXIF/time gate.
- **Exact duplicates:** a streamed full-file MD5 (`_computeMD5`, hash_worker.dart L121). This is better than CleanSweep's partial hash, but MD5 is not collision-resistant, so it is fine for dedupe and not for adversarial input.

### "Encrypted recycle bin": owner's claim vs code
- **Owner said: "encrypted recycle bin". Actually, the encrypted bin is dead code.**
  - [`recycle_bin.dart`](https://github.com/MZarc/Clearo/blob/main/lib/engine/recycle_bin.dart) defines `RecycleBin.moveToRecycleBin`, which uses AES-256 with a random 16-byte IV prepended and a key in `flutter_secure_storage` (Android Keystore-backed) under alias `clearo_recycle_key`.
  - It uses the `encrypt` package's default AES mode, which is SIC/CTR with **no authentication (no MAC/GCM)**, and it reads the **whole file into memory** (OOM risk on large videos).
  - **Nothing calls it** (grep finds only its definition).
- **The live path** is `ScanEngine.deleteFile` ([scan_engine.dart ~L130-215](https://github.com/MZarc/Clearo/blob/main/lib/engine/scan_engine.dart)):
  1. Plaintext `File.copy` to `getApplicationDocumentsDirectory()/.clearo_bin/<ts>_<title>` (app-private internal storage).
  2. MediaStore `contentResolver.delete(uri)`, falling back to `File.deleteSync()`.
  3. A row in the sqflite `recycle` table: originalPath, originalUri, binPath, deletedAt, size, type, source.
- **Data-loss bug:**
  - `copiedToBin` is computed but **never checked**, so if the copy fails the original is still deleted.
  - Nothing verifies the copy (no size or hash comparison).
- **Retention:**
  - The UI shows "kept for 7 days", and Settings has a 1-30 day `recycleTTLDays` slider.
  - The bin screen hard-codes `7 - daysElapsed`.
  - **No purge job was found.** `workmanager` is a dependency, but no TTL deletion code references it, so the bin grows until manual clear.
- **Space accounting:**
  - The bin sits on the same internal volume, so "deleting" frees **zero bytes** until purge. The "GB saved" stats are therefore misleading during the retention window.
  - This applies to KeepClear's 30-day trash too and must be shown honestly.

### Learn from
- The idea of the bin record schema (originalPath + originalUri + binPath + size + type + source).
- Native downsampled dHash.
- **Avoid:** copy-then-delete without verification, a decorative TTL, unauthenticated encryption, and whole-file reads.

---

## 4. AppManager (MuntashirAkon)

### Facts
- **License:** GPL-3.0-or-later ([COPYING](https://github.com/MuntashirAkon/AppManager/blob/master/COPYING)). The `LICENSES/` folder carries Apache-2.0, BSD, MIT, ISC, WTFPL, CC-BY-SA-4.0 and **GPL-2.0** texts for bundled components, so check the per-file SPDX headers before copying.
- **Stack:** Java. Modules: `app`, `server`/`libserver` (privileged process), `hiddenapi` (stubs for `IPackageManager`, `IActivityManager`, …), `libcore`.
- **SDK:** min 21, target/compile 36 ([versions.gradle](https://github.com/MuntashirAkon/AppManager/blob/master/versions.gradle)).
- **Manifest:** 62 `uses-permission`. Most are signature/privileged permissions that only take effect when running as root/shell through its server, e.g. `CLEAR_APP_CACHE`, `DELETE_CACHE_FILES`, `INTERNAL_DELETE_CACHE_FILES`, `GET_PACKAGE_SIZE`, `PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `FORCE_STOP_PACKAGES` ([AndroidManifest](https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/AndroidManifest.xml)).

### Privilege model
[`settings/Ops.java`](https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/settings/Ops.java):
- Modes: `auto`, `root`, `adb_tcp`, `adb_wifi`, `no-root`. **There is no Shizuku mode in upstream** (grep finds no Shizuku references).
- **Owner implied Shizuku integration; that is only in AppManagerNG.**
- `autoDetectRootSystemOrAdbAndPersist` (L407):
  1. Try root. If granted, start its own server as root via `LocalServices.bindServices()`.
  2. If root is granted but binder setup fails, start ADB-over-TCP via root.
  3. Otherwise reuse a running ADB server, then fall back to no-root.
- Privileged calls go through its own binder proxy (`ProxyBinder`) to hidden `IPackageManager` and `IStorageStatsManager` interfaces, **not shell commands**.

### Cache clearing: what it actually calls
**Owner expected `pm trim-caches` and `pm clear --cache-only`. AppManager uses neither string** (grep for `trim-caches` finds nothing). It calls the binder APIs those shell commands wrap:

| Action | Code | Required permission |
|---|---|---|
| Trim all caches | `IPackageManager.freeStorageAndNotify(null, 1 TB, FLAG_ALLOCATE_DEFY_ALL_RESERVED, observer)` ([OneClickOpsViewModel.trimCaches](https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/oneclickops/OneClickOpsViewModel.java), [PackageManagerCompat.freeStorageAndNotify ~L627](https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/compat/PackageManagerCompat.java)) | `CLEAR_APP_CACHE`. The code notes shell lacks it before Oreo, so it falls back to clearing per package. |
| Clear one app's cache | `IPackageManager.deleteApplicationCacheFilesAsUser(pkg, userId, observer)` (API 24+), else `deleteApplicationCacheFiles` (~L575) | `DELETE_CACHE_FILES` + `INTERNAL_DELETE_CACHE_FILES` (P+) |
| Clear data | `IPackageManager.clearApplicationUserData(...)` (~L533) | `CLEAR_APP_USER_DATA` |

- For KeepClear with Shizuku (shell UID), the shell equivalents are `pm trim-caches <size>` (calls `freeStorageAndNotify`) and, on Android 14+, `pm clear --cache-only <pkg>`.
- Both are **[UNVERIFIED]** in this doc: confirm against AOSP `PackageManagerShellCommand` and sdmaid-se in doc 01.
- `trim-caches` is global and best-effort (the system decides what to free). Per-app clearing gives exact attribution, which our "why" UI needs.

### Usage stats and storage stats
- **App size:** `PackageUtils.getPackageSizeInfo` (L317) calls `IStorageStatsManager.queryStatsForPackage(uuid, pkg, user, callingPkg)` through `ProxyBinder` ([PackageUtils.java ~L348](https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/utils/PackageUtils.java)).
  - The unprivileged public equivalent is `StorageStatsManager.queryStatsForPackage`, which needs the `PACKAGE_USAGE_STATS` app-op (user-granted in Usage Access settings) ([docs](https://developer.android.com/reference/android/app/usage/StorageStatsManager#queryStatsForPackage(java.util.UUID,%20java.lang.String,%20android.os.UserHandle))).
  - This gives app/data/cache bytes, which is enough for the "app cache 3.85 GB" card **without root**.
- **Last-used:** [`usage/AppUsageStatsManager.java`](https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/usage/AppUsageStatsManager.java) uses `UsageStatsManagerCompat.queryEvents(...)` (activity resumed/paused events), which needs `PACKAGE_USAGE_STATS`.
  - **Caveat:** usage history is retained only for limited windows, with daily buckets about 7 days and yearly coarser **[UNVERIFIED exact retention]**. A "not used for 6 months" signal should use `UsageStats.getLastTimeUsed()` from `INTERVAL_YEARLY`, cross-checked with the package `firstInstallTime`/`lastUpdateTime`.

### Onboarding
Upstream has a mode preference (`ModeOfOpsPreference`) but no first-run privilege tutorial. See AppManagerNG.

---

## 5. AppManagerNG (SysAdminDoc): verified to exist and cloned

### Facts
- **Exists:** cloned from `https://github.com/SysAdminDoc/AppManagerNG` (main, 2026-09-30, README version 0.6.24).
- **License:** GPL-3.0-or-later (COPYING inherited from upstream, plus `LICENSE` GPL-3.0 text).
- **Package ID:** `io.github.sysadmindoc.AppManagerNG` (source namespace unchanged).
- **Origin:** the README calls it "a maintained continuation" starting from upstream commit `3d11bcb` (2026-04-30) ([README](https://github.com/SysAdminDoc/AppManagerNG/blob/main/README.md)).
  - **Owner said "friendlier fork". Correct, but upstream is not abandoned:** AppManager master had commits on 2026-10-30.
  - Whether it is a GitHub-flagged fork is **[UNVERIFIED]**.
- **Distribution:** GitHub releases (Full and FLOSS variants) and Obtainium. **Not found on F-Droid.**
- **Adds Shizuku:** `dev.rikka.shizuku:api` and `provider` 13.1.5 ([versions.gradle L44](https://github.com/SysAdminDoc/AppManagerNG/blob/main/versions.gradle), [app/build.gradle L277](https://github.com/SysAdminDoc/AppManagerNG/blob/main/app/build.gradle)), wrapped in `shizuku/ShizukuBridge.java`, and adds `Ops.MODE_SHIZUKU`.

### Privilege onboarding (best reference for KeepClear's "advanced mode" sheet)
[`onboarding/OnboardingFragment.java`](https://github.com/SysAdminDoc/AppManagerNG/blob/main/app/src/main/java/io/github/muntashirakon/AppManager/onboarding/OnboardingFragment.java) is a 1,089-line `BottomSheetDialogFragment` shown once after the disclaimer.
- **Six mode cards:** Auto, Root, Shizuku, Wireless ADB, ADB-TCP, No-root. Each has a title, a one-line summary, and an "info" explainer dialog (strings in [res/values/strings.xml ~L2513-2576](https://github.com/SysAdminDoc/AppManagerNG/blob/main/app/src/main/res/values/strings.xml)).
- **Live capability snapshot:**
  - Root: "✓ Detected" or "Not detected".
  - Shizuku: installed version, recommended version, permission granted, root-backed, supports UserService, OEM warning, Android 17 risk.
  - ADB readiness.
  - A "Re-check" button.
- **Explicit warnings**, which are worth copying in spirit:
  - A root-backed Shizuku can make banking and Play-Integrity apps crash.
  - Shizuku has Android 17 compatibility issues, with a fallback to Wireless ADB.
  - An old security patch makes ADB risky (minimum recommended patch is hard-coded as `2026-05-01`).
- **"Confidence checklist":** it tells the user which features work in the chosen mode (e.g. "Does not turn shell into full root; protected app data and IFW paths still require root").
- **Auto-mode explainer** lists the probe order: root → Shizuku/Sui → Wireless debugging → ADB-TCP → no-root.
- `settings/PrivilegeModeDoctor.java` produces a diagnostics report of each probe. This is useful for support.

### Learn from
- The mode-card plus explainer plus live-status plus re-check pattern.
- Honest per-mode capability lists.
- **Avoid** its density. KeepClear needs at most two modes in the UI: "Standard" and "Advanced (Shizuku)", with root hidden.

---

## 6. Neo-Backup (NeoApplications): shallow clone, skim only

### Facts
- **License: AGPL-3.0** ([LICENSE.md](https://github.com/NeoApplications/Neo-Backup/blob/main/LICENSE.md), README "Licenses" section). Owner listed no license; noted here.
- **Stack and SDK:** Kotlin and Compose, min 26, target 36 ([build.gradle.kts](https://github.com/NeoApplications/Neo-Backup/blob/main/build.gradle.kts)).
- **Root:** required ("It requires root", README).
- **Backup location:** a user-picked SAF tree (`ACTION_OPEN_DOCUMENT_TREE`, [Constants.kt L423](https://github.com/NeoApplications/Neo-Backup/blob/main/src/main/java/com/machiav3lli/backup/Constants.kt)).
- **Distribution:** F-Droid (8.3.18) and IzzyOnDroid.
- **Permissions:** also SMS, call log and contacts (for its message/call-log backup features).

### Backup verification: there is essentially none at content level
- `BackupAppAction` ([L150-235](https://github.com/NeoApplications/Neo-Backup/blob/main/src/main/java/com/machiav3lli/backup/manager/actions/BackupAppAction.kt)):
  1. Writes the tar parts (apk, data, de, ext, obb, media), optionally compressed and encrypted (AES via password or PGP).
  2. Computes `size = backupInstanceDir.listFiles().sumOf { it.size }`.
  3. Writes a **properties file last** (`BackupBuilder.createBackup` → `saveBackupProperties`, [BackupBuilder.kt L151](https://github.com/NeoApplications/Neo-Backup/blob/main/src/main/java/com/machiav3lli/backup/manager/handler/BackupBuilder.kt)).
  4. If any step threw, the partial backup is deleted (`app.deleteBackup`).
- In other words, completeness is marked by "props file written last", and size is recorded. There are **no per-file checksums and no read-back or restore-test**. The grep for sha/checksum/verify finds only unrelated items:
  - `pref_disableVerification` toggles Android's *APK install verifier* on restore (`settings put global verifier_verify_adb_installs 0`).
  - `pref_paranoidBackupLists` and `pref_paranoidHousekeeping` only **re-list the filesystem** after add/delete ([AdvancedPrefsPage.kt ~L394](https://github.com/NeoApplications/Neo-Backup/blob/main/src/main/java/com/machiav3lli/backup/ui/pages/AdvancedPrefsPage.kt)).
- **Takeaways for KeepClear MOVE:**
  - Adopt the commit-marker idea: write the manifest last and treat a backup without it as incomplete.
  - Go further than Neo-Backup with per-file size and hash in the manifest, then read back from the target and compare before any delete.

---

## 7. License compatibility for code reuse into KeepClear (GPL-3.0)

| Source | License | Copy code into GPL-3.0 KeepClear? | Notes |
|---|---|---|---|
| WhatsAppCleaner | GPL-3.0-or-later | ✅ Yes | Keep copyright headers and attribute. |
| CleanSweep | GPL-3.0 | ✅ Yes | Check whether "only" or "or later" in file headers **[UNVERIFIED]**; either works for GPL-3.0. |
| Clearo | MIT | ✅ Yes | Retain the MIT notice (copyright + permission text) in NOTICE or file header. |
| AppManager / AppManagerNG | GPL-3.0-or-later (project) | ✅ Yes for GPL-3.0-or-later files | `LICENSES/` includes **GPL-2.0** texts. Any file that is **GPL-2.0-only** is *incompatible* with GPL-3.0 ([FSF compatibility](https://www.gnu.org/licenses/gpl-faq.html#AllCompatibility)). Check each file's SPDX header before copying. CC-BY-SA-4.0 (docs/art) is one-way compatible into GPL-3.0 only for some content; don't copy art. |
| Neo-Backup | AGPL-3.0 | ⚠️ Possible but changes terms | GPLv3 §13 allows combining with AGPLv3 code, but the AGPL portions keep their network-use clause ([GPL-3.0 §13](https://www.gnu.org/licenses/gpl-3.0.html#section13)). **Reference only; don't copy.** |
| Shizuku-API (via AppManagerNG) | Apache-2.0 / MIT (per owner; verify in doc 01) | ✅ Apache-2.0 → GPL-3.0 is OK ([FSF](https://www.gnu.org/licenses/license-list.html#apache2)) | The Shizuku name and icon are not licensed for reuse. |

---

## Implications for KeepClear

### Adopt
1. **Dual storage backend, as in WhatsAppCleaner.**
   - Use a `StorageBackend` interface with a File/All-Files implementation (F-Droid build) and a SAF implementation (Play build), selected by a `BuildConfig` flag.
   - The SAF picker opens with `EXTRA_INITIAL_URI = primary:Android/media/com.whatsapp/WhatsApp` and validates that `Media/` exists.
   - Stream results and support cancellation.
2. **WhatsApp layout table** (§1), extended with:
   - `Sent/` → MOVE/REVIEW, explained as "copies of what you sent".
   - `Private/` → REVIEW.
   - `.Statuses` → SAFE after 24 h.
   - A separate legacy `/sdcard/WhatsApp` leftover item.
   - A **hard denylist** for `Databases/`, `Backups/` and `*.crypt*`, enforced at the delete call, not just by omission.
3. **Swipe review, as in CleanSweep:**
   - Deferred `PendingChange` list, undo, and a summary sheet before commit.
   - Folder shortcuts that **move** files.
   - Strict/Balanced/Loose presets, a "not similar" memory, and a hash cache keyed by (path, mtime, size).
4. **Similarity:**
   - dHash 9×8 (native, downsampled decode as in Clearo).
   - Default ≤5 bits plus a color-histogram gate, as CleanSweep's BALANCED level.
   - A BK-tree instead of prefix buckets.
5. **Editable confirmation grid**, as in WhatsAppCleaner: remove items before confirming.
6. **Privilege onboarding, as in AppManagerNG** but reduced:
   - Live status, re-check, and a per-mode "what works" list.
   - Warn about root-backed Shizuku and banking apps.
7. **Cache:**
   - Without root: `StorageStatsManager.queryStatsForPackage` (Usage Access) for sizes, and `UsageStats` last-used for "unused 6 months".
   - With Shizuku: per-app cache clear (`deleteApplicationCacheFilesAsUser` / `pm clear --cache-only`), plus optional `freeStorageAndNotify` / `pm trim-caches`.
8. **MOVE commit marker, as in Neo-Backup:** write the manifest last. Extend it with per-file size + SHA-256 and a read-back verify.

### Avoid
- Permanent deletes. WhatsAppCleaner and CleanSweep both do this.
- **Lossy "exact duplicate" hashes**: partial-file or thumbnail-pixel SHA (CleanSweep). SAFE one-tap must use a full-content hash.
- Clearo's bin pattern:
  - Copy-then-delete without checking that the copy succeeded or verifying it (`copiedToBin` unused).
  - TTL shown in the UI with no purge job.
  - Unauthenticated whole-file-in-RAM encryption.
- Claiming space as "saved" while it sits in our trash on the same volume. Show "will be freed on <date>" and allow "empty now".
- O(N²) greedy similarity grouping (Clearo).
- Copying GPL-2.0-only or AGPL files.

### Open questions
- Is WhatsAppCleaner actually live on Play with the SAF build? The `play.google.com` listing for `com.vishnu.whatsappcleaner.play` returned "Not Found" from this session, and IzzyOnDroid says "check if it's there". **[UNVERIFIED]**
- Should the trash for SAF-only Play builds use `MediaStore.createTrashRequest`? The system trash auto-deletes after ~30 days and does not apply to non-media or `Android/media` files **[UNVERIFIED for Android/media]**. Or should it use an app-private copy, which doubles space temporarily?
- What is the exact shell command availability under Shizuku (shell UID) for `pm clear --cache-only` on Android 13-15 (owner's phone is Android 15)? Verify in AOSP / sdmaid-se (doc 01).
- What is the CleanSweep ↔ LoopOtto/cleansweep relationship, and which is canonical?
- Does `UsageStatsManager` retain enough history on OnePlus/ColorOS to support a 6-month "unused" claim? Needs on-device ADB check: `dumpsys usagestats`.
