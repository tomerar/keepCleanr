# Builder handoff: implement KeepClear's efficient local gallery organizer

Status: **Owner-authorized scope, 2026-11-08.** This is an implementation handoff, not a claim of completed code or benchmark results. The documentation owner has requested this handoff after reviewing the research and answering product questions.

Use the following prompt in a coding agent with access to `https://github.com/tomerar/keepCleanr`.

---

You are implementing KeepClear's gallery organizer. Act as a senior Kotlin/Android engineer who cares about free operation, simplicity, measured speed, incremental computation and data safety. Complete the next authorized milestone with real code and meaningful verification; do not stop after producing another general plan.

## 1. Read and establish the current baseline

Read repository instructions (`AGENTS.md` if present and `CLAUDE.md`) and the accepted specifications before editing:

- `docs/spec/requirements.md`, `design.md`, `edge-cases.md`, `threat-model.md`, `tasks.md`, `ux-flows.md`;
- `docs/spec/gallery-decisions.md` — D-G1…D-G8 are the owner's explicit decisions;
- `docs/spec/gallery-requirements.md`, `gallery-design.md`, `gallery-edge-cases.md`, `gallery-threat-model.md`, `gallery-benchmarks.md`, `gallery-tasks.md`, `gallery-traceability.md`;
- ADRs 0014/0015 plus the existing trash/hash/network/flavor/design ADRs;
- `docs/progress.md`, the gallery research/audit/source register and current tests/build policies.

The research audited `d8b596dbe6bb96184427d0aabe4f0eb9065772ae`; your current main may be newer. Inspect actual code, branch state and changes before reusing that audit. Preserve unrelated user changes. Use the current schema/milestone versions; do not assume schema 7 remains current. Implement the real existing architecture, not obsolete Hilt/Coil descriptions in historical prose.

G0 owner scope/authorization is already recorded. The earlier freeze has a scoped exception for staged gallery implementation; do not request the same approval again. Existing phone/release/safety checks are not waived. Start at the first incomplete G milestone, currently **G1a**. Complete it and its checks before starting a later milestone; never combine everything into a giant speculative change. Follow the repository's one-small-milestone-or-less PR convention. This prompt authorizes implementation work, not bypassing protected branches, force-pushing, publishing a release, spending money or sending photos externally.

## 2. Product contract: non-negotiable

1. **Zero additional product cost.** No API billing, new subscription, paid server, credits, expiring trial, paid quality tier or mandatory account. A user's existing ChatGPT/Claude subscription is not a dependency or an assumed API entitlement.
2. **Entirely on-device gallery processing.** No gallery images, thumbnails, embeddings or content may leave the phone. No cloud inference, telemetry, remote model downloader or automatic provider fallback. Existing user-selected backup networking is a separate unchanged capability.
3. **Deterministic first.** Most work should be solved without a model. AI is optional and may analyze only a bounded, explicitly ambiguous subset that has a measured unresolved task.
4. **First task: duplicates/similar photos.** Virtual comparison groups and a reasoned keeper recommendation. Semantic albums/search, faces, OCR, captions, compression and physical file organization are outside scope.
5. **Reasoned advice, never action authority.** Recommend using observable reasons and abstain on a tie/uncertainty. Do not preselect any item for disposal. No automatic deletion.
6. **Speed and efficiency are required.** Valid feature caching, invalidation, incremental changes, bounded candidate search, lazy model loading, early stopping and measured battery/memory/latency are core design work, not later polish.

MCP/A2A, consumer session cookies or a coding-assistant login do not make a hosted model free or local. Do not add such a bridge. An optional future existing-subscription idea is documented only for later separate research under the same cost/privacy constraints.

## 3. Implement a measured cascade

Use narrow ports and typed outputs. The intended flow is:

1. **Authorized inventory + cheap metadata:** reuse the actual scanner, scope, classification and exclusions. Do not introduce a second unrestricted scan or inspect backup/trash/protected/pending/kept content.
2. **Exact identity candidates:** size buckets and inexpensive prefilters, then full SHA-256 only where needed. Partial hashes are never byte-equality proof. Preserve the current retained-witness rules; gallery-wide exact groups do not broaden SAFE eligibility automatically.
3. **Cheap visual features:** bounded canonical decode, orientation/colour/aspect rules, dHash/pHash and deterministic quality measurements. Share the decode where possible. Reuse valid versioned results.
4. **Deterministic grouping and recommendation:** bounded retrieval and conservative consistency; avoid A~B~C transitive false merges and screenshot/text collisions. Use observable quality differences to recommend, with reasons and abstention.
5. **Explicit uncertainty routing:** distinguish resolved deterministic results, ambiguous candidates, no match, and unsupported/inaccessible items. Route to learned analysis only for calibrated ambiguity flags, not because a file exists or because a model is available. A no-match item does not automatically trigger semantic AI search.
6. **Optional local escalation:** if the model has passed all admission gates, infer only on the unresolved pair/group participants whose compatible feature is not already cached. Stop when the ambiguity is resolved, the current source/permission epoch changes, the user stops or a resource budget is reached. Do not run model inference across the library by default.
7. **Honest result:** keep unresolved/limited/skipped counts and deterministic suggestions if AI is disabled, unhelpful, unavailable or out of budget. Do not label truncated work complete or change confidence into deletion permission.

