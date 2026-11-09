# Gallery traceability and documentation changes

**Status: Owner-approved scope and policy, 2026-11-08; G1a internal engine implemented; application integration pending.** Quantitative targets are provisional and unmeasured. [Owner decisions](gallery-decisions.md) govern scope; existing safety/resource constraints remain binding. The G1a verification section below lists actual tests. Other proposed test names remain plans, not claims those tests already exist.

## G1b primitive under development

`CanonicalPixelsTest` covers synthetic EXIF orientations, aspect-preserving padding, transparency, low-detail/quality facts and rejected bounds. `BoundedPngDecoderTest` covers a synthetic PNG, malformed/animated/EXIF inputs, byte and pixel limits, cancellation and stream closure. These tests do not establish other formats, permission handling, persistence, photographic accuracy or device resource gates. The primitive is not wired to the app.

## G1b feature cache (2026-11-09, local)

| Requirement / case | Actual tests | Remaining |
|---|---|---|
| GAL-12, GAL-26, AC-G15; E-G07/10 | `GalleryFeatureStoreTest`: round trip; key, size, time, revision, missing revision, preprocessing and algorithm mismatches are misses; revision-backed reuse across runs; observation-bound records without a revision; corrupt stored facts are misses | A real caller; provider reindex/restore on devices |
| GAL-12; E-G02/25 | `GalleryFeatureStoreTest`: a newer epoch hides old records before any write, then deletes them; stale-epoch puts and backwards epochs refused; no write before an epoch is recorded | Real permission-epoch source and Selected Photos reductions |
| GAL-11, AC-G8 | `GalleryFeatureStoreTest` (no key/ref/path/revision/run text in any column); `ArchitectureTest` "gallery cache stays out of cloud backup and device transfer" | Report-redaction tests when diagnostics exist |
| GAL-13, AC-G8; E-G21; T-G12 | `GalleryFeatureStoreTest` clear isolation; `MigrationTest` "7 to 8 …" (every authoritative row identical); `ArchitectureTest` "gallery cache code cannot reach other tables or DAOs" | Clear UI wording ("clear suggestions") with G4a |
| GAL-14/15; E-G14/15; LOW-1/2/4 | `GalleryFeatureStoreTest` low-space refusal (LIGHT, MINIMAL, failing probe) and real `SQLITE_FULL` via `max_page_count`; `GalleryCacheQuotaTest` measured file and WAL at the 23,437-row cap after churn; eviction order tests | Phone size/WAL measurements; whole-app 50 MB share; filesystem ENOSPC |

## Actual G1a test mapping (2026-11-08)

| Contract portion | Existing implementation verification | Remaining gate |
|---|---|---|
| GAL-3; AC-G2; T-G1/10/14 | Build-logic `ForbiddenApisTest`: fully qualified storage/proof/action/Android/network/ML and reflection/native escape counterexamples; `ArchitectureTest` adds gallery purity/import/native rules | G1a full CI passed; later action-boundary regressions remain; no disposal bridge exists |
| GAL-5; E-G05/06; T-G8 | `GalleryGroupingPropertyTest`: chain rejection, seeded complete-link/disjoint/permutation properties, delayed copies, flat/text/aspect negatives, 5,000 collisions, cap counts and sizes | G2 candidate-generation recall, photographic quality and memory/latency measurements; AC-G4 not passed |
| GAL-10; E-G22/32; AC-G15 policy | `GalleryKeeperAdviceTest`: measured reasons, tie/conflict/invalid-fact abstention; grouping test keeps tied quality resolved without escalation | Blinded preference evaluation and independent selection UI |
| GAL-12/19 contract; E-G07/10/18/25/31; T-G4/5/13 | `GalleryGroupingPropertyTest`: changed same-size/time revision, incompatible feature version, NaN/Infinity/range errors, epoch/run changes during reads, cancellation | G1b real provider/cache/lifecycle behavior and process-death persistence |
| GAL-24/25; AC-G13/14 numeric policy; E-G29/30/31 | `GalleryRunBudgetTest`: uncalibrated/resolved/no-match/unsupported rejection, pair admission, run-wide count, shared/cache participants, source/model/preprocessing mismatch, initialization/time/overrun, pause/cancel/stale/foreign ticket, failed retry, early stop | G2 calibration, G3 actual runtime lifecycle/initialization/inference and cache persistence; no model is loaded by G1a |
| Feature primitive conventions | `PerceptualHashesTest`: dHash bit order, flat/structured pHash golden values and malformed samples | G1b real-format/orientation/colour/resize/quality fixtures |

The 32 gallery tests and focused policy checks are JVM evidence only; the [verification record](../verification/gallery-g1a-2026-11-08.md) lists exact runs and pending checks. The principal verification table below retains later integration plans.

