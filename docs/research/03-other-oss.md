# 03 — Other open-source projects worth studying

Scope: open-source projects the owner's list (context brief) does not cover: storage analyzers, duplicate/similar-photo finders, file managers and galleries with a trash, backup/sync tools, WhatsApp tools, Shizuku utilities, and OEM notes.
Method: shallow, blobless `git clone` of each repo on 2026-10-31. **License** comes from the LICENSE file at HEAD. **Last activity** is the date of the HEAD commit on the default branch at clone time. Code claims cite permalink-style URLs (`blob/<default-branch>/path#Lx`). The GitHub REST API was blocked in this session, so star counts are not included.

---

## 0. TL;DR

- **Immich mobile is the best model for "verify, then free space".** On the phone it computes SHA-1 of the original bytes (`MediaStore.setRequireOriginal`). The server computes its own SHA-1 while it receives the upload. The app offers to free up space only for local assets whose checksum matches a non-trashed server asset owned by the user. Messaging-app albums (WhatsApp etc.) are kept by default, and local deletes go to the **system MediaStore trash**. See §4.1.
- **Ente Photos is weaker as a verification model, because it is E2EE.** It hashes on the client with BLAKE2b-512 (libsodium `crypto_generichash`). "Free up space" trusts the local DB flag `uploadedFileID IS NOT NULL` and does not re-check against the server. See §4.2.
- **There are three trash patterns in the wild** (§3):
  1. **Copy into a private app dir plus a DB row.** Fossify Gallery uses `filesDir`; Aves uses `Android/data/<pkg>/files/trash`. This costs a full copy, and the data is lost if the app is uninstalled.
  2. **A hidden folder on shared storage plus a JSON manifest.** Amaze uses `/sdcard/.AmazeData`, through the Apache-2.0 **AmazeTrashBin** library. The trash survives an uninstall.
  3. **The system MediaStore trash.** Immich uses it. It is a rename in place, with no copy, and the OS deletes the item after a system-defined period.
- **New find: czkawka now ships an Android app, "Cedinia"** (GPL-3.0 app on an MIT core, Rust + Slint). Its similar-image pipeline uses `image_hasher` perceptual hashes, a BK-tree and Hamming distance. Exact duplicates go through size → 4 KiB prehash → full hash (BLAKE3/xxh3/CRC32), backed by caches. It is the best algorithm reference available (§2).
- **Syncthing cannot be a safe MOVE target unless the PC side is configured.** The official Android app is discontinued (README). The continuation is Syncthing-Fork (`researchxxl/syncthing-android`; `Catfriend1/syncthing-android` now resolves to the same repo and commit). A PC folder set to "Receive Only" **still applies deletions**. Only "Trash Can"/versioning or `ignoreDelete` keeps the files (§5.1). **Correction to doc 06 §3.4:** it implies that a Receive Only folder on the PC protects against deletes. It does not.
- **OEM fact for the owner's phone:** the ColorOS/OnePlus File Manager recycle bin is `/sdcard/.FileManagerRecycler`. SD Maid SE's `db_trash_files.json` lists it (§8). That is very probably where the owner's 3.8 GB lives **[UNVERIFIED on device]**.

---

## 1. Storage analyzers

| Project | URL | License | Lang | Last activity | GPL-3 reuse | Relevance |
|---|---|---|---|---|---|---|
| DiskUsage (Ivan Volosyuk) | https://github.com/IvanVolosyuk/diskusage | GPL-2.0-**or-later** (file headers, e.g. https://github.com/IvanVolosyuk/diskusage/blob/master/app/src/main/java/com/google/android/diskusage/ui/DiskUsage.java) | Java/Kotlin | 2026-09-07 | **Yes** ("or later" lets us use it under GPL-3) | Classic radial/sunburst size map. Its `datasource/fast` package has a fast scanner. The README itself points to a better-maintained fork. |
| DiskUsage (WhiredPlanck fork) | https://github.com/WhiredPlanck/diskusage | GPL-2.0+ (inherited) **[UNVERIFIED file headers]** | Java/Kotlin | 2024-12-30 | Yes | The README of the upstream repo recommends this fork, but it is less recently active than upstream. |
| disky (newhinton) | https://github.com/newhinton/disky | GPL-3.0 (LICENCE) | Kotlin | 2026-05-31 | **Yes** | "Find your biggest diskspace thieves". On F-Droid as `de.felixnuesse.disky`. Same author as Round-Sync. A small, modern codebase, a good reference for a "where is my space" screen. |
| SD Maid SE Analyzer | (doc 01) | GPL-3.0 | Kotlin | 2026-10-22 | Yes | Already covered in doc 01. |

