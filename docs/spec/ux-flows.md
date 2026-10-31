# KeepClear: MVP UX flows

Status: **Draft for owner approval** · Date: 2026-10-31
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

Notation: `[Button]`, `(chip)`, `›` = navigates to. Copy shown en / he. Hebrew lines that start with a digit get an RLM (U+200F) in resources.

---

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
| All files (foss) | **See all your files.** Finds WhatsApp media, folders left from your old phone, and other apps' recycle bins. Android still keeps other apps' private data closed. Without it: you can give access folder by folder. | **לראות את כל הקבצים.** כדי למצוא מדיה של וואטסאפ, תיקיות שנשארו מהטלפון הקודם וסלי מחזור של אפליקציות אחרות. גם עם ההרשאה הזו, אנדרואיד לא חושף נתונים פרטיים של אפליקציות אחרות. בלי זה: אפשר לתת גישה תיקייה אחרי תיקייה. |
| WhatsApp folder (SAF, play) | **Let KeepClear see WhatsApp's media folder.** On the next screen, tap **Use this folder** at the bottom. We only read photos, videos and voice notes. Your chats and backups are never touched. | **לתת ל-KeepClear לראות את תיקיית המדיה של וואטסאפ.** במסך הבא, הקישו על **שימוש בתיקייה הזו** למטה. אנחנו קוראים רק תמונות, סרטונים והודעות קוליות. הצ'אטים והגיבויים לא נוגעים בהם אף פעם. |
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
- Trash chip top-end shows trash size; tap › Trash.

Hebrew card titles: **לנקות עכשיו** / **לבדוק** / **להעביר לגיבוי**. Buttons: `[לנקות 7.9GB]`, `[לבדוק ›]`, `[להתחיל ›]`.

---

## 3. Clean now (SAFE) (SAFE-1…8)

### 3.1 SAFE details
List of groups, each pre-selected, each with count, bytes, risk chip and expandable "Why":

