# ADR 0005: Two flavors, `play` and `foss`

Status: Accepted (2026-10-31) · Date: 2026-10-31

## Context
Google Play does not list "cleaner" among permitted uses of All files access or QUERY_ALL_PACKAGES, and calls cleaners "not accessibility tools" ([05-play-policy-and-flavors](../research/05-play-policy-and-flavors.md)). WhatsAppCleaner dropped all-files from its Play build; SD Maid SE kept it (established app, file-manager features). F-Droid/IzzyOnDroid/GitHub have no such restriction but forbid proprietary deps.

## Decision
- Product flavors `play` and `foss`, same codebase, capability-gated features (not `#ifdef`-style forks).
- `foss`: + `MANAGE_EXTERNAL_STORAGE`, + `QUERY_ALL_PACKAGES`, direct `File` I/O, `ACTION_CLEAR_APP_CACHE`, leftover-folder detection.
- `play`: MediaStore (READ_MEDIA_IMAGES/VIDEO, Photo & Video permissions declaration) + SAF trees + usage access + optional Shizuku. No all-files, no QUERY_ALL_PACKAGES (launcher `<queries>` only).
- **One applicationId for both flavors: `io.github.tomerar.keepclear`** (owner decisions Q6, Q8), signed with the same key (ADR 0009). Installing the `play` build over a `foss` install is possible and drops all-files features; the app detects the lost capability and says so.
- Neither: AccessibilityService, Google/Firebase SDKs, analytics.
- Restricted permissions live only in `app/src/foss/AndroidManifest.xml`; a CI check fails if they appear in the merged `play` manifest.
- Distribution order: GitHub Releases → IzzyOnDroid → F-Droid main → Play (internal track test of READ_MEDIA_* declaration first).
- Reproducible builds for `foss`; register the package + signing key for Android developer verification before its 2027 global rollout.

## Consequences
- `play` loses one-dialog external cache clear, automatic detection of old-phone folders (needs per-folder SAF grant), and manage-space intents. MOVE and WhatsApp cleanup work in both.
- Two storage backends (File and SAF/MediaStore) behind one interface; both tested.

## Alternatives
- Single all-files build on Play: likely rejection; one Play account is a single point of failure (SD Maid 2023 removal).
- F-Droid only: limits reach.
