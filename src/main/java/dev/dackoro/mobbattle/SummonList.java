package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The summon list (Summon Wand and /mobbattle summon): NPC roles with a fixed count or "random",
 * spawned in a ring around the player to set up battles quickly.
 */
public final class SummonList {

    public static final String RANDOM = "random";

    /** Entry key (the role, or role#n for more entries of it) -> count ("3") or "random". Insertion order is kept for /mobbattle summon list. */
    public static final Map<String, String> ENTRIES = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    /** Entry key -> team index (Team Wand colors). Used by summon and /mobbattle teams auto. */
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

    /** A list count: "random" or a whole number (clamped to MAX_MOBS_LIMIT by {@link #normalCount}). */
    static boolean isCount(String s) {
        return s != null && (RANDOM.equalsIgnoreCase(s.trim()) || s.trim().matches("\\d{1,9}"));
    }

    /** Lower case "random", or the number clamped to 0..MAX_MOBS_LIMIT (no 999 cap since 2026-10-13, user). */
    static String normalCount(String s) {
        String v = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (RANDOM.equals(v)) return v;
        return String.valueOf(Math.min(FxSettings.MAX_MOBS_LIMIT, Long.parseLong(v)));
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
        // A mob already listed first, then the game's own spelling: hasRoleName ignores case, and "zombie" became an
        // entry of its own next to "Zombie" (user, 2026-10-13), so the counts didn't add up.
        for (String known : snapshot().keySet()) {
            if (known.equalsIgnoreCase(name) || roleOf(known).equalsIgnoreCase(name)) {
                return roleOf(known);
            }
        }
        NPCPlugin npc = NPCPlugin.get();
        if (npc.hasRoleName(name)) {
            for (String role : npc.getRoleTemplateNames(true)) {
                if (role.equalsIgnoreCase(name)) return role;
            }
            return name;
        }
        return null;
    }

    /*
     * Entry keys (2026-10-13, user: 10 Skeleton_Archer in red and 10 in green): the first entry of a role is keyed by
     * the role itself, more entries of the same role (another count or team) by "role#2", "role#3"... ENTRIES and TEAMS
     * are keyed by entry; spawning uses roleOf(key). Old lists (one entry per role) load unchanged.
     */

    /** The role of a list entry key ("Skeleton_Archer#2" -> "Skeleton_Archer"). */
    static String roleOf(String key) {
        if (key == null) return null;
        int i = key.lastIndexOf('#');
        return i > 0 && i < key.length() - 1 && key.substring(i + 1).chars().allMatch(Character::isDigit)
                ? key.substring(0, i) : key;
    }

    /** A free entry key for one more entry of {@code role}. */
    static String newKey(String role) {
        synchronized (ENTRIES) {
            if (!ENTRIES.containsKey(role)) return role;
            for (int n = 2; ; n++) {
                String key = role + "#" + n;
                if (!ENTRIES.containsKey(key)) return key;
            }
        }
    }

    /** The entry of {@code role} with that team rule (-1 = none), or null. */
    static String entryOf(String role, int team) {
        for (String key : snapshot().keySet()) {
            Integer t = TEAMS.get(key);
            if (roleOf(key).equalsIgnoreCase(role) && (t == null ? team < 0 : t == team)) return key;
        }
        return null;
    }

    /** A listed entry by its key (any case), else the first entry of that role; null if none. */
    static String listedKey(String name) {
        if (name == null) return null;
        String n = name.trim();
        Map<String, String> list = snapshot();
        for (String key : list.keySet()) if (key.equalsIgnoreCase(n)) return key;
        for (String key : list.keySet()) if (roleOf(key).equalsIgnoreCase(n)) return key;
        return null;
    }

    /** The team rules of a role, one per entry with a team, in list order (teams auto). */
    static List<Integer> teamsOf(String role) {
        List<Integer> out = new ArrayList<>();
        for (String key : snapshot().keySet()) {
            Integer t = TEAMS.get(key);
            if (t != null && Teams.exists(t) && roleOf(key).equals(role)) out.add(t);
        }
        return out;
    }

    /** Adds the role, or removes every entry of it if it is already listed. Returns true if it is now in the list. */
    public static boolean toggle(String role) {
        boolean removed = false;
        for (String key : snapshot().keySet()) {
            if (roleOf(key).equals(role)) {
                ENTRIES.remove(key);
                TEAMS.remove(key);
                removed = true;
            }
        }
        if (removed) return false;
        ENTRIES.put(role, "1");
        return true;
    }

