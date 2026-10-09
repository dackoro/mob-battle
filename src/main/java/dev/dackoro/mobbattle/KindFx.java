package dev.dackoro.mobbattle;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Presentation settings (countdown, cameras, titles, music, laser, area look, runes) with a global value and an
 * optional override per battle kind: /mobbattle &lt;option&gt; &lt;value&gt; sets the global value,
 * /mobbattle &lt;option&gt; &lt;kind&gt; &lt;value&gt; overrides it for that kind only, and
 * /mobbattle &lt;option&gt; &lt;kind&gt; default makes that kind follow the global value again.
 * The global values live in {@link FxSettings} fields (same property keys as before); overrides are saved as
 * {@code kind.<kind>.<option>}.
 */
final class KindFx {

    enum Opt {
        COUNTDOWN("countdown"),
        CAMERA("camera"),
        CAMERA_STYLE("cameraStyle"),
        CAMERA_TIME("cameraTime"),
        CAMERA_SHOTS("cameraShots"),
        END_CAMERA("endCamera"),
        END_CAMERA_TIME("endCameraTime"),
        START_TITLE("startTitle"),
        WIN_TITLE("winTitle"),
        MUSIC("music"),
        LASER("fightLaser"),
        AREA_STYLE("areaStyle"),
        RUNES("runes");

        /** Command word in camelCase (help, settings); the property key uses it lowercased. */
        final String word;

        Opt(String word) {
            this.word = word;
        }

        String key() {
            return word.toLowerCase(Locale.ROOT);
        }
    }

    static final String[] CAMERA_STYLES = {"auto", "orbit", "duel", "showcase", "teams", "focus", "drone"};
    static final String[] END_CAMERAS = {"off", "winner", "lasthit"};
    static final int MAX_CAMERA_TIME = 30;
    static final int MAX_CAMERA_SHOTS = 12;
    static final int MAX_END_CAMERA_TIME = 15;

    /** Kind -> option -> value (canonical, see {@link #parse}). Missing = the global value. */
    private static final Map<BattleManager.Kind, Map<Opt, String>> OVERRIDES = new EnumMap<>(BattleManager.Kind.class);

    static {
        for (BattleManager.Kind k : BattleManager.Kind.values()) OVERRIDES.put(k, new ConcurrentHashMap<>());
    }

    private KindFx() {
    }

    /** Option by its command word (any case), or null. */
    static Opt optOf(String word) {
        for (Opt o : Opt.values()) {
            if (o.word.equalsIgnoreCase(word)) return o;
        }
        return null;
    }

    // ---- typed getters (kind null = the global value) ----

    static int countdown(BattleManager.Kind kind) {
        return intOf(get(kind, Opt.COUNTDOWN));
    }

    static boolean camera(BattleManager.Kind kind) {
        return "on".equals(get(kind, Opt.CAMERA));
    }

    /** The intro camera style, "auto" resolved: duel western shots, allvsone focus, teams sweep, royale orbit. */
    static String cameraStyle(BattleManager.Kind kind) {
        String s = get(kind, Opt.CAMERA_STYLE);
        if (!"auto".equals(s)) return s;
        if (kind == null) return "orbit";
        return switch (kind) {
            case DUEL -> "duel";
            case ALL_VS_ONE -> "focus";
            case TEAMS -> "teams";
            default -> "orbit";
        };
    }

    /** Seconds the intro camera plays: cameraTime, or the countdown when cameraTime is 0. 0 = no intro. */
    static int introSeconds(BattleManager.Kind kind) {
        if (!camera(kind)) return 0;
        int t = intOf(get(kind, Opt.CAMERA_TIME));
        return t > 0 ? t : countdown(kind);
    }

    static int cameraShots(BattleManager.Kind kind) {
        return Math.max(1, intOf(get(kind, Opt.CAMERA_SHOTS)));
    }

    /** off, winner or lasthit. */
    static String endCamera(BattleManager.Kind kind) {
        return get(kind, Opt.END_CAMERA);
    }

    static int endCameraSeconds(BattleManager.Kind kind) {
        return Math.max(1, intOf(get(kind, Opt.END_CAMERA_TIME)));
    }

    static boolean startTitle(BattleManager.Kind kind) {
        return "on".equals(get(kind, Opt.START_TITLE));
    }

    static boolean winTitle(BattleManager.Kind kind) {
        return "on".equals(get(kind, Opt.WIN_TITLE));
    }

