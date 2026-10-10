package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Battle buffs (/mobbattle buff &lt;who&gt; &lt;stat&gt; &lt;percent&gt;): a mob can get bigger (size), more max health,
 * harder hits (damage) and fewer taken (defense), from three sources that add up:
 * <ul>
 *   <li>lone: the target of everyone vs one, for that battle;</li>
 *   <li>streak: per kill in a battle (the % times its kills, up to maxKills), for that battle;</li>
 *   <li>champion: the winners of a battle, until they die or get the Cleanse Wand;</li>
 *   <li>wand: the Buff Wand, one stack per click (up to wand maxStacks), mobs and players, until they die or get
 *   the Cleanse Wand.</li>
 * </ul>
 * Size is the game's EntityScaleComponent (what the builder tools scale entities with: the model stays, so a morph
 * and particles on it are kept, and the hitbox follows). Health is a StaticModifier (MAX, MULTIPLICATIVE) on max
 * health: it raises the max, and only heals with buff &lt;who&gt; heal on (lone: heals the target when picked, even with no
 * lone buff). Damage and defense (BuffDamageSystem): the battle
 * sources count against battle enemies only, the wand against anyone. Buffed mobs except the lone target wear a
 * crown (buff crown, MobBattle_Fx_CrownBuff_&lt;Color&gt;). The scale and the modifier are saved with the world like
 * any component, so a server that stops keeps them; everything else is in memory.
 * Every change runs on the mob's world thread ({@link #run}).
 */
final class Buffs {

    enum Source {
        LONE("lone"), STREAK("streak"), CHAMPION("champion"), WAND("wand");

        final String key;

        Source(String key) {
            this.key = key;
        }

        /** Goes when its battle ends (lone, streak). */
        boolean battleOnly() {
            return this == LONE || this == STREAK;
        }

        static Source of(String word) {
            for (Source s : values()) if (s.key.equalsIgnoreCase(word)) return s;
            return null;
        }
    }

    enum Stat {
        SIZE("size", 200), HEALTH("health", 1000), DAMAGE("damage", 1000), DEFENSE("defense", 90);

        final String key;
        /** Highest % for one source (defense: 100 % would make it unkillable). */
        final int max;

        Stat(String key, int max) {
            this.key = key;
            this.max = max;
        }

        static Stat of(String word) {
            for (Stat s : values()) if (s.key.equalsIgnoreCase(word)) return s;
            return null;
        }
    }

    /** % per source and stat (streak: per kill). 0 = none. */
    private static final Map<Source, EnumMap<Stat, Integer>> PERCENT = new EnumMap<>(Source.class);
    /** Streak: kills that count at most. */
    static int streakMax = 5;
    static final int MAX_STREAK = 50;
    /** Crown on streak, champion and wand-buffed mobs: Gold, a palette color, or off. */
    static String crown = "Gold";
    /**
     * Seconds between crown replays on buffed mobs (/mobbattle buff sparks), 0 = off. The game's crown fires its
     * rays, sparks and flash once (burst spawners); only its glow stays, so the crown is put on again.
     */
    static int sparksEvery = 3;
    static final int MAX_SPARKS_EVERY = 60;
    /** Buff Wand: clicks that stack at most. */
    static int wandMax = 5;
    static final int MAX_WAND = 50;
    /** Per source: a new buff (lone: being picked) fills the health to its max (on) or leaves it (off, default). */
    private static final EnumMap<Source, Boolean> HEAL = new EnumMap<>(Source.class);

    private static final String MODIFIER_KEY = "MobBattle_Buff";
    /** The key of the old targetBuff (TargetBuff), still on mobs saved mid-battle with it. */
    private static final String OLD_MODIFIER_KEY = "MobBattle_TargetBuff";

    /** One buffed mob: its sources (weight = 1, or the kills of a streak) and what was changed, to undo it. */
    private static final class State {
        final EnumMap<Source, Integer> weight = new EnumMap<>(Source.class);
        /** EntityScaleComponent scale before the size buff (null: not scaled by us). */
        Float baseScale;
        boolean health;
        String crownFx;
        /** Last time its crown was put on (ms), for the sparks replay. */
        long crownAt;
        /** Buff Wand: lasting tint and aura, MobBattle_Fx_BuffLook_<buffColor>. */
        String lookFx;
    }

