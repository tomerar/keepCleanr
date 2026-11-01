# 09 · Accessibility-based per-app cache cleaner (foss only?)

Status: research, 2026-11-01. Proposal only: no code. Decision record: [ADR 0012](../adr/0012-foss-accessibility-cache-cleaner.md) (Proposed).
Builds on [04 §4](04-android-apis.md) (cache APIs), [05 §2](05-play-policy-and-flavors.md) (Play Accessibility policy), [01 §4](01-sdmaid-se.md) (SD Maid SE AppCleaner), [ADR 0008](../adr/0008-no-accessibility-no-network.md) and [ADR 0004](../adr/0004-privilege-tiers-shizuku-optional.md).

Legend: **[UNVERIFIED]** = not confirmed in a primary source or on a device.

**Licence note.** `com.github.bmx666.appcachecleaner` is **AGPL-3.0** (its `LICENSE` file; F-Droid lists "GNU Affero General Public License v3.0 only"). CLAUDE.md forbids copying AGPL code. This document describes its *approach* from its README, changelogs, manifest and file layout, read in a throwaway clone in the scratchpad (commit `21864c2`, 2026-10-24). No code, strings or translations from it may enter KeepClear. SD Maid SE is GPL-3.0 code (reusable with its copyright line and a `THIRD_PARTY.md` entry), but its docs and icons are excluded.

---

## 0. TL;DR

1. **What it does.** An AccessibilityService opens each app's *App info → Storage & cache* page in the Settings app and presses **Clear cache** for you, one app after another. Settings then calls `PackageManager.deleteApplicationCacheFiles`, which clears that app's internal *and* external cache. No special permission is needed apart from the Accessibility grant.
2. **New finding: SAFE-5 probably frees almost nothing on the owner's phone.** In AOSP, `allocateBytes` can only clear `cacheTotal − cacheReserved`, and the reserve is **2–10 % of the disk**. On a 240 GB phone with ~7 % free, that reserve is roughly 11 GB, more than the owner's 3.85 GB of cache. So the predicted SAFE-5 yield is **≈ 0 bytes** (§1.2). This prediction is what device check D7-3 should confirm.
3. **SAFE-6** (`ACTION_CLEAR_APP_CACHE`, foss + All files) clears **external** caches only (`Android/data/<pkg>/cache`). Internal caches (`/data/data/<pkg>/cache`, `code_cache`) need Shizuku/root (SAFE-7, now "Later") or a tap on "Clear cache" in Settings.
4. **The risk is concrete.** On AOSP, **"Clear storage"** (permanently deletes app data) sits in the same button row as **"Clear cache"**. On MIUI the flow *starts* by tapping "Clear data". OEM labels and layouts differ, Android 14 and 16 changed Settings, and sideloaded installs must pass "Allow restricted settings". Android 17 Advanced Protection blocks the service entirely.
5. **Recommendation: not in v1. "Later" in foss only, behind hard gates.** First ship a **guided manual** flow: KeepClear opens App info for the biggest caches and the user taps the button themselves. That flow needs no new permission and has zero misclick risk. Revisit Accessibility only if device data shows a large residual internal cache that people actually want cleared (§7).

---

## 1. How Accessibility cache cleaners work and what they add

### 1.1 Mechanism

