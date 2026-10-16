package mtgmcp;

import com.google.common.collect.Multiset;
import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Plain-text views of the game for the MCP tools. Card ids in brackets are the ids the tools accept. */
final class Render {
    static final ZoneType[] ZONES = {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
            ZoneType.Command, ZoneType.Library, ZoneType.Flashback};

    /** The visibility rule of the GUI that owns the game: the same rule the desktop match screen uses. */
    static volatile Predicate<CardView> mayView = c -> true;

    private Render() { }

    static String phaseLine(GameView gv, PlayerView me) {
        PlayerView active = gv.getPlayerTurn();
        String who = active == null ? "?" : active.getName() + (me != null && active.getId() == me.getId() ? " (you)" : "");
        return "Turn " + gv.getTurn() + ", " + who + ", phase " + gv.getPhase() + ", stack " + gv.getStack().size();
    }

    static String cardLine(CardView c) {
        if (c == null) return "(none)";
        CardStateView s = c.getCurrentState();
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(c.getId()).append("] ");
        if (!mayView.test(c)) {
            sb.append(c.isFaceDown() ? "face-down card" : "hidden card");
        } else {
            sb.append(s.getName());
            String cost = s.getManaCost() == null ? "" : s.getManaCost().toString();
            if (!cost.isEmpty() && !"no cost".equalsIgnoreCase(cost)) sb.append(' ').append(cost);
            sb.append(" — ").append(s.getType());
            if (s.isCreature() || s.isVehicle()) sb.append(' ').append(s.getPower()).append('/').append(s.getToughness());
            if (s.isPlaneswalker()) sb.append(" loyalty ").append(s.getLoyalty());
            if (s.isBattle()) sb.append(" defense ").append(s.getDefense());
        }
        List<String> flags = new ArrayList<>();
        if (c.isTapped()) flags.add("tapped");
        if (c.getZone() == ZoneType.Battlefield && c.isSick() && s.isCreature()) flags.add("summoning sick");
        if (c.isAttacking()) flags.add("attacking");
        if (c.isBlocking()) flags.add("blocking");
        if (c.isToken()) flags.add("token");
        if (c.isCommander()) flags.add("commander");
        if (c.isFaceDown()) flags.add("face down");
        if (c.getDamage() > 0) flags.add("damage " + c.getDamage());
        Multiset<CounterType> counters = c.getCounters();
        if (counters != null) {
            for (Multiset.Entry<CounterType> e : counters.entrySet()) flags.add(e.getElement() + " x" + e.getCount());
        }
        if (c.getAttachedTo() != null) flags.add("attached to " + c.getAttachedTo().getName() + " [" + c.getAttachedTo().getId() + "]");
        if (c.getEnchantedPlayer() != null) flags.add("enchanting " + c.getEnchantedPlayer().getName());
        if (!flags.isEmpty()) sb.append(" (").append(String.join(", ", flags)).append(')');
        return sb.toString();
    }

    static String cardText(CardView c) {
        if (!mayView.test(c)) return cardLine(c) + "\n  You may not look at this card.\n";
        CardStateView s = c.getCurrentState();
        StringBuilder sb = new StringBuilder(cardLine(c)).append('\n');
        sb.append("  zone ").append(c.getZone());
        if (c.getController() != null) sb.append(", controller ").append(c.getController().getName());
        if (c.getOwner() != null) sb.append(", owner ").append(c.getOwner().getName());
        sb.append('\n');
        String text = c.getText();
        if (text == null || text.isBlank()) text = s.getOracleText();
        if (text != null && !text.isBlank()) sb.append("  ").append(text.trim().replace("\n", "\n  ")).append('\n');
        if (c.hasBackSide() && c.getBackSideName() != null) sb.append("  back side: ").append(c.getBackSideName()).append('\n');
        return sb.toString();
    }

