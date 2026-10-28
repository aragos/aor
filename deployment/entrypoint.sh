#!/bin/sh
set -eu
umask 077
exec /app/server/bin/server
