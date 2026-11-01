# KeepClear: Threat model

Status: **Approved by owner 2026-10-31** · Date: 2026-10-31
Scope: the MVP and v1 designs in [design.md](design.md). Requirements added here are `SEC-*` in [requirements.md](requirements.md) §10a. Edge-case tests are `E-SEC-*` in [edge-cases.md](edge-cases.md) §7.

## 1. What we protect

| Asset | Why it matters |
|---|---|
| User files (photos, videos, chats' media) | Losing them is the worst possible outcome. |
| WhatsApp/Telegram databases and backups | Losing them means losing chat history. These paths are PROTECTED. |
| Trash contents and their original locations | Restore must put files back where they came from, and nowhere else. |
| Backup copies on the target + manifest | They are the only copy after a MOVE. |
| The user's trust ("no network, no tracking") | One leak or silent deletion ends the product. |

## 2. Trust boundaries and untrusted inputs

KeepClear treats **everything outside its private app storage (`/data/data/<pkg>`) as untrusted input**, including files KeepClear itself wrote to shared storage or to a backup drive.

| Input | Where it comes from | Who can tamper |
|---|---|---|
| Trash sidecar JSON (`.KeepClearTrash/**/<uuid>.json`) | Shared storage | Any app with all-files access, the user via a PC, another cleaner |
| Files and folder names in shared storage | Shared storage | Any app with write access; FUSE is case-insensitive |
| Archives inspected for "already extracted" (SAFE-3) | Downloads, WhatsApp documents | Anyone who can send the user a file |
| Backup manifest, `SHA256SUMS`, target marker file | Backup drive / SMB share | Anyone with the drive, any PC it was plugged into |
| Intents, PendingIntents, broadcasts | Other apps, the system | Any installed app |
| On-screen input | Overlays | Apps with `SYSTEM_ALERT_WINDOW` (tapjacking) |
| Dependencies and build | Maven Central, Gradle plugins | Supply chain |

Out of scope: a fully compromised OS or root-level malware (it can already do anything), and physical access to an unlocked phone.

## 3. Threats and mitigations

### T1. Tampered trash sidecar (restore abuse)
**Attack:** edit `<uuid>.json` so that `originalPath` is `../../Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt14`, an absolute path elsewhere, a path containing a symlink, or the path of an existing file. Restore would then overwrite chats or plant a file in a sensitive place.
**Mitigations:**
- **Room is the source of truth.** Sidecars are only read during `recover()` when the Room index is missing an entry. Normal restores never read the sidecar.
- **HMAC on sidecars:** each sidecar carries an HMAC-SHA256 over its canonical content, keyed by an Android Keystore key. A missing or bad MAC (e.g. after reinstall, when the key is gone) makes the entry *untrusted-recovered*.
- **Path validation on every restore, trusted or not** (`RestorePathValidator`):
  1. Parse with a strict schema, size ≤ 64 KiB, known `v` (see MIG-2).
  2. The name has no `/`, `\`, NUL, `..`, control characters; length ≤ 255 bytes UTF-8.
  3. Resolve the destination **canonically** (case-insensitive, NFC) and require it to be inside the same volume root as the trash entry, outside `.KeepClearTrash`, and not matching any PROTECTED rule (SAFETY-3) after canonicalisation.
  4. Every existing ancestor of the destination must be a real directory, not a symlink.
  5. **Never overwrite:** create-new semantics; an occupied target becomes `name (restored).ext` (E-DEL-6).
  6. The trashed file's size (and SHA-256 when recorded) must match the sidecar; otherwise the entry is shown as "contents changed".
- Untrusted-recovered entries restore only after an explicit user confirmation that shows the destination path, and only into `Download/KeepClear Restored/` unless the user picks the original folder.

### T2. Zip Slip and hostile archives (SAFE-3)
**Attack:** an archive whose entries are named `../../DCIM/Camera/x.jpg` or `/sdcard/...`, or a forged central directory that claims matching sizes so a different zip is marked SAFE.
**Mitigations:**
- KeepClear **never extracts** archives. It only reads the central directory.
- Entry names are normalised (backslash → slash, NFC). Entries that are absolute, contain `..`, drive letters or NUL disqualify the archive from SAFE (→ REVIEW "unusual archive").
- Each resolved entry path must stay inside the sibling folder after canonicalisation.
- Matching requires name **and** size **and** CRC-32 (computed from the sibling file) for every entry. Limits: ≤ 100,000 entries, ≤ 4 MiB central directory; beyond that → REVIEW.
- Residual risk: a forged CRC can still mark a zip SAFE. Impact is low (the zip goes to trash and can be restored).

### T3. Malicious manifest or marker on a backup drive
**Attack:** a manifest whose paths escape the KeepClear folder, claims files are verified, or points restore at PROTECTED paths; a copied marker file that makes a different drive look like the original target.
**Mitigations:**
- **Deletion decisions never use the manifest.** MOVE deletion relies only on our Room records and our own read-back hash (SAFETY-5).
- Manifest is parsed strictly: line ≤ 8 KiB, `sha256` is 64 hex chars, `rel` is a relative path that stays inside `KeepClear/` after normalisation, `size` ≥ 0.
- Restore from backup (MOVE-17) computes destinations itself and runs them through `RestorePathValidator` (T1); manifest `src` paths are hints only.
- The target marker holds a random 128-bit id **plus** the volume/tree identity. A resume requires both to match (E-MOVE-2).

### T4. Races with other apps (TOCTOU)
**Attack:** another app with all-files access swaps a file between scan/verify and action (same size, mtime forged), or swaps a directory for a symlink.
**Mitigations:**
- Re-check before acting (SAFETY-6): size, mtime, and where available inode (File gateway) or MediaStore `GENERATION_MODIFIED`.
- Trash is a **rename of the path**, with no-follow semantics (`Files.move(..., NOFOLLOW_LINKS)` / `DocumentsContract.moveDocument`). Swapping in a symlink moves the link, not its target.
- **Strict mode, on by default for all files** (owner decision Q13): every MOVE original is re-hashed immediately before trashing; any mismatch → skipped. Same-size, forged-mtime swaps are therefore caught for MOVE deletes.
- Accepted residual risk: an app with all-files access can delete files itself; KeepClear doesn't defend against it, but never makes it worse. Every action stays recoverable from trash.

### T5. Exported components and intents
**Attack:** another app starts our service, sends a broadcast or opens an activity with extras that trigger a delete, a restore or dry-run toggling.
**Mitigations:**
- All components `android:exported="false"` except the launcher activity. CI checks the merged manifest.
- No component performs a destructive action from intent extras. Destructive actions start only from an in-app confirmation (SAFETY-1).
- Notification actions (pause/cancel MOVE) use explicit intents to a non-exported receiver with `PendingIntent.FLAG_IMMUTABLE`.
- `FileProvider` (for previews) is non-exported, with per-URI read grants only.
- Dry-run and debug flags exist only in `debug` builds.
- **Tapjacking:** confirm buttons for destructive actions reject touches flagged as obscured (checked per touch in `SafeConfirmButton`, which also covers dialog windows) and enable 500 ms after the sheet appears.
- **Confirmation integrity (review PR B):** a plan is confirmed by the user's tap: the confirm UI creates a `Confirmation` naming exactly the items it showed; ViewModels can't create one (Konsist). The guard rejects any op not in the confirmation and any op whose path ref differs from the path shown.
- **Test key:** debug/test builds are `io.github.tomerar.keepclear.test`, so the public test key can't update a real install.
- **Logs:** no file paths or names in logcat, in any build (Konsist).
- **CI:** checkout steps use `persist-credentials: false`; the Gradle wrapper properties are checked (plain key=value, services.gradle.org, pinned SHA-256).

### T6. Symlinks, case and Unicode tricks
**Attack:** symlinks or bind mounts making a "junk" path resolve into a PROTECTED area; `whatsapp/DATABASES` on a case-insensitive FUSE volume; NFD or zero-width characters to dodge PROTECTED matching.
**Mitigations:**
- The scanner doesn't follow symlinks (SCAN-6). Actions `lstat` and refuse symlinks to directories.
- **PROTECTED matching runs on the canonical path, case-insensitively, after NFC normalisation and removal of zero-width/bidi control characters**, for every volume including `/storage/emulated/<any user>/` and SD cards.
- Whether shared storage on Android 11+ allows creating symlinks at all is **[UNVERIFIED]**; we defend anyway.

### T7. Privacy leaks
**Risks:** paths and file names in logs; trashed files readable by other apps; unencrypted backups on a lost drive.
**Mitigations:**
- No network (ADR 0008).
- Logs are local, path redaction is on by default in exports (PRIV-4).
- The UI says plainly that trashed files stay readable by apps with file access until purged, as in any gallery bin.
- Backup encryption is **Later** (open question).

### T8. Supply chain and release integrity
**Mitigations:**
- Gradle dependency verification (`verification-metadata.xml`), pinned versions, dependency allowlist (PRIV-1).
- Reproducible `foss` builds; signing per [ADR 0009](../adr/0009-signing-and-release.md).
- GitHub Actions pinned by commit SHA; no secrets in PR workflows.

### T9. Privileged helpers (v1, Shizuku/root)
**Mitigations:**
- The AIDL user service checks the calling UID equals our app UID.
- It exposes only narrow operations (list, stat, delete-cache-for-package). Never a generic "run shell command" from UI input.

## 4. Security requirements → tests

| Req | Test |
|---|---|
| SEC-1 sidecar validation | Fuzz sidecars (path traversal, absolute, PROTECTED targets, huge, wrong types, bad MAC) → never write outside allowed roots, never overwrite (E-SEC-1…3). |
| SEC-2 Zip Slip | Crafted zips (`../`, absolute, backslash, NUL, 100k entries) → never SAFE, no file written (E-SEC-4). |
| SEC-3 manifest | Hostile manifests → restore validator rejects; deletion unaffected (E-SEC-5, E-SEC-6). |
| SEC-4 TOCTOU | Fake gateway swaps file/dir/symlink between plan and action → skipped (E-SEC-7). |
| SEC-5 components | Merged-manifest test: only launcher exported; Robolectric test sending crafted intents → no destructive call (E-SEC-8). |
| SEC-6 canonical PROTECTED matching | Table test with case variants, NFD, zero-width chars, `/storage/emulated/999/` (E-SEC-9). |
| SEC-7 tapjacking | UI test: obscured touch on confirm is ignored (E-SEC-10). |
| SEC-8 supply chain | CI fails on unverified dependency checksum. |
