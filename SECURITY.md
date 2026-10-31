# Security policy

KeepClear deletes and moves people's files, so security bugs can mean lost photos or chats. We take reports seriously.

## Supported versions

Only the latest release (and `main`) receives security fixes. KeepClear is in early development; no release exists yet.

## Reporting a vulnerability

**Please do not open a public issue.** Report privately through GitHub:
https://github.com/tomerar/keepCleanr/security/advisories/new

Include: affected version/flavor (`foss` or `play`), device and Android version, steps to reproduce, and impact. A proof-of-concept file (crafted zip, sidecar, manifest) is very welcome.

What to expect:
- Acknowledgement within 7 days.
- An assessment and a fix plan within 30 days for confirmed issues.
- Credit in the release notes if you want it.

## What we especially want to hear about

See the [threat model](docs/spec/threat-model.md). In particular:
- Any way to make KeepClear delete, move or overwrite a file without the user's confirmation.
- Any way to reach PROTECTED paths (WhatsApp `Databases/`, `Backups/`, `*.crypt*`, …).
- Restore path traversal via trash sidecars or backup manifests.
- Archive (Zip Slip) issues, exported components, tapjacking.
- Any network traffic from a build that should have none.

## Out of scope

Attacks that require root, a compromised OS, or an unlocked phone in the attacker's hands.
