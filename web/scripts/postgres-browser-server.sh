#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
exec ./gradlew :server:postgresBrowserServer --no-daemon
