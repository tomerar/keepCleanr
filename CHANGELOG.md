# Changelog

All notable changes to KeepClear are documented here. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/) (see [ADR 0009](docs/adr/0009-signing-and-release.md)).

## [Unreleased]

### Documentation
- Add a developer-only offline gallery evaluation scorer and synthetic counterexample tests. No real-image benchmark results are claimed.
- Adopt the owner's 2026-11-08 gallery scope: zero additional payment, on-device-only similar/duplicate grouping, deterministic-first selective local AI, incremental reuse and reasoned keeper advice. Add requirements/architecture/accepted ADR policies, edge/threat/test/benchmark/phase annexes, source evidence and a complete builder handoff; link them from the main specs and record G1a as the next unimplemented milestone. No application feature, ML dependency, runtime approval or measured device result is included in this documentation change.

### Changed
- Thumbnails, temp files, WhatsApp statuses older than 24 hours and OS junk now stay in "Clean now" even when they are recent (owner decision B1; rules v3). Favourites and everything else recent still go to Review.

### Added
- Add an internal gallery analysis coordinator: it works out the current photo access (none, selected photos, all photos, all files) on every check, hides cached results at once when that access changes, analyses only still images the latest scan found and that are not protected, kept, in a bin or on a backup drive, reuses unchanged results, pauses when the phone is nearly full and can be stopped and continued. Nothing in the app starts it yet.
- Add an internal, private gallery feature cache (database schema 8): numeric facts only, reused only for the same source version, feature version and permission scope, bounded in size, paused when the phone is nearly full, and clearable without touching the trash, backups or kept choices. Nothing uses it yet.
- Begin an internal guarded PNG read that compares MediaStore observations before and after decoding; no application entry or action is enabled.
- Add a limited internal PNG stream decoder with byte and pixel caps, CRC validation, animated/EXIF rejection and cooperative cancellation; it is not connected to the application.
- Begin G1b with a bounded Android bitmap-to-feature primitive; no gallery UI, source access or storage changes are enabled.
- G1a internal gallery JVM engine: bounded full-digest/complete-link visual groups, versioned read-only contracts, factual keeper advice with abstention, explicit uncertainty routes and a global numeric inference-budget policy. This is test-only groundwork: no application entry, Android decoder/cache, model/runtime, network or file-action integration.
- Get one photo back (H1c): the Trash and "Restore from a backup" show pictures by month with search by name or date, restore a single file, open as sheets, say where files go, offer the newest files that fit when the phone is full, and give TalkBack actions on each picture.
- Test mode: test builds start with a banner and change no files; actions show what they would have freed. Turning it off takes Settings › Advanced and two confirmations.
- Backup drives: KeepClear reads the drive's format where Android shows it; on exFAT (and other non-FAT32) drives, files over 4 GB can be backed up. If the format can't be read, the 4 GB limit stays and the screen explains why.
- Test builds: CI publishes a signed test APK from `main` for installing on a phone (no GitHub Release).
- M10: Move to backup on a USB-C drive or SD card: pick sources and age, pre-flight checks, copy and verify in a foreground service with pause/stop and resume after unplug or app update, receipt with how to get files back, then free the verified originals into the 30-day trash.
- M9: MOVE engine (JVM): copy → hash → place → read-back verify per file, resumable after unplug or full drive, never overwrites, `SHA256SUMS` that `sha256sum -c` accepts, manifest with original paths; crash-safety property test.
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
