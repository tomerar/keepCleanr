# CLAUDE.md: KeepClear

KeepClear is an open-source (GPL-3.0-or-later) Android storage cleaner. Junk is deleted in one tap, anything unclear is explained, and memories are **moved** (backup → verify → delete), never just deleted.

## Read before coding

1. `docs/spec/requirements.md`: what to build, with IDs (SAFETY-*, MOVE-*, …) and priorities.
2. `docs/spec/design.md`: modules, data flow, abstractions.
3. `docs/spec/edge-cases.md`: required behaviour for failures (E-*).
   Also `docs/spec/threat-model.md` (SEC-*): treat sidecars, manifests, archives and intents as untrusted.
4. `docs/spec/tasks.md`: current milestone, definition of done, required tests.
5. `docs/adr/`: decisions; don't contradict an ADR without writing a new one.
6. `docs/research/`: evidence and sources. Check it before assuming an Android API or Play policy behaviour.

Work only on the current milestone in `tasks.md`. If the spec is wrong or incomplete, update the spec in the same PR and say so.

## Current gallery work (owner 2026-11-08)

The owner accepted `docs/spec/gallery-decisions.md` (D-G1…D-G8) and requested the documentation on main plus a full builder handoff. Gallery development is a **scoped exception** to the earlier feature freeze; existing phone/safety/release checks remain pending. Read `docs/spec/gallery-overview.md` and `docs/prompts/gallery-builder.md`. G0 owner approval is recorded; **G1a internal JVM implementation and CI passed on `d079573`; finish the PR gate before G1b**. Do not ask for that approval again or start unrelated Later features.

Gallery must cost the user nothing extra and run entirely on the phone without an account/credits/provider. Implement deterministic metadata/exact-hash/visual-hash/quality stages first, factual keeper advice with abstention, incremental reuse and an explicit uncertainty router. Optional admitted local AI may process only a budgeted ambiguous subset; lazy-load, deduplicate participants and stop early. No whole-library embedding, cloud/subscription/A2A/MCP integration, automatic preselection/deletion or category/proof weakening. Keep MOVE memories on the verified-backup path. Both new ADR policies are Accepted; no model/runtime is admitted. Benchmarks are provisional targets until actually measured.

## Status

