#!/usr/bin/env bash
# Compile the bridge against the installed Forge jar.
set -euo pipefail
source "$(dirname "$0")/env.sh"
rm -rf "$ROOT/build/classes" && mkdir -p "$ROOT/build/classes"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$ROOT/build/classes" $(find "$ROOT/bridge/src" -name '*.java')
echo "Built against $(basename "$FORGE_JAR") ($(cat "$FORGE_HOME/build.txt"))"