| Group | Chip | Why (en) | למה (he) |
|---|---|---|---|
| Other apps' recycle bins (3.8 GB) | Already deleted | Files you already deleted in another app. They're waiting in that app's bin. | קבצים שכבר מחקתם באפליקציה אחרת, והם מחכים בסל המחזור שלה. |
| WhatsApp: copies of photos you sent (x GB) | Copy exists | Exact copies of photos that are still in your Camera folder. We checked: same content, byte for byte. | עותקים מדויקים של תמונות שעדיין נמצאות בתיקיית המצלמה. בדקנו: התוכן זהה לגמרי. |
| Unpacked zip files (x GB) | Copy exists | The folder next to each zip already has all of its files. | בתיקייה שליד כל קובץ zip כבר נמצאים כל הקבצים שבתוכו. |
| Viewed WhatsApp statuses | Rebuilt automatically | Statuses you watched. They expire after 24 hours anyway. | סטטוסים שכבר צפיתם בהם. הם פגים אחרי 24 שעות בכל מקרה. |
| Thumbnails, temp files, empty folders | Rebuilt automatically | Small preview images and leftovers that apps recreate if needed. | תמונות תצוגה מקדימה וקבצים זמניים שאפליקציות יוצרות מחדש לפי הצורך. |
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
Older than: [ 1 year ▾ ]   ☐ Include favourites
Total: 58.1 GB · 8,912 files
[Next: choose where]
```
he: **מה להעביר?** · "תמונות וסרטונים מהמצלמה" · "תמונות וסרטונים שקיבלתם בוואטסאפ" · "רק סרטונים גדולים" · "ישנים מ: שנה" · "לכלול מועדפים" · `[הבא: לבחור לאן]`

### 5.2 Choose where
Cards:
- **USB-C drive or SD card** (recommended): "Plug in a drive. Fast enough, no internet, and we can check every file." `[Choose drive]` → SAF tree picker.
- **Computer on your Wi-Fi** (v1, greyed "Coming soon").
- **Google Drive / other cloud apps**: "You can copy there, but we can't check the copy, so KeepClear won't delete the originals." (MOVE-14)
- Info line: "Using Google Photos? KeepClear can't see what's in it. Check there, or use its Free up space." `[Open Google Photos]` (MOVE-16)

he for the first card: **כונן USB-C או כרטיס SD** — "מחברים כונן. מספיק מהיר, בלי אינטרנט, ואפשר לבדוק כל קובץ."

### 5.3 Pre-flight check
Checklist screen, each line ✓ / ✕ with a fix:
- Space on drive: "120 GB free, 58.1 GB needed ✓"
- Drive format: "FAT32: 3 videos are over 4 GB and can't be copied to it ✕ — [Copy the rest] [How to fix]"
- Battery: "Plug in your phone. Copying 58 GB takes about 1–1.5 hours." (estimate from measured speed after first 200 MB)
- File names: "12 names changed to fit the drive ✓ (see Details)"

`[Start backup]` / `[להתחיל גיבוי]`

### 5.4 Running
- Screen + ongoing notification: "Backing up · 3,120 of 8,912 · 21.4 of 58.1 GB · about 40 min left" `[Pause]` `[Cancel]`.
- Two phases are visible: **Copying** then **Checking** ("Reading every file back from the drive to make sure it's identical.").
- Disconnect: "Drive disconnected. Plug it back in to continue. Nothing was deleted." / "הכונן נותק. חברו אותו שוב כדי להמשיך. שום דבר לא נמחק."

### 5.5 Receipt (gate to deletion)
> **Backup checked ✓**
> 8,909 files · 58.0 GB copied to "SanDisk › KeepClear › OnePlus 10T › Camera".
> Every file was read back from the drive and matched the original exactly (SHA-256 check).
> 3 files weren't copied (over 4 GB for this drive). They stay on your phone.
> You can check this backup on a computer too: `SHA256SUMS` is on the drive.
> `[Free 58.0 GB from phone]` `[Keep them for now]`

> **הגיבוי נבדק ✓**
> ‏8,909 קבצים · 58.0GB הועתקו אל "SanDisk › KeepClear › OnePlus 10T › Camera".
> כל קובץ נקרא בחזרה מהכונן ונמצא זהה לגמרי למקור (בדיקת SHA-256).
> ‏3 קבצים לא הועתקו (גדולים מ-4GB לכונן הזה). הם נשארים בטלפון.
> אפשר לבדוק את הגיבוי גם במחשב: הקובץ `SHA256SUMS` נמצא בכונן.
> `[לפנות 58.0GB מהטלפון]` `[להשאיר בינתיים]`

"Keep them for now" saves the receipt; Home's MOVE card then shows "58 GB backed up and checked. Ready to free."

### 5.6 Free from phone (confirm)
> **Free 58.0 GB?**
> 8,909 photos and videos will leave this phone. The checked copies stay on your drive.
> They'll wait in KeepClear's trash for 30 days, and that space returns when the trash empties. **Need the space now?** Since every file has a checked copy, you can release it right away.
> `[Move to trash (safest)]` `[Free the space now]` `[Cancel]`

(Final wording depends on owner answer Q1 in [requirements.md](requirements.md) §14.)

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

---

## 7. Apps (MVP: sizes + cache trim; v1: unused apps)

- Header: "Apps take 59.8 GB." Card: `[Let Android trim caches]` (SAFE-5).
- foss + all-files: `[Clear all apps' external caches]` (system dialog, SAFE-6).
- List (with Usage access): app, size (app / data / cache), "last opened: about 7 months ago". Tap › `[Open app info]` (system, where "Clear cache"/"Uninstall" live).
- v1: "Not opened in about 6 months · 11 apps · 2.8 GB" bucket with `[Uninstall]` per app (system dialog).

---

## 8. Settings (SET-1)

- Language: System / English / עברית.
- Trash keeps items for: 30 days (7–60).
- Move defaults: older than 1 year; include favourites off.
- Permissions: list with status + "Manage" link for each.
- About: version, license GPL-3.0, source link, "KeepClear has no internet permission", trash survives uninstall note, credits (SD Maid SE rules data etc.).

---

## 9. Empty and error states

| State | en | he |
|---|---|---|
| Tiny junk | Only 120 MB of real junk. Most of your space is photos and videos — Move to backup can help. | רק 120MB של קבצים מיותרים באמת. רוב המקום תפוס בתמונות ובסרטונים, ושם "העברה לגיבוי" יכולה לעזור. |
| Partial media access | KeepClear sees only 40 photos you picked. Allow all to get real numbers. `[Change]` | KeepClear רואה רק 40 תמונות שבחרתם. כדי לקבל מספרים אמיתיים, אפשר לתת גישה לכולן. `[לשנות]` |
| Permission removed mid-job | Paused: access to your photos was removed. Nothing was deleted. `[Allow again]` | הושהה: הגישה לתמונות הוסרה. שום דבר לא נמחק. `[לאשר שוב]` |
| Almost full (light mode) | Your phone is almost full. KeepClear is using as little space as possible. | הטלפון כמעט מלא. KeepClear משתמש בכמה שפחות מקום. |
| Very full (minimal mode) | Less than 100 MB left. Start with these, they free space without needing any: | נשארו פחות מ-100MB. כדאי להתחיל מאלה, הם מפנים מקום בלי לצרוך מקום: |
| Some items skipped | 3 files changed since the scan, so we left them alone. | ‏3 קבצים השתנו מאז הסריקה, אז לא נגענו בהם. |
