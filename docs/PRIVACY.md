# KeepClear Privacy Policy

Effective date: **[set at first release]** · Applies to: KeepClear for Android, all builds (`foss` and `play`).
Published at: https://github.com/tomerar/keepCleanr/blob/main/docs/PRIVACY.md (a GitHub Pages copy may be added later; this file is the source).

## Short version

KeepClear works only on your phone. It does not collect, send, sell or share any data. It has no ads, no analytics, no crash reporting and no account. The current version cannot connect to the internet at all.

## What KeepClear accesses, and why

| Access | Why | Leaves the phone? |
|---|---|---|
| Photos and videos (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`) | To find screenshots, large videos, duplicates and memories to back up. | No |
| All files access (`MANAGE_EXTERNAL_STORAGE`, `foss` build only) | To find WhatsApp media, folders from an old phone, other apps' recycle bins and leftover files. | No |
| Folders you pick (Storage Access Framework) | To read a folder you chose (e.g. WhatsApp media) or write backups to a drive you chose. | Only to the drive or folder you picked |
| Usage access (`PACKAGE_USAGE_STATS`) | To show how much space each app uses and when it was last opened. | No |
| List of apps (`QUERY_ALL_PACKAGES` in `foss`; launcher apps only in `play`) | To match folders and caches to the apps that own them. | No |
| Notifications (`POST_NOTIFICATIONS`) | To show backup progress. | No |
| Foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) | To keep a backup running while the screen is off. | No |
| Media management (`MANAGE_MEDIA`, optional) | To move items to the trash without an extra Android confirmation each time. | No |

All of these are optional. KeepClear explains each one before Android asks you, and works with less if you say no.

## Data KeepClear stores on your phone

- An index of scanned files (paths, sizes, dates, content fingerprints), kept in KeepClear's private storage.
- Its trash: files you deleted, kept for 30 days in a hidden folder on the same storage (`.KeepClearTrash`), each with a small description file. Other apps with file access can see this folder, just like any gallery's "Recently deleted".
- Backup receipts (what was copied where, and when).

You can delete all of it by clearing KeepClear's storage in Android settings, or by uninstalling. The trash folder stays after uninstall so you can still recover files; KeepClear tells you this and offers to empty it.

## Backups

When you move files to a drive or computer you choose, KeepClear copies them there and writes a list of the copied files with their checksums (`keepclear-manifest.jsonl`, `SHA256SUMS`). These files contain file names and paths. KeepClear does not encrypt backups in this version; keep the drive safe.

## Network

The current version does not have the Android `INTERNET` permission. If a future version adds backup to a computer or server on your network, it will connect only to the address you type in, and this policy will be updated before that release.

## Children

KeepClear does not collect data from anyone, including children.

## Changes

Changes to this policy are made in this file in the public repository, with history at https://github.com/tomerar/keepCleanr/commits/main/docs/PRIVACY.md. Any change in what KeepClear accesses is listed in the release notes.

## Contact

Open an issue at https://github.com/tomerar/keepCleanr/issues, or for security matters see [SECURITY.md](../SECURITY.md).

---

<div dir="rtl">

## מדיניות פרטיות בקצרה

KeepClear עובד רק בטלפון שלכם. הוא לא אוסף, לא שולח, לא מוכר ולא משתף שום מידע. אין בו פרסומות, אין ניתוח שימוש, אין דיווחי קריסה ואין חשבון. בגרסה הנוכחית אין לו בכלל הרשאת אינטרנט. קבצים יוצאים מהטלפון רק לכונן או לתיקייה שבחרתם לגיבוי.

</div>
