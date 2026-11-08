# Repository audit

> **Owner update, 2026-11-08:** scope/policy adopted in [gallery-decisions.md](../spec/gallery-decisions.md). Strict zero extra payment and on-device processing; deterministic first, optional AI only on a budgeted ambiguous subset; reasoned keeper advice. Research observations remain pinned/historical; no application implementation or new benchmark result is implied.

**Status: Historical research record; scope subsequently adopted 2026-11-08.** Snapshot: `d8b596dbe6bb96184427d0aabe4f0eb9065772ae`, default branch `main`, latest commit 4 November 2026, retrieved 8 November 2026.

## Evidence and limits

The audit enumerated the complete recursive repository tree, retrieved a 157-file working snapshot spanning the specs, all 13 ADRs, prior research, build policies, scanner, classifier, enrichment, safety, database, and current gallery/review code, and separately inspected relevant app tests and MediaStore/backup integration sources. Focused source review was used to distinguish implementation from plans. This is not a claim that every line of every repository file was reviewed.

GitHub reported no open PRs. Its all-state issues response contained issues/PRs #1–#64; the only open non-PR issue in that response was [#36](https://github.com/tomerar/keepCleanr/issues/36), concerning an intermittent SMB listing test. The public GitHub milestones API was separately checked for all states and returned an empty collection with no next page: milestone tracking currently lives in repository documentation/PRs rather than hosted milestone objects. [R01–R04, R17]

