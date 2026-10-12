package dev.dackoro.mobbattle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * /mobbattle preset: whole looks at once.
 * <ul>
 *   <li>default: every setting back to its default (team list and summon list are kept).</li>
 *   <li>fight: just the fights, nothing in the way (no countdown, cameras, titles, music, lasers, runes, area
 *       drawings or timers; arenas keep only their wall).</li>
 *   <li>effects: countdown 3, 2 min timer (most health wins; the royale keeps its 3 min judge), titles, music, runes, area, scoreboards, no laser;
 *       no cameras.</li>
 *   <li>debug: everything visible for testing (area floor + zone + glow, lasers, runes, arenas drawn, team
 *       look, duel card, every scoreboard), no cinematics. The debug log is not part of any preset (see isSecurityKey).</li>
 *   <li>cinematic: countdown and full camera work (western duel, drone royale, team sweep, focus, end
 *       camera with kill count), music and titles, area look, waves and arenas as by default; no lasers, runes
 *       or border glow.</li>
 *   <li>save / load / delete &lt;name&gt;: your own presets, in &lt;plugin data dir&gt;/presets/&lt;name&gt;.properties.</li>
 * </ul>
 * Built-in presets other than default only change the presentation settings (KindFx options, overrides
 * included) plus what they list here; areaRadius, maxMobs, battle rules and so on stay as they are.
 */
final class Presets {

    static final String[] BUILT_IN = {"default", "fight", "effects", "debug", "cinematic"};

    /** Presentation keys a built-in preset first puts back to default (with every per-kind override). */
    private static final String[] PRESENTATION_KEYS = {
            "fight.countdown", "fight.camera", "camera.style", "camera.time", "camera.shots", "camera.end",
            "camera.end.time", "camera.autowatch", "title.enabled", "wintitle.enabled", "battle.music", "battle.music.track",
            "laser.enabled", "chaos.area", "fx.runes", "fx.crown", "battle.duelCard"
    };

    /** Prefixes of presentation keys a built-in preset also puts back first (waves, glow, mob looks). */
    private static final String[] PRESENTATION_PREFIXES = {"wave.", "glow.", "mobfx.", "battle.scoreboard."};

    private Presets() {
    }

