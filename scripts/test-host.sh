#!/usr/bin/env bash
# Host a test Commander game: Forge AI in seat 0, seat 1 open. Usage: test-host.sh <port> <ai-deck>
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$FORGE_HOME"
exec "$JAVA_HOME/bin/java" -Djava.awt.headless=true -Xmx2g -cp "$CP" mtgmcp.TestHost "$@"