Head CI [run 41823352718](https://github.com/tomerar/keepCleanr/actions/runs/41823352718) was completed successfully, with successful build/tests/lint/policies, API 30 and API 36 launch jobs, reproducible fossRelease build, and test-APK jobs. These are observed remote results, not newly executed tests. No phone performance, battery, accuracy or owner acceptance results were produced here. [R04]

## What exists and what can be reused

| Area | Implementation observed | Reuse and boundary |
|---|---|---|
| Product identity | Native Kotlin/Compose storage cleaner; application ID `io.github.tomerar.keepclear`, test suffix `.test`; GPL-3.0-or-later | Keep the storage-cleaner purpose and Paper & Ink UI; no new gallery app identity |
| Modules | `core:*`, pure-JVM `engine:*`, `backup:target-*`, `backup:net`, Android app, architecture tests and fixtures | Add an isolated suggestion engine; avoid introducing Android or ML imports into existing pure-JVM engines |
| Scan | `Scanner`, `ScanItem`, `MediaStoreSource`, generations, file/MediaStore/SAF references; backup-root exclusions | Reuse authorized inventory and exclusion policy; do not start a second unrestricted traversal |
| Classification | Deterministic `Classifier`; PROTECTED and user-kept first; rules v3; SAFE/REVIEW/MOVE; favourites/recent keep signals | Suggestions are an overlay. Never feed a similarity score into SAFE rules |
| Exact duplicates | `FactEnricher` narrows WhatsApp Sent candidates by size, head/tail prehash, then full SHA-256 against a retained non-messenger copy | Reuse cryptographic proof semantics and witness validation; this is not a general gallery-wide duplicate service |
| Current enrichment scope | `classifyRun` passes only `KPath.Local` items to `FactEnricher` | A MediaStore image embedder needs its own read adapter; existing Sent duplicate enrichment is not universal across access modes |
| Hash cache | Room has `hash_cache`; app currently supplies `MemoryHashCache` to enrichment | Reuse invalidation concepts, not an assumption that full hashes already persist across restarts |
| Action safety | `ActionPlan`, UI-created `Confirmation`, `SafetyGuard`, `ActionExecutor`, session binding, live kept checks, source/witness validation | The only route from reviewed choices to an action; gallery engine never gets a write gateway |
| Permanent deletion | `PermanentKinds` decides narrow regenerable/bin cases and verified-backup original freeing at the choke point | Similarity and quality hints must never satisfy these exceptions |
| Recovery | Folder trash, Android MediaStore consent/trash, SAF granted-folder trash, restore, write-ahead records and signed sidecars | Reuse existing recovery and result accounting; do not create an AI trash system |
| Backup | Copy/verify, second-check records, free-time target rehash, strict original rehash, `BackupProof`; restore | Use existing backup handoff for camera memories; an embedding is never a backup proof |
| Review UX | Grid/month grouping, editable confirmation, swipe Keep/Delete/Skip/Undo, persisted resumable sessions, stale-decision pruning | Reuse visual components and interaction contracts; new group identity needs its own safe session mapping |
| Thumbnails | Platform `loadThumbnail`/`ThumbnailUtils`, 32 MiB memory LRU, cancellation, no disk thumbnail cache | Reuse UI loader. ML needs a reproducible orientation/aspect/colour preprocessing path rather than treating arbitrary UI thumbnails as canonical inputs |
| Database | Room schema **7**, declared migrations 1→7, exported schemas; no destructive fallback | Additive declared migration and migration tests; preserve trash and backup records if gallery cache is cleared |
| UI and language | Stable Material 3, design-system components, three tabs, English/Hebrew, bidi, contrast, TalkBack and reduced motion | Add entry points inside existing flows; use equal-weight decisions and localized explanations |
| App graph | Manual composition in `KeepClearApplication` | Extend the existing graph when needed; do not add Hilt merely because an older stack description names it |
| Policy enforcement | Exact/prefix dependency allowlists, manifest provenance, bytecode/source network confinement, destructive-API restrictions, string/copy tests | Any admitted runtime needs explicit exact-module review, checksums, license notices, native compatibility and confinement |

Implementation anchors: [R05–R14].

## What is absent, incomplete or deliberately deferred

| Capability | Evidence-based status |
|---|---|
| Similar-photo detection | `REV-8` is **Later**; tasks name dHash/pHash and BK-tree. No implemented gallery embedding/similarity pipeline was found in the enumerated source layout |
| Learned image embeddings / vector index | No ML runtime/model dependency, embedding table or visual similarity index observed |
| Best-shot model | No implemented learned or calibrated photographic quality ranking observed |
| General semantic categories/search | Current screenshot and AI-image buckets rely on path/name rules. They do not establish image understanding or reliable AI-image detection |
| Vision benchmark corpus | OwnerDevice generates deterministic filesystem/byte fixtures, not a labelled natural-photo corpus suitable for accuracy or realistic image-decoding throughput |
| Accuracy/battery evidence | No gallery AI accuracy or hardware battery benchmark results observed |
| Real-phone validation | Many D*/H* checks in progress.md still wait for the owner. A green emulator CI does not close them |
| Google Drive | Owner postponed it; do not revive it as part of gallery AI |
| Dropbox | Engine implemented/tested; app sign-in and wiring wait for W8 |
| Release/distribution | M18a engineering exists; release signing/account work, device checks and distribution steps still require owner completion |
| SMB issue #36 | Remains open. Diagnostics exist; neither its root cause nor universal resolution was established by this audit |

## Milestone reconciliation

M0–M10, review PRs A–C, M11–M16a, M17a, M17b Nextcloud, M17c Dropbox engine and M18a engineering have merged evidence in progress.md and the PR collection. Later safety/stability/design work D/E/F/G and H1–H6 is also merged through PRs #42–#62; #63 concerns reproducibility and #64 English/Hebrew README organization. **Merged** describes code integration, not the completion of every owner device test. [R02–R04]

At the audited baseline, the owner feature freeze allowed only narrow scoped exceptions and the original request authorized research/documentation. **Subsequent owner decision D-G8 on 2026-11-08 authorizes the staged G gallery scope and documentation on main.** G0 owner approval is now recorded; start G1a after baseline reconciliation. Phone/release checks remain pending and the exception is not general permission for unrelated features. [R02, R03]

## Documentation drift requiring reconciliation

1. `CLAUDE.md` names Hilt and Coil in its stack, while current app composition and thumbnails use a manual graph and platform APIs. Use implementation as the integration baseline; propose correcting the stack description.
2. Old MVP sections still say no INTERNET. Current app includes SMB/Nextcloud backup modules and confines networking through later ADR 0011 amendments and build checks. The gallery proposal preserves that confinement; it must not claim the whole app has no internet permission.
3. The Hebrew privacy summary still says there is no internet permission, despite the English backup-network explanation and current modules. Reconcile before shipping additional privacy copy.
4. Older tasks/UX passages describe earlier trash behavior, Google Drive ordering and swipe directions. Later owner decisions and amendments change them: verified backup originals may be freed for good; Drive is postponed; swipe direction is physical and configurable. Use current implementation plus latest accepted amendments.
5. Some progress notes describe historical session loss, while later H2b code/tests implement resumable review. Reuse the latter.
6. Room's `hash_cache` schema does not prove that the app uses persistent hash caching; the graph currently passes a memory cache.
7. The progress page's highlighted test APK refers to an older commit than the audited head. CI at head is separately verified; do not identify the older artifact as the latest built binary without checking artifacts.

These findings describe the pinned pre-adoption baseline, not newly reproduced runtime defects. The gallery documentation adoption corrects the relevant stack/privacy/entry-point wording; no application fix or device result is implied.

## Existing safety interpretation used by this proposal

- SAFE requires deterministic existing evidence. A visually related picture is not a duplicate witness.
- Camera and received memories keep their MOVE classification. The existing UI test explicitly prohibits deleting MOVE groups from Review.
- Every new gallery disposal must be an explicitly selected **existing REVIEW** item through the normal confirmation and trash route. If the owner later wants to discard camera originals before backup, that is a separate change to CLS-3 and product policy, outside this plan.
- Existing exact-duplicate cleanup is preserved. Broadening its eligible sources or witness rules would be its own tested milestone.
- Previously approved permanent-deletion and retention exceptions remain intact. This proposal creates no new exception and does not disable the existing daily purge.

## Verification record

| Check | Result |
|---|---|
| Default branch/head resolved | main, full SHA above |
| Complete recursive tree | Returned without truncation |
| Specs/ADRs/build-policy snapshot | Retrieved; focused source inspection performed |
| Relevant tests | Reviewed test cases and contracts; see traceability file |
| Remote head checks | Five reported success |
| Open PRs | Empty collection |
| Hosted milestones | Public API all-state collection empty; no next page |
| Fresh Gradle build/tests | Not run in this planning project |
| Physical Android benchmarks | Not run; proposed protocol supplied |
| Application code/repository mutations | None |
