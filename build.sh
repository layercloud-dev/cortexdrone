#!/usr/bin/env bash
set -euo pipefail

# Legacy manual javac build — replaced by the standard Gradle build.
# This script is kept for compatibility and now delegates to Gradle.
# Build configuration lives in:
#   - settings.gradle   (project name)
#   - gradle.properties (forgeDir, layercloudDir, modsDir, versions)
#   - build.gradle      (source layout src/main/java, src/main/resources; validation; jar; deploy)
#
# Usage:
#   ./build.sh                              # full build (validate + jar + deploy)
#   ./gradlew jar                           # just the jar
#   ./gradlew validate                      # just validation
#   ./gradlew clean build

cd "$(dirname "$0")"

# Gradle 8.14 requires JDK 17+ to run (not Java 25). Prefer JDK 21 if available.
if [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
fi

exec ./gradlew build "$@"