| Step | What happens | Evidence |
|---|---|---|
| 1 | The user picks apps. The cleaner reads per-app cache sizes via `StorageStatsManager` (needs usage access) | appcachecleaner README "How to use" steps 1–4 ([README](https://github.com/bmx666/android-appcachecleaner/blob/main/README.md)); SD Maid SE `InaccessibleCacheProvider` ([01 §4](01-sdmaid-se.md)) |
| 2 | For each app it starts `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (`package:<pkg>`) | appcachecleaner scenario comment "open 'App Info' settings for specific package" (`DefaultClearScenario.kt`, approach only) |
| 3 | On `TYPE_WINDOW_STATE_CHANGED` it searches the node tree for the "Storage & cache" entry, scrolling the `RecyclerView` if needed, and clicks it | same; changelog 42 "fix find Storage menu if it was out of view", 49 "extra step to find Storage and Cache menu for Android 14" |
| 4 | On the storage page it finds **Clear cache** and calls `performAction(ACTION_CLICK)`. If the button is disabled (0 bytes, or size not yet computed) it skips | scenario steps 3.2.1–3.2.2 |
| 5 | Back, next app. An overlay shows progress and a Stop button | README step 6 "Accessibility overlay button to interrupt"; SD Maid SE `AutomationOverlay` ([01 §4](01-sdmaid-se.md)) |

**How labels are found.** Both open-source cleaners read the label at runtime from the Settings app's own resources, e.g. `getResourcesForApplication("com.android.settings")` → `clear_cache_btn_text` / `storage_settings_for_app`. They then fall back to bundled strings and user-entered "extra search text" (appcachecleaner `ExtraSearchTextHelper.kt`; SD Maid SE `OxygenOSSpecs` reads `storage_settings` the same way, [01 §4](01-sdmaid-se.md)). This is what makes it work in any language, Hebrew included, as long as the OEM keeps the AOSP resource names.

**Service config (appcachecleaner, for reference).** `packageNames="com.android.settings,com.miui.securitycenter"`, events `typeWindowStateChanged`, flags include `flagReportViewIds` and `flagRetrieveInteractiveWindows`, `canRetrieveWindowContent=true`, no gestures. The service is declared `exported="false"` with `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`. Its Android 14+ builds also run a `specialUse` foreground service (F-Droid permission list; changelog 85). That `exported="false"` works for binding by the system is a precedent from a shipping app, not something we tested **[UNVERIFIED for KeepClear]**.

**AOSP Settings facts** (primary source: [AppStorageSettings.java](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/applications/AppStorageSettings.java)):
- "Clear storage" (`button1`, `clear_user_data_text`) and "Clear cache" (`button2`, `clear_cache_btn_text`) sit **side by side in one `ActionButtonsPreference`** ([layout](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/ActionButtonsPreference/res/layout/settingslib_action_buttons.xml): `button1`…`button4`).
- **Clear cache has no confirmation dialog.** `handleClearCacheClick()` calls `deleteApplicationCacheFiles` directly.
- **Clear storage** shows the dialog `clear_data_dlg_title` with the positive button `dlg_delete`. **If the app declares `manageSpaceActivity`, Settings launches that app's own screen instead**, which an automation cannot predict.
- What node resource IDs those buttons expose on a device (`com.android.settings:id/button2`?) **[UNVERIFIED]**: SettingsLib is linked into Settings, so the package prefix needs a device dump.

AOSP strings that matter, from [values/strings.xml](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values/strings.xml) and [values-iw/strings.xml](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-iw/strings.xml) (read at runtime, never bundled):

| Resource | English | Hebrew | Role for us |
|---|---|---|---|
| `storage_settings_for_app` | Storage & cache | אחסון ומטמון | navigate |
| `clear_cache_btn_text` | Clear cache | ניקוי המטמון | **only allowed tap** |
| `clear_user_data_text` | Clear storage | מחיקת כל הנתונים מהאחסון | **deny** |
| `clear_data_dlg_title` | Delete app data? | למחוק את נתוני האפליקציה? | **deny / stop** |
| `dlg_delete` | Delete | מחיקה | **deny** |
| `force_stop` | Force stop | אילוץ עצירה | **deny** |
| `app_restricted_settings_lockscreen_title` | Allow restricted settings | הרשאה להגדרות מוגבלות | setup copy |

### 1.2 What it can clear that SAFE-5 and SAFE-6 cannot

| Mechanism | Scope | When | Limit |
|---|---|---|---|
| **SAFE-5** `allocateBytes(getAllocatableBytes)` | All apps, internal + external cache | When the system decides it needs the space | Can free only `cacheClearable = max(0, cacheTotal − cacheReserved)` ([StorageManagerService.getAllocatableBytes](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/StorageManagerService.java)). Without the hidden `FLAG_ALLOCATE_AGGRESSIVE`, `cacheReserved = computeStorageCacheBytes()`: **10 % of total** if > 20 % free, **2 %** if free < min(5 %, 500 MB), interpolated in between ([StorageManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/storage/StorageManager.java), defaults overridable by `DeviceConfig`) |
| **SAFE-6** `ACTION_CLEAR_APP_CACHE` (foss, All files) | All apps, **external** cache dirs only | On demand, one system dialog | Handled by MediaProvider ([manifest](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/AndroidManifest.xml)); javadoc: "clearing app external cache directories" ([StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager#ACTION_CLEAR_APP_CACHE)) |
| Settings "Clear cache" (manual or Accessibility) | One app, internal + external | On demand | One app per tap; no reserve applies |
| SAFE-7 Shizuku/root `freeStorageAndNotify` / per-app delete | All or one app | On demand | Needs ADB identity ([04 §4](04-android-apis.md)); now "Later" |

**Worked example (owner's OnePlus 10T: 240 GB, 93 % full, 3.85 GB app cache).** Free ≈ 16.8 GB, which lies between the 500 MB low and 48 GB high thresholds. Interpolation gives a reserve of ≈ 0.40 × 16.8 + 4.6 ≈ **11 GB**. Even the 2 % floor (4.8 GB) exceeds 3.85 GB. Prediction: **SAFE-5 frees ≈ 0 bytes of cache on this phone**, and on any phone whose total cache is below 2–10 % of its storage. This rests on AOSP defaults; OnePlus may override them through `DeviceConfig` **[UNVERIFIED]**, and D7-3 measures it. If confirmed, the SAFE-5 copy must say plainly that "Android kept its cache reserve; nothing to trim now".

What is left after SAFE-6 is the **internal** cache. How the owner's 3.85 GB splits between internal and external is unknown **[UNVERIFIED: measure with `StorageStats.getCacheBytes()` vs `getExternalCacheBytes()` (API 31)]**.

### 1.3 Measured or claimed benefits

- Neither appcachecleaner nor SD Maid SE publishes measurements. Their claims are qualitative ("replace the manual steps", README; "taps the 'Clear Cache' button for you", [SD Maid SE FAQ](https://github.com/d4rken-org/sdmaid-se/blob/main/docs/faq.md)).
- CCleaner's Android "hidden cache" cleaning is store-listing copy only ([APKMirror 4.8.0 notes](https://www.apkmirror.com/apk/piriform/ccleaner/ccleaner-4-8-0-release)). That it uses Accessibility for this today is **[UNVERIFIED]**.
- Owner's data: 3.85 GB of cache, regenerated within days ([ADR 0008](../adr/0008-no-accessibility-no-network.md)). Cache is a working set: clearing it costs re-downloads (mobile data), slower first opens and battery. That gain is temporary, and the store copy must not imply otherwise (no "boost").
- **Cache is not always disposable.** A user reported that Cache Cleaner "deleted the downloaded maps and the voice of my navigation app", adding "When you use it without thinking you can produce serious data loss" (irrlicht, 2024-03-24, [/e/ community](https://community.e.foundation/t/application-for-cleaning-cache-for-xiaomi-mi8/56493)). The app stored offline data in its cache. That is legal for the app, and still a real loss for the user.

### 1.4 Android 13–17 changes

| Version | Change | Effect | Source |
|---|---|---|---|
| 13 | **Restricted settings.** Accessibility and notification-listener grants are blocked for apps installed outside a session-based (store) installer. The user must open App info → ⋮ → "Allow restricted settings" | GitHub APK installs always need it. F-Droid-client installs (session API) were exempt on 13 **[UNVERIFIED for current F-Droid client]** | [Android Help 12623953](https://support.google.com/android/answer/12623953); [ThreatFabric](https://www.threatfabric.com/blogs/droppers-bypassing-android-13-restrictions); [BleepingComputer](https://www.bleepingcomputer.com/news/security/malware-devs-already-bypassed-android-13s-new-security-feature/); appcachecleaner README "Warning" |
| 14 | Settings App-info layout changed (Storage entry needs an extra step or a scroll). A platform bug (Google issue 322519674) interrupts long runs; workaround "go back after every 25 apps". FGS needed | More special-casing | appcachecleaner changelogs 47, 49, 70, 85, 88, 97 |
| 15 | Reports of a wider restricted-settings scope (CDD: on by default) and "Enhanced Confirmation Mode" with a factory allowlist, which may remove the session-installer loophole | F-Droid installs may also need the unlock step **[UNVERIFIED]** | [Kaspersky](https://www.kaspersky.com/blog/android-15-new-security-and-privacy-features/51311/); [Android Authority](https://www.androidauthority.com/android-15-enhanced-confirmation-mode-3436697) |
| 16 | Advanced Protection Mode arrives; SD Maid SE says some OEM builds block enabling, or clean incompletely, and recommends Shizuku instead | Partial breakage | [SD Maid SE FAQ](https://github.com/d4rken-org/sdmaid-se/blob/main/docs/faq.md); appcachecleaner changelog 108 "support Android 16" |
| 17 | Advanced Protection blocks AccessibilityService for non-`isAccessibilityTool` apps and revokes existing grants | Feature unavailable for APM users | [05 §2.3](05-play-policy-and-flavors.md); [Android Authority](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860); appcachecleaner changelogs 109–110 |

The appcachecleaner maintainer has put the project in **maintenance-only** mode "for as long as Google keeps the Accessibility Service usable for this purpose" (README). Its GitHub issue tracker currently shows no issues (README links to #8, which returns 404), so no issue history could be read **[UNVERIFIED why]**.

### 1.5 OEM differences

| OEM / ROM | Settings package and path | Labels | Notes |
|---|---|---|---|
| AOSP / Pixel | `com.android.settings`: App info → "Storage & cache" → "Clear cache" | AOSP strings above | The baseline; the emulator has it, so it is testable |
| Samsung One UI | `com.android.settings`, own layout: App info → "Storage" → "Clear cache" / "Clear data" **[UNVERIFIED]** | OEM strings; whether they keep AOSP resource names is **[UNVERIFIED]** | SD Maid SE has a OneUI spec ([specs/](https://github.com/d4rken-org/sdmaid-se/tree/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/automation/specs)) |
| OnePlus OxygenOS 13+ (ColorOS base) | `com.android.settings` with ColorOS screens: "Storage usage" → "Clear cache" **[UNVERIFIED on 10T]** | SD Maid SE reads `storage_settings` at runtime and, on API 34+, walks up to the clickable parent ([01 §4](01-sdmaid-se.md)) | Owner's device; needs a node dump |
| Xiaomi MIUI / HyperOS | **`com.miui.securitycenter`** app manager. Regional builds: tap **"Clear data"** → menu "Clear all data" / "Clear cache" → OK dialog | `app_manager_clear_cache` in securitycenter | appcachecleaner has a separate "Xiaomi MIUI" scenario that **opens the Clear-data menu first** (scenario steps 3–6). README: Huawei/Xiaomi battery savers break the service |
| Huawei, Honor, Realme, Vivo, etc. | Various | Various | SD Maid SE keeps ~18 ROM specs; appcachecleaner tells users to type in custom labels |

**Locale.** Runtime resource lookup handles Hebrew on AOSP-like Settings. When an OEM renames a resource, both apps fall back to bundled strings or user-typed text. That is where misclicks become possible, for example if a user types a label that also matches "Clear data". RTL does not change node matching, but scroll direction and "walk up to the clickable parent" logic must not assume left-to-right order **[UNVERIFIED]**.

### 1.6 Fragility (summary)

Each piece of the flow is a new place for it to break: the Settings package (AOSP vs securitycenter), the screen sequence (one or two levels), scrolling, resource names, button nesting (clickable parent), dialogs (MIUI), timing (cache size "computing…" leaves the button disabled), and battery killers (Huawei/Xiaomi). Platform releases also break it: appcachecleaner shipped explicit "support Android 14/15/16/17/17.2" releases (changelogs 47, 85, 108–111), roughly one adaptation per platform release.

---

## 2. Risks

| Risk | Severity | Why | Mitigation if built |
|---|---|---|---|
| **Misclick → "Clear storage" / "Clear data"** | **Critical** (permanent app-data loss: logins, offline files, chats of apps without backup, 2FA seeds) | The button is in the same row on AOSP. The MIUI flow starts at "Clear data". With `manageSpaceActivity` the Clear-storage tap opens an arbitrary app screen. User-typed labels are unchecked | Tap only a node that matches the runtime `clear_cache_btn_text` **and** matches no deny label, with ID and parent checks. **Never** tap inside any dialog. **No MIUI-style flows**. No user-typed labels (§4.2) |
| Data in cache that the user values | High | Navigation maps and voices (§1.3), offline media in some apps | Per-app opt-in, never pre-selected (rule 7); a hint naming known heavy-cache categories; default exclusions such as SD Maid SE's banking/2FA list ([01 §4](01-sdmaid-se.md)) |
| **Security / power** | High | The grant lets an app read the screen and tap anywhere. It is a malware favourite, which is why restricted settings exist. A bug or a compromised build could act in other apps | `packageNames` restricted to the Settings package. Service inert unless a user-started run is active. No `canPerformGestures`. No INTERNET (ADR 0008), so nothing read can leave the phone. Reproducible builds |
| **User trust and reputation** | High | The product promise is "nothing scary". Users meet a red "Restricted setting" screen and an "Allow restricted settings" warning that says to trust the developer. AV heuristics flag Accessibility plus broad storage access (SD Maid SE FAQ). appcachecleaner was flagged PUP by AhnLab (over a leaked certificate, README). Repackaged clones of accessibility cleaners circulate (appcachecleaner README) | Strictly optional. Off by default. Never asked for during onboarding. Honest copy (§4) |
| Maintenance burden | High | Per Android version × OEM × locale; ~1 fix per platform release (§1.6); SD Maid SE keeps ~18 specs | Support AOSP-like Settings only; anything else gets "not supported on this phone" plus the guided manual flow |
| Testability | Medium–High | Real Settings UIs exist only on devices; Robolectric cannot render them; OEM screens can't be emulated | Pure-JVM matcher over recorded node trees (table-driven, property tests). Emulator instrumented test (AOSP, en + he). Device checks per OEM |
| Policy | Play: blocking. F-Droid: none | §3 | foss-only source set + manifest check |
| Platform lock-out | Medium | Android 17 APM; future restrictions | Detect and explain; the feature degrades to guided manual |

---

## 3. Policy

### 3.1 Google Play: never in `play`

The [Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491) lists "**cleaners**" explicitly among apps that are *not* accessibility tools. Non-tools need in-app prominent disclosure with affirmative consent, a Play Console declaration, and a **video** of the disclosure, consent and non-consent flows. Autonomous agent-like use is prohibited; "deterministic, rule-based automation … 'If Trigger X occurs, perform Action Y'" is allowed. A user-started "tap Clear cache in these N apps" probably fits that, but it adds a declaration, review risk, and account-level risk for a cleaner. SD Maid's account was terminated in 2023 under a different policy ([05 §1.5](05-play-policy-and-flavors.md)). KeepClear's `play` flavor stays Accessibility-free, so it never needs the declaration.

### 3.2 F-Droid

- F-Droid's [Anti-Features list](https://f-droid.org/docs/Anti-Features/) has **no** anti-feature for Accessibility or UI automation.
- Precedent: appcachecleaner is in F-Droid main with **no anti-features** ([F-Droid listing](https://f-droid.org/packages/com.github.bmx666.appcachecleaner/), v2.3.0, 2026-06-10). Accessibility services do not appear in F-Droid's permission list; the listing shows usage stats, query-all-packages and FGS `specialUse`.
- No inclusion-policy obstacle was found ([inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)). An explicit F-Droid statement on Accessibility cleaners is **[UNVERIFIED]**.
- Restricted settings: whether F-Droid-client installs need "Allow restricted settings" on Android 15+ is **[UNVERIFIED]** (§1.4). GitHub/Obtainium APK installs do need it.

### 3.3 Build-logic changes (if accepted; not done now)

Today `ManifestPolicy.check` fails **every** flavor on any `<service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">` ("AccessibilityService is not allowed (ADR 0008)"), and `ManifestPolicyTest` asserts it. A foss-only exception would need all of the following:
1. `play` keeps the hard failure, unchanged. Add a test proving that the merged `play` manifest still fails if a library leaks the service.
2. In `foss`, allow exactly **one** named service class (allowlist by FQCN), with `exported="false"` (so SEC-5 is untouched) and `permission=BIND_ACCESSIBILITY_SERVICE`.
3. A second check that parses its `accessibility-service` XML: `packageNames` must equal the allowlisted Settings package(s); `canPerformGestures` absent or false; no `isAccessibilityTool`; no `flagRequestTouchExplorationMode`, `flagRequestFilterKeyEvents` or `canRequestFilterKeyEvents`; event types limited to window-state and content changes.
4. `INTERNET` stays forbidden everywhere (ADR 0008), which is part of the safety argument.
5. Konsist: `AccessibilityNodeInfo.performAction` only in the foss automation module; the matcher lives in a pure-JVM module; nothing in `main` references the service.
6. `docs/PRIVACY.md` documents the service (PRIV-6), and CLAUDE.md's "No AccessibilityService" product rule is amended by the ADR.

---

## 4. Explaining it to users; opt-in flow and safeguards

### 4.1 Opt-in flow (foss only, never during onboarding)

1. **Entry point:** on the Apps/Cache screen, after SAFE-5/SAFE-6 have run, KeepClear shows what is left: "Still in app caches: ⁨1.9 GB⁩ in ⁨34⁩ apps". Two actions follow: **"Open each app myself"** (guided manual, always available) and a secondary **"Let KeepClear tap 'Clear cache' for me…"**.
2. **Explainer** (copy in §4.3) with "Turn on" / "No thanks". "No thanks" is remembered and never nagged.
3. **Support check** before asking for anything: Settings package recognised; `clear_cache_btn_text` *and* every deny label resolve from its resources; Advanced Protection off. If any check fails, show "Not supported on this phone" and offer the guided manual flow.
4. **Grant:** deep link to Accessibility settings. If the grant is blocked, show the restricted-settings help (§4.3) with a button to KeepClear's App info.
5. **Dry run (calibration), mandatory** on first use and whenever the device fingerprint changes (Settings `versionCode`, Android build, locale, or a new OEM skin). KeepClear walks 3 apps and **taps nothing**. It highlights the button it *would* tap and asks "Is the highlighted button 'Clear cache'?" (Yes / No). A "No" disables the feature for this fingerprint.
6. **Select apps:** list sorted by cache size, **nothing pre-selected** (safety rule 7), with a "Select the 10 largest" helper. Default exclusions (banking/2FA, navigation/offline-maps apps flagged with a note, KeepClear, Settings).
7. **Confirm** with exact count and bytes plus the SAFETY-7 label ("Cleared by Android, cannot be restored, the app rebuilds it").
8. **Run** in the foreground only: an overlay (`TYPE_ACCESSIBILITY_OVERLAY`, no extra permission) shows "app 3 of 10" and a large **Stop** button. Back, Home, screen-off, a phone call or any user touch stops the run.
9. **Report** measured per-app before/after (`StorageStats`), skipped apps with the reason, and the total actually freed. Then suggest turning the service off, with a button that calls `disableSelf()`.

### 4.2 Hard safeguards (all mandatory)

| # | Safeguard |
|---|---|
| G1 | **Single allowed action:** `ACTION_CLICK` on a node whose normalised text equals the runtime `clear_cache_btn_text` of the *foreground* Settings package, and nothing else. No coordinates, no gestures, no `ACTION_SCROLL` except on the App-info list to reveal "Storage & cache" |
| G2 | **Deny list wins:** if the candidate, its clickable ancestor, or any sibling *within the candidate's clickable ancestor* has text equal to `clear_user_data_text`, `dlg_delete`, `force_stop`, `clear_data_dlg_title`, or the OEM's clear-data label → **stop the run** |
| G3 | **Uniqueness and placement:** exactly one match, enabled, inside the action-buttons container. Resource ID in a per-ROM allowlist when the ROM exposes one. App names are untrusted input: an app named "Clear cache" appears as header text, so header nodes are never candidates (threat model, SEC-*) |
| G4 | **Right app:** the page header shows the target app's label, the window belongs to the allowlisted Settings package, and the window title matches `storage_settings_for_app` / `storage_label`. Otherwise go back and skip the app |
| G5 | **No dialogs, ever:** if any dialog window appears after a tap → press Back, stop the run, report. This excludes MIUI's Clear-data → Clear-cache menu flow by design |
| G6 | **No guessed labels:** no user-typed or bundled fallback labels. If the runtime resource is missing, the phone is "not supported" |
| G7 | **Inert by default:** the service ignores all events unless an in-memory run token exists, created by the user's confirm tap in the foreground and expiring at the end of the run or after a timeout. No background, scheduled or WorkManager trigger (safety rule 1) |
| G8 | **Stop on mismatch, timeout or anything unexpected** (fail closed). Skipping an app is fine; guessing is never fine |
| G9 | **Re-check before acting** (safety rule 6): re-read the app's cache size just before opening its page; skip apps that have 0 bytes or have since been uninstalled |
| G10 | **Dry run per fingerprint** (§4.1 step 5); calibration result stored locally |
| G11 | **Supported matrix:** start with AOSP-like Settings only (Pixel/emulator, then OxygenOS after a node dump on the 10T). Other ROMs are "not supported" until each one has fixtures and a device check |

### 4.3 Draft copy (English / Hebrew; Hebrew uses gender-neutral plural or infinitive; wrap numbers and app names in bidi isolates)

**Entry action**
- EN: "Let KeepClear tap “Clear cache” for me…"
- HE: "לתת ל-KeepClear ללחוץ על „ניקוי המטמון”…"

**Explainer: title**
- EN: "Clear each app's cache for you (optional)"
- HE: "ניקוי המטמון של כל אפליקציה (לא חובה)"

**Explainer: body**
- EN: "KeepClear can open each app's storage page in Settings and tap “Clear cache”, one app at a time. It is the same button you can tap yourself.
  To do this, Android needs you to turn on an accessibility permission for KeepClear. This permission is powerful: it lets an app read what is on the screen and tap buttons. KeepClear uses it only inside the Settings app, only while a cleanup you started is running, and only to tap “Clear cache”. It never taps “Clear storage” or “Clear data”. KeepClear has no internet access, so nothing it reads can leave your phone.
  What you get: about ⁨1.9 GB⁩ in ⁨34⁩ apps. Apps rebuild their cache as you use them, so this space usually fills up again within days. Some apps keep offline maps or downloads in their cache. Leave those apps unselected.
  You can stop at any time with Stop or Back."
- HE: "KeepClear יכולה לפתוח בהגדרות את מסך האחסון של כל אפליקציה, אחת אחרי השנייה, וללחוץ על „ניקוי המטמון”. זה אותו כפתור שאפשר ללחוץ עליו גם ידנית.
  לשם כך צריך להפעיל עבור KeepClear הרשאת נגישות. זו הרשאה חזקה: היא מאפשרת לאפליקציה לקרוא את מה שמוצג במסך וללחוץ על כפתורים. KeepClear משתמשת בה רק בתוך אפליקציית ההגדרות, רק בזמן ניקוי שהתחלתם, ורק כדי ללחוץ על „ניקוי המטמון”. היא אף פעם לא לוחצת על „מחיקת כל הנתונים מהאחסון” או „ניקוי נתונים”. ל-KeepClear אין גישה לאינטרנט, ולכן שום דבר שהיא קוראת לא יוצא מהטלפון.
  מה מקבלים: בערך ⁨1.9 GB⁩ ב-⁨34⁩ אפליקציות. אפליקציות בונות את המטמון מחדש תוך כדי שימוש, ולכן המקום הזה בדרך כלל מתמלא שוב תוך כמה ימים. יש אפליקציות ששומרות במטמון מפות או הורדות לשימוש בלי אינטרנט. כדאי לא לבחור אותן.
  אפשר לעצור בכל רגע עם „עצירה” או „חזרה”."

**Buttons**
- EN: "Turn on and continue" / "No thanks"
- HE: "להפעיל ולהמשיך" / "לא, תודה"

**Restricted-settings help**
- EN: "Android blocked this because KeepClear was not installed from an app store. To allow it: open KeepClear's App info, tap ⋮, then “Allow restricted settings”. Only do this if you trust KeepClear. You can also skip this and clear caches yourself."
- HE: "Android חסמה את ההגדרה הזו כי KeepClear לא הותקנה מחנות אפליקציות. כדי לאפשר: לפתוח את פרטי האפליקציה של KeepClear, ללחוץ על ⋮ ואז על „הרשאה להגדרות מוגבלות”. כדאי לעשות זאת רק אם סומכים על KeepClear. אפשר גם לוותר ולנקות את המטמון ידנית."
  (Use the runtime Settings label `app_restricted_settings_lockscreen_title`; the AOSP Hebrew is "הרשאה להגדרות מוגבלות". OEM wording may differ **[UNVERIFIED]**.)

**Dry-run question**
- EN: "Is the highlighted button “Clear cache”? KeepClear has not tapped anything."
- HE: "האם הכפתור המסומן הוא „ניקוי המטמון”? KeepClear עדיין לא לחצה על שום דבר."

**Stopped on mismatch**
- EN: "Stopped. The button on screen did not match “Clear cache”, so KeepClear tapped nothing in ⁨<app>⁩. Nothing was deleted."
- HE: "נעצר. הכפתור במסך לא תאם ל„ניקוי המטמון”, ולכן KeepClear לא לחצה על שום דבר ב-⁨<app>⁩. שום דבר לא נמחק."

**Result**
- EN: "Cleared ⁨1.7 GB⁩ of cache in ⁨31⁩ apps (measured). Skipped ⁨3⁩ apps. Apps will rebuild their cache as you use them. Turn off KeepClear's accessibility permission now?"
- HE: "נוקו ⁨1.7 GB⁩ של מטמון ב-⁨31⁩ אפליקציות (לפי מדידה). ⁨3⁩ אפליקציות דולגו. האפליקציות יבנו את המטמון מחדש תוך כדי שימוש. לכבות עכשיו את הרשאת הנגישות של KeepClear?"

**Guided manual (the alternative, no permission)**
- EN: "Open each app's storage page. Tap “Clear cache” there, then come back. KeepClear will show how much was freed."
- HE: "לפתוח את מסך האחסון של כל אפליקציה, ללחוץ שם על „ניקוי המטמון” ולחזור. KeepClear תראה כמה מקום התפנה."

---

## 5. Testability plan (if built)

- **Pure-JVM matcher** (`engine:*` style, no `android.*`): input is a serialised node tree (`text`, `viewId`, `className`, `clickable`, `enabled`, `bounds`, children) plus the resolved label set; output is `Tap(nodePath) | Navigate | Skip(reason) | Stop(reason)`. Table-driven positive and negative tests per ROM and locale (en, he, plus one non-Latin LTR).
- **Property test (Kotest):** for any generated tree, the matcher never returns `Tap` on a node whose text, or whose clickable ancestor's text, is in the deny set. It never returns `Tap` when two candidates match, or when any dialog window is present.
- **Fixtures:** node dumps from real devices, captured by a debug-only "export screen tree" in dry-run, reviewed by hand and stripped of personal data. They are treated as untrusted input (threat model).
- **Instrumented** (emulator, AOSP Settings, en and he): a test app with a known cache, run → measured cache = 0, and app data intact (sentinel file in `filesDir` still present). A negative test sets a locale whose label is unresolvable → no tap.
- **Device checks:** OnePlus 10T (OxygenOS 15/16) en + he before enabling OxygenOS; each further ROM needs its own.

---

## 6. Decision matrix

Scores: 1 = poor, 5 = good. Weights reflect KeepClear's safety-first rules (CLAUDE.md).

| Criterion (weight) | A. SAFE-5 + SAFE-6 only | B. A + guided manual (deep link per app, user taps) | C. A + Accessibility automation (foss, opt-in) | D. A + Shizuku (SAFE-7, "Later") |
|---|---|---|---|---|
| Internal cache actually freed (×3) | 1 (SAFE-5 ≈ 0 when cache < reserve) | 4 (top-N apps hold most of it **[UNVERIFIED]**) | 5 | 5 |
| User effort (×1) | 5 | 2–3 (one tap + back per app) | 4 after setup | 2 (ADB pairing) |
| Data-loss risk (×3) | 5 | 5 (user taps the button) | 2 (adjacent "Clear storage", OEM drift) | 4 (API call, no UI) |
| Security / trust (×2) | 5 | 5 | 2 (powerful grant, AV flags, restricted-settings warning) | 3 (ADB-level helper app) |
| Setup friction (×1) | 5 | 5 | 2 (grant + restricted settings + dry run) | 1 |
| Maintenance (×2) | 5 | 4 | 1 (per OEM × version × locale) | 4 |
| Testability (×1) | 5 | 4 | 2 | 3 |
| Policy (×1) | 5 | 5 | 3 (foss only; Play never) | 4 |
| Future-proof (×1) | 5 | 5 | 2 (Android 17 APM blocks it) | 3 |
| **Weighted total (max 75)** | **63** | **66–67** | **40** | **54** |

A scores well only because it is safe and cheap; it fails the actual goal. B beats C on every safety criterion and loses only on effort. C's advantage over B is convenience, and its costs are a critical data-loss mode plus a permanent maintenance stream.

---

## 7. Recommendation

1. **Not in v1, in any flavor.** ADR 0008 stays in force. Never in `play`.
2. **v1 instead:** (a) run D7-3 and adjust SAFE-5 copy to the reserve finding. (b) Measure internal vs external cache on the 10T. (c) Add a **guided manual "Clear cache" flow**: per-app deep link to `ACTION_APPLICATION_DETAILS_SETTINGS`, sorted by size, measured delta on return. ADR 0008 already allows deep links; this needs a requirement ID in `requirements.md` when it is scheduled.
3. **"Later" candidate (ADR 0012, Proposed):** an opt-in foss-only Accessibility cleaner, built only if **all** of these gates hold:
   - G-a: after B ships, device data shows a typical residual internal cache of ≥ 1 GB that users actually clear;
   - G-b: the owner accepts the maintenance cost;
   - G-c: safeguards G1–G11 and the tests in §5 are in the same PR (safety rule 8);
   - G-d: the build-logic exception in §3.3 is in place.
   Scope at first: AOSP-like Settings and OxygenOS only; no MIUI flow, ever.
4. If Shizuku (SAFE-7) returns to the plan first, it covers the same need with less risk; C is then probably unnecessary.

## Open questions

- D7-3 actual SAFE-5 yield on the 10T, and OnePlus `DeviceConfig` overrides of `cache_reserve_percent_*`.
- Internal vs external split of the owner's 3.85 GB (`getExternalCacheBytes`).
- Do F-Droid-client installs need "Allow restricted settings" on Android 15/16?
- Node resource IDs of the Clear-cache button on AOSP and OxygenOS 15/16; does OxygenOS keep `clear_cache_btn_text`?
- Is `exported="false"` sufficient for an AccessibilityService on API 30–37 (appcachecleaner precedent only)?
