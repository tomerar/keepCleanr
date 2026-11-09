# G4a: read-only gallery comparison wired into the app

Date: 2026-11-09. Base: `51bff23` (work branch with G1a, the G1b PNG and JPEG decoders, the schema-8 feature cache and the G1b coordinator). Branch `g4a-ui`, local, not yet a PR. Decisions C220–C226 in [progress](../progress.md). This increment makes the deterministic comparison usable from the app, read-only: no selection, no deletion, no disposal path (G4b), no model.

## What was built

- **Composition (C220).** `KeepClearApplication.gallery` is a lazy `GalleryCoordinator` over the Room `GalleryFeatureStore` (free space from `StatFs`), `MediaStoreGalleryReads` and `LiveGalleryScope` (foss: All files access counts; play: never). It is created on the first "Compare photos" only; the live scope on the first visit to the screen. `GalleryController` (plain class in the app; `GalleryViewModel` only holds it) builds a `GallerySnapshot` from Home's latest `ClassificationSummary` (complete only when the last scan finished completely) and live exclusions rebuilt from the backup jobs (`BackupRoots.protectedPaths`, `BackupRoots.scanExclusions`) and the kept choices, which are read again before every item. `MediaStoreGalleryReads` is now public with an internal constructor; nothing else in `core:gallery-android` changed.
- **File actions pause a run (E-G20, C221).** `pauseGalleryForFileAction()` is called by `KeepClearApplication.executor()` (every clean, trash restore, purge, release and free plan), at the start of `HomeViewModel.clean`, by `MoveController.start` (a backup starts or continues) and by `MoveController.runTask` (free, check, restore from a backup). It never creates the coordinator. The run does not resume by itself.
- **Entry (C222).** A "Find similar photos" / „למצוא תמונות דומות” card at the top of the "Choose what to delete" list. No tab, no Home hero, no Home total. A Help topic "Similar photos" / „תמונות דומות”.
- **Run screen (GAL-2, C224).** The scope in plain words (all photos KeepClear can see; only the photos you picked; no access with the existing permission request). While running: „N מתוך M תמונות” with the skipped count and Stop. Stopped, paused for low space, paused for a file action: honest text and Continue. Access changed: earlier groups hidden, start again. Done: „כל N התמונות … הושוו” only when the run completed, the coordinator reports it not partial, nothing was skipped and grouping hit no limit; otherwise „N מתוך M”. Counts from the cache, decoded now, not reached, skips by reason and exclusions are under „לראות פרטים” only.
- **Groups and comparison (GAL-4, GAL-6, GAL-10, GAL-21, C223, C225, C226).** Groups list: photo count, thumbnails (the existing memory-only `ThumbnailLoader`), the marker's reasons or „אין המלצה ברורה”, a note when memories are inside; no bytes. Comparison: two photos per row with date and size, the advice card („מומלץ להשאיר” with one or two of „רזולוציה גבוהה יותר”, „חדה יותר”, „פחות אזורים בהירים או כהים מדי”, or abstention), memories under „זיכרונות לגבות” with „לגבות” (opens the Backup tab), other photos with no action and a line that choosing what to delete comes later. No checkbox, nothing selected, no delete label. „להשאיר את כולן הפעם” hides the group for this session; „לא להציע את זה שוב” is the existing per-file kept rule, on the photo's preview screen and as a TalkBack action on the tile. Members are shown only if they are in the latest scan's MediaStore items; a group needs two shown members, and a marker on a photo that can't be shown becomes abstention. Both screens re-read the live scope on every resume and hide groups from an older permission epoch.
- **Architecture rule (C224).** `ArchitectureTest` "app gallery code is offline, advisory and foreground": `Gallery.kt` and `GalleryScreens.kt` import no network, backup, safety, trash, move or WorkManager package and name no socket, URL, executor, plan, confirmation, `GlobalScope` or service start. The project-wide network rules still apply.

## Tests and checks run locally

All with `--max-workers=2` and in-process Kotlin compilation.

