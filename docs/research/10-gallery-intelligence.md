# Gallery intelligence: research and recommendation

> **Owner update, 2026-11-08:** scope/policy adopted in [gallery-decisions.md](../spec/gallery-decisions.md). Strict zero extra payment and on-device processing; deterministic first, optional AI only on a budgeted ambiguous subset; reasoned keeper advice. Research observations remain pinned/historical; no application implementation or new benchmark result is implied.

**Status: Research evidence; owner scope/policy adopted 2026-11-08.** Research date: 8 November 2026. Repository baseline: `d8b596dbe6bb96184427d0aabe4f0eb9065772ae`. Source identifiers resolve in `10-gallery-sources.md`.

## Recommendation

Build a local, read-only suggestion layer around KeepClear's existing inventory, review, safety and backup capabilities. Start with perceptual hashes and modest, explainable quality measurements. **Do not commit to an AI runtime, cloud service, subscription connection, automatic deletion or physical album reorganization yet.**

If evaluation demonstrates sufficient benefit, Google's **bundled MobileNetV3-small image embedder** is the first model candidate. The examined modern LiteRT 2.x packages pull in a forbidden Play-delivery dependency, so do not choose the latest CompiledModel package by default. A preliminary no-Play candidate is the documented LiteRT 1.4.2 CPU/Interpreter line, subject to its maintenance/native review; ONNX Runtime is an alternative with an explicitly validated model conversion or different vetted encoder. MediaPipe's examined packages also require telemetry/dependency review. Retain the simpler implementation whenever any admission gate fails.

This recommendation implements the intent of existing `REV-8` without converting uncertain model predictions into SAFE cleanup. It preserves P-1 phone-only use, P-2 simple defaults, P-3 explicit freeing after verified backup, and SAFETY-1…10. [R05, R06]

## Product problem and measurable value

The useful problem is reducing the time people spend reviewing repetitive screenshots and related images while protecting memories. A gallery-wide generative assistant is not required to establish that value.

Three concepts must remain distinct:

| Concept | Evidence | Allowed consequence |
|---|---|---|
| Byte-identical copy | Full SHA-256 and valid retained witness under current rules | Existing duplicate handling, unchanged |
| Looks similar / likely burst | Perceptual hash and possibly learned features | Comparison suggestion only |
| Might be worth keeping | Favourite, explicit keep, source, quality hints and personal preference | Keep/dismiss or backup handoff; no inferred deletion permission |

The initial experience offers “Find similar photos” / “למצוא תמונות דומות”, shows the scope and progress, and opens small comparison groups. It explains observable reasons such as “Taken close together” or “One image is sharper.” It offers no “AI says delete” action and no confidence percentage presented as truth. Camera groups offer keep/dismiss and the existing backup flow. Existing REVIEW items retain their explicit choice → editable confirmation → trash path.

Success is measured by trustworthy groups, useful review-time reduction, no safety regressions, and bounded resource use. Numbers of model calls, labels or generated captions are not success metrics.

## Runtime and service research