Initial provisional AI budget for experiments: at most `min(256, max(2, ceil(0.05 * eligibleImageCount)))` **new unique image-version inferences per user-started run**, and 30 seconds of total learned-stage wall time including first model initialization. Use the lower bound imposed by either limit. A pair needs both participants; do not start a group that cannot fit. Cache hits require no new inference. Do not reset the budget per group/pair, automatically restart, or bypass it with unbounded batching. No eligible unresolved work means zero model load and zero inference. On a time limit stop scheduling immediately; finish/cancel only the single bounded in-flight item and report measured overrun. These are provisional engineering caps to validate, not measured throughput or permission to relax existing resource limits.

Document candidate-generation recall separately: learned reranking cannot recover candidates it never sees. Any exploratory bounded search expansion stays inside the same unresolved-work policy and must earn its cost. Do not build an all-pairs cosine matrix or add HNSW/vector-database infrastructure without scale evidence.

## 4. Reuse and isolate

- Keep `engine:gallery` pure JVM and advisory: candidate/group/router/ranking policies over narrow read ports. No Android, native ML, network, mutable StorageGateway, ActionExecutor or file-action proof capability in that engine.
- Put bounded local decode/features in an Android adapter and optional single-runtime adapter only when admitted. Reuse current manual app composition, platform thumbnails and Paper & Ink components.
- Use the existing Room infrastructure through a narrow cache store, declared migration, exported schema and no destructive fallback. Do not assume a schema entity implies the app already uses it; the audit found MemoryHashCache wiring despite a Room hash-cache table.
- Version source identity, volume/ref/generation, permission epoch, model/algorithm/preprocessing/threshold and group decisions. Unknown/incompatible records are recomputable, never authorization. Source updates and permission reductions invalidate affected results/decisions.
- Do not persist vector JSON, all float vectors or thumbnail disk copies. Preserve total app-data ≤50 MB; gallery cache maximum 16 MB is **within**, not in addition to, that total and must include WAL/index allowance. Pause optional work below 500 MB free; minimal mode below 100 MB keeps its existing behavior. Preserve memory/UI-thread constraints and measure native allocations too.
- Keep one decode/inference worker initially, a bounded queue of 8 refs, at most 100 comparisons per image per stage, group maximum 20, cancellation, and explicit cap/partial counters. Recalibrate only with documented evidence.

## 5. Preserve safety at every handoff

Maintain SAFETY-1…10, PROTECTED/kept exclusions, current-session UI confirmation, source/witness rechecks, test mode and existing trash/recovery/backup proofs. A hash collision, identical vector or quality preference must not alter categories or manufacture a Witness/BackupProof.

Camera/received memories remain MOVE and offer comparison/keep/dismiss plus the existing backup route. Do not add a direct “delete worse camera shot” path. Only explicitly selected currently eligible REVIEW items may reach the editable confirmation and existing executor/trash route. Existing exact-duplicate cleanup remains unchanged.

Before enabling new gallery disposal UI, implement a separate typed visual-group retained-member check at the choke point, with cross-group/session/source-race tests. Do not overload exact-duplicate evidence with semantic claims. No all-member disposal, no stale decision transfer to replacement content and no revived confirmation after process death. Existing narrow permanent-deletion exceptions remain as defined; gallery creates none.

## 6. Model admission is conditional, not a prerequisite

Build and evaluate the deterministic baseline first. No ML dependency belongs in G1a. No model/runtime is approved for production by this prompt.

The research inspected Google's float32 MobileNetV3-small v1 model: 4,117,670 bytes, input `[1,224,224,3]`, output `[1,1024]`, embedded Apache-2.0 metadata, SHA-256 `bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40`. It was **not executed or benchmarked**. Recheck source/license/NOTICE/preprocessing/operator/runtime compatibility before using it.

Do not use package branding as proof of privacy: examined LiteRT 2.x graphs included denied Play AI-delivery; examined MediaPipe cores included data-transport/CCT components and the latest task package included a larger LiteRT-LM runtime. The documented LiteRT 1.4.2 CPU compatibility line and ONNX Runtime were only preliminary alternatives. Re-verify current exact artifacts, maintenance/security, transitive/native graph, F-Droid policy, 16 KiB/ABI/R8 and reproducibility. Never blindly exclude required dependencies or allowlist a whole vendor group.

