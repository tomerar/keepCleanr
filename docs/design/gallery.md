# Gallery: before → after

What the owner's review of main **25a3be7** (2026-11-02) saw, next to the app after PRs F1a, F1b, F2 and F3. Hebrew, light theme
(dark Home last), phone size (360 × 800 dp), on the owner-shaped phone of the screenshot tests (`OwnerPhone`: 128 GB with 16.8 GB
free, 7.9 GB to clean, 2,324 screenshots, 526 AI images, 81 big videos; progress C97).

Where the "before" comes from (progress C103):

- **Home, Review group, dark Home:** the screenshots committed at 25a3be7 (`app/src/test/screenshots/`). Their fixture had no scan
  summary, which is why Home says "סופרים…" on every card: that is what the store screenshot showed too.
- **ConfirmSheet, Trash, Backup summary:** 25a3be7 had no screenshot of these. They were rendered from a worktree checked out at
  25a3be7, with the same fixture as "after". At 25a3be7 the clean confirmation was an `AlertDialog` on the "Clean now" screen (Home
  had no main button yet), so that is the "before" for the ConfirmSheet.

The "after" images are the screenshot tests (`app/src/test/screenshots/*_iw_light_api36.png`), scaled to 360 px wide.

| Screen | Before (25a3be7) | After |
|---|---|---|
| Home | ![Home before](gallery/before/home.png) | ![Home after](gallery/after/home.png) |
| Review group (screenshots) | ![Review group before](gallery/before/review_group.png) | ![Review group after](gallery/after/review_group.png) |
| ConfirmSheet (clean) | ![Clean confirmation before](gallery/before/confirm_clean.png) | ![ConfirmSheet after](gallery/after/confirm_clean.png) |
| Trash | ![Trash before](gallery/before/trash.png) | ![Trash after](gallery/after/trash.png) |
| Backup summary | ![Backup summary before](gallery/before/backup_summary.png) | ![Backup summary after](gallery/after/backup_summary.png) |
| Home, dark | ![Dark Home before](gallery/before/home_dark.png) | ![Dark Home after](gallery/after/home_dark.png) |

Every other screen, in English and Hebrew, light and dark, is in `app/src/test/screenshots/` (progress C96).

## Design QA (owner, 2026-11-02)

The owner's design QA of the F2/F3 gallery, items 1–8 (progress, "PR G design QA"). **Before** is this gallery's "after"
above (main 3f38cdf; the result banner from main's screenshot); **after** is the screenshot tests at this PR, Hebrew,
light theme, scaled to 360 px wide. The fixture's clock is now 2026-11-01 (item 7), so dates on screen are this autumn.

| Item | Screen | Before (3f38cdf) | After |
|---|---|---|---|
| 2 | Home: the strip and its legend in the same order (לנקות, לבחור, לגבות, אחר, פנוי), colour dots and sizes | ![Home before](gallery/qa-before/home.png) | ![Home after](gallery/qa-after/home.png) |
| 3 | Trash: the list first, a one-line header, „מסל הגלריה” for the gallery's file, „להסתיר את הרשימה” | ![Trash before](gallery/qa-before/trash.png) | ![Trash after](gallery/qa-after/trash.png) |
| 3 | Trash, scrolled down: „לשחזר הכול” and „לפנות עותקים שנבדקו” tonal, „לרוקן את הסל” outlined red | (the red buttons were at the top, see above) | ![Trash actions after](gallery/qa-after/trash_actions.png) |
| 4 | Backup summary: „לפנות 31.0GB מהטלפון” with the count under it | ![Backup summary before](gallery/qa-before/backup_summary.png) | ![Backup summary after](gallery/qa-after/backup_summary.png) |
| 5 (and 1) | ConfirmSheet: the title is only the question; the rows say it is deleted for good and why; the button is ready (filled) | ![ConfirmSheet before](gallery/qa-before/confirm_clean.png) | ![ConfirmSheet after](gallery/qa-after/confirm_clean.png) |
| 1 | Result after cleaning: the measured result („פונו … · עכשיו פנויים …”) | ![Result before](gallery/qa-before/result.png) | ![Result after](gallery/qa-after/result.png) |

On the owner-shaped phone everything "Clean" offers (WhatsApp statuses, thumbnails, temp files, the gallery's bin) is
one of the two kinds deleted for good, so the sheet has no trash part and the banner has no Undo. A plan that mixes both
shows two rows ("deleted for good now" and "to the trash, for 30 days") and the banner adds „<size> בסל, יתפנו ב-…”
with Undo and "Empty now" (`DesignQaTest`).
