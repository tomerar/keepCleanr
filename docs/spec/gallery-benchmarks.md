# Gallery benchmark and test strategy

**Status: Owner-approved efficiency/quality policy; provisional numerical protocol and targets — no measured KeepClear gallery results.** Date: 8 November 2026. D-G1…D-G8 apply.

Existing owner checks, CI and no-loss tests remain mandatory. This protocol distinguishes filesystem scale, image decoding, task accuracy, user usefulness and energy; none substitutes for the others. The developer measurement workflow may use a computer. Ordinary setup/review and the owner's acceptance checklist remain phone-only, per P-1.

## Questions the evaluation must answer

1. Does the simple baseline already solve the relevant review task?
2. Do suggestions group genuinely repetitive images, including difficult non-matches, with conservative precision?
3. Does a learned encoder improve useful recall and user review time at the same precision, rather than merely grouping more photos?
4. Can the entire pipeline run on a 4 GB/API 30 device and the owner's OnePlus 10T with bounded sustained memory, energy, temperature and disk use?
5. Are offline access, cancellation, source identity, trash recovery and existing backup behavior intact across faults?

## Offline scoring tool — preparation for G2

`scripts/gallery-benchmark-score.py` scores **metadata and human-labelled relationships only**, without reading, decoding or uploading any photos. Its synthetic unit tests verify arithmetic and safeguards; they are not photographic accuracy measurements and do not complete G2. Run:

```sh
python3 -I scripts/gallery-benchmark-score.py --manifest manifest.csv --labels labels.csv --groups groups.csv --candidates candidates.csv --split holdout > private-aggregate.json
python3 -I -B -m unittest discover -s scripts/tests -p 'test_gallery_benchmark*.py'
```

Use only opaque IDs and nonsensitive category names; keep the CSVs and the output private and review aggregates before sharing. Input is UTF-8 CSV with these headers:

- Manifest: `image_id,event_id,split,stratum,status`, where split is `development`, `validation` or `holdout`, and status is `analyzed`, `unsupported`, `skipped` or `not_processed`. Include the full eligible reference scope, not only analyzed items. An event cannot appear in multiple splits. An optional `sha256` column holds locally computed lowercase full-file digests: when supplied, identical bytes in different splits or assigned bootstrap clusters are rejected, including unlabelled members and intermediate unlabelled events linking labelled events. The report shows how many images had digests; missing digests limit this leakage check, and digest values are never printed.
- Labels: `image_a,image_b,label,cluster_id,pair_stratum`. Labels are `match`, `different` or `ambiguous`; every pair must remain within one split. Cluster IDs represent independent event/family units for resampling: all pairs sharing an event must use the same cluster, including cross-event hard-negative links. This constraint prevents a known form of false statistical independence; reviewers must still validate provenance, sampling and inter-cluster independence.
- Groups: `group_id,image_id`, one row per member in the selected split. Groups must be disjoint, contain 2–20 images and include only analyzed items. Every within-group pair is scored, not only its anchor edges.
- Optional candidates: `image_a,image_b` from the pre-reranking candidate stage, including candidates that were not grouped. Without this file candidate recall is reported as unknown.

The JSON reports labelled reference-pair recall including unprocessed images, pre-reranking recall on that reference, known-pair precision, uncertain/unlabelled suggestions, false-merge groups, split/event counts and labelled pair strata. The deterministic 95% cluster-bootstrap interval is **only for known, unambiguous suggested pairs** (clusters sorted by ID; percentile indices use floor); freeze the tool version, Python version and seed alongside the corpus; it is unavailable if there are fewer than two clusters, too many invalid resamples, or more than ten million bootstrap draws. Undefined metrics are `null`, never zero. Missing/ambiguous labels and incomplete processing are reported separately; a precision estimate over known labels is not the precision of all suggestions. No automatic release-gate decision is made.

This tool cannot establish dataset rights, label quality, sufficient negative sampling, device performance, energy, confidence-interval validity for the actual study, or AC-G4 compliance. G2 must freeze the corpus/splits and threshold before held-out evaluation and document those independent checks.

## Corpus and ground truth

Create a versioned, licensed/consented **real-image** benchmark corpus, stored locally for testing. Do not upload a family library to providers, commit personal photos, or use owner consent to this research as permission to access a personal gallery. Public fixtures must have redistribution rights; private owner evaluation stays on the owner's phone. Keep a local manifest with image digests, dimensions, codec, provenance/rights, source/event labels and transformations. Shared reports contain only aggregate/opaque test identifiers.

