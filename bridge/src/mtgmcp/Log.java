package mtgmcp;

import java.io.PrintStream;

/** Bridge log. Writes to stderr, because stdout carries the MCP protocol. */
final class Log {
    static PrintStream err = System.err;

    private Log() { }

    static void info(String msg) {
        err.println("[mtg-mcp] " + msg);
    }
}
