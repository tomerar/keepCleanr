#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes docs/hebrew-review.md: every Hebrew string with its key, the English, the screen it appears on and its
review status.

Screens come from where the code uses the string (R.string.<key> / R.plurals.<key>), mapped from the source file
and, for files that hold several screens, from the function around the use.

Status compares each Hebrew string with docs/hebrew-review-reviewed.json, the text as the owner last reviewed it:
"reviewed" (unchanged), "changed" (different since that review) or "new" (not there at that review). Changed and new
strings are flagged as new since the last review.

Run from the repository root:
  python3 scripts/hebrew-review.py                  writes docs/hebrew-review.md
  python3 scripts/hebrew-review.py --check          fails when docs/hebrew-review.md is stale (CI)
  python3 scripts/hebrew-review.py --mark-reviewed  after the owner's review: the current texts become the baseline
"""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "core/i18n/src/main/res"
OUT = ROOT / "docs/hebrew-review.md"
REVIEWED = ROOT / "docs/hebrew-review-reviewed.json"
CODE_DIRS = [ROOT / "app/src", ROOT / "core", ROOT / "engine", ROOT / "backup"]

# Source file → screen (in the order screens appear in the app). Unknown files fall back to "Other".
SCREENS = [
    ("Intro and permissions", ["IntroScreen", "MediaAccess", "Onboarding"]),
    ("Top app bar and tabs", ["Navigation"]),
    ("Home", ["HomeScreen", "HomeViewModel", "UiText"]),
    ("Clean now", ["SafeScreen", "SafeConfirm"]),
    ("Review (categories, groups, item preview)", ["ReviewScreens", "RuleText", "UserChoices"]),
    ("Swipe review", ["SwipeReview"]),
    # MainActivity's own strings are the notification question before a backup and the shared backup summary.
    ("Move to backup and restore", ["MoveScreen", "MoveSupport", "MoveViewModel", "RestoreUi", "NetworkDrive",
                                    "OpenedTarget", "MoveNotifications", "MoveController", "MoveWorker",
                                    "MainActivity"]),
    ("Trash", ["TrashScreen", "TrashSupport"]),
    ("Apps", ["AppsScreen", "AppsScreens", "AppsSource"]),
    ("Settings", ["SettingsScreen", "TestMode"]),
    # PR H3: Help, "What KeepClear did", About, Report a problem, the crash card, What's new.
    ("Help, history and About", ["HelpScreen", "HistoryScreen", "History", "AboutScreen", "ReportProblem",
                                 "CrashReport"]),
    ("Nextcloud set-up (Settings › Advanced)", ["Nextcloud"]),
    ("Notifications", ["SpaceReminder", "Notification"]),
    ("Device check (test builds)", ["DeviceCheck"]),
]


# Key prefixes win over the source file (some files hold several screens, e.g. the intro lives in SettingsScreen.kt).
PREFIXES = [
    ("Intro and permissions", ["intro_", "explainer_", "allfiles_", "access_", "whatsapp_", "media_"]),
    ("Top app bar and tabs", ["tab_"]),
    ("Move to backup and restore", ["move_", "restore_", "backup_place"]),
    ("Trash", ["trash_"]),
    ("Apps", ["apps_", "app_"]),
    ("Swipe review", ["swipe_"]),
    ("Notifications", ["reminder_text", "reminder_title", "reminder_channel"]),
    ("Settings", ["settings_", "reminder_setting", "test_mode", "choice_kept", "choice_junk_list"]),
    ("Nextcloud set-up (Settings › Advanced)", ["nextcloud_"]),
    ("Help, history and About", ["help_", "history_", "about_", "report_", "crash_", "whats_new_"]),
    ("Launcher shortcuts", ["shortcut_"]),
    ("Device check (test builds)", ["debug_device_check"]),
]


# Files that hold several screens: the function around the use decides (e.g. the intro lives in SettingsScreen.kt).
FUNCTION_SCREENS = {
    "IntroScreen": "Intro and permissions",
    "TestModeBanner": "Top app bar and tabs",
}

# Files that only route between screens (AppScreens) say nothing about where a string shows; other uses decide.
ROUTERS = ["AppScreens"]

STATUS_REVIEWED, STATUS_CHANGED, STATUS_NEW = "reviewed", "changed", "new"


def screen_of_key(key: str):
    for name, prefixes in PREFIXES:
        if any(key.startswith(p) for p in prefixes):
            return name
    return None


def screen_of(path: pathlib.Path, function=None):
    stem = path.stem
    if function in FUNCTION_SCREENS:
        return FUNCTION_SCREENS[function]
    if any(stem.startswith(r) for r in ROUTERS):
        return None
    for name, prefixes in SCREENS:
        if any(stem.startswith(p) for p in prefixes):
            return name
    return "Other"


def text_of(el) -> str:
    raw = "".join(el.itertext()).strip()
    return raw.replace("\\'", "'").replace('\\"', '"').replace("\\n", " ⏎ ")


def load(path, text=None) -> dict:
    out = {}
    root = ET.fromstring(text) if text is not None else ET.parse(path).getroot()
    for el in root:
        name = el.get("name")
        if el.tag == "string":
            out[name] = text_of(el)
        elif el.tag == "plurals":
            out[name] = " / ".join(f"[{i.get('quantity')}] {text_of(i)}" for i in el)
    return out


# Keys whose every use sits inside a FUNCTION_SCREENS function (filled by usages()). That function is more specific
# than the key's prefix: the trial-mode banner shows above every screen, not in Settings where its switch is (F3).
FUNCTION_ONLY: dict = {}


def usages() -> dict:
    found: dict = {}
    by_function: dict = {}
    pattern = re.compile(r"R\.(?:string|plurals)\.([a-zA-Z0-9_]+)")
    function = re.compile(r"^(?:internal |private |public )?fun (?:[\w.<>, ]+\.)?(\w+)\(", re.M)
    for d in CODE_DIRS:
        for f in sorted(d.rglob("*.kt")):
            if "/build/" in str(f) or "/test/" in str(f) or "/androidTest/" in str(f):
                continue
            text = f.read_text(encoding="utf-8")
            starts = [(m.start(), m.group(1)) for m in function.finditer(text)]
            for m in pattern.finditer(text):
                around = None
                for start, name in starts:
                    if start > m.start():
                        break
                    around = name
                found.setdefault(m.group(1), set()).add(screen_of(f, around))
                by_function.setdefault(m.group(1), []).append(FUNCTION_SCREENS.get(around))
    # PR H3: strings used only by resource XML (the launcher's shortcuts).
    for f in sorted((ROOT / "app/src").rglob("res/xml/*.xml")):
        for m in re.finditer(r"@string/([a-zA-Z0-9_]+)", f.read_text(encoding="utf-8")):
            found.setdefault(m.group(1), set()).add("Launcher shortcuts")
    for key in found:
        found[key].discard(None)
    FUNCTION_ONLY.clear()
    for key, screens in by_function.items():
        if all(screens) and len(set(screens)) == 1:
            FUNCTION_ONLY[key] = screens[0]
    return {k: v for k, v in found.items() if v}


def reviewed() -> dict:
    if not REVIEWED.exists():
        return {}
    return json.loads(REVIEWED.read_text(encoding="utf-8"))["strings"]


def status_of(key: str, he: str, baseline: dict) -> str:
    if key not in baseline:
        return STATUS_NEW
    return STATUS_REVIEWED if baseline[key] == he else STATUS_CHANGED


def status_cell(status: str) -> str:
    # The flag is words, not colour or an emoji: easy to search for ("new since last review").
    return status if status == STATUS_REVIEWED else f"**{status}** (new since last review)"


def cell(s: str) -> str:
    return s.replace("|", "\\|")


def render() -> str:
    order = [name for name, _ in SCREENS] + ["Launcher shortcuts", "Other", "Explanations (why / if deleted)",
                                             "Not used in code"]
    # PR H3: help.xml holds the offline Help's long texts.
    en = {**load(RES / "values/strings.xml"), **load(RES / "values/explanations.xml"), **load(RES / "values/help.xml")}
    he = {**load(RES / "values-iw/strings.xml"), **load(RES / "values-iw/explanations.xml"),
          **load(RES / "values-iw/help.xml")}
    explanation_keys = set(load(RES / "values/explanations.xml"))
    used = usages()
    baseline = reviewed()
    groups: dict = {}
    for key in sorted(he):
        if key in explanation_keys:
            screen, also = "Explanations (why / if deleted)", ""
        elif key in used:
            screens = sorted(used[key], key=order.index)
            first = FUNCTION_ONLY.get(key) or screen_of_key(key) or screens[0]
            screen, also = first, ", ".join(x for x in screens if x != first)
        else:
            screen, also = "Not used in code", ""
        status = status_of(key, he[key], baseline)
        groups.setdefault(screen, []).append((key, en.get(key, "(no English)"), he[key], also, status))
    lines = [
        "# Hebrew strings for review",
        "",
        "Generated by `scripts/hebrew-review.py` from `core/i18n/src/main/res`; do not edit by hand (CI checks it is",
        "current). Fix a string in `values-iw/strings.xml` or `values-iw/explanations.xml`, then run the script again.",
        "",
        "Rules: gender-neutral (plural or infinitive), plain words, no Latin \"Android\", exact numbers. `%1$s`, `%2$d`",
        "and similar are placeholders filled by the app; ⏎ is a line break. The screen is where the code uses the",
        "string; \"also\" lists other screens.",
        "",
        "Status compares each string with `docs/hebrew-review-reviewed.json` (the texts at the owner's last review):",
        "**new** and **changed** strings are new since the last review; `reviewed` ones are unchanged. After a review,",
        "`python3 scripts/hebrew-review.py --mark-reviewed` makes the current texts the new baseline.",
        "",
    ]
    statuses = [row[4] for rows in groups.values() for row in rows]
    flagged = sum(1 for x in statuses if x != STATUS_REVIEWED)
    lines += [
        f"{len(he)} Hebrew strings; {flagged} new since the last review "
        f"({statuses.count(STATUS_NEW)} new, {statuses.count(STATUS_CHANGED)} changed).",
        "",
    ]
    lines.append("## Contents")
    lines.append("")
    for screen in order:
        if screen in groups:
            anchor = re.sub(r"[^a-z0-9 -]", "", screen.lower()).replace(" ", "-")
            new = sum(1 for row in groups[screen] if row[4] != STATUS_REVIEWED)
            suffix = f", {new} new since last review" if new else ""
            lines.append(f"- [{screen}](#{anchor}) ({len(groups[screen])}{suffix})")
    for screen in order:
        if screen not in groups:
            continue
        lines += ["", f"## {screen}", "", "| Key | English | עברית | Also | Status |", "|---|---|---|---|---|"]
        for key, e, h, also, status in groups[screen]:
            lines.append(f"| `{key}` | {cell(e)} | {cell(h)} | {also} | {status_cell(status)} |")
    store = ROOT / "fastlane/metadata/android"
    if (store / "iw-IL").is_dir():
        lines += ["", "## Store listing (F-Droid / IzzyOnDroid)", "", "| File | English | עברית |", "|---|---|---|"]
        for f in sorted((store / "iw-IL").rglob("*.txt")):
            rel = f.relative_to(store / "iw-IL")
            en_file = store / "en-US" / rel
            e = en_file.read_text(encoding="utf-8").strip() if en_file.exists() else ""
            h = f.read_text(encoding="utf-8").strip()
            lines.append(f"| `{rel}` | {cell(e).replace(chr(10), '<br>')} | {cell(h).replace(chr(10), '<br>')} |")
    return "\n".join(lines) + "\n"


def mark_reviewed() -> None:
    he = {**load(RES / "values-iw/strings.xml"), **load(RES / "values-iw/explanations.xml")}
    data = {
        "note": "Hebrew texts as the owner last reviewed them; written by scripts/hebrew-review.py --mark-reviewed.",
        "strings": dict(sorted(he.items())),
    }
    REVIEWED.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def main() -> int:
    if "--mark-reviewed" in sys.argv:
        mark_reviewed()
    text = render()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print("docs/hebrew-review.md is stale: run python3 scripts/hebrew-review.py", file=sys.stderr)
            return 1
        return 0
    OUT.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
