#!/usr/bin/env bash
set -euo pipefail
cd /root/forge-smp/cortexdrone-src
mkdir -p classes
rm -rf classes/*

mapfile -t JAVA_SOURCES < <(find src -name "*.java" | sort)

/usr/lib/jvm/graalvm-jdk-25/bin/javac --release 8 -Xlint:-options \
    -cp "/root/forge-smp/layercloud-smp-src/srg_gen/mc-srg.jar:/root/forge-smp/mods/OpenComputers-1.12.70-GTNH.jar:/root/forge-smp/lwjgl3ify-forgePatches.jar:/root/forge-smp/libraries/com/google/guava/guava/17.0/guava-17.0.jar" \
    -d classes \
    "${JAVA_SOURCES[@]}"

if [ -d resources ]; then
    cp -r resources/. classes/
fi

/usr/lib/jvm/graalvm-jdk-25/bin/jar cf /tmp/opencode/CortexDrone-1.0.0.jar \
    -C classes . \
    -C /root/forge-smp/cortexdrone-src mcmod.info

mv -f /tmp/opencode/CortexDrone-1.0.0.jar /root/forge-smp/mods/CortexDrone-1.0.0.jar
echo "Built and deployed /root/forge-smp/mods/CortexDrone-1.0.0.jar successfully."
