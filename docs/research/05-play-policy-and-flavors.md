# 05 - Google Play policy, F-Droid rules, and distribution flavors

Status: research, verified 2026-10-31. Scope: which permissions/APIs KeepClear can ship on Google Play vs F-Droid/IzzyOnDroid/GitHub, and the resulting build flavors.

Legend: **[UNVERIFIED]** = could not confirm from a primary source. "Owner said X; actually Y" = correction of `context.md`.

---

## TL;DR

| Topic | Verdict |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (All files access) on Play | Allowed only for listed core use cases (file managers, backup & restore, antivirus, document mgmt, on-device search, encryption, device migration). **"Cleaner" is not listed** (neither allowed nor banned by name). Precedent: SD Maid SE's Play build *does* ship it; WhatsAppCleaner's author removed it from Play ("Play forbids MANAGE_EXTERNAL_STORAGE for this use case"). High rejection risk; plan Play v1 without it. |
| AccessibilityService on Play | Play explicitly says **cleaners are not accessibility tools**. Non-tools need in-app prominent disclosure + affirmative consent + Play Console declaration + video. Autonomous agent-like use is banned; deterministic user-triggered automation is allowed. Plus Android 13+ restricted settings (sideload) and Android 17 Advanced Protection block. **Skip in v1 in all flavors.** |
| `QUERY_ALL_PACKAGES` | Permitted examples: device search, antivirus, file managers, browsers. Must not be used if a narrower `<queries>` works. Cleaner not listed -> avoid on Play; use `<queries>` for launcher apps. |
| `PACKAGE_USAGE_STATS`, `REQUEST_DELETE_PACKAGES` | Not on Play's restricted-permission declaration list. Usage access still needs user-facing justification under User Data policy. |
| Foreground service | Each FGS type (e.g. `dataSync`) needs a Play Console declaration: description, user impact, **video**. |
| Data safety | Form mandatory even with zero collection; privacy policy required. On-device processing is not "collection". |
| Target API | API 36 (Android 16) required for new apps and updates since 2026-08-31 (extension to 2026-11-01). |
| F-Droid | FOSS only, built from source, no GMS/Firebase. Shizuku as an *optional* dependency is fine (SD Maid SE, Hail have no NonFreeDep); Canta (requires Shizuku) carries **NonFreeDep**. |
| Developer verification | Enforced 2026-09-30 in BR/ID/SG/TH; global 2027. Unregistered apps installable only via ADB or the "advanced flow" (24h wait). Register the package + signing key; prefer reproducible builds so F-Droid ships our signature. |
| Recommended flavors | `play` (SAF + MediaStore + optional Shizuku, no all-files, no accessibility, no QUERY_ALL_PACKAGES) and `foss` (+ all-files, + root/Shizuku full paths). Same codebase, capability-gated features. |

---

## 1. All files access (`MANAGE_EXTERNAL_STORAGE`)

Source: Play Console Help "Use of All files access permission" https://support.google.com/googleplay/android-developer/answer/10467955 and Permissions policy https://support.google.com/googleplay/android-developer/answer/9888170

### 1.1 Permitted uses (Play's list)
- File management (core purpose = managing files/folders outside app-specific storage)
- Backup and restore apps that must automatically access multiple directories
- Anti-virus apps whose core purpose is scanning the device
- Document management apps (must justify why privacy-friendly alternatives fail)
- On-device search across external storage
- Disk or folder encryption and locking
- Device migration / phone transfer

Developer docs list the same set: https://developer.android.com/training/data-storage/manage-all-files

### 1.2 Invalid uses
- Media access -> use MediaStore.
- User-picked files -> use SAF.
- "Core functionality is defined as the main purpose of the app" and must be prominently documented in the store description (10467955).
- "Apps may not request access to device storage on behalf of any third-party for any purpose unrelated to core functionality" (9888170).

### 1.3 Storage cleaners
- **Not mentioned at all** on the policy page (no "cleaner", "junk", "storage cleaning"). So a cleaner must argue it is a *file manager* or *backup & restore* app.
- Declaration: Play Console Permissions Declaration Form, justify why SAF/MediaStore are insufficient; resubmit if usage changes (10467955).
- **Video**: the All-files page does **not** mention a video requirement (unlike Accessibility and FGS). **[UNVERIFIED]** whether the form UI currently asks for one.
- Undeclared/deceptive use -> removal/suspension (10467955).

