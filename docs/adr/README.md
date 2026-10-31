# Architecture Decision Records

Short records of the big, hard-to-reverse choices. Format: Context → Decision → Consequences → Alternatives considered. Status is one of Proposed / Accepted / Superseded.

All ADRs are **Proposed** until the owner approves the spec phase.

| # | Title | Status |
|---|---|---|
| [0001](0001-kotlin-compose-native.md) | Native Kotlin + Jetpack Compose, not Flutter | Proposed |
| [0002](0002-license-gpl-3.0.md) | License: GPL-3.0-or-later | Proposed |
| [0003](0003-min-sdk.md) | minSdk 30 (Android 11), target/compile 36 | Proposed |
| [0004](0004-privilege-tiers-shizuku-optional.md) | Privilege tiers; Shizuku and root strictly optional | Proposed |
| [0005](0005-flavors-play-foss.md) | Two flavors: `play` and `foss` | Proposed |
| [0006](0006-own-trash.md) | Own 30-day trash; never permanent-delete user files directly | Proposed |
| [0007](0007-verify-by-hash.md) | MOVE deletes only after per-file SHA-256 verification | Proposed |
| [0008](0008-no-accessibility-no-network.md) | No AccessibilityService, no INTERNET in v1 | Proposed |
| [0009](0009-signing-and-release.md) | Signing keys, Play App Signing, versioning, changelog, release checklist | Proposed |