| Workload | Size and composition | Purpose |
|---|---|---|
| V1 smoke | 1,000 real decodable images | Fast pipeline, golden preprocessing and error tests |
| V2 representative | 10,000 assets: approximately 6,000 camera JPEG/HEIC, 2,000 PNG screenshots, 1,200 messenger/recompressed images, 500 downloaded/rendered WebP/JPEG, 200 animated assets, 100 RAW/corrupt/unsupported fixtures | End-to-end owner-scale work, truthful skip rates, storage/energy |
| V3 scale | 50,000 real assets using disjoint events plus explicitly labelled repeated/transformed families | Sustained performance, candidate explosion and bounded-cache eviction. Duplicating the same bytes under new names is a separate stress test, not realistic accuracy evidence |
| F1 metadata | 100,000 filesystem entries with names/depth/permissions matching OwnerDevice | Existing scan/identity/fault bounds only; never used to claim vision accuracy or photo decode throughput |
| H1 difficult subset | ≥1,000 labelled near-duplicate families and ≥3,000 hard-negative candidate pairs, with adequate distinct events | Precision/recall calibration and confidence intervals; includes pairs missed by candidate generation |

Composition is a recruitment target, not a claim that those files exist today. Publish actual counts and excluded/unsupported formats; never calculate throughput only on the easy JPEG subset while calling it a full-library result.

Ground truth separates **exact bytes**, **near-duplicate variations/bursts**, **same subject but distinct useful photo**, and **unrelated**. Near-duplicate here means humans would reasonably want to compare the pair for review; it does not mean either may safely be discarded. Ambiguous pairs are labelled ambiguous and trigger abstention; report their prevalence. A single subjective keeper label is never a data-safety oracle.

Use two independent reviewers for H1, adjudicate disagreements, and report agreement. Include low-light/motion blur, children with subtly different expressions, a person absent in one frame, different family members in similar clothes, sunsets, paintings/AI renders, screenshots with a one-digit difference, receipts, rotated/mirrored images, crops, filters, recompression, HDR, transparency, panorama and low-detail collisions. Keep difficult formats represented at meaningful counts.

Split by original event/burst/source family, not by file or generated transformation: 40% development/calibration, 20% validation, 40% final holdout is the proposed split. Ensure holdout has sufficient hard negatives/families; if it does not, collect more instead of borrowing training data. Freeze the held-out set and thresholds before final comparison. Report subgroup sample sizes and wide intervals rather than implying certainty from a tiny subgroup.

## Devices and environments

| Class | Proposed representative | Required coverage |
|---|---|---|
| Owner reference | **OnePlus 10T**, actual RAM/OS/OxygenOS/storage recorded from device | Primary phone-only UX, cold/warm 10k, partial permissions, native CPU path, thermal and battery |
| Entry floor | Physical 4 GB Android 11/12 device, e.g. a maintained Redmi/Galaxy A-series unit running an API 30-compatible image | Real memory pressure, slower flash/CPU; no reliance on flagship acceleration |
| Midrange OEM | Galaxy A54/A55 or a Redmi Note 13-class device; exact variant recorded | One UI/HyperOS media permissions, codecs and lifecycle behavior |
| Stock power reference | Physical Pixel 6 or newer supported Pixel | ODPM/Perfetto power rails when supported; compare CPU vs accelerator, report exact hardware |
| Modern native compatibility | Android 15/16 arm64 device or configured system image with 16 KiB pages | Native model/runtime installation/loading, ABI/packaging and release R8 behavior |
| Layout | Foldable/tablet or faithful emulator | Large widths, landscape, 200% font, TalkBack and reduced motion |
| No Google services | API 30/36 AOSP image or available physical non-GMS build | Fresh-install bundled model/core operation without GMS or account |

Exact OEM examples are recruitment choices, not benchmark results or promises about an uninspected phone's installed OS/RAM. Record build fingerprint, security patch, SoC, actual RAM, storage free/capacity, filesystem, display refresh rate, battery health/nominal capacity, codec availability, runtime/model/ABI, page size, app flavor/build and checksums. All physical classes need the baseline; model admission requires owner + entry + Pixel + one OEM midrange. Emulators verify API/permission/native correctness, **not energy or sustained phone throughput**.

Repository CI at the audited head ran API 30 and 36 launch jobs. Proposed additional permission coverage includes API 33, 34/35 Selected Photos, and current target API 36; test API 37 if/when that target is adopted. Native libraries must support 16 KiB pages rather than relying on compatibility modes. Android's current page-size guidance is the implementation authority; this plan makes no assumption based on an older policy deadline. [S29–S31]

## Runs and controls