Takeaway: KeepClear does not need a treemap. The owner's data shows the answer comes from **categories** (Camera, WhatsApp, Apps), not folders. Use disky or DiskUsage only as a reference for fast recursive sizing.

---

## 2. Duplicate and similar-photo finders

| Project | URL | License | Lang | Last activity | GPL-3 reuse | Relevance |
|---|---|---|---|---|---|---|
| **czkawka / krokiet / Cedinia** | https://github.com/qarmin/czkawka | `czkawka_core`, `czkawka_cli`, `czkawka_gui`: **MIT**. `krokiet` and `cedinia`: **GPL-3.0-only**, because of Slint. Icons: CC BY 4.0. Source: each crate's `Cargo.toml` and the root LICENSE files. | Rust | 2026-10-31 | **Yes** (MIT core) | **Cedinia is an Android app** (https://github.com/qarmin/czkawka/blob/master/cedinia/README.md). It covers duplicates, similar images, empty files/folders, big files and more. It has no video similarity because FFmpeg is missing on Android. It is distributed only as APKs on GitHub releases, not on F-Droid or Play. The repo includes `CLAUDE.md`/`AGENTS.md`. |
| dupeGuru | https://github.com/arsenetar/dupeguru | GPL-3.0 | Python + C | 2026-09-07 | Yes (algorithm ideas) | Picture mode: 15×15 grid of mean-colour blocks per image, compared by average difference against a % threshold, plus an EXIF-timestamp mode. |
| SimilarPhoto (gavinliu) | https://github.com/gavinliu/SimilarPhoto | no LICENSE file | Java | 2017-03-28 | **No** (no license) | An old Android pHash demo. Ignore it. |
| image_similarity_analyzer (Flutter) | https://pub.dev/packages/image_similarity_analyzer/changelog | BSD-3 (per pub.dev) **[UNVERIFIED]** | Dart/Kotlin | **[UNVERIFIED]** | Yes | MediaStore scan with dHash + aHash and Hamming distance. Too small to depend on. |
| Clearo / CleanSweep | (doc 02) | MIT / GPL-3.0 | | 2026-08 | Yes | Already covered in doc 02. |

### 2.1 czkawka algorithms (best reference)

