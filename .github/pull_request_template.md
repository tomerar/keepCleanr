## What and why

<!-- Short description. Link the issue and the requirement IDs (e.g. SAFE-3, E-MOVE-2). -->

## Milestone

<!-- e.g. M2 (docs/spec/tasks.md) -->

## Safety checklist

- [ ] No new deletion/move/rename path outside `core:safety` / trash / storage implementations
- [ ] Every user-file deletion goes to the trash
- [ ] PROTECTED paths unaffected (or the set only grows, with tests)
- [ ] Destructive changes include unit tests (+ kill-point tests where relevant)
- [ ] No new permission, network access or dependency (or: PRIVACY.md and ADRs updated)

## Product checklist

- [ ] Strings in English and Hebrew (or "needs Hebrew" below)
- [ ] RTL and dark mode checked (screenshots attached for UI changes)
- [ ] CHANGELOG.md updated (user-visible changes)
- [ ] Spec updated if behaviour differs from docs/spec

## Needs Hebrew

<!-- List string keys still needing Hebrew, if any. -->
