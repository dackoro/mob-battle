package dev.dackoro.mobbattle;

import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fight start effect settings, changed in game with /mobbattle and saved to
 * &lt;plugin data dir&gt;/fx.properties.
 */
public final class FxSettings {

    public static final String RANDOM = "random";

    /** Vivid colors used when the laser color is "random". */
    private static final String[] RANDOM_COLORS = {
            "#FF4B3E", "#2EFFD3", "#FFD23E", "#B44BFF", "#FF8A00", "#4BFF6A", "#FF4BD8", "#4B9BFF"
    };

    /** Laser between fighters at fight start (off by default; the Chaos Wand area has its own setting). */
    public static boolean laserEnabled = false;
    /** A hex color like #FF4B3E, or "random". */
    public static String laserColor = "#FF4B3E";
    public static boolean titleEnabled = true;
    /** "X WINS!" title when a duel or group battle ends. */
    public static boolean winTitleEnabled = true;
    /** An EventTitleStyle name (Default, Major, GoblinBreach, VoidEviction), or "random". */
    public static String titleStyle = "Major";

    /** Chaos Wand area radius in blocks (/mobbattle radius). */
    public static int radius = 430;
    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 430;

    /** How the Chaos Wand marks its area: glow columns, laser styles, or off (/mobbattle area). */
    public static String areaStyle = "both";
    public static final String[] AREA_STYLES = {"floor", "zone", "glow", "both", "off"};
    /** Area color: a team palette name (Teams.PALETTE) or "random" (/mobbattle areacolor). */
    public static String areaColor = "Red";

    /** Battle mobs only fight their battle's opponents; outside NPCs can't hurt them (/mobbattle isolation). */
    public static boolean battleIsolation = true;
    /** Height in blocks of the pillars (/mobbattle height). */
    public static int pillarHeight = 30;
    public static final int MIN_HEIGHT = 3;
    public static final int MAX_HEIGHT = 128;

    /** Max mobs one Chaos Wand click affects (/mobbattle maxmobs). Mostly limited by the game's own AI cost. */
    public static int maxMobs = 40;
    public static final int MAX_MOBS_LIMIT = 200;

    /** Team look: both, tint, mist or off (/mobbattle teamfx). */
    public static String teamFx = "both";
    /** Seconds the team look stays after a mob joins a team; 0 = while it is in the team (/mobbattle teamfx time). */
    public static int teamFxSeconds = 0;
    public static final int TEAM_FX_MAX_SECONDS = 600;

    /** Effect ID the Effect Wand gives (/mobbattle effect). */
    public static String effect = "Burn";

    private static Path file;

    private FxSettings() {
    }

    public static String pickLaserColor() {
        if (RANDOM.equalsIgnoreCase(laserColor)) {
            return RANDOM_COLORS[ThreadLocalRandom.current().nextInt(RANDOM_COLORS.length)];
        }
        return laserColor;
    }

    public static EventTitleStyle pickTitleStyle() {
        EventTitleStyle[] all = EventTitleStyle.VALUES;
        if (RANDOM.equalsIgnoreCase(titleStyle)) {
            // GoblinBreach is skipped: on 0.7.0-pre.5.1 the client shows nothing for it (even /eventtitle).
            EventTitleStyle style;
            do {
                style = all[ThreadLocalRandom.current().nextInt(all.length)];
            } while (style == EventTitleStyle.GoblinBreach && all.length > 1);
            return style;
        }
        EventTitleStyle style = parseStyle(titleStyle);
        return style != null ? style : EventTitleStyle.Major;
    }

    /** Case-insensitive style lookup; null if unknown. */
    public static EventTitleStyle parseStyle(String name) {
        for (EventTitleStyle style : EventTitleStyle.VALUES) {
            if (style.name().equalsIgnoreCase(name)) {
                return style;
            }
        }
        return null;
    }