    private static final Map<Ref<EntityStore>, State> STATES = new ConcurrentHashMap<>();

    static {
        reset();
    }

    private Buffs() {
    }

    static void reset() {
        for (Source s : Source.values()) {
            EnumMap<Stat, Integer> m = new EnumMap<>(Stat.class);
            for (Stat st : Stat.values()) m.put(st, 0);
            PERCENT.put(s, m);
        }
        // The Buff Wand does something out of the box: per click.
        PERCENT.get(Source.WAND).putAll(Map.of(Stat.SIZE, 10, Stat.HEALTH, 20, Stat.DAMAGE, 10, Stat.DEFENSE, 5));
        streakMax = 5;
        wandMax = 5;
        sparksEvery = 3;
        for (Source s : Source.values()) HEAL.put(s, false);
        crown = "Gold";
    }

    static int percent(Source s, Stat st) {
        return PERCENT.get(s).get(st);
    }

    static void setPercent(Source s, Stat st, int value) {
        PERCENT.get(s).put(st, Math.max(0, Math.min(st.max, value)));
    }

    static boolean heals(Source s) {
        return HEAL.get(s);
    }

    static void setHeal(Source s, boolean on) {
        HEAL.put(s, on);
    }

    static boolean enabled(Source s) {
        return PERCENT.get(s).values().stream().anyMatch(v -> v > 0);
    }

    /** Gold, a palette name (canonical case) or off; null if not one. */
    static String parseCrown(String value) {
        if (value == null) return null;
        if ("off".equalsIgnoreCase(value)) return "off";
        if ("gold".equalsIgnoreCase(value)) return "Gold";
        for (String name : Teams.PALETTE.keySet()) if (name.equalsIgnoreCase(value)) return name;
        return null;
    }

    // ---- sources ----

    /** Puts (weight > 0) or takes (0) one source on a mob; its looks and stats follow on its world thread. */
    static void set(Ref<EntityStore> mob, Source source, int weight) {
        if (mob == null || !mob.isValid()) return;
        if (weight > 0 && !enabled(source) && !STATES.containsKey(mob)) {
            // e.g. buff lone heal on with no lone buff: the picked target is still healed.
            if (heals(source)) run(mob, store -> fill(mob, store));
            return;
        }
        run(mob, store -> {
            State s = STATES.get(mob);
            if (weight <= 0) {
                if (s == null || s.weight.remove(source) == null) return;
            } else {
                if (!enabled(source)) {
                    if (heals(source)) fill(mob, store);
                    return;
                }
                // Queued just before its battle ended (e.g. the last kill): a battle-only buff would then never go.
                if (source.battleOnly() && BattleManager.battleOf(mob) == null) return;
                // Same weight (a streak already at maxKills): nothing to redo, no new model sent.
                if (s != null && Integer.valueOf(weight).equals(s.weight.get(source))) return;
                if (s == null) STATES.put(mob, s = new State());
                s.weight.put(source, weight);
            }
            apply(mob, s, heals(source) && weight > 0, store);
            // The gold swirl (buffColor) when a Buff Wand click or a duel win buffs it; a moment after apply, since
            // the size change rebuilds the mob on the client and would drop a swirl put on now.
            if (weight > 0 && (source == Source.WAND || source == Source.CHAMPION)) {
                WandFx.swirlLater(mob, FxSettings.buffColor);
                // The Heal Wand's sound, played here (an effect's sound doesn't replay on a quick second click).
                WandFx.burst(null, "SFX_Deployable_Totem_Heal_Spawn", ChaosWand.positionOf(mob, store), store);
            }
        });
    }

    /** Health to its max. World thread. */
    private static void fill(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        EntityStatMap stats = mob.isValid() ? accessor.getComponent(mob, EntityStatMap.getComponentType()) : null;
        if (stats != null) stats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
    }

    /** Buff Wand left click: one more stack (up to wandMax). Returns the stacks it will have, 0 if the wand is off. */
    static int addWandStack(Ref<EntityStore> mob) {
        if (mob == null || !mob.isValid() || !enabled(Source.WAND)) return 0;
        int next = Math.min(wandMax, wandStacks(mob) + 1);
        set(mob, Source.WAND, next);
        return next;
    }

