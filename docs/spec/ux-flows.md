# KeepClear: MVP UX flows

Status: **Approved by owner 2026-10-31**, updated 2026-11-01 (product principles P-1…P-3) · Date: 2026-11-01
Implements: [requirements.md](requirements.md). Evidence: [07-competitor-ux](../research/07-competitor-ux.md).

## 0. UX rules (apply to every screen)

1. **Exact numbers on every action button**: count + bytes ("Clean 3.9 GB · 1,214 files"). Never a vague "Clean".
2. **Every item answers three questions**: What is it? Why is it here? What happens if I delete it?
3. **Nothing irreversible in one tap.** Trash for 30 days, Undo snackbar after every action.
4. **Honest about trash**: "Moved to trash. Space returns on 30 Nov, or Empty now."
5. **No fear, no hype**: no red alarm screens, no "your phone is in danger", no boost/RAM/battery claims, no countdowns, no upsells.
6. **Calm defaults**: SAFE may be pre-selected; REVIEW and MOVE never are.
7. **Plain language first**, technical detail behind "Details" (paths, rule id).
8. **Hebrew is first-class**: gender-neutral (plural/infinitive), RTL mirrored, numbers and paths bidi-isolated, "MB/GB" in Latin without a space.
9. **Phone-only (P-1).** No screen ever asks for a computer. Where a power user might use one, the app offers its own way ("Check backup").
10. **Simple by default, Advanced on request (P-2).** The default path has no technical terms: say "checked: every file was read back and matches", not "SHA-256"; say "drive", not "SAF tree"; say "files apps can download again", not "cache". Hashes, paths on the drive, the drive's format, logs and expert switches live under **Details** on a screen and in **Settings › Advanced**.
11. **Always ask before freeing space (P-3).** A finished backup shows the receipt and waits. Nothing is freed automatically or on a schedule.

Notation: `[Button]`, `(chip)`, `›` = navigates to. Copy shown en / he. Hebrew lines that start with a digit get an RLM (U+200F) in resources.

---

### Review PR C (2026-11-01): what changed on screen

