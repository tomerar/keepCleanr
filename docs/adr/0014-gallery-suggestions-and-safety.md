# ADR 0014: Gallery suggestions are local, advisory and separate from cleanup proof

Status: **Accepted by owner, 2026-11-08** · G1a internal advisory engine implemented; application integration pending

Relates to ADRs 0006/0007/0008/0010/0011/0013; expands REV-8. Owner decisions D-G1…D-G8 are recorded in docs/spec/gallery-decisions.md. Acceptance approves staged local development, not a claim that the feature ships.

## Context

KeepClear already has deterministic classification, exact Sent-copy detection, explicit review, verified backups and an action choke point. Similar-photo detection is planned but absent. An embedding can improve retrieval without establishing that two pictures contain the same bytes or emotional meaning. Current network exceptions are for backup targets, not remote gallery analysis. Camera and received memories belong to MOVE.

## Decision

1. Implement a free, user-started, foreground, local, read-only suggestion subsystem with deterministic metadata/exact-hash/visual-hash stages, reasoned keeper advice and abstention. Local AI may refine only a calibrated ambiguous subset after ADR 0015 admission. It produces virtual groups, explanations and optional quality hints, with no source-write or network capability.
2. Keep SAFE/REVIEW/MOVE/PROTECTED classification and all proof types authoritative and separate. Similarity cannot create an exact-duplicate witness or BackupProof, downgrade a memory to SAFE, or satisfy PermanentKinds.
3. Keep MOVE camera/received memories on the backup route. The proposal adds no direct disposal of these originals before verified backup. New group-review disposal can apply only to currently eligible REVIEW items through the existing confirmation/executor/trash flow.
4. Nothing is preselected for disposal. Add a distinct keep-one retention guard for visual groups before enabling their disposal UI; test its source versions, intersections and races at the choke point. Until that extension passes, comparisons are read-only.
5. Do not add cloud AI, provider auth, account linking, a backend or a gallery-specific network exception. Existing backup networking remains unchanged. A cloud request would require a separate task, owner decision and privacy/network ADR amendment.
6. No physical album moves, source renames, compression, metadata rewriting, face identity, OCR or generative captions in this milestone.

## Consequences

The first useful increment is free and works offline without a model. There is no added processor of personal photos. Photographic recall will be limited by the baseline; incomplete results and conservative abstention are acceptable. More ambitious gallery cleanup of camera originals is intentionally not authorized by this decision. It would need an explicit product/spec change, rather than an AI confidence threshold.

The accepted exceptions for daily expiry, existing regenerable/bin deletion and verified-backup original freeing remain exactly as defined today. This ADR creates none and removes none.

## Alternatives considered

- Cloud-first gallery classification: new privacy/billing/network surface without established task value.
- ML Kit labeling as a free universal solution: SDK terms/metrics and dependency rules conflict with current principles; object labels are not redundancy proof.
- Treat all high-similarity pairs as SAFE: violates the distinction between uncertain relatedness and verified redundancy.
- Rebuild storage/action infrastructure around an ML engine: unnecessary and increases the data-loss surface.

## Adoption evidence

Owner accepted the scope and requested documentation on main plus an implementation handoff on 2026-11-08. This creates a scoped gallery-development exception to the earlier freeze; it does not pass any device/release gate. Read-only/no-network tests and any new disposal-guard tests remain required in the implementation milestones. Existing safety ADRs are not weakened.

## Implementation note: G4b retention guard and REVIEW bridge (2026-11-09, local, not yet a PR)

Decision 4 is implemented as an additive extension of the existing choke point ([verification](../verification/gallery-g4b-2026-11-09.md); C227–C234 in [progress](../progress.md)). Nothing in decisions 1–3 or 5–6 changes.

- **Typed evidence (C227).** `PlannedOp.visualGroups` carries a `VisualRetention` (group id, a group-version digest, retained members with ref, path, size, modification time and identity) for every similar group the photo belongs to. It is a distinct type: no SHA-256, no `Witness`, no `duplicateGroup`, no `BackupProof`.
- **Structural refusal (C227).** `SafetyGuard` rejects the whole plan when the evidence sits on anything other than a plain REVIEW move of one file to the trash, is malformed, keeps no member, names one group twice, differs between ops of one group, keeps a MediaStore row without an identity, or keeps a member that any op of the same plan acts on (choosing every member, overlapping groups).
- **Live refusal (C227).** `ActionExecutor` re-reads every retained member right before each op of the group, after the existing re-checks and also in test mode; a member that is missing, a link or folder, changed in size, time or identity (a MediaStore edit or a move to Android's trash changes the identity), PROTECTED or kept since refuses the group for the rest of the run (`SkipReason.RETAINED_MEMBER_GONE`).
- **Never permanent (C228).** `PermanentKinds` returns no reason for an op with similar-photos evidence, whatever its rule, SAFE flag or backup proof; such an op always goes to the 30-day trash (KeepClear's folder trash, or Android's own trash for MediaStore rows after Android's dialog).
- **Bridge (C230–C232).** Only explicitly chosen REVIEW photos reach the existing editable confirmation and the existing clean path; MOVE memories keep only the backup handoff.

Residual risk, unchanged in kind from every MediaStore deletion: Android's own dialog runs after the executor's checks, so a kept photo removed by another app in that window is not seen; the evidence is only as complete as the comparison's grouping (the choke point cannot know a group the app does not name).
