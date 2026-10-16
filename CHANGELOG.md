# Changelog

The client compiles against one Forge build and must match the host's build. Each entry names the
Forge build, its commit, and what the client had to change for it.

## Forge 2.0.16-SNAPSHOT, build 2026-10-15 (commit `0b0f7ec`)

705 Forge commits after the 2.0.15 build. The ones that touch the client are in `IGuiGame`,
`AbstractGuiGame` and `CardView`, mostly from Forge's netplay work (#12023, #12056, #12091, #12205).

**Forge API changes that broke the build**

| 2.0.15 | 2.0.16 | Client change |
|---|---|---|
| `IGuiGame.openZones(...)` returned `PlayerZoneUpdates` | `default void openZones(...)` with one argument fewer | override removed |
| `IGuiGame.restoreOldZones`, `tempShowZones`, `hideZones` | removed (zone display moved to the client, #12023) | overrides removed |
| `IGuiGame.getGamestate()` | removed | override removed |
| `CardView.hasBackSide()`, `getBackSideName()` | removed | `forge_card` uses `hasAlternateState()` and `getAlternateState().getName()` |

Many `IGuiGame` display callbacks, for example `updatePhase`, `updateStack` and `showManaPool`, are now
`default` methods. The client still overrides them, which is allowed.

**Fixes that 2.0.16 made possible**

- `AbstractGuiGame.awaitNextInput()` was `final` in 2.0.15 and is not `final` in 2.0.16. Forge now builds
  the "Waiting for …" prompt on the client (#12205). Its timer could show disabled buttons over a prompt that
  had just arrived, so the client looked as if nothing was asked. The client now overrides `awaitNextInput()`
  with an empty method.
- A race in `ClaudeGuiGame.answer`: the server can send the next prompt while an answer is still
  being applied. The client marked that new prompt as answered too, and `forge_wait` then waited forever.
  Now only the prompt version that was answered is marked.

**Other**

- `scripts/run-mcp.sh` appends the server's stderr to `build/mcp-live.log`. Read it when a game hangs.
- The default Forge folder is now `C:\Games\forge-installer-2.0.16`.

**Not used yet:** 2.0.16 adds `setSelectableTargets` and `pickStackItem` to `AbstractGuiGame`, for
targets that are players or stack items. The client may need them for spells that target a spell.

## Forge 2.0.15-SNAPSHOT, build 2026-08-14 (commit `0aa78d4`)

First version. The headless network client, the MCP tools, the test host and the autoplay test.

## How to check a new Forge build

1. `scripts/sync-forge.sh && scripts/build.sh`. Compile errors show the API changes that matter.
2. Read the build commit in the first lines of `CHANGES.txt` in the Forge folder.
3. List the Forge commits between the old and new build that touch the client's API:
   `git log --oneline <old>..<new> -- forge-gui/src/main/java/forge/gamemodes/net forge-gui/src/main/java/forge/gui/interfaces/IGuiGame.java`
4. Run the autoplay test (see `README.md`) before a game with a person.
5. Add an entry here.