    static boolean laser(BattleManager.Kind kind) {
        return "on".equals(get(kind, Opt.LASER));
    }

    static boolean runes(BattleManager.Kind kind) {
        return "on".equals(get(kind, Opt.RUNES));
    }

    static String areaStyle(BattleManager.Kind kind) {
        return get(kind, Opt.AREA_STYLE);
    }

    /** Music track of this kind's battles, or null for no music. A kind set to "on" uses the global track. */
    static String musicTrack(BattleManager.Kind kind) {
        String v = get(kind, Opt.MUSIC);
        if ("off".equals(v)) return null;
        if ("on".equals(v)) return FxSettings.musicTrack;
        return v;
    }

    // ---- raw access ----

    /** The value for {@code kind}: its override, else the global value. */
    static String get(BattleManager.Kind kind, Opt opt) {
        if (kind != null) {
            String v = OVERRIDES.get(kind).get(opt);
            if (v != null) return v;
        }
        return global(opt);
    }

    static String override(BattleManager.Kind kind, Opt opt) {
        return kind == null ? null : OVERRIDES.get(kind).get(opt);
    }

    /** Sets ({@code value} canonical, from {@link #parse}) or clears ({@code null}) a kind's override. */
    static void setOverride(BattleManager.Kind kind, Opt opt, String value) {
        if (value == null) OVERRIDES.get(kind).remove(opt);
        else OVERRIDES.get(kind).put(opt, value);
    }

    static void clearOverrides(Opt opt) {
        for (Map<Opt, String> m : OVERRIDES.values()) m.remove(opt);
    }

    static void clearAllOverrides() {
        for (Map<Opt, String> m : OVERRIDES.values()) m.clear();
    }

    /** The global value as a canonical string. */
    static String global(Opt opt) {
        return switch (opt) {
            case COUNTDOWN -> String.valueOf(FxSettings.countdownSeconds);
            case CAMERA -> onOff(FxSettings.fightCamera);
            case CAMERA_STYLE -> FxSettings.cameraStyle;
            case CAMERA_TIME -> String.valueOf(FxSettings.cameraSeconds);
            case CAMERA_SHOTS -> String.valueOf(FxSettings.cameraShots);
            case END_CAMERA -> FxSettings.endCamera;
            case END_CAMERA_TIME -> String.valueOf(FxSettings.endCameraSeconds);
            case START_TITLE -> onOff(FxSettings.titleEnabled);
            case WIN_TITLE -> onOff(FxSettings.winTitleEnabled);
            case MUSIC -> FxSettings.battleMusic ? FxSettings.musicTrack : "off";
            case LASER -> onOff(FxSettings.laserEnabled);
            case AREA_STYLE -> FxSettings.areaStyle;
            case RUNES -> onOff(FxSettings.runes);
        };
    }

    /** Sets the global value; {@code value} canonical, from {@link #parse}. */
    static void setGlobal(Opt opt, String value) {
        switch (opt) {
            case COUNTDOWN -> FxSettings.countdownSeconds = intOf(value);
            case CAMERA -> FxSettings.fightCamera = "on".equals(value);
            case CAMERA_STYLE -> FxSettings.cameraStyle = value;
            case CAMERA_TIME -> FxSettings.cameraSeconds = intOf(value);
            case CAMERA_SHOTS -> FxSettings.cameraShots = intOf(value);
            case END_CAMERA -> FxSettings.endCamera = value;
            case END_CAMERA_TIME -> FxSettings.endCameraSeconds = intOf(value);
            case START_TITLE -> FxSettings.titleEnabled = "on".equals(value);
            case WIN_TITLE -> FxSettings.winTitleEnabled = "on".equals(value);
            case MUSIC -> {
                if ("off".equals(value)) {
                    FxSettings.battleMusic = false;
                } else {
                    FxSettings.battleMusic = true;
                    if (!"on".equals(value)) FxSettings.musicTrack = value;
                }
            }
            case LASER -> FxSettings.laserEnabled = "on".equals(value);
            case AREA_STYLE -> FxSettings.areaStyle = value;
            case RUNES -> FxSettings.runes = "on".equals(value);
        }
    }

