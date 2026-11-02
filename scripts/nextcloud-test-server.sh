#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# W4: starts a throwaway Nextcloud (the official Docker image, SQLite, one admin user) on localhost for the
# WebDAV/Nextcloud target's real-server tests (backup/target-webdav, NextcloudServerTest). Used by CI and by
# developers; needs Docker. Plain http is fine: it only listens on 127.0.0.1, and KeepClear allows http only to
# the phone itself (WebDavLocation).
# Prints the environment variables the tests read; source them with: eval "$(scripts/nextcloud-test-server.sh)"
# Stop it with: docker rm -f keepclear-nextcloud
set -euo pipefail

# Pinned by digest (Nextcloud 31.0.14, Apache, linux/amd64 index as pulled on 2026-11-02): the tag alone moves.
IMAGE="${KEEPCLEAR_NEXTCLOUD_IMAGE:-nextcloud:31-apache@sha256:07ec73cc816e58d6f45a162cd53ef886462c29271a23fc68d0124cec276e3767}"
NAME="${KEEPCLEAR_NEXTCLOUD_NAME:-keepclear-nextcloud}"
PORT="${KEEPCLEAR_NEXTCLOUD_PORT:-8089}"
ADMIN="kcadmin"
PASSWORD="kc-test-only-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p "127.0.0.1:$PORT:80" \
  -e SQLITE_DATABASE=nextcloud \
  -e NEXTCLOUD_ADMIN_USER="$ADMIN" \
  -e NEXTCLOUD_ADMIN_PASSWORD="$PASSWORD" \
  -e NEXTCLOUD_TRUSTED_DOMAINS="localhost 127.0.0.1" \
  "$IMAGE" >/dev/null

occ() { docker exec -u www-data "$NAME" php occ "$@"; }

# The image installs Nextcloud on first start (a minute or two); status.php says when it is done.
installed=""
for _ in $(seq 1 300); do
  if curl -fsS "http://127.0.0.1:$PORT/status.php" 2>/dev/null | grep -q '"installed":true'; then
    installed=yes
    break
  fi
  sleep 1
done
if [ -z "$installed" ]; then
  docker logs --tail 50 "$NAME" >&2 || true
  echo "Nextcloud did not finish installing" >&2
  exit 1
fi
# The entrypoint may still be writing the config right after status.php flips; wait until occ answers.
for _ in $(seq 1 60); do
  if occ status --output=json 2>/dev/null | grep -q '"installed":true'; then break; fi
  sleep 1
done

# Tests send wrong passwords on purpose; brute-force throttling would slow every later request down.
occ config:system:set auth.bruteforce.protection.enabled --type=boolean --value=false >/dev/null
occ config:system:set ratelimit.protection.enabled --type=boolean --value=false >/dev/null
# Background jobs by cron, so no web request runs them in the middle of a test.
occ background:cron >/dev/null

# An app password, as a user of KeepClear would make one (Settings > Security). Falls back to the login password
# on images whose occ has no command for it.
APP_PASSWORD=""
for cmd in user:auth-tokens:add user:add-app-password; do
  if out=$(docker exec -u www-data -e NC_PASS="$PASSWORD" "$NAME" php occ "$cmd" --password-from-env "$ADMIN" 2>/dev/null); then
    APP_PASSWORD=$(printf '%s\n' "$out" | grep -Eo '[A-Za-z0-9]{72}' | tail -n 1 || true)
    if [ -n "$APP_PASSWORD" ]; then break; fi
  fi
done
if [ -z "$APP_PASSWORD" ]; then APP_PASSWORD="$PASSWORD"; fi

echo "export KEEPCLEAR_NEXTCLOUD_URL=http://127.0.0.1:$PORT"
echo "export KEEPCLEAR_NEXTCLOUD_USER=$ADMIN"
echo "export KEEPCLEAR_NEXTCLOUD_PASSWORD=$APP_PASSWORD"
echo "export KEEPCLEAR_NEXTCLOUD_CONTAINER=$NAME"