    /** Stacks of the Buff Wand on a mob (0 = none). */
    static int wandStacks(Ref<EntityStore> mob) {
        State s = mob != null ? STATES.get(mob) : null;
        Integer w = s != null ? s.weight.get(Source.WAND) : null;
        return w != null ? w : 0;
    }

    /** A player died (a respawned player is the same entity): every buff goes, as a mob's goes with its entity. */
    static void onPlayerDeath(Ref<EntityStore> player) {
        if (player == null || !STATES.containsKey(player)) return;
        for (Source s : Source.values()) set(player, s, 0);
    }

    /** End of a battle: the lone and streak buffs of its mobs go; champions stay. */
    static void endBattle(Ref<EntityStore> mob) {
        State s = mob != null ? STATES.get(mob) : null;
        if (s == null || !s.weight.containsKey(Source.LONE) && !s.weight.containsKey(Source.STREAK)) return;
        set(mob, Source.LONE, 0);
        set(mob, Source.STREAK, 0);
    }

    /** Cleanse Wand (on its world thread, right now): every buff goes, model and health back. */
    static void clearNow(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        State s = STATES.get(mob);
        if (s == null) {
            // Not tracked (e.g. buffed before a server restart: the modifier is saved with the mob, our state isn't):
            // its extra max health still goes. The bigger model can't be told apart from its own after a restart.
            EntityStatMap stats = mob.isValid() ? accessor.getComponent(mob, EntityStatMap.getComponentType()) : null;
            if (stats != null) {
                stats.removeModifier(DefaultEntityStatTypes.getHealth(), MODIFIER_KEY);
                stats.removeModifier(DefaultEntityStatTypes.getHealth(), OLD_MODIFIER_KEY);
            }
            // A size buff left on it goes too (saved with it; otherwise only the builder tools scale entities).
            EntityScaleComponent scale = mob.isValid() ? accessor.getComponent(mob, EntityScaleComponent.getComponentType()) : null;
            if (scale != null && scale.getScale() != 1f) scale.setScale(1f);
            return;
        }
        s.weight.clear();
        apply(mob, s, false, accessor);
    }

    /** /mobbattle resetAll: every buff of every mob goes. */
    static int clearEverything() {
        int n = 0;
        for (Ref<EntityStore> mob : new java.util.ArrayList<>(STATES.keySet())) {
            State s = STATES.get(mob);
            if (s == null || !mob.isValid()) continue;
            n++;
            run(mob, store -> {
                s.weight.clear();
                apply(mob, s, false, store);
            });
        }
        return n;
    }

    // ---- effect ----

    /** Total % of a stat on a mob: every source's % times its weight, capped at the stat's max. */
    private static int total(State s, Stat st) {
        return total(s, st, true);
    }

    /** Same; battleSources false = only the Buff Wand's (a hit between mobs that aren't battle enemies). */
    private static int total(State s, Stat st, boolean battleSources) {
        int sum = 0;
        for (Map.Entry<Source, Integer> e : s.weight.entrySet()) {
            if (battleSources || e.getKey() == Source.WAND) sum += percent(e.getKey(), st) * e.getValue();
        }
        return Math.min(st.max, sum);
    }

    /** Total % of a stat on a mob right now (0 = unbuffed), for DuelCard. Any thread. */
    static int totalOf(Ref<EntityStore> mob, Stat st) {
        State s = STATES.get(mob);
        return s == null ? 0 : total(s, st);
    }

