# G1b: private gallery feature cache (schema 7→8)

Date: 2026-11-09. Base: `60036c3`. Branch `g1b-cache`, local, not yet a PR. This is one G1b increment: the cache and its narrow store exist and are tested on the real schema-8 database; nothing in the application, the MediaStore observation or the decoders calls them yet.

## What was built

- **Port** `GalleryFeatureStore` in `engine:gallery` (pure JVM, no new imports): `get`, `put`, `beginEpoch`, `invalidate`, `clear`, with `StoreWrite` results (`STORED`, `PAUSED_LOW_SPACE`, `STALE_EPOCH`, `REJECTED`, `FAILED`). The grouping engine does not call it; a later coordinator will.
- **Schema 8** in `core:database` (C205): two new tables, `gallery_feature` (one row per source) and `gallery_state` (one row: the current permission epoch, C207), added by the declared `AutoMigration(7, 8)` like every earlier version; `8.json` is committed. No existing table, column or row is touched; no destructive fallback.
- **Row contents** (C206): the source key, the full source version and the feature version are stored only as SHA-256 digests (64 hex characters, length-prefixed fields under a domain tag). No volume name, media ID, path, revision string or run ID is stored. Facts are numeric only: dHash, pHash, aspect, low-detail flag, usable pixels, sharpness, clipped fraction. No vectors, no thumbnails, no JSON.
- **Matching** (GAL-12, E-G07, E-G10): `get` returns a record only when key, version digest, feature-version digest and the caller's live permission epoch all match and the stored facts are still valid; anything else, including a database read error, is a miss. With a provider revision (the MediaStore observation's store version + generation) the version digest covers size, time and revision, so an unchanged source is reusable in a later run (AC-G15). Without a revision it also covers the observation ID, so same size and time never permit cross-run reuse.
- **Epoch** (GAL-12, E-G02/25, C207): the caller passes its live epoch, so a reduced scope is hidden at once, before anything is written. `beginEpoch` records a newer epoch and deletes every other epoch's rows in one transaction; an older epoch is refused, and `put` is refused (`STALE_EPOCH`) unless its epoch is the recorded one, so an old run cannot write after a newer one began.
- **Writes** (GAL-15, E-G14/15): `put` is one Room transaction (insert, then eviction) or nothing. It is refused below 500 MB free (LIGHT) and in MINIMAL (<100 MB), using the existing `SpaceMode` thresholds; a failing space probe also refuses. An `SQLiteException` (for example `SQLiteFullException`) during any gallery write returns `FAILED` after Room rolls back that transaction. Removals (`beginEpoch`, `invalidate`, `clear`) run in every space mode because they only shrink the cache; if one fails, the epoch and version checks in `get` still hide stale records.
- **Quota** (C206): at most `MAX_ROWS = (16,000,000 − 4,000,000) / 512 = 23,437` rows. When a put exceeds it, rows of another feature version go first, then the least recently stored; an evicted source is simply a miss ("needs analysis again", never "checked").
- **Clear** (GAL-13, E-G21): one transaction deleting both gallery tables. The store holds only `GalleryDao`, whose queries name only `gallery_*` tables. User-kept choices are in `SharedPreferences` (`UserChoices`), which this code cannot reach.
- **Isolation rules** (C208): `ArchitectureTest` now refuses any reference to `core.database` from `engine/gallery` or `core/gallery-android`, any mention of the other DAOs, `KeepClearDatabase`, `RoomDatabase` or `execSQL` in `GalleryCache.kt`, and any `@Query` there that names a table other than `gallery_*`.
- **Backup exclusion** (GAL-11, AC-G8, C209): the cache is a table in `databases/keepclear.db`. The manifest has `allowBackup="false"`; `data_extraction_rules.xml` (cloud backup and device transfer) and `backup_rules.xml` exclude every domain, including `database`, with no `include`. A new `ArchitectureTest` case parses these files and fails if any of that changes. The manifest also sets `hasFragileUserData="true"`, so a user may choose to keep app data on uninstall; the cache then returns with the database, but it is recomputable, holds no consent and every record still has to match the live epoch and source version.

## Measured