### 1.4 Real-world precedents
| App | Play build has all-files? | Evidence |
|---|---|---|
| SD Maid SE (`eu.darken.sdmse`) | **Yes.** `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`, `REQUEST_DELETE_PACKAGES`, AccessibilityService are all in the shared `main` manifest; the `gplay` flavor only adds billing/review/updater code. | https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/AndroidManifest.xml , https://github.com/d4rken-org/sdmaid-se/blob/main/app/build.gradle.kts (flavors `foss`/`gplay`), gplay sources under https://github.com/d4rken-org/sdmaid-se/tree/main/app/src/gplay/java/eu/darken/sdmse/common |
| SD Maid SE FAQ | "There is currently no functional difference between the Google Play and the `FOSS` version" | https://github.com/d4rken-org/sdmaid-se/blob/main/docs/faq.md |
| WhatsAppCleaner (`com.vishnu.whatsappcleaner`) | **No.** Separate `play` build type (`applicationIdSuffix ".play"`, `IS_PLAY=true`) falls back to SAF; manifest comment: "Google Play forbids MANAGE_EXTERNAL_STORAGE for this use case". | https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/src/release/AndroidManifest.xml , https://github.com/VishnuSanal/WhatsAppCleaner/blob/main/app/build.gradle.kts |
| Generic devs (B4X forum) | Rejected: "access to the memory was not necessary", told to use MediaStore. | https://www.b4x.com/android/forum/threads/api-30-manage_external_storage-problem.137214/ |

Interpretation: SD Maid SE is a long-established, multi-tool app (file browser/"Analyzer", app manager) that can credibly claim file-management core functionality. A new single-purpose "cleaner" is the weak case. Approval reasoning for SD Maid SE is not public **[UNVERIFIED]**.