- **Exact duplicates** (https://github.com/qarmin/czkawka/blob/master/czkawka_core/src/tools/duplicate/core.rs):
  1. Group by size.
  2. **Prehash** of at most `2 × PREHASHING_BUFFER_SIZE` bytes, where `PREHASHING_BUFFER_SIZE = 4 KiB` (https://github.com/qarmin/czkawka/blob/master/czkawka_core/src/tools/duplicate/mod.rs#L33).
  3. **Full hash** of the survivors, with a choice of BLAKE3, xxh3 or CRC32 (`czkawka_core/Cargo.toml`).

  Both hashing stages keep caches. Hard links are filtered out with `filter_hard_links`.
- **Reference folders** (`use_reference_folders`, `hash_reference_folders()`): files in a reference folder are never offered for deletion. They only serve to mark other files as duplicates. **This maps directly onto "the phone copy is already in the backup folder"** (for example a mounted USB drive or SAF tree): the backup acts as the reference and the phone files are the candidates.
- **Similar images** (https://github.com/qarmin/czkawka/blob/master/czkawka_core/src/tools/similar_images/core.rs):
  - Hashes come from `image_hasher` (`HashAlg` and `FilterType` are user-selectable, as is `hash_size`).
  - Optional `GeometricInvariance` hashes rotated and flipped variants.
  - All hashes go into a **BK-tree with a Hamming metric**, which avoids O(n²) comparison.
  - Grouping is greedy: each hash attaches to its closest parent.
  - The `SIMILAR_VALUES` preset table maps "VeryHigh…Minimal" to Hamming thresholds per hash size (`similar_images/mod.rs#L26`).
  - Hashes are cached on disk, keyed by hash size, algorithm and filter.
- **For KeepClear:** use the same tiers (size → partial hash → full hash) for the duplicates that are SAFE to delete. Use dHash or gradient hash with a BK-tree for the REVIEW "similar photos" feature. The MIT core means a Kotlin port is fine.

### 2.2 dupeGuru picture mode

- `BLOCK_COUNT_PER_SIDE = 15`. Each picture becomes 225 average RGB blocks, rotated for each EXIF orientation when `match_rotated` is set. A match is `100 − avgdiff ≥ threshold` (https://github.com/arsenetar/dupeguru/blob/master/core/pe/matchblock.py#L37, `#L121-L163`). Results are cached in SQLite (`core/pe/cache_sqlite.py`).
- This is more robust to recompression than a 64-bit dHash, and it is O(n²) in chunks. Use it only as a second-stage confirmer for dHash candidates.

---

## 3. File managers and galleries with a trash

| Project | URL | License | Lang | Last activity | Trash? | GPL-3 reuse |
|---|---|---|---|---|---|---|
| **Aves** | https://github.com/deckerst/aves | **BSD-3-Clause** | Dart + Kotlin | 2026-10-31 (`develop`) | Yes, 30 days | **Yes** (permissive) |
| **Fossify Gallery** | https://github.com/FossifyOrg/Gallery | GPL-3.0 | Kotlin | 2026-10-31 | Yes, 30 days | Yes |
| **Amaze File Manager** | https://github.com/TeamAmaze/AmazeFileManager | GPL-3.0 | Java/Kotlin | 2026-10-25 (`release/4.0`) | Yes, through AmazeTrashBin | Yes |
| **AmazeTrashBin** (library) | https://github.com/TeamAmaze/AmazeTrashBin | **Apache-2.0** | Kotlin | 2023-12-31 | It *is* a trash library | **Yes** (Apache-2.0 can go into GPL-3) |
| Material Files | https://github.com/zhanghai/MaterialFiles | GPL-3.0 | Kotlin | 2026-10-15 | None found (no trash/recycle source files) | Yes. Its SAF/root/archive filesystem abstraction is the part worth reusing. |
| Fossify File Manager | https://github.com/FossifyOrg/File-Manager | GPL-3.0 | Kotlin | 2026-10-27 | None found in app module file names **[UNVERIFIED]** | Yes |

### 3.1 Aves recycle bin
- **Location:** `<externalFilesDir>/trash`, one per storage volume. That is `Android/data/deckers.thibault.aves/files/trash` (https://github.com/deckerst/aves/blob/develop/android/app/src/main/kotlin/deckers/thibault/aves/storage/StorageUtils.kt#L67-L79).
- **Metadata:** a DB row `TrashDetails{id, path, dateMillis}` (https://github.com/deckerst/aves/blob/develop/lib/model/metadata/trash.dart).
- **Retention:** `binKeepDuration = Duration(days: 30)`. Expired items are deleted by `deleteExpiredTrash()` (https://github.com/deckerst/aves/blob/develop/lib/model/source/trash.dart).
- **Self-healing:** `recoverUntrackedTrashItems()` lists files in the trash dirs that have no DB row. These are leftovers from a crash or a lost DB. It moves them to `Pictures/<recovery dir>` instead of deleting them. **KeepClear should copy this idea.**
- **How a file gets there:** `moveMultiple(... toBin)`. When MediaStore cannot do a same-volume move into the target, Aves "always copy[s], even for move, then delete[s]": it streams the bytes, then calls `deleteSingle()` (https://github.com/deckerst/aves/blob/develop/android/app/src/main/kotlin/deckers/thibault/aves/model/provider/ImageProvider.kt#L307-L560). My reading is that bin moves from DCIM take the copy path **[UNVERIFIED at runtime]**. That costs temporary space and I/O, which matters for 81 videos larger than 100 MB.
- **Permissions:** the manifest declares `MANAGE_MEDIA` and **no** `MANAGE_EXTERNAL_STORAGE`, so Aves can delete originals without a per-file system dialog on Android 12+.
- **Caveat:** `Android/data/<pkg>` is wiped on uninstall, so the trash is lost with it.

### 3.2 Fossify Gallery recycle bin
- **Location:** `Context.recycleBin = filesDir`, which is internal private storage (https://github.com/FossifyOrg/Gallery/blob/main/app/src/main/kotlin/org/fossify/gallery/extensions/Context.kt#L148).
- **Trashing:** `movePathsInRecycleBin()` runs `file.copyRecursively(internalFile)` and then `mediaDB.updateDeleted(..., deleted_ts = now)` (https://github.com/FossifyOrg/Gallery/blob/main/app/src/main/kotlin/org/fossify/gallery/extensions/Activity.kt#L345-L395).
  - For OTG sources it stream-copies and checks **copied byte count == source size** before marking the item deleted. That is a minimal verify step.
  - It also preserves `lastModified`.
- **Purge:** runs at most once a day **when MainActivity opens**. It calls `getOldRecycleBinItems(now − MONTH_MILLISECONDS)` (https://github.com/FossifyOrg/Gallery/blob/main/app/src/main/kotlin/org/fossify/gallery/activities/MainActivity.kt#L1641-L1647; DAO query at `interfaces/MediumDao.kt#L23`). There is no background worker, so the bin grows while the app is unused.
- **Restore:** if the original location is no longer writable on SDK 30+, it falls back to `Pictures/` (`Activity.kt#L415-L420`).

### 3.3 Amaze + AmazeTrashBin
- **Base dir:** `/sdcard/.AmazeData` (https://github.com/TeamAmaze/AmazeFileManager/blob/release/4.0/app/src/main/java/com/amaze/filemanager/application/AppConfig.java#L89-L90). It is configured with `TrashBinConfig(basePath, days, bytes, numOfFiles, intervalHours, deleteRogue=false, …)` (`#L290-L312`).
- **Library** (https://github.com/TeamAmaze/AmazeTrashBin/blob/main/README.md):
  - Files live in `baseDir/TrashBinFiles/`, with a `metadata.json` index.
  - Retention can be set by **days, total bytes and file count**.
  - There is a "rogue file" cleanup.
  - The library warns: "avoid using app cache directory … if user uninstall your app they'll lose all their files".
- **Code quality:** the actual move is a caller-supplied callback (`moveToBin(files, …, moveFilesCallback)`), so it can be a cheap same-volume `rename`. There is a small bug: the result of `filesMetadata.sortedByDescending{}` is discarded (https://github.com/TeamAmaze/AmazeTrashBin/blob/main/trashbin/src/main/java/com/amaze/trashbin/TrashBin.kt#L137-L162). The library is unmaintained (last commit 2023-12-31). **Port the design, don't add the dependency.**

### 3.4 Trash pattern comparison (for KeepClear's 30-day trash)

| Pattern | Space freed immediately? | Copy cost | Survives uninstall | Visible to other apps | Used by |
|---|---|---|---|---|---|
| Private dir (`filesDir` / `Android/data`) | No | **Full copy** across FUSE/volume | No | No | Fossify Gallery, Aves |
| Hidden folder on `/sdcard` + manifest | No | Rename on the same volume (cheap) | **Yes** | Hidden-file browsers can see it | Amaze, ColorOS `.FileManagerRecycler`, Files by Google `.FilesByGoogleTrash` |
| System MediaStore trash (`createTrashRequest`, `IS_TRASHED`) | No | None (rename to `.trashed-…`) | Yes (system-owned) | Yes, through system/gallery UIs | Immich (§4.1) |

Android documents only that "items in the trash are permanently deleted after a system-defined time period" (https://developer.android.com/training/data-storage/shared/media). It does not state a fixed 30 days.

**Key product tension:** no trash pattern frees space until the purge. The owner's phone is 93 % full. KeepClear needs an explicit "empty now" action, or a policy such as "MOVE items whose backup has been verified skip the trash, or purge after N days", for the trash to deliver anything.

---

## 4. Photo-backup apps: how they decide "already backed up" (critical for MOVE verify)

### 4.1 Immich mobile (Flutter + Kotlin). AGPL-3.0, https://github.com/immich-app/immich, last activity 2026-10-30
1. **Local hash.** `HashService.hashAssets()` runs per backup album, in batches, and stores the result in the Drift DB (https://github.com/immich-app/immich/blob/main/mobile/lib/domain/services/hash.service.dart). The native side, `hashAsset()`, does the following (https://github.com/immich-app/immich/blob/main/mobile/android/app/src/main/kotlin/app/alextran/immich/sync/MessagesImplBase.kt#L447-L480):
   - computes `MessageDigest.getInstance("SHA-1")` over `MediaStore.setRequireOriginal(uri)`, the **original** bytes including location EXIF;
   - streams with a buffer and checks for cancellation;
   - Base64-encodes the digest;
   - limits concurrency with a semaphore.

   It also hashes items that are in the Android trash, as a pseudo-album.
2. **The server computes its own checksum.** The upload interceptor runs `createHash('sha1')` over the received stream (https://github.com/immich-app/immich/blob/main/server/src/middleware/file-upload.interceptor.ts#L118-L138). A unique (owner, checksum) constraint turns a re-upload into `DUPLICATE` (https://github.com/immich-app/immich/blob/main/server/src/services/asset-media.service.ts#L218-L231). The **checksum is therefore calculated independently on both ends**, which gives real end-to-end integrity.
3. **Pre-upload check.** `POST /assets/bulk-upload-check` returns REJECT/DUPLICATE plus `isTrashed` for checksums the server already has (`asset-media.service.ts#L321-L345`).
4. **Backup status.** The query is `local_asset LEFT JOIN remote_asset ON lae.checksum = rae.checksum AND owner_id = me`. A null checksum counts as "processing", and no match counts as "remainder" (https://github.com/immich-app/immich/blob/main/mobile/lib/infrastructure/repositories/backup.repository.dart#L36-L50).
5. **Free up space.** `getRemovalCandidates()` joins on `INNER JOIN remote_asset ON checksum` and adds these conditions (https://github.com/immich-app/immich/blob/main/mobile/lib/infrastructure/repositories/local_asset.repository.dart#L159-L204):
   - `createdAt ≤ cutoffDate`;
   - `remote.ownerId = me` **and `remote.deletedAt IS NULL`**, so an asset in the server trash does not count;
   - not in iOS shared albums;
   - not in albums the user chose to keep;
   - optionally only photos or only videos.

   `CleanupService.getDefaultKeepAlbumIds()` **keeps albums whose names contain whatsapp/telegram/signal/messenger/viber/wechat/line by default** (https://github.com/immich-app/immich/blob/main/mobile/lib/services/cleanup.service.dart).
6. **Delete.** `deleteAll(ids, trash: true)` uses `PhotoManager.editor.android.moveToTrash` (the MediaStore trash) when it is supported. Otherwise it deletes outright (https://github.com/immich-app/immich/blob/main/mobile/lib/repositories/asset_media.repository.dart#L50-L57). Restores go through `MediaTrashDelegate`, which needs `MANAGE_MEDIA` (`.../sync/MediaTrashDelegate.kt`).

**Lessons for KeepClear MOVE:**
- Hash the **original** stream (`setRequireOriginal`).
- Persist hashes keyed by MediaStore ID and invalidate them on size/mtime change.
- Treat a destination as verified only when the **destination computed its own hash**, or when KeepClear read the file back and re-hashed it.
- Exclude items that sit in the destination's trash.
- Keep messaging-app media out of auto-cleanup by default.
- Apply a cutoff date.

Licensing: AGPL code must not be copied into GPL-3.0 KeepClear without the combined work taking on AGPL §13 obligations. **Re-implement; do not copy.** Calling the Immich HTTP API is fine (see doc 06 §6).

### 4.2 Ente Photos (Flutter). AGPL-3.0, https://github.com/ente-io/ente (`mobile/apps/photos`), last activity 2026-10-31
- **Hash:** `fileHash = bin2base64(CryptoUtil.getHash(sourceFile))` (https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/module/upload/upload_data.dart#L97). `getHash` comes from `ente_crypto_dart` and is libsodium `genericHash`, i.e. **BLAKE2b with `outLen = bytesMax` (64 bytes)**, computed as a stream (https://github.com/ente/ente_crypto_dart/blob/main/lib/src/crypto_util.dart#L82-L89; that repo is GPL-3.0, last activity 2026-10-08). Live photos get a combined image+video hash.
- **Dedup:** `existing_upload_resolver.dart` looks up already-uploaded files with the same hash and type for the owner, then relinks or skips the upload.
- **"Free up space"** (https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/db/files_db.dart#L650-L676):
  - The candidates are rows with `localID NOT NULL AND uploadedFileID NOT NULL/-1 AND updationTime NOT NULL`. In other words, the app **trusts its local "upload committed" state** and does not ask the server again.
  - The page refreshes the candidate list just before deleting and keeps only the intersection (`retainOriginalDeletionCandidates`, https://github.com/ente-io/ente/blob/main/mobile/apps/photos/lib/ui/tools/free_space_page.dart#L119-L127).
  - Deletion is batched and checkpointed (`services/free_space/deletion_batch_runner.dart`).
- **Why this is weaker:** because of E2EE the server only stores ciphertext, so it can never vouch for the plaintext hash. Ente's guarantee rests on the upload protocol (encrypted upload plus a server ack).
- **For KeepClear:** the refresh-and-intersect step before deleting, and batched deletes with checkpoints, are good patterns to copy.

### 4.3 Nextcloud Android (auto-upload). https://github.com/nextcloud/android, GPL-2.0-only AND (AGPL-3.0-or-later OR GPL-2.0-only) per SPDX headers, last activity 2026-10-31
- Auto-upload offers a local behaviour of COPY, MOVE, DELETE or FORGET. **DELETE calls `Files.delete(original)` right after the upload succeeds** (https://github.com/nextcloud/android/blob/master/app/src/main/java/com/owncloud/android/operations/UploadFileOperation.java#L1328-L1352). I found no checksum read-back on that path; the code only uses etag/mtime for conflicts **[UNVERIFIED whether the library sends a checksum header]**.
- This is the **anti-pattern** KeepClear must avoid.
- Licence: GPL-2.0-only code is **not** GPL-3-compatible. Do not reuse it.

### 4.4 Seedvault. https://github.com/seedvault-app/seedvault, Apache-2.0 (README), last activity 2026-10-14 (branch `android17`)
- Seedvault is a system app and "can not be installed as a regular app" (README), so it is not usable as a target.
- **The file-backup design is worth stealing** (https://github.com/seedvault-app/seedvault/blob/android17/storage/doc/design.md):
  - change detection from MediaStore **size + lastModified + generation**;
  - chunk IDs are keyed HMAC-SHA256;
  - AES-GCM encryption;
  - snapshots list chunk IDs per file.
- The `(size, mtime, GENERATION_MODIFIED)` triple is the right cache key for KeepClear's hash cache.

---

## 5. Sync tools as backup paths

### 5.1 Syncthing on Android
| Project | URL | License | Last activity | Status |
|---|---|---|---|---|
| syncthing-android (official) | https://github.com/syncthing/syncthing-android | MPL-2.0 | 2024-12-03 | **Discontinued**. The README says Play publishing became "between hard and impossible". |
| Syncthing-Fork | https://github.com/researchxxl/syncthing-android (F-Droid `com.github.catfriend1.syncthingfork`) | MPL-2.0 | 2026-10-21 | Active. `github.com/Catfriend1/syncthing-android` now serves the identical commit (`f881907`), so it was transferred or redirected. |

**Syncthing as the PC backup path:**
- **Verification is possible through the REST API.**
  - `GET /rest/db/completion?folder=&device=` gives `completion` and `needBytes` (doc 06 §3.4).
  - `GET /rest/db/file?folder=&file=` gives per file the `availability` (the device IDs that have it) and `global`/`local` entries with `size`, `modified` and `version`. **It exposes no content hash**, only `numBlocks` (https://docs.syncthing.net/rest/db-file-get.html).
  - KeepClear could therefore confirm "PC device X has this file at the same size/version", but **not** byte-identity, unless it hashes the file on the PC side itself.
- **Deletes propagate.** "Receive Only" on the PC still applies remote changes: "all changes from the cluster are applied" (https://docs.syncthing.net/users/foldertypes.html). The safe setups are:
  - (a) **Trash Can versioning** on the PC. Files deleted by remote devices are moved to `.stversions`, and `cleanoutDays = 0` keeps them forever (https://docs.syncthing.net/users/versioning.html).
  - (b) `ignoreDelete` on the PC folder, which the docs mark as "not recommended, unless you are a power user" (https://docs.syncthing.net/advanced/folder-ignoredelete.html).
- **Correction to doc 06 §3.4:** it says the delete risk is avoided with a "Send Only" phone folder plus a "Receive Only"/versioning PC folder. Receive Only alone does **not** protect, so versioning (or `ignoreDelete`) is required.
- **Verdict:** keep it as an "advanced, detect and guide" path. If KeepClear detects Syncthing-Fork, it can check `completion == 100` and require that the user confirms PC versioning. Never treat Syncthing as verified backup by default.

### 5.2 Other sync tools
| Project | URL | License | Lang | Last activity | GPL-3 reuse | Relevance |
|---|---|---|---|---|---|---|
| DAVx⁵ | https://github.com/bitfireAT/davx5-ose | GPL-3.0 | Kotlin | 2026-10-29 | Yes | It exposes **WebDAV mounts as a SAF DocumentsProvider** (https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/webdav/DavDocumentsProvider.kt). KeepClear could write MOVE copies to Nextcloud/WebDAV through SAF without its own network code, which is consistent with "no network except user-chosen targets". Verify by reading the file back through SAF; it is slow over the network. |
| Round Sync (rclone) | https://github.com/newhinton/Round-Sync | GPL-3.0 (+ the rcloneExplorer licence file) | Kotlin + rclone (Go, MIT) | 2025-09-09 on `master` (other branches **[UNVERIFIED]**) | Yes | It bundles the rclone binary. rclone `check`/`hashsum` give server-side hashes for many backends, which is a potential "verify on cloud" path. Activity has slowed. |
| RCX (rcloneExplorer fork) | https://github.com/x0b/rcx | GPL-3.0 | Java | 2021-07-09 | Yes | Dead. Round Sync is its successor. |
| FolderSync | (Tacit Dynamics) | Proprietary **[UNVERIFIED]** | | | No | Closed source. It is only a UX reference for SMB/SFTP "sync then delete source". |

---

## 6. WhatsApp-specific tools
- **WhatsAppCleaner** (VishnuSanal), GPL-3.0, already covered in doc 02. F-Droid lists v1.4.1 with Android 7.0+ and `MANAGE_EXTERNAL_STORAGE` (https://f-droid.org/packages/com.vishnu.whatsappcleaner/).
- A web search found **no other maintained open-source WhatsApp cleaner** (search 2026-10-31; results limited to the F-Droid listing above). In particular, no project targets **`Sent/` folder duplicates** specifically. That is an open gap KeepClear can own: hash the files in `WhatsApp Images/Sent` against the camera and received files.
- Immich's default keep-list for messaging apps (§4.1) is a reminder that WhatsApp media should be **REVIEW, not auto**.

---

## 7. Shizuku-based utilities (for "11 apps unused 6 months = 2.8 GB")
| Project | URL | License | Lang | Last activity | GPL-3 reuse | Relevance |
|---|---|---|---|---|---|---|
| **Hail** | https://github.com/aistra0528/Hail | GPL-3.0 | Kotlin | 2026-10-28 | Yes | Freezes apps: **disable / hide / suspend**, through Shizuku, Device Owner, Dhizuku or root. "Suspend" (grayscale icon, reversible) is a gentle option for unused apps, but it **frees no space**. |
| **Canta** | https://github.com/samolego/Canta | **LGPL-3.0** | Kotlin | 2026-10-31 | Yes | Uninstalls any app through Shizuku. The README links an **Android 16 Shizuku fork** (https://github.com/thedjchi/Shizuku), a compatibility signal to watch. This is the reference for "uninstall unused app" via `pm uninstall` (for user apps a normal `ACTION_DELETE` intent is enough, without Shizuku). |
| **Inure** | https://github.com/Hamza417/Inure | GPL-3.0 | Kotlin | 2026-10-31 | Yes, with care | App manager: usage stats, batch operations, Root and Shizuku modes. Some features are behind a **paid "Unlocker"** (README). Use it as a UX reference for usage-stats-based "unused apps". |
| AppManager / AppManagerNG | (doc 02) | GPL-3.0 | | | | Already covered in doc 02. |

KeepClear should not need Shizuku for this category. `UsageStatsManager` (with `PACKAGE_USAGE_STATS`) plus the `ACTION_DELETE` intent covers "unused apps → uninstall" (see doc 04). Shizuku only adds batch uninstalls without a prompt for each app.

---

## 8. OEM-specific notes (trash locations that silently hold space)
From SD Maid SE's curated DB (https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/assets/expendables/db_trash_files.json, used by `RecycleBinsFilter.kt`):

| Package | Trash location |
|---|---|
| `com.coloros.filemanager` (OnePlus/OPPO **File Manager**) | `/sdcard/.FileManagerRecycler/` |
| `com.oneplus.gallery` | `Android/data/com.oneplus.gallery/files/recyclebin/` + `databases/recyclebin.db` (private) |
| `com.miui.gallery` | `/sdcard/MIUI/Gallery/cloud/.trashBin/` |
| `com.google.android.apps.nbu.files` | `/sdcard/.FilesByGoogleTrash/` |
| Generic names SD Maid SE matches | `.trash`, `.trashfiles`, `.trashbin`, `.recycle`, `.recyclebin`, `.garbage` (`RecycleBinsFilter.kt#L126-L136`) |
| System MediaStore trash | files prefixed `.trashed-<expiry>-` (SD Maid SE `TrashedFilter.kt#L56`) |

- **Owner's phone:** the 3.8 GB "file manager recycle bin" most likely sits in `/sdcard/.FileManagerRecycler`. It is reachable with `MANAGE_EXTERNAL_STORAGE`, because it is on shared storage and not under `Android/data`. **[UNVERIFIED on device; confirm with `adb shell du -sh /sdcard/.FileManagerRecycler`]**
- **Pre-2022 leftovers:** the `MIUI/` folder (2.8 GB, ShareMe) and possibly `MIUI/Gallery/cloud/.trashBin`.
- Samsung's My Files bin "cannot be turned off" (https://www.samsung.com/za/support/apps-services/the-updated-recycle-bin-feature-in-my-files-on-galaxy-devices-with-one-ui-6/). Its path is not in SD Maid SE's DB **[UNVERIFIED]**.
- **Recommendation:** ship an "Other apps' recycle bins" SAFE/REVIEW card. Seed it with this table, credit SD Maid SE (GPL-3.0, compatible), and keep the data in a JSON file that can be updated.

---

## 9. Top 10 to study (ranked)

| # | Project | What to study | Why |
|---|---|---|---|
| 1 | **Immich mobile** | `hash.service.dart`, `MessagesImplBase.hashAsset`, `getRemovalCandidates`, `cleanup.service.dart`, `deleteAll(trash:true)` | The complete, production-tested "verify by checksum, then free space" flow. Study it, do not copy it (AGPL). |
| 2 | **czkawka core (+ Cedinia)** | `duplicate/core.rs` (size → prehash → hash, caches, reference folders), `similar_images/core.rs` (image_hasher + BK-tree) | Best duplicate/similar algorithms; MIT core; there is now an Android front end. |
| 3 | **Aves** | `StorageUtils.trashDirFor`, `TrashMixin` (30 days, untracked recovery), `ImageProvider.moveMultiple`, MANAGE_MEDIA usage | A mature media trash under BSD-3, so its code is reusable. |
| 4 | **SD Maid SE `db_trash_files.json` + `RecycleBinsFilter`** | OEM trash paths | Finds the owner's 3.8 GB directly. |
| 5 | **Fossify Gallery** | `movePathsInRecycleBin`, `getOldRecycleBinItems`, restore fallback | A simple GPL-3 trash, plus a lesson: purge only when the app opens is a weakness. |
| 6 | **AmazeTrashBin** | Config (days/bytes/count), `metadata.json`, rogue cleanup | An Apache-2.0 design for a trash that survives uninstall on shared storage. |
| 7 | **Ente Photos** | `files_db.getFreeableFileIDs`, `free_space_page` refresh-and-intersect, `deletion_batch_runner` | Safe batched deletion patterns. Its verification model is the weaker one to compare against. |
| 8 | **Seedvault storage design** | MediaStore change detection (size, mtime, generation), chunked HMAC IDs | Hash-cache invalidation and a possible future backup format (Apache-2.0). |
| 9 | **Syncthing-Fork + Syncthing REST docs** | `/rest/db/completion`, `/rest/db/file`, versioning/ignoreDelete | The PC backup path's verification limits and its delete-propagation trap. |
| 10 | **DAVx⁵ `DavDocumentsProvider`** | WebDAV exposed through SAF | A MOVE target with no network code in KeepClear (GPL-3). |

Honourable mentions: dupeGuru picture blocks (a second-stage confirm), disky (size screen), Hail/Canta (app freezing and uninstall).

---

## 10. Implications for KeepClear

### Adopt
- **A hash cache keyed by MediaStore `_ID` + (size, DATE_MODIFIED, GENERATION_MODIFIED)**, as in Seedvault and Immich. Hash `setRequireOriginal` streams.
- **MOVE verified means one of two things:**
  - the destination computed its own hash and it matches (an Immich server, or a KeepClear read-back over SAF/USB); or
  - the item is not in the destination's trash (as with Immich `deletedAt IS NULL`).

  Never verify from the "upload succeeded" signal alone, which is the Nextcloud DELETE path.
- **Refresh and intersect candidates immediately before deleting** (Ente), and delete in checkpointed batches.
- **Duplicate pipeline from czkawka:** size → 4 KiB head/tail prehash → full hash. Add "reference folder" semantics to answer "already in backup".
- **Similar photos:** dHash or gradient hash plus a BK-tree with Hamming thresholds tied to hash size.
- **Trash:**
  - Prefer the **system MediaStore trash** for media on Android 11+ with `MANAGE_MEDIA` (no copy, Immich-style).
  - Use a **hidden shared-storage folder plus a manifest** (Amaze-style) for non-media files.
  - Add Aves-style **untracked-item recovery**.
  - Purge with WorkManager, not only on app open.
- **Messaging-app media is REVIEW by default** (Immich keep-list).
- **An "Other apps' recycle bins" card**, seeded from SD Maid SE's JSON (credit GPL-3.0).

### Avoid
- Copying AGPL code (Immich, Ente) or GPL-2.0-only code (Nextcloud) into the codebase.
- A private-dir trash (`filesDir`, `Android/data`) for multi-GB videos. It costs a copy, temporary space and data loss on uninstall.
- Treating Syncthing completion as proof of backup without PC-side versioning. Receive Only is not enough.
- Depending on unmaintained libraries (AmazeTrashBin 2023, RCX 2021). Port their ideas instead.

### Open questions
1. A trash does not free space for 30 days. Should verified-MOVE items skip the trash, or use a shorter retention, given the owner's phone is 93 % full?
2. Does the OnePlus (ColorOS) Android 15 MediaStore trash honour `createTrashRequest` with `MANAGE_MEDIA` and no dialog? What is its retention period? Test on the owner's device.
3. Should KeepClear offer "verify against an existing Immich server" without uploading (SHA-1 bulk check), as a quick win for users who already self-host?
4. Should Cedinia/czkawka be recommended to power users as a companion, or should the algorithms be ported to Kotlin? A Rust core through JNI would add a lot of build complexity.
5. Should the Syncthing path be "detect and guide" only, or should KeepClear add a PC-side hash receipt (doc 06 §4.3) to get byte-level verification?
