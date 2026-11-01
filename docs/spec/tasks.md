# KeepClear: Implementation plan

Status: **Approved by owner 2026-10-31**; v1 order updated with owner decisions of 2026-11-01 · Date: 2026-11-01
Milestones are small, ordered, and each ends in something testable. Requirement IDs refer to [requirements.md](requirements.md); edge-case IDs to [edge-cases.md](edge-cases.md).

Rules for every milestone:
- One PR per milestone (or less). CI green: build both flavors, unit tests, architecture tests, lint, string parity.
- **Definition of done (DoD)** includes the listed tests passing and the spec updated if reality differed.
- Destructive code lands only with its tests in the same PR.
- **Device checks (D)** are run by the owner on the OnePlus 10T with a written checklist in the PR. They need only the phone (P-1): any step that uses a computer (`adb`, `sha256sum` on a PC) is marked *optional, developer only*, and has an in-app equivalent where one exists ("Check backup", MOVE-15).
- **Test builds:** every push to `main` publishes a signed `fossDebug` test APK as a GitHub Actions artifact (no GitHub Release); see `docs/progress.md` for how to install it from the phone.

---

## Phase 0: Foundations (no user-visible deletion)

### M0: Project skeleton and guard rails
Status: **implemented** (PR for M0). Spec adjustments made while implementing:
- Modules are created when a milestone needs them, not as empty shells: M0 has `app`, `core:model`, `core:common`, `core:designsystem`, `core:i18n`, `testing:architecture` (+ `build-logic`). The full layout stays in design.md §2.
- Static analysis: Android Lint (warnings as errors) + ktlint via Spotless. detekt is deferred (not needed for M0; revisit if Lint/ktlint miss something).
- Robolectric/Compose UI tests use JUnit 4 (Robolectric runner); pure-JVM modules use JUnit 5 + Kotest.
- The exported-component check (SEC-5) allows two debug-only tooling activities (Compose preview, ui-test-manifest) in **debug** variants only; release variants are strict.
- Policy checks run per variant: `check<Variant>ManifestPolicy` (INTERNET, restricted permissions in `play`, accessibility service, exported components, PRIVACY.md sync) and `check<Variant>DependencyAllowlist`.
- In-app language picker for API 30–32 is deferred to Settings (M-later); M0 uses the system locale and Android 13+ per-app language via `locales_config.xml`.

- Gradle multi-module skeleton per [design.md §2](design.md) (empty modules for later ones), version catalog, `build-logic` convention plugins, flavors `foss`/`play`, minSdk 30/target 36/compile 37.
- Compose app with Home placeholder in en + he; per-app language; RTL toggle in debug.
- CI (GitHub Actions): assemble `fossDebug` + `playDebug`, unit tests, detekt/ktlint, Konsist architecture tests, **merged-manifest check** (no `MANAGE_EXTERNAL_STORAGE`/`QUERY_ALL_PACKAGES` in `play`; no `INTERNET` anywhere), **string parity en↔he**, dependency allowlist.
- SPDX headers, `THIRD_PARTY.md`.
- **DoD / tests:** CI green; a deliberately added `INTERNET` permission in a test branch fails CI; a missing he string fails CI; Konsist rule "engines must not import android.*" passes; app launches on API 30 and 36 emulators in both languages, light and dark (screenshot test, UI-3); merged manifest exports only the launcher (SEC-5); Gradle dependency verification on (SEC-8); PRIVACY.md permission-sync check (PRIV-6); label-sync workflow for `.github/labels.yml` added (community files themselves already exist, see §"Community").

### M1: Storage abstraction + fake file system
Status: **implemented**. Notes: the fake and the contract suite live in `core:storage-api` test fixtures; the fake supports virtual (seeded) content so owner-scale trees cost no memory, and a case-insensitive mode matching Android's FUSE storage. `OwnerDevice` tags every generated path (protected databases included) as the oracle for later classification tests.

