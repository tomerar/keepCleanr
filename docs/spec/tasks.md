# KeepClear: Implementation plan

Status: **Approved by owner 2026-10-31** · Date: 2026-10-31
Milestones are small, ordered, and each ends in something testable. Requirement IDs refer to [requirements.md](requirements.md); edge-case IDs to [edge-cases.md](edge-cases.md).

Rules for every milestone:
- One PR per milestone (or less). CI green: build both flavors, unit tests, architecture tests, lint, string parity.
- **Definition of done (DoD)** includes the listed tests passing and the spec updated if reality differed.
- Destructive code lands only with its tests in the same PR.
- **Device checks (D)** are run by the owner on the OnePlus 10T with a written checklist in the PR.

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
- `core:safety`: `ProtectedPaths`, `SafetyGuard`, `ActionPlan`, `ActionExecutor` (stat re-check, per-op results), dry-run `RecordingGateway`.
- Konsist rule: destructive APIs only in allowed modules.
- **DoD / tests:** SAFETY-1/3/6/8/9 tests; property test: 10k random plans over the fixture tree never touch PROTECTED paths (E-DEL-2); unconfirmed plan rejected; changed-file skipped (E-DEL-1); last-copy-of-duplicate rejected; canonical, case-insensitive, NFC PROTECTED matching incl. WhatsApp Business and `/storage/emulated/999/` (SEC-6, MSG-2, E-SEC-9); symlink/identity swap skipped (SEC-4, E-SEC-7); ENOSPC fault injection never crashes (LOW-4).

---

## Phase 1: See (read-only app, safe to give to the owner)

### M3: Scanner + Room index
- `engine:scan` over gateway + MediaStore source port; `core:database` (ScanItem, HashCache); `FileGateway` (foss) and `MediaGateway` read paths; incremental generations.
- Permissions: READ_MEDIA_* with explainer, partial-access detection (ONB-2/4), all-files on foss (ONB-5).
- Room schema export + migration test harness from day one (MIG-1).
- **DoD / tests:** SCAN-3/5/6 unit tests on fixtures (cancel leaves no state; Hebrew/NFD names; symlink loop; 100k dir streams with bounded memory); instrumented MediaStore seeding test on API 30/34/36; **D:** full scan of owner's phone ≤ 60 s, totals within 1% of Settings › Storage; light/minimal modes (LOW-1/2, E-LOW-1/4).

### M4: Classifier + rules + explanations
- `core:rules` JSON + loader, `engine:classify`, explanation keys and he/en strings, risk chips. SAFE seeds from SD Maid SE filters (attributed).
- **DoD / tests:** table tests per rule (positive + negative fixtures), including E-CLS-1…7; string-completeness for every rule's why/if-deleted; owner-fixture snapshot test: category totals match expected numbers.

### M5: Home + Review browsing (read-only)
- Light/dark themes (UI-1) and the screenshot matrix (UI-3).
- Home cards (SAFE/REVIEW/MOVE totals, breakdown), Review hub, bucket grid with month grouping and preview, item "Why" sheets. No delete buttons yet (disabled with "coming soon").
- **DoD / tests:** Compose UI tests; screenshot tests en/he (RTL) incl. mixed-direction filenames; 2k-item grid scroll benchmark; **D:** owner confirms buckets match his ADB measurements (2,324 screenshots, 526 AI images, 81 big videos/14.85 GB, WhatsApp breakdown, Databases not listed anywhere).

**Checkpoint A (owner):** app shows the truth about the phone, deletes nothing.

---

## Phase 2: Clean with a safety net

### M6: Trash (both backends) + restore
- `engine:trash`, `TrashEntry`, `FolderTrash` (rename + sidecar + `.nomedia`), `MediaStoreTrash` (chunks ≤ 2,000; `MANAGE_MEDIA` optional), purge worker, recover-from-sidecars, Trash screen, Undo.
- Sidecar format v1 with HMAC and `RestorePathValidator` (SEC-1), write-ahead journal (LOW-3).
- **DoD / tests:** TRASH-1…6, E-DEL-3…15, E-SEC-1…3, E-LOW-3, MIG-2 golden files; kill-point property test (no-loss invariant) for trash/restore/purge; instrumented: createTrashRequest + restore on API 30/34/36, SAF move into tree trash; **D:** trash 50 test files on owner phone, restore, verify OnePlus Gallery behaviour (OPEN: does it show IS_TRASHED items?).

