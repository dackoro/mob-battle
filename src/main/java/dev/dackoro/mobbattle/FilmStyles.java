package dev.dackoro.mobbattle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Battle film styles (2026-10-16, playtest: presets for the film shots, "one so the battle shows all the time and less of the
 * player, one very close so attacks and mobs are seen all the time, a more cinematic one with open shots, plus the
 * default as it is now"). A style only writes the film keys (camera.film.*: player and far shares, shot length and
 * variation, pace, transition, every shot weight); everything else stays. The values are a starting point: each one
 * can still be changed by hand after.
 */
final class FilmStyles {

    private FilmStyles() {
    }

    static final String[] NAMES = {"default", "battle", "action", "cinematic"};

    private static final String WEIGHT = "camera.film.weight.";

    /** The film keys a style sets, as properties; null for an unknown style. */
    static Map<String, String> of(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        Map<String, String> out = new LinkedHashMap<>();
        switch (n) {
            case "default" -> {
                Properties d = FxSettings.defaults();
                for (String key : d.stringPropertyNames()) {
                    if (isFilmKey(key)) out.put(key, d.getProperty(key));
                }
                // Defaults captured before the weights were saved as keys: fill any missing shot from the table.
                for (String w : FilmCamera.SHOT_WORDS) {
                    out.putIfAbsent(WEIGHT + w, String.valueOf(FxSettings.FILM_DEFAULT_WEIGHTS.getOrDefault(w, 100)));
                }
            }
            // The battle in view all the time: few player shots, few far ones, the moving and close shots up.
            case "battle" -> {
                base(out, 5, 20, 7, 25, 100, "cut");
                weights(out, "wide", 110, "track", 130, "arc", 120, "pushin", 110, "closeup", 120, "landscape", 15,
                        "playerback", 30, "playerclose", 0, "zenith", 30, "spiral", 70, "telephoto", 70, "descent", 40,
                        "groundrise", 30, "profile", 130);
            }
            // Close on the fighters: their attacks and faces fill the screen, no player, no landscape.
            case "action" -> {
                base(out, 0, 5, 5, 20, 110, "cut");
                weights(out, "wide", 40, "track", 150, "arc", 110, "pushin", 160, "closeup", 260, "landscape", 0,
                        "playerback", 0, "playerclose", 0, "zenith", 0, "spiral", 60, "telephoto", 90, "descent", 15,
                        "groundrise", 60, "profile", 230);
            }
            // Open, slow and gliding: wide shots, cranes and sweeps, longer shots, some of the player watching.
            case "cinematic" -> {
                base(out, 25, 50, 11, 30, 75, "glide");
                weights(out, "wide", 150, "track", 70, "arc", 140, "pushin", 70, "closeup", 60, "landscape", 120,
                        "playerback", 100, "playerclose", 60, "zenith", 80, "spiral", 120, "telephoto", 100, "descent", 140,
                        "groundrise", 100, "profile", 80);
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    /** The current settings with the style's film keys on top (for the panel draft or a direct apply). */
    static Properties over(Properties settings, String name) {
        Map<String, String> style = of(name);
        if (style == null) return null;
        Properties p = new Properties();
        p.putAll(settings);
        p.putAll(style);
        return p;
    }

    /** Sets the style now and saves; false for an unknown name. */
    static boolean apply(String name) {
        Properties p = over(FxSettings.toProperties(), name);
        if (p == null) return false;
        FxSettings.applyAll(p);
        return true;
    }

    /** Which style the current settings match, or null when they were changed by hand. */
    static String current() {
        Properties now = FxSettings.toProperties();
        for (String name : NAMES) {
            Map<String, String> style = of(name);
            boolean same = true;
            for (Map.Entry<String, String> e : style.entrySet()) {
                if (!e.getValue().equals(now.getProperty(e.getKey()))) {
                    same = false;
                    break;
                }
            }
            if (same) return name;
        }
        return null;
    }

    static boolean isFilmKey(String key) {
        return key.startsWith(WEIGHT) || key.equals("camera.film.player") || key.equals("camera.film.wide")
                || key.equals("camera.film.shot") || key.equals("camera.film.variation") || key.equals("camera.film.pace")
                || key.equals("camera.film.transition");
    }

    private static void base(Map<String, String> out, int player, int wide, int shot, int variation, int pace, String transition) {
        out.put("camera.film.player", String.valueOf(player));
        out.put("camera.film.wide", String.valueOf(wide));
        out.put("camera.film.shot", String.valueOf(shot));
        out.put("camera.film.variation", String.valueOf(variation));
        out.put("camera.film.pace", String.valueOf(pace));
        out.put("camera.film.transition", transition);
    }

    /** Pairs of shot word and percent; any shot not listed keeps 100. */
    private static void weights(Map<String, String> out, Object... pairs) {
        for (String w : FilmCamera.SHOT_WORDS) out.put(WEIGHT + w, "100");
        for (int i = 0; i + 1 < pairs.length; i += 2) out.put(WEIGHT + pairs[i], String.valueOf(pairs[i + 1]));
    }
}