    /**
     * Canonical value for {@code opt} (lowercase words, clamped numbers, on/off for true/false), or null if
     * {@code raw} is not valid for it.
     */
    static String parse(Opt opt, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        String lower = v.toLowerCase(Locale.ROOT);
        return switch (opt) {
            case COUNTDOWN -> number(lower, 0, FxSettings.MAX_COUNTDOWN);
            case CAMERA_TIME -> number(lower, 0, MAX_CAMERA_TIME);
            case CAMERA_SHOTS -> number(lower, 1, MAX_CAMERA_SHOTS);
            case END_CAMERA_TIME -> number(lower, 1, MAX_END_CAMERA_TIME);
            case CAMERA, START_TITLE, WIN_TITLE, LASER, RUNES -> onOffWord(lower);
            case CAMERA_STYLE -> oneOf(lower, CAMERA_STYLES);
            case END_CAMERA -> oneOf(lower, END_CAMERAS);
            case AREA_STYLE -> FxSettings.parseAreaStyle(lower);
            case MUSIC -> {
                String onOff = onOffWord(lower);
                if (onOff != null) yield onOff;
                yield com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer
                        .getAssetMap().getIndex(v) > 0 ? v : null;
            }
        };
    }

    /** What each option accepts, for usage messages. */
    static String usage(Opt opt) {
        return switch (opt) {
            case COUNTDOWN -> "<seconds 0-" + FxSettings.MAX_COUNTDOWN + ">";
            case CAMERA_TIME -> "<seconds 0-" + MAX_CAMERA_TIME + "> (0 = as long as the countdown)";
            case CAMERA_SHOTS -> "<1-" + MAX_CAMERA_SHOTS + ">";
            case END_CAMERA_TIME -> "<seconds 1-" + MAX_END_CAMERA_TIME + ">";
            case CAMERA, START_TITLE, WIN_TITLE, LASER, RUNES -> "on|off";
            case CAMERA_STYLE -> "auto|orbit|duel|showcase|teams|focus|drone";
            case END_CAMERA -> "off|winner|lastHit";
            case AREA_STYLE -> String.join("|", FxSettings.AREA_STYLES);
            case MUSIC -> "on|off|<track>";
        };
    }

    // ---- saving ----

    static void save(Properties p) {
        OVERRIDES.forEach((kind, map) -> map.forEach((opt, v) -> p.setProperty(propKey(kind, opt), v)));
    }

    /** Replaces every override with the {@code kind.*} keys of {@code p} (invalid values are skipped). */
    static void load(Properties p) {
        clearAllOverrides();
        for (BattleManager.Kind kind : BattleManager.Kind.values()) {
            for (Opt opt : Opt.values()) {
                String raw = p.getProperty(propKey(kind, opt));
                if (raw == null) continue;
                // Music tracks are only known once assets are loaded: keep a saved track as is.
                // An area style saved before the current styles loads as its new one (FxSettings.loadAreaStyle).
                String v = opt == Opt.MUSIC && onOffWord(raw.toLowerCase(Locale.ROOT)) == null ? raw.trim()
                        : opt == Opt.AREA_STYLE ? FxSettings.loadAreaStyle(raw, FxSettings.currentAreaStyles(p), null)
                        : parse(opt, raw);
                if (v != null) OVERRIDES.get(kind).put(opt, v);
            }
        }
    }

    static boolean isOverrideKey(String key) {
        return key.startsWith("kind.");
    }

    static String propKey(BattleManager.Kind kind, Opt opt) {
        return "kind." + kind.key + "." + opt.key();
    }

    /** "royale cameraStyle showcase, duel countdown 0" or "none". */
    static String describeOverrides() {
        StringBuilder sb = new StringBuilder();
        OVERRIDES.forEach((kind, map) -> {
            for (Opt opt : Opt.values()) {
                String v = map.get(opt);
                if (v == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(kind.key).append(' ').append(opt.word).append(' ').append(v);
            }
        });
        return sb.length() == 0 ? "none" : sb.toString();
    }

    // ---- helpers ----

    private static String number(String v, int min, int max) {
        try {
            return String.valueOf(Math.max(min, Math.min(max, Integer.parseInt(v))));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String oneOf(String v, String[] allowed) {
        for (String a : allowed) {
            if (a.equals(v)) return a;
        }
        return null;
    }

    private static String onOffWord(String v) {
        return switch (v) {
            case "on", "true" -> "on";
            case "off", "false" -> "off";
            default -> null;
        };
    }

    private static int intOf(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
