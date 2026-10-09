package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Per wand action: the color of its ground shockwave and of its glow columns (/mobbattle wave, /mobbattle glow).
 * Colors are palette names only (Teams.PALETTE): the glow particles are prebuilt per palette color.
 * Wave: a color or off. Glow: a color, off, or area (the chaos area's own style and areaColor).
 */
public final class ActionFx {

    public enum Action {
        SUMMON("summon", "Green", "Green"),
        SNAP("snap", "White", "off"),
        SLAM("slam", "Purple", "off"),
        ALL_VS_ONE("allvsone", "Yellow", "Yellow"),
        ROYALE("royale", "Purple", "Purple");

        public final String key;
        final String defWave;
        final String defGlow;

        Action(String key, String defWave, String defGlow) {
            this.key = key;
            this.defWave = defWave;
            this.defGlow = defGlow;
        }

        static Action of(String key) {
            for (Action a : values()) if (a.key.equalsIgnoreCase(key)) return a;
            return null;
        }
    }

    public static final String OFF = "off";
    public static final String AREA = "area";
    /** How long a glow ring of snap/slam (no area of its own) stays. */
    private static final float RING_GLOW_SECONDS = 3f;

    private static final Map<Action, String> WAVE = new EnumMap<>(Action.class);
    private static final Map<Action, String> GLOW = new EnumMap<>(Action.class);

    /**
     * The look put on battle mobs at the start, per role (/mobbattle mobFx): a palette color (tint, aura,
     * burst and rune at the feet: MobBattle_Fx_Mark_<Color>), default (the original effect) or off.
     */
    public enum Role {
        DUEL("duel", "default", WandFx.CHARM),
        LONE("allvsone", "default", WandFx.LONE),
        ATTACKERS("attackers", "default", WandFx.RAGE),
        ROYALE("royale", "Purple", WandFx.BRAWL);

        public final String key;
        final String def;
        final String original;

        Role(String key, String def, String original) {
            this.key = key;
            this.def = def;
            this.original = original;
        }
    }

    public static final String DEFAULT = "default";
    /** Everyone vs one: the crown over the lone target (/mobbattle crown): Gold (the game's), a palette color or off. */
    static String crown = "Gold";
    private static final Map<Role, String> MOB = new EnumMap<>(Role.class);

    static {
        reset();
    }

    /** The WandFx effect of a role, or null when off. */
    static String mobFx(Role r) {
        String v = MOB.getOrDefault(r, r.def);
        if (OFF.equals(v)) return null;
        return DEFAULT.equals(v) ? r.original : WandFx.MARK + v;
    }

    static boolean setMob(String roleWord, String value) {
        String v = DEFAULT.equalsIgnoreCase(value) ? DEFAULT : parse(value, false);
        if (v == null || roleWord == null) return false;
        boolean any = false;
        for (Role r : Role.values()) {
            if ("all".equalsIgnoreCase(roleWord) || r.key.equalsIgnoreCase(roleWord)) {
                MOB.put(r, v);
                any = true;
            }
        }
        return any;
    }

    static String roles() {
        StringBuilder b = new StringBuilder();
        for (Role r : Role.values()) b.append(r.key).append('|');
        return b.append("all").toString();
    }

    private ActionFx() {
    }

    static void reset() {
        for (Action a : Action.values()) {
            WAVE.put(a, a.defWave);
            GLOW.put(a, a.defGlow);
        }
        for (Role r : Role.values()) MOB.put(r, r.def);
        crown = "Gold";
    }

    public static String wave(Action a) {
        return WAVE.get(a);
    }

    public static String glow(Action a) {
        return GLOW.get(a);
    }

    /** A palette name (canonical case), "off", or (glow only) "area"; null if not valid. */
    static String parse(String value, boolean glow) {
        if (value == null) return null;
        if (OFF.equalsIgnoreCase(value)) return OFF;
        if (glow && AREA.equalsIgnoreCase(value)) return AREA;
        for (String name : Teams.PALETTE.keySet()) if (name.equalsIgnoreCase(value)) return name;
        return null;
    }

    /** /mobbattle wave|glow &lt;action|all&gt; &lt;value&gt;; false if the words aren't valid. */
    static boolean set(boolean glow, String actionWord, String value) {
        String v = parse(value, glow);
        if (v == null || actionWord == null) return false;
        Map<Action, String> map = glow ? GLOW : WAVE;
        if ("all".equalsIgnoreCase(actionWord)) {
            for (Action a : Action.values()) map.put(a, v);
            return true;
        }
        Action a = Action.of(actionWord);
        if (a == null) return false;
        map.put(a, v);
        return true;
    }

    static String keys() {
        StringBuilder b = new StringBuilder();
        for (Action a : Action.values()) b.append(a.key).append('|');
        return b.append("all").toString();
    }

    static String colors(boolean glow) {
        return String.join("|", Teams.PALETTE.keySet()).toLowerCase(Locale.ROOT) + "|off" + (glow ? "|area" : "");
    }

    static void load(Properties p) {
        reset();
        for (Action a : Action.values()) {
            String w = parse(p.getProperty("wave." + a.key), false);
            if (w != null) WAVE.put(a, w);
            String g = parse(p.getProperty("glow." + a.key), true);
            if (g != null) GLOW.put(a, g);
        }
        // Saved as true/false before the crown had colors.
        String c = p.getProperty("fx.crown", "Gold");
        crown = "true".equalsIgnoreCase(c) ? "Gold" : "false".equalsIgnoreCase(c) ? "off"
                : Buffs.parseCrown(c) != null ? Buffs.parseCrown(c) : "Gold";
        for (Role r : Role.values()) {
            String v = p.getProperty("mobfx." + r.key);
            if (DEFAULT.equalsIgnoreCase(v)) MOB.put(r, DEFAULT);
            else if (parse(v, false) != null) MOB.put(r, parse(v, false));
        }
    }

    static void save(Properties p) {
        for (Action a : Action.values()) {
            p.setProperty("wave." + a.key, WAVE.get(a));
            p.setProperty("glow." + a.key, GLOW.get(a));
        }
        for (Role r : Role.values()) p.setProperty("mobfx." + r.key, MOB.get(r));
        p.setProperty("fx.crown", crown);
    }

    static String describe() {
        StringBuilder b = new StringBuilder();
        for (Action a : Action.values()) {
            if (b.length() > 0) b.append(", ");
            b.append(a.key).append(' ').append(WAVE.get(a).toLowerCase(Locale.ROOT))
                    .append('/').append(GLOW.get(a).toLowerCase(Locale.ROOT));
        }
        StringBuilder m = new StringBuilder();
        for (Role r : Role.values()) {
            if (m.length() > 0) m.append(", ");
            m.append(r.key).append(' ').append(MOB.getOrDefault(r, r.def).toLowerCase(Locale.ROOT));
        }
        return "Waves/borderColor: " + b + " | mobFx: " + m + " | crown " + crown.toLowerCase(Locale.ROOT);
    }

    /** The action's shockwave out to {@code radius} (nothing when off). */
    static void shockwave(Action a, org.joml.Vector3d center, double radius, ComponentAccessor<EntityStore> buffer) {
        String c = WAVE.get(a);
        if (OFF.equals(c)) return;
        FightFx.shockwave(center, radius, Teams.PALETTE.get(c), buffer);
    }

    /**
     * The palette color of an action's border glow, or null for none: off = none, area = the area's own color
     * ({@code areaColor}), else that color.
     */
    static String glowColor(Action a, String areaColor) {
        String g = GLOW.get(a);
        if (OFF.equals(g)) return null;
        return AREA.equals(g) ? areaColor : g;
    }

    /** Snap / slam (no area): a ring of glow columns at {@code radius} when its glow is a color. */
    static void glowRing(Action a, org.joml.Vector3d center, double radius, ComponentAccessor<EntityStore> buffer) {
        String c = glowColor(a, FxSettings.pickAreaColorName());
        if (c == null || !FxSettings.wandFx || center == null) return;
        FightFx.drawArea(center, radius, "off", c, c, RING_GLOW_SECONDS, null, buffer);
    }

}
