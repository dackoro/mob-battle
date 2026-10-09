package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * /mobbattle &lt;setting&gt; [value]
 * <ul>
 *   <li>show</li>
 *   <li>laser on|off</li>
 *   <li>color #RRGGBB|random</li>
 *   <li>title on|off</li>
 *   <li>wintitle on|off (winner title)</li>
 *   <li>style Default|Major|GoblinBreach|VoidEviction|random</li>
 *   <li>radius &lt;blocks&gt; (Chaos Wand area)</li>
 *   <li>area ring|pillars|both|off (how the Chaos Wand area is drawn)</li>
 *   <li>height &lt;blocks&gt; (pillar height)</li>
 *   <li>maxmobs &lt;2-200&gt; (mobs per Chaos Wand click)</li>
 *   <li>effect &lt;id&gt; (Effect Wand effect, any loaded EntityEffect)</li>
 *   <li>summon [list|add &lt;role&gt; [n|random]|remove &lt;role&gt;|clear|random &lt;min&gt; &lt;max&gt;|atleastone true|false]</li>
 * </ul>
 */
public class MobBattleCommand extends CommandBase {

    private static final String OK = "#7fd3ff";
    private static final String ERR = "#ffcc55";
    private static final String[] WAND_IDS = {
            "MobBattle_Wand", "MobBattle_ChaosWand", "MobBattle_TeamWand",
            "MobBattle_SummonWand", "MobBattle_MorphWand", "MobBattle_HealWand",
            "MobBattle_KillWand", "MobBattle_EffectWand", "MobBattle_CleanseWand", "MobBattle_ArmyWand",
            "MobBattle_SnapWand"
    };

    /** /mobbattle help [topic]: line 0 is the title, "  " lines are details. Commands in camelCase (any case works). */
    static final java.util.Map<String, String[]> HELP = new java.util.LinkedHashMap<>();
    static {
        HELP.put("", new String[] {
                "Dackoro's Mob Battle help",
                "/mobbattle give - get all eleven wands",
                "/mobbattle settings - show the current settings",
                "/mobbattle help <topic> - more about one topic:",
                "  wands - what each wand does",
                "  titles - countdown, start and winner titles, camera, music",
                "  chaos - Chaos Wand area and mob count",
                "  battles - duels, rules, arenas, timers, snap, debug, stop, army",
                "  teams - Team Wand, teams and team look",
                "  summon - Summon Wand list",
                "  morph - Morph, Effect, Kill wands and wand effects",
        });
        HELP.put("wands", new String[] {
                "Wands (/mobbattle give)",
                "Battle Wand: click a mob, then another: they fight",
                "  crouch + click a mob: that mob vs you; click players too (playerFights duel on)",
                "Army Wand: left click a mob: it joins your army (click again: leaves); right click: disband",
                "  your army follows you, defends you and attacks what you hit (also players)",
                "Chaos Wand: left click a mob (or player): everyone attacks it",
                "  right click: battle royale, last one standing wins",
                "Team Wand: left click adds a mob (or player) to the current team",
                "  right click air: next team; right click a mob: start",
                "Summon Wand: left click a mob adds its type to the list",
                "  right click: summon the whole list",
                "Morph Wand: right click copies a model, left click applies it",
                "Heal / Kill / Effect / Cleanse: left click any entity",
                "  Effect Wand right click: next effect; Kill Wand: soul rip, then death with loot",
                "Snap Wand: half of the mobs turn to dust (clicked mob's battle, or every mob within snapRadius, fighting or not)",
        });
        HELP.put("titles", new String[] {
                "Titles",
                "countdown <seconds> - 3, 2, 1 titles before any fight starts (0 = instant)",
                "startTitle on|off - title when a fight starts",
                "winTitle on|off - title with the winner",
                "titleStyle default|major|goblinBreach|voidEviction|random",
                "titleScope owner|nearby|world - who sees fight titles",
                "camera on|off - films the countdown: orbit of the area (Chaos Wand), western shots (duel)",
                "music on|off|<track> - battle music from the countdown to the end",
        });
        HELP.put("chaos", new String[] {
                "Chaos area",
                "areaStyle floor|zone|glow|both|all|off - how the area looks",
                "areaColor <color>|random - palette color",
                "areaRadius <blocks> - area size",
                "areaHeight <blocks> - zone wall height (3-128)",
                "areaOnlyWand on|off - only you, only while holding the wand",
                "maxMobs <n> - mobs per click and per summon (2-200)",
        });
        HELP.put("battles", new String[] {
                "Battles",
                "debug - combat snapshots of 4 nearest NPCs within 20 blocks; hit log for 10s",
                "debugLog on|off - battle decisions (config, arena, health scores, winner) in the server log",
                "duelMode exclusive|accumulate - replace a duel or keep connected fights",
                "fightLaser on|off - laser between fighters at the start",
                "fightLaserColor #RRGGBB|random - laser color",
                "battleIsolation <duel|allVsOne|royale|teams|all> on|off - outside mobs cannot hurt battle mobs",
                "friendlyFire <allVsOne|teams|all> on|off - allies hurt each other",
                "playerFights <duel|allVsOne|royale|teams|all> on|off - players fight too (default off, never in Creative):",
                "  the wands work on players like on mobs; crouch + Battle Wand on a mob: it vs you",
                "chaos me - every mob in the area attacks you (playerFights allVsOne on)",
                "battlePvp on|off - enemy players in a battle hurt each other even with the world's PvP off",
                "arena <kind|all> on|show|wall|off - area drawn the whole fight (show), nobody leaves it (wall), both (on)",
                "arenaSize <blocks>|auto - arena radius of duels and team battles (Chaos Wand: areaRadius)",
                "timer <kind|all> <time>|off - time limit with a timer box: 90, 2m, 1m30s, --minutes=2 --seconds=30",
                "arenaShrink <kind|all> on|off - the arena closes in like a battle royale zone (default: royale on)",
                "arenaShrinkTo <blocks> - final radius (default 15); arenaShrinkTime <time> - with no timer (default 2m)",
                "unstick on|off - a mob stuck 10 s on another level (cave, mountain; fliers 20 s, walls too) appears next to its opponent",
                "tieBreak damage|kills|random|draw - timeUp health/judge when even on health",
                "snap - half of the mobs within snapRadius (fighting or not) turn to dust, like the Snap Wand",
                "snapMode global|team - half of everyone, or half of each side",
                "snapPercent <1-100> - share that turns to dust (default 50)",
                "snapTime <1-30> - seconds over which they turn to dust (default 5)",
                "snapRadius <blocks> - snap reach without battles (default 30, separate from areaRadius)",
                "timerStyle custom|portal - our own timer box (default) or the game's Void portal box",
                "timeUp <kind|all> draw|kill|continue|health|judge - when the timer ends (health: most health in % wins; judge: and the losers die, the winners heal)",
                "stop - end every battle, countdown, timer box, arena, camera shot and battle music",
                "army clearAll - disband every player's army",
                "resetAll - stop + teams clearAll + army clearAll + morph reset",
        });
        HELP.put("teams", new String[] {
                "Teams",
                "teams list - show the teams",
                "teams auto - put mobs in range into teams",
                "teams start - start the team battle",
                "teams clear - empty your teams",
                "teams clearAll - empty every player's teams",
                "teams join <team> / leave - fight with a team yourself (playerFights teams on)",
                "teams add <name> <color> - new team",
                "teams color <team> <color> / rename <team> <name>",
                "teams remove <team> / reset - back to the 4 defaults",
                "teamLook both|tint|mist|off - how team mobs look",
                "teamLook time <seconds>|infinite - how long it stays",
                "teamLook onlyWand on|off - only while holding the Team Wand",
        });
        HELP.put("summon", new String[] {
                "Summon list",
                "summon - summon the list around you",
                "summon list - show the list",
                "summon add <role> [count|random] [team]",
                "summon modify <role> <count|random>",
                "summon remove <role> / clear",
                "summon team <role> <team|none> - team rule",
                "summon random <min> <max> - range for random counts",
                "summonRadius <blocks>|auto - summon area size (auto grows with the count)",
                "summon atLeastOne true|false - random types spawn 1+",
        });
        HELP.put("morph", new String[] {
                "Morph and effects",
                "morph <model> - set the Morph Wand model",
                "morph list [text] - search model IDs",
                "morph reset - every morphed mob gets its model back",
                "wandEffect <id> - Effect Wand effect, e.g. Burn",
                "wandFx on|off - glow, soul rip, charm, summoning runes... on the clicked mob",
                "killDelay <seconds> - Kill Wand soul rip before death (0-3, 0 = instant)",
                "killLoot on|off - the disintegrated mob drops its loot (default on)",
                "killPlayers on|off - players disintegrate too, then die normally (default off)",
        });
    }