- `G4aGalleryTest` (13, Robolectric SDK 36): a real coordinator over fakes that follow the port contracts (`GalleryFakes`: scope epochs, key + version + epoch store, gated reads). Complete run with a reasoned keeper and an abstention; Selected photos, an unsupported photo and an incomplete scan never "all"; no access; nothing created before a start or by the ViewModel; Stop then Continue finishes the same run; keep this time; don't suggest this again; memories keep their lane; an access change hides groups; the executor, a backup start, a free/check/restore task and the clean flow pause a run; the hook never creates the coordinator.
- `G4aScreensTest` (9, Robolectric Compose): marker is not a selection (no checkbox role, nothing selected, no delete label); memories show „לגבות”, others don't; abstention; TalkBack (one node per photo with Preview and Don't suggest this again, role Button); keep this time; honest partial/complete wording; running progress and Stop; no access offers the permission request; Details sheet; Hebrew at 200 % (56 dp main action, 48 dp target, first photo on the right).
- `G4aScreenshotTest`: 28 recorded Roborazzi images (`gallery_entry`, `gallery_running`, `gallery_partial`, `gallery_groups`, `gallery_compare`, `gallery_compare_none`, `gallery_no_access`, each en/iw × light/dark), recorded with `recordRoborazziFossDebug --tests '*G4aScreenshotTest*'` and inspected.
- Updated: `H3HelpTest` (11 topics; the new topic quotes the real entry, marker and lane labels), `ArchitectureTest` (new rule), `docs/hebrew-review.md` regenerated with a "Similar photos" screen.
- Full `./gradlew ciCheck --continue` (both flavours' unit tests, lint, `verifyRoborazziFossDebug`, architecture and policy checks, spotless): the first run failed only on lint `UnusedResources` (an unused string, removed); the second run after that fix passed (`BUILD SUCCESSFUL`). The API 30/36 emulator tests are not part of `ciCheck` and are pending CI; no instrumented test was added.

Mutations (each applied alone with `scripts`-style text replacement, the named tests run, then reverted):

| Mutation | Failing test(s) |
|---|---|
| "All photos" ignores skipped photos (`skipped == 0` removed) | `G4aGalleryTest` "selected photos or a skipped photo never read as all photos" |
| Abstention marks the first member as the keeper | `G4aGalleryTest` "a complete run over all photos …" |
| Hook removed from `executor()` | `G4aGalleryTest` "the clean and trash executor pauses a running comparison" |
| Hook removed from `MoveController.runTask` | `G4aGalleryTest` "a backup start and a free, check or restore task …" |
| Hook removed from `MoveController.start` | same test |
| Hook removed from the start of `HomeViewModel.clean` | `G4aGalleryTest` "the clean flow pauses the comparison before it plans …" |
| Hook always calls the coordinator (no `isInitialized` check) | same test (the coordinator is created) |
| ViewModel resolves the coordinator eagerly | `G4aGalleryTest` "the ViewModel creates nothing until a run starts" |
| Access recheck branch removed | `G4aGalleryTest` "an access change after the run hides the groups" |
| Keep-this-time filter removed | `G4aGalleryTest` "keep this time hides a group …" |
| Don't-suggest filter removed | `G4aGalleryTest` "don't suggest this again takes the photo out …" |
| Photo tile given `selected = false` (a checkbox) | `G4aScreensTest` "the keeper marker is a label, not a selection …" |
| Backup section shown for every group | `G4aScreensTest` "memories offer the existing backup …" |
| Done always says "all N photos" | `G4aScreensTest` "run wording is honest …" |
| `Gallery.kt` imports `core.safety.ActionPlan` | `ArchitectureTest` "app gallery code is offline, advisory and foreground" |

Each test-mutation run counted as killed only when Gradle reported failing tests (not a compile error).

## Not verified

- No phone run: Selected Photos grants, revocation while the screen is open, the real MediaStore thumbnails of grouped photos, TalkBack and large text on the owner's phone, airplane mode and low space are owner checks G4a-1…G4a-4 in [progress](../progress.md). Instrumented tests: none added.
- The comparison quality itself (thresholds, keeper reasons) is the uncalibrated G1a/G1b policy; no photographic accuracy, speed or battery result is claimed.
- A run lives in the ViewModel and the process: after process death nothing resumes and dismissals are forgotten; persisted review decisions are G4a-2.
- "Back up" opens the Backup tab; it does not preselect the group's photos there.
- Items from the foss all-files walk still have no guarded reader and are counted as skipped ("places KeepClear can't compare yet").
- The pause hook covers the flows that go through the executor or the MOVE controller; Android's own cache trim (`allocateBytes`) and the system cache-clear screen are not file actions on user files and do not pause a run.
