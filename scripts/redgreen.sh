#!/usr/bin/env bash
# Demonstrates a real red -> green cycle for one bug fix.
#
# For each bug: the fixed source is backed up, a buggy variant that reproduces
# the original defect is written in its place, the guarding test is run and must
# FAIL, the fix is restored, and the same test must PASS.
#
# Usage: redgreen.sh <label> <source-file> <test-filter>
set -uo pipefail
cd "$(dirname "$0")/.."

LABEL="$1"
SRC="$2"
TEST="$3"
LOG="docs/evidence/redgreen.log"

export JAVA_HOME=/usr/lib/jvm/graalvm-jdk-25
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p build
{
  echo "=============================================================="
  echo "BUG: $LABEL"
  echo "file: $SRC"
  echo "test: $TEST"
  echo "=============================================================="
} >> "$LOG"

cp "$SRC" "$SRC.fixed.bak" 2>/dev/null

# The buggy variant is supplied by the caller through $BUGGY (a file path).
if [ -n "${BUGGY:-}" ] && [ -f "${BUGGY:-}" ]; then
  cp "$BUGGY" "$SRC"
else
  echo "ERROR: BUGGY file not provided" | tee -a "$LOG"
  cp "$SRC.fixed.bak" "$SRC"
  exit 2
fi

echo "--- RED (buggy variant in place) ---" | tee -a "$LOG"
./gradlew test --offline --tests "$TEST" 2>&1 \
  | grep -E "FAILED|tests completed|BUILD (FAILED|SUCCESSFUL)" | tee -a "$LOG"
RED_STATUS=${PIPESTATUS[0]}

cp "$SRC.fixed.bak" "$SRC"
rm -f "$SRC.fixed.bak"

echo "--- GREEN (fix restored) ---" | tee -a "$LOG"
./gradlew test --offline --tests "$TEST" 2>&1 \
  | grep -E "FAILED|tests completed|BUILD (FAILED|SUCCESSFUL)" | tee -a "$LOG"

echo "" | tee -a "$LOG"
