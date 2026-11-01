# ADR 0012: Accessibility "Clear cache" automation: foss only, "Later", behind gates

Status: Proposed (needs owner decision) · Date: 2026-11-01
Relates to: [ADR 0008](0008-no-accessibility-no-network.md) (stays in force), [ADR 0004](0004-privilege-tiers-shizuku-optional.md), [ADR 0005](0005-flavors-play-foss.md)

## Context
Without Shizuku/root (SAFE-7, now "Later"), KeepClear cannot clear an app's *internal* cache ([09 §1.2](../research/09-accessibility-cache-cleaner.md)):
- SAFE-5 (`allocateBytes`) frees only cache above a reserve of 2–10 % of the disk (AOSP `computeStorageCacheBytes`). On the owner's 240 GB, 93 %-full phone, the predicted yield for 3.85 GB of cache is ≈ 0 (D7-3 will measure it).
- SAFE-6 (`ACTION_CLEAR_APP_CACHE`) clears external caches only.
- Cleaners such as SD Maid SE and Cache Cleaner (`com.github.bmx666.appcachecleaner`, AGPL-3.0: approach studied, no code reused) use an AccessibilityService to open App info → Storage and tap "Clear cache" for each app.

The costs ([09 §1.4–3](../research/09-accessibility-cache-cleaner.md)):
- "Clear storage" sits next to "Clear cache" on AOSP, and the MIUI flow starts at "Clear data", so a misclick means permanent data loss. Some apps keep offline maps in their cache.
- Labels and layouts differ by OEM, Android version and locale. The reference app needed about one fix per Android release.
- Sideloaded installs must pass "Allow restricted settings" (Android 13+), and Android 17 Advanced Protection blocks the service.
- Play lists cleaners as non-tools that need a declaration plus a video. F-Droid has no anti-feature for this.

## Decision
- **v1: no AccessibilityService in any flavor** (ADR 0008 unchanged). **Never in `play`.**
- v1 ships a **guided manual** flow instead: deep links to each app's App info, sorted by cache size; the user taps "Clear cache" and KeepClear shows the measured delta.
- **"Later" candidate, `foss` only, opt-in, off by default**, built only when all four gates hold:
  - (a) device data shows a typical residual internal cache of at least 1 GB after the guided flow ships;
  - (b) the owner accepts the maintenance cost;
  - (c) the safeguards and tests in [09 §4.2/§5](../research/09-accessibility-cache-cleaner.md) land in the same PR;
  - (d) the build-logic exception described below is in place.
- Mandatory safeguards if built:
  - **Taps:** the service clicks only the node matching the Settings app's runtime `clear_cache_btn_text`. Any deny label (`clear_user_data_text`, `dlg_delete`, `force_stop`, `clear_data_dlg_title`) nearby stops the run. It never taps in a dialog, so there is no MIUI flow. No user-typed or bundled labels.
  - **Activation:** the service is inert outside a user-started foreground run, with a stop overlay and per-app before/after measurement.
  - **Calibration:** a mandatory dry run whenever the device fingerprint changes (Settings version, Android build, locale).
  - **Selection:** nothing pre-selected, and SAFETY-7 labelling applies.
  - **Supported phones:** start with AOSP-like Settings and OxygenOS only; every other ROM is "not supported" until it has fixtures and a device check.
- **Build-logic exception:**
  - `ManifestPolicy` keeps failing `play` on any `BIND_ACCESSIBILITY_SERVICE` service.
  - In `foss` it allows exactly one allowlisted, non-exported service and checks its config: `packageNames` limited to Settings, no gestures, no `isAccessibilityTool`.
  - `INTERNET` stays forbidden.
  - Konsist confines `performAction` to the foss automation module.

## Consequences
- v1 stays simple and Play-safe; its cache story becomes honest: "Android keeps a cache reserve. Open the biggest apps and tap Clear cache."
- If the gates are ever met, accepting this ADR also amends CLAUDE.md's "No AccessibilityService" product rule, `requirements.md` (a new SAFE-* item) and `docs/PRIVACY.md`. Each supported ROM then becomes ongoing maintenance with its own device check.
- The pure-JVM matcher plus recorded node-tree fixtures make the riskiest logic testable without devices.

## Alternatives
- **Build it now (foss):** the most convenient option, but it brings a critical data-loss mode, restricted-settings friction and per-OEM upkeep for about 1–4 GB that regrows within days. Rejected for v1.
- **Ship it in `play` as well:** cleaners are not accessibility tools under Play policy; it would need a declaration and video and adds account risk. Rejected permanently.
- **Shizuku (SAFE-7):** the same result via an API with no UI taps, but users must pair over ADB. If SAFE-7 returns to the plan, it likely makes this ADR unnecessary.
- **Do nothing beyond SAFE-5/6:** safe, but it misses internal caches entirely; the guided manual flow fills that gap at no risk.