Spec approved by the owner on 2026-10-31 (decisions in `docs/spec/requirements.md` §14). MVP milestones **M0–M10 are merged** (PRs #1–#11). Owner decisions of 2026-11-01 (B1, B2, Q4, product principles, backup targets, caches) are in `requirements.md` §0a and §14. The review PRs A, B, C and the v1 milestones M11–M16a, M17a (Nextcloud/WebDAV engine), M17c (Dropbox engine) and M18a (reproducible build, store listing, accessibility) are merged (PRs #12–#33). **Historical feature freeze (owner, 2026-11-02): no new user features until the app runs on the owner's phone; amended for staged gallery development by D-G8 on 2026-11-08 (above), without waiving device/release checks.** The three allowed items are merged (PRs #37–#40: SMB flaky-test diagnostics with issue #36, Device check screen, OkHttp + Nextcloud in CI, Nextcloud under Advanced); next is the owner's Device check on the phone. The owner review of 25a3be7 (2026-11-02) is done: PRs #42–#49 (D safety and security, E stability, F1 "Paper & Ink" design system and three-tab navigation, F2 screens and Hebrew voice, F3 screenshots and store); the freeze was lifted only for those items. Dropbox stays engine-only until W8; Play listing, developer verification and F-Droid submission come after the device checks pass. Device checks and proposals waiting for the owner: `docs/progress.md`.

## Product principles (owner, 2026-11-01)

1. **Phone-only.** Everything works with only the phone; no user step ever needs a computer. `adb` and PC `sha256sum` are developer checks; the user-facing equivalent is the in-app "Check backup" (MOVE-15).
2. **Simple by default, Advanced on request.** No technical terms on the default path (no "SHA-256", "SAF", "manifest", "cache partition"); details live under "Details" and Settings › Advanced.
3. **After a verified backup, always ask.** Show the receipt; free space only when the user taps "Free up space". Never automatic.

## Identity

- App name **KeepClear**, applicationId **`io.github.tomerar.keepclear`** for both flavors, one signing key (ADR 0009). Debug/test builds are `io.github.tomerar.keepclear.test` ("KeepClear test"), so the public test key can never update a real install.

## Stack

- Kotlin only, Jetpack Compose (stable Material 3), Room, WorkManager, Coroutines/Flow, kotlinx.serialization. Actual composition is a manual graph in `KeepClearApplication`; thumbnails use platform APIs with a memory-only cache. Hilt/Coil are not current dependencies or gallery prerequisites.
- minSdk 30, targetSdk 36, compileSdk 37 (ADR 0003).
- Flavors: `foss` (all-files access, F-Droid/GitHub) and `play` (MediaStore + SAF only) (ADR 0005).
- Tests: JUnit5, Kotest (property tests), Turbine, Robolectric (sparingly), Compose UI tests, screenshot tests, Konsist architecture rules.

## Safety rules (non-negotiable)

1. **Never delete without explicit confirmation** in the current session. No background or scheduled deletion of user files.
2. **Every user-file deletion goes to the 30-day trash** (`engine:trash`). Permanent deletion happens only through purge, "Empty now" with a second confirmation, system cache trimming that is labelled as such, or (owner, 2026-11-02, ADR 0006 amendment) the two kinds `ActionExecutor` itself decides per item with `PermanentKinds`: items that rebuild themselves (`thumbnails`, `temp_files`, `os_junk`, `wa_status_old`) and items already in another app's bin (`other_app_bin`), SAFE only, rule and strict path agreeing, never duplicates/witnesses/folders; and (owner decision P1, 2026-11-03) **MOVE originals freed after a verified backup**, only with a `BackupProof` (second check passed, copy re-hashed at free time) and after the strict re-hash of the original, unless the user turned on "Keep the original in the 30-day trash too". Everything else (duplicates, extracted archives, REVIEW) still goes to the trash.
3. **One choke point**: only `ActionExecutor` in `core:safety` (plus the trash and storage implementations) may call `File.delete`, `DocumentsContract.deleteDocument`, `ContentResolver.delete`, `MediaStore.create*Request`, or rename user files. Konsist enforces this.
4. **PROTECTED paths are enforced at the choke point**: WhatsApp `Databases/`, `Backups/`, `*.crypt*`, `msgstore*`, our trash, the active backup target, and user exclusions.
5. **MOVE deletes an original only after an L3 (SHA-256 read-back) verification confirmed by the second, uncached check**, a re-hash of the copy on the target at free time (batch by batch), a size/mtime/identity re-check and a re-hash of the original right before deleting (strict mode, on by default; ADR 0007 and its 2026-11-03 amendment). A file failing any check is skipped and reported, never deleted. The free step's pre-ticked "keep favourites / the last 30 days on the phone" options keep files; they are not pre-selections for deletion (rule 7).
6. **Re-check before acting**: if an item changed since the scan, skip it and report it.
7. **Never pre-select** REVIEW or MOVE items. Duplicate groups always keep at least one copy.
8. Destructive code ships **with its tests in the same PR**: unit tests on `FakeStorageGateway` plus kill-point property tests for the no-loss invariant.

## Product rules

- No ads, analytics, crash SDKs or Google Play Services. No `INTERNET` permission in MVP (ADR 0008). Network/cloud backup targets per ADR 0011 (accepted; Google Drive is **postponed by the owner**, so SMB, then Nextcloud, Dropbox): network code only in dedicated `backup:target-*` modules, and a cloud copy counts as verified only with a checksum the server computed.
- Network places never point at the phone itself (D3): the app always uses `HostRule.NetworkOnly`; tests that need a server on their own machine pass `TestsOnThisDevice` with `@OptIn(ThisDeviceInTests::class)`, and no main class may refer to it (`checkForbiddenApis`). An SMB guest place is copy-only.
- No AccessibilityService (ADR 0008). No Accessibility cleaner in v1 (ADR 0012, accepted); the guided per-app list replaces it. Shizuku is Later; "Clear app caches" uses `allocateBytes` (SAFE-5) and `ACTION_CLEAR_APP_CACHE` (SAFE-6).
- Copy: exact numbers (count + bytes), plain language, no fear or "boost" claims. Every item has why / what-happens-if-deleted texts in **English and Hebrew**.
- RTL: use start/end only, auto-mirrored icons, and bidi-isolate filenames, paths and numbers. Hebrew is gender-neutral (plural/infinitive).
- All strings live in resources; en/he parity is checked in CI.
- Help, trust and finish (PR H3, HELP-1…7): Help's long texts live in `core/i18n/.../values*/help.xml` (parity, `TerminologyTest` and `hebrew-review.py` read it) and must stay true to the code (`H3HelpTest` pins the facts): change a topic in the same PR as the behaviour. "Report a problem" and the crash card are KeepClear's own code, user-initiated, never automatic, and never carry a path or a file name (`ReportFacts`, `CrashText`). The activity history records only what really happened, never in test mode, and its total counts only space really freed. Launcher shortcuts only navigate (`ShortcutNavigator`).
- Platform (PR H4, PLAT-1…9): Android's "Manage space" opens `ManageSpaceActivity`, which never clears data or the trash index; the manifest's platform attributes, the no-boot-start rule and fully qualified names are checked on the merged manifest (`ManifestPolicy`). A backup is "stopped by the phone" only when `ApplicationExitInfo` says the system ended it (`KillReasons`). Battery help opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, never the restricted request. The notification permission is asked only from the action that needs it; channels live in `NotificationChannels` (ids never renamed). Hebrew is compared as `he` or `iw` (`Languages`); on Android 11–12 the in-app language is applied in `attachBaseContext` (`AppLanguage`). Never call Java 21 `List` methods (`removeFirst()`, `removeLast()`, …): `checkForbiddenApis` refuses them. The one mutable PendingIntent is the archive status (explicit, to a non-exported receiver, C178).

## Design rules ("Paper & Ink", ADR 0013, `docs/design/design-system.md`)

- New and rebuilt screens use only `core:designsystem` components (`KcButton`, `KcCard`, `LaneCard`, `StorageStrip`, `HeroNumber`, `ConfirmSheet`, `InfoSheet`, `PanelSheet`, `ResultBanner`, `DaysLeftChip`, `ThumbStrip`, `MediaTile`, `MonthHeader`, `KcSearchField`, `VerifiedStamp`, `InfoChip`, and the later `PanelSheet`, `NoticeBar` …) and theme tokens; no hard-coded colours, fonts or shapes in `app`.
- One ink colour (`primary`) for actions. Red (`error`, `KcButtonStyle.Destructive` / `OutlinedDestructive`, `ConfirmSheet(permanent = true)`) **only** for permanent, unrecoverable actions; going to the 30-day trash is not permanent. No parentheses in button labels (`ButtonLabelsTest`).
- Lanes (SAFE sage, REVIEW slate, MOVE amber) are always icon + label, never colour alone. Dynamic colour stays off by default.
- Sizes and counts: `ByteSize` (decimal) in Rubik with tabular figures (`KeepClearTheme.type.number` / `hero`).
- Stable Material 3 only (no Expressive beta, no new `@ExperimentalMaterial3Api` use); the main tabs use the stable `NavigationSuite` (material3-adaptive-navigation-suite). Motion respects `KeepClearTheme.reducedMotion` (a test scans every animation).
- Main actions (filled buttons, the confirm button) are 56 dp, every touch target ≥ 48 dp. Android 14+'s contrast setting picks `KeepClearContrastSchemes` (medium/high only ever stronger; `ContrastTest`).
- Contrast is tested (`ContrastTest`): text ≥ 4.5:1, large numbers/icons/outlines ≥ 3:1 in light and dark. Never lower a token to pass a test silently.
- No neon, rockets, brooms, shields, lightning, gauges or "boost".

## Hebrew voice rules (owner, 2026-11-02)

- KeepClear is grammatically masculine everywhere ("KeepClear מתחבר", never "מתחברת").
- Address the user in plural ("לכם", "בחרתם"), never singular.
- Buttons are infinitives (לשחזר, להתחבר, לבחור, למחוק, לדלג, לסגור), never nouns (שחזור, התחברות, בחירת…, מחיקה, דילוג, ביטול, סגירה). One owner exception (terminology 2026-11-03): every undo button (the swipe's, the result banner's) is „חזרה” / "Undo", with the auto-mirrored ↶ icon.
- "לשמור/להשאיר" means only "this time"; the permanent choice is "לא להציע את זה שוב".
- No jargon on the default path: NAS, smb://, .KeepClearTrash, F-Droid/GitHub, SHA-256 go to Details/Advanced.
- Curly Hebrew quotes „”. "יימחקו" with double yod.
- Store listing uses the same lane names as the app ("לנקות עכשיו" / "לבחור מה למחוק" / "לגבות"; owner brief H2 item 7: the REVIEW lane deletes, so it is "לבחור מה למחוק" with "שום דבר לא נמחק בלי שתבחרו"; PR H2c: the MOVE lane copies first, so it is "לגבות").
- One term per concept (owner brief H2 item 9, PR H2c; `TerminologyTest` checks every string and the store listing, table-driven, with an allow-list):

  | Concept | Hebrew | English | Never |
  |---|---|---|---|
  | Bytes really come back (rebuild/bin kinds, P1 free after a checked backup, emptying the trash) | לפנות מקום / לפנות 7.9GB | Free up space / Free up 7.9 GB | "Free now"; "לפנות" for a move to the trash |
  | Move to KeepClear's trash | להעביר לסל | Move to trash | להעביר לסל המחזור |
  | Permanent | למחוק לצמיתות | Delete for good | סופית, לתמיד, "permanently" |
  | Home's hero button (junk) | לפנות 7.9GB (the hero sentence's verb) | Free up 7.9 GB | לנקות 7.9GB on Home |
  | Copying memories | לגבות | Back up | להעביר לגיבוי, "Move to backup" |
  | Bring back / undo | לשחזר / חזרה | Restore / Undo | להחזיר, "put back" |
  | KeepClear's trash only | סל המחזור (chip: סל) | Trash | סל המחזור של אנדרואיד/KeepClear, "recycle bin" |
  | Other bins | סל הגלריה, סל של אפליקציה אחרת | the gallery's bin, another app's bin | סלי מחזור |
  | Thumbnails | תמונות ממוזערות | Thumbnails | תמונות תצוגה מקדימה |
  | USB drive (default path) | דיסק און קי | USB drive | כונן USB |
  | Details link | לראות פרטים | See details | לראות פירוט |
