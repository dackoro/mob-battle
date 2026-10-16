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
 * and particles on it are kept, and the hitbox follows); a player also gets their model's scale, since their camera
 * follows the model (MorphWand.setPlayerSize). Health is a StaticModifier (MAX, MULTIPLICATIVE) on max
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
        // 2026-10-13, user: higher caps (health/damage x100, defense 99% = takes 1/100; 100% would be unkillable)
        // and speed as its own stat, on top of the speed a size buff already gives.
        SIZE("size", MAX_SIZE), HEALTH("health", 99900), DAMAGE("damage", 99900), DEFENSE("defense", 99), SPEED("speed", 4900);

        final String key;
        /** Highest % for one source (defense: 100 % would make it unkillable). */
        final int max;

        Stat(String key, int max) {
            this.key = key;
            this.max = max;
        }

        /** Highest % on one mob, all sources together: size stops at sizeMax. */
        int totalMax() {
            return this == SIZE ? sizeMax : max;
        }

        static Stat of(String word) {
            for (Stat s : values()) if (s.key.equalsIgnoreCase(word)) return s;
            return null;
        }
    }

    /**
     * Most extra size on one mob, all sources together (/mobbattle buff sizeMax, key buff.size.max). Default 200 (x3);
     * above SIZE_SAFE the command and the panel warn (user, 2026-10-12): players don't fit under roofs and their camera
     * ends up in blocks, and the buff looks (which grow with the square root of the size, WandFx.grown) sink into big heads.
     */
    static int sizeMax = 200;

    /**
     * How much a size buff speeds up and raises the jump (2026-10-13, user: x4 at twice the speed looked slow, its
     * steps four times longer): off, natural (default: speed x sqrt(size), jump height x size; calibrated in game),
     * strong, full (speed x size: the steps match the ground, but very fast). Keys buff.size.speed / buff.size.jump.
     */
    static final java.util.List<String> SIZE_EFFECTS = java.util.List.of("off", "natural", "strong", "full");
    static String sizeSpeed = "natural";
    static String sizeJump = "natural";

    /** Walking speed = base x size^this. */
    static double speedExponent() {
        return switch (sizeSpeed) {
            case "off" -> 0;
            case "strong" -> 0.75;
            case "full" -> 1;
            default -> 0.5;
        };
    }

    /** A player's jump force = default x size^this (0.55: the jump height grows with the size, measured in game). */
    static double jumpExponent() {
        return switch (sizeJump) {
            case "off" -> 0;
            case "strong" -> 0.7;
            case "full" -> 0.85;
            default -> 0.55;
        };
    }

    /** A mob's jump height = base x size^this (the same steps as the players' force: height ~ force squared). */
    static double mobJumpExponent() {
        return switch (sizeJump) {
            case "off" -> 0;
            case "strong" -> 1.4;
            case "full" -> 1.7;
            default -> 1;
        };
    }

    /** off|natural|strong|full, any case; null if none. */
    static String parseSizeEffect(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return SIZE_EFFECTS.contains(s) ? s : null;
    }

    /** After a sizeSpeed/sizeJump change: every buffed mob and player moves by the new rule now. */
    static void refreshMovementAll() {
        // One task per world, not one per buffed mob (audit 2026-10-14: every panel save queued one per mob).
        forEachWorld(STATES.keySet(), (store, mobs) -> {
            for (Ref<EntityStore> mob : mobs) {
                if (!mob.isValid()) continue;
                // The buff's size factor, as apply() passes it (not the entity scale, which may be a saved one).
                BuffJump.npc(mob, 1 + totalOf(mob, Stat.SIZE) / 100f, 1 + totalOf(mob, Stat.SPEED) / 100f, store);
                BuffJump.player(mob, store);
            }
        });
    }

    /** Runs {@code task} once per world on its thread with that world's mobs of {@code mobs}. */
    private static void forEachWorld(java.util.Collection<Ref<EntityStore>> mobs,
                                     java.util.function.BiConsumer<Store<EntityStore>, java.util.List<Ref<EntityStore>>> task) {
        Map<Store<EntityStore>, java.util.List<Ref<EntityStore>>> byStore = new java.util.HashMap<>();
        for (Ref<EntityStore> mob : mobs) {
            if (mob.isValid()) byStore.computeIfAbsent(mob.getStore(), k -> new java.util.ArrayList<>()).add(mob);
        }
        byStore.forEach((store, list) -> {
            if (store.isShutdown()) return;
            store.getExternalData().getWorld().execute(() -> {
                if (!store.isShutdown()) task.accept(store, list);
            });
        });
    }
    static final int SIZE_SAFE = 200;
    static final int MIN_SIZE = 50;
    static final int MAX_SIZE = 9900;

    /** The warning for a size limit above SIZE_SAFE, else null. */
    static String sizeWarning(int percent) {
        if (percent <= SIZE_SAFE) return null;
        return "Size limit " + both(Stat.SIZE, percent) + " is above the recommended " + both(Stat.SIZE, SIZE_SAFE)
                + ": big players don't fit under roofs or in caves, their camera can end up inside blocks, and "
                + "big mobs' runes and crowns can end up inside their head (the buff looks grow less than the body).";
    }

    /** Same warning as {@link #sizeWarning} for the player's chat, translated (null when there is none). */
    static com.hypixel.hytale.server.core.Message sizeWarningMessage(int percent) {
        if (percent <= SIZE_SAFE) return null;
        return I18n.tr("chat.buffs.sizewarn").param("limit", both(Stat.SIZE, percent))
                .param("safe", both(Stat.SIZE, SIZE_SAFE));
    }

    /**
     * How buff values are shown and typed (/mobbattle buff unit, key buff.unit, 2026-10-13, user): percent (default,
     * +100 % = double) or multiplier (x2 = double, decimals allowed; defense x2 = half the damage taken). Stored as
     * percent either way, so switching never changes a buff. Sources and stacks still add up as percent: two x2
     * sources = +200 % = x3, five Buff Wand clicks of x1.1 = x1.5.
     */
    static boolean multiplier = false;
    static final String UNIT_PERCENT = "percent";
    static final String UNIT_MULTIPLIER = "multiplier";

    static String unit() {
        return multiplier ? UNIT_MULTIPLIER : UNIT_PERCENT;
    }

    /** "percent"/"%" or "multiplier"/"x" -> true for multiplier; null if neither. */
    static Boolean parseUnit(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals(UNIT_PERCENT) || s.equals("%")) return false;
        if (s.equals(UNIT_MULTIPLIER) || s.equals("x")) return true;
        return null;
    }

    /** The multiplier of a percent: 1 + p/100; defense 1/(1 - p/100) (x2 = takes half the damage). */
    static double toMultiplier(Stat st, int percent) {
        return st == Stat.DEFENSE ? 1 / (1 - Math.min(99, percent) / 100.0) : 1 + percent / 100.0;
    }

    /** The percent of a multiplier (x1 or less = 0). */
    static int fromMultiplier(Stat st, double m) {
        if (!(m > 1)) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(st == Stat.DEFENSE ? 100 * (1 - 1 / m) : (m - 1) * 100));
    }

    /** "x2", "x1.5", "x3.03". */
    static String multText(double m) {
        double r = Math.round(m * 100) / 100.0;
        return "x" + (r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r).replaceAll("0+$", ""));
    }

    /**
     * For chat: the value in the current unit, the other one in brackets: "x2 (+100%)" or "+100% (x2)" (user,
     * 2026-10-13: in multiplier mode the chat still spoke percent).
     */
    static String both(Stat st, int percent) {
        String pct = "+" + percent + "%", mult = multText(toMultiplier(st, percent));
        return multiplier ? mult + " (" + pct + ")" : pct + " (" + mult + ")";
    }

    /** Short, for a HUD: "+100%" or "x2" in the current unit. */
    static String brief(Stat st, int percent) {
        return multiplier ? multText(toMultiplier(st, percent)) : "+" + percent + "%";
    }

    /** A value in the current unit: "50%" or "x1.5". */
    static String show(Stat st, int percent) {
        return multiplier ? multText(toMultiplier(st, percent)) : percent + "%";
    }

    /**
     * A typed value as percent: "x2"/"2x" is always a multiplier, "50%" always percent, a plain number follows the
     * unit. -1 if not a number.
     */
    static int parseValue(Stat st, String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        boolean mult = multiplier;
        if (s.startsWith("x") || s.endsWith("x")) {
            mult = true;
            s = s.startsWith("x") ? s.substring(1) : s.substring(0, s.length() - 1);
        } else if (s.endsWith("%")) {
            mult = false;
            s = s.substring(0, s.length() - 1);
        }
        try {
            double d = Double.parseDouble(s.trim());
            if (!Double.isFinite(d) || d < 0) return -1;
            // Below x1 would shrink or weaken, which buffs don't do: refused instead of silently turned into "off"
            // (audit 2026-10-14). x0 and x1 still mean none.
            if (mult && d > 0 && d < 1) return -1;
            // Huge values ("x1e9") stay huge instead of wrapping around int: the callers' range checks reject them.
            return mult ? fromMultiplier(st, d) : (int) Math.min(Integer.MAX_VALUE, Math.round(d));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** % per source and stat (streak: per kill). 0 = none. */
    private static final Map<Source, EnumMap<Stat, Integer>> PERCENT = new EnumMap<>(Source.class);
    /** Streak: kills that count at most. */
    static int streakMax = 5;
    static final int MAX_STREAK = 1000;
    /**
     * Crown on streak, champion and wand-buffed mobs: Gold, a palette color, or off (default: the crown belongs to
     * the everyone-vs-one target, user 2026-10-10).
     */
    static String crown = "off";
    /** Buff Wand: clicks that stack at most. */
    static int wandMax = 5;
    static final int MAX_WAND = 1000;
    /** Per source: a new buff (lone: being picked) fills the health to its max (on) or leaves it (off, default). */
    private static final EnumMap<Source, Boolean> HEAL = new EnumMap<>(Source.class);

    private static final String MODIFIER_KEY = "MobBattle_Buff";
    /** The key of the old targetBuff (TargetBuff), still on mobs saved mid-battle with it. */
    private static final String OLD_MODIFIER_KEY = "MobBattle_TargetBuff";

    /** One buffed mob: its sources (weight = 1, or the kills of a streak) and what was changed, to undo it. */
    private static final class State {
        final EnumMap<Source, Integer> weight = new EnumMap<>(Source.class);
        /**
         * Direct amounts set by /mobbattle apply (size/health/damage/defense <value>), on top of the sources, against
         * anyone (as the Buff Wand's). No shimmer or crown: an admin's resize of a player shouldn't look like a buff.
         */
        final EnumMap<Stat, Integer> direct = new EnumMap<>(Stat.class);
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
        /** Speed total last applied to its movement (BuffJump), so a change is pushed once. */
        int speed;
        /**
         * Size factor last applied to its movement (BuffJump). Compared instead of the entity scale (audit 2026-10-14: a
         * size saved with the mob across a restart is already on it, so a new buff of that size never reached BuffJump).
         */
        float moveSize = 1f;
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
        sizeMax = SIZE_SAFE;
        multiplier = false;
        sizeSpeed = "natural";
        sizeJump = "natural";
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
        return sizeScale(mob, accessor);
    }

    /**
     * How much bigger it is drawn than its own size: a player's entity scale (their model carries the same factor,
     * MorphWand.setPlayerSize); a mob's entity scale over its model's scale, since vanilla draws an entity scale INSTEAD
     * of the model's (EntityScaleBoundingBoxSystem.resolveBoxScale = entity / model scale, javap 0.7.2).
     */
    static float sizeScale(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        EntityScaleComponent scale = accessor.getComponent(mob, EntityScaleComponent.getComponentType());
        if (scale == null) return 1f;
        return isPlayer(mob, accessor) ? scale.getScale() : scale.getScale() / naturalScale(mob, accessor);
    }

    /**
     * The entity scale a mob has without any size buff: its model's scale (2026-10-16, user: Souls-like Bosses'
     * Nightlord, model MinScale/MaxScale 2.5, shrank to x1.1 with a +10% buff and to x1 with Cleanse, because the
     * base was a fixed 1). Players: 1 (their buff rebuilds the model at the factor). A builder-tools scale is not
     * kept, as before: after a restart it can't be told apart from a size buff saved with the mob.
     */
    static float naturalScale(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        if (isPlayer(mob, accessor)) return 1f;
        com.hypixel.hytale.server.core.modules.entity.component.ModelComponent mc = accessor.getComponent(mob,
                com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getComponentType());
        float s = mc != null && mc.getModel() != null ? mc.getModel().getScale() : 1f;
        return Float.isFinite(s) && s > 0 ? s : 1f;
    }

    private static boolean isPlayer(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        return accessor.getComponent(mob, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()) != null;
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
        for (Stat st : Stat.values()) setDirect(player, st, 0);
    }

    /**
     * PlayerReadyEvent: a player who left while buffed comes back as a new entity with no buff state, but the game
     * saved their entity scale and health modifier, while vanilla rebuilds their model at 1 (so body, camera and
     * jump disagreed; user, 2026-10-13). Their leftovers go, as on death. ❓ Untested in game.
     */
    static void onPlayerReady(Ref<EntityStore> player) {
        if (player == null || !player.isValid() || STATES.containsKey(player)) return;
        Store<EntityStore> store = player.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!player.isValid() || STATES.containsKey(player)) return;
            EntityScaleComponent scale = store.getComponent(player, EntityScaleComponent.getComponentType());
            EntityStatMap stats = store.getComponent(player, EntityStatMap.getComponentType());
            EntityStatValue health = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
            boolean modifier = health != null && health.getModifiers() != null && health.getModifiers().containsKey(MODIFIER_KEY);
            if (modifier || scale != null && scale.getScale() != 1f) clearNow(player, store);
        });
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
            // A size buff left on it goes too (saved with it; otherwise only the builder tools scale entities): back to
            // the model's own scale, which an entity scale replaces.
            EntityScaleComponent scale = mob.isValid() ? accessor.getComponent(mob, EntityScaleComponent.getComponentType()) : null;
            if (scale != null) {
                float natural = naturalScale(mob, accessor);
                if (scale.getScale() != natural) scale.setScale(natural);
            }
            MorphWand.setPlayerSize(mob, 1f, accessor);
            BuffJump.npc(mob, 1f, 1f, accessor);
            return;
        }
        s.weight.clear();
        s.direct.clear();
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
                s.direct.clear();
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
        long sum = s.direct.getOrDefault(st, 0);
        for (Map.Entry<Source, Integer> e : s.weight.entrySet()) {
            if (battleSources || e.getKey() == Source.WAND) sum += (long) percent(e.getKey(), st) * e.getValue();
        }
        return (int) Math.min(st.totalMax(), sum);
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
        // Size: the entity scale (mobs and players), from the one it had before the first buff. A player also gets
        // their model's scale (MorphWand.setPlayerSize): the client draws a player's body at the entity scale but puts
        // their camera by the model's scale (user, 2026-10-12: each alone grew only one of them).
        int size = total(s, Stat.SIZE);
        EntityScaleComponent scale = accessor.getComponent(mob, EntityScaleComponent.getComponentType());
        float scaleBefore = sizeScale(mob, accessor);
        MorphWand.setPlayerSize(mob, size > 0 ? 1 + size / 100f : 1f, accessor);
        if (size > 0) {
            // Its model's scale (1 for players), not the current entity scale: a size buff saved with the mob before a
            // restart/reload is still on it (our state isn't saved), and taking that scale as the base made it grow
            // again on every new buff and need two Cleanse clicks. Not a fixed 1 either: an entity scale replaces the
            // model's, so a mob built 2.5x big (Nightlord) shrank (2026-10-16). Same rule as clearNow.
            if (s.baseScale == null) s.baseScale = naturalScale(mob, accessor);
            float want = s.baseScale * (1 + size / 100f);
            if (scale == null) accessor.putComponent(mob, EntityScaleComponent.getComponentType(), new EntityScaleComponent(want));
            else if (scale.getScale() != want) scale.setScale(want);
        } else if (s.baseScale != null) {
            if (scale != null) scale.setScale(s.baseScale);
            s.baseScale = null;
        }
        float scaled = sizeScale(mob, accessor);
        // Mobs jump, climb and run in step with their size and speed (players: BuffJump on their model change, and
        // here when only the speed changed).
        // A player growing past x2: their reach doesn't grow (per item, measured from the eye; validated by the server,
        // docs/general/hallazgos.md 2026-10-13), so mobs at their feet can't be hit. Told once per crossing.
        if (scaled > REACH_WARN_SCALE && scaleBefore <= REACH_WARN_SCALE) {
            com.hypixel.hytale.server.core.universe.PlayerRef pr = accessor.getComponent(mob, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (pr != null) pr.sendMessage(I18n.tr("chat.buffs.reach").color("#ffcc55"));
        }
        int speed = total(s, Stat.SPEED);
        float factor = size > 0 ? 1 + size / 100f : 1f;
        if (Math.abs(factor - s.moveSize) > 0.001f || speed != s.speed) {
            BuffJump.npc(mob, factor, 1 + speed / 100f, accessor);
            if (speed != s.speed) BuffJump.player(mob, accessor);
            s.speed = speed;
            s.moveSize = factor;
        }
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
        // Sized to the mob (attached particles don't grow with the entity scale): CrownBuff_<color>_S200 at x2. A
        // size-buffed player's model is scaled too and their particles grow with it: no extra size for them.
        float fxScale = WandFx.particleScale(mob, scaled);
        String want = crowned ? "CrownBuff_" + crown + WandFx.crownSize(fxScale) : null;
        if (s.crownFx != null && !s.crownFx.equals(want)) {
            WandFx.removeFx(mob, s.crownFx, accessor);
            s.crownFx = null;
        }
        if (want != null && (!want.equals(s.crownFx) || resized)) {
            s.crownFx = want;
            if (resized) putAgainLater(mob, want, () -> s.crownFx);
            else WandFx.addFx(mob, want, accessor);
        }
        applyLook(mob, s, resized, fxScale, accessor);
        // A Jade/Shimmer look of its own (Effect Wand) also loses its ModelVFX in the rebuild: LookKeeper re-sends it.
        if (resized) LookKeeper.resized(mob);
        if (s.weight.isEmpty() && s.direct.isEmpty()) STATES.remove(mob, s);
    }

    /**
     * /mobbattle apply ... size|health|damage|defense|speed &lt;value&gt;: a direct amount (percent; 0 = none) on that mob or
     * player, kept until Cleanse, unbuff, death or a restart. Its world thread.
     */
    static void setDirect(Ref<EntityStore> mob, Stat st, int percent) {
        if (mob == null || !mob.isValid()) return;
        run(mob, store -> {
            State s = STATES.get(mob);
            if (percent <= 0) {
                if (s == null || s.direct.remove(st) == null) return;
            } else {
                if (s == null) STATES.put(mob, s = new State());
                s.direct.put(st, Math.min(st.totalMax(), percent));
            }
            apply(mob, s, false, store);
        });
    }

    /** A player bigger than this is told their reach doesn't grow. */
    private static final float REACH_WARN_SCALE = 2.01f;

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
        // buffLook off: no lasting look; runes: only the eye flames and runes, never the shimmer veins.
        boolean looks = !"off".equals(FxSettings.buffLook) && (s.weight.containsKey(Source.STREAK)
                || s.weight.containsKey(Source.CHAMPION) || s.weight.containsKey(Source.WAND));
        boolean runesOnly = "runes".equals(FxSettings.buffLook) || LookKeeper.hasOwnLook(mob);
        String look = looks ? (runesOnly ? BUFF_RUNES : BUFF_SHIMMER) + FxSettings.buffColor
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
        // Sized as apply() and refreshLookAll() do (audit 2026-10-14: the raw scale made a x3 player's runes huge again).
        applyLook(mob, s, false, WandFx.particleScale(mob, sizeScale(mob, accessor)), accessor);
    }

    /**
     * buffLook or buffColor changed (command, panel save, preset, reload): every buffed mob gets its look again, each
     * on its world thread. A look that didn't change is left alone (applyLook compares the effect id).
     */
    static void refreshLookAll() {
        forEachWorld(STATES.keySet(), (store, mobs) -> {
            for (Ref<EntityStore> mob : mobs) {
                State s = STATES.get(mob);
                if (s != null && mob.isValid()) applyLook(mob, s, false, WandFx.particleScale(mob, sizeScale(mob, store)), store);
            }
        });
    }

    /** "size +20% (x1.2), health +40% (x1.4)" for that many Buff Wand stacks (each stat capped at its max). */
    static String describeWand(int stacks) {
        StringBuilder b = new StringBuilder();
        for (Stat st : Stat.values()) {
            int v = Math.min(st.totalMax(), percent(Source.WAND, st) * stacks);
            if (v <= 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(st.key).append(' ').append(both(st, v));
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
        // Damage it does to itself (its own burn or fall, an effect it carries) is not raised by its own damage buff.
        State a = attacker != null && !attacker.equals(victim) ? STATES.get(attacker) : null;
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
        if (s != null) {
            modelChanged(neu);
            // The new role has fresh motion controllers: its size and speed buffs go on them again.
            float factor = 1 + total(s, Stat.SIZE) / 100f, speed = 1 + total(s, Stat.SPEED) / 100f;
            s.moveSize = factor;
            run(neu, store -> {
                if (neu.isValid()) BuffJump.npc(neu, factor, speed, store);
            });
        }
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
        try {
            sizeMax = Math.max(MIN_SIZE, Math.min(MAX_SIZE, Integer.parseInt(p.getProperty("buff.size.max", String.valueOf(SIZE_SAFE)).trim())));
        } catch (NumberFormatException ignored) {
        }
        // buff heal (one switch for every source, before buff <who> heal) loads into every source.
        String oldHeal = p.getProperty("buff.heal");
        for (Source s : Source.values()) {
            HEAL.put(s, Boolean.parseBoolean(p.getProperty("buff." + s.key + ".heal", oldHeal != null ? oldHeal : "false").trim()));
        }
        String c = parseCrown(p.getProperty("buff.crown"));
        if (c != null) crown = c;
        Boolean u = parseUnit(p.getProperty("buff.unit"));
        if (u != null) multiplier = u;
        String sp = parseSizeEffect(p.getProperty("buff.size.speed"));
        if (sp != null) sizeSpeed = sp;
        String sj = parseSizeEffect(p.getProperty("buff.size.jump"));
        if (sj != null) sizeJump = sj;
        if (!STATES.isEmpty()) refreshMovementAll();
    }

    static void save(Properties p) {
        for (Source s : Source.values()) {
            for (Stat st : Stat.values()) p.setProperty("buff." + s.key + "." + st.key, String.valueOf(percent(s, st)));
        }
        p.setProperty("buff.streak.maxkills", String.valueOf(streakMax));
        p.setProperty("buff.wand.maxstacks", String.valueOf(wandMax));
        p.setProperty("buff.size.max", String.valueOf(sizeMax));
        for (Source s : Source.values()) p.setProperty("buff." + s.key + ".heal", String.valueOf(heals(s)));
        p.setProperty("buff.crown", crown);
        p.setProperty("buff.unit", unit());
        p.setProperty("buff.size.speed", sizeSpeed);
        p.setProperty("buff.size.jump", sizeJump);
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
                if (v > 0) b.append(' ').append(st.key).append(' ').append(both(st, v));
            }
            if (s == Source.STREAK) b.append(" per kill");
            if (s == Source.WAND) b.append(" per click");
        }
        return b.append(" | sizeMax ").append(both(Stat.SIZE, sizeMax)).append(" | sizeSpeed ").append(sizeSpeed)
                .append(" | sizeJump ").append(sizeJump).append(" | unit ").append(unit())
                .append(" | maxKills ").append(streakMax).append(" | wand maxStacks ").append(wandMax)
                .append(" | crown ").append(crown.toLowerCase(Locale.ROOT)).toString();
    }
}
