# Gallery implementation specification

Status: **Owner-approved scope, 2026-11-08; G1a internal engine implemented, CI gate pending.**

Build a free, entirely on-device organizer for similar/duplicate photos with a reasoned recommendation about what to keep. Use deterministic stages first; learned inference is optional and limited to an explicitly uncertain subset. Speed, incremental work, battery, memory and original-data safety are acceptance constraints.

## Read order

1. [Owner decisions and authority](gallery-decisions.md)
2. [Requirements and acceptance criteria](gallery-requirements.md)
3. [Architecture and selective-AI router](gallery-design.md)
4. [ADR 0014: advisory local suggestions](../adr/0014-gallery-suggestions-and-safety.md) and [ADR 0015: model admission policy](../adr/0015-local-model-admission.md)
5. [Edge cases](gallery-edge-cases.md) and [threat model](gallery-threat-model.md)
6. [Benchmark and test strategy](gallery-benchmarks.md)
7. [Milestones](gallery-tasks.md) and [traceability](gallery-traceability.md)
8. [Research](../research/10-gallery-intelligence.md), [pinned repository audit](../research/10-gallery-repository-audit.md) and [sources](../research/10-gallery-sources.md)
9. [Complete builder handoff](../prompts/gallery-builder.md)

The main requirements/design/tasks point here; gallery annexes own the detailed GAL/AC-G/E-G/T-G contracts. Historical research records are evidence, not claims that the feature ships. Existing safety, classification, networking and release rules remain binding. Only the gallery development scope is an exception to the older feature freeze; owner phone/release checks are still required at their real gates.

## Current milestone

G0 scope approval is recorded. **G1a: read-only contracts and deterministic engine** is implemented behind an internal/test entry. Complete its required CI gate before G1b. See [verification](../verification/gallery-g1a-2026-11-08.md). Do not add an ML dependency during G1a, do not change application safety categories and do not implement a hosted-provider integration. Follow each subsequent small milestone and its checks rather than implementing a speculative complete AI stack in one change.
