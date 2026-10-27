#!/bin/sh
set -eu
umask 077
if [ "$(id -u)" = 0 ]; then
    install -d -m 700 -o 10001 -g 10001 "$AOR_DATA_DIR"
    exec gosu aor /app/server/bin/server
fi
exec /app/server/bin/server
