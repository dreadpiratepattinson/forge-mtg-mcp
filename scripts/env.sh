# Shared paths. Source this file; do not run it.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORGE_HOME="${FORGE_HOME:-$ROOT/.tools/forge}"
FORGE_JAR="$(ls "$FORGE_HOME"/forge-gui-desktop-*-jar-with-dependencies.jar | head -1)"
JAVA_HOME="$ROOT/.tools/jdk"
CP="$FORGE_JAR:$(ls "$ROOT"/.tools/lib/*.jar | tr '\n' ':')${MCP_CLASSES:-$ROOT/build/classes}"