### M7: SAFE cleanup end-to-end
- Junk filters, WhatsApp `.Statuses`, other apps' recycle bins card (OnePlus path verified on device), extracted-zip detection, Sent duplicates via full SHA-256, Android cache trim (`allocateBytes`, measured), foss `ACTION_CLEAR_APP_CACHE`. Confirm sheet → executor → trash → result screen.
- **DoD / tests:** SAFE-1…6/8 tests; extracted-zip fixture tests (full/partial/renamed); duplicate pipeline tests (same-size-different-content, NFD names, 0-byte files); crafted-zip tests (SEC-2, E-SEC-4); **D:** on owner's phone ≈ 8 GB SAFE found, cleaned to trash, restore of a sample works, Empty now frees space (Settings shows it).

### M8: REVIEW actions
- Multi-select delete with editable confirm sheet (REV-3), "Move instead" handoff stub (REV-4), old-phone leftovers detail, WhatsApp Private/Sent-without-original.
- **DoD / tests:** UI tests for select-by-month, remove-from-confirm; SAFETY-10 (nothing pre-selected); **D:** owner reviews screenshots/AI images.

**Checkpoint B (owner):** daily-usable cleaner with trash; MOVE not yet.

---

## Phase 3: Move memories (the core)

### M9: MOVE engine (JVM)
- `backup:target-api`, `engine:move`: selection spec, preflight (space, FAT32, names, collisions, path length, battery), state machine, temp+rename, SHA-256 during copy, read-back verify, manifest + SHA256SUMS, resume.
- **DoD / tests:** E-MOVE-1…13, 18…22 against `FakeTarget` with faults (disconnect at random byte, ENOSPC, bit-flip on read-back, case-insensitive FS, FAT32 limit); kill-point property test (no-loss invariant); manifest round-trip + `sha256sum -c` compatibility test (generate, then verify with a reference implementation); hostile manifest/marker tests (SEC-3, E-SEC-5/6); manifest records original paths (MOVE-18).

### M10: SAF target + MOVE UI + foreground service
- `backup:target-saf` (OTG/SD, read-back allowlist, target marker file), `dataSync` FGS with notification, pause/resume/cancel, onTimeout handling, receipt screen, delete-originals plan via guard → trash (with `verifiedCopyRef`), Google Photos honesty note, cloud-provider "copy only" mode.
- **DoD / tests:** MOVE-1…2, 5…14, 16, 18; ONB-10 + E-NOTIF-1/2 (notifications denied); MIG-4/5 + E-MIG-1/2 (update mid-job); instrumented test with a test DocumentsProvider simulating eject; **D (owner):** move 1 year of camera media (~5–10 GB) to a USB-C drive, unplug mid-copy and resume, verify `sha256sum -c` on PC, free originals, restore 10 files from trash.

**Checkpoint C = MVP release candidate** (GitHub Release, foss APK).

---

## Phase 4: v1 (public)

| Milestone | Scope | Key tests |
|---|---|---|
| M11 | `play` flavor storage backend: SAF trees for WhatsApp/old folders, MediaStore-only flows; Play internal-track submission to test READ_MEDIA_* declaration | Contract suite on `SafGateway`; merged-manifest check; Play pre-launch report |
| M12 | Apps: usage access, sizes, unused ~6 months, uninstall via system | Usage bucket math tests (month precision); D on owner phone (11 apps) |
| M13 | SMB target (smbj), INTERNET confined to module, UIDT on API 34+, `ACCESS_LOCAL_NETWORK` prep | SMB against Samba in CI (docker) + Windows 11 D test; lint rule for network use |
| M14 | Shizuku tier: Advanced access sheet, Android/data listing, per-app cache clear | Fake Shizuku binder tests; D with Shizuku on owner phone |
| M15 | Swipe review, exclusions UI, reclassify, re-verify existing backup (MOVE-15), **restore from backup (MOVE-17)**, reminders | UI + engine tests; MOVE→restore round-trip byte-identical |
| M15a | Messengers: cloned-app roots (MSG-3), Telegram (MSG-4), large screens (UI-2) | Fixture tests; device checks on OnePlus + Xiaomi (user 999) |
| M16 | Release engineering: reproducible build, F-Droid/IzzyOnDroid metadata, developer-verification registration, accessibility pass, Hebrew native review | Reproducibility diff in CI; a11y scanner |

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
Similar photos (dHash + BK-tree), WebDAV/Nextcloud `X-Hash`, Immich checksum target, PC companion, root tier, video squeeze.

---

## Proposed first milestone for approval

**M0 + M1 + M2 as "Foundations"** (no UI beyond a placeholder, no deletion possible): project skeleton with both flavors, CI guard rails, storage abstraction with fault-injecting fake, owner-shaped fixtures, and the safety core with property tests. It proves the safety architecture before any feature exists, and everything after builds on it.
