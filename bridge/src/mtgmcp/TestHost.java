package mtgmcp;

import forge.deck.Deck;
import forge.game.GameType;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;

/**
 * Hosts a Commander game for testing: the Forge AI in seat 0, seat 1 open for a network client.
 * With "-" as the AI deck, both seats are open for network clients.
 * Usage: TestHost <port> <ai-deck|->
 */
public final class TestHost {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Session.initForge();
        boolean twoClients = "-".equals(args[1]);
        Deck aiDeck = twoClients ? null : Session.loadDeck(args[1]);

        FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        ServerGameLobby lobby = new ServerGameLobby();
        server.setLobby(lobby);
        lobby.applyVariant(GameType.Commander);

        if (twoClients) {
            for (int i = 0; i < 2; i++) {
                lobby.getSlot(i).setType(LobbySlotType.OPEN);
                lobby.getSlot(i).setIsReady(false);
            }
            Log.info("TestHost listening on port " + port + " with two open seats");
        } else {
            LobbySlot ai = lobby.getSlot(0);
            ai.setType(LobbySlotType.AI);
            ai.setName("ForgeAI");
            ai.setDeck(aiDeck);
            ai.setIsReady(true);
            LobbySlot open = lobby.getSlot(1);
            open.setType(LobbySlotType.OPEN);
            open.setIsReady(false);
            Log.info("TestHost listening on port " + port + " with AI deck " + aiDeck.getName());
        }

        int first = twoClients ? 0 : 1;
        while (true) {
            boolean ready = true;
            for (int i = first; i < 2; i++) {
                LobbySlot sl = lobby.getSlot(i);
                ready &= sl.getType() == LobbySlotType.REMOTE && sl.isReady();
            }
            if (ready) break;
            Thread.sleep(200);
        }
        for (int i = first; i < 2; i++) {
            Log.info("client ready: " + lobby.getSlot(i).getName() + " with " + lobby.getSlot(i).getDeck().getName());
        }
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