- A count with its size is always count first: „2,324 קבצים · 2.9GB” (`countAndSize` / `totalText`); an empty selection says „לא נבחר כלום”, never "0B". Places are shown in plain words (`HumanLocation`: drive label, folders joined with " › "), never raw paths.

## Code conventions

- Engines (`engine:*`, `core:model`, `core:safety`, `core:rules`, `core:hash`) are pure JVM: no `android.*` imports.
- Rules and filters are data (JSON/tables) with table-driven positive and negative tests.
- English is the default language of the repository (owner, 2026-11-04): `README.md`, every doc, ADR, spec, comment and commit is in professional English; Hebrew appears in docs only as quoted app strings. The Hebrew README is `README.he.md` (a full translation, linked from the top of `README.md`); change both together.
- Add `// SPDX-License-Identifier: GPL-3.0-or-later` to new files. Code reused from GPL-3.0/MIT/Apache projects keeps its copyright line and gets an entry in `THIRD_PARTY.md`. Never copy AGPL or GPL-2.0-only code, SD Maid SE icons/docs, or upstream translations.
- Restricted permissions go only in `app/src/foss/AndroidManifest.xml`.

## Formats and migrations

- Room: explicit migrations only, schemas committed, no destructive fallback (MIG-1).
- Sidecar, manifest and rules JSON are versioned; never rename stored enum values (MIG-2/3).

## Build and checks

- JDK 21, Android SDK with platform `android-37.0`. `./gradlew ciCheck` runs what CI runs (except emulator tests).
- `./gradlew -p build-logic test` tests the policy checkers.
- Screenshots: `./gradlew :app:recordRoborazziFossDebug` to re-record after an intended UI change; CI verifies them.
- Test APK: every push to `main` publishes a signed `fossDebug` APK as an Actions artifact (job "Test APK"), signed with the public test key in `keystore/` (never for releases, ADR 0009). No GitHub Releases.
- New dependency: add it to `gradle/libs.versions.toml`, then refresh `gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 ciCheck`, then run `scripts/verification-extras.py` (re-adds macOS/Windows aapt2 checksums) and review the diff. Runtime deps must pass the allowlist (PRIV-1).

## Git

- Small commits with clear messages. Run the build, unit tests and lint before pushing.
