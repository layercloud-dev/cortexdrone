#!/usr/bin/env bash
set -euo pipefail

# Legacy standalone validator — the structural / compile / JS checks now
# live in the Gradle `validate` task (build.gradle).
# This script is kept for compatibility and now delegates to Gradle.
# Build configuration lives in:
#   - settings.gradle   (project name)
#   - gradle.properties (forgeDir, layercloudDir, modsDir, versions)
#   - build.gradle      (source layout src/main/java, src/main/resources; validation; jar; deploy)
#
# Usage:
#   ./validate-all.sh                     # validate (structural + JS syntax, via Gradle)
#   ./gradlew validate                    # same
#   ./gradlew validate --offline          # offline mode allowed

cd "$(dirname "$0")"

# Gradle 8.14 requires JDK 17+ to run (not Java 25). Prefer JDK 21 if available.
if [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
fi

exec ./gradlew validate "$@"
