package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The summon list (Summon Wand and /mobbattle summon): NPC roles with a fixed count or "random",
 * spawned in a ring around the player to set up battles quickly.
 */
public final class SummonList {

    public static final String RANDOM = "random";

    /** Role name -> count ("3") or "random". Insertion order is kept for /mobbattle summon list. */
    public static final Map<String, String> ENTRIES = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    /** Role name -> team index (Team Wand colors). Used by summon and /mobbattle teams auto. */
    public static final Map<String, Integer> TEAMS = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /*
     * Both maps are edited by /mobbattle commands and read by the world thread (Summon Wand, teams auto).
     * Single calls (get/put/remove/forEach/removeIf) are synchronized by the wrapper; anything that walks
     * a key/entry view must work on a snapshot() copy instead.
     */

    /** Copy of the list in its order, safe to iterate while a command edits it. */
    static Map<String, String> snapshot() {
        synchronized (ENTRIES) {
            return new LinkedHashMap<>(ENTRIES);
        }
    }

    /** Range used by "random" entries. */
    public static int randomMin = 1;
    public static int randomMax = 5;
    /** If true, "random" entries spawn at least one mob. */
    public static boolean atLeastOne = true;

    private SummonList() {
    }

    /** The loaded role whose name matches ignoring case, or null. */
    public static String findRole(String name) {
        if (name == null) {
            return null;
        }
        NPCPlugin npc = NPCPlugin.get();
        if (npc.hasRoleName(name)) {
            return name;
        }
        for (String known : snapshot().keySet()) {
            if (known.equalsIgnoreCase(name)) {
                return known;
            }
        }
        return null;
    }

    /** Adds the role, or removes it if it is already listed. Returns true if it is now in the list. */
    public static boolean toggle(String role) {
        if (ENTRIES.remove(role) != null) {
            TEAMS.remove(role);
            return false;
        }
        ENTRIES.put(role, "1");
        return true;
    }

    public static String describe() {
        if (ENTRIES.isEmpty()) {
            return "Summon list is empty. Add mobs with the Summon Wand or /mobbattle summon add <role> [count|random].";
        }
        StringBuilder sb = new StringBuilder("Summon list: ");
        ENTRIES.forEach((role, count) -> {
            sb.append(role).append(" x").append(count);
            Integer team = TEAMS.get(role);
            if (team != null) {
                sb.append(" (").append(Teams.name(team)).append(")");
            }
            sb.append(", ");
        });
        sb.setLength(sb.length() - 2);
        sb.append(" | random ").append(randomMin).append("-").append(randomMax)
                .append(", at least one: ").append(atLeastOne);
        return sb.toString();
    }

