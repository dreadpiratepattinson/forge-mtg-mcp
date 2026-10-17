# mtg-mcp

Claude plays Magic: The Gathering against the user in Forge. The user hosts a network game in the Forge
window on Windows; this repo's MCP server (`forge`, see `.mcp.json`) joins it as a headless Forge client.

## How it works

- `bridge/src/mtgmcp/` — Java. `McpServer` (stdio JSON-RPC) → `Session` (one connection) → `ClaudeGuiGame`
  (the client's `IGuiGame`: every Forge question becomes a `Decision`) → `Render` (text board).
- The client loads the Forge jar at run time, so it must be the **same Forge build** as the host.
  Forge sends serialized Java objects; a different build can fail.
- Hidden cards are filtered with Forge's own `mayView` rule. Never print a card that rule hides.

## Setup and update

```
scripts/bootstrap.sh    # project-local JDK 17 + Gson in .tools/ (never the system Java)
scripts/sync-forge.sh   # copy jar + res/ from C:\Games\forge-installer-2.0.16 (reads from /mnt/c are slow)
scripts/build.sh        # compile against .tools/forge
```

Run `sync-forge.sh` and `build.sh` again after each Forge update. A Claude Code restart reloads the server.

## Testing without the user

`scripts/test-host.sh <port> <ai-deck>` hosts a Commander game with the Forge AI in seat 0.
`python3 tests/autoplay.py <port> <deck> [steps]` plays it through the MCP server with a fixed policy.
Logs go to `build/`.

## Playing

1. `forge_decks`, then `forge_connect` with a deck. The host defaults to the Windows side of WSL.
2. Loop: `forge_wait` → read the DECISION → `forge_act` with its number.
3. Read card text with `forge_card` before relying on it.
4. `forge_stops` adds priority stops, e.g. the opponent's END_OF_TURN when holding instants.
   `pass_on_abilities: true` skips the many passes on triggers and activations when you have no responses.
5. Likely Forge 2.0.16 host bug (not yet reproduced on purpose): if the host clicks End Turn and so skips a block declaration against our attack,
   the host crashes at combat damage (NPE in `Combat.assignAttackersDamage`). Ask the host to click OK at blocks.

Prompt answers: `ok`, `cancel`, `undo` (required when cancel means Undo), `card <id>`, `player <index>`, joined with `;`. On a mana payment prompt,
`ok` is Forge's auto-pay. Attackers and blockers: click creatures with `card <id>`, then `ok`.
