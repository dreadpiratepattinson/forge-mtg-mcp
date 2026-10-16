package mtgmcp;

import forge.deck.Deck;
import forge.game.GameType;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;

/**
 * Hosts a Commander game for testing: the Forge AI in seat 0, seat 1 open for a network client.
 * Usage: TestHost <port> <ai-deck>
 */
public final class TestHost {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Session.initForge();
        Deck aiDeck = Session.loadDeck(args[1]);

        FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        ServerGameLobby lobby = new ServerGameLobby();
        server.setLobby(lobby);
        lobby.applyVariant(GameType.Commander);

        LobbySlot ai = lobby.getSlot(0);
        ai.setType(LobbySlotType.AI);
        ai.setName("ForgeAI");
        ai.setDeck(aiDeck);
        ai.setIsReady(true);
        LobbySlot open = lobby.getSlot(1);
        open.setType(LobbySlotType.OPEN);
        open.setIsReady(false);
        Log.info("TestHost listening on port " + port + " with AI deck " + aiDeck.getName());

        while (!(lobby.getSlot(1).getType() == LobbySlotType.REMOTE && lobby.getSlot(1).isReady())) {
            Thread.sleep(200);
        }
        Log.info("client ready: " + lobby.getSlot(1).getName() + " with " + lobby.getSlot(1).getDeck().getName());
        Thread.sleep(500);
        Runnable start = lobby.startGame();
        if (start == null) {
            Log.info("startGame() returned null; deck validation failed?");
            System.exit(1);
        }
        start.run();
        Log.info("game started");
    }
}
