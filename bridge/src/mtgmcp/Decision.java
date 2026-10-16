package mtgmcp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * One question that Forge asks this player. A dialog blocks a Forge thread until {@link #answer} completes it;
 * a prompt is the current Input (priority, attackers, targets, ...) and is answered by sending controller actions.
 */
final class Decision {
    final int id;
    final String kind;
    final String text;
    final List<String> options;
    final String howTo;
    /** Turns the answer string into the value Forge expects, or acts on the controller. Throws to reject. */
    final Function<String, Object> parser;
    final CompletableFuture<Object> reply = new CompletableFuture<>();

    Decision(int id, String kind, String text, List<String> options, String howTo, Function<String, Object> parser) {
        this.id = id;
        this.kind = kind;
        this.text = text;
        this.options = options;
        this.howTo = howTo;
        this.parser = parser;
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("DECISION #").append(id).append(" (").append(kind).append(")\n");
        sb.append(text == null || text.isEmpty() ? "(no message)" : text.trim()).append('\n');
        for (int i = 0; i < options.size(); i++) {
            sb.append("  ").append(i).append(": ").append(options.get(i)).append('\n');
        }
        sb.append("Answer: ").append(howTo).append('\n');
        return sb.toString();
    }
}