| Option | Price/license | Android/offline fit | Privacy and limitations | Conclusion |
|---|---|---|---|---|
| Kotlin dHash/pHash + BK-tree | No inference fee; implement within the GPL project, review any reused algorithm code's license | Uses platform decoding at minSdk 30; clean-install offline; no native ML runtime | No network needed. Misses crops/edits and can collide on low-detail/text images | Required baseline; may be enough for v1 |
| ML Kit image labeling | No API fee; Google ML Kit/API terms, not a blanket Apache license for SDK/models | Bundled and Play-delivered forms differ. Label API docs require API 23; bundled model works immediately, unbundled may download first | Images/results stay local, but terms document usage/performance metrics and possible server contact. Play-delivered form uses GMS. Broad labels do not solve near-duplicate verification | Exclude under current PRIV-1/privacy rules; free is insufficient |
| MediaPipe Image Embedder | Apache-2.0 runtime; check model license independently | Android setup API 24+, Kotlin/Java API, bundled task/model, image mode and quantized output | Examined current and 0.10.35 core POMs include Google data-transport/CCT components; current vision also includes LiteRT-LM | Not admitted as-is; a source-audited alternative build would need proof. Never use dynamic `latest.release` |
| Standalone LiteRT | Apache-2.0; no hosted inference fee | Docs list 2.3.2/API 23/CompiledModel and the supported 1.4.2/API 21 Interpreter line | Examined 2.x dependency graphs include Play AI delivery; 1.4.2 API POM has no further dependencies, but native/API security/compatibility remain to verify | 2.x as inspected fails current policy; 1.4.2 CPU is a conditional experiment, not a selected dependency |
| ONNX Runtime Android | MIT runtime; models separately licensed; no hosted fee | Java/Kotlin through Android package; CPU, XNNPACK, optional device-specific providers; reduced builds possible | Works locally. Export/quantization/operator parity, native binary size and maintenance are costs; acceleration can partition poorly | Feasible alternative, not an additional runtime to ship alongside LiteRT by default |
| Gemini Nano / AICore | No per-call server fee for ML Kit GenAI; separate Google terms | Restricted supported-device/features list, feature download/state checks; OnePlus 10T is not on the retrieved support list | On-device generation exists, but availability, setup/downloads, terms and coverage differ from a bundled portable encoder | Not a universal solution for this app's floor or owner's phone |
| Gemini Developer API | Hosted proprietary service; quota-limited free tier and paid tokens | Android HTTPS client/backend integration possible, no offline inference | Unpaid terms generally permit improvement/human review and prohibit submitting personal/sensitive information; regional exception applies. Paid terms differ, retain safety logs | Free tier is unsuitable as the family-gallery default; cloud remains deferred |
| Claude API | Hosted proprietary service; token billing | REST integration possible; no local Claude weights established here | No training without permission under API retention documentation; state/retention depends on feature, model and account arrangement | Research comparison only; no current product need established |
| OpenAI API | Hosted proprietary service; token billing | REST integration possible; image-input models exist; no offline hosted inference | API training off by default; standard abuse logs can retain content for up to 30 days; storage controls and exceptions matter | Research comparison only; no current product need established |

Evidence: [S01–S10, S16–S27]. Runtime source licenses do not automatically license weights, datasets, labels, conversion tools, delegates, or hosted services. GPL distribution review must include those artifacts and their notices; do not relabel a proprietary SDK as open source because its sample code is Apache licensed.

Apache's compatibility guidance permits Apache-2.0 material in GPLv3 projects, with applicable notices preserved. That does not establish F-Droid acceptance: its current inclusion policy separately reviews freely licensed dependencies, trusted binary sources, toolchains and reproducibility. Validate the exact model/native artifacts with the distribution recipe; keep A available if those artifacts cannot meet FOSS distribution requirements. No store approval or F-Droid inclusion is assumed. [S35, S36]

### A concrete, inspected model candidate

The official MediaPipe model linked by Google's Image Embedder guide was downloaded **for metadata inspection only**, not executed. Using that model does not require adopting the MediaPipe task framework:

| Property | Observation |
|---|---|
| Artifact | `mobilenet_v3_small.tflite`, float32 version 1 |
| Origin | `https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite` |
| File size | **4,117,670 bytes** |
| SHA-256 | `bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40` |
| File signature | `TFL3` |
| Input tensor | Float32 `[1, 224, 224, 3]` |
| Output tensor | Float32 `[1, 1024]` |
| Embedded metadata | Describes an image feature vector; identifies Apache License 2.0 and MediaPipe |
| Unproven | Android inference compatibility with the selected runtime, exact app preprocessing parity, photographic usefulness, quantized-output loss, energy, peak native memory and end-to-end scan time |

This resolves weight provenance more concretely than choosing an unspecified “MobileNet.” Record the same hash, upstream license/NOTICE and preprocessing contract if this artifact is later admitted. Google's published Pixel 6 task figures are 3.94 ms CPU and 7.83 ms GPU for small, versus 9.75/9.08 ms for large. These are vendor task benchmarks, **not KeepClear file-scanning results**. They motivate testing CPU before assuming GPU is better; they do not predict a 10,000-photo completion time. [S03, S05, S11]

### Dependency artifacts change the runtime decision

Official Maven metadata/POMs were fetched directly, not inferred from project license labels:

