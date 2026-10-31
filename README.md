# KeepClear

**Keep what matters. Clear the rest.**

Smart storage cleaner for Android. Deletes junk in one tap, explains anything unclear, and backs up your photos before freeing space. Open source, no ads, no tracking.

## How it handles files

| Category | What it means |
|----------|---------------|
| **SAFE** | Junk. Deleted in one tap. |
| **REVIEW** | Explained to you. You decide. |
| **MOVE** | Memories. Backup, verify, then delete. |

## Core principle

> Delete junk instantly, explain anything unclear, never remove memories without a verified backup. Every deletion goes to a 30-day trash first.

## Status

🚧 Early development. No release yet. Research and spec live in [`docs/`](docs/) ([requirements](docs/spec/requirements.md), [design](docs/spec/design.md), [plan](docs/spec/tasks.md)).

## Building

Requires JDK 21 and the Android SDK (platform `android-37.0`).

```sh
./gradlew :app:assembleFossDebug   # F-Droid/GitHub flavor
./gradlew ciCheck                  # everything CI checks
```

## License

[GPL-3.0](LICENSE)

---

<div dir="rtl">

## KeepClear (עברית)

**שומרים את מה שחשוב. מנקים את השאר.**

מנקה אחסון חכם לאנדרואיד. מוחק קבצי זבל בלחיצה אחת, מסביר כל דבר לא ברור, ומגבה את התמונות שלך לפני שהוא מפנה מקום. קוד פתוח, בלי פרסומות, בלי מעקב.

### איך הוא מטפל בקבצים

- **SAFE (בטוח):** קבצי זבל. נמחקים בלחיצה אחת.
- **REVIEW (לבדיקה):** מקבלים הסבר, ואתה מחליט.
- **MOVE (העברה):** זיכרונות. גיבוי, אימות, ורק אז מחיקה.

### העיקרון המרכזי

> מוחקים זבל מיד, מסבירים כל דבר לא ברור, ולעולם לא מוחקים זיכרונות בלי גיבוי מאומת. כל מחיקה עוברת קודם לסל מחזור ל-30 יום.

### סטטוס

🚧 בפיתוח ראשוני. עדיין אין גרסה.

### רישיון

[GPL-3.0](LICENSE)

</div>