## Requirements → criteria → failure cases → integration tests → milestone

| Requirements | Acceptance | Edge cases | Proposed principal verification | Milestone |
|---|---|---|---|---|
| GAL-1, GAL-2 | AC-G1/7 | E-G01…04/19/26 | `GalleryScopeTest`, Selected Photos device tests, scope UI snapshots | G1b/G4a |
| GAL-3 | AC-G2 | E-G05/18 | `GalleryEvidenceIsolationTest`, constant-vector/collision property tests, import/call confinement | G1a/G4b |
| GAL-4 | AC-G3 | E-G28 | `GalleryMemoryPolicyTest`, extend existing `ReviewActionsTest` MOVE case | G4a/G4b |
| GAL-5 | AC-G4 | E-G05/06/13 | `GalleryGroupingPropertyTest`, bounded-candidate reference/holdout evaluation | G1a/G2 |
| GAL-6, GAL-7, GAL-21 | AC-G3/5/9 | E-G22/25/27 | `GalleryReviewFlowTest`, exact confirmation/accounting/Undo/test-mode tests | G4a/G4b |
| GAL-8 | AC-G6 | E-G06/09 | `SimilarityRetentionGuardTest`, intersecting/sequential-group property and device tests | G4b |
| GAL-9, GAL-12, GAL-19 | AC-G5/6/7/11 | E-G02/07…10/23 | `GalleryVersionTest`, `GalleryResumeTest`, source-replacement and model-version golden tests | G1b/G3/G4b |
| GAL-10 | AC-G4 | E-G05/11/22 | `GalleryQualityHintsTest`, blinded preference/abstention evaluation | G2/G4a/G5 |
| GAL-11, GAL-13, GAL-20 | AC-G8 | E-G10/14/21/26 | `GalleryCacheIsolationTest`, backup-rule/report-redaction, fresh offline/no-GMS and online connection tests | G1b/G3/G5 |
| GAL-14, GAL-15 | AC-G7/9 | E-G06/13…17/20/25 | `GalleryBudgetPolicyTest`, ENOSPC/thermal/kill tests; V2/V3 device benchmarks | G1b/G2/G5 |
| GAL-16 | AC-G10 | E-G23/24 | Existing accessibility/terminology/parity/screenshot infrastructure extended for gallery | G4a/G5 |
| GAL-17, GAL-18 | AC-G7/11 | E-G17/18/26 | Model license/dependency/native audit, `GalleryModelContractTest`, fallback and controlled A/B evaluation | G3 only |
| GAL-22 | AC-G2/8 | E-G26/28 | No cloud/deletion/physical-move capability in dependency/API/UI review | G0 and every future PR |

## Existing tests to extend or preserve

| Existing test/contract inspected | Existing intent | Relevance |
|---|---|---|
| `FactEnricherTest` | Only true Sent duplicates get a fact; same size/different middle bytes fail; trash/removable/SAFE witnesses rejected | Preserve exact proof; do not replace SHA with a perceptual hash |
| `ProtectedPlanPropertyTest` | Random plans cannot touch protected paths | Add gallery-origin plans to the generator |
| `ActionExecutorTest` / `SafetyGuardTest` | Confirmation/session/source/witness/kept behavior | Add typed similarity-retention cases without weakening old checks |
| `PermanentKindsTest` / `PermanentDeleteTest` | Narrow permanent exceptions; everything else recoverable; explicit confirmation/test mode | Assert gallery similarity can never enable permanent disposal |
| `PermanentKillPointPropertyTest` | Non-permanent kinds preserved across random crashes | Extend failure injection around the gallery handoff |
| `MigrationTest` / `ScanIndexTest` | Declared schema evolution preserves rows; complete/incomplete scan and references | Preserve schema 7 safety data and authorized gallery records |
| `ReviewActionsTest` | Editable month selection, filenames/folders, MOVE not deletable, Undo after whole-group action | Reuse confirmation behavior and source policy |
| `SwipeH2bTest` / `SwipeSessionStoreTest` | Finish mid-review, deferred decisions, resume, changed/vanished item pruning, physical swipe mapping | Preserve current behavior; do not follow obsolete earlier mirrored-direction prose |
| `ViewModelStabilityTest` | No main-thread heavy work, scan cancellation, action progress/lifecycle and test-mode free behavior | New coordinator must preserve old flows and prevent stale run publication |
| `TestModeTest` | Debug default, two confirmations to disable, banners, redacted logs | No new bypass through a gallery action |
| `AccessibilityChecksTest` | Labels, targets and no clipped numbers at 200% font | Add comparison, pause, empty and confirmation screens |
| `ArchitectureTest` / build policies | Pure-JVM boundaries, write/network confinement, exact dependencies, manifest/privacy sync | Add runtime/model checks and source/bytecode boundaries |

