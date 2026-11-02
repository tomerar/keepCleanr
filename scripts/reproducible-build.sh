#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# M18 / reproducible builds (research 05 §6.4): builds the unsigned `fossRelease` APK twice, from two fresh clones of
# the same commit at two different paths, without the Gradle build cache, and fails if the two APKs differ in any
# byte. F-Droid ships our own signed APK only when its build of the same tag is byte-identical apart from the
# signature, so this is the check that keeps that possible.
#
# D6 (owner review 2026-11-02): the two builds also differ in everything else a builder's machine may differ in:
# each has its **own Gradle user home** (no shared transforms, daemons or caches; downloaded artifacts are only
# read from the caller's cache, GRADLE_RO_DEP_CACHE), a different **time zone** (TZ), **locale** (LANG/LC_ALL) and
# **umask**, and runs without a daemon.
#
# Usage: scripts/reproducible-build.sh [commit]   (default: HEAD; only committed changes are built)
# Env:   KEEPCLEAR_REPRO_DIR         work directory (default: a new temporary directory; it is not deleted, for
#                                    inspection; the two Gradle homes in it are, unless KEEPCLEAR_REPRO_KEEP_HOMES=1)
#        KEEPCLEAR_REPRO_GRADLE_ARGS extra Gradle arguments for both builds (e.g. --max-workers=2)
# Needs: git, unzip, sha256sum, python3 (all on GitHub's ubuntu runners). Uses the caller's Android SDK
# (ANDROID_HOME, or the sdk.dir of this checkout's local.properties). A second locale (he_IL.UTF-8) is used when
# installed (`locale -a`); CI installs it.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
COMMIT="$(git -C "$ROOT" rev-parse "${1:-HEAD}")"
WORK="${KEEPCLEAR_REPRO_DIR:-$(mktemp -d -t keepclear-repro.XXXXXX)}"
APK_PATH="app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk"
mkdir -p "$WORK"
echo "Reproducible build check of $COMMIT in $WORK"

CALLER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_VERSION="$(sed -n 's/^distributionUrl=.*gradle-\(.*\)-\(bin\|all\)\.zip$/\1/p' "$ROOT/gradle/wrapper/gradle-wrapper.properties")"

# A second UTF-8 locale for the second build: Hebrew when installed, else whatever differs from C.UTF-8.
SECOND_LOCALE="$(locale -a 2>/dev/null | grep -iE '^(he_IL|en_US)\.utf-?8$' | sort -r | head -n 1 || true)"
if [ -z "$SECOND_LOCALE" ]; then
  SECOND_LOCALE="C.UTF-8"
  echo "Note: no second UTF-8 locale installed; both builds use C.UTF-8 (install he_IL.UTF-8 to vary it)."
fi

fresh_gradle_home() {
  local home="$1"
  rm -rf "$home"
  mkdir -p "$home"
  # The caller's init scripts and properties (repository mirrors, memory) are settings, not build outputs.
  if [ -d "$CALLER_HOME/init.d" ]; then cp -R "$CALLER_HOME/init.d" "$home/"; fi
  if [ -f "$CALLER_HOME/gradle.properties" ]; then cp "$CALLER_HOME/gradle.properties" "$home/"; fi
  # The Gradle distribution is the same zip either way; hard links save the download and the disk space.
  if [ -n "$GRADLE_VERSION" ] && ls -d "$CALLER_HOME"/wrapper/dists/gradle-"$GRADLE_VERSION"-* >/dev/null 2>&1; then
    mkdir -p "$home/wrapper/dists"
    for d in "$CALLER_HOME"/wrapper/dists/gradle-"$GRADLE_VERSION"-*; do
      cp -al "$d" "$home/wrapper/dists/" 2>/dev/null || cp -a "$d" "$home/wrapper/dists/"
    done
  fi
}

# build <name> <time zone> <locale> <Java language_COUNTRY> <umask>
build() {
  # Different directory names (and lengths) on purpose: an absolute path that leaks into the APK shows up as a diff.
  local dir="$WORK/$1"
  local home="$WORK/gradle-home-$1"
  rm -rf "$dir"
  git clone --quiet --no-hardlinks "$ROOT" "$dir"
  git -C "$dir" -c advice.detachedHead=false checkout --quiet "$COMMIT"
  if [ -f "$ROOT/local.properties" ]; then cp "$ROOT/local.properties" "$dir/"; fi
  fresh_gradle_home "$home"
  echo "== Building in $dir (TZ=$2, LANG=$3, Java locale $4, umask $5, Gradle home $home)"
  (
    cd "$dir"
    umask "$5"
    export TZ="$2" LANG="$3" LC_ALL="$3" GRADLE_USER_HOME="$home"
    # Every JVM of the build (wrapper, Gradle, Kotlin, workers) gets the locale and time zone even when the system
    # has no such locale installed; the caller's own JVM options are kept.
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.language=${4%_*} -Duser.country=${4#*_} -Duser.timezone=$2"
    # Downloaded artifacts are read from the caller's cache when it has them, never written there.
    if [ -d "$CALLER_HOME/caches/modules-2" ]; then export GRADLE_RO_DEP_CACHE="$CALLER_HOME/caches"; fi
    # shellcheck disable=SC2086 # extra arguments are meant to split
    ./gradlew --quiet --no-daemon --no-build-cache --no-configuration-cache ${KEEPCLEAR_REPRO_GRADLE_ARGS:-} \
      :app:assembleFossRelease
  )
  cp "$dir/$APK_PATH" "$WORK/$1.apk"
  if [ "${KEEPCLEAR_REPRO_KEEP_HOMES:-0}" != "1" ]; then rm -rf "$home"; fi
}

build first "UTC" "C.UTF-8" en_US 022
build second-checkout "Pacific/Kiritimati" "$SECOND_LOCALE" he_IL 077

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