    static int countOf(String value) {
        if (RANDOM.equalsIgnoreCase(value)) {
            int lo = atLeastOne ? Math.max(1, randomMin) : Math.max(0, randomMin);
            int hi = Math.max(lo, randomMax);
            return ThreadLocalRandom.current().nextInt(lo, hi + 1);
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Normalize both ends, including reversed, negative or oversized saved ranges. */
    static void setRandomRange(int min, int max) {
        randomMin = Math.max(0, Math.min(100, Math.min(min, max)));
        randomMax = Math.max(0, Math.min(100, Math.max(min, max)));
    }

    /**
     * Spawns the whole list in a ring around {@code center}. Must run on the world thread
     * (pass the world's Store). Capped at /mobbattle maxmobs. Mobs stand on the nearest floor under them
     * (works under roofs), close to the player (the ring grows with the count), inside a drawn area,
     * and play their model's "Spawn" animation when it has one (108 vanilla models do).
     */
    public static void spawn(Store<EntityStore> store, Vector3d center, PlayerRef player, Ref<EntityStore> owner) {
        if (ENTRIES.isEmpty()) {
            player.sendMessage(Message.raw(describe()).color("#ffcc55"));
            return;
        }
        NPCPlugin npc = NPCPlugin.get();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int spawned = 0;
        int failed = 0;
        int attempted = 0;
        StringBuilder summary = new StringBuilder();
        // One copy: the list can change from a command while this runs (see snapshot()).
        Map<String, String> entries = snapshot();
        int planned = 0;
        for (String value : entries.values()) {
            planned += value.equalsIgnoreCase("random") ? Math.max(1, (randomMin + randomMax) / 2) : countOf(value);
        }
        planned = Math.min(planned, FxSettings.maxMobs);
        // /mobbattle summonradius, or auto: about one mob per 5 square blocks, never past half the Chaos area radius.
        double ring = FxSettings.summonRadius > 0 ? FxSettings.summonRadius
                : Math.max(4, Math.min(FxSettings.radius / 2.0, 3 + Math.sqrt(planned) * 1.6));
        var floor = FightFx.floorBelow(center.y, store);
        for (Map.Entry<String, String> e : entries.entrySet()) {
            int n = countOf(e.getValue());
            int ok = 0;
            for (int i = 0; i < n && attempted < FxSettings.maxMobs; i++) {
                attempted++;
                double angle = rnd.nextDouble(Math.PI * 2);
                double dist = 1.5 + Math.sqrt(rnd.nextDouble()) * (ring - 1.5);
                double x = center.x + Math.cos(angle) * dist;
                double z = center.z + Math.sin(angle) * dist;
                double y = floor.applyAsDouble(x, z);
                // floorBelow is 0.25 above the block top; no floor loaded nearby: the player's height.
                Vector3d pos = new Vector3d(x, Double.isFinite(y) ? y - 0.25 : center.y, z);
                try {
                    var result = npc.spawnNPC(store, e.getKey(), null, pos,
                            new Rotation3f(0f, (float) rnd.nextDouble(Math.PI * 2), 0f));
                    if (result != null && result.first() != null) {
                        Integer team = TEAMS.get(e.getKey());
                        if (team != null) {
                            // Spawned straight into the player's team draft (Team Wand / teams start).
                            BattleManager.assign(BattleManager.draft(player), result.first(), team);
                        }
                        WandFx.burst(WandFx.SUMMON_PARTICLE, null, pos, store);
                        playSpawnAnimation(result.first(), store);
                        ok++;
                        spawned++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    MobBattlePlugin.log("Could not spawn " + e.getKey() + ": " + ex);
                }
            }
            if (ok > 0) {
                summary.append(e.getKey()).append(" x").append(ok).append(", ");
            }
        }
        if (summary.length() > 0) {
            summary.setLength(summary.length() - 2);
        }
        if (spawned > 0) {
            FightFx.area(owner, center, ring, SUMMON_AREA_SECONDS, store);
            WandFx.burst(null, WandFx.SUMMON_SOUND, center, store);
        }
        player.sendMessage(Message.raw("Summoned " + spawned + " mobs: " + summary
                + (failed > 0 ? " (" + failed + " failed)" : "")
                + (attempted >= FxSettings.maxMobs ? " — capped by /mobbattle maxMobs (spawn attempts)" : "")).color("#4bff6a"));
    }

    /** The summon area is a quick flash, not the Chaos Wand's 6 s. */
    private static final float SUMMON_AREA_SECONDS = 3f;

    /** Plays the model's "Spawn" animation (e.g. skeletons rising from the ground), if it has one. */
    private static void playSpawnAnimation(Ref<EntityStore> mob, Store<EntityStore> store) {
        if (!FxSettings.wandFx || mob == null || !mob.isValid()) {
            return;
        }
        var mc = store.getComponent(mob, com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getComponentType());
        var sets = mc != null && mc.getModel() != null ? mc.getModel().getAnimationSetMap() : null;
        if (sets != null && sets.containsKey("Spawn")) {
            com.hypixel.hytale.server.core.entity.AnimationUtils.playAnimation(mob,
                    com.hypixel.hytale.protocol.AnimationSlot.Action, "Spawn", true, store);
        }
    }

    // ---- persistence (fx.properties, via FxSettings) ----

    static void load(Properties p) {
        ENTRIES.clear();
        String list = p.getProperty("summon.list", "");
        for (String item : list.split(",")) {
            String[] kv = item.trim().split(":");
            if (kv.length == 2 && !kv[0].isBlank()) {
                ENTRIES.put(kv[0].trim(), kv[1].trim());
            }
        }
        try {
            randomMin = Integer.parseInt(p.getProperty("summon.min", String.valueOf(randomMin)));
            randomMax = Integer.parseInt(p.getProperty("summon.max", String.valueOf(randomMax)));
        } catch (NumberFormatException ignored) {
        }
        setRandomRange(randomMin, randomMax);
        atLeastOne = Boolean.parseBoolean(p.getProperty("summon.atleastone", String.valueOf(atLeastOne)));
        TEAMS.clear();
        for (String item : p.getProperty("summon.teams", "").split(",")) {
            String[] kv = item.trim().split(":");
            int team = kv.length == 2 ? BattleManager.teamIndex(kv[1].trim()) : -1;
            if (team >= 0 && !kv[0].isBlank()) {
                TEAMS.put(kv[0].trim(), team);
            }
        }
    }

    static void save(Properties p) {
        StringBuilder sb = new StringBuilder();
        ENTRIES.forEach((role, count) -> sb.append(role).append(':').append(count).append(','));
        p.setProperty("summon.list", sb.toString());
        p.setProperty("summon.min", String.valueOf(randomMin));
        p.setProperty("summon.max", String.valueOf(randomMax));
        p.setProperty("summon.atleastone", String.valueOf(atLeastOne));
        StringBuilder teams = new StringBuilder();
        TEAMS.forEach((role, team) -> {
            if (Teams.exists(team)) {
                teams.append(role).append(':').append(Teams.name(team)).append(',');
            }
        });
        p.setProperty("summon.teams", teams.toString());
    }
}