Admit one backend only after the held-out quality, selective workload, review-time, zero-network, memory/footprint/battery and native gates pass. Benchmark A vs the **selective** hybrid, not a whole-library embedding architecture. Float-weight inference and byte-quantized stored vectors are different decisions. Keep a fully functional no-model path; a valid final outcome is shipping no model.

## 7. UX

Keep the existing tabs/design system; no new AI marketing or paid screen. User-started analysis, clear authorized/partial scope, calm progress and Stop/Continue. Compare small groups; show a keeper suggestion with one or two factual reasons, and say “No clear recommendation” when appropriate. A recommended image is not a checkbox or a disposal selection. Let the user disagree.

Use English/Hebrew resources together, current terminology, plural/infinitive Hebrew, bidi-isolated names/numbers, physical configurable swipe semantics, TalkBack actions, 200% font, contrast, 48 dp targets/56 dp primary actions and reduced motion. Keep technical model/delegate numbers under Details/Advanced. “Keep this time” differs from persistent “Do not offer again.”

Virtual grouping/cache clearing is never “space freed.” REVIEW trash and measured permanent/backup freeing retain current honest accounting. Do not inflate Home totals with overlapping suggestions.

## 8. Verification and evidence

Run checks appropriate to the actual milestone. Follow the repo's required build/policy suite (`./gradlew ciCheck`, build-logic tests and release flavor builds where applicable), migrations, screenshot/string parity and architecture checks. Extend meaningful existing tests, not duplicate formulas as tests.

At minimum cover:

- hash/quality preprocessing golden cases, finite/zero-vector behavior and deterministic grouping;
- identical/flat/text images, A~B~C chains, huge buckets and bounded comparisons;
- zero inference/model initialization for resolved/no-match/unsupported work, mixed ambiguity, cached features, shared participants, strict run-wide count/time budgets, cancellation and no automatic retry;
- partial permission reduction, source replacement (including same size/coarse mtime), late old-run publication, process death and stale review decisions;
- DB/WAL quota, clear-cache isolation, ENOSPC, low space, thermal/battery/memory pressure and descriptor/model lifecycle;
- no category/proof promotion, MOVE-not-deletable, keep-one guard and existing trash/restore/current-session/test-mode invariants;
- real no-GMS/airplane behavior and **online zero gallery network** with backup networking idle; static/native/dependency checks too;
- en/he × light/dark, TalkBack/200% font and current swipe settings.

Benchmark protocol is in `gallery-benchmarks.md`: real licensed/consented 1k/10k/50k image workloads, separate 100k metadata stress, representative OnePlus 10T/4 GB/OEM/physical Pixel/16 KiB environments, cold/warm and incremental runs, hard negatives and event-level holdout. Report precision/recall/coverage, inference fraction, cache hit rate, stage/total latency, PSS/native/DB/WAL/package, gross/idle-adjusted energy and sensor limitations. Vendor milliseconds are not KeepClear 10k results. Do not upload personal photos or commit them.

Quantitative targets are provisional unmeasured gates. Do not claim passed phone/energy/accuracy tests without running them. Missing hardware/data may leave admission/release pending while independently verified baseline implementation proceeds. Preserve existing hard safety/resource policy; narrow/defer a capability rather than invent results or silently weaken a gate.

## 9. Milestones, documentation and completion

Follow G1a → G1b → G2; G3 is optional and subset-only; G4a comparison UX and G4b safe REVIEW bridge do not depend on a model; G5/G6 validate and release only what passed. Work on the current milestone under repository conventions. Keep changes reviewable and supply tests with every destructive extension.

Update `docs/progress.md`, affected specs, ADR status, edge/threat/traceability, help/privacy/licenses and changelog honestly in the same implementation change. Do not describe planned behavior as already shipped. Never mark a hardware/data gate complete to unblock the next stage.

For each milestone report: concrete behavior delivered; requirement/edge IDs; changed files; checks actually run and results; measurements with device/corpus identity; remaining blockers; and the next incomplete milestone. Review the final diff for scope, lost data, hidden cost/network and accidental safety relaxation. Follow repository delivery conventions; no release, protected-branch bypass, forced update or unrelated cleanup.

Start now by inspecting current main and implementing **G1a**, not by asking whether the owner wants implementation or by adding an AI dependency. If you discover a real new scope/privacy/cost/safety decision that cannot be resolved within these accepted constraints, first finish the independent authorized work and present the concrete blocker and alternatives.

---

This documentation commit does not implement any of the above code. The handoff deliberately separates accepted product decisions, proposed engineering budgets, observed research metadata and unperformed acceptance tests.
