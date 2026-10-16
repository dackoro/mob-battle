package dev.dackoro.mobbattle;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Team slots (up to {@link #MAX}): RED, BLUE, GREEN, YELLOW by default, plus custom teams with any
 * name and a fixed palette color (/mobbattle teams add). Names and colors live in BattleManager.TEAM_NAMES /
 * TEAM_COLORS (null = free slot).
 * <p>
 * Team look: all 14 palette colors ship as assets (MobBattle_Team_&lt;Color&gt;_&lt;Mode&gt;).
 * Legacy custom colors are mapped to the palette when loading; no runtime assets are created.
 */
public final class Teams {

    public static final int MAX = 14;
    public static final String[] MODES = {"Both", "Tint", "Mist"};

    /** Palette with prebuilt effects. Keys are the asset names. */
    public static final Map<String, String> PALETTE = new LinkedHashMap<>();

    static {
        PALETTE.put("Red", "#FF4B3E");
        PALETTE.put("Blue", "#4B9BFF");
        PALETTE.put("Green", "#4BFF6A");
        PALETTE.put("Yellow", "#FFD23E");
        PALETTE.put("Orange", "#FF8C1A");
        PALETTE.put("Purple", "#A24BFF");
        PALETTE.put("Pink", "#FF6BD5");
        PALETTE.put("Cyan", "#3EF0FF");
        PALETTE.put("White", "#F2F2F2");
        PALETTE.put("Black", "#2A2A2A");
        PALETTE.put("Brown", "#8B5A2B");
        PALETTE.put("Lime", "#B6FF3E");
        PALETTE.put("Gray", "#808080");
        PALETTE.put("Magenta", "#FF00FF");
        resetDefaults();
    }

    /** Prebuilt palette effect base id per slot, resolved lazily. */
    private static final String[] EFFECT_BASE = new String[MAX];

    private Teams() {
    }

    public static void resetDefaults() {
        for (int i = 0; i < MAX; i++) {
            BattleManager.TEAM_NAMES[i] = null;
            BattleManager.TEAM_COLORS[i] = null;
            if (EFFECT_BASE != null) {
                EFFECT_BASE[i] = null;
            }
        }
        set(0, "RED", PALETTE.get("Red"));
        set(1, "BLUE", PALETTE.get("Blue"));
        set(2, "GREEN", PALETTE.get("Green"));
        set(3, "YELLOW", PALETTE.get("Yellow"));
    }

    private static void set(int slot, String name, String hex) {
        BattleManager.TEAM_NAMES[slot] = name;
        BattleManager.TEAM_COLORS[slot] = hex;
        if (EFFECT_BASE != null) {
            EFFECT_BASE[slot] = null;
        }
    }

    public static boolean exists(int slot) {
        return slot >= 0 && slot < MAX && BattleManager.TEAM_NAMES[slot] != null;
    }

    public static String name(int slot) {
        return exists(slot) ? BattleManager.TEAM_NAMES[slot] : "?";
    }

    public static String color(int slot) {
        return exists(slot) ? BattleManager.TEAM_COLORS[slot] : "#aaaaaa";
    }

    /** Team slot by name, ignoring case, or -1. */
    public static int find(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < MAX; i++) {
            if (name.equalsIgnoreCase(BattleManager.TEAM_NAMES[i])) {
                return i;
            }
        }
        return -1;
    }

    /** "#RRGGBB" for a named palette color only, or null. */
    public static String parseColor(String value) {
        if (value == null) {
            return null;
        }
        for (Map.Entry<String, String> e : PALETTE.entrySet()) {
            if (e.getKey().equalsIgnoreCase(value)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Adds a team. Returns its slot, -1 if the name is taken, -2 if all slots are used. */
    public static int add(String name, String hex) {
        requirePaletteColor(hex);
        if (find(name) >= 0) {
            return -1;
        }
        for (int i = 0; i < MAX; i++) {
            if (!exists(i)) {
                set(i, name.toUpperCase(Locale.ROOT), hex);
                return i;
            }
        }
        return -2;
    }

    /** The team (other than {@code except}) that already has this color, or -1. */
    public static int withColor(String hex, int except) {
        for (int i = 0; i < MAX; i++) {
            if (i != except && exists(i) && color(i).equalsIgnoreCase(hex)) return i;
        }
        return -1;
    }

    public static void recolor(int slot, String hex) {
        requirePaletteColor(hex);
        set(slot, BattleManager.TEAM_NAMES[slot], hex);
    }

    private static void requirePaletteColor(String hex) {
        if (!PALETTE.containsValue(hex)) {
            throw new IllegalArgumentException("Team colors must come from the fixed palette");
        }
    }

    /** Renames a team without changing its slot, color, members or summon rules. */
    public static boolean rename(int slot, String name) {
        if (!exists(slot) || name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            return false;
        }
        int other = find(name);
        if (other >= 0 && other != slot) {
            return false;
        }
        BattleManager.TEAM_NAMES[slot] = name.toUpperCase(Locale.ROOT);
        return true;
    }

    /** Each team slot's equipment kit (Kits) for team battles, or null. Saved as teams.kits = slot:kit,. */
    private static final String[] KIT = new String[MAX];

    static String kit(int slot) {
        return slot >= 0 && slot < MAX && exists(slot) ? KIT[slot] : null;
    }

    static void setKit(int slot, String kit) {
        if (slot >= 0 && slot < MAX) KIT[slot] = kit;
    }

    public static void remove(int slot) {
        KIT[slot] = null;
        BattleManager.removeConfiguredTeam(slot);
        BattleManager.TEAM_NAMES[slot] = null;
        BattleManager.TEAM_COLORS[slot] = null;
        EFFECT_BASE[slot] = null;
        SummonList.TEAMS.values().removeIf(t -> t == slot);
    }

    public static int count() {
        int n = 0;
        for (int i = 0; i < MAX; i++) {
            if (exists(i)) {
                n++;
            }
        }
        return n;
    }

    /** Next existing slot after {@code slot}, wrapping around. */
    public static int next(int slot) {
        for (int k = 1; k <= MAX; k++) {
            int i = (slot + k) % MAX;
            if (exists(i)) {
                return i;
            }
        }
        return 0;
    }

    /** Same as {@link #describe} but translated, for the player's chat. */
    public static com.hypixel.hytale.server.core.Message describeMessage() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < MAX; i++) {
            if (exists(i)) {
                parts.add(name(i) + " " + color(i));
            }
        }
        return I18n.tr("chat.teams.list").param("count", count()).param("max", MAX).param("list", String.join(", ", parts));
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder("Teams (" + count() + "/" + MAX + "): ");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < MAX; i++) {
            if (exists(i)) {
                parts.add(name(i) + " " + color(i));
            }
        }
        return sb.append(String.join(", ", parts)).toString();
    }

    // ---- team look effects ----

    /**
     * Every effect id a team look can use, so TeamFx can remove the old one. Built once: the palette is
     * fixed, and the old runtime "MobBattle_Custom_*" effects no longer exist, so no mob can have them.
     */
    public static final List<String> ALL_EFFECT_IDS;

    static {
        List<String> ids = new ArrayList<>();
        for (String c : PALETTE.keySet()) {
            for (String m : MODES) {
                ids.add("MobBattle_Team_" + c + "_" + m);
            }
        }
        ALL_EFFECT_IDS = List.copyOf(ids);
    }

    /** Prebuilt effect id for the slot's look in the given mode (Both/Tint/Mist). */
    public static String effectId(int slot, String mode) {
        if (!exists(slot)) {
            return null;
        }
        if (EFFECT_BASE[slot] == null) {
            EFFECT_BASE[slot] = resolve(slot);
        }
        return EFFECT_BASE[slot] + "_" + mode;
    }

    private static String resolve(int slot) {
        return effectBaseForColor(color(slot));
    }

    static String effectIdForColor(String hex, String mode) {
        return effectBaseForColor(hex) + "_" + mode;
    }

    private static String effectBaseForColor(String hex) {
        for (Map.Entry<String, String> e : PALETTE.entrySet()) {
            if (e.getValue().equalsIgnoreCase(hex)) {
                return "MobBattle_Team_" + e.getKey();
            }
        }
        // Can't happen with the fixed palette, but never throw from the world tick: use the nearest color.
        return "MobBattle_Team_" + nearestPalette(hex);
    }

    static String nearestPalette(String hex) {
        int c = Integer.parseInt(hex.substring(1), 16);
        String best = "Red";
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<String, String> e : PALETTE.entrySet()) {
            int p = Integer.parseInt(e.getValue().substring(1), 16);
            long dr = ((c >> 16) & 255) - ((p >> 16) & 255);
            long dg = ((c >> 8) & 255) - ((p >> 8) & 255);
            long db = (c & 255) - (p & 255);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    // ---- persistence (fx.properties, via FxSettings; must load before SummonList) ----

    static void load(Properties p) {
        java.util.Arrays.fill(KIT, null);
        for (String item : p.getProperty("teams.kits", "").split(",")) {
            String[] kv = item.trim().split(":");
            try {
                int slot = kv.length == 2 ? Integer.parseInt(kv[0].trim()) : -1;
                if (slot >= 0 && slot < MAX && Kits.validName(kv[1].trim())) KIT[slot] = kv[1].trim();
            } catch (NumberFormatException ignored) {
            }
        }
        String list = p.getProperty("teams.list");
        if (list == null || list.isBlank()) {
            resetDefaults();
            return;
        }
        for (int i = 0; i < MAX; i++) {
            BattleManager.TEAM_NAMES[i] = null;
            BattleManager.TEAM_COLORS[i] = null;
            EFFECT_BASE[i] = null;
        }
        for (String item : list.split(",")) {
            String[] kv = item.trim().split(":");
            if (kv.length == 3) {
                try {
                    int slot = Integer.parseInt(kv[0]);
                    String hex = parseSavedColor(kv[2]);
                    if (slot >= 0 && slot < MAX && hex != null && !kv[1].isBlank()) {
                        set(slot, kv[1], hex);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (count() < 2) {
            resetDefaults();
        }
    }

    static void save(Properties p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX; i++) {
            if (exists(i)) {
                sb.append(i).append(':').append(name(i)).append(':').append(color(i).substring(1)).append(',');
            }
        }
        p.setProperty("teams.list", sb.toString());
        StringBuilder kits = new StringBuilder();
        for (int i = 0; i < MAX; i++) if (exists(i) && KIT[i] != null) kits.append(i).append(':').append(KIT[i]).append(',');
        p.setProperty("teams.kits", kits.toString());
    }

    /** Older saves store bare hex values, including previously allowed custom colors. */
    private static String parseSavedColor(String value) {
        String named = parseColor(value);
        if (named != null) {
            return named;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return PALETTE.get(nearestPalette("#" + hex));
    }
}
