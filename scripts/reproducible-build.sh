#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# M18 / reproducible builds (research 05 §6.4): builds the unsigned `fossRelease` APK twice, from two fresh clones of
# the same commit at two different paths, without the Gradle build cache, and fails if the two APKs differ in any
# byte. F-Droid ships our own signed APK only when its build of the same tag is byte-identical apart from the
# signature, so this is the check that keeps that possible.
#
# Usage: scripts/reproducible-build.sh [commit]   (default: HEAD; only committed changes are built)
# Env:   KEEPCLEAR_REPRO_DIR  work directory (default: a new temporary directory; it is not deleted, for inspection)
# Needs: git, unzip, sha256sum, python3 (all on GitHub's ubuntu runners). Uses the caller's Android SDK
# (ANDROID_HOME, or the sdk.dir of this checkout's local.properties).
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
COMMIT="$(git -C "$ROOT" rev-parse "${1:-HEAD}")"
WORK="${KEEPCLEAR_REPRO_DIR:-$(mktemp -d -t keepclear-repro.XXXXXX)}"
APK_PATH="app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk"
mkdir -p "$WORK"
echo "Reproducible build check of $COMMIT in $WORK"

build() {
  # Different directory names (and lengths) on purpose: an absolute path that leaks into the APK shows up as a diff.
  local dir="$WORK/$1"
  rm -rf "$dir"
  git clone --quiet --no-hardlinks "$ROOT" "$dir"
  git -C "$dir" -c advice.detachedHead=false checkout --quiet "$COMMIT"
  if [ -f "$ROOT/local.properties" ]; then cp "$ROOT/local.properties" "$dir/"; fi
  echo "== Building in $dir"
  (cd "$dir" && ./gradlew --quiet --no-build-cache --no-configuration-cache :app:assembleFossRelease)
  cp "$dir/$APK_PATH" "$WORK/$1.apk"
}

build first
build second-checkout

A="$WORK/first.apk"
B="$WORK/second-checkout.apk"
sha256sum "$A" "$B"
if cmp -s "$A" "$B"; then
  echo "Reproducible: both builds of $COMMIT are byte-identical."
  exit 0
fi

echo "NOT reproducible: the two APKs differ. Entries that differ (name, size, sha256):" >&2
python3 -I - "$A" "$B" >&2 <<'PY'
import hashlib, sys, zipfile

def entries(path):
    with zipfile.ZipFile(path) as z:
        return [(i.filename, i.file_size, i.date_time, i.external_attr, hashlib.sha256(z.read(i)).hexdigest())
                for i in z.infolist()]

a, b = entries(sys.argv[1]), entries(sys.argv[2])
da, db = {e[0]: e for e in a}, {e[0]: e for e in b}
for name in sorted(set(da) | set(db)):
    ea, eb = da.get(name), db.get(name)
    if ea != eb:
        print(f"  {name}\n    first:  {ea[1:] if ea else 'missing'}\n    second: {eb[1:] if eb else 'missing'}")
if [e[0] for e in a] != [e[0] for e in b]:
    print("  Entry order differs.")
PY
echo "Hint: compare single entries with 'unzip -p <apk> <entry> | sha256sum'; dex differences usually come from" >&2
echo "R8 or code generation, assets/dexopt/baseline.prof from profile compilation." >&2
exit 1
