# Gallery threat-model extension

**Status: Owner-approved scope and policy, 2026-11-08; implementation pending.** Quantitative targets are provisional and unmeasured. [Owner decisions](gallery-decisions.md) govern scope; existing safety/resource constraints remain binding. Extends the main threat model and SEC-* controls; T-G*/SEC-G* are gallery identifiers.

## Assets and trust boundaries

Protect original bytes and metadata; trash/recovery records; backup verification proofs; user-kept choices; private image features; authorization scope; explicit pending decisions; and the integrity of the runtime/model/threshold contract.

Untrusted inputs include shared-storage files, malformed codecs/EXIF, filenames, provider metadata and handles, remotely obtained model artifacts at development time, cached/partially migrated records and any future provider response. Current classification and safety checks are authoritative but still revalidated at action time. Embeddings and blur scores are untrusted advice.

Boundaries: external storage/provider → decoder; decoder/model → feature cache; cache → grouping/UI; user review → confirmed action plan; executor → existing storage/trash; app process → existing backup network modules. A compromised OS/root remains outside the repository's current threat scope, but exposing extra feature data is still minimized.

| Threat | Impact | Required mitigation / security contract | Verification | Residual risk |
|---|---|---|---|---|
| T-G1: embedding/hash collision or semantic confusion | Unique photos wrongly suggested together | SEC-G1: advisory types cannot produce SAFE/witness/backup proof; no automatic selection; complete-link group checks, previews and keep-one rule | Constant/identical-vector fuzz, low-detail screenshots, unrelated-family hard negatives, architecture checks | User can still choose the wrong REVIEW item; recoverability and clear comparison reduce harm |
| T-G2: model/native/codec exploit | Crash, resource abuse or possible code execution | SEC-G2: bounded dimensions and allocations, local-format allowlist, patched pinned runtime, exact artifact digests, no custom ops/downloaded executable code, fail closed to baseline | Malformed-image corpus, fuzzing, native compatibility, SBOM/review and fault tests | Native codecs are a material attack surface; isolate adapter and consider a capability-minimized process later only if evidence justifies its complexity |
| T-G3: model supply-chain swap | Hidden behavior or corrupted comparisons | SEC-G3: verify approved SHA-256 and provenance at build; validate input/output contract; atomic versioned deployment and no runtime downloader | Deliberately wrong digest/shape/version must fail CI or model admission | A vetted upstream itself can contain defects; updates require review |
| T-G4: stale permission/ID reuse | Cached private image shown or analyzed after access loss | SEC-G4: authorization epoch, per-resume/per-read checks, volume-scoped IDs/generation and MediaStore version; invalidate/hide inaccessible features immediately | Selected Photos reductions, grant revoke, provider replacement, restored/reindexed media | Race after final permission check may throw; treat as unavailable, not permission to continue |
| T-G5: same-path edit or symlink swap | Decision acts on another file | SEC-G5: source/group versions, existing no-follow/stat/identity safeguards; uncertain content invalidates decisions; live executor recheck | Same-size/coarse-time edit, identity swap, rename during review, process restart | Existing all-files-access TOCTOU residual risk remains; ML is never accepted as cryptographic identity |
| T-G6: cached features leak photo meaning | Sensitive family/location/document inference | SEC-G6: app-private storage, no backup/device transfer, no ordinary log/report/export payloads, clearable feature cache, bounded retention | Backup-rule inspection, redacted report tests, private cache permissions | Unlocked-device/compromised-OS access cannot be prevented by this feature |
| T-G7: hidden network/telemetry in SDK | Photos/features or usage leave phone | SEC-G7: standalone audited runtime, exact allowlist, no sockets in gallery modules, dependency/native review and dynamic connection observation; no backup running during isolation tests | Fresh install offline and online zero-network tests; test with INTERNET already present for backups | Android permission is process-wide, so static confinement alone is insufficient; dependency behavior must be checked |
| T-G8: algorithmic denial of service | Hot phone, OOM, full disk, unresponsive UI | SEC-G8: one worker, bounded queue/candidates/groups, source and pixel caps, cache/WAL quota, thermal/battery/low-space pauses | Giant dimensions, 50k near-identical images, ENOSPC, memory trim, candidate-explosion tests | Some photos will remain unprocessed; report coverage honestly |
| T-G9: intersecting groups / double confirmation | Every related member selected over one or several sessions | SEC-G9: disjoint actionable groups, global plan validation, typed live retained-member check at executor, invalidate old group decisions | Cross-group and sequential-session counterexamples, simultaneous selection, rapid taps | Similarity is not equivalence; retaining one does not prove every discarded variation is redundant |
| T-G10: model output becomes authority | Model strings/IDs route actions or alter categories | SEC-G10: explanation codes from an allowlist, bounded finite numeric outputs, no tool execution, no plan fields accepted from model responses | Arbitrary strings, NaN/Inf/zero vectors, malformed cache/test provider outputs | Model errors remain possible as suggestions only |
| T-G11: deletion intent survives restart as consent | User files altered without a current-session tap | SEC-G11: persisted review is deferred intent; confirmations are ephemeral/current session; source versions checked before reconstructing list | Kill after decision/summary/confirmation/before action, update/reinstall/resume tests | Review may need to be repeated; that is safer than implicit consent |
| T-G12: clear-cache/migration deletes safety records | Trash or backup recovery lost | SEC-G12: gallery tables scoped separately; clear is transactional and cannot touch other tables/user choices; declared migrations with golden legacy databases | Gallery clear under each migration/fault point; rows/proofs/sidecars unchanged | Device storage corruption remains an existing platform risk |

## No-loss definition

For read-only analysis, **every original remains byte-for-byte unchanged at its original authorized location**; no operation may move, overwrite or delete it. Cache-write failure cannot violate this.

For gallery disposal of an explicitly selected existing REVIEW item, the existing invariant holds: after each crash point, an item is at its original path or in a recoverable, indexed/safely recoverable trash state. Gallery similarity never supplies the proof for permanent deletion. Existing verified-backup freeing keeps the repository's separate proof-based invariant; no tests or guarantees are weakened.

## Future cloud boundary, currently excluded

If separately approved later: require explicit thumbnail selection/preview, metadata stripping, no names/paths/EXIF by default, per-provider retention disclosure, bounded authenticated HTTPS, a secure credential architecture, cost/rate ceilings, no redirects or automatic cross-provider fallback, and strict response validation. Treat text inside images as untrusted data, disable tools/actions, and never give a provider storage/plan capabilities. Children/family images increase privacy sensitivity; the free Gemini policy is not the default path. This paragraph authorizes no upload or implementation.

## Selective-compute additions

| Threat | Required mitigation | Verification |
|---|---|---|
| T-G13: pathological ambiguity amplifies compute or per-group budget resets evade limits | SEC-G13: calibrated explicit routing states; unique participant deduplication; one run-wide count/elapsed budget including initialization; cache/lazy load/early stop; no automatic retries/new runs | All-resolved, all-ambiguous, shared-participant, time-cap, cancel/resume and stale-epoch counterexamples; measured inference fraction |
| T-G14: cost or quality fallback sends gallery data to a provider or requires subscription credits | SEC-G14: D-G1/D-G2 are capability restrictions. No provider/download/account/paid-tier module, A2A/MCP bridge, token reuse or hosted fallback; model-free offline core remains useful | Dependency/UI/source review and online zero-network tests with no account/credits; model failure and budget exhaustion never create a remote request |

Keeper recommendations are untrusted advice bound to measured facts, never selection/confirmation. T-G1/T-G10 apply to explanations and recommendation markers too.
