# G1b: live gallery scope, permission epoch and analysis coordinator

Date: 2026-11-09. Base: `1a4463e` (work branch with the merged G1b feature cache, schema 8). Branch `g1b-coord`, local, not yet a PR. Decisions C210–C214 in [progress](../progress.md). This increment connects the guarded read, the feature cache and the deterministic grouping engine behind one internal coordinator; nothing in the application creates or calls it yet.

## What was built

- **Live scope and permission epoch** (GAL-1, GAL-12, AC-G1, E-G01/02, C211). `PlatformGalleryScope` derives `NONE`, `SELECTED`, `FULL_IMAGES` or `ALL_FILES` from image permissions only (`READ_MEDIA_IMAGES`; `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+; `READ_EXTERNAL_STORAGE` below Android 13) plus a caller-supplied all-files flag (foss: `MediaAccess.hasAllFiles()`; play: false). `LiveGalleryScope` re-reads it on every call and advances the epoch (`PermissionEpoch.advance`, `engine:gallery`) on any change, narrower or wider. The last scope and epoch are kept in a private `gallery-scope` preferences file written with `commit()` on `Dispatchers.IO` (never on the main thread); they only detect a change and are never read authority. A garbled file reads as "never seen".
- **Eligibility** (GAL-1, E-G03/04, C210). From the latest scan's `ClassifiedItem`s (`GallerySnapshot.of(scanId, ClassificationSummary, complete, exclusions)`): still images (`image/*`) only. Excluded and never read: user-kept rules (`Rules.USER_KEPT`), kept paths (`UserChoices` roots, matched with `CanonicalPath`), favourites; PROTECTED category and `ProtectedPaths` (chat databases, KeepClear's trash, backup targets when built with `BackupRoots.protectedPaths`); items in another app's bin; backup drives/folders (`BackupRoots.scanExclusions`); SAFE junk. Pending media are not in the scan (`MATCH_EXCLUDE`) and the observation refuses `IS_PENDING` again. Exclusions are re-evaluated by the read gate before and after each read, so a file kept mid-run is not read.
- **Read ports** (C210). `GallerySourceObserver` (metadata only) and `GalleryImageReader` with its own `GalleryRead.Read` / `Unsupported(reason)` / `Unavailable` result, in `GalleryReadPorts.kt`. One adapter, `MediaStoreGalleryReads`, binds them to `MediaStoreObservation` and `GuardedMediaStillRead` (#73): the read picks PNG or JPEG from the content signature and its skip reasons map one to one to `UnsupportedImage` (`MediaStoreGalleryReadsTest`). Written first against the PNG-only read; when both increments were merged, only this adapter changed.
- **Coordinator** (GAL-2, GAL-15, GAL-26, AC-G7/15, C212/C213). `GalleryCoordinator` (plain Kotlin, no `android.*` import) runs one user-started run at a time. Per item: live scope check → observation → `GalleryFeatureStore.get` for exactly the observed version and the run's epoch → otherwise one guarded read → `put` of the completed record only. `beginEpoch` is called at every start and as soon as a different epoch is seen; `STALE_EPOCH` from `beginEpoch` (the cache is ahead of the ledger) clears the recomputable gallery cache and begins again. `PAUSED_LOW_SPACE` stops decoding and writing and reports the run paused; `FAILED` keeps the run going in memory without further writes; `STALE_EPOCH` on `put` ends the run as access changed; `REJECTED` skips the item. Bounds: one worker, at most 8 item refs handed out per batch, cancellation between items and inside the read. A refusal caused by a stop, pause or scope change leaves the item pending.
- **Grouping entry** (C210). `GalleryGrouping.group` in `engine:gallery` is the smallest public entry to the internal `DeterministicGallery`: it groups only completed records, reads nothing and requests no full-file digest (so this path yields no exact-identity groups yet). The result is the existing advisory `GalleryResult`.
- **Result**: state (`COMPLETED`, `STOPPED`, `PAUSED_LOW_SPACE`, `PAUSED_FOR_FILE_ACTION`, `ACCESS_CHANGED`, `NO_ACCESS`), scope, counts (still images, excluded by reason, eligible, from cache, newly decoded, analysed, skipped by reason, not reached), `partial` (Selected photos, incomplete scan, items without a reader, a stop or a pause), cache-write flags, the grouping (withheld after an access change) and the observed queue/in-flight maxima.
- **Lifecycle** (GAL-15, E-G16/20/25, C213/C214). Stop and `GalleryWorkGate.pauseForFileAction` (the hook for backup/free/restore/clean flows) leave a partial result; `continueRun` resumes the same run and epoch, reusing completed records, only while the live authorization is unchanged. State is in memory only: nothing resumes after process death, and an `ArchitectureTest` rule forbids WorkManager, JobScheduler, AlarmManager, boot receivers, services and `GlobalScope` in the gallery modules and `android.*`/`androidx.*` imports in the coordinator, eligibility and port files.
- **No app wiring** (C214): `KeepClearApplication` does not create the coordinator; the app does not depend on the gallery modules yet. G4a adds the screen, the composition and the snapshot from `HomeViewModel`'s summary, and calls the file-action hook from the existing flows.

## Tests and checks run locally

All with `--rerun-tasks`, `--max-workers=2`, in-process Kotlin compilation:

- `:core:gallery-android:testDebugUnitTest`: 47 tests passed, including the new `GalleryCoordinatorTest` (17, plain JUnit with fakes for the store, both read ports and the scope; no Robolectric) and `LiveGalleryScopeTest` (2, Robolectric SDK 36: the platform scope per API level and the preferences ledger across instances).
- `:engine:gallery:test`: 36 passed, including the new `GalleryGroupingTest` (4: completed-only grouping without digests, a refused source withheld and partial scope disclosed, text-sensitive records never matched, the epoch moves on every scope change).
- `:testing:architecture:test`: 17 passed, including the new "gallery work never schedules itself and the coordinator is plain Kotlin".
- `spotlessCheck`, `:engine:gallery:checkForbiddenApis`, `:core:gallery-android:checkForbiddenApis` and `:core:gallery-android:assembleDebugAndroidTest` (no androidTest source changed; compiled to confirm) passed.
- Not run: the full `./gradlew ciCheck` (both app flavours, lint, screenshot verification), because the shared build disk had about 3.7 GB free with another worktree building; the app and its resources are unchanged. CI must run it.

Fakes follow the real contracts: the fake store matches key, version (the observation ignored when a revision exists, as in `RoomGalleryFeatureStore`) and epoch, returns the requested version, refuses writes outside the recorded epoch and drops other epochs in `beginEpoch`; the fake reads ask the gate before and after a read, like `GuardedMediaPngRead`.

Mutations (each applied alone, the named tests run, then reverted):

| Mutation | Failing test(s) |
|---|---|
| Cache lookup skipped | "cache hit needs no decode …", "a changed source version is recomputed …" |
| A scope change does not begin the new epoch in the cache | "scope reduction mid-run stops reads, hides cached records …" |
| No live scope check before reads (per-item check and gate clause removed) | "scope reduction mid-run …" (items were observed and served from the old epoch's cache) |
| `PAUSED_LOW_SPACE` does not pause | "low space pauses decoding and writing …" |
| Completed items stay pending | six tests, among them "stop cancels the in-flight read promptly … Continue reuses completed" |
| Unbounded queue (one batch of all items) | "one worker and a queue of at most eight refs" |
| A newer cache epoch is trusted instead of cleared | "a newer epoch in the cache than in the ledger clears the cache …" |
| `FAILED` write does not stop writes | "a failing cache write keeps the run going in memory …" |
| Changed and unreadable swapped after an `Unavailable` read | "unsupported, damaged, empty and changed items are skipped …" |
| Selected scope counted complete | "selected photos are analysed but always partial …", "scope reduction mid-run …" |
| Kept / trashed / backup-volume items not excluded | "protected, kept, favourite, trashed, junk, excluded and pending items are never read" (kept also "an item kept mid-run is not read") |
| Kept state not rechecked in the read gate | "an item kept mid-run is not read" |
| Ledger never written | "the epoch is persisted privately …" |
| `READ_EXTERNAL_STORAGE` counted on Android 13+ | "platform scope from the permissions each API level really uses" |
| Epoch kept on a scope change (`PermissionEpoch`) | "the permission epoch moves on every scope change …" |
| Groups shown after an access change (both guards removed) | "scope reduction mid-run …" |
| `GalleryGrouping` ignores `current` | "a refused source is withheld …" |
| A comment naming `androidx.work.WorkManager` added to the coordinator | `ArchitectureTest` "gallery work never schedules itself …" |

Removing only the first of the two "no groups after an access change" guards survives, because the second (a live scope check after grouping) still withholds them; the combined mutation is killed.

## Not verified

- No app caller, no UI and no device test: real Selected Photos grants, revocation and auto-reset, all-files access and MediaStore behaviour were exercised only through fakes (the scope function and the preferences ledger run on Robolectric's SDK 36). Instrumented tests: none added.
- A Selected Photos set that shrinks without any permission change does not move the epoch; such items are refused by the live observation (C212), but their old rows stay in the cache, unreachable, until the next epoch or eviction.
- Items from the foss all-files file walk (`KPath.Local`) have no guarded reader yet: they are counted as `NO_READER` and keep the run partial.
- Exact-identity (full-file digest) grouping is not used by this path; memory, thermal and battery pauses (E-G16) other than low space are not implemented; the backup/clean flows do not call the pause hook yet (E-G20).
- Low space is learned from the store's refusal of a write, so at most one image is decoded after crossing 500 MB before the run pauses.
- Process death, performance, battery and photographic accuracy were not measured.

## Review of PR #74

- **Progress while running.** The worker publishes a running snapshot after every handled item, so `progress` shows the actual analysed and not-reached counts during a run instead of only at its end. Test: `progress shows real counts while the run is working`. Mutation: removing the publish makes it fail.
- **Revalidation before Continue (E-G07/E-G09).** Before a stopped or paused run resumes, every completed source is checked against the latest exclusions and re-observed. A source that changed version, disappeared or became excluded, for example kept during the pause, is taken back into the queue with its count corrected, so the run decides it again instead of grouping old features. Test: `continue re-checks completed sources and never groups a changed or newly kept one`: one photo gets a new revision and another is kept while stopped; Continue decodes the first again and skips the second. Mutation: removing the revalidation makes it fail. Revalidation reads metadata only. During one uninterrupted run, a completed source is not re-observed before grouping; every later action still revalidates independently.
