# Gallery implementation milestones

**Status: Owner-authorized staged gallery scope, 2026-11-08. G1a merged (#65); G1b code merged (#67–#74; device checks pending); G2: developer tooling merged (#75), evaluation blocked (no consented labelled corpus or physical devices); G3: not started, prerequisites unmet ([G2 status](../verification/gallery-g2-2026-11-09.md)); G4a read-only comparison UI implemented (2026-11-09); disposal (G4b) pending.** Quantitative targets remain provisional/unmeasured.

The owner reviewed the research, chose strict free/on-device behavior, similar/duplicate grouping, deterministic-first selective local AI and reasoned keeper advice, then requested these documents on main and a full implementation handoff. See gallery-decisions.md (D-G1…D-G8). This is a scoped development exception to the prior freeze, not a device/release waiver. G0 owner authorization and baseline reconciliation are complete; finish the G1a PR gate without asking for the same approval again. [Verification](../verification/gallery-g1a-2026-11-08.md) records the internal implementation and remaining checks. Estimates are engineering effort, not calendar promises; hardware/data-dependent gates remain honestly pending.

## Rules for every future milestone

- One small milestone or less per PR, using the existing repository process. This document does not create any PR.
- Existing build-both-flavors, lint, architecture/policy, migration, en/he parity, screenshots and required device checks stay green.
- Every destructive change includes its meaningful fake/property/device tests in the same PR. No enabling deletion UI before the additive retention guard passes.
- Update spec, edge cases, threat model, ADR status, progress, privacy/help/licenses and changelog where behavior changes; do not silently diverge.
- User steps remain phone-only. Developer-only profiling/adb/conversion steps are labelled as such and never appear as normal setup requirements.
- Models, provider use and physical-file organization are not bundled together as one approval.

## Phase gates and milestones

| Milestone | Work | Definition of done / evidence | Effort |
|---|---|---|---|
| **G0 — scope adopted; reconcile baseline/resources** | **Owner authorization complete 2026-11-08.** At code start inspect current main/schema, dataset rights and available devices; maintain the pending hardware list | D-G1…D-G8 and Accepted ADRs 0014/0015 govern; no runtime selected, camera policy unchanged, physical acceptance not claimed. This inventory is not a new approval gate | 1–2 days baseline/resource inventory; owner approval already recorded |
| **G1a — read-only contracts (merged in #65; final-head CI/review passed)** | Define pure-JVM suggestion/source-version types, explicit uncertainty states and one run-wide budget policy; implement no-model metadata/hash/quality grouping and factual keeper advice behind an internal/test entry | Property/golden tests for collisions/chains/caps; engine cannot import Android/network/safety action APIs; no source mutations possible | 2–3 days |
| **G1b — Android read adapter and cache** | Bounded still-image decode/preprocessing, actual source scope/permission epoch, cancellation; additive gallery cache with declared migration and safe clear | Real-format golden fixtures, no-GMS/airplane pass, ENOSPC/permission/identity tests, existing DB rows preserved, measured bounded cache/WAL | 3–4 days |
| **G2 — evaluate baseline** (2026-11-09: developer harness, runbook and candidate probe ready, C215–C217; **evaluation blocked**: no licensed/consented real-image corpus, no independent labellers, no OnePlus 10T or 4 GB API 30 device, no energy setup; nothing measured, C218) | Label/split real-image corpus; calibrate Balanced; run V1/V2/V3/F1 and owner/entry hardware; instrument actual stages and quality | Frozen held-out results and failure analysis. Meets hard gates and AC-G4, or task narrowed/deferred. Decide whether a model experiment has a worthwhile unresolved gap | 3–5 days plus corpus/hardware sessions |
| **G3a — optional local model spike** (**not started**: G2 has shown no measured gap, so its prerequisites are unmet, C219) | Only if G2 justifies it: admit exact dependency/native graph first, then evaluate a viable CPU backend (documented LiteRT 1.4.2 compatibility line or ONNX with validated conversion/encoder); preprocess parity and float reference. Examined LiteRT 2.x/MediaPipe artifacts are not accepted as-is | License/NOTICE/digest, maintenance and ABI/16 KiB/R8/reproducibility evidence; no denied Play/unaudited transport dependency, valid output contract, no network or safety integration | 2–4 days; re-estimate if source build/conversion required |
| **G3b — optional model decision** | Evaluate byte-cache quantization and ambiguity-only retrieval/reranking; verify lazy load, shared-participant reuse and global budgets; repeated latency/energy/accuracy/user tasks vs A | AC-G11 and every hard gate pass. Choose **one** backend or reject the model. A valid result is “ship A without AI” | 3–6 days plus evaluation time |
| **G4a — read-only comparison UX (local 2026-11-09, not yet PR; [verification](../verification/gallery-g4a-ui-2026-11-09.md))** | Existing design components, scope/progress, small groups, quality-hint abstention, keep/dismiss and backup handoff for MOVE. Done locally: entry, run, groups, comparison, Details, Help topic, E-G20 hooks, en/he screenshots and UI tests. Remaining: owner phone review (progress.md G4a-1…4), persisted review decisions (G4a-2) | Accessible en/he screens, no category change or disposal preselection, model-free usable workflow, owner phone review | 2–3 days |
| **G4b — REVIEW action bridge** | Add distinct typed visual-group retention evidence at the choke point; connect only explicit eligible REVIEW choices to current summary/executor/trash | Cross-group/session/source-race tests, keep-one counterexamples, kill/fault tests, real Android trash/restore and test-mode verification in same PR. MOVE direct deletion remains unavailable | 3–5 days |
| **G5 — whole-product validation** | Counterbalanced review-time study, sustained resource tests, lifecycle/permissions/OEM matrix, help/privacy/license/terminology review | All AC-G*, E-G*, old safety regression suites and owner phone-only checks documented; accepted/rejected model decision preserved | 3–5 days plus participants |
| **G6 — release readiness** | Reproducible artifacts, native page-size/ABI checks, release metadata and current Play access-policy review, owner regression checklist | Existing release/signing/device gates satisfied; honest local-only capability listing; no unsupported “AI safe delete” claim | 1–2 days plus owner release steps |

Approximate engineering total: **18–29 working days without the optional learned spike**, or **23–39 with it**, including the listed evaluation work but excluding recruitment/device waits. The ranges express uncertainty, not a commitment. Re-estimate after G2; do not implement G3 simply because it was budgeted.

Dependencies: owner approval in G0 is complete; baseline/resource reconciliation → G1a → G1b → G2. G4a can use the evaluated deterministic baseline; G4b does not require G3. G3 is optional and may be skipped permanently. G5 validates the actual selected release combination; G6 follows its acceptance. No cloud milestone is part of this dependency chain.

## Phone-only owner checklist

Use the separate test app, starting in test mode. All destructive tests use deliberately prepared disposable/checked copies; preserve the repository's current two-confirmation requirement before leaving test mode.

1. Open “Find similar photos” with a chosen subset; confirm it says it sees only those pictures. Deny access and confirm the app remains useful.
2. In airplane mode start a local run. Check progress, Stop/Continue, empty results, supported/skipped counts and that normal scan/backup navigation remains understandable.
3. Compare repetitive screenshots and a camera burst. Nothing is preselected. Camera items offer backup, not direct deletion. Keep/dismiss never moves a photo.
4. For REVIEW images only, choose a few for disposal, change the confirmation list, then cancel. Files remain where they were. Choose every member and confirm the action is refused.
5. Close/reopen during review. Continue deferred choices; change one test image outside the app and confirm its old choice is dropped. No action runs merely because the app reopened.
6. With the model absent/disabled, verify useful simpler comparison still works. With low battery/space, see a calm pause rather than an error loop.
7. Check Hebrew/English, largest text, TalkBack and swapped swipe directions on the real phone.
8. Once implementation safety gates pass and disposable copies are prepared, perform a controlled REVIEW → trash → restore check and verify the images return. Existing camera backup/check/free/restore regression is a separate unchanged workflow.

In-app test diagnostics may show measured elapsed/count/memory information and aggregate status; precise energy profiling remains a developer task. Owner acceptance does not require adb, shell commands or a desktop.

## Rollback and stop criteria

Before first shipment, an internal local feature switch can disable gallery/model entry points while preserving existing scan/clean/backup/trash. Model disable retains A. Clearing gallery caches is scoped and does not remove kept decisions or safety records. No remote kill switch or analytics is introduced.

Stop release for any unconfirmed action, PROTECTED/MOVE boundary violation, lost/replaced original, corrupt safety migration, zero-network failure, unsatisfied native compatibility or hard resource/precision gate. If only a quality hint fails usefulness, remove that hint and retest the selected release, rather than blocking otherwise sound grouping. Rollback cannot rely on destructive DB downgrades; new cache tables are ignorable/recomputable while authoritative tables remain preserved.

## Owner decisions recorded 2026-11-08

| Decision | Accepted direction / pending evidence |
|---|---|
| Is the first scope comparison and virtual organization? | Yes; avoid source file reorganization and a new data-loss surface |
| May camera/received MOVE items be discarded without checked backup? | No under this plan; changing that would need a separate explicit product/spec decision |
| Should a model be added automatically? | No; approve only an evidence-gated experiment after A is evaluated |
| Should existing chat subscriptions be connected to the app? | No integration is authorized. A later idea must still satisfy D-G1/D-G2: no additional payment, credits dependency or off-device gallery processing |
| Should any personal photos be sent to a provider? | No; this plan proposes no cloud upload or provider integration |
| Are performance numbers accepted results? | No; they are proposed gates requiring real devices |
| Does the older freeze block gallery development? | D-G8 authorizes staged gallery development; existing phone/safety/release checks still apply at their real gates and remain pending |

ADR 0015's policy is accepted; G3 and any actual model remain conditional on baseline evidence. Use ../prompts/gallery-builder.md as the implementation handoff. No additional product cost or gallery network integration is authorized.
