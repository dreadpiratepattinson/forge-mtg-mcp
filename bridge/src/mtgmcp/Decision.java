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
        if (options.size() > 40 && options.stream().distinct().count() == options.size()
                && options.stream().allMatch(o -> o.length() <= 24 && o.indexOf('\n') < 0)) {
            // Long lists of short names (creature types, card names): eight per line.
            for (int i = 0; i < options.size(); i++) {
                sb.append(i % 8 == 0 ? "  " : " | ").append(i).append(": ").append(options.get(i));
                if (i % 8 == 7 || i == options.size() - 1) sb.append('\n');
            }
        } else {
            for (int i = 0; i < options.size(); i++) {
                int j = i;
                while (j + 1 < options.size() && options.get(j + 1).equals(options.get(i))) j++;
                if (j - i >= 3) { // e.g. a whole library of "hidden card" in a scry order prompt
                    sb.append("  ").append(i).append("-").append(j).append(": ").append(options.get(i))
                            .append(" (").append(j - i + 1).append(" items)\n");
                    i = j;
                } else {
                    sb.append("  ").append(i).append(": ").append(options.get(i)).append('\n');
                }
            }
        }
        sb.append("Answer: ").append(howTo).append('\n');
        return sb.toString();
    }
}
