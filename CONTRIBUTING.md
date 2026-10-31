# Contributing to KeepClear

Thanks for helping. KeepClear touches people's photos and chats, so we optimise for **safety first, then clarity, then features**.

## Before you start

1. Read [CLAUDE.md](CLAUDE.md) (the project rules, for humans too) and the spec in [`docs/spec/`](docs/spec/).
2. Check [`docs/spec/tasks.md`](docs/spec/tasks.md) for the current milestone. Work outside it needs an issue first.
3. For anything that deletes, moves or restores files, open an issue to agree on the approach before coding.

## Ground rules

- **Safety rules are non-negotiable** (CLAUDE.md §Safety). PRs that add a deletion path outside `core:safety`, skip the trash, or weaken PROTECTED paths will be closed.
- Destructive code ships **with tests in the same PR**: unit tests on `FakeStorageGateway`, and kill-point property tests where relevant.
- Every user-visible string in **English and Hebrew** (`values/` and `values-iw/`). If you don't speak Hebrew, add the English string, tick "needs Hebrew" in the PR template, and a maintainer or translator will add it before merge; CI checks parity.
- No new dependencies without discussion; they must be GPL-3.0-compatible, have no network/analytics, and pass dependency verification.
- No Google Play Services, Firebase, analytics, ads, or AccessibilityService.
- Don't contradict an ADR (`docs/adr/`) without proposing a new one.

## Rules and junk filters

New junk/folder rules are data in `core:rules`. Use the "Rule proposal" issue template, and include:
- Example paths that **should** match and paths that **must not** match (these become table tests).
- Evidence (device, app version, `adb shell ls` output) and the "why" / "what happens if deleted" texts.
- New rules start as REVIEW. Promoting to SAFE needs evidence that the data is regenerable or duplicated.

## Code style

- Kotlin, ktlint + detekt (run `./gradlew check` once the project exists).
- `// SPDX-License-Identifier: GPL-3.0-or-later` on new files.
- Code from other projects: only GPL-3.0-compatible licenses; keep the original copyright line and add an entry to `THIRD_PARTY.md`. Never copy AGPL or GPL-2.0-only code, or SD Maid SE icons/docs/translations.

## Commits and PRs

- Small, focused commits with clear messages. Reference requirement IDs (e.g. `SAFE-3`, `E-MOVE-2`) in PR descriptions.
- Fill in the PR template's safety checklist.
- Update `CHANGELOG.md` under `Unreleased` for user-visible changes.

## Translations

See [docs/TRANSLATING.md](docs/TRANSLATING.md).

## Security

Report vulnerabilities privately; see [SECURITY.md](SECURITY.md).

## Maintainers

Release signing and key custody follow [ADR 0009](docs/adr/0009-signing-and-release.md). The signing key never enters the repository or PR CI.

## Code of conduct

[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) (Contributor Covenant 2.1).
