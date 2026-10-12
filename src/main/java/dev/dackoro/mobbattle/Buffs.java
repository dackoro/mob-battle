package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
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
    /**
     * Crown on streak, champion and wand-buffed mobs: Gold, a palette color, or off (default: the crown belongs to
     * the everyone-vs-one target, user 2026-10-10).
     */
    static String crown = "off";
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
        /**
         * Wand, streak and champion buffs: the shimmer in buffColor while the buff lasts, BuffShimmer_&lt;Color&gt;[_S200]
         * (veins, eye flames, runes; guarded by LookKeeper), or only its runes and eyes, BuffRunes_&lt;Color&gt;, on a mob with a
         * look of its own (a jade summon keeps its jade). scripts/gen-looks.py.
         */
        String lookFx;
        /** lookFx is the shimmer waiting for the wand's short effects to end (addLookLater), not on the mob yet. */
        volatile boolean lookPending;
    }

    private static final Map<Ref<EntityStore>, State> STATES = new ConcurrentHashMap<>();
    /** Lone targets buffed during the countdown, before their battle exists (pruned with STATES in prune). */
    private static final java.util.Set<Ref<EntityStore>> PRE_LONE = ConcurrentHashMap.newKeySet();

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
        for (Source s : Source.values()) HEAL.put(s, false);
        crown = "off";
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

    /**
     * Everyone vs one: the lone buff from the start of the countdown, so the target is already big and tough when
     * the mobs around it (maybe right next to it) start hitting. Taken again with {@link #cancelPreLone}.
     */
    static void preLone(Ref<EntityStore> mob) {
        if (mob == null || !mob.isValid()) return;
        PRE_LONE.add(mob);
        set(mob, Source.LONE, 1);
    }

    /** The countdown was cancelled (or its battle never started): the early lone buff goes. */
    static void cancelPreLone(Ref<EntityStore> mob) {
        if (mob == null || !PRE_LONE.remove(mob)) return;
        set(mob, Source.LONE, 0);
    }

    /** Puts (weight > 0) or takes (0) one source on a mob; its looks and stats follow on its world thread. */
    static void set(Ref<EntityStore> mob, Source source, int weight) {
        if (mob == null || !mob.isValid()) return;
        if (source == Source.LONE && weight <= 0) PRE_LONE.remove(mob);
        if (weight > 0 && !enabled(source) && !STATES.containsKey(mob)) {
            // e.g. buff lone heal on with no lone buff: the picked target is still healed.
            if (heals(source)) run(mob, store -> fill(mob, store));
            return;
        }
        run(mob, store -> setHere(mob, source, weight, true, store));
    }

    /**
     * {@link #set} on the mob's world thread, right now. {@code swirl} false: the caller already showed the swirl
     * (Buff Wand light: on the click).
     */
    private static void setHere(Ref<EntityStore> mob, Source source, int weight, boolean swirl, Store<EntityStore> store) {
        State s = STATES.get(mob);
        if (weight <= 0) {
            if (s == null || s.weight.remove(source) == null) return;
        } else {
            if (!enabled(source)) {
                if (heals(source)) fill(mob, store);
                return;
            }
            // Queued just before its battle ended (e.g. the last kill): a battle-only buff would then never go.
            if (source.battleOnly() && BattleManager.battleOf(mob) == null
                    && !(source == Source.LONE && PRE_LONE.contains(mob))) return;
            // Same weight (a streak already at maxKills): nothing to redo, no new model sent.
            if (s != null && Integer.valueOf(weight).equals(s.weight.get(source))) return;
            if (s == null) STATES.put(mob, s = new State());
            s.weight.put(source, weight);
        }
        apply(mob, s, heals(source) && weight > 0, store);
        // The mob can die in the same tick: its position can't be read any more.
        if (!mob.isValid()) return;
        // The gold swirl (buffColor) when a Buff Wand click or a duel win buffs it; a moment after apply, since
        // the size change rebuilds the mob on the client and would drop a swirl put on now.
        if (weight > 0 && (source == Source.WAND || source == Source.CHAMPION)) {
            if (swirl) WandFx.swirlLater(mob, FxSettings.buffColor);
            // The Heal Wand's sound, played here (an effect's sound doesn't replay on a quick second click).
            WandFx.burst(null, "SFX_Deployable_Totem_Heal_Spawn", ChaosWand.positionOf(mob, store), store);
        }
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

    /**
     * Buff Wand clicks whose light is still coming down (mob -> how many): they count for maxStacks at once and land
     * later (addWandStackLater). Pruned in prune.
     */
    private static final Map<Ref<EntityStore>, Falling> FALLING = new ConcurrentHashMap<>();

    /** One mob's stacks still coming down. A new holder after cancelFalling, so the old lights land on nothing. */
    private static final class Falling {
        volatile int count;
    }

    /** Stacks the mob has plus the ones still coming down with the light. */
    static int wandStacksSoon(Ref<EntityStore> mob) {
        Falling f = mob != null ? FALLING.get(mob) : null;
        return wandStacks(mob) + (f != null ? f.count : 0);
    }

    /** Buff Wand right click / resetAll: stacks still coming down don't land (the buff would come back). */
    static void cancelFalling(Ref<EntityStore> mob) {
        if (mob != null) FALLING.remove(mob);
    }

    /**
     * Buff Wand left click with buffLight: one more stack once the light lands, {@code delayMs} from now; {@code onLand}
     * runs then too (the mark). The swirl is the caller's, on the click (feedback that the click landed). Returns the stacks it will have, 0 if the wand is off. The stack is counted when it
     * lands (on the world thread, not queued again), so quick clicks each add one.
     */
    static int addWandStackLater(Ref<EntityStore> mob, long delayMs, java.util.function.Consumer<Store<EntityStore>> onLand) {
        if (mob == null || !mob.isValid() || !enabled(Source.WAND)) return 0;
        int next = Math.min(wandMax, wandStacksSoon(mob) + 1);
        // Clicks and landings both run on the mob's world thread.
        Falling falling = FALLING.computeIfAbsent(mob, k -> new Falling());
        falling.count++;
        Store<EntityStore> store = mob.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            // Taken off (cancelFalling) while it came down: it doesn't land.
            if (FALLING.get(mob) != falling) return;
            if (--falling.count <= 0) FALLING.remove(mob, falling);
            if (store.isShutdown() || !mob.isValid() || !BattleManager.isAlive(mob, store)) return;
            onLand.accept(store);
            float before = scaleOf(mob, store);
            setHere(mob, Source.WAND, Math.min(wandMax, wandStacks(mob) + 1), false, store);
            // The swirl went on with the click; a size change now rebuilds the mob on the client and cuts it, so it
            // goes on again once the new size is there.
            if (mob.isValid() && Math.abs(scaleOf(mob, store) - before) > 0.001f) WandFx.swirlLater(mob, FxSettings.buffColor);
        }), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        return next;
    }

    private static float scaleOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        EntityScaleComponent scale = accessor.getComponent(mob, EntityScaleComponent.getComponentType());
        return scale != null ? scale.getScale() : 1f;
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
                int index = DefaultEntityStatTypes.getHealth();
                EntityStatValue before = stats.get(index);
                float oldValue = before != null ? before.get() : 0;
                boolean had = stats.removeModifier(index, MODIFIER_KEY) != null;
                had |= stats.removeModifier(index, OLD_MODIFIER_KEY) != null;
                // As apply: the health it had, cut to the smaller max.
                EntityStatValue after = stats.get(index);
                if (had && oldValue > 0 && after != null && after.getMax() > 0) stats.setStatValue(index, Math.min(oldValue, after.getMax()));
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
        FALLING.clear();
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
            STATES.remove(mob, s);
            return;
        }
        // Size: the entity scale (mobs and players), from the one it had before the first buff.
        int size = total(s, Stat.SIZE);
        EntityScaleComponent scale = accessor.getComponent(mob, EntityScaleComponent.getComponentType());
        float scaleBefore = scale != null ? scale.getScale() : 1f;
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
        EntityScaleComponent scaleNow = accessor.getComponent(mob, EntityScaleComponent.getComponentType());
        float scaled = scaleNow != null ? scaleNow.getScale() : 1f;
        // A new scale rebuilds the mob on the client and drops the particles on it (WandFx.MODEL_SETTLE_MS).
        boolean resized = Math.abs(scaled - scaleBefore) > 0.001f;
        // Health.
        int health = total(s, Stat.HEALTH);
        EntityStatMap stats = accessor.getComponent(mob, EntityStatMap.getComponentType());
        if (stats != null) {
            int index = DefaultEntityStatTypes.getHealth();
            // A bigger max keeps health's share of it: a full mob stays full at once instead of regenerating the
            // extra slowly (heal on fills it). A smaller max (buff lowered or gone) keeps the health it had, cut to
            // the new max: hits taken while buffed came out of the extra, so 500/1000 becomes 100/100, not 50/100.
            EntityStatValue before = stats.get(index);
            float share = before != null && before.getMax() > 0 ? before.get() / before.getMax() : -1;
            float oldMax = before != null ? before.getMax() : 0, oldValue = before != null ? before.get() : 0;
            boolean changed = false;
            if (health > 0) {
                stats.putModifier(index, MODIFIER_KEY, new StaticModifier(Modifier.ModifierTarget.MAX,
                        StaticModifier.CalculationType.MULTIPLICATIVE, 1 + health / 100f));
                s.health = true;
                changed = true;
            } else if (s.health) {
                stats.removeModifier(index, MODIFIER_KEY);
                s.health = false;
                changed = true;
            }
            if (changed) {
                EntityStatValue after = stats.get(index);
                float target = -1;
                if (fillHealth && health > 0 && after != null) target = after.getMax();
                else if (share > 0 && after != null && after.getMax() > 0) {
                    target = after.getMax() >= oldMax ? share * after.getMax() : Math.min(oldValue, after.getMax());
                }
                // A bigger value goes in a moment later: sent in the same tick as the new max, the client applied it
                // first and cut it to the old max, so it thought the mob had less health than it has. Its predicted
                // hits then "killed" it (death animation, no more hits sent) while the server still had health left
                // (user, 2026-10-12: a Buff Wand skeleton froze dying at 8/84 and came back). A smaller one can't
                // be cut, so it goes now.
                if (target >= 0 && after != null && target > after.get() + 0.001f) {
                    setHealthLater(mob, after.get(), target);
                } else if (target >= 0) {
                    stats.setStatValue(index, target);
                }
            }
        }
        // Crown on streak, champion and wand-buffed mobs (the lone target has its own, /mobbattle crown).
        boolean crowned = !"off".equals(crown) && (s.weight.containsKey(Source.STREAK) || s.weight.containsKey(Source.CHAMPION)
                || s.weight.containsKey(Source.WAND));
        // Sized to the mob (attached particles don't grow with the entity scale): CrownBuff_<color>_S200 at x2.
        String want = crowned ? "CrownBuff_" + crown + WandFx.crownSize(scaled) : null;
        if (s.crownFx != null && !s.crownFx.equals(want)) {
            WandFx.removeFx(mob, s.crownFx, accessor);
            s.crownFx = null;
        }
        if (want != null && (!want.equals(s.crownFx) || resized)) {
            s.crownFx = want;
            if (resized) putAgainLater(mob, want, () -> s.crownFx);
            else WandFx.addFx(mob, want, accessor);
        }
        applyLook(mob, s, resized, scaled, accessor);
        // A Jade/Shimmer look of its own (Effect Wand) also loses its ModelVFX in the rebuild: LookKeeper re-sends it.
        if (resized) LookKeeper.resized(mob);
        if (s.weight.isEmpty()) STATES.remove(mob, s);
    }

    private static final String BUFF_SHIMMER = "BuffShimmer_";
    private static final String BUFF_RUNES = "BuffRunes_";

    /**
     * Wand, streak and champion buffs: the shimmer in buffColor while the buff lasts, or only its runes and eyes on a
     * mob with a look of its own (one ModelVFX per mob: a jade summon keeps its jade). Put on again after a resize,
     * since the rebuilt model drops particles put on now. Sized to the mob ({@link WandFx#lookSize}): attached particles
     * don't grow with the entity scale, so a grown mob's runes hid inside its head (_S200 = x2).
     */
    private static void applyLook(Ref<EntityStore> mob, State s, boolean resized, float scaled,
                                  ComponentAccessor<EntityStore> accessor) {
        boolean looks = s.weight.containsKey(Source.STREAK) || s.weight.containsKey(Source.CHAMPION)
                || s.weight.containsKey(Source.WAND);
        String look = looks ? (LookKeeper.hasOwnLook(mob) ? BUFF_RUNES : BUFF_SHIMMER) + FxSettings.buffColor
                + WandFx.lookSize(scaled) : null;
        if (s.lookFx != null && !s.lookFx.equals(look)) {
            LookKeeper.untrackBuff(mob);
            if (!s.lookPending) WandFx.removeFx(mob, s.lookFx, accessor);
            s.lookFx = null;
            s.lookPending = false;
        }
        if (look == null) return;
        if (!look.equals(s.lookFx)) {
            s.lookFx = look;
            if (look.startsWith(BUFF_SHIMMER)) {
                // Each of the wand's short effects ending (light, swirl, mark) makes LookKeeper re-send the shimmer,
                // which restarts its veins: it went on three times after a click (user, 2026-10-12). It goes on
                // once they are over instead.
                s.lookPending = true;
                addLookLater(mob, s, look, resized ? WandFx.MODEL_SETTLE_MS : LOOK_CHECK_MS, 0);
                return;
            }
            WandFx.addFx(mob, look, accessor);
        }
        if (resized && !s.lookPending) putAgainLater(mob, look, () -> s.lookPending ? null : s.lookFx);
    }

    /** First check for the wand's short effects (they go on in the same tick as the stack). */
    private static final long LOOK_CHECK_MS = 50;
    /** The shimmer never waits longer than this for them (quick clicks keep adding new ones). */
    private static final long LOOK_WAIT_CAP_MS = 3000;

    /**
     * Puts the buff's shimmer on once no short MobBattle_Fx_ effect (Buff Wand light, swirl, mark) is left on the mob,
     * and only then lets LookKeeper guard it. Gives up when the buff look changed meanwhile.
     */
    private static void addLookLater(Ref<EntityStore> mob, State s, String look, long delayMs, long waited) {
        Store<EntityStore> store = mob.getStore();
        if (store.isShutdown()) return;
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> run(mob, st -> {
            if (!mob.isValid() || STATES.get(mob) != s || !s.lookPending || !look.equals(s.lookFx)) return;
            long total = waited + delayMs;
            long left = total < LOOK_WAIT_CAP_MS ? shortFxLeftMs(mob, st) : 0;
            if (left > 0) {
                addLookLater(mob, s, look, Math.min(left + LOOK_CHECK_MS, LOOK_WAIT_CAP_MS - total + LOOK_CHECK_MS), total);
                return;
            }
            s.lookPending = false;
            WandFx.addFx(mob, look, st);
            LookKeeper.trackBuff(mob, EntityEffect.getAssetMap().getAsset("MobBattle_Fx_" + look));
        }), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Milliseconds until the last short (not infinite) MobBattle_Fx_ effect on the mob ends; 0 when none. */
    private static long shortFxLeftMs(Ref<EntityStore> mob, Store<EntityStore> store) {
        var effects = store.getComponent(mob, com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent.getComponentType());
        if (effects == null) return 0;
        var active = effects.getActiveEffects();
        float left = 0;
        for (int index : effects.getActiveEffectIndexes()) {
            EntityEffect e = EntityEffect.getAssetMap().getAsset(index);
            var a = active.get(index);
            if (e == null || a == null || a.isInfinite() || !e.getId().startsWith("MobBattle_Fx_")) continue;
            left = Math.max(left, a.getRemainingDuration());
        }
        return (long) (left * 1000);
    }

    /**
     * The mob's model was swapped (Morph Wand, Cleanse): the client rebuilt it and dropped the crown and the buff look
     * put on the old one, so both go on again once the new model has arrived.
     */
    static void modelChanged(Ref<EntityStore> mob) {
        State s = mob == null ? null : STATES.get(mob);
        if (s == null || !mob.isValid()) return;
        if (s.crownFx != null) putAgainLater(mob, s.crownFx, () -> s.crownFx);
        if (s.lookFx != null && !s.lookPending) putAgainLater(mob, s.lookFx, () -> s.lookPending ? null : s.lookFx);
    }

    /** The mob got a look of its own (Effect Wand): its buff look turns from the shimmer into runes and eyes. */
    static void refreshLook(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        State s = mob == null ? null : STATES.get(mob);
        if (s == null || !mob.isValid()) return;
        EntityScaleComponent scale = accessor.getComponent(mob, EntityScaleComponent.getComponentType());
        applyLook(mob, s, false, scale != null ? scale.getScale() : 1f, accessor);
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

    /** True if the mob (or player) has any buff state. False for null. */
    static boolean has(Ref<EntityStore> ref) {
        return ref != null && STATES.containsKey(ref);
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

    /**
     * The crown or the buff look, put on again once a size change has reached the client (it would drop one put on
     * now), if it is still the current one.
     */
    private static void putAgainLater(Ref<EntityStore> mob, String fx, java.util.function.Supplier<String> current) {
        Store<EntityStore> store = mob.getStore();
        if (store.isShutdown()) return;
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> run(mob, st -> {
            if (!mob.isValid() || !fx.equals(current.get())) return;
            WandFx.removeFx(mob, fx, st);
            WandFx.addFx(mob, fx, st);
        }), WandFx.MODEL_SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Raises health to {@code target} once the new max reached the client (Buffs.apply). Hits taken meanwhile still
     * count: the value moves by what it changed since {@code valueNow}.
     */
    private static void setHealthLater(Ref<EntityStore> mob, float valueNow, float target) {
        Store<EntityStore> store = mob.getStore();
        if (store.isShutdown()) return;
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> run(mob, st -> {
            if (!mob.isValid() || st.getComponent(mob, com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent.getComponentType()) != null) return;
            EntityStatMap stats = st.getComponent(mob, EntityStatMap.getComponentType());
            int index = DefaultEntityStatTypes.getHealth();
            EntityStatValue v = stats != null ? stats.get(index) : null;
            if (v == null || v.get() <= 0) return;
            stats.setStatValue(index, Math.max(1f, Math.min(v.getMax(), target + v.get() - valueNow)));
        }), WandFx.MODEL_SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** RoleChange: the same mob under a new Ref keeps its buffs; its crown and look go on again on the new model. */
    static void carry(Ref<EntityStore> old, Ref<EntityStore> neu) {
        State s = STATES.remove(old);
        if (s != null) STATES.put(neu, s);
        if (PRE_LONE.remove(old)) PRE_LONE.add(neu);
        Falling f = FALLING.remove(old);
        if (f != null) FALLING.put(neu, f);
        if (s != null) modelChanged(neu);
    }

    /** BattleManager.maintain: drops dead or unloaded mobs. */
    static void prune() {
        STATES.keySet().removeIf(r -> !r.isValid());
        PRE_LONE.removeIf(r -> !r.isValid());
        FALLING.keySet().removeIf(r -> !r.isValid());
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
        String c = parseCrown(p.getProperty("buff.crown"));
        if (c != null) crown = c;
    }

    static void save(Properties p) {
        for (Source s : Source.values()) {
            for (Stat st : Stat.values()) p.setProperty("buff." + s.key + "." + st.key, String.valueOf(percent(s, st)));
        }
        p.setProperty("buff.streak.maxkills", String.valueOf(streakMax));
        p.setProperty("buff.wand.maxstacks", String.valueOf(wandMax));
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
                .append(" | crown ").append(crown.toLowerCase(Locale.ROOT)).toString();
    }
}
