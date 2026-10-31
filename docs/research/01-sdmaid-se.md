# 01: SD Maid SE code study

Source studied: local shallow clone of `d4rken-org/sdmaid-se`, branch `main`, commit `95a673f` (2026-10-22), `VERSION` = `2.2.2-rc0`.
All file links below point at `https://github.com/d4rken-org/sdmaid-se/blob/main/…`. They are **not** commit permalinks, so line numbers can drift. Short form used below: `SE:` = `https://github.com/d4rken-org/sdmaid-se/blob/main/`.

> TL;DR: SD Maid SE is a well-engineered, test-heavy Kotlin/Compose/Hilt cleaner. Its most reusable parts for KeepClear are:
> 1. the **APath + GatewaySwitch** I/O abstraction (NORMAL → ROOT → ADB escalation);
> 2. the **clutter-marker DB + reverse-ownership logic** (CorpseFinder);
> 3. the **sieve/criterium filter DSL** with `pos()/neg()` table tests;
> 4. the **Deduplicator arbiter** (ordered keep-criteria);
> 5. Squeezer's **`FileTransaction`** (atomic replace with a backup and verify step).
>
> SD Maid SE has **no trash or undo**: every delete is permanent. It also has **no "move/backup" concept**. KeepClear's core differentiators are therefore not covered by SE.

---

## 1. Project overview

