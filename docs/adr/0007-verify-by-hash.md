# ADR 0007: MOVE deletes originals only after per-file SHA-256 verification

Status: Proposed · Date: 2026-10-31

## Context
The owner's initial rule was "verify file count + size". A truncated-then-padded or bit-flipped copy has the right size; FAT32 silently fails on files >4 GiB; SAF providers (e.g. Google Drive) may report success before upload. Neo-Backup has no content verification; Google Photos "Free up space" shows no visible verification ([06](../research/06-backup-targets-and-verification.md), [07](../research/07-competitor-ux.md)).

On the owner's phone the link (USB 2.0 ≈30–40 MB/s, Wi-Fi 6 ≈ up to ~110 MB/s) is far slower than SHA-256 on a modern ARM core, so hashing is not the bottleneck [ESTIMATE].

## Decision
- Compute SHA-256 of the source **while copying** (single read).
- Verification levels; only **L3** unlocks deletion:
  - L1 count + total bytes, L2 per-file size, **L3 per-file SHA-256 match** from a read-back of the target (or a trusted server-side digest, e.g. Nextcloud `X-Hash`, Immich checksum).
- Copy to temp name → flush/fsync → rename → read back → hash compare → mark `VERIFIED` in Room and in the target manifest.
- Target gets `keepclear-manifest.jsonl` + `SHA256SUMS` (`sha256sum -c` compatible) written **last**.
- Right before deleting each original: re-check size + mtime (and target presence); if changed → skip and re-queue.
- Targets that cannot be read back (Drive via SAF, MTP done by the user) never unlock automatic deletion.

## Consequences
- Roughly 2× the target I/O (write + read back). 75 GB ≈ 70–90 min over USB 2.0 [ESTIMATE]. Acceptable for a one-time move; resumable by year.
- Users can independently verify their backup on a PC with `sha256sum -c SHA256SUMS`.

## Alternatives
- Count + size only: fast, misses corruption.
- XXH3/BLAKE3: faster but no benefit when I/O-bound; less interoperable.
