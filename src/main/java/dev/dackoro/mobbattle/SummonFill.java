package dev.dackoro.mobbattle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * /mobbattle summon fill (2026-10-14, user: summon mobs that make good battles at random, with the same classifier as
 * the panel's mob list): fills the summon list with random roles that fight, of similar strength (their MaxHealth,
 * PickLists.maxHealthOf), optionally dealt into balanced teams. The Summon Wand or /mobbattle summon then spawns them.
 */
final class SummonFill {

    private SummonFill() {
    }

    /** What the command asked for. */
    record Request(int types, int each, int teams, String mod, PickLists.Kind kind, String group, boolean add) {
    }

    /** The picked roles (in team order when teams > 1), or an error. */
    record Result(List<String> roles, List<Integer> teams, String error) {
    }

    /** Groups that need special ground or are bosses: only when asked for by name. */
    private static final List<String> SPECIAL_GROUPS = List.of("aquatic", "boss");
    /** Starting spread of "similar strength": from health / BAND to health x BAND, widened when too few. */
    private static final double BAND = 2.5;

    static Result pick(Request r) {
        List<PickLists.Entry> pool = new ArrayList<>();
        List<PickLists.Entry> all = PickLists.roles();
        if (all.isEmpty()) return new Result(List.of(), List.of(), "list");
        for (PickLists.Entry e : all) {
            PickLists.Kind k = e.kind();
            if (r.kind() != null ? k != r.kind() : k != PickLists.Kind.FIGHTS) continue;
            if (PickLists.observedNoAttack(e.value())) continue;
            if (!PickLists.ALL.equalsIgnoreCase(r.mod())
                    && !(PickLists.HYTALE.equalsIgnoreCase(r.mod()) ? e.mod() == null : r.mod().equalsIgnoreCase(e.mod()))) continue;
            String g = PickLists.groupOf(e);
            String gl = g == null ? "" : g.toLowerCase(Locale.ROOT);
            if (r.group() != null ? !r.group().equalsIgnoreCase(g) : SPECIAL_GROUPS.contains(gl)) continue;
            String name = e.value();
            // Variants that only change where they walk, and fixed turrets, add nothing to a battle.
            if (name.endsWith("_Patrol") || name.endsWith("_Wander") || name.contains("Turret") || name.startsWith("Tamed_")) continue;
            pool.add(e);
        }
        if (pool.isEmpty()) return new Result(List.of(), List.of(), "none");
        Collections.shuffle(pool);
        // An anchor with a known health, then mobs within the strength band around it; the band widens if needed.
        double anchor = -1;
        for (PickLists.Entry e : pool) {
            double h = PickLists.maxHealthOf(e.value());
            if (h > 0) {
                anchor = h;
                break;
            }
        }
        List<String> picked = new ArrayList<>();
        double band = BAND;
        while (picked.size() < r.types() && band < 1000) {
            for (PickLists.Entry e : pool) {
                if (picked.size() >= r.types()) break;
                if (picked.contains(e.value())) continue;
                double h = PickLists.maxHealthOf(e.value());
                if (anchor > 0 && h > 0 && (h < anchor / band || h > anchor * band)) continue;
                picked.add(e.value());
            }
            band *= 2;
        }
        List<Integer> teams = new ArrayList<>();
        if (r.teams() > 1) {
            // Fewer types than teams would leave a team empty, and the team battle refuses to start.
            if (picked.size() < r.teams()) return new Result(List.of(), List.of(), "types");
            // Balanced: strongest first, dealt in a snake (0,1,..,n-1,n-1,..,0) over the first configured team slots.
            List<Integer> slots = new ArrayList<>();
            for (int s = 0; s < 64 && slots.size() < r.teams(); s++) if (Teams.exists(s)) slots.add(s);
            if (slots.size() < r.teams()) return new Result(List.of(), List.of(), "teams");
            picked.sort((a, b) -> Double.compare(PickLists.maxHealthOf(b), PickLists.maxHealthOf(a)));
            for (int i = 0; i < picked.size(); i++) {
                int round = i / slots.size(), pos = i % slots.size();
                teams.add(slots.get(round % 2 == 0 ? pos : slots.size() - 1 - pos));
            }
        }
        return new Result(picked, teams, null);
    }

    /** Puts the result into the summon list (replacing it unless {@code add}). Returns how many entries. */
    static int apply(Request r, Result res) {
        if (!r.add()) {
            SummonList.ENTRIES.clear();
            SummonList.TEAMS.clear();
        }
        for (int i = 0; i < res.roles().size(); i++) {
            String role = res.roles().get(i);
            int team = res.teams().isEmpty() ? -1 : res.teams().get(i);
            // add: the same mob and team already listed gets more, as with summon add (a random count stays as it is).
            String listed = r.add() ? SummonList.entryOf(role, team) : null;
            if (listed != null) {
                String count = SummonList.ENTRIES.get(listed);
                if (count != null && count.chars().allMatch(Character::isDigit)) {
                    long sum = Long.parseLong(count) + r.each();
                    SummonList.ENTRIES.put(listed, String.valueOf(Math.min(sum, FxSettings.MAX_MOBS_LIMIT)));
                    continue;
                }
            }
            String key = SummonList.newKey(role);
            SummonList.ENTRIES.put(key, String.valueOf(r.each()));
            if (team >= 0) SummonList.TEAMS.put(key, team);
        }
        return res.roles().size();
    }
}
