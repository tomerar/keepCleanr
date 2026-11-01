# ADR 0007: MOVE deletes originals only after per-file SHA-256 verification

Status: Accepted (2026-10-31) · Date: 2026-10-31

## Context
The owner's initial rule was "verify file count + size". A truncated-then-padded or bit-flipped copy has the right size; FAT32 silently fails on files >4 GiB; SAF providers (e.g. Google Drive) may report success before upload. Neo-Backup has no content verification; Google Photos "Free up space" shows no visible verification ([06](../research/06-backup-targets-and-verification.md), [07](../research/07-competitor-ux.md)).

On the owner's phone the link (USB 2.0 ≈30–40 MB/s, Wi-Fi 6 ≈ up to ~110 MB/s) is far slower than SHA-256 on a modern ARM core, so hashing is not the bottleneck [ESTIMATE].

## Decision
- Compute SHA-256 of the source **while copying** (single read).
- Verification levels; only **L3** unlocks deletion:
  - L1 count + total bytes, L2 per-file size, **L3 per-file SHA-256 match** from a read-back of the target (or a trusted server-side digest, e.g. Nextcloud `X-Hash`, Immich checksum).
- Copy to temp name → flush/fsync → rename → read back → hash compare → mark `VERIFIED` in Room and in the target manifest.
- Target gets `keepclear-manifest.jsonl` + `SHA256SUMS` (`sha256sum -c` compatible) written **last**.
- Right before deleting each original: re-check size + mtime and target presence, **and re-hash the original** (strict mode, on by default for all files, owner decision Q13); any difference → skip and re-queue. The extra cost is one sequential read of the phone copy (fast local flash).
- Targets that cannot be read back (Drive via SAF, MTP done by the user) never unlock automatic deletion.

### Amendment (2026-11-01, independent review H1)
The first read-back happens seconds after writing, through the same kernel, so it can be served from the phone's
page cache instead of the drive. A fake-capacity USB stick (reports 2 TB, wraps writes around) passes that read.
**Reality differed from the decision above; L3 now needs two reads:**
- **First pass** (during the job): as above. It marks `VERIFIED` but does **not** unlock deletion.
- **Second pass, uncached** ("Check backup", MOVE-15): every verified copy is read again and hashed only after the
  app has seen the drive **unplugged and plugged back in** (unmount drops its cache), or after the phone
  **restarted** since the first pass (different boot id). A match sets `confirmedAt`; a mismatch or missing copy
  sends the file back to "copy again". Only confirmed copies can be freed.
- **At free time** each drive copy is hashed again (not just its size), and the original is re-hashed (strict mode).
- **Unknown storage providers are copy-only** (`canReadBack = false`): a provider can answer from its own cache or
  upload later, so a matching read proves nothing. Only Android's external-storage provider (USB, SD) can unlock
  deletion; cloud providers need a server-computed checksum (ADR 0011).
- Copies verified before this change (database v3) are not confirmed and must pass "Check backup" first.

## Consequences
- Roughly 3× the target I/O (write + two reads). 75 GB ≈ 70–90 min over USB 2.0 [ESTIMATE]. Acceptable for a one-time move; resumable by year.
- Users can independently verify their backup on a PC with `sha256sum -c SHA256SUMS`.

## Alternatives
- Count + size only: fast, misses corruption.
- XXH3/BLAKE3: faster but no benefit when I/O-bound; less interoperable.
