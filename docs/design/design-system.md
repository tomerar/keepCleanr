# KeepClear design system: "Paper & Ink" (נייר ודיו)

Status: approved direction (owner review brief, 2026-11-02, PR F1). Decision record: [ADR 0013](../adr/0013-paper-and-ink-design-system.md).
Code: `core/designsystem` (theme, tokens, fonts, icons, components). Screens use only these components and tokens.

## 1. Direction

Calm and honest, like a tidy notebook, not like a "booster". White space, **one ink colour for actions**, three quiet lane colours that carry meaning (SAFE, REVIEW, MOVE), and red **only** for what can't be undone. No neon green or cyan, no rockets, brooms, shields, lightning bolts, speedometers or confetti.

The app's job is to tell the truth about a phone's storage and to never lose a memory. The visual language follows: numbers are exact and large, wording is plain, actions are few and named by their verb, and nothing pulses, flashes or counts down to create urgency.

### 1.1 Competitor study (why this direction)

Based on general knowledge of the category (no assets, screenshots or text of other apps are copied or used here):

- **Typical cleaner apps** use saturated green/cyan or electric blue on dark gradients, a big circular gauge or "boost" button, rockets, brooms, shields and lightning icons, animated particles, and alarm language ("junk detected!", "phone is overheating"). The design sells urgency and a feeling of speed; the numbers are often rounded, inflated or hidden behind "optimise".
- **Platform storage tools** (Android's own storage settings, file managers with a "clean" tab) are calm and honest but generic: grey cards, category lists, small numbers, no explanation of *why* something is safe to remove and no concept of "move memories off the phone".
- **Photo/backup apps** use thumbnails well (you recognise memories by sight, not by file name) and treat backup as reassuring, but rarely show proof that a copy was checked.

KeepClear takes the honesty of the platform tools, the thumbnails of the photo apps, and adds what neither has: lanes with meaning, measured results ("freed 7.9GB, now 24.7GB free (was 16.8GB)"), and a visible "checked file by file" stamp after a backup. It deliberately avoids the booster look: no gauge, no gradient, no "boost", no fear. Warm paper backgrounds and an ink-blue action colour make it feel like a notebook you trust, not a slot machine.

## 2. Colour tokens

Dynamic colour (Material You) is **off by default** so lane colours keep their meaning (`KeepClearTheme(dynamicColor = false)`; the parameter stays for a later opt-in, UI-1). Every Material 3 colour role is set; Material's default lavender never appears (tested).

### 2.1 Brief tokens (exact)

| Role | Light | Dark |
|---|---|---|
| background | `#F6F2EA` | `#161513` |
| surface | `#FFFDF8` | `#1E1C1A` |
| surfaceContainer (cards) | `#FBF8F1` | `#24221F` |
| onSurface (text) | `#1E1C19` | `#ECE6DC` |
| onSurfaceVariant | `#5A554D` | `#BDB5A8` |
| outline | `#8A8378` | `#8F887D` |
| outlineVariant | `#DDD6CA` | `#3A3732` |
| primary (ink, all main buttons) | `#2F4B7C` | `#A9C1EE` |
| onPrimary | `#FFFFFF` | `#10284F` |
| primaryContainer | `#DCE4F5` | `#2A3F66` |
| lane SAFE (sage) | `#3D6B4F` | `#9DCDAE` |
| lane SAFE container | `#DCEBDF` | `#24392C` |
| lane MOVE / memories (amber) | `#8A5A12` | `#E7B970` |
| lane MOVE container | `#F5E4C6` | `#3D2E14` |
| lane REVIEW (slate) | `#4D5D73` | `#B7C4D8` |
| lane REVIEW container | `#E1E6EE` | `#2C3440` |
| error (ONLY permanent, unrecoverable actions) | `#B3261E` | `#F2B8B5` |

Every brief token passed the contrast test unchanged (§2.4); none was adjusted.

### 2.2 Derived roles (not in the brief; chosen from the palette)

| Role | Light | Dark | Why |
|---|---|---|---|
| onBackground | `#1E1C19` | `#ECE6DC` | = onSurface |
| onPrimaryContainer | `#13284A` | `#DCE4F5` | deep ink / light ink, text on primaryContainer |
| inversePrimary | `#A9C1EE` | `#2F4B7C` | the other theme's primary |
| secondary | `#5B5850` | `#CFC7BA` | warm graphite: neutral selection (chips, nav indicator) never borrows a lane colour |
| onSecondary | `#FFFFFF` | `#2E2B26` | |
| secondaryContainer | `#ECE6DA` | `#3A3732` | darker paper; bottom-nav indicator, selected chips |
| onSecondaryContainer | `#1E1C19` | `#ECE6DC` | |
| tertiary / container | SAFE sage `#3D6B4F` / `#DCEBDF` | `#9DCDAE` / `#24392C` | our components never use tertiary; if a stock M3 component does, it reads as calm sage |
| onTertiary / onTertiaryContainer | `#FFFFFF` / `#10291A` | `#0E2A19` / `#DCEBDF` | |
| onError | `#FFFFFF` | `#601410` | |
| errorContainer / onErrorContainer | `#F9DEDC` / `#410E0B` | `#8C1D18` / `#F9DEDC` | |
| surfaceVariant | `#EFE9DE` | `#2E2B27` | |
| surfaceTint | = surface | = surface | no blue tint on raised paper (tonal elevation draws nothing) |
| inverseSurface / inverseOnSurface | `#33302B` / `#F6F2EA` | `#ECE6DC` / `#33302B` | snackbars |
| scrim | `#000000` | `#000000` | |
| surfaceContainerLowest | `#FFFFFF` | `#121110` | |
| surfaceContainerLow | `#FDFAF4` | `#1B1917` | |
| surfaceContainerHigh | `#F4EFE6` | `#2C2A26` | sheets, dialogs |
| surfaceContainerHighest | `#EEE8DD` | `#35322E` | |
| surfaceBright / surfaceDim | `#FFFDF8` / `#E4DED3` | `#3A3732` / `#161513` | |

### 2.3 `KeepClearColors` (lane extension, `KeepClearTheme.colors`)

| Token | Light | Dark | Use |
|---|---|---|---|
| safe / safeContainer / onSafeContainer | `#3D6B4F` / `#DCEBDF` / `#10291A` | `#9DCDAE` / `#24392C` / `#DCEBDF` | "Clean now" lane |
| review / reviewContainer / onReviewContainer | `#4D5D73` / `#E1E6EE` / `#151E2B` | `#B7C4D8` / `#2C3440` / `#E1E6EE` | "Choose what to delete" lane (H2a) |
| move / moveContainer / onMoveContainer | `#8A5A12` / `#F5E4C6` / `#2B1A00` | `#E7B970` / `#3D2E14` / `#F5E4C6` | "Move to backup" lane, memories |
| stripUsed | `#8A8378` | `#8F887D` | StorageStrip: used by everything else (= outline) |
| stripFree | `#DDD6CA` | `#3A3732` | StorageStrip track: free space (= outlineVariant) |

Lane colour is used for the lane's icon, its strip segment and its accent; text on a lane container uses `onXContainer` (or the lane colour for a short label). **A lane is never shown by colour alone**: always icon + label (§9).

### 2.4 Contrast targets (tested: `ContrastTest`, pure JVM WCAG 2.x relative luminance)

| Pair | Target | Light | Dark |
|---|---|---|---|
| onSurface / surface, background, surfaceContainer | ≥ 4.5 | 16.7 / 15.2 / 16.0 | 13.7 / 14.7 / 12.8 |
| onSurfaceVariant / surface, background, surfaceContainer | ≥ 4.5 | 7.3 / 6.6 / 7.0 | 8.4 / 9.0 / 7.8 |
| onPrimary / primary | ≥ 4.5 | 8.7 | 8.0 |
| primary (text buttons, links) / surface, background | ≥ 4.5 | 8.5 / 7.8 | 9.3 / 10.0 |
| lane / its container | ≥ 4.5 | SAFE 5.0, MOVE 4.7, REVIEW 5.4 | 6.9, 7.2, 7.1 |
| lane / surface, background, surfaceContainer | ≥ 4.5 | ≥ 5.3 | ≥ 8.7 |
| error / surface, background | ≥ 4.5 | 6.4 / 5.9 | 9.9 / 10.7 |
| outline, stripUsed (non-text) / surface, background | ≥ 3 | 3.7 / 3.4 | 4.8 / 5.2 |
| every on-colour / its container (M3 pairs) | ≥ 4.5 | tested | tested |

If a token ever fails, it is changed minimally, the change and its numbers are written here, and it is logged as a "Decided conservatively" decision in `docs/progress.md`. As of F1a, no token needed a change.

`stripFree` (`outlineVariant`) is under 3:1 on purpose: it is the track, not information. The strip's segments are separated by 2dp gaps and the strip always has a text description and a legend, so no meaning depends on the track's edge.

## 3. Typography

Fonts are bundled (`core/designsystem/src/main/res/font`, SIL OFL 1.1, THIRD_PARTY.md); no downloadable fonts (they need Play services).

- **Rubik** (Latin + Hebrew): headings, the hero number, every size and count. Numbers use `fontFeatureSettings = "tnum"` so digits don't jump while counting.
- **Heebo** (Hebrew + Latin): body and UI text.
- Only the weights used are bundled, as **static instances** of the official variable fonts (fontTools `varLib.instancer`): Rubik 400/500/600, Heebo 400/500 (about 750 KB). With the variable files, the `wght` axis was not applied in the screenshot renderer (every weight drew as Regular), and whether it is applied to resource fonts differs between Android versions; static files draw the same weight everywhere.

| Style | Font | Size / line | Weight | Use |
|---|---|---|---|---|
| `KeepClearTheme.type.hero` | Rubik, tnum | 52sp / 60sp | SemiBold 600 | the free-space number |
| `KeepClearTheme.type.number` | Rubik, tnum | 16sp / 24sp | Medium 500 | sizes and counts inside text rows |
| displaySmall / headlineLarge | Rubik | 36sp / 32sp | Medium | rare large headings |
| headlineMedium / headlineSmall | Rubik | 28sp / 24sp | Medium | |
| titleLarge | Rubik | 22sp / 28sp | Medium 500 | screen and card titles |
| titleMedium / titleSmall | Rubik | 18sp / 16sp | Medium | section titles |
| bodyLarge | Heebo | 16sp / 24sp | Regular 400 | body |
| bodyMedium / bodySmall | Heebo | 14sp / 13sp (lines 20/18) | Regular | secondary text |
| labelLarge | Heebo | 14sp / 20sp | Medium 500 | buttons, chips |
| labelMedium / labelSmall | Heebo | 13sp / 12sp | Medium | small labels |

200% font scale: no fixed heights around text; the hero number wraps instead of clipping (it has no `maxLines`), sheets scroll, buttons grow. Tested for HeroNumber and ConfirmSheet.

## 4. Shape and spacing

- Cards 28dp (`KcShapes.card`), hero container 32dp (`KcShapes.hero`), buttons full pill (`CircleShape`), chips 12dp (`KcShapes.chip`), sheet top corners 28dp, thumbnails 12dp.
- 8dp grid: `KcSpacing.xs 4`, `s 8`, `m 16`, `l 20`, `xl 24`, `xxl 32`. Card padding 20dp, hero padding 24dp. Touch targets ≥ 48dp.
- Cards are flat paper: `surfaceContainer` with a 1dp `outlineVariant` border, no shadow.

## 5. Icons

Material Symbols **Rounded** (weight 400, fill 0, 24px), Apache-2.0, from `google/material-design-icons` (`symbols/android/<name>/materialsymbolsrounded/`). Only the ones used are bundled as vector drawables (`kc_ic_*`) and exposed as `KcIcons.*`; directional ones are `autoMirrored`.

| Name | Meaning | Mirrored |
|---|---|---|
| `eco` (leaf) | SAFE lane, "Clean now" | |
| `photo_library` | REVIEW lane, "Choose what to delete" | |
| `inventory_2` (box) | MOVE lane, memories, "Move to backup" | |
| `auto_delete` (bin with clock) | Trash: 30 days | |
| `task_alt` | verified (VerifiedStamp) | |
| `settings` | Settings | |
| `apps` | Apps tab | |
| `arrow_back` | back | yes |
| `chevron_right` | "more" / open | yes |
| `undo` | Undo in ResultBanner | yes |
| `check_circle` | selected thumbnail | |
| `info` | InfoChip ("why is this here?") | |
| `schedule` | DaysLeftChip | |
| `close` | close a sheet or notice | |
| `expand_more` | collapsed card ("see more files") | |
| `image`, `movie`, `music_note`, `description`, `draft` | a grid tile's type when it has no picture (photo, video, audio, document, other; H1c) | |
| `search` | search field (H1c) | |
| `grid_view`, `view_list` | switch between the grid and the list (H1c) | |

Every icon that is a control has a content description; decorative icons next to a text label have none (the label speaks).

## 6. Motion

- Stable Compose animation APIs only (`animateFloatAsState`, `Animatable`, `spring`, `AnimatedVisibility`).
- Springs, subtle: `dampingRatio = 1` (no bounce), `stiffness = StiffnessMediumLow` for layout, `StiffnessLow` for counts.
- Two "moments" only: (1) after freeing space, the hero number counts up and the StorageStrip slides; (2) after a verified backup, the VerifiedStamp fades/scales in once.
- **Reduced motion:** `Settings.Global.ANIMATOR_DURATION_SCALE` is read once when the theme starts and provided as `LocalReducedMotion` (`KeepClearTheme.reducedMotion`). When it is 0 ("Remove animations"), every component jumps to its end state with no animation. Tests cover both paths.
- Nothing loops, pulses or counts down.

## 7. Components (`core/designsystem`, prefix `Kc` where a Material name exists)

All components wrap stable Material 3 (1.4.x) so Expressive can be swapped in later (ADR 0013). Text is always passed in by the app (strings live in `core:i18n`); the design system has no strings of its own.

| Component | Purpose | Anatomy | States / rules |
|---|---|---|---|
| **KcButton** | Every button | pill; `Primary` = ink filled (onPrimary text), `Tonal` = secondaryContainer, `Text` = ink text, `Destructive` = error filled, `OutlinedDestructive` = error outline and label, no fill (design QA 2026-11-02) | Destructive and OutlinedDestructive are **only** for permanent, unrecoverable actions (empty trash, release now); OutlinedDestructive is for a permanent action that isn't the screen's main one (the Trash's "Empty the trash" at the bottom), its confirmation stays red. No parentheses in labels: a size goes in the label („לפנות 31.0GB מהטלפון”), a count on a line under the button (ButtonLabelsTest). Recoverable actions ("to the trash, 30 days") use Primary. Optional leading icon; min height 48dp; disabled at 38% |
| **KcCard** | Paper card | surfaceContainer, 28dp corners, 1dp outlineVariant border, 20dp padding; optional `onClick` | Clickable cards are one target with role Button |
| **LaneCard** | One lane on Home / Backup | lane icon in its container circle + title (lane name) + count + bytes (Rubik tnum) + optional ThumbStrip + chevron | Lane = icon + label, never colour alone; one merged TalkBack label "title, count, bytes"; `loading` shows a text placeholder, never "0" |
| **StorageStrip** | Where the space is | 14dp (default, 16dp max) rounded bar; segments used / SAFE / REVIEW / MOVE / free, filling from the **start** side (right in Hebrew); a ghost segment shows what remains used "after cleaning" | 2dp gaps between segments; segments animate (spring) when values change, jump under reduced motion; one `contentDescription` passed in by the app (e.g. "240GB in total: 16.8GB free, 7.9GB can be cleaned now…") | Design QA 2026-11-02: segments in the order SAFE, REVIEW, MOVE, other used, free from the start side, each as wide as its share of the capacity; **StorageLegend** lists the same segments in the same order (colour dot, lane name „לנקות” / „לבחור” / „לגבות” / „אחר” / „פנוי”, size), so both mirror together; tested to 1 px against the drawn spans (`ComponentsTest`).
| **HeroNumber** | The free space as the biggest thing on screen | number (Rubik SemiBold 52sp tnum) + label ("free"), then one sentence, then one ink pill button (slots) | Counts up from the previous value (spring, ~600ms) when it changes; no animation under reduced motion; wraps at 200% font, never clips; heading semantics |
| **ConfirmSheet** | Every confirmation of a destructive action | bottom sheet: title (heading), what / how many / how much (number style) / where it goes, scrollable body slot (editable list), the confirm button slot + cancel | Sheet-like dialog anchored to the bottom (Material's ModalBottomSheet is still experimental in 1.4.x). `permanent = false` (default) uses ink; `permanent = true` uses error red and says it can't be undone. The confirm slot takes the app's `SafeConfirmButton` (tapjacking delay and obscured-touch check, SEC-7), whose `permanent` has no default (F2, C79). Scrolls at 200% font; a long editable list is a bounded lazy list inside it (C89) | Design QA 2026-11-02: the title is only the question („לנקות 7.9GB?”), the details are the rows; during its 500 ms delay `SafeConfirmButton` fills like a progress bar from the start side (label readable on the 16 % track and on the fill, `ContrastTest`) instead of looking disabled, with no click action until it is ready; under reduced motion it is an unfilled track until ready. Screenshots are taken once it is ready.
| **InfoSheet** | Explainers (F1b) | the same bottom-anchored sheet frame as ConfirmSheet: handle, heading title, scrolling body, optional primary button + dismiss | Opens from what was tapped (a card's button, an InfoChip), never at the top of a screen; scrolls at 200% font |
| **PanelSheet** | A long list or grid in a sheet (H1c: restore) | the same bottom-anchored frame: handle, heading title (also the TalkBack pane title), a body that doesn't scroll by itself so it can hold a lazy grid, primary button + dismiss | Material's ModalBottomSheet is still experimental (C137); the app keeps anything that must stay in view (free space) outside the grid and lets it scroll at 200 % |
| **MediaTile** | One file in a grid (Trash, Restore; H1c) | square 12dp-rounded picture or type icon on surfaceContainerHigh, date (2 lines max), size (Rubik tnum), optional note; selected = 3dp ink ring + `check_circle` | One TalkBack node: the app's description (name, date, size) and custom actions (Restore, Preview, …); as a checkbox when selectable; a long press can preview. Grids use adaptive columns of at least `MEDIA_TILE_MIN` (104dp) |
| **MonthHeader** | A month in a grid (H1c) | titleMedium heading, always the whole row | Wraps only between words at 200 % (never inside a FlowRow, the „אוקטוב/ר” bug) |
| **KcSearchField** | Local search (H1c) | one-line outlined pill field, `search` icon, clear button once something is typed | The placeholder gives an example („למשל יולי 2021”) |
| **ResultBanner** | The measured result after an action | sage-tinted (or neutral) banner: icon + one or two lines ("Freed 7.9GB · now 24.7GB free (was 16.8GB)") + Undo text button, and (F2) an optional second text action ("Empty now") | Polite live region; Undo only when the action can be undone; dismiss button. A clean to the trash says what moved and when the space comes back, never a measured change (C80) |
| **DaysLeftChip** | Days until a trashed item is deleted | 12dp chip, `schedule` icon + text ("23 days left") | ≤ 3 days uses the MOVE amber container (attention, not alarm); never red |
| **ThumbStrip** | 3–4 real thumbnails on a REVIEW/MOVE card | row of up to 4 square 12dp-rounded thumbnails + "+N" tile | The app supplies `Painter`s (it owns thumbnail loading); decorative for TalkBack (the card's label carries the count) |
| **VerifiedStamp** | Proof after a backup | ink outline stamp, slightly rotated (−4°), `task_alt` + "Checked file by file" | Appears once with a fade/scale (skipped under reduced motion); the paper-slip backup summary card hosts it |
| **InfoChip** | "Why is this here?" | 12dp assist-style chip with `info` icon | Opens an explainer bottom sheet next to what was tapped; role Button |
| **ConfirmSheet `risk`** (H2a) | The one line about what can't be undone | error-red bodyLarge line right under the title, before the rows; a fade in the sheet's colour above the sticky buttons while more of the body is below | Only for a permanent loss (other apps' bins); every other line stays neutral |
| **BalancedChipRow** (H2a) | A wrapping row of choices (age chips) | one line when they fit; else an even split that leaves no choice alone; else one per line | Mirrors in RTL (`placeRelative`); pure split `BalancedLines` is unit-tested |
| **WholeWordsAutoSize** (H2a) | Short labels in narrow places (rail) | a `TextAutoSize` that steps down until the text fits and wraps only between words | Never below the label's size at 100 % font |
| **IntroIllustration** (H2a) | The intro's three pictures | line drawings on a 160 × 100 grid in theme tokens only: the strip getting shorter (SAFE ghost dashed), photos with check circles (REVIEW), photos into a USB stick with a ring stamp (MOVE) | Decorative for TalkBack; mirrored in RTL; lane colour always with a shape |

## 8. Signature elements

1. **StorageStrip** (above), under the hero on Home and on the Backup tab.
2. **HeroNumber**: "16.8GB free" + one sentence ("7.9GB can be freed safely") + one ink pill button.
3. **ThumbStrip** on every REVIEW/MOVE group card: you recognise memories by sight, not by file name.
4. **DaysLeftChip** on every trash item/group.
5. **Backup summary as a paper slip**: a KcCard with a dashed tear edge look (surfaceContainerLowest, 28dp), totals in Rubik tnum, and a VerifiedStamp.

## 9. Accessibility

- Contrast: text ≥ 4.5:1, large numbers, icons and outlines ≥ 3:1, in light and dark (tested, §2.4).
- 200% font: no clipped numbers, sheets scroll, buttons grow (tested for HeroNumber and ConfirmSheet; UI-3 screenshots in F3).
- Lanes always icon + label; colour is never the only signal (WCAG 1.4.1).
- Screen and card titles have heading semantics; HeroNumber is a heading.
- Every icon-only control has a label; check rows are one toggleable target with a label.
- Touch targets ≥ 48dp. Reduced motion honoured (§6).

## 10. Units and numbers

- Decimal units like Android Settings: 1 GB = 1000³ bytes. **One formatter**: `core:common` `ByteSize.format` (one decimal from 1 GB up, locale digits; Hebrew writes "16.8GB" without a space; the result is bidi-isolated).
- Counts use plurals resources; sizes and counts render in Rubik tnum.

## 11. RTL

- Layout uses start/end only; directional icons are auto-mirrored (`arrow_back`, `chevron_right`, `undo`).
- StorageStrip fills from the start side (right in Hebrew; tested).
- File names, paths and numbers are bidi-isolated (`Bidi.isolate…`); sizes from `ByteSize` are already isolated.
- Swipe directions are fixed by meaning, not by layout direction (F2).

## 12. Navigation and information architecture (F1b, after PR E)

Implemented in F1b (UI-4; decisions C72–C78). Components: `MainTabs`, `MainTopBar`, `SubScreenTopBar` (app, `Navigation.kt`) on stable `NavigationBar`/`NavigationRail`; explainers use `InfoSheet` (core:designsystem), which shares `ConfirmSheet`'s bottom-anchored frame. The plan:

- **Top app bar** on every main tab: small "KeepClear" wordmark, a Trash chip with its size (shown whenever the trash isn't empty), a Settings gear. Sub-screens: top app bar with an auto-mirrored back arrow and a title (no "לחזור" text link). Screen titles have heading semantics.
- **Bottom navigation**, 3 tabs (NavigationRail on medium/expanded widths):
  1. **"לפנות מקום" (Free up space):** HeroNumber + StorageStrip + SAFE primary button (opens the ConfirmSheet directly; "see details" link), REVIEW LaneCard, MOVE LaneCard (jumps to tab 2), "app temporary files" card, and **one** collapsed "see more files" card holding the permission steps (all files, WhatsApp folder). Dismissible notices (cloned apps) at the bottom.
  2. **"גיבוי" (Backup):** move memories (sources by year), "restore from backup" (works without a scan), configured backup places, history of backup summaries (paper slips).
  3. **"אפליקציות" (Apps):** unused apps, by size, one-at-a-time cache list, app folders, cloned-apps note.
- **Settings:** language, how to get files back, kept by you, reminder, intro, About + privacy. **Advanced ("מתקדם"):** test mode, backup places setup (computer at home/SMB, Nextcloud), device check (test builds).
- Explainers open as bottom sheets next to what was tapped, not at the top of Home.

## 13. Screens (F2)

Implemented in F2 (UI-5; decisions C79–C95): every screen uses these components; see ux-flows §0 "F2". The backup summary is a paper slip (`PaperSlip` in the app: a `KcCard` on `surfaceContainerLowest` with a dashed tear line) holding the `VerifiedStamp` ("Checked file by file") whenever copies were checked. Problem lines use the neutral container, never `error` (C94).