These tests were inspected as source evidence and existing head CI was verified. No new local execution result is asserted.

## Main-document adoption and future implementation updates

| Existing document | Adoption / implementation follow-up | Preserve |
|---|---|---|
| `docs/spec/requirements.md` | Expand REV-8 into the accepted GAL-* scope or link the gallery annex; add AC-G references and explicit advisory/category boundaries | All SAFETY/CLS/MOVE/PRIV/LOW invariants; no accidental general camera-deletion requirement |
| `docs/spec/design.md` | Add component/port/data contracts and optional model admission; correct actual manual DI/platform-thumbnail baseline where stale | Existing safety, storage and backup contracts |
| `docs/spec/edge-cases.md` | Adopt accepted E-G cases or link annex, preserving IDs | Every existing failure contract |
| `docs/spec/threat-model.md` | Add T-G/SEC-G threats and evidence firewall; use actual current backup exceptions | Existing recovery/proof/no-loss model |
| `docs/spec/tasks.md` | G scope adopted and linked; G1a is current. Implementation and hardware gates remain pending | Existing device/release checklist and destructive-tests-in-same-PR rule |
| `docs/spec/ux-flows.md` | Add comparison/progress/empty/error/backup handoff and eligible REVIEW summary flows | P-1/2/3, no preselection, honest freed/trash accounting, current physical swipe mapping |
| `docs/adr/README.md` | 0014/0015 indexed as Accepted policy/scope; no runtime or implementation is approved by status alone | Accepted ADR history and amendments |
| `docs/progress.md` / `CLAUDE.md` | Record actual G status and current stack; reconcile stale resumption notes and freeze decision | Owner decisions; don't label pending real-phone checks complete |
| `docs/PRIVACY.md` | Explain local derived features, cache clear/no backup/logging, no gallery networking; correct stale Hebrew no-INTERNET wording | Existing selected backup destinations and explicit user-sharing behavior |
| `THIRD_PARTY.md` and in-app licenses | Only if a dependency/model is admitted: source, version, digest, license/NOTICE and conversion provenance | GPL-3.0-or-later project identity |
| Version catalog / dependency verification / policy docs | Only at admitted runtime implementation: exact coordinates/versions, checksums, confinement and ABI/16 KiB validation | No GMS/telemetry and no broad group allowlist |
| Help, en/he resources, review list, changelog, fastlane | Accepted actual behavior and limitations, measured resource claims only; add both languages together | Paper & Ink terminology, neutral Hebrew and no fear/boost claims |

The package's new files can remain annexes to reduce churn, but the accepted main spec must point to them and one authoritative requirement must own each behavior. Do not maintain contradictory duplicate requirements.

## Review checklist

Owner scope/category/cost/privacy and keeper choices are recorded in D-G1…D-G8. At implementation start verify actual device/corpus access and current code; measure the provisional quantitative gates and keep hardware/release checks pending until performed. A future implementation PR should quote its accepted requirement/ADR IDs and publish its own validation evidence.

## Owner-decision additions

| Requirements / decisions | Acceptance | Edge/threat cases | Planned verification | Milestone |
|---|---|---|---|---|
| GAL-23; D-G1/D-G2 | AC-G12/8 | E-G26; T-G14/SEC-G14 | Fresh offline/no-account, dependency/UI/online-zero-network checks; no provider/subscription capability | All stages |
| GAL-24; D-G4 | AC-G13 | E-G29/30; T-G13 | GalleryEscalationPolicyTest: calibrated state/reason eligibility, all-resolved/no-match/unsupported give zero inference | G1a/G3 |
| GAL-25; D-G6 | AC-G14 | E-G29/31; T-G13/SEC-G13 | GalleryRunBudgetTest: shared participants, global count/time, initialization/stop/resume, no cap reset or automatic retry | G1a/G3/G5 |
| GAL-26; D-G6 | AC-G15/9 | E-G30/31 | GalleryIncrementalWorkTest: zero-work revisit, targeted invalidation, decode/inference reuse, private aggregate stage counters | G1b/G3/G5 |
| GAL-10; D-G5 | AC-G15/4 | E-G32; T-G1/T-G10 | GalleryKeeperAdviceTest: measured reason, tie/contradiction abstention, no implicit selection/confirmation | G1a/G4a/G5 |

Implementation authority is in gallery-decisions.md, read order in gallery-overview.md and complete handoff in ../prompts/gallery-builder.md. The decision record supersedes any earlier proposal wording that suggests the same owner approval is still required. Hardware measurements and unimplemented test names stay pending.
