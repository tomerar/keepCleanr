# ADR 0011: Network and cloud backup targets, and when they may free originals

Status: Accepted with one change (owner, 2026-11-01): Google Drive first · Date: 2026-11-01
Amends: [ADR 0008](0008-no-accessibility-no-network.md) (network confinement rule). Builds on [ADR 0007](0007-verify-by-hash.md).

## Context
MOVE can free originals only after per-file SHA-256 proof (ADR 0007). Today that proof is a read-back from USB/SD. The owner wants phone-only
backups (no computer), which means network and cloud targets. Research [08](../research/08-cloud-targets.md) found:
- **Server-computed cryptographic digests** exist for Google Drive (`sha256Checksum`), Dropbox (`content_hash`, SHA-256 of 4 MiB block hashes;
  the upload is rejected on mismatch) and Nextcloud (`X-Hash: sha256` → `X-Hash-SHA256`). These prove the stored bytes without a download.
- OneDrive guarantees only `quickXorHash` (`sha256Hash` "isn't supported"). It is linear and can't tell apart two swapped 320 KiB-multiple
  upload fragments, so it is not proof. SMB and generic WebDAV have no server hash.
- Client-supplied checksums that the server stores unchecked (Nextcloud `OC-Checksum`, `oc:checksums`) are not proof.
- OAuth works without Play Services via AppAuth + Custom Tabs + PKCE for Google, Microsoft and Dropbox. Google turns custom URI schemes off by
  default for new Android clients, so they must be enabled per client. Nextcloud's Login Flow v2 needs no registration at all.
- Least scopes exist: Drive `drive.file` (non-sensitive, no security assessment), Dropbox App folder, Graph `Files.ReadWrite.AppFolder`.
- ADR 0008's "only to the host the user typed" can't hold for cloud providers, whose hosts are fixed and include variable upload hosts.

## Decision
- **Targets and order** (each its own milestone and PR, after the owner's OK), **as changed by the owner on 2026-11-01**: **Google Drive first**
  (most non-technical users have a Google account but no NAS), gated by a 1-day sign-in spike without Play Services → SMB → WebDAV/Nextcloud →
  Dropbox. OneDrive stays Later (no trustworthy checksum). **If the spike fails, SMB goes first and the owner is told.**
- **Drive space, honestly:** before a Drive backup, show how much space the selection needs and how much is free in the user's Google account
  (Drive `about.storageQuota`), in plain words; the free 15 GB is shared with Gmail and Photos and won't fit large moves. Never start a job that
  can't fit; offer a smaller selection instead.
- **Modules:** each target is `backup:target-<name>` implementing `BackupTarget`. Shared `backup:net` (one OkHttp factory) and
  `backup:auth-oauth` (AppAuth). Only these modules may import network or OAuth APIs (Konsist + dependency allowlist). No Play Services, no MSAL,
  no Google/Microsoft client libraries, no embedded client secrets, in any flavor.
- **Verification capability:** `TargetCaps` gains `serverDigests: Set<DigestKind>`. A file is `VERIFIED` (may be freed) only if
  (a) a read-back SHA-256 matches, or (b) a **server-computed** digest of a trusted kind (`SHA256`, `DROPBOX_CONTENT_HASH`, server-computed
  `SHA1`) equals KeepClear's local digest of the same bytes. `QUICK_XOR`, `MD5` and client-echoed values never verify. If a digest is missing,
  fall back to read-back if `canReadBack`, else the file stays "copied, not verified" (MOVE-14). The method is recorded per file.
- **Delete-time re-check:** server-digest targets re-query presence + size + digest. Read-back targets re-check presence + size. If the target
  is unreachable, nothing is deleted. Deletion still needs the user's tap and goes to the 30-day trash (safety rules 1, 2, 5).
- **Uploads:** a new `ResumableUpload` port. Session handles (Drive session URI, Graph `uploadUrl`, Dropbox `session_id`, Nextcloud upload id)
  are stored encrypted in the job store, and the job resumes from the server-reported offset after a process kill. Uploads never overwrite.
- **Scopes:** `drive.file`; Dropbox App folder; `Files.ReadWrite.AppFolder` (personal accounts only); Nextcloud app password via Login Flow v2.
- **Network rule (amends 0008):** each target declares a host allowlist: the host the user typed for SMB/WebDAV, or fixed provider hosts and
  domain suffixes for cloud. The shared client refuses every other host. Settings shows "Network: only to <target> (<hosts>)". Unmetered networks
  by default. `INTERNET` (and `ACCESS_LOCAL_NETWORK` once targetSdk ≥ 37) is the only network permission allowed by the manifest check.
- **Flavors:** all targets in both flavors. If F-Droid insists on NonFreeNet, accept the label or offer a build property that leaves the
  proprietary-cloud modules out.

### Amendment (2026-11-01, M17a WebDAV/Nextcloud engine; proposed; its first point superseded on 2026-11-02, see below)
Building `backup:target-webdav` showed two points where the decision above needs detail or a change:
- ~~**No OkHttp for WebDAV.**~~ *Superseded by the owner's decision of 2026-11-02 (below).* Android's `HttpURLConnection` refuses the WebDAV methods (PROPFIND, MKCOL, MOVE) and
  `java.net.http` does not exist on Android, so `backup:target-webdav` speaks HTTP/1.1 itself over the JDK's
  sockets and TLS (`MiniHttp`): no new dependency (PRIV-1), no redirects followed (credentials never leave the
  typed host), bounded responses, system CAs plus host-name check, never "trust all". The shared `backup:net`
  (OkHttp) is decided again with the first OAuth cloud (Dropbox), where it may still be the better choice.
  Self-signed home servers are refused for now; pinning a certificate on first use is a later step.
- **How a server digest is used.** `TargetCaps.serverDigests` (empty by default) says which kinds the server
  computes; `BackupTarget.serverDigest(rel, kind)` returns the value the server answered for this target's own
  upload (Nextcloud `X-Hash-SHA256` for `X-Hash: sha256`, followed through the rename to the final name). It
  can't be asked again later. The MOVE engine verifies with it instead of a read-back when it is there, and
  marks the copy confirmed at once (the server hashed what it stored; no phone cache is involved). A missing or
  malformed value falls back to read-back; a wrong one is a mismatch. **At free time the copy is still read back
  and re-hashed** (stricter than "re-query presence + size + digest" above, because `X-Hash` can't be
  re-queried). Generic WebDAV has no server digest and works like SMB (read-back, "Check backup" over a new
  connection). Recording the method per file needs a Room migration and comes with the app wiring (M17b).

### Amendment (2026-11-01, M17c Dropbox engine; proposed; its first point superseded on 2026-11-02, see below)
- ~~**One HTTP client, no OkHttp.**~~ *Superseded by the owner's decision of 2026-11-02 (below).* `MiniHttp` moved from `backup:target-webdav` into the shared `backup:net` (pure JVM,
  unchanged behaviour; a `repeatable` flag lets read-only Dropbox RPCs, which are POSTs, be resent once on a fresh
  connection like GET). `backup:target-dropbox` uses it, so there is still no third-party HTTP library and one audited
  client instead of two. OkHttp stays possible later if the owner prefers it (W3).
