# Changelog

All notable changes to KeepClear are documented here. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/) (see [ADR 0009](docs/adr/0009-signing-and-release.md)).

## [Unreleased]

### Added
- M8: Review actions: multi-select (nothing pre-selected, select a whole month), editable confirm list, delete to trash with Undo; "Move instead" placeholder.
- M7: "Clean now": byte-identical WhatsApp Sent copies, fully extracted archives and junk are found; pre-selected SAFE groups move to the trash after one confirmation, with Undo; Android cache trim with a measured result; foss-only external cache clear.
- M6: 30-day trash with restore and "Empty now" (second, delayed confirmation), signed sidecars for recovery after reinstall, daily expiry purge, Trash screen; crash-safety property test over 2,000 random kill points.
- M5: Home shows Clean now / Review / Move to backup totals and where space is; read-only browsing by category, group (month headers, thumbnails) and item, with why / what-happens texts.
- M4: deterministic classifier with a versioned rule table (WhatsApp variants, Telegram, junk, screenshots, AI images, old-phone folders, camera), keep signals, and English/Hebrew explanations for every rule.
- M3: storage scan: java.nio file gateway, MediaStore reader, read-only cancellable scanner, Room scan index with schema export and migration harness, media/all-files permission flow with plain-language explainers, scan summary on Home, low-space modes.
- M2: safety core: canonical PROTECTED-path matching (all WhatsApp variants, any volume/user root), plan guard, single-choke-point executor with re-checks, strict re-hash and dry-run; SHA-256 module.
- M1: storage abstraction (`core:storage-api`), fault-injecting in-memory fake with a gateway contract suite, owner-shaped test fixtures.
- M0 foundations: Gradle multi-module skeleton (Kotlin 2.4, AGP 9.5, Compose), `foss`/`play` flavors, placeholder Home in English and Hebrew (RTL), light/dark themes.
- CI guard rails: manifest policy (no INTERNET, restricted permissions only in `foss`, only the launcher exported, PRIVACY.md in sync), dependency allowlist, en/he string parity, ktlint, Android Lint, Konsist architecture rules, screenshot tests, Gradle dependency verification, SHA-pinned GitHub Actions.
- Research (`docs/research/`), specification (`docs/spec/`), threat model, privacy policy and ADRs 0001–0009. No app code yet.