`GalleryCacheQuotaTest` (Robolectric's SQLite on the build machine, on-disk database in WAL mode) wrote 35,155 records with the longest legal key (512 + 4,096 characters), 4,096-character revision and observation, and extreme numeric values, with a scattered invalidation every seventh write during the first 23,437. At the 23,437-row cap after that churn, the checkpointed main database file was **8,970,240 bytes** and the largest sampled WAL file **465,592 bytes** (≈383 bytes per row). The test asserts main ≤ 12,000,000 bytes and WAL ≤ 4,000,000 bytes. These are build-machine measurements, not phone measurements; the on-device size, the WAL under concurrent writes to other tables and LOW-5's whole-app 50 MB figure are still to be measured.

## Tests and checks run locally

- `:core:database:testDebugUnitTest`: 31 tests passed, including the new `GalleryFeatureStoreTest` (13), `GalleryCacheQuotaTest` (1) and `MigrationTest` "7 to 8 …" (a schema-7 database with rows in `scan_run`, `scan_item`, `hash_cache`, `trash_entry`, `move_job` and `move_file`, including verified/confirmed backup proofs, migrates with every column of every row identical and both gallery tables empty). The existing migration cases now validate against schema 8.
- The disk-full case is a real `SQLITE_FULL`: `PRAGMA max_page_count` is set to the current page count and records are written until one fails; that write returns `FAILED`, is absent afterwards, earlier records and every authoritative row are unchanged, and the same record is stored once the limit is lifted.
- `:testing:architecture:test` (16 tests, 2 new), `:engine:gallery:test` (32), `:core:gallery-android:testDebugUnitTest` (28), `spotlessCheck`, all with `--rerun-tasks`; `:engine:gallery:checkForbiddenApis` and `:core:database:checkForbiddenApis` passed.
- Not run: the full `./gradlew ciCheck` (lint, app build and screenshot verification), because the shared build disk had about 2.6 GB free; CI must run it. No instrumented test was added.
- Mutations: see the table below; each made at least one named test fail and was reverted.

| Mutation in `GalleryCache.kt` | Failing test(s) |
|---|---|
| `get` ignores the source-version digest | "any source or feature version difference is a miss", "without a revision a record is bound to the observation" |
| Version digest without a revision drops the observation ID | "without a revision a record is bound to the observation that read it" |
| `get` ignores the permission epoch | "a new permission epoch hides old records at once and then drops them" |
| `beginEpoch` keeps other epochs' rows | "a new permission epoch hides old records …" |
| `put` skips the recorded-epoch check | "nothing is written before an epoch is recorded", "a new permission epoch …", "clearing the gallery keeps …" |
| `clear` keeps `gallery_state` | "clearing the gallery keeps trash, backup proofs, move jobs, scan and hash data" |
| No eviction at the cap | "at quota the least recently stored valid row is evicted …", "rows of another feature version are evicted before current ones" |
| Eviction prefers current-version rows | "rows of another feature version are evicted before current ones" |
| Only MINIMAL refuses writes (LIGHT allowed) | "no gallery writes below 500 MB free or in MINIMAL" |
| `SQLiteException` not caught | "a full database aborts only the gallery write and keeps authoritative rows" |
| Stored facts not re-validated on read | "invalid facts and corrupt rows are never cached or returned" |
| Plaintext key instead of the digest | "no key, ref, path or revision text is stored" |
| Gallery clear query deletes `trash_entry` | "clearing the gallery keeps …" |
| `gallery_state` clear query names `trash_entry` | `ArchitectureTest` "gallery cache code cannot reach other tables or DAOs" |
| `device-transfer` no longer excludes `database` | `ArchitectureTest` "gallery cache stays out of cloud backup and device transfer" (run with `--rerun`: the XML files are not inputs of that test task) |

## Not verified

- No caller: the store is not wired into the app, the MediaStore observation, the PNG reader or any coordinator; there is no real permission-epoch source yet, so "epoch" is whatever the future coordinator supplies (monotonic by contract).
- No instrumented/device test of the cache; Robolectric's SQLite is not a phone's. Real ENOSPC on a full filesystem (as opposed to `max_page_count`) was not exercised.
- The 16 MB cap is per cache; the whole-app 50 MB budget (LOW-5) and the rule to shrink the gallery share when the rest of the app grows are not implemented.
- Group, run and user-decision records (G4a) are deliberately absent. No full digest (`FullDigest`) caching.
