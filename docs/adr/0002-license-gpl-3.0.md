# ADR 0002: License GPL-3.0-or-later

Status: Accepted (2026-10-31) · Date: 2026-10-31

## Context
The repo already ships the GPL-3.0 text. The richest prior art is GPL-3.0 (SD Maid SE, WhatsAppCleaner, CleanSweep) or GPL-3.0-or-later (AppManager/AppManagerNG). Permissive code (Clearo MIT, Shizuku-API Apache-2.0/MIT, smbj Apache-2.0) can be combined into a GPL-3.0 work. A user-trust product benefits from a strong copyleft: forks must stay open, so "KeepClear with trackers" cannot be closed-source.

## Decision
- Code: **GPL-3.0-or-later**. Add SPDX headers (`// SPDX-License-Identifier: GPL-3.0-or-later`) to source files.
- Reused code keeps its original copyright line plus a `NOTICE`/`THIRD_PARTY.md` entry naming the source file URL and license.
- Never copy: AGPL-only code (Neo-Backup), GPL-2.0-only files (check each AppManager file header), non-code assets that upstream excludes (SD Maid SE icons, mascots, docs, screenshots), or upstream translations.
- Our own icons/artwork: free license (CC-BY-SA-4.0 or GPL) so F-Droid does not flag NonFreeAssets.

## Consequences
- Can't be relicensed proprietary; OK for an ad-free, no-tracking app.
- Google Play allows GPL apps; F-Droid prefers it.
- Dependencies must be GPL-3.0-compatible (Apache-2.0, MIT, BSD, LGPL OK; no proprietary SDKs).

## Alternatives
- Apache-2.0/MIT: would forbid copying GPL code from SD Maid SE etc. and allows closed forks.