- `core:storage-api` (`KPath`, `StorageGateway`, `Stat`, capabilities), `FakeStorageGateway` with fault injection (ENOSPC, disconnect, slow, EACCES, file-changes-during-read, kill points).
- `testing:fixtures`: owner-shaped synthetic tree generator (Camera 10k files 2019–2026, WhatsApp layout incl. Databases/Backups/Sent/Private/.Statuses, WhatsApp Business (`com.whatsapp.w4b`), a clone root `/storage/emulated/999/`, Telegram shared-storage folders, MIUI, legacy WhatsApp, zips + extracted folders, screenshots, ChatGPT images, Hebrew/emoji/NFD names, 100k-entry dir, symlink loop).
- **DoD / tests:** fake passes a gateway contract test suite (the same suite will later run against `FileGateway` and `SafGateway` instrumented); fixture generator deterministic by seed.

### M2: Safety core (guard + executor + dry-run)
Status: **implemented**. Spec details settled while implementing:
- `PlannedOp` carries `displayPath` (used for PROTECTED matching even for SAF/MediaStore refs) and `isDirectory`.
- A plan is valid only in the session it was confirmed in (`sessionId`), which implements SAFETY-1 "current session" and MIG-5.
- Directory ops: the executor walks the subtree and refuses if anything inside is PROTECTED **or a symlink** (SkipReason `CONTAINS_PROTECTED`).
- After a `PermissionDenied` or `Disconnected` failure the remaining ops are not attempted (`STOPPED`, E-PERM-2).
- Strict mode re-hashes `MOVE_DELETE_ORIGINAL` ops by default (Q13).
- The executor never deletes itself; it calls an `ActionHandler` (the trash, M6). Konsist allows destructive file APIs only in `core/storage-*` and `engine/trash`.

- `core:safety`: `ProtectedPaths`, `SafetyGuard`, `ActionPlan`, `ActionExecutor` (stat re-check, per-op results), dry-run `RecordingGateway`.
- Konsist rule: destructive APIs only in allowed modules.
- **DoD / tests:** SAFETY-1/3/6/8/9 tests; property test: 10k random plans over the fixture tree never touch PROTECTED paths (E-DEL-2); unconfirmed plan rejected; changed-file skipped (E-DEL-1); last-copy-of-duplicate rejected; canonical, case-insensitive, NFC PROTECTED matching incl. WhatsApp Business and `/storage/emulated/999/` (SEC-6, MSG-2, E-SEC-9); symlink/identity swap skipped (SEC-4, E-SEC-7); ENOSPC fault injection never crashes (LOW-4).

---

## Phase 1: See (read-only app, safe to give to the owner)

