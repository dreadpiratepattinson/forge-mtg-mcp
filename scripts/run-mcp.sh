#!/usr/bin/env bash
# Start the Forge MCP server on stdio. Claude Code runs this from .mcp.json.
set -euo pipefail
source "$(dirname "$0")/env.sh"
# In WSL NAT mode, the Windows host (where the Forge window runs) is the default gateway.
export FORGE_HOST="${FORGE_HOST:-$(ip route show default | awk '{print $3; exit}')}"
# The Windows Forge keeps its decks in %APPDATA%\Forge\decks.
if [ -z "${FORGE_DECKS:-}" ] && command -v cmd.exe > /dev/null; then
    appdata="$(cd /mnt/c && cmd.exe /c 'echo %APPDATA%' 2> /dev/null | tr -d '\r')"
    [ -n "$appdata" ] && export FORGE_DECKS="$(wslpath "$appdata")/Forge/decks"
fi
cd "$FORGE_HOME"   # Forge finds res/ relative to the working directory
exec "$JAVA_HOME/bin/java" -Djava.awt.headless=true -Xmx2g -cp "$CP" mtgmcp.McpServer 2>>"${MCP_LOG:-$ROOT/build/mcp-live.log}"
