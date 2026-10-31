# 06 — Backup targets for MOVE, and how to verify backups reliably

Status: research, 2026-10-31. Scope: the MOVE flow (backup, then verify, then delete) for ~75 GB of camera media
(10,472 files, 81 videos >100 MB = 14.85 GB) plus WhatsApp media, on the owner's OnePlus 10T / Android 15.
Every claim is cited. **[UNVERIFIED]** = could not confirm from a primary source. **[ESTIMATE]** = back-of-envelope math, not measured.

---

## 0. TL;DR

| # | Target | MVP? | Verify strength | Main blocker |
|---|--------|------|-----------------|--------------|
| A | **External USB-C drive / SD card via SAF** | **Yes, first** | Strong: full read-back hash from the drive itself | FAT32 4 GiB file limit; USB 2.0 port on the owner's phone (~35 MB/s) |
| B | **SMB share on a home PC/NAS (smbj)** | **Yes, second** | Strong: read back over network + hash (no server-side hash in SMB) | Read-back doubles traffic; NTLM/MD4 quirks on Android |
| C | **PC over USB with a desktop companion (ADB)** | Phase 2 | Strong, if companion hashes and returns a signed-off receipt | Needs USB debugging + a desktop tool; fragile for non-technical users |
| D | WebDAV / Nextcloud | Phase 2 | Strong on Nextcloud (`X-Hash: sha256` response header); generic WebDAV = read-back | Many server dialects |
| E | SFTP (sshj) | Phase 2-3 | Strong: read-back, or `sha256sum` over SSH exec | Power-user only |
| F | Immich | Phase 3 | Medium-strong: server-side SHA-1 lookup | Separate API; SHA-1 only |
| G | Google Drive via SAF | **No** | **Cannot verify** (cached local copy; upload is async) | No folder-tree picker; async upload |
| H | Google Drive via REST API (`drive.file`) | Maybe later | Strong: server `sha256Checksum` | OAuth on Android without Play Services is hard (F-Droid) |
| I | Google Photos | **No (as a verified target)** | **Impossible**: no API to read user's library since 2025-03-31 | Policy |
| J | Plain MTP (PC pulls, app passive) | Only as "manual, unverified" | None from phone side | Phone can't see PC |

Key finding: **the owner's OnePlus 10T has a USB 2.0 port** (OnePlus spec page: "USB 2.0", https://www.oneplus.com/vn/10t/specs;
Notebookcheck: "the USB-C port of the OnePLus 10T is only of the 2.0 specification",
https://www.notebookcheck.net/OnePlus-10T-smartphone-review-The-more-efficient-OnePlus-10-Pro-thanks-to-Snapdragon-8-Gen-1-accompanied-by-insanely-fast-charging.641734.0.html).
The brief assumed USB 3. On this phone **Wi-Fi 6 (measured ~933 Mbit/s by Notebookcheck) is ~3x faster than any USB path**.
So SMB over Wi-Fi is not a "slow fallback"; for this owner it is the fast path.

---

## 1. Throughput and time budget (75 GB)