    /** Overlay of each built-in preset (on top of the presentation defaults). */
    private static Map<String, String> overlay(String name) {
        Map<String, String> m = new LinkedHashMap<>();
        switch (name) {
            case "fight" -> {
                m.put("fight.countdown", "0");
                m.put("fight.camera", "false");
                m.put("camera.end", "off");
                m.put("title.enabled", "false");
                m.put("wintitle.enabled", "false");
                m.put("battle.music", "false");
                m.put("laser.enabled", "false");
                m.put("chaos.area", "off");
                m.put("fx.runes", "false");
                m.put("fx.crown", "off");
                for (ActionFx.Action a : ActionFx.Action.values()) {
                    m.put("wave." + a.key, ActionFx.OFF);
                    m.put("glow." + a.key, ActionFx.OFF);
                }
                for (ActionFx.Role r : ActionFx.Role.values()) m.put("mobfx." + r.key, ActionFx.OFF);
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    m.put("battle.timer." + k.key, "0");
                    // Arenas that were on keep their wall, without drawing it.
                    Arena.Mode mode = FxSettings.arenaMode(k);
                    m.put("battle.arena." + k.key, mode == Arena.Mode.OFF ? "off" : "wall");
                }
            }
            case "effects" -> {
                // Fights with all the show but no cameras: countdown, timer, titles, music, runes, area (the laser
                // is debug only, user 2026-10-12).
                m.put("fight.countdown", "3");
                m.put("fight.camera", "false");
                m.put("camera.end", "off");
                m.put("title.enabled", "true");
                m.put("wintitle.enabled", "true");
                m.put("battle.music", "true");
                m.put("chaos.area", "discBorder");
                m.put("fx.runes", "true");
                Properties defaults = FxSettings.defaults();
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    // The royale keeps its own 3 min and judge (user, 2026-10-12); the rest get 2 min, health.
                    boolean royale = k == BattleManager.Kind.ROYALE;
                    m.put("battle.timer." + k.key, royale ? defaults.getProperty("battle.timer." + k.key, "180") : "120");
                    m.put("battle.timeup." + k.key, royale ? defaults.getProperty("battle.timeup." + k.key, "judge") : "health");
                    m.put("battle.scoreboard." + k.key, "true");
                }
            }
            case "debug" -> {
                m.put("fight.countdown", "0");
                m.put("fight.camera", "false");
                m.put("camera.end", "off");
                m.put("laser.enabled", "true");
                m.put("chaos.area", "wall");
                m.put("fx.runes", "true");
                m.put("team.fx", "both");
                // Everything to read while testing: the duel card and every scoreboard (user, 2026-10-12).
                m.put("battle.duelCard", "true");
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    m.put("battle.scoreboard." + k.key, "true");
                    // Every arena drawn; the ones that had a wall keep it.
                    Arena.Mode mode = FxSettings.arenaMode(k);
                    m.put("battle.arena." + k.key, mode == Arena.Mode.ON || mode == Arena.Mode.WALL ? "on" : "show");
                }
            }
            case "cinematic" -> {
                m.put("fight.countdown", "5");
                m.put("fight.camera", "true");
                m.put("camera.style", "auto");
                m.put("camera.time", "0");
                m.put("camera.shots", "4");
                m.put("camera.end", "winner");
                m.put("camera.end.time", "5");
                m.put("title.enabled", "true");
                m.put("wintitle.enabled", "true");
                m.put("battle.music", "true");
                m.put("laser.enabled", "false");
                m.put("fx.runes", "false");
                // The area and the waves keep their defaults (user, 2026-10-12); no border glow in the shots. The
                // mob looks and the crown stay.
                for (ActionFx.Action a : ActionFx.Action.values()) {
                    m.put("glow." + a.key, ActionFx.OFF);
                }
                m.put(KindFx.propKey(BattleManager.Kind.ROYALE, KindFx.Opt.CAMERA_STYLE), "drone");
                m.put(KindFx.propKey(BattleManager.Kind.ROYALE, KindFx.Opt.END_CAMERA), "lasthit");
                m.put(KindFx.propKey(BattleManager.Kind.ALL_VS_ONE, KindFx.Opt.END_CAMERA), "lasthit");
                Properties defaults = FxSettings.defaults();
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    // Arenas as they come by default (drawn where they are on).
                    String key = "battle.arena." + k.key;
                    if (defaults.getProperty(key) != null) m.put(key, defaults.getProperty(key));
                }
            }
            default -> {
            }
        }
        return m;
    }

    static boolean isBuiltIn(String name) {
        for (String b : BUILT_IN) {
            if (b.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Windows device names (CON, COM1...): a preset file with one of these names can't be written. */
    private static final java.util.regex.Pattern WINDOWS_RESERVED =
            java.util.regex.Pattern.compile("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]", java.util.regex.Pattern.CASE_INSENSITIVE);

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}") && !WINDOWS_RESERVED.matcher(name).matches();
    }

    /** Settings a preset or reset would give: {@code props}, or why not ({@code error}). Nothing is applied. */
    record Preview(Properties props, String error) {
    }

    /**
     * Applies a built-in or saved preset and saves the settings. Returns null if done, else why not.
     */
    static String apply(String name) {
        Preview pv = preview(name);
        if (pv.error() != null) return pv.error();
        FxSettings.applyAll(pv.props());
        return null;
    }

    /** The settings a preset gives (the settings panel loads them into its form). */
    static Preview preview(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        Properties p = FxSettings.toProperties();
        Properties defaults = FxSettings.defaults();
        if ("default".equals(lower)) {
            // Everything back to default, except the team and summon lists.
            for (String key : defaults.stringPropertyNames()) {
                if (!isListKey(key) && !isSecurityKey(key)) p.setProperty(key, defaults.getProperty(key));
            }
            p.keySet().removeIf(k -> KindFx.isOverrideKey(k.toString()));
        } else if (isBuiltIn(lower)) {
            for (String key : PRESENTATION_KEYS) {
                String v = defaults.getProperty(key);
                if (v != null) p.setProperty(key, v);
            }
            for (String key : defaults.stringPropertyNames()) {
                for (String prefix : PRESENTATION_PREFIXES) {
                    if (key.startsWith(prefix)) p.setProperty(key, defaults.getProperty(key));
                }
            }
            p.keySet().removeIf(k -> KindFx.isOverrideKey(k.toString()));
            overlay(lower).forEach(p::setProperty);
        } else {
            Path file = fileOf(name);
            if (file == null || !Files.exists(file)) return new Preview(null, "No preset named " + name + ". See /mobbattle preset list.");
            Properties saved = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                saved.load(in);
            } catch (IOException | IllegalArgumentException e) {
                return new Preview(null, "Could not read " + file.getFileName() + ": " + e.getMessage());
            }
            // A saved preset has its own per-kind overrides: drop the current ones first.
            p.keySet().removeIf(k -> KindFx.isOverrideKey(k.toString()));
            for (String key : saved.stringPropertyNames()) {
                if (!isListKey(key) && !isSecurityKey(key)) p.setProperty(key, saved.getProperty(key));
            }
        }
        return new Preview(p, null);
    }

    /** Saves the current settings (not the team and summon lists) as a preset. Returns null if done, else why not. */
    static String save(String name) {
        return save(name, FxSettings.toProperties());
    }

    /** Saves {@code settings} (the panel's form: saved settings + unsaved changes) as a preset. */
    static String save(String name, Properties settings) {
        if (!validName(name)) return "Preset names use letters, digits, - and _ (up to 32).";
        if (isBuiltIn(name)) return name + " is a built-in preset: pick another name.";
        Path file = fileOf(name);
        if (file == null) return "Settings are not loaded yet.";
        Properties p = new Properties();
        p.putAll(settings);
        p.keySet().removeIf(k -> isListKey(k.toString()) || isSecurityKey(k.toString()));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Mob Battle preset " + name + " (/mobbattle preset load " + name + ")");
            }
        } catch (IOException e) {
            return "Could not save " + file.getFileName() + ": " + e.getMessage();
        }
        return null;
    }

    /** Deletes a saved preset. Returns null if done, else why not. */
    static String delete(String name) {
        if (isBuiltIn(name)) return name + " is built in and can't be deleted.";
        Path file = fileOf(name);
        try {
            if (file == null || !Files.deleteIfExists(file)) return "No saved preset named " + name + ".";
        } catch (IOException e) {
            return "Could not delete " + name + ": " + e.getMessage();
        }
        return null;
    }

    /** Names of the saved presets, sorted. */
    static List<String> saved() {
        List<String> out = new ArrayList<>();
        Path dir = dir();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.map(f -> f.getFileName().toString())
                    .filter(f -> f.endsWith(".properties"))
                    .map(f -> f.substring(0, f.length() - ".properties".length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** summon.* keys that are settings, not part of the summon list (they go in presets and resets). */
    private static final java.util.Set<String> SUMMON_SETTINGS = java.util.Set.of(
            "summon.radius", "summon.despawn", "summon.lifetime", "summon.exit", "summon.jade", "summon.jade.color", "summon.army",
            // The random count range is a setting too (panel "Save changes"), not part of the list.
            "summon.min", "summon.max", "summon.atleastone");

    static boolean isListKey(String key) {
        return key.startsWith("teams.") || key.startsWith("summon.") && !SUMMON_SETTINGS.contains(key);
    }

    /**
     * Access policy, the debug log and the debug recorder are security settings: they must survive every preset and
     * reset group (only /mobbattle permissions, debugLog and debugRecord change them).
     */
    private static boolean isSecurityKey(String key) {
        return FxSettings.PERMISSIONS_KEY.equals(key) || "debug".equals(key) || "debug.record".equals(key);
    }

    /** /mobbattle reset groups, in help order. */
    static final String[] GROUPS = {"effects", "cameras", "titles", "arenas", "rules", "wands", "all"};

    /** The reset group of a fx.properties key (null = none: the team and summon lists). */
    static String groupOf(String key) {
        if (isSecurityKey(key)) return null;
        if (isListKey(key)) return null;
        if (KindFx.isOverrideKey(key)) {
            // kind.<kind>.<option>: by its option.
            String opt = key.substring(key.lastIndexOf('.') + 1);
            return switch (opt) {
                case "camera", "camerastyle", "cameratime", "camerashots", "endcamera", "endcameratime", "autowatch" -> "cameras";
                case "countdown", "starttitle", "wintitle", "music" -> "titles";
                default -> "effects";
            };
        }
        if (key.startsWith("wave.") || key.startsWith("glow.") || key.startsWith("mobfx.") || key.startsWith("fx.")
                || key.startsWith("chaos.area") || key.equals("chaos.height") || key.startsWith("laser.")
                || key.startsWith("team.fx") || key.equals("wand.fx") || key.equals("wand.cleanse.color") || key.equals("wand.buff.color")
                || key.equals("wand.buff.light") || key.equals("wand.heal.light") || key.startsWith("summon.jade") || key.startsWith("army.tint")) return "effects";
        if (key.equals("fight.camera") || key.startsWith("camera.")) return "cameras";
        if (key.equals("fight.countdown") || key.startsWith("title.") || key.startsWith("wintitle.")
                || key.startsWith("battle.music")) return "titles";
        if (key.startsWith("battle.arena.") || key.startsWith("battle.timer") || key.startsWith("battle.timeup.")
                || key.startsWith("battle.judgeby.") || key.startsWith("battle.shrink") || key.startsWith("battle.scoreboard.") || key.equals("battle.tiebreak")
                || key.equals("battle.unstick") || key.equals("battle.standoff") || key.startsWith("battle.passivewin") || key.equals("battle.duelCard")) return "arenas";
        if (key.startsWith("kill.") || key.startsWith("snap.") || key.startsWith("morph.") || key.equals("effect.id")
                || key.equals("wand.debug.track")) return "wands";
        return "rules";
    }

    /**
     * /mobbattle reset &lt;group&gt;: only the settings of that group back to default (all = preset default). Returns
     * null if done, else why not.
     */
    static String reset(String group) {
        Preview pv = previewReset(group);
        if (pv.error() != null) return pv.error();
        FxSettings.applyAll(pv.props());
        return null;
    }

    /** The settings {@link #reset} would give (the settings panel loads them into its form). */
    static Preview previewReset(String group) {
        String g = group.toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Arrays.asList(GROUPS).contains(g)) return new Preview(null, "Unknown group. Groups: " + String.join(", ", GROUPS));
        if (g.equals("all")) return preview("default");
        Properties p = FxSettings.toProperties();
        Properties defaults = FxSettings.defaults();
        p.keySet().removeIf(k -> g.equals(groupOf(k.toString())) && !defaults.containsKey(k));
        for (String key : defaults.stringPropertyNames()) {
            if (g.equals(groupOf(key))) p.setProperty(key, defaults.getProperty(key));
        }
        return new Preview(p, null);
    }

    private static Path dir() {
        Path data = FxSettings.dataDirectory();
        return data == null ? null : data.resolve("presets");
    }

    private static Path fileOf(String name) {
        Path dir = dir();
        return dir == null || !validName(name) ? null : dir.resolve(name + ".properties");
    }
}