    private static final String USAGE = "Unknown command. /mobbattle help lists them all.";

    /**
     * One word of the command tree. Every node is a real subcommand so the chat suggests the next word;
     * all of them run the same parser over the raw input, and free values (numbers, IDs, colors) are
     * allowed as extra arguments ("/mobbattle areaRadius 20").
     */
    private static final class Sub extends CommandBase {
        Sub(String name, String alias, String desc, Sub... children) {
            this(name, desc, children);
            addAliases(alias);
        }

        Sub(String name, String description, Sub... children) {
            super(name, description);
            if ("give".equals(name)) {
                requirePermission(com.hypixel.hytale.server.core.permissions.HytalePermissions.fromCommand("give.self"));
                setPermissionGroups("hytale:Builder");
            }
            setAllowsExtraArguments(true);
            for (Sub child : children) {
                addSubCommand(child);
            }
        }

        /** Accepts --seconds=&lt;n&gt; and --minutes=&lt;n&gt; (the game's named argument syntax). */
        Sub withDuration() {
            withOptionalArg("minutes", "Minutes (adds to --seconds)",
                    com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.INTEGER);
            withOptionalArg("seconds", "Seconds (adds to --minutes)",
                    com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.INTEGER);
            return this;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            dispatch(ctx);
        }
    }

