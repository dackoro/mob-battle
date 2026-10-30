package dev.dackoro.mobbattle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Which shot the battle film plays next (FilmCamera). Plain logic with no game classes, so FilmPickerSim runs it
 * offline. 2026-10-16, playtest: wide and landscape came one after the other again and again ("design a weights algorithm
 * so a shot doesn't repeat that much; sometimes it looks fine, but it gets monotonous").
 * <p>
 * Each shot's chance = its base weight for this fight (fighters, spread) x the player's filmShot percent, then:
 * <ul>
 *   <li>recency: never the last shot again, 2 shots ago x0.3, 3 ago x0.6;</li>
 *   <li>family (far: wide, landscape, telephoto, zenith, descent; moving: track, arc, spiral, push-in; close: close-up,
 *       profile, groundrise): the last shot's family x0.45, the last two's x0.1, so no family runs three times;</li>
 *   <li>balance: what each shot is owed (its share of every pick so far minus the times it came) raises or lowers it
 *       (x0.35 to x3), so over a long fight the shares still follow the weights the player set.</li>
 * </ul>
 * The player shots (filmPlayerShots) and filmWideShots' far shots get the same treatment: never twice in a row, and a
 * running balance keeps their overall share.
 */
final class FilmPicker {

    static final List<String> BATTLE = List.of("wide", "track", "arc", "pushin", "closeup", "landscape", "zenith",
            "spiral", "telephoto", "descent", "groundrise", "profile");
    static final List<String> PLAYER = List.of("playerback", "playerclose");
    private static final Set<String> FAR = Set.of("wide", "landscape", "telephoto", "zenith", "descent");
    private static final Set<String> MOVING = Set.of("track", "arc", "spiral", "pushin");
    /** filmWideShots turns a battle shot into one of these, filmed from further back. */
    static final List<String> FAR_CAPABLE = List.of("wide", "landscape");

    private static final double[] RECENT = {0, 0.3, 0.6};
    private static final double SAME_FAMILY = 0.45, SAME_FAMILY_TWICE = 0.1, FAMILY_TWO_AGO = 0.6;
    private static final double BALANCE = 0.9, BALANCE_MIN = 0.35, BALANCE_MAX = 3;
    private static final int HISTORY = 6;

    static String familyOf(String shot) {
        if (PLAYER.contains(shot)) return "player";
        if (FAR.contains(shot)) return "far";
        if (MOVING.contains(shot)) return "moving";
        return "close";
    }

    /** What the picker needs to know of the fight right now. */
    record Scene(int count, double spread, double tall, boolean roofed, boolean playerOk, boolean first) {
    }

    /** One pick: the shot word and whether it is one of filmWideShots' far ones. */
    record Choice(String shot, boolean far) {
        boolean player() {
            return PLAYER.contains(shot);
        }
    }

    private final Random rnd;
    /** Newest first: the families of the last shots (a far shot counts as "far" whatever its word). */
    private final Deque<String> families = new ArrayDeque<>();
    private final Deque<String> shots = new ArrayDeque<>();
    private final Map<String, Double> owed = new HashMap<>();
    private double playerOwed, farOwed;
    private boolean playerNext;
    /** Filled by next(), applied by commit(): the shares this pick was made from. */
    private Map<String, Double> pendingShares = Map.of();
    private double pendingPlayer = -1, pendingFar = -1;

    FilmPicker(Random rnd) {
        this.rnd = rnd;
    }

    /** Base weight of a battle shot for this fight (before the player's filmShot percent). */
    static double base(String shot, Scene s) {
        return switch (shot) {
            case "wide" -> 3 + (s.spread() > 12 ? 1.5 : 0) + (s.count() > 20 ? 1 : 0);
            case "arc" -> 2;
            case "landscape" -> s.spread() > 10 ? 0.5 : 0.25;
            case "closeup" -> s.count() <= 2 ? 3 : s.count() <= 4 ? 2 : s.count() <= 10 ? 1 : 0.5;
            case "zenith" -> 0.8;
            case "spiral", "profile" -> s.count() >= 2 ? 1.2 : 0;
            default -> 1;
        };
    }

    /**
     * The next shot, or null when nothing is left once {@code skip} (shots whose view was blocked this time) is out.
     * {@code weight} = the player's filmShot percent per word, {@code playerShare} filmPlayerShots, {@code farShare}
     * filmWideShots (plus the big mob bonus). Call {@link #commit} with the one that is really played.
     */
    Choice next(Scene s, ToIntFunction<String> weight, int playerShare, int farShare, Set<String> skip) {
        List<String> battle = new ArrayList<>();
        for (String b : BATTLE) if (!skip.contains(b) && weight.applyAsInt(b) > 0 && base(b, s) > 0) battle.add(b);
        List<String> players = new ArrayList<>();
        if (s.playerOk()) for (String p : PLAYER) if (!skip.contains(p) && weight.applyAsInt(p) > 0) players.add(p);
        if (battle.isEmpty() && players.isEmpty()) return null;

        // Player shots: filmPlayerShots percent, never two in a row, balanced over the fight.
        pendingPlayer = -1;
        // the first shot is a battle shot, unless there is none to pick (2026-10-29, playtest: only player shots on, a wide
        // shot opened the film)
        if (!players.isEmpty() && (!s.first() || battle.isEmpty())) {
            double share = battle.isEmpty() ? 1 : Math.min(1, playerShare / 100.0);
            double p = share * balance(playerOwed);
            if ("player".equals(families.peekFirst()) && !battle.isEmpty()) p = 0;
            pendingPlayer = share;
            if (battle.isEmpty() || rnd.nextDouble() < Math.min(0.95, p)) {
                String pick = players.size() == 1 ? players.get(0) : players.get(playerNext ? 1 : 0);
                return new Choice(pick, false);
            }
        }
        if (battle.isEmpty()) return null;
        if (s.first() && battle.contains("wide")) return new Choice("wide", false);

        // Shares the player's weights ask for (what the balance follows).
        Map<String, Double> share = new HashMap<>();
        double sum = 0;
        for (String b : battle) {
            double w = base(b, s) * weight.applyAsInt(b) / 100.0;
            share.put(b, w);
            sum += w;
        }
        for (String b : battle) share.put(b, share.get(b) / sum);
        pendingShares = share;

        // filmWideShots: a far wide/landscape, never right after another far-family shot twice.
        pendingFar = -1;
        if (!s.roofed() && farShare > 0) {
            double want = Math.min(1, farShare / 100.0);
            pendingFar = want;
            double p = want * balance(farOwed) * familyFactor("far");
            if (rnd.nextDouble() < Math.min(0.9, p)) {
                List<String> fars = new ArrayList<>();
                for (String b : FAR_CAPABLE) if (battle.contains(b)) fars.add(b);
                // By their own weights too: the old even split made landscape (weight 50) a fifth of all shots.
                String pick = weighted(fars, b -> share.get(b) * recency(b));
                if (pick != null) return new Choice(pick, true);
            }
        }
        String pick = weighted(battle, b -> share.get(b) * recency(b) * familyFactor(familyOf(b))
                * balance(owed.getOrDefault(b, 0.0)));
        if (pick == null) pick = weighted(battle, share::get);   // everything penalised to 0: plain weights
        return new Choice(pick, false);
    }

    /** The shot really played (after the view checks): history, balance and the back/close turn. */
    void commit(Choice c) {
        if (c.player()) {
            playerNext = !playerNext;
            if (pendingPlayer >= 0) playerOwed += pendingPlayer - 1;
        } else {
            if (pendingPlayer >= 0) playerOwed += pendingPlayer;
            if (pendingFar >= 0) farOwed += pendingFar - (c.far() ? 1 : 0);
            for (Map.Entry<String, Double> e : pendingShares.entrySet()) {
                owed.merge(e.getKey(), e.getValue() - (e.getKey().equals(c.shot()) ? 1 : 0), Double::sum);
            }
        }
        pendingShares = Map.of();
        pendingPlayer = -1;
        pendingFar = -1;
        families.addFirst(c.far() ? "far" : familyOf(c.shot()));
        shots.addFirst(c.shot());
        while (families.size() > HISTORY) families.removeLast();
        while (shots.size() > HISTORY) shots.removeLast();
    }

    private double recency(String shot) {
        int i = 0;
        for (String s : shots) {
            if (i >= RECENT.length) break;
            if (s.equals(shot)) return RECENT[i];
            i++;
        }
        return 1;
    }

    private double familyFactor(String family) {
        var it = families.iterator();
        boolean last = it.hasNext() && family.equals(it.next());
        boolean before = it.hasNext() && family.equals(it.next());
        return last ? before ? SAME_FAMILY_TWICE : SAME_FAMILY : before ? FAMILY_TWO_AGO : 1;
    }

    private static double balance(double owed) {
        return Math.max(BALANCE_MIN, Math.min(BALANCE_MAX, Math.exp(BALANCE * owed)));
    }

    private String weighted(List<String> pool, java.util.function.ToDoubleFunction<String> w) {
        double sum = 0;
        double[] ws = new double[pool.size()];
        for (int i = 0; i < ws.length; i++) {
            ws[i] = Math.max(0, w.applyAsDouble(pool.get(i)));
            sum += ws[i];
        }
        if (sum <= 0) return null;
        double roll = rnd.nextDouble() * sum;
        for (int i = 0; i < ws.length; i++) {
            roll -= ws[i];
            if (roll <= 0 && ws[i] > 0) return pool.get(i);
        }
        for (int i = ws.length - 1; i >= 0; i--) if (ws[i] > 0) return pool.get(i);
        return null;
    }
}
