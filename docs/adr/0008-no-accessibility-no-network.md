# ADR 0008: No AccessibilityService and no INTERNET permission in v1

Status: Accepted (2026-10-31) · Date: 2026-10-31

## Context
SD Maid SE clears other apps' caches without root by automating Settings taps via AccessibilityService, with per-ROM label tables (~18 ROMs). Play says cleaners are not accessibility tools (extra disclosure, declaration, video); Android 13+ "restricted settings" block it for sideloaded installs; Android 17 Advanced Protection blocks it entirely ([05](../research/05-play-policy-and-flavors.md), [01](../research/01-sdmaid-se.md)). The owner's payoff is small (3.85 GB cache, regenerated within days).

The product promise is "no network except user-chosen backup targets". SAF-based targets (USB-C drive, SD card, any DocumentsProvider) need no INTERNET permission. SMB/WebDAV do.

## Decision
- No AccessibilityService in any flavor through v1. Cache clearing = Shizuku/root, `ACTION_CLEAR_APP_CACHE` (foss), or per-app deep links to the system App Info screen.
- MVP ships **without INTERNET**. When SMB/WebDAV arrive (v1), INTERNET is added with: network calls only from the `backup-target-*` modules, only to the host the user typed, a lint/CI rule forbidding OkHttp/URL use elsewhere, and a visible "Network: only to <host>" line in Settings.

## Consequences
- Weaker cache clearing without Shizuku; honest explanation instead ("Android regenerates cache anyway").
- A strong, checkable privacy claim for MVP.

## Alternatives
- Accessibility like SD Maid SE: higher rejection and breakage risk, low GB payoff.
