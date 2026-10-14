package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.Message;

import java.util.List;
import java.util.Properties;

/**
 * Soft limits (2026-10-13, user: "more freedom, with performance warnings, and say what stops working past some
 * amount"): the hard caps are high on purpose, and a value past {@link Rule#warn} only gets a warning in the panel
 * ({@link ConfigPage}) and after a /mobbattle command (any fx.properties key it changed). The size limit keeps its
 * own warning ({@link Buffs#sizeWarning}).
 */
final class Limits {
    private Limits() {
    }

    /** {@code key}: exact key, or a "prefix*...suffix" pattern; text: {@code limits.<id>} with {warn} and {value}. */
    private record Rule(String prefix, String suffix, int warn, String id) {
        boolean matches(String key) {
            return suffix == null ? key.equals(prefix) : key.startsWith(prefix) && key.endsWith(suffix);
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("chaos.maxmobs", null, FxSettings.MAX_MOBS_WARN, "maxmobs"),
            new Rule("summon.max", null, FxSettings.MAX_MOBS_WARN, "summoncount"),
            new Rule("chaos.radius", null, FxSettings.RADIUS_WARN, "radius"),
            new Rule("snap.radius", null, FxSettings.RADIUS_WARN, "radius"),
            new Rule("morph.burst.radius", null, FxSettings.RADIUS_WARN, "radius"),
            new Rule("cleanse.area.radius", null, FxSettings.RADIUS_WARN, "radius"),
            new Rule("effect.area.radius", null, FxSettings.RADIUS_WARN, "radius"),
            new Rule("heal.area.radius", null, FxSettings.RADIUS_WARN, "radius"),
            new Rule("battle.arena.size", null, FxSettings.ARENA_WARN, "arena"),
            new Rule("chaos.height", null, FxSettings.HEIGHT_WARN, "height"),
            new Rule("summon.radius", null, FxSettings.SUMMON_RADIUS_WARN, "summonradius"),
            new Rule("camera.time", null, KindFx.CAMERA_TIME_WARN, "cameratime"),
            new Rule("kind.", ".cameratime", KindFx.CAMERA_TIME_WARN, "cameratime"),
            new Rule("camera.end.time", null, KindFx.END_CAMERA_TIME_WARN, "cameratime"),
            new Rule("kind.", ".endcameratime", KindFx.END_CAMERA_TIME_WARN, "cameratime"),
            // Speed buff: +300 % = x4 (players rubber-band, mobs can slip through corners).
            new Rule("buff.", ".speed", 300, "speed"));

    /** The warning for this key and value, or null (within the safe range, not a number, or no rule). */
    static Message warning(String key, String value) {
        if (key == null || value == null) return null;
        double v;
        try {
            v = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        for (Rule r : RULES) {
            if (r.matches(key) && v > r.warn) {
                return I18n.tr("limits." + r.id).param("warn", String.valueOf(r.warn)).param("value", value.trim());
            }
        }
        return null;
    }

    /** Warnings for every key whose value changed between two settings snapshots (a command's before/after). */
    static List<Message> changed(Properties before, Properties after) {
        List<Message> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String key : after.stringPropertyNames()) {
            String now = after.getProperty(key);
            if (now.equals(before.getProperty(key))) continue;
            Message m = warning(key, now);
            // One line per kind of warning (areaRadius and snapRadius together say it once).
            if (m != null && seen.add(ruleId(key))) out.add(m);
        }
        return out;
    }

    private static String ruleId(String key) {
        for (Rule r : RULES) if (r.matches(key)) return r.id;
        return key;
    }
}
