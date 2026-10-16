# mtg-mcp

Play Magic: The Gathering against Claude in [Forge](https://github.com/Card-Forge/forge).
You host a network game in the Forge window. Claude joins it through an MCP server that runs a
headless Forge client, so Forge's own rules engine runs the whole game.

## How to play

1. In Forge, open **Online Multiplayer** and host a game. The default port is 36743.
2. Set the game type (for example Commander) and choose your deck. Leave a seat open.
3. Start Claude Code in this folder and approve the `forge` MCP server.
4. Ask Claude to join your game, and name the deck it plays (a file in your Forge deck folder).
5. When Claude's seat shows ready, start the game.

The first time you host, Windows can ask if Java may use the network. Allow it on public networks too,
because Windows counts the WSL network as public.

## First-time setup

```
scripts/bootstrap.sh    # downloads JDK 17 and Gson into .tools/ — the system Java is not touched
scripts/sync-forge.sh   # copies the Forge jar and res/ from C:\Games\forge-installer-2.0.15
scripts/build.sh        # compiles the client against that Forge build
```

## After a Forge update

The client must use the same Forge build as the host. After you update Forge, run:

```
scripts/sync-forge.sh && scripts/build.sh
```

Then restart Claude Code. Set `FORGE_INSTALL` if Forge is in a different folder.

## Settings

| Variable | Default | Use |
|---|---|---|
| `FORGE_INSTALL` | `/mnt/c/Games/forge-installer-2.0.15` | Forge folder that `sync-forge.sh` copies |
| `FORGE_DECKS` | `%APPDATA%\Forge\decks` on WSL, else `~/.forge/decks` | deck folder Claude reads |
| `FORGE_HOST` | the Windows host of WSL | address of the Forge host |

## Test without a human player

```
scripts/test-host.sh 36800 "<path to AI deck>.dck" &
python3 tests/autoplay.py 36800 "Eldrazi Incursion [M3C] [2024]" 150
```

`test-host.sh` hosts a game with the Forge AI in seat 0. `autoplay.py` joins it and plays with a simple
fixed policy. This tests the connection, not the play. Logs go to `build/`.

## MCP tools

| Tool | Does |
|---|---|
| `forge_decks` | list deck files |
| `forge_connect` | join the hosted game with a deck |
| `forge_wait` | wait for the next decision; returns events, chat, board and the decision |
| `forge_act` | answer a decision |
| `forge_state` | the full board |
| `forge_card` | rules text of visible cards |
| `forge_stops` | phases where Claude wants priority |
| `forge_chat` | send a chat message |
| `forge_game` | concede, next game, quit, disconnect |

Claude sees only what your opponent would see in the Forge window. The client hides cards with
Forge's own visibility rule.
