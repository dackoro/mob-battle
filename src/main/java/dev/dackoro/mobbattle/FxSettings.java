package dev.dackoro.mobbattle;

import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;

import java.io.IOException;
import java.io.InputStream;
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
    public static final int MAX_RADIUS = 2048;
    /** Above this radius (areaRadius, snapRadius, wave radii) the panel and command warn: past view distance mobs are not loaded. */
    public static final int RADIUS_WARN = 128;

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
    /**
     * Seconds to get in position before the VS screen and the countdown (/mobbattle readyTime, KindFx READY_TIME,
     * ReadyWait; user, 2026-10-20). Default 0 = none; crouching starts it at once.
     */
    public static int readySeconds = 0;
    public static final int MAX_READY = 60;

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
    static final int MAX_STANDOFF_SECONDS = 3600;
    /**
     * /mobbattle passiveWin: once only one side (or none) can still fight back, seconds without a hit before that
     * side wins (none: a draw), with an alert bottom left (BattleManager.passiveEnd). 0 = off. Default 15.
     */
    public static int passiveWinSeconds = 15;
    /**
     * How far below / above the center a royale or all vs one takes mobs, and how far the arena wall lets them go
     * before bringing them back (2026-10-14, user: a surface battle pulled in mobs from a deep cave; keep shallow caves
     * and fliers, optional). Blocks, 0 = no limit (the whole sphere, as before). Defaults 50 below (user: wide, but not a deep magma cave), 40 above.
     */
    public static int battleBelow = 50, battleAbove = 40;
    /**
     * How far above or below the arena's center height a fighter may go before the arena wall brings it back (cave
     * shafts, cliffs; fliers never). Separate from battleBelow/Above, which only choose who joins (user, 2026-10-14).
     * Blocks, 0 = no limit (default).
     */
    public static int arenaVertical = 0;
    static final int MAX_BATTLE_DEPTH = 512;
    static final int MAX_PASSIVE_WIN_SECONDS = 3600;
    /**
     * /mobbattle idleEnd (2026-10-13, user): a battle where no fighter hurts an enemy for this long ends in a draw
     * (a royale of defensive animals stood still for its whole 3 min timer). 0 = off.
     */
    public static int idleEndSeconds = 150;
    static final int MAX_IDLE_END_SECONDS = 3600;
    /**
     * /mobbattle battleMaxTime (2026-10-13, user: an infinite battle option): minutes after which a battle with no winner
     * ends anyway (was a fixed 15 min, BattleManager.MAX_MILLIS). 0 = never (infinite). A longer arena timer still
     * pushes it past the timer's end (Arena.setup).
     */
    public static int battleMaxMinutes = 15;
    static final int MAX_BATTLE_MINUTES = 10080;
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
    /** All vs one card: the lone target's stats and the attackers (/mobbattle focusCard). Default: off. */
    public static boolean focusCard = false;

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
    /**
     * Server features (2026-10-18, user: a server owner can turn parts of the mod off; on by default). Off = the
     * feature does nothing AND its pictures are neither drawn nor registered at boot, so joining players do not
     * download them (that part takes a server restart). Admin only, never changed by presets, resets or Undo.
     * VS screen (keys server.vs), Morph Wand hologram (server.hologram), panel mob pictures (server.icons).
     */
    public static volatile boolean serverVs = true;
    public static volatile boolean serverHolograms = true;
    public static volatile boolean serverIcons = true;
    static final String[] SERVER_KEYS = {"server.vs", "server.hologram", "server.icons"};
    /** Debug Wand: seconds a left-clicked mob is followed after its report (/mobbattle debugTrack, 0 = report only). */
    public static int debugTrackSeconds = 30;
    static final int MAX_DEBUG_TRACK_SECONDS = 600;
    /** timeUp health/judge when even on health: "damage" (default), "random" or "draw" (/mobbattle tieBreak). */
    public static String tieBreak = "damage";
    /** Kill table ties (/mobbattle killTieBreak, KindFx): damage (default), alive, first or shared. */
    public static String killTieBreak = "damage";
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
    /**
     * Lasting look of a wand-, streak- or champion-buffed mob (/mobbattle buffLook, 2026-10-13, user: a Buff Wand
     * without the shimmer): "shimmer" (veins, eye flames and runes, default), "runes" (only eye flames and runes, the
     * skin stays as it is) or "off" (no lasting look; the click's mark, swirl and light are wandFx/buffLight).
     */
    public static String buffLook = "shimmer";
    static final java.util.List<String> BUFF_LOOKS = java.util.List.of("shimmer", "runes", "off");

    /** Canonical buffLook value, or null if it is not one. */
    static String parseBuffLook(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("none") || v.equals("false")) v = "off";
        return BUFF_LOOKS.contains(v) ? v : null;
    }

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
    public static final int MAX_SNAP_SECONDS = 600;
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
    public static final int MAX_ARENA = 1024;
    public static final int ARENA_WARN = 128;
    public static final int MAX_TIMER = 86400;

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
        String rule = KindFx.get(kind, KindFx.Opt.TIE_BREAK);
        if (rule.equals(by)) return by.equals("damage") ? "kills" : "damage";
        return rule;
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
    public static final int MAX_HEIGHT = 512;
    public static final int HEIGHT_WARN = 64;

    /** Summon area radius in blocks (/mobbattle summonradius); 0 = auto, grows with how many mobs. */
    public static int summonRadius = 0;
    public static final int MAX_SUMMON_RADIUS = 512;
    public static final int SUMMON_RADIUS_WARN = 64;

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
    /**
     * Hits inside one army (mates, a leader and its mobs) do their damage instead of being cancelled, and nobody fights
     * back or calls the others: they stay allies (/mobbattle armyFriendlyFire, key army.friendlyfire, 2026-10-14,
     * user). Default off.
     */
    public static boolean armyFriendlyFire = false;
    /** Color of the army tint (/mobbattle armyTintColor), a palette color. Default blue (the Army Wand color). */
    public static String armyTintColor = "Blue";
    /** How a summon leaves: disintegrate (the Kill Wand's soul rip, no loot), vanish (removed at once) or shatter (jade glass) (/mobbattle summonExit). */
    public static String summonExit = SummonDespawn.DISINTEGRATE;
    /** Summons wait calm until a battle, wand fight, army or hit (/mobbattle summonCalm): off (default), summons (they ignore each other) or all (players and other mobs too). */
    public static String summonCalm = SummonCalm.OFF;

    /** Max mobs one Chaos Wand click, Snap or summon affects (/mobbattle maxmobs). Mostly limited by the game's own AI cost. */
    public static int maxMobs = 200;
    /**
     * Highest maxMobs (and summon list count) allowed: no real cap on purpose (user, 2026-10-13: "5000 if I want,
     * my PC is my problem"); above {@link #MAX_MOBS_WARN} the command and the panel only warn.
     */
    public static final int MAX_MOBS_LIMIT = 100000;
    /** Above this maxMobs the server may lag: a warning, never a cap. */
    public static final int MAX_MOBS_WARN = 60;

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
    /** Watch: how the camera goes to another fighter, cut (at once) or glide (it travels there through blocks) (/mobbattle watchTransition). */
    public static String watchTransition = "glide";
    static final String[] WATCH_TRANSITIONS = {"cut", "glide"};
    /** Watch: seconds on each fighter before the camera goes to the next by itself, 0 = off (/mobbattle watchAutoSwitch). */
    public static int watchAutoSwitch = 0;
    static final int MIN_WATCH_AUTO_SWITCH = 3;
    static final int MAX_WATCH_AUTO_SWITCH = 600;
    /** Black cinema bars over the intro and end cameras (/mobbattle cameraBars): off, slim, classic or wide (KindFx). */
    public static String cameraBars = "classic";
    /** /mobbattle cameraHud: hide = the game's HUD (hotbar, compass, boss bar...) goes away while a camera films (CameraHud). */
    public static String cameraHud = "hide";
    /**
     * /mobbattle cameraFace on|off (2026-10-16, user): close-ups (spotlight, duel eyes, film) stand in front of the
     * mob's face, wherever it looks; off = toward the battle / the opponent, as before. Either way they keep clear of
     * walls and other mobs.
     */
    public static boolean cameraFace = true;
    static final String[] CAMERA_HUDS = {"hide", "show"};
    /** Background blur of the intro and end cameras, focused on what each shot films (/mobbattle cameraBlur): off, soft or strong. */
    public static String cameraBlur = "soft";
    /** The camera viewers get the battle film (FilmCamera, /mobbattle film) from the battle start (/mobbattle autoFilm, KindFx). */
    public static boolean autoFilm = false;
    /** Smash-like VS screen over the first seconds of the countdown, before the intro camera (VsScreen, /mobbattle vsScreen, KindFx). */
    public static boolean vsScreen = true;
    /** VS screen, 3+ teams: columns per team with 4 teams or fewer, 1 or 2 (/mobbattle vsTeamReps, camera.vs.teamreps). */
    public static int vsTeamReps = 2;
    /** Battle film: share of shots that show the player watching (percent, /mobbattle filmPlayerShots). */
    public static int filmPlayerShots = 20;
    /** Battle film: share of battle shots that are far wide shots (percent, /mobbattle filmWideShots; +15 with a big mob). */
    public static int filmWideShots = 30;
    /**
     * Battle film: how often each shot type comes up, percent of its usual weight (/mobbattle filmShot <shot> <0-300>,
     * keys camera.film.weight.<shot>; 2026-10-16, user: a percent per shot instead of on/off buttons; 0 = never,
     * 200 = twice as often). Defaults in FILM_DEFAULT_WEIGHTS (2026-10-16, user: "all the shots are good, which
     * weights for great cinematics?"): the showy ones lower so they stay a surprise.
     */
    static final java.util.Map<String, Integer> filmWeights = new java.util.concurrent.ConcurrentHashMap<>();
    static final int MAX_FILM_WEIGHT = 300;
    static final java.util.Map<String, Integer> FILM_DEFAULT_WEIGHTS = java.util.Map.ofEntries(
            java.util.Map.entry("wide", 100), java.util.Map.entry("track", 100), java.util.Map.entry("arc", 100),
            java.util.Map.entry("pushin", 100), java.util.Map.entry("closeup", 100), java.util.Map.entry("landscape", 50),
            java.util.Map.entry("playerback", 100), java.util.Map.entry("playerclose", 100), java.util.Map.entry("zenith", 40),
            java.util.Map.entry("spiral", 80), java.util.Map.entry("telephoto", 70), java.util.Map.entry("descent", 70),
            java.util.Map.entry("groundrise", 50), java.util.Map.entry("profile", 100));
    /** Shots that came after the old on/off list (an old camera.film.shots never mentions them). */
    static final java.util.Set<String> FILM_NEWER_SHOTS = java.util.Set.of("zenith", "spiral", "telephoto", "descent", "groundrise", "profile");

    static int filmWeight(String shot) {
        return filmWeights.getOrDefault(shot, FILM_DEFAULT_WEIGHTS.getOrDefault(shot, 100));
    }

    /** "wide 100%, track 100%, ..." for /mobbattle settings. */
    static String filmWeightsText() {
        StringBuilder sb = new StringBuilder();
        for (String w : FilmCamera.SHOT_WORDS) sb.append(sb.length() > 0 ? ", " : "").append(w).append(' ').append(filmWeight(w)).append('%');
        return sb.toString();
    }
    /** Battle film: length of each shot in seconds (/mobbattle filmShotTime). */
    public static int filmShotSeconds = 8;
    /** Battle film: how far the camera moves in a shot, percent of normal (/mobbattle filmPace). */
    public static int filmPace = 100;
    /** Battle film: each shot lasts filmShotTime plus or minus up to this percent, at random (/mobbattle filmShotVariation). */
    public static int filmShotVariation = 25;
    /** Battle film: how one shot goes to the next, cut (at once) or glide (the camera travels) (/mobbattle filmTransition). */
    public static String filmTransition = "cut";
    static final String[] FILM_TRANSITIONS = {"cut", "glide"};
    static final int MIN_FILM_SHOT = 3;
    static final int MAX_FILM_SHOT = 60;
    static final int MIN_FILM_PACE = 25;
    static final int MAX_FILM_PACE = 300;
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
    /** Effect Wand held left click: every mob and player in effectRadius gets the effect (/mobbattle effectArea). */
    public static boolean effectArea = true;
    /** Effect Wand held click: reach in blocks (/mobbattle effectRadius). */
    public static final int DEFAULT_EFFECT_RADIUS = 12;
    public static int effectRadius = DEFAULT_EFFECT_RADIUS;
    /** Heal Wand held left click: every mob and player in healRadius, the caster too, is healed (/mobbattle healArea). */
    public static boolean healArea = true;
    /**
     * Held-click waves: whether the caster is in their own wave (2026-10-13, user: "not heal me", "always cleanse me").
     * Heal on by default (it always healed the caster), the others off (they never touched the caster). Keys
     * heal.area.self, cleanse.area.self, effect.area.self, morph.burst.self (/mobbattle healAreaSelf ...).
     */
    public static boolean healAreaSelf = true;
    public static boolean cleanseAreaSelf = false;
    public static boolean effectAreaSelf = false;
    public static boolean morphBurstSelf = false;
    /**
     * Kill Wand held left click: every mob in killRadius dies, never the caster nor their own army (/mobbattle killArea);
     * players only with killAreaPlayers (and killPlayers) on.
     */
    public static boolean killArea = true;
    public static final int DEFAULT_KILL_RADIUS = 12;
    public static int killRadius = DEFAULT_KILL_RADIUS;
    public static boolean killAreaPlayers = false;
    /** Heal Wand held click: reach in blocks (/mobbattle healRadius). */
    public static final int DEFAULT_HEAL_RADIUS = 12;
    public static int healRadius = DEFAULT_HEAL_RADIUS;
    /** Wand waves (Morph, Cleanse, Effect, Heal): each target is hit when the ring reaches it (/mobbattle waveTravel). */
    public static boolean waveTravel = true;
    /** Wand waves: only targets near the ground of the wave, as vanilla slams (/mobbattle waveGroundOnly). */
    public static boolean waveGroundOnly = false;
    /** Snap, royale and all-vs-one waves: each target is hit when the ring reaches it (/mobbattle battleWaveTravel). */
    public static boolean battleWaveTravel = false;
    /** Snap, royale and all-vs-one waves: only targets near the ground (/mobbattle battleWaveGroundOnly). */
    public static boolean battleWaveGroundOnly = false;

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
        if (!focusCard) FocusCard.clearAll();
        Buffs.refreshLookAll();
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
            String calm = SummonCalm.parseMode(p.getProperty("summon.calm"));
            if (calm != null) summonCalm = calm;
            summonJade = Boolean.parseBoolean(p.getProperty("summon.jade", String.valueOf(summonJade)));
            summonArmy = Boolean.parseBoolean(p.getProperty("summon.army", String.valueOf(summonArmy)));
            String jadeColor = SummonList.parseJadeColor(p.getProperty("summon.jade.color"));
            if (jadeColor != null) summonJadeColor = jadeColor;
            armyTint = Boolean.parseBoolean(p.getProperty("army.tint", String.valueOf(armyTint)));
            armyFriendlyFire = Boolean.parseBoolean(p.getProperty("army.friendlyfire", String.valueOf(armyFriendlyFire)));
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
            String buffLookValue = parseBuffLook(p.getProperty("wand.buff.look", buffLook));
            if (buffLookValue != null) buffLook = buffLookValue;
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
            try {
                readySeconds = Math.max(0, Math.min(MAX_READY,
                        Integer.parseInt(p.getProperty("fight.ready", String.valueOf(readySeconds)).trim())));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            wandFx = Boolean.parseBoolean(p.getProperty("wand.fx", String.valueOf(wandFx)));
            debug = Boolean.parseBoolean(p.getProperty("debug", String.valueOf(debug)));
            debugRecord = Boolean.parseBoolean(p.getProperty("debug.record", String.valueOf(debugRecord)));
            serverVs = Boolean.parseBoolean(p.getProperty("server.vs", String.valueOf(serverVs)));
            serverHolograms = Boolean.parseBoolean(p.getProperty("server.hologram", String.valueOf(serverHolograms)));
            serverIcons = Boolean.parseBoolean(p.getProperty("server.icons", String.valueOf(serverIcons)));
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
            // One key each (audit 2026-10-14: a bad value in one of them skipped the others).
            battleBelow = Math.max(0, Math.min(MAX_BATTLE_DEPTH, intOr(p, "battle.below", battleBelow)));
            battleAbove = Math.max(0, Math.min(MAX_BATTLE_DEPTH, intOr(p, "battle.above", battleAbove)));
            arenaVertical = Math.max(0, Math.min(MAX_BATTLE_DEPTH, intOr(p, "battle.arena.vertical", arenaVertical)));
            passiveWinSeconds = Math.max(0, Math.min(MAX_PASSIVE_WIN_SECONDS, intOr(p, "battle.passivewin", passiveWinSeconds)));
            battleMaxMinutes = Math.max(0, Math.min(MAX_BATTLE_MINUTES, intOr(p, "battle.maxtime", battleMaxMinutes)));
            try {
                idleEndSeconds = Math.max(0, Math.min(MAX_IDLE_END_SECONDS,
                        Integer.parseInt(p.getProperty("battle.idleend", String.valueOf(idleEndSeconds)))));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
            String ignore = parsePassiveWinIgnore(p.getProperty("battle.passivewin.ignore", passiveWinIgnore));
            if (ignore != null) passiveWinIgnore = ignore;
            duelCard = Boolean.parseBoolean(p.getProperty("battle.duelCard", String.valueOf(duelCard)));
            focusCard = Boolean.parseBoolean(p.getProperty("battle.focusCard", String.valueOf(focusCard)));
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
            String ktb = KindFx.parse(KindFx.Opt.KILL_TIE_BREAK, p.getProperty("battle.killtiebreak", killTieBreak));
            killTieBreak = ktb != null ? ktb : "damage";
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
            String wt = p.getProperty("camera.watch.transition", watchTransition).trim().toLowerCase(java.util.Locale.ROOT);
            watchTransition = java.util.Arrays.asList(WATCH_TRANSITIONS).contains(wt) ? wt : "glide";
            int was = intOr(p, "camera.watch.autoswitch", watchAutoSwitch);
            watchAutoSwitch = was <= 0 ? 0 : Math.max(MIN_WATCH_AUTO_SWITCH, Math.min(MAX_WATCH_AUTO_SWITCH, was));
            String bars = KindFx.parse(KindFx.Opt.CAMERA_BARS, p.getProperty("camera.bars", cameraBars));
            cameraBars = bars != null ? bars : "classic";
            String hud = p.getProperty("camera.hud", cameraHud).trim().toLowerCase(java.util.Locale.ROOT);
            cameraHud = java.util.Arrays.asList(CAMERA_HUDS).contains(hud) ? hud : "hide";
            cameraFace = Boolean.parseBoolean(p.getProperty("camera.face", String.valueOf(cameraFace)));
            String blur = KindFx.parse(KindFx.Opt.CAMERA_BLUR, p.getProperty("camera.blur", cameraBlur));
            cameraBlur = blur != null ? blur : "soft";
            autoFilm = Boolean.parseBoolean(p.getProperty("camera.autofilm", String.valueOf(autoFilm)));
            vsScreen = Boolean.parseBoolean(p.getProperty("camera.vsscreen", String.valueOf(vsScreen)));
            vsTeamReps = Math.max(1, Math.min(2, intOr(p, "camera.vs.teamreps", vsTeamReps)));
            filmPlayerShots = Math.max(0, Math.min(100, intOr(p, "camera.film.player", filmPlayerShots)));
            filmWideShots = Math.max(0, Math.min(100, intOr(p, "camera.film.wide", filmWideShots)));
            filmShotSeconds = Math.max(MIN_FILM_SHOT, Math.min(MAX_FILM_SHOT, intOr(p, "camera.film.shot", filmShotSeconds)));
            filmPace = Math.max(MIN_FILM_PACE, Math.min(MAX_FILM_PACE, intOr(p, "camera.film.pace", filmPace)));
            filmShotVariation = Math.max(0, Math.min(90, intOr(p, "camera.film.variation", filmShotVariation)));
            String ft = p.getProperty("camera.film.transition", filmTransition).toLowerCase(java.util.Locale.ROOT);
            filmTransition = java.util.Arrays.asList(FILM_TRANSITIONS).contains(ft) ? ft : "cut";
            // Older files: camera.film.shots (on/off list) and camera.film.closeups (percent) set the defaults.
            String oldList = p.getProperty("camera.film.shots");
            String parsed = oldList != null ? FilmCamera.parseShots(oldList) : null;
            java.util.Set<String> listed = parsed != null ? java.util.Set.of(parsed.split(",")) : null;
            java.util.Map<String, Integer> weights = new java.util.HashMap<>();
            for (String w : FilmCamera.SHOT_WORDS) {
                int def = FILM_DEFAULT_WEIGHTS.getOrDefault(w, 100);
                if (listed != null && !FILM_NEWER_SHOTS.contains(w) && !listed.contains(w)) def = 0;
                if (w.equals("closeup") && def > 0) def = intOr(p, "camera.film.closeups", def);
                weights.put(w, Math.max(0, Math.min(MAX_FILM_WEIGHT, intOr(p, "camera.film.weight." + w, def))));
            }
            filmWeights.clear();
            filmWeights.putAll(weights);
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
            effectArea = Boolean.parseBoolean(p.getProperty("effect.area", String.valueOf(effectArea)));
            int er = intOr(p, "effect.area.radius", effectRadius);
            effectRadius = er <= 0 ? DEFAULT_EFFECT_RADIUS : clampRadius(er);
            healArea = Boolean.parseBoolean(p.getProperty("heal.area", String.valueOf(healArea)));
            healAreaSelf = Boolean.parseBoolean(p.getProperty("heal.area.self", String.valueOf(healAreaSelf)));
            cleanseAreaSelf = Boolean.parseBoolean(p.getProperty("cleanse.area.self", String.valueOf(cleanseAreaSelf)));
            effectAreaSelf = Boolean.parseBoolean(p.getProperty("effect.area.self", String.valueOf(effectAreaSelf)));
            morphBurstSelf = Boolean.parseBoolean(p.getProperty("morph.burst.self", String.valueOf(morphBurstSelf)));
            int hr = intOr(p, "heal.area.radius", healRadius);
            healRadius = hr <= 0 ? DEFAULT_HEAL_RADIUS : clampRadius(hr);
            killArea = Boolean.parseBoolean(p.getProperty("kill.area", String.valueOf(killArea)));
            int kr = intOr(p, "kill.area.radius", killRadius);
            killRadius = kr <= 0 ? DEFAULT_KILL_RADIUS : clampRadius(kr);
            killAreaPlayers = Boolean.parseBoolean(p.getProperty("kill.area.players", String.valueOf(killAreaPlayers)));
            waveTravel = Boolean.parseBoolean(p.getProperty("wand.wave.travel", String.valueOf(waveTravel)));
            waveGroundOnly = Boolean.parseBoolean(p.getProperty("wand.wave.groundonly", String.valueOf(waveGroundOnly)));
            battleWaveTravel = Boolean.parseBoolean(p.getProperty("battle.wave.travel", String.valueOf(battleWaveTravel)));
            battleWaveGroundOnly = Boolean.parseBoolean(p.getProperty("battle.wave.groundonly", String.valueOf(battleWaveGroundOnly)));
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
            storeAtomically(file, p, "Mob Battle fight start effects (/mobbattle)");
        } catch (IOException e) {
            MobBattlePlugin.log("Could not save " + file + ": " + e);
        }
    }

    /** One write at a time through writeAtomically (small, rare files: fx.properties, presets, summon lists, armies). */
    private static final Object WRITE_LOCK = new Object();

    /**
     * Writes {@code bytes} to {@code file} through {@code <file>.tmp} and a rename, so a stop halfway through a write
     * leaves the old file whole instead of a cut one (as PictureCache and vanilla BsonUtil.writeDocumentSync do).
     */
    static void writeAtomically(Path file, byte[] bytes) throws IOException {
        synchronized (WRITE_LOCK) {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /** {@code p} as Properties.store writes it, through writeAtomically. */
    static void storeAtomically(Path file, Properties p, String comment) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        p.store(out, comment);
        writeAtomically(file, out.toByteArray());
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
        p.setProperty("fight.ready", String.valueOf(readySeconds));
        p.setProperty("wand.fx", String.valueOf(wandFx));
        p.setProperty("debug", String.valueOf(debug));
        p.setProperty("debug.record", String.valueOf(debugRecord));
        p.setProperty("server.vs", String.valueOf(serverVs));
        p.setProperty("server.hologram", String.valueOf(serverHolograms));
        p.setProperty("server.icons", String.valueOf(serverIcons));
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
        p.setProperty("battle.below", String.valueOf(battleBelow));
        p.setProperty("battle.above", String.valueOf(battleAbove));
        p.setProperty("battle.arena.vertical", String.valueOf(arenaVertical));
        p.setProperty("battle.passivewin.ignore", passiveWinIgnore);
        p.setProperty("battle.idleend", String.valueOf(idleEndSeconds));
        p.setProperty("battle.maxtime", String.valueOf(battleMaxMinutes));
        p.setProperty("battle.duelCard", String.valueOf(duelCard));
        p.setProperty("battle.focusCard", String.valueOf(focusCard));
        p.setProperty("battle.shrink.time", String.valueOf(shrinkSeconds));
        p.setProperty("battle.timer.style", timerStyle);
        p.setProperty("battle.tiebreak", tieBreak);
        p.setProperty("battle.killtiebreak", killTieBreak);
        p.setProperty("snap.mode", snapMode);
        p.setProperty("snap.percent", String.valueOf(snapPercent));
        Buffs.save(p);
        p.setProperty("wand.cleanse.color", cleanseColor);
        p.setProperty("wand.buff.color", buffColor);
        p.setProperty("wand.buff.light", buffLight);
        p.setProperty("wand.buff.look", buffLook);
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
        p.setProperty("summon.calm", summonCalm);
        p.setProperty("summon.jade", String.valueOf(summonJade));
        p.setProperty("summon.army", String.valueOf(summonArmy));
        p.setProperty("summon.jade.color", summonJadeColor);
        p.setProperty("army.tint", String.valueOf(armyTint));
        p.setProperty("army.friendlyfire", String.valueOf(armyFriendlyFire));
        p.setProperty("army.tint.color", armyTintColor);
        p.setProperty("chaos.height", String.valueOf(pillarHeight));
        p.setProperty("camera.style", cameraStyle);
        p.setProperty("camera.time", String.valueOf(cameraSeconds));
        p.setProperty("camera.shots", String.valueOf(cameraShots));
        p.setProperty("camera.end", endCamera);
        p.setProperty("camera.end.time", String.valueOf(endCameraSeconds));
        p.setProperty("camera.autowatch", String.valueOf(autoWatch));
        p.setProperty("camera.watch.transition", watchTransition);
        p.setProperty("camera.watch.autoswitch", String.valueOf(watchAutoSwitch));
        p.setProperty("camera.bars", cameraBars);
        p.setProperty("camera.hud", cameraHud);
        p.setProperty("camera.face", String.valueOf(cameraFace));
        p.setProperty("camera.blur", cameraBlur);
        p.setProperty("camera.autofilm", String.valueOf(autoFilm));
        p.setProperty("camera.vsscreen", String.valueOf(vsScreen));
        p.setProperty("camera.vs.teamreps", String.valueOf(vsTeamReps));
        p.setProperty("camera.film.player", String.valueOf(filmPlayerShots));
        p.setProperty("camera.film.wide", String.valueOf(filmWideShots));
        p.setProperty("camera.film.shot", String.valueOf(filmShotSeconds));
        p.setProperty("camera.film.pace", String.valueOf(filmPace));
        p.setProperty("camera.film.variation", String.valueOf(filmShotVariation));
        p.setProperty("camera.film.transition", filmTransition);
        for (String w : FilmCamera.SHOT_WORDS) p.setProperty("camera.film.weight." + w, String.valueOf(filmWeight(w)));
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
        p.setProperty("heal.area.self", String.valueOf(healAreaSelf));
        p.setProperty("cleanse.area.self", String.valueOf(cleanseAreaSelf));
        p.setProperty("effect.area.self", String.valueOf(effectAreaSelf));
        p.setProperty("morph.burst.self", String.valueOf(morphBurstSelf));
        p.setProperty("cleanse.area.radius", String.valueOf(cleanseRadius));
        p.setProperty("effect.area", String.valueOf(effectArea));
        p.setProperty("effect.area.radius", String.valueOf(effectRadius));
        p.setProperty("heal.area", String.valueOf(healArea));
        p.setProperty("heal.area.radius", String.valueOf(healRadius));
        p.setProperty("kill.area", String.valueOf(killArea));
        p.setProperty("kill.area.radius", String.valueOf(killRadius));
        p.setProperty("kill.area.players", String.valueOf(killAreaPlayers));
        p.setProperty("wand.wave.travel", String.valueOf(waveTravel));
        p.setProperty("wand.wave.groundonly", String.valueOf(waveGroundOnly));
        p.setProperty("battle.wave.travel", String.valueOf(battleWaveTravel));
        p.setProperty("battle.wave.groundonly", String.valueOf(battleWaveGroundOnly));
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
                "Titles: countdown " + countdownSeconds + " s, readyTime " + readySeconds + " s, startTitle " + onOff(titleEnabled) + ", winTitle " + onOff(winTitleEnabled) + ", titleStyle " + titleStyle + ", titleScope " + titleScope + ", camera " + onOff(fightCamera) + ", music " + (battleMusic ? musicTrack : "off")
                        + ", runes " + onOff(runes) + " | cameraStyle " + cameraStyle + ", cameraTime " + (cameraSeconds > 0 ? cameraSeconds + " s" : "countdown")
                        + ", cameraShots " + cameraShots + ", endCamera " + endCamera + ", endCameraTime " + endCameraSeconds + " s, cameraScope " + cameraScope + ", autoWatch " + onOff(autoWatch)
                        + ", watchTransition " + watchTransition + ", watchAutoSwitch " + (watchAutoSwitch > 0 ? watchAutoSwitch + " s" : "off")
                        + ", cameraBars " + cameraBars + ", cameraBlur " + cameraBlur + ", cameraHud " + cameraHud + ", cameraFace " + onOff(cameraFace)
                        + ", vsScreen " + onOff(vsScreen) + ", vsTeamReps " + vsTeamReps + ", autoFilm " + onOff(autoFilm) + ", filmPlayerShots " + filmPlayerShots + "%, filmWideShots " + filmWideShots + "%, filmShotTime " + filmShotSeconds + " s, filmShotVariation " + filmShotVariation + "%, filmPace " + filmPace + "%, filmTransition " + filmTransition + ", filmShot " + filmWeightsText()
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
                        + " with no timer | scoreboard " + rules(SCOREBOARD) + " | unstick " + onOff(unstick) + ", standoffTime " + (standoffSeconds > 0 ? standoffSeconds + " s" : "off") + ", battleBelow " + (battleBelow > 0 ? battleBelow : "off") + ", battleAbove " + (battleAbove > 0 ? battleAbove : "off") + ", arenaVertical " + (arenaVertical > 0 ? arenaVertical : "off") + ", passiveWin " + (passiveWinSeconds > 0 ? passiveWinSeconds + " s" : "off") + " (ignore " + (passiveWinIgnore.isEmpty() ? "none" : passiveWinIgnore) + "), idleEnd " + (idleEndSeconds > 0 ? idleEndSeconds + " s" : "off") + ", battleMaxTime " + (battleMaxMinutes > 0 ? battleMaxMinutes + " min" : "off (infinite)")
                        + " | duelCard " + onOff(duelCard) + ", focusCard " + onOff(focusCard)
                        + " | arenaSize " + (arenaSize > 0 ? arenaSize : "auto") + ", timerStyle " + timerStyle + ", tieBreak " + tieBreak + ", killTieBreak " + killTieBreak + ", snapMode " + snapMode
                        + ", snapPercent " + snapPercent + "%, snapTime " + snapSeconds + " s, snapHitDelay " + snapHitDelayMs + " ms, snapRadius " + snapRadius,
                "Chaos area: areaStyle " + areaStyle + ", areaColor " + areaColor.toLowerCase() + ", areaRadius " + radius
                        + ", areaHeight " + pillarHeight + ", areaOnlyWand " + onOff(areaOnlyWand) + ", maxMobs " + maxMobs
                        + ", summonRadius " + (summonRadius > 0 ? summonRadius : "auto") + ", summonDespawn " + summonDespawn
                        + ", summonLifetime " + summonLifetime + " s, summonExit " + summonExit + ", summonCalm " + summonCalm + ", summonArmy " + onOff(summonArmy) + ", summonJade " + onOff(summonJade) + ", summonJadeColor " + summonJadeColor.toLowerCase() + " | " + ActionFx.describe() + " (wave/borderColor)",
                "Team look: teamByType " + onOff(teamByType) + ", teamLook " + teamFx + ", time " + (teamFxSeconds > 0 ? teamFxSeconds + " s" : "infinite")
                        + ", onlyWand " + onOff(teamLookOnlyWand) + " | armyTint " + onOff(armyTint) + ", armyTintColor " + armyTintColor.toLowerCase() + ", armyFriendlyFire " + onOff(armyFriendlyFire),
                "Wands: wandEffect " + effect + ", effectArea " + onOff(effectArea) + ", effectRadius " + effectRadius + ", healArea " + onOff(healArea) + ", healAreaSelf " + onOff(healAreaSelf) + ", effectAreaSelf " + onOff(effectAreaSelf) + ", cleanseAreaSelf " + onOff(cleanseAreaSelf) + ", morphBurstSelf " + onOff(morphBurstSelf) + ", healRadius " + healRadius + ", waveTravel " + onOff(waveTravel) + ", waveGroundOnly " + onOff(waveGroundOnly) + ", battleWaveTravel " + onOff(battleWaveTravel) + ", battleWaveGroundOnly " + onOff(battleWaveGroundOnly) + ", wandFx " + onOff(wandFx) + ", killDelay " + (killDelayMs / 1000f) + " s, killHitDelay " + killHitDelayMs + " ms, killLoot " + onOff(killLoot) + ", killPlayers " + onOff(killPlayers) + ", killArea " + onOff(killArea) + ", killRadius " + killRadius + ", killAreaPlayers " + onOff(killAreaPlayers) + ", snapPlayers " + onOff(snapPlayers) + ", morphPlayers " + onOff(morphPlayers) + ", morphBurst " + onOff(morphBurst) + ", morphShuffle " + onOff(morphShuffle) + ", morphBurstRadius " + morphBurstRadius + ", cleanseColor " + cleanseColor.toLowerCase() + ", cleanseArea " + onOff(cleanseArea) + ", cleanseRadius " + cleanseRadius + ", buffColor " + buffColor.toLowerCase() + ", buffLight " + buffLight.toLowerCase() + ", buffLook " + buffLook + ", healLight " + healLight.toLowerCase() + ", lookGlow " + lookGlow + ", deathGlow " + deathGlow + ", debugTrack " + debugTrackSeconds + " s, debugLog " + onOff(debug) + ", debugRecord " + onOff(debugRecord),
                "Permissions: " + permissionsMode + " (/mobbattle permissions open|server) | server features: serverVs " + onOff(serverVs)
                        + ", serverHolograms " + onOff(serverHolograms) + ", serverIcons " + onOff(serverIcons),
        };
    }

    /** Which describeLines() line a setting key belongs to. */
    public static int groupOf(String key) {
        return switch (key) {
            case "title", "wintitle", "style", "countdown", "readytime", "titlescope", "camera", "music", "runes", "camerastyle", "cameratime", "camerashots", "endcamera", "endcameratime", "camerascope", "autowatch", "watchtransition", "watchautoswitch", "camerabars", "camerablur", "camerahud", "cameraface", "vsscreen", "vsteamreps", "autofilm", "filmplayershots", "filmwideshots", "filmshot", "filmshottime", "filmpace", "filmshotvariation", "filmtransition", "filmshots", "filmstyle", "film" -> 0;
            case "laser", "color" -> 1;
            case "isolation", "battleisolation", "selfdefense", "friendlyfire", "duelmode", "playerfights", "battlepvp", "buff" -> 2;
            case "scoreboard", "arena", "timer", "timeup", "judgeby", "arenasize", "arenashrink", "arenashrinkto", "arenashrinktime", "unstick", "standofftime", "battlebelow", "battleabove", "arenavertical", "passivewin", "passivewinignore", "idleend", "battlemaxtime", "duelcard", "focuscard", "timerstyle", "tiebreak", "killtiebreak", "snapmode", "snappercent", "snaptime", "snaphitdelay", "snapradius" -> 3;
            case "debuglog", "debugrecord", "debugtrack", "killarea", "killradius", "killareaplayers", "bufflook", "snapplayers", "morphplayers", "morphburst", "morphshuffle", "morphburstradius", "cleansearea", "cleanseradius", "healareaself", "cleanseareaself", "effectareaself", "morphburstself", "effectarea", "effectradius", "healarea", "healradius", "wavetravel", "wavegroundonly", "battlewavetravel", "battlewavegroundonly", "killhitdelay", "cleansecolor", "buffcolor", "bufflight", "heallight" -> 6;
            case "radius", "area", "areacolor", "wave", "glow", "mobfx", "crown", "height", "areaonlywand", "maxmobs", "summonradius",
                 "summondespawn", "summonlifetime", "summonexit", "summoncalm", "summonarmy", "summonjade", "summonjadecolor" -> 4;
            case "teamfx", "teambytype", "armytint", "armytintcolor", "armyfriendlyfire" -> 5;
            case "permissions", "servervs", "serverholograms", "servericons" -> 7;
            default -> 6;
        };
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