Compare A (hash only) with one admitted local candidate on identical images. B may use the documented LiteRT 1.4.2 CPU compatibility line after maintenance review; D may use ONNX Runtime with a validated encoder/conversion. Examined LiteRT 2.x Play-delivery and MediaPipe data-transport dependencies block automatic selection. MediaPipe can be a developer reference only after appropriate source/privacy review; do not build or ship every alternative by default.

Separate timing stages: permission/query/snapshot; read/decode/orientation/resize; hashes; model initialization/compilation; inference; index lookup; group consistency; DB commit/checkpoint; first visible progress/group; UI preview; handoff/revalidation. Record first-run model setup, total wall time, active CPU time, sustained images/sec, p50/p95/p99 image latency and error/skip/cap counts. Stage timings cannot simply be summed if work overlaps; total wall time is authoritative.

For each variant/device, run at least 5 cold-start and 5 warm runs on V2, randomized/counterbalanced in order, with cooldown to the same thermal state. A cold app/cache run is not necessarily cold kernel storage: label precisely “process cold,” “feature cache cold,” and “post-reboot.” Do not claim cold-flash results from clearing app memory. Use no root-only cache tricks as a user requirement.

Exercise 0-change rescan, +100 new images, changed/moved/deleted 1%, updated permission scope, missing model, unsupported delegate and interrupted resume. Run V3 to completion where within supported limits and also to the documented cap. For tiny microbenchmarks use many repetitions after warmup, but retain first-run latency separately.

Use a release-equivalent profileable build, the same stable R8 policy as production and the same model packaging. Debug StrictMode remains a correctness check, not the basis of release performance estimates. Record both flavors' install sizes and correctness; repeat performance when access backend changes decoding/I/O materially.

## Energy, battery and heat

Use Perfetto/System Trace and Android Studio Power Profiler on supported physical Pixels. ODPM measures device/subsystem energy, **not app-only attribution**. On other devices use available coulomb/charge counters and voltage sampling, plus controlled matched idle runs; declare unsupported counters. BatteryStats can help attribute activity, but a battery-percent screenshot is not precise energy measurement. An external power monitor is an optional developer reference, not an owner requirement. [S32–S34]

For unplugged tests: ambient 23–27°C, matched starting state of charge (for example 50–80%), fixed brightness and refresh rate, identical radio state, no USB power, same active apps and screen-on duration, and no concurrent backup/update. Randomize A/B order, cool between runs, record thermal status/frequency throttling, and subtract a matched same-duration idle trace. Report **gross** and **incremental over idle** mWh, charge used, mWh per 1,000 analyzed images, and capacity-normalized percentage with sensor uncertainty. Do not publish estimated app mWh as direct hardware measurements.

Test charging separately for thermal behavior; a charging run cannot establish unplugged battery drain. Integrate sampled current/voltage or use supported energy counters with documented units and resolution. Rated battery capacity is a normalization reference, not a substitute for battery-health measurement. Use medians and paired confidence intervals across runs.

Proposed runtime pause policy: battery ≤10% and not charging, Android thermal status at least MODERATE, memory trim indicating pressure, or free space <500 MB. Verify response within one bounded in-flight item; if a native call cannot be interrupted, stop scheduling immediately and report the maximum observed overrun. No background continuation or auto-restart after reboot/process death.

## Proposed performance gates

These are reviewable targets, not observed results. G2 establishes the actual baseline. A miss is a design/coverage decision; do not silently change targets or conceal cap-limited work.

| Metric | Owner reference target | Entry-floor target / shared hard rule |
|---|---|---|
| Existing normal scan | Preserve SCAN-4 ≤60 s; unchanged scan SCAN-5 ≤10 s, excluding expensive hashing/optional gallery | No coupling of basic scan completion to gallery inference |
| First analyzed progress | Visible feedback ≤1 s; first 100 eligible images analyzed ≤15 s | ≤30 s for first 100; “no group found yet” is valid |
| Cold V2 10k analysis | ≤15 min total with actual supported/skip counts disclosed | ≤30 min; unsupported cases may skip, not vanish from accounting |
| No-change gallery revisit | ≤10 s metadata validation, **zero image decode/inference** for truly unchanged authorized entries | Same behavior; disk cache/permission checks still required |
| +100 images | ≤30 s additional gallery work | ≤60 s |
| Total process memory | **<200 MB** peak total process PSS during scan+gallery scenario, including native/model/bitmap allocations | Same cap; report RSS/native/managed peaks too; no OOM/leak/LMK cycle |
| Repeated-run stability | No increasing live allocation/FD/model-session count after 10 start/cancel/reopen cycles | Equivalent on entry device; bounded pools and descriptor closure |
| App data | **≤50 MB total** on owner-scale V2, including DB/WAL/settings/disk cache; gallery cache target ≤16 MB within this | Read-only/eviction fallback when budget is unavailable; no deletion of safety records |
| Model/runtime package delta | Proposed ≤15 MB compressed APK increase; installed/native/model copies separately reported | If exceeded, do not silently raise budget or require model download |
| UI | p95 frame time ≤32 ms during 2k-tile scrolling and analysis; no frozen frame >700 ms attributable to gallery | Report frame distribution/refresh rate; model work stays off main thread |
| Stop responsiveness | UI acknowledges stop ≤1 s; no new item scheduled; bounded work reaches idle ≤5 s in accepted formats | Show measured worst case; formats violating this need reduced support or an explicit revised decision |
| Incremental unplugged energy per V2 run | ≤3% of nominal full battery energy over matched idle | ≤5% over idle; report mWh and uncertainty. No gate claim if counters are too coarse |
| Thermal/battery policy | Pause at policy trigger and prevent sustained workload at MODERATE+ | No thermal shutdown/ANR/crash; recovery explicit and foreground |
| Network during gallery | **Zero requests/bytes/connections** attributable to gallery, including first launch and model load | Online and offline, no backup running; existing backup networking tested separately |

