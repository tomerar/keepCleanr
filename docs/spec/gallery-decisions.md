# Gallery: owner decisions and implementation authority

Status: **Accepted by owner, 2026-11-08. G1a internal implementation exists; application integration remains pending.**

This records the discussion after the research review. It is authoritative for gallery scope; requirements, architecture, ADRs, tasks and the builder handoff must agree with it. It does not claim that an experiment, benchmark, migration or device check has passed.

| ID | Owner decision | Implementation consequence |
|---|---|---|
| D-G1 | **Free means free: no additional payment.** No API bill, new subscription, paid server, trial expiry, credit dependence, quality upsell or mandatory paid service | Core and any admitted local enhancement work without an account, subscription, credits or network. Development tooling is separate from product runtime and cannot become a user prerequisite |
| D-G2 | **Analyze on the phone only.** | No gallery media, thumbnail, embedding or content leaves the device. No hosted model, remote model download or automatic cloud fallback. Existing explicitly chosen backup destinations remain a separate unchanged feature |
| D-G3 | **Similar and duplicate photos are the first priority.** Semantic album classification is later | Implement small comparison groups. Keep SHA-256 byte identity, perceptual similarity and safety category separate; do not reclassify memories as junk |
| D-G4 | **Deterministic first; use local AI only for the problematic subset if it earns its cost.** | Metadata/cheap hashing/visual hashes and deterministic quality facts resolve the easy cases. An explicit uncertainty router may request learned features only for bounded ambiguous candidates. Never embed the whole library by default |
| D-G5 | **Give a reasoned recommendation about what to keep.** | Use observable within-group reasons such as usable resolution, sharpness or clipped exposure. Explain the reason; abstain when no meaningful advantage exists. A recommendation is neither a selected item nor permission to delete |
| D-G6 | **Efficiency, speed and resource use are first-class constraints.** | Reuse valid results, analyze only new/changed/invalidated content, bound candidate work and AI workload, stop early when resolved or over budget, and measure real latency, memory and battery on representative devices |
| D-G7 | **Preserve the current safety policy.** | No automatic selection/deletion; current-session editable confirmation, kept/PROTECTED rules, source rechecks, keep-one guard and trash still apply. Camera/received MOVE memories stay on the existing verified-backup route |
| D-G8 | **Update all relevant documentation on main and prepare a complete implementation handoff.** | This documentation-only change is authorized directly on main. Gallery development is a scoped exception to the earlier feature freeze, starting at G1a and following the task gates. Existing release, physical-device and data-safety checks are not waived or marked complete |

## Existing ChatGPT/Claude subscriptions

The owner mentioned a possible future quality enhancement through an existing subscription, integration or A2A-like connection **only without additional cost**. The owner also chose on-device-only processing. These constraints apply together: the idea is recorded for later research, not approved as an exception to D-G1/D-G2. No account-linking, provider client, subscription-token transport, MCP/A2A bridge or paid backend belongs in this implementation. Such a protocol does not establish API entitlement or local execution.

## What is approved and what is not measured

The local-first staged product scope, selective-AI admission policy and reasoned-keeper UX are approved. ADR 0014 and the **policy** in ADR 0015 are accepted; no runtime/checkpoint is selected for production. Quantitative benchmark targets and initial router budgets are provisional engineering gates, not measured facts or retroactive owner-device acceptance. Existing SAFETY, PRIV, LOW and NFR constraints remain binding; do not silently loosen them to admit a model.

G0's owner scope/authorization portion is complete in this record. At implementation start, reconcile the current repository and record actual dataset/device availability. That inventory step is not another request for the same approval. Implement the next independently verifiable milestone, and record hardware/data-dependent work as pending when those resources are unavailable. Do not turn a missing phone benchmark into either fabricated evidence or a reason to do no useful implementation work.

## Scope of first implementation

User-started foreground analysis; virtual still-image groups; reasoned keeper recommendation and abstention; current authorized scope; incremental private cache; model-free operation; optional bounded local inference for ambiguous cases after admission; safe REVIEW confirmation/trash and existing MOVE backup handoff. Physical album moves, compression, EXIF edits, face identity, semantic search/OCR/captions and cloud integrations remain outside scope.