    private static String mana(PlayerView p) {
        StringBuilder sb = new StringBuilder();
        byte[] colors = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN, MagicColor.COLORLESS};
        String[] names = {"W", "U", "B", "R", "G", "C"};
        for (int i = 0; i < colors.length; i++) {
            int n = p.getMana(colors[i]);
            if (n > 0) sb.append(names[i]).append(n).append(' ');
        }
        return sb.length() == 0 ? "empty" : sb.toString().trim();
    }

    static String state(GameView gv, PlayerView me) {
        if (gv == null) return "No game is running.\n";
        StringBuilder sb = new StringBuilder();
        sb.append(phaseLine(gv, me)).append('\n');
        if (gv.isGameOver()) sb.append("GAME OVER. Winner: ").append(gv.getWinningPlayerName()).append('\n');

        if (!gv.getStack().isEmpty()) {
            sb.append("\nSTACK (top first):\n");
            for (StackItemView si : gv.getStack()) {
                sb.append("  ").append(si.getText());
                if (si.getActivatingPlayer() != null) sb.append(" — by ").append(si.getActivatingPlayer().getName());
                if (si.getSourceCard() != null) sb.append(" — source [").append(si.getSourceCard().getId()).append(']');
                sb.append('\n');
            }
        }

        CombatView combat = gv.getCombat();
        if (combat != null && combat.getNumAttackers() > 0) {
            sb.append("\nCOMBAT:\n");
            for (GameEntityView def : combat.getDefenders()) {
                for (CardView a : combat.getAttackersOf(def)) {
                    sb.append("  ").append(cardLine(a)).append(" -> ").append(def instanceof CardView dc ? cardLine(dc) : def.toString());
                    Iterable<CardView> blockers = combat.getPlannedBlockers(a);
                    if (blockers == null) blockers = combat.getBlockers(a);
                    if (blockers != null) for (CardView b : blockers) sb.append("\n      blocked by ").append(cardLine(b));
                    sb.append('\n');
                }
            }
        }

        int idx = 0;
        for (PlayerView p : gv.getPlayers()) {
            boolean mine = me != null && p.getId() == me.getId();
            sb.append("\nPLAYER ").append(idx++).append(": ").append(p.getName()).append(mine ? " (you)" : "")
                    .append(p.getHasLost() ? " (LOST)" : "")
                    .append(" — life ").append(p.getLife())
                    .append(", hand ").append(p.getZoneSize(ZoneType.Hand))
                    .append(", library ").append(p.getZoneSize(ZoneType.Library))
                    .append(", lands played ").append(p.getNumLandThisTurn()).append('/').append(p.getMaxLandString())
                    .append(", mana pool ").append(mana(p));
            Multiset<CounterType> counters = p.getCounters();
            if (counters != null && !counters.isEmpty()) sb.append(", counters ").append(counters);
            sb.append('\n');
            for (String info : p.getPlayerCommanderInfo()) sb.append("  ").append(info).append('\n');
            zone(sb, "command zone", p.getCards(ZoneType.Command));
            zone(sb, "battlefield", p.getCards(ZoneType.Battlefield));
            if (mine) {
                zone(sb, "hand", p.getCards(ZoneType.Hand));
            } else {
                List<CardView> seen = new ArrayList<>();
                Iterable<CardView> hand = p.getCards(ZoneType.Hand);
                if (hand != null) for (CardView c : hand) if (mayView.test(c)) seen.add(c);
                if (!seen.isEmpty()) zone(sb, "hand, cards you may see", seen);
            }
            zone(sb, "graveyard", p.getCards(ZoneType.Graveyard));
            zone(sb, "exile", p.getCards(ZoneType.Exile));
        }
        return sb.toString();
    }

    private static void zone(StringBuilder sb, String name, Iterable<CardView> cards) {
        if (cards == null || !cards.iterator().hasNext()) return;
        sb.append("  ").append(name).append(":\n");
        // Lands first on the battlefield, so the board reads like a table.
        List<CardView> lands = new ArrayList<>(), other = new ArrayList<>();
        for (CardView c : cards) {
            if (mayView.test(c) && c.getCurrentState().isLand() && "battlefield".equals(name)) lands.add(c);
            else other.add(c);
        }
        for (CardView c : other) sb.append("    ").append(cardLine(c)).append('\n');
        if (!lands.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (CardView c : lands) parts.add(c.getId() + " " + c.getCurrentState().getName() + (c.isTapped() ? " (T)" : ""));
            sb.append("    lands: ").append(String.join("; ", parts)).append('\n');
        }
    }

    /** Log lines after the first {@code seen} entries, in the order they happened. */
    static List<String> logSince(GameView gv, int seen) {
        List<String> out = new ArrayList<>();
        if (gv == null || gv.getGameLog() == null) return out;
        List<GameLogEntry> all = gv.getGameLog().getAllEntries();
        for (int i = Math.max(0, seen); i < all.size(); i++) out.add(all.get(i).message());
        return out;
    }

    static int logSize(GameView gv) {
        return gv == null || gv.getGameLog() == null ? 0 : gv.getGameLog().getAllEntries().size();
    }
}