| Examined artifact | Observed dependency finding | Effect under current repository rules |
|---|---|---|
| LiteRT 2.3.2 and API 2.3.2 | API depends on `com.google.android.play:ai-delivery:0.1.1-alpha01`; inspected 2.3.0/2.2.0 and 2.1.0 graphs also include it | `com.google.android.play` is explicitly denied. “Standalone” does not mean dependency-free. Do not blindly exclude a potentially required artifact |
| LiteRT 1.4.2 + API 1.4.2 | Runtime depends on API 1.4.2; that API POM declares no external dependencies | Plausible no-Play CPU candidate, still needs exact allowlisting, native review and acceptance of the older documented compatibility API |
| MediaPipe tasks-vision 1.2.2 | Current Maven release; includes `litertlm-android:0.18.0` and tasks-core | Much broader than a tiny image task; does not pass automatic admission |
| MediaPipe tasks-core 1.2.2 and 0.10.35 | Include `transport-api`, `transport-backend-cct` and `transport-runtime`, plus Flogger/protobuf/Guava | Data-transport dependencies require privacy/telemetry/source review. POM presence alone is not proof that photos are transmitted, but no-tracking cannot be assumed |
| ONNX Runtime Android 1.31.1 | Current Maven release; MIT; POM declares no external dependencies | Clean preliminary graph, not proof its bundled native code meets all size/telemetry/ABI requirements |

These findings strengthen the no-model-first recommendation. An older MediaPipe version is not assumed safe just because it is smaller. B and D below are feasible local experiments; C is a technically possible adapter architecture whose examined stock artifacts are **not eligible as-is**. Exact source/native audits remain incomplete for every runtime. [S37]

### Other open image encoders

| Candidate | License evidence | Relevance and caveats |
|---|---|---|
| Google MobileNetV3 feature-vector family | Google model listing identifies Apache-2.0; exact task artifact metadata checked above | Small first candidate. ImageNet features are not trained specifically to decide which family photo can be discarded |
| timm MobileNetV3-small-075 | Publisher model card explicitly Apache-2.0 and supports image embeddings | Alternate weights/export path; not numerically interchangeable with Google's task model |
| OpenAI CLIP | Official repository MIT license; retain model/source provenance | Image/text retrieval and zero-shot tags; greater model/conversion burden than the small baseline. No need to call the OpenAI API to run licensed CLIP locally |
| Google SigLIP2 base | Publisher card Apache-2.0; image-text retrieval/vision tower | Useful semantic challenger. Test image tower separately and precomputed fixed-label vectors if needed. Full checkpoint size must not be mistaken for image-only mobile size |
| DINOv2 | Official project Apache-2.0; recheck exact selected checkpoint | Visual similarity challenger without inherent text-search capability; not a drop-in tiny Android model |
| Apple MobileCLIP-S0 | Software MIT, **weights under separate Apple research model license** | Inspected weight terms limit use to research and exclude product development/commercial exploitation. Do not ship the examined checkpoint in KeepClear merely because it is downloadable |

Evidence: [S11–S15]. No learned model here was validated on KeepClear images. Semantic similarity also creates a specific risk: two different children, receipts, outfits or landmarks can be semantically close without being redundant. High cosine similarity is neither byte equality nor a calibrated probability of interchangeability.

### Models are not tasks

Embeddings help retrieve related pictures. They do not automatically provide trustworthy best-shot ranking, OCR, screenshot classification, AI-generated-image detection or family-member identity. Each requires separate evidence. For this proposal:

- Best-shot hints use observable within-group signals (usable resolution, sharpness and exposure), with abstention. Artistic and emotional value remains the user's choice.
- Screenshot/AI-image provenance keeps the current metadata/rule explanation. Do not claim forensic detection of generated images.
- Face recognition, biometric templates, text extraction, location inference and free-form caption generation are out of scope.
- Semantic collections/search are future experiments. The initial organizer uses virtual groups, never filesystem moves or EXIF rewrites.

## Subscriptions, quotas and costs

The common statement “chat subscriptions never include API credits” is too broad as of this research date. Official documents now distinguish these cases:

| Product | What was verified | Planning implication |
|---|---|---|
| ChatGPT Plus | Excluded from the current API-credit benefit | Do not budget gallery API usage as included with the user's Plus subscription |
| ChatGPT Pro/Business | Eligible plans can claim limited API credits into a linked API organization; eligibility, limits and expiry apply | These are metered credits, not unlimited mobile inference or an entitlement for every KeepClear user |
| Claude Pro | Does not include API usage; excluded from the documented credit benefit | A Pro chat account is not the app's API backend |
| Claude Max/Ultra/Team | Current docs describe claimable limited API credits, account linking and expiry | Useful for eligible development experiments; do not assume the owner's actual tier or that this funds a distributed app |
| Google AI subscription | Official AI-plan documentation separates AI Studio UI benefits from external Gemini API billing | Check API project's tier and billing; a consumer plan is not an offline deployment license |
| Gemini free API | Rate limits depend on project/model/account and are not guaranteed; check active limits in AI Studio | No fixed “free 10,000 photos/day” promise |

