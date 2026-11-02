# ADR 0013: "Paper & Ink" design system on stable Material 3

Status: Accepted (owner review brief, 2026-11-02) · Date: 2026-11-02

## Context
Before F1 the app looked like an unstyled Material template: every card the same grey, no icons, a huge Latin title, a small free-space number and a "לחזור" text link instead of a back arrow. Typical cleaner apps look like "boosters" (neon green/cyan, rockets, brooms, shields, alarm copy), which contradicts KeepClear's promise of calm, exact and honest numbers. The owner chose the "Paper & Ink" direction in the review brief of 2026-11-02 (PR F1). Full specification: [docs/design/design-system.md](../design/design-system.md).

## Decision
- **Colours:** warm paper backgrounds (`#F6F2EA` / `#161513`), one ink colour for every main action (`#2F4B7C` / `#A9C1EE`), three lane colours with meaning: SAFE sage, REVIEW slate, MOVE amber, each with a container. Red (`error`) only for permanent, unrecoverable actions. Every Material 3 role is set (no default lavender); derived roles are documented in the design system. Lane colours are a `KeepClearColors` extension (`KeepClearTheme.colors`).
- **Contrast is a test:** text ≥ 4.5:1 and large numbers/icons/outlines ≥ 3:1 in light and dark (`ContrastTest`, pure JVM WCAG luminance). A failing token is changed minimally and logged as a "decided conservatively" item.
- **Dynamic colour off by default.** Material You would recolour the ink and, through the scheme, make lane meaning depend on the wallpaper. The theme keeps a `dynamicColor` parameter for a later opt-in that must still pass the contrast rule (UI-1).
- **Fonts:** Rubik (headings, the hero number, every size and count, with tabular figures `tnum`) and Heebo (body, UI), both variable fonts bundled under SIL OFL 1.1. No downloadable fonts: they need Google Play services, which KeepClear doesn't use.
- **Icons:** Material Symbols Rounded (Apache-2.0) as vector drawables, only the ones used, directional ones auto-mirrored.
- **Stable Material 3 only (1.4.x from the Compose BOM).** Material 3 Expressive ships in material3 1.5.0, still beta, with parts marked experimental. We don't add a beta library to an app whose safety depends on predictable UI behaviour (confirm buttons, sheets). Every screen uses our own wrappers (`KcButton`, `KcCard`, `LaneCard`, `StorageStrip`, `HeroNumber`, `ConfirmSheet`, `ResultBanner`, `DaysLeftChip`, `ThumbStrip`, `VerifiedStamp`, `InfoChip`), so Expressive can be swapped in later in one module.
- **Motion:** stable Compose animation APIs, subtle springs, two moments (free-space count-up with the strip sliding; the "verified" stamp). `ANIMATOR_DURATION_SCALE = 0` turns all motion off.
- **Units:** decimal (1 GB = 1000³ bytes) through the single `ByteSize` formatter.

## Consequences
- About 480 KB of fonts in the APK; all screenshots change once (intended).
- Material's `ModalBottomSheet` is still experimental in 1.4.x, so `ConfirmSheet` is a sheet-like dialog anchored to the bottom until it is stable.
- Wallpaper-based colours are not available until the opt-in exists.

## Alternatives
- Material 3 Expressive (1.5.0 beta): richer shapes and motion, but beta and partly experimental.
- Default Material baseline / dynamic colour only: generic, and lane colours lose their meaning.
- A typical cleaner look (neon, gauge, rockets): contradicts the product principles (calm, exact, no fear).
- System fonts only: no reliable tabular figures in Hebrew UIs and inconsistent across OEMs.
