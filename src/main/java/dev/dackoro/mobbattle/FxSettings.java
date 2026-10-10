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

    /**
     * What an area or arena draws (/mobbattle areaStyle): border (only the border glow), disc (a disc on the ground),
     * discBorder (both), wall (disc + see-through wall) or off. The border's color per action is /mobbattle borderColor
     * (ActionFx). Drawing code takes the ground part ({@link #groundOf}) and whether the border shows ({@link #hasBorder}).
     */
    public static String areaStyle = "border";
    public static final String[] AREA_STYLES = {"border", "disc", "discBorder", "wall", "off"};
    /** fx.properties written with the current area styles (without it, chaos.area holds the old floor/zone/off). */
    static final String AREA_STYLES_KEY = "chaos.area.styles";
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
    /**
     * Kill Wand: ms from the click to the cast's hit, when the Hakai sound and the disintegration start
     * (/mobbattle killHitDelay). Default 175: the hit frame (0.175 s) of KillCast.blockyanim.
     */
    public static final int DEFAULT_KILL_HIT_DELAY_MS = 175;
    public static int killHitDelayMs = DEFAULT_KILL_HIT_DELAY_MS;
    /** Kill Wand: the disintegrated mob drops its normal loot (/mobbattle killLoot). Default: on. */
    public static boolean killLoot = true;
    /** Kill Wand: works on players too; they disintegrate, then die normally (/mobbattle killPlayers). Default: off. */
    public static boolean killPlayers = false;

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
    /** Per battle kind: fight time limit in seconds with the timer box, 0 = none (/mobbattle timer). Default: 0, royale 180 (3 min). */
    public static final java.util.Map<BattleManager.Kind, Integer> TIMER = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Per battle kind: what happens at 0 (/mobbattle timeUp draw|kill|continue|health|judge). Default: draw, royale judge. */
    public static final java.util.Map<BattleManager.Kind, Arena.TimeUp> TIME_UP = new java.util.EnumMap<>(BattleManager.Kind.class);
    /**
     * Per battle kind: the arena closes in, like a battle royale zone (/mobbattle arenaShrink). Its wall pushes
     * everyone toward the center until arenaShrinkTo, over the timer (or arenaShrinkTime with no timer).
     * Default: on for royale only.
     */
    public static final java.util.Map<BattleManager.Kind, Boolean> SHRINK = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Per battle kind: the scoreboard HUD, one row per side (/mobbattle scoreboard). Default: teams on. */
    public static final java.util.Map<BattleManager.Kind, Boolean> SCOREBOARD = new java.util.EnumMap<>(BattleManager.Kind.class);
    /** Final radius of a closing arena in blocks (/mobbattle arenaShrinkTo). */
    public static int shrinkTo = 15;
    /** How long a closing arena takes when its battle has no timer, in seconds (/mobbattle arenaShrinkTime). */
    public static int shrinkSeconds = 120;
    public static final int MIN_SHRINK_TO = 4;
    /** Battle mobs stuck on another level than their opponent are brought next to it (/mobbattle unstick). Default: on. */
    public static boolean unstick = true;
    /** Read-only 1 vs 1 duel card with both fighters' stats, from the countdown to the end (/mobbattle duelCard). Default: off. */
    public static boolean duelCard = false;

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
    /** Cleanse Wand look, a palette color (/mobbattle cleanseColor). */
    public static String cleanseColor = "White";
    /** Buff Wand look, a palette color (/mobbattle buffColor): tint, swirl, burst, aura, rune. Yellow = gold. */
    public static String buffColor = "Yellow";

    /** Snap: share of the fighters that turn to dust, 1-100 % (/mobbattle snapPercent). */
    public static int snapPercent = 50;
    /** Snap: seconds over which they turn to dust, 1-30 (/mobbattle snapTime). */
    public static int snapSeconds = 5;
    public static final int MAX_SNAP_SECONDS = 30;
    /** Snap Wand: ms from the click to the slam's hit, when sound, shockwave and targets fire (/mobbattle snapHitDelay). */
    public static final int DEFAULT_SNAP_HIT_DELAY_MS = 310;
    public static final int MAX_SNAP_HIT_DELAY_MS = 2000;
    public static int snapHitDelayMs = DEFAULT_SNAP_HIT_DELAY_MS;
    /** Snap without battles: reach in blocks (/mobbattle snapRadius), on its own: not tied to areaRadius. */
    public static final int DEFAULT_SNAP_RADIUS = 60;
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
            // The battle royale has a 3 min timer (its zone closes over it) and the judge decides at the end.
            boolean royale = k == BattleManager.Kind.ROYALE;
            TIMER.put(k, royale ? 180 : 0);
            TIME_UP.put(k, royale ? Arena.TimeUp.JUDGE : Arena.TimeUp.DRAW);
            SHRINK.put(k, k == BattleManager.Kind.ROYALE);
            SCOREBOARD.put(k, k == BattleManager.Kind.TEAMS);
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

    /** Intro camera style (/mobbattle cameraStyle): auto, orbit, duel, showcase or teams (KindFx). */
    public static String cameraStyle = "auto";
    /** Intro camera length in seconds, 0 = as long as the countdown (/mobbattle cameraTime). */
    public static int cameraSeconds = 0;
    /** Showcase camera: how many fighters it shows (/mobbattle cameraShots). */
    public static int cameraShots = 4;
    /** Camera at the end of a battle (/mobbattle endCamera): off, winner or lasthit (who landed the final kill). */
    public static String endCamera = "off";
    /** How long the end camera films, in seconds (/mobbattle endCameraTime). */
    public static int endCameraSeconds = 5;
    /** The camera viewers start watching (/mobbattle watch) as soon as a battle starts (/mobbattle autoWatch). */
    public static boolean autoWatch = false;
    /** Summoning runes and the fighters' rage/lone/brawl marks at the start (/mobbattle runes). */
    public static boolean runes = true;
    /** Team Wand: a clicked mob brings every mob of its type in areaRadius into the team (/mobbattle teamByType). */
    public static boolean teamByType = false;
    /** Snap: players in reach (not Creative, not the one snapping) can turn to dust too (/mobbattle snapPlayers). */
    public static boolean snapPlayers = false;
    /** Morph Wand: players can be morphed too (/mobbattle morphPlayers). */
    public static boolean morphPlayers = true;

    /** Who sees the fight cameras: owner (default), nearby or world, like titleScope; never a fighter's own intro. */
    public static String cameraScope = "owner";

    public static boolean scoreboard(BattleManager.Kind kind) {
        return kind != null && SCOREBOARD.getOrDefault(kind, false);
    }

    /** Every setting as first loaded (before fx.properties): the "default" preset. */
    private static Properties defaults;

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
        if (defaults == null) defaults = toProperties();
        if (!Files.exists(file)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            MobBattlePlugin.log("Could not read " + file + ": " + e);
            return;
        }
        apply(p);
    }

    /** The plugin data directory (where fx.properties lives), or null before load. */
    static Path dataDirectory() {
        return file == null ? null : file.getParent();
    }

    /** A copy of the settings as first loaded, before fx.properties (empty before load). */
    static Properties defaults() {
        Properties copy = new Properties();
        if (defaults != null) copy.putAll(defaults);
        return copy;
    }

    /**
     * Sets every setting from {@code p}. A missing key keeps its current value, except the per-kind battle
     * rules and KindFx overrides, which are replaced as a whole: pass a full set (toProperties + changes).
     */
    static void apply(Properties p) {
        ActionFx.load(p);
        {
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
            Buffs.load(p);
            for (String c : Teams.PALETTE.keySet()) {
                if (c.equalsIgnoreCase(p.getProperty("wand.cleanse.color", cleanseColor))) cleanseColor = c;
                if (c.equalsIgnoreCase(p.getProperty("wand.buff.color", buffColor))) buffColor = c;
            }
            areaStyle = loadAreaStyle(p.getProperty("chaos.area"), currentAreaStyles(p), areaStyle);
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
            try {
                killHitDelayMs = Math.max(0, Math.min(MAX_SNAP_HIT_DELAY_MS,
                        Integer.parseInt(p.getProperty("kill.hitdelay", String.valueOf(killHitDelayMs)))));
            } catch (NumberFormatException ignored) {
            }
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
            duelCard = Boolean.parseBoolean(p.getProperty("battle.duelCard", String.valueOf(duelCard)));
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
                    v = p.getProperty("battle.scoreboard." + k.key);
                    if (v != null) SCOREBOARD.put(k, Boolean.parseBoolean(v));
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
                snapHitDelayMs = Math.max(0, Math.min(MAX_SNAP_HIT_DELAY_MS, Integer.parseInt(p.getProperty("snap.hitdelay", String.valueOf(snapHitDelayMs)))));
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
            String cs = p.getProperty("camera.style", cameraStyle).toLowerCase(java.util.Locale.ROOT);
            cameraStyle = java.util.Arrays.asList(KindFx.CAMERA_STYLES).contains(cs) ? cs : "auto";
            String ec = p.getProperty("camera.end", endCamera).toLowerCase(java.util.Locale.ROOT);
            endCamera = java.util.Arrays.asList(KindFx.END_CAMERAS).contains(ec) ? ec : "off";
            try {
                cameraSeconds = Math.max(0, Math.min(KindFx.MAX_CAMERA_TIME, Integer.parseInt(p.getProperty("camera.time", String.valueOf(cameraSeconds)))));
                cameraShots = Math.max(1, Math.min(MAX_MOBS_LIMIT, Integer.parseInt(p.getProperty("camera.shots", String.valueOf(cameraShots)))));
                endCameraSeconds = Math.max(1, Math.min(KindFx.MAX_END_CAMERA_TIME, Integer.parseInt(p.getProperty("camera.end.time", String.valueOf(endCameraSeconds)))));
            } catch (NumberFormatException ignored) {
            }
            runes = Boolean.parseBoolean(p.getProperty("fx.runes", String.valueOf(runes)));
            autoWatch = Boolean.parseBoolean(p.getProperty("camera.autowatch", String.valueOf(autoWatch)));
            String camScope = p.getProperty("camera.scope", cameraScope).toLowerCase(java.util.Locale.ROOT);
            cameraScope = java.util.Arrays.asList(TITLE_SCOPES).contains(camScope) ? camScope : "owner";
            teamByType = Boolean.parseBoolean(p.getProperty("team.bytype", String.valueOf(teamByType)));
            snapPlayers = Boolean.parseBoolean(p.getProperty("snap.players", String.valueOf(snapPlayers)));
            morphPlayers = Boolean.parseBoolean(p.getProperty("morph.players", String.valueOf(morphPlayers)));
            KindFx.load(p);
        }
    }

    /** Synchronized: a command thread and a world thread (Summon Wand) can save at the same time. */
    public static synchronized void save() {
        if (file == null) {
            return;
        }
        Properties p = toProperties();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Mob Battle fight start effects (/mobbattle)");
            }
        } catch (IOException e) {
            MobBattlePlugin.log("Could not save " + file + ": " + e);
        }
    }

    /** Every setting as properties (what fx.properties holds). */
    static Properties toProperties() {
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
        p.setProperty(AREA_STYLES_KEY, "2");
        p.setProperty("chaos.area.color", areaColor);
        p.setProperty("team.fx.onlywand", String.valueOf(teamLookOnlyWand));
        p.setProperty("fight.countdown", String.valueOf(countdownSeconds));
        p.setProperty("wand.fx", String.valueOf(wandFx));
        p.setProperty("debug", String.valueOf(debug));
        p.setProperty("kill.delay.millis", String.valueOf(killDelayMs));
        p.setProperty("kill.loot", String.valueOf(killLoot));
        p.setProperty("kill.hitdelay", String.valueOf(killHitDelayMs));
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
        p.setProperty("battle.duelCard", String.valueOf(duelCard));
        p.setProperty("battle.shrink.time", String.valueOf(shrinkSeconds));
        p.setProperty("battle.timer.style", timerStyle);
        p.setProperty("battle.tiebreak", tieBreak);
        p.setProperty("snap.mode", snapMode);
        p.setProperty("snap.percent", String.valueOf(snapPercent));
        Buffs.save(p);
        p.setProperty("wand.cleanse.color", cleanseColor);
        p.setProperty("wand.buff.color", buffColor);
        p.setProperty("snap.time", String.valueOf(snapSeconds));
        p.setProperty("snap.hitdelay", String.valueOf(snapHitDelayMs));
        p.setProperty("snap.radius", String.valueOf(snapRadius));
        p.setProperty("effect.id", effect);
        Teams.save(p);
        SummonList.save(p);
        p.setProperty("team.fx", teamFx);
        p.setProperty("team.fx.seconds", String.valueOf(teamFxSeconds));
        p.setProperty("chaos.maxmobs", String.valueOf(maxMobs));
        p.setProperty("summon.radius", String.valueOf(summonRadius));
        p.setProperty("chaos.height", String.valueOf(pillarHeight));
        p.setProperty("camera.style", cameraStyle);
        p.setProperty("camera.time", String.valueOf(cameraSeconds));
        p.setProperty("camera.shots", String.valueOf(cameraShots));
        p.setProperty("camera.end", endCamera);
        p.setProperty("camera.end.time", String.valueOf(endCameraSeconds));
        p.setProperty("camera.autowatch", String.valueOf(autoWatch));
        p.setProperty("fx.runes", String.valueOf(runes));
        p.setProperty("camera.scope", cameraScope);
        SCOREBOARD.forEach((k, v) -> p.setProperty("battle.scoreboard." + k.key, String.valueOf(v)));
        p.setProperty("team.bytype", String.valueOf(teamByType));
        ActionFx.save(p);
        p.setProperty("snap.players", String.valueOf(snapPlayers));
        p.setProperty("morph.players", String.valueOf(morphPlayers));
        KindFx.save(p);
        return p;
    }

    public static int clampMaxMobs(int value) {
        return Math.max(2, Math.min(MAX_MOBS_LIMIT, value));
    }

    public static int clampHeight(int value) {
        return Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, value));
    }

    /** The area style as written in AREA_STYLES (camelCase), or null if it isn't one. */
    public static String parseAreaStyle(String value) {
        if (value == null) return null;
        for (String s : AREA_STYLES) if (s.equalsIgnoreCase(value)) return s;
        return null;
    }

    /** True for an area style the command takes (AREA_STYLES). */
    public static boolean isAreaStyle(String value) {
        return parseAreaStyle(value) != null;
    }

    /** True if {@code p} was written with the current area styles (see AREA_STYLES_KEY). */
    static boolean currentAreaStyles(Properties p) {
        return p.getProperty(AREA_STYLES_KEY) != null;
    }

    /**
     * A saved area style. Files from before AREA_STYLES_KEY hold the old ground-only styles (floor was the default
     * then, so it was saved in almost every world): every old style loads as border, the new default, so the red
     * disc goes away everywhere. Unknown -> {@code fallback}.
     */
    static String loadAreaStyle(String raw, boolean current, String fallback) {
        if (raw == null) return fallback;
        if (!current) {
            switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case "floor", "both", "zone", "all", "off", "glow" -> { return "border"; }
                default -> { }
            }
        }
        String s = parseAreaStyle(raw);
        return s != null ? s : fallback;
    }

    /** The ground drawn by an area style, as FightFx.drawArea takes it: floor (disc), zone (disc + wall) or off. */
    static String groundOf(String style) {
        if (style == null) return "off";
        return switch (style.toLowerCase(java.util.Locale.ROOT)) {
            case "disc", "discborder" -> "floor";
            case "wall" -> "zone";
            default -> "off";
        };
    }

    /** True if an area style shows the border glow. */
    static boolean hasBorder(String style) {
        return "border".equalsIgnoreCase(style) || "discBorder".equalsIgnoreCase(style);
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
                "Titles: countdown " + countdownSeconds + " s, startTitle " + onOff(titleEnabled) + ", winTitle " + onOff(winTitleEnabled) + ", titleStyle " + titleStyle + ", titleScope " + titleScope + ", camera " + onOff(fightCamera) + ", music " + (battleMusic ? musicTrack : "off")
                        + ", runes " + onOff(runes) + " | cameraStyle " + cameraStyle + ", cameraTime " + (cameraSeconds > 0 ? cameraSeconds + " s" : "countdown")
                        + ", cameraShots " + cameraShots + ", endCamera " + endCamera + ", endCameraTime " + endCameraSeconds + " s, cameraScope " + cameraScope + ", autoWatch " + onOff(autoWatch)
                        + " | per kind: " + KindFx.describeOverrides(),
                "Fighter laser: fightLaser " + onOff(laserEnabled) + ", fightLaserColor " + laserColor,
                "Battles: duelMode " + (accumulateDuels ? "accumulate" : "exclusive")
                        + " | battleIsolation " + rules(ISOLATION) + " | friendlyFire " + rules(FRIENDLY_FIRE)
                        + " | playerFights " + rules(PLAYER_FIGHTS) + ", battlePvp " + onOff(battlePvp)
                        + " | buff " + Buffs.describe(),
                "Arenas: arena " + kinds(ARENA, v -> v.name().toLowerCase(java.util.Locale.ROOT))
                        + " | timer " + kinds(TIMER, MobBattleCommand::formatDuration)
                        + " | timeUp " + kinds(TIME_UP, v -> v.name().toLowerCase(java.util.Locale.ROOT))
                        + " | arenaShrink " + rules(SHRINK) + " to " + shrinkTo + ", over " + MobBattleCommand.formatDuration(shrinkSeconds)
                        + " with no timer | scoreboard " + rules(SCOREBOARD) + " | unstick " + onOff(unstick)
                        + " | duelCard " + onOff(duelCard)
                        + " | arenaSize " + (arenaSize > 0 ? arenaSize : "auto") + ", timerStyle " + timerStyle + ", tieBreak " + tieBreak + ", snapMode " + snapMode
                        + ", snapPercent " + snapPercent + "%, snapTime " + snapSeconds + " s, snapHitDelay " + snapHitDelayMs + " ms, snapRadius " + snapRadius,
                "Chaos area: areaStyle " + areaStyle + ", areaColor " + areaColor.toLowerCase() + ", areaRadius " + radius
                        + ", areaHeight " + pillarHeight + ", areaOnlyWand " + onOff(areaOnlyWand) + ", maxMobs " + maxMobs
                        + ", summonRadius " + (summonRadius > 0 ? summonRadius : "auto") + " | " + ActionFx.describe() + " (wave/borderColor)",
                "Team look: teamByType " + onOff(teamByType) + ", teamLook " + teamFx + ", time " + (teamFxSeconds > 0 ? teamFxSeconds + " s" : "infinite")
                        + ", onlyWand " + onOff(teamLookOnlyWand),
                "Wands: wandEffect " + effect + ", wandFx " + onOff(wandFx) + ", killDelay " + (killDelayMs / 1000f) + " s, killHitDelay " + killHitDelayMs + " ms, killLoot " + onOff(killLoot) + ", killPlayers " + onOff(killPlayers) + ", snapPlayers " + onOff(snapPlayers) + ", morphPlayers " + onOff(morphPlayers) + ", cleanseColor " + cleanseColor.toLowerCase() + ", buffColor " + buffColor.toLowerCase() + ", debugLog " + onOff(debug),
        };
    }

    /** Which describeLines() line a setting key belongs to. */
    public static int groupOf(String key) {
        return switch (key) {
            case "title", "wintitle", "style", "countdown", "titlescope", "camera", "music", "runes", "camerastyle", "cameratime", "camerashots", "endcamera", "endcameratime", "camerascope" -> 0;
            case "laser", "color" -> 1;
            case "isolation", "battleisolation", "friendlyfire", "duelmode", "playerfights", "battlepvp", "buff" -> 2;
            case "scoreboard", "arena", "timer", "timeup", "arenasize", "arenashrink", "arenashrinkto", "arenashrinktime", "unstick", "duelcard", "timerstyle", "tiebreak", "snapmode", "snappercent", "snaptime", "snaphitdelay", "snapradius" -> 3;
            case "debuglog", "snapplayers", "morphplayers", "killhitdelay", "cleansecolor", "buffcolor" -> 6;
            case "radius", "area", "areacolor", "wave", "glow", "mobfx", "crown", "height", "areaonlywand", "maxmobs", "summonradius" -> 4;
            case "teamfx", "teambytype" -> 5;
            default -> 6;
        };
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