    /** Brings the mob's model, health modifier and crown in line with its sources. World thread. */
    private static void apply(Ref<EntityStore> mob, State s, boolean fillHealth, ComponentAccessor<EntityStore> accessor) {
        if (!mob.isValid()) {
            STATES.remove(mob);
            return;
        }
        // Size: the entity scale (mobs and players), from the one it had before the first buff.
        int size = total(s, Stat.SIZE);
        EntityScaleComponent scale = accessor.getComponent(mob, EntityScaleComponent.getComponentType());
        if (size > 0) {
            // Always 1, not the current scale: a size buff saved with the mob before a restart/reload is still on it
            // (our state isn't saved), and taking that scale as the base made it grow again on every new buff
            // and need two Cleanse clicks. Same assumption as clearNow (only the builder tools scale entities).
            if (s.baseScale == null) s.baseScale = 1f;
            float want = s.baseScale * (1 + size / 100f);
            if (scale == null) accessor.putComponent(mob, EntityScaleComponent.getComponentType(), new EntityScaleComponent(want));
            else if (scale.getScale() != want) scale.setScale(want);
        } else if (s.baseScale != null) {
            if (scale != null) scale.setScale(s.baseScale);
            s.baseScale = null;
        }
        // Health.
        int health = total(s, Stat.HEALTH);
        EntityStatMap stats = accessor.getComponent(mob, EntityStatMap.getComponentType());
        if (stats != null) {
            int index = DefaultEntityStatTypes.getHealth();
            if (health > 0) {
                stats.putModifier(index, MODIFIER_KEY, new StaticModifier(Modifier.ModifierTarget.MAX,
                        StaticModifier.CalculationType.MULTIPLICATIVE, 1 + health / 100f));
                if (fillHealth) stats.maximizeStatValue(index);
                s.health = true;
            } else if (s.health) {
                stats.removeModifier(index, MODIFIER_KEY);
                s.health = false;
            }
        }
        // Crown on streak, champion and wand-buffed mobs (the lone target has its own, /mobbattle crown).
        boolean crowned = !"off".equals(crown) && (s.weight.containsKey(Source.STREAK) || s.weight.containsKey(Source.CHAMPION)
                || s.weight.containsKey(Source.WAND));
        String want = crowned ? "CrownBuff_" + crown : null;
        if (s.crownFx != null && !s.crownFx.equals(want)) {
            WandFx.removeFx(mob, s.crownFx, accessor);
            s.crownFx = null;
        }
        if (want != null && !want.equals(s.crownFx)) {
            WandFx.addFx(mob, want, accessor);
            s.crownFx = want;
            s.crownAt = System.currentTimeMillis();
        }
        // Wand, streak and champion buffs: their tint and aura stay while the buff lasts (scripts/gen-bufflook.py).
        boolean looks = s.weight.containsKey(Source.STREAK) || s.weight.containsKey(Source.CHAMPION)
                || s.weight.containsKey(Source.WAND);
        String look = looks ? "BuffLook_" + FxSettings.buffColor : null;
        if (s.lookFx != null && !s.lookFx.equals(look)) {
            WandFx.removeFx(mob, s.lookFx, accessor);
            s.lookFx = null;
        }
        if (look != null && !look.equals(s.lookFx)) {
            WandFx.addFx(mob, look, accessor);
            s.lookFx = look;
        }
        if (s.weight.isEmpty()) STATES.remove(mob);
    }

