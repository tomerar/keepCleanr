#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# M15: starts a throwaway Samba server on localhost for the SMB target's integration tests
# (backup/target-smb). Used by CI and by developers; needs the `samba` package and root (or sudo).
# Prints the environment variables the tests read; source them with: eval "$(scripts/samba-test-server.sh)"
set -euo pipefail

PORT="${KEEPCLEAR_SMB_PORT:-4445}"
DIR="${KEEPCLEAR_SMB_DIR:-/tmp/keepclear-smb}"
USER_NAME="kctest"
PASSWORD="kc-test-only-$(date +%s)"
SUDO=""
if [ "$(id -u)" != "0" ]; then SUDO="sudo"; fi

$SUDO mkdir -p "$DIR/share" "$DIR/drop" "$DIR/run" "$DIR/lock" "$DIR/state" "$DIR/cache" "$DIR/private"
id "$USER_NAME" >/dev/null 2>&1 || $SUDO useradd -M -s /usr/sbin/nologin "$USER_NAME"
$SUDO chown "$USER_NAME" "$DIR/share" "$DIR/drop"

CONF="$DIR/smb.conf"
$SUDO tee "$CONF" >/dev/null <<CONF
[global]
  smb ports = $PORT
  server min protocol = SMB2_10
  server signing = mandatory
  pid directory = $DIR/run
  lock directory = $DIR/lock
  state directory = $DIR/state
  cache directory = $DIR/cache
  private dir = $DIR/private
  ncalrpc dir = $DIR/run/ncalrpc
  binddns dir = $DIR/private
  log file = $DIR/log.%m
  # Enough detail to tell why an SMB test failed (CI uploads these logs on failure), bounded in size.
  log level = 3
  max log size = 20000
  map to guest = never
  disable netbios = yes
  interfaces = lo
  bind interfaces only = yes
  load printers = no

[keepclear]
  path = $DIR/share
  read only = no
  valid users = $USER_NAME
  create mask = 0644

# Only for the test that makes the server drop a share: closing it must not disturb tests running in parallel.
[keepclear-drop]
  path = $DIR/drop
  read only = no
  valid users = $USER_NAME
  create mask = 0644
CONF

printf '%s\n%s\n' "$PASSWORD" "$PASSWORD" | $SUDO smbpasswd -c "$CONF" -s -a "$USER_NAME" >/dev/null
$SUDO pkill -f "smbd.*$CONF" 2>/dev/null || true
$SUDO smbd -s "$CONF" -D

for _ in $(seq 1 50); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then break; fi
  sleep 0.2
done

echo "export KEEPCLEAR_SMB_HOST=127.0.0.1"
echo "export KEEPCLEAR_SMB_PORT=$PORT"
echo "export KEEPCLEAR_SMB_SHARE=keepclear"
echo "export KEEPCLEAR_SMB_DROP_SHARE=keepclear-drop"
echo "export KEEPCLEAR_SMB_USER=$USER_NAME"
echo "export KEEPCLEAR_SMB_PASSWORD=$PASSWORD"
echo "export KEEPCLEAR_SMB_CONF=$CONF"