### 1.5 Enforcement anecdote: SD Maid removal (Aug 2023)
- 2023-08-22: Google removed SD Maid 1 and SD Maid 2/SE citing the **Stalkerware** policy and terminated the developer's 12-year account ("pattern of high risk or abuse"). Nox Cleaner was removed the same week (reason unclear). Sources: https://www.tuttoandroid.net/news/2023/08/22/sd-maid-rimossa-play-store-stalkerware-1008873/ , https://futurezone.at/apps/google-systemreiniger-apps-play-store-geloescht-entfernt-nox-sd-maid/402566318
- SD Maid SE is on Play again today (README Play badges, https://github.com/d4rken-org/sdmaid-se/blob/main/README.md). Date/mechanism of reinstatement **[UNVERIFIED]**.
- Lesson: cleaners with broad permissions are flagged by automated review; a single Play account holding all of a developer's apps is a single point of failure. Keep GitHub/F-Droid channels alive.

### 1.6 What all-files does and does not give
From https://developer.android.com/training/data-storage/manage-all-files :
- Grants: read/write all shared storage, `MediaStore.Files` table, SD/OTG roots.
- Does **not** grant other apps' `Android/data/` (and most of `/sdcard/Android`): "Apps that are granted this permission still can't access the app-specific directories that belong to other apps".
- With all-files **+** `QUERY_ALL_PACKAGES`, an app can call `StorageManager.getManageSpaceActivityIntent()` (API 31+) to open another app's "manage space" screen. Owner's claim **confirmed**.
- `StorageManager.ACTION_CLEAR_APP_CACHE` (API 30+): "Allows the user to free up space by clearing app **external** cache directories ... shows a dialog and lets the user decide ... Requires `MANAGE_EXTERNAL_STORAGE`". https://developer.android.com/reference/android/os/storage/StorageManager#ACTION_CLEAR_APP_CACHE . This is a one-dialog, all-apps external-cache clear with no Accessibility, but only in builds with all-files.
- Owner said all-files on Play has been accepted for "file managers/backup/on-device search since May 2021": **correct**; dev docs: "This policy is in effect as of May 2021."

### 1.7 Alternatives (no all-files)
| Need | Alternative |
|---|---|
| Camera/screenshots/WhatsApp images & videos | MediaStore + `READ_MEDIA_IMAGES/VIDEO` (Play "Photo and Video Permissions" declaration; see 1.8) or Photo Picker; delete/trash via `MediaStore.createDeleteRequest`/`createTrashRequest` (system confirm dialog). |
| `Android/media/com.whatsapp`, `/sdcard/MIUI`, old `/sdcard/WhatsApp`, file-manager recycle bin folders | SAF `ACTION_OPEN_DOCUMENT_TREE` per folder (user grants once; persistable). Android 13+ blocks `Android/data` and `Android/obb` trees (WhatsAppCleaner's Play build relies on SAF for `Android/media`). SD Maid SE docs on Files app SAF blocking: https://github.com/d4rken-org/sdmaid-se/blob/main/docs/setup.md |
| Other apps' cache | Shizuku/ADB or root (both flavors); `ACTION_CLEAR_APP_CACHE` (foss only); per-app settings deep link (manual). |

### 1.8 Photo and Video permissions (relevant for Play flavor)
Source: https://support.google.com/googleplay/android-developer/answer/14115180
- `READ_MEDIA_IMAGES/VIDEO` allowed only when "core functionality revolves around broad access to Photos and Videos"; explicit example: gallery apps. Declaration required; custom pickers do not qualify automatically. Fully enforced since 2025-05-28.
- Must degrade gracefully when the user denies (picker flow).
- KeepClear's MOVE (back up whole camera roll -> verify -> delete) and duplicate/similar detection need broad media access: argue "media organizer/backup". Acceptance for a cleaner **[UNVERIFIED]**; forum reports say photo backup tools and media organizers are the accepted cases (https://community.appinventor.mit.edu/t/read-media-images-read-media-video/159748).

---

## 2. AccessibilityService

Source: https://support.google.com/googleplay/android-developer/answer/10964491

### 2.1 Rules
- `isAccessibilityTool` restricted to apps whose primary purpose is supporting people with disabilities. Explicitly **not** tools: "antivirus software, automation tools, assistants, monitoring apps, **cleaners**, password managers, and launchers."
- Non-tools need: in-app prominent disclosure (not only in description/privacy policy), affirmative user action to accept, a *separate* disclosure saying why the API is needed; `android:description` of the service is not a substitute.
- Play Console declaration since 2021-11-03 (target API 31+). Re-submit when usage changes.
- **Video**: app opening, full disclosure, consent and non-consent flows, and a core feature using the API.
- Automation: "Any use of the Accessibility API that enables an app to autonomously initiate, plan, and execute actions or decisions" is "strictly prohibited"; deterministic, user-defined "If Trigger X, perform Action Y" automation is allowed.
- Deceptive/undeclared use -> app suspension and/or developer account termination.
- Permissions policy summary: "Implement clear in-app disclosure and obtain user consent if not a designated accessibility tool" (https://support.google.com/googleplay/android-developer/answer/9888170). Older Play notices about "changing user settings without permission / circumventing privacy controls" are reported by press (https://www.techradar.com/news/new-google-play-store-policy-cracks-down-on-call-recording-apps); exact current wording **[UNVERIFIED]**.

### 2.2 Is automating "Clear cache" taps allowed?
- Not addressed by name. It is user-initiated, deterministic, on-device, collects nothing -> fits the allowed "deterministic automation" pattern, provided disclosure + declaration + video are in place.
- SD Maid SE does exactly this on Play: FAQ "SD Maid SE uses the Accessibility Service to automate cache cleaning. On modern Android, there is no API for apps to clear another app's cache directly ... taps the 'Clear Cache' button for you" (https://github.com/d4rken-org/sdmaid-se/blob/main/docs/faq.md).
- Its disclosure strings: "SD Maid will not use the accessibility service without your consent, will always display a prominent (cancelable) indicator while active and will not use it to collect information." Consent buttons "Enable service and grant access" / "Don't use accessibility service". https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/res/values/strings.xml
- Service config: `canRetrieveWindowContent=true`, `canPerformGestures=true`, no `isAccessibilityTool` https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/res/xml/accessibility_service.xml

### 2.3 Platform headwinds (beyond Play)
| Issue | Effect | Source |
|---|---|---|
| Android 13+ "restricted settings" | Sideloaded apps (GitHub/F-Droid) cannot enable Accessibility until user unlocks "Allow restricted settings" in App info. SD Maid SE shows a dedicated hint. | https://support.google.com/android/answer/12623953 ; SD Maid string `setup_acs_appops_restriction_body` (strings.xml above) |
| Android 17 Advanced Protection Mode | Blocks AccessibilityService for non-`isAccessibilityTool` apps; existing grants revoked. SD Maid SE declares `QUERY_ADVANCED_PROTECTION_MODE` to explain this. | https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html , https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860 , https://github.com/d4rken-org/sdmaid-se/blob/main/app/src/main/AndroidManifest.xml |
| Android 16 OEM builds | SD Maid FAQ: some devices block enabling, cache cleaning incomplete; recommends Shizuku instead. | https://github.com/d4rken-org/sdmaid-se/blob/main/docs/faq.md |
| AV false positives | Heuristics flag apps with Accessibility + broad storage. | same FAQ |

### 2.4 Risk assessment for KeepClear
App cache = 3.85 GB on the owner's phone (context.md), about 4% of the reachable ~97 GB. The value doesn't justify: an extra Play declaration + video, explicit "cleaners are not tools" language, Advanced Protection lockout, restricted-settings friction, and AV flags. **Recommend: no AccessibilityService in v1 (any flavor)**; clear caches via Shizuku/root, `ACTION_CLEAR_APP_CACHE` (foss), or guided manual per-app deep links.

---

## 3. `QUERY_ALL_PACKAGES`

Source: https://support.google.com/googleplay/android-developer/answer/10158779
- Installed-app inventory is "personal and sensitive information".
- Permitted examples: **device search, antivirus apps, file managers, browsers**. Without it the app must be "broken".
- Invalid: uses not tied to core purpose, data sale, sharing for analytics/ads, "When the required task can be done with a less broad app-visibility method."
- Declaration form required; deceptive use -> suspension/termination.
- **Cleaner not listed.** KeepClear needs: list user apps, sizes, last use, uninstall. A `<queries>` element with `ACTION_MAIN`/`CATEGORY_LAUNCHER` covers user-launchable apps; system/launcher-less packages are out of scope for a cleaner anyway. -> **Play: no `QUERY_ALL_PACKAGES`.** `foss`: allowed (needed for `getManageSpaceActivityIntent`, leftover-folder ("corpse") detection that maps folders to uninstalled packages).
- SD Maid SE ships both `QUERY_ALL_PACKAGES` and `com.android.permission.GET_INSTALLED_APPS` on Play (manifest above). Same caveat as 1.4: established app.

---

## 4. Other permissions and declarations

| Item | Play status | Notes / source |
|---|---|---|
| `PACKAGE_USAGE_STATS` (usage access) | Not in the restricted-permission list of the Permissions policy | https://support.google.com/googleplay/android-developer/answer/9888170 (not covered). Needed for `StorageStatsManager` sizes of other apps and "unused 6 months". User grants in Special app access; disclose why in-app (User Data policy, prominent disclosure if data is personal) **[UNVERIFIED that Play reviewers require a disclosure screen for usage access]**. |
| `REQUEST_DELETE_PACKAGES` | Not restricted by Play (normal permission, API 26+) | Not in 9888170 list. Uninstall shows system confirmation per app. Contrast `REQUEST_INSTALL_PACKAGES`, which *is* restricted (9888170). |
| Foreground service types (target 34+) | Declare each type on App content page: functionality description, user impact if deferred/interrupted, **video** of user triggering the feature, use case | https://support.google.com/googleplay/android-developer/answer/13392821 . `dataSync` "can include transferring data locally or over a network"; for user-requested network transfers Play recommends the **user-initiated data transfer job** instead; backup videos "cannot show fully automatic or background backup". All types subject to review. |
| `dataSync` runtime limit | Android 15 caps `dataSync` FGS at ~6 h per 24 h for apps targeting 35+ | https://developer.android.com/about/versions/15/behavior-changes-15#datasync-timeout **[UNVERIFIED exact wording; well-known change]** |
| `INTERNET` | No declaration; triggers nothing by itself | F-Droid: only network *to proprietary services* earns NonFreeNet (sec. 6). Hail's F-Droid recipe strips INTERNET at build: `prebuild: sed -i -e '/INTERNET/d'` https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.aistra.hail.yml |
| Data safety form | Mandatory for every app, "developers that do not collect any user data must complete this form"; privacy policy required | https://support.google.com/googleplay/android-developer/answer/10787469 |
| Data safety: what is "collection" | "transmitting data from your app off a user's device"; local-only processing exempt; uploads to the user's own cloud account governed by the provider's terms exempt if "your app never collects or accesses the data"; user-initiated transfers exempt from "sharing" | same page |
| Target API | API 36 for new apps + updates from 2026-08-31 (extension to 2026-11-01). API 37 expected Aug 2027 **[projection]** | https://support.google.com/googleplay/android-developer/answer/11926878 |
| Developer verification (Play side) | "register any remaining apps by September 30, 2026 to avoid global removal from Google Play"; Play auto-registers most apps | https://android-developers.googleblog.com/2026/06/android-developer-verification.html , https://developer.android.com/developer-verification |

Data-safety implication for KeepClear: if backups go through SAF to a DocumentsProvider (Drive, Nextcloud, SMB-provider app, USB), KeepClear itself does **no** network I/O and can omit `INTERNET` entirely -> "No data collected" is straightforward and verifiable from the manifest. Built-in SMB/WebDAV clients would need `INTERNET` but still are user-initiated transfers to the user's own server (likely exempt; **[UNVERIFIED]** for self-hosted servers since the FAQ names "their own drive or cloud account").

---

## 5. Misleading claims / Device & Network Abuse (cleaner/booster risks)

- Deceptive Behavior policy, "Misleading claims": no false/misleading info in title, description, icon, screenshots; no impossible functionality; apps "are prohibited from mimicking other apps or OS warnings"; no ads mimicking system notifications. https://support.google.com/googleplay/android-developer/answer/9888077
- The current page has **no cleaner/"boost"/RAM/battery-specific example** (earlier Play guidance on fake boosters is often cited by blogs, **[UNVERIFIED]** in current text). Fake "phone is infected/overheating" warnings would violate the "mimicking OS warnings" clause.
- Device & Network Abuse: no interfering with "other apps on the device"; apps not eligible for allowlisting must not bypass power management; FGS only as long as needed, prefer WorkManager; no self-updating outside Play; no downloading executable code. https://support.google.com/googleplay/android-developer/answer/9888379
- Stalkerware precedent (sec. 1.5): keyword-based automated review hit a cleaner's description ("browse", "detect", "manipulate" per commenters, https://www.tuttoandroid.net/news/2023/08/22/sd-maid-rimossa-play-store-stalkerware-1008873/). **[UNVERIFIED]** cause.

Store-listing rules for KeepClear (derived):
- Never claim RAM/CPU "boost", battery saving, speed-up or "virus" cleaning. KeepClear only frees storage.
- Show measured bytes; no fabricated "junk found" numbers; no system-styled alarm notifications.
- Avoid wording that reads like monitoring ("track", "monitor activity", "detect usage of apps"); describe usage access as "find apps you haven't opened in months".
- Play build must not contain a self-updater (SD Maid's gplay uses a Play update checker, foss its own: https://github.com/d4rken-org/sdmaid-se/tree/main/app/src/gplay/java/eu/darken/sdmse/common/updater).

---

## 6. F-Droid and IzzyOnDroid

### 6.1 F-Droid inclusion policy
Source: https://f-droid.org/docs/Inclusion_Policy/
- All apps FLOSS; built from source by F-Droid with a FLOSS toolchain.
- Banned: "proprietary tracking or advertising libraries and analytics tools such as Google Play Services and Firebase and Crashlytics"; need a flavor without them.
- Binary deps only from source or trusted repos (Maven Central, Google Maven for AndroidX etc.).
- "Apps which fail to rebuild (such as requiring Play Services) or contain undisclosed anti-features will receive a rejection."

### 6.2 Anti-features
Source: https://f-droid.org/docs/Anti-Features/
| Anti-feature | Meaning | KeepClear risk |
|---|---|---|
| NonFreeNet | Promotes/depends entirely on a proprietary network service | Only if a built-in Google Drive/Photos API client is added. SAF-based backup avoids it. |
| NonFreeDep | "applied to apps that require things that are not Free Software in order to run" | Avoid by keeping Shizuku optional (below). |
| NonFreeAdd | Promotes non-free apps/plugins | Don't push Google Photos/Drive apps. |
| Tracking | Tracks/reports activity without permission or by default | None (no analytics, no crash reporter by default). |
| NonFreeAssets | Non-free media | Our icons must be FOSS-licensed. SD Maid SE gets NonFreeAssets because its icons are not GPL. |
| Ads | Ads | None. |

### 6.3 How comparable apps are listed (fdroiddata metadata, verified 2026-10-31)
| App | F-Droid main | IzzyOnDroid | Anti-features | Shizuku | Notes |
|---|---|---|---|---|---|
| SD Maid SE `eu.darken.sdmse` | yes (2.2.2-rc0) | yes | **NonFreeAssets only** | optional | Builds `foss` flavor; `Binaries:` field = **reproducible**, F-Droid ships developer-signed APK. https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/eu.darken.sdmse.yml |
| Canta `io.github.samolego.canta` | yes (3.2.2) | yes | **NonFreeDep** ("Depends on Shizuku for functionality"), **NonFreeNet** ("Connects to Github as app info data source") | **required** | https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/io.github.samolego.canta.yml |
| Hail `com.aistra.hail` | yes (1.11.0) | yes | none | optional | INTERNET stripped at build. https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.aistra.hail.yml |
| WhatsAppCleaner `com.vishnu.whatsappcleaner` | yes (v1.4.1) | no | - | no | F-Droid build keeps all-files. |
| Shizuku app `moe.shizuku.privileged.api` | **no** | yes | - | - | Hence "requires Shizuku" => NonFreeDep (Shizuku not in F-Droid main) **[UNVERIFIED rationale]**; Shizuku-API library itself is Apache-2.0/MIT and buildable. |

Package presence checked via `https://f-droid.org/api/v1/packages/<id>` and `https://apt.izzysoft.de/packages/<id>` on 2026-10-31.

### 6.4 Reproducible builds
Source: https://f-droid.org/docs/Reproducible_Builds/
- With `Binaries:` + `AllowedAPKSigningKeys`, F-Droid publishes the upstream-signed APK only if its own build is byte-identical (except signature).
- Pitfalls: JDK mismatch, `baseline.prof` non-determinism (SD Maid SE pins CPU count; Canta loops rebuilds), timestamps, VCS info; release from a clean tagged commit.
- Benefit: one signing key across GitHub + F-Droid + IzzyOnDroid; users can switch sources without reinstall; matters for developer verification (sec. 7).
- Disable `dependenciesInfo` in the FOSS build (Google-encrypted blob), as SD Maid SE does: https://github.com/d4rken-org/sdmaid-se/blob/main/app/build.gradle.kts

### 6.5 IzzyOnDroid
Source: https://apt.izzysoft.de/fdroid/index/info
- Ships "official binaries built by the original application developers" (faster than F-Droid's build queue).
- FOSS required; scanners flag trackers/proprietary libs; must be updated within last 12 months; signing key pinned at first inclusion; self-updaters only if opt-in.
- Good first channel while F-Droid main inclusion (and reproducibility) is being set up.

---

## 7. Android developer verification (sideloading) - status 2026-10-31

Sources: https://developer.android.com/developer-verification , https://android-developers.googleblog.com/2026/06/android-developer-verification.html , https://9to5google.com/2026/08/18/google-gradually-rolling-out-androids-advanced-sideloading-ahead-of-developer-verification/ , F-Droid open letter https://f-droid.org/en/2026/02/24/open-letter-opposing-developer-verification.html

| Date | Event |
|---|---|
| Aug 2026 | Developer Console API, free **limited distribution accounts** (up to 20 devices, no ID/fee), and the power-user **advanced flow** launched. |
| 2026-09-30 | Protections active in **Brazil, Indonesia, Singapore, Thailand** on certified Android 7+ devices. Official wording: "for all users who install apps from participating stores" (Play, HONOR, OPPO, Samsung, Transsion, vivo, Xiaomi). |
| 2027 | "expand these protections globally for all apps on certified Android devices". |

- Advanced flow: Developer options -> "Allow apps from unverified developers" -> warnings -> one-time **24 h wait** -> enable for 7 days or indefinitely; ADB installs unaffected (9to5google).
- Official dev page mentions an **open-source registry** path ("register both existing and new open source apps"); details not reviewed **[UNVERIFIED]**.
- Fee for non-Play registration: secondary sources say one-time US$25 + government ID **[UNVERIFIED]**. Multiple signing keys per package **[UNVERIFIED]**.
- Ambiguity: whether F-Droid/browser sideloads in the 4 countries are already gated in 2026 or only from 2027 is not clear from Google's wording ("participating stores") vs press ("sideloading") **[UNVERIFIED]**.

Impact on KeepClear:
- F-Droid normally signs with **its own key** -> the app would be attributed to F-Droid, not us. Reproducible builds keep **our** key on F-Droid/IzzyOnDroid/GitHub, so one registration covers all non-Play channels.
- Register `io.github.tomerar.keepclear` (decided ID, was placeholder `app.keepclear`) + FOSS signing key in Android Developer Console before global enforcement (2027). Play-signed build is auto-registered via Play Console.
- Expect users outside Play to need ADB or the advanced flow if we don't register; document both.

---

## 8. Recommended flavors

### 8.1 Flavor definitions
| Flavor | Channels | Signing | Manifest adds |
|---|---|---|---|
| `foss` | GitHub Releases, IzzyOnDroid, F-Droid main (reproducible) | our key | `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`, `REQUEST_DELETE_PACKAGES`, Shizuku provider, FGS `dataSync` |
| `play` | Google Play | Play App Signing | `READ_MEDIA_IMAGES/VIDEO` (+ declaration), `PACKAGE_USAGE_STATS`, `REQUEST_DELETE_PACKAGES`, `<queries>` launcher intent, Shizuku provider (optional), FGS type(s) with declaration |

- Put restricted permissions in `src/foss/AndroidManifest.xml`, never in `main` (WhatsAppCleaner pattern); add a CI check on the merged `play` manifest that fails on `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `BIND_ACCESSIBILITY_SERVICE` (libraries can leak permissions, see B4X/bubble forum reports above).
- Application ID: SD Maid SE uses **one ID** for both flavors (different keys -> uninstall to switch); WhatsAppCleaner uses `.play` suffix (both installable side by side). Recommend **one ID** (`foss` and `play` are alternatives, not companions); revisit if Play Console registration conflicts with the FOSS key **[UNVERIFIED]**.
- Neither flavor ships GMS, Firebase, analytics or ads. `play` may later add Play Billing only if donations are added (SD Maid SE pattern), nothing else.
- Feature code is shared; runtime capability detection (`isExternalStorageManager()`, Shizuku binder alive, root) decides which engines are offered.

### 8.2 Feature x flavor matrix (owner's measured data in brackets)
| Feature | `foss` | `play` (v1) | Mechanism in `play` |
|---|---|---|---|
| Camera MOVE: backup -> verify -> delete [75.2 GB] | Yes | Yes | MediaStore + READ_MEDIA_* declaration (fallback: Photo Picker); `createTrashRequest/createDeleteRequest` system dialog; SAF destination |
| Large videos list [14.85 GB] | Yes | Yes | MediaStore query |
| WhatsApp received/sent media [42.3 GB] | Yes (direct path, `Android/media`) | Yes | SAF tree on `Android/media/com.whatsapp/WhatsApp/Media` (WhatsAppCleaner Play precedent); databases never touched |
| Old `/sdcard/WhatsApp`, `/sdcard/MIUI`, ShareMe [5.4 GB] | Yes, auto-detected | Partial | SAF tree grant per folder, guided |
| File-manager recycle bin [3.8 GB] | Yes | Partial | SAF grant; hidden `.Trash`-style dirs may need all-files **[UNVERIFIED]** |
| Screenshots / ChatGPT images / extracted zips | Yes | Yes (media) / Partial (zips need SAF) | MediaStore + SAF |
| Duplicates / similar photos | Yes | Yes | MediaStore |
| App sizes + unused apps [2.8 GB] | Yes | Yes | Usage access + `StorageStatsManager` + launcher `<queries>`; uninstall via `REQUEST_DELETE_PACKAGES` |
| Clear all apps' external cache in one dialog | Yes (`ACTION_CLEAR_APP_CACHE`) | No | - |
| Clear internal app cache [3.85 GB] | Shizuku/root | Shizuku (optional) / manual deep link | Shizuku on Play is precedent (SD Maid SE FAQ links Shizuku's Play listing) |
| Leftover folders of uninstalled apps | Yes (QUERY_ALL_PACKAGES) | Limited | Only for known launcher apps |
| `getManageSpaceActivityIntent` (open app's "manage space") | Yes | No | - |
| `Android/data`, `Android/obb` of other apps | Shizuku/root only | Shizuku only | Blocked for all-files and SAF on 13+ |
| AccessibilityService automation | **No (v1)** | **No** | - |
| In-app 30-day trash | Yes | Yes (system MediaStore trash for media; app trash for SAF files) | - |
| Network | None (SAF-based backup) | None | Omit `INTERNET` in both if feasible |

### 8.3 Possible later Play upgrade
If v1 Play gains traction, file a single All-files declaration framing KeepClear as **backup & restore + file management** (MOVE flow automatically reads multiple directories; built-in folder browser), with store description emphasizing those as core. Keep `play` fully functional without it so a rejection costs nothing.

---

## Implications for KeepClear

### Adopt
- Two flavors `foss` / `play` from day one; restricted permissions only in `src/foss/AndroidManifest.xml`; CI guard on merged `play` manifest.
- `play` v1 without all-files, accessibility, `QUERY_ALL_PACKAGES`; MediaStore + SAF + usage access + optional Shizuku.
- SAF-based backup destinations so neither flavor needs `INTERNET`; Data safety = no data collected; privacy policy anyway.
- Target API 36 now; plan API 37 by Aug 2027.
- Declare FGS types and record the required videos; prefer WorkManager / user-initiated data transfer jobs where they fit.
- Reproducible `foss` builds (pinned JDK, deterministic baseline profile or disabled), `dependenciesInfo` off, publish to IzzyOnDroid first, then F-Droid main.
- Shizuku strictly optional (avoids NonFreeDep); FOSS-licensed icons (avoid NonFreeAssets).
- Register package + FOSS signing key under Android developer verification before the 2027 global rollout; document ADB/advanced-flow install.
- Store copy: storage only, measured numbers, no "boost/speed/battery/virus", no OS-style warnings, no monitoring vocabulary.

### Avoid
- AccessibilityService in v1 (Play calls cleaners non-tools; Android 17 APM blocks; restricted settings; AV flags; low GB payoff).
- Any Google SDK (Firebase, Crashlytics, Play Services) in `foss`; self-updater in `play`.
- Mixing all apps under one Play account without off-Play channels (SD Maid 2023 account termination).
- Built-in proprietary cloud clients (NonFreeNet + Data safety burden).

### Open questions
- Will Play approve `READ_MEDIA_IMAGES/VIDEO` for a MOVE/backup-centric cleaner, or force Photo Picker (which breaks "back up the whole roll")? Needs a test submission on an internal track.
- Is a later All-files declaration as "backup & restore" realistic for a non-file-manager?
- Developer verification: fee, multi-key per package, open-source registry details, and whether F-Droid sideloads in BR/ID/SG/TH are already gated.
- Single vs suffixed applicationId across flavors under verification.
- Does SAF still grant `Android/media` trees on Android 16/17 across OEM Files apps (SD Maid notes Files-app version differences)?
