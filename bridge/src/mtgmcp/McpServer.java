package mtgmcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP server over stdio (newline-delimited JSON-RPC). Forge prints to System.out freely, so the real stdout is
 * kept for the protocol and System.out is pointed at stderr before Forge loads.
 */
public final class McpServer {
    private static final Gson GSON = new Gson();
    private static final String PHASES = "UNTAP, UPKEEP, DRAW, MAIN1, COMBAT_BEGIN, COMBAT_DECLARE_ATTACKERS, "
            + "COMBAT_DECLARE_BLOCKERS, COMBAT_FIRST_STRIKE_DAMAGE, COMBAT_DAMAGE, COMBAT_END, MAIN2, END_OF_TURN, CLEANUP";

    private final PrintStream out;
    private final Session session = new Session();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-call");
        t.setDaemon(true);
        return t;
    });

    private McpServer(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        McpServer server = new McpServer(protocol);
        // Load Forge now, so the first forge_connect does not wait for it.
        new Thread(Session::initForge, "forge-init").start();
        server.run();
    }

    private void run() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            pool.submit(() -> handle(msg));
        }
        System.exit(0);
    }

    private void handle(JsonObject msg) {
        JsonElement id = msg.get("id");
        String method = msg.has("method") ? msg.get("method").getAsString() : "";
        try {
            JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
            JsonElement result = switch (method) {
                case "initialize" -> initialize(params);
                case "tools/list" -> toolList();
                case "tools/call" -> callTool(params);
                case "ping" -> new JsonObject();
                default -> null;
            };
            if (id == null) return; // notification
            if (result == null) {
                reply(id, null, error(-32601, "Method not found: " + method));
            } else {
                reply(id, result, null);
            }
        } catch (Exception e) {
            Log.info("error in " + method + ": " + e);
            if (id != null) reply(id, null, error(-32603, String.valueOf(e)));
        }
    }

    private synchronized void reply(JsonElement id, JsonElement result, JsonObject error) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        if (error != null) r.add("error", error);
        else r.add("result", result);
        out.println(GSON.toJson(r));
        out.flush();
    }

    private static JsonObject error(int code, String message) {
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        return e;
    }

    private JsonObject initialize(JsonObject params) {
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-06-18");
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "forge");
        info.addProperty("version", "0.1.0");
        r.add("serverInfo", info);
        r.addProperty("instructions", "Play Magic: The Gathering in Forge as a network client. Loop: forge_wait -> read the "
                + "DECISION -> forge_act with its number. Card ids are in brackets. Use forge_card to read card text before "
                + "you rely on it.");
        return r;
    }

    // ---- tools ----

    private record Tool(String name, String description, JsonObject schema) { }

    private static JsonObject schema(Object... props) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        JsonObject p = new JsonObject();
        JsonArray required = new JsonArray();
        for (int i = 0; i < props.length; i += 4) {
            JsonObject prop = new JsonObject();
            String type = (String) props[i + 1];
            if (type.endsWith("[]")) {
                prop.addProperty("type", "array");
                JsonObject items = new JsonObject();
                items.addProperty("type", type.substring(0, type.length() - 2));
                prop.add("items", items);
            } else {
                prop.addProperty("type", type);
            }
            prop.addProperty("description", (String) props[i + 2]);
            p.add((String) props[i], prop);
            if ((Boolean) props[i + 3]) required.add((String) props[i]);
        }
        s.add("properties", p);
        s.add("required", required);
        return s;
    }

    private static final List<Tool> TOOLS = List.of(
            new Tool("forge_decks", "List the deck files in the Forge deck folder.",
                    schema("format", "string", "Deck sub-folder: commander (default) or constructed.", false)),
            new Tool("forge_connect", "Join a Forge network game that the user hosts. Sends the deck and marks the seat ready.",
                    schema("deck", "string", "Deck file name from forge_decks, or an absolute path.", true,
                            "host", "string", "Host address. Default: the Windows host of this WSL box.", false,
                            "port", "integer", "Host port. Default 36743.", false,
                            "name", "string", "Player name. Default Claude.", false)),
            new Tool("forge_wait", "Wait until Forge asks you something, then return new game events, chat, the board and the "
                    + "open DECISION. Priority on an empty stack is passed for you except at your stops (see forge_stops).",
                    schema("timeout_seconds", "integer", "How long to wait. Default 50.", false,
                            "with_state", "boolean", "Include the full board. Default true.", false)),
            new Tool("forge_act", "Answer the open decision. The DECISION text says which answers it accepts.",
                    schema("decision", "integer", "The decision number, from DECISION #n.", true,
                            "answer", "string", "For a prompt: 'ok', 'cancel', 'card <id>', 'player <index>', several joined by ';'. "
                                    + "For a dialog: indices like '0,2', 'yes'/'no', 'none', or 'i=n' pairs.", true)),
            new Tool("forge_state", "Return the full board: players, life, zones, stack, combat.", schema()),
            new Tool("forge_card", "Return the full rules text of visible cards.",
                    schema("ids", "integer[]", "Card ids from the board.", true)),
            new Tool("forge_stops", "Set the phases where you want priority on an empty stack. Phases: " + PHASES + ".",
                    schema("my_turn", "string[]", "Stops on your own turn. Default MAIN1, MAIN2.", false,
                            "opponent_turn", "string[]", "Stops on opponents' turns. Default none.", false,
                            "pass_on_own_stack", "boolean", "Pass by yourself when only your own spells are on the stack. Default true.", false,
                            "pass_on_abilities", "boolean", "Pass by yourself when the stack holds only abilities (triggers, activations), no spells. Default false.", false)),
            new Tool("forge_chat", "Send a chat message to the players.",
                    schema("message", "string", "The text.", true)),
            new Tool("forge_game", "Game control: concede, next_game, quit, disconnect.",
                    schema("action", "string", "One of concede, next_game, quit, disconnect.", true)));

    private JsonObject toolList() {
        JsonArray arr = new JsonArray();
        for (Tool t : TOOLS) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.schema());
            arr.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("tools", arr);
        return r;
    }

    private JsonObject callTool(JsonObject params) {
        String name = params.get("name").getAsString();
        JsonObject a = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        String text;
        boolean isError = false;
        try {
            text = switch (name) {
                case "forge_decks" -> Session.listDecks(str(a, "format", "commander"));
                case "forge_connect" -> session.connect(str(a, "host", System.getenv().getOrDefault("FORGE_HOST", "localhost")), num(a, "port", 36743),
                        str(a, "name", "Claude"), str(a, "deck", null));
                case "forge_wait" -> session.waitForDecision(num(a, "timeout_seconds", 50) * 1000L, bool(a, "with_state", true));
                case "forge_act" -> session.act(num(a, "decision", -1), str(a, "answer", ""));
                case "forge_state" -> session.state();
                case "forge_card" -> session.cards(ints(a, "ids"));
                case "forge_stops" -> session.stops(strs(a, "my_turn"), strs(a, "opponent_turn"),
                        a.has("pass_on_own_stack") ? a.get("pass_on_own_stack").getAsBoolean() : null,
                        a.has("pass_on_abilities") ? a.get("pass_on_abilities").getAsBoolean() : null);
                case "forge_chat" -> session.chat(str(a, "message", ""));
                case "forge_game" -> session.game(str(a, "action", ""));
                default -> throw new IllegalArgumentException("Unknown tool " + name);
            };
        } catch (Exception e) {
            Log.info(name + " failed: " + e);
            text = "Error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            isError = true;
        }
        JsonObject r = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject c = new JsonObject();
        c.addProperty("type", "text");
        c.addProperty("text", text);
        content.add(c);
        r.add("content", content);
        r.addProperty("isError", isError);
        return r;
    }

    private static String str(JsonObject a, String k, String def) {
        if (a.has(k) && !a.get(k).isJsonNull()) return a.get(k).getAsString();
        if (def == null) throw new IllegalArgumentException("Missing argument '" + k + "'.");
        return def;
    }

    private static int num(JsonObject a, String k, int def) {
        return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsInt() : def;
    }

    private static boolean bool(JsonObject a, String k, boolean def) {
        return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsBoolean() : def;
    }

    private static List<Integer> ints(JsonObject a, String k) {
        List<Integer> out = new ArrayList<>();
        if (a.has(k)) for (JsonElement e : a.getAsJsonArray(k)) out.add(e.getAsInt());
        return out;
    }

    private static List<String> strs(JsonObject a, String k) {
        if (!a.has(k) || a.get(k).isJsonNull()) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement e : a.getAsJsonArray(k)) out.add(e.getAsString());
        return out;
    }
}