    public static boolean isHexColor(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}");
    }

    public static void load(Path dataDirectory) {
        file = dataDirectory.resolve("fx.properties");
        if (!Files.exists(file)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
            laserEnabled = Boolean.parseBoolean(p.getProperty("laser.enabled", String.valueOf(laserEnabled)));
            String savedColor = p.getProperty("laser.color", laserColor);
            if (RANDOM.equalsIgnoreCase(savedColor) || isHexColor(savedColor)) {
                laserColor = savedColor;
            }
            titleEnabled = Boolean.parseBoolean(p.getProperty("title.enabled", String.valueOf(titleEnabled)));
            winTitleEnabled = Boolean.parseBoolean(p.getProperty("wintitle.enabled", String.valueOf(winTitleEnabled)));
            titleStyle = p.getProperty("title.style", titleStyle);
            try {
                maxMobs = clampMaxMobs(Integer.parseInt(p.getProperty("chaos.maxmobs", String.valueOf(maxMobs))));
            } catch (NumberFormatException ignored) {
            }
            effect = p.getProperty("effect.id", effect);
            Teams.load(p);
            SummonList.load(p);
            String fx = p.getProperty("team.fx", teamFx);
            teamFx = TeamFx.isMode(fx) ? fx.toLowerCase() : teamFx;
            try {
                teamFxSeconds = Math.max(0, Math.min(TEAM_FX_MAX_SECONDS,
                        Integer.parseInt(p.getProperty("team.fx.seconds", String.valueOf(teamFxSeconds)))));
            } catch (NumberFormatException ignored) {
            }
            areaStyle = parseAreaStyle(p.getProperty("chaos.area", areaStyle));
            String savedArea = p.getProperty("chaos.area.color", areaColor);
            areaColor = parseAreaColor(savedArea) != null ? parseAreaColor(savedArea) : areaColor;
            battleIsolation = Boolean.parseBoolean(p.getProperty("battle.isolation", String.valueOf(battleIsolation)));
            try {
                pillarHeight = clampHeight(Integer.parseInt(p.getProperty("chaos.height", String.valueOf(pillarHeight))));
            } catch (NumberFormatException ignored) {
            }
            try {
                radius = clampRadius(Integer.parseInt(p.getProperty("chaos.radius", String.valueOf(radius))));
            } catch (NumberFormatException ignored) {
            }
        } catch (IOException e) {
            MobBattlePlugin.log("Could not read " + file + ": " + e);
        }
    }

    public static void save() {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("laser.enabled", String.valueOf(laserEnabled));
        p.setProperty("laser.color", laserColor);
        p.setProperty("title.enabled", String.valueOf(titleEnabled));
        p.setProperty("wintitle.enabled", String.valueOf(winTitleEnabled));
        p.setProperty("title.style", titleStyle);
        p.setProperty("chaos.radius", String.valueOf(radius));
        p.setProperty("chaos.area", areaStyle);
        p.setProperty("chaos.area.color", areaColor);
        p.setProperty("battle.isolation", String.valueOf(battleIsolation));
        p.setProperty("effect.id", effect);
        Teams.save(p);
        SummonList.save(p);
        p.setProperty("team.fx", teamFx);
        p.setProperty("team.fx.seconds", String.valueOf(teamFxSeconds));
        p.setProperty("chaos.maxmobs", String.valueOf(maxMobs));
        p.setProperty("chaos.height", String.valueOf(pillarHeight));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Mob Battle fight start effects (/mobbattle)");
            }
        } catch (IOException e) {
            MobBattlePlugin.log("Could not save " + file + ": " + e);
        }
    }

    public static int clampMaxMobs(int value) {
        return Math.max(2, Math.min(MAX_MOBS_LIMIT, value));
    }

    public static int clampHeight(int value) {
        return Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, value));
    }

    /** Valid area style (lowercase), or "both" if unknown (old laser styles were removed). */
    public static String parseAreaStyle(String value) {
        for (String style : AREA_STYLES) {
            if (style.equalsIgnoreCase(value)) {
                return style;
            }
        }
        return "both";
    }

    public static boolean isAreaStyle(String value) {
        for (String style : AREA_STYLES) {
            if (style.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** Palette name as written in Teams.PALETTE, "random", or null. */
    public static String parseAreaColor(String value) {
        if (RANDOM.equalsIgnoreCase(value)) {
            return RANDOM;
        }
        for (String name : Teams.PALETTE.keySet()) {
            if (name.equalsIgnoreCase(value)) {
                return name;
            }
        }
        return null;
    }

    /** Hex of the area color; "random" picks a palette color each time. */
    public static String pickAreaColor() {
        if (RANDOM.equals(areaColor)) {
            var colors = new java.util.ArrayList<>(Teams.PALETTE.values());
            return colors.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(colors.size()));
        }
        return Teams.PALETTE.getOrDefault(areaColor, "#FF4B3E");
    }

    public static int clampRadius(int value) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, value));
    }

    public static String describe() {
        return "laser " + (laserEnabled ? "on" : "off") + ", color " + laserColor
                + " | title " + (titleEnabled ? "on" : "off") + ", style " + titleStyle
                + " | win title " + (winTitleEnabled ? "on" : "off")
                + " | isolation " + (battleIsolation ? "on" : "off")
                + " | chaos radius " + radius + ", area " + areaStyle + " " + areaColor.toLowerCase() + ", height " + pillarHeight + ", max mobs " + maxMobs + " | effect " + effect + " | team fx " + teamFx + (teamFxSeconds > 0 ? " for " + teamFxSeconds + " s" : " (infinite: whole battle)");
    }
}