- **Dropbox's digest can be asked again.** Unlike Nextcloud's `X-Hash`, `content_hash` is part of the file's metadata,
  so `serverDigest` re-queries `get_metadata` and returns what Dropbox reports for the stored file now. The engine
  computes the block hash in the same pass as the SHA-256 (no second read of the original) and treats a missing or
  malformed value as "no digest" (read-back) and a wrong one as a mismatch, exactly as for Nextcloud. Free time still
  downloads and re-hashes each copy (stricter than the re-query above).
- **Not yet:** the `ResumableUpload` port. An upload session lives only as long as one upload stream; a paused job
  uploads the interrupted file again from the start. The per-request `content_hash` upload argument isn't sent (the
  comparison afterwards is the proof). Both can come with M17b.

### Owner decision (2026-11-02, W3 and W4)
- **OkHttp in `backup:net`** (W3): "Use OkHttp (Apache-2.0) instead of the hand-written HTTP client. Add it to the
  dependency allowlist, confined to the network target modules (ADR 0008). Delete the custom client and port its
  tests. Reason: TLS, hostname verification, redirects and chunked encoding are too risky to hand-roll in a backup
  app." Done: `MiniHttp` is deleted; `backup:net`'s `HostHttpClient` wraps OkHttp 4.12.0 (and Okio) with the same
  rules as before (one host, no redirects, no proxy, system CAs + host-name check, HTTP/1.1, bounded answers, no
  automatic retries, the last byte of an announced length held back, `close()` aborts everything in flight;
  design.md §6.1). OkHttp and Okio are on the PRIV-1 allowlist as exact modules, and only `backup:net` may declare
  them (build-logic `checkDependencyConfinement`) or import them (Konsist). The decision's first paragraph above
  ("one OkHttp factory" in `backup:net`) stands; the two "no OkHttp" points of the amendments are superseded. The
  rest of both amendments (how server digests are used, Dropbox's re-queryable `content_hash`, "Not yet") stays.
- **A real Nextcloud in CI** (W4): "Don't wait for an account. Run the official Nextcloud Docker image in CI (like
  Samba for SMB) and test the WebDAV target against it, including server checksums." Done:
  `scripts/nextcloud-test-server.sh` and the CI step; findings in design.md §6.1 (one difference from the fake:
  423 Locked while a name is being uploaded).

## Consequences
- Phone-only users get a verified MOVE target. Drive, Dropbox and Nextcloud verify without doubling traffic; SMB, OneDrive and generic WebDAV
  pay a read-back.
- The privacy promise changes from "no network" to "no network unless you connect a backup place, and then only to it". The ONB-1 copy,
  PRIVACY.md and the Data safety form are updated in the first network milestone.
- Operational work for the owner: a Google Cloud project with consent-screen/brand verification, an Entra app registration, and Dropbox
  production approval (after 50 users). Google and Microsoft sign-in only works on builds signed with the release key; self-built APKs can't sign in.
- Requires two local digests for Dropbox (plain SHA-256 and the block hash) in one pass. Cheap compared with the link.
- Spec updates in the first network milestone: MOVE-4 (targets), PRIV-2 (host allowlist instead of user-typed host only), design.md §6.1 caps.

## Alternatives
- **Read-back for every network target:** simplest and uniform, but doubles cloud traffic and time for 75 GB.
- **Trust any provider hash (incl. quickXorHash/MD5) + size:** faster, but quickXorHash misses swapped fragments, and it breaks ADR 0007's SHA-256 bar.
- **Cloud via SAF DocumentsProviders only (no INTERNET):** no tree grant on Drive and asynchronous upload, so it can't verify (ADR 0007, MOVE-14).
- **Official SDKs (play-services-auth, MSAL, Google API client):** Play Services or heavy, F-Droid-hostile dependencies.
- **Proprietary clouds only in `play`:** loses phone-only users on F-Droid. K-9 Mail shows optional proprietary sign-in is accepted on F-Droid without an anti-feature.