Evidence: [S18, S19, S22, S23, S24]. No account eligibility, quota, credit activation or billing configuration was inspected. Consumer app session tokens and coding-assistant subscriptions are not proposed as an undocumented API transport. ChatGPT/Claude can assist development and evaluate explicitly supplied samples; that is different from integrating them into KeepClear.

### Published API price comparison

USD, standard uncached input/output per million tokens, as retrieved 8 November 2026. These are **illustrative eligible models, not selected dependencies or claims of equivalent quality**. Image-tokenization, reasoning, output length, batching, residency, taxes, retries and provider changes affect bills.

| Example model | Input / 1M | Output / 1M | Hypothetical 10,000-image workload* |
|---|---:|---:|---:|
| Gemini 3.1 Flash-Lite | $0.25 | $1.50 | $5.50 |
| Claude Haiku 5.5, each prompt ≤100k tokens | $0.10 | $0.50 | $2.00 |
| Claude Sonnet 5.5 | $2.00 | $10.00 | $40.00 |
| OpenAI GPT-6 Luna | $0.10 | $0.50 | $2.00 |
| On-device baseline / licensed bundled encoder | $0 service inference fee | $0 | $0 service inference fee; hardware energy/storage and development remain costs |

*Arithmetic scenario only: 1,000 billable input tokens including image and prompt plus 200 billable output tokens per image; 10M input and 2M output tokens total. It is **not** a measured or normalized vision-token estimate. Compute actual input via provider counters and constrain reasoning/output before budgeting. Formula: `N × ((Timage + Tprompt) × Pin + Toutput × Pout) / 1,000,000`. At 10k thumbnails of 100 kB each, payload alone is about 1 GB before encoding/protocol overhead; actual bytes must be measured. [S16, S20, S25]

Paid Gemini does not use prompts/responses for product improvement under the fetched terms, but keeps limited safety logs; a paid request is not a no-retention promise. OpenAI's `store=false` does not eliminate standard abuse-log retention or all image-safety exceptions. Claude's current documentation distinguishes default content handling, stateful features and covered models requiring retention; verify the exact model/endpoint and organization settings rather than applying one retention number to everything. [S17, S21, S26]

### What would justify cloud later?

Only a separately requested task with demonstrable value beyond local methods: for example, optional captions for a handful of deliberately selected images. Admission would require an owner-approved amendment to networking/privacy ADRs, task-specific benefit measurement, preview of exactly what leaves the phone, provider/data-policy disclosure, explicit per-batch consent, cost ceiling, cancellation, deletion/retention controls, no background upload, no automatic provider fallback, and no access to file-action capabilities. A production owner API key must not be embedded in the APK; a secure service or supported scoped credential design introduces additional infrastructure and trust. [S28]

There is no evidence in this audit that a cloud caption feature is necessary to solve repetitive-photo review. Therefore no cloud module, credential screen, provider abstraction, telemetry or backend is proposed for implementation.

## Architecture alternatives

All alternatives use the existing action choke point and preserve classification. “Feasible” means technically implementable, not approved under current product constraints.

