package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

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
 *   <li>champion: the winners of a battle, until they die or get the Cleanse Wand.</li>
 * </ul>
 * Size scales its model (as the Morph Wand, mobs only), health is a StaticModifier (MAX, MULTIPLICATIVE) on max health,
 * damage and defense scale its hits against battle enemies (FightDamageSystem). Streak and champion mobs wear a crown
 * (buff crown, MobBattle_Fx_CrownBuff_&lt;Color&gt;). The model and the modifier are saved with the world like any
 * component, so a server that stops mid-battle keeps them; everything else is in memory.
 * Every change runs on the mob's world thread ({@link #run}).
 */
final class Buffs {

    enum Source {
        LONE("lone"), STREAK("streak"), CHAMPION("champion");

        final String key;

        Source(String key) {
            this.key = key;
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
    /** Crown on streak and champion mobs: Gold, a palette color, or off. */
    static String crown = "Gold";

    private static final String MODIFIER_KEY = "MobBattle_Buff";
    /** The key of the old targetBuff (TargetBuff), still on mobs saved mid-battle with it. */
    private static final String OLD_MODIFIER_KEY = "MobBattle_TargetBuff";

    /** One buffed mob: its sources (weight = 1, or the kills of a streak) and what was changed, to undo it. */
    private static final class State {
        final EnumMap<Source, Integer> weight = new EnumMap<>(Source.class);
        Model original;
        Model scaled;
        boolean health;
        String crownFx;
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
        streakMax = 5;
        crown = "Gold";
    }

    static int percent(Source s, Stat st) {
        return PERCENT.get(s).get(st);
    }

    static void setPercent(Source s, Stat st, int value) {
        PERCENT.get(s).put(st, Math.max(0, Math.min(st.max, value)));
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
        if (weight > 0 && !enabled(source) && !STATES.containsKey(mob)) return;
        run(mob, store -> {
            State s = STATES.get(mob);
            if (weight <= 0) {
                if (s == null || s.weight.remove(source) == null) return;
            } else {
                if (!enabled(source)) return;
                // Queued just before its battle ended (e.g. the last kill): a battle-only buff would then never go.
                if (source != Source.CHAMPION && BattleManager.battleOf(mob) == null) return;
                // Same weight (a streak already at maxKills): nothing to redo, no new model sent.
                if (s != null && Integer.valueOf(weight).equals(s.weight.get(source))) return;
                if (s == null) STATES.put(mob, s = new State());
                s.weight.put(source, weight);
            }
            // A new lone target or champion starts with full health; a streak kill doesn't heal.
            apply(mob, s, source != Source.STREAK && weight > 0, store);
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
                stats.removeModifier(DefaultEntityStatTypes.getHealth(), MODIFIER_KEY);
                stats.removeModifier(DefaultEntityStatTypes.getHealth(), OLD_MODIFIER_KEY);
            }
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
        int sum = 0;
        for (Map.Entry<Source, Integer> e : s.weight.entrySet()) sum += percent(e.getKey(), st) * e.getValue();
        return Math.min(st.max, sum);
    }

    /** Brings the mob's model, health modifier and crown in line with its sources. World thread. */
    private static void apply(Ref<EntityStore> mob, State s, boolean fillHealth, ComponentAccessor<EntityStore> accessor) {
        if (!mob.isValid()) {
            STATES.remove(mob);
            return;
        }
        // Size: always from the model it had before the first buff.
        int size = total(s, Stat.SIZE);
        ModelComponent mc = accessor.getComponent(mob, ModelComponent.getComponentType());
        boolean npc = accessor.getComponent(mob, NPCEntity.getComponentType()) != null;
        Model current = mc != null ? mc.getModel() : null;
        boolean ours = current != null && current == s.scaled;
        if (size > 0 && npc && current != null) {
            Model base = ours && s.original != null ? s.original : current;
            ModelAsset asset = base.getModelAssetId() != null ? ModelAsset.getAssetMap().getAsset(base.getModelAssetId()) : null;
            if (asset != null) {
                float scale = (base.getScale() > 0 ? base.getScale() : 1f) * (1 + size / 100f);
                s.original = base;
                s.scaled = Model.createScaledModel(asset, scale);
                accessor.putComponent(mob, ModelComponent.getComponentType(), new ModelComponent(s.scaled));
            }
        } else if (s.original != null) {
            // A morph applied meanwhile stays.
            if (ours) accessor.putComponent(mob, ModelComponent.getComponentType(), new ModelComponent(s.original));
            s.original = null;
            s.scaled = null;
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
        // Crown on streak and champion mobs.
        boolean crowned = !"off".equals(crown) && (s.weight.containsKey(Source.STREAK) || s.weight.containsKey(Source.CHAMPION));
        String want = crowned ? "CrownBuff_" + crown : null;
        if (s.crownFx != null && !s.crownFx.equals(want)) {
            WandFx.removeFx(mob, s.crownFx, accessor);
            s.crownFx = null;
        }
        if (want != null && !want.equals(s.crownFx)) {
            WandFx.addFx(mob, want, accessor);
            s.crownFx = want;
        }
        if (s.weight.isEmpty()) STATES.remove(mob);
    }

    /** Hit damage between battle enemies: more from a buffed attacker, less to a buffed victim. */
    static float scale(Ref<EntityStore> attacker, Ref<EntityStore> victim, float amount) {
        if (STATES.isEmpty()) return amount;
        State a = STATES.get(attacker);
        if (a != null) amount *= 1 + total(a, Stat.DAMAGE) / 100f;
        State v = STATES.get(victim);
        if (v != null) amount *= 1 - total(v, Stat.DEFENSE) / 100f;
        return amount;
    }

    /** BattleManager.maintain: drops dead or unloaded mobs. */
    static void prune() {
        STATES.keySet().removeIf(r -> !r.isValid());
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
        String c = parseCrown(p.getProperty("buff.crown"));
        if (c != null) crown = c;
    }

    static void save(Properties p) {
        for (Source s : Source.values()) {
            for (Stat st : Stat.values()) p.setProperty("buff." + s.key + "." + st.key, String.valueOf(percent(s, st)));
        }
        p.setProperty("buff.streak.maxkills", String.valueOf(streakMax));
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
                continue;
            }
            for (Stat st : Stat.values()) {
                int v = percent(s, st);
                if (v > 0) b.append(' ').append(st.key).append(' ').append(v).append('%');
            }
            if (s == Source.STREAK) b.append(" per kill");
        }
        return b.append(" | maxKills ").append(streakMax).append(" | crown ").append(crown.toLowerCase(Locale.ROOT)).toString();
    }
}