    /** "size +20%, health +40%" for that many Buff Wand stacks (each stat capped at its max). */
    static String describeWand(int stacks) {
        StringBuilder b = new StringBuilder();
        for (Stat st : Stat.values()) {
            int v = Math.min(st.max, percent(Source.WAND, st) * stacks);
            if (v <= 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(st.key).append(" +").append(v).append('%');
        }
        return b.toString();
    }

    static boolean any() {
        return !STATES.isEmpty();
    }

    /**
     * A hit: more from a buffed attacker, less to a buffed victim. enemies = the two are battle enemies (every
     * source counts); otherwise only the Buff Wand's.
     */
    static float scale(Ref<EntityStore> attacker, Ref<EntityStore> victim, float amount, boolean enemies) {
        if (STATES.isEmpty()) return amount;
        State a = attacker != null ? STATES.get(attacker) : null;
        if (a != null) amount *= 1 + total(a, Stat.DAMAGE, enemies) / 100f;
        State v = STATES.get(victim);
        if (v != null) amount *= 1 - total(v, Stat.DEFENSE, enemies) / 100f;
        return amount;
    }

    /** BattleManager.maintain: drops dead or unloaded mobs. */
    static void prune() {
        STATES.keySet().removeIf(r -> !r.isValid());
        if (sparksEvery <= 0) return;
        long now = System.currentTimeMillis();
        STATES.forEach((mob, s) -> {
            // Replays the lasting Buff Wand look (tint + aura), not the crown: the crown stays as it was put on.
            String fx = s.lookFx;
            if (fx == null || now - s.crownAt < sparksEvery * 1000L) return;
            s.crownAt = now;
            // Removed and put on again: a running effect only refreshes its time, without replaying particles.
            run(mob, store -> {
                if (!mob.isValid() || !fx.equals(s.lookFx)) return;
                WandFx.removeFx(mob, fx, store);
                WandFx.addFx(mob, fx, store);
            });
        });
    }

    private static void run(Ref<EntityStore> mob, java.util.function.Consumer<Store<EntityStore>> task) {
        Store<EntityStore> store = mob.getStore();
        if (store.isShutdown()) return;
        store.getExternalData().getWorld().execute(() -> task.accept(store));
    }

    // ---- settings (fx.properties via FxSettings) ----

    static void load(Properties p) {
        reset();
        for (Source s : Source.values()) {
            for (Stat st : Stat.values()) {
                String raw = p.getProperty("buff." + s.key + "." + st.key);
                // targetBuff (before buff lone) was saved as buff.<stat>.
                if (raw == null && s == Source.LONE) raw = p.getProperty("buff." + st.key);
                try {
                    if (raw != null) setPercent(s, st, Integer.parseInt(raw.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        try {
            streakMax = Math.max(1, Math.min(MAX_STREAK, Integer.parseInt(p.getProperty("buff.streak.maxkills", "5").trim())));
        } catch (NumberFormatException ignored) {
        }
        try {
            wandMax = Math.max(1, Math.min(MAX_WAND, Integer.parseInt(p.getProperty("buff.wand.maxstacks", "5").trim())));
        } catch (NumberFormatException ignored) {
        }
        // buff heal (one switch for every source, before buff <who> heal) loads into every source.
        String oldHeal = p.getProperty("buff.heal");
        for (Source s : Source.values()) {
            HEAL.put(s, Boolean.parseBoolean(p.getProperty("buff." + s.key + ".heal", oldHeal != null ? oldHeal : "false").trim()));
        }
        try {
            sparksEvery = Math.max(0, Math.min(MAX_SPARKS_EVERY, Integer.parseInt(p.getProperty("buff.sparks", "3").trim())));
        } catch (NumberFormatException ignored) {
        }
        String c = parseCrown(p.getProperty("buff.crown"));
        if (c != null) crown = c;
    }

    static void save(Properties p) {
        for (Source s : Source.values()) {
            for (Stat st : Stat.values()) p.setProperty("buff." + s.key + "." + st.key, String.valueOf(percent(s, st)));
        }
        p.setProperty("buff.streak.maxkills", String.valueOf(streakMax));
        p.setProperty("buff.wand.maxstacks", String.valueOf(wandMax));
        p.setProperty("buff.sparks", String.valueOf(sparksEvery));
        for (Source s : Source.values()) p.setProperty("buff." + s.key + ".heal", String.valueOf(heals(s)));
        p.setProperty("buff.crown", crown);
    }

    /** "lone size 10% health 50% | streak off | champion off | maxKills 5 | crown gold". */
    static String describe() {
        StringBuilder b = new StringBuilder();
        for (Source s : Source.values()) {
            if (b.length() > 0) b.append(" | ");
            b.append(s.key);
            if (!enabled(s)) {
                b.append(" off");
                if (heals(s)) b.append(" heal on");
                continue;
            }
            if (heals(s)) b.append(" heal on");
            for (Stat st : Stat.values()) {
                int v = percent(s, st);
                if (v > 0) b.append(' ').append(st.key).append(' ').append(v).append('%');
            }
            if (s == Source.STREAK) b.append(" per kill");
            if (s == Source.WAND) b.append(" per click");
        }
        return b.append(" | maxKills ").append(streakMax).append(" | wand maxStacks ").append(wandMax)
                .append(" | sparks ").append(sparksEvery > 0 ? sparksEvery + " s" : "off").append(" | crown ").append(crown.toLowerCase(Locale.ROOT)).toString();
    }
}