### 1.1 Modules (from `settings.gradle`)
Source: [SE:settings.gradle](https://github.com/d4rken-org/sdmaid-se/blob/main/settings.gradle), [SE:.claude/rules/architecture.md](https://github.com/d4rken-org/sdmaid-se/blob/main/.claude/rules/architecture.md)

| Module | What it does (verified by reading the code) |
|---|---|
| `app` | Entry point, flavors (`foss`/`gplay`), setup flow (`setup/{root,shizuku,saf,storage,usagestats,automation,notification,inventory}`), dashboard, accessibility-service XML, expendables JSON DBs (`assets/expendables/*.json`) |
| `app-common` | Shared utils (logging, `DispatcherProvider`, `hasApiLevel`, …) |
| `app-common-io` | **APath** types (`LocalPath`, `SAFPath`, `RawPath`), gateways (`LocalGateway`, `SAFGateway`, `GatewaySwitch`), AIDL IPC (`FileOpsConnection`, `PkgOpsConnection`, `ShellOpsConnection`), `RootManager`, `AdbManager`, `PkgOps`, `MediaStoreTool` |
| `app-common-root` | Launching the privileged root host via `su` + `app_process` (librootjava-style), `RootIPC` |
| `app-common-adb` | Launching the privileged host as a **user service** of Shizuku **or Porter** (see §2.3) |
| `app-common-shell` | `FlowShell`: a coroutine/Flow-based shell wrapper |
| `app-common-pkgs` | Package repo (installed, uninstalled-with-data, **archived** apps; multi-user) |
| `app-common-data` | Room DB, **DataArea** model (SDCARD, PUBLIC_DATA, PUBLIC_MEDIA, PRIVATE_DATA, …), **FileForensics/CSI** owner detection, **ClutterRepo** (marker DB) |
| `app-common-exclusion` | User + default exclusions (pkg / path / segment), tagged per tool |
| `app-common-automation` | AccessibilityService engine (`AutomationService`, overlay UI, step/explorer DSL) |
| `app-common-ui` | Compose base components; `ViewModel1/2/4` hierarchy; dialogs (`SdmConfirmDialog`) |
| `app-common-coil` | Coil image loading for files/APKs/thumbnails |
| `app-common-picker` | Path picker UI |
| `app-common-stats` | Reports DB, `SpaceTracker`, `SpaceHistoryRepo`, forecast (storage over time) |
| `app-common-setup` | Setup-module framework (`SetupModule`, `SetupHealer`) |
| `app-common-test` | Test base classes and fakes (`BaseTest`, `BaseComposeRobolectricTest`, `SafTestHarness`, `FakeDocumentsProvider`) |
| `app-e2e` | Device E2E tests (`DashboardCleanupTest`, `UpgradeTest`) |
| `app-tool-corpsefinder` | Leftovers of uninstalled apps, plus an uninstall watcher |
| `app-tool-systemcleaner` | Stock and custom filters for generic junk |
| `app-tool-appcleaner` | Per-app expendables plus inaccessible cache clearing (ADB trim or Accessibility) |
| `app-tool-deduplicator` | Checksum, pHash and audio/video duplicate detection, plus the arbiter |
| `app-tool-squeezer` | Lossy re-compression of JPEG/WebP/HEIC and video, done in place |
| `app-tool-swiper` | Tinder-style keep/delete per file |
| `app-tool-analyzer` | Storage analyzer |
| `app-tool-appcontrol` | Enable/disable/uninstall/force-stop apps |
| `app-tool-scheduler` | Scheduled one-click runs via WorkManager |

**Owner's list check:** the module list is correct. It is missing `app-common-{data,automation,coil,picker,setup,test}` and `app-e2e`. `app-tool-swiper` and `app-tool-squeezer` do exist.

### 1.2 Build setup
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`, package `eu.darken.sdmse`. Source: [SE:buildSrc/src/main/java/ProjectConfigPlugin.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/buildSrc/src/main/java/ProjectConfigPlugin.kt).
- Flavors: `foss` and `gplay` in one dimension called `version`. FOSS excludes the dependencies-info block. Source: [SE:app/build.gradle.kts](https://github.com/d4rken-org/sdmaid-se/blob/main/app/build.gradle.kts).
- **Jetpack Compose** (with the `com.android.compose.screenshot` screenshot-test plugin), **Hilt** DI, kotlinx-serialization, DataStore preferences, Room, WorkManager. The UI is fully Compose. The rules say new ViewModels extend `ViewModel4` ([architecture.md](https://github.com/d4rken-org/sdmaid-se/blob/main/.claude/rules/architecture.md)).
- Monetisation: many actions check `upgradeRepo.isPro()`. Examples:
  - Swiper free tier: 50 items and 2 sessions ([SE:app-tool-swiper/.../SwiperSettings.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-swiper/src/main/java/eu/darken/sdmse/swiper/core/SwiperSettings.kt)).
  - AppCleaner tasks have a `requiresPro` flag ([SE:app-tool-appcleaner/.../AppCleaner.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/AppCleaner.kt)).
  - The FOSS "upgrade" is GitHub Sponsors, unlocked by a "return-after-5s" heuristic ([SE:app/src/foss/.../FossUpgrade.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/foss/java/eu/darken/sdmse/common/upgrade/core/FossUpgrade.kt), [UpgradeViewModel.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/foss/java/eu/darken/sdmse/common/upgrade/ui/UpgradeViewModel.kt)).
- Permissions in the manifest include `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS` and `WRITE_SECURE_SETTINGS` ([SE:app/src/main/AndroidManifest.xml](https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/AndroidManifest.xml)).
- Network: only MOTD fetches from GitHub, which can be turned off ([SE:PRIVACY_POLICY.md](https://github.com/d4rken-org/sdmaid-se/blob/main/PRIVACY_POLICY.md)).

### 1.3 License
- `LICENSE` is the full GNU GPL v3 text ([SE:LICENSE](https://github.com/d4rken-org/sdmaid-se/blob/main/LICENSE)).
- README §License says code is GPL v3, **excluding** ([SE:README.md](https://github.com/d4rken-org/sdmaid-se/blob/main/README.md)):
  - icons, logos, mascots, marketing materials and assets;
  - animations and videos;
  - **documentation**;
  - Google Play screenshots.
- **Owner said "icons/docs/translations excluded". Correction:** the README does **not** list translations. It lists icons/logos/mascots/marketing assets, animations/videos, documentation and Play screenshots. Translations (`strings.xml` via Crowdin, [SE:crowdin.yml](https://github.com/d4rken-org/sdmaid-se/blob/main/crowdin.yml)) are not explicitly excluded, so formally they fall under GPL. Their copyright belongs to the Crowdin contributors. Not reusing them is still the right policy: our UX copy differs, and Hebrew is not in their list anyway.
- The `docs/` folder (the Jekyll site, e.g. `docs/swiper.md`) is excluded. We may cite it but must not copy its text.

### 1.4 Popularity
- Stars: **[UNVERIFIED]**. The GitHub API was blocked from this session. An ecosyste.ms aggregator shows **2,778 stars** with an unknown snapshot date ([sponsors.ecosyste.ms/accounts/d4rken](https://sponsors.ecosyste.ms/accounts/d4rken)). Owner said "~7.9k". That figure may be confused with the older SD Maid 1 or another repo. **Check manually on the repo page.**

### 1.5 `.claude/` folder (it exists, so the owner's claim is correct)
Source: [SE:.claude/](https://github.com/d4rken-org/sdmaid-se/tree/main/.claude)
- `CLAUDE.md`: a tool overview. It says to use the FOSS debug flavor, never hardcode UI strings, and read an existing test before writing one.
- `rules/`: `architecture.md`, `automation.md`, `build-commands.md`, `code-style.md`, `commit-guidelines.md`, `datastore-settings.md`, `docs.md`, `localization.md`, `release.md`, `testing.md`. Several are **path-scoped** through `paths:` frontmatter, e.g. testing rules load only when test files are touched.
- `settings.json`: allows `./gradlew`, git and `gh` reads, WebFetch for developer.android.com. It enables third-party plugins from a "clanker-cafe" marketplace (android-translation, google-play, debugbadger, …).
- **Worth copying:** path-scoped rules, the testing rule that says which base class to use and when JUnit4 vs JUnit5, and the "strings must live in strings.xml" rule.

---

## 2. Access layer

### 2.1 Paths and gateways
- `APath` has three `PathType`s: `RAW`, `LOCAL`, `SAF` ([SE:app-common-io/.../files/APath.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-io/src/main/java/eu/darken/sdmse/common/files/APath.kt)). Paths are serializable and use `segments` (the rules forbid string-splitting paths).
- `APathGateway<P, Lookup, LookupExtended>` is the single file API: `listFiles`, `lookup`, `lookupFiles` (streaming Flow), `walk(WalkOptions)`, `du`, `exists`, `canRead/canWrite`, `file()` handle, `delete(recursive)`, … ([SE:.../files/APathGateway.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-io/src/main/java/eu/darken/sdmse/common/files/APathGateway.kt)).
  - The KDoc on `delete` admits that "refuse non-empty dir" is **best-effort, not atomic**, because a child can appear between the check and the delete.
- `LocalGateway` has `Mode { NORMAL, ROOT, ADB, AUTO }`. In `AUTO` it tries plain `java.io` first. If `canRead` fails it escalates to ROOT if available, then ADB ([SE:.../files/local/LocalGateway.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-io/src/main/java/eu/darken/sdmse/common/files/local/LocalGateway.kt)). Walks with suspend callbacks (`onFilter`) must run app-side, because lambdas can't cross IPC (`WalkOptions.isDirect`).
- `SAFGateway` wraps DocumentsContract ([SE:.../files/saf/SAFGateway.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-io/src/main/java/eu/darken/sdmse/common/files/saf/SAFGateway.kt)). `GatewaySwitch` dispatches by `PathType` with a fallback ([SE:.../files/GatewaySwitch.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-io/src/main/java/eu/darken/sdmse/common/files/GatewaySwitch.kt)).
- **DataArea** model: every scan goes through typed storage areas. `SDCARD`, `PUBLIC_MEDIA`, `PUBLIC_DATA`, `PUBLIC_OBB`, `PRIVATE_DATA`, `DALVIK_*`, `DATA_SYSTEM*`, `PORTABLE`, `SYSTEM*`, … ([SE:app-common-data/.../areas/DataArea.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/main/java/eu/darken/sdmse/common/areas/DataArea.kt)). Each area has a module under `areas/modules/{pub,priv,system,dalvik,misc}`.
- **`Android/data` reality** ([SE:.../areas/modules/pub/PublicDataModule.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/main/java/eu/darken/sdmse/common/areas/modules/pub/PublicDataModule.kt)):

  | API level | Root / ADB available | Otherwise |
  |---|---|---|
  | 33+ | Converted to `LocalPath` and used | **Skipped**: `"Skipping Android/data (API33, no root/adb)"` |
  | 30–32 | Used | The SAF "select Android/data directly" workaround. `SAFSetupModule` detects DocumentsUI ≥ 331120000, which blocks it ([SE:app/.../setup/saf/SAFSetupModule.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/java/eu/darken/sdmse/setup/saf/SAFSetupModule.kt)) |

  `Android/media` (where WhatsApp lives) stays reachable with `MANAGE_EXTERNAL_STORAGE`. **On the owner's OnePlus 10T / Android 15, without Shizuku, SE cannot see `Android/data`.**

### 2.2 Root
- `RootManager.isRooted()` binds the root service and calls `ipc.checkBase()`. The result is cached per (setting, generation) key ([SE:app-common-io/.../root/RootManager.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-io/src/main/java/eu/darken/sdmse/common/root/RootManager.kt)).
- Launch: `su --mount-master`, then `/system/bin/app_process` runs a Kotlin `BaseRootHost` class from the APK. This is based on Chainfire's librootjava and handles linker-config/APEX quirks ([SE:app-common-root/.../RootHostCmdBuilder.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-root/src/main/java/eu/darken/sdmse/common/root/service/internal/RootHostCmdBuilder.kt), [RootHostLauncher.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-root/src/main/java/eu/darken/sdmse/common/root/service/internal/RootHostLauncher.kt)).
- The host returns its binder to the app through a broadcast sent by reflection (`ReflectionBroadcast.kt`). The IPC surfaces are AIDL in `app-common-io/src/main/aidl/`: `FileOpsConnection`, `PkgOpsConnection`, `ShellOpsConnection`, `RemoteInputStream/OutputStream`, `RemoteFileHandle`.

### 2.3 ADB: Shizuku **and Porter**
- **Porter is d4rken's own "minimal, maintained Shizuku fork"**, an ADB-privilege server app with its own SDK `com.github.d4rken-org.porter-api:sdk:0.9.0`. A `shizuku-compat` artifact lets the same SDK receive binders from Shizuku servers ([SE:app-common-adb/build.gradle.kts](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-adb/build.gradle.kts); [porter-api 0.8.0 release](https://github.com/d4rken-org/porter-api/releases/tag/0.8.0); [trendshift listing](https://trendshift.io/repositories/93780)).
  - **Owner's "Root + ADB via Shizuku/Porter" is correct.** Note that SE no longer depends on `dev.rikka.shizuku:*` directly. The Gradle comment says it "must not coexist with dev.rikka.shizuku:provider".
  - Porter's license: **[UNVERIFIED]**.
- `PorterGateway` is the single seam into the SDK. It exposes `link: Flow<AdbLink?>`, `connectionChanges` and `availability()`, the last mapped to `NotInstalled / Installed(backend=PORTER|SHIZUKU, connected) / Incompatible(serverTooOld, clientTooOld)` ([SE:.../adb/shizuku/PorterGateway.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-adb/src/main/java/eu/darken/sdmse/common/adb/shizuku/PorterGateway.kt), [AdbBackend.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-adb/src/main/java/eu/darken/sdmse/common/adb/shizuku/AdbBackend.kt)).
- `AdbHostLauncher` binds a **user service** (the Shizuku-style `UserServiceArgs`) and handshakes over the binder. It has a connect watchdog timeout, a generation lock, and closes on rebind or death so that the next acquire rebinds ([SE:.../adb/service/internal/AdbHostLauncher.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-adb/src/main/java/eu/darken/sdmse/common/adb/service/internal/AdbHostLauncher.kt)). The same `FileOps`/`PkgOps` AIDL hosts serve both root and ADB.
- Setup UX: `app/.../setup/shizuku/AdbManagerInstallGuide.kt` walks users through installing a manager.

**Takeaway:** a privileged process that speaks the same AIDL as the unprivileged path, plus mode escalation in the gateway, gives one code path for three access levels. KeepClear should copy this shape but start with NORMAL + Shizuku/Porter only.

---

## 3. CorpseFinder (leftovers of uninstalled apps)

Code: [SE:app-tool-corpsefinder/.../core/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-corpsefinder/src/main/java/eu/darken/sdmse/corpsefinder/core)

**Pipeline:** each `CorpseFilter` per area (`SdcardCorpseFilter`, `PublicMediaCorpseFilter`, `PublicDataCorpseFilter`, `PublicObbCorpseFilter`, `PrivateDataCorpseFilter`, `Dalvik…`, `AppLib…`, `AppSource…`, `ArtProfiles…`) asks **FileForensics** who owns each candidate. The answer is an `OwnerInfo(owners, installedOwners, hasUnknownOwner)`.

**Ownership via CSI processors** (one per area, under [SE:app-common-data/.../forensics/csi/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-common-data/src/main/java/eu/darken/sdmse/common/forensics/csi)):
- **Blacklist locations** (`Android/data`, `Android/media`, `Android/obb`, `/data/data`, app source dirs): the folder name *is* the package. Anything without an installed owner is a corpse.
  - `PublicDataCSI` also strips `.`, `_`, `.external.` and `:remote` decorations, which devs use to dodge uninstall cleanup. It then consults the clutter DB, and falls back to dirname = pkg ([SE:.../csi/pub/PublicDataCSI.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/main/java/eu/darken/sdmse/common/forensics/csi/pub/PublicDataCSI.kt)).
- **Whitelist location** (`SDCARD` root folders such as `/sdcard/MIUI`, `/sdcard/WhatsApp`): a folder is a corpse **only if a clutter marker names its owner** and no owner is installed (`OwnerInfo.isCorpse`, [SE:.../forensics/OwnerInfo.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/main/java/eu/darken/sdmse/common/forensics/OwnerInfo.kt)). Unknown folders are never flagged.

**Clutter DB** ([SE:app-common-data/src/main/assets/clutter/db_clutter_markers.json](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/main/assets/clutter/db_clutter_markers.json)):
- About 430 KB of `{pkgs:[…], mrks:[{loc, path|regex, flags}]}`.
- Flags (from [Marker.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/main/java/eu/darken/sdmse/common/clutter/Marker.kt)):
  - `custodian`: system-owned folders such as `DCIM`, `Download`, `Pictures`, `Android/media`. These are owned by `android`, so they are never corpses.
  - `keeper`: likely user content, e.g. `DCIM/AndroVid`.
  - `common`: shared names.
- Dynamic Kotlin matchers handle patterns JSON can't express (`SamsungTrashDynamicMarker`, `PrivateToSdcardPathDevMistakeMarker`, Tencent/Helpshift/VideoCache, …) ([SE:.../clutter/dynamic/modules/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-common-data/src/main/java/eu/darken/sdmse/common/clutter/dynamic/modules)).
- WhatsApp markers exist for `WhatsApp` and `WhatsApp Business`. This matters for the owner's pre-2022 `/sdcard/WhatsApp`. **WhatsApp is still installed**, so SE treats it as *owned* and not a corpse. KeepClear needs a separate "legacy WhatsApp folder" rule.

**False-positive protections** (`SdcardCorpseFilter.checkPotentialCorpses`, [SE:.../filter/SdcardCorpseFilter.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-corpsefinder/src/main/java/eu/darken/sdmse/corpsefinder/core/filter/SdcardCorpseFilter.kt)):
1. `hasUnknownOwner`: the item counts as owned.
2. `keeper` and `common` items are excluded unless the user opts in. `risk.include.keeper` and `risk.include.common` both default to `false` ([CorpseFinderSettings.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-corpsefinder/src/main/java/eu/darken/sdmse/corpsefinder/core/CorpseFinderSettings.kt)). Results also carry `RiskLevel {NORMAL, KEEPER, COMMON}` for the UI.
3. **Blocked by living item:** a dead folder that is an ancestor of any alive item is dropped.
4. **Covered:** nested corpses inside a corpse are merged.
5. Nested markers are only resolved if the top-level parent exists. Regex markers are not reverse-resolved. Unreadable paths yield no corpse. Casing is corrected to the on-disk spelling.
6. "Installed" includes **archived apps** and **`pm uninstall -k` (kept-data)** packages through `MATCH_ARCHIVED_PACKAGES | MATCH_UNINSTALLED_PACKAGES` ([SE:app-common-pkgs/.../sources/NotInstalledPkgsSource.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-pkgs/src/main/java/eu/darken/sdmse/common/pkgs/sources/NotInstalledPkgsSource.kt)). An archived app's data is therefore not a corpse. **This matters on Android 15.**
7. Risky filters are off by default: app source, dalvik, applib, OBB, asec.

**Uninstall watcher** (opt-in): a broadcast receiver on package removal scans for that package's leftovers. It **auto-deletes by default once enabled** (`watcher.uninstall.autodelete.enabled = true`). KeepClear should not auto-delete. It should notify and route to the trash.

---

## 4. AppCleaner (caches)

Code: [SE:app-tool-appcleaner/.../core/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core)

**Two kinds of junk:**
- **Accessible expendables:** files under `Android/data/<pkg>/cache`, `Android/media/<pkg>/…` and sdcard. They are matched by `ExpendablesFilter`s backed by JSON sieves in `app/src/main/assets/expendables/db_*.json`: thumbnails, webcaches, trash, offline caches, analytics, ads, bug reporting, game files, hidden caches. Some are code filters: `WhatsAppReceivedFilter`, `WhatsAppBackupsFilter`, `TelegramFilter`, `RecycleBinsFilter`, …
- **Inaccessible cache** (`/data/data/<pkg>/cache`): the size comes from `StorageStatsManager.queryStatsForPackage` (`cacheBytes`, `externalCacheBytes`), which needs `PACKAGE_USAGE_STATS` ([InaccessibleCacheProvider.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/scanner/InaccessibleCacheProvider.kt)).

**How inaccessible cache gets cleared** ([InaccessibleDeleter.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/InaccessibleDeleter.kt)):
1. **Root or ADB (Shizuku/Porter), whole-tool clean:** `pkgOps.trimCaches(Long.MAX_VALUE)`, which runs `PackageManager.freeStorageAndNotify` in the privileged host ([PkgOpsHost.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-io/src/main/java/eu/darken/sdmse/common/pkgs/pkgops/ipc/PkgOpsHost.kt)). It is device-global: the system trims every app's cache. The code waits 3 s, then re-queries per-app stats to credit results.
2. **Accessibility automation** for whatever remains:
   - only if the user enabled it (`acs.enabled` default `false`) **and** gave explicit `hasAcsConsent`;
   - a pre-flight skips apps with no reachable settings page or disabled apps;
   - optional force-stop first (default off).
   - The service opens each app's *App info → Storage* page and clicks "Clear cache" by matching localized labels.
   - Per-ROM "specs" exist for AOSP, OneUI, MIUI, HyperOS, **OxygenOS**, **ColorOS**, Realme, Flyme, Huawei, Honor, Nubia, LGE, Alcatel, Doogee, Oukitel, OriginOS, FuntouchOS, AndroidTV ([specs/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/automation/specs)).
   - OxygenOS: the ROM is auto-detected. Step 1 finds the "Storage" entry using labels read dynamically from `com.android.settings` resources (`storage_settings`). Step 2 finds "Clear cache". On API 34+ it walks up to the clickable parent ([OxygenOSSpecs.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/automation/specs/oxygenos/OxygenOSSpecs.kt)). OnePlus on Android 13+ ships ColorOS-based OxygenOS. Which spec actually fires on a OnePlus 10T/A15 depends on `deviceDetective` **[UNVERIFIED on device]**.
3. **What the user sees:** Settings screens flash by for each app, under a Compose overlay that explains what is happening and how to cancel ([AutomationOverlay.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-automation/src/main/java/eu/darken/sdmse/automation/ui/AutomationOverlay.kt)). The service config has `canRetrieveWindowContent` + `canPerformGestures` ([accessibility_service.xml](https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/res/xml/accessibility_service.xml)).

**Defaults worth noting** ([AppCleanerSettings.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/AppCleanerSettings.kt)):
- Minimum cache size 48 KB.
- Messenger filters are **off by default**: WhatsApp received/sent/backups, Telegram, Viber, WeChat, Threema.
- Recycle bins off, offline caches off, game files off.
- System apps excluded.
- Default exclusions protect banking/TAN apps (`com.starfinanz…pushtan`) from AppCleaner ([DefaultExclusions.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-exclusion/src/main/java/eu/darken/sdmse/exclusion/core/DefaultExclusions.kt)). The reasoning: clearing their data breaks 2FA.

**WhatsApp filters, directly relevant to the owner's 42 GB:**
- `WhatsAppReceivedFilter` matches `WhatsApp/Media/WhatsApp {Video,Images,Animated Gifs,Audio,Documents,Voice Notes}` in both legacy `/sdcard/WhatsApp` and `Android/media/com.whatsapp/WhatsApp`. It **excludes `Sent`, `Private` and `.nomedia`** ([WhatsAppReceivedFilter.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/forensics/filter/WhatsAppReceivedFilter.kt)).
- `WhatsAppBackupsFilter` only takes backup files older than 1 day, leaving the newest backup alone ([WhatsAppBackupsFilter.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/forensics/filter/WhatsAppBackupsFilter.kt)).
- There is no age filter on received media and no "keep last N months". That is all-or-nothing, which is **unsafe for memories**.

**Risks of the ACS approach:**
- Play policy scrutiny for AccessibilityService. SE declares it as optional, opt-in automation.
- Fragile label matching across OEM updates; the code ships a `LabelDebugger` for exactly this.
- It can mis-click. SE mitigates with window-package checks, `ClearCacheButtonVerdict` and `StorageSnapshotDelta`.
- Slow: roughly 4–10 s per app, per the code comment.
- It is frightening UX for non-technical users.

KeepClear: prefer Shizuku `freeStorageAndNotify`. Otherwise deep-link each app's settings page for the user to tap. Treat ACS as a v2+ question.

**Recycle-bin junk** ([RecycleBinsFilter.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/forensics/filter/RecycleBinsFilter.kt), [db_trash_files.json](https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/assets/expendables/db_trash_files.json)):
- Generic `<top>/.trash|trash|.trashfiles|trashbin/…` patterns.
- Per-app trash folders, including **`com.coloros.filemanager`**, `com.oneplus.gallery` (`files/recyclebin/`), MIUI gallery, Google Files and Solid Explorer.
- Directly relevant to the owner's 3.8 GB file-manager bin. The JSON is GPL data and can be reused with attribution.

---

## 5. Deduplicator

Code: [SE:app-tool-deduplicator/.../core/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core)

| "Sleuth" | Algorithm | Default |
|---|---|---|
| `ChecksumSleuth` | Group by **size** first (buckets of 2 or more), then **SHA-256** of the content | on |
| `PHashSleuth` | DCT pHash: 64×64 grayscale, then DCT, then 12×12 low-frequency AC terms against their mean, giving 121 bits. Decode target 256 px. **Similarity > 0.95** groups. Low-complexity images (AC variance < 100, e.g. blank or solid) are **skipped** to avoid false matches | off |
| `MediaSleuth` | Video/audio: bucket by duration, then frame and audio fingerprint (`AudioFingerprinter`, `SimpleFFT`). Accept above 0.95, reject below 0.85 | off |

Sources: [ChecksumSleuth.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/scanner/checksum/ChecksumSleuth.kt), [PHashAlgorithm.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/scanner/phash/phash/PHashAlgorithm.kt) (credits a public gist and the hackerfactor article), [PHashSleuth.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/scanner/phash/PHashSleuth.kt), [MediaComparator.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/scanner/media/MediaComparator.kt).

Other defaults: minimum file size 512 KB, and `skipUncommon` (only common image/video/audio/archive/doc MIME types, via `CommonFilesCheck`).

**Arbiter: which copy to keep** ([DuplicatesArbiter.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/arbiter/DuplicatesArbiter.kt), [ArbiterCriterium.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/arbiter/ArbiterCriterium.kt)):
- An ordered, user-reorderable list of criteria is applied as **stable sorts in reverse order**, so the top criterion wins. The first element of the result is kept.
- Default order:
  1. DuplicateType: prefer the checksum group over the pHash group.
  2. PreferredPath: user "keep" folders.
  3. MediaProvider: prefer files indexed by MediaStore.
  4. Location: prefer primary storage.
  5. Nesting: prefer shallow.
  6. Modified: **prefer older**.
  7. Size: prefer larger.
- Protections: `allowDeleteAll = false` by default, and the user can pin a `keeperIdentifier` per group ([DuplicatesDeleter.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/deleter/DuplicatesDeleter.kt)).
  - Caveat: when a cluster is deleted, the non-favorite *groups* of that cluster are deleted entirely (`deleteAll = true`). This relies on the cluster linkage being correct.
  - After deleting, it calls `mediaStoreTool.flush()` so the gallery updates.
- **For WhatsApp "Sent" vs camera:** this arbiter directly fits the owner's "Sent folders = duplicates" case. Add a KeepClear criterion: "prefer DCIM/Camera over WhatsApp/Sent".

---

## 6. Swiper

Code: [SE:app-tool-swiper/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-swiper/src/main/java/eu/darken/sdmse/swiper). The docs in [SE:docs/swiper.md](https://github.com/d4rken-org/sdmaid-se/blob/main/docs/swiper.md) are excluded from GPL, so they are cited here, not copied.

- **Flow:**
  1. Pick folders or files, with an optional file-type filter and sort order (oldest first by default).
  2. Scan.
  3. Swipe each file: right = keep, left = delete, up = skip, down = undo. Undo goes back up to 50 decisions. Long-press skip creates an exclusion.
  4. Review.
  5. Confirm the delete.
- Sessions are persisted in Room (`SwiperDatabase`, `SwipeSessionEntity`, `SwipeItemEntity`), so a session can be resumed.
- Picking a whole storage root requires extra confirmation and an "I understand these files will be permanently deleted" checkbox (strings in `app-tool-swiper/src/main/res/values/strings.xml`).
- "Deleting is permanent. Files are not moved to a trash folder and SD Maid can't restore them." ([docs/swiper.md](https://github.com/d4rken-org/sdmaid-se/blob/main/docs/swiper.md))
- The free tier is capped at 50 items and 2 sessions (§1.2).
- **Relevance:** this is the right UX for the owner's 2,324 screenshots and 526 ChatGPT images. KeepClear's version adds trash, a "why" line per card, and batch-by-month.

---

## 7. SystemCleaner and Squeezer

### 7.1 SystemCleaner stock filters
Code: [SE:app-tool-systemcleaner/.../filter/stock/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-systemcleaner/src/main/java/eu/darken/sdmse/systemcleaner/core/filter/stock). Defaults from [SystemCleanerSettings.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-systemcleaner/src/main/java/eu/darken/sdmse/systemcleaner/core/SystemCleanerSettings.kt).

| Filter | What it matches | Default |
|---|---|---|
| `ScreenshotsFilter` | Files under `Pictures/Screenshots` (SDCARD, PORTABLE) older than `filterScreenshotsAge` (**14 days**) | **off** |
| `TrashedFilter` | Files named `.trashed-*` (Android 11+ MediaStore trash, i.e. the gallery "Recently deleted") | **off** |
| `ThumbnailsFilter` | Any `.thumbnails` segment (SDCARD, PUBLIC_MEDIA, PORTABLE) | off |
| `SuperfluousApksFilter` | `.apk/.apks` whose package is installed at the same or a newer version. Opens the archive to read the version; skips `Backup/Backups/Recover` folders | off |
| `EmptyDirectoryFilter` | Empty dirs, with protections for Android/data, media and obb roots | on |
| `LostDirFilter` | `LOST.DIR` contents | on |
| `TempFilesFilter` | `*.tmp`, `*.temp`, `.mmsyscache` | on |
| `LogFilesFilter`, `AnrFilter`, `TombstonesFilter`, `LogDropboxFilter`, `DataLoggerFilter`, `UsagestatsFilter`, `RecentTasksFilter`, `PackageCacheFilter`, `DownloadCacheFilter`, `DataLocalTmpFilter` | System logs and caches (most need root) | mixed |
| `LinuxFilesFilter`, `MacFilesFilter`, `WindowsFilesFilter` | `.directory`, `.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini` | on |
| `AdvertisementFilter`, `AnalyticsFilter` | Known ad and analytics dirs | on |

Custom filters are user-defined (path/name/regex/age/size) with an editor plus import/export ([custom/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-systemcleaner/src/main/java/eu/darken/sdmse/systemcleaner/core/filter/custom)).

**Pattern:** every filter is a `SystemCrawlerSieve.Config` (target types, areas, `SegmentCriterium`/`NameCriterium` with modes `Ancestor/Contain/Start/End/Equal/Specific(index, backwards)`, minimum age). These are declarative and easy to test.

Gaps for the owner's phone (not covered by SE):
- extracted zips next to their folders;
- ChatGPT images;
- "MIUI/ShareMe" leftovers when the owning app is still in the clutter DB but not installed. CorpseFinder may catch this if markers exist.

### 7.2 Squeezer (lossy compression in place)
Code: [SE:app-tool-squeezer/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-squeezer/src/main/java/eu/darken/sdmse/squeezer/core)

- **Defaults:** JPEG/WebP on, HEIC/video off; quality 80; minimum 512 KB; minimum age **90 days**; skip previously compressed (a history DB keyed by content hash); optional EXIF marker; motion photos and "lossy aux" images excluded.
- **Implementation:** EXIF preserved (`ExifPreserver`, `HeifExifExtractor`), video timestamps preserved, Media3 Transformer for video.
- **`FileTransaction.replace()`** ([FileTransaction.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-squeezer/src/main/java/eu/darken/sdmse/squeezer/core/processor/FileTransaction.kt)):
  1. Recover orphans from interrupted runs.
  2. Write the new version to a temp file in a hidden workdir.
  3. Abort if it is not smaller.
  4. Rename the original to a backup.
  5. Rename the temp file to the original name.
  6. Verify.
  7. Delete the backup.

  The backup is **never** deleted on an error path. **This is the best template in SE for KeepClear's MOVE flow** (copy → verify → delete).
- It is still destructive: the original quality is lost and there is no undo after success.

---

## 8. Deletion, confirmation, trash, exclusions, scheduler

- **Confirmation:**
  - List screens emit `Event.ConfirmDeletion(ids)`, and the delete only runs from `onDeleteConfirmed` (e.g. [CorpseFinderListViewModel.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-corpsefinder/src/main/java/eu/darken/sdmse/corpsefinder/ui/list/CorpseFinderListViewModel.kt)), using the shared `SdmConfirmDialog`.
  - **But** "one-click" dashboard and shortcut/widget modes run scan + delete for CorpseFinder, SystemCleaner and AppCleaner (Deduplicator off) with one tap ([GeneralSettings.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/main/java/eu/darken/sdmse/main/core/GeneralSettings.kt)). The Scheduler runs them unattended.
- **Trash / undo:** **none.** No code path moves files to an app trash. `grep` for trash/restore/`IS_TRASHED`/`createTrashRequest` in `app-common-io` finds nothing relevant. Deletes go straight through `GatewaySwitch.delete`, then `MediaStoreTool.flush`. The Swiper docs state it is permanent. The only "undo" is in-session swipe undo and Squeezer's transient backup.
- **Reports/stats:** `app-common-stats` records affected paths and packages per task (`AffectedPath`, `AffectedPkg`, `StatsRepo`) plus storage history and a forecast. It is an audit log, not a restore point ([SE:app-common-stats/.../core/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-common-stats/src/main/java/eu/darken/sdmse/stats/core)).
- **Exclusions:**
  - Types: `PkgExclusion`, `PathExclusion`, `SegmentExclusion`, tagged `GENERAL|CORPSEFINDER|SYSTEMCLEANER|APPCLEANER|DEDUPLICATOR|SQUEEZER|SWIPER`.
  - Built-in defaults carry a GitHub-issue URL as their reason and can be removed or restored.
  - `PathExclusionIndex` provides fast lookup. Import/export and legacy import are supported.
  - Source: [SE:app-common-exclusion/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-common-exclusion/src/main/java/eu/darken/sdmse/exclusion/core).
- **Scheduler:** `SchedulerManager` enqueues a `OneTimeWorkRequest` per schedule with WorkManager and re-enqueues after each run. `SchedulerRestoreReceiver` handles boot ([SE:app-tool-scheduler/.../SchedulerManager.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-scheduler/src/main/java/eu/darken/sdmse/scheduler/core/SchedulerManager.kt)).

---

## 9. Tests

- Volume of JVM test files, per module:

  | Module | Test files |
  |---|---|
  | app-common-data | 73 |
  | app-tool-systemcleaner | 56 |
  | app-common-io | 42 |
  | app-tool-squeezer | 39 |
  | app-tool-deduplicator | 36 |
  | app-tool-appcleaner | 29 |
  | app-tool-corpsefinder | 28 |
  | app-tool-swiper | 21 |

  Plus `app-e2e` on a device.
- Stack: JUnit 5 + Kotest matchers + MockK + coroutine test. Robolectric runs on JUnit 4, and Compose UI tests run on the JVM through `BaseComposeRobolectricTest` ([.claude/rules/testing.md](https://github.com/d4rken-org/sdmaid-se/blob/main/.claude/rules/testing.md)).
- **Filter table tests (copy this):**
  - `SystemCleanerFilterTest` builds fake `DataArea`s (two sdcards, private data, portable, …) and mocks lookups.
  - Each filter test is a list of `pos(SDCARD, "Pictures/Screenshots/123ABC.png", Flag.File)` / `neg(SDCARD, "Pictures/Screenshots", Flag.Dir)` lines, followed by `confirm(filter)`.
  - Sources: [SystemCleanerFilterTest.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-systemcleaner/src/test/java/eu/darken/sdmse/systemcleaner/core/filter/SystemCleanerFilterTest.kt), [ScreenshotsFilterTest.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-systemcleaner/src/test/java/eu/darken/sdmse/systemcleaner/core/filter/stock/ScreenshotsFilterTest.kt).
- **Forensics tests:** `BaseCSITest` provides MockK-heavy area and package setup for each CSI ([BaseCSITest.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-data/src/test/java/eu/darken/sdmse/common/forensics/csi/BaseCSITest.kt)).
- **Behaviour-named corpse tests**, e.g. `` `a nested clutter marker with an installed owner is not a corpse` `` and `` `markers pointing at an unreadable path yield no corpse` `` ([SdcardCorpseFilterTest.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-corpsefinder/src/test/java/eu/darken/sdmse/corpsefinder/core/filter/SdcardCorpseFilterTest.kt)). These read as a safety spec.
- **Fake SAF:** `SafTestHarness` + `FakeDocumentsProvider` wire a real `SAFGateway` to an in-memory DocumentsProvider under Robolectric, including narrow tree grants such as `Android/data` ([SafTestHarness.kt](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common-test/src/main/java/testhelpers/saf/SafTestHarness.kt)).
- **Seams for testability:** `PorterGateway` and `AdbHostLauncherSeam`/`RootHostLauncherSeam` exist so that IPC orchestration can be unit-tested without a device.
- **Dry-run mode:** `Bugs.isDryRun` short-circuits destructive host ops (trimCaches, ACS click, Squeezer swap). It is useful for QA and screenshots.

---

## 10. Adopt / Avoid / Exact files worth learning from

| Area | Adopt | Avoid | Exact files |
|---|---|---|---|
| I/O abstraction | `APath` + segments; one gateway interface; NORMAL→ADB escalation; streaming `lookupFiles` | The root host via `app_process` (complex, and the owner has no root); SAF Android/data hacks (dead on 13+) | `app-common-io/.../files/APathGateway.kt`, `GatewaySwitch.kt`, `local/LocalGateway.kt` |
| Privileged access | Shizuku/Porter **user service** + AIDL; availability states (NotInstalled / Installed / Connected / Incompatible) to drive onboarding | Hard dependency on one backend; showing Shizuku branding | `app-common-adb/.../shizuku/PorterGateway.kt`, `service/internal/AdbHostLauncher.kt`, `app-common-io/src/main/aidl/**` |
| Storage areas | Typed `DataArea` with per-API resolution | Areas KeepClear can't reach (system, dalvik) in v1 | `app-common-data/.../areas/DataArea.kt`, `modules/pub/*.kt` |
| Leftover detection | Clutter-marker DB with custodian/keeper/common flags; blacklist vs whitelist locations; blocked-by-living-item; archived apps count as installed | Uninstall watcher **auto-delete** default; regex reverse-matching | `forensics/OwnerInfo.kt`, `forensics/csi/pub/PublicDataCSI.kt`, `corpsefinder/.../SdcardCorpseFilter.kt`, `assets/clutter/db_clutter_markers.json` |
| App cache | `StorageStatsManager` sizes; Shizuku `freeStorageAndNotify`; min-size threshold; banking-app default exclusions | Accessibility auto-clicking in v1 (Play risk, fragile, scary UX) | `appcleaner/core/InaccessibleDeleter.kt`, `scanner/InaccessibleCacheProvider.kt`, `pkgops/ipc/PkgOpsHost.kt` |
| WhatsApp | Folder mapping for legacy and `Android/media`; exclude `Sent`/`Private`/`.nomedia` in the received filter; keep the newest backup | All-or-nothing received-media deletion; touching `Databases/` (SE doesn't, and neither should we) | `appcleaner/.../filter/WhatsAppReceivedFilter.kt`, `WhatsAppBackupsFilter.kt` |
| Trash folders | JSON list of OEM recycle bins (ColorOS file manager, OnePlus gallery, Google Files…) | Deleting bins silently: show size and age, and explain it is already-deleted data | `app/src/main/assets/expendables/db_trash_files.json`, `RecycleBinsFilter.kt` |
| Junk filters | Sieve/criterium DSL; `.trashed-*`, `.thumbnails`, superfluous APKs (version check), empty dirs, LOST.DIR, temp | Screenshot auto-delete by age without review | `systemcleaner/.../filter/stock/*.kt`, `common/sieve/*` |
| Duplicates | Size → SHA-256; pHash with a low-complexity skip; **ordered arbiter**; `allowDeleteAll=false`; MediaStore flush | Cluster-wide `deleteAll` of non-favorite groups without showing them | `deduplicator/core/scanner/checksum/ChecksumSleuth.kt`, `phash/phash/PHashAlgorithm.kt`, `arbiter/DuplicatesArbiter.kt` |
| Swiper | Persisted sessions, undo stack, long-press to exclude, review screen before delete | Permanent delete; free-tier caps | `swiper/core/db/*`, `swiper/core/Swiper.kt` |
| Atomic file ops | **`FileTransaction`** (temp, backup, swap, verify, orphan recovery) as the base for trash and MOVE | Compressing originals in place (quality loss) as a default | `squeezer/core/processor/FileTransaction.kt` |
| Exclusions | Tagged pkg/path/segment exclusions; defaults with reason URLs | Hidden defaults with no explanation | `app-common-exclusion/.../DefaultExclusions.kt`, `types/*.kt` |
| Tests | `pos/neg` filter tables; behaviour-named safety tests; fake SAF provider; seams around IPC; dry-run flag | Over-mocking (BaseCSITest is heavy) | `SystemCleanerFilterTest.kt`, `SdcardCorpseFilterTest.kt`, `testhelpers/saf/*` |
| AI tooling | `.claude/rules/*` path-scoped rules | Third-party plugin marketplaces in shared settings | `.claude/CLAUDE.md`, `.claude/rules/testing.md` |

### GPL reuse notes
- KeepClear is GPL-3.0, so copying SE **source code and code-embedded data** (Kotlin, AIDL, JSON marker/expendable DBs) is allowed if we:
  - keep the copyright and license headers;
  - add a `NOTICE`/`THIRD_PARTY.md` entry naming "SD Maid SE © Matthias Urhahn (d4rken) and contributors, GPL-3.0", with source URL and commit `95a673f`;
  - mark modified files (GPL-3 §5a);
  - keep KeepClear under GPL-3.0 (no "or later" mismatch issue, since SE's text is plain GPLv3 **[verify whether SE intends "or later" — the README just says "GPL v3"]**).
- **Do not copy:** icons, logos, mascot, animations/videos, `docs/` text, Play screenshots (README exclusions).
- **Translations:** not explicitly excluded, but don't reuse them. Write our own English and Hebrew.
- The pHash code in SE credits an external gist by Elliot Shepherd. Its upstream license is **[UNVERIFIED]**. Re-implement from the hackerfactor description, or verify the gist's license first.
- Porter SDK: license **[UNVERIFIED]**. Shizuku-API is Apache-2.0/MIT per the owner's notes. Decide on Porter vs Shizuku separately.
- Practical approach: **port selectively** (gateway interface, CSI logic, JSON DBs, filter DSL, FileTransaction) rather than forking. SE's module graph, pro-gating and ACS engine are heavy baggage.

---

## Implications for KeepClear

**Adopt**
- The APath/gateway abstraction with NORMAL + Shizuku/Porter modes, and AIDL user-service hosts.
- The CorpseFinder model: clutter marker DB, flags, and blocked-by-living-item. Treat archived and kept-data apps as installed.
- The JSON expendables DBs (trash bins incl. ColorOS/OnePlus, thumbnails, webcaches) as the SAFE category's seed data.
- The Deduplicator pipeline (size → SHA-256, optional pHash with a variance skip) and the **ordered arbiter**. Add "prefer DCIM over WhatsApp/Sent".
- Squeezer's `FileTransaction` as the core primitive for both the **30-day trash** (move into a trash dir with a manifest) and **MOVE** (copy → verify count + size → delete).
- `pos/neg` table tests and behaviour-named safety tests. Write these for every KeepClear rule.

**Avoid**
- Permanent delete anywhere: SE has no trash. One-click or scheduled unattended deletes. The uninstall-watcher auto-delete default.
- Accessibility-service cache clicking in v1. Use Shizuku `freeStorageAndNotify`, or per-app settings deep links.
- Age-only screenshot deletion and all-or-nothing WhatsApp received deletion. KeepClear must use REVIEW with month buckets.
- Pro-gating safety-relevant actions.

**Open questions**
1. On Android 15 without Shizuku, `Android/data` is invisible: do we push Shizuku/Porter in onboarding, or accept the limit? Note that WhatsApp media is in `Android/media` and is reachable.
2. Porter vs Shizuku: license and Play-policy implications of bundling the Porter SDK **[UNVERIFIED]**.
3. Does the ColorOS file manager expose its recycle bin path on the OnePlus 10T / A15 as listed in `db_trash_files.json`? Verify with ADB.
4. Which AppCleaner spec (OxygenOS vs ColorOS) the 10T resolves to. This only matters if ACS is ever added.
5. Star count and current SE status: verify manually (aggregator says ~2.8k; the owner said ~7.9k).
