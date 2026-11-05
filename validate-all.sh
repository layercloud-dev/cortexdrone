#!/usr/bin/env bash
set -euo pipefail

# Structural Java layout, line-limit and JavaScript syntax validation.
# The actual validation logic lives in the Gradle `validate` task.
#
# Usage:
#   ./validate-all.sh                     # validate (structural + JS syntax, via Gradle)
#   ./gradlew validate                    # same
#   ./gradlew validate --offline          # offline mode allowed once dependencies are cached

cd "$(dirname "$0")"

# RetroFuturaGradle 2.x runs on Gradle 9.2 and requires a Java 25 daemon.
if [ -d /usr/lib/jvm/graalvm-jdk-25 ]; then
  export JAVA_HOME=/usr/lib/jvm/graalvm-jdk-25
  export PATH="$JAVA_HOME/bin:$PATH"
fi

exec ./gradlew validate "$@"