| Architecture | Pipeline | Strengths | Costs / failure modes | Decision |
|---|---|---|---|---|
| A — deterministic local | Canonical decode → dHash/pHash → BK-tree/candidate buckets → conservative groups → quality hints | Fully offline, tiny dependency surface, no model provenance problem, direct fit to REV-8 | Limited crop/semantic recall; collisions on screenshots/flat scenes; BK-tree worst-case degeneration | **Recommended first delivery and control condition** |
| B — local LiteRT hybrid | A plus vetted standalone CPU runtime, e.g. evaluated 1.4.2 line, and bundled MobileNetV3-small → bounded retrieval/reranking | Free local inference; concrete licensed 4.12 MB model; preliminary no-Play POM graph for 1.4.2 | Compatibility API maintenance, native packaging, preprocessing, quantization and memory; modern examined 2.x is blocked | Conditional model experiment; no runtime selected yet |
| C — MediaPipe hybrid | A plus Image Embedder task/model and built-in similarity/quantized output in a policy-conforming build | Less task/preprocessing glue, useful reference architecture | Stock examined packages include data transport; current version adds LiteRT-LM. Exclusion/custom build needs source proof | **Not eligible as-is**; do not ship to simplify glue |
| D — ONNX semantic hybrid | A plus ONNX Runtime with a vetted CLIP/SigLIP2 image encoder | Broader semantic grouping, optional fixed-label retrieval, flexible export ecosystem | Higher model/storage/energy burden; quantization/export parity; semantic false matches | Feasible later if B cannot meet an established task |
| E — local + selected cloud | A/B for bulk work, explicit selected-image cloud request for a narrow extra task | Richer optional descriptions without sending all images | New network permission purpose, service terms, spending, credentials, connectivity and processor trust | **Deferred; fails current networking/privacy admission gate** |

### Explicit decision criteria

Hard gates precede numerical scoring: preserve safety invariants; no automatic media transmission or tracking; GPL-compatible redistributable artifacts; functional baseline on API 30 without Google Play Services; clean-install offline core; no bypass of freeze/owner review. ML Kit and cloud-as-default fail current gates. A runtime that fails memory/native compatibility also fails, regardless of its score.

Scores below are planning hypotheses on a 1–5 scale, not empirical quality rankings. Weighted total is `Σ(weight × score / 5)`. B assumes a fully admitted no-Play runtime; C describes a hypothetical policy-conforming build and its score does **not** override the stock-artifact rejection. Re-score using benchmark evidence before choosing a learned backend.

| Criterion | Weight | A | B | C | D |
|---|---:|---:|---:|---:|---:|
| Safety separation | 25 | 5 | 5 | 5 | 5 |
| Privacy/no network dependency | 20 | 5 | 5 | 5 | 5 |
| Offline/device coverage | 15 | 5 | 5 | 5 | 5 |
| Expected useful grouping | 15 | 3 | 4 | 4 | 5 |
| Memory/energy/installed footprint | 10 | 5 | 4 | 3 | 2 |
| License/provenance fit | 10 | 5 | 5 | 5 | 5 |
| Maintenance/integration simplicity | 5 | 5 | 3 | 4 | 2 |
| **Weighted total / 100** | **100** | **94** | **93** | **92** | **91** |

A wins the current decision because its lower complexity is certain while the models' extra usefulness is unmeasured. B or D can be a later experiment after dependency admission, not a foregone production integration. For any learned architecture to displace A, demand at least a **10 percentage-point held-out recall gain at the same precision floor**, plus **20% median review-time reduction** in the target workflow, with all resource/safety gates passing. If the baseline is already sufficient, stop. If neither method meets quality gates, release neither and narrow the task.

Sensitivity: raising the utility weight and lowering the footprint weight can favour D; that does not remove the hard resource and safety gates. Devices without acceleration must still work. Do not require HNSW/vector databases, a text encoder, on-device LLM or cloud stack until scale/task measurements justify them.

## Outstanding evidence

The downloaded artifact's hash, size, tensor shapes and license metadata were inspected. Inference and on-device quality were not. Exact runtime/transitive license inventory, 16 KiB native support, APK delta, complete preprocessing equivalence, quantization loss and energy remain admission tests. Real personal-photo labels and owner preference cannot be inferred from synthetic storage fixtures.

The owner subsequently accepted the product scope and both ADR policies (0014/0015), as recorded in gallery-decisions.md. The implementation annexes now include selective routing, zero-cost and reasoned-keeper contracts. No application implementation, runtime choice or benchmark result is claimed.

## Adopted product policy after owner review

Deterministic metadata/exact-hash/visual-hash grouping and quality facts are the normal path. Only version-valid ambiguous pair/group participants may request admitted local learned features; compatible features are reused, runtime loading is lazy and a global per-run count/time budget stops escalation. Whole-library embedding and cloud/subscription integrations are outside the approved implementation. Paid-provider prices above are historical research comparisons, not a selected budget or provider entitlement. The complete handoff is ../prompts/gallery-builder.md; current requirements and budgets are in the gallery annexes.