### M3: Scanner + Room index
Status: **implemented**. Spec adjustments made while implementing:
- `FileGateway` is pure JVM (`core:storage-file`, java.nio) and passes the M1 contract suite on a real file system. Rename uses `ATOMIC_MOVE` only, so a cross-volume move fails instead of silently copying.
- **Dependency injection:** a small manual graph in `KeepClearApplication` instead of Hilt. Hilt is deferred until the graph needs it; Room uses KSP already, so adding Hilt later is mechanical.
- The MediaStore seeding test runs on the CI emulators at API 30 and 36 (not 34) to keep CI time reasonable; API 34-specific partial access is covered by the Home UI tests.
- Scans run while the app is open (no background scanning). Without All files access the scan is MediaStore-only, and Home says so.
- The device check (full scan of the owner's phone ≤ 60 s, totals within 1% of Settings) is waiting for the owner (docs/progress.md).

- `engine:scan` over gateway + MediaStore source port; `core:database` (ScanItem, HashCache); `FileGateway` (foss) and `MediaGateway` read paths; incremental generations.
- Permissions: READ_MEDIA_* with explainer, partial-access detection (ONB-2/4), all-files on foss (ONB-5).
- Room schema export + migration test harness from day one (MIG-1).
- **DoD / tests:** SCAN-3/5/6 unit tests on fixtures (cancel leaves no state; Hebrew/NFD names; symlink loop; 100k dir streams with bounded memory); instrumented MediaStore seeding test on API 30/34/36; **D:** full scan of owner's phone ≤ 60 s, totals within 1% of Settings › Storage; light/minimal modes (LOW-1/2, E-LOW-1/4).

### M4: Classifier + rules + explanations
Status: **implemented**. Spec adjustments made while implementing:
- Rules are a versioned Kotlin table (`Rules.all`, `RULES_VERSION`), not JSON yet; JSON comes with community rule files. PROTECTED is decided by `core:safety` before any rule runs.
- Facts from second-pass enrichers (`HAS_TWIN_OUTSIDE_MESSENGER`, archive extraction) are inputs; M7 computes them. Until then WhatsApp `Sent/` copies classify as REVIEW.
- Camera videos > 100 MB stay MOVE (camera rule); the REVIEW "Large videos" bucket is for non-camera videos.
- CLS-5 is applied conservatively: every SAFE item that is a favourite or modified in the last 30 days becomes REVIEW, including regenerable junk and other apps' bins. Open decision B1 in docs/progress.md.

- `core:rules` JSON + loader, `engine:classify`, explanation keys and he/en strings, risk chips. SAFE seeds from SD Maid SE filters (attributed).
- **DoD / tests:** table tests per rule (positive + negative fixtures), including E-CLS-1…7; string-completeness for every rule's why/if-deleted; owner-fixture snapshot test: category totals match expected numbers.

### M5: Home + Review browsing (read-only)
Status: **implemented**. Notes: navigation is a small in-memory stack with system back (no navigation library yet); thumbnails use MediaStore `loadThumbnail` / `ThumbnailUtils` with a memory-only cache (no image library; LOW-1 no disk cache); rule texts are looked up through a typed `RuleText` map. The 2k-item grid has a functional scroll test; frame-time benchmarking is a device check (D5-1).

- Light/dark themes (UI-1) and the screenshot matrix (UI-3).
- Home cards (SAFE/REVIEW/MOVE totals, breakdown), Review hub, bucket grid with month grouping and preview, item "Why" sheets. No delete buttons yet (disabled with "coming soon").
- **DoD / tests:** Compose UI tests; screenshot tests en/he (RTL) incl. mixed-direction filenames; 2k-item grid scroll benchmark; **D:** owner confirms buckets match his ADB measurements (2,324 screenshots, 526 AI images, 81 big videos/14.85 GB, WhatsApp breakdown, Databases not listed anywhere).

**Checkpoint A (owner):** app shows the truth about the phone, deletes nothing.

---

## Phase 2: Clean with a safety net

### M6: Trash (both backends) + restore
Status: **implemented** (folder backend). Spec adjustments made while implementing:
- MVP uses the folder trash for every file it can reach by path; MediaStore trash moves to M11 (ADR 0010, decision B2 in docs/progress.md).
- Daily purge runs in WorkManager without a new confirmation: it only deletes entries the user confirmed into the trash 30+ days earlier, never within 24 h of elapsed time in the same boot (E-DEL-10).
- Sidecars are HMAC-signed with an Android Keystore key; after reinstall they are untrusted and restore to `Download/KeepClear Restored/` (SEC-1).
- `ACCESS_NETWORK_STATE` (added by WorkManager) is removed from the manifest; `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` are documented in PRIVACY.md.
- Destructive confirm buttons enable after 500 ms and the activity filters obscured touches (SEC-7).

- `engine:trash`, `TrashEntry`, `FolderTrash` (rename + sidecar + `.nomedia`), `MediaStoreTrash` (chunks ≤ 2,000; `MANAGE_MEDIA` optional), purge worker, recover-from-sidecars, Trash screen, Undo.
- Sidecar format v1 with HMAC and `RestorePathValidator` (SEC-1), write-ahead journal (LOW-3).
- **DoD / tests:** TRASH-1…6, E-DEL-3…15, E-SEC-1…3, E-LOW-3, MIG-2 golden files; kill-point property test (no-loss invariant) for trash/restore/purge; instrumented: createTrashRequest + restore on API 30/34/36, SAF move into tree trash; **D:** trash 50 test files on owner phone, restore, verify OnePlus Gallery behaviour (OPEN: does it show IS_TRASHED items?).

### M7: SAFE cleanup end-to-end
Status: **implemented**. Notes: twins and archive facts come from `engine:enrich` (size → head/tail prehash → full SHA-256; zip central directory only, never extracted). An end-to-end JVM test runs scan → facts → classify → executor → trash on the owner fixture and undoes it byte-exact. Cache trim reports the measured free-space difference only. Device checks D7-1…3 (OnePlus recycle-bin path, ≈ 8 GB SAFE, Empty now frees space) wait for the owner.

- Junk filters, WhatsApp `.Statuses`, other apps' recycle bins card (OnePlus path verified on device), extracted-zip detection, Sent duplicates via full SHA-256, Android cache trim (`allocateBytes`, measured), foss `ACTION_CLEAR_APP_CACHE`. Confirm sheet → executor → trash → result screen.
- **DoD / tests:** SAFE-1…6/8 tests; extracted-zip fixture tests (full/partial/renamed); duplicate pipeline tests (same-size-different-content, NFD names, 0-byte files); crafted-zip tests (SEC-2, E-SEC-4); **D:** on owner's phone ≈ 8 GB SAFE found, cleaned to trash, restore of a sample works, Empty now frees space (Settings shows it).

### M8: REVIEW actions
Status: **implemented**. Notes: groups show nothing pre-selected (SAFETY-10); select-by-month, an editable confirm list (remove items before confirming), delete through the executor into the 30-day trash with Undo. "Move instead" is a disabled stub until M10; MOVE groups cannot be deleted from Review. Old-phone leftovers and WhatsApp Private/Sent-without-original use the M4 rules and texts. Device check D8 (owner reviews screenshots/AI images) waits for the owner.

- Multi-select delete with editable confirm sheet (REV-3), "Move instead" handoff stub (REV-4), old-phone leftovers detail, WhatsApp Private/Sent-without-original.
- **DoD / tests:** UI tests for select-by-month, remove-from-confirm; SAFETY-10 (nothing pre-selected); **D:** owner reviews screenshots/AI images.

**Checkpoint B (owner):** daily-usable cleaner with trash; MOVE not yet.

---

## Phase 3: Move memories (the core)

### M9: MOVE engine (JVM)
Status: **implemented**. Notes: `backup:target-api` (`BackupTarget`, `GatewayBackupTarget`, FAT32/exFAT name and size rules) and `engine:move` (planner, per-file state machine PENDING → COPYING → COPIED → PLACED → VERIFIED, temp + rename, SHA-256 while copying, read-back verify, `SHA256SUMS` + JSONL manifest merged per device folder, resume). The engine never deletes or renames an original: a Konsist rule allows only `target.delete/rename` in `engine:move`. Files already on the drive are reused only when the hash matches and are never deleted, even if a later read-back fails. An existing manifest that can't be read whole is kept, and the job writes `<name>-<jobId>` instead. The job store is in memory here; the Room store and the originals plan through the executor come in M10. Preflight (space with margin, FAT32 4 GiB limit, renamed names, battery) takes the readings as inputs; M10 reads them from Android.

- `backup:target-api`, `engine:move`: selection spec, preflight (space, FAT32, names, collisions, path length, battery), state machine, temp+rename, SHA-256 during copy, read-back verify, manifest + SHA256SUMS, resume.
- **DoD / tests:** E-MOVE-1…13, 18…22 against `FakeTarget` with faults (disconnect at random byte, ENOSPC, bit-flip on read-back, case-insensitive FS, FAT32 limit); kill-point property test (no-loss invariant); manifest round-trip + `sha256sum -c` compatibility test (generate, then verify with a reference implementation); hostile manifest/marker tests (SEC-3, E-SEC-5/6); manifest records original paths (MOVE-18).

### M10: SAF target + MOVE UI + foreground service
Status: **implemented**. Notes:
- `backup:target-saf`: `SafBackupTarget` (never overwrites, even when a provider auto-renames; fsync before read-back) and `SafTargets`. `SafTargets` classifies providers: USB/SD → copy, verify and free; known cloud providers → copy only (MOVE-14); unknown providers → read back plus a warning. It refuses folders on the phone itself and writes a `.keepclear-target` marker with a random 128-bit id. A resume needs the same tree **and** marker (E-MOVE-2, SEC-3); a foreign or damaged marker is refused, never overwritten.
- SAF does not show the file system. Since 2026-11-01 (Q4) KeepClear reads the drive's format from the mount table where Android shows it and lifts the 4 GiB limit only for drives known not to be FAT32 (exFAT, NTFS, ext4, f2fs); otherwise files > 4 GiB are blocked with an explanation, never truncated. Owner check D10-2 confirms it on the exFAT drive.
- Room v3 (`move_job`, `move_file`, auto-migration 2→3). Unknown stored states read back as PENDING (copy and verify again), never as VERIFIED.
- `MoveService` (`dataSync` FGS): pause/stop via non-exported receiver + immutable PendingIntents; `onTimeout` pauses with "time limit". Pausing takes effect after the current file. The notification shows *n of m checked*; ETA is not shown yet.
- MIG-4: `MY_PACKAGE_REPLACED` (non-exported receiver) and app start mark a RUNNING job without a service as "paused, interrupted"; copying resumes only on "Continue". MIG-5 holds by design: confirmed plans live in memory, bound to the session.
- MOVE UI: sources (nothing pre-selected), age presets (12 months default, 6 months, everything; favourites never), pre-flight dialog, progress, receipt (verified count + bytes, skipped by reason, how to get files back, `sha256sum -c`, unencrypted warning), "Free … from the phone" with a delayed confirm → executor (strict re-hash) → 30-day trash with the verified copy recorded; history with "Share receipt"; Google Photos note with a link. MOVE-1's free year/month range is simplified to the three age presets.
- ONB-10: explainer before Android's notification prompt on API 33+; denied → the job still runs, the screen warns, results are read from Room.
- Tests: `RoomMoveJobStoreTest`, `MigrationTest` 2→3, `SafTargetsTest`, `MoveScreenTest` (Robolectric). Instrumented (CI emulator): `SafBackupTargetTest` against a fake USB DocumentsProvider (`testing:usb-provider`, debug builds only, inert until a test enables it) with unplug mid-job, provider auto-rename and marker checks; `MoveJobOnDeviceTest` with the real service: full job, update mid-job (MIG-4) and unplug/resume. The notifications-denied path can't be forced on CI (revoking a permission kills the test process); the job is shown to complete either way.

- `backup:target-saf` (OTG/SD, read-back allowlist, target marker file), `dataSync` FGS with notification, pause/resume/cancel, onTimeout handling, receipt screen, delete-originals plan via guard → trash (with `verifiedCopyRef`), Google Photos honesty note, cloud-provider "copy only" mode.
- **DoD / tests:** MOVE-1…2, 5…14, 16, 18; ONB-10 + E-NOTIF-1/2 (notifications denied); MIG-4/5 + E-MIG-1/2 (update mid-job); instrumented test with a test DocumentsProvider simulating eject; **D (owner):** move 1 year of camera media (~5–10 GB) to a USB-C drive, unplug mid-copy and resume, check the receipt in the app (optional, developer only: `sha256sum -c` on a PC), free originals, restore 10 files from trash.

**Checkpoint C = MVP release candidate** (GitHub Release, foss APK).

---

## Phase 4: v1 (public)

Order updated by the owner on 2026-11-01: phone-only (P-1), simple by default with an Advanced area (P-2), always ask before freeing space (P-3); Shizuku moved to Later; network and cloud targets after the phone-only polish. Each milestone stops for the owner's OK.

| Milestone | Scope | Key tests |
|---|---|---|
| M11 | **Phone-first polish (no new permissions).** Plain-language pass over every default screen and a **Settings › Advanced** area (P-2; e.g. the receipt says "every file was read back and matches", hashes and `SHA256SUMS` move to Details). **Check backup** (MOVE-15). **Clear app caches** as the main path for everyone on Home and in Clean now (SAFE-5/6), with an honest 0-byte result and the guided **Clear one app at a time** list (SAFE-10). `Android/data` app-content cards (SAFE-9). TRASH-7 uninstall note. Drive-format detection (Q4) is already in. | Copy lint: no technical terms on default-path strings (word list in CI); Check backup fault tests (missing / truncated / bit-flipped); SAFE-5 0-byte path; SAFE-9 cards have no delete action; D: read-only on owner phone |
| M12 | `play` flavor storage backend: SAF trees for WhatsApp/old folders, MediaStore-only flows and the MediaStore trash (ADR 0006/0010), Play internal-track submission to test the READ_MEDIA_* declaration | Contract suite on `SafGateway`; merged-manifest check; Play pre-launch report |
| M13 | Apps: usage access, sizes, unused ~6 months, uninstall via system; per-app cache sizes for SAFE-10 | Usage bucket math tests (month precision); D on owner phone (11 apps) |
| M14 | **Network foundation + SMB network drive** (MOVE-3, [ADR 0011](../adr/0011-network-and-cloud-targets.md) once accepted): `backup:net`, INTERNET only in `backup:target-*` modules (policy + Konsist), resumable uploads, `ACCESS_LOCAL_NETWORK` prep, setup from the phone only | SMB against Samba in CI (docker); fault/kill-point tests; D with a home NAS or router share |
| M15 | **Google Drive** (MOVE-4, MOVE-20): 1-day sign-in spike first (AppAuth + PKCE, no Play Services, `drive.file`); resumable upload; free originals only after a server `sha256Checksum` match | Fake Drive server tests (missing / mismatched / client-echoed checksum never frees); D on owner phone |
| M16 | Restore from backup (MOVE-17), swipe review, exclusions UI, reclassify (CLS-7), reminders | UI + engine tests; MOVE→restore round-trip byte-identical |
| M16a | Messengers: cloned-app roots (MSG-3), Telegram (MSG-4), large screens (UI-2) | Fixture tests; device checks on OnePlus + Xiaomi (user 999) |
| M17 | More clouds, only with a server-computed checksum: Nextcloud/WebDAV (`X-Hash`), then Dropbox (`content_hash`) | Same fake-server contract as M15 |
| M18 | Release engineering: reproducible build, F-Droid/IzzyOnDroid metadata, developer-verification registration, accessibility pass, Hebrew native review | Reproducibility diff in CI; a11y scanner |

Moved to **Later** on 2026-11-01: Shizuku tier (ONB-8, SAFE-7, SEC-9; was M14), Accessibility per-app cache cleaner (`foss` only, [ADR 0012](../adr/0012-foss-accessibility-cache-cleaner.md), proposed, not approved), OneDrive (no trustworthy checksum).

## Device test matrix (v1)

Run the device checklist on each before a v1 release. Emulators in CI cover API 30, 33, 34, 35, 36 (and 37 when available).

| Device class | Example | Android / skin | Why it's in the matrix | OEM-specific checks |
|---|---|---|---|---|
| Google Pixel | Pixel 7/8/9 | Android 16 (and 17 beta) stock | Reference behaviour; first to get new restrictions | MediaStore trash in Google Photos/Files, Files by Google bin, UIDT, `ACCESS_LOCAL_NETWORK` |
| Samsung | Galaxy A55 / S24 | One UI 7–8 (Android 15–16) | Largest Android share; own Gallery and My Files bins; Device Care | Gallery/My Files recycle bin paths, `MANAGE_MEDIA`, Secure Folder invisible (document), exFAT OTG, background limits |
| Xiaomi | Redmi Note 13 / Xiaomi 14 | HyperOS 2–3 (Android 15–16) | Aggressive background killing; Dual apps (user 999); MIUI legacy folders | Dual apps storage (MSG-3), MIUI Gallery trash, FGS survival, autostart permission |
| OnePlus | OnePlus 10T (owner), a current OnePlus | OxygenOS 15–16 | Owner's device; ColorOS base; Parallel apps | `.FileManagerRecycler` path, OnePlus Gallery trash, Parallel apps (user 999), FGS kill, USB 2.0 throughput |
| Low-end | Any 4 GB RAM device | Android 11–12 | minSdk 30 floor, low memory | Scan memory (NFR-2), thumbnail grid performance |
| Foldable / tablet | Galaxy Z Fold or Pixel Fold / any tablet | Android 15–16 | Large-screen layouts | UI-2, E-LIFE-4 |

Each device run records: device, OS build, flavor, checklist results and throughput numbers in `docs/device-reports/<device>-<date>.md`.

## Community

Added in the spec phase (no app code): `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `.github/ISSUE_TEMPLATE/*`, `.github/pull_request_template.md`, `.github/labels.yml`, `docs/TRANSLATING.md` (Weblate workflow). Label sync and Weblate hookup are applied once the owner approves (they change GitHub/Weblate settings).

## Later
Similar photos (dHash + BK-tree), Shizuku tier, Accessibility cache cleaner (foss only, if ADR 0012 is approved), OneDrive, Immich checksum target, SFTP, PC companion, root tier, video squeeze.

---

## Proposed first milestone for approval

**M0 + M1 + M2 as "Foundations"** (no UI beyond a placeholder, no deletion possible): project skeleton with both flavors, CI guard rails, storage abstraction with fault-injecting fake, owner-shaped fixtures, and the safety core with property tests. It proves the safety architecture before any feature exists, and everything after builds on it.
