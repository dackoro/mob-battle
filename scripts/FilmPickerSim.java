package dev.dackoro.mobbattle;

import java.util.*;

/**
 * Offline check of the battle film's shot picking (FilmPicker), old picker vs new, no game needed (2026-10-16, user:
 * "simulate a fight with 1 s frames and see whether it follows the percentages or a bad pattern").
 * Run: scripts/sim-film.ps1 (javac of FilmPicker + this file into build/sim, then java).
 * Each scenario plays many battles second by second: shots last filmShotTime 8 s +-25 %, fighters drop over the
 * fight, and each planned shot may have its view blocked (indoor arenas block far and high shots more often).
 */
public final class FilmPickerSim {

    record Scenario(String name, int seconds, int startCount, int endCount, double spread, double tall, boolean roofed,
                    Map<String, Double> blocked) {
    }

    static final Map<String, Integer> WEIGHTS = Map.ofEntries(
            Map.entry("wide", 100), Map.entry("track", 100), Map.entry("arc", 100), Map.entry("pushin", 100),
            Map.entry("closeup", 100), Map.entry("landscape", 50), Map.entry("playerback", 100),
            Map.entry("playerclose", 100), Map.entry("zenith", 40), Map.entry("spiral", 80), Map.entry("telephoto", 70),
            Map.entry("descent", 70), Map.entry("groundrise", 50), Map.entry("profile", 100));
    static final int PLAYER_SHARE = 20, WIDE_SHARE = 30, BIG_BONUS = 15;
    static final int RUNS = 300;

    static final Map<String, Double> OUTDOOR = Map.of();
    static final Map<String, Double> INDOOR = Map.of("landscape", 0.5, "telephoto", 0.5, "zenith", 0.6,
            "descent", 0.3, "wide", 0.15, "track", 0.15, "pushin", 0.1, "closeup", 0.2, "groundrise", 0.2, "arc", 0.15);

    public static void main(String[] args) {
        List<Scenario> all = List.of(
                new Scenario("Royale 14 mobs, big boss, indoor colosseum, 3 min", 180, 14, 2, 20, 6, true, INDOOR),
                new Scenario("Royale 14 mobs, big boss, outdoors, 3 min", 180, 14, 2, 20, 6, false, OUTDOOR),
                new Scenario("Duel 1v1, outdoors, 2 min", 120, 2, 2, 4, 2, false, OUTDOOR),
                new Scenario("Royale 60 mobs, outdoors, 5 min", 300, 60, 2, 35, 2, false, OUTDOOR));
        for (Scenario s : all) {
            System.out.println("=== " + s.name() + " (" + RUNS + " battles) ===");
            Stats old = run(s, false), neu = run(s, true);
            print(old, neu);
            System.out.println("  sample, old: " + old.sample);
            System.out.println("  sample, new: " + neu.sample);
            System.out.println();
        }
    }

    static final class Stats {
        final Map<String, Integer> count = new TreeMap<>();
        int shots, repeats, family2, family3, wideLandscapePairs, far, player, raised;
        int longestFamily;
        String sample = "";
    }

