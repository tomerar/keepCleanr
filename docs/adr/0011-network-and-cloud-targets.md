# ADR 0011: Network and cloud backup targets, and when they may free originals

Status: Proposed (needs owner confirmation) · Date: 2026-11-01
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
- **Targets and order** (each its own milestone and PR, after the owner's OK): SMB (M13, unchanged) → Google Drive (gated by a 1-day OAuth spike
  without Play Services) → WebDAV/Nextcloud → Dropbox → OneDrive.
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
