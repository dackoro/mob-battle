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
 *   <li>effects: countdown 3, 2 min timer (most health wins), titles, music, lasers, runes, area, scoreboards;
 *       no cameras.</li>
 *   <li>debug: everything visible for testing (area floor + zone + glow, lasers, runes, arenas drawn, team
 *       look, debug log), no cinematics.</li>
 *   <li>cinematic: countdown and full camera work (western duel, drone royale, team sweep, focus, end
 *       camera with kill count), music and titles, no lasers, runes or area drawings.</li>
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
            "laser.enabled", "chaos.area", "fx.runes", "debug", "fx.crown"
    };

    /** Prefixes of presentation keys a built-in preset also puts back first (waves, glow, mob looks). */
    private static final String[] PRESENTATION_PREFIXES = {"wave.", "glow.", "mobfx."};

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
                // Fights with all the show but no cameras: countdown, timer, titles, music, lasers, runes, area.
                m.put("fight.countdown", "3");
                m.put("fight.camera", "false");
                m.put("camera.end", "off");
                m.put("title.enabled", "true");
                m.put("wintitle.enabled", "true");
                m.put("battle.music", "true");
                m.put("laser.enabled", "true");
                m.put("chaos.area", "discBorder");
                m.put("fx.runes", "true");
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    m.put("battle.timer." + k.key, "120");
                    m.put("battle.timeup." + k.key, "health");
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
                m.put("debug", "true");
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
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
                m.put("chaos.area", "off");
                m.put("fx.runes", "false");
                // No waves or border glow in the shots; the mob looks and the crown stay.
                for (ActionFx.Action a : ActionFx.Action.values()) {
                    m.put("wave." + a.key, ActionFx.OFF);
                    m.put("glow." + a.key, ActionFx.OFF);
                }
                m.put(KindFx.propKey(BattleManager.Kind.ROYALE, KindFx.Opt.CAMERA_STYLE), "drone");
                m.put(KindFx.propKey(BattleManager.Kind.ROYALE, KindFx.Opt.END_CAMERA), "lasthit");
                m.put(KindFx.propKey(BattleManager.Kind.ALL_VS_ONE, KindFx.Opt.END_CAMERA), "lasthit");
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    // Arenas keep working, but are never drawn.
                    Arena.Mode mode = FxSettings.arenaMode(k);
                    m.put("battle.arena." + k.key, mode == Arena.Mode.OFF ? "off" : "wall");
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

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
    }

    /**
     * Applies a built-in or saved preset and saves the settings. Returns null if done, else why not.
     */
    static String apply(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        Properties p = FxSettings.toProperties();
        Properties defaults = FxSettings.defaults();
        if ("default".equals(lower)) {
            // Everything back to default, except the team and summon lists.
            for (String key : defaults.stringPropertyNames()) {
                if (!isListKey(key)) p.setProperty(key, defaults.getProperty(key));
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
            if (file == null || !Files.exists(file)) return "No preset named " + name + ". See /mobbattle preset list.";
            Properties saved = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                saved.load(in);
            } catch (IOException e) {
                return "Could not read " + file.getFileName() + ": " + e.getMessage();
            }
            // A saved preset has its own per-kind overrides: drop the current ones first.
            p.keySet().removeIf(k -> KindFx.isOverrideKey(k.toString()));
            for (String key : saved.stringPropertyNames()) {
                if (!isListKey(key)) p.setProperty(key, saved.getProperty(key));
            }
        }
        FxSettings.apply(p);
        FxSettings.save();
        if (!FxSettings.battleMusic) BattleMusic.stopAll();
        return null;
    }

    /** Saves the current settings (not the team and summon lists) as a preset. Returns null if done, else why not. */
    static String save(String name) {
        if (!validName(name)) return "Preset names use letters, digits, - and _ (up to 32).";
        if (isBuiltIn(name)) return name + " is a built-in preset: pick another name.";
        Path file = fileOf(name);
        if (file == null) return "Settings are not loaded yet.";
        Properties p = FxSettings.toProperties();
        p.keySet().removeIf(k -> isListKey(k.toString()));
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

    private static boolean isListKey(String key) {
        // summon.radius is a setting, not part of the summon list.
        return key.startsWith("teams.") || key.startsWith("summon.") && !key.equals("summon.radius");
    }

    /** /mobbattle reset groups, in help order. */
    static final String[] GROUPS = {"effects", "cameras", "titles", "arenas", "rules", "wands", "all"};

    /** The reset group of a fx.properties key (null = none: the team and summon lists). */
    static String groupOf(String key) {
        if (isListKey(key)) return null;
        if (KindFx.isOverrideKey(key)) {
            // kind.<kind>.<option>: by its option.
            String opt = key.substring(key.lastIndexOf('.') + 1);
            return switch (opt) {
                case "camera", "camerastyle", "cameratime", "camerashots", "endcamera", "endcameratime" -> "cameras";
                case "countdown", "starttitle", "wintitle", "music" -> "titles";
                default -> "effects";
            };
        }
        if (key.startsWith("wave.") || key.startsWith("glow.") || key.startsWith("mobfx.") || key.startsWith("fx.")
                || key.startsWith("chaos.area") || key.equals("chaos.height") || key.startsWith("laser.")
                || key.startsWith("team.fx") || key.equals("wand.fx") || key.equals("wand.cleanse.color") || key.equals("wand.buff.color") || key.equals("debug")) return "effects";
        if (key.equals("fight.camera") || key.startsWith("camera.")) return "cameras";
        if (key.equals("fight.countdown") || key.startsWith("title.") || key.startsWith("wintitle.")
                || key.startsWith("battle.music")) return "titles";
        if (key.startsWith("battle.arena.") || key.startsWith("battle.timer") || key.startsWith("battle.timeup.")
                || key.startsWith("battle.shrink") || key.startsWith("battle.scoreboard.") || key.equals("battle.tiebreak")
                || key.equals("battle.unstick") || key.equals("battle.duelCard")) return "arenas";
        if (key.startsWith("kill.") || key.startsWith("snap.") || key.startsWith("morph.") || key.equals("effect.id")) return "wands";
        return "rules";
    }

    /**
     * /mobbattle reset &lt;group&gt;: only the settings of that group back to default (all = preset default). Returns
     * null if done, else why not.
     */
    static String reset(String group) {
        String g = group.toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Arrays.asList(GROUPS).contains(g)) return "Unknown group. Groups: " + String.join(", ", GROUPS);
        if (g.equals("all")) return apply("default");
        Properties p = FxSettings.toProperties();
        Properties defaults = FxSettings.defaults();
        p.keySet().removeIf(k -> g.equals(groupOf(k.toString())) && !defaults.containsKey(k));
        for (String key : defaults.stringPropertyNames()) {
            if (g.equals(groupOf(key))) p.setProperty(key, defaults.getProperty(key));
        }
        FxSettings.apply(p);
        FxSettings.save();
        if (!FxSettings.battleMusic) BattleMusic.stopAll();
        return null;
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