The total-PSS cap operationalizes the existing memory principle conservatively. If the current non-gallery baseline itself exceeds it, record that as an unresolved baseline discrepancy before admitting ML; do not treat it as free extra memory. Migration peak/WAL and installed package are separate measurements.

50k stress need not persist every vector. It must remain bounded and responsive, state which range/items were processed, and avoid quadratic candidate work. Report both total elapsed time and coverage. A 10k success cannot be extrapolated linearly to 50k without this run.

## Quality and usefulness gates

Evaluate candidate recall **before** reranking, pair precision/recall, group purity, false merges, false splits, coverage/abstention, cap-hit rate and keeper preference agreement. Compute metrics on all within-group pairs, not just anchor edges. Deduplicate mirrored pairs and stratify by camera/screenshots/messenger/generated/rendered, codec, low light and difficult families.

Proposed release floor is AC-G4: ≥99% point pair precision, 95% cluster-bootstrap lower bound ≥98%, ≥80% near-duplicate relationship recall on the labelled reference subset, and no adequately sampled subgroup below 97% precision. Group/event-level bootstrap is required because pair observations in a burst are correlated; Wilson/binomial intervals are descriptive only for independently sampled pairs. Report numbers of distinct families and false merges, not just percentage scores. Increase the holdout if uncertainty prevents a decision.

Every false positive that could hide a unique expression, person, receipt digit or document version is reviewed by severity. “No data loss in the sample” is not evidence that model decisions are safe to automate. Any observed invariant violation blocks release regardless of average accuracy.

For the model, require ≥10 percentage-point recall gain over A at the **same** precision floor, no material hard-negative regression and all resource gates. If quantized cache vectors are used, repeat near-threshold/ranking evaluation against float output and recalibrate; no unmeasured “almost identical” assertion.

Run a counterbalanced user study of approximately 8–12 consenting users plus owner acceptance, on matched tasks with several hundred images per participant. Compare current manual/month or swipe review, A, and the candidate learned experience; use equivalent disjoint events to reduce learning effects. Report participant counts, task mix, median completion time, paired 95% interval, unintended choice/correction rate, skipped items and subjective usefulness. The 20% median time reduction gate must show improvement in the confidence interval; a tiny owner-only pilot is formative, not a population claim. Quality-hint preference agreement is separate; a weak hint can be removed while grouping remains.

## Automated test layers

