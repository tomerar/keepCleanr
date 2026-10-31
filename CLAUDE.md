# CLAUDE.md: KeepClear

KeepClear is an open-source (GPL-3.0-or-later) Android storage cleaner. Junk is deleted in one tap, anything unclear is explained, and memories are **moved** (backup → verify → delete), never just deleted.

## Read before coding

1. `docs/spec/requirements.md`: what to build, with IDs (SAFETY-*, MOVE-*, …) and priorities.
2. `docs/spec/design.md`: modules, data flow, abstractions.
3. `docs/spec/edge-cases.md`: required behaviour for failures (E-*).
   Also `docs/spec/threat-model.md` (SEC-*): treat sidecars, manifests, archives and intents as untrusted.
4. `docs/spec/tasks.md`: current milestone, definition of done, required tests.
5. `docs/adr/`: decisions; don't contradict an ADR without writing a new one.
6. `docs/research/`: evidence and sources. Check it before assuming an Android API or Play policy behaviour.

Work only on the current milestone in `tasks.md`. If the spec is wrong or incomplete, update the spec in the same PR and say so.

## Status

Spec approved by the owner on 2026-10-31 (decisions in `docs/spec/requirements.md` §14). MVP milestones **M0–M10 are merged** (PRs #1–#11); device checks and open decisions are tracked in `docs/progress.md`. Next: v1 milestones per `tasks.md`, one PR per milestone, after the owner's OK.

## Identity

- App name **KeepClear**, applicationId **`io.github.tomerar.keepclear`** for both flavors, one signing key (ADR 0009).

## Stack

- Kotlin only, Jetpack Compose (Material 3), Hilt, Room, WorkManager, Coroutines/Flow, kotlinx.serialization, Coil.
- minSdk 30, targetSdk 36, compileSdk 37 (ADR 0003).
- Flavors: `foss` (all-files access, F-Droid/GitHub) and `play` (MediaStore + SAF only) (ADR 0005).
- Tests: JUnit5, Kotest (property tests), Turbine, Robolectric (sparingly), Compose UI tests, screenshot tests, Konsist architecture rules.

## Safety rules (non-negotiable)

1. **Never delete without explicit confirmation** in the current session. No background or scheduled deletion of user files.
2. **Every user-file deletion goes to the 30-day trash** (`engine:trash`). Permanent deletion happens only through purge, "Empty now" with a second confirmation, or system cache trimming that is labelled as such.
3. **One choke point**: only `ActionExecutor` in `core:safety` (plus the trash and storage implementations) may call `File.delete`, `DocumentsContract.deleteDocument`, `ContentResolver.delete`, `MediaStore.create*Request`, or rename user files. Konsist enforces this.
4. **PROTECTED paths are enforced at the choke point**: WhatsApp `Databases/`, `Backups/`, `*.crypt*`, `msgstore*`, our trash, the active backup target, and user exclusions.
5. **MOVE deletes an original only after an L3 (SHA-256 read-back) verification**, a size/mtime re-check and a re-hash of the original right before deleting (strict mode, on by default; ADR 0007).
6. **Re-check before acting**: if an item changed since the scan, skip it and report it.
7. **Never pre-select** REVIEW or MOVE items. Duplicate groups always keep at least one copy.
8. Destructive code ships **with its tests in the same PR**: unit tests on `FakeStorageGateway` plus kill-point property tests for the no-loss invariant.

## Product rules

- No ads, analytics, crash SDKs or Google Play Services. No `INTERNET` permission in MVP (ADR 0008).
- No AccessibilityService.
- Copy: exact numbers (count + bytes), plain language, no fear or "boost" claims. Every item has why / what-happens-if-deleted texts in **English and Hebrew**.
- RTL: use start/end only, auto-mirrored icons, and bidi-isolate filenames, paths and numbers. Hebrew is gender-neutral (plural/infinitive).
- All strings live in resources; en/he parity is checked in CI.

## Code conventions

- Engines (`engine:*`, `core:model`, `core:safety`, `core:rules`, `core:hash`) are pure JVM: no `android.*` imports.
- Rules and filters are data (JSON/tables) with table-driven positive and negative tests.
- Add `// SPDX-License-Identifier: GPL-3.0-or-later` to new files. Code reused from GPL-3.0/MIT/Apache projects keeps its copyright line and gets an entry in `THIRD_PARTY.md`. Never copy AGPL or GPL-2.0-only code, SD Maid SE icons/docs, or upstream translations.
- Restricted permissions go only in `app/src/foss/AndroidManifest.xml`.

## Formats and migrations

- Room: explicit migrations only, schemas committed, no destructive fallback (MIG-1).
- Sidecar, manifest and rules JSON are versioned; never rename stored enum values (MIG-2/3).

## Build and checks

- JDK 21, Android SDK with platform `android-37.0`. `./gradlew ciCheck` runs what CI runs (except emulator tests).
- `./gradlew -p build-logic test` tests the policy checkers.
- Screenshots: `./gradlew :app:recordRoborazziFossDebug` to re-record after an intended UI change; CI verifies them.
- New dependency: add it to `gradle/libs.versions.toml`, then refresh `gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 ciCheck`, then run `scripts/verification-extras.py` (re-adds macOS/Windows aapt2 checksums) and review the diff. Runtime deps must pass the allowlist (PRIV-1).

## Git

- Small commits with clear messages. Run the build, unit tests and lint before pushing.