| Path | Raw link | Realistic sustained | 75 GB copy | + full read-back verify | Source / note |
|------|----------|--------------------|------------|-------------------------|---------------|
| USB 2.0 (any: MTP, ADB, OTG drive) | 480 Mbit/s | 30-40 MB/s | 32-43 min | Remote read-back +32-43 min (OTG drive: same link) | [ESTIMATE]; USB 2.0 HS = 480 Mbit/s; one report of adb push ~22 MB/s on USB 2.0 (https://www.v2ex.com/t/515462) |
| USB 3.x (other phones) | 5 Gbit/s | 100-300 MB/s; adb historically slower | 4-13 min | similar | [ESTIMATE]; AOSP adb commit "large boost to speed on usb 3.0" (https://gerrit.omnirom.org/plugins/gitiles/android_system_core/+/b5a34a2bce41d3f944223a1d8c7a10599802af36%5E%21) |
| Wi-Fi 6, 2x2, 5 GHz, near router | ~1200 Mbit/s PHY | lab iperf3 933 Mbit/s (~116 MB/s); home SMB realistically 40-90 MB/s | 14-31 min | +14-31 min | Notebookcheck iperf3 (URL above); SMB/home figure [ESTIMATE] |
| Wi-Fi 5 / far from router | — | 10-30 MB/s | 42-125 min | double | [ESTIMATE] |

Notes
- 10,472 files means per-file overhead matters (SAF `createDocument` round-trip, SMB create/close, MTP object handle). Budget ~20-50 ms/file [ESTIMATE] = 3.5-9 min extra.
- Source-side hashing on the phone: SHA-256 via `MessageDigest` (Conscrypt, ARMv8 SHA2 instructions) runs far above USB 2.0 / Wi-Fi rates [ESTIMATE ~0.5-1.5 GB/s on Snapdragon 8+ Gen 1]; UFS 3.1 read is also not the bottleneck. **I/O link, not hash, is the bottleneck**, so hash choice should optimize interoperability, not speed (see §7).
- Read-back verification of a remote target doubles transfer time. Total worst realistic case for this owner: ~1.5 h over USB 2.0, ~1 h over Wi-Fi 6. Must run as a long, resumable, background job (§9).

---

## 2. Target A — External USB-C drive / SD card via SAF (recommended MVP #1)

### Feasibility and API
- `ACTION_OPEN_DOCUMENT_TREE` lets the user pick a folder; app persists access with `takePersistableUriPermission()` (https://developer.android.com/training/data-storage/shared/documents-files).
- Android 11+ blocks picking: internal-storage root, the **root of SD volumes the OEM marks "reliable"**, and `Download/` (same URL). Removable USB OTG drives are not "reliable" in that sense, so root should be pickable **[UNVERIFIED per-OEM]**; UX should ask the user to pick/create a `KeepClear-Backup/` subfolder anyway, which always works.
- Persisted grant is lost if the folder is moved/deleted (same URL); a reformatted/replaced drive yields a new volume UUID and needs re-pick.
- Alternative without SAF: `MANAGE_EXTERNAL_STORAGE` + `java.io.File` on `/storage/XXXX-XXXX/`. Faster (no Binder per call), but whether all-files access covers removable volumes for writes is **[UNVERIFIED]**; keep SAF as the portable path.

### Filesystems mounted by Android (vold)
| FS | Android support | Max file | Notes |
|----|-----------------|----------|-------|
| FAT32 (vfat) | Universal baseline | **4 GiB** (https://learn.microsoft.com/en-us/windows/win32/fileio/filesystem-functionality-comparison) | Many USB sticks ship FAT32. 2-second mtime granularity **[UNVERIFIED here; FAT spec]** |
| exFAT | Kernel + vold helpers needed; Pixels got it with Android 13 (https://www.androidpolice.com/pixel-android-13-exfat-support-history/, https://www.esper.io/blog/android-dessert-bites-27-exfat-on-pixel-532176849). OnePlus/Oppo has shipped exFAT for years **[UNVERIFIED for 10T]** | 2^64-1 | Best choice for large drives |
| NTFS | Not mounted by stock AOSP vold; kernel ntfs3 exists since Linux 5.15 but vold lacks support (same sources) | — | Tell user to format exFAT; OEMs vary **[UNVERIFIED]** |

- **4 GiB check is mandatory**: the owner has 81 videos >100 MB; any 4K video >4 GiB fails on FAT32 mid-write. Pre-flight: query volume FS type (`StorageVolume`/`statfs` f_type **[UNVERIFIED exact API on SAF]**) or simply attempt and catch `EFBIG`/IOException; better: list files >4 GiB up front and refuse/route them.
- Pre-flight free space check (`StorageStatsManager` / `StatFs` on the volume path, or `DocumentsContract` root `COLUMN_AVAILABLE_BYTES`).

### Write durability
- Write via `ContentResolver.openFileDescriptor(uri, "w")` → `FileOutputStream`; call `fd.sync()` (`FileDescriptor.sync()` → fsync) before close. For `ExternalStorageProvider` the fd is a real file on the FUSE/vfat mount, so fsync reaches the device **[UNVERIFIED through FUSE layer, but FUSE passes fsync]**.
- **Unmount mid-copy**: user yanks the drive or it browns out (USB OTG power). Expect `IOException`/`EIO`, a truncated `.part` file, possibly a dirty FAT needing fsck. Mitigation: temp name + rename (§8), re-verify on resume, never trust a file whose final rename didn't happen.
- Power: OTG drive draws from phone battery; HDD-based drives may brown out — recommend SSD or a powered hub [ESTIMATE / general knowledge].

### Read-back verification
- Re-open the written document **read-only**, stream, SHA-256, compare to source hash. This reads from the drive (no network), so it is a true verification, but: **page cache** may serve the just-written bytes from RAM instead of the medium. For strong verify, (a) verify in a second pass after all files are written (cache likely evicted for 75 GB on a 8-16 GB RAM phone), or (b) ask the user to unplug/replug before "Verify & delete" and verify then. Option (b) is the gold standard and maps to a nice UX ("Plug the drive back in to confirm your backup").
- Also check: `DocumentsContract.Document.COLUMN_SIZE` equals source size; file count in manifest equals count on drive.

### Libraries
None needed: framework SAF + `DocumentFile` (AndroidX, Apache-2.0). `DocumentFile.findFile()` is O(n) per call — on 10k files use `DocumentsContract.buildChildDocumentsUriUsingTree` + one query per directory **[performance claim widely reported; UNVERIFIED benchmark]**.

---

## 3. Target B — Local network: SMB / WebDAV / SFTP / Syncthing

### 3.1 SMB (recommended MVP #2)
| Library | License | Status | Notes |
|---------|---------|--------|-------|
| **smbj** (com.hierynomus:smbj) | Apache-2.0 (Maven POM, https://central.sonatype.com/artifact/com.hierynomus/smbj) | Active; 0.15.0 is latest release (https://github.com/hierynomus/smbj/releases) | SMB2/SMB3 per README (https://github.com/hierynomus/smbj). Android issue: "no such algorithm: MD4 for provider BC" for NTLM (https://github.com/hierynomus/smbj/issues/42) → bundle full BouncyCastle (`bcprov`, MIT-style license) or a custom MD4. Used by F-Droid SambaLite with a workaround (https://f-droid.org/de/packages/de.schliweb.sambalite) |
| jcifs-ng (eu.agno3.jcifs) | LGPL-2.1 (https://central.sonatype.com/artifact/eu.agno3.jcifs/jcifs-ng) | 2.1.10 on Maven; SMB2 (2.02) + "some SMB3" (https://github.com/otavard/jcifs-ng) | LGPL is GPL-3.0 compatible; SMB3 support weaker |

- Both licenses are GPL-3.0-compatible (Apache-2.0 → GPL-3 one-way; LGPL-2.1+ → GPL-3).
- **Owner said smbj Apache-2.0, jcifs-ng LGPL — confirmed.**
- **Server-side hash: none.** SMB2 has no "hash this file" request usable here (BranchCache `FSCTL_SRV_READ_HASH` is for content-information, not a whole-file digest) **[UNVERIFIED in MS-SMB2 detail]**. Verification = read back over network + hash. Doubles traffic (§1).
- Mitigation for page/oplock caching: open with no caching / new session for read-back; Windows server cache still may serve from RAM, which is acceptable (server RAM → disk is the server's durability job; SMB2 FLUSH request forces it — smbj exposes `File.flush()` **[UNVERIFIED API name]**).
- Discovery: user types `\\host\share` or IP; mDNS/NetBIOS discovery optional. Credentials stored with Android Keystore-wrapped encryption.
- Failure modes: Wi-Fi drops (resume), PC sleeps mid-transfer (tell user to disable sleep / keep plugged), SMB1-only old NAS (unsupported; smbj is SMB2+), guest/anonymous sessions quirks.
- Filename translation: Windows NTFS reserved chars/names (§10) apply even though Android created the name.

### 3.2 WebDAV (Nextcloud, ownCloud, rclone serve, NAS apps)
| Library | License | Status |
|---------|---------|--------|
| sardine-android (thegrizzlylabs) | Apache-2.0 (https://github.com/thegrizzlylabs/sardine-android) | Last commit 2024-02-14 (https://github.com/thegrizzlylabs/sardine-android/commits/master) — low activity |
| Raw OkHttp (PUT/PROPFIND/MOVE/MKCOL) | Apache-2.0 | WebDAV verbs are trivial; recommended over sardine |

- WebDAV (RFC 4918) does **not** standardize checksums. **Owner said "Nextcloud has OC-Checksum header" — partially correct**: per Nextcloud docs (https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html):
  - `OC-Checksum` request header: client-supplied value is **stored, not validated, for regular PUT**; validated only for bulk upload.
  - `X-Hash: sha256` request header: **server hashes content while writing and returns `X-Hash-SHA256`** in the response. This is the useful one: server-computed SHA-256 of what it actually received = verification without download.
  - `oc:checksums` PROPFIND property returns stored checksums (MD5, SHA1, SHA256, SHA3-256, Adler32) — but these may be client-supplied, so don't trust them as proof.
  - `X-OC-MTime` sets mtime.
- Generic WebDAV server: verify by GET read-back + hash.
- Caveat: `X-Hash` proves bytes reached the server process, not that they're on disk durably — acceptable.

### 3.3 SFTP
- **sshj**: Apache-2.0, latest 0.40.0, "complete sftp version 0-3", SFTP resume since 0.34.0, BouncyCastle optional since 0.39.0, Android compatibility fixes (https://github.com/hierynomus/sshj).
- Verification: SFTP v3 has no hash op (the `check-file` extension isn't in OpenSSH) **[UNVERIFIED for every server]**; if user grants shell, run `sha256sum -- 'path'` over an SSH exec channel (sshj supports exec) — no read-back needed. Else read-back.
- Target audience: NAS/Linux users. Phase 2-3.

### 3.4 Syncthing interplay
- Official syncthing-android was discontinued (last release Dec 2024); fork moved from Catfriend1 to researchxxl with a contested handover (https://forum.syncthing.net/t/discontinuing-syncthing-android/23002?page=2, https://forum.syncthing.net/t/does-anyone-know-why-syncthing-fork-is-no-longer-available-on-github/25661?page=2).
- Syncthing exposes `GET /rest/db/completion?folder=&device=` → `completion`, `needBytes`, `needItems` for a remote device (https://docs.syncthing.net/rest/db-completion-get.html). Theoretically KeepClear could treat "remote device 100% complete for DCIM folder" as backup proof. But: needs the Syncthing API key (user copy/paste), localhost HTTP (INTERNET permission), and **Syncthing is sync, not backup: deleting on phone propagates the delete to the PC** unless the folder is "Send Only" on phone + "Receive Only"/versioning on PC. Moving = deleting = data loss risk.
- **Correction (from [03-other-oss](03-other-oss.md)):** a PC folder set to "Receive Only" still applies deletes coming from the phone. Only File Versioning (e.g. trash-can versioning) or the `ignoreDelete` advanced option protects the PC copy. The completion API also reports no content hash, so it cannot give L3 verification.
- Verdict: **Avoid as a MOVE target.** Optional later: detect Syncthing and warn ("this folder is synced; deleting here deletes there").

---

## 4. Target C — PC over USB

### 4.1 MTP (phone passive)
- Phone exposes media via Android's MTP server; the PC (Explorer, macOS Android File Transfer/OpenMTP) pulls. The app has **no visibility** into what the PC received. No resume, Explorer copy errors on long jobs are common **[UNVERIFIED anecdotal]**.
- Can still be used with a **manifest-based** protocol (§4.3), where a PC-side tool (or the user, manually) verifies. Without a companion, the best KeepClear can do: write a manifest + instructions and mark the backup "unverified", which **must not unlock deletion** (or only with a loud second confirmation; product decision).

### 4.2 ADB + desktop companion
- `adb pull` copies arbitrary files/dirs from device; `adb forward tcp:H tcp:D` forwards host→device ports (https://developer.android.com/tools/adb). `adb reverse` (device→host) exists in the adb CLI but is not in that page **[UNVERIFIED in official docs]**.
- Requires Developer options + USB debugging + RSA key accept. For non-technical users this is a real barrier and a security footgun (leaving debugging on). Large pulls have reported stalls (https://eis.utoronto.ca/~pkern/stuff/adb-pull/adb-pull-large-file.txt).
- Better design than bare `adb pull`: **phone is the orchestrator**, companion is a dumb verified sink:
  1. Companion (`keepclear-receiver`, Kotlin/JVM single jar or Python) runs on PC, listens on `localhost:PORT`; `adb reverse tcp:PORT tcp:PORT`.
  2. App connects to `127.0.0.1:PORT` (needs `INTERNET` permission — localhost only; document it), streams files with header {relpath, size, sha256}.
  3. Companion writes `relpath.part`, fsyncs, re-reads from disk, hashes, renames, replies `OK sha256=…` per file. App records the receipt in its local DB.
  - Same protocol works over Wi-Fi (companion on LAN) — so one companion covers USB and Wi-Fi; on this owner's phone Wi-Fi is faster anyway.
- Companion language: Kotlin/JVM shares code (manifest model, hash) with the app; needs a JRE or jpackage (~40 MB). Python = tiny, ubiquitous on Linux/macOS, not on Windows. **[Product call]**; recommend Kotlin + jpackage for Windows, since Windows is the likely PC.

### 4.3 Manifest + receipt file protocol (works for MTP and ADB, no socket)
1. App writes `KeepClear/manifests/<jobId>.json` (relpath, size, mtime, sha256) to shared storage before the user copies.
2. User copies `DCIM/Camera` + manifest to PC (MTP or adb pull).
3. PC tool verifies every file against the manifest and writes `<jobId>.receipt.json` (per-file status + receipt hash over the sorted manifest).
4. User/adb pushes receipt back into a folder the app can read (e.g., an app-SAF-granted folder; `adb push` into `Android/data/<pkg>/` from shell is **[UNVERIFIED on Android 13+]**).
5. App validates the receipt's manifest hash = its own, then unlocks deletion.
- Weakness: the receipt is self-asserted by the PC tool; integrity relies on trusting the companion. Acceptable (user owns both ends). Add HMAC with a per-job key shown as QR/6-digit code if we want anti-spoof (overkill for MVP).

### 4.4 Companion vs none
| Option | Verify | UX | Effort |
|--------|--------|----|--------|
| None (MTP only) | No | Familiar | 0 |
| Manifest + PC script | Yes (deferred) | Multi-step, error-prone | Low |
| Companion socket (USB via adb reverse, or LAN) | Yes, per-file, live | Good after setup | Medium |
**Recommendation:** Phase 2. In MVP, SMB covers "back up to my PC" with zero PC-side install (Windows has built-in SMB sharing) and is faster on this phone.

---

## 5. Target G/H — Google Drive

### 5.1 Drive via SAF (DocumentsProvider) — avoid
- Drive implements a `DocumentsProvider` (https://developer.android.com/guide/topics/providers/document-provider), but **Drive does not offer `ACTION_OPEN_DOCUMENT_TREE` folder selection**; cloud providers generally don't (Google issue 135636079 cited in https://github.com/chenxiaolong/BCR/pull/31; user report https://community.cryptomator.org/t/cryptomator-not-usable-without-webdav-or-not-listed-cloud-storages/11933/16). Without a tree grant, each file needs its own `ACTION_CREATE_DOCUMENT` picker → impossible for 10,472 files. Re-check on current Drive **[UNVERIFIED for 2026 Drive app]**.
- Even per-file: the Drive provider writes to a local cache and uploads asynchronously **[UNVERIFIED — no official doc found; consistent with Drive's "waiting to upload" UI]**. Reading the document back via SAF likely returns the **local cached copy**, which proves nothing about the cloud. **This is the verification problem the owner flagged — confirmed as a design blocker.**

### 5.2 Drive REST API v3 — possible later
- File resource exposes server-computed `md5Checksum`, `sha1Checksum`, `sha256Checksum` ("only populated for files with content stored in Google Drive") (https://developers.google.com/workspace/drive/v3/reference/files). So: resumable upload → `files.get?fields=size,sha256Checksum` → compare. No download needed. Strong verification.
- Scope: `drive.file` is **non-sensitive**, "recommended", basic verification only; `drive`/`drive.readonly` are **restricted** (security assessment) (https://developers.google.com/workspace/drive/api/guides/api-specific-auth). `drive.file` suffices: we only need files we created.
- Blockers: OAuth on Android. Google recommends its Sign-In SDKs (Play Services, proprietary) and has deprecated loopback redirects for Android clients (https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration). AppAuth (Apache-2.0) + custom scheme may or may not be allowed for new Android OAuth clients **[UNVERIFIED]**. Play Services dependency conflicts with F-Droid and the "no proprietary" stance. Needs a Google Cloud project + OAuth consent verification.
- Verdict: Phase 3, Play-flavor only, if users demand it. Network is allowed (user-chosen target), so principle is OK; the problem is libraries and OAuth.

---

## 6. Target I/F — Photo services

### Google Photos — not verifiable
- **Owner said "No API to know what's in Google Photos" — confirmed and now stricter.** Since 2025-03-31 the Library API removed `photoslibrary.readonly`, `photoslibrary.sharing`, `photoslibrary`; remaining scopes are `appendonly`, `readonly.appcreateddata`, `edit.appcreateddata`; "You can now only list, search, and retrieve albums and media items that were created by your app" (https://developers.google.com/photos/support/updates). Picker API is user-selection only.
- Backup status is visible only in the Photos app UI ("In Queue", cloud-check icons) (https://support.google.com/photos/answer/6174018, https://support.google.com/photos/answer/6193313).
- Even uploading ourselves via `appendonly` gives a media item, but Photos may **re-encode** ("Storage saver") so byte-hash comparison is meaningless **[UNVERIFIED for Original quality]**.
- Product implication: KeepClear can say "If you use Google Photos, open Photos → check backup is complete, then use its *Free up space*" — i.e., hand off; never claim we verified it.

### Immich (self-hosted)
- `POST /assets/bulk-upload-check`: "Determine which assets have already been uploaded to the server based on their SHA1 checksums"; `x-immich-checksum` header on `POST /assets` = SHA-1 for duplicate detection (OpenAPI spec, https://github.com/immich-app/immich/blob/main/open-api/immich-openapi-specs.json).
- So we can ask the server "do you have a file with SHA-1 X?" → server-side proof of byte-identical asset without download. Good verification (SHA-1 collision risk irrelevant for accidental corruption). Requires computing SHA-1 alongside SHA-256 (cheap). Immich server AGPL; we'd only call its HTTP API (no license impact).
- Bonus: many users already run the Immich app for backup; KeepClear could verify "already in Immich" **without uploading** → then free space. High value for the self-hosting niche. Phase 3.

### Ente
- End-to-end encrypted; client-side encryption before upload; duplicate detection is "hash-based" (https://help.ente.io/photos/features/backup-and-sync/duplicate-detection). Server can't hash plaintext; integrating means re-implementing Ente's crypto client. **Avoid.**

---

## 7. Hashing: algorithm and libraries

| Algo | JVM/Android source | License | Speed on phone | Interop |
|------|--------------------|---------|----------------|---------|
| **SHA-256** | `java.security.MessageDigest` (platform, Conscrypt) | platform | High; ARMv8 SHA2 instructions [ESTIMATE] | `sha256sum`, PowerShell `Get-FileHash`, Drive `sha256Checksum`, Nextcloud `X-Hash-SHA256` |
| SHA-1 | platform | platform | High | Immich |
| BLAKE3 | Apache Commons Codec `Blake3` (since 1.16), pure Java (https://commons.apache.org/proper/commons-codec/apidocs/org/apache/commons/codec/digest/Blake3.html) | Apache-2.0 | Pure Java, no SIMD → likely slower than HW SHA-256 on ARM [ESTIMATE] | `b3sum` only |
| XXH3/XXH128 | OpenHFT Zero-Allocation-Hashing (https://github.com/OpenHFT/Zero-Allocation-Hashing) | Apache-2.0 | Very fast, non-crypto; uses `sun.misc.Unsafe` — Android compatibility **[UNVERIFIED]** | `xxhsum` |

**Decision: SHA-256 everywhere** (plus SHA-1 only when talking to Immich). The link (USB 2.0 / Wi-Fi) is 30-120 MB/s; SHA-256 with HW acceleration is ≥5x that, so a faster non-crypto hash buys nothing and loses interop with PC tools and server-side checksums. Compute source hash **once, streaming, during the copy** (tee input stream into digest) to avoid an extra read of 75 GB.

---

## 8. Verification design (target-agnostic)

### 8.1 Levels
| Level | What | When it's enough |
|-------|------|-----------------|
| L0 | File count + total bytes | Never alone for deletion; progress/UX only |
| L1 | Per-file size match (stat on target) | Catches truncation; not bit-rot or wrong content |
| L2 | Per-file SHA-256: source hash computed during copy; target hash from **server-side digest** (Nextcloud `X-Hash`, Drive `sha256Checksum`, Immich SHA-1 check, SSH `sha256sum`, companion receipt) | **Required for deletion** |
| L2' | Same, target hash via **full read-back** (OTG drive, SMB, generic WebDAV) | **Required for deletion** where no server digest |
| L3 | L2' after cache eviction (replug drive / new session) | Optional "paranoid" toggle |

Product rule: **delete unlocks only at L2/L2' for 100% of files in the batch; any mismatch excludes that file (not the batch).** Owner's principle says "verify file count + size" — **that's L0/L1, not sufficient**: a corrupted copy has the right size. Recommend upgrading the spec to hash-verify.

### 8.2 Copy protocol (per file)
1. Snapshot source: `size`, `mtime`, (MediaStore `_id`, `GENERATION_MODIFIED` on API 30+ **[UNVERIFIED column semantics]**).
2. Create `name.ext.keepclear-part` on target (temp name).
3. Stream source → target, teeing into SHA-256. fsync / SMB flush.
4. Rename temp → final. If final exists: compare hash; equal → skip (idempotent); different → `name (1).ext` (never overwrite).
5. Verify target (L2/L2'). Record in local DB: `{relpath, size, mtime, sha256, targetId, verifiedAt, method}`.
6. Write/update manifest on target (§8.4) in batches, itself via temp+rename.

Resume: on restart, any `.keepclear-part` is deleted or re-hashed; DB rows without `verifiedAt` are redone. SMB/SFTP/WebDAV support offset writes, so partial resume of huge videos is possible (sshj has SFTP resume; SMB write at offset), but **simpler to restart the file** in MVP — worst case one 4 GB video retransmit.

Atomicity: rename is atomic on POSIX/SMB/NTFS within a directory; on FAT/exFAT via SAF `renameDocument` it's a directory-entry update, not crash-atomic **[UNVERIFIED]** → hence verify after rename, not before.

### 8.3 Delete-time re-check (TOCTOU)
Right before moving a source file to the in-app trash:
- Re-stat source: `size` and `mtime` unchanged since snapshot → OK. Changed → re-queue for backup, don't delete.
- For extra safety on files modified within the last N days (edited photos), re-hash source and compare to recorded hash (cheap for photos, a few seconds for videos).
- Target-side presence check (L1 stat) still true at delete time (drive still the same volume UUID / server reachable). If target unreachable at delete time → allow delete only if verified ≤ X hours ago **[product call]**; default: require target reachable.
- Deleted files go to the 30-day trash first (product principle), which is a second safety net — note the trash still occupies space until purged, so the "space freed" number must say so.

### 8.4 Manifest format
JSON Lines (streamable, append-friendly, one record per line) + a header; CSV alternative for Excel users.
```
{"type":"header","format":"keepclear-manifest/1","jobId":"…","device":"OnePlus 10T","createdAt":"2026-10-31T12:00:00Z","hash":"sha256","normalization":"NFC"}
{"path":"DCIM/Camera/IMG_20240101_101010.jpg","size":3481123,"mtime":"2024-01-01T10:10:10Z","sha256":"…","verified":"readback"}
{"type":"footer","files":10472,"bytes":80745123456,"manifestSha256":"…"}
```
- `path` = relative to source root, `/` separators, UTF-8, NFC. Also emit `KeepClear-SHA256SUMS.txt` in `sha256sum` format so a user can run `sha256sum -c` on Linux/macOS (or PowerShell loop) **independent of KeepClear** — great for trust and for an open-source audience.
- Store manifest at target root `KeepClear-Backup/<device>/manifests/` and a copy in app DB.

### 8.5 Unicode / filename rules
- Hebrew is mostly precomposed-free (base letters have no NFC/NFD difference) but **niqqud/combining marks and Latin accents** do. macOS favors NFD, Windows/most others NFC; mixed normalization breaks SMB access from Macs (https://eclecticlight.co/2021/05/08/explainer-unicode-normalization-and-apfs/, https://doc.openafs.org/ReleaseNotesWindows/chap_3.html). Rule: **write names as-is (byte-preserving), but compare in NFC** (`java.text.Normalizer`), and record NFC in the manifest.
- Windows/NTFS/FAT/exFAT/SMB-to-Windows forbidden: `< > : " / \ | ? *`, control chars 1-31, reserved names `CON PRN AUX NUL COM0-9 LPT0-9` (also with extensions, incl. superscript ¹²³), trailing space or period (https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file). Android (ext4/f2fs) allows most of these; WhatsApp/Telegram-saved names can contain `:` or `?`. Map deterministically (e.g., `:` → `꞉` U+A789 or `_`) and record original name in the manifest.
- Case-insensitive targets (FAT, exFAT, NTFS default, SMB-to-Windows): `IMG_1.JPG` vs `img_1.jpg` collide (same MS table). Detect collisions before copy.
- Length: 255 UTF-16 units per component on NTFS/exFAT/FAT32; **Hebrew in UTF-8 is 2 bytes/char, so ext4's 255-byte limit is hit at ~127 Hebrew chars**, but on the target the 255-char rule applies (MS table above). Windows MAX_PATH 260 unless long paths enabled (same naming doc) → keep target prefix short (`KeepClear-Backup/<device>/DCIM/Camera/…`), warn if total >240.
- FAT32 mtime is 2-second granularity and local-time-based → never compare mtime source vs target for verification; use hash. Set target mtime only for user convenience.

---

## 9. Android execution constraints (long transfers)

| Mechanism | Fit | Limits |
|-----------|-----|--------|
| **User-Initiated Data Transfer job** (API 34+) | Best for network targets (SMB/WebDAV/SFTP/companion) | `setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS` permission, must be scheduled while app visible, must `setNotification()`, not subject to standby quotas, may run "for an extended period"; system may stop it (thermal, constraints) → must persist & resume; user stop from Task Manager kills process without `onStopJob` (https://developer.android.com/develop/background-work/background-tasks/uidt). Doc frames it around network transfers; use without a network constraint for OTG is **[UNVERIFIED]** |
| `dataSync` foreground service | Fallback for API <34 and possibly OTG copies; type lists "Backup-and-restore", "Local file processing" (https://developer.android.com/develop/background-work/services/fgs/service-types) | **Android 15: 6 h per 24 h** total, then `onTimeout` and must `stopSelf()` (https://developer.android.com/develop/background-work/services/fgs/timeout). 75 GB at ≥30 MB/s with verify fits in ~1.5 h, OK |
| `connectedDevice` FGS | USB device via `UsbManager` permission | Not needed for mass-storage via SAF |
| WorkManager | Older-API fallback for UIDT per docs | — |

- Battery/thermal: 1-1.5 h of sustained Wi-Fi + flash I/O + hashing. Require charger connected (or warn below 50%), keep screen-off allowed, take a partial wake lock only via the job/FGS. Use `PowerManager.getThermalHeadroom()`/`addThermalStatusListener` to throttle at `THERMAL_STATUS_SEVERE` **[API exists since 29/30; behavior UNVERIFIED on OnePlus]**. Wi-Fi: hold a `WifiManager.WifiLock` (high-perf / low-latency) during transfer **[UNVERIFIED necessity on Android 15]**.
- Batch by year (2019…2026) so each batch completes, verifies and can free space independently: partial progress = partial value, and failures are contained.

---

## 10. Failure-mode matrix

| Failure | A OTG | B SMB | C Companion | D WebDAV | Mitigation |
|---------|-------|-------|-------------|----------|------------|
| Link drops mid-file | unplug | Wi-Fi roam/sleep | cable/adb drop | Wi-Fi | temp name, resume from DB, restart file |
| Target full | yes | yes | yes | quota (507) | pre-flight free space; `OC-Total-Length` on Nextcloud |
| >4 GiB file | FAT32 | no | no | server limits/proxy body size | pre-flight list; exFAT advice |
| Silent corruption | flaky flash/fake-capacity sticks | rare | rare | rare | hash read-back; L3 replug |
| Fake-capacity USB stick (writes wrap) | **yes** | — | — | — | read-back after full write catches it; L3 recommended for unknown sticks [general knowledge] |
| Name invalid on target | yes | yes (Windows) | Windows | some | sanitize + manifest original |
| Source changed after verify | all | | | | delete-time re-check (§8.3) |
| Process killed | all | | | | persisted DB state, idempotent steps |
| Credentials leaked | — | yes | — | yes | Keystore encryption; no logs |

---

## 11. Recommended order

1. **MVP-1: External drive / SD via SAF** — no network, no INTERNET permission, no accounts, works offline, strongest verification story (read-back from the medium, optional replug). Ship with exFAT/FAT32 detection, 4 GiB guard, temp+rename, SHA-256 manifest + `SHA256SUMS`.
2. **MVP-2: SMB to a home PC/NAS (smbj + BouncyCastle)** — no PC install on Windows/macOS/most NAS; fastest path on the owner's USB-2.0 phone; read-back verify. Adds `INTERNET` permission — justify in privacy text ("only to the address you enter").
3. Phase 2: WebDAV via OkHttp with Nextcloud `X-Hash` fast-verify; desktop companion (LAN + `adb reverse`) for users without shares; SFTP (sshj).
4. Phase 3: Immich "already backed up?" check (verify without upload); Drive REST with `drive.file` + `sha256Checksum` (Play flavor only).
5. Never as verified targets: Google Photos, Drive-via-SAF, plain MTP, Syncthing.

---

## Implications for KeepClear

### Adopt
- SHA-256 per file computed during copy; deletion gated on per-file hash match (server digest or read-back). Upgrade owner's "count + size" rule to hash.
- Temp name + fsync + rename; idempotent, resumable jobs persisted in a local DB; batch by year.
- JSON Lines manifest + `sha256sum`-compatible `SHA256SUMS` file on the target, NFC paths, original names recorded.
- Delete-time TOCTOU re-check (size+mtime, optional re-hash) and target presence check; then 30-day trash.
- MVP targets: OTG/SD via SAF, then SMB via smbj (Apache-2.0). Both GPL-3.0-compatible.
- UIDT job on API 34+, `dataSync` FGS fallback; respect the Android 15 6-h dataSync budget; require/encourage charger; thermal throttling.
- Pre-flight: free space, FAT32 >4 GiB files, filename sanitization, case-collision and path-length checks.
- Honest Google Photos handoff text: "We can't see Google Photos; check it there."

### Avoid
- Drive via SAF (no tree grant; async upload makes read-back meaningless).
- Google Photos as a "verified" target (API removed `photoslibrary.readonly` 2025-03-31).
- Syncthing as a MOVE target (delete propagates).
- Ente integration (E2E crypto).
- Trusting `OC-Checksum`/`oc:checksums` on Nextcloud as proof (client-supplied, unvalidated on PUT); use `X-Hash`.
- sardine-android (stale since 2024-02); use OkHttp directly.
- Non-crypto/fast hashes for interop reasons; link speed is the bottleneck.
- Assuming USB is faster than Wi-Fi: on the owner's 10T it's USB 2.0.

### Open questions
- Does UIDT accept jobs without a network constraint for OTG copies on Android 15? Test on device.
- Does the OnePlus 10T mount exFAT OTG drives out of the box? Does `MANAGE_EXTERNAL_STORAGE` grant direct `File` write on `/storage/XXXX-XXXX`? Test.
- Does smbj (0.15.0) work on Android 15 with NTLMv2 against Windows 11 24H2 (SMB signing required by default) without full BouncyCastle? Test.
- Drive provider: does any 2026 Drive version support `OPEN_DOCUMENT_TREE`? Probably not; low priority.
- Google OAuth for Android without Play Services (AppAuth + custom scheme) still permitted for new clients?
- Should an "unverified" backup (MTP manual copy) ever unlock deletion? Recommend no, or only with typed confirmation.
- Measure real throughput on the owner's phone: SMB over Wi-Fi 6 vs OTG on USB 2.0, 1 GB video and 1,000 small JPEGs.
- Companion language (Kotlin+jpackage vs Python) — only if Phase 2 companion is pursued.