| Layer | Required tests | Existing reuse |
|---|---|---|
| Pure JVM contracts | dHash/pHash golden values; finite/zero-vector math; deterministic bounded candidate retrieval; complete-link grouping; disjoint actionable groups; stop/budget/epoch policy | JUnit 5/Kotest; existing fixture and pure-JVM conventions |
| Property/counterexample | Same vector for every image; low-detail collisions; A~B~C chains; random group selections/intersections; source edits/permission loss at every checkpoint; latest-run wins | FakeStorageGateway fault injection and existing protected-plan/no-loss testing style |
| Feature adapter | Canonical orientation/colour/alpha/resize; exact model digest/shape/normalization; known image vectors; float/quantized parity; CPU/delegate fallback; fd/native session closure | New real-image fixtures, not OwnerDevice virtual bytes |
| Database/migration | Declared 7→next migration preserving all existing rows; incomplete feature records invisible; corrupt/unknown versions ignored; scoped cache clear; WAL/ENOSPC quotas | MigrationTest, exported schema golden files, ScanIndex tests |
| Safety/action | No category/proof promotion; MOVE never directly disposable; current-session confirmation; live keep/identity checks; keep-one guard; dry run; actual trash and restore | SafetyGuardTest, ActionExecutorTest, ProtectedPlanPropertyTest, ReviewActionsTest, PermanentKindsTest |
| Fault/no-loss | Kill after every new state write and action handoff, random ENOSPC/revocation/disconnect; original-or-recoverable-trash invariant | Existing permanent/trash kill-point suites; extend without relaxing their assertions |
| UI/lifecycle | No preselection; exact editable summary; Keep/Skip/Undo/resume; stale decision drop; cancel; no late image/run; rotation/background/foreground; model absent | SwipeH2bTest, SwipeSessionStoreTest, ViewModelStabilityTest, TestModeTest |
| Accessibility/visual | en/he × light/dark, TalkBack, 200% font, RTL mixed names, swapped physical direction, contrast, reduced motion | Existing screenshot, terminology, string parity and AccessibilityChecksTest |
| Device integration | Scoped MediaStore/SAF access, permission reduction, Android consent decline/partial batches, unsupported codec, real trash/restore roundtrip | Existing MediaStore/SAF contract/device tests; both flavors |
| Policy/build | No Android imports in engine, no broad/write/network port, exact runtime confinement/bytecode checks, no telemetry/GMS, model digests, licenses, 16 KiB, R8/reproducibility | Konsist/build-logic checks, dependency verification, existing CI |

Meaningful mutation checks should show that removing category isolation, current-session validation or keep-one recheck causes their counterexample tests to fail. Test files should not merely restate a formula or mirror one implementation method.

## Results artifact contract

For each admitted variant produce `docs/device-reports/gallery-<device>-<date>.md` and aggregate CSV/JSON with: baseline/app/model/runtime digests; device/OS; corpus ID/split; supported/analyzed/skipped counts; candidate/cap counts; latency distribution and stage/total timings; PSS/RSS/native/managed/WAL/package sizes; gross/idle-adjusted energy and sensor quality; thermal states; quality metrics/confidence intervals; offline/network observations; data-safety outcomes; known failures; and pass/fail for every gate. Keep traces private until checked for paths/content; publish only redacted aggregates with explicit user initiation.

Do not populate the template with invented results. The research project delivered here supplies the protocol and the inspected model metadata only.

## Selective AI and incremental-efficiency experiment

The accepted architecture compares A against **A + uncertainty-routed local inference**, not a whole-library model run. Freeze routing states/reasons, feature/threshold versions and run-wide budgets with the evaluation configuration. Measure deterministic resolved/no-match/unsupported counts, ambiguous groups and unique participants, cache hits, new inference count, model load count, learned-stage wall time including initialization, budget deferrals and overall quality/coverage. Score the whole eligible benchmark scope; excluding unprocessed/triage-missed pairs from recall is forbidden.

Use the initial provisional cap `min(256, max(2, ceil(0.05 * eligibleImageCount)))` new unique image-version inferences and 30 seconds learned-stage wall time per user-started run. Both apply globally across groups; initialization counts and paused idle time does not. Record cap-hit frequency and any bounded in-flight overrun. Do not restart automatically or relabel every source ambiguous. Increase no cap without measured review and an explicit spec change; inherited hard safety/resource limits never relax implicitly.

Required controls: all-resolved workload gives zero runtime initialization/inference; no-match/unsupported-only workload never escalates; compatible cached ambiguous participants give zero new inference; shared participants are computed once; changed metadata/content/permission/model/preprocessing invalidates only affected features/groups; many tiny groups cannot multiply the run budget; time-cap during initialization schedules no images; cancellation/version change rejects stale output; restart/resume retains the completed records and no destructive consent.

Use an ablation on the same licensed holdout: deterministic baseline; uncertainty router with no inference (reveals routing recall); selective local hybrid under the run cap; optionally an **offline developer-only** uncapped oracle on the consented corpus to quantify missed benefits. The oracle is an evaluation control, never the shipping architecture, and must be labelled separately. Report both quality gain per inferred image and total review-time/energy impact; more inference is not itself success.

Reasoned keeper evaluation must test factual explanation correctness and abstention as well as reviewer preference. Equal/contradictory technical facts must not fabricate a winning shot; recommendation markers must not change selections. Shipping deterministic explainable advice is valid if the model adds no measurable value.

JPEG preprocessing preparation: an instrumented in-memory four-color fixture exercises all eight EXIF orientations in Android ImageDecoder. Its results are correctness evidence only; they do not establish JPEG coverage, photographic accuracy, resource use or physical-device acceptance.
