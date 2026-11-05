#!/usr/bin/env bash
set -euo pipefail

# Forge 1.7.10 / RetroFuturaGradle build.
# Build configuration lives in:
#   - settings.gradle   (RFG plugin repository, toolchain resolver, project name)
#   - gradle.properties (mod version, deploy folder, OpenComputers version, JDK 25)
#   - build.gradle      (Minecraft/Forge, MCP mappings, validation, reobf, deploy)
#
# Usage:
#   ./build.sh                              # full build (validate + test + reobf + deploy)
#   ./gradlew compileJava                   # compile against MCP-named Minecraft
#   ./gradlew test                          # unit tests
#   ./gradlew validate                      # structural + JS syntax checks
#   ./gradlew reobfJar                      # production (SRG-named) jar
#   ./gradlew clean build

cd "$(dirname "$0")"

# RetroFuturaGradle 2.x runs on Gradle 9.2 and requires a Java 25 daemon.
if [ -d /usr/lib/jvm/graalvm-jdk-25 ]; then
  export JAVA_HOME=/usr/lib/jvm/graalvm-jdk-25
  export PATH="$JAVA_HOME/bin:$PATH"
fi

exec ./gradlew build "$@"
