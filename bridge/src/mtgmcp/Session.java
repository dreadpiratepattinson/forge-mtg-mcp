package mtgmcp;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gui.GuiBase;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One connection to a Forge host, and the state the MCP tools read from it. */
final class Session {
    static final File DECKS = new File(System.getenv().getOrDefault("FORGE_DECKS",
            System.getProperty("user.home") + "/.forge/decks"));

    private static boolean initialized;

    private FGameClient client;
    private ClaudeGuiGame gui;
    private String name;
    private Deck deck;
    private int slot = -1;
    private boolean sentDeck;
    private int logSeen;
    private final List<String> chat = new ArrayList<>();

    static synchronized void initForge() {
        if (initialized) return;
        GuiBase.setInterface(new HeadlessGui());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
            return null;
        });
        initialized = true;
        Log.info("Forge ready: " + StaticData.instance().getCommonCards().getUniqueCards().size() + " cards");
    }

    static String listDecks(String format) {
        File dir = new File(DECKS, format);
        String[] names = dir.list((d, n) -> n.endsWith(".dck"));
        if (names == null) return "No deck folder at " + dir;
        Arrays.sort(names);
        return "Decks in " + dir + ":\n" + String.join("\n", names);
    }

    static Deck loadDeck(String path) {
        File f = new File(path);
        if (!f.isAbsolute()) {
            f = new File(DECKS, path.contains("/") ? path : "commander/" + path);
        }
        if (!f.getName().endsWith(".dck")) f = new File(f.getPath() + ".dck");
        if (!f.isFile()) throw new IllegalArgumentException("No deck file " + f);
        Deck d = DeckSerializer.fromFile(f);
        if (d == null) throw new IllegalArgumentException("Forge could not read " + f);
        return d;
    }

    synchronized String connect(String host, int port, String playerName, String deckPath) {
        initForge();
        if (client != null) return "Already connected. Disconnect first.";
        deck = loadDeck(deckPath);
        name = playerName;
        gui = new ClaudeGuiGame();
        client = new FGameClient(playerName, gui, host, port);
        ClientGameLobby lobby = new ClientGameLobby();
        gui.setClientLobby(lobby);
        client.addLobbyListener(new ILobbyListener() {
            @Override
            public void update(GameLobbyData state, int newSlot) {
                lobby.setLocalPlayer(newSlot);
                lobby.setData(state);
                onLobbyUpdate(lobby, newSlot);
            }

            @Override
            public void message(String source, String message, ChatMessage.MessageType type) {
                synchronized (Session.this) {
                    chat.add((source == null ? "(server)" : source) + ": " + message);
                }
            }

            @Override
            public void close() {
                Log.info("connection closed by host");
                synchronized (Session.this) {
                    chat.add("(connection closed by host)");
                }
                gui.abortDialogs();
            }

            @Override
            public ClientGameLobby getLobby() {
                return lobby;
            }
        });
        try {
            client.connect();
        } catch (RuntimeException e) {
            client = null;
            throw new IllegalStateException("Could not connect to " + host + ":" + port + ": " + e.getMessage(), e);
        }
        return "Connected to " + host + ":" + port + " as " + playerName + " with deck '" + deck.getName()
                + "'. The deck is sent and marked ready when the host gives a seat. Then call forge_wait.";
    }

    private void onLobbyUpdate(ClientGameLobby lobby, int newSlot) {
        synchronized (this) {
            if (newSlot < 0 || sentDeck) return;
            slot = newSlot;
            sentDeck = true;
        }
        Log.info("seat " + newSlot + " assigned; sending deck and ready");
        UpdateLobbyPlayerEvent deckEvent = UpdateLobbyPlayerEvent.deckUpdate(deck);
        UpdateLobbyPlayerEvent readyEvent = UpdateLobbyPlayerEvent.isReadyUpdate(true);
        lobby.applyToSlot(newSlot, deckEvent);
        lobby.applyToSlot(newSlot, readyEvent);
        client.send(deckEvent);
        client.send(readyEvent);
    }

    private ClaudeGuiGame requireGui() {
        if (gui == null) throw new IllegalStateException("Not connected. Call forge_connect first.");
        return gui;
    }

    /** Waits for the next decision. Reports what happened since the last call. */
    String waitForDecision(long timeoutMs, boolean withState) throws InterruptedException {
        ClaudeGuiGame g = requireGui();
        Decision d = g.awaitDecision(timeoutMs);
        GameView gv = g.getGameView();
        StringBuilder sb = new StringBuilder();
        synchronized (this) {
            List<String> log = Render.logSince(gv, logSeen);
            logSeen = Render.logSize(gv);
            if (!log.isEmpty()) sb.append("EVENTS:\n  ").append(String.join("\n  ", log)).append("\n\n");
            if (!chat.isEmpty()) {
                sb.append("CHAT:\n  ").append(String.join("\n  ", chat)).append("\n\n");
                chat.clear();
            }
        }
        synchronized (g) {
            if (!g.notices.isEmpty()) {
                sb.append("NOTICES:\n  ").append(String.join("\n  ", g.notices)).append("\n\n");
                g.notices.clear();
            }
        }
        if (gv == null) {
            sb.append(slot < 0 ? "Waiting for the host to give a seat.\n" : "Seated in slot " + slot + ". Waiting for the host to start the game.\n");
            return sb.toString();
        }
        if (withState) sb.append(Render.state(gv, g.me())).append('\n');
        if (d != null) sb.append(d.render());
        else if (g.isGameOver()) sb.append("The game is over. Use forge_game with 'next_game' or 'quit'.\n");
        else sb.append("No decision yet (the opponent is acting). Call forge_wait again.\n");
        return sb.toString();
    }

    String act(int id, String answer) {
        return requireGui().answer(id, answer);
    }

    String state() {
        ClaudeGuiGame g = requireGui();
        return Render.state(g.getGameView(), g.me());
    }

    String cards(List<Integer> ids) {
        ClaudeGuiGame g = requireGui();
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            CardView c = g.findCard(id);
            sb.append(c == null ? "[" + id + "] not visible to you\n" : Render.cardText(c));
        }
        return sb.toString();
    }

    String stops(List<String> mine, List<String> opp, Boolean passOwnStack) {
        ClaudeGuiGame g = requireGui();
        synchronized (g) {
            if (mine != null) {
                g.myStops.clear();
                for (String p : mine) g.myStops.add(forge.game.phase.PhaseType.valueOf(p.toUpperCase()));
            }
            if (opp != null) {
                g.oppStops.clear();
                for (String p : opp) g.oppStops.add(forge.game.phase.PhaseType.valueOf(p.toUpperCase()));
            }
            if (passOwnStack != null) g.passOnOwnStack = passOwnStack;
            return "Stops on your turn: " + g.myStops + "; on opponents' turns: " + g.oppStops
                    + "; pass when only your own items are on the stack: " + g.passOnOwnStack;
        }
    }

    String chat(String message) {
        if (client == null) throw new IllegalStateException("Not connected.");
        client.send(new MessageEvent(name, message));
        return "Sent.";
    }

    synchronized String game(String action) {
        ClaudeGuiGame g = requireGui();
        switch (action) {
            case "concede" -> g.forfeit();
            case "next_game" -> g.nextGame(NextGameDecision.CONTINUE);
            case "quit" -> g.nextGame(NextGameDecision.QUIT);
            case "disconnect" -> {
                client.close();
                g.abortDialogs();
                client = null;
                gui = null;
                sentDeck = false;
                slot = -1;
                logSeen = 0;
            }
            default -> throw new IllegalArgumentException("Unknown action " + action);
        }
        return "Done: " + action;
    }
}
