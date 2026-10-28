package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * /mobbattle test ... (admin, 2026-10-13, user: automate the feature tests from the server console, without /sudo
 * and without having to look at a mob). Everything works by coordinates and entity ids, so the console can run it:
 * <ul>
 *   <li>spawn &lt;role&gt; &lt;here|x y z&gt; [count] [world]: spawns and prints each id ({@code last} = the newest).</li>
 *   <li>list / clear: the mobs spawned by these tests that are still alive; clear removes them.</li>
 *   <li>walk &lt;id|last&gt; &lt;x&gt; &lt;z&gt;: a path to that block, as vanilla /npc path (roles without a path
 *   behaviour ignore it).</li>
 *   <li>chase &lt;id|last&gt; &lt;here|x y z&gt;: a stationary training dummy there, and the mob turns on it, so any
 *   mob that can attack runs from where it is to that spot (walk only works for the few roles with a path behaviour:
 *   a cow ignored it, 2026-10-13).</li>
 *   <li>duel &lt;roleA&gt; &lt;roleB&gt; &lt;distance&gt; &lt;here|x y z&gt; [world]: spawns both facing each other,
 *   starts a duel through the API and logs every hit (damage, cause, center and edge distance); a summary at the
 *   end (winner, time, hits, damage, reach).</li>
 *   <li>perf &lt;role&gt; &lt;count&gt; &lt;here|x y z&gt; [world]: the world's tick time over 10 s before and after
 *   spawning them.</li>
 *   <li>royale &lt;role:count+...&gt; &lt;here|x y z&gt; &lt;radius&gt;: spawns the mix and starts a royale there, with a
 *   summary at the end.</li>
 *   <li>wave &lt;heal|cleanse|effect|morph|shuffle|kill&gt; &lt;here|x y z&gt; [world] [effect|model]: a wand's held-click wave
 *   from that spot with the current settings; says how many it reached and when the ring got to them.</li>
 *   <li>stats &lt;id|last&gt;: one line with health, size, buffs, effects, battle, calm, army and morph.</li>
 *   <li>expect &lt;id|last&gt; &lt;field&gt; &lt;op&gt; &lt;value&gt;: a check, ✅/❌ in the log; a script counts them.</li>
 *   <li>camera &lt;style&gt; &lt;player&gt; [seconds]: an intro camera style over the test mobs, for that player.</li>
 *   <li>snapshot / restore [name]: copies fx.properties (settings and lists) to tests/snapshots and back.</li>
 *   <li>run &lt;script&gt; / stop: runs {@code <data dir>/tests/<script>.txt} (one command per line, {@code wait
 *   <seconds>}, {@code say <text>}, # comments) as whoever ran it.</li>
 * </ul>
 * Every reply also goes to the server log as "[test] ...". Test mobs are tracked by UUID in memory only.
 */
final class TestTools {

    static final String USAGE = "Usage: /mobbattle test spawn <role> <here|x y z> [count] [world] | list | clear | walk <id|last> <x> <z>"
            + " | chase <id|last> <here|x y z> | duel <roleA> <roleB> <distance> <here|x y z> [world] | royale <role:count+...> <here|x y z> <radius> [world]"
            + " | wave <heal|cleanse|effect|morph|shuffle|kill> <here|x y z> [world] [effect|model] | stats <id|last> | expect <id|last> <field> <op> <value>"
            + " | camera <style> <player> [seconds] | thumbnail [pose auto|idle|run|jump|<set>] [t 0..1] [side left|right|front|profileLeft|profileRight] [size N] [flat] | hologram on|off | mask [seconds] | send [seconds] | thumbnails <rebuild [force] [mod:<m>|<text>]|status> | snapshot [name] | restore [name] | perf <role> <count> <here|x y z> [world] | run <script> | stop";
    private static final String OK = "#7dff8a";
    private static final String INFO = "#c8e6ff";
    private static final String ERR = "#ff6b6b";
    static final int MAX_SPAWN = FxSettings.MAX_MOBS_LIMIT;
    static final int MAX_PERF = FxSettings.MAX_MOBS_LIMIT;
    /** Perf: wait this long after the spawn, so the spawn tick itself is out of the 10 s window read after. */
    private static final long PERF_SETTLE_MS = 12_000;
    /** A duel test still running after this long stops logging. */
    private static final long DUEL_MAX_MS = TimeUnit.MINUTES.toMillis(20);
    private static final int MAX_SCRIPT_LINES = 500;
    /** Script: pause after each command, so its world-thread part has run before the next line. */
    private static final long COMMAND_GAP_MS = 300;

    /** Mobs spawned by these tests: UUID -> world name. */
    private static final Map<UUID, String> SPAWNED = new ConcurrentHashMap<>();
    private static volatile UUID last;

    private TestTools() {
    }

    static void run(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (!Perms.admin(to)) {
            say(to, "No permission (needs " + Perms.ADMIN + ").", ERR);
            return;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        try {
            switch (sub) {
                case "spawn" -> spawnCommand(ctx, a);
                case "list" -> list(to);
                case "clear" -> clear(to);
                case "walk" -> walk(to, a);
                case "chase" -> chase(ctx, a);
                case "duel" -> duelCommand(ctx, a);
                case "perf" -> perfCommand(ctx, a);
                case "royale" -> royaleCommand(ctx, a);
                case "wave" -> waveCommand(ctx, a);
                case "stats" -> stats(to, a);
                case "roles" -> roles(to, a);
                case "verify" -> verifyCommand(ctx, a);
                case "calls" -> callsCommand(ctx, a);
                case "ai" -> aiCommand(ctx, a);
                case "jump" -> jumpCommand(ctx, a);
                case "wipe" -> wipe(ctx, a);
                case "speed" -> speedCommand(ctx, a);
                case "hit" -> hitCommand(ctx, a);
                case "wand" -> wandCommand(ctx, a);
                case "equip" -> equipCommand(ctx, a);
                case "tick" -> {
                    // test tick [world]: the world's tick time over the last 10 s (to read during a running battle).
                    World w = a.length > 1 ? Universe.get().getWorld(a[1]) : Universe.get().getDefaultWorld();
                    if (w == null) say(to, "No world " + (a.length > 1 ? a[1] : "") + ".", ERR);
                    else say(to, "Tick time in " + w.getName() + " over the last 10 s: " + tickStats(w) + ".", OK);
                }
                case "expect" -> expect(to, a);
                case "camera" -> camera(to, a);
                case "thumbnail" -> ThumbnailTest.command(ctx, a);
                case "thumbnails" -> thumbnailsCommand(to, a);
                case "vsframes" -> vsFramesCommand(to, a);
                case "vs" -> VsScreen.command(ctx, a);
                case "mask" -> UiTests.command(ctx, a, false);
                case "send" -> UiTests.command(ctx, a, true);
                case "playerlook" -> PlayerLookTest.command(ctx, a);
                case "hologram" -> MorphHologram.command(ctx, a);
                case "snapshot" -> snapshot(to, a.length > 1 ? a[1] : "default");
                case "restore" -> restore(to, a.length > 1 ? a[1] : "default");
                case "run" -> runScript(to, a.length > 1 ? a[1] : null);
                case "stop" -> {
                    boolean stopped = Script.stopAll();
                    say(to, stopped ? "Script stopped." : "No script running.", stopped ? OK : ERR);
                }
                default -> say(to, USAGE, ERR);
            }
        } catch (RuntimeException e) {
            say(to, "Test failed: " + e, ERR);
            MobBattlePlugin.log("[test] " + e);
        }
    }

    /**
     * test thumbnails rebuild [force] [mod:&lt;m&gt;|mod:hytale|&lt;text&gt;] | status (2026-10-16): ThumbnailCache in the
     * background. Without force only missing or outdated pictures are drawn; mod: uses the panel's model list.
     */
    private static void thumbnailsCommand(CommandSender to, String[] a) {
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (what.equals("status")) {
            say(to, ThumbnailCache.running() ? "Thumbnails are being made in the background." : "No thumbnail run going.", INFO);
            return;
        }
        if (!what.equals("rebuild")) {
            say(to, "Usage: /mobbattle test thumbnails <rebuild [force] [mod:<m>|<text>]|status>", ERR);
            return;
        }
        boolean force = false;
        String mod = null, text = null;
        for (int i = 2; i < a.length; i++) {
            String w = a[i];
            if (w.equalsIgnoreCase("force")) force = true;
            else if (w.regionMatches(true, 0, "mod:", 0, 4)) mod = w.substring(4);
            else text = w.toLowerCase(Locale.ROOT);
        }
        java.util.function.Predicate<String> which = id -> true;
        if (mod != null) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            boolean hytale = mod.equalsIgnoreCase("hytale");
            for (PickLists.Entry e : PickLists.models()) {
                if (hytale ? e.mod() == null : mod.equalsIgnoreCase(e.mod())) ids.add(e.value());
            }
            which = ids::contains;
        }
        if (text != null) {
            String t = text;
            java.util.function.Predicate<String> before = which;
            which = id -> before.test(id) && id.toLowerCase(Locale.ROOT).contains(t);
        }
        boolean started = ThumbnailCache.rebuild(which, force, r -> say(to, "Thumbnails: " + ThumbnailCache.describe(r), OK));
        say(to, started ? "Thumbnails: making them in the background" + (force ? " (all again)" : " (missing or outdated only)") + "..."
                : "A thumbnail run is already going.", started ? INFO : ERR);
    }

    /**
     * test vsframes &lt;current|body&gt; [text] (2026-10-17, user: try framing the VS pictures by the body before changing
     * the real ones): VsScreen.testFrames into &lt;picture cache&gt;/vs-test/&lt;method&gt;/, in the background. Preview it
     * out of the game: python scripts/vs-preview.py columns 8 --folder &lt;that folder&gt;.
     */
    private static void vsFramesCommand(CommandSender to, String[] a) {
        String method = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (!method.equals("current") && !method.equals("body")) {
            say(to, "Usage: /mobbattle test vsframes <current|body> [wide:<0.5-1>] [text]", ERR);
            return;
        }
        // wide:<fit> (2026-10-18, user: a Glutton's arm was cut): how much of a wide mob's width the square keeps
        double fit = -1;
        String only = null;
        for (int i = 2; i < a.length; i++) {
            if (a[i].regionMatches(true, 0, "wide:", 0, 5)) {
                try {
                    fit = Math.max(0.5, Math.min(1.0, Double.parseDouble(a[i].substring(5))));
                } catch (NumberFormatException e) {
                    say(to, "wide: takes a number from 0.5 to 1.", ERR);
                    return;
                }
            } else {
                only = a[i];
            }
        }
        Path root = PictureCache.root();
        if (root == null) {
            say(to, "No picture cache folder.", ERR);
            return;
        }
        Path dir = root.resolve("vs-test").resolve(fit > 0 ? method + "-wide" + Math.round(fit * 100) : method);
        final double useFit = fit;
        final String onlyText = only;
        long t0 = System.nanoTime();
        say(to, "VS frame test (" + method + "): drawing into " + dir + " ...", INFO);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                int[] r = (useFit > 0 ? VsScreen.testFrames(method.equals("body"), onlyText, dir, useFit, n -> {
                    if (n % 100 == 0) say(to, "VS frame test (" + method + "): " + n + " done", INFO);
                }) : VsScreen.testFrames(method.equals("body"), onlyText, dir, n -> {
                    if (n % 100 == 0) say(to, "VS frame test (" + method + "): " + n + " done", INFO);
                }));
                say(to, "VS frame test (" + method + "): " + r[0] + " of " + r[2] + " drawn" + (r[1] > 0 ? ", " + r[1] + " failed" : "")
                        + " in " + MobBattlePlugin.took(t0) + ". Preview: python scripts/vs-preview.py columns 8 --folder \"" + dir + "\"", OK);
            } catch (Exception e) {
                say(to, "VS frame test failed: " + e, ERR);
            }
        });
    }

    // ---- targets by id ----

    /** "last", "id:&lt;uuid&gt;" or a bare UUID. */
    static boolean isIdWord(String word) {
        if (word == null) return false;
        if (word.equalsIgnoreCase("last") || word.regionMatches(true, 0, "id:", 0, 3)) return true;
        return parseUuid(word) != null;
    }

    private static UUID parseUuid(String word) {
        String w = word.regionMatches(true, 0, "id:", 0, 3) ? word.substring(3) : word;
        if (w.equalsIgnoreCase("last")) return last;
        try {
            return w.length() == 36 ? UUID.fromString(w) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The entity an id word names, in whichever world has it; null if none. Blocks on each world's thread
     * (World.getEntityRef), so never call it from a world thread.
     */
    static Ref<EntityStore> resolve(String word) {
        UUID id = parseUuid(word);
        if (id == null) return null;
        String known = SPAWNED.get(id);
        World first = known != null ? Universe.get().getWorld(known) : null;
        if (first != null) {
            Ref<EntityStore> r = first.getEntityRef(id);
            if (r != null && r.isValid()) return r;
        }
        for (World w : Universe.get().getWorlds().values()) {
            if (w == first) continue;
            Ref<EntityStore> r = w.getEntityRef(id);
            if (r != null && r.isValid()) return r;
        }
        return null;
    }

    // ---- spawn / list / clear / walk ----

    private static void spawnCommand(CommandContext ctx, String[] a) {
        if (a.length < 3) {
            say(ctx.sender(), "Usage: /mobbattle test spawn <role> <here|x y z> [count] [world] [calm]", ERR);
            return;
        }
        String role = SummonList.findRole(a[1]);
        if (role == null) {
            say(ctx.sender(), "No mob role " + a[1] + ".", ERR);
            return;
        }
        Place place = place(ctx, a, 2);
        if (place == null) return;
        int count = 1;
        if (place.next < a.length && a[place.next].matches("\\d{1,3}")) count = Math.max(1, Math.min(MAX_SPAWN, Integer.parseInt(a[place.next])));
        int n = count;
        // "calm": into SummonCalm as a summoned mob would be (needs summonCalm summons|all), to test calm summons.
        boolean calm = java.util.Arrays.stream(a).anyMatch("calm"::equalsIgnoreCase);
        place.world.execute(() -> {
            Store<EntityStore> store = place.world.getEntityStore().getStore();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double ang = Math.PI * 2 * i / n, r = n == 1 ? 0 : 1.5 + n * 0.15;
                Ref<EntityStore> mob = spawn(role, new Vector3d(place.pos.x + Math.cos(ang) * r, place.pos.y, place.pos.z + Math.sin(ang) * r),
                        (float) ang, store);
                if (mob != null && calm) SummonCalm.add(mob, store);
                if (mob != null) ids.add(idOf(mob, store) + " " + where(mob, store));
            }
            if (ids.isEmpty()) {
                say(ctx.sender(), "Could not spawn " + role + ".", ERR);
                return;
            }
            say(ctx.sender(), "Spawned " + ids.size() + " " + role + (calm ? " (calm: " + FxSettings.summonCalm + ")" : "") + " in " + place.world.getName() + " (last = the newest): "
                    + String.join(", ", ids), OK);
        });
    }

    /** One test mob at pos (feet put on the standable spot nearest to pos.y), yaw in radians. World thread. */
    private static Ref<EntityStore> spawn(String role, Vector3d pos, float yaw, Store<EntityStore> store) {
        double y = FightFx.standableY(pos.x, pos.z, pos.y, 8, store);
        Vector3d at = new Vector3d(pos.x, Double.isFinite(y) ? y : pos.y, pos.z);
        var result = NPCPlugin.get().spawnNPC(store, role, null, at, new Rotation3f(0f, yaw, 0f));
        if (result == null || result.first() == null) return null;
        Ref<EntityStore> mob = result.first();
        // Test mobs drop no loot when they die (user, 2026-10-13: the test field filled up with items).
        NPCEntity npc = store.getComponent(mob, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null) npc.getRole().setDeathItemsDropped();
        UUID id = idOf(mob, store);
        if (id != null) {
            SPAWNED.put(id, store.getExternalData().getWorld().getName());
            last = id;
        }
        return mob;
    }

    private static void list(CommandSender to) {
        if (SPAWNED.isEmpty()) {
            say(to, "No test mobs.", INFO);
            return;
        }
        Map<String, List<UUID>> byWorld = new java.util.HashMap<>();
        SPAWNED.forEach((id, w) -> byWorld.computeIfAbsent(w, k -> new ArrayList<>()).add(id));
        byWorld.forEach((name, ids) -> {
            World w = Universe.get().getWorld(name);
            if (w == null) {
                ids.forEach(SPAWNED::remove);
                return;
            }
            w.execute(() -> {
                Store<EntityStore> store = w.getEntityStore().getStore();
                List<String> lines = new ArrayList<>();
                for (UUID id : ids) {
                    Ref<EntityStore> r = w.getEntityRef(id);
                    if (r == null || !r.isValid() || !BattleManager.isAlive(r, store)) {
                        SPAWNED.remove(id);
                        continue;
                    }
                    lines.add(ChaosWand.nameOf(r, store) + " " + id + " " + where(r, store) + " " + health(r, store)
                            + (id.equals(last) ? " (last)" : ""));
                }
                say(to, lines.isEmpty() ? "No test mobs alive in " + name + "." : "Test mobs in " + name + ": " + String.join(" | ", lines), INFO);
            });
        });
    }

    private static void clear(CommandSender to) {
        int[] removed = {0};
        Map<String, List<UUID>> byWorld = new java.util.HashMap<>();
        SPAWNED.forEach((id, w) -> byWorld.computeIfAbsent(w, k -> new ArrayList<>()).add(id));
        SPAWNED.clear();
        last = null;
        if (byWorld.isEmpty()) {
            say(to, "No test mobs.", INFO);
            return;
        }
        byWorld.forEach((name, ids) -> {
            World w = Universe.get().getWorld(name);
            if (w == null) return;
            w.execute(() -> {
                Store<EntityStore> store = w.getEntityStore().getStore();
                int n = 0;
                for (UUID id : ids) {
                    Ref<EntityStore> r = w.getEntityRef(id);
                    if (r != null && r.isValid()) {
                        store.removeEntity(r, RemoveReason.REMOVE);
                        n++;
                    }
                }
                removed[0] += n;
                say(to, "Removed " + n + " test mobs in " + name + ".", OK);
            });
        });
    }

    private static void walk(CommandSender to, String[] a) {
        if (a.length < 4) {
            say(to, "Usage: /mobbattle test walk <id|last> <x> <z>", ERR);
            return;
        }
        double x, z;
        try {
            x = Double.parseDouble(a[2]);
            z = Double.parseDouble(a[3]);
        } catch (NumberFormatException e) {
            say(to, "Usage: /mobbattle test walk <id|last> <x> <z>", ERR);
            return;
        }
        Ref<EntityStore> mob = resolve(a[1]);
        if (mob == null) {
            say(to, "No entity " + a[1] + ".", ERR);
            return;
        }
        Store<EntityStore> store = mob.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!mob.isValid()) return;
            NPCEntity npc = store.getComponent(mob, NPCEntity.getComponentType());
            Vector3d from = ChaosWand.positionOf(mob, store);
            if (npc == null || from == null) {
                say(to, "That is not a mob.", ERR);
                return;
            }
            double y = FightFx.standableY(x, z, from.y, 16, store);
            Vector3d target = new Vector3d(x, Double.isFinite(y) ? y : from.y, z);
            Rotation3f rot = new Rotation3f(0f, (float) Math.atan2(-(target.x - from.x), -(target.z - from.z)), 0f);
            // As vanilla /npc path: a transient path from where it stands. A role whose idle has no path behaviour
            // never follows it (vanilla's command has the same limit).
            var path = new com.hypixel.hytale.builtin.path.path.TransientPath();
            path.addWaypoint(new Vector3d(from), rot);
            path.addWaypoint(target, rot);
            npc.getPathManager().setTransientPath(path);
            say(to, String.format(Locale.ROOT, "%s walks to %.1f %.1f %.1f (%.1f blocks). Roles with no path behaviour ignore it.",
                    ChaosWand.nameOf(mob, store), target.x, target.y, target.z, from.distance(target)), OK);
        });
    }

    /** Training dummy for chase: it never moves, so the chaser runs exactly to the given spot. */
    private static final String CHASE_DUMMY = "Test_Dummy_Stationary";

    private static void chase(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (a.length < 3) {
            say(to, "Usage: /mobbattle test chase <id|last> <here|x y z>", ERR);
            return;
        }
        Ref<EntityStore> mob = resolve(a[1]);
        if (mob == null) {
            say(to, "No entity " + a[1] + ".", ERR);
            return;
        }
        Place place = place(ctx, a, 2);
        if (place == null) return;
        Store<EntityStore> store = mob.getStore();
        if (place.world != store.getExternalData().getWorld()) {
            say(to, "That spot is in another world than the mob.", ERR);
            return;
        }
        place.world.execute(() -> {
            if (!mob.isValid()) return;
            Vector3d from = ChaosWand.positionOf(mob, store);
            UUID keep = last;
            Ref<EntityStore> dummy = spawn(CHASE_DUMMY, place.pos, 0f, store);
            last = keep; // last stays the chaser, for the measure that follows
            if (from == null || dummy == null) {
                say(to, "Could not place the dummy.", ERR);
                return;
            }
            FightManager.Result r = FightManager.turnAgainst(mob, dummy, store);
            Vector3d at = ChaosWand.positionOf(dummy, store);
            say(to, String.format(Locale.ROOT, "%s (%s) chases a dummy %s, %.1f blocks away: %s.%s", ChaosWand.nameOf(mob, store),
                    a[1], where(dummy, store), at != null ? from.distance(at) : 0, r,
                    r == FightManager.Result.AGGRESSIVE ? " Measure it with /mobbattle measure " + a[1] + "."
                            : " It can't attack, so it won't run there."), r == FightManager.Result.AGGRESSIVE ? OK : ERR);
        });
    }

    // ---- duel with hit log ----

    /** A running duel test: its fighters (A, B) and what they did. */
    private static final class DuelLog {
        final CommandSender to;
        final Ref<EntityStore> a, b;
        final String nameA, nameB;
        final long startedAt = System.currentTimeMillis();
        volatile UUID eventId;
        volatile long firstHit;
        final int[] hits = new int[2];
        final double[] damage = new double[2];
        final double[] minReach = {Double.MAX_VALUE, Double.MAX_VALUE}, maxReach = {0, 0};
        final double[] minCenter = {Double.MAX_VALUE, Double.MAX_VALUE}, maxCenter = {0, 0};
        int cancelled, other;

        DuelLog(CommandSender to, Ref<EntityStore> a, Ref<EntityStore> b, String nameA, String nameB) {
            this.to = to;
            this.a = a;
            this.b = b;
            this.nameA = nameA;
            this.nameB = nameB;
        }

        int side(Ref<EntityStore> r) {
            return r == a ? 0 : r == b ? 1 : -1;
        }

        String name(int side) {
            return side == 0 ? nameA : nameB;
        }
    }

    private static final Map<Ref<EntityStore>, DuelLog> WATCH = new ConcurrentHashMap<>();
    private static final Map<UUID, DuelLog> DUELS = new ConcurrentHashMap<>();
    private static volatile AutoCloseable subscription;

    /** DebugHitSystem: whether any duel test is logging hits (every hit in every world asks this). */
    static boolean watching() {
        return !WATCH.isEmpty() || !VERIFY.isEmpty() || !CALLS.isEmpty();
    }

    /** A hit as our filters leave it (DebugHitSystem, world thread). */
    /**
     * test verify isolation: true when {@code victim} is a checked mob and the hit does not come from its own copy
     * (another pair's area attack, a map-spawned mob, a hit with no entity source). DebugHitSystem cancels it.
     */
    static boolean verifyBlocks(Ref<EntityStore> attacker, Ref<EntityStore> victim) {
        if (VERIFY.isEmpty() || victim == null) return false;
        VerifyPair pair = VERIFY.get(victim);
        if (pair == null) return false;
        return attacker == null || attacker == victim || (attacker != pair.mob && attacker != pair.dummy);
    }

    static void onHit(Ref<EntityStore> attacker, Ref<EntityStore> victim, float amount, String cause, boolean cancelled,
                      ComponentAccessor<EntityStore> buffer) {
        // test verify: a hit (even cancelled) from a checked mob on its own dummy.
        // Either copy of the pair hitting the other one counts.
        VerifyPair pair = attacker != null ? VERIFY.get(attacker) : null;
        if (pair != null && victim != attacker && (victim == pair.mob || victim == pair.dummy)
                && amount > FightManager.PROVOKE_DAMAGE * 2) {
            if (pair.hits == 0) pair.firstHit = System.currentTimeMillis();
            pair.hits++;
        }
        // test calls: an onlooker hitting a fighter (any cell), cancelled by battle isolation or not.
        CallCell byCell = attacker != null ? CALLS.get(attacker) : null;
        CallCell onCell = CALLS.get(victim);
        if (byCell != null && onCell != null && !byCell.fighter(attacker) && onCell.fighter(victim) && amount > FightManager.PROVOKE_DAMAGE * 2) {
            byCell.hits++;
        }
        DuelLog log = WATCH.get(victim);
        if (log == null && attacker != null) log = WATCH.get(attacker);
        if (log == null) return;
        int from = attacker != null ? log.side(attacker) : -1, to = log.side(victim);
        String line;
        boolean provoke = amount <= FightManager.PROVOKE_DAMAGE * 2;
        if (from >= 0 && to >= 0 && from != to) {
            Vector3d pa = ChaosWand.positionOf(attacker, buffer), pv = ChaosWand.positionOf(victim, buffer);
            double center = pa != null && pv != null ? Math.hypot(pa.x - pv.x, pa.z - pv.z) : -1;
            double edge = center < 0 ? -1 : Math.max(0, center - halfWidth(attacker, buffer) - halfWidth(victim, buffer));
            if (cancelled) {
                log.cancelled++;
            } else if (!provoke) {
                // A provocation (FightManager.provoke, 0.01 on every target switch) is not a hit.
                if (log.firstHit == 0) log.firstHit = System.currentTimeMillis();
                log.hits[from]++;
                log.damage[from] += amount;
                if (edge >= 0) {
                    log.minReach[from] = Math.min(log.minReach[from], edge);
                    log.maxReach[from] = Math.max(log.maxReach[from], edge);
                    log.minCenter[from] = Math.min(log.minCenter[from], center);
                    log.maxCenter[from] = Math.max(log.maxCenter[from], center);
                }
            }
            line = String.format(Locale.ROOT, "%s -> %s: %.2f (%s)%s, distance %.2f center / %.2f edge, %s before it",
                    log.name(from), log.name(to), amount, cause, cancelled ? " CANCELLED" : provoke ? " provocation, not counted" : "", center, edge, health(victim, buffer));
        } else {
            log.other++;
            line = String.format(Locale.ROOT, "%s hit by %s: %.2f (%s)%s", log.name(to >= 0 ? to : from),
                    attacker == null ? "no entity" : from >= 0 ? "itself" : ChaosWand.nameOf(attacker, buffer), amount, cause,
                    cancelled ? " CANCELLED" : "");
        }
        MobBattlePlugin.log("[test] hit " + line);
    }

    private static void duelCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (a.length < 5) {
            say(to, "Usage: /mobbattle test duel <roleA> <roleB> <distance> <here|x y z> [world]", ERR);
            return;
        }
        String roleA = SummonList.findRole(a[1]), roleB = SummonList.findRole(a[2]);
        if (roleA == null || roleB == null) {
            say(to, "No mob role " + (roleA == null ? a[1] : a[2]) + ".", ERR);
            return;
        }
        double dist;
        try {
            dist = Math.max(1, Math.min(64, Double.parseDouble(a[3])));
        } catch (NumberFormatException e) {
            say(to, "The distance must be a number of blocks.", ERR);
            return;
        }
        Place place = place(ctx, a, 4);
        if (place == null) return;
        ensureSubscribed();
        World world = place.world;
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            // A on the west, B on the east, facing each other (yaw = atan2(-dx, -dz)).
            Ref<EntityStore> mobA = spawn(roleA, new Vector3d(place.pos.x - dist / 2, place.pos.y, place.pos.z), (float) (-Math.PI / 2), store);
            Ref<EntityStore> mobB = spawn(roleB, new Vector3d(place.pos.x + dist / 2, place.pos.y, place.pos.z), (float) (Math.PI / 2), store);
            if (mobA == null || mobB == null) {
                say(to, "Could not spawn both fighters.", ERR);
                return;
            }
            UUID idA = idOf(mobA, store), idB = idOf(mobB, store);
            String nameA = "A " + ChaosWand.nameOf(mobA, store), nameB = "B " + ChaosWand.nameOf(mobB, store);
            DuelLog log = new DuelLog(to, mobA, mobB, nameA, nameB);
            WATCH.put(mobA, log);
            WATCH.put(mobB, log);
            say(to, nameA + " (" + idA + " " + where(mobA, store) + ", " + health(mobA, store) + ") vs " + nameB + " (" + idB + " "
                    + where(mobB, store) + ", " + health(mobB, store) + "), " + dist + " blocks apart. Hits go to the server log as [test] hit.", INFO);
            MobBattlePlugin.getApi().startDuel(world, idA, idB).thenAccept(r -> {
                if (!r.accepted()) {
                    say(to, "Duel refused: " + r.error(), ERR);
                    unwatch(log);
                    return;
                }
                log.eventId = r.snapshot().eventId();
                DUELS.put(log.eventId, log);
            });
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                if (WATCH.get(mobA) == log) {
                    say(to, nameA + " vs " + nameB + ": still running after 20 min, no longer logged.", ERR);
                    unwatch(log);
                }
            }, DUEL_MAX_MS, TimeUnit.MILLISECONDS);
        });
    }

    private static void ensureSubscribed() {
        if (subscription != null) return;
        synchronized (TestTools.class) {
            if (subscription != null) return;
            subscription = MobBattlePlugin.getApi().subscribe(event -> {
                var snap = event.snapshot();
                DuelLog log = snap != null && snap.eventId() != null ? DUELS.remove(snap.eventId()) : null;
                if (log != null) summary(log, snap);
                RoyaleLog royale = snap != null && snap.eventId() != null ? ROYALES.remove(snap.eventId()) : null;
                if (royale != null) royaleSummary(royale, snap);
            });
        }
    }

    private static void summary(DuelLog log, MobBattleApi.Snapshot snap) {
        unwatch(log);
        UUID winner = snap.winners().isEmpty() ? null : snap.winners().get(0);
        Store<EntityStore> store = log.a.isValid() ? log.a.getStore() : log.b.isValid() ? log.b.getStore() : null;
        String who = winner == null ? "nobody" : store != null && log.a.isValid() && winner.equals(idOf(log.a, store)) ? log.nameA
                : store != null && log.b.isValid() && winner.equals(idOf(log.b, store)) ? log.nameB : winner.toString();
        long now = System.currentTimeMillis();
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "Duel over (%s): winner %s, %.1f s after the command, %.1f s after the first hit.",
                snap.endReason(), who, (now - log.startedAt) / 1000.0, log.firstHit == 0 ? 0 : (now - log.firstHit) / 1000.0));
        for (int side = 0; side < 2; side++) {
            s.append(String.format(Locale.ROOT, " %s: %d hits, %.1f damage", log.name(side), log.hits[side], log.damage[side]));
            if (log.hits[side] > 0 && log.maxCenter[side] > 0) {
                s.append(String.format(Locale.ROOT, ", reach %.2f-%.2f edge (%.2f-%.2f center)", log.minReach[side], log.maxReach[side],
                        log.minCenter[side], log.maxCenter[side]));
            }
            s.append('.');
        }
        if (log.cancelled > 0) s.append(" Cancelled hits: ").append(log.cancelled).append('.');
        if (log.other > 0) s.append(" Other hits (falls, outsiders...): ").append(log.other).append('.');
        say(log.to, s.toString(), OK);
    }

    private static void unwatch(DuelLog log) {
        WATCH.remove(log.a, log);
        WATCH.remove(log.b, log);
        if (log.eventId != null) DUELS.remove(log.eventId, log);
    }

    // ---- wand (wand actions as a player, on players or mobs, 2026-10-14) ----

    /**
     * test wand &lt;owner&gt; &lt;duel|focus|brawl|kill|snap&gt; [target] [target2] (2026-10-14, user: test the mod with
     * players, two real players on the test server): what the owner's wand click would do, through the same code.
     * Targets: a player name, last, id:&lt;uuid&gt; or a bare UUID; duel takes two (a player vs a mob, two players...),
     * focus/kill one, brawl and snap a target or none (around the owner). Players still need playerFights for the
     * battle kind (and not Creative), Kill needs killPlayers; their refusals are the wand's own chat lines.
     */
    /**
     * test equip lists [mod:<m>|mod:hytale] [type:<t>] [group:<g>]: how many items each Equipment Wand slot lists with
     * those filters, plus the mods/types/groups the filter rows offer. test equip read <id|last>: what a mob wears and
     * whether it has an off hand. test equip set <id|last> <slot 0-5> <item|none>: one slot, as the panel does.
     */
    private static void equipCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (what) {
            case "lists" -> {
                String mod = "", type = "", group = "";
                for (int i = 2; i < a.length; i++) {
                    String w = a[i];
                    if (w.startsWith("mod:")) mod = w.substring(4);
                    else if (w.startsWith("type:")) type = w.substring(5);
                    else if (w.startsWith("group:")) group = w.substring(6);
                }
                long t0 = System.nanoTime();
                int total = EquipWand.catalog().size();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                say(to, "Items: " + total + " (catalog " + ms + " ms). Mods: " + EquipWand.mods(), OK);
                say(to, "Types for mod '" + mod + "': " + EquipWand.typesFor(mod), OK);
                say(to, "Groups for '" + mod + "'/'" + type + "': " + EquipWand.groupsFor(mod, type), OK);
                for (int s = 0; s < EquipWand.SLOTS.length; s++) {
                    var l = EquipWand.listFor(s, mod, type, group);
                    String sample = l.stream().limit(4).map(EquipWand.ItemInfo::id).reduce((x, y) -> x + ", " + y).orElse("");
                    say(to, EquipWand.SLOTS[s] + ": " + l.size() + " items, e.g. " + sample, OK);
                }
                // Translated names as the panel shows them (en-US and es-ES).
                var i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
                for (String id : List.of("Weapon_Sword_Adamantite", "Armor_Iron_Head", "Weapon_Shield_Scrap_Spiked")) {
                    var item = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(id);
                    String key = item != null ? item.getTranslationKey() : null;
                    String short_ = key != null && key.startsWith("server.") ? key.substring(7) : key;
                    say(to, id + " key " + key + ": en '" + (key == null ? null : i18n.getMessage("en-US", key)) + "' / '"
                            + (short_ == null ? null : i18n.getMessage("en-US", short_)) + "', es '"
                            + (key == null ? null : i18n.getMessage("es-ES", key)) + "' / '" + (short_ == null ? null : i18n.getMessage("es-ES", short_)) + "'", OK);
                }
            }
            case "near" -> {
                // test equip near <player> [radius]: every mob around that player with what it wears (max 24).
                PlayerRef p = a.length > 2 ? ApplyCommand.onlinePlayer(a[2]) : null;
                Ref<EntityStore> pr = p != null ? p.getReference() : null;
                if (pr == null || !pr.isValid()) {
                    say(to, "Usage: /mobbattle test equip near <player> [radius]", ERR);
                    return;
                }
                double radius = 16;
                try {
                    if (a.length > 3) radius = Double.parseDouble(a[3]);
                } catch (NumberFormatException ignored) {
                }
                double r = radius;
                Store<EntityStore> store = pr.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var center = ChaosWand.positionOf(pr, store);
                    if (center == null) return;
                    List<Ref<EntityStore>> mobs = new ArrayList<>();
                    for (Ref<EntityStore> m : com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInSphere(center, r, store)) {
                        if (m != null && m.isValid() && EquipWand.inventory(m, store) != null) mobs.add(m);
                    }
                    int n = 0;
                    for (Ref<EntityStore> m : mobs) {
                        if (n++ >= 24) break;
                        var b = BattleManager.battleOf(m);
                        say(to, EquipWand.nameOf(m, store) + (b != null ? " [battle team " + b.team(m) + "]" : "") + ": "
                                + EquipWand.describe(EquipWand.read(m, store)), OK);
                    }
                    say(to, mobs.size() + " mobs within " + r + " blocks.", OK);
                });
            }
            case "power" -> {
                // test equip power <item...>: the damage breakdown the game gives each weapon (calibration).
                for (int i = 2; i < a.length; i++) {
                    String id = ApplyCommand.findItem(a[i]);
                    say(to, a[i] + (id == null ? ": unknown" : ": " + EquipWand.breakdown(id)), OK);
                }
            }
            case "needs" -> {
                // test equip needs [role ...]: the hand items each role's own sensors check (none given = every role).
                List<String> roles = new ArrayList<>();
                for (int i = 2; i < a.length; i++) roles.add(a[i]);
                if (roles.isEmpty()) {
                    for (PickLists.Entry e : PickLists.roles()) roles.add(e.value());
                }
                int found = 0;
                for (String r : roles) {
                    List<String>[] need = EquipWand.needed(r);
                    if (need[0].isEmpty() && need[1].isEmpty()) {
                        if (a.length > 2) say(to, r + ": no hand item needed.", OK);
                        continue;
                    }
                    found++;
                    say(to, r + ": main " + need[0] + ", off " + need[1], OK);
                }
                say(to, found + " of " + roles.size() + " roles need items in hand.", OK);
            }
            case "read", "set", "restore" -> {
                if (a.length < 3 || what.equals("set") && a.length < 5) {
                    say(to, "Usage: /mobbattle test equip read <id|last> | set <id|last> <slot 0-5> <item|none>", ERR);
                    return;
                }
                Ref<EntityStore> mob = resolve(a[2]);
                if (mob == null || !mob.isValid()) {
                    say(to, "No entity " + a[2] + ".", ERR);
                    return;
                }
                Store<EntityStore> store = mob.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    if (!mob.isValid()) return;
                    if (what.equals("restore")) say(to, "restore: " + (EquipWand.restore(mob, store) ? "ok" : "failed"), OK);
                    if (what.equals("set")) {
                        int slot;
                        try {
                            slot = Integer.parseInt(a[3]);
                        } catch (NumberFormatException e) {
                            slot = -1;
                        }
                        if (slot < 0 || slot >= EquipWand.SLOTS.length) {
                            say(to, "Slot 0-5: " + String.join(", ", EquipWand.SLOTS) + ".", ERR);
                            return;
                        }
                        String item = a[4].equalsIgnoreCase("none") ? null : ApplyCommand.findItem(a[4]);
                        if (item == null && !a[4].equalsIgnoreCase("none")) {
                            say(to, "Unknown item " + a[4] + ".", ERR);
                            return;
                        }
                        String why = EquipWand.set(mob, slot, item, store);
                        say(to, "set " + EquipWand.SLOTS[slot] + " = " + (item == null ? "-" : item) + ": " + (why == null ? "ok" : why), why == null ? OK : ERR);
                    }
                    say(to, EquipWand.nameOf(mob, store) + " wears: " + EquipWand.describe(EquipWand.read(mob, store))
                            + " | off hand: " + (EquipWand.hasOffHand(mob, store) ? "yes" : "no") + " | " + EquipWand.dump(mob, store), OK);
                });
            }
            default -> say(to, "Usage: /mobbattle test equip lists [mod:..] [type:..] [group:..] | read <id|last> | set <id|last> <slot 0-5> <item|none>", ERR);
        }
    }

    private static void wandCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (a.length < 3) {
            say(to, "Usage: /mobbattle test wand <owner> <duel|focus|brawl|kill|snap|army|armypick|armydisband|equipcopy|equippaste|equiparea> [target] [target2]  (target = player name, last, id:<uuid>)", ERR);
            return;
        }
        PlayerRef owner = ApplyCommand.onlinePlayer(a[1]);
        Ref<EntityStore> ownerRef = owner != null ? owner.getReference() : null;
        if (ownerRef == null || !ownerRef.isValid()) {
            say(to, "No player " + a[1] + " online.", ERR);
            return;
        }
        String action = a[2].toLowerCase(Locale.ROOT);
        int need = switch (action) {
            case "duel" -> 2;
            case "focus", "kill" -> 1;
            case "brawl", "snap", "armypick", "armydisband" -> 0;
            case "army", "equipcopy", "equippaste" -> 1;
            case "equiparea" -> 0;
            default -> -1;
        };
        if (need < 0) {
            say(to, "Actions: duel, focus, brawl, kill, snap, army <mob>, armypick [mob|player] (none = own army), armydisband.", ERR);
            return;
        }
        List<Ref<EntityStore>> targets = new ArrayList<>();
        for (int i = 3; i < a.length; i++) {
            PlayerRef p = ApplyCommand.onlinePlayer(a[i]);
            Ref<EntityStore> r = p != null ? p.getReference() : resolve(a[i]);
            if (r == null || !r.isValid()) {
                say(to, "No player or entity " + a[i] + ".", ERR);
                return;
            }
            targets.add(r);
        }
        if (targets.size() < need) {
            say(to, action + " needs " + need + " target" + (need > 1 ? "s" : "") + ".", ERR);
            return;
        }
        World world = ownerRef.getStore().getExternalData().getWorld();
        for (Ref<EntityStore> r : targets) {
            if (r.getStore() != ownerRef.getStore()) {
                say(to, "Every target must be in " + owner.getUsername() + "'s world.", ERR);
                return;
            }
        }
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (!ownerRef.isValid()) return;
            Ref<EntityStore> t0 = targets.isEmpty() ? null : targets.get(0);
            String names = targets.stream().map(r -> ChaosWand.nameOf(r, store)).reduce((x, y) -> x + " vs " + y).orElse("around " + owner.getUsername());
            switch (action) {
                case "duel" -> {
                    Ref<EntityStore> first = targets.get(0), second = targets.get(1);
                    for (Ref<EntityStore> r : List.of(first, second)) {
                        if (PlayerFighters.isPlayer(r, store)) {
                            if (!PlayerFighters.eligible(owner, r, BattleManager.Kind.DUEL, store)) {
                                say(to, "Duel refused for " + ChaosWand.nameOf(r, store) + " (see " + owner.getUsername() + "'s chat).", ERR);
                                return;
                            }
                            BattleManager.markPlayer(r, owner.getUuid());
                        }
                    }
                    // the VS screen and intro camera, as the Battle Wand's click (SelectFighterInteraction); the test duel
                    // skipped them, so a duel test never showed the VS (2026-10-17)
                    FightCamera.intro(BattleManager.Kind.DUEL, owner, null, List.of(List.of(first), List.of(second)), store);
                    Countdown.run(BattleManager.Kind.DUEL, owner, store, new Countdown.Label("DUEL: " + names,
                                    I18n.tr("battle.label.duel").param("a", MobNames.message(ChaosWand.nameOf(first, store))).param("b", MobNames.message(ChaosWand.nameOf(second, store)))),
                            List.of(first, second), acc -> SelectFighterInteraction.duel(owner, ownerRef, first, second, acc));
                }
                case "focus" -> {
                    if (PlayerFighters.isPlayer(t0, store)) {
                        if (!PlayerFighters.eligible(owner, t0, BattleManager.Kind.ALL_VS_ONE, store)) {
                            say(to, "All vs one refused for " + ChaosWand.nameOf(t0, store) + ".", ERR);
                            return;
                        }
                        BattleManager.markPlayer(t0, owner.getUuid());
                    }
                    ChaosWand.focus(owner, ownerRef, t0, store);
                }
                case "brawl" -> {
                    Vector3d center = ChaosWand.positionOf(t0 != null ? t0 : ownerRef, store);
                    if (center != null) ChaosWand.brawl(owner, ownerRef, center, store);
                }
                case "kill" -> KillInteraction.use(ownerRef, t0, store);
                case "snap" -> say(to, "Snap: " + Snap.snap(owner, ownerRef, t0, store) + " will turn to dust.", INFO);
                // Army Wand: a left click, a crouch left click (pick whom to recruit for), a right click.
                case "army" -> ArmyToggleInteraction.click(owner, ownerRef, t0, false, store);
                case "armypick" -> ArmyToggleInteraction.click(owner, ownerRef, t0, true, store);
                case "armydisband" -> ArmyDisbandInteraction.disband(owner, ownerRef, store);
                // Equipment Wand clicks (crouch + right, left, held left), then what the mob wears.
                case "equipcopy" -> {
                    if (EquipWand.copyFrom(owner, t0, store)) say(to, "Copied from " + names + ": " + EquipWand.describe(EquipWand.read(t0, store)), OK);
                }
                case "equippaste" -> {
                    if (EquipWand.pasteOn(owner, t0, store)) say(to, names + " now wears: " + EquipWand.describe(EquipWand.read(t0, store)), OK);
                }
                case "equiparea" -> say(to, "Area paste reached " + EquipWand.pasteArea(owner, ownerRef, store) + " mobs.", OK);
                default -> {
                }
            }
            say(to, "Wand " + action + " as " + owner.getUsername() + ": " + names + ".", INFO);
        });
    }

    // ---- perf ----

    private static void perfCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (a.length < 4) {
            say(to, "Usage: /mobbattle test perf <role> <count> <here|x y z> [world]", ERR);
            return;
        }
        String role = SummonList.findRole(a[1]);
        if (role == null || !a[2].matches("\\d{1,3}")) {
            say(to, role == null ? "No mob role " + a[1] + "." : "Usage: /mobbattle test perf <role> <count 1-" + MAX_PERF + "> <here|x y z> [world]", ERR);
            return;
        }
        int count = Math.max(1, Math.min(MAX_PERF, Integer.parseInt(a[2])));
        Place place = place(ctx, a, 3);
        if (place == null) return;
        World world = place.world;
        String before = tickStats(world);
        say(to, "Tick time in " + world.getName() + " over the last 10 s: " + before + ". Spawning " + count + " " + role
                + ", reading again in " + PERF_SETTLE_MS / 1000 + " s.", INFO);
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            long t0 = System.nanoTime();
            int done = 0;
            double ring = 2 + Math.sqrt(count) * 1.5;
            for (int i = 0; i < count; i++) {
                double ang = Math.PI * 2 * i / count, r = ring * Math.sqrt((i + 1) / (double) count);
                if (spawn(role, new Vector3d(place.pos.x + Math.cos(ang) * r, place.pos.y, place.pos.z + Math.sin(ang) * r), (float) ang, store) != null) done++;
            }
            int spawned = done;
            double spawnMs = (System.nanoTime() - t0) / 1e6;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> say(to, String.format(Locale.ROOT,
                    "Perf: %d %s spawned in %.1f ms (one tick). Tick time now: %s (before: %s). /mobbattle test clear removes them.",
                    spawned, role, spawnMs, tickStats(world), before), OK), PERF_SETTLE_MS, TimeUnit.MILLISECONDS);
        });
    }

    /** Average and longest tick of the last 10 s (the world's first metric period) against its budget. */
    private static String tickStats(World world) {
        var m = world.getBufferedTickLengthMetricSet();
        double budget = 1000.0 / Math.max(1, world.getTps());
        return String.format(Locale.ROOT, "average %.2f ms, longest %.2f ms (budget %.1f ms at %d TPS)",
                m.getAverage(0) / 1e6, m.calculateMax(0) / 1e6, budget, world.getTps());
    }

    // ---- scripts ----

    /** A running script: its lines, where it is, who runs it. One at a time. */
    private static final class Script {
        static volatile Script current;
        final CommandSender sender;
        final String name;
        final List<String> lines;
        final long startedAt = System.currentTimeMillis();
        volatile boolean stopped;
        int at, commands;
        final java.util.concurrent.atomic.AtomicInteger passed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();

        Script(CommandSender sender, String name, List<String> lines) {
            this.sender = sender;
            this.name = name;
            this.lines = lines;
        }

        static boolean running() {
            return current != null;
        }

        static boolean stopAll() {
            Script s = current;
            if (s == null) return false;
            s.stopped = true;
            current = null;
            return true;
        }

        /** Runs lines until a wait or the end; each command waits for the previous one to finish. */
        void step() {
            while (!stopped && at < lines.size()) {
                String line = lines.get(at++).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("wait ")) {
                    long ms;
                    try {
                        ms = (long) (Double.parseDouble(line.substring(5).trim().replace("s", "")) * 1000);
                    } catch (NumberFormatException e) {
                        say(sender, name + " line " + at + ": bad wait \"" + line + "\".", ERR);
                        continue;
                    }
                    HytaleServer.SCHEDULED_EXECUTOR.schedule(this::step, Math.max(0, Math.min(600_000, ms)), TimeUnit.MILLISECONDS);
                    return;
                }
                if (lower.startsWith("say ")) {
                    say(sender, name + ": " + line.substring(4).trim(), INFO);
                    continue;
                }
                String command = line.startsWith("/") ? line.substring(1) : line;
                if (command.toLowerCase(Locale.ROOT).matches("(mobbattle|mb)\\s+test\\s+run\\b.*")) {
                    say(sender, name + " line " + at + ": a script can't run another script.", ERR);
                    continue;
                }
                commands++;
                MobBattlePlugin.log("[test] " + name + " > " + command);
                CommandManager.get().handleCommand(sender, command).whenComplete((v, e) -> {
                    if (e != null) say(sender, name + " line " + at + " failed: " + e, ERR);
                    // Most commands queue their work on a world thread and finish before it runs: give it a few
                    // ticks, else "last" on the next line was not spawned yet (2026-10-13, first smoke run).
                    HytaleServer.SCHEDULED_EXECUTOR.schedule(this::step, COMMAND_GAP_MS, TimeUnit.MILLISECONDS);
                });
                return;
            }
            if (current == this) current = null;
            if (!stopped) {
                int ok = passed.get(), bad = failed.get();
                say(sender, String.format(Locale.ROOT, "Script %s done: %d commands in %.1f s.%s", name, commands,
                        (System.currentTimeMillis() - startedAt) / 1000.0,
                        ok + bad == 0 ? "" : " Checks: " + ok + " passed, " + bad + " failed."), bad > 0 ? ERR : OK);
            }
        }
    }

    private static void runScript(CommandSender to, String name) {
        Path dir = FxSettings.dataDirectory().resolve("tests");
        if (name == null) {
            List<String> names = new ArrayList<>();
            try (var files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                        .forEach(p -> names.add(p.getFileName().toString().replaceFirst("\\.txt$", "")));
            } catch (java.io.IOException ignored) {
                // no folder yet
            }
            say(to, "Usage: /mobbattle test run <script>. Scripts in " + dir + ": " + (names.isEmpty() ? "none" : String.join(", ", names)), ERR);
            return;
        }
        if (!name.matches("[A-Za-z0-9_-]{1,40}")) {
            say(to, "A script name is letters, digits, _ and -.", ERR);
            return;
        }
        Path file = dir.resolve(name + ".txt");
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            say(to, "No script " + file + ".", ERR);
            return;
        }
        if (lines.size() > MAX_SCRIPT_LINES) lines = lines.subList(0, MAX_SCRIPT_LINES);
        if (Script.running()) {
            say(to, "Script " + Script.current.name + " is still running (/mobbattle test stop).", ERR);
            return;
        }
        Script s = new Script(to, name, lines);
        Script.current = s;
        say(to, "Running script " + name + " (" + lines.size() + " lines).", INFO);
        HytaleServer.SCHEDULED_EXECUTOR.execute(s::step);
    }

    // ---- royale ----

    private record RoyaleLog(CommandSender to, int mobs, long startedAt) {
    }

    private static final Map<UUID, RoyaleLog> ROYALES = new ConcurrentHashMap<>();
    static final int MAX_ROYALE = FxSettings.MAX_MOBS_LIMIT;

    /** test royale: the battle starts this long after the spawns (see royaleCommand). */
    private static final long ROYALE_START_DELAY_MS = 250;

    private static void royaleCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (a.length < 4) {
            say(to, "Usage: /mobbattle test royale <role:count+role:count+...> <here|x y z> <radius> [world]", ERR);
            return;
        }
        List<String> roles = new ArrayList<>();
        // "+" too: a comma list did not reach the command from the console (test 2026-10-13).
        for (String part : a[1].split("[,+]")) {
            String[] rc = part.split(":");
            String role = SummonList.findRole(rc[0]);
            if (role == null) {
                say(to, "No mob role " + rc[0] + ".", ERR);
                return;
            }
            int n = 1;
            try {
                if (rc.length > 1) n = Math.max(1, Integer.parseInt(rc[1]));
            } catch (NumberFormatException e) {
                say(to, "Bad count in " + part + ".", ERR);
                return;
            }
            for (int i = 0; i < n && roles.size() < MAX_ROYALE; i++) roles.add(role);
        }
        Place place = place(ctx, a, 2);
        if (place == null) return;
        double radius;
        try {
            radius = Math.max(4, Math.min(FxSettings.MAX_RADIUS, Double.parseDouble(a[place.next])));
        } catch (RuntimeException e) {
            say(to, "Give the royale radius after the position.", ERR);
            return;
        }
        ensureSubscribed();
        World world = place.world;
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            java.util.Collections.shuffle(roles);
            int spawned = 0;
            double ring = radius * 0.6;
            for (int i = 0; i < roles.size(); i++) {
                double ang = Math.PI * 2 * i / roles.size(), r = ring * Math.sqrt((i + 1) / (double) roles.size());
                if (spawn(roles.get(i), new Vector3d(place.pos.x + Math.cos(ang) * r, place.pos.y, place.pos.z + Math.sin(ang) * r),
                        (float) ang, store) != null) spawned++;
            }
            int n = spawned;
            say(to, "Spawned " + n + " mobs for the royale; starting it with radius " + radius + ".", INFO);
            // A tick later: the area search did not see mobs spawned in this same tick yet ("needs two living NPCs",
            // console test 2026-10-13).
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> MobBattlePlugin.getApi()
                    .startRoyale(world, new Vector3d(place.pos), radius, Math.max(n, FxSettings.maxMobs)).thenAccept(r -> {
                        if (!r.accepted()) {
                            say(to, "Royale refused: " + r.error(), ERR);
                            return;
                        }
                        ROYALES.put(r.snapshot().eventId(), new RoyaleLog(to, n, System.currentTimeMillis()));
                    }), ROYALE_START_DELAY_MS, TimeUnit.MILLISECONDS);
        });
    }

    private static void royaleSummary(RoyaleLog log, MobBattleApi.Snapshot snap) {
        say(log.to(), String.format(Locale.ROOT, "Royale over (%s) after %.1f s: %d of %d spawned mobs standing as winners, side %s.",
                snap.endReason(), (System.currentTimeMillis() - log.startedAt()) / 1000.0, snap.winners().size(), log.mobs(),
                snap.winnerSide() == null ? "none" : snap.winnerSide()), OK);
    }

    // ---- wave ----

    private static void waveCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (a.length < 3) {
            say(to, "Usage: /mobbattle test wave <heal|cleanse|effect|morph|shuffle|kill> <here|x y z> [world] [effect id|model]", ERR);
            return;
        }
        String kind = a[1].toLowerCase(Locale.ROOT);
        if (!List.of("heal", "cleanse", "effect", "morph", "shuffle", "kill").contains(kind)) {
            say(to, "Waves: heal, cleanse, effect, morph, shuffle.", ERR);
            return;
        }
        Place place = place(ctx, a, 2);
        if (place == null) return;
        String value = place.next < a.length ? a[place.next] : null;
        MorphWand.Copy copy = null;
        if (kind.equals("morph")) {
            String id = value != null ? MorphWand.findModel(value) : null;
            if (id == null) {
                say(to, "test wave morph needs a model: ... morph <x y z> <model>", ERR);
                return;
            }
            copy = new MorphWand.Copy(id, 1f);
        }
        MorphWand.Copy look = copy;
        World world = place.world;
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Vector3d c = new Vector3d(place.pos);
            int radius;
            List<Ref<EntityStore>> hit;
            switch (kind) {
                case "heal" -> {
                    radius = FxSettings.healRadius;
                    hit = HealAreaInteraction.wave(c, null, store);
                }
                case "kill" -> {
                    radius = FxSettings.killRadius;
                    hit = KillAreaInteraction.wave(c, null, null, store);
                }
                case "cleanse" -> {
                    radius = FxSettings.cleanseRadius;
                    hit = CleanseAreaInteraction.wave(c, null, store);
                }
                case "effect" -> {
                    radius = FxSettings.effectRadius;
                    hit = EffectAreaInteraction.wave(c, null, EffectWand.chain(value != null ? value : FxSettings.effect), store);
                }
                default -> {
                    radius = FxSettings.morphBurstRadius;
                    hit = MorphWand.burstAt(c, null, look, kind.equals("shuffle"), store);
                }
            }
            long first = Long.MAX_VALUE, lastMs = 0;
            for (Ref<EntityStore> r : hit) {
                Vector3d p = r.isValid() ? ChaosWand.positionOf(r, store) : null;
                if (p == null) continue;
                long at = FxSettings.waveTravel ? FightFx.waveReachMs(Math.hypot(p.x - c.x, p.z - c.z), radius) : 0;
                first = Math.min(first, at);
                lastMs = Math.max(lastMs, at);
            }
            say(to, hit.isEmpty() ? "The " + kind + " wave (radius " + radius + ") reached nobody."
                    : String.format(Locale.ROOT, "The %s wave (radius %d, waveTravel %s, waveGroundOnly %s) reaches %d: the first at %d ms, the last at %d ms.",
                    kind, radius, FxSettings.waveTravel ? "on" : "off", FxSettings.waveGroundOnly ? "on" : "off", hit.size(), first, lastMs), OK);
        });
    }

    // ---- roles (the panel's pick list filters, from the console) ----

    /** Roles per chat message (the whole list always goes to the log). */
    private static final int ROLES_PER_LINE = 40;

    /**
     * test roles [mod:&lt;name&gt;|mod:hytale] [type:&lt;fighters|defend|noattack|avoid&gt;] [text]: the spawnable mob roles
     * the settings panel's Show filters keep (PickLists), with their mod and type, so scripts and tests can pick mobs
     * by what they are (2026-10-13, user).
     */
    private static void roles(CommandSender to, String[] a) {
        String mod = PickLists.ALL;
        PickLists.Kind kind = null;
        String text = null;
        for (int i = 1; i < a.length; i++) {
            String w = a[i];
            String low = w.toLowerCase(Locale.ROOT);
            if (low.startsWith("mod:")) {
                mod = w.substring(4).isBlank() ? PickLists.ALL : w.substring(4);
            } else if (low.startsWith("type:")) {
                kind = PickLists.kindOf(w.substring(5));
                if (kind == null && !w.substring(5).equalsIgnoreCase("all")) {
                    say(to, "Types: fighters, defend, noattack, avoid (or all).", ERR);
                    return;
                }
            } else {
                text = text == null ? low : text + " " + low;
            }
        }
        List<String> out = new ArrayList<>();
        Map<PickLists.Kind, Integer> perKind = new java.util.EnumMap<>(PickLists.Kind.class);
        for (PickLists.Entry e : PickLists.roles()) {
            boolean modOk = PickLists.ALL.equalsIgnoreCase(mod)
                    || (PickLists.HYTALE.equalsIgnoreCase(mod) ? e.mod() == null : mod.equalsIgnoreCase(e.mod()));
            if (!modOk || kind != null && e.kind() != kind) continue;
            if (text != null && !e.value().toLowerCase(Locale.ROOT).contains(text) && !e.label().toLowerCase(Locale.ROOT).contains(text)) continue;
            if (e.kind() != null) perKind.merge(e.kind(), 1, Integer::sum);
            out.add(e.value() + (e.mod() != null ? " [" + e.mod() + "]" : "") + (kind == null && e.kind() != null ? " (" + e.kind().label + ")" : ""));
        }
        StringBuilder head = new StringBuilder(out.size() + " roles (mod " + mod + ", type " + (kind == null ? "all" : kind.label)
                + (text != null ? ", text \"" + text + "\"" : "") + ")");
        if (kind == null && !perKind.isEmpty()) {
            List<String> counts = new ArrayList<>();
            perKind.forEach((k, n) -> counts.add(k.label + " " + n));
            head.append(": ").append(String.join(", ", counts));
        }
        say(to, head.toString(), INFO);
        for (int i = 0; i < out.size(); i += ROLES_PER_LINE) {
            say(to, String.join(", ", out.subList(i, Math.min(out.size(), i + ROLES_PER_LINE))), INFO);
        }
    }

    // ---- verify (the Type guesses against real hits) ----

    /** One mob checked against its own still dummy. */
    private static final class VerifyPair {
        final String role;
        final PickLists.Kind kind;
        final Ref<EntityStore> mob, dummy;
        Vector3d at;
        volatile int hits;
        /** Samples (every VERIFY_SAMPLE_MS) in which either copy was running an attack, damage or not. */
        volatile int attacking;
        volatile long firstHit;
        long startedAt;
        FightManager.Result order;

        VerifyPair(String role, PickLists.Kind kind, Ref<EntityStore> mob, Ref<EntityStore> dummy) {
            this.role = role;
            this.kind = kind;
            this.mob = mob;
            this.dummy = dummy;
        }
    }

    private static final Map<Ref<EntityStore>, VerifyPair> VERIFY = new ConcurrentHashMap<>();
    private static volatile boolean verifyRunning;
    /** Blocks between pairs (a grid, VERIFY_COLUMNS along +X, rows along +Z, centred on the spot), and mob to dummy. */
    private static final double VERIFY_SPACING = 12, VERIFY_GAP = 3;
    private static final int VERIFY_BATCH = 24, VERIFY_COLUMNS = 6, VERIFY_MAX = 2000;
    private static final int VERIFY_SECONDS = 15;
    /** How often test verify looks whether a checked mob is running an attack (CombatSupport.isExecutingAttack). */
    private static final long VERIFY_SAMPLE_MS = 500;

    /**
     * test verify &lt;here|x y z&gt; [mod:..] [type:..] [text] [time:&lt;s&gt;] [batch:&lt;n&gt;] (2026-10-13, user): every
     * role the filters keep is put next to a still training dummy and ordered to attack it (as a wand fight does),
     * a few at a time in a row along +X; after the time, did it land a hit? Fighters and Defend only should, No attack
     * should not (Avoid is skipped). Mismatches go to chat and every result to tests/verify_&lt;time&gt;.txt. It checks
     * whether a mob CAN fight; whether it starts fights on its own (Fighters vs Defend only) needs a player to provoke
     * it and is not tested here. Test mobs are removed after each batch.
     */
    private static void verifyCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (verifyRunning) {
            say(to, "A verify is already running.", ERR);
            return;
        }
        Place place = place(ctx, a, 1);
        if (place == null) {
            say(to, "Usage: /mobbattle test verify <here|x y z> [mod:<name>|mod:hytale] [type:<fighters|defend|noattack|avoid>] [text] [time:<seconds>] [batch:<n>] [retry|roles:<a+b+...>]", ERR);
            return;
        }
        String mod = PickLists.ALL, text = null;
        PickLists.Kind only = null;
        int seconds = VERIFY_SECONDS, batch = VERIFY_BATCH;
        java.util.Set<String> retry = null;
        for (int i = place.next; i < a.length; i++) {
            String w = a[i], low = w.toLowerCase(Locale.ROOT);
            try {
                if (low.startsWith("roles:")) {
                    // An exact list, role+role+... (2026-10-14: the 64 fighting roles that join flocks on their own).
                    retry = new java.util.HashSet<>(java.util.Arrays.asList(w.substring(6).split("[+,]")));
                } else if (low.equals("retry")) {
                    retry = lastVerifyFailures();
                    if (retry == null) {
                        say(to, "No earlier verify report with roles that did not attack.", ERR);
                        return;
                    }
                } else if (low.startsWith("mod:")) mod = w.substring(4);
                else if (low.startsWith("type:")) only = PickLists.kindOf(w.substring(5));
                else if (low.startsWith("time:")) seconds = Math.max(3, Math.min(60, Integer.parseInt(w.substring(5))));
                else if (low.startsWith("batch:")) batch = Math.max(1, Math.min(60, Integer.parseInt(w.substring(6))));
                else if (Universe.get().getWorld(w) == null) text = text == null ? low : text + " " + low;
            } catch (NumberFormatException e) {
                say(to, "Bad number in " + w + ".", ERR);
                return;
            }
        }
        List<PickLists.Entry> picks = new ArrayList<>();
        for (PickLists.Entry e : PickLists.roles()) {
            if (e.kind() == null || e.kind() == PickLists.Kind.AVOID) continue;
            boolean modOk = PickLists.ALL.equalsIgnoreCase(mod)
                    || (PickLists.HYTALE.equalsIgnoreCase(mod) ? e.mod() == null : mod.equalsIgnoreCase(e.mod()));
            if (!modOk || only != null && e.kind() != only) continue;
            if (text != null && !e.value().toLowerCase(Locale.ROOT).contains(text)) continue;
            if (retry != null && !retry.contains(e.value())) continue;
            picks.add(e);
            if (picks.size() >= VERIFY_MAX) break;
        }
        if (picks.isEmpty()) {
            say(to, "No roles match (Avoid roles are never verified).", ERR);
            return;
        }
        int total = picks.size(), per = batch, secs = seconds;
        verifyRunning = true;
        say(to, String.format(Locale.ROOT, "Verifying %d roles, %d at a time, %d s each batch (about %d min). Each fights a copy of itself %.0f blocks away and ignores everything else; grid centred on %s.",
                total, per, secs, (total + per - 1) / per * (secs + 2) / 60 + 1, VERIFY_GAP, place.pos.toString()), INFO);
        List<String> report = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        verifyBatch(to, place.world, new Vector3d(place.pos), picks, 0, per, secs, report, wrong, System.currentTimeMillis());
    }

    private static void verifyBatch(CommandSender to, World world, Vector3d origin, List<PickLists.Entry> picks, int from, int per,
                                    int seconds, List<String> report, List<String> wrong, long startedAt) {
        if (from >= picks.size()) {
            verifyRunning = false;
            String file = writeVerifyReport(report);
            // Medium: every role seen hitting with a projectile so far (copy it to observed-ranged.txt for game roles).
            String ranged = writeReport("ranged", "# Roles seen hitting with a projectile (Medium.rangedRoles), " + Medium.rangedRoles().size() + " roles", Medium.rangedRoles());
            if (ranged != null) MobBattlePlugin.log("[test] verify: ranged roles in " + ranged);
            say(to, String.format(Locale.ROOT, "Verify done: %d roles in %.0f s, %d mismatches.%s%s", picks.size(),
                    (System.currentTimeMillis() - startedAt) / 1000.0, wrong.size(),
                    wrong.isEmpty() ? "" : " " + String.join("; ", wrong.subList(0, Math.min(20, wrong.size()))) + (wrong.size() > 20 ? "..." : ""),
                    file != null ? " Full report: " + file : ""), wrong.isEmpty() ? OK : ERR);
            return;
        }
        List<PickLists.Entry> now = picks.subList(from, Math.min(picks.size(), from + per));
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            List<VerifyPair> pairs = new ArrayList<>();
            // A grid centred on the origin (user, 2026-10-13: one long row was slow and ran out of loaded chunks).
            int cols = Math.min(VERIFY_COLUMNS, now.size()), rows = (now.size() + cols - 1) / cols;
            for (int i = 0; i < now.size(); i++) {
                PickLists.Entry e = now.get(i);
                int col = i % cols, row = i / cols;
                Vector3d at = new Vector3d(origin.x + (col - (cols - 1) / 2.0) * VERIFY_SPACING, origin.y,
                        origin.z + (row - (rows - 1) / 2.0) * VERIFY_SPACING);
                Ref<EntityStore> mob = spawn(e.value(), at, (float) (-Math.PI / 2), store);
                // Its opponent is a copy of itself (2026-10-13): mobs with the intelligent combat AI (skeletons, trorks,
                // kweebecs, goblins) never took the attitude-less training dummy as an enemy, so verify said 190 roles
                // that fight "never hit". Two of the same role ordered at each other is what a battle does.
                Ref<EntityStore> dummy = spawn(e.value(), new Vector3d(at.x, at.y, at.z + VERIFY_GAP), (float) (Math.PI / 2), store);
                if (mob == null || dummy == null) {
                    report.add(e.value() + " | " + e.kind().label + " | could not spawn");
                    if (mob != null) store.removeEntity(mob, RemoveReason.REMOVE);
                    if (dummy != null) store.removeEntity(dummy, RemoveReason.REMOVE);
                    continue;
                }
                VerifyPair p = new VerifyPair(e.value(), e.kind(), mob, dummy);
                p.at = at;
                // A real Mob Battle duel, silent (no countdown, camera or titles): two copies of a role share its
                // attitude group, and outside a battle the game kept them from hurting each other (only 1 of 468 hit).
                p.order = silentDuel(world, at, mob, dummy, store);
                p.startedAt = System.currentTimeMillis();
                VERIFY.put(mob, p);
                VERIFY.put(dummy, p);
                pairs.add(p);
            }
            // The duel picks targets and ignores outsiders (battle isolation); verifyBlocks cancels any other hit.
            verifySample(world, pairs, System.currentTimeMillis() + seconds * 1000L);
            world.scheduleAfter(() -> {
                for (VerifyPair p : pairs) {
                    VERIFY.remove(p.mob, p);
                    VERIFY.remove(p.dummy, p);
                    // An attack that did no damage still counts: copies of a role are often immune to their own
                    // element (goblin burners to fire, seeker scaraks to poison; user, 2026-10-13).
                    boolean hit = p.hits > 0 || p.attacking > 0;
                    boolean expected = p.kind != PickLists.Kind.NO_ATTACK;
                    String result = p.hits > 0 ? String.format(Locale.ROOT, "hit %d times, first after %.1f s", p.hits, (p.firstHit - p.startedAt) / 1000.0)
                            : p.attacking > 0 ? "attacked, no damage (immune to its own kind?)"
                            : "no attack in " + seconds + " s";
                    report.add(p.role + " | " + p.kind.label + " | order " + p.order + " | " + result + (hit == expected ? "" : " | MISMATCH"));
                    if (hit != expected) wrong.add(p.role + " (" + p.kind.label + ": " + (hit ? "it attacked" : "no attack") + ")");
                    for (Ref<EntityStore> r : List.of(p.mob, p.dummy)) {
                        UUID id = r.isValid() ? idOf(r, store) : null;
                        if (id != null) SPAWNED.remove(id);
                        if (r.isValid()) store.removeEntity(r, RemoveReason.REMOVE);
                    }
                    // Anything a test mob left behind (a Void's tentacle, a summoned pet) goes too (user: a loose Void stayed).
                    removeNpcsNear(p.at, VERIFY_SPACING / 2.0 - 1, store);
                }
                MobBattlePlugin.log("[test] verify: " + Math.min(picks.size(), from + per) + " of " + picks.size() + " checked");
                verifyBatch(to, world, origin, picks, from + per, per, seconds, report, wrong, startedAt);
            }, seconds * 1000L, TimeUnit.MILLISECONDS);
        });
    }

    /** Counts, every VERIFY_SAMPLE_MS until {@code end}, the pairs where either copy is running an attack. */
    private static void verifySample(World world, List<VerifyPair> pairs, long end) {
        world.scheduleAfter(() -> {
            if (System.currentTimeMillis() >= end) return;
            Store<EntityStore> store = world.getEntityStore().getStore();
            for (VerifyPair p : pairs) {
                for (Ref<EntityStore> r : List.of(p.mob, p.dummy)) {
                    if (!r.isValid()) continue;
                    var combat = com.hypixel.hytale.server.npc.role.support.CombatSupport.get(r, store);
                    if (combat != null && combat.isExecutingAttack()) {
                        p.attacking++;
                        break;
                    }
                }
            }
            verifySample(world, pairs, end);
        }, VERIFY_SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    /** Removes every NPC within {@code radius} of {@code at} (test area clean-up). World thread. */
    private static void removeNpcsNear(Vector3d at, double radius, Store<EntityStore> store) {
        if (at == null) return;
        List<Ref<EntityStore>> near = new ArrayList<>(
                com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInSphere(at, radius, store));
        for (Ref<EntityStore> r : near) {
            if (r == null || !r.isValid() || store.getComponent(r, NPCEntity.getComponentType()) == null) continue;
            UUID id = idOf(r, store);
            if (id != null) SPAWNED.remove(id);
            store.removeEntity(r, RemoveReason.REMOVE);
        }
    }

    /**
     * test verify ... retry: the roles that did not attack in the newest tests/verify_*.txt (so slow starters can be
     * told from mobs that never attack, with a longer time:); null if there is no such report.
     */
    private static java.util.Set<String> lastVerifyFailures() {
        try {
            Path dir = FxSettings.dataDirectory().resolve("tests");
            if (!Files.isDirectory(dir)) return null;
            Path newest = null;
            try (var files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String n = f.getFileName().toString();
                    if (n.startsWith("verify_") && n.endsWith(".txt") && (newest == null || n.compareTo(newest.getFileName().toString()) > 0)) newest = f;
                }
            }
            if (newest == null) return null;
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String line : Files.readAllLines(newest, StandardCharsets.UTF_8)) {
                if (line.contains("no attack in") || line.contains("no hit in")) out.add(line.split(" \\| ")[0].trim());
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** The full verify results in tests/verify_&lt;time&gt;.txt; the path, or null if it could not be written. */
    /**
     * A real Mob Battle duel between two test mobs, silent (no countdown, camera or titles), ordered at each other at
     * once. World thread. Returns how the first one took the order.
     */
    private static FightManager.Result silentDuel(World world, Vector3d at, Ref<EntityStore> a, Ref<EntityStore> b, Store<EntityStore> store) {
        BattleManager.detach(List.of(a, b));
        FightManager.Result order = FightManager.turnAgainst(a, b, store);
        FightManager.turnAgainst(b, a, store);
        BattleManager.Battle duel = BattleManager.startDuel(
                new BattleManager.Battle.Anchor(world.getWorldConfig().getUuid(), new Vector3d(at), UUID.randomUUID()), a, b);
        duel.silent = true;
        BattleManager.setTarget(duel, a, b);
        BattleManager.setTarget(duel, b, a);
        FightManager.provoke(b, a, store);
        FightManager.provoke(a, b, store);
        return order;
    }

    // ---- calls (group calls must not pull onlookers into a battle, 2026-10-14) ----

    /** One duel of two copies of a role, with onlookers of the same role standing beside it (not in any battle). */
    private static final class CallCell {
        final String role;
        final PickLists.Kind kind;
        final Ref<EntityStore> a, b;
        final List<Ref<EntityStore>> watchers = new ArrayList<>();
        final java.util.Set<Ref<EntityStore>> joined = ConcurrentHashMap.newKeySet();
        /** Who a fighter had locked instead of its opponent (names, for the report). */
        final java.util.Set<String> offTargets = ConcurrentHashMap.newKeySet();
        Vector3d at;
        /** Group calls our filters dropped (fighters' and onlookers' mailboxes); onlooker hits on any fighter. */
        volatile int calls, hits;
        /** Samples in which a fighter's LockedTarget was someone other than its opponent. */
        volatile int distracted;

        CallCell(String role, PickLists.Kind kind, Ref<EntityStore> a, Ref<EntityStore> b) {
            this.role = role;
            this.kind = kind;
            this.a = a;
            this.b = b;
        }

        boolean fighter(Ref<EntityStore> r) {
            return r == a || r == b;
        }
    }

    private static final Map<Ref<EntityStore>, CallCell> CALLS = new ConcurrentHashMap<>();
    private static volatile boolean callsRunning;
    /** Cells further apart than a group call carries (ActionBeacon range 30), so each cell only hears its own. */
    private static final double CALLS_SPACING = 40, CALLS_SIDE = 6;
    private static final int CALLS_BATCH = 8, CALLS_COLUMNS = 4, CALLS_WATCHERS = 3, CALLS_SECONDS = 20;

    /** FightTickSystem: a group call was dropped from this NPC's mailbox. Cheap when no calls test runs. */
    static void callDropped(Ref<EntityStore> self) {
        if (!callsRunning) return;
        CallCell c = CALLS.get(self);
        if (c != null) c.calls++;
    }

    /**
     * test calls &lt;here|x y z&gt; [mod:..] [type:..] [text] [time:&lt;s&gt;] [batch:&lt;n&gt;] [watchers:&lt;n&gt;] (2026-10-14,
     * user: goblins alarm each other too, check every mob that fights): for each role that fights (Fighters or Defend
     * only, not the passive ones), a silent duel of two copies with onlookers of the same role 6 blocks away, outside
     * any battle. The fighters call for help (Trork/Outlander TrorkEnemySighted, goblins Goblin_Attack, skeletons
     * Under_Attack, Flock_Attack...) and the call sets the hearer's LockedTarget whatever the attitudes say. Fails when
     * an onlooker locks onto or hits a fighter, or a fighter locks onto anyone but its opponent. Every result goes to
     * tests/calls_&lt;time&gt;.txt with the calls our filters dropped (0 = that role never called).
     */
    private static void callsCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (callsRunning) {
            say(to, "A calls test is already running.", ERR);
            return;
        }
        Place place = place(ctx, a, 1);
        if (place == null) {
            say(to, "Usage: /mobbattle test calls <here|x y z> [mod:<name>|mod:hytale] [type:<fighters|defend>] [text] [time:<seconds>] [batch:<n>] [watchers:<n>]", ERR);
            return;
        }
        String mod = PickLists.ALL, text = null;
        PickLists.Kind only = null;
        int seconds = CALLS_SECONDS, batch = CALLS_BATCH, watchers = CALLS_WATCHERS;
        for (int i = place.next; i < a.length; i++) {
            String w = a[i], low = w.toLowerCase(Locale.ROOT);
            try {
                if (low.startsWith("mod:")) mod = w.substring(4);
                else if (low.startsWith("type:")) only = PickLists.kindOf(w.substring(5));
                else if (low.startsWith("time:")) seconds = Math.max(5, Math.min(120, Integer.parseInt(w.substring(5))));
                else if (low.startsWith("batch:")) batch = Math.max(1, Math.min(16, Integer.parseInt(w.substring(6))));
                else if (low.startsWith("watchers:")) watchers = Math.max(1, Math.min(8, Integer.parseInt(w.substring(9))));
                else if (Universe.get().getWorld(w) == null) text = text == null ? low : text + " " + low;
            } catch (NumberFormatException e) {
                say(to, "Bad number in " + w + ".", ERR);
                return;
            }
        }
        List<PickLists.Entry> picks = new ArrayList<>();
        for (PickLists.Entry e : PickLists.roles()) {
            if (e.kind() != PickLists.Kind.FIGHTS && e.kind() != PickLists.Kind.DEFENDS) continue;
            if (PickLists.observedNoAttack(e.value())) continue;
            boolean modOk = PickLists.ALL.equalsIgnoreCase(mod)
                    || (PickLists.HYTALE.equalsIgnoreCase(mod) ? e.mod() == null : mod.equalsIgnoreCase(e.mod()));
            if (!modOk || only != null && e.kind() != only) continue;
            if (text != null && !e.value().toLowerCase(Locale.ROOT).contains(text)) continue;
            picks.add(e);
            if (picks.size() >= VERIFY_MAX) break;
        }
        if (picks.isEmpty()) {
            say(to, "No fighting roles match.", ERR);
            return;
        }
        callsRunning = true;
        say(to, String.format(Locale.ROOT, "Calls test: %d roles, %d at a time, %d s each (about %d min). Each is a duel of two copies with %d onlookers of the same role %.0f blocks away; grid centred on %s.",
                picks.size(), batch, seconds, (picks.size() + batch - 1) / batch * (seconds + 2) / 60 + 1, watchers, CALLS_SIDE, place.pos.toString()), INFO);
        callsBatch(to, place.world, new Vector3d(place.pos), picks, 0, batch, seconds, watchers, new ArrayList<>(), new ArrayList<>(),
                new int[1], System.currentTimeMillis());
    }

    private static void callsBatch(CommandSender to, World world, Vector3d origin, List<PickLists.Entry> picks, int from, int per,
                                   int seconds, int watchers, List<String> report, List<String> wrong, int[] calling, long startedAt) {
        if (from >= picks.size()) {
            callsRunning = false;
            String file = writeReport("calls", "role | type | group calls dropped | onlookers that went for a fighter | onlooker hits on fighters | fighter samples off its opponent | result", report);
            say(to, String.format(Locale.ROOT, "Calls test done: %d roles in %.0f s, %d called for help, %d failed.%s%s", picks.size(),
                    (System.currentTimeMillis() - startedAt) / 1000.0, calling[0], wrong.size(),
                    wrong.isEmpty() ? "" : " " + String.join("; ", wrong.subList(0, Math.min(20, wrong.size()))) + (wrong.size() > 20 ? "..." : ""),
                    file != null ? " Full report: " + file : ""), wrong.isEmpty() ? OK : ERR);
            return;
        }
        List<PickLists.Entry> now = picks.subList(from, Math.min(picks.size(), from + per));
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            List<CallCell> cells = new ArrayList<>();
            int cols = Math.min(CALLS_COLUMNS, now.size()), rows = (now.size() + cols - 1) / cols;
            for (int i = 0; i < now.size(); i++) {
                PickLists.Entry e = now.get(i);
                int col = i % cols, row = i / cols;
                Vector3d at = new Vector3d(origin.x + (col - (cols - 1) / 2.0) * CALLS_SPACING, origin.y,
                        origin.z + (row - (rows - 1) / 2.0) * CALLS_SPACING);
                Ref<EntityStore> mob = spawn(e.value(), at, (float) (-Math.PI / 2), store);
                Ref<EntityStore> other = spawn(e.value(), new Vector3d(at.x, at.y, at.z + VERIFY_GAP), (float) (Math.PI / 2), store);
                if (mob == null || other == null) {
                    report.add(e.value() + " | " + e.kind().label + " | could not spawn");
                    if (mob != null) store.removeEntity(mob, RemoveReason.REMOVE);
                    if (other != null) store.removeEntity(other, RemoveReason.REMOVE);
                    continue;
                }
                CallCell c = new CallCell(e.value(), e.kind(), mob, other);
                c.at = at;
                for (int w = 0; w < watchers; w++) {
                    Ref<EntityStore> r = spawn(e.value(), new Vector3d(at.x + CALLS_SIDE, at.y, at.z - 1.5 + w * 1.5), (float) Math.PI, store);
                    if (r == null) continue;
                    c.watchers.add(r);
                    CALLS.put(r, c);
                }
                CALLS.put(mob, c);
                CALLS.put(other, c);
                cells.add(c);
            }
            // Every cell's duel starts after every spawn: a pet-like flock mate (a feran cub) spawned for a later cell
            // counted as "spawned during" an earlier cell's battle and joined it.
            for (CallCell c : cells) silentDuel(world, c.at, c.a, c.b, store);
            callsSample(world, cells, System.currentTimeMillis() + seconds * 1000L);
            world.scheduleAfter(() -> {
                for (CallCell c : cells) {
                    boolean failed = !c.joined.isEmpty() || c.hits > 0 || c.distracted > 2;
                    if (c.calls > 0) calling[0]++;
                    report.add(c.role + " | " + c.kind.label + " | " + c.calls + " | " + c.joined.size() + "/" + c.watchers.size()
                            + " | " + c.hits + " | " + c.distracted + (c.offTargets.isEmpty() ? "" : " " + c.offTargets) + " | " + (failed ? "FAIL" : "ok"));
                    if (failed) wrong.add(c.role + " (" + c.joined.size() + " onlookers joined, " + c.hits + " hits, " + c.distracted + " off target)");
                    List<Ref<EntityStore>> all = new ArrayList<>(c.watchers);
                    all.add(c.a);
                    all.add(c.b);
                    for (Ref<EntityStore> r : all) {
                        CALLS.remove(r, c);
                        UUID id = r.isValid() ? idOf(r, store) : null;
                        if (id != null) SPAWNED.remove(id);
                        if (r.isValid()) store.removeEntity(r, RemoveReason.REMOVE);
                    }
                    removeNpcsNear(c.at, CALLS_SPACING / 2.0 - 1, store);
                }
                MobBattlePlugin.log("[test] calls: " + Math.min(picks.size(), from + per) + " of " + picks.size() + " checked");
                callsBatch(to, world, origin, picks, from + per, per, seconds, watchers, report, wrong, calling, startedAt);
            }, seconds * 1000L, TimeUnit.MILLISECONDS);
        });
    }

    /** Every VERIFY_SAMPLE_MS until {@code end}: who each onlooker and fighter has in its LockedTarget slot. */
    private static void callsSample(World world, List<CallCell> cells, long end) {
        world.scheduleAfter(() -> {
            if (System.currentTimeMillis() >= end) return;
            Store<EntityStore> store = world.getEntityStore().getStore();
            for (CallCell c : cells) {
                for (Ref<EntityStore> w : c.watchers) {
                    Ref<EntityStore> locked = lockedOf(w, store);
                    CallCell of = locked != null ? CALLS.get(locked) : null;
                    if (of != null && of.fighter(locked)) c.joined.add(w);
                }
                for (Ref<EntityStore> f : List.of(c.a, c.b)) {
                    Ref<EntityStore> locked = lockedOf(f, store);
                    // The opponent's pet or summon (joined its side) is a fair target too.
                    if (locked != null && locked != f && !c.fighter(locked) && !BattleManager.areEnemies(f, locked)) {
                        c.distracted++;
                        c.offTargets.add(ChaosWand.nameOf(locked, store) + (c.watchers.contains(locked) ? " (onlooker)" : ""));
                    }
                }
            }
            callsSample(world, cells, end);
        }, VERIFY_SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    private static Ref<EntityStore> lockedOf(Ref<EntityStore> mob, Store<EntityStore> store) {
        if (mob == null || !mob.isValid()) return null;
        var marked = com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport.get(mob, store);
        Ref<EntityStore> locked = marked != null ? marked.getMarkedEntityRef(FightManager.TARGET_SLOT) : null;
        return locked != null && locked.isValid() ? locked : null;
    }

    private static String writeVerifyReport(List<String> lines) {
        try {
            Path dir = FxSettings.dataDirectory().resolve("tests");
            Files.createDirectories(dir);
            Path file = dir.resolve("verify_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
            List<String> out = new ArrayList<>();
            out.add("role | type guessed | attack order | result");
            out.addAll(lines);
            Files.write(file, out, StandardCharsets.UTF_8);
            return file.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- ai (what ChaseSupport finds in each built role) ----

    private static volatile boolean aiRunning;
    /** Roles spawned, read and removed per world task (one task per tick). */
    private static final int AI_PER_TICK = 8;

    /**
     * test ai &lt;here|x y z&gt; [mod:..] [type:..] [text] (2026-10-13, user): every role the filters keep is spawned for
     * one tick, read with {@link ChaseSupport#describe} (the chase slot/state/range ChaseSupport will use in battles,
     * and the Leash sensor ranges per combat state = the soft leash) and removed. The JSON scan of Assets.zip
     * (docs/general/npcs.md) predicts these; this checks the server's built roles, mods included. Chat: counts and the
     * fighters with no chase found; every role in tests/ai_&lt;time&gt;.txt.
     */
    private static void aiCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (aiRunning) {
            say(to, "An ai check is already running.", ERR);
            return;
        }
        Place place = place(ctx, a, 1);
        if (place == null) {
            say(to, "Usage: /mobbattle test ai <here|x y z> [mod:<name>|mod:hytale] [type:<fighters|defend|noattack|avoid>] [text]", ERR);
            return;
        }
        String mod = PickLists.ALL, text = null;
        PickLists.Kind only = null;
        for (int i = place.next; i < a.length; i++) {
            String w = a[i], low = w.toLowerCase(Locale.ROOT);
            if (low.startsWith("mod:")) mod = w.substring(4);
            else if (low.startsWith("type:")) only = PickLists.kindOf(w.substring(5));
            else if (Universe.get().getWorld(w) == null) text = text == null ? low : text + " " + low;
        }
        List<PickLists.Entry> picks = new ArrayList<>();
        for (PickLists.Entry e : PickLists.roles()) {
            boolean modOk = PickLists.ALL.equalsIgnoreCase(mod)
                    || (PickLists.HYTALE.equalsIgnoreCase(mod) ? e.mod() == null : mod.equalsIgnoreCase(e.mod()));
            if (!modOk || only != null && e.kind() != only) continue;
            if (text != null && !e.value().toLowerCase(Locale.ROOT).contains(text)) continue;
            picks.add(e);
        }
        if (picks.isEmpty()) {
            say(to, "No roles match.", ERR);
            return;
        }
        aiRunning = true;
        say(to, "Reading the AI of " + picks.size() + " roles (" + AI_PER_TICK + " per tick, each spawned and removed at once)...", INFO);
        aiBatch(to, place.world, new Vector3d(place.pos), picks, 0, new ArrayList<>(), System.currentTimeMillis());
    }

    private static void aiBatch(CommandSender to, World world, Vector3d at, List<PickLists.Entry> picks, int from,
                                List<String[]> rows, long startedAt) {
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            int end = Math.min(picks.size(), from + AI_PER_TICK);
            for (int i = from; i < end; i++) {
                PickLists.Entry e = picks.get(i);
                String kind = e.kind() != null ? e.kind().label : "?";
                Ref<EntityStore> mob = null;
                try {
                    mob = spawn(e.value(), at, 0f, store);
                    String[] d = mob != null ? ChaseSupport.describe(mob, store) : new String[] {"could not spawn", "?"};
                    rows.add(new String[] {e.value(), kind, e.mod() != null ? e.mod() : "Hytale", d[0], d[1]});
                } catch (RuntimeException ex) {
                    rows.add(new String[] {e.value(), kind, e.mod() != null ? e.mod() : "Hytale", "error: " + ex, "?"});
                } finally {
                    if (mob != null && mob.isValid()) {
                        UUID id = idOf(mob, store);
                        if (id != null) SPAWNED.remove(id);
                        store.removeEntity(mob, RemoveReason.REMOVE);
                    }
                }
            }
            // Nothing spawned in the first batches: the spot's chunk isn't loaded (no player near it). Stop there.
            boolean noneYet = rows.stream().noneMatch(r -> !r[3].equals("could not spawn"));
            if (end < picks.size() && noneYet && rows.size() >= AI_PER_TICK * 2) {
                aiRunning = false;
                say(to, "AI read stopped: no role could spawn at " + at + ". Its chunk is not loaded; stand near it (or use 'here' as a player) and try again.", ERR);
                return;
            }
            if (end < picks.size()) {
                aiBatch(to, world, at, picks, end, rows, startedAt);
                return;
            }
            aiRunning = false;
            aiReport(to, rows, System.currentTimeMillis() - startedAt);
        });
    }

    private static void aiReport(CommandSender to, List<String[]> rows, long ms) {
        int chase = 0, leash = 0, failed = 0;
        List<String> noChase = new ArrayList<>();
        for (String[] r : rows) {
            if (r[3].equals("could not spawn") || r[3].startsWith("error")) {
                failed++;
                continue;
            }
            boolean found = r[3].startsWith("state ");
            if (found) chase++;
            if (!r[4].equals("none") && !r[4].equals("?")) leash++;
            boolean fighter = r[1].equals(PickLists.Kind.FIGHTS.label) || r[1].equals(PickLists.Kind.DEFENDS.label);
            if (!found && fighter) noChase.add(r[0]);
        }
        String file = null;
        try {
            Path dir = FxSettings.dataDirectory().resolve("tests");
            Files.createDirectories(dir);
            Path p = dir.resolve("ai_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
            List<String> out = new ArrayList<>();
            out.add("role | type | mod | chase (ChaseSupport) | leash ranges per combat state");
            for (String[] r : rows) out.add(String.join(" | ", r));
            Files.write(p, out, StandardCharsets.UTF_8);
            file = p.toString();
        } catch (Exception ignored) {
        }
        say(to, String.format(Locale.ROOT, "AI read: %d roles in %.1f s (%d could not spawn). Chase found: %d. Soft leash in a combat state: %d. Fighters with no chase found: %d%s%s",
                rows.size(), ms / 1000.0, failed, chase, leash, noChase.size(),
                noChase.isEmpty() ? "" : " (" + String.join(", ", noChase.subList(0, Math.min(25, noChase.size()))) + (noChase.size() > 25 ? "..." : "") + ")",
                file != null ? ". Full report: " + file : ""), noChase.isEmpty() ? OK : INFO);
    }

    // ---- hit (a player's hit, for the Army Wand's owner's order) ----

    /**
     * test hit &lt;player&gt; &lt;id|last&gt; [damage] (2026-10-14): the player "hits" that mob through the damage pipeline
     * (EntitySource = the player, cause Physical, default 1), as a sword hit would, so the Army Wand's owner's order
     * (FightDamageSystem -> Army.order) can be tested from the console. Not the other way round: a hit on a Creative
     * player is cancelled before the army sees it.
     */
    private static void hitCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (a.length < 3) {
            say(to, "Usage: /mobbattle test hit <player> <id|last> [damage]", ERR);
            return;
        }
        PlayerRef player = ApplyCommand.onlinePlayer(a[1]);
        if (player == null) {
            say(to, "No player " + a[1] + " online.", ERR);
            return;
        }
        Ref<EntityStore> victim = resolve(a[2]);
        if (victim == null) {
            say(to, "No entity " + a[2] + ".", ERR);
            return;
        }
        float amount;
        try {
            amount = a.length > 3 ? Float.parseFloat(a[3]) : 1f;
        } catch (NumberFormatException e) {
            say(to, "Bad damage " + a[3] + ".", ERR);
            return;
        }
        World world = victim.getStore().getExternalData().getWorld();
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> me = player.getReference();
            if (me == null || !me.isValid() || me.getStore() != store || !victim.isValid()) {
                say(to, "The player and the mob must be in the same world.", ERR);
                return;
            }
            int cause = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getIndex("Physical");
            com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems.executeDamage(victim, store,
                    new com.hypixel.hytale.server.core.modules.entity.damage.Damage(
                            new com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource(me), cause, amount));
            say(to, player.getUsername() + " hit " + ChaosWand.nameOf(victim, store) + " for " + amount + ".", OK);
        });
    }

    // ---- wipe (clean the test field) ----

    private static final double WIPE_RADIUS = 96, WIPE_MAX = 512;

    /**
     * test wipe &lt;here|x y z&gt; [radius] (2026-10-13): removes every entity that is not a player (mobs, dropped items,
     * projectiles) within the radius (default 96), on the world thread. `sudo &lt;player&gt; entity clean` from the console
     * sometimes never ran (no "executed command" line), so leftovers from one test joined the next one.
     */
    private static void wipe(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        Place place = place(ctx, a, 1);
        if (place == null) {
            say(to, "Usage: /mobbattle test wipe <here|x y z> [radius]", ERR);
            return;
        }
        double radius = WIPE_RADIUS;
        if (place.next < a.length) {
            try {
                radius = Math.max(1, Math.min(WIPE_MAX, Double.parseDouble(a[place.next])));
            } catch (NumberFormatException e) {
                say(to, "Bad radius " + a[place.next] + ".", ERR);
                return;
            }
        }
        double r = radius;
        World world = place.world;
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            List<Ref<EntityStore>> near = new ArrayList<>(
                    com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInSphere(place.pos, r, store));
            int removed = 0;
            for (Ref<EntityStore> e : near) {
                if (e == null || !e.isValid()) continue;
                if (store.getComponent(e, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()) != null) continue;
                UUID id = idOf(e, store);
                if (id != null) SPAWNED.remove(id);
                store.removeEntity(e, RemoveReason.REMOVE);
                removed++;
            }
            say(to, "Wiped " + removed + " entities within " + Math.round(r) + " blocks of " + Math.round(place.pos.x) + " "
                    + Math.round(place.pos.y) + " " + Math.round(place.pos.z) + ".", OK);
        });
    }

    // ---- speed (how fast a mob runs, per size) ----

    private static volatile boolean speedRunning;
    /** Lane length (start to the dummy) and the distance between lanes. */
    private static final double SPEED_LANE = 25, SPEED_LANE_GAP = 6;
    private static final int SPEED_SECONDS = 25;
    private static final long SPEED_SAMPLE_MS = 100;

    /**
     * test speed &lt;role&gt; &lt;here|x y z&gt; [sizes, e.g. 1+2+4] (2026-10-14, user: see the size speed, not only read it):
     * one lane per size, side by side along +Z, each a mob at the start and a still dummy SPEED_LANE blocks away along
     * +X; all start together, ordered at their dummy (as test chase), with that size buff (Buffs.setDirect SIZE). Per
     * lane: the time to get within 3 blocks of its dummy and the average speed over the lane, plus the top speed over
     * 0.5 s (positions sampled every 100 ms on the world thread). Sizes above buff sizeMax are cut to it.
     */
    private static void speedCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (speedRunning) {
            say(to, "A speed test is already running.", ERR);
            return;
        }
        if (a.length < 3) {
            say(to, "Usage: /mobbattle test speed <role> <here|x y z> [sizes like 1+2+4]", ERR);
            return;
        }
        String role = SummonList.findRole(a[1]);
        if (role == null) {
            say(to, "No mob role " + a[1] + ".", ERR);
            return;
        }
        Place place = place(ctx, a, 2);
        if (place == null) return;
        List<Double> sizes = new ArrayList<>(List.of(1.0, 2.0, 4.0));
        if (place.next < a.length) {
            try {
                sizes.clear();
                for (String s : a[place.next].split("[+,]")) sizes.add(Math.max(1, Math.min(10, Double.parseDouble(s.replace("x", "")))));
            } catch (NumberFormatException e) {
                say(to, "Bad sizes " + a[place.next] + ".", ERR);
                return;
            }
        }
        speedRunning = true;
        World world = place.world;
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            int n = sizes.size();
            Ref<EntityStore>[] mobs = newRefs(n), dummies = newRefs(n);
            Vector3d[] starts = new Vector3d[n], ends = new Vector3d[n];
            for (int i = 0; i < n; i++) {
                double z = place.pos.z + (i - (n - 1) / 2.0) * SPEED_LANE_GAP;
                starts[i] = new Vector3d(place.pos.x, place.pos.y, z);
                ends[i] = new Vector3d(place.pos.x + SPEED_LANE, place.pos.y, z);
                dummies[i] = spawn("Test_Dummy_Stationary", ends[i], 0f, store);
                mobs[i] = spawn(role, starts[i], (float) (-Math.PI / 2), store);
                if (mobs[i] == null) continue;
                if (sizes.get(i) > 1.0001) Buffs.setDirect(mobs[i], Buffs.Stat.SIZE, (int) Math.round((sizes.get(i) - 1) * 100));
            }
            say(to, String.format(Locale.ROOT, "Speed test: %s, %d lanes of %.0f blocks along +X from %.0f %.0f %.0f, sizes %s.",
                    role, n, SPEED_LANE, place.pos.x, place.pos.y, place.pos.z, sizes), INFO);
            // Give the size buff a moment (model rebuild), then go.
            world.scheduleAfter(() -> {
                for (int i = 0; i < n; i++) {
                    if (mobs[i] != null && mobs[i].isValid() && dummies[i] != null && dummies[i].isValid()) {
                        // A silent duel with its dummy (user, 2026-10-14: the zombies only walked, a mob ordered
                        // outside a battle does not chase a target past its sight): the battle's chase makes it run.
                        BattleManager.detach(List.of(mobs[i], dummies[i]));
                        FightManager.turnAgainst(mobs[i], dummies[i], store);
                        BattleManager.Battle lane = BattleManager.startDuel(new BattleManager.Battle.Anchor(
                                world.getWorldConfig().getUuid(), new Vector3d(ends[i]), UUID.randomUUID()), mobs[i], dummies[i]);
                        lane.silent = true;
                        BattleManager.setTarget(lane, mobs[i], dummies[i]);
                        FightManager.provoke(dummies[i], mobs[i], store);
                    }
                }
                long t0 = System.currentTimeMillis();
                speedSample(to, world, role, sizes, mobs, dummies, ends, new double[n], new long[n], new ArrayList<>(), t0);
            }, 600, TimeUnit.MILLISECONDS);
        });
    }

    @SuppressWarnings("unchecked")
    private static Ref<EntityStore>[] newRefs(int n) {
        return (Ref<EntityStore>[]) new Ref[n];
    }

    /** Positions every SPEED_SAMPLE_MS; arrival = within 3 blocks of its dummy (horizontal). */
    private static void speedSample(CommandSender to, World world, String role, List<Double> sizes, Ref<EntityStore>[] mobs,
                                    Ref<EntityStore>[] dummies, Vector3d[] ends, double[] top, long[] arrived,
                                    List<Vector3d[]> history, long t0) {
        world.scheduleAfter(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            long now = System.currentTimeMillis();
            Vector3d[] at = new Vector3d[mobs.length];
            boolean all = true;
            for (int i = 0; i < mobs.length; i++) {
                at[i] = mobs[i] != null && mobs[i].isValid() ? ChaosWand.positionOf(mobs[i], store) : null;
                if (at[i] != null && arrived[i] == 0) {
                    double dx = ends[i].x - at[i].x, dz = ends[i].z - at[i].z;
                    if (dx * dx + dz * dz <= 9) arrived[i] = now;
                }
                if (arrived[i] == 0) all = false;
            }
            history.add(at);
            // Top speed over the last 0.5 s (5 samples).
            int back = (int) (500 / SPEED_SAMPLE_MS);
            if (history.size() > back) {
                Vector3d[] old = history.get(history.size() - 1 - back);
                for (int i = 0; i < mobs.length; i++) {
                    if (at[i] == null || old[i] == null || arrived[i] != 0 && arrived[i] < now - 500) continue;
                    double dx = at[i].x - old[i].x, dz = at[i].z - old[i].z;
                    top[i] = Math.max(top[i], Math.sqrt(dx * dx + dz * dz) / (back * SPEED_SAMPLE_MS / 1000.0));
                }
            }
            if (!all && now - t0 < SPEED_SECONDS * 1000L) {
                speedSample(to, world, role, sizes, mobs, dummies, ends, top, arrived, history, t0);
                return;
            }
            List<String> report = new ArrayList<>();
            StringBuilder chat = new StringBuilder("Speed test " + role + ":");
            for (int i = 0; i < mobs.length; i++) {
                double scale = mobs[i] != null && mobs[i].isValid() ? Buffs.sizeScale(mobs[i], store) : 0;
                String lane = arrived[i] == 0
                        ? String.format(Locale.ROOT, "x%s (real x%.2f): did not arrive in %d s, top %.1f b/s", fmtSize(sizes.get(i)), scale, SPEED_SECONDS, top[i])
                        : String.format(Locale.ROOT, "x%s (real x%.2f): %.1f s, average %.1f b/s, top %.1f b/s", fmtSize(sizes.get(i)), scale,
                                (arrived[i] - t0) / 1000.0, (SPEED_LANE - 3) / ((arrived[i] - t0) / 1000.0), top[i]);
                report.add(lane);
                chat.append(" | ").append(lane);
                for (Ref<EntityStore> r : List.of(mobs[i], dummies[i])) {
                    if (r == null || !r.isValid()) continue;
                    UUID id = idOf(r, store);
                    if (id != null) SPAWNED.remove(id);
                    store.removeEntity(r, RemoveReason.REMOVE);
                }
            }
            speedRunning = false;
            String file = writeReport("speed", "size | time to the dummy | average | top speed over 0.5 s", report);
            say(to, chat + (file != null ? ". Full report: " + file : ""), OK);
        }, SPEED_SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    // ---- jump (how high a mob climbs, per size) ----

    private static volatile boolean jumpRunning;
    /** Steps 1..JUMP_STEPS blocks high, JUMP_GAP blocks apart along +X; each a 3x3 platform with a dummy on top. */
    private static final int JUMP_STEPS = 8, JUMP_GAP = 8;
    /** The mob starts this far west of its step. */
    private static final double JUMP_START = 5;
    private static final int JUMP_SECONDS = 15;
    private static final long JUMP_SAMPLE_MS = 250;

    /** A block changed by test jump, to put back. */
    private record SavedBlock(int x, int y, int z, int id) {
    }

    /**
     * test jump &lt;role&gt; &lt;here|x y z&gt; [sizes, e.g. 1+2+3+4] [time:&lt;s&gt;] (2026-10-13, user): mobs only jump when their
     * path needs it, so it builds steps 1..8 blocks high (3x3 platforms of the ground's block, 8 blocks apart along +X)
     * with a still dummy on each, then for each size puts one mob 5 blocks west of each step with that size buff
     * (direct, as /mobbattle apply size) and orders it at the dummy; it samples the mob's height every 250 ms for the
     * time (default 15 s). Result per size: the highest step whose top it stood on. Mobs, dummies and blocks are
     * removed after. Sizes above the size limit (buff sizeMax) are cut to it; the report says the real scale.
     */
    private static void jumpCommand(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (jumpRunning) {
            say(to, "A jump test is already running.", ERR);
            return;
        }
        if (a.length < 3) {
            say(to, "Usage: /mobbattle test jump <role> <here|x y z> [sizes like 1+2+3+4] [time:<seconds>]", ERR);
            return;
        }
        String role = SummonList.findRole(a[1]);
        if (role == null) {
            say(to, "No mob role " + a[1] + ".", ERR);
            return;
        }
        Place place = place(ctx, a, 2);
        if (place == null) return;
        List<Double> sizes = new ArrayList<>(List.of(1.0, 2.0, 3.0));
        int seconds = JUMP_SECONDS;
        for (int i = place.next; i < a.length; i++) {
            String w = a[i].toLowerCase(Locale.ROOT);
            try {
                if (w.startsWith("time:")) seconds = Math.max(5, Math.min(60, Integer.parseInt(w.substring(5))));
                else {
                    sizes.clear();
                    for (String s : w.split("[+,]")) sizes.add(Math.max(1, Math.min(10, Double.parseDouble(s.replace("x", "")))));
                }
            } catch (NumberFormatException e) {
                say(to, "Bad number in " + a[i] + ".", ERR);
                return;
            }
        }
        jumpRunning = true;
        int secs = seconds;
        World world = place.world;
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            List<SavedBlock> saved = new ArrayList<>();
            List<Ref<EntityStore>> dummies = new ArrayList<>();
            double groundY;
            try {
                double y = FightFx.standableY(place.pos.x, place.pos.z, place.pos.y, 8, store);
                groundY = Double.isFinite(y) ? Math.floor(y) : Math.floor(place.pos.y);
                if (!buildSteps(world, (int) Math.floor(place.pos.x), (int) groundY, (int) Math.floor(place.pos.z), saved)) {
                    restoreBlocks(world, saved);
                    jumpRunning = false;
                    say(to, "Could not build the steps there (chunk not loaded?).", ERR);
                    return;
                }
                for (int h = 1; h <= JUMP_STEPS; h++) {
                    dummies.add(spawn("Test_Dummy_Stationary", stepTop(place.pos, groundY, h), 0f, store));
                }
            } catch (RuntimeException e) {
                restoreBlocks(world, saved);
                jumpRunning = false;
                say(to, "Jump test failed while building: " + e, ERR);
                return;
            }
            say(to, String.format(Locale.ROOT, "Jump test: %s, steps 1-%d blocks along +X from %.0f %.0f %.0f, sizes %s, %d s each.",
                    role, JUMP_STEPS, place.pos.x, groundY, place.pos.z, sizes, secs), INFO);
            jumpRound(to, world, role, place.pos, groundY, sizes, 0, secs, dummies, saved, new ArrayList<>());
        });
    }

    /** One size: one mob per step, sampled for {@code seconds}, then the next size (or the clean-up). World thread. */
    private static void jumpRound(CommandSender to, World world, String role, Vector3d origin, double groundY, List<Double> sizes,
                                  int index, int seconds, List<Ref<EntityStore>> dummies, List<SavedBlock> saved, List<String> report) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (index >= sizes.size()) {
            for (Ref<EntityStore> d : dummies) {
                if (d != null && d.isValid()) {
                    UUID id = idOf(d, store);
                    if (id != null) SPAWNED.remove(id);
                    store.removeEntity(d, RemoveReason.REMOVE);
                }
            }
            restoreBlocks(world, saved);
            jumpRunning = false;
            String file = writeReport("jump", "size | real scale | highest step stood on | per step: max feet height above ground", report);
            List<String> brief = new ArrayList<>();
            for (String r : report) brief.add(r.substring(0, r.indexOf(" | per step")));
            say(to, "Jump test done. " + String.join("; ", brief) + (file != null ? ". Full report: " + file : ""), OK);
            return;
        }
        double size = sizes.get(index);
        List<Ref<EntityStore>> mobs = new ArrayList<>();
        double[] best = new double[JUMP_STEPS];
        for (int h = 1; h <= JUMP_STEPS; h++) {
            Vector3d top = stepTop(origin, groundY, h);
            Ref<EntityStore> mob = spawn(role, new Vector3d(top.x - JUMP_START, groundY, top.z), (float) (-Math.PI / 2), store);
            mobs.add(mob);
            best[h - 1] = Double.NEGATIVE_INFINITY;
            if (mob == null) continue;
            if (size > 1.0001) Buffs.setDirect(mob, Buffs.Stat.SIZE, (int) Math.round((size - 1) * 100));
            Ref<EntityStore> dummy = dummies.get(h - 1);
            if (dummy != null && dummy.isValid()) FightManager.turnAgainst(mob, dummy, store);
        }
        long end = System.currentTimeMillis() + seconds * 1000L;
        jumpSample(to, world, role, origin, groundY, sizes, index, seconds, dummies, saved, report, mobs, best, end);
    }

    private static void jumpSample(CommandSender to, World world, String role, Vector3d origin, double groundY, List<Double> sizes,
                                   int index, int seconds, List<Ref<EntityStore>> dummies, List<SavedBlock> saved, List<String> report,
                                   List<Ref<EntityStore>> mobs, double[] best, long end) {
        world.scheduleAfter(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            for (int i = 0; i < mobs.size(); i++) {
                Ref<EntityStore> m = mobs.get(i);
                Vector3d p = m != null && m.isValid() ? ChaosWand.positionOf(m, store) : null;
                if (p != null) best[i] = Math.max(best[i], p.y - groundY);
            }
            if (System.currentTimeMillis() < end) {
                jumpSample(to, world, role, origin, groundY, sizes, index, seconds, dummies, saved, report, mobs, best, end);
                return;
            }
            double scale = 1;
            int highest = 0;
            StringBuilder per = new StringBuilder();
            for (int i = 0; i < mobs.size(); i++) {
                int h = i + 1;
                Ref<EntityStore> m = mobs.get(i);
                if (m != null && m.isValid()) {
                    scale = Math.max(scale, Buffs.sizeScale(m, store));
                    // Stood on the top: its feet reached the step's height (small margin for the sampling).
                    if (best[i] >= h - 0.2) highest = Math.max(highest, h);
                    UUID id = idOf(m, store);
                    if (id != null) SPAWNED.remove(id);
                    store.removeEntity(m, RemoveReason.REMOVE);
                }
                per.append(per.length() > 0 ? ", " : "").append(h).append(':')
                        .append(Double.isInfinite(best[i]) ? "-" : String.format(Locale.ROOT, "%.1f", best[i]));
            }
            report.add(String.format(Locale.ROOT, "x%s | x%.2f | step %d | per step %s", fmtSize(sizes.get(index)), scale, highest, per));
            say(to, "Jump x" + fmtSize(sizes.get(index)) + " (real x" + String.format(Locale.ROOT, "%.2f", scale) + "): climbed up to step "
                    + highest + " of " + JUMP_STEPS + ".", INFO);
            jumpRound(to, world, role, origin, groundY, sizes, index + 1, seconds, dummies, saved, report);
        }, JUMP_SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    private static String fmtSize(double s) {
        return s == Math.rint(s) ? String.valueOf((long) s) : String.valueOf(s);
    }

    /** Top centre of step h (where its dummy stands). */
    private static Vector3d stepTop(Vector3d origin, double groundY, int h) {
        return new Vector3d(Math.floor(origin.x) + h * JUMP_GAP + 0.5, groundY + h, Math.floor(origin.z) + 0.5);
    }

    /** Builds the 3x3 steps (the block under the start spot if solid, else Rock_Stone), saving every block it replaces. */
    private static boolean buildSteps(World world, int x0, int groundY, int z0, List<SavedBlock> saved) {
        var types = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap();
        int ground = blockAt(world, x0, groundY - 1, z0);
        var type = ground > 0 ? types.getAsset(ground) : null;
        if (type == null || type.getMaterial() != com.hypixel.hytale.protocol.BlockMaterial.Solid) {
            int stone = types.getIndex("Rock_Stone");
            type = stone >= 0 ? types.getAsset(stone) : null;
        }
        if (type == null) return false;
        int id = types.getIndex(type.getId());
        for (int h = 1; h <= JUMP_STEPS; h++) {
            int cx = x0 + h * JUMP_GAP;
            for (int y = groundY; y < groundY + h; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int bx = cx + dx, bz = z0 + dz;
                        int old = blockAt(world, bx, y, bz);
                        if (old < 0) return false;
                        saved.add(new SavedBlock(bx, y, bz, old));
                        if (!setBlockAt(world, bx, y, bz, id, type)) return false;
                    }
                }
            }
        }
        return true;
    }

    private static void restoreBlocks(World world, List<SavedBlock> saved) {
        var types = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap();
        for (int i = saved.size() - 1; i >= 0; i--) {
            SavedBlock b = saved.get(i);
            try {
                setBlockAt(world, b.x(), b.y(), b.z(), b.id(), types.getAsset(b.id()));
            } catch (RuntimeException ignored) {
            }
        }
        saved.clear();
    }

    /** Block id at a position, -1 if its chunk section isn't loaded. World thread. */
    private static int blockAt(World world, int x, int y, int z) {
        var chunks = world.getChunkStore();
        var ref = chunks.getChunkSectionReference(x >> 5, y >> 5, z >> 5);
        if (ref == null || !ref.isValid()) return -1;
        var section = chunks.getStore().getComponent(ref,
                com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
        return section != null ? section.get(x & 31, y & 31, z & 31) : -1;
    }

    /** Sets a block the way the game's /block set does (BlockOperations.setBlock, no extra settings). World thread. */
    private static boolean setBlockAt(World world, int x, int y, int z, int id,
                                      com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType type) {
        var chunks = world.getChunkStore();
        var ref = chunks.getChunkSectionReference(x >> 5, y >> 5, z >> 5);
        if (ref == null || !ref.isValid()) return false;
        com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations.setBlock(chunks, ref, x, y, z, id, type, 0, 0, 0);
        return true;
    }

    /** tests/&lt;name&gt;_&lt;time&gt;.txt with a header line; the path, or null. */
    private static String writeReport(String name, String header, List<String> lines) {
        try {
            Path dir = FxSettings.dataDirectory().resolve("tests");
            Files.createDirectories(dir);
            Path file = dir.resolve(name + "_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
            List<String> out = new ArrayList<>();
            out.add(header);
            out.addAll(lines);
            Files.write(file, out, StandardCharsets.UTF_8);
            return file.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- stats / expect ----

    private static void stats(CommandSender to, String[] a) {
        if (a.length < 2) {
            say(to, "Usage: /mobbattle test stats <id|last>", ERR);
            return;
        }
        Ref<EntityStore> r = resolve(a[1]);
        if (r == null) {
            say(to, "No entity " + a[1] + " (gone, dead or unloaded).", ERR);
            return;
        }
        Store<EntityStore> store = r.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!r.isValid()) return;
            StringBuilder b = new StringBuilder(ChaosWand.nameOf(r, store)).append(' ').append(idOf(r, store));
            b.append(' ').append(where(r, store)).append(", ").append(health(r, store));
            b.append(String.format(Locale.ROOT, ", size x%.2f", Buffs.sizeScale(r, store)));
            List<String> buffs = new ArrayList<>();
            for (Buffs.Stat st : Buffs.Stat.values()) {
                int v = Buffs.totalOf(r, st);
                if (v != 0) buffs.add(st.key + " +" + v + "%");
            }
            b.append(", buffs ").append(buffs.isEmpty() ? "none" : String.join(" ", buffs));
            List<String> fx = effectIds(r, store);
            b.append(", effects ").append(fx.isEmpty() ? "none" : String.join(" ", fx.subList(0, Math.min(10, fx.size())))
                    + (fx.size() > 10 ? " +" + (fx.size() - 10) : ""));
            b.append(", battle ").append(field("battle", r, store)).append(" team ").append(field("team", r, store));
            b.append(", calm ").append(field("calm", r, store)).append(", army ").append(field("army", r, store));
            b.append(", model ").append(field("model", r, store)).append(MorphWand.originalOf(r) != null ? " (morphed)" : "");
            say(to, b.toString(), INFO);
        });
    }

    private static List<String> effectIds(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        List<String> out = new ArrayList<>();
        var c = acc.getComponent(r, com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent.getComponentType());
        // getAllActiveEntityEffects is null (not empty) when it has none.
        var all = c != null ? c.getAllActiveEntityEffects() : null;
        if (all == null) return out;
        for (var e : all) {
            var asset = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.getAssetMap().getAsset(e.getEntityEffectIndex());
            if (asset != null) out.add(asset.getId());
        }
        return out;
    }

    /** One field for stats/expect, as text. World thread. */
    private static String field(String name, Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        EntityStatMap stats = acc.getComponent(r, EntityStatMap.getComponentType());
        EntityStatValue h = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        Vector3d p = ChaosWand.positionOf(r, acc);
        BattleManager.Battle battle = BattleManager.battleOf(r);
        return switch (name) {
            case "alive" -> String.valueOf(BattleManager.isAlive(r, acc));
            case "health" -> h == null ? "?" : fmt(h.get());
            case "maxhealth" -> h == null ? "?" : fmt(h.getMax());
            case "healthpct" -> h == null || h.getMax() <= 0 ? "?" : fmt(100 * h.get() / h.getMax());
            case "size" -> fmt(Buffs.sizeScale(r, acc));
            case "battle" -> battle == null ? "none" : battle.kind.key;
            case "team" -> battle == null ? "none" : String.valueOf(battle.team(r));
            case "calm" -> String.valueOf(SummonCalm.isCalm(r));
            case "army" -> String.valueOf(Army.ownerOf(r) != null);
            case "morphed" -> String.valueOf(MorphWand.originalOf(r) != null);
            case "model" -> {
                MorphWand.Copy c = MorphWand.read(r, acc);
                yield c == null ? "?" : c.modelId();
            }
            case "x" -> p == null ? "?" : fmt(p.x);
            case "y" -> p == null ? "?" : fmt(p.y);
            case "z" -> p == null ? "?" : fmt(p.z);
            default -> {
                if (name.startsWith("buff.")) {
                    Buffs.Stat st = Buffs.Stat.of(name.substring(5));
                    yield st == null ? null : String.valueOf(Buffs.totalOf(r, st));
                }
                yield null;
            }
        };
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static final String EXPECT_FIELDS = "alive, health, maxhealth, healthpct, size, buff.<size|health|damage|defense|speed>, effect, battle,"
            + " team, calm, army, morphed, model, x, y, z";

    /** test expect &lt;id|last&gt; &lt;field&gt; &lt;op&gt; &lt;value&gt;: one check, counted by the running script. */
    private static void expect(CommandSender to, String[] a) {
        if (a.length < 5) {
            say(to, "Usage: /mobbattle test expect <id|last> <field> <=|!=|>|<|>=|<=> <value>. Fields: " + EXPECT_FIELDS, ERR);
            return;
        }
        String fieldName = a[2].toLowerCase(Locale.ROOT), op = a[3], want = String.join(" ", java.util.Arrays.copyOfRange(a, 4, a.length));
        if (!List.of("=", "==", "!=", ">", "<", ">=", "<=").contains(op)) {
            say(to, "Operators: = != > < >= <=", ERR);
            return;
        }
        Script script = Script.current;
        String label = "expect " + a[1] + " " + fieldName + " " + op + " " + want;
        Ref<EntityStore> r = resolve(a[1]);
        if (r == null) {
            // Gone: only "alive = false" (or != true) holds.
            boolean ok = fieldName.equals("alive") && (op.startsWith("=") ? want.equalsIgnoreCase("false") : op.equals("!=") && want.equalsIgnoreCase("true"));
            report(to, script, label, ok, "entity gone");
            return;
        }
        Store<EntityStore> store = r.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!r.isValid()) {
                report(to, script, label, false, "entity gone");
                return;
            }
            if (fieldName.equals("effect")) {
                boolean has = false;
                for (String id : effectIds(r, store)) if (id.toLowerCase(Locale.ROOT).contains(want.toLowerCase(Locale.ROOT))) has = true;
                boolean ok = op.equals("!=") ? !has : has;
                report(to, script, label, ok, has ? "has it" : "doesn't have it");
                return;
            }
            String got = field(fieldName, r, store);
            if (got == null) {
                report(to, script, label, false, "unknown field (" + EXPECT_FIELDS + ")");
                return;
            }
            report(to, script, label, compare(got, op, want), "got " + got);
        });
    }

    private static boolean compare(String got, String op, String want) {
        try {
            double g = Double.parseDouble(got), w = Double.parseDouble(want.replace("x", "").replace("%", ""));
            return switch (op) {
                case "=", "==" -> Math.abs(g - w) < 0.01;
                case "!=" -> Math.abs(g - w) >= 0.01;
                case ">" -> g > w;
                case "<" -> g < w;
                case ">=" -> g >= w - 0.01;
                default -> g <= w + 0.01;
            };
        } catch (NumberFormatException e) {
            boolean same = got.equalsIgnoreCase(want);
            return op.equals("!=") ? !same : (op.equals("=") || op.equals("==")) && same;
        }
    }

    private static void report(CommandSender to, Script script, String label, boolean ok, String detail) {
        if (script != null && Script.current == script) (ok ? script.passed : script.failed).incrementAndGet();
        say(to, (ok ? "✅ " : "❌ ") + label + " (" + detail + ")", ok ? OK : ERR);
    }

    // ---- camera ----

    static final List<String> CAMERA_STYLES = List.of("orbit", "duel", "showcase", "teams", "focus", "drone", "spotlight");

    /** test camera &lt;style&gt; &lt;player&gt; [seconds]: that style over the living test mobs of the player's world. */
    private static void camera(CommandSender to, String[] a) {
        if (a.length < 3 || !CAMERA_STYLES.contains(a[1].toLowerCase(Locale.ROOT))) {
            say(to, "Usage: /mobbattle test camera <" + String.join("|", CAMERA_STYLES) + "> <player> [seconds]", ERR);
            return;
        }
        String style = a[1].toLowerCase(Locale.ROOT);
        var player = ApplyCommand.onlinePlayer(a[2]);
        Ref<EntityStore> me = player != null ? player.getReference() : null;
        if (me == null || !me.isValid()) {
            say(to, "No player online named " + a[2] + ".", ERR);
            return;
        }
        int seconds = 8;
        if (a.length > 3 && a[3].matches("\\d{1,2}")) seconds = Math.max(2, Math.min(60, Integer.parseInt(a[3])));
        int secs = seconds;
        Store<EntityStore> store = me.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            List<Ref<EntityStore>> mobs = new ArrayList<>();
            SPAWNED.forEach((id, w) -> {
                if (!w.equals(world.getName())) return;
                Ref<EntityStore> r = world.getEntityRef(id);
                if (r != null && r.isValid() && BattleManager.isAlive(r, store)) mobs.add(r);
            });
            if (mobs.isEmpty()) {
                say(to, "No test mobs alive in " + world.getName() + ": spawn some first (test spawn / test royale).", ERR);
                return;
            }
            List<List<Ref<EntityStore>>> sides = new ArrayList<>();
            if (style.equals("teams")) {
                int half = (mobs.size() + 1) / 2;
                sides.add(new ArrayList<>(mobs.subList(0, half)));
                if (half < mobs.size()) sides.add(new ArrayList<>(mobs.subList(half, mobs.size())));
            } else {
                for (Ref<EntityStore> r : mobs) sides.add(List.of(r));
            }
            FightCamera.test(style, secs, player, sides, store);
            say(to, "Camera " + style + " for " + player.getUsername() + " over " + mobs.size() + " test mobs, " + secs + " s.", OK);
        });
    }

    // ---- settings snapshot ----

    private static Path snapshotFile(String name) {
        return FxSettings.dataDirectory().resolve("tests").resolve("snapshots").resolve(name + ".properties");
    }

    /** test snapshot [name]: fx.properties as it is now (settings, teams and summon list) into tests/snapshots. */
    private static void snapshot(CommandSender to, String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,40}") || FxSettings.dataDirectory() == null) {
            say(to, "A snapshot name is letters, digits, _ and -.", ERR);
            return;
        }
        try {
            FxSettings.save();
            Path target = snapshotFile(name);
            Files.createDirectories(target.getParent());
            Files.copy(FxSettings.dataDirectory().resolve("fx.properties"), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            say(to, "Settings saved to snapshot " + name + ".", OK);
        } catch (java.io.IOException e) {
            say(to, "Could not save the snapshot: " + e.getMessage(), ERR);
        }
    }

    /** test restore [name]: the snapshot back into fx.properties, then applied as /mobbattle reload does. */
    private static void restore(CommandSender to, String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,40}") || FxSettings.dataDirectory() == null) {
            say(to, "A snapshot name is letters, digits, _ and -.", ERR);
            return;
        }
        Path source = snapshotFile(name);
        if (!Files.exists(source)) {
            say(to, "No snapshot " + name + ".", ERR);
            return;
        }
        try {
            Files.copy(source, FxSettings.dataDirectory().resolve("fx.properties"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            say(to, "Could not restore: " + e.getMessage(), ERR);
            return;
        }
        String error = FxSettings.reload();
        say(to, error == null ? "Settings restored from snapshot " + name + "." : error, error == null ? OK : ERR);
    }

    // ---- helpers ----

    /** Where a test happens: a world and a position, and the index of the first word after them. */
    private record Place(World world, Vector3d pos, int next) {
    }

    /**
     * "here" (the player's position and world) or "x y z", then an optional world name. Replies and returns null
     * when it can't tell.
     */
    private static Place place(CommandContext ctx, String[] a, int i) {
        CommandSender to = ctx.sender();
        if (i < a.length && a[i].equalsIgnoreCase("here")) {
            Ref<EntityStore> me = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
            if (me == null || !me.isValid()) {
                say(to, "Only a player in a world can use here; give x y z.", ERR);
                return null;
            }
            World w = me.getStore().getExternalData().getWorld();
            // Read on the world thread (a World is an Executor), as World.getEntityRef does from other threads.
            Vector3d pos;
            try {
                pos = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Vector3d p = me.isValid() ? ChaosWand.positionOf(me, me.getStore()) : null;
                    return p != null ? new Vector3d(p) : null;
                }, w).join();
            } catch (RuntimeException e) {
                pos = null;
            }
            if (pos == null) {
                say(to, "Could not read your position.", ERR);
                return null;
            }
            return new Place(w, pos, i + 1);
        }
        if (i + 2 >= a.length) {
            say(to, "Give a position: here or x y z.", ERR);
            return null;
        }
        Vector3d pos;
        try {
            pos = new Vector3d(Double.parseDouble(a[i]), Double.parseDouble(a[i + 1]), Double.parseDouble(a[i + 2]));
        } catch (NumberFormatException e) {
            say(to, "Give a position: here or x y z.", ERR);
            return null;
        }
        int next = i + 3;
        World w = null;
        if (next < a.length) w = Universe.get().getWorld(a[next]);
        if (w != null) {
            next++;
        } else if (ctx.isPlayer() && ctx.senderAsPlayerRef() != null && ctx.senderAsPlayerRef().isValid()) {
            w = ctx.senderAsPlayerRef().getStore().getExternalData().getWorld();
        } else {
            w = Universe.get().getDefaultWorld();
        }
        if (w == null) {
            say(to, "No world.", ERR);
            return null;
        }
        return new Place(w, pos, next);
    }

    private static UUID idOf(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        UUIDComponent c = acc.getComponent(r, UUIDComponent.getComponentType());
        return c != null ? c.getUuid() : null;
    }

    private static String where(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        Vector3d p = ChaosWand.positionOf(r, acc);
        return p == null ? "?" : String.format(Locale.ROOT, "at %.1f %.1f %.1f", p.x, p.y, p.z);
    }

    private static String health(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        EntityStatMap stats = acc.getComponent(r, EntityStatMap.getComponentType());
        EntityStatValue h = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        return h == null ? "health ?" : String.format(Locale.ROOT, "health %.1f/%.1f", h.get(), h.getMax());
    }

    private static double halfWidth(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        BoundingBox bb = acc.getComponent(r, BoundingBox.getComponentType());
        if (bb == null || bb.getBoundingBox() == null) return 0.4;
        var box = bb.getBoundingBox();
        return Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2;
    }

    /** To the sender and the server log ("[test] ..."). */
    static void say(CommandSender to, String text, String color) {
        if (to != null) to.sendMessage(Message.raw(text).color(color));
        MobBattlePlugin.log("[test] " + text);
    }

    /** Plugin shutdown: scripts and duel logs end. */
    static void stopAll() {
        Script.stopAll();
        VERIFY.clear();
        verifyRunning = false;
        CALLS.clear();
        callsRunning = false;
        WATCH.clear();
        DUELS.clear();
        AutoCloseable s = subscription;
        subscription = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // shutting down
            }
        }
    }
}
