# Test-build signing key (not secret)

`debug-test.keystore` signs **debug/test builds only** (local and the CI test APK), so that each new test
APK can update the previous one on a phone. Its passwords are public (`android` / alias `androiddebugkey`),
like Android's standard debug key.

It must never sign a release. Release signing follows [ADR 0009](../docs/adr/0009-signing-and-release.md):
one owner-held key, never in this repository.

Since review PR B, debug/test builds are a **separate app**: `io.github.tomerar.keepclear.test`, shown as
"KeepClear test". Because the key is public, anyone could sign an APK with it; with a different package name such an
APK can never replace a real (release) install.

**An existing test install from before this change** (package `io.github.tomerar.keepclear`) is not updated by the
new test APK; both apps appear side by side. Before uninstalling the old one: open it, then **restore or empty its
trash** (`.KeepClearTrash` on each volume). Its trash files stay on the phone after uninstall, and the new app finds
them but treats them as untrusted (its own Keystore key differs), so it offers them only for restore into
`Download/KeepClear Restored/` or for "Empty now".