    public static String describe() {
        Map<String, String> list = snapshot();
        if (list.isEmpty()) {
            return "Summon list is empty. Add mobs with the Summon Wand or /mobbattle summon add <role> [count|random].";
        }
        StringBuilder sb = new StringBuilder("Summon list: ");
        list.forEach((role, count) -> {
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

    /** Same as {@link #describe} but translated, for the player's chat. */
    public static Message describeMessage() {
        // A snapshot (the list may change meanwhile), and role names, not the internal entry keys ("Skeleton#2").
        Map<String, String> list = snapshot();
        if (list.isEmpty()) return I18n.tr("chat.summon.empty");
        StringBuilder sb = new StringBuilder();
        list.forEach((role, count) -> {
            sb.append(roleOf(role)).append(" x").append(count);
            Integer team = TEAMS.get(role);
            if (team != null) {
                sb.append(" (").append(Teams.name(team)).append(")");
            }
            sb.append(", ");
        });
        sb.setLength(sb.length() - 2);
        return I18n.tr("chat.summon.list").param("list", sb.toString()).param("min", randomMin)
                .param("max", randomMax).param("atleast", atLeastOne);
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
        randomMin = Math.max(0, Math.min(FxSettings.MAX_MOBS_LIMIT, Math.min(min, max)));
        randomMax = Math.max(0, Math.min(FxSettings.MAX_MOBS_LIMIT, Math.max(min, max)));
    }

    /**
     * Spawns the whole list in a ring around {@code center}. Must run on the world thread
     * (pass the world's Store). Capped at /mobbattle maxmobs. Mobs stand on the nearest floor under them
     * (works under roofs), close to the player (the ring grows with the count), inside a drawn area,
     * and play their model's "Spawn" animation when it has one (108 vanilla models do).
     */
    public static void spawn(Store<EntityStore> store, Vector3d center, PlayerRef player, Ref<EntityStore> owner) {
        if (ENTRIES.isEmpty()) {
            player.sendMessage(describeMessage().color("#ffcc55"));
            return;
        }
        // One copy: the list can change from a command while this runs (see snapshot()). The plan is one role per
        // mob, in list order, capped by maxMobs; random counts are rolled here, once.
        Map<String, String> entries = snapshot();
        List<String> plan = new ArrayList<>();
        boolean capped = false;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            int n = countOf(e.getValue());
            for (int i = 0; i < n; i++) {
                if (plan.size() >= FxSettings.maxMobs) {
                    capped = true;
                    break;
                }
                plan.add(e.getKey());
            }
        }
        // /mobbattle summonradius, or auto: about one mob per 5 square blocks, never past half the Chaos area radius.
        double ring = FxSettings.summonRadius > 0 ? FxSettings.summonRadius
                : Math.max(4, Math.min(FxSettings.radius / 2.0, 3 + Math.sqrt(plan.size()) * 1.6));
        Batch batch = new Batch(store, new Vector3d(center), player, owner, plan, capped, ring);
        if (!plan.isEmpty()) {
            // The ring, its shockwave and the sound right away; the mobs rise from it over the next ticks.
            FightFx.area(ActionFx.Action.SUMMON, null, owner, center, ring, SUMMON_AREA_SECONDS, store);
            // A shockwave out to the summoning ring (/mobbattle wave summon, default green).
            ActionFx.shockwave(ActionFx.Action.SUMMON, center, ring, store);
            WandFx.burst(null, WandFx.SUMMON_SOUND, center, store);
        }
        batch.run();
    }

    /** Mobs spawned per world tick at most; also stops early once SPAWN_BUDGET_MS is used up (at least one). */
    private static final int SPAWN_PER_TICK = 20;
    private static final long SPAWN_BUDGET_MS = 25;
    /** Gap between two spawn steps (one world tick). */
    private static final long SPAWN_STEP_MS = 50;

    /**
     * One summon, spread over several ticks: spawning 100+ NPCs in one go held the world thread for up to 293 ms
     * ("Task took", 2026-10-12). Each step runs on the world thread and schedules the next one itself (never all the
     * steps up front); it stops if the world closed. The chat summary, the jade and the army join come at the end.
     */
    private static final class Batch implements Runnable {
        final Store<EntityStore> store;
        final Vector3d center;
        final PlayerRef player;
        final Ref<EntityStore> owner;
        final List<String> plan;
        final boolean capped;
        final double ring;
        final EntityEffect look = lookEffect();
        final String rune = runeParticle();
        final java.util.function.DoubleBinaryOperator floor;
        /** Role -> how many came out, in list order, for the chat summary (entries of one role add up). */
        final Map<String, Integer> ok = new LinkedHashMap<>();
        final List<Ref<EntityStore>> toLook = new ArrayList<>();
        final List<Ref<EntityStore>> toArmy = new ArrayList<>();
        int next;
        int spawned;
        int failed;

        Batch(Store<EntityStore> store, Vector3d center, PlayerRef player, Ref<EntityStore> owner, List<String> plan,
              boolean capped, double ring) {
            this.store = store;
            this.center = center;
            this.player = player;
            this.owner = owner;
            this.plan = plan;
            this.capped = capped;
            this.ring = ring;
            this.floor = FightFx.floorBelow(center.y, store);
        }

        /** One step; world thread. */
        @Override
        public void run() {
            if (store.isShutdown()) return;
            NPCPlugin npc = NPCPlugin.get();
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            long until = System.currentTimeMillis() + SPAWN_BUDGET_MS;
            int done = 0;
            while (next < plan.size() && done < SPAWN_PER_TICK && (done == 0 || System.currentTimeMillis() < until)) {
                spawnOne(npc, rnd, plan.get(next++));
                done++;
            }
            if (next < plan.size()) {
                var world = store.getExternalData().getWorld();
                com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(this),
                        SPAWN_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }
            finish();
        }

        private void spawnOne(NPCPlugin npc, ThreadLocalRandom rnd, String key) {
            String role = roleOf(key);
            double angle = rnd.nextDouble(Math.PI * 2);
            double dist = 1.5 + Math.sqrt(rnd.nextDouble()) * (ring - 1.5);
            double x = center.x + Math.cos(angle) * dist;
            double z = center.z + Math.sin(angle) * dist;
            double y = floor.applyAsDouble(x, z);
            // floorBelow is 0.25 above the block top; no floor loaded nearby: the player's height.
            Vector3d pos = new Vector3d(x, Double.isFinite(y) ? y - 0.25 : center.y, z);
            try {
                var result = npc.spawnNPC(store, role, null, pos, new Rotation3f(0f, (float) rnd.nextDouble(Math.PI * 2), 0f));
                if (result == null || result.first() == null) {
                    failed++;
                    return;
                }
                SummonDespawn.track(result.first());
                Integer team = TEAMS.get(key);
                if (team != null) {
                    // Spawned straight into the player's team draft (Team Wand / teams start).
                    BattleManager.assign(BattleManager.draft(player), result.first(), team);
                } else if (FxSettings.summonArmy && owner != null) {
                    toArmy.add(result.first());
                }
                // Army summons are the player's; the rest may wait calm for a battle (/mobbattle summonCalm).
                if (team != null || !FxSettings.summonArmy || owner == null) SummonCalm.add(result.first(), store);
                WandFx.burst(rune, null, pos, store);
                playSpawnAnimation(result.first(), store);
                if (look != null) toLook.add(result.first());
                ok.merge(role, 1, Integer::sum);
                spawned++;
            } catch (RuntimeException ex) {
                failed++;
                MobBattlePlugin.log("Could not spawn " + role + ": " + ex);
            }
        }

        private void finish() {
            StringBuilder summary = new StringBuilder();
            ok.forEach((role, n) -> summary.append(role).append(" x").append(n).append(", "));
            if (summary.length() > 0) summary.setLength(summary.length() - 2);
            afterSpawn(toLook, look, toArmy, player, owner, store);
            player.sendMessage(Message.join(I18n.tr("chat.summon.done").param("count", spawned).param("summary", summary.toString()),
                    failed > 0 ? I18n.tr("chat.summon.failed").param("count", failed) : Message.empty(),
                    capped ? I18n.tr("chat.summon.capped") : Message.empty()).color("#4bff6a"));
        }
    }

    // ---- Summon jade (/mobbattle summonJade, summonJadeColor) ----

    /** The jade goes on this long after the spawn, once the new mob's components are in place. */
    private static final long LOOK_DELAY_MS = 150;

    /** A palette color (its palette spelling; "gold" = Yellow), or null. */
    static String parseJadeColor(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.equalsIgnoreCase("gold")) v = "Yellow";
        for (String c : Teams.PALETTE.keySet()) {
            if (c.equalsIgnoreCase(v)) return c;
        }
        return null;
    }

    /** The jade summons get now (the Effect Wand's jade:&lt;color&gt;), or null when summonJade is off. */
    static EntityEffect lookEffect() {
        return FxSettings.summonJade ? EffectWand.find("jade:" + FxSettings.summonJadeColor) : null;
    }

    /**
     * A moment after the spawn (components in place), in ONE task for the whole summon: the jade on {@code toLook}
     * (kept by LookKeeper like an Effect Wand look) and {@code toArmy} into the player's army (summonArmy).
     */
    private static void afterSpawn(List<Ref<EntityStore>> toLook, EntityEffect look, List<Ref<EntityStore>> toArmy,
                                   PlayerRef player, Ref<EntityStore> owner, Store<EntityStore> store) {
        if ((toLook.isEmpty() || look == null) && toArmy.isEmpty()) return;
        var world = store.getExternalData().getWorld();
        java.util.UUID id = player.getUuid();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown()) return;
            if (look != null) {
                for (Ref<EntityStore> mob : toLook) {
                    if (!mob.isValid()) continue;
                    EffectControllerComponent effects = store.getComponent(mob, EffectControllerComponent.getComponentType());
                    if (effects != null && effects.addEffect(mob, look, store)) LookKeeper.track(mob, look);
                }
            }
            if (toArmy.isEmpty() || owner == null || !owner.isValid()) return;
            for (Ref<EntityStore> mob : toArmy) {
                if (mob.isValid() && !Army.isMember(id, mob)) Army.toggle(player, owner, mob, store);
            }
        }), LOOK_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * The rune each summon rises from (gen-summonrunes.py): the summon border color (/mobbattle borderColor summon),
     * else the summon wave's, else green.
     */
    static String runeParticle() {
        String c = ActionFx.glowColor(ActionFx.Action.SUMMON, FxSettings.areaColor);
        if (c == null) c = ActionFx.wave(ActionFx.Action.SUMMON);
        if (c == null || !Teams.PALETTE.containsKey(c)) c = "Green";
        return "MobBattle_SummonRune_" + c;
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
