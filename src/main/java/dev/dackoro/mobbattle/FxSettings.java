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
    /** Wide camera shot of the fighters during a Chaos Wand countdown (/mobbattle camera). */
    public static boolean fightCamera = true;
    /** Battle music from the countdown to the end of the battle (/mobbattle music). */
    public static boolean battleMusic = true;
    public static String musicTrack = BattleMusic.DEFAULT_TRACK;
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
    /** Kill Wand: players disintegrate too, then die normally (/mobbattle killPlayers). Default: on. */
    public static boolean killPlayers = true;

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
    /**
     * Per battle kind: the arena (/mobbattle arena &lt;kind|all&gt; on|show|wall|off). show: the area stays drawn
     * for the whole fight; wall: fighters can't leave it. Default: on for allvsone and royale, off for duel, teams.
     */
    public static final java.util.Map<BattleManager.Kind, Arena.Mode> ARENA = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Per battle kind: fight time limit in seconds with the timer box, 0 = none (/mobbattle timer). Default: 0. */
    public static final java.util.Map<BattleManager.Kind, Integer> TIMER = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Per battle kind: what happens at 0 (/mobbattle timeUp draw|kill|continue|health). Default: draw. */
    public static final java.util.Map<BattleManager.Kind, Arena.TimeUp> TIME_UP = new java.util.EnumMap<>(BattleManager.Kind.class);
    /**
     * Per battle kind: the arena closes in, like a battle royale zone (/mobbattle arenaShrink). Its wall pushes
     * everyone toward the center until arenaShrinkTo, over the timer (or arenaShrinkTime with no timer).
     * Default: on for royale only.
     */
    public static final java.util.Map<BattleManager.Kind, Boolean> SHRINK = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Final radius of a closing arena in blocks (/mobbattle arenaShrinkTo). */
    public static int shrinkTo = 15;
    /** How long a closing arena takes when its battle has no timer, in seconds (/mobbattle arenaShrinkTime). */
    public static int shrinkSeconds = 120;
    public static final int MIN_SHRINK_TO = 4;
    /** Battle mobs stuck on another level than their opponent are brought next to it (/mobbattle unstick). Default: on. */
    public static boolean unstick = true;

    public static boolean shrinks(BattleManager.Kind kind) {
        return kind != null && SHRINK.getOrDefault(kind, false);
    }

    /** Arena radius of duels and team battles (/mobbattle arenaSize); 0 = auto, around the fighters. */
    public static int arenaSize = 0;
    /** /mobbattle debug: battle decisions (config, arena, health scores, winner) in the server log. */
    public static boolean debug = false;
    /** timeUp health/judge when even on health: "damage" (default), "random" or "draw" (/mobbattle tieBreak). */
    public static String tieBreak = "damage";
    /** Snap Wand and /mobbattle snap: "global" (half of everyone, default) or "team" (half of each side). */
    public static String snapMode = "global";
    /** Snap: share of the fighters that turn to dust, 1-100 % (/mobbattle snapPercent). */
    public static int snapPercent = 50;
    /** Snap: seconds over which they turn to dust, 1-30 (/mobbattle snapTime). */
    public static int snapSeconds = 5;
    public static final int MAX_SNAP_SECONDS = 30;
    /** Snap without battles: reach in blocks (/mobbattle snapRadius), on its own: not tied to areaRadius. */
    public static final int DEFAULT_SNAP_RADIUS = 30;
    public static int snapRadius = DEFAULT_SNAP_RADIUS;
    /** Timer box: "custom" (TimerHud, default) or "portal" (the game's portal box) (/mobbattle timerStyle). */
    public static String timerStyle = "custom";
    public static final int MIN_ARENA = 8;
    public static final int MAX_ARENA = 128;
    public static final int MAX_TIMER = 3600;

    public static Arena.Mode arenaMode(BattleManager.Kind kind) {
        return kind == null ? Arena.Mode.OFF : ARENA.getOrDefault(kind, Arena.Mode.OFF);
    }

    public static int timerSeconds(BattleManager.Kind kind) {
        return kind == null ? 0 : TIMER.getOrDefault(kind, 0);
    }

    public static Arena.TimeUp timeUp(BattleManager.Kind kind) {
        return kind == null ? Arena.TimeUp.DRAW : TIME_UP.getOrDefault(kind, Arena.TimeUp.DRAW);
    }

    /** Kinds where friendly fire means something (duels and royales have no allies). */
    public static final BattleManager.Kind[] ALLY_KINDS = {BattleManager.Kind.ALL_VS_ONE, BattleManager.Kind.TEAMS};
    /**
     * Per battle kind: players can fight in it (/mobbattle playerFights). Default: off for every kind.
     * Duel: Battle Wand while crouching; allvsone: /mobbattle chaos me; teams: /mobbattle teams join.
     */
    public static final java.util.Map<BattleManager.Kind, Boolean> PLAYER_FIGHTS = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Kinds a player can fight in (every kind). */
    public static final BattleManager.Kind[] PLAYER_KINDS = BattleManager.Kind.values();
    /**
     * Enemy players of the same battle can hurt each other even where the world's PvP is off (/mobbattle
     * battlePvp). Default: off (the world's rule).
     */
    public static boolean battlePvp = false;

    public static boolean playerFights(BattleManager.Kind kind) {
        return kind != null && PLAYER_FIGHTS.getOrDefault(kind, false);
    }

    static {
        resetBattleRules();
    }

    private static void resetBattleRules() {
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            ISOLATION.put(k, true);
        }
        FRIENDLY_FIRE.put(BattleManager.Kind.ALL_VS_ONE, true);
        FRIENDLY_FIRE.put(BattleManager.Kind.TEAMS, false);
        for (BattleManager.Kind k : PLAYER_KINDS) {
            PLAYER_FIGHTS.put(k, false);
        }
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            boolean chaos = k == BattleManager.Kind.ALL_VS_ONE || k == BattleManager.Kind.ROYALE;
            ARENA.put(k, chaos ? Arena.Mode.ON : Arena.Mode.OFF);
            TIMER.put(k, 0);
            TIME_UP.put(k, Arena.TimeUp.DRAW);
            SHRINK.put(k, k == BattleManager.Kind.ROYALE);
        }
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

    private static <V> String kinds(java.util.Map<BattleManager.Kind, V> map, java.util.function.Function<V, String> text) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        map.forEach((k, v) -> parts.add(k.key + " " + text.apply(v)));
        return String.join(", ", parts);
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
            return all[ThreadLocalRandom.current().nextInt(all.length)];
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
            fightCamera = Boolean.parseBoolean(p.getProperty("fight.camera", String.valueOf(fightCamera)));
            battleMusic = Boolean.parseBoolean(p.getProperty("battle.music", String.valueOf(battleMusic)));
            musicTrack = p.getProperty("battle.music.track", musicTrack);
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
            debug = Boolean.parseBoolean(p.getProperty("debug", String.valueOf(debug)));
            try {
                killDelayMs = Math.max(0, Math.min(MAX_KILL_DELAY_MS,
                        Integer.parseInt(p.getProperty("kill.delay.millis", String.valueOf(killDelayMs)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            killLoot = Boolean.parseBoolean(p.getProperty("kill.loot", String.valueOf(killLoot)));
            killPlayers = Boolean.parseBoolean(p.getProperty("kill.players", String.valueOf(killPlayers)));
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
            for (BattleManager.Kind k : PLAYER_KINDS) {
                String v = p.getProperty("battle.players." + k.key);
                if (v != null) PLAYER_FIGHTS.put(k, Boolean.parseBoolean(v));
            }
            battlePvp = Boolean.parseBoolean(p.getProperty("battle.pvp", String.valueOf(battlePvp)));
            unstick = Boolean.parseBoolean(p.getProperty("battle.unstick", String.valueOf(unstick)));
            for (BattleManager.Kind k : BattleManager.Kind.values()) {
                try {
                    String v = p.getProperty("battle.arena." + k.key);
                    if (v != null) ARENA.put(k, Arena.Mode.valueOf(v.toUpperCase(java.util.Locale.ROOT)));
                    v = p.getProperty("battle.timeup." + k.key);
                    if (v != null) TIME_UP.put(k, Arena.TimeUp.valueOf(v.toUpperCase(java.util.Locale.ROOT)));
                    v = p.getProperty("battle.timer." + k.key);
                    if (v != null) TIMER.put(k, Math.max(0, Math.min(MAX_TIMER, Integer.parseInt(v))));
                    v = p.getProperty("battle.shrink." + k.key);
                    if (v != null) SHRINK.put(k, Boolean.parseBoolean(v));
                } catch (IllegalArgumentException ignored) {
                    // keep the default
                }
            }
            String style = p.getProperty("battle.timer.style", timerStyle);
            timerStyle = "portal".equalsIgnoreCase(style) ? "portal" : "custom";
            String tb = p.getProperty("battle.tiebreak", tieBreak).toLowerCase(java.util.Locale.ROOT);
            tieBreak = tb.equals("random") || tb.equals("draw") || tb.equals("kills") ? tb : "damage";
            snapMode = "team".equalsIgnoreCase(p.getProperty("snap.mode", snapMode)) ? "team" : "global";
            try {
                snapPercent = Math.max(1, Math.min(100, Integer.parseInt(p.getProperty("snap.percent", String.valueOf(snapPercent)))));
                snapSeconds = Math.max(1, Math.min(MAX_SNAP_SECONDS, Integer.parseInt(p.getProperty("snap.time", String.valueOf(snapSeconds)))));
                int r = Integer.parseInt(p.getProperty("snap.radius", String.valueOf(snapRadius)));
                // Older saves: 0 meant "auto = areaRadius"; the snap now has its own reach.
                snapRadius = r <= 0 ? DEFAULT_SNAP_RADIUS : clampRadius(r);
            } catch (NumberFormatException ignored) {
            }
            try {
                shrinkTo = Math.max(MIN_SHRINK_TO, Math.min(MAX_ARENA, Integer.parseInt(p.getProperty("battle.shrink.to", String.valueOf(shrinkTo)))));
                shrinkSeconds = Math.max(10, Math.min(MAX_TIMER, Integer.parseInt(p.getProperty("battle.shrink.time", String.valueOf(shrinkSeconds)))));
                int v = Integer.parseInt(p.getProperty("battle.arena.size", String.valueOf(arenaSize)));
                arenaSize = v <= 0 ? 0 : Math.max(MIN_ARENA, Math.min(MAX_ARENA, v));
            } catch (NumberFormatException ignored) {
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
        p.setProperty("fight.camera", String.valueOf(fightCamera));
        p.setProperty("battle.music", String.valueOf(battleMusic));
        p.setProperty("battle.music.track", musicTrack);
        p.setProperty("title.scope", titleScope);
        p.setProperty("duel.mode", accumulateDuels ? "accumulate" : "exclusive");
        p.setProperty("chaos.radius", String.valueOf(radius));
        p.setProperty("chaos.area", areaStyle);
        p.setProperty("chaos.area.color", areaColor);
        p.setProperty("team.fx.onlywand", String.valueOf(teamLookOnlyWand));
        p.setProperty("fight.countdown", String.valueOf(countdownSeconds));
        p.setProperty("wand.fx", String.valueOf(wandFx));
        p.setProperty("debug", String.valueOf(debug));
        p.setProperty("kill.delay.millis", String.valueOf(killDelayMs));
        p.setProperty("kill.loot", String.valueOf(killLoot));
        p.setProperty("kill.players", String.valueOf(killPlayers));
        p.setProperty("chaos.area.onlywand", String.valueOf(areaOnlyWand));
        ISOLATION.forEach((k, v) -> p.setProperty("battle.isolation." + k.key, String.valueOf(v)));
        FRIENDLY_FIRE.forEach((k, v) -> p.setProperty("battle.friendlyfire." + k.key, String.valueOf(v)));
        PLAYER_FIGHTS.forEach((k, v) -> p.setProperty("battle.players." + k.key, String.valueOf(v)));
        p.setProperty("battle.pvp", String.valueOf(battlePvp));
        ARENA.forEach((k, v) -> p.setProperty("battle.arena." + k.key, v.name().toLowerCase(java.util.Locale.ROOT)));
        TIMER.forEach((k, v) -> p.setProperty("battle.timer." + k.key, String.valueOf(v)));
        TIME_UP.forEach((k, v) -> p.setProperty("battle.timeup." + k.key, v.name().toLowerCase(java.util.Locale.ROOT)));
        p.setProperty("battle.arena.size", String.valueOf(arenaSize));
        SHRINK.forEach((k, v) -> p.setProperty("battle.shrink." + k.key, String.valueOf(v)));
        p.setProperty("battle.shrink.to", String.valueOf(shrinkTo));
        p.setProperty("battle.unstick", String.valueOf(unstick));
        p.setProperty("battle.shrink.time", String.valueOf(shrinkSeconds));
        p.setProperty("battle.timer.style", timerStyle);
        p.setProperty("battle.tiebreak", tieBreak);
        p.setProperty("snap.mode", snapMode);
        p.setProperty("snap.percent", String.valueOf(snapPercent));
        p.setProperty("snap.time", String.valueOf(snapSeconds));
        p.setProperty("snap.radius", String.valueOf(snapRadius));
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
                "Titles: countdown " + countdownSeconds + " s, startTitle " + onOff(titleEnabled) + ", winTitle " + onOff(winTitleEnabled) + ", titleStyle " + titleStyle + ", titleScope " + titleScope + ", camera " + onOff(fightCamera) + ", music " + (battleMusic ? musicTrack : "off"),
                "Fighter laser: fightLaser " + onOff(laserEnabled) + ", fightLaserColor " + laserColor,
                "Battles: duelMode " + (accumulateDuels ? "accumulate" : "exclusive")
                        + " | battleIsolation " + rules(ISOLATION) + " | friendlyFire " + rules(FRIENDLY_FIRE)
                        + " | playerFights " + rules(PLAYER_FIGHTS) + ", battlePvp " + onOff(battlePvp),
                "Arenas: arena " + kinds(ARENA, v -> v.name().toLowerCase(java.util.Locale.ROOT))
                        + " | timer " + kinds(TIMER, MobBattleCommand::formatDuration)
                        + " | timeUp " + kinds(TIME_UP, v -> v.name().toLowerCase(java.util.Locale.ROOT))
                        + " | arenaShrink " + rules(SHRINK) + " to " + shrinkTo + ", over " + MobBattleCommand.formatDuration(shrinkSeconds)
                        + " with no timer | unstick " + onOff(unstick)
                        + " | arenaSize " + (arenaSize > 0 ? arenaSize : "auto") + ", timerStyle " + timerStyle + ", tieBreak " + tieBreak + ", snapMode " + snapMode
                        + ", snapPercent " + snapPercent + "%, snapTime " + snapSeconds + " s, snapRadius " + snapRadius,
                "Chaos area: areaStyle " + areaStyle + ", areaColor " + areaColor.toLowerCase() + ", areaRadius " + radius
                        + ", areaHeight " + pillarHeight + ", areaOnlyWand " + onOff(areaOnlyWand) + ", maxMobs " + maxMobs
                        + ", summonRadius " + (summonRadius > 0 ? summonRadius : "auto"),
                "Team look: teamLook " + teamFx + ", time " + (teamFxSeconds > 0 ? teamFxSeconds + " s" : "infinite")
                        + ", onlyWand " + onOff(teamLookOnlyWand),
                "Wands: wandEffect " + effect + ", wandFx " + onOff(wandFx) + ", killDelay " + (killDelayMs / 1000f) + " s, killLoot " + onOff(killLoot) + ", killPlayers " + onOff(killPlayers) + ", debugLog " + onOff(debug),
        };
    }

    /** Which describeLines() line a setting key belongs to. */
    public static int groupOf(String key) {
        return switch (key) {
            case "title", "wintitle", "style", "countdown", "titlescope", "camera", "music" -> 0;
            case "laser", "color" -> 1;
            case "isolation", "battleisolation", "friendlyfire", "duelmode", "playerfights", "battlepvp" -> 2;
            case "arena", "timer", "timeup", "arenasize", "arenashrink", "arenashrinkto", "arenashrinktime", "unstick", "timerstyle", "tiebreak", "snapmode", "snappercent", "snaptime", "snapradius" -> 3;
            case "debuglog" -> 6;
            case "radius", "area", "areacolor", "height", "areaonlywand", "maxmobs", "summonradius" -> 4;
            case "teamfx" -> 5;
            default -> 6;
        };
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
