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
    /** Who sees the big titles (countdown, start, winner): owner, nearby (default: same world, 64 blocks) or world. */
    public static String titleScope = "nearby";
    public static final String[] TITLE_SCOPES = {"owner", "nearby", "world"};
    public static final double TITLE_NEARBY_BLOCKS = 64;

    /** Chaos Wand area radius in blocks (/mobbattle arearadius). */
    public static int radius = 60;
    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 430;

    /** How the Chaos Wand marks its area: glow columns, laser styles, or off (/mobbattle areastyle). */
    public static String areaStyle = "both";
    public static final String[] AREA_STYLES = {"floor", "zone", "glow", "both", "all", "off"};
    /** Area color: a team palette name (Teams.PALETTE) or "random" (/mobbattle areacolor). */
    public static String areaColor = "Red";

    /** Team look (tint/mist) only while the team's owner holds the Team Wand (/mobbattle teamlook onlywand). */
    public static boolean teamLookOnlyWand = false;

    /** Seconds of "3, 2, 1" titles before a fight starts (/mobbattle countdown). Default 0 = instant. */
    public static int countdownSeconds = 0;
    /** Battle Wand: retain and merge connected duels instead of replacing the old pair. */
    public static boolean accumulateDuels = false;
    public static final int MAX_COUNTDOWN = 10;

    /** Wand target effects: heal glow, soul rip, charm... (/mobbattle wandfx). Default: on. */
    public static boolean wandFx = true;
    /** Kill Wand: soul rip time before the mob dies, ms (/mobbattle killdelay, 0 = instant). */
    public static int killDelayMs = 500;
    public static final int MAX_KILL_DELAY_MS = 3000;
    /** Kill Wand: the disintegrated mob drops its normal loot (/mobbattle killLoot). Default: on. */
    public static boolean killLoot = true;

    /** Area only for its owner, only while holding the Chaos Wand (/mobbattle areaonlywand). Default: off. */
    public static boolean areaOnlyWand = false;

    /**
     * Per battle kind: battle mobs only fight their battle's opponents and outside NPCs can't hurt them
     * (/mobbattle battleisolation <kind|all> on|off). Default: on for every kind.
     */
    public static final java.util.Map<BattleManager.Kind, Boolean> ISOLATION = new java.util.EnumMap<>(BattleManager.Kind.class);
    /**
     * Per battle kind with allies (allvsone, teams): hits between allies hurt (/mobbattle friendlyfire).
     * Allies still never target each other. Default: on for allvsone, off for teams.
     */
    public static final java.util.Map<BattleManager.Kind, Boolean> FRIENDLY_FIRE = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Kinds where friendly fire means something (duels and royales have no allies). */
    public static final BattleManager.Kind[] ALLY_KINDS = {BattleManager.Kind.ALL_VS_ONE, BattleManager.Kind.TEAMS};

    static {
        resetBattleRules();
    }

    private static void resetBattleRules() {
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            ISOLATION.put(k, true);
        }
        FRIENDLY_FIRE.put(BattleManager.Kind.ALL_VS_ONE, true);
        FRIENDLY_FIRE.put(BattleManager.Kind.TEAMS, false);
    }

    public static boolean isolated(BattleManager.Kind kind) {
        return kind == null || ISOLATION.getOrDefault(kind, true);
    }

    public static boolean friendlyFire(BattleManager.Kind kind) {
        return kind != null && FRIENDLY_FIRE.getOrDefault(kind, false);
    }

    /** Kind by its command key ("duel", "allvsone", "royale", "teams"), or null. */
    public static BattleManager.Kind kindOf(String key) {
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            if (k.key.equalsIgnoreCase(key)) {
                return k;
            }
        }
        return null;
    }

    private static String rules(java.util.Map<BattleManager.Kind, Boolean> map) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        map.forEach((k, v) -> parts.add(k.key + " " + onOff(v)));
        return String.join(", ", parts);
    }
    /** Height in blocks of the pillars (/mobbattle areaheight). */
    public static int pillarHeight = 30;
    public static final int MIN_HEIGHT = 3;
    public static final int MAX_HEIGHT = 128;

    /** Summon area radius in blocks (/mobbattle summonradius); 0 = auto, grows with how many mobs. */
    public static int summonRadius = 0;
    public static final int MAX_SUMMON_RADIUS = 64;

    /** Max mobs one Chaos Wand click affects (/mobbattle maxmobs). Mostly limited by the game's own AI cost. */
    public static int maxMobs = 200;
    public static final int MAX_MOBS_LIMIT = 200;

    /** Team look: both, tint, mist or off (/mobbattle teamlook). */
    public static String teamFx = "tint";
    /** Seconds the team look stays after a mob joins a team; 0 = while it is in the team (/mobbattle teamlook time). */
    public static int teamFxSeconds = 0;
    public static final int TEAM_FX_MAX_SECONDS = 600;

    /** Effect ID the Effect Wand gives (/mobbattle wandeffect). */
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
            // GoblinBreach is not offered: the client draws it tiny at the top (or not at all) on 0.7.0-pre.
            if (style != EventTitleStyle.GoblinBreach && style.name().equalsIgnoreCase(name)) {
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
            String scope = p.getProperty("title.scope", titleScope).toLowerCase();
            titleScope = java.util.Arrays.asList(TITLE_SCOPES).contains(scope) ? scope : "nearby";
            accumulateDuels = "accumulate".equalsIgnoreCase(p.getProperty("duel.mode", "exclusive"));
            if (!RANDOM.equalsIgnoreCase(titleStyle) && parseStyle(titleStyle) == null) {
                titleStyle = "Major";
            }
            try {
                maxMobs = clampMaxMobs(Integer.parseInt(p.getProperty("chaos.maxmobs", String.valueOf(maxMobs))));
                summonRadius = Math.max(0, Math.min(MAX_SUMMON_RADIUS,
                        Integer.parseInt(p.getProperty("summon.radius", String.valueOf(summonRadius)))));
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
            teamLookOnlyWand = Boolean.parseBoolean(p.getProperty("team.fx.onlywand", String.valueOf(teamLookOnlyWand)));
            try {
                countdownSeconds = Math.max(0, Math.min(MAX_COUNTDOWN,
                        Integer.parseInt(p.getProperty("fight.countdown", String.valueOf(countdownSeconds)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            wandFx = Boolean.parseBoolean(p.getProperty("wand.fx", String.valueOf(wandFx)));
            try {
                killDelayMs = Math.max(0, Math.min(MAX_KILL_DELAY_MS,
                        Integer.parseInt(p.getProperty("kill.delay.millis", String.valueOf(killDelayMs)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            killLoot = Boolean.parseBoolean(p.getProperty("kill.loot", String.valueOf(killLoot)));
            areaOnlyWand = Boolean.parseBoolean(p.getProperty("chaos.area.onlywand", String.valueOf(areaOnlyWand)));
            resetBattleRules();
            // Older saves have one global value: it becomes the value of every kind.
            String oldIso = p.getProperty("battle.isolation");
            String oldFf = p.getProperty("battle.friendlyfire");
            for (BattleManager.Kind k : BattleManager.Kind.values()) {
                String v = p.getProperty("battle.isolation." + k.key, oldIso);
                if (v != null) ISOLATION.put(k, Boolean.parseBoolean(v));
            }
            for (BattleManager.Kind k : ALLY_KINDS) {
                String v = p.getProperty("battle.friendlyfire." + k.key, oldFf);
                if (v != null) FRIENDLY_FIRE.put(k, Boolean.parseBoolean(v));
            }
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

    /** Synchronized: a command thread and a world thread (Summon Wand) can save at the same time. */
    public static synchronized void save() {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("laser.enabled", String.valueOf(laserEnabled));
        p.setProperty("laser.color", laserColor);
        p.setProperty("title.enabled", String.valueOf(titleEnabled));
        p.setProperty("wintitle.enabled", String.valueOf(winTitleEnabled));
        p.setProperty("title.style", titleStyle);
        p.setProperty("title.scope", titleScope);
        p.setProperty("duel.mode", accumulateDuels ? "accumulate" : "exclusive");
        p.setProperty("chaos.radius", String.valueOf(radius));
        p.setProperty("chaos.area", areaStyle);
        p.setProperty("chaos.area.color", areaColor);
        p.setProperty("team.fx.onlywand", String.valueOf(teamLookOnlyWand));
        p.setProperty("fight.countdown", String.valueOf(countdownSeconds));
        p.setProperty("wand.fx", String.valueOf(wandFx));
        p.setProperty("kill.delay.millis", String.valueOf(killDelayMs));
        p.setProperty("kill.loot", String.valueOf(killLoot));
        p.setProperty("chaos.area.onlywand", String.valueOf(areaOnlyWand));
        ISOLATION.forEach((k, v) -> p.setProperty("battle.isolation." + k.key, String.valueOf(v)));
        FRIENDLY_FIRE.forEach((k, v) -> p.setProperty("battle.friendlyfire." + k.key, String.valueOf(v)));
        p.setProperty("effect.id", effect);
        Teams.save(p);
        SummonList.save(p);
        p.setProperty("team.fx", teamFx);
        p.setProperty("team.fx.seconds", String.valueOf(teamFxSeconds));
        p.setProperty("chaos.maxmobs", String.valueOf(maxMobs));
        p.setProperty("summon.radius", String.valueOf(summonRadius));
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

    /** Palette name of the area color; "random" picks one each time. */
    public static String pickAreaColorName() {
        if (RANDOM.equals(areaColor)) {
            String[] names = Teams.PALETTE.keySet().toArray(new String[0]);
            return names[ThreadLocalRandom.current().nextInt(names.length)];
        }
        return Teams.PALETTE.containsKey(areaColor) ? areaColor : "Red";
    }

    public static int clampRadius(int value) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, value));
    }

    /** Settings as short labelled lines, one per group (see {@link #groupOf}). */
    public static String[] describeLines() {
        return new String[] {
                "Titles: countdown " + countdownSeconds + " s, startTitle " + onOff(titleEnabled) + ", winTitle " + onOff(winTitleEnabled) + ", titleStyle " + titleStyle + ", titleScope " + titleScope,
                "Fighter laser: fightLaser " + onOff(laserEnabled) + ", fightLaserColor " + laserColor,
                "Battles: duelMode " + (accumulateDuels ? "accumulate" : "exclusive")
                        + " | battleIsolation " + rules(ISOLATION) + " | friendlyFire " + rules(FRIENDLY_FIRE),
                "Chaos area: areaStyle " + areaStyle + ", areaColor " + areaColor.toLowerCase() + ", areaRadius " + radius
                        + ", areaHeight " + pillarHeight + ", areaOnlyWand " + onOff(areaOnlyWand) + ", maxMobs " + maxMobs
                        + ", summonRadius " + (summonRadius > 0 ? summonRadius : "auto"),
                "Team look: teamLook " + teamFx + ", time " + (teamFxSeconds > 0 ? teamFxSeconds + " s" : "infinite")
                        + ", onlyWand " + onOff(teamLookOnlyWand),
                "Wands: wandEffect " + effect + ", wandFx " + onOff(wandFx) + ", killDelay " + (killDelayMs / 1000f) + " s, killLoot " + onOff(killLoot),
        };
    }

    /** Which describeLines() line a setting key belongs to. */
    public static int groupOf(String key) {
        return switch (key) {
            case "title", "wintitle", "style", "countdown", "titlescope" -> 0;
            case "laser", "color" -> 1;
            case "isolation", "battleisolation", "friendlyfire", "duelmode" -> 2;
            case "radius", "area", "areacolor", "height", "areaonlywand", "maxmobs", "summonradius" -> 3;
            case "teamfx" -> 4;
            default -> 5;
        };
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
