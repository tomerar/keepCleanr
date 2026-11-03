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

## App icon options (PR H3)

Owner brief H3 item 6: three Paper & Ink adaptive icons (our own vectors in `app/src/main/res/drawable/ic_launcher_option{1,2,3}_*.xml`):
paper background, ink with the three lane colours, and a monochrome layer for themed icons. Each column is one option; the
rows are the full 108 dp square, the launcher's circle mask, and the themed icon (the monochrome layer tinted by the system).
**Option 1 is the launcher icon now** (and, in white, the notification icon); the owner picks one later (progress W16).

| | Light | Dark |
|---|---|---|
| Options 1 · 2 · 3 | ![Icon options, light](../../app/src/test/screenshots/icon_options_iw_light_api36.png) | ![Icon options, dark](../../app/src/test/screenshots/icon_options_iw_dark_api36.png) |

1. **Strip stack:** an ink frame (the phone's storage) holding three stacked rounded bars, sage, slate and amber, shortest last.
2. **Strip and check:** the Home storage strip (sage, slate, amber, then free paper) in an ink outline under an ink check mark.
3. **Ring:** an ink ring with three bars inside, tall to short, in the lane colours.

## Help, history and About (PR H3)

Hebrew, light theme, from the screenshot tests (`H3ScreenshotTest`; English and dark are next to them in `app/src/test/screenshots/`).

| Help | A topic | Report a problem | After a crash | What KeepClear did | About |
|---|---|---|---|---|---|
| ![Help](../../app/src/test/screenshots/help_iw_light_api36.png) | ![Help topic](../../app/src/test/screenshots/help_topic_iw_light_api36.png) | ![Report](../../app/src/test/screenshots/report_iw_light_api36.png) | ![Crash card](../../app/src/test/screenshots/crash_card_iw_light_api36.png) | ![History](../../app/src/test/screenshots/history_iw_light_api36.png) | ![About](../../app/src/test/screenshots/about_iw_light_api36.png) |

## Final polish (H1–H5)

The owner's final-polish brief (2026-11-03), PR H6. **Before** is main **de5e351**, the commit the H round started from (PR H1a's parent): the
screenshots committed there (`git show de5e351:app/src/test/screenshots/<screen>_iw_light_api36.png`). Where de5e351 had no
screenshot of the screen, the row says "new screen" and why (progress C196). **After** is the screenshot tests at PR H6
(`app/src/test/screenshots/*_iw_light_api36.png`). Both Hebrew, light theme, on the owner-shaped phone (`OwnerPhone`),
scaled to 360 px wide like the rows above. English, dark, 200 % and tablet (`tablet_*`) are next to them in
`app/src/test/screenshots/`. Also new in PR H6: the Trash chip no longer cuts its words on a 360 dp phone (progress C197),
so every main tab's top bar in "after" differs from H5's screenshots there.

| Screen | Before (de5e351) | After |
|---|---|---|
| First-run Home: one access card, lanes say „יופיע אחרי האישור” (H2 item 2) | ![First-run Home before](gallery/h-before/home_access.png) | ![First-run Home after](gallery/h-after/home_access.png) |
| Clean confirm: „מה יקרה”, red only for other apps' bins, the reassurance line (H2 item 5) | ![Clean confirm before](gallery/h-before/confirm_clean.png) | ![Clean confirm after](gallery/h-after/confirm_clean.png) |
| Right after cleaning: the cleaned items gone from the totals, the hero at the measured free space (H2 item 6) | ![Result before](gallery/h-before/result.png) | ![Result after](gallery/h-after/result.png) |
| „הכול נקי ✓” the next day, pointing to the backup (H2 item 6) | New state: de5e351 had no screenshot of Home with nothing left to clean (its hero offered the zero size) | ![All clean after](gallery/h-after/home_all_clean.png) |
| Backup tab: „גיבוי”, „בשלב הזה רק מעתיקים”, the sticky „לגבות” bar (H1 items 1–2) | ![Backup before](gallery/h-before/backup.png) | ![Backup after](gallery/h-after/backup.png) |
| Backup mode: the guidance lines and one progress line (H1 item 4) | ![Backup mode before](gallery/h-before/backup_progress.png) | ![Backup mode after](gallery/h-after/backup_progress.png) |
| Copied, before the second check: „הועתק ✓ · שלב אחרון: בדיקה”, no stamp yet (H1 item 6) | ![Summary before](gallery/h-before/backup_summary.png) (de5e351 showed the stamp before asking to check again) | ![Copied after](gallery/h-after/backup_copied.png) |
| Free confirm: the honest sentence, the WhatsApp line, „להשאיר בטלפון” options ticked (P1, P2, P4) | No screenshot at de5e351: its confirmation (a sheet that moved the originals to the trash, without options) was never recorded | ![Free confirm after](gallery/h-after/free_confirm.png) |
| After freeing: the measured result with „לשחזר מגיבוי” (H1 item 7) | New screen: no screenshot at de5e351 | ![Free result after](gallery/h-after/free_result.png) |
| Trash: a grid of pictures by month (H1 item 9) | ![Trash before](gallery/h-before/trash.png) | ![Trash after](gallery/h-after/trash.png) |
| Restore from a backup: a sheet, by month, „לתיקיית השחזורים” (H1 item 9) | ![Restore before](gallery/h-before/restore.png) | ![Restore after](gallery/h-after/restore.png) |
| Swipe: „לסיים ולבדוק”, equal Keep and Delete, „חזרה”, the date (H2 item 8) | ![Swipe before](gallery/h-before/swipe.png) | ![Swipe after](gallery/h-after/swipe.png) |
| Help (H3 item 1) | New screen: de5e351 had no Help | ![Help after](gallery/h-after/help.png) |
| App icon: three Paper & Ink options; option 1 is in the app (H3 item 6, W16) | ![Icon before](gallery/h-before/icon_options.png) | ![Icon options after](gallery/h-after/icon_options.png) |

**The re-plug check** (H1 item 6), a new screen: its live states, then the stamp.

| Unplug | Unplugged ✓, plug it back in | Checking | Checked |
|---|---|---|---|
| ![Unplug](gallery/h-after/backup_check_unplug.png) | ![Plug back](gallery/h-after/backup_check_plugback.png) | ![Checking](gallery/h-after/backup_check_checking.png) | ![Checked](gallery/h-after/backup_check_done.png) |

**Intro** (H2 item 1): de5e351 had one intro picture (the first page, no illustration, its counter read "3 / 1" in Hebrew); now each of the three pages has its own picture and „1 מתוך 3”.

| Before (de5e351) | Page 1 | Page 2 | Page 3 |
|---|---|---|---|
| ![Intro before](gallery/h-before/intro.png) | ![Intro 1](gallery/h-after/intro_1.png) | ![Intro 2](gallery/h-after/intro_2.png) | ![Intro 3](gallery/h-after/intro_3.png) |

The icon's "before" is de5e351's placeholder (a white check in a ring on the old green, square and in the launcher's
circle), drawn from its own vector (`ic_launcher_foreground.xml` at de5e351) because no screenshot of it existed.
