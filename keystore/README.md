# Test-build signing key (not secret)

`debug-test.keystore` signs **debug/test builds only** (local and the CI test APK), so that each new test
APK can update the previous one on a phone. Its passwords are public (`android` / alias `androiddebugkey`),
like Android's standard debug key.

It must never sign a release. Release signing follows [ADR 0009](../docs/adr/0009-signing-and-release.md):
one owner-held key, never in this repository. A phone with a test build must uninstall it (after emptying or
restoring KeepClear's trash) before installing a release build.
