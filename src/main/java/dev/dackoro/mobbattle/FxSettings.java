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
    /** Global access policy for item interactions and Mob Battle commands. */
    public static volatile String permissionsMode = "open";
    static final String PERMISSIONS_KEY = "permissions.mode";

    /** Vivid colors used when the laser color is "random". */
    /** Laser "random": the eight bright palette colors (palette only, see Teams.PALETTE). */
    private static final String[] RANDOM_COLORS = {
            "#FF4B3E", "#3EF0FF", "#FFD23E", "#A24BFF", "#FF8C1A", "#4BFF6A", "#FF6BD5", "#4B9BFF"
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
     * Light around mobs wearing a Jade/Shimmer look or the buff's shimmer, in the look's color, 0-15 like block light
     * (/mobbattle lookGlow, 0 = off). Default 6: a soft glow. See LookLight.
     */
    public static int lookGlow = 6;
    /** Kill Wand disintegration flash: starts at this light level and fades out (/mobbattle deathGlow, 0 = off). */
    public static int deathGlow = 12;
    public static final int MAX_GLOW = 15;
    /**
     * Kill Wand: ms from the click to the cast's hit, when the Hakai sound and the disintegration start
     * (/mobbattle killHitDelay). Default 175: the hit frame (0.175 s) of KillCast.blockyanim.
     */
    public static final int DEFAULT_KILL_HIT_DELAY_MS = 175;
    public static int killHitDelayMs = DEFAULT_KILL_HIT_DELAY_MS;
    /** Kill Wand and Snap: the killed mob drops its normal loot (/mobbattle killLoot). Default: off (user, 2026-10-11). */
    public static boolean killLoot = false;
    /** Kill Wand: works on players too; they disintegrate, then die normally (/mobbattle killPlayers). Default: on. */
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
     * Per battle kind: a battle mob hit by an enemy of its battle that isn't its target turns on it, like an Army
     * Wand mob defends itself (/mobbattle selfDefense). Default: on for every kind.
     */
    public static final java.util.Map<BattleManager.Kind, Boolean> SELF_DEFENSE = new java.util.EnumMap<>(BattleManager.Kind.class);
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
     * Per battle kind: what timeUp health/judge scores (/mobbattle judgeBy): health (average health % left, default),
     * damage (share of the damage dealt, per fighter), kills (share of the kills, per fighter) or mix (the three averaged).
     */
    public static final java.util.Map<BattleManager.Kind, String> JUDGE_BY = new java.util.EnumMap<>(BattleManager.Kind.class);
    public static final String[] JUDGE_BY_VALUES = {"health", "damage", "kills", "mix"};
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
    /**
     * Stand-off shove (/mobbattle standoffTime, Unstick): seconds a battle mob on its opponent's level may stand off
     * (no closer, no hit given or taken, out of reach) before it is shoved toward it. 0 = never. Default 15.
     */
    public static int standoffSeconds = 15;
    static final int MAX_STANDOFF_SECONDS = 120;
    /**
     * /mobbattle passiveWin: once only one side (or none) can still fight back, seconds without a hit before that
     * side wins (none: a draw), with an alert bottom left (BattleManager.passiveEnd). 0 = off. Default 15.
     */
    public static int passiveWinSeconds = 15;
    static final int MAX_PASSIVE_WIN_SECONDS = 120;
    /**
     * /mobbattle passiveWinIgnore: role ids (comma separated, a trailing * matches any ending, case-insensitive) that
     * never let passiveWin start while one is alive in the battle, e.g. an immortal training dummy a modder tests a mob
     * against (user, 2026-10-12). "" = none. Default Test_Dummy* (the game's six test dummies).
     */
    public static String passiveWinIgnore = "Test_Dummy*";

    /** Canonical passiveWinIgnore list ("none"/blank = ""), or null if a name has characters a role id can't have. */
    static String parsePassiveWinIgnore(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("none") || v.equalsIgnoreCase("off")) return "";
        StringBuilder out = new StringBuilder();
        for (String part : v.split("[,\\s]+")) {
            if (part.isEmpty()) continue;
            if (!part.matches("[A-Za-z0-9_\\-]+\\*?")) return null;
            if (out.length() > 0) out.append(',');
            out.append(part);
        }
        return out.toString();
    }

    /** Whether {@code role} is on the passiveWinIgnore list. */
    static boolean passiveWinIgnored(String role) {
        String list = passiveWinIgnore;
        if (role == null || list.isEmpty()) return false;
        for (String p : list.split(",")) {
            if (p.endsWith("*") ? role.regionMatches(true, 0, p, 0, p.length() - 1) : role.equalsIgnoreCase(p)) return true;
        }
        return false;
    }
    /** Read-only 1 vs 1 duel card with both fighters' stats, from the countdown to the end (/mobbattle duelCard). Default: off. */
    public static boolean duelCard = false;

    public static boolean shrinks(BattleManager.Kind kind) {
        return kind != null && SHRINK.getOrDefault(kind, false);
    }

    /** Arena radius of duels and team battles (/mobbattle arenaSize); 0 = auto, around the fighters. */
    public static int arenaSize = 0;
    /** /mobbattle debug: battle decisions (config, arena, health scores, winner) in the server log. */
    public static volatile boolean debug = false;
    /**
     * /mobbattle debugRecord: keep the last 5 min of log and [debug] lines in memory for /mobbattle debugDump
     * (DebugRecorder). Default off. Admin only, like debug (Presets.isSecurityKey).
     */
    public static volatile boolean debugRecord = false;
    /** Debug Wand: seconds a left-clicked mob is followed after its report (/mobbattle debugTrack, 0 = report only). */
    public static int debugTrackSeconds = 30;
    static final int MAX_DEBUG_TRACK_SECONDS = 600;
    /** timeUp health/judge when even on health: "damage" (default), "random" or "draw" (/mobbattle tieBreak). */
    public static String tieBreak = "damage";
    /** Snap Wand and /mobbattle snap: "global" (half of everyone, default) or "team" (half of each side). */
    public static String snapMode = "global";
    /** Cleanse Wand look, a palette color (/mobbattle cleanseColor). */
    public static String cleanseColor = "White";
    /**
     * Buff look, a palette color (/mobbattle buffColor): the Buff Wand's mark and swirl, and the shimmer of wand,
     * streak and champion mobs (Buffs).
     */
    public static String buffColor = "Purple";
    /**
     * Buff Wand light from the sky (/mobbattle buffLight): "buff" (in buffColor, default), a palette color, or "off";
     * the buff lands with the light (BuffGiveInteraction), off = at once.
     */
    public static String buffLight = "buff";

    /** The Buff Wand light's palette color now, or null when it is off. */
    static String buffLightColor() {
        if ("off".equals(buffLight)) return null;
        return "buff".equals(buffLight) ? buffColor : buffLight;
    }

    /**
     * Heal Wand light from the sky (/mobbattle healLight): a palette color (default Green) or "off"; the heal lands
     * with the light (HealInteraction), off = at once. Same light effects as the Buff Wand's.
     */
    public static String healLight = "Green";

    /** The Heal Wand light's palette color now, or null when it is off. */
    static String healLightColor() {
        return "off".equals(healLight) ? null : healLight;
    }

    /** "off" or a palette name (canonical case); null if {@code value} is neither. */
    static String parseHealLight(String value) {
        if (value == null) return null;
        String c = Buffs.parseCrown(value.trim());
        return c == null || c.equals("Gold") ? null : c;
    }

    /** "buff", "off" or a palette name (canonical case); null if {@code value} is none of them. */
    static String parseBuffLight(String value) {
        if (value == null) return null;
        if ("buff".equalsIgnoreCase(value.trim())) return "buff";
        String c = Buffs.parseCrown(value.trim());
        return c == null || c.equals("Gold") ? null : c;
    }

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

    public static String judgeBy(BattleManager.Kind kind) {
        return kind == null ? "health" : JUDGE_BY.getOrDefault(kind, "health");
    }

    /**
     * The tie break a battle kind really uses: tieBreak damage with judgeBy damage (or kills with kills) would only
     * tie again, so it takes the other one (damage <-> kills).
     */
    public static String tieBreak(BattleManager.Kind kind) {
        String by = judgeBy(kind);
        if (tieBreak.equals(by)) return by.equals("damage") ? "kills" : "damage";
        return tieBreak;
    }

    /** A judgeBy value as stored (lowercase), or null. */
    public static String parseJudgeBy(String value) {
        if (value == null) return null;
        for (String v : JUDGE_BY_VALUES) if (v.equalsIgnoreCase(value.trim())) return v;
        return null;
    }

    public static Arena.TimeUp timeUp(BattleManager.Kind kind) {
        return kind == null ? Arena.TimeUp.DRAW : TIME_UP.getOrDefault(kind, Arena.TimeUp.DRAW);
    }

    /** Kinds where friendly fire means something (duels and royales have no allies). */
    public static final BattleManager.Kind[] ALLY_KINDS = {BattleManager.Kind.ALL_VS_ONE, BattleManager.Kind.TEAMS};
    /**
     * Per battle kind: players can fight in it (/mobbattle playerFights). Default: on for every kind (user,
     * 2026-10-12: every wand works on players); Creative players never fight.
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
        return kind != null && PLAYER_FIGHTS.getOrDefault(kind, true);
    }

    static {
        resetBattleRules();
    }

    private static void resetBattleRules() {
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            ISOLATION.put(k, true);
            SELF_DEFENSE.put(k, true);
        }
        FRIENDLY_FIRE.put(BattleManager.Kind.ALL_VS_ONE, true);
        FRIENDLY_FIRE.put(BattleManager.Kind.TEAMS, false);
        for (BattleManager.Kind k : PLAYER_KINDS) {
            PLAYER_FIGHTS.put(k, true);
        }
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            boolean chaos = k == BattleManager.Kind.ALL_VS_ONE || k == BattleManager.Kind.ROYALE;
            ARENA.put(k, chaos ? Arena.Mode.ON : Arena.Mode.OFF);
            // The battle royale has a 3 min timer (its zone closes over it) and the judge decides at the end.
            boolean royale = k == BattleManager.Kind.ROYALE;
            TIMER.put(k, royale ? 180 : 0);
            TIME_UP.put(k, royale ? Arena.TimeUp.JUDGE : Arena.TimeUp.DRAW);
            JUDGE_BY.put(k, "health");
            SHRINK.put(k, k == BattleManager.Kind.ROYALE);
            SCOREBOARD.put(k, k == BattleManager.Kind.TEAMS);
        }
    }

    public static boolean isolated(BattleManager.Kind kind) {
        return kind == null || ISOLATION.getOrDefault(kind, true);
    }

    public static boolean selfDefense(BattleManager.Kind kind) {
        return kind != null && SELF_DEFENSE.getOrDefault(kind, true);
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

    /** Summoned mobs leave on their own: never (default, they stay), battleEnd (the surviving summons of a battle go when it ends) or timer (summonLifetime after spawn) (/mobbattle summonDespawn). Global, not per kind: summons spawn outside battles. */
    public static String summonDespawn = SummonDespawn.NEVER;
    /** Seconds a summon lives with summonDespawn timer (/mobbattle summonLifetime). */
    public static int summonLifetime = 60;
    /** Summons come out wearing the jade look (/mobbattle summonJade). Default off. The shimmer is the buff's look. */
    public static boolean summonJade = false;
    /** Summons join the summoner's army, as if added with the Army Wand (/mobbattle summonArmy). Default off. */
    public static boolean summonArmy = false;
    /** Color of the summons' jade (/mobbattle summonJadeColor), a palette color. Default green. */
    public static String summonJadeColor = EffectWand.JADE_COLOR;
    /** Army mobs wear a tint for as long as they are in the army (/mobbattle armyTint). Default off. */
    public static boolean armyTint = false;
    /** Color of the army tint (/mobbattle armyTintColor), a palette color. Default blue (the Army Wand color). */
    public static String armyTintColor = "Blue";
    /** How a summon leaves: disintegrate (the Kill Wand's soul rip, no loot), vanish (removed at once) or shatter (jade glass) (/mobbattle summonExit). */
    public static String summonExit = SummonDespawn.DISINTEGRATE;

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
    public static boolean snapPlayers = true;
    /** Morph Wand: players can be morphed too (/mobbattle morphPlayers). */
    public static boolean morphPlayers = true;
    /** Morph Wand held left click: every mob in morphBurstRadius takes the copied look (/mobbattle morphBurst). */
    public static boolean morphBurst = true;
    /** Morph Wand held right click: the looks in morphBurstRadius are swapped in random pairs (/mobbattle morphShuffle). */
    public static boolean morphShuffle = true;
    /** Morph Wand held click: reach in blocks of the burst and the shuffle (/mobbattle morphBurstRadius). */
    public static final int DEFAULT_MORPH_BURST_RADIUS = 12;
    public static int morphBurstRadius = DEFAULT_MORPH_BURST_RADIUS;
    /** Cleanse Wand held left click: every mob and player in cleanseRadius is cleansed (/mobbattle cleanseArea). */
    public static boolean cleanseArea = true;
    /** Cleanse Wand held click: reach in blocks (/mobbattle cleanseRadius). */
    public static final int DEFAULT_CLEANSE_RADIUS = 12;
    public static int cleanseRadius = DEFAULT_CLEANSE_RADIUS;

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

    /** Laser color from a command: random, a palette name or a palette hex; null for anything else. */
    public static String parseLaserColor(String value) {
        if (value == null) return null;
        if (RANDOM.equalsIgnoreCase(value)) return RANDOM;
        String hex = Teams.parseColor(value);
        if (hex != null) return hex;
        for (String h : Teams.PALETTE.values()) if (h.equalsIgnoreCase(value)) return h;
        return null;
    }

    /** The palette hex closest (RGB distance) to {@code hex}. */
    static String nearestPaletteHex(String hex) {
        int c = Integer.parseInt(hex.substring(1), 16);
        String best = Teams.PALETTE.get("Red");
        long bestD = Long.MAX_VALUE;
        for (String h : Teams.PALETTE.values()) {
            int p = Integer.parseInt(h.substring(1), 16);
            long dr = ((c >> 16) & 255) - ((p >> 16) & 255), dg = ((c >> 8) & 255) - ((p >> 8) & 255), db = (c & 255) - (p & 255);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** Palette name of the laser color, or "random". */
    static String laserColorName() {
        if (RANDOM.equals(laserColor)) return RANDOM;
        for (var e : Teams.PALETTE.entrySet()) if (e.getValue().equalsIgnoreCase(laserColor)) return e.getKey().toLowerCase(java.util.Locale.ROOT);
        return laserColor;
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

    /** The int stored under {@code key}, or {@code current} when the key is missing or not a number. */
    private static int intOr(Properties p, String key, int current) {
        String v = p.getProperty(key);
        if (v == null) return current;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return current;
        }
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
        } catch (IOException | IllegalArgumentException e) {
            MobBattlePlugin.log("Could not read " + file + ": " + e);
            return;
        }
        apply(p);
    }

    /** Reloads the server's fx.properties, resetting removed keys to their startup defaults. */
    static String reload() {
        if (file == null) return "Settings are not loaded yet.";
        Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return "Could not read " + file.getFileName() + ": " + e.getMessage();
        }
        Properties all = defaults();
        all.putAll(p);
        applyAll(all, false);
        return null;
    }

    /**
     * Sets every setting from a full set of properties (toProperties + changes), saves fx.properties and switches
     * off what a setting turned off now stops (music, unstick tracking, duel cards). Presets, resets, reload and the
     * settings panel all go through here, like a single command would.
     */
    static void applyAll(Properties p) {
        applyAll(p, true);
    }

    private static void applyAll(Properties p, boolean write) {
        apply(p);
        if (write) save();
        BattleMusic.stopSilenced();
        if (!Unstick.active()) Unstick.clear();
        if (!duelCard) DuelCard.clearAll();
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
            String permissions = p.getProperty(PERMISSIONS_KEY, permissionsMode);
            permissionsMode = "server".equalsIgnoreCase(permissions) ? "server" : "open";
            laserEnabled = Boolean.parseBoolean(p.getProperty("laser.enabled", String.valueOf(laserEnabled)));
            String savedColor = p.getProperty("laser.color", laserColor);
            if (RANDOM.equalsIgnoreCase(savedColor)) {
                laserColor = RANDOM;
            } else if (isHexColor(savedColor)) {
                // Older files could hold any #RRGGBB: keep the closest palette color.
                laserColor = nearestPaletteHex(savedColor);
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
            maxMobs = clampMaxMobs(intOr(p, "chaos.maxmobs", maxMobs));
            summonRadius = Math.max(0, Math.min(MAX_SUMMON_RADIUS, intOr(p, "summon.radius", summonRadius)));
            String despawn = SummonDespawn.parseMode(p.getProperty("summon.despawn"));
            if (despawn != null) summonDespawn = despawn;
            summonLifetime = SummonDespawn.clampLifetime(intOr(p, "summon.lifetime", summonLifetime));
            String exit = SummonDespawn.parseExit(p.getProperty("summon.exit"));
            if (exit != null) summonExit = exit;
            summonJade = Boolean.parseBoolean(p.getProperty("summon.jade", String.valueOf(summonJade)));
            summonArmy = Boolean.parseBoolean(p.getProperty("summon.army", String.valueOf(summonArmy)));
            String jadeColor = SummonList.parseJadeColor(p.getProperty("summon.jade.color"));
            if (jadeColor != null) summonJadeColor = jadeColor;
            armyTint = Boolean.parseBoolean(p.getProperty("army.tint", String.valueOf(armyTint)));
            String tintColor = SummonList.parseJadeColor(p.getProperty("army.tint.color"));
            if (tintColor != null) armyTintColor = tintColor;
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
            String light = parseBuffLight(p.getProperty("wand.buff.light", buffLight));
            if (light != null) buffLight = light;
            String healL = parseHealLight(p.getProperty("wand.heal.light", healLight));
            if (healL != null) healLight = healL;
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
            debugRecord = Boolean.parseBoolean(p.getProperty("debug.record", String.valueOf(debugRecord)));
            if (!debugRecord) DebugRecorder.clear();
            try {
                debugTrackSeconds = Math.max(0, Math.min(MAX_DEBUG_TRACK_SECONDS,
                        Integer.parseInt(p.getProperty("wand.debug.track", String.valueOf(debugTrackSeconds)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            try {
                killDelayMs = Math.max(0, Math.min(MAX_KILL_DELAY_MS,
                        Integer.parseInt(p.getProperty("kill.delay.millis", String.valueOf(killDelayMs)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            killLoot = Boolean.parseBoolean(p.getProperty("kill.loot", String.valueOf(killLoot)));
            try {
                lookGlow = Math.max(0, Math.min(MAX_GLOW, Integer.parseInt(p.getProperty("fx.glow.look", String.valueOf(lookGlow)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            try {
                deathGlow = Math.max(0, Math.min(MAX_GLOW, Integer.parseInt(p.getProperty("fx.glow.death", String.valueOf(deathGlow)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
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
            for (BattleManager.Kind k : BattleManager.Kind.values()) {
                String v = p.getProperty("battle.selfdefense." + k.key);
                if (v != null) SELF_DEFENSE.put(k, Boolean.parseBoolean(v));
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
            try {
                standoffSeconds = Math.max(0, Math.min(MAX_STANDOFF_SECONDS,
                        Integer.parseInt(p.getProperty("battle.standoff", String.valueOf(standoffSeconds)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            try {
                passiveWinSeconds = Math.max(0, Math.min(MAX_PASSIVE_WIN_SECONDS,
                        Integer.parseInt(p.getProperty("battle.passivewin", String.valueOf(passiveWinSeconds)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            String ignore = parsePassiveWinIgnore(p.getProperty("battle.passivewin.ignore", passiveWinIgnore));
            if (ignore != null) passiveWinIgnore = ignore;
            duelCard = Boolean.parseBoolean(p.getProperty("battle.duelCard", String.valueOf(duelCard)));
            // Each key on its own: a bad value keeps that kind's current setting, the other keys still load.
            for (BattleManager.Kind k : BattleManager.Kind.values()) {
                try {
                    String v = p.getProperty("battle.arena." + k.key);
                    if (v != null) ARENA.put(k, Arena.Mode.valueOf(v.toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // keep the default
                }
                try {
                    String v = p.getProperty("battle.timeup." + k.key);
                    if (v != null) TIME_UP.put(k, Arena.TimeUp.valueOf(v.toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // keep the default
                }
                String judge = parseJudgeBy(p.getProperty("battle.judgeby." + k.key));
                if (judge != null) JUDGE_BY.put(k, judge);
                try {
                    String v = p.getProperty("battle.timer." + k.key);
                    if (v != null) TIMER.put(k, Math.max(0, Math.min(MAX_TIMER, Integer.parseInt(v))));
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
                // Booleans never throw (parseBoolean), so their keys need no try.
                String shrink = p.getProperty("battle.shrink." + k.key);
                if (shrink != null) SHRINK.put(k, Boolean.parseBoolean(shrink));
                String scoreboard = p.getProperty("battle.scoreboard." + k.key);
                if (scoreboard != null) SCOREBOARD.put(k, Boolean.parseBoolean(scoreboard));
            }
            String style = p.getProperty("battle.timer.style", timerStyle);
            timerStyle = "portal".equalsIgnoreCase(style) ? "portal" : "custom";
            String tb = p.getProperty("battle.tiebreak", tieBreak).toLowerCase(java.util.Locale.ROOT);
            tieBreak = tb.equals("random") || tb.equals("draw") || tb.equals("kills") ? tb : "damage";
            snapMode = "team".equalsIgnoreCase(p.getProperty("snap.mode", snapMode)) ? "team" : "global";
            snapPercent = Math.max(1, Math.min(100, intOr(p, "snap.percent", snapPercent)));
            snapSeconds = Math.max(1, Math.min(MAX_SNAP_SECONDS, intOr(p, "snap.time", snapSeconds)));
            snapHitDelayMs = Math.max(0, Math.min(MAX_SNAP_HIT_DELAY_MS, intOr(p, "snap.hitdelay", snapHitDelayMs)));
            int r = intOr(p, "snap.radius", snapRadius);
            // Older saves: 0 meant "auto = areaRadius"; the snap now has its own reach.
            snapRadius = r <= 0 ? DEFAULT_SNAP_RADIUS : clampRadius(r);
            shrinkTo = Math.max(MIN_SHRINK_TO, Math.min(MAX_ARENA, intOr(p, "battle.shrink.to", shrinkTo)));
            shrinkSeconds = Math.max(10, Math.min(MAX_TIMER, intOr(p, "battle.shrink.time", shrinkSeconds)));
            int v = intOr(p, "battle.arena.size", arenaSize);
            arenaSize = v <= 0 ? 0 : Math.max(MIN_ARENA, Math.min(MAX_ARENA, v));
            pillarHeight = clampHeight(intOr(p, "chaos.height", pillarHeight));
            radius = clampRadius(intOr(p, "chaos.radius", radius));
            String cs = p.getProperty("camera.style", cameraStyle).toLowerCase(java.util.Locale.ROOT);
            cameraStyle = java.util.Arrays.asList(KindFx.CAMERA_STYLES).contains(cs) ? cs : "auto";
            String ec = p.getProperty("camera.end", endCamera).toLowerCase(java.util.Locale.ROOT);
            endCamera = java.util.Arrays.asList(KindFx.END_CAMERAS).contains(ec) ? ec : "off";
            cameraSeconds = Math.max(0, Math.min(KindFx.MAX_CAMERA_TIME, intOr(p, "camera.time", cameraSeconds)));
            cameraShots = Math.max(1, Math.min(MAX_MOBS_LIMIT, intOr(p, "camera.shots", cameraShots)));
            endCameraSeconds = Math.max(1, Math.min(KindFx.MAX_END_CAMERA_TIME, intOr(p, "camera.end.time", endCameraSeconds)));
            runes = Boolean.parseBoolean(p.getProperty("fx.runes", String.valueOf(runes)));
            autoWatch = Boolean.parseBoolean(p.getProperty("camera.autowatch", String.valueOf(autoWatch)));
            String camScope = p.getProperty("camera.scope", cameraScope).toLowerCase(java.util.Locale.ROOT);
            cameraScope = java.util.Arrays.asList(TITLE_SCOPES).contains(camScope) ? camScope : "owner";
            teamByType = Boolean.parseBoolean(p.getProperty("team.bytype", String.valueOf(teamByType)));
            snapPlayers = Boolean.parseBoolean(p.getProperty("snap.players", String.valueOf(snapPlayers)));
            morphPlayers = Boolean.parseBoolean(p.getProperty("morph.players", String.valueOf(morphPlayers)));
            morphBurst = Boolean.parseBoolean(p.getProperty("morph.burst", String.valueOf(morphBurst)));
            morphShuffle = Boolean.parseBoolean(p.getProperty("morph.shuffle", String.valueOf(morphShuffle)));
            int mr = intOr(p, "morph.burst.radius", morphBurstRadius);
            morphBurstRadius = mr <= 0 ? DEFAULT_MORPH_BURST_RADIUS : clampRadius(mr);
            cleanseArea = Boolean.parseBoolean(p.getProperty("cleanse.area", String.valueOf(cleanseArea)));
            int cr = intOr(p, "cleanse.area.radius", cleanseRadius);
            cleanseRadius = cr <= 0 ? DEFAULT_CLEANSE_RADIUS : clampRadius(cr);
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
        p.setProperty(PERMISSIONS_KEY, permissionsMode);
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
        p.setProperty("debug.record", String.valueOf(debugRecord));
        p.setProperty("wand.debug.track", String.valueOf(debugTrackSeconds));
        p.setProperty("kill.delay.millis", String.valueOf(killDelayMs));
        p.setProperty("kill.loot", String.valueOf(killLoot));
        p.setProperty("fx.glow.look", String.valueOf(lookGlow));
        p.setProperty("fx.glow.death", String.valueOf(deathGlow));
        p.setProperty("kill.hitdelay", String.valueOf(killHitDelayMs));
        p.setProperty("kill.players", String.valueOf(killPlayers));
        p.setProperty("chaos.area.onlywand", String.valueOf(areaOnlyWand));
        ISOLATION.forEach((k, v) -> p.setProperty("battle.isolation." + k.key, String.valueOf(v)));
        FRIENDLY_FIRE.forEach((k, v) -> p.setProperty("battle.friendlyfire." + k.key, String.valueOf(v)));
        SELF_DEFENSE.forEach((k, v) -> p.setProperty("battle.selfdefense." + k.key, String.valueOf(v)));
        PLAYER_FIGHTS.forEach((k, v) -> p.setProperty("battle.players." + k.key, String.valueOf(v)));
        p.setProperty("battle.pvp", String.valueOf(battlePvp));
        ARENA.forEach((k, v) -> p.setProperty("battle.arena." + k.key, v.name().toLowerCase(java.util.Locale.ROOT)));
        TIMER.forEach((k, v) -> p.setProperty("battle.timer." + k.key, String.valueOf(v)));
        TIME_UP.forEach((k, v) -> p.setProperty("battle.timeup." + k.key, v.name().toLowerCase(java.util.Locale.ROOT)));
        JUDGE_BY.forEach((k, v) -> p.setProperty("battle.judgeby." + k.key, v));
        p.setProperty("battle.arena.size", String.valueOf(arenaSize));
        SHRINK.forEach((k, v) -> p.setProperty("battle.shrink." + k.key, String.valueOf(v)));
        p.setProperty("battle.shrink.to", String.valueOf(shrinkTo));
        p.setProperty("battle.unstick", String.valueOf(unstick));
        p.setProperty("battle.standoff", String.valueOf(standoffSeconds));
        p.setProperty("battle.passivewin", String.valueOf(passiveWinSeconds));
        p.setProperty("battle.passivewin.ignore", passiveWinIgnore);
        p.setProperty("battle.duelCard", String.valueOf(duelCard));
        p.setProperty("battle.shrink.time", String.valueOf(shrinkSeconds));
        p.setProperty("battle.timer.style", timerStyle);
        p.setProperty("battle.tiebreak", tieBreak);
        p.setProperty("snap.mode", snapMode);
        p.setProperty("snap.percent", String.valueOf(snapPercent));
        Buffs.save(p);
        p.setProperty("wand.cleanse.color", cleanseColor);
        p.setProperty("wand.buff.color", buffColor);
        p.setProperty("wand.buff.light", buffLight);
        p.setProperty("wand.heal.light", healLight);
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
        p.setProperty("summon.despawn", summonDespawn);
        p.setProperty("summon.lifetime", String.valueOf(summonLifetime));
        p.setProperty("summon.exit", summonExit);
        p.setProperty("summon.jade", String.valueOf(summonJade));
        p.setProperty("summon.army", String.valueOf(summonArmy));
        p.setProperty("summon.jade.color", summonJadeColor);
        p.setProperty("army.tint", String.valueOf(armyTint));
        p.setProperty("army.tint.color", armyTintColor);
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
        p.setProperty("morph.burst", String.valueOf(morphBurst));
        p.setProperty("morph.shuffle", String.valueOf(morphShuffle));
        p.setProperty("morph.burst.radius", String.valueOf(morphBurstRadius));
        p.setProperty("cleanse.area", String.valueOf(cleanseArea));
        p.setProperty("cleanse.area.radius", String.valueOf(cleanseRadius));
        KindFx.save(p);
        return p;
    }

    static boolean serverPermissions() {
        return "server".equals(permissionsMode);
    }

    static String setPermissionsMode(String mode) {
        if (!"open".equalsIgnoreCase(mode) && !"server".equalsIgnoreCase(mode)) return "Use /mobbattle permissions open|server.";
        permissionsMode = mode.toLowerCase(java.util.Locale.ROOT);
        save();
        return null;
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
                "Fighter laser: fightLaser " + onOff(laserEnabled) + ", fightLaserColor " + laserColorName(),
                "Battles: duelMode " + (accumulateDuels ? "accumulate" : "exclusive")
                        + " | battleIsolation " + rules(ISOLATION) + " | selfDefense " + rules(SELF_DEFENSE) + " | friendlyFire " + rules(FRIENDLY_FIRE)
                        + " | playerFights " + rules(PLAYER_FIGHTS) + ", battlePvp " + onOff(battlePvp)
                        + " | buff " + Buffs.describe(),
                "Arenas: arena " + kinds(ARENA, v -> v.name().toLowerCase(java.util.Locale.ROOT))
                        + " | timer " + kinds(TIMER, MobBattleCommand::formatDuration)
                        + " | timeUp " + kinds(TIME_UP, v -> v.name().toLowerCase(java.util.Locale.ROOT))
                        + " | judgeBy " + kinds(JUDGE_BY, v -> v)
                        + " | arenaShrink " + rules(SHRINK) + " to " + shrinkTo + ", over " + MobBattleCommand.formatDuration(shrinkSeconds)
                        + " with no timer | scoreboard " + rules(SCOREBOARD) + " | unstick " + onOff(unstick) + ", standoffTime " + (standoffSeconds > 0 ? standoffSeconds + " s" : "off") + ", passiveWin " + (passiveWinSeconds > 0 ? passiveWinSeconds + " s" : "off") + " (ignore " + (passiveWinIgnore.isEmpty() ? "none" : passiveWinIgnore) + ")"
                        + " | duelCard " + onOff(duelCard)
                        + " | arenaSize " + (arenaSize > 0 ? arenaSize : "auto") + ", timerStyle " + timerStyle + ", tieBreak " + tieBreak + ", snapMode " + snapMode
                        + ", snapPercent " + snapPercent + "%, snapTime " + snapSeconds + " s, snapHitDelay " + snapHitDelayMs + " ms, snapRadius " + snapRadius,
                "Chaos area: areaStyle " + areaStyle + ", areaColor " + areaColor.toLowerCase() + ", areaRadius " + radius
                        + ", areaHeight " + pillarHeight + ", areaOnlyWand " + onOff(areaOnlyWand) + ", maxMobs " + maxMobs
                        + ", summonRadius " + (summonRadius > 0 ? summonRadius : "auto") + ", summonDespawn " + summonDespawn
                        + ", summonLifetime " + summonLifetime + " s, summonExit " + summonExit + ", summonArmy " + onOff(summonArmy) + ", summonJade " + onOff(summonJade) + ", summonJadeColor " + summonJadeColor.toLowerCase() + " | " + ActionFx.describe() + " (wave/borderColor)",
                "Team look: teamByType " + onOff(teamByType) + ", teamLook " + teamFx + ", time " + (teamFxSeconds > 0 ? teamFxSeconds + " s" : "infinite")
                        + ", onlyWand " + onOff(teamLookOnlyWand) + " | armyTint " + onOff(armyTint) + ", armyTintColor " + armyTintColor.toLowerCase(),
                "Wands: wandEffect " + effect + ", wandFx " + onOff(wandFx) + ", killDelay " + (killDelayMs / 1000f) + " s, killHitDelay " + killHitDelayMs + " ms, killLoot " + onOff(killLoot) + ", killPlayers " + onOff(killPlayers) + ", snapPlayers " + onOff(snapPlayers) + ", morphPlayers " + onOff(morphPlayers) + ", morphBurst " + onOff(morphBurst) + ", morphShuffle " + onOff(morphShuffle) + ", morphBurstRadius " + morphBurstRadius + ", cleanseColor " + cleanseColor.toLowerCase() + ", cleanseArea " + onOff(cleanseArea) + ", cleanseRadius " + cleanseRadius + ", buffColor " + buffColor.toLowerCase() + ", buffLight " + buffLight.toLowerCase() + ", healLight " + healLight.toLowerCase() + ", lookGlow " + lookGlow + ", deathGlow " + deathGlow + ", debugTrack " + debugTrackSeconds + " s, debugLog " + onOff(debug) + ", debugRecord " + onOff(debugRecord),
                "Permissions: " + permissionsMode + " (/mobbattle permissions open|server)",
        };
    }

    /** Which describeLines() line a setting key belongs to. */
    public static int groupOf(String key) {
        return switch (key) {
            case "title", "wintitle", "style", "countdown", "titlescope", "camera", "music", "runes", "camerastyle", "cameratime", "camerashots", "endcamera", "endcameratime", "camerascope", "autowatch" -> 0;
            case "laser", "color" -> 1;
            case "isolation", "battleisolation", "selfdefense", "friendlyfire", "duelmode", "playerfights", "battlepvp", "buff" -> 2;
            case "scoreboard", "arena", "timer", "timeup", "judgeby", "arenasize", "arenashrink", "arenashrinkto", "arenashrinktime", "unstick", "standofftime", "passivewin", "passivewinignore", "duelcard", "timerstyle", "tiebreak", "snapmode", "snappercent", "snaptime", "snaphitdelay", "snapradius" -> 3;
            case "debuglog", "debugrecord", "debugtrack", "snapplayers", "morphplayers", "morphburst", "morphshuffle", "morphburstradius", "cleansearea", "cleanseradius", "killhitdelay", "cleansecolor", "buffcolor", "bufflight", "heallight" -> 6;
            case "radius", "area", "areacolor", "wave", "glow", "mobfx", "crown", "height", "areaonlywand", "maxmobs", "summonradius",
                 "summondespawn", "summonlifetime", "summonexit", "summonarmy", "summonjade", "summonjadecolor" -> 4;
            case "teamfx", "teambytype", "armytint", "armytintcolor" -> 5;
            case "permissions" -> 7;
            default -> 6;
        };
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
