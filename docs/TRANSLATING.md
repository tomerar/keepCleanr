# Translating KeepClear

KeepClear ships **English and Hebrew** from day one. Other languages are welcome later.

## Where strings live

- `core/i18n/src/main/res/values/strings.xml` (English, source of truth) and `values-iw/` (Hebrew; Android maps `he` and `iw`).
- Plurals in `plurals.xml` (Hebrew uses `one`, `two`, `other`; Android Lint rejects `many` for Hebrew).
- Store texts in `fastlane/metadata/android/<locale>/`.

## Workflow (proposed: Hosted Weblate)

1. The English source is edited only in PRs to this repository.
2. [Hosted Weblate](https://hosted.weblate.org/) (free for libre projects, subject to their approval) watches `main`, offers new strings to translators, and opens PRs back to the repo.
3. A maintainer reviews every translation PR. CI checks key parity, placeholders (`%1$s`, `%d`) and plural forms.
4. **Safety-critical strings** (anything with keys under `confirm_*`, `why_*`, `if_deleted_*`, `trash_*`, `move_receipt_*`) need review by a second fluent speaker before release, because a mistranslation there can make someone delete the wrong thing.

Until Weblate is approved and connected, translate directly in PRs.

## Style for Hebrew

- Gender-neutral: plural imperative or infinitive ("למחוק", "בחרו").
- "סל המחזור" for trash, "מטמון" (with a plain explanation) for cache, "גיבוי" for backup.
- Write sizes as `1.4GB` / `412MB` (Latin, no space). Numbers, file names and paths are wrapped in bidi isolates by the code, so don't add RLM/LRM marks manually in translations unless a reviewer asks.
- No fear words, no "boost", no exclamation marks in warnings.

## Adding a new language

Open an issue first. A language ships only when 100% of safety-critical strings are translated and reviewed.
