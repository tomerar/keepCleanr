# 07 · Competitor UX review: trust, fear, and dark patterns in storage cleaners

Status: research. Date: 2026-10-31. Method: web research (official help pages, policy pages, press, security-vendor reports) plus a local read of SD Maid SE strings.
Anything that could not be confirmed from a primary or credible secondary source is marked **[UNVERIFIED]**.

---

## 0. TL;DR

- The category has a **trust deficit it earned**: malware posing as junk cleaners (McAfee, 2022), click-injection fraud in Cheetah's "Clean Master" family (2018), "fleeceware" subscription traps (Sophos 2019/2020, Avast 2021), and Avast's (CCleaner's parent) $16.5M FTC order for selling browsing data (2024).
- The **most trusted patterns come from platform owners** (Google Photos, Files by Google, iOS): they say exactly what will be removed, point to where the copy still lives, and keep a recoverable trash.
- Even the good ones fail at **count + size before action**, **verifiable backup status**, and **unified undo** (Samsung and Android each have several separate bins).
- KeepClear can stand out by being the cleaner that is **boring, exact, and reversible**: real bytes and file counts, plain-language "why", a 30-day in-app trash, no boost claims, no upsell.

---

## 1. Per-competitor review

### 1.1 Files by Google (Clean tab)

| Aspect | Findings |
|---|---|
| What it does | Clean tab of suggestion cards: junk files, duplicates, screenshots, downloads, large files, "memes", WhatsApp media, unused apps, "suggested pictures", plus "Smart Storage" ([Google help](https://support.google.com/files/answer/10862356?hl=en)). |
| Does well | Card per category with one clear action. Opt-in **Smart Storage** auto-deletes photos *already backed up to Google Photos*, only if they have been on the device 60 days or storage is under 25%. Pixel / Android 12 only ([Google help](https://support.google.com/files/answer/10862356?hl=en)). Trash keeps files 30 days ([Google help](https://support.google.com/files/?p=FBG_Bin)). Safe Folder is PIN-encrypted ([Google blog](https://blog.google/company-news/inside-google/around-the-globe/google-asia/making-privacy-personal-files-google/)). |
| Breeds fear | Reportedly, junk cleared from the Clean tab does **not** go to Trash, so it cannot be restored ([devx guide](https://www.devx.com/how-tos/how-to-empty-trash-on-android-3/), **[UNVERIFIED]** by Google docs). Smart Storage says "permanently delete" and runs in the background, so the user never sees which photos went ([XDA](https://www.xda-developers.com/files-by-google-app-smart-storage-automatically-delete-backed-up-media/)). Resetting the Safe Folder PIN deletes everything in it ([Safe Folder FAQ](https://support.google.com/files/community-guide/241022676/files-by-google-safe-folder-faq?hl=en)). |
| Notable pattern | **Backup-gated deletion** (it trusts Google Photos' backup flag). **Age threshold** (60 days) before auto-deleting. Category cards that each name the file type, not "junk" in general. |

### 1.2 Google Photos "Free up space"

| Aspect | Findings |
|---|---|
| What it does | Removes the on-device copies of photos and videos that are already backed up. The library stays viewable in the app and on the web ([Google help 6128843](https://support.google.com/photos/answer/6128843?hl=en)). |
| How it "verifies" backup | It relies on its own per-item backup state (Google has the server-side copy). Google's page tells users "you must ensure that you've already backed up your photos properly" first, and says items **younger than 30 days may be kept** on the device ([Google help 6128843](https://support.google.com/photos/answer/6128843?hl=en)). Users can check each item via **More → Details → backup status** ([Google help 6128858](https://support.google.com/photos/answer/6128858?hl=en)). There is no user-visible checksum or count reconciliation. |
| Does well | Says clearly "This feature deletes photos from your device", and says where the copy still lives. Local-only items are never touched ([Android Central](https://androidcentral.com/how-free-space-google-photos-free-space-feature); [9to5Google](https://9to5google.com/2023/03/29/google-photos-free-space/)). |
| Breeds fear | Afterwards, photos are **gone from the phone's built-in gallery, unavailable offline, and invisible when signed out** ([Google help 6128843](https://support.google.com/photos/answer/6128843?hl=en)). Users who tapped it by accident have asked how to get thousands of photos back ([community Q](https://yomotherboard.com/?p=503088)). Google does not document an undo for the free-up action itself. Deleting *inside* Photos deletes from **all** synced devices, which confuses people who expect "Free up space" to behave the same way ([Google help 6128858](https://support.google.com/photos/answer/6128858?hl=en)). Trash retention changed from 60 days to 30 days for backed-up items, and many guides are now wrong ([Android Authority](https://androidauthority.com/google-photos-trash-retention-change-apk-teardown-3703109); current text in [Google help 6128858](https://support.google.com/photos/answer/6128858?hl=en)). |
| Notable pattern | **"Copy lives elsewhere" framing**: delete only what has a verified copy elsewhere, and keep the newest items. KeepClear's MOVE flow is the same idea but must work for *any* backup target, so verification has to be explicit (file count + bytes, ideally hashes). |

### 1.3 CCleaner for Android (Piriform → Avast → Gen Digital)

| Aspect | Findings |
|---|---|
| History | Avast acquired Piriform in July 2017. In September 2017 a CCleaner **Windows** build shipped with a supply-chain backdoor ([Wikipedia](https://en.wikipedia.org/wiki/CCleaner)). In 2018 the Windows 5.45 "Active Monitoring" release could not be switched off and opted users into data collection. Avast pulled it, then split the toggles and renamed the feature "Smart Cleaning" ([CSO](https://www.csoonline.com/article/565979/avast-pulls-ccleaner-version-that-lacked-privacy-options-after-backlash.html); [BetaNews](https://betanews.com/2018/08/30/ccleaner-improved-privacy)). In December 2018 the installer bundled Avast Antivirus ([Wikipedia](https://en.wikipedia.org/wiki/CCleaner)). In 2024 the parent company Avast received an FTC order: **$16.5M** and a ban on selling browsing data, over Jumpshot's sales of browsing data from 2014 to 2020 ([The Record](https://therecord.media/avast-fine-ftc-alleged-browser-data-sales); [Hunton](https://www.hunton.com/privacy-and-cybersecurity-law-blog/ftc-announces-16-5-million-settlement-against-uk-service-provider-and-ban-from-selling-browsing-data-for-advertising-purposes)). |
| Correction | Brief said "CCleaner Android 2016-2018 controversies … Play policy/fines". **Actually**, the Active Monitoring, backdoor and bundling controversies were about the **Windows** product, and the FTC fine was against **Avast** over Jumpshot (desktop browser extensions and AV), not CCleaner Android. No Google Play fine against CCleaner was found. The reputational spillover onto the Android brand is real, but it was not caused by an Android incident. |
| Modern Android UX | Free tier with ads, aggressive paywall, and review prompts that interrupt tasks, per review-aggregator summaries ([marlvel.ai](https://marlvel.ai/apps/ccleaner-phone-cleaner/vs); [Technipages 2019](https://www.technipages.com/review/10-best-android-phone-cleaner-apps-in-2019/)). **[UNVERIFIED]**: these are low-authority aggregators, so the specifics should be checked against current Play reviews. |
| Breeds fear | Brand association with data selling. Lessons from the "Active Monitoring" episode: telemetry and background monitoring that re-enables itself is exactly what drives users away. |
| Notable pattern | Renaming "Active Monitoring" to "Smart Cleaning" so users would not "freak out" ([BetaNews](https://betanews.com/2018/08/30/ccleaner-improved-privacy)) is an **anti-pattern**: the fix was a euphemism, not a capability change. |

### 1.4 SD Maid SE (open source, GPL-3.0)

| Aspect | Findings |
|---|---|
| Does well | **Risk tagging** on CorpseFinder results: chips "Desirable" ("may contain data you want to keep … e.g. photos or backups") and "Common" (folder names shared by many apps), both off by default in settings ([strings.xml](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-corpsefinder/src/main/res/values/strings.xml)). The **Deduplicator keeps at least one copy** by default ("At least one file per duplicate set must remain"), and an explicit settings switch is needed to allow "delete all" ([strings.xml](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-deduplicator/src/main/res/values/strings.xml)). It shows only items it can actually delete, and its FAQ says the goal is "not to delete the most files, but the right files" ([FAQ](https://github.com/d4rken-org/sdmaid/wiki/Frequently-Asked-Questions)). |
| Breeds fear | **No trash or undo**: deletion is permanent, and the FAQ points users to data-recovery tools ([FAQ](https://github.com/d4rken-org/sdmaid/wiki/Frequently-Asked-Questions)). Generic confirmations ("Delete %d selected items?", "Delete all app remnants?") give **no size or "what happens"** ([common strings](https://github.com/d4rken-org/sdmaid-se/blob/main/app-common/src/main/res/values/strings.xml)). It is a power-user tool: jargon such as "Corpses", "AppCleaner", "Squeezer", "Arbiter", plus ADB/root/Shizuku setup. AppCleaner automation is "a bit brittle" because it matches UI text that varies by language and device ([wiki](https://github.com/d4rken/sdmaid-public/wiki/Appcleaner)). It was pulled from Play in August 2023 under the stalkerware policy along with Nox Cleaner, and the developer's account was terminated ([futurezone](https://futurezone.at/apps/google-systemreiniger-apps-play-store-geloescht-entfernt-nox-sd-maid/402566318); [Xataka](https://www.xatakandroid.com/play-store/google-elimina-popular-sd-maid-su-play-store-motivo-que-no-tiene-mucho-sentido)). It is listed on Play again now ([sdmse.darken.eu](https://sdmse.darken.eu/)). The reinstatement date is **[UNVERIFIED]**. |
| Reddit/GitHub UX complaints | No Reddit threads on "confusing/scary" were reachable via search. A GitHub issue search for trash/undo found no explicit recycle-bin request ([issues query](https://github.com/d4rken-org/sdmaid-se/issues?q=is%3Aissue+recycle+bin+OR+trash+OR+undo)). The density critique is the owner's opinion and is **[UNVERIFIED]** by user data. |
| Notable pattern | **Risk chips + safe defaults + "keep one" invariant.** Copy the semantics, not the jargon. The Play takedown is a warning: a cleaner whose description says "monitor/scan/system" plus broad permissions can trip automated stalkerware heuristics ([futurezone](https://futurezone.at/apps/google-systemreiniger-apps-play-store-geloescht-entfernt-nox-sd-maid/402566318)). |

### 1.5 Samsung Device Care / OnePlus-Oppo-realme Phone Manager

| Aspect | Findings |
|---|---|
| Samsung | Settings → Battery and device care → Storage shows Recycle bin(s), **Duplicate files** and **Large files** lists, with select-then-Delete ([Samsung UK](https://www.samsung.com/uk/support/mobile-devices/how-to-clear-up-storage-space-on-your-smartphone)). "Clean now" / memory screen **stops background apps**, which is a RAM action next to storage UI ([Samsung CA](https://www.samsung.com/ca/support/mobile-devices/how-to-remove-unnecessary-files-and-close-background-apps-on-your-samsung-galaxy-phone/)). |
| Samsung fear trigger | **Several separate bins** (Gallery, My Files, Google Photos) with no unified view, so "I deleted it but space didn't free up" ([techgorilla](https://thetechgorilla.com/how-to-empty-trash-on-samsung-phone/)). This directly matches the owner's 3.8 GB in the file-manager bin. Also, a "Clean now" that kills apps blurs the line between storage and performance. |
| ColorOS / OxygenOS / realme | Phone Manager → Clear up storage → Clean. "Deep cleanup" groups photos, videos, audio, documents and apps. Optional **auto-cleanup every day / every 3 days**. Cache cleanup "without affecting your chat history" ([realme](https://www.realme.com/ph/support/kw/doc/2073692); [realme](https://realme.com/in/support/kw/doc/2053428)). |
| OEM fear trigger | Scheduled auto-clean with no per-run report. Whether OEM cleaners route deletions to a bin is **[UNVERIFIED]**. |
| Notable pattern | OEM tools are trusted by default (pre-installed, no ads), but they are **shallow**: no backup-then-delete, and no explanation per item. |

### 1.6 Apple iOS (patterns, not a competitor)

| Pattern | Findings |
|---|---|
| Recently Deleted | Photos are recoverable for **30 days**. In iOS 16+ the album is locked behind Face ID / Touch ID ([Apple 124460](https://support.apple.com/124460)). |
| Offload Unused Apps | "Frees up storage used by the app, but **keeps its documents and data**". Reinstalling restores the app ([Apple 108429](https://support.apple.com/en-us/108429)). |
| Storage recommendations | iPhone Storage lists recommendations, with "Enable" or "tap to review the contents" ([Apple 108429](https://support.apple.com/en-us/108429)). |
| Takeaway | **Reversible by design**. Each recommendation says what is kept, not just what is removed. Android equivalent: archive apps (Android 15 `requestArchive`, covered in other docs) and point users to `getManageSpaceActivityIntent`. |

### 1.7 Avast Cleanup and Norton Clean (security-vendor cleaners)

| Aspect | Avast Cleanup | Norton Clean |
|---|---|---|
| What it does | Junk + photo cleanup. Photo review asks for confirmation "if it isn't 100% sure" a photo is bad ([HomePage Media](https://my.homepage.net/news/avast-cleanup-review), **[UNVERIFIED]**) | Junk, residual files, "optimizing device memory", app management / bloatware ([Norton support](https://support.norton.com/sp/en/us/norton-mobile/all/solutions/v117873343); [Norton community](https://community.norton.com/t/introducing-norton-clean-1-0-for-android/234073)) |
| Monetization | Free with in-app purchase / Premium tiers ([Avast](https://www.avast.com/es-cl/cleanup-android)) | Free version plus paid tier and trial (aggregator data, **[UNVERIFIED]**) |
| Breeds fear | Avast's parent company was fined over data sales ([FTC coverage](https://therecord.media/avast-fine-ftc-alleged-browser-data-sales)). The marketing frames cleaning as "boosting performance" ([Avast blog via Security Boulevard](https://securityboulevard.com/2018/06/how-avast-cleanup-boosts-your-phones-performance-avast-blog)) | "Optimizing memory" is a RAM-boost claim that Android does not need ([Digit test](https://www.digit.in/features/mobile-phones/tested-do-memory-cleaning-apps-really-work-30612.html)) |
| Notable pattern | Uncertainty-aware photo review (asks when unsure) is a good pattern | Brand trust borrowed from security products, which encourages cross-selling the AV suite |

Both Avast and Norton now belong to Gen Digital ([Gen newsroom](https://newsroom.gendigital.com/2021-03-24-Free-trial-for-Only-3,432-per-Year-Avast-Discovers-new-Fleeceware-Apps-on-the-Google-Play-and-Apple-App-Stores)).

---

## 2. Category-level evidence (why users distrust cleaners)

| Evidence | Source |
|---|---|
| Malware on Play disguised as **junk cleaners and battery optimizers** (e.g. "Junk Cleaner" 1M+, "Super Clean") auto-ran, then hid their icon to serve ads (HiddenAds), 2022 | [McAfee Labs](https://mcafee.com/blogs/other-blogs/mcafee-labs/new-hiddenads-malware-that-runs-automatically-and-hides-on-google-play-1m-users-affected); [ghacks](https://www.ghacks.net/2022/08/01/researchers-discover-hiddenads-malware-in-a-dozen-android-apps-that-were-distributed-on-the-google-play-store) |
| Optimizer and booster apps doing ad fraud and abusing Accessibility to post fake reviews, 2020 | [Trend Micro](https://www.trendmicro.com/en_us/research/20/b/malicious-apps-on-google-play-communicate-with-trojans-install-malware-perform-mobile-ad-fraud.html) |
| Cheetah Mobile (Clean Master, Battery Doctor, Security Master…) accused of **click-injection** ad fraud. Google removed CM File Manager, 2018 | [BuzzFeed News](https://www.buzzfeednews.com/article/craigsilverman/android-apps-cheetah-mobile-kika-kochava-ad-fraud); [Android Police](https://www.androidpolice.com/2018/12/04/google-has-removed-two-apps-implicated-in-cheetah-mobile-and-kika-click-fraud-scheme/) |
| **Fleeceware**: Sophos coined the term in 2019 (24 utility apps charging $100-240/yr after trials). Avast found 204 apps and $400M+ in 2021 | [Sophos 2020](https://www.sophos.com/en-us/press/press-releases/2020/08/new-rip-off-wave-on-smartphones); [Gen/Avast](https://newsroom.gendigital.com/2021-03-24-Free-trial-for-Only-3,432-per-Year-Avast-Discovers-new-Fleeceware-Apps-on-the-Google-Play-and-Apple-App-Stores) |
| Memory cleaners give only marginal gain at best, and killed apps relaunch and cost battery | [Digit test](https://www.digit.in/features/mobile-phones/tested-do-memory-cleaning-apps-really-work-30612.html); [Android Police](https://www.androidpolice.com/2016/04/08/htc-is-testing-a-phone-cleaner-app-called-boost-via-its-preview-program/) |
| 95% of 240 popular Play apps contained ≥1 dark pattern (about 7 types on average), and users rarely spot them unless warned (CHI 2020) | [Di Geronimo et al., via deceptive.design](https://deceptive.design/articles/ui-dark-patterns-and-where-to-find-them-a-study-on-mobile-applications-and-user-perception); DOI 10.1145/3313831.3376600 |
| Play policy forbids impossible or misrepresented functionality, ads mimicking OS warnings, and **misleading users into removing third-party apps or changing settings** | [Play Deceptive Behavior](https://support.google.com/googleplay/android-developer/answer/9888077?hl=en) |
| August 2023 sweep removed cleaners (SD Maid, Nox) under the stalkerware policy | [futurezone](https://futurezone.at/apps/google-systemreiniger-apps-play-store-geloescht-entfernt-nox-sd-maid/402566318); [androidworld.nl](https://androidworld.nl/nieuws/google-gaat-met-bezem-door-de-play-store-meerdere-cleaner-apps-verwijderd/) |

Academic gap: no peer-reviewed study specifically on **storage**-cleaner trust was found. The closest are the dark-pattern prevalence work above and vendor malware reports. **[UNVERIFIED]**: a Google Scholar / ACM DL pass on "task killer" or "memory cleaner" energy studies is still open.

### Play Store review themes (synthesized)

Sourced from aggregators and press, which have low authority. All items are **[UNVERIFIED]** against raw Play reviews.
- "Ads after every action", "full-screen ad before the result" ([Technipages](https://www.technipages.com/review/10-best-android-phone-cleaner-apps-in-2019/)).
- "Paywall blocks basic features", "constant rate-us prompts" ([marlvel.ai](https://marlvel.ai/apps/ccleaner-phone-cleaner/vs)).
- "Says 2 GB junk every day, nothing changes" (the recurring inflated-number complaint, [PhoneArena](https://www.phonearena.com/news/The-only-junk-on-your-phone-is-your-junk-cleaner-speed-booster-app_id90821)).
- "Deleted my photos / WhatsApp media" and "space did not free up" (bins and caches, see 1.5).

---

## 3. Trust builders vs fear triggers

| Trust builders | Fear triggers |
|---|---|
| Exact bytes **and** file count before any action | Round, inflated, or animated "junk" numbers |
| Names what is deleted ("Thumbnails of WhatsApp images") | Generic "junk", "residue", "garbage" |
| Says **where the copy lives** ("also on your Drive, checked 31/10") | "Permanently delete" with no alternative |
| Recoverable trash with visible countdown | Silent background deletion or scheduled auto-clean with no report |
| Preview (thumbnails, file list, oldest/newest date) | One giant "CLEAN" button with no list |
| Undo snackbar plus trash | Confirmation dialogs that only say "Are you sure?" |
| Default-off for risky categories; "keep at least one" invariants | Pre-selected checkboxes for personal media |
| No network, no ads, no analytics (verifiable via F-Droid / permissions list) | Telemetry that re-enables itself (CCleaner 2018) |
| Results honestly small ("only 120 MB of real junk") | "Phone is 87% overloaded!", red gauges, fake heat or virus warnings |
| Explains limits ("Android doesn't let apps read Android/data, here is the system screen") | Asking for Accessibility or all-files access without explaining why |
| Open source, reproducible builds | Brand tied to data selling or bundleware |

---

## 4. Dark patterns KeepClear must explicitly avoid

1. **Fake or inflated junk numbers**: never count files we cannot delete, never count the trash twice, never re-detect regenerated cache as "new junk" on the same day without saying so.
2. **Fear-mongering / scareware**: no red alarms, "critical", "infected", "overheating", or notifications that mimic system warnings (forbidden by [Play policy](https://support.google.com/googleplay/android-developer/answer/9888077?hl=en)).
3. **"Boost" / RAM / battery claims**: KeepClear frees *storage*. It never kills processes and never claims speed gains ([Digit](https://www.digit.in/features/mobile-phones/tested-do-memory-cleaning-apps-really-work-30612.html)).
4. **Upsells, trials, paywalled "deep clean"**: none, by principle (GPL, no ads). Donations only, outside the cleaning flow.
5. **Pre-checked personal media**: MOVE and REVIEW items are never pre-selected. Only SAFE may be.
6. **Confirm-shaming** ("No, I like a slow phone").
7. **Nagging**: no daily "you have junk!" notifications. At most an opt-in storage-low reminder.
8. **Euphemistic renaming** of monitoring or deletion (the CCleaner "Smart Cleaning" lesson).
9. **Pressuring app uninstalls**: Play forbids *misleading* users into removing apps ([policy](https://support.google.com/googleplay/android-developer/answer/9888077?hl=en)). Show neutral "last used" facts and let the user choose.
10. **Hidden permanence**: if something truly cannot go to trash (e.g. another app's cache cleared via the system), say so *before* the user acts.
11. **Celebration over substance**: no confetti "You saved 4 GB!" when 3.8 GB of it was just emptying a bin the user already chose to delete. Report it honestly as "emptied bin".

---

## 5. UX principles for KeepClear

1. **Numbers first, and exact**: every action button carries size + count ("Move 1,284 photos · 9.6 GB"). Use the same units everywhere, and use binary vs decimal consistently (decide once and document it; Android Settings shows decimal GB).
2. **Every item answers three questions**: *What is it? Why is it here? What happens if I delete it?* (product principle).
3. **Category = risk contract**:
   - **SAFE**: regenerable or duplicate. Pre-selectable, one tap, still goes to trash.
   - **REVIEW**: user's data, nothing pre-selected, preview grid, sorted by size.
   - **MOVE**: backup → verify (count + bytes, optional hash) → show a verification receipt → then offer delete. Never one-step.
4. **Show where the other copy lives**, and how it was checked (Google Photos pattern, made explicit).
5. **Default to keeping the newest**: as Google Photos keeps items <30 days, MOVE suggests items older than N months by default.
6. **"Keep at least one" invariants** for duplicates and WhatsApp "Sent" (SD Maid SE pattern). Never offer to delete the last copy as part of a bulk action.
7. **Undo everywhere**: snackbar "Undo" for 10 s, plus a 30-day in-app trash with per-item restore and a visible countdown. Trash size is shown on the home screen, so it isn't hidden "used" space.
8. **One honest home screen**: storage bar split by category with real numbers, including other apps' bins (link to Gallery / Files bins, like Samsung but unified in view).
9. **Explain platform limits instead of hiding them** (Android/data, other apps' caches): deep-link to the system screen and say why.
10. **Plain language, no jargon**: no "corpse", "residual", "arbiter". Use "Leftovers from apps you uninstalled".
11. **No surprises in the background**: any scheduled task produces a report and never deletes REVIEW or MOVE items automatically.
12. **Permission requests are just-in-time and justified**, in one sentence tied to the feature. This also reduces Play stalkerware-heuristic risk (keep store copy free of "monitor/track").
13. **RTL-first layout checks**: numbers and file paths stay LTR inside RTL text (bidi isolation), and size units follow locale.

---

## 6. UI copy examples (English / Hebrew)

The Hebrew is written natively (spoken-modern register, gender-neutral where possible through infinitive or plural forms), not translated word for word.

### 6.1 Buttons and labels

| Context | English | עברית |
|---|---|---|
| Primary SAFE action | Clear 412 MB (1,930 files) | לפנות 412MB ‏(1,930 קבצים) |
| Review action | Review 2,324 screenshots | לעבור על 2,324 צילומי מסך |
| MOVE start | Back up 9.6 GB of videos | לגבות 9.6GB של סרטונים |
| After verification | Remove from phone (copies verified) | למחוק מהטלפון (הגיבוי נבדק) |
| Undo snackbar | Moved to trash · Undo | הועבר לסל המחזור · ביטול |
| Trash entry | Trash · 3.1 GB · auto-empties in 30 days | סל המחזור · 3.1GB · מתרוקן לבד אחרי 30 יום |
| Secondary | Not now | לא עכשיו |

### 6.2 Confirmation dialog (SAFE)

> **Clear 412 MB of temporary files?**
> 1,930 files from 14 apps. These are thumbnails and cache that apps recreate on their own. Your photos, chats and documents stay as they are.
> They'll stay in KeepClear's trash for 30 days in case you change your mind.
> [Clear 412 MB] [Cancel]

> **לפנות 412MB של קבצים זמניים?**
> ‏1,930 קבצים מ-14 אפליקציות: תמונות מוקטנות ומטמון שהאפליקציות יוצרות מחדש בעצמן. התמונות, הצ'אטים והמסמכים שלך לא נפגעים.
> הקבצים יישמרו בסל המחזור של KeepClear במשך 30 יום, למקרה שתתחרטו.
> [לפנות 412MB] [ביטול]

### 6.3 MOVE: verification receipt before delete

> **Backup checked ✓**
> 81 videos · 14.85 GB copied to "Drive › Phone videos 2019-2024". We compared file count and size: all 81 match.
> Delete them from the phone now? They'll sit in the trash for 30 days first.
> [Delete from phone] [Keep them for now]

> **הגיבוי נבדק ✓**
> ‏81 סרטונים · 14.85GB הועתקו אל "Drive › סרטונים מהטלפון 2019-2024". השווינו את מספר הקבצים ואת הגודל, וכל ה-81 תואמים.
> למחוק אותם עכשיו מהטלפון? לפני שהם נמחקים סופית, הם יחכו 30 יום בסל המחזור.
> [למחוק מהטלפון] [להשאיר בינתיים]

### 6.4 "Why" text per category

| Item | English | עברית |
|---|---|---|
| SAFE: WhatsApp "Sent" folder | **Copies of media you sent.** WhatsApp keeps a copy of every photo you send, and the original is usually still in your gallery. Deleting these won't remove anything from your chats' history list, but you won't be able to re-open those images inside the chat. | **עותקים של מה ששלחת.** וואטסאפ שומר עותק של כל תמונה שנשלחה, והמקור בדרך כלל עדיין נמצא בגלריה. המחיקה לא מוחקת הודעות מהצ'אט, אבל אי אפשר יהיה לפתוח את התמונות האלה מתוך השיחה. |
| REVIEW: old screenshots | **2,324 screenshots, mostly from 2021-2023.** Screenshots are often temporary (a code, a map, a receipt). Some might matter, so take a quick look first. | **‏2,324 צילומי מסך, רובם מ-2021 עד 2023.** צילומי מסך הם בדרך כלל דברים זמניים: קוד, מפה, קבלה. ייתכן שחלק מהם עוד חשובים, אז כדאי להציץ לפני שמוחקים. |
| REVIEW: unused apps | **Not opened in over 6 months.** Removing an app deletes its data too. You can reinstall it from Play, but logins and local data may be gone. | **לא נפתחו יותר מחצי שנה.** הסרת אפליקציה מוחקת גם את הנתונים שלה. אפשר להתקין אותה שוב מ-Play, אבל ייתכן שתצטרכו להתחבר מחדש ושנתונים מקומיים ילכו לאיבוד. |
| MOVE: camera videos | **Your memories, and the biggest space saver.** We won't delete these until they're safely copied somewhere you choose and we've checked every file arrived. | **הזיכרונות שלך, וגם המקום הכי גדול שאפשר לפנות.** לא נמחק אותם עד שהם יועתקו למקום שבחרת ונוודא שכל קובץ הגיע בשלמותו. |
| Platform limit | **Android doesn't let any app clear other apps' cache directly.** We'll open the system screen for you. One tap there frees it. | **אנדרואיד לא מאפשר לאף אפליקציה לנקות את המטמון של אפליקציות אחרות.** נפתח בשבילך את מסך ההגדרות המתאים, ושם זה לחיצה אחת. |
| Honest small result | **Only 120 MB of real junk.** Most of your space is photos and videos. That's where the MOVE section can help. | **רק 120MB של קבצים מיותרים באמת.** רוב המקום תפוס בתמונות ובסרטונים, ושם החלק של "העברה" יכול לעזור. |

Copy notes for Hebrew: use the plural/infinitive form ("למחוק", "תתחרטו") to avoid gendered singular. Use "סל המחזור" (familiar from Windows/Samsung) rather than "פח". Insert U+200F (RLM) before lines that start with digits so bidi renders correctly. Write "MB/GB" in Latin without a space, which is the common Israeli usage.

---

## 7. Implications for KeepClear

### Adopt
- Google Photos' **"copy lives elsewhere" framing**, with *explicit* verification (count + bytes, optional hash) and a visible receipt.
- **Age threshold** defaults for MOVE (keep recent items) and an opt-in-only automation, as with Smart Storage.
- SD Maid SE's **risk chips** and **keep-at-least-one** invariant, with plain-language labels.
- iOS **reversibility**: a 30-day trash with countdown, and an "offload"-style app archive where Android supports it.
- A **unified bin view** that surfaces other apps' bins (Gallery / Files / Photos) as a SAFE-adjacent item with deep links (owner's 3.8 GB case).
- Exact sizes + counts on every button. Honest small numbers.

### Avoid
- All items in §4: inflated numbers, scareware, boost/RAM/battery claims, upsells, pre-checked media, nags, euphemisms.
- Permanent deletion without trash (SD Maid SE gap) and silent background deletion (Smart Storage / OEM auto-clean).
- Store-listing words such as "monitor", "track" or "system scan", and unexplained broad permissions (2023 stalkerware sweep).

### Open questions
- How to verify a MOVE to targets with no hash API (USB-OTG, SMB, Drive via SAF)? Is count + size + sampled hash enough?
- Can items deleted via the system (other apps' cache) be represented honestly in "history" when they can't go to trash?
- Hebrew number formatting: "1,930" vs "1930", and GB vs ג'יגה, both need a native-speaker review pass.
- Should "Free up" for Google Photos-backed items be supported at all, given there is no API to confirm Photos backup (see the brief)? Probably deep-link to Photos' own "Free up space" instead.
- Validate the Play-review themes in §2 with a real scrape of 1-2★ reviews for CCleaner, Avast Cleanup and Norton Clean.
