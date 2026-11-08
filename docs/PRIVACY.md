# KeepClear Privacy Policy

Effective date: **[set at first release]** · Applies to: KeepClear for Android, all builds (`foss` and `play`).
Published at: https://github.com/tomerar/keepCleanr/blob/main/docs/PRIVACY.md (a GitHub Pages copy may be added later; this file is the source).

## Short version

KeepClear works only on your phone. It does not collect, sell or share your data with KeepClear or tracking/AI services. It has no ads, no analytics, no automatic crash reporting and no account. It connects to the network only if you set up a backup place on the network (a shared folder on your computer or NAS, or your own Nextcloud under Settings › Advanced), and then only to the address you typed.

## What KeepClear accesses, and why

| Access | Why | Leaves the phone? |
|---|---|---|
| Photos, videos and audio (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`; `READ_MEDIA_VISUAL_USER_SELECTED` for Android 14 "selected photos"; `READ_EXTERNAL_STORAGE` on Android 11–12) | To find screenshots, large videos, duplicates and memories to back up. | No |
| All files access (`MANAGE_EXTERNAL_STORAGE`, `foss` build only) | To find WhatsApp media, folders from an old phone, other apps' bins and leftover files. | No |
| Folders you pick (Storage Access Framework) | To read a folder you chose (e.g. WhatsApp media) or write backups to a drive you chose. | Only to the drive or folder you picked |
| Usage access (`PACKAGE_USAGE_STATS`) | To show how much space each app uses and when it was last opened. | No |
| List of apps (`QUERY_ALL_PACKAGES` in `foss`; launcher apps only in `play`) | To match folders and caches to the apps that own them. | No |
| Uninstalling an app (`REQUEST_DELETE_PACKAGES`) | To open Android's own uninstall window when you tap Uninstall in Apps, and on Android 15+ to ask Android to archive an app when you tap Archive (the app goes, its data stays). Android asks you to confirm either way; KeepClear never removes an app by itself. | No |
| Network (`INTERNET`) | Only to back up to a network drive or a Nextcloud you set up, at the address you typed. | Only the files you back up, to that address (for Nextcloud also the sign-in, to that server) |
| Notifications (`POST_NOTIFICATIONS`) | To show backup progress. | No |
| Promoted notifications (`POST_PROMOTED_NOTIFICATIONS`, Android 16) | To show backup progress as a Live Update (status-bar chip, lock screen); a normal permission. | No |
| Foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) | To keep a backup running while the screen is off. | No |
| Background jobs (`RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, added by Android's WorkManager) | To empty trash items once their 30 days are over, also after a restart. | No |
| Media management (`MANAGE_MEDIA`, optional) | To move items to the trash without an extra Android confirmation each time. | No |

All of these are optional. KeepClear explains each one before Android asks you, and works with less if you say no.

## Data KeepClear stores on your phone

- An index of scanned files (paths, sizes, dates, content fingerprints), kept in KeepClear's private storage.
- Its trash: files you deleted, kept for 30 days in a hidden folder on the same storage (`.KeepClearTrash`), each with a small description file. Other apps with file access can see this folder, just like any gallery's "Recently deleted".
- Backup receipts (what was copied where, and when).
- "What KeepClear did" (Settings): for each action (cleaned, trash emptied, backed up, space freed, restored) its time, kind, number of files and size, plus the total space freed. No file names or paths. In KeepClear's private storage, never in a cloud backup or device transfer.
- If KeepClear closes unexpectedly: where in its own code it stopped (a stack trace with no messages, paths or file names), in KeepClear's private storage, so the next launch can offer to share it. Android's own record of how KeepClear's process ended is read on the phone for the same card.
- A one-by-one review you left before finishing ("Continue where you left off"): the paths, sizes and dates of the files you decided on and what you chose, in KeepClear's private settings (never in a cloud backup or device transfer); it is forgotten when you finish or start over.

You can delete all of it by clearing KeepClear's storage in Android settings, or by uninstalling. The trash folder stays after uninstall so you can still recover files; KeepClear tells you this and offers to empty it.

## Gallery design approved 2026-11-08 — not implemented yet

The accepted [gallery specification](spec/gallery-decisions.md) requires analysis entirely on the phone and no additional payment, subscription/API credits or account. Its future private, recomputable cache may hold perceptual fingerprints, local image features and virtual groups, excluded from cloud backup/device transfer and ordinary reports. Clearing it must leave original photos, kept choices, trash and backup records intact. Deterministic stages run first; any admitted local model is limited to a bounded ambiguous subset. The gallery has no network purpose and never uploads photos/features or falls back to a hosted provider. This paragraph describes the approved design, not data already produced by a shipped gallery feature.

## Reports you choose to send

Nothing is sent by KeepClear itself.

- **Report a problem (Settings):** KeepClear opens your email app with a text you can read and change first: what you write, then the device model, Android version, KeepClear's version and build, whether test mode is on, and totals (how many files and how much space in each group and in the trash, how many backups). No file names, paths or folder names. You can also attach a diagnostics file: the same kind of counts and sizes from a read-only check of the phone (storage, permissions, bins, drives), with no file names or paths. The file is made in KeepClear's private cache and shared only with the email app you pick, read-only. You decide whether and to whom to send.
- **After an unexpected exit:** a card offers to share a report through Android's share sheet: KeepClear's version, the device model, the kind of exit and where in KeepClear's code it stopped, without messages, file names or paths. Nothing is shared unless you tap it.
- **Test mode's list (test builds, Settings › Advanced):** after a test-mode clean you can tap "Export the list". KeepClear first tells you the list has the name, folder and size of every file that would have been deleted, then Android asks where to save it (a CSV file). It contains full file paths, so keep it to yourself. KeepClear writes it only to the place you pick, never makes it by itself, never sends it and never reads it back. Release builds start with test mode off and offer nothing to export.

## Backups

When you move files to a drive or computer you choose, KeepClear copies them there and writes a list of the copied files with their checksums (`keepclear-manifest.jsonl`, `SHA256SUMS`). These files contain file names and paths. KeepClear does not encrypt backups in this version; keep the drive safe.

## Network

KeepClear has the Android `INTERNET` permission only for backups to a network drive (SMB: a shared folder on your computer or NAS, ADR 0011). It connects only to the address you typed, only while a backup, a check of a backup, or freeing space after a backup runs, and it sends only the files you chose to back up. The password is stored on the phone, encrypted with a key kept in Android's secure key store; it is never logged or sent anywhere else. Nothing else in KeepClear can use the network (checked automatically in every build). Settings shows the address in use.

The network drive form can also take a port (445 unless you change it), a domain and a folder inside the shared folder; they are stored with the address and change nothing about where KeepClear connects. KeepClear does not search the network for computers: you type the address.

**Nextcloud (Settings › Advanced).** If you connect your own Nextcloud, KeepClear talks only to the server address you typed, over HTTPS (plain http is refused except to the phone itself), and never through a proxy or to another address the server names: the sign-in page, the sign-in check and every backup request must be on that same server, or KeepClear stops. You sign in on your Nextcloud's own page in the browser (Nextcloud "Login Flow v2"), or type an app password you made there; KeepClear never asks for or stores your account password. It keeps only the app password Nextcloud issues, encrypted with a separate key in Android's secure key store, plus the server address, your user name and user id. Your Nextcloud learns what any Nextcloud app shows it: that a client named "KeepClear" signed in, from which network address, and the files you back up. Revoke the app password in Nextcloud (Settings › Security) at any time; "Forget your Nextcloud" in KeepClear's Settings removes it from the phone and stops a backup that is running to it. A server whose certificate isn't trusted is refused. Settings shows the address in use.

## Children

KeepClear does not collect data from anyone, including children.

## Changes

Changes to this policy are made in this file in the public repository, with history at https://github.com/tomerar/keepCleanr/commits/main/docs/PRIVACY.md. Any change in what KeepClear accesses is listed in the release notes.

## Contact

Open an issue at https://github.com/tomerar/keepCleanr/issues, or for security matters see [SECURITY.md](../SECURITY.md).

---

<div dir="rtl">

## מדיניות פרטיות בקצרה

KeepClear עובד רק בטלפון שלכם. הוא לא אוסף, לא מוכר ולא משתף את המידע שלכם עם KeepClear או עם שירותי מעקב ו־AI. אין בו פרסומות, אין ניתוח שימוש, אין דיווחי קריסה אוטומטיים ואין חשבון. דוח על בעיה נשלח רק אם בוחרים לשלוח אותו, מאפליקציית המייל או השיתוף, ואין בו שמות של קבצים. במצב ניסיון אפשר לייצא בעצמכם את רשימת הקבצים שהיו נמחקים; היא כוללת שמות ותיקיות, ונשמרת רק במקום שתבחרו. הרשאת האינטרנט משמשת רק לגיבוי לכתובת רשת או לשרת Nextcloud שהגדרתם, ולא לניתוח תמונות או לשירות AI בענן. קבצים יוצאים מהטלפון רק לכונן או לתיקייה שבחרתם לגיבוי.

</div>