    /**
     * A duration in seconds from command words: "90", "90s", "2m", "1m30s", "--minutes=2", "--seconds 30",
     * "--minutes=1 --seconds=30" (summed), or "off" (0). Returns -1 if it isn't one.
     */
    static int parseDuration(String[] words) {
        if (words.length == 0) return -1;
        if (words.length == 1 && "off".equalsIgnoreCase(words[0])) return 0;
        long total = 0;
        boolean any = false;
        for (int i = 0; i < words.length; i++) {
            String w = words[i].toLowerCase(java.util.Locale.ROOT);
            String unit = null;
            String number;
            if (w.startsWith("--")) {
                int eq = w.indexOf('=');
                unit = eq > 0 ? w.substring(2, eq) : w.substring(2);
                if (eq > 0) {
                    number = w.substring(eq + 1);
                } else if (i + 1 < words.length) {
                    number = words[++i];
                } else {
                    return -1;
                }
                if (!unit.equals("seconds") && !unit.equals("minutes")) return -1;
                unit = unit.equals("minutes") ? "m" : "s";
            } else {
                number = w;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([ms]?)").matcher(number);
            int at = 0;
            boolean matched = false;
            while (m.find() && m.start() == at) {
                long n = Long.parseLong(m.group(1));
                String u = !m.group(2).isEmpty() ? m.group(2) : unit != null ? unit : "s";
                total += u.equals("m") ? n * 60 : n;
                at = m.end();
                matched = true;
                if (total > Integer.MAX_VALUE) return -1;
            }
            if (!matched || at != number.length()) return -1;
            any = true;
        }
        return any ? (int) total : -1;
    }

    /** "1:05", "12:00" (custom timer box). */
    static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** "1m 30s", "45s", "off". */
    static String formatDuration(int seconds) {
        if (seconds <= 0) return "off";
        int m = seconds / 60, s = seconds % 60;
        return m == 0 ? s + "s" : s == 0 ? m + "m" : m + "m " + s + "s";
    }

    private static Sub[] words(String description, String... names) {
        Sub[] out = new Sub[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = new Sub(names[i], description);
        }
        return out;
    }

    /** Old command words keep working: /mobbattle fightlasercolor = fightlasercolor, etc. */
    private static final java.util.Map<String, String> RENAMED = java.util.Map.ofEntries(
            java.util.Map.entry("settings", "show"),
            java.util.Map.entry("starttitle", "title"),
            java.util.Map.entry("titlestyle", "style"),
            java.util.Map.entry("fightlaser", "laser"),
            java.util.Map.entry("fightlasercolor", "color"),
            java.util.Map.entry("arearadius", "radius"),
            java.util.Map.entry("areastyle", "area"),
            java.util.Map.entry("areaheight", "height"),
            java.util.Map.entry("wandeffect", "effect"),
            java.util.Map.entry("teamlook", "teamfx"),
            java.util.Map.entry("battleisolation", "isolation"));

    private static Sub arenaKind(String name) {
        return new Sub(name, "Arena of " + name, new Sub("on", "Drawn the whole fight, nobody leaves it"),
                new Sub("show", "Only drawn the whole fight"), new Sub("wall", "Only: nobody leaves it"),
                new Sub("off", "No arena"));
    }

    private static Sub timerKind(String name) {
        return new Sub(name, "Time limit of " + name + ": 90, 2m, 1m30s, --minutes=2 --seconds=30, or off",
                new Sub("off", "No time limit")).withDuration();
    }

    private static Sub timeUpKind(String name) {
        return new Sub(name, "When the timer of " + name + " ends",
                new Sub("draw", "They stop: draw (default)"), new Sub("kill", "Every fighter dies"),
                new Sub("continue", "They keep fighting, no winner title"),
                new Sub("health", "The side with most health left (in %) wins"),
                new Sub("judge", "Most health left (in %) wins and is healed; the losers die"));
    }

    /** /mobbattle arena|timer|timeup [kind|all] value. Returns the kinds and the value, or null after an error reply. */
    private static java.util.Map.Entry<java.util.List<BattleManager.Kind>, String> kindAndValue(CommandContext ctx, String word,
                                                                                         String usage) {
        String[] a = argsAfter(ctx, word);
        String kindWord = a.length >= 2 ? a[0].toLowerCase(java.util.Locale.ROOT) : "all";
        String value = a.length >= 2 ? a[1] : a.length == 1 ? a[0] : null;
        BattleManager.Kind kind = FxSettings.kindOf(kindWord);
        if (value == null || !"all".equals(kindWord) && kind == null) {
            reply(ctx, "Usage: /mobbattle " + usage, ERR);
            return null;
        }
        java.util.List<BattleManager.Kind> kinds = "all".equals(kindWord)
                ? java.util.List.of(BattleManager.Kind.values()) : java.util.List.of(kind);
        return java.util.Map.entry(kinds, value.toLowerCase(java.util.Locale.ROOT));
    }

    private static Sub battleKind(String name, String desc) {
        return new Sub(name, desc, new Sub("on", "On"), new Sub("off", "Off"));
    }

    /**
     * /mobbattle friendlyfire|battleisolation [kind|all] on|off for the given kinds. A bare on|off means all.
     * Returns false (after an error reply) if the input is invalid.
     */
    private static boolean setBattleRule(CommandContext ctx, String word, java.util.Map<BattleManager.Kind, Boolean> map,
                                         BattleManager.Kind[] allowed) {
        String[] a = argsAfter(ctx, word);
        String kindWord = a.length >= 2 ? a[0].toLowerCase(java.util.Locale.ROOT) : "all";
        Boolean on = parseOnOff(a.length >= 2 ? a[1] : a.length == 1 ? a[0] : null);
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (BattleManager.Kind k : allowed) keys.add(k.key);
        BattleManager.Kind kind = FxSettings.kindOf(kindWord);
        if (on == null || !"all".equals(kindWord) && (kind == null || !keys.contains(kind.key))) {
            reply(ctx, "Usage: /mobbattle " + word + " <" + String.join("|", keys) + "|all> on|off", ERR);
            return false;
        }
        for (BattleManager.Kind k : allowed) {
            if ("all".equals(kindWord) || k == kind) map.put(k, on);
        }
        return true;
    }

    public MobBattleCommand() {
        super("mobbattle", "Dackoro's Mob Battle: wands that make mobs fight. Settings, teams, summon list and morphs.");
        setAllowsExtraArguments(true);
        addSubCommand(new Sub("settings", "show", "Show the current settings"));
        addSubCommand(new Sub("help", "Explain the wands and commands: help [topic]",
                words("Help topic", "wands", "titles", "chaos", "battles", "teams", "summon", "morph")));
        Sub give = new Sub("give", "Receive one of each of the eleven Mob Battle wands");
        addSubCommand(give);
        addSubCommand(new Sub("starttitle", "title", "Fight-start title on/off", words("Fight-start title", "on", "off")));
        addSubCommand(new Sub("wintitle", "Winner title on/off", words("Winner title", "on", "off")));
        addSubCommand(new Sub("music", "Battle music: on, off, or a music track ID (default " + BattleMusic.DEFAULT_TRACK + ")",
                words("Music", "on", "off", "Track_Z1D_Goblin_Boss_Battle", "Track_Void_Escalation", "Track_Void_Tension")));
        addSubCommand(new Sub("camera", "Film the countdown: orbit of the Chaos Wand area, western shots of a duel",
                new Sub("on", "Show it (default; needs a countdown)"), new Sub("off", "Normal camera")));
        addSubCommand(new Sub("titlescope", "Who sees fight titles: owner, nearby (default, 64 blocks) or world",
                words("Title scope", "owner", "nearby", "world")));
        addSubCommand(new Sub("titlestyle", "style", "Title style", words("Title style", "default", "major", "goblinbreach", "voideviction", "random")));
        addSubCommand(new Sub("fightlaser", "laser", "Laser line between fighters at fight start on/off", words("Fighter laser", "on", "off")));
        addSubCommand(new Sub("fightlasercolor", "color", "Color of the fighter laser: #RRGGBB or random", words("Fighter laser color", "random")));
        addSubCommand(new Sub("arearadius", "radius", "Chaos Wand area radius in blocks (" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ")"));
        addSubCommand(new Sub("playerfights", "Players can fight in battles: <duel|allvsone|royale|teams|all> on|off",
                battleKind("duel", "Battle Wand on players, or crouching on a mob: it vs you (default off)"),
                battleKind("allvsone", "Chaos Wand left click on a player, or chaos me (default off)"),
                battleKind("royale", "Chaos Wand right click takes in the players in the area (default off)"),
                battleKind("teams", "Team Wand on players, or teams join <team> (default off)"),
                battleKind("all", "Every kind at once")));
        addSubCommand(new Sub("battlepvp", "Enemy players in a battle hurt each other even with the world's PvP off",
                new Sub("on", "PvP inside battles"), new Sub("off", "The world's PvP rule (default)")));
        addSubCommand(new Sub("chaos", "Chaos battles with you in them: chaos me",
                new Sub("me", "Every mob within areaRadius attacks you (needs playerFights allvsone on)")));
        addSubCommand(new Sub("friendlyfire", "Allies hurt each other: <allvsone|teams|all> on|off",
                battleKind("allvsone", "Chaos Wand everyone vs one: attackers hurt each other (default on)"),
                battleKind("teams", "Team Wand battles: teammates hurt each other (default off)"),
                battleKind("all", "Both kinds at once")));
        addSubCommand(new Sub("battleisolation", "isolation", "Outside mobs can't hurt battle mobs and vice versa: <kind|all> on|off",
                battleKind("duel", "Battle Wand duels"),
                battleKind("allvsone", "Chaos Wand everyone vs one"),
                battleKind("royale", "Chaos Wand battle royale"),
                battleKind("teams", "Team Wand battles"),
                battleKind("all", "Every kind at once")));
        addSubCommand(new Sub("arena", "Arena per battle kind: <duel|allvsone|royale|teams|all> on|show|wall|off",
                arenaKind("duel"), arenaKind("allvsone"), arenaKind("royale"), arenaKind("teams"), arenaKind("all")));
        addSubCommand(new Sub("arenasize", "Arena radius of duels and team battles (" + FxSettings.MIN_ARENA + "-"
                + FxSettings.MAX_ARENA + "), or auto (default: around the fighters)", words("Arena size", "auto")));
        addSubCommand(new Sub("timer", "Fight time limit with a timer box: <kind|all> <90|2m|1m30s|--minutes=2 --seconds=30|off>",
                timerKind("duel"), timerKind("allvsone"), timerKind("royale"), timerKind("teams"), timerKind("all"))
                .withDuration());
        addSubCommand(new Sub("arenashrink", "Closing arena (battle royale zone): <duel|allvsone|royale|teams|all> on|off",
                battleKind("duel", "Battle Wand duels"),
                battleKind("allvsone", "Chaos Wand everyone vs one"),
                battleKind("royale", "Chaos Wand battle royale (default on)"),
                battleKind("teams", "Team Wand battles"),
                battleKind("all", "Every kind at once")));
        addSubCommand(new Sub("arenashrinkto", "Final radius of a closing arena in blocks (" + FxSettings.MIN_SHRINK_TO + "-"
                + FxSettings.MAX_ARENA + ", default 15)"));
        addSubCommand(new Sub("arenashrinktime", "How long a closing arena takes when the battle has no timer: 90, 2m, 1m30s (default 2m)")
                .withDuration());
        addSubCommand(new Sub("unstick", "Battle mobs stuck on another level than their opponent (cave, mountain) appear next to it",
                new Sub("on", "Bring them together after 10 s (default)"), new Sub("off", "Leave them")));
        addSubCommand(new Sub("tiebreak", "timeUp health/judge when even on health: damage (default), kills, random or draw",
                new Sub("damage", "The side that dealt more damage wins"), new Sub("kills", "The side with more kills wins"),
                new Sub("random", "A coin toss"), new Sub("draw", "It stays a draw")));
        addSubCommand(new Sub("snap", "Half of the mobs within snapRadius of you, fighting or not, turn to dust"));
        addSubCommand(new Sub("snappercent", "Snap: share of the fighters that turn to dust, 1-100 (default 50)"));
        addSubCommand(new Sub("snaptime", "Snap: seconds over which they turn to dust, 1-" + FxSettings.MAX_SNAP_SECONDS + " (default 5)"));
        addSubCommand(new Sub("snapradius", "Snap without battles: reach in blocks (default 30, separate from areaRadius)"));
        addSubCommand(new Sub("snapmode", "Snap: global (half of everyone, default) or team (half of each side)",
                new Sub("global", "Half of everyone, at random"), new Sub("team", "Half of each side")));
        addSubCommand(new Sub("timerstyle", "Timer box: custom (our own, default) or portal (the game's Void portal box)",
                words("Timer style", "custom", "portal")));
        addSubCommand(new Sub("timeup", "When the timer ends: <kind|all> draw|kill|continue|health|judge",
                timeUpKind("duel"), timeUpKind("allvsone"), timeUpKind("royale"), timeUpKind("teams"), timeUpKind("all")));
        java.util.List<String> areaColors = new java.util.ArrayList<>();
        Teams.PALETTE.keySet().forEach(n -> areaColors.add(n.toLowerCase(java.util.Locale.ROOT)));
        areaColors.add("random");
        addSubCommand(new Sub("areaonlywand", "Area only for you, only while you hold the Chaos Wand",
                new Sub("on", "Only you, only while holding the Chaos Wand"),
                new Sub("off", "Everyone sees it for 6 s (default)")));
        addSubCommand(new Sub("areacolor", "Area color from the palette, or random",
                words("Area color", areaColors.toArray(new String[0]))));
        addSubCommand(new Sub("areastyle", "area", "How the Chaos Wand area is shown", words("Area style", "floor", "zone", "glow", "both", "all", "off")));
        addSubCommand(new Sub("areaheight", "height", "Height of the zone wall in blocks (3-128)"));
        addSubCommand(new Sub("stop", "End every battle, countdown, timer box, arena, camera shot and battle music"));
        addSubCommand(new Sub("resetall", "Stop every battle, empty every team and army and undo every morph"));
        addSubCommand(new Sub("army", "Army Wand armies",
                new Sub("clearall", "Disband every player's army")));
        addSubCommand(new Sub("countdown", "Seconds of 3, 2, 1 titles before a fight starts (0-10, 0 = instant)"));
        addSubCommand(new Sub("debug", "Read nearby NPC combat state and log their hits for 10 seconds"));
        addSubCommand(new Sub("duelmode", "Battle Wand: replace duels or accumulate connected fights",
                words("Duel mode", "exclusive", "accumulate")));
        addSubCommand(new Sub("wandfx", "Wand effects on the clicked mob (heal glow, soul rip, charm...)",
                new Sub("on", "Show them (default)"),
                new Sub("off", "No effects on the mob")));
        addSubCommand(new Sub("debuglog", "Battle decisions (config, arena, health scores, winner) in the server log",
                new Sub("on", "Log them, prefixed [debug]"),
                new Sub("off", "Normal log (default)")));
        addSubCommand(new Sub("killdelay", "Kill Wand: seconds of soul rip before the mob dies (0-3, 0 = instant)"));
        addSubCommand(new Sub("killplayers", "Kill Wand: players disintegrate too, then die a normal death",
                new Sub("on", "Disintegrate players too"),
                new Sub("off", "Players die at once (default)")));
        addSubCommand(new Sub("killloot", "Kill Wand: the disintegrated mob drops its loot",
                new Sub("on", "Drop its normal loot (default)"),
                new Sub("off", "No drops")));
        addSubCommand(new Sub("summonradius", "Summon area radius in blocks (1-64), or auto (default: grows with the count)",
                words("Radius", "auto")));
        addSubCommand(new Sub("maxmobs", "Max mobs per Chaos Wand click and per summon (2-200)"));
        addSubCommand(new Sub("wandeffect", "effect", "Effect Wand effect: any effect ID, e.g. Burn or Potion_Morph_Frog"));
        addSubCommand(new Sub("teamlook", "teamfx", "Team look: both, tint, mist, off, or time <seconds|infinite>",
                new Sub("both", "Tint + mist"), new Sub("tint", "Color the whole mob"),
                new Sub("mist", "Mist of the team color"), new Sub("off", "No team look"),
                new Sub("time", "How long the look stays: seconds (1-600) or infinite", words("Whole battle", "infinite")),
                new Sub("onlywand", "Show the look only while the team owner holds the Team Wand",
                        words("Only with the Team Wand", "on", "off"))));
        addSubCommand(new Sub("teams", "Team battles and custom teams",
                new Sub("auto", "Put mobs in range into teams by the team rules"),
                new Sub("start", "Start the team battle"),
                new Sub("clear", "Empty your teams"),
                new Sub("clearall", "Empty every player's teams"),
                new Sub("list", "Show the teams"),
                new Sub("add", "Add a team: <name> <palette color>"),
                new Sub("color", "Recolor a team: <team> <palette color>"),
                new Sub("rename", "Rename a team: <team> <new name>"),
                new Sub("remove", "Remove a team: <team>"),
                new Sub("reset", "Back to the 4 default teams"),
                new Sub("join", "Fight with a team yourself: <team> (needs playerFights teams on)"),
                new Sub("leave", "Leave your team or team battle")));
        addSubCommand(new Sub("summon", "Summon the list around you, or edit it",
                new Sub("list", "Show the summon list"),
                new Sub("add", "Add a mob type: <role> [count|random] [team]"),
                new Sub("modify", "Change a listed mob's count: <role> <count|random>"),
                new Sub("remove", "Remove a mob type: <role>"),
                new Sub("clear", "Empty the list"),
                new Sub("team", "Team rule: <role> <team|none>"),
                new Sub("random", "Range for random counts: <min> <max>"),
                new Sub("atleastone", "Random types spawn at least one", words("At least one", "true", "false"))));
        addSubCommand(new Sub("morph", "Morph Wand model: <model ID>, list [text] or reset",
                new Sub("list", "Search model IDs: [text]"),
                new Sub("reset", "Give every morphed mob its own model back")));
    }

    @Override
    protected void executeSync(CommandContext ctx) {
        dispatch(ctx);
    }

    /** First word after "mobbattle" in the raw input, lowercase, or "". */
    private static String settingOf(CommandContext ctx) {
        String input = ctx.getInputString();
        if (input == null) {
            return "";
        }
        String[] words = input.trim().split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].replace("/", "").equalsIgnoreCase("mobbattle")) {
                return words[i + 1].toLowerCase();
            }
        }
        return "";
    }

    private static void dispatch(CommandContext ctx) {
        String key = settingOf(ctx);
        String val = valueOf(ctx, key);
        key = RENAMED.getOrDefault(key, key);

        switch (key) {
            case "help" -> {
                String[] lines = HELP.get(val == null ? "" : val.toLowerCase());
                if (lines == null) {
                    reply(ctx, "Unknown help topic. Topics: wands, titles, chaos, battles, teams, summon, morph", ERR);
                    return;
                }
                reply(ctx, lines[0], "#ffd23e");
                for (int i = 1; i < lines.length; i++) {
                    reply(ctx, lines[i], lines[i].startsWith("  ") ? "#c8e6ff" : OK);
                }
                return;
            }
            case "give" -> {
                giveWands(ctx);
                return;
            }
            case "show" -> {
                reply(ctx, "Dackoro's Mob Battle settings", "#ffd23e");
                for (String line : FxSettings.describeLines()) {
                    reply(ctx, line, OK);
                }
                return;
            }
            case "laser" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle fightLaser on|off", ERR);
                    return;
                }
                FxSettings.laserEnabled = on;
            }
            case "color" -> {
                if (val == null || !(FxSettings.RANDOM.equalsIgnoreCase(val) || FxSettings.isHexColor(val))) {
                    reply(ctx, "Usage: /mobbattle fightLaserColor #RRGGBB|random  (e.g. #FFD23E)", ERR);
                    return;
                }
                FxSettings.laserColor = val.toLowerCase().equals(FxSettings.RANDOM) ? FxSettings.RANDOM : val.toUpperCase();
            }
            case "title" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle startTitle on|off", ERR);
                    return;
                }
                FxSettings.titleEnabled = on;
            }
            case "friendlyfire" -> {
                if (!setBattleRule(ctx, "friendlyfire", FxSettings.FRIENDLY_FIRE, FxSettings.ALLY_KINDS)) {
                    return;
                }
            }
            case "playerfights" -> {
                if (!setBattleRule(ctx, "playerfights", FxSettings.PLAYER_FIGHTS, FxSettings.PLAYER_KINDS)) {
                    return;
                }
            }
            case "battlepvp" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle battlePvp on|off", ERR);
                    return;
                }
                FxSettings.battlePvp = on;
            }
            case "chaos" -> {
                String[] w = argsAfter(ctx, "chaos");
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (w.length != 1 || !"me".equalsIgnoreCase(w[0]) || ref == null || !ref.isValid()) {
                    reply(ctx, "Usage (player): /mobbattle chaos me", ERR);
                    return;
                }
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (player != null) PlayerFighters.chaosMe(player, ref, store);
                });
                return;
            }
            case "arena" -> {
                var kv = kindAndValue(ctx, "arena", "arena <duel|allVsOne|royale|teams|all> on|show|wall|off");
                if (kv == null) return;
                Arena.Mode mode;
                try {
                    mode = Arena.Mode.valueOf(kv.getValue().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    reply(ctx, "Usage: /mobbattle arena <duel|allVsOne|royale|teams|all> on|show|wall|off", ERR);
                    return;
                }
                kv.getKey().forEach(k -> FxSettings.ARENA.put(k, mode));
            }
            case "timer" -> {
                String[] a = argsAfter(ctx, "timer");
                BattleManager.Kind kind = a.length > 0 ? FxSettings.kindOf(a[0]) : null;
                boolean named = a.length > 0 && (kind != null || "all".equalsIgnoreCase(a[0]));
                int seconds = parseDuration(named ? java.util.Arrays.copyOfRange(a, 1, a.length) : a);
                if (seconds < 0 || seconds > FxSettings.MAX_TIMER) {
                    reply(ctx, "Usage: /mobbattle timer <duel|allVsOne|royale|teams|all> <90|2m|1m30s|--minutes=2 --seconds=30|off>"
                            + " (up to " + formatDuration(FxSettings.MAX_TIMER) + ")", ERR);
                    return;
                }
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    if (kind == null || k == kind) FxSettings.TIMER.put(k, seconds);
                }
            }
            case "unstick" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle unstick on|off", ERR);
                    return;
                }
                FxSettings.unstick = on;
                if (!on) Unstick.clear();
            }
            case "arenashrink" -> {
                if (!setBattleRule(ctx, "arenashrink", FxSettings.SHRINK, BattleManager.Kind.values())) {
                    return;
                }
            }
            case "arenashrinkto" -> {
                try {
                    FxSettings.shrinkTo = Math.max(FxSettings.MIN_SHRINK_TO, Math.min(FxSettings.MAX_ARENA,
                            Integer.parseInt(val == null ? "" : val)));
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle arenaShrinkTo <" + FxSettings.MIN_SHRINK_TO + "-" + FxSettings.MAX_ARENA + ">", ERR);
                    return;
                }
            }
            case "arenashrinktime" -> {
                int seconds = parseDuration(argsAfter(ctx, "arenashrinktime"));
                if (seconds < 10 || seconds > FxSettings.MAX_TIMER) {
                    reply(ctx, "Usage: /mobbattle arenaShrinkTime <90|2m|1m30s> (10 s to " + formatDuration(FxSettings.MAX_TIMER) + ")", ERR);
                    return;
                }
                FxSettings.shrinkSeconds = seconds;
            }
            case "timeup" -> {
                var kv = kindAndValue(ctx, "timeup", "timeUp <duel|allVsOne|royale|teams|all> draw|kill|continue|health|judge");
                if (kv == null) return;
                Arena.TimeUp rule;
                try {
                    rule = Arena.TimeUp.valueOf(kv.getValue().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    reply(ctx, "Usage: /mobbattle timeUp <kind|all> draw|kill|continue|health|judge", ERR);
                    return;
                }
                kv.getKey().forEach(k -> FxSettings.TIME_UP.put(k, rule));
            }
            case "tiebreak" -> {
                String v = val == null ? "" : val.toLowerCase(java.util.Locale.ROOT);
                if (!v.equals("damage") && !v.equals("kills") && !v.equals("random") && !v.equals("draw")) {
                    reply(ctx, "Usage: /mobbattle tieBreak damage|kills|random|draw", ERR);
                    return;
                }
                FxSettings.tieBreak = v;
            }
            case "snappercent", "snaptime" -> {
                boolean pct = key.equals("snappercent");
                int max = pct ? 100 : FxSettings.MAX_SNAP_SECONDS;
                try {
                    int v = Integer.parseInt(val == null ? "" : val.replace("%", "").replace("s", ""));
                    if (v < 1 || v > max) throw new NumberFormatException();
                    if (pct) FxSettings.snapPercent = v; else FxSettings.snapSeconds = v;
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle " + (pct ? "snapPercent" : "snapTime") + " <1-" + max + ">", ERR);
                    return;
                }
            }
            case "snapradius" -> {
                try {
                    FxSettings.snapRadius = FxSettings.clampRadius(Integer.parseInt(val == null ? "" : val));
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle snapRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS
                            + "> (default " + FxSettings.DEFAULT_SNAP_RADIUS + ", separate from areaRadius)", ERR);
                    return;
                }
            }
            case "snapmode" -> {
                if (!"global".equalsIgnoreCase(val) && !"team".equalsIgnoreCase(val)) {
                    reply(ctx, "Usage: /mobbattle snapMode global|team", ERR);
                    return;
                }
                FxSettings.snapMode = val.toLowerCase(java.util.Locale.ROOT);
            }
            case "snap" -> {
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (ref == null || !ref.isValid()) {
                    reply(ctx, "Only a player can snap (it uses your battles and position).", ERR);
                    return;
                }
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var pr = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    Snap.snap(pr, ref, null, store);
                });
                return;
            }
            case "timerstyle" -> {
                if (!"custom".equalsIgnoreCase(val) && !"portal".equalsIgnoreCase(val)) {
                    reply(ctx, "Usage: /mobbattle timerStyle custom|portal", ERR);
                    return;
                }
                FxSettings.timerStyle = val.toLowerCase(java.util.Locale.ROOT);
            }
            case "arenasize" -> {
                if ("auto".equalsIgnoreCase(val)) {
                    FxSettings.arenaSize = 0;
                } else {
                    try {
                        int v = Integer.parseInt(val == null ? "" : val);
                        if (v < FxSettings.MIN_ARENA || v > FxSettings.MAX_ARENA) throw new NumberFormatException();
                        FxSettings.arenaSize = v;
                    } catch (NumberFormatException e) {
                        reply(ctx, "Usage: /mobbattle arenaSize <" + FxSettings.MIN_ARENA + "-" + FxSettings.MAX_ARENA + ">|auto", ERR);
                        return;
                    }
                }
            }
            case "isolation" -> {
                if (!setBattleRule(ctx, settingOf(ctx), FxSettings.ISOLATION, BattleManager.Kind.values())) {
                    return;
                }
            }
            case "wintitle" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle winTitle on|off", ERR);
                    return;
                }
                FxSettings.winTitleEnabled = on;
            }
            case "music" -> {
                Boolean on = parseOnOff(val);
                if (on != null) {
                    FxSettings.battleMusic = on;
                    if (!on) BattleMusic.stopAll();
                } else if (val != null && com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer
                        .getAssetMap().getIndex(val) > 0) {
                    FxSettings.musicTrack = val;
                    FxSettings.battleMusic = true;
                } else {
                    reply(ctx, "Usage: /mobbattle music on|off|<track>, e.g. " + String.join(", ", BattleMusic.SUGGESTED), ERR);
                    return;
                }
            }
            case "camera" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle camera on|off", ERR);
                    return;
                }
                FxSettings.fightCamera = on;
            }
            case "titlescope" -> {
                String scope = val == null ? "" : val.toLowerCase();
                if (!java.util.Arrays.asList(FxSettings.TITLE_SCOPES).contains(scope)) {
                    reply(ctx, "Usage: /mobbattle titleScope owner|nearby|world", ERR);
                    return;
                }
                FxSettings.titleScope = scope;
            }
            case "style" -> {
                if (val != null && FxSettings.RANDOM.equalsIgnoreCase(val)) {
                    FxSettings.titleStyle = FxSettings.RANDOM;
                } else {
                    EventTitleStyle style = val != null ? FxSettings.parseStyle(val) : null;
                    if (style == null) {
                        String names = Arrays.stream(EventTitleStyle.VALUES).map(Enum::name).collect(Collectors.joining(", "));
                        reply(ctx, "Usage: /mobbattle titleStyle <" + names + ", random>", ERR);
                        return;
                    }
                    FxSettings.titleStyle = style.name();
                }
            }
            case "radius" -> {
                int r;
                try {
                    r = Integer.parseInt(val == null ? "" : val);
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle areaRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">", ERR);
                    return;
                }
                FxSettings.radius = FxSettings.clampRadius(r);
            }
            case "areaonlywand" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle areaOnlyWand on|off", ERR);
                    return;
                }
                FxSettings.areaOnlyWand = on;
            }
            case "areacolor" -> {
                String color = FxSettings.parseAreaColor(val);
                if (color == null) {
                    reply(ctx, "Usage: /mobbattle areaColor <color>|random. Colors: "
                            + String.join(", ", Teams.PALETTE.keySet()).toLowerCase(java.util.Locale.ROOT) + ".", ERR);
                    return;
                }
                FxSettings.areaColor = color;
            }
            case "area" -> {
                if (!FxSettings.isAreaStyle(val)) {
                    reply(ctx, "Usage: /mobbattle areaStyle floor|zone|glow|both|all|off", ERR);
                    return;
                }
                FxSettings.areaStyle = FxSettings.parseAreaStyle(val);
            }
            case "teamfx" -> {
                if ("onlywand".equalsIgnoreCase(val)) {
                    String[] w = argsAfter(ctx, "onlywand");
                    Boolean on = parseOnOff(w.length > 0 ? w[0] : null);
                    if (on == null) {
                        reply(ctx, "Usage: /mobbattle teamLook onlyWand on|off", ERR);
                        return;
                    }
                    FxSettings.teamLookOnlyWand = on;
                    if (!on) {
                        reply(ctx, "Team look always visible again; mobs get it back on their next team order.", OK);
                    }
                } else if ("time".equalsIgnoreCase(val)) {
                    String[] a = argsAfter(ctx, "time");
                    String t = a.length > 0 ? a[0].toLowerCase() : "";
                    int n;
                    if (t.equals("infinite") || t.equals("inf") || t.equals("battle")) {
                        n = 0;
                    } else {
                        try {
                            n = Integer.parseInt(t);
                        } catch (NumberFormatException e) {
                            reply(ctx, "Usage: /mobbattle teamLook time infinite|<1-" + FxSettings.TEAM_FX_MAX_SECONDS
                                    + "> (infinite = whole battle). No team look at all: /mobbattle teamLook off", ERR);
                            return;
                        }
                        if (n <= 0) {
                            reply(ctx, "Use a positive duration or infinite. For no team look use /mobbattle teamLook off.", ERR);
                            return;
                        }
                    }
                    FxSettings.teamFxSeconds = Math.max(0, Math.min(FxSettings.TEAM_FX_MAX_SECONDS, n));
                } else if (!TeamFx.isMode(val)) {
                    reply(ctx, "Usage: /mobbattle teamLook both|tint|mist|off  or  /mobbattle teamLook time <seconds>", ERR);
                    return;
                } else {
                    FxSettings.teamFx = val.toLowerCase();
                }
            }
            case "summon" -> {
                summon(ctx);
                return;
            }
            case "morph" -> {
                morph(ctx);
                return;
            }
            case "teams" -> {
                teams(ctx);
                return;
            }
            case "army" -> {
                String[] a = argsAfter(ctx, "army");
                if (a.length != 1 || !a[0].equalsIgnoreCase("clearall")) {
                    reply(ctx, "Usage: /mobbattle army clearAll", ERR);
                    return;
                }
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                Runnable job = () -> reply(ctx, "Every army disbanded (" + Army.clearAll() + " mobs).", OK);
                if (ref != null && ref.isValid()) ref.getStore().getExternalData().getWorld().execute(job); else job.run();
                return;
            }
            case "stop", "resetall" -> {
                reset(ctx, key.equals("resetall"));
                return;
            }
            case "effect" -> {
                var found = EffectWand.find(val);
                if (found == null) {
                    reply(ctx, "Unknown effect. Examples: " + String.join(", ", EffectWand.PRESETS), ERR);
                    return;
                }
                FxSettings.effect = found.getId();
            }
            case "duelmode" -> {
                if (argsAfter(ctx, "duelmode").length != 1 || val == null
                        || !(val.equalsIgnoreCase("exclusive") || val.equalsIgnoreCase("accumulate"))) {
                    reply(ctx, "Usage: /mobbattle duelMode exclusive|accumulate", ERR);
                    return;
                }
                FxSettings.accumulateDuels = val.equalsIgnoreCase("accumulate");
            }
            case "debug" -> {
                if (!ctx.isPlayer() || argsAfter(ctx, "debug").length != 0) {
                    reply(ctx, "Usage (player): /mobbattle debug", ERR);
                    return;
                }
                var ref = ctx.senderAsPlayerRef();
                if (ref != null && ref.isValid()) {
                    var store = ref.getStore();
                    store.getExternalData().getWorld().execute(() -> BattleDebug.run(ref, store));
                }
                return;
            }
            case "countdown" -> {
                int n;
                try {
                    n = Integer.parseInt(val == null ? "" : val);
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle countdown <seconds 0-" + FxSettings.MAX_COUNTDOWN + "> (0 = instant)", ERR);
                    return;
                }
                FxSettings.countdownSeconds = Math.max(0, Math.min(FxSettings.MAX_COUNTDOWN, n));
            }
            case "summonradius" -> {
                if ("auto".equalsIgnoreCase(val)) {
                    FxSettings.summonRadius = 0;
                } else {
                    try {
                        FxSettings.summonRadius = Math.max(1, Math.min(FxSettings.MAX_SUMMON_RADIUS, Integer.parseInt(val == null ? "" : val)));
                    } catch (NumberFormatException e) {
                        reply(ctx, "Usage: /mobbattle summonRadius <1-" + FxSettings.MAX_SUMMON_RADIUS + "|auto>", ERR);
                        return;
                    }
                }
            }
            case "debuglog" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle debugLog on|off", ERR);
                    return;
                }
                FxSettings.debug = on;
                if (on) {
                    for (String line : FxSettings.describeLines()) MobBattlePlugin.debug("settings: " + line);
                }
            }
            case "wandfx" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle wandFx on|off", ERR);
                    return;
                }
                FxSettings.wandFx = on;
            }
            case "killplayers" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle killPlayers on|off", ERR);
                    return;
                }
                FxSettings.killPlayers = on;
            }
            case "killloot" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle killLoot on|off", ERR);
                    return;
                }
                FxSettings.killLoot = on;
            }
            case "killdelay" -> {
                float sec;
                try {
                    sec = Float.parseFloat(val == null ? "" : val.replace(',', '.'));
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle killDelay <seconds 0-3> (0 = instant)", ERR);
                    return;
                }
                FxSettings.killDelayMs = Math.max(0, Math.min(FxSettings.MAX_KILL_DELAY_MS, Math.round(sec * 1000)));
            }
            case "maxmobs" -> {
                int n;
                try {
                    n = Integer.parseInt(val == null ? "" : val);
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle maxMobs <2-" + FxSettings.MAX_MOBS_LIMIT + ">", ERR);
                    return;
                }
                FxSettings.maxMobs = FxSettings.clampMaxMobs(n);
                if (FxSettings.maxMobs > 60) {
                    reply(ctx, "Warning: more than 60 fighting mobs can lag the server (their AI and pathfinding).", ERR);
                }
            }
            case "height" -> {
                int h;
                try {
                    h = Integer.parseInt(val == null ? "" : val);
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle areaHeight <" + FxSettings.MIN_HEIGHT + "-" + FxSettings.MAX_HEIGHT + ">", ERR);
                    return;
                }
                FxSettings.pillarHeight = FxSettings.clampHeight(h);
            }
            default -> {
                reply(ctx, USAGE, ERR);
                return;
            }
        }
        FxSettings.save();
        if (key.equals("radius") || key.equals("area") || key.equals("areacolor") || key.equals("height") || key.equals("areaonlywand")) {
            previewArea(ctx);
        }
        reply(ctx, "Saved. " + FxSettings.describeLines()[FxSettings.groupOf(key)], OK);
    }

    /** Draws the Chaos Wand area around the player, so radius/area/height/color changes are visible right away. */
    private static void previewArea(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            var t = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if (t != null) {
                FightFx.area(ref, new org.joml.Vector3d(t.getPosition()), FxSettings.radius, store);
            }
        });
    }

    /** /mobbattle summon ... (the list is saved with the other settings). */
    private static void summon(CommandContext ctx) {
        String[] a = argsAfter(ctx, "summon");
        String sub = a.length > 0 ? a[0].toLowerCase() : "spawn";
        switch (sub) {
            case "spawn", "go" -> {
                spawnAtSender(ctx);
                return;
            }
            case "list" -> {
                reply(ctx, SummonList.describe(), OK);
                return;
            }
            case "add" -> {
                if (a.length < 2) {
                    reply(ctx, "Usage: /mobbattle summon add <role> [count|random]", ERR);
                    return;
                }
                String role = SummonList.findRole(a[1]);
                if (role == null) {
                    reply(ctx, "Unknown NPC role: " + a[1] + " (IDs are case sensitive, e.g. Skeleton_Archer).", ERR);
                    return;
                }
                String count = a.length > 2 ? a[2] : "1";
                if (!SummonList.RANDOM.equalsIgnoreCase(count) && !count.matches("\\d{1,3}")) {
                    reply(ctx, "Count must be a number or random.", ERR);
                    return;
                }
                int team = -1;
                if (a.length > 3) {
                    team = BattleManager.teamIndex(a[3]);
                    if (team < 0) {
                        reply(ctx, "Unknown team. " + Teams.describe(), ERR);
                        return;
                    }
                }
                SummonList.ENTRIES.put(role, count.toLowerCase(java.util.Locale.ROOT));
                if (team >= 0) {
                    SummonList.TEAMS.put(role, team);
                }
            }
            case "modify" -> {
                if (a.length != 3) {
                    reply(ctx, "Usage: /mobbattle summon modify <role in the list> <count|random>", ERR);
                    return;
                }
                String role = SummonList.snapshot().keySet().stream()
                        .filter(known -> known.equalsIgnoreCase(a[1])).findFirst().orElse(null);
                if (role == null) {
                    reply(ctx, "Mob is not in the summon list: " + a[1] + ". Use summon add first.", ERR);
                    return;
                }
                String count = a[2];
                if (!SummonList.RANDOM.equalsIgnoreCase(count) && !count.matches("\\d{1,3}")) {
                    reply(ctx, "Count must be a number or random.", ERR);
                    return;
                }
                SummonList.ENTRIES.put(role, count.toLowerCase(java.util.Locale.ROOT));
            }
            case "remove" -> {
                String role = a.length > 1 ? SummonList.findRole(a[1]) : null;
                SummonList.TEAMS.remove(role);
                if (role == null || SummonList.ENTRIES.remove(role) == null) {
                    reply(ctx, "Usage: /mobbattle summon remove <role in the list>", ERR);
                    return;
                }
            }
            case "clear" -> {
                SummonList.ENTRIES.clear();
                SummonList.TEAMS.clear();
            }
            case "team" -> {
                String role = a.length > 2 ? SummonList.findRole(a[1]) : null;
                int team = a.length > 2 ? BattleManager.teamIndex(a[2]) : -1;
                if (role == null || (team < 0 && !"none".equalsIgnoreCase(a[2]))) {
                    reply(ctx, "Usage: /mobbattle summon team <role> <team|none>. " + Teams.describe(), ERR);
                    return;
                }
                if (team < 0) {
                    SummonList.TEAMS.remove(role);
                } else {
                    SummonList.TEAMS.put(role, team);
                }
            }
            case "random" -> {
                try {
                    int min = Integer.parseInt(a[1]);
                    int max = Integer.parseInt(a[2]);
                    SummonList.setRandomRange(min, max);
                } catch (RuntimeException e) {
                    reply(ctx, "Usage: /mobbattle summon random <min> <max>", ERR);
                    return;
                }
            }
            case "atleastone" -> {
                Boolean on = a.length > 1 ? parseOnOff(a[1]) : null;
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle summon atLeastOne true|false", ERR);
                    return;
                }
                SummonList.atLeastOne = on;
            }
            default -> {
                reply(ctx, "Usage: /mobbattle summon [list | add <role> [count|random] [team] | modify <role> <count|random> | team <role> <color|none> | remove <role> | clear"
                        + " | random <min> <max> | atLeastOne true|false]", ERR);
                return;
            }
        }
        FxSettings.save();
        reply(ctx, SummonList.describe(), OK);
    }

    private static final String TEAMS_USAGE = "Usage: /mobbattle teams auto | start | clear | join <team> | leave | list"
            + " | add <name> <color> | color <team> <color> | rename <team> <name> | remove <team> | reset";

    /** Team setup: list, add, color, rename, remove, reset. */
    private static boolean teamConfig(CommandContext ctx, String sub, String[] a) {
        String colors = " Fixed colors only: " + String.join(", ", Teams.PALETTE.keySet()).toLowerCase(java.util.Locale.ROOT) + ". Custom hex colors are disabled.";
        switch (sub) {
            case "list" -> reply(ctx, Teams.describe(), OK);
            case "add" -> {
                String hex = a.length > 2 ? Teams.parseColor(a[2]) : null;
                if (hex == null || !a[1].matches("[A-Za-z0-9_]{1,16}")) {
                    reply(ctx, "Usage: /mobbattle teams add <name> <color>. Name: letters, numbers, _ (max 16)." + colors, ERR);
                    return true;
                }
                int slot = Teams.add(a[1], hex);
                if (slot == -1) {
                    reply(ctx, "There is already a team called " + a[1] + ".", ERR);
                    return true;
                }
                if (slot == -2) {
                    reply(ctx, "All " + Teams.MAX + " team slots are used. Remove one first.", ERR);
                    return true;
                }
                reply(ctx, "Team " + Teams.name(slot) + " added (" + hex + "). " + Teams.describe(), hex);
            }
            case "color" -> {
                int slot = a.length > 2 ? Teams.find(a[1]) : -1;
                String hex = a.length > 2 ? Teams.parseColor(a[2]) : null;
                if (slot < 0 || hex == null) {
                    reply(ctx, "Usage: /mobbattle teams color <team> <color>." + colors + " " + Teams.describe(), ERR);
                    return true;
                }
                Teams.recolor(slot, hex);
                reply(ctx, Teams.name(slot) + " is now " + hex + ".", hex);
            }
            case "rename" -> {
                int slot = a.length == 3 ? Teams.find(a[1]) : -1;
                if (slot < 0 || !Teams.rename(slot, a[2])) {
                    reply(ctx, "Usage: /mobbattle teams rename <team> <new name>. Name must be unique: letters, numbers, _ (max 16). " + Teams.describe(), ERR);
                    return true;
                }
                reply(ctx, "Team renamed to " + Teams.name(slot) + ".", Teams.color(slot));
            }
            case "remove" -> {
                int slot = a.length > 1 ? Teams.find(a[1]) : -1;
                if (slot < 0) {
                    reply(ctx, "Usage: /mobbattle teams remove <team>. " + Teams.describe(), ERR);
                    return true;
                }
                if (Teams.count() <= 2) {
                    reply(ctx, "You need at least 2 teams.", ERR);
                    return true;
                }
                String name = Teams.name(slot);
                Teams.remove(slot);
                reply(ctx, "Team " + name + " removed (and its summon rules). " + Teams.describe(), OK);
            }
            case "reset" -> {
                BattleManager.clearConfiguredTeams();
                Teams.resetDefaults();
                SummonList.TEAMS.values().removeIf(t -> !Teams.exists(t));
                reply(ctx, "Teams reset to the defaults. " + Teams.describe(), OK);
            }
            default -> {
                return false;
            }
        }
        FxSettings.save();
        return true;
    }

    /** /mobbattle morph <model> | list [text] | reset: pick the Morph Wand model by ID, or undo morphs. */
    private static void morph(CommandContext ctx) {
        String[] a = argsAfter(ctx, "morph");
        String sub = a.length > 0 ? a[0] : "";
        if (sub.isEmpty()) {
            reply(ctx, "Usage: /mobbattle morph <model> | list [text] | reset", ERR);
            return;
        }
        if (sub.equalsIgnoreCase("list")) {
            var found = MorphWand.search(a.length > 1 ? a[1] : "", 40);
            reply(ctx, found.isEmpty() ? "No model matches." : "Models: " + String.join(", ", found), OK);
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, "Only a player can use /mobbattle morph.", ERR);
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        var store = ref.getStore();
        if (sub.equalsIgnoreCase("reset")) {
            store.getExternalData().getWorld().execute(() ->
                    reply(ctx, MorphWand.restoreAll(store) + " mobs got their own model back.", OK));
            return;
        }
        String id = MorphWand.findModel(sub);
        if (id == null) {
            reply(ctx, "Unknown model. Search with /mobbattle morph list <text>, e.g. /mobbattle morph list frog", ERR);
            return;
        }
        store.getExternalData().getWorld().execute(() -> {
            var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (player != null) {
                MorphWand.setCopy(player.getUuid(), new MorphWand.Copy(id, 1f));
                reply(ctx, "Morph Wand model: " + id + ". Left click a mob with the Morph Wand.", OK);
            }
        });
    }

    /** /mobbattle stop | resetall. Runs on the sender's world thread (team look and models are components). */
    private static void reset(CommandContext ctx, boolean all) {
        Runnable job = () -> {
            int countdowns = Countdown.cancelAll();
            // Everything a fight shows goes too: timer boxes, arenas and areas, camera shots, battle music.
            Arena.clearAll();
            Snap.clearAll();
            int shots = FightCamera.stopAll();
            BattleMusic.stopAll();
            var sender = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
            FightFx.clearAreas(sender != null && sender.isValid() ? sender.getStore().getExternalData().getWorld() : null);
            String msg = BattleManager.stopAll() + " battles stopped, " + countdowns + " countdowns cancelled"
                    + (shots > 0 ? ", " + shots + " camera shots ended" : "");
            if (all) {
                msg += ", " + BattleManager.clearAllDrafts() + " mobs out of their teams";
                msg += ", " + Army.clearAll() + " mobs out of their armies";
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (ref != null && ref.isValid()) {
                    msg += ", " + MorphWand.restoreAll(ref.getStore()) + " morphs undone";
                }
            }
            reply(ctx, msg + ".", OK);
        };
        var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref != null && ref.isValid()) {
            ref.getStore().getExternalData().getWorld().execute(job);
        } else {
            job.run();
        }
    }

    /** /mobbattle teams auto | start | clear: team battles without clicking every mob. */
    private static void teams(CommandContext ctx) {
        String[] a = argsAfter(ctx, "teams");
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        if (teamConfig(ctx, sub, a)) {
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, "Only a player can use /mobbattle teams.", ERR);
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            var t = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if (player == null || t == null) {
                return;
            }
            switch (sub) {
                case "auto" -> {
                    if (SummonList.TEAMS.isEmpty()) {
                        player.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                                "No team rules. Example: /mobbattle summon team Skeleton red").color(ERR));
                        return;
                    }
                    var draft = BattleManager.draft(player);
                    int n = 0;
                    for (var npc : ChaosWand.npcsAround(new org.joml.Vector3d(t.getPosition()), store)) {
                        Integer team = SummonList.TEAMS.get(ChaosWand.nameOf(npc, store));
                        if (team != null) {
                            BattleManager.assign(draft, npc, team);
                            n++;
                        }
                    }
                    ChaosWand.warnLeftOut(player);
                    FightFx.area(ref, new org.joml.Vector3d(t.getPosition()), FxSettings.radius, store);
                    player.sendMessage(com.hypixel.hytale.server.core.Message.raw(n + " mobs within " + FxSettings.radius
                            + " blocks put in teams. Start with /mobbattle teams start or the Team Wand.").color(OK));
                }
                case "start" -> TeamActionInteraction.start(player, ref, BattleManager.draft(player), store);
                case "join" -> PlayerFighters.joinTeam(player, ref, a.length > 1 ? a[1] : null, store);
                case "leave" -> PlayerFighters.leaveTeam(player, ref);
                case "clearall" -> player.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                        "Every team cleared (" + BattleManager.clearAllDrafts() + " mobs).").color(OK));
                case "clear" -> player.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                        "Teams cleared (" + BattleManager.clearDraftAndFx(player) + " mobs).").color(OK));
                default -> player.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                        TEAMS_USAGE).color(ERR));
            }
        });
    }

    private static void giveWands(CommandContext ctx) {
        if (argsAfter(ctx, "give").length != 0) {
            reply(ctx, "Usage: /mobbattle give", ERR);
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, "Only a player can receive the wands.", ERR);
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid() || ref.getStore() != store) {
                return;
            }
            int received = 0;
            var missing = new java.util.ArrayList<String>();
            for (String id : WAND_IDS) {
                var transaction = com.hypixel.hytale.server.core.entity.entities.Player.giveItem(
                        new com.hypixel.hytale.server.core.inventory.ItemStack(id, 1), ref, store);
                var remainder = transaction.getRemainder();
                if (remainder == null || remainder.isEmpty()) {
                    received++;
                } else {
                    missing.add(id);
                }
            }
            reply(ctx, "Received " + received + "/" + WAND_IDS.length + " Mob Battle wands."
                    + (missing.isEmpty() ? "" : " Not enough inventory space for: " + String.join(", ", missing)),
                    missing.isEmpty() ? OK : ERR);
        });
    }

    private static void spawnAtSender(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            reply(ctx, "Only a player can summon (mobs spawn around you).", ERR);
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            var t = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (t != null && player != null) {
                SummonList.spawn(store, new org.joml.Vector3d(t.getPosition()), player, ref);
            }
        });
    }

    /** Every word after {@code key} in the raw input. */
    private static String[] argsAfter(CommandContext ctx, String key) {
        String input = ctx.getInputString();
        if (input == null) {
            return new String[0];
        }
        String[] words = input.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            if (words[i].equalsIgnoreCase(key)) {
                return java.util.Arrays.copyOfRange(words, i + 1, words.length);
            }
        }
        return new String[0];
    }

    /** The word after the setting in the raw command input, or null. */
    private static String valueOf(CommandContext ctx, String key) {
        String input = ctx.getInputString();
        if (input == null) {
            return null;
        }
        String[] words = input.trim().split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].equalsIgnoreCase(key)) {
                return words[i + 1];
            }
        }
        return null;
    }

    private static Boolean parseOnOff(String val) {
        if (val == null) {
            return null;
        }
        return switch (val.toLowerCase()) {
            case "on", "true", "yes", "si" -> true;
            case "off", "false", "no" -> false;
            default -> null;
        };
    }

    private static void reply(CommandContext ctx, String text, String color) {
        ctx.sendMessage(Message.raw(text).color(color));
    }
}
