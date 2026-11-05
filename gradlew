#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
WRAPPER_HOME="$GRADLE_USER_HOME/wrapper/dists/gradle-8.14-bin"
GRADLE_BIN=""

# Locate cached Gradle in wrapper/dists (layout: dists/gradle-X-bin/<hash>/gradle-X/bin/gradle)
for d in "$WRAPPER_HOME"/*/gradle-8.14/bin/gradle; do
  [ -x "$d" ] && GRADLE_BIN="$d" && break
done

if [ -z "$GRADLE_BIN" ]; then
  echo "Downloading Gradle 8.14 (supports Java 24; falls back to running on GraalVM JDK 25 with toolchain target 8)..."
  mkdir -p "$WRAPPER_HOME"
  TMP="$(mktemp -d)"
  curl -sL https://services.gradle.org/distributions/gradle-8.14-bin.zip -o "$TMP/gradle.zip"
  unzip -q "$TMP/gradle.zip" -d "$WRAPPER_HOME"
  rm -f "$TMP/gradle.zip"
  rmdir "$TMP"
  for d in "$WRAPPER_HOME"/*/gradle-8.14/bin/gradle; do
    [ -x "$d" ] && GRADLE_BIN="$d" && break
  done
fi

cd "$DIR"
exec "$GRADLE_BIN" "$@"