- **Home:** one primary action (SAFE card's filled "לנקות X GB"); REVIEW renamed "לבחור מה להשאיר"; before a scan cards say "סופרים…" or "צריך גישה" (never "בקרוב"); scan status is one line; "לנקות קבצים זמניים של אפליקציות" is always on Home, even with no permission (ONB-3); the all-files card sits below the categories.
- **Results:** every action that frees space shows a measured line ("פונו X · עכשיו פנויים Y (היו Z)"); results and Undo belong to the screen they came from and are cleared on navigation; buttons are disabled while an action runs. A 0-byte trim explains why.
- **Words:** test mode is "מצב ניסיון", so "בדיקה" means only backup checking; buttons are infinitives; count sentences use one/two/other plurals; zero counts are hidden; SHA-256, `sha256sum`, the manifest and `.keepclear-target` live under "פרטים" (copy lint in CI).
- **Colours:** moving to the 30-day trash is never red; permanent deletion is never a green primary button; the theme sets every Material role (contrast test).
- **MOVE:** progress in bytes with phase and time left; Stop asks first, and Pause vs Stop is explained; two plain steps (and a drive check) before Android's folder picker; the preflight is a scrollable ✓/✕ checklist; the receipt offers "לפנות את המקום עכשיו"; the done notification says what to do next; the Google Photos note sits below the selection.
- **Review grid:** each tile is a TalkBack checkbox with name and state; long press previews; "select all"; no action bar at 0 selected; the "move instead" stub is gone. SAFE rows are one touch target.
- **Trash:** grouped by what sent items there (clean / chosen / backed up); restore a group; "free everything that has a checked copy"; Restore all asks first (it needs no extra space: files are already on the phone); the empty-trash confirm separates items with a checked copy from items with no copy.
- **Settings:** language, how to get files back, About + privacy policy (PRIV-5), show the intro again. The intro (ONB-1) has 3 screens.
- **Bidi:** file names keep "2023.png" together in Hebrew; sizes are isolated so lines that start with a number still read right-to-left. Bottom bars pad for the navigation bar.

### F1b (2026-11-02): navigation and information architecture (UI-4)

Replaces the single Home feed. Decisions C72–C78 in [progress.md](../progress.md); design in [design-system.md §12](../design/design-system.md).

- **Three tabs**, a bottom bar on phones and a NavigationRail at the start side from 600 dp: **"לפנות מקום" / Free up space**, **"גיבוי" / Backup**, **"אפליקציות" / Apps**. Each tab keeps its own stack and its place (scroll, choices); tapping the shown tab again goes back to its root. The tabs show only on a tab's root; sub-screens are full screen (C72).
- **Top app bar on every tab:** small wordmark "KeepClear" (a heading), the **Trash chip** (bin + size, whenever KeepClear's or Android's trash isn't empty; TalkBack: "Trash · 3.1 GB") and the Settings gear. The chip is the trash's only entry point, besides the links in results ("Empty now", the free-up result) (C73).
- **Sub-screens:** a pinned top app bar with the auto-mirrored back arrow and the title (a heading); no "לחזור" text link. Back pops the tab's stack; at the root of Backup or Apps, Back goes to Free up space; there Android closes the app.
- **Free up space:** the free space (hero) with the storage strip, then the SAFE main button `[לפנות 7.9GB]` (PR H2c: the hero sentence's verb; it was `[לנקות 7.9GB]`), which opens the usual confirmation directly (phone storage only, C74), and `לראות פרטים` (Clean now). Then the REVIEW card, the MOVE card (jumps to Backup), "Clear apps' temporary files", ONE collapsed **"לראות עוד קבצים"** card with the permission steps (All files access, WhatsApp's folder), "where your space is", and dismissible notices (cloned apps, C75) at the bottom.
- **Backup:** move memories (sources, age), **"לשחזר מגיבוי" works without a scan** (before a scan the sources say "סופרים…" / "צריך גישה"), the backup places (a drive is chosen when a backup starts; the computer at home and the Nextcloud set up in Settings › Advanced, C76), and the history of backups.
- **Apps:** clear one app at a time, apps' own folders (when the scan found any), the cloned-apps note, then the apps by size and the unused ones.
- **Settings:** language, how to get files back, the uninstall note while the trash isn't empty, kept by you, the reminder, the intro again, About + privacy. **מתקדם:** test mode, backup places (Nextcloud set-up; the saved addresses with "Forget"), device check (test builds).
- **Explainers** (why a permission is needed) open as a bottom sheet from the button that was tapped (C77), never at the top of the tab.

### F2 (2026-11-02): screens, UX fixes and Hebrew (UI-5)

Rebuilt on the design system (ADR 0013). Decisions C79–C95 in [progress.md](../progress.md).

- **Confirmations** are a bottom ConfirmSheet: title, how many, how much, where it goes ("לסל המחזור של KeepClear, ל-30 יום"), the editable list where there is one, the specific verb, Cancel. The confirm keeps `SafeConfirmButton` (delay + covered-screen check); it now has no default colour: red only for permanent actions (empty the trash, release now / all, empty Android's trash), ink for everything else (to the trash, trim, restore a changed file, last copy, test mode off) (C79).
- **Results** are a ResultBanner on top of the screen: a clean says what moved to the trash and when the space comes back, with Undo and "לרוקן עכשיו"; the measured line ("פונו 7.9GB · עכשיו פנויים 24.7GB (היו 16.8GB)") shows after the space really comes back (trim, emptying or releasing the trash) (C80). A running action shows a progress line.
- **Free up space:** "where your space is" is one line under the strip (and in the strip's spoken description) (C90); every card is a design-system card.
- **Review group:** back-arrow bar with the group's name; the size; "למה זה כאן?" chip opens a sheet with why / what happens; "לעבור אחד-אחד" (tonal) and select all; month sections with "לבחור את כל החודש"; 4 columns of thumbnails with a check-circle when selected; the bottom bar ("למחוק 340MB · 12 קבצים") appears only with a selection. Group cards in lists show 3–4 real thumbnails.
- **Swipe review:** the card follows the finger and tilts (no tilt with "Remove animations"); past a quarter of the width it says „למחוק” or „להשאיר” and ticks; Delete is toward the Delete button (right in English, left in Hebrew); Close or Back with decisions asks „לצאת? 14 החלטות יאבדו” (C83). *Replaced by PR H2b below (left deletes in both languages; closing keeps the session).*
- **Trash:** grouped by what sent items there, each item and group with "עוד 23 ימים"; release-all names how many items have a checked copy (deleted for good) and how many don't (stay in the trash) (C81); the uninstall note on the first visit only, without the hidden folder's name (C82).
- **Backup tab:** sources are one toggleable row each with thumbnails; age chips wrap; "לבחור לאן לגבות" opens a sheet with the USB drive or memory card and only the places set up in Settings › מתקדם (C84); the summary is a paper slip with the stamp „נבדק קובץ-קובץ”. **Restore:** the same choice of places, then "KeepClear קורא את הגיבוי…" with Cancel, the catalog with "תמונות מהמצלמה · יולי 2021" rows (one target each), and Stop while it runs (C85, C86).
- **Settings › מתקדם:** "לגבות למחשב בבית (דרך ה-Wi-Fi)": the address form; KeepClear connects once and saves the place only if that worked (C84). The reminder explains first and stays off without notification permission (C88).
- **Apps:** app icons; each app once (unused apps, then the others); "KeepClear קורא את רשימת האפליקציות…" while loading (C87). One app at a time offers "להפעיל גישה לנתוני שימוש".
- **Hebrew:** the owner's voice rules (CLAUDE.md) applied to every string: KeepClear masculine, plural address, infinitive buttons, „curly quotes”, "יימחקו"; the store listing uses the app's lane names (C91).

### H2a (2026-11-03): Home, clean, intro (owner brief H2 items 1–7, 11, 12; UI-6)

Decisions C141–C147 in [progress.md](../progress.md). Replaces the parts of §1.1, §1.2, §2, §3.2 and §9 it names.

- **Intro (§1.1):** a picture per page (our own line drawings in theme colours: the strip getting shorter; a finger choosing photos; photos going into a USB stick with the check stamp), dots under it that TalkBack reads „1 מתוך 3”, and on every page the text button „טלפון חדש? לשחזר מגיבוי”, which closes the intro and opens the restore flow on the Backup tab. Page 2: „את רוב הדברים אפשר לשחזר” / „רוב מה שמנקים מחכה 30 יום בסל המחזור. קבצים שנבנים מחדש לבד נמחקים מיד.”
- **First-run Home (§1.2):** the free space and strip, ONE access card „לראות מה תופס מקום”; the hero and the REVIEW and MOVE cards say „יופיע אחרי האישור” (no more „צריך גישה” four times); no trim card, no collapsed "more files" card.
- **After the first scan:** one card right under the scan line, „למצוא גם תמונות ומסמכים מוואטסאפ?”: on `foss` „לאשר גישה לכל הקבצים” (explainer first), on `play` it says this version doesn't ask for All files access and offers „לבחור את התיקייה של וואטסאפ”; „לא עכשיו” hides it for the visit (C143).
- **Top bar:** the Trash chip always shows a word: „סל · 258MB”; with an empty trash „סל”, muted (variant outline and text), still opening the Trash (TalkBack: „סל המחזור ריק”). At 200 % the icon gives way to the words, which shrink rather than being cut (never below their size at 100 %). On tablets the bar sits at the content's readable width.
- **Strip legend:** nouns, „קבצים מיותרים · לבחירה · לגיבוי · אחר · פנוי”; an empty lane isn't listed.
- **One clean button on Home:** „לפנות 7.9GB” (H2c; „לנקות 7.9GB” in H2a); „לנקות קבצים זמניים” and „לנקות אפליקציה אחת בכל פעם” are under „לראות פרטים” (Clean now) and on the Apps tab; when the phone is nearly full (under 100 MB) the banner's button is „לנקות קבצים זמניים” (C144).
- **Clean confirm (§3.2):** title „לנקות 7.9GB?”; first, in red, only when there are any: „3.0GB · 96 קבצים מסלים של אפליקציות אחרות יימחקו לצמיתות. הם כבר נמחקו פעם אחת, ואי אפשר יהיה לשחזר אותם.”; then „כמה”, „כמה מקום”, „מה יקרה”: „נמחקים עכשיו; הטלפון יוצר אותם מחדש לבד” and/or „לסל המחזור של KeepClear, ל-30 יום” (each with its part's size when there is more than one part); then always „התמונות, הסרטונים והצ'אטים שלכם לא נמחקים.” The body scrolls; while more is below, a fade sits above the sticky buttons (C141, C142).
- **After a clean:** the cleaned items leave every total at once and the hero shows the free space just measured; the next scan confirms. With nothing left to clean the hero says „הכול נקי ✓” and points to the biggest win: „הכי הרבה מקום יתפנה מגיבוי התמונות: 31.9GB” `[לגבות תמונות]` (or, with nothing to back up, „…ממה שתבחרו למחוק” `[לבחור מה למחוק]`); never „0B” (C145).
- **REVIEW lane:** „לבחור מה למחוק” with „שום דבר לא נמחק בלי שתבחרו” on the card and at the top of its list.
- **Layout (UI-2, A11Y-1):** the review month header wraps below its button instead of breaking the month's name; rail labels wrap only between words and fit the rail; bottom tab labels keep a margin at 200%; the age chips never leave „הכול” alone on a line (one per line instead); a short drive name („SanDisk USB”) stays on one line; the review grid has columns of at least 80 dp (4 on a phone, more on tablets) (C146).

### H2b (2026-11-03): swipe review (owner brief H2 item 8; REV-5, REV-5a)

Decisions C148–C153 in [progress.md](../progress.md). Replaces F2's swipe bullet above.

- **Layout, top to bottom:** „לסגור ✕” · „41 מתוך 2,324” (TalkBack says it politely after each decision) · „חזרה ↶” (auto-mirrored; disabled with nothing to undo); a one-line reminder „שום דבר לא נמחק עד שמסיימים ומאשרים.” (or, the very first time, the hint below); the picture; the file name; its date and size („8 באוק׳ 2026 · 1.2MB”); „למחוק” and „להשאיר” (same tonal style, same width, an icon each) with „לדלג” between them (on its own line at large font sizes); and always „לסיים ולבדוק · 14”.
- **Directions (C148):** a swipe to the **left** deletes, to the right keeps, in Hebrew and in English; the Delete button sits on the left. Settings › „להחליף את כיווני ההחלקה” swaps the gesture, the buttons, the hint and the edge labels. While dragging, the card's trailing edge shows „למחוק” or „להשאיר” (icon + word), fading in up to the threshold; past it the phone ticks.
- **One-time hint:** the first session ever shows „החלקה שמאלה — למחוק, ימינה — להשאיר.” above the picture until the first decision.
- **Haptics:** Keep → CONFIRM, Delete → REJECT, the threshold → GESTURE_THRESHOLD_ACTIVATE (API 34+, else CLOCK_TICK); never with Android's touch feedback off.
- **„לסיים ולבדוק · N”** (N = marked Delete so far; disabled, without a number, while N is 0) ends the session and opens the usual editable confirmation (§4.3) with exactly those N; the rest stay untouched (nothing pre-selected). Deletion happens only from that confirmation, to the 30-day trash, and test mode applies. At the end of the list the summary shows above the same button.
- **Resume (C149, C150):** closing (✕, Back) keeps the session; the next „לעבור עליהם אחד-אחד” asks „עברתם על 38 מתוך 2,324” · „13 קבצים מסומנים למחיקה. שום דבר עוד לא נמחק.” · (if any) „2 קבצים השתנו או נעלמו מאז, אז הבחירות שלהם לא נשמרו.” `[להמשיך מאיפה שעצרתם]` `להתחיל מחדש`. A file that changed (size or date) or went away since loses its decision and comes up again. Finishing or starting over forgets the session.
- **TalkBack:** the picture, name and date are one item with the actions „להשאיר”, „למחוק” and (after a decision) „חזרה”.

### H2c (2026-11-03): terminology and formatting (owner brief H2 items 9 and 10)

Decisions C154–C160 in [progress.md](../progress.md); the full list of changed strings is in the PR description. One term per concept, in every string, the store listing and this spec (table in CLAUDE.md, checked by `TerminologyTest`):

| Concept | עברית | English |
|---|---|---|
| Bytes really come back | לפנות מקום, „לפנות 7.9GB” on Home | Free up space, "Free up 7.9 GB" |
| Move to KeepClear's trash | להעביר לסל | Move to trash |
| Permanent | למחוק לצמיתות | Delete for good |
| Copying memories (the MOVE lane too) | לגבות | Back up |
| Bring back / undo | לשחזר / „חזרה ↶” on every undo (swipe, result banner) | Restore / Undo |
| KeepClear's trash / other bins | סל המחזור (chip „סל · 258MB”) / סל הגלריה, סל של אפליקציה אחרת | Trash / the gallery's bin, another app's bin |
| Thumbnails | תמונות ממוזערות | Thumbnails |
| USB drive (default path) | דיסק און קי | USB drive |
| Details link | לראות פרטים | See details |

- **Count first:** every count with its size reads „2,324 קבצים · 2.9GB” (lists, totals, the clean sheet's parts, the Trash header and groups, the apps not opened in 6 months, the receipt „הועתקו 2,324 קבצים · 2.9GB.” and the shared summary).
- **Empty selection:** „נבחרו: לא נבחר כלום” never appears; Clean now and the Backup bar say „לא נבחר כלום” and the disabled buttons read „לנקות” / „לגבות”; restore says „פנוי בטלפון: 41.2GB. לא נבחר כלום.”.
- **Reminder setting:** „…כשנשאר פחות מ-10% (או 2.0GB) פנוי.” The size is the reminder's own threshold, formatted and isolated like every size (it read „(או GB 2)”).
- **Places in plain words:** the receipt „…בתיקייה KeepClear › Pixel 8”; a trash row „עותק שנבדק נמצא ב-SanDisk” (its details sheet: „SanDisk › KeepClear › Pixel 8 › Camera”); „ישוחזר לתיקייה KeepClear › Restored-changed › DCIM”. Never "USB:KeepClear/Phone/IMG" or "/storage/emulated/0/…".
- **About:** the "no ads, no tracking" sentence appears once (the second line under the privacy link is gone).

### H3 (2026-11-03): help, trust and finish (owner brief H3 items 1–7; HELP-1…7)

Decisions C161–C171 in [progress.md](../progress.md).

- **Help („עזרה”):** the main top bar is wordmark · „סל · 258MB” · **?** · ⚙. "?" and Settings' „עזרה” card open the topic list (offline, string resources): „איך זה עובד” · „האם זה בטוח” · „מה נמחק לצמיתות ומה עובר לסל” · „איך משחזרים קובץ” · „למה צריך גישה לכל הקבצים” (`play`: „למה צריך גישה”) · „מה קורה לוואטסאפ” · „הגיבוי נעצר — מה לעשות” · „טלפון חדש”. A topic is short paragraphs; topics about getting files back end with tonal buttons „לפתוח את סל המחזור” / „לשחזר מגיבוי” (screens only). Under the list: „עדיין לא מסתדרים? אפשר לדווח על בעיה מההגדרות…”. PR H4 adds „ניהול שטח / שמירת נתונים בהסרה” and „מרחב פרטי”.
- **Settings:** „עזרה” (card with ›) above „איך משחזרים קבצים”, which now has „לפתוח את סל המחזור” and „לשחזר מגיבוי”; after the intro button: „מה KeepClear עשה” and „לדווח על בעיה” (cards with ›); the About card keeps its sentence and the privacy link and adds „לראות פרטים” (About screen).
- **Report a problem:** a sheet: one line on what happens („KeepClear פותח את אפליקציית המייל עם הטקסט הזה…”), the editable text („מה קרה?”, room to write, then `---` and the device facts in English: version, flavor, build type, device, Android, language, test mode, Clean now / Choose / Back up / Trash totals, backups made, "No file paths are included."), the checkbox „לצרף קובץ אבחון: מספרים וגדלים מהטלפון, בלי שמות של קבצים”, then `[לפתוח במייל]`, „להעתיק את הטקסט”, „לסגור”. With the file, „מכינים את קובץ האבחון…” with a progress bar first. No email app: „לא נמצאה אפליקציית מייל בטלפון. אפשר להעתיק את הטקסט…”.
- **After an unexpected exit:** on Home, above everything, once: card „KeepClear נסגר באופן לא צפוי. לשתף דוח?” · „בדוח רואים איפה בקוד שלו KeepClear נעצר, בלי שמות של קבצים. אתם בוחרים לאן לשלוח אותו.” · `[לשתף את הדוח]` (Android's share sheet) · „לא עכשיו”.
- **What KeepClear did:** hero „18.4GB פיניתם עד היום” with „נספר רק מקום שבאמת התפנה. קבצים שעברו לסל נספרים כשהסל מתרוקן.” (nothing yet: „עוד לא פיניתם מקום”), then one card per action, newest first: icon, kind („נוקו קבצים”, „נוקו קבצים זמניים של אפליקציות”, „נמחקו לצמיתות מהסל”, „סל המחזור: קבצים בני יותר מ-30 יום נמחקו לצמיתות”, „גובו ונבדקו”, „פונה מקום אחרי גיבוי שנבדק”, „המקור עבר לסל אחרי גיבוי”, „שוחזרו מסל המחזור”, „שוחזרו מגיבוי”), „2,324 קבצים · 7.9GB”, „פונו 7.9GB” when space came back, date and time. Empty: „עוד אין כאן כלום…”.
- **About:** „גרסה 0.0.1 (1)”; „לראות את קוד המקור”; a privacy sentence and „לקרוא את מדיניות הפרטיות”; „תרגומים” „עברית ואנגלית: צוות KeepClear.” with „לעזור לתרגם”; „רישיונות”: one card per font, icon set and library (name, copyright, licence) with „לקרוא את הרישיון” (the text from the APK, in a sheet). Links open in the browser.
- **What's new:** a sheet „מה חדש” with `[להמשיך]`, only the first launch after an update that has an entry (none yet), never after a first install.
- **App icon and splash:** paper background, option 1 (ink frame with three stacked bars in the lane colours), themed icon from its monochrome layer; the starting window is paper with the mark (light and dark), then the app. Options 1–3: [gallery](../design/gallery.md#app-icon-options-pr-h3).
- **Launcher shortcuts** (long-press the icon): „לפנות מקום” (Home), „סל המחזור” (the Trash), „לגבות” (the Backup tab). They open the screen and do nothing else.

### H4 (2026-11-03): platform and accessibility (owner brief H4 items 1–9; PLAT-1…9)

Decisions C172–C186 in [progress.md](../progress.md).

- **Android's Settings › Apps › KeepClear › Storage:** „ניהול מקום” (not „ניקוי האחסון”) opens „המקום של KeepClear”: „בסל המחזור: 1,284 קבצים · 3.4GB” · „קבצים בסל נמחקים לצמיתות אחרי 30 יום, או מיד כשמרוקנים אותו.” · why there is no "clear data" here · `[לפתוח את סל המחזור]` `[לסגור]`. Uninstalling asks whether to keep KeepClear's data (Android's own dialog).
- **Back:** predictive everywhere; in a group with something selected, Back first clears the selection; sheets slide up and down (none with "Remove animations").
- **Tabs:** a bottom bar on phones, a rail from 600 dp (NavigationSuite); sub-screens move above the keyboard.
- **Notifications:** asked only from the first backup and the reminder switches. Four kinds: „התקדמות הגיבוי”, „תוצאות גיבוי”, „תזכורות על מקום”, „תזכורות גיבוי”; Settings › „התראות” lists them, each opens its Android page.
- **The phone stopped the backup:** the paused card reads „הטלפון עצר את הגיבוי כדי לחסוך סוללה” · „אנדרואיד סוגר אפליקציות ברקע כדי לחסוך סוללה. אם מאפשרים לגיבוי לרוץ ברקע, זה קורה פחות.” · `[להמשיך]` · `[לאפשר לגיבוי לרוץ ברקע]` · „בטלפון הזה” with the maker's steps (OnePlus: „אופטימיזציה עמוקה”, „אופטימיזציה במצב שינה”, lock in Recents) · `[לפתוח את המדריך המלא לטלפון הזה]`. Only when Android recorded a system stop; a crash or an update reads „הגיבוי הופסק”.
- **Settings › „פתרון בעיות”:** „הגיבוי רץ לאחרונה: 3 בנוב׳ 2026, 14:05”, „בפעם האחרונה הטלפון עצר אותו כדי לחסוך סוללה.” when so, and the same battery help.
- **Language:** the chips work on Android 11–12 too; on 13+ also `[לבחור בהגדרות של אנדרואיד]`.
- **Apps (Android 15+):** an unused app from a store that supports it: `[לארכב]` `[להסיר]` and „ארכוב שומר את הנתונים ואת הסמל של האפליקציה ומפנה את האפליקציה עצמה. לוחצים על הסמל כדי לקבל אותה בחזרה.”; every app row: „אפליקציה 2.0GB · נתונים 200MB · קבצים זמניים 100MB”.
- **Accessibility:** Android 14+'s contrast setting gives medium/high-contrast ink; main buttons are 56 dp; app rows are one TalkBack item each; Trash rows have Restore and „לפנות עכשיו” in the actions menu.
- **Help:** two more topics: „המקום של KeepClear והסרת ההתקנה” and „מרחב פרטי”; „הגיבוי נעצר — מה לעשות” ends with this phone's steps.

### H5 (2026-11-03): power users (owner brief H5; POW-1…7)

Decisions C187–C193 in [progress.md](../progress.md). Nothing here is on the default path until asked for.

- **Review group:** under „למה זה כאן?” a text button `[🔍 למיין ולסנן]`. Open: a search field („חיפוש לפי שם או תאריך, למשל יולי 2021”), chips „מהחדש לישן” (default, month sections) · „מהישן לחדש” · „מהגדול לקטן” (one list, header „מהגדול לקטן”), chips „כל השנים” · 2026 · 2025 · …, then `[לבחור את הגדולים ביותר]`. With a filter: „מוצגים 312 מתוך 2,324” `[להציג הכול]`; „לבחור הכול” reads „לבחור את כל המוצגים”.
- **„כמה מהגדולים לבחור?”** sheet: „מתוך מה שמוצג עכשיו. שום דבר לא נמחק עד שבודקים את הרשימה ומאשרים.” `[לבחור את 10 הגדולים · 1.2GB]` `[לבחור את 25 הגדולים · …]` … `[ביטול]`. Picking selects exactly those (replacing the selection, C189).
- **Selection bar** (bottom, above „למחוק …”): „נבחרו: 25 קבצים · 3.1GB” · „4 מהם לא מוצגים עכשיו” when a filter hides some · `[לבטל את הבחירה]`.
- **Settings › „הכללים שלכם”:** „כללים משנים רק את מה ש-KeepClear מציע. ביטול של כלל לא מוחק ולא מזיז אף קובץ.” Each row: „לא להציע שוב” / „להציע תמיד לנקות” · the place („Pictures › Screenshots › Screenshot_….png”) · „בסריקה האחרונה: 1 קובץ · 1.2MB” / „לא נמצא כלום בסריקה האחרונה” / „יופיע אחרי הסריקה הבאה” · `[לבטל את הבחירה]`.
- **After „לא להציע את זה שוב”:** back in the group, a notice at the bottom „KeepClear לא יציע שוב את IMG_….jpg.” `[חזרה]`, for 10 s or longer if Android's "time to take action" asks.
- **Test mode:** the result „היה מתפנה … (מצב ניסיון)” has `[לייצא את הרשימה]` → sheet „לייצא את מה שמצב הניסיון היה מוחק?” · „הרשימה כוללת את השם, התיקייה והגודל של כל אחד מ-… היא נשמרת רק איפה שתבחרו; KeepClear לא שולח אותה לשום מקום.” `[לבחור איפה לשמור]` `[ביטול]` → Android's save screen → „הרשימה נשמרה.” in the result.
- **Network drive form (Settings › Advanced):** address · פורט („445”, „אפשר להשאיר ריק”) · שם התיקייה המשותפת · תיקייה בתוך התיקייה המשותפת („Backups/Phone”, „אפשר להשאיר ריק”) · user · password · דומיין („אפשר להשאיר ריק”). A bad port, a folder with `..` or a leading `/`, or a domain with spaces says so under the field and keeps `[להתחבר]` off. No "searching for computers" (C192): the address is typed.

## 1. First launch & onboarding (ONB-1…3)

### 1.1 Intro (3 pages, skippable)

| # | English | עברית |
|---|---|---|
| 1 | **Keep what matters. Clear the rest.** KeepClear finds what's taking your space and sorts it into three groups. | **שומרים את מה שחשוב. מפנים את השאר.** KeepClear מוצא מה תופס לך מקום וממיין אותו לשלוש קבוצות. |
| 2 | **Clean · Review · Move.** Junk goes in one tap. Anything unclear is explained, and you decide. Your memories are moved to your own drive and checked before anything is deleted. | **לנקות · לבדוק · להעביר.** קבצים מיותרים נמחקים בלחיצה. כל מה שלא ברור מוסבר, וההחלטה שלך. הזיכרונות עוברים לכונן שלך ונבדקים לפני שמשהו נמחק. |
| 3 | **Nothing disappears for 30 days.** Everything you delete waits in KeepClear's trash. No ads. No tracking. KeepClear can't even connect to the internet. | **שום דבר לא נעלם ל-30 יום.** כל מה שמוחקים מחכה בסל המחזור של KeepClear. בלי פרסומות. בלי מעקב. ל-KeepClear אין בכלל גישה לאינטרנט. |

`[Start]` / `[להתחיל]` › Home (no permission asked yet).

### 1.2 Home without permissions (ONB-3)

- Top: storage bar (used / free, from `StatFs`): "214 GB used · 16.8 GB free" / "‏214GB בשימוש · 16.8GB פנויים".
- Card **"See what's taking space"**: "To look at your photos, videos and files, KeepClear needs your permission. Everything stays on this phone." `[Allow access]`.
- Card **"Let Android trim app caches"** (works with no permission, SAFE-5): `[Trim now]` → result "Android freed 1.2 GB of app cache" (measured).

### 1.3 Permission explainer pattern (ONB-2)

Every permission request is preceded by a bottom sheet with the same structure:

```
[icon]  Title: what it unlocks
        Why we need it  (1 sentence)
        What we'll never do  (1 sentence)
        Without it  (what still works)
[Continue to Android's screen]   [Not now]
```

| Permission | en | he |
|---|---|---|
| Photos & videos (READ_MEDIA_*) | **See your photos and videos.** To find screenshots, big videos and what can be backed up. Nothing leaves your phone unless you move it to a drive you choose. Without it: only junk and app caches. | **לראות תמונות וסרטונים.** כדי למצוא צילומי מסך, סרטונים גדולים ומה אפשר לגבות. שום דבר לא יוצא מהטלפון, אלא אם מעבירים אותו לכונן שבוחרים. בלי זה: רק קבצים זמניים ומטמון של אפליקציות. |
| All files (foss) | **See all your files.** Finds WhatsApp media, folders left from your old phone, and other apps' bins. Android still keeps other apps' private data closed. Without it: you can give access folder by folder. | **לראות את כל הקבצים.** כדי למצוא מדיה של וואטסאפ, תיקיות שנשארו מהטלפון הקודם וסלי מחזור של אפליקציות אחרות. גם עם ההרשאה הזו, אנדרואיד לא חושף נתונים פרטיים של אפליקציות אחרות. בלי זה: אפשר לתת גישה תיקייה אחרי תיקייה. |
| WhatsApp folder (SAF, play) | **Let KeepClear see WhatsApp's media folder.** On the next screen, tap **Use this folder** at the bottom. We only read photos, videos and voice notes. Your chats and backups are never touched. | **לתת ל-KeepClear לראות את תיקיית המדיה של וואטסאפ.** במסך הבא, הקישו על **שימוש בתיקייה הזו** למטה. אנחנו קוראים רק תמונות, סרטונים והודעות קוליות. הצ'אטים והגיבויים לא נוגעים בהם אף פעם. |
| Notifications (API 33+, at first MOVE) | **Get told about your backup.** Progress while it runs, and a note when it's done or needs you (e.g. the drive was unplugged). Without it: the backup still runs; you'll see progress only inside KeepClear. | **לקבל עדכונים על הגיבוי.** התקדמות בזמן שהוא רץ, והודעה כשהוא מסתיים או כשצריך אתכם (למשל כשהכונן נותק). בלי זה: הגיבוי עדיין רץ, ואת ההתקדמות רואים רק בתוך KeepClear. |
| Usage access | **See app sizes and when you last used them.** To show apps you haven't opened in months. Read on this phone only. | **לראות כמה מקום כל אפליקציה תופסת ומתי השתמשו בה.** כדי להראות אפליקציות שלא נפתחו כבר חודשים. המידע נקרא רק בטלפון. |

Returning from system settings re-checks state on `onResume` and shows a ✓ toast or, if still denied, the "Without it" line.

---

## 2. Home (SCAN-1, SCAN-2)

```
┌──────────────────────────────────────────────┐
│ KeepClear                           ⚙  🗑 3.1GB│
│ ███████████████████████████░░  93% full       │
│ 16.8 GB free of 240 GB                         │
│                                                │
│ ┌ Clean now ───────────────────────────────┐   │
│ │ 7.9 GB · junk you won't miss          (SAFE)│
│ │ [Clean 7.9 GB]                 Details ›  │   │
│ └──────────────────────────────────────────┘   │
│ ┌ Review ──────────────────────────────────┐   │
│ │ 24.6 GB · screenshots, big videos, old    │   │
│ │ phone folders. You decide.     [Review ›] │   │
│ └──────────────────────────────────────────┘   │
│ ┌ Move to backup ──────────────────────────┐   │
│ │ 58 GB of photos & videos older than 1 yr  │   │
│ │ Back up, check, then free the space.      │   │
│ │ [Start ›]                                 │   │
│ └──────────────────────────────────────────┘   │
│ Where your space is:  Photos 75 · WhatsApp 42 │
│ Apps 60 · Other 46                             │
└──────────────────────────────────────────────┘
```
- In RTL the whole layout mirrors; numbers inside text are isolated.
- While scanning: cards fill progressively with a subtle "Still counting…" line; `[Cancel]`.
- Trash chip top-end shows trash size; tap › Trash. (F1b: the chip, wordmark and Settings are in the top app bar of every tab; the layout above is now the "Free up space" tab, see "F1b" in §0.)

Hebrew card titles: **לנקות עכשיו** / **לבחור מה למחוק** (H2a) / **לגבות** (H2c; was **להעביר לגיבוי**). Buttons: `[לפנות 7.9GB]` (H2c), `[לבדוק ›]`, `[להתחיל ›]`.

---

## 3. Clean now (SAFE) (SAFE-1…8)

### 3.1 SAFE details
List of groups, each pre-selected, each with count, bytes, risk chip and expandable "Why":

| Group | Chip | Why (en) | למה (he) |
|---|---|---|---|
| Other apps' recycle bins (3.8 GB) | Already deleted | Files you already deleted in another app. They're waiting in that app's bin. | כבר מחקתם את אלה באפליקציה אחרת, והם מחכים בסל של אותה אפליקציה. |
| WhatsApp: copies of photos you sent (x GB) | Copy exists | Exact copies of photos that are still in your Camera folder. We checked: same content, byte for byte. | עותקים מדויקים של תמונות שעדיין נמצאות בתיקיית המצלמה. בדקנו: התוכן זהה לגמרי. |
| Unpacked zip files (x GB) | Copy exists | The folder next to each zip already has all of its files. | בתיקייה שליד כל קובץ zip כבר נמצאים כל הקבצים שבתוכו. |
| Viewed WhatsApp statuses | Rebuilt automatically | Statuses you watched. They expire after 24 hours anyway. | סטטוסים שכבר צפיתם בהם. הם פגים אחרי 24 שעות בכל מקרה. |
| Thumbnails, temp files, empty folders | Rebuilt automatically | Small preview images and leftovers that apps recreate if needed. | תמונות ממוזערות וקבצים זמניים שאפליקציות יוצרות מחדש לפי הצורך. |
| App caches (trimmed by Android) | Can't be restored | Android removes temporary app data; apps rebuild it. This one can't go to the trash. | אנדרואיד מוחק נתונים זמניים של אפליקציות, והן בונות אותם מחדש. את זה אי אפשר להעביר לסל המחזור. |

Tap a group › item list (thumbnails/paths) where items can be unticked.

### 3.2 Confirm sheet
> **Clean 7.9 GB?**
> 1,214 files in 5 groups. They'll wait in KeepClear's trash for 30 days, so you can restore anything.
> App caches (1.2 GB) are cleared by Android and can't be restored. Apps rebuild them.
> `[Clean 7.9 GB]` `[Cancel]`

> **לנקות 7.9GB?**
> ‏1,214 קבצים ב-5 קבוצות. הם יחכו 30 יום בסל המחזור של KeepClear, וכל דבר אפשר לשחזר.
> מטמון האפליקציות (1.2GB) מנוקה על ידי אנדרואיד ואי אפשר לשחזר אותו. האפליקציות בונות אותו מחדש.
> `[לנקות 7.9GB]` `[ביטול]`

If the plan includes MediaStore items and `MANAGE_MEDIA` isn't granted, Android shows its own dialog next; a one-line pre-note says so: "Android will ask you once more to confirm." / "אנדרואיד יבקש אישור פעם נוספת."

### 3.3 Result
> **Done. 7.9 GB moved to trash.** · `[Undo]`
> Space returns on 30 Nov 2026, when the trash empties. Need it now? `[Empty trash now]`
> Skipped 3 files (changed since the scan).

> **בוצע. 7.9GB הועברו לסל המחזור.** · `[ביטול]`
> המקום יתפנה ב-30 בנובמבר 2026, כשסל המחזור יתרוקן. צריך את המקום עכשיו? `[לרוקן את סל המחזור]`
> ‏3 קבצים דולגו (השתנו מאז הסריקה).

---

## 4. Review (REV-1…4)

### 4.1 Review hub
Buckets as cards, none pre-selected, sorted by size:
- Big videos · 81 · 14.85 GB — "Videos over 100 MB. Most are long recordings." `[Look]` `[Move instead]`
- Screenshots · 2,324 · x GB — "Mostly 2021–2023. Often temporary: codes, maps, receipts."
- AI images · 526 · x GB — "Images made with ChatGPT and similar apps."
- Left from your old phone · 5.4 GB — "MIUI and ShareMe folders and an old WhatsApp folder from before 2022."
- WhatsApp: hidden media (Private) · 1.26 GB
- WhatsApp: copies you sent (no original found) · x GB
- Downloads older than 3 months · x GB

### 4.2 Bucket grid
- Grid of thumbnails, grouped by month headers with "Select month" checkbox; top bar: `Selected 312 · 1.4 GB`.
- Tap = select; long-press / magnifier = full-screen preview (swipe through, play video, play voice note).
- Sort: date / size. Filter: year.
- Bottom bar: `[Delete 312 · 1.4 GB]` `[Move 312 instead]`.

### 4.3 Confirm sheet (editable)
Thumbnail strip of selected items (tap ✕ to remove one) + text:
> **Delete 312 screenshots (1.4 GB)?** They'll wait in the trash for 30 days.

> **למחוק 312 צילומי מסך (1.4GB)?** הם יחכו 30 יום בסל המחזור.

### 4.4 Old-phone leftovers detail
Each folder row shows what's inside ("1,840 photos, 220 voice recordings, 12 apps' data") and per-folder actions `[Look inside]`, `[Move]`, `[Delete]`. The old `/sdcard/WhatsApp` shows a lock row: "Databases and Backups inside are your old chats. KeepClear won't delete them." / "Databases ו-Backups שבתוכה הם הצ'אטים הישנים שלך. KeepClear לא ימחק אותם."

---

## 5. Move to backup (MOVE-1…16)

### 5.1 What to move
```
What should we move?
(●) Camera photos & videos     58.1 GB · 8,912 files
( ) WhatsApp photos & videos you received   17.8 GB
( ) Big videos only            14.85 GB · 81
Older than: [ 1 year ▾ ]   (favourites are always included, owner decision P2)
Total: 58.1 GB · 8,912 files
[Next: choose where]
```
he: **מה להעביר?** · "תמונות וסרטונים מהמצלמה" · "תמונות וסרטונים שקיבלתם בוואטסאפ" · "רק סרטונים גדולים" · "ישנים מ: שנה" · "לכלול מועדפים" · `[הבא: לבחור לאן]`

### 5.2 Choose where
Cards:
- **USB-C drive or SD card** (recommended): "Plug in a drive. Fast enough, no internet, and we can check every file." `[Choose drive]` → SAF tree picker.
- **Network drive on your Wi-Fi** (v1, greyed "Coming soon"): "A shared folder on a home network drive or router. No computer needed." (MOVE-3)
- **Google Drive** (v1, greyed "Coming soon"): "Sign in to your Google account. Google tells us a fingerprint of every file it stored, so KeepClear can check each copy before freeing space." (MOVE-4, MOVE-20; the word "checksum" appears only under Details.)
- **Other cloud apps** (OneDrive, Dropbox, Nextcloud): added only where the service can prove each copy (MOVE-4). Through the system folder picker today: "You can copy there, but KeepClear can't check the copy, so it won't free space on the phone." (MOVE-14)
- Info line: "Using Google Photos? KeepClear can't see what's in it. Check there, or use its Free up space." `[Open Google Photos]` (MOVE-16)

he for the first card: **כונן USB-C או כרטיס SD** — "מחברים כונן. מספיק מהיר, בלי אינטרנט, ואפשר לבדוק כל קובץ."

### 5.3 Pre-flight check
Checklist screen, each line ✓ / ✕ with a fix:
- Space on drive: "120 GB free, 58.1 GB needed ✓"
- Drive format: "FAT32: 3 videos are over 4 GB and can't be copied to it ✕ — [Copy the rest] [How to fix]"
- Battery: "Plug in your phone. Copying 58 GB takes about 1–1.5 hours." (estimate from measured speed after first 200 MB)
- Battery (owner decision P3, 2026-11-03): below 10% and not charging it can't start ("הסוללה מתחת ל-10 אחוז. כדי להתחיל, צריך לחבר מטען."); 10–19% is a note, not a block: "כדאי לטעון קודם; הגיבוי ייעצר לבד ב-10% וימשיך אחר כך". While running, at 10% on battery it pauses by itself ("הגיבוי נעצר לבד כי הסוללה ירדה ל-10%. הוא ימשיך כשהטלפון ייטען.") and goes on when the phone charges.
- File names: "12 names changed to fit the drive ✓ (see Details)"

`[Start backup]` / `[להתחיל גיבוי]`

### 5.4 Running
- Screen + ongoing notification: "Backing up · 3,120 of 8,912 · 21.4 of 58.1 GB · about 40 min left" `[Pause]` `[Cancel]`.
- Two phases are visible: **Copying** then **Checking** ("Reading every file back from the drive to make sure it's identical.").
- Disconnect: "Drive disconnected. Plug it back in to continue. Nothing was deleted." / "הכונן נותק. חברו אותו שוב כדי להמשיך. שום דבר לא נמחק."

### 5.5 Receipt (gate to deletion)
> **Backup checked ✓**
> 8,909 files · 58.0 GB copied to "SanDisk › KeepClear › OnePlus 10T › Camera".
> Every file was read back from the drive and matched the original exactly.
> 3 files weren't copied (over 4 GB for this drive). They stay on your phone.
> `[Check backup]` re-reads the drive any time and tells you if anything changed (MOVE-15).
> `Details ›` (Advanced: SHA-256 per file, the `SHA256SUMS` file, the drive's format)
> The copy on the drive is not encrypted. Anyone with this drive can open these files, so keep it somewhere safe.
> To get files back later, open `KeepClear/…` on the drive with the phone's Files app (in-app "Restore from backup" comes in v1).
> `[Free 58.0 GB from phone]` `[Keep them for now]`

> **הגיבוי נבדק ✓**
> ‏8,909 קבצים · 58.0GB הועתקו אל "SanDisk › KeepClear › OnePlus 10T › Camera".
> כל קובץ נקרא בחזרה מהכונן ונמצא זהה לגמרי למקור.
> ‏3 קבצים לא הועתקו (גדולים מ-4GB לכונן הזה). הם נשארים בטלפון.
> `[לבדוק את הגיבוי]` קורא שוב את הכונן בכל זמן ומראה אם משהו השתנה.
> `פרטים ›` (מתקדם: SHA-256 לכל קובץ, הקובץ `SHA256SUMS`, הפורמט של הכונן)
> העותק בכונן לא מוצפן. כל מי שמחזיק בכונן יכול לפתוח את הקבצים, אז כדאי לשמור אותו במקום בטוח.
> כדי להחזיר קבצים בעתיד, פותחים את `KeepClear/…` בכונן באפליקציית הקבצים של הטלפון (שחזור מתוך האפליקציה יגיע ב-v1).
> `[לפנות 58.0GB מהטלפון]` `[להשאיר בינתיים]`

"Keep them for now" saves the receipt; Home's MOVE card then shows "58 GB backed up and checked. Ready to free." Nothing is freed until the user taps it (P-3).

### 5.7 Check backup (MOVE-15, v1 first)
From a receipt or Settings › Backups: `[Check backup]` → plug in the drive (or sign in) → progress "Checking 8,909 files…" → result:
> **All 8,909 files are fine.** Checked today. / **3 files are missing or changed.** `[See which]` `[Copy them again]`

he: **כל 8,909 הקבצים תקינים.** / **‏3 קבצים חסרים או השתנו.** `[להציג אותם]` `[להעתיק שוב]`

### 5.6 Free from phone (confirm)
> **Free 58.0 GB?**
> 8,909 photos and videos will leave this phone. The checked copies stay on your drive.
> They'll wait in KeepClear's trash for 30 days, and that space returns when the trash empties. **Need the space now?** Since every file has a checked copy, you can release it right away.
> `[Move to trash (safest)]` `[Free the space now]` `[Cancel]`

Decided (Q1): "Move to trash" is the default action; "Free the space now" is the prominent release button. Hebrew: `[להעביר לסל המחזור (הכי בטוח)]` `[לפנות את המקום עכשיו]` `[ביטול]`. Before trashing, every original is re-hashed (strict mode, Q13); progress shows "Checking each photo one last time…" / "בודקים כל תמונה פעם אחרונה…".

**Owner decisions P1, P2, P4 (2026-11-03) replace the sheet above.** Shown only after "Check backup" passed:
> **לפנות מקום בטלפון?**
> כמה: ‏6,830 קבצים · 24.1GB · מה יקרה: יימחקו מהטלפון; העותק נשאר ב-SanDisk
> המקור יימחק מהטלפון. העותק נשאר ב-SanDisk, נבדק קובץ-קובץ, ואפשר לשחזר ממנו בכל זמן. (H2c: „לשחזר”, the terminology table's "bring back")
> (only when WhatsApp media is freed) בצ'אט יופיע שהקובץ לא זמין; העותק שמור בגיבוי
> ☑ להשאיר בטלפון מועדפים · ‏412 קבצים נשארים בטלפון
> ☑ להשאיר בטלפון את 30 הימים האחרונים · ‏96 קבצים נשארים בטלפון
> `[לפנות 24.1GB מהטלפון]` `[לבטל]`

- The two options are ticked by default and **keep** files (SAFETY-10 is about selecting for deletion). Unticking one adds its files to the count, the size and the tap's confirmation. If they keep everything: "עם הבחירות האלה אין מה לפנות." and the button does nothing.
- The button is ink, not red (C118): the checked copy is the undo.
- Progress: "נמחקו 400 מתוך 6,830" (trial mode: "נבדקו 400 מתוך 6,830 · מצב ניסיון, שום דבר לא נמחק").
- Result: "פונו 24.1GB · 6,830 קבצים מהטלפון. העותק נשאר ב-SanDisk." `[לשחזר מגיבוי]`; skipped files are counted and stay on the phone; if the drive goes away: "הפינוי נעצר: …".
- With Settings' "לשמור את המקור גם בסל 30 יום (לא מפנה מקום)" on, the sheet and result are the trash version above (H2c: title „להעביר את המקור לסל?”, the receipt's button „להעביר לסל 24.1GB” and the confirm „להעביר לסל”, never „לפנות”: nothing is freed until the trash is emptied; Empty now).

### 5.8 Backup screens after PR H1b (owner brief H1, 2026-11-03; replaces §5.1, §5.2, §5.4 and the receipt's stamp)

```
גיבוי
בשלב הזה רק מעתיקים. שום תמונה לא נמחקת.
[the latest job / receipt]
מה לגבות?  (age chips, source rows; hidden while a job runs)
──────────────── sticky, above the tabs ────────────────
נבחרו: ‏8,912 קבצים · 31.0GB
[ לגבות 31.0GB ]
```
- **Choose where (item 3).** The sheet follows the drives live: none plugged → „לחבר את הדיסק און קי לטלפון” · "KeepClear מזהה אותו ברגע שהוא מחובר." and `[לבחור את הכונן]` disabled; plugged → „זוהה ✓ SanDisk USB drive” · "במסך הבא להקיש על „שימוש בתיקייה הזו”." and Android's picker opens right at the drive. `[לבחור מקום אחר]` and the places set up under Advanced stay.
- **Running (items 4, 5).** „מגבים אל SanDisk” · „אפשר להמשיך להשתמש בטלפון · לא לנתק את הדיסק און קי · כדאי לחבר למטען” (network: without the drive part) · small label „מעתיקים” / „בודקים” · ONE line „12.9 מתוך 31.0GB · נשארו כ-41 דקות” (time left from a smoothed rate, nothing in the first 10 s) · progress bar · `[להשהות]` `[לעצור]`. TalkBack hears „הגיבוי הגיע ל-40%” in 10 % steps. Notification: same line, Pause / Stop; Android 16: a Live Update with two segments (copy, check) and „42%” in the status-bar chip.
- **Receipt (item 6).** Before the second check: „הועתק ✓ · שלב אחרון: בדיקה” and `[לבדוק את הגיבוי]`, no stamp. The stamp „נבדק קובץ-קובץ” only when every copy passed the second check. Re-plug: „לנתק את הדיסק און קי” → „ניתוק זוהה ✓ — לחבר שוב” → „בודקים…” → stamp. "Details" holds „הקבצים בכונן נשמרים כמו שהם, בלי הצפנה, כך שאפשר לפתוח אותם בכל מחשב.”
- **Partial (item 8).** „הסתיים עם 3 שגיאות” `[להציג]` opens the reasons and the file names.
- **Paused (item 8).** One message, one button `[להמשיך]`: „אין מספיק מקום בדיסק און קי: צריך 4.1GB, פנויים 2.3GB” (after a restart: „…: צריך עוד 4.1GB”), „הדיסק און קי נותק — לחבר ולהמשיך”, „הטלפון עצר את הגיבוי כדי לחסוך סוללה” (a job found running with no service and no user stop; PR H4 adds the real exit reason). The battery wait has no button: it goes on by itself.
- **Freed (item 7).** „פונו 31.0GB · עכשיו פנויים 47.8GB” (measured) · „פונו … מהטלפון. העותק נשאר ב-SanDisk.” · `[לשחזר מגיבוי]`.
- **On the drive (item 10).** `KeepClear/<phone>/README.html` next to the manifest: Hebrew then English, what the folder is, how to open it on a computer, how to restore with KeepClear. Not a backup file.
- **Reminder (item 11).** Settings: „להזכיר לבדוק את הגיבוי” (off). Three months after the last check: „עברו 3 חודשים מהבדיקה האחרונה — לחבר את הדיסק און קי לבדיקה חוזרת”; tapping opens Backup with "לחבר את הדיסק און קי, ואז להקיש על „לבדוק את הגיבוי”." `[לבדוק את הגיבוי]`.

---

## 6. Trash (TRASH-1…6)

```
Trash · 61.1 GB · 9,203 items
Space returns as items expire, or empty now.
─ Expires 30 Nov ─ Camera (moved, checked copy on SanDisk) · 58 GB   [Restore] [Empty]
─ Expires 30 Nov ─ Clean now: 5 groups · 3.1 GB                      [Restore] [Empty]
[Restore all]  [Empty trash now]
```
- Group rows expand to items with original path and preview.
- Items with a verified copy show "Checked copy on SanDisk" / "עותק שנבדק נמצא ב-SanDisk".
- Empty confirm: "Delete 61.1 GB permanently? 58 GB of it has a checked copy on your drive. 3.1 GB has no other copy." / "למחוק לצמיתות 61.1GB? ל-58GB מתוכם יש עותק שנבדק בכונן. ל-3.1GB אין עותק נוסף."
- Restore: "Restored 312 files to their folders." / "‏312 קבצים שוחזרו לתיקיות שלהם."
- Uninstall note (TRASH-7, always visible while the trash holds items): "Files in KeepClear's trash are kept in a hidden folder, .KeepClearTrash, on this phone. If you uninstall KeepClear, they stay there and keep taking space (now 61.1 GB). Restore or empty the trash first." / "הקבצים בסל המחזור של KeepClear נשמרים בתיקייה מוסתרת, ‎.KeepClearTrash‎, בטלפון. אם מסירים את KeepClear, הם נשארים שם וממשיכים לתפוס מקום (כרגע 61.1GB). כדאי לשחזר או לרוקן את הסל לפני כן." `[Restore all]` `[Empty trash]`

### 6.1 Trash and restore grid after PR H1c (owner brief H1 item 9, 2026-11-03)

```
Trash                                   ← back
3.0 GB · 6 files
[Show as a list]
( Search by name or date, e.g. July 2021 )
October 2026
[pic][pic][pic]
 12 Oct 2026  1.2 MB …
July 2021
[pic][pic]
[Restore all] [Free all …] [Empty trash now]
```
- Default is the grid (TRASH-8): month sections by the file's own date, newest first; 3 columns on a phone, more on tablets (adaptive, min 104 dp). A month header always spans the whole row, so at 200 % it wraps only between words („אוקטובר 2026”, never „אוקטוב/ר”).
- Tap a tile → details sheet: picture, „12 ביולי 2021 · 4.5MB”, days left, where it goes back, `[לשחזר]` (or `[לשחזר לתיקייה נפרדת]` for a changed file) and `[לפנות עכשיו]` only with a checked copy.
- TalkBack: „IMG_20210714.jpg, 14 ביולי 2021, 4.5MB”; actions „לשחזר” · „להציג” · („לפנות עכשיו” only with a checked copy).
- `[להציג כרשימה]` shows the grouped list of §6 (Clean now / Review / Backup originals).
- Empty: „סל המחזור ריק. קבצים שמנקים נשארים כאן 30 יום.” `[לשחזר מגיבוי]`.

Restore from a backup (MOVE-17), as a sheet:
```
What to bring back
Where they go: back where they were, if KeepClear moved them from there on this phone.
Everything else goes to the restore folder, Pictures › KeepClear Restored. Nothing on the phone is replaced.
[Show by month]
( Search … )
December 2022
[✓pic][pic][▶]
…
2 files · 7.0 MB
Free on the phone: 1.0 GB. Chosen: 907 MB.
Not enough room … There is room for 2 of 3 files.  [Restore these now]
[Restore]
[Cancel]
```
- he: „לאן: למקום שבו היו, אם KeepClear העביר אותם משם בטלפון הזה. כל השאר לתיקיית השחזורים, תמונות › KeepClear Restored.” · „יש מקום ל-1,240 מתוך 6,830 קבצים.” `[לשחזר את אלה עכשיו]` (the newest that fit, C136).
- Tap = choose; long press or the TalkBack action „להציג” = preview with `[לשחזר את הקובץ הזה]`; TalkBack action „לשחזר את הקובץ הזה” restores that file alone.
- Pictures only from a drive on the phone (USB-C stick, SD card); a network drive or Nextcloud shows type icons (C139).
- Needs All files access: text + `[לפתוח את ההגדרות]` (`foss`). No backup found: `[לבחור מקום אחר]`. Not enough room at start: `[לבחור מה שנכנס]`.

---

## 7. Apps (MVP: sizes + cache trim; v1: unused apps)

- Header: "Apps take 59.8 GB." Main card for everyone (also on Home and in Clean now): **Clear app caches** — "Android clears data that apps can download again. Nothing personal is removed." `[Clear app caches]` (SAFE-5; result shows the measured space freed). In foss with all-files access the same button also runs Android's one-dialog external cache clear (SAFE-6). No Shizuku, no Accessibility.
- If Android freed little or nothing: "Android keeps some app data while the phone is this full, so it freed only 0 MB. You can clear the biggest apps one at a time instead." `[Clear one app at a time]` (SAFE-10).
- **Clear one app at a time** (SAFE-10, v1): list sorted by cache size, `[Open app info]` per row; after returning: "Chrome: 812 MB freed." KeepClear never taps anything for the user.
- App content cards (SAFE-9, v1): "Spotify keeps 4.2 GB of downloaded songs. Remove them inside Spotify: Settings › Storage › Remove all downloads." `[Open Spotify]` `[Open app info]`. Same for Telegram media, Netflix/YouTube downloads, etc. KeepClear never deletes inside another app's folder.
- List (with Usage access): app, size (app / data / cache), "last opened: about 7 months ago". Tap › `[Open app info]` (system, where "Clear cache"/"Uninstall" live).
- v1: "Not opened in about 6 months · 11 apps · 2.8 GB" bucket with `[Uninstall]` per app (system dialog).

---

## 8. Settings (SET-1)

- Language: System / English / עברית.
- Trash keeps items for: 30 days (7–60).
- Move defaults: older than 1 year (the first backup: everything); favourites included (owner decision P2, 2026-11-03).
- Permissions: list with status + "Manage" link for each.
- About: version, license GPL-3.0, source link, "KeepClear has no internet permission", trash survives uninstall note (TRASH-7), credits (SD Maid SE rules data etc.).
- **Advanced** (P-2, off the default path): file details (hashes, paths on the drive), the drive's format, export logs, strict-mode switch with its warning, and later expert options (e.g. Shizuku, Later). Nothing here is needed for normal use.

---

## 8a. Themes and screen sizes (UI-1…3)

- Light/dark follow the system; Settings › Appearance offers System / Light / Dark and "Use wallpaper colours" (Android 12+).
- Destructive buttons keep a fixed, high-contrast colour in both themes; risk chips use icon + text, never colour alone.
- Medium/expanded widths: Review hub (list) beside the bucket grid (detail); Trash list beside item detail; MOVE steps stay single-column, centred, max 600 dp.

## 9. Empty and error states

| State | en | he |
|---|---|---|
| Tiny junk | Only 120 MB of real junk. Most of your space is photos and videos — backing them up can help. | רק 120MB של קבצים מיותרים באמת. רוב המקום תפוס בתמונות ובסרטונים, ושם גיבוי יכול לעזור. |
| Partial media access | KeepClear sees only 40 photos you picked. Allow all to get real numbers. `[Change]` | KeepClear רואה רק 40 תמונות שבחרתם. כדי לקבל מספרים אמיתיים, אפשר לתת גישה לכולן. `[לשנות]` |
| Permission removed mid-job | Paused: access to your photos was removed. Nothing was deleted. `[Allow again]` | הושהה: הגישה לתמונות הוסרה. שום דבר לא נמחק. `[לאשר שוב]` |
| Almost full (light mode) | Your phone is almost full. KeepClear is using as little space as possible. | הטלפון כמעט מלא. KeepClear משתמש בכמה שפחות מקום. |
| Very full (minimal mode) | Less than 100 MB left. Start with these, they free space without needing any: | נשארו פחות מ-100MB. כדאי להתחיל מאלה, הם מפנים מקום בלי לצרוך מקום: |
| Some items skipped | 3 files changed since the scan, so we left them alone. | ‏3 קבצים השתנו מאז הסריקה, אז לא נגענו בהם. |
| Empty trash (H1c) | The trash is empty. Files you clean stay here for 30 days. `[Restore from a backup]` | סל המחזור ריק. קבצים שמנקים נשארים כאן 30 יום. `[לשחזר מגיבוי]` |
| No backup found (H1c) | No KeepClear backup was found there. `[Choose another place]` | לא נמצא שם גיבוי של KeepClear. `[לבחור מקום אחר]` |
| Search finds nothing (H1c) | No file matches this search. | אין קובץ שמתאים לחיפוש. |
| Nothing to choose (H2a) | Nothing to choose from right now. New screenshots and big videos show up here after the next scan. `[Back up photos]` | אין כרגע מה לבחור. צילומי מסך וסרטונים גדולים חדשים יופיעו כאן אחרי הסריקה הבאה. `[לגבות תמונות]` |
| Nothing to back up (H2a) | No photos or videos to back up right now. `[Free up space]` | אין כרגע תמונות או סרטונים לגבות. `[לפנות מקום]` |
| Empty lane on Home (H2a) | Nothing here right now | אין כאן כלום כרגע |
| Backup paused, drive full (H2a) | …then: Delete what you don't need from the USB drive, or choose fewer photos this time, then tap Continue. `[Continue]` | …ואז: אפשר למחוק מהדיסק און קי דברים שלא צריך, או לבחור פחות תמונות הפעם, ואז ללחוץ על „להמשיך”. `[להמשיך]` |
| Backup paused, phone full (H2a) | Free some space on the phone first, then tap Continue. `[Continue]` `[Free up space on the phone]` | כדאי קודם לפנות קצת מקום בטלפון, ואז ללחוץ על „להמשיך”. `[להמשיך]` `[לפנות מקום בטלפון]` |
| Backup paused, unexpected error (H2a) | Tap Continue. If it happens again, unplug the USB drive and plug it back in, or restart the phone. `[Continue]` | אפשר ללחוץ על „להמשיך”. אם זה קורה שוב, לנתק את הדיסק און קי ולחבר שוב, או להפעיל מחדש את הטלפון. `[להמשיך]` |

## 10. Gallery comparison — owner scope accepted 2026-11-08; read-only part implemented (G4a, 2026-11-09); REVIEW choices implemented locally (G4b, 2026-11-09)

**G4a status (local, not yet PR; [verification](../verification/gallery-g4a-ui-2026-11-09.md)).** Implemented: the entry card at the top of „לבחור מה למחוק” / "Choose what to delete"; the run screen (scope in plain words, „N מתוך M תמונות” with the skipped count, Stop / Continue, honest partial and „כל … התמונות” only when nothing was partial, skipped or limited, low-space and file-action pauses, access changed, no access with the existing permission request, technical counts under „לראות פרטים”); the groups list (photo count, thumbnails, the marker's reasons or „אין המלצה ברורה”, a note when a group holds memories; no bytes); the comparison (date and size per photo, „מומלץ להשאיר” with one or two reasons or abstention, no checkbox, nothing preselected, no delete button); „לגבות” for memories opening the Backup tab; „להשאיר את כולן הפעם” (this session only) and the existing per-photo „לא להציע את זה שוב” (preview screen and TalkBack action). Not yet: REVIEW choices and their confirmation (G4b), persisted review decisions across process death (G4a-2), swipe review of a group. Keep/Skip/Undo below therefore apply from G4b on.

**G4b status (local, not yet a PR; [verification](../verification/gallery-g4b-2026-11-09.md)).** In the comparison, other photos (REVIEW) are checkboxes, none chosen at first, under the line „הקישו על התמונות שאתם לא צריכים כדי לבחור אותן להעברה לסל; לחיצה ארוכה מראה תמונה גדולה. לפחות תמונה אחת מכל קבוצה של תמונות דומות תמיד נשארת.”; the hint above becomes „זו רק המלצה, שום דבר לא נבחר עד שתקישו על תמונה למטה.”. Memories stay buttons with „לגבות” only. The last unchosen photo of the group (or the last available photo of an overlapping group) is not offered; a tap on it opens it. „למחוק …” with the exact count and size opens the existing editable confirmation („למחוק …?” with the size, how many, how much, „לסל המחזור, ל-30 יום”, ✕ per photo, „להעביר לסל”, „לבטל”). After Android's own dialog the existing result says the photos moved to the gallery's bin, with „חזרה” (Android asks again and puts exactly those back: „… שוחזרו מסל הגלריה.”); test mode shows the simulated result. A group refused because a kept photo changed says so on its own line. Choices last for this session only and drop when a photo or the group changes; nothing is restored after the app closes. Not yet: Keep/Skip as stored decisions, swipe review of a group.


Entry within existing review/navigation: **Find similar photos** / **למצוא תמונות דומות**. State the currently authorized scope and incomplete/selected-photo status, then show calm analyzed/eligible/skipped progress and Stop. Do not add a paid tier, sign-in, model download or a new Home safe-bytes hero. Technical router/model details belong under Advanced, not default copy.

Small virtual groups show pictures/date/size/source plus a **Suggested to keep** / **מומלץ להשאיר** marker with an actual reason, such as **Sharper** / **חדה יותר** or **Higher resolution** / **רזולוציה גבוהה יותר**. Start from deterministic quality facts; only admitted local AI may refine the bounded ambiguous subset. When facts do not support a meaningful preference, show **No clear recommendation** / **אין המלצה ברורה**. These labels are planned copy for bilingual implementation, not new resource keys shipped by this documentation change.

The recommendation marker is separate from selection and never chooses another image for deletion. Keep/Skip/Dismiss/Undo remain explicit; accepting/disagreeing with advice changes no source file. Persistent “do not offer again” and “keep this time” remain distinct. MOVE camera/received memories offer the existing backup handoff, not direct gallery disposal. Explicit eligible REVIEW choices alone enter the existing editable count/byte/destination confirmation, ActionExecutor and trash after the new live keep-one guard passes.

Resolved/no-match/unsupported work does not load a model; budget exhaustion shows valid deterministic results plus unresolved/deferred count, without automatically starting another run. Cache clearing affects suggestions only, never photos or backup/trash records. Preserve existing en/he terminology, bidi, TalkBack, large text, physical configurable swipe mapping and Paper & Ink. Detailed contracts: [requirements](gallery-requirements.md), [design](gallery-design.md), [edge cases](gallery-edge-cases.md).
