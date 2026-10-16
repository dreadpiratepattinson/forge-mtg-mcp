#!/usr/bin/env bash
# Download a project-local JDK and Java libraries into .tools/. Touches nothing outside the repo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
JDK_MAJOR=17
GSON_VERSION=2.11.0
mkdir -p "$TOOLS/lib"

if [ ! -x "$TOOLS/jdk/bin/java" ]; then
    echo "Downloading Temurin JDK $JDK_MAJOR"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/jdk.tar.gz" \
        "https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p "$tmp/x" && tar -xzf "$tmp/jdk.tar.gz" -C "$tmp/x"
    rm -rf "$TOOLS/jdk" && mv "$tmp/x"/jdk-* "$TOOLS/jdk"
    rm -rf "$tmp"
fi
"$TOOLS/jdk/bin/java" -version

gson="$TOOLS/lib/gson-$GSON_VERSION.jar"
if [ ! -f "$gson" ]; then
    curl -fsSL -o "$gson" \
        "https://repo1.maven.org/maven2/com/google/code/gson/gson/$GSON_VERSION/gson-$GSON_VERSION.jar"
fi
echo "Tools ready in $TOOLS"
