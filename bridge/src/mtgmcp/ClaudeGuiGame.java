package mtgmcp;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import forge.util.Localizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * The client-side game GUI. Forge calls it as it would call the desktop match screen; every question becomes a
 * {@link Decision} that the MCP tools read with {@link #awaitDecision} and answer with {@link #answer}.
 */
final class ClaudeGuiGame extends NetworkGuiGame {
    private static final long SETTLE_NANOS = 250_000_000L;

    private PlayerView me;
    private IGameController controller;
    private int nextId = 1;

    // Blocking dialog, if one is open. Prompt state otherwise.
    private Decision dialog;
    private String promptText = "";
    private String label1 = "", label2 = "";
    private boolean enable1, enable2;
    private final List<CardView> selectables = new ArrayList<>();
    private long promptVersion, consumedVersion, lastChange;
    /** Prompt version just before the last click of an answer; prompts up to it are stale. */
    private long lastClickVersion;
    /** Set when an answer had only card/player clicks; such a click can leave Forge's prompt unchanged. */
    private boolean clickOnlyAnswer;
    private long answeredAt;
    private static final long RESHOW_NANOS = 6_000_000_000L;
    private Decision prompt;
    private boolean gameOver;
    /** Messages and errors from the server since the last forge_wait. */
    final List<String> notices = new ArrayList<>();

    // Priority stops. Without a stop, the client passes priority on an empty stack by itself.
    final Set<PhaseType> myStops = EnumSet.of(PhaseType.MAIN1, PhaseType.MAIN2);
    final Set<PhaseType> oppStops = EnumSet.noneOf(PhaseType.class);
    boolean passOnOwnStack = true;
    /** Pass by yourself when the stack holds only abilities (no spells), whoever controls them. */
    boolean passOnAbilities = false;

    ClaudeGuiGame() {
        Render.mayView = this::mayView;
    }

    PlayerView me() { return me; }
    synchronized boolean isGameOver() { return gameOver; }

    // ---- decision access, called from MCP threads ----

    /** Blocks until a decision is open, the game ends, or the timeout passes. Returns null on timeout. */
    synchronized Decision awaitDecision(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            if (dialog != null && !dialog.reply.isDone()) return dialog;
            if (gameOver) return null;
            long now = System.nanoTime();
            // A click Forge ignored (no new prompt) leaves its prompt live: show it again.
            if (clickOnlyAnswer && promptVersion == consumedVersion && now - answeredAt >= RESHOW_NANOS
                    && controller != null && (enable1 || enable2 || !selectables.isEmpty())) {
                Log.info("re-showing prompt v" + promptVersion + " after a click with no effect");
                clickOnlyAnswer = false;
                consumedVersion = promptVersion - 1;
                prompt = null;
                continue;
            }
            if (promptOpen()) {
                if (now - lastChange >= SETTLE_NANOS) {
                    if (isPriorityPrompt() && shouldAutoPass()) {
                        Log.info("auto-pass: " + getGameView().getPhase());
                        consumedVersion = promptVersion;
                        controller.selectButtonOk();
                        continue;
                    }
                    if (prompt == null) prompt = buildPrompt();
                    return prompt;
                }
                wait(Math.max(1, (SETTLE_NANOS - (now - lastChange)) / 1_000_000L));
                continue;
            }
            long left = deadline - now;
            if (left <= 0) return null;
            wait(Math.max(1, left / 1_000_000L));
        }
    }

    synchronized Decision current() {
        if (dialog != null && !dialog.reply.isDone()) return dialog;
        return promptOpen() ? (prompt != null ? prompt : (prompt = buildPrompt())) : null;
    }

    /** Applies an answer to the open decision with this id. Returns a short confirmation. */
    String answer(int id, String answer) {
        Decision d;
        long answeredVersion;
        synchronized (this) {
            d = current();
            if (d == null || d.id != id) {
                return "Decision #" + id + " is not open any more. Call forge_wait.";
            }
            answeredVersion = promptVersion;
        }
        Log.info("answer #" + id + " at v" + answeredVersion + ": " + answer.trim());
        Object value = d.parser.apply(answer.trim()); // throws IllegalArgumentException on a bad answer
        synchronized (this) {
            if (d == dialog) {
                d.reply.complete(value);
            } else {
                // The server can send the next prompt while the answer is applied; that prompt stays open.
                consumedVersion = Math.max(consumedVersion, Math.max(answeredVersion, lastClickVersion));
                String low = answer.trim().toLowerCase();
                clickOnlyAnswer = !low.matches(".*\\b(ok|cancel)\\b.*");
                answeredAt = System.nanoTime();
                if (promptVersion == answeredVersion) prompt = null;
            }
            notifyAll();
        }
        return "OK: answered #" + id + " with '" + answer.trim() + "'.";
    }

    private boolean promptOpen() {
        // A player-target prompt has no buttons and no card list, only its text.
        return controller != null && promptVersion > consumedVersion
                && (enable1 || enable2 || !selectables.isEmpty()
                    || (!promptText.isBlank() && !promptText.startsWith("Waiting for")));
    }

    /** Waits until the server changes the prompt after version v and the change settles, or the timeout passes. */
    private synchronized void awaitPromptAfter(long v, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long now = System.nanoTime();
            if (promptVersion > v && now - lastChange >= SETTLE_NANOS) return;
            if (dialog != null && !dialog.reply.isDone()) return; // Forge asked a question mid-chain
            long left = deadline - now;
            if (left <= 0) return;
            wait(Math.max(1, Math.min(left, SETTLE_NANOS) / 1_000_000L));
        }
    }

    private void promptChanged() {
        clickOnlyAnswer = false; // Forge reacted; nothing to re-show
        promptVersion++;
        lastChange = System.nanoTime();
        prompt = null;
        notifyAll();
    }

    // ---- priority auto-pass ----

    private boolean isPriorityPrompt() {
        Localizer loc = Localizer.getInstance();
        return enable1 && loc.getMessage("lblOK").equals(label1)
                && (label2.equals(loc.getMessage("lblEndTurn")) || label2.startsWith(loc.getMessage("lblUndo")));
    }

    private boolean shouldAutoPass() {
        GameView gv = getGameView();
        if (gv == null) return false;
        // The stack rules apply even when the cancel button reads Undo: Forge shows Undo after a pass too.
        if (!gv.getStack().isEmpty()) {
            boolean allMine = passOnOwnStack, allAbilities = passOnAbilities;
            for (StackItemView si : gv.getStack()) {
                if (si.getActivatingPlayer() == null || si.getActivatingPlayer().getId() != me.getId()) allMine = false;
                if (!si.isAbility()) allAbilities = false;
            }
            return allMine || allAbilities;
        }
        if (isUndo()) return false; // we just acted (e.g. played a land): keep the phase open
        boolean myTurn = gv.getPlayerTurn() != null && gv.getPlayerTurn().getId() == me.getId();
        return !(myTurn ? myStops : oppStops).contains(gv.getPhase());
    }

    // ---- prompt (Input) decisions ----

    private Decision buildPrompt() {
        List<String> opts = new ArrayList<>();
        StringBuilder how = new StringBuilder();
        if (enable1) how.append("'ok' = ").append(label1).append(". ");
        if (enable2) {
            how.append("'cancel' = ").append(label2);
            if (isUndo()) how.append(" (takes back your last action, it does NOT end the turn; send 'undo' to use it)");
            else if (label2.startsWith(Localizer.getInstance().getMessage("lblEndTurn")))
                how.append(" (passes every priority until this turn ends: you cannot respond to anything; use 'ok' to keep responses open)");
            how.append(". ");
        }
        if (!selectables.isEmpty()) {
            how.append("'card <id>' selects one of the cards below. ");
            for (CardView c : selectables) opts.add(Render.cardLine(c));
        } else {
            how.append("'card <id>' clicks a card (cast/play from hand, activate on battlefield, or pick a target). ");
        }
        how.append("'player <index>' picks a player as shown in forge_state. Several actions: separate with ';'.");
        String text = promptText;
        GameView gv = getGameView();
        if (gv != null) text = "[" + Render.phaseLine(gv, me) + "]\n" + text;
        return new Decision(nextId++, "prompt", text, opts, how.toString(), this::actOnPrompt);
    }

    private boolean isUndo() {
        return label2 != null && label2.startsWith(Localizer.getInstance().getMessage("lblUndo"));
    }

    private Object actOnPrompt(String answer) {
        List<Runnable> actions = new ArrayList<>();
        String[] parts = answer.split(";");
        for (int k = 0; k < parts.length; k++) {
            String[] w = parts[k].trim().split("\\s+");
            String verb = w[0].toLowerCase();
            // Agents sent 'cancel' meaning "pass" or "end turn" and undid a resolved spell; Undo must be asked for by name.
            if (k == 0 && verb.equals("cancel") && enable2 && isUndo())
                throw new IllegalArgumentException("'cancel' is Undo here (it takes back your last action). Send 'undo' to undo, or 'ok' to pass.");
            if (verb.equals("undo")) {
                if (k == 0 && !(enable2 && isUndo())) throw new IllegalArgumentException("There is nothing to undo now.");
                verb = "cancel";
            }
            switch (verb) {
                case "ok" -> {
                    if (k == 0 && !enable1) throw new IllegalArgumentException("'" + label1 + "' (ok) is disabled now.");
                    actions.add(controller::selectButtonOk);
                }
                case "cancel" -> {
                    if (k == 0 && !enable2) throw new IllegalArgumentException("'" + label2 + "' (cancel) is disabled now.");
                    actions.add(controller::selectButtonCancel);
                }
                case "card" -> {
                    for (int i = 1; i < w.length; i++) {
                        int id = Integer.parseInt(w[i]);
                        // A creature whose trigger is on the stack matches both; a card the prompt lists as selectable wins.
                        boolean listed = selectables.stream().anyMatch(c -> c.getId() == id);
                        StackItemView si = listed ? null : findStackItem(id);
                        if (si != null) { // a spell on the stack is picked as a stack item, e.g. as a counterspell target
                            actions.add(() -> controller.selectStackItem(si));
                            continue;
                        }
                        CardView c = findCard(id);
                        if (c == null) throw new IllegalArgumentException("No visible card with id " + w[i] + ".");
                        actions.add(() -> controller.selectCard(c, null, null));
                    }
                }
                case "player" -> {
                    PlayerView p = findPlayer(w.length > 1 ? w[1] : "");
                    actions.add(() -> controller.selectPlayer(p, null));
                }
                default -> throw new IllegalArgumentException("Unknown action '" + verb + "'. Use ok, cancel, undo, card <id>, player <index>.");
            }
        }
        // Each click changes the prompt; the next click must go to the new prompt, not the old one.
        for (int i = 0; i < actions.size(); i++) {
            long v;
            synchronized (this) { v = promptVersion; lastClickVersion = v; }
            actions.get(i).run();
            if (i + 1 < actions.size()) {
                try {
                    awaitPromptAfter(v, 10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                synchronized (this) {
                    if (dialog != null && !dialog.reply.isDone()) {
                        notices.add("Only the first " + (i + 1) + " of " + actions.size()
                                + " actions were sent: Forge asked a question (DECISION #" + dialog.id
                                + "; forge_wait shows it). Answer it, then send the rest.");
                        break;
                    }
                }
            }
        }
        return null;
    }

    private StackItemView findStackItem(int id) {
        GameView gv = getGameView();
        if (gv == null) return null;
        for (StackItemView si : gv.getStack()) {
            if (si.getSourceCard() != null && si.getSourceCard().getId() == id) return si;
        }
        return null;
    }

    CardView findCard(int id) {
        for (CardView c : selectables) if (c.getId() == id) return c;
        GameView gv = getGameView();
        if (gv == null) return null;
        for (PlayerView p : gv.getPlayers()) {
            for (ZoneType z : Render.ZONES) {
                Iterable<CardView> cards = p.getCards(z);
                if (cards == null) continue;
                for (CardView c : cards) if (c.getId() == id) return c;
            }
        }
        for (StackItemView si : gv.getStack()) {
            if (si.getSourceCard() != null && si.getSourceCard().getId() == id) return si.getSourceCard();
        }
        return null;
    }

    PlayerView findPlayer(String key) {
        List<PlayerView> players = new ArrayList<>(getGameView().getPlayers());
        try {
            return players.get(Integer.parseInt(key));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            for (PlayerView p : players) if (p.getName().equalsIgnoreCase(key)) return p;
            throw new IllegalArgumentException("No player '" + key + "'. Use the index from forge_state.");
        }
    }

    // ---- dialog decisions: these run on a Forge thread and block until answered ----

    @SuppressWarnings("unchecked")
    private <R> R ask(String kind, String text, List<String> options, String howTo, Function<String, Object> parser) {
        Decision d;
        synchronized (this) {
            d = new Decision(nextId++, kind, text, options, howTo, parser);
            dialog = d;
            notifyAll();
        }
        try {
            return (R) d.reply.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } finally {
            synchronized (this) {
                if (dialog == d) dialog = null;
                notifyAll();
            }
        }
    }

    void abortDialogs() {
        synchronized (this) {
            if (dialog != null) dialog.reply.completeExceptionally(new IllegalStateException("disconnected"));
            gameOver = true;
            notifyAll();
        }
    }

    private static List<Integer> indices(String answer, int size, int min, int max) {
        List<Integer> out = new ArrayList<>();
        String a = answer.trim().toLowerCase();
        if (!a.isEmpty() && !a.equals("none")) {
            for (String s : a.split("[,\\s]+")) {
                int i = Integer.parseInt(s);
                if (i < 0 || i >= size) throw new IllegalArgumentException("Index " + i + " is out of range 0.." + (size - 1) + ".");
                if (out.contains(i)) throw new IllegalArgumentException("Index " + i + " is repeated.");
                out.add(i);
            }
        }
        if (out.size() < min || out.size() > max) {
            throw new IllegalArgumentException("Choose between " + min + " and " + max + " items, not " + out.size() + ".");
        }
        return out;
    }

    /** Lets a list dialog of cards take 'card 241' as well as an index: each card id becomes its index. */
    private static <T> String cardIdsToIndices(String answer, List<T> choices) {
        if (!answer.toLowerCase().contains("card")) return answer;
        List<String> out = new ArrayList<>();
        for (String s : answer.toLowerCase().replace("card", " ").split("[,;\\s]+")) {
            if (s.isEmpty()) continue;
            int id = Integer.parseInt(s), at = -1;
            for (int i = 0; i < choices.size(); i++)
                if (choices.get(i) instanceof CardView c && c.getId() == id) at = i;
            if (at < 0) throw new IllegalArgumentException("No card with id " + id + " in this list; answer with its index.");
            out.add(String.valueOf(at));
        }
        return String.join(",", out);
    }

    private static boolean yesNo(String a) {
        String s = a.toLowerCase();
        if (s.startsWith("y") || s.equals("true") || s.equals("0")) return true;
        if (s.startsWith("n") || s.equals("false") || s.equals("1")) return false;
        throw new IllegalArgumentException("Answer yes or no.");
    }

    private static String describe(Object o) {
        if (o instanceof CardView c) return Render.cardLine(c);
        if (o instanceof PlayerView p) return "player " + p.getName();
        if (o instanceof SpellAbilityView sa) return sa.toString();
        return String.valueOf(o);
    }

    private static <T> List<String> describeAll(Collection<T> items, FSerializableFunction<T, String> display) {
        List<String> out = new ArrayList<>();
        for (T t : items) out.add(display != null ? display.apply(t) : describe(t));
        return out;
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        List<String> opts = options != null && options.size() == 2 ? options : List.of("Yes", "No");
        String text = (c != null ? Render.cardLine(c) + "\n" : "") + question;
        return ask("confirm", text, opts, "'yes' (option 0) or 'no' (option 1).", ClaudeGuiGame::yesNo);
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        return ask("confirm", title + ": " + message, List.of(yesButtonText, noButtonText),
                "'yes' (option 0) or 'no' (option 1).", ClaudeGuiGame::yesNo);
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return ask("option", title + ": " + message, options, "the index of one option.",
                a -> indices(a, options.size(), 1, 1).get(0));
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            return ask("input", title + ": " + message, inputOptions, "the index of one option.",
                    a -> inputOptions.get(indices(a, inputOptions.size(), 1, 1).get(0)));
        }
        return ask("input", title + ": " + message, List.of(),
                (isNumeric ? "a number" : "free text") + (initialInput != null ? " (default '" + initialInput + "')" : "") + ".",
                a -> {
                    if (isNumeric) Integer.parseInt(a);
                    return a;
                });
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) return new ArrayList<>();
        int hi = max < 0 ? choices.size() : Math.min(max, choices.size());
        int lo = Math.max(0, Math.min(min, hi));
        return ask("choose", message, describeAll(choices, display),
                "indices separated by commas, between " + lo + " and " + hi + " of them" + (lo == 0 ? " ('none' for zero)" : "") + ".",
                a -> {
                    List<T> out = new ArrayList<>();
                    for (int i : indices(cardIdsToIndices(a, choices), choices.size(), lo, hi)) out.add(choices.get(i));
                    return out;
                });
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices, CardView referenceCard, boolean sideboardingMode, boolean showRememberCheckbox) {
        String text = title + (top != null ? " (first = " + top + ")" : "");
        // A "reorder" prompt can put every item in destChoices and leave sourceChoices empty; order them all.
        List<T> items = new ArrayList<>();
        if (sourceChoices != null) items.addAll(sourceChoices);
        if (destChoices != null) for (T t : destChoices) if (!items.contains(t)) items.add(t);
        return ask("order", text, describeAll(items, null),
                "all indices in the new order, first one first; or 'keep' for the order shown; or 'bottom <indices>' "
                        + "to move those items to the end and keep the rest in place.",
                a -> {
                    if (a.equalsIgnoreCase("keep")) return new IGuiGame.OrderResult<>(items, false);
                    if (a.toLowerCase().startsWith("bottom")) {
                        List<Integer> low = indices(a.substring(6), items.size(), 0, items.size());
                        List<T> out = new ArrayList<>();
                        for (int i = 0; i < items.size(); i++) if (!low.contains(i)) out.add(items.get(i));
                        for (int i : low) out.add(items.get(i));
                        return new IGuiGame.OrderResult<>(out, false);
                    }
                    List<T> out = new ArrayList<>();
                    for (int i : indices(a, items.size(), items.size(), items.size())) out.add(items.get(i));
                    return new IGuiGame.OrderResult<>(out, false);
                });
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return main.toFlatList(); // no sideboarding: keep the main deck as it is
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) return null;
        String text = title + (delayedReveal != null ? "\n(revealed: " + describeAll(new ArrayList<>(delayedReveal.getCards()), null) + ")" : "");
        return ask("choose", text, describeAll(new ArrayList<>(optionList), null),
                "the index of one option" + (isOptional ? ", or 'none'." : "."),
                a -> {
                    List<Integer> ix = indices(a, optionList.size(), isOptional ? 0 : 1, 1);
                    return ix.isEmpty() ? null : optionList.get(ix.get(0));
                });
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) return new ArrayList<>();
        int hi = Math.min(max, optionList.size());
        return ask("choose", title, describeAll(new ArrayList<>(optionList), null),
                "indices separated by commas, between " + min + " and " + hi + " of them" + (min == 0 ? " ('none' for zero)" : "") + ".",
                a -> {
                    List<GameEntityView> out = new ArrayList<>();
                    for (int i : indices(a, optionList.size(), min, hi)) out.add(optionList.get(i));
                    return out;
                });
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable, boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> list = new ArrayList<>();
        cards.forEach(list::add);
        return ask("order", title, describeAll(list, null), "all indices in the new order, or 'keep'.",
                a -> {
                    if (a.equalsIgnoreCase("keep")) return list;
                    List<CardView> out = new ArrayList<>();
                    for (int i : indices(a, list.size(), list.size(), list.size())) out.add(list.get(i));
                    return out;
                });
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) return null;
        // Same rule as the desktop screen: one ability that does not ask to be confirmed is played at once.
        if (abilities.size() == 1 && !abilities.get(0).promptIfOnlyPossibleAbility()) return abilities.get(0);
        return ask("ability", "Choose an ability of " + Render.cardLine(hostCard), describeAll(abilities, null),
                "the index of one ability, or 'none' to play nothing.",
                a -> {
                    List<Integer> ix = indices(a, abilities.size(), 0, 1);
                    return ix.isEmpty() ? null : abilities.get(ix.get(0));
                });
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        List<String> opts = describeAll(blockers, null);
        if (defender != null) opts.add("defender: " + describe(defender));
        return ask("damage", "Assign " + damage + " combat damage from " + Render.cardLine(attacker), opts,
                "'i=n' pairs separated by commas that add up to " + damage + ", or 'auto' for lethal damage in order.",
                a -> {
                    Map<CardView, Integer> out = new LinkedHashMap<>();
                    if (a.equalsIgnoreCase("auto")) {
                        int left = damage;
                        for (CardView b : blockers) {
                            int dmg = Math.min(left, Math.max(0, b.getLethalDamage()));
                            out.put(b, dmg);
                            left -= dmg;
                        }
                        if (left > 0) {
                            // Rest goes to the defender when trample allows it, else to the last blocker.
                            CardView sink = defender instanceof CardView dc ? dc : (defender == null ? blockers.get(blockers.size() - 1) : null);
                            if (sink != null) out.merge(sink, left, Integer::sum);
                            else out.put(null, left);
                        }
                        return out;
                    }
                    int sum = 0;
                    for (String pair : a.split(",")) {
                        String[] kv = pair.trim().split("=");
                        int i = Integer.parseInt(kv[0].trim()), n = Integer.parseInt(kv[1].trim());
                        sum += n;
                        if (i < blockers.size()) out.put(blockers.get(i), n);
                        else if (i == blockers.size() && defender != null) out.put(defender instanceof CardView dc ? dc : null, n);
                        else throw new IllegalArgumentException("Index " + i + " is out of range.");
                    }
                    if (sum != damage) throw new IllegalArgumentException("The amounts add up to " + sum + ", not " + damage + ".");
                    return out;
                });
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount, boolean atLeastOne, String amountLabel) {
        List<Object> keys = new ArrayList<>(target.keySet());
        return ask("distribute", "Distribute " + amount + " " + amountLabel + " from " + Render.cardLine(effectSource)
                        + (atLeastOne ? " (at least 1 each)" : ""), describeAll(keys, null),
                "'i=n' pairs separated by commas that add up to " + amount + ".",
                a -> {
                    Map<Object, Integer> out = new LinkedHashMap<>();
                    int sum = 0;
                    for (String pair : a.split(",")) {
                        String[] kv = pair.trim().split("=");
                        int i = Integer.parseInt(kv[0].trim()), n = Integer.parseInt(kv[1].trim());
                        if (i < 0 || i >= keys.size()) throw new IllegalArgumentException("Index " + i + " is out of range.");
                        out.put(keys.get(i), n);
                        sum += n;
                    }
                    if (sum != amount) throw new IllegalArgumentException("The amounts add up to " + sum + ", not " + amount + ".");
                    return out;
                });
    }

    // ---- prompt state, sent by the server ----

    @Override
    public synchronized void showPromptMessage(PlayerView playerView, String message, CardView card) {
        promptText = message == null ? "" : message;
        promptChanged();
        Log.info("prompt v" + promptVersion + " (consumed v" + consumedVersion + "): " + promptText.replace('\n', ' '));
    }

    @Override
    public synchronized void updateButtons(PlayerView owner, String l1, String l2, boolean e1, boolean e2, boolean focus1) {
        label1 = l1 == null ? "" : l1;
        label2 = l2 == null ? "" : l2;
        enable1 = e1;
        enable2 = e2;
        promptChanged();
        Log.info("buttons v" + promptVersion + ": " + label1 + "=" + e1 + " " + label2 + "=" + e2);
    }

    @Override
    public synchronized void setSelectables(Iterable<CardView> cards, int min, int max) {
        super.setSelectables(cards, min, max);
        selectables.clear();
        if (cards != null) cards.forEach(selectables::add);
        promptChanged();
    }

    @Override
    public synchronized void clearSelectables() {
        super.clearSelectables();
        selectables.clear();
        promptChanged();
    }

    @Override
    public void setOriginalGameController(PlayerView view, IGameController gameController) {
        super.setOriginalGameController(view, gameController);
        synchronized (this) {
            if (gameController != null) {
                me = view;
                controller = gameController;
                gameOver = false;
                notifyAll();
            }
        }
    }

    @Override
    public void setGameController(PlayerView player, IGameController gameController) {
        super.setGameController(player, gameController);
        synchronized (this) {
            if (gameController != null) controller = gameController;
        }
    }

    @Override
    public void finishGame() {
        synchronized (this) {
            gameOver = true;
            notifyAll();
        }
    }

    @Override
    public void afterGameEnd() {
        super.afterGameEnd();
        finishGame();
    }

    void nextGame(forge.gamemodes.match.NextGameDecision decision) {
        if (controller != null) controller.nextGameDecision(decision);
        synchronized (this) {
            gameOver = false;
        }
    }

    void forfeit() {
        if (controller != null) controller.concede();
    }

    // ---- display callbacks this client has no use for ----

    @Override protected void updateCurrentPlayer(PlayerView player) { }
    // Forge's timer shows "Waiting for ..." with disabled buttons and can hide a prompt that arrived after it started.
    @Override public void awaitNextInput() { }
    @Override public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) { return false; }
    @Override public void openView(TrackableCollection<PlayerView> myPlayers) { }
    @Override public void showCombat() { }
    @Override public synchronized void flashIncorrectAction() {
        notices.add("Forge rejected the last action.");
        // The prompt stays as it was; reopen it so the player can answer again.
        if (consumedVersion >= promptVersion) consumedVersion = promptVersion - 1;
        prompt = null;
        notifyAll();
    }
    @Override public void alertUser() { }
    @Override public void updatePhase(boolean saveState) { }
    @Override public void updateTurn(PlayerView player) { }
    @Override public void updatePlayerControl() { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public void showManaPool(PlayerView player) { }
    @Override public void hideManaPool(PlayerView player) { }
    @Override public void updateStack() { }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { }
    @Override public void updateCards(Iterable<CardView> cards) { }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { }
    @Override public void updateShards(Iterable<PlayerView> shardsUpdate) { }
    @Override public void setPanelSelection(CardView hostCard) { }
    @Override public synchronized void message(String message, String title) { notices.add(title + ": " + message); }
    @Override public synchronized void showErrorDialog(String message, String title) { notices.add("ERROR " + title + ": " + message); }
    @Override
    public synchronized <T> void reveal(String message, List<T> items) {
        notices.add("REVEALED — " + message + ": " + String.join("; ", describeAll(items, null)));
    }

    @Override
    public void updateRevealedCards(TrackableCollection<CardView> collection) {
        super.updateRevealedCards(collection);
        if (collection == null || collection.isEmpty()) return;
        synchronized (this) {
            notices.add("REVEALED: " + String.join("; ", describeAll(collection, null)));
        }
    }

    @Override public void setCard(CardView card) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }
}