    static Stats run(Scenario s, boolean neu) {
        Stats st = new Stats();
        Random rnd = new Random(42);
        for (int b = 0; b < RUNS; b++) {
            FilmPicker picker = new FilmPicker(rnd);
            List<String> seq = new ArrayList<>();
            List<String> fam = new ArrayList<>();
            String last = null;
            boolean playerNext = false;
            int shotEnd = 0, n = 0;
            for (int t = 0; t < s.seconds(); t++) {   // 1 s frames
                if (t < shotEnd) continue;
                int count = (int) Math.round(s.startCount() + (s.endCount() - s.startCount()) * (double) t / s.seconds());
                double spread = Math.max(3, s.spread() * (0.4 + 0.6 * count / (double) s.startCount()));
                FilmPicker.Scene scene = new FilmPicker.Scene(count, spread, s.tall(), s.roofed(), true, n == 0);
                int wideShare = Math.min(100, WIDE_SHARE + (s.tall() > 4 ? BIG_BONUS : 0));
                String shot;
                boolean far = false, raised = false;
                if (neu) {
                    Set<String> skip = new HashSet<>();
                    FilmPicker.Choice c = null;
                    for (int tries = 0; tries < 4; tries++) {
                        FilmPicker.Choice pick = picker.next(scene, w -> WEIGHTS.getOrDefault(w, 100), PLAYER_SHARE, wideShare, skip);
                        if (pick == null) break;
                        if (rnd.nextDouble() >= s.blocked().getOrDefault(pick.shot(), 0.0)) {
                            c = pick;
                            break;
                        }
                        skip.add(pick.shot());
                        if (pick.player()) skip.addAll(FilmPicker.PLAYER);
                    }
                    if (c == null) {
                        c = new FilmPicker.Choice("wide", false);
                        raised = !s.roofed();
                    }
                    picker.commit(c);
                    shot = c.shot();
                    far = c.far();
                } else {
                    // The picker as it was (FilmCamera.nextPlan / pickBattleShot before this change).
                    boolean player = n > 0 && rnd.nextInt(100) < PLAYER_SHARE
                            && rnd.nextDouble() >= s.blocked().getOrDefault("playerback", 0.0);
                    if (player) {
                        shot = playerNext ? "playerclose" : "playerback";
                        playerNext = !playerNext;
                    } else {
                        shot = oldBattle(scene, last, n, rnd);
                        List<String> wides = new ArrayList<>();
                        for (String w : List.of("wide", "landscape")) if (!w.equals(last)) wides.add(w);
                        if (n > 0 && !wides.isEmpty() && rnd.nextInt(100) < wideShare) {
                            shot = wides.get(rnd.nextInt(wides.size()));
                            far = true;
                        }
                        if (rnd.nextDouble() < s.blocked().getOrDefault(shot, 0.0)) {
                            raised = true;
                            if (Set.of("track", "pushin", "closeup", "profile", "groundrise").contains(shot)) shot = "wide";
                        }
                    }
                }
                String family = far ? "far" : FilmPicker.familyOf(shot);
                st.shots++;
                st.count.merge(shot, 1, Integer::sum);
                if (far) st.far++;
                if (raised) st.raised++;
                if (FilmPicker.PLAYER.contains(shot)) st.player++;
                if (shot.equals(last)) st.repeats++;
                if (!fam.isEmpty() && fam.get(fam.size() - 1).equals(family)) st.family2++;
                if (fam.size() >= 2 && fam.get(fam.size() - 1).equals(family) && fam.get(fam.size() - 2).equals(family)) st.family3++;
                if (last != null && Set.of("wide", "landscape").contains(last) && Set.of("wide", "landscape").contains(shot)) st.wideLandscapePairs++;
                fam.add(family);
                int run = 1;
                for (int i = fam.size() - 2; i >= 0 && fam.get(i).equals(family); i--) run++;
                st.longestFamily = Math.max(st.longestFamily, run);
                seq.add(shot + (far ? "*" : "") + (raised ? "^" : ""));
                last = shot;
                n++;
                int ms = (int) Math.max(1000, 8000 * (1 + 0.25 * (rnd.nextDouble() * 2 - 1)));
                shotEnd = t + (int) Math.ceil(ms / 1000.0);
            }
            if (b == 0) st.sample = String.join(" ", seq.subList(0, Math.min(18, seq.size())));
        }
        return st;
    }

    static String oldBattle(FilmPicker.Scene s, String last, int n, Random rnd) {
        List<String> pool = new ArrayList<>(FilmPicker.BATTLE);
        if (n == 0) return "wide";
        if (last != null) pool.remove(last);
        double[] w = new double[pool.size()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = FilmPicker.base(pool.get(i), s) * WEIGHTS.getOrDefault(pool.get(i), 100) / 100.0;
            sum += w[i];
        }
        double roll = rnd.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll <= 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    static void print(Stats old, Stats neu) {
        System.out.printf("  %-34s %8s %8s%n", "", "old", "new");
        row("same shot twice in a row", old.repeats, old.shots, neu.repeats, neu.shots);
        row("same family twice in a row", old.family2, old.shots, neu.family2, neu.shots);
        row("same family 3 in a row", old.family3, old.shots, neu.family3, neu.shots);
        row("wide/landscape right after wide/landscape", old.wideLandscapePairs, old.shots, neu.wideLandscapePairs, neu.shots);
        System.out.printf("  %-34s %8d %8d%n", "longest family run", old.longestFamily, neu.longestFamily);
        row("far shots (asked 30-45 %)", old.far, old.shots, neu.far, neu.shots);
        row("player shots (asked 20 %)", old.player, old.shots, neu.player, neu.shots);
        row("raised (blocked view fallback)", old.raised, old.shots, neu.raised, neu.shots);
        System.out.println("  share per shot:");
        Set<String> keys = new TreeSet<>(old.count.keySet());
        keys.addAll(neu.count.keySet());
        for (String k : keys) row("    " + k, old.count.getOrDefault(k, 0), old.shots, neu.count.getOrDefault(k, 0), neu.shots);
    }

    static void row(String label, int a, int an, int b, int bn) {
        System.out.printf("  %-34s %7.1f%% %7.1f%%%n", label, 100.0 * a / Math.max(1, an), 100.0 * b / Math.max(1, bn));
    }
}
