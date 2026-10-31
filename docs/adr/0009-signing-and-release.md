# ADR 0009: Signing, keys, versioning and release

Status: Accepted (2026-10-31) · Date: 2026-10-31

## Context
- Android only updates an app if the new APK is signed with the same key (or a rotated key in the v3 lineage). Losing the key means users can never get updates.
- Google Play requires App Bundles and Play App Signing for new apps. By default Google generates and holds the app signing key, and we hold only an upload key. Builds from Play and from GitHub/F-Droid then carry different signatures and can't update each other.
- F-Droid can ship the developer's own signature when the build is reproducible. IzzyOnDroid ships the developer's APK as is ([05](../research/05-play-policy-and-flavors.md)).
- Android developer verification (enforced in 4 countries since 2026-09-30, global in 2027) ties package names and signing keys to a verified developer ([05](../research/05-play-policy-and-flavors.md)).
- The `foss` and `play` flavors have different permissions (ADR 0005).

## Decision

### Keys
- **One app signing key** (RSA 4096, validity ≥ 30 years) generated offline by the owner. It signs every `foss` release (GitHub Releases, IzzyOnDroid, F-Droid via reproducible builds).
- For Play, enrol in Play App Signing with **"use my own key"** (upload the same app signing key via Google's PEPK tool), plus a **separate upload key**. All channels then carry the same signature, and a lost upload key can be reset by Google.
- **Custody (owner decision Q9):** the owner holds the key.
  - The keystore and its password live in two places: an encrypted entry in the owner's password manager, and an encrypted copy on a USB drive kept at home.
  - The owner checks both copies once a year (open the keystore, compare the certificate fingerprint with the one in the README) and after any device/password-manager change.
  - The key is never in the repo, never in CI logs, and never on a shared machine.
- **Signing location:**
  - MVP: the owner signs releases locally with a documented script.
  - Later: CI signing only from a protected GitHub Environment with required reviewer approval and the key as an encrypted secret; it is never available to PR workflows.
- **Rotation:** if the key is ever compromised, rotate with APK Signature Scheme v3 lineage (`apksigner rotate`) and document it.
- **Developer verification:** register the package name(s) and the key fingerprint before the 2027 global rollout.

### Package names
- **One applicationId for both flavors: `io.github.tomerar.keepclear`** (owner decisions Q6, Q8), as SD Maid SE does. With the same signing key, users can move between channels without reinstalling; a `play` install replacing `foss` loses all-files features, which the app detects and explains.

### Versioning
- **SemVer** `MAJOR.MINOR.PATCH`; `0.x` until v1. MVP = `0.1.0`.
- `versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH` (monotonic, flavor-independent). Example: 0.1.0 → 1000.
- Git tag `vX.Y.Z` per release; release branch not needed until v1.

### Changelog and release notes
- `CHANGELOG.md` in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format, updated in every user-visible PR under `Unreleased`.
- Store notes in `fastlane/metadata/android/{en-US,iw-IL}/changelogs/<versionCode>.txt` (format read by F-Droid and IzzyOnDroid). Any change to permissions or data handling is listed explicitly and mirrored in [PRIVACY.md](../PRIVACY.md) (PRIV-6).

### Release checklist (each release)
1. CI green, including the merged-manifest checks, PRIVACY.md sync and string parity.
2. Device checklist from [tasks.md](../spec/tasks.md) passed.
3. CHANGELOG, fastlane changelogs (en + he), versionCode bumped.
4. Reproducible build check for `foss`: two clean builds produce identical APKs except the signature block.
5. Signed with the app key; `apksigner verify --print-certs` fingerprint matches the published fingerprint in the README.
6. GitHub Release with APK, SHA-256 of the APK and the certificate fingerprint.
7. Play: AAB to internal track first; Data safety and permission declarations re-checked (PRIV-7).

## Consequences
- Users can move between GitHub, IzzyOnDroid and F-Droid builds without reinstalling. Play builds share the signature and the applicationId.
- The owner carries the key-custody responsibility; losing both key copies ends updates. This is the single biggest operational risk and is called out in CONTRIBUTING.md (maintainers section).

## Alternatives
- Google-generated Play signing key: simpler, but the Play signature differs from GitHub/F-Droid forever.
- Separate keys per channel: no single point of failure, but users can't switch channels and developer verification must cover several keys.
- Let F-Droid sign with its own key: standard for F-Droid, but its builds can't update GitHub installs.
