# 08 — Network and cloud backup targets for MOVE

Status: research, 2026-11-01. Scope: which network targets MOVE can use **and still free originals**, given ADR 0007 (delete only after per-file
SHA-256 proof), ADR 0008 (no network except user-chosen targets), ADR 0005 (`foss` + `play`, no Google Play Services anywhere) and the owner's
principles: **phone-only (no computer ever needed), plain language, never automatic deletion**.
Targets: Google Drive (REST v3), OneDrive (Microsoft Graph), Dropbox (API v2), Nextcloud/ownCloud (WebDAV), SMB share/NAS on home Wi-Fi.
Every claim is cited. **[UNVERIFIED]** = could not confirm from a primary source. **[ESTIMATE]** = back-of-envelope. **[ANALYSIS]** = our own
reasoning from a cited primary source. Builds on [06](06-backup-targets-and-verification.md) §3, §5, §8.

---

## 0. TL;DR

| Target | Server digest usable as proof? | Auth without Play Services | Minimum scope | Main blocker |
|---|---|---|---|---|
| **SMB** (smbj) | None → full read-back over Wi-Fi | Username/password (NTLM), no account | Share path the user types | Needs a PC/NAS that is on; Android 17 local-network permission |
| **Nextcloud** (WebDAV) | **Yes**: `X-Hash: sha256` → server computes `X-Hash-SHA256` while writing | **Login Flow v2** in the browser → app password; no OAuth client to register | One app password (full account; no folder scope) | Self-hosted TLS quirks; huge files need chunking |
| **ownCloud 10** (WebDAV) | Likely: server validates client `OC-Checksum: SHA1:…` and rejects mismatch **[UNVERIFIED on current versions]** | App password / OAuth2 app | Account | SHA-1 only; oCIS differs |
| **Generic WebDAV** | No → GET read-back | Basic auth over TLS | Account | Many dialects |
| **Google Drive** | **Yes**: `sha256Checksum` (output-only) | AppAuth + Custom Tabs, but **custom URI schemes are off by default for new Android OAuth clients**; must opt in per client | `drive.file` (non-sensitive, no security assessment) | OAuth client setup; 15 GB free tier shared with Gmail/Photos |
| **Dropbox** | **Yes**: `content_hash` (SHA-256 of 4 MiB block SHA-256s); upload can also carry `content_hash` and the server rejects a mismatch | AppAuth or Dropbox SDK (MIT), **PKCE + custom scheme**, no secret | **App folder** | Production approval after 50 users; 2 GB free tier |
| **OneDrive** | **No cryptographic one**: only `quickXorHash` is guaranteed; `sha256Hash` "isn't supported"; SHA-1 phased out on Personal → read-back (download) | AppAuth + `msauth://` redirect (Thunderbird does this), no MSAL needed | `Files.ReadWrite.AppFolder` (personal accounts) | Verify = full download; 5 GB free tier |

**Recommended order** (justified in §8): **1. SMB** (M13, unchanged) → **2. Google Drive** (after a 1-day OAuth spike) → **3. WebDAV/Nextcloud** →
**4. Dropbox** → **5. OneDrive**.

Key findings
- Three providers give a **server-computed cryptographic digest** that proves the stored bytes without downloading: Drive `sha256Checksum`,
  Dropbox `content_hash`, Nextcloud `X-Hash-SHA256`. That is enough for ADR 0007's "trusted server-side digest".
- **OneDrive's `quickXorHash` is not good enough as proof** [ANALYSIS]: it is linear and 160 bits wide, and two swapped upload fragments of the
  recommended size (multiples of 320 KiB) produce the **same** hash (§4.1). OneDrive needs a read-back download to free originals.
- No provider strictly **requires** Google Play Services or a client secret for a native app. Google comes closest: its recommended Android path
  is `play-services-auth`, and the browser-redirect path (custom scheme) must be switched on per OAuth client.
- Nextcloud's Login Flow v2 needs no developer registration at all and fits "phone-only, plain language" best among self-hosted options.

---

## 1. Verification model this research assumes

From ADR 0007 / design.md §6: the source SHA-256 is computed while copying; **only L3 unlocks deletion**: a SHA-256 match from a read-back of the
target **or a trusted server-side digest**. "Trusted" here means:

1. **Server-computed from the stored bytes** (not a value the client sent and the server echoed back), and
2. **Cryptographic** (SHA-256, or a SHA-256 construction like Dropbox's), so accidental corruption patterns can't cancel out, and
3. Re-readable later (metadata call) so the delete-time re-check (SAFETY-5, ADR 0007) can confirm the copy still exists with the same digest without a download.

A value the client supplied and the server stored without checking (Nextcloud `OC-Checksum` on regular PUT, `oc:checksums`) is **not proof**
([06](06-backup-targets-and-verification.md) §3.2).

---

## 2. Google Drive (REST API v3)

### 2.1 Checksums
- `files` resource: `md5Checksum` "The MD5 checksum for the content of the file"; `sha1Checksum`, `sha256Checksum` "The SHA256 checksum associated
  with this file, if available … only populated for files with content stored in Google Drive". All three are **Output only**
  (https://developers.google.com/workspace/drive/api/reference/rest/v3/files).
- Clients cannot set them, so the server provides them; the docs don't say how they are computed. Treat `sha256Checksum` as server-computed
  [ANALYSIS from "Output only"]. Verify: after upload, `files.get?fields=id,size,sha256Checksum,md5Checksum` → compare with the source SHA-256.
- "If available": whether `sha256Checksum` can be **missing right after upload** (computed asynchronously) is **[UNVERIFIED]**. Design: if it is
  missing, retry with backoff for a short time, then fall back to a read-back (`files.get?alt=media`) + SHA-256. Never fall back to MD5 alone.
- Drive allows **duplicate names in one folder** (files are addressed by id). The target must keep a `rel → fileId` map and check by name before
  creating, so "never overwrite" and "idempotent resume" hold [general Drive behaviour; **UNVERIFIED** in the cited page].

### 2.2 Auth without Play Services
- Google's recommended Android path is `AuthorizationClient` from `com.google.android.gms:play-services-auth`
  (https://developer.android.com/identity/authorization). That is Play Services → **not allowed** (CLAUDE.md, ADR 0005).
- Browser-based OAuth (AppAuth + Custom Tabs) for installed apps (https://developers.google.com/identity/protocols/oauth2/native-app):
  - "Custom URI schemes are no longer supported on Android and Chrome apps."
  - "The loopback IP address redirect option is DEPRECATED for Android, Chrome app and iOS OAuth client types."
  - PKCE is supported; "The client_secret is not applicable to requests from clients registered as Android, iOS, or Chrome applications."
- Google Developers Blog, 2023-10-02: "By default, new Android apps will no longer be allowed to use Custom URI schemes"; the method can be enabled
  in the client's **Advanced Settings** in the API Console
  (https://developers.googleblog.com/2023/10/enhancing-oauth-app-impersonation-protections.html).
- Google's docs (seen only in search snippets of the same native-app page) say enabling it ("Enable Custom URI Scheme" checkbox) needs a **Play
  Console app with the same package name and SHA-1 signing fingerprint** and admin rights on it **[UNVERIFIED: not shown on the fetched page]**.
  KeepClear plans a Play listing (ADR 0005) with the same key on every channel (ADR 0009), so one Android client covers `foss` and `play`.
- Precedent: Thunderbird for Android / K-9 Mail (on F-Droid) use AppAuth with a Google Android client and redirect
  `${applicationId}:/oauth2redirect`
  (https://github.com/thunderbird/thunderbird-android/blob/main/app-thunderbird/src/release/kotlin/net/thunderbird/android/auth/TbOAuthConfigurationFactory.kt).
  Their client may predate the 2023 default **[UNVERIFIED]**.
- Fallbacks if the toggle is refused: (a) a "Desktop app" client with a loopback redirect on `127.0.0.1` inside the phone (rclone-style). This
  works technically, but Google may treat it as the wrong client type **[UNVERIFIED policy risk]**. (b) Drop Drive. Do **not** use a "Web" client
  with an embedded secret.
- Consequence: the Android client is bound to our package + signing certificate. **Self-built or differently-signed builds can't sign in to Drive**
  [ANALYSIS]. Document this for contributors.

### 2.3 Scope
- `drive.file` is **non-sensitive** and "recommended": "Create new Drive files, or modify existing files, that you open with an app". Non-sensitive
  scopes "only require basic OAuth App Verification"; the **security assessment** applies only to restricted scopes (`drive`, `drive.readonly`)
  (https://developers.google.com/workspace/drive/api/guides/api-specific-auth).
- Unverified apps that show the "unverified app" screen are capped at **100 new users** (https://support.google.com/cloud/answer/7454865). That page
  ties this to sensitive/restricted scopes, so it likely doesn't hit `drive.file` **[UNVERIFIED]**. Brand verification (app name/logo on the consent
  screen, privacy-policy URL, authorized domain) is still needed **[UNVERIFIED: exact steps for a `github.io` domain]**.
- `drive.file` fits: KeepClear only reads back files it created. It cannot see an existing user folder (the Picker API is web-only
  **[UNVERIFIED for Android]**), so backups go to a folder KeepClear creates ("KeepClear Backup").

### 2.4 Limits and resumable upload
- 750 GB upload per user per day; max file 5 TB; quota 325,000 units/min per user (edits cost 50 units); 403 "User rate limit exceeded"/429 →
  exponential backoff (https://developers.google.com/workspace/drive/api/guides/limits). The owner's 75 GB fits in one day.
- Resumable upload: the session URI "expires after one week"; resume with an empty `PUT` and `Content-Range: bytes */<size>` → `308 Resume
  Incomplete` with `Range`; `404` = expired, restart; chunks are multiples of 256 KiB
  (https://developers.google.com/workspace/drive/api/guides/manage-uploads).
- **Survives app kill**: persist the session URI (encrypted, it's a capability) + confirmed offset in Room; on restart query the offset, re-read the
  local prefix to rebuild the SHA-256 state (MessageDigest state isn't serializable), continue.
- Storage reality: the free tier is 15 GB, shared with Gmail and Google Photos [general knowledge, **UNVERIFIED** current figure]. 75 GB needs a paid
  plan. Pre-flight must read `about.get?fields=storageQuota` and say so plainly.

### 2.5 What the user sees / failure modes
- "Back up to Google Drive" → Chrome Custom Tab with Google's account chooser → "KeepClear wants to: see, edit, create and delete only the
  specific Google Drive files you use with this app" → back in the app: "Connected as name@gmail.com. Backups go to *KeepClear Backup* in your Drive."
- Failures: no browser that supports Custom Tabs (AppAuth falls back to the default browser); token revoked or password changed (`invalid_grant`)
  → "Sign in to Google Drive again"; Drive full (`storageQuotaExceeded`) → stop, keep what's verified; daily limit → "Google limits uploads to 750 GB
  a day; we'll continue tomorrow"; user deletes the folder in Drive → the delete-time presence check fails → originals are kept.

---

## 3. Dropbox (API v2)

### 3.1 Checksums
- `FileMetadata.content_hash`: "A hash of the file content. This field can be used to verify data integrity"
  (https://github.com/dropbox/dropbox-api-spec/blob/master/files.stone).
- Algorithm: split into 4 MiB blocks, SHA-256 each block, concatenate the binary digests, SHA-256 of that; "make sure the server-generated
  `content_hash` is identical to your locally-computed version"
  (https://docs.dropboxapi.com/dropbox-api/docs/technical-reference/content-hash). **Server-generated, SHA-256-based → trusted.**
- The upload/commit argument can carry `content_hash`: "If provided and the uploaded content does not match this hash, an error will be returned"
  (`content_hash_mismatch`) (Dropbox SDK types, https://github.com/dropbox/dropbox-sdk-js/blob/main/lib/types.js). The server **checks** it, so the
  upload fails instead of storing bad bytes.
- Cost: KeepClear computes **two** digests while copying (plain SHA-256 for the manifest/`SHA256SUMS`, plus the Dropbox block hash). Both are
  SHA-256 over the same bytes in one pass; still far faster than Wi-Fi [ESTIMATE, see 06 §1].

### 3.2 Auth
- PKCE is the documented choice for "desktop and mobile apps … and open source apps"; `token_access_type=offline` gives a refresh token;
  access tokens are short-lived; long-lived tokens are deprecated (https://docs.dropboxapi.com/dropbox-api/docs/oauth).
- Redirect: Dropbox staff say a **custom URI scheme works with the PKCE code flow** (https only applies to the non-PKCE code flow)
  (https://www.dropboxforum.com/t5/Discuss-Dropbox-Developer-API/Deep-linking-using-custom-url-scheme/m-p/484493;
  https://dropboxforum.com/discussions/101000014/moblie-app-using-oauth2authorize-interface-redirect-uri-problem/498465/replies/498718).
  Forum, not docs → **[UNVERIFIED in official docs]**. Alternative: the official Java SDK (MIT) has `Auth.startOAuth2PKCE` + `AuthActivity` with
  scheme `db-<APP_KEY>`; no Play Services dependency (https://github.com/dropbox/dropbox-sdk-java). App key is public (fine with PKCE).
- Works on any signature (no cert binding) [ANALYSIS], unlike Google/Microsoft.

### 3.3 Scope
- **App folder** ("Access is limited to the app's own folder") vs Full Dropbox; "request the least access your app needs"
  (https://docs.dropboxapi.com/dropbox-api/docs/oauth). Use App folder (`/Apps/KeepClear/`).
- New apps are in **development** status (up to 500 linked users); after **50 linked users** you have 2 weeks to get **production** approval or
  linking freezes (https://www.dropbox.com/developers/reference/developer-guide).

### 3.4 Limits and resumable upload
- `files/upload` ≤ 150 MB per request; upload sessions for bigger files; one request ≤ 150 MB; max **350 GB** per file; a session is usable for
  **48 hours**; wrong offset → `incorrect_offset` with the correct value (https://github.com/dropbox/dropbox-api-spec/blob/master/files.stone).
- **Survives app kill**: persist `session_id` + offset; on restart append at the server-reported offset (an `incorrect_offset` error tells us it);
  after 48 h restart the file. Rate limits: `too_many_requests` / `too_many_write_operations` with retry-after (same SDK types file).
- Free tier is 2 GB [general knowledge, **UNVERIFIED**]. Useful only to paying users.

### 3.5 What the user sees / failure modes
- Custom Tab with Dropbox sign-in → "KeepClear would like access to its own folder in your Dropbox" → "Connected. Backups go to Apps › KeepClear."
- Failures: app frozen (production approval missing) → sign-in fails for new users (our ops risk, not the user's); quota full (`insufficient_space`);
  hash mismatch → that file is retried, never marked verified.

---

## 4. OneDrive (Microsoft Graph)

### 4.1 Checksums
- `hashes` resource: `crc32Hash`, `quickXorHash` ("A proprietary hash of the file"), `sha1Hash` ("if available"), and `sha256Hash`: **"This
  property isn't supported. Don't use."** "quickXorHash is the only value that is guaranteed to be available for both OneDrive for work or school and
  OneDrive for home" (https://learn.microsoft.com/en-us/graph/api/resources/hashes).
- rclone: "Starting from July 2023 SHA1 support is being phased out in Onedrive Personal in favour of QuickXorHash"
  (https://rclone.org/onedrive/). Third-party, but consistent with Microsoft's "only quickXorHash is guaranteed".
- QuickXorHash: "A quick, simple non-cryptographic hash algorithm that works by XORing the bytes in a circular-shifting fashion"; 160-bit block;
  byte *i* is rotated by *11·i* bits, then the length is XORed in
  (https://learn.microsoft.com/en-us/onedrive/developer/code-snippets/quickxorhash). Server-computed, but:
  - **[ANALYSIS]** The hash is linear (XOR) and a byte's rotation depends only on *i* mod 160. Two errors that are the same and 160·k bytes apart
    cancel. Worse, two **swapped equal-size blocks whose offsets differ by a multiple of 160 bytes** hash the same. Graph requires upload fragments
    to be multiples of **320 KiB = 327,680 = 2,048 × 160 bytes**. So "fragment 3 stored where fragment 4 should be", which is exactly the failure
    a chunked upload can have, is **invisible** to quickXorHash.
  - Verdict: quickXorHash + size is useful as a cheap **pre-check** (and to detect later changes), but **not L3**. Freeing originals on OneDrive
    needs a **read-back download** + SHA-256 (double traffic, as with SMB).

### 4.2 Auth
- MSAL Android (MIT) uses redirect `msauth://<package>/<base64 signature hash>` and the default browser; broker optional
  (https://github.com/AzureAD/microsoft-authentication-library-for-android). It does not obviously need Play Services, but its dependency tree is
  heavy, and Cryptomator's F-Droid recipe strips `msgraphAuth`/MSAL together with Play Services auth
  (https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.cryptomator.lite.yml). Prefer **AppAuth**.
- AppAuth with the same `msauth://` redirect works: Thunderbird for Android does this against `login.microsoftonline.com/common`
  (TbOAuthConfigurationFactory.kt, URL in §2.2). Register the app under "Android" / "Mobile and desktop applications"; public client, PKCE, no
  secret (https://learn.microsoft.com/en-us/entra/identity-platform/reply-url). Personal-account audiences forbid query parameters in redirect URIs (same page).
- Registration needs a (free) Entra tenant **[UNVERIFIED cost]**. The redirect is bound to the signing certificate hash (same caveat as Google).

### 4.3 Scope
- `Files.ReadWrite.AppFolder` (delegated, "(preview)", no admin consent) "is available for consent in personal Microsoft accounts"
  (https://learn.microsoft.com/en-us/graph/permissions-reference). The app folder lives at `/drive/special/approot` under `Apps/<app name>`
  (https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/special-folders-appfolder).
- Work/school accounts: AppFolder support and tenant admin-consent / publisher-verification rules **[UNVERIFIED]** → scope v1 to **personal accounts**.

### 4.4 Limits and resumable upload
- Max file 250 GiB (https://rclone.org/onedrive/, third-party **[UNVERIFIED in Microsoft docs]**).
- Upload session: fragments < 60 MiB and multiples of 320 KiB, sent in order; `expirationDateTime` is extended by each fragment; resume with `GET
  uploadUrl` → `nextExpectedRanges`; `404` = start over; **don't** send `Authorization` on the PUTs (the URL is pre-authenticated);
  `conflictBehavior: fail` (default) gives "never overwrite"; `fileSize` over quota → `507`
  (https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession).
- **Survives app kill**: persist `uploadUrl` (a bearer capability → encrypt at rest), resume from `nextExpectedRanges`. Throttling: 429/503 with
  `Retry-After`. Upload hosts vary (e.g. `*.up.1drv.com` in the doc's example), so the network allowlist must be by domain suffix.
- Free tier 5 GB [general knowledge, **UNVERIFIED**].

### 4.5 What the user sees / failure modes
- Custom Tab with Microsoft sign-in → "Let this app access your info? … Have full access to the application's folder" → "Connected. Backups go to
  Apps › KeepClear in OneDrive."
- Receipt must say why verification takes as long as the upload: "OneDrive doesn't give us a fingerprint we can trust, so KeepClear downloads each
  file once to check it." Read-back download of 75 GB on Wi-Fi ≈ another 14–31 min on the owner's phone [ESTIMATE, 06 §1].

---

## 5. Nextcloud / ownCloud (WebDAV)

### 5.1 Checksums
- Nextcloud (https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html):
  - `X-Hash` request header on PUT "instructs the server to compute a hash of the uploaded file content during the write"; response headers
    `X-Hash-MD5`, `X-Hash-SHA1`, `X-Hash-SHA256`; values `md5|sha1|sha256|all`. **Server-computed → trusted.**
  - `OC-Checksum`: "For regular PUT uploads, the server stores the value without validation"; validated only in bulk upload. **Not proof.**
  - `oc:checksums`: "checksums stored in the DB by other clients". **Not proof.**
- History: added in Nextcloud 19, also on "the MOVE request at the end of a multipart upload" (https://github.com/nextcloud/server/pull/19351);
  made opt-in via the `X-Hash` request header in 22.2.9 (https://github.com/nextcloud/server/pull/32760). Whether current chunked-upload v2 (incl.
  S3 primary storage with backend multipart) still returns `X-Hash-*` on the final MOVE is **[UNVERIFIED]** → if the header is missing, fall back
  to GET read-back.
- `X-Hash` is per request; it can't be re-queried later. For the delete-time re-check, use PROPFIND `getcontentlength` + `getetag` + presence and
  record the etag at verify time [ANALYSIS].
- ownCloud 10: per the client architecture doc and server tests, the **server computes SHA1/MD5/Adler-32 during upload and rejects a client
  `OC-Checksum` that doesn't match (HTTP 400)**
  (https://git.brokenco.de/nextcloud/desktop/blame/commit/7d9d75e90a7411f26afc1d48662f3c8b2750aaa4/doc/architecture.rst;
  https://github.com/owncloud/core/blob/72c43e63b88cab96d1f595d21ce61dbfef31cd35/tests/integration/features/checksums.feature). One forum report
  says the stored checksum was set only every second upload (https://central.owncloud.org/t/checksum-is-only-set-every-2nd-webdav-upload/24801).
  Current ownCloud 10 / oCIS behaviour **[UNVERIFIED]** → treat ownCloud as generic WebDAV (read-back) until tested.

### 5.2 Auth
- **Login Flow v2**: anonymous `POST /index.php/login/v2` → open the `login` URL "in the default browser" → poll → `{server, loginName,
  appPassword}`; revoke with `DELETE /ocs/v2.php/core/apppassword`
  (https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html). No OAuth client registration, no Play Services,
  works with 2FA and SSO on the server side.
- Generic WebDAV / ownCloud: username + app password over HTTPS (Basic). Self-signed certificates on home servers: show the fingerprint and
  pin it on first use (TOFU), never "trust all" [design recommendation].

### 5.3 Scope
- An app password grants the whole account (no folder-scoped token) [ANALYSIS of Login Flow docs]. Mitigation: KeepClear only writes under the
  folder the user picks, and the Settings line names the host.

### 5.4 Limits and resumable upload
- Chunked upload v2: `MKCOL /remote.php/dav/uploads/<user>/<id>`, PUT chunks named 1…10000, 5 MB–5 GB each, `MOVE .file` to the destination;
  `Destination` header required; `OC-Total-Length` lets the server refuse early with 507; "Nextcloud will expire the upload directory after 24
  hours of inactivity" (https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/chunking.html).
- **Survives app kill**: persist the upload id; PROPFIND the upload folder to see which chunks exist **[UNVERIFIED as documented resume method]**;
  after 24 h restart the file. Real max single-PUT size depends on PHP/proxy limits (e.g. reverse-proxy body size) **[UNVERIFIED per server]** →
  chunk everything above ~100 MB [ESTIMATE].
- LAN servers: Android 17 local-network protection applies (§6.2).

### 5.5 What the user sees / failure modes
- "Back up to Nextcloud" → type the address (`cloud.example.com`) → browser opens the Nextcloud login page → "Grant access" → "Connected as
  *dana* on cloud.example.com".
- Failures: certificate changed ("The server's certificate is different from last time. Don't continue unless you changed it."), server down,
  quota 507, maintenance mode 503, app password revoked → "Sign in again".

---

## 6. SMB network drive / NAS (no PC needed)

### 6.1 Checksums and library
- **No server-side whole-file hash** usable from smbj → read-back + SHA-256 over the network (06 §3.1). Already the plan for M13 (tasks.md).
- smbj (Apache-2.0), SMB2/3; the MD4/NTLM BouncyCastle issue on Android (06 §3.1). "No PC" works with NAS boxes and routers that share a USB disk
  over SMB [general knowledge].
- Resumable: SMB writes at an offset, so a partial file can be continued, but MVP restarts the file (06 §8.2). No server session to expire.

### 6.2 Auth / permissions
- Username + password (or guest), stored Keystore-encrypted (06 §3.1). No account, no OAuth, no third party.
- **Android 17 local network protection**: "mandatory and enforced for apps targeting Android 17 or higher"; permission `ACCESS_LOCAL_NETWORK`
  (Android 16 opt-in uses `NEARBY_WIFI_DEVICES`), in the NEARBY_DEVICES group; mDNS/`.local` lookups need it too
  (https://developer.android.com/privacy-and-security/local-network-permission). KeepClear targets 36 today (ADR 0003); when it moves to 37, SMB
  and LAN Nextcloud need this runtime permission. tasks.md M13 already lists the prep.

### 6.3 What the user sees / failure modes
- "Back up to a network drive" → type `\\nas\photos` or pick a found device → user name/password → "Connected to *nas*".
- Failures: NAS asleep, Wi-Fi off, SMB1-only device (unsupported), wrong password, disk full; all resumable (06 §3.1, §10).

---

## 7. Policy

### 7.1 Google Play
- `INTERNET` is a normal permission; it is not in Play's sensitive-permissions policy
  (https://support.google.com/googleplay/android-developer/answer/9888170: not listed).
- Data safety: "Collect" = "transmitting data from your app off a user's device". If "the user chooses to upload their data directly to their own
  external drive or cloud storage account" under the provider's terms and the app never accesses the data, it need not be declared; "depends on the
  particular implementation". User-initiated transfers are exempt from "sharing"
  (https://support.google.com/googleplay/android-developer/answer/10787469). KeepClear's developer never receives anything, so "No data collected"
  remains defensible [ANALYSIS]. Whether self-hosted servers (SMB/Nextcloud) count as the user's "own drive" is still **[UNVERIFIED]** ([05](05-play-policy-and-flavors.md), Data-safety implication).
- Account email shown in the app stays on the device → not "collected" [ANALYSIS].

### 7.2 F-Droid
- NonFreeNet: "apps that promote or depend entirely on a proprietary network service"; NonFreeDep: requires non-free software to run
  (https://f-droid.org/docs/Anti-Features/).
- Precedent: K-9 Mail (built-in Gmail/Outlook OAuth among standard IMAP) has **no** anti-feature in fdroiddata
  (https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.fsck.k9.yml). KeepClear would offer Drive/Dropbox/OneDrive as options next to
  USB, SMB and Nextcloud, so it neither "depends entirely" on them nor needs non-free code (AppAuth is Apache-2.0, no Play Services). Whether
  reviewers add NonFreeNet anyway is **[UNVERIFIED]**; accept it, or keep a build property that leaves the proprietary-cloud modules out
  (as Cryptomator Lite's recipe strips modules).

### 7.3 What ADR 0008 must change
- 0008 says INTERNET arrives with SMB/WebDAV, network calls only from `backup-target-*` modules, "only to the host the user typed", a CI rule, and
  a Settings line. Cloud providers break "the host the user typed": hosts are **fixed per provider** and include OAuth endpoints and variable
  upload hosts. New rule: each target declares a **host allowlist** (exact hosts or provider domain suffixes); the HTTP client refuses anything
  else; Settings shows "Network: only to Google Drive (googleapis.com)". The sign-in page runs in the user's browser, not in our process.
- Also: CI/merged-manifest check must allow `INTERNET` (and later `ACCESS_LOCAL_NETWORK`) but still fail on anything else; the onboarding privacy
  promise "no network" (ONB-1) becomes "no network unless you connect a backup place".

---

## 8. Comparison and priority

| | SMB | Nextcloud | Google Drive | Dropbox | OneDrive |
|---|---|---|---|---|---|
| Proof without download | No (read-back) | **Yes** (`X-Hash-SHA256`) | **Yes** (`sha256Checksum`) | **Yes** (`content_hash`, server rejects mismatch) | No (quickXor only) |
| Traffic for 75 GB incl. verify | ~150 GB (LAN) | ~75 GB | ~75 GB | ~75 GB | ~150 GB |
| Auth | password | Login Flow v2 (browser) | AppAuth + custom scheme (**opt-in per client**) | AppAuth/SDK + PKCE | AppAuth + `msauth://` |
| Developer registration | none | none | Google Cloud project + consent screen + brand verification | App console + production review at 50 users | Entra app registration |
| Works on self-built APKs | yes | yes | no (cert-bound) | yes | no (cert-bound) |
| Scope | share | account (app password) | `drive.file` | App folder | `Files.ReadWrite.AppFolder` |
| Max file / session life | none / n.a. | chunks ≤ 5 GB / 24 h idle | 5 TB / 1 week | 350 GB / 48 h | 250 GiB [UNVERIFIED] / extended per fragment |
| Free storage | user's disk | user's server | 15 GB shared [UNVERIFIED] | 2 GB [UNVERIFIED] | 5 GB [UNVERIFIED] |
| F-Droid risk | none | none | NonFreeNet? | NonFreeNet? | NonFreeNet? |
| Phone-only? | needs NAS/PC on LAN | needs a server (or hosted Nextcloud) | **yes** | **yes** | **yes** |

**Recommended order**
1. **SMB (M13, as planned).** No account, no third party, fastest path on the owner's USB-2.0 phone, smbj already chosen. Read-back doubles LAN traffic, which is cheap.
2. **Google Drive.** The only truly phone-only target most Android users already have (and often pay for via Google One); strongest
   server digest; `drive.file` is non-sensitive (no CASA). **Gate:** a 1-day spike that registers an Android OAuth client, enables the custom
   scheme, and signs in with AppAuth on a phone without Play Services. If the spike fails, Drive drops below Dropbox.
3. **WebDAV / Nextcloud.** FOSS, no NonFreeNet, no developer registration, server-side SHA-256 via `X-Hash`; generic WebDAV falls back to read-back.
   Smaller audience (needs a server), so after Drive.
4. **Dropbox.** Easy auth, strong digest, but 2 GB free tier and a production review we must pass.
5. **OneDrive.** No trustworthy server digest (read-back download), cert-bound auth, AppFolder scope still "preview". Only on demand.

Never as verified targets (unchanged from 06): Google Photos, Drive via SAF, Syncthing, plain MTP.

---

## 9. Proposed architecture

```
backup/
  target-api/        (JVM)  BackupTarget, TargetCaps (+ serverDigests), DigestKind, ResumableUpload port
  target-saf/        (Android) existing
  net/               (JVM)  OkHttp client factory: host allowlist, TLS pinning (TOFU), retry/backoff, no logging of bodies/tokens
  auth-oauth/        (Android) AppAuth wrapper (Custom Tabs, PKCE), token store (Keystore-encrypted)
  target-smb/        (JVM/Android) smbj
  target-webdav/     (JVM)  Nextcloud X-Hash, chunking v2, Login Flow v2; generic WebDAV
  target-gdrive/     (JVM)  Drive v3 over OkHttp (no Google client libs)
  target-dropbox/    (JVM)  Dropbox v2 over OkHttp (or the MIT SDK if it passes the allowlist)
  target-onedrive/   (JVM)  Graph over OkHttp
```
- Each `target-*` implements the existing `BackupTarget` port (`backup/target-api/.../BackupTarget.kt`). Only `backup:net`, `backup:auth-oauth`
  and `backup:target-*` may import `okhttp3`, `java.net.Socket/URL/HttpURLConnection`, `com.hierynomus.smbj`, `net.openid.appauth`
  (Konsist + dependency allowlist). Engines stay pure JVM.
- **Caps** (design.md §6.1 already names `serverDigest`):
  ```kotlin
  enum class DigestKind { SHA256, DROPBOX_CONTENT_HASH, SHA1, QUICK_XOR, MD5 }
  data class TargetCaps(
      val canReadBack: Boolean,
      val serverDigests: Set<DigestKind> = emptySet(),   // computed by the server from stored bytes, re-queryable
      val maxFileSize: Long? = null, ...
  )
  // new, optional
  suspend fun BackupTarget.serverDigest(rel: String, kind: DigestKind): String?   // null = not available now
  ```
- **"May free originals"** (engine:move, unchanged rule SAFETY-5 / ADR 0007): a file is `VERIFIED` only if
  (a) a read-back SHA-256 equals the source SHA-256, **or** (b) the target returns a server digest of a kind in
  `TRUSTED = {SHA256, DROPBOX_CONTENT_HASH, SHA1-server-computed}` that equals the digest KeepClear computed locally of the same bytes.
  `QUICK_XOR`, `MD5`, `OC-Checksum`/`oc:checksums` never verify. A missing digest falls back to read-back if `canReadBack`, else the file stays
  "copied, not verified" (MOVE-14). The verification method is recorded per file in Room and in the manifest (`"verified":"server-sha256"` /
  `"readback"`).
- **Upload model:** cloud uploads need the size up front and resumable sessions, which `create(): OutputStream` can't express. Add a
  `ResumableUpload` port (`start(rel, size) → handle`, `offset(handle)`, `write(handle, from, bytes)`, `finish(handle) → Stat + digests`); the
  handle is persisted (encrypted) in the job store so a killed process resumes. Cloud uploads become visible only on finish, so the temp+rename
  step is not needed there; "never overwrite" comes from `conflictBehavior=fail` / Dropbox `mode=add, autorename=false` / Drive name check.
- **Delete-time re-check** (ADR 0007): for server-digest targets, re-query metadata (presence + size + digest) instead of downloading; for
  read-back targets, presence + size (as today). Unreachable → don't delete.
- **Execution:** UIDT job with network constraint (API 34+), `dataSync` FGS fallback (06 §9); default **unmetered only**, with a plain question
  before using mobile data.

---

## Implications for KeepClear

### Adopt
- Server-computed SHA-256-class digests (Drive `sha256Checksum`, Dropbox `content_hash`, Nextcloud `X-Hash-SHA256`) as L3, with read-back fallback.
- AppAuth (Apache-2.0) + Custom Tabs + PKCE for all OAuth providers; no Play Services, no MSAL, no Google client libraries, no client secrets.
- Least scopes: `drive.file`, Dropbox App folder, `Files.ReadWrite.AppFolder`; Nextcloud Login Flow v2.
- Per-target host allowlist enforced in one OkHttp factory; Konsist rule confining network imports to `backup:*` network modules.
- Persisted, encrypted resumable-upload handles; resume from server-reported offsets.

### Avoid
- `quickXorHash`, MD5, `OC-Checksum`, `oc:checksums` as proof of a good copy.
- `play-services-auth` / Credential Manager Google flows (Play Services); MSAL (heavy, stripped by F-Droid recipes); embedded client secrets.
- `drive` / `drive.readonly` (restricted, security assessment).
- Loopback redirect for Google unless the custom-scheme route is refused (policy risk).

### Open questions
- Can a new Google Android OAuth client still enable "custom URI scheme", and does it require a Play Console app first? (spike)
- Is `sha256Checksum` present immediately after a resumable upload completes? (spike)
- Does Nextcloud 30+ return `X-Hash-SHA256` on the final MOVE of chunked upload v2 (local disk and S3 primary storage)? (Docker test)
- ownCloud 10 / oCIS: is `OC-Checksum: SHA1:` still validated on PUT? (test or treat as generic WebDAV)
- Will F-Droid reviewers add NonFreeNet for optional Drive/Dropbox/OneDrive targets?
- OneDrive work/school accounts: AppFolder availability and admin-consent rules.
