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
 *   <li>settings</li>
 *   <li>laser on|off</li>
 *   <li>color #RRGGBB|random</li>
 *   <li>title on|off</li>
 *   <li>wintitle on|off (winner title)</li>
 *   <li>style Default|Major|GoblinBreach|VoidEviction|random</li>
 *   <li>radius &lt;blocks&gt; (Chaos Wand area)</li>
 *   <li>areaStyle border|disc|discBorder|wall|off (what areas and arenas draw; the border color is /mobbattle borderColor)</li>
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
            "MobBattle_SnapWand", "MobBattle_BuffWand", "MobBattle_DebugWand"
    };

    /** /mobbattle help [topic]: line 0 is the title, "  " lines are details. Commands in camelCase (any case works). */
    static final java.util.Map<String, String[]> HELP = new java.util.LinkedHashMap<>();
    static {
        HELP.put("", new String[] {
                "Dackoro's Mob Battle help",
                "/mobbattle give - get all thirteen wands (admin)",
                "/mobbattle settings - show the current settings",
                "/mobbattle help <topic> - more about one topic:",
                "  wands - what each wand does",
                "  titles - countdown, titles, cameras, music (global or per battle kind)",
                "  chaos - Chaos Wand area and mob count",
                "  battles - duels, rules, arenas, timers, snap, debug, stop, army",
                "  teams - Team Wand, teams and team look",
                "  summon - Summon Wand list",
                "  morph - Morph, Effect, Kill wands and wand effects",
                "  presets - default, fight, debug, cinematic, your own",
                "  config - the settings panel (every setting, lists, presets, tools)",
                "  server - permissions, server configuration file and reload",
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
                "Debug Wand: left click a mob: full report (chat, server log, debug/ file); right click: log its effects and health for 60 s",
        });
        HELP.put("titles", new String[] {
                "Titles and cameras (global, or per kind: <option> <duel|allVsOne|royale|teams|all> <value>; <kind> default = follow global)",
                "countdown <seconds> - 3, 2, 1 before a fight starts (0 = instant)",
                "startTitle on|off - title when a fight starts",
                "winTitle on|off - title with the winner",
                "titleStyle default|major|goblinBreach|voidEviction|random",
                "titleScope owner|nearby|world - who sees fight titles",
                "cameraScope owner|nearby|world - who sees the cameras (a fighter never gets the intro)",
                "camera on|off - intro camera during the countdown (or cameraTime)",
                "cameraStyle auto|orbit|duel|showcase|teams|focus|drone - auto: duel western, allVsOne focus, teams sweep, royale orbit",
                "cameraTime <seconds> - intro camera length; 0 = the countdown; >0 films even with countdown 0",
                "cameraShots <n> - showcase: how many fighters it shows (1 to maxMobs; each shot needs 1.2 s of cameraTime)",
                "endCamera off|winner|lastHit - films the winner or who landed the final kill, with its kill count",
                "endCameraTime <seconds> - end camera length",
                "  crouch while a camera plays (or skipCamera) to end it; an intro also ends when its battle ends",
                "skipCamera - end your intro or end camera now",
                "autoWatch on|off - start watching by yourself when the battle starts (after the countdown), for the cameraScope players; it ends the intro (default off)",
                "music on|off|<track> - battle music from the countdown to the end",
                "runes on|off - summoning runes and fighter marks at the start",
                "  e.g. countdown royale 5, cameraStyle royale showcase, camera duel off, endCamera all winner",
        });
        HELP.put("chaos", new String[] {
                "Chaos area",
                "areaStyle border|disc|discBorder|wall|off - what areas and arenas draw: border (only the border glow, default), disc (a disc on the ground), discBorder (both), wall (disc + see-through wall) or off",
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
                "buff lone|streak|champion size|health|damage|defense|all|off <percent> - battle buffs: lone = everyone-vs-one target, streak = per kill (up to buff streak maxKills, default 5), champion = the winners until they die or get cleansed (default 0 = none)",
                "buff wand size|health|damage|defense|all|off <percent> - Buff Wand, per click (default size 10, health 20, damage 10, defense 5; stacks up to buff wand maxStacks, default 5)",
                "buff lone|streak|champion|wand heal on|off - that buff also fills the health (lone: heals the target when picked, even with no lone buff); default off: health keeps the same share of the new max",
                "buff crown gold|<color>|off - crown on streak, champion and Buff Wand mobs (default gold)",
                "buff sparks <seconds>|off - replay the crown's sparks and rays on buffed mobs every N seconds (default 3)",
                "fightLaser on|off - laser between fighters at the start (per kind too)",
                "fightLaserColor <color>|random - laser color (palette; random = a bright one each fight)",
                "battleIsolation <duel|allVsOne|royale|teams|all> on|off - outside mobs cannot hurt battle mobs",
                "selfDefense <duel|allVsOne|royale|teams|all> on|off - a mob hit by an enemy that isn't its target turns on it (default on)",
                "friendlyFire <allVsOne|teams|all> on|off - allies hurt each other",
                "playerFights <duel|allVsOne|royale|teams|all> on|off - players fight too (default off, never in Creative):",
                "  the wands work on players like on mobs; crouch + Battle Wand on a mob: it vs you",
                "chaos me - every mob in the area attacks you (playerFights allVsOne on)",
                "battlePvp on|off - enemy players in a battle hurt each other even with the world's PvP off",
                "arena <kind|all> on|show|wall|off - area drawn the whole fight (show), nobody leaves it (wall), both (on)",
                "arenaSize <blocks>|auto - arena radius of duels and team battles (Chaos Wand: areaRadius)",
                "timer <kind|all> <time>|off - time limit with a timer box: 90, 2m, 1m30s, --minutes=2 --seconds=30",
                "scoreboard <kind|all> on|off - HUD with one row per side: teams alive/total and kills, target health vs attackers, royale left + top killers, duel health (default teams on)",
                "wave <summon|snap|slam|allVsOne|royale|all> <color|off> - ground shockwave color per action (palette colors; default summon green, snap white, slam/royale purple, allVsOne yellow)",
                "mobFx <duel|allVsOne|attackers|royale|all> <color|default|off> - tint, aura and rune on the battle mobs (default = original look; royale purple by default)",
                "crown gold|<color>|off - everyone vs one: crown with rays of light over the lone target for 20 s (default gold)",
                "cleanseColor <color> - Cleanse Wand look: light from above, burst, aura and rune (default white)",
                "buffColor <color> - Buff Wand look: tint, swirl, burst, aura and rune (default yellow = gold; taking the buff off is grey)",
                "borderColor <summon|snap|slam|allVsOne|royale|all> <color|off|area> - border glow color per action (area = areaColor; default summon green, allVsOne yellow, royale purple, snap/slam off: a color draws a ring at their reach)",
                "arenaShrink <kind|all> on|off - the arena closes in like a battle royale zone (default: royale on)",
                "arenaShrinkTo <blocks> - final radius (default 15); arenaShrinkTime <time> - with no timer (default 2m)",
                "arenaShrinkTime <90|2m|1m30s> - how long a closing arena takes when the battle has no timer (default 2m)",
                "unstick on|off - a mob stuck 10 s on another level (cave, mountain; fliers 20 s, walls too) appears next to its opponent",
                "duelCard on|off - read-only card with both fighters' stats (health, attack, defense, size, with their buffs) in a 1 vs 1 duel, from the countdown to the end (default off)",
                "judgeBy <kind|all> health|damage|kills|mix - what timeUp health/judge scores: health % left (default), share of the damage or kills (per fighter, so bigger sides don't win for size), or the three averaged",
                "tieBreak damage|kills|random|draw - timeUp health/judge when the judgeBy score is even (the same as judgeBy, damage or kills, takes the other one)",
                "snap - half of the mobs within snapRadius (fighting or not) turn to dust, like the Snap Wand",
                "snapMode global|team - half of everyone, or half of each side",
                "snapPercent <1-100> - share that turns to dust (default 50)",
                "snapTime <1-30> - seconds over which they turn to dust (default 5)",
                "snapHitDelay <ms> - Snap Wand: wait after the click until the slam hits and the snap fires (default 310, 0-2000)",
                "snapRadius <blocks> - snap reach without battles (default 60, separate from areaRadius)",
                "snapPlayers on|off - the snap takes players in reach too (never you, never Creative; default off)",
                "timerStyle custom|portal - our own timer box (default) or the game's Void portal box",
                "timeUp <kind|all> draw|kill|continue|health|judge - when the timer ends (health: the best judgeBy score wins, default most health in %; judge: and the losers die, the winners heal)",
                "watch - spectate the nearest running battle (yours first), even after it started: the camera follows a fighter",
                "  right click: nearest fighter (not seen yet), left click: back to the previous one, mouse: turn the camera, Q or watch exit: leave",
                "  when the fighter dies the camera goes to its killer; a card shows its stats; after the battle you come back",
                "stop - end every battle, countdown, timer box, arena, camera shot, battle music and watch",
                "skipTimer - every running timer jumps to 0: the battles end by their timeUp rule (draw, judge...)",
                "army clearAll - disband every player's army",
                "resetAll - stop + teams clearAll + army clearAll + morph reset",
        });
        HELP.put("teams", new String[] {
                "Teams",
                "teams list - show the teams",
                "teams auto - put mobs in range into teams: summon team rules first, the rest one team per type (one type: split in two)",
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
                "teamByType on|off - clicking a mob brings every mob of its type within areaRadius into the team",
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
                "summonDespawn never|battleEnd|timer - summons leave on their own: never (default, they stay), battleEnd (the surviving summons of a battle go when it ends), timer (each goes summonLifetime seconds after it spawned)",
                "summonLifetime <seconds> - how long a summon lives with summonDespawn timer (5-3600, default 60)",
                "summonExit disintegrate|vanish - how summons leave: disintegrate like the Kill Wand (soul rip, no loot, default) or vanish at once (no loot)",
        });
        HELP.put("morph", new String[] {
                "Morph and effects",
                "morph <model> - set the Morph Wand model",
                "morph list [text] - search model IDs",
                "morph reset - every morphed mob gets its model back",
                "wandEffect <id> - Effect Wand effect, e.g. Burn; A>B>C gives each when the previous ends; jade[:color], shimmer[:color]",
                "wandFx on|off - glow, soul rip, charm, summoning runes... on the clicked mob",
                "killDelay <seconds> - Kill Wand soul rip before death (0-3, 0 = instant)",
                "killHitDelay <ms> - Kill Wand: wait after the click until the cast hits; Hakai sound and disintegration start then (default 175, 0-2000)",
                "killLoot on|off - the disintegrated mob drops its loot (default on)",
                "killPlayers on|off - the Kill Wand works on players too: they disintegrate, then die normally (default off: players are not affected)",
                "morphPlayers on|off - the Morph Wand works on players too (default on)",
                "eyeTest [<color>|off] - (experimental, admin) recolors the eyes of the nearest mob (8 blocks) with a texture made at runtime (palette color, default Purple); off puts the original models back",
        });
        HELP.put("presets", new String[] {
                "Presets",
                "preset list - built-in and saved presets",
                "preset default - every setting back to default (team and summon lists stay)",
                "preset fight - just the fights: no countdown, cameras, titles, music, lasers, runes, waves, borders, mob looks, crown, drawings or timers",
                "preset effects - countdown 3, 2 min timer (most health wins), titles, music, lasers, runes, area discBorder, waves, borders, mob looks, scoreboards; no cameras",
                "preset debug - everything visible: area wall, waves, borders, mob looks, lasers, runes, arenas (presets never change the debug log: it's admin only)",
                "preset cinematic - countdown 5, cameras (duel western, royale drone, allVsOne focus, teams sweep), end camera with kill count, music, mob looks and crown; no waves or border glow",
                "preset save <name> / load <name> / delete <name> - your own presets",
                "reset effects|cameras|titles|arenas|rules|wands|all - only that group of settings back to default",
        });
        HELP.put("server", new String[] {
                "Server setup",
                "permissions open|server - open lets anyone use and configure; server checks use/config grants",
                "  dackoro.mob_battle.use - wands and player-started battles (default hytale:Adventurer)",
                "  dackoro.mob_battle.config - settings, presets and team/summon configuration (default hytale:Admin)",
                "  dackoro.mob_battle.admin - stop, resetAll, skipTimer, army clearAll, teams clearAll, teams reset, morph reset, debug log, reload, give, permissions and events (default hytale:Admin; required in both modes)",
                "config/panel opens the settings panel for reading in either mode; saving needs config when server mode is on.",
                "The panel Admin tab is separate and shown only to players with the real admin permission, even in open mode.",
                "permissions open|server, reload, stop, resetAll, skipTimer, army clearAll, teams clearAll, teams reset, morph reset, give, debug log, and server events remain admin-only in both modes.",
                "Pure settings work from the server console. Position-based actions need a player.",
                "Server settings are in the plugin data directory's fx.properties; edit it while the server is stopped, then run /mobbattle reload.",
                "reload - reload fx.properties from disk (admin)",
                "The permissions mode is saved in fx.properties and presets/reset preserve it; change it explicitly with the admin-only permissions command.",
                "event duel <uuidA> <uuidB> [world] - start an ownerless NPC duel",
                "event royale <x> <y> <z> <radius> [maxMobs] [world] - start an ownerless NPC royale",
                "event focus <uuid> [world] - nearby NPCs attack the specified NPC",
                "event list [radius] - show nearby NPC UUIDs (admin; player only)",
        });
        HELP.put("config", new String[] {
                "Configuration panel",
                "/mobbattle config or /mobbattle panel - open the settings panel; both commands are public for reading.",
                "Left menu: Presentation, Cameras, Per kind, Battle rules, Arenas & timers, Chaos area, Colors & looks, Buffs, Wands,",
                "  Teams, Summon list, Presets & reset, Tools, Admin. Every setting the commands change is there.",
                "Changing a value only marks it (gold): Save changes applies them all at once, Discard changes drops them.",
                "Presets and Reset buttons fill the form first, so you can check them before saving.",
                "Team list, summon list, tools and admin buttons run their command at once (chat shows the result).",
                "In server mode saving needs dackoro.mob_battle.config; the Admin page needs dackoro.mob_battle.admin.",
                "Admin is a separate tab, visible only with a real dackoro.mob_battle.admin grant in either permissions mode.",
                "Admin page: permissions open|server, reload, give, end timers, royale event, stop, armies, teams, morphs, resetAll.",
        });
    }


    /** wave / glow: one subcommand per action plus all, each with its values (chat suggestions). */
    private static Sub[] actionSubs(String... extra) {
        return new Sub[] {new Sub("summon", "Summon Wand right click", colorWords(extra)),
                new Sub("snap", "Snap Wand", colorWords(extra)),
                new Sub("slam", "Battle Wand crouch + air", colorWords(extra)),
                new Sub("allvsone", "Chaos Wand everyone vs one", colorWords(extra)),
                new Sub("royale", "Chaos Wand battle royale", colorWords(extra)),
                new Sub("all", "Every action at once", colorWords(extra))};
    }

    /** The palette colors (lowercase) plus {@code extra} words (off, area, default...), as suggestion nodes. */
    private static Sub[] colorWords(String... extra) {
        java.util.List<String> names = new java.util.ArrayList<>();
        Teams.PALETTE.keySet().forEach(n -> names.add(n.toLowerCase(java.util.Locale.ROOT)));
        names.addAll(java.util.List.of(extra));
        return words("Value", names.toArray(new String[0]));
    }

    private static final String USAGE = "Unknown command. /mobbattle help lists them all.";

    /**
     * One word of the command tree. Every node is a real subcommand so the chat suggests the next word;
     * all of them run the same parser over the raw input, and free values (numbers, IDs, colors) are
     * allowed as extra arguments ("/mobbattle areaRadius 20").
     */
    private static final class Sub extends CommandBase {
        Sub(String name, String description, Sub... children) {
            super(name, description);
            requireNoPermission();
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
                long n;
                try {
                    n = Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    return -1;
                }
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

    /** Command name -> the key its case uses in dispatch (internal only: the short keys are not commands). */
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
            java.util.Map.entry("battleisolation", "isolation"),
            java.util.Map.entry("bordercolor", "glow"));

    private static Sub arenaKind(String name) {
        return new Sub(name, "Arena of " + name, new Sub("on", "Drawn the whole fight, nobody leaves it"),
                new Sub("show", "Only drawn the whole fight"), new Sub("wall", "Only: nobody leaves it"),
                new Sub("off", "No arena"));
    }

    private static Sub timerKind(String name) {
        return new Sub(name, "Time limit of " + name + ": 90, 2m, 1m30s, --minutes=2 --seconds=30, or off",
                new Sub("off", "No time limit")).withDuration();
    }

    private static Sub judgeByKind(String name) {
        return new Sub(name, "What decides " + name + " at time up",
                new Sub("health", "Most health left in % (default)"),
                new Sub("damage", "Biggest share of the damage dealt, per fighter"),
                new Sub("kills", "Biggest share of the kills, per fighter"),
                new Sub("mix", "Health, damage and kills averaged"));
    }

    private static Sub timeUpKind(String name) {
        return new Sub(name, "When the timer of " + name + " ends",
                new Sub("draw", "They stop: draw (default, except royale: judge)"), new Sub("kill", "Every fighter dies"),
                new Sub("continue", "They keep fighting, no winner title"),
                new Sub("health", "The best judgeBy score wins (default: most health left in %)"),
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

    /** Gold, the palette colors and off (crown colors). */
    private static Sub[] crownWords() {
        java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of("gold"));
        Teams.PALETTE.keySet().forEach(n -> names.add(n.toLowerCase(java.util.Locale.ROOT)));
        names.add("off");
        return words("Crown", names.toArray(new String[0]));
    }

    /** buff &lt;who&gt;: its stats, all and off (and maxKills for the streak), with example percents. */
    private static Sub buffWho(String who, String desc) {
        java.util.List<Sub> kids = new java.util.ArrayList<>();
        for (Buffs.Stat st : Buffs.Stat.values()) {
            kids.add(new Sub(st.key, st.key + " %, 0-" + st.max + ("streak".equals(who) ? " per kill" : "wand".equals(who) ? " per click" : ""),
                    words("Percent", "0", "10", "25", "50")));
        }
        kids.add(new Sub("all", "Every stat to the same % (size stops at 200, defense at 90)", words("Percent", "0", "10", "25", "50")));
        kids.add(new Sub("off", "Every stat back to 0"));
        kids.add(new Sub("heal", "lone".equals(who) ? "Heal the target to full when it is picked (default off)"
                : "This buff also fills the health (default off: health keeps the same share of the new max)",
                new Sub("on", "Heal to full"), new Sub("off", "Don't heal (default)")));
        if ("wand".equals(who)) {
            kids.add(new Sub("maxstacks", "Clicks that stack at most (1-" + Buffs.MAX_WAND + ", default 5)", words("Stacks", "1", "3", "5", "10")));
        }
        if ("streak".equals(who)) {
            kids.add(new Sub("maxkills", "Kills that count at most (1-" + Buffs.MAX_STREAK + ", default 5)", words("Kills", "3", "5", "10")));
        }
        return new Sub(who, desc, kids.toArray(new Sub[0]));
    }

    /** /mobbattle buff ...; false (after an error reply) if the input is invalid. */
    private static boolean setBuff(CommandContext ctx) {
        String[] a = argsAfter(ctx, "buff");
        String usage = "Usage: /mobbattle buff <lone|streak|champion|wand> <size|health|damage|defense|all|off> <percent>, "
                + "buff streak maxKills <1-" + Buffs.MAX_STREAK + ">, buff wand maxStacks <1-" + Buffs.MAX_WAND + ">, "
                + "buff <who> heal on|off, buff crown gold|<color>|off, buff sparks <1-" + Buffs.MAX_SPARKS_EVERY + " seconds>|off";
        if (a.length >= 2 && "sparks".equalsIgnoreCase(a[0])) {
            int s = -1;
            if ("off".equalsIgnoreCase(a[1])) s = 0;
            else try {
                s = Integer.parseInt(a[1].replace("s", ""));
            } catch (NumberFormatException ignored) {
            }
            if (s < 0 || s > Buffs.MAX_SPARKS_EVERY) {
                reply(ctx, usage, ERR);
                return false;
            }
            Buffs.sparksEvery = s;
            return true;
        }
        if (a.length >= 2 && "crown".equalsIgnoreCase(a[0])) {
            String c = Buffs.parseCrown(a[1]);
            if (c == null) {
                reply(ctx, usage, ERR);
                return false;
            }
            Buffs.crown = c;
            return true;
        }
        Buffs.Source who = a.length >= 2 ? Buffs.Source.of(a[0]) : null;
        if (who == null) {
            reply(ctx, usage, ERR);
            return false;
        }
        String what = a[1].toLowerCase(java.util.Locale.ROOT);
        if (what.equals("heal")) {
            Boolean on = a.length >= 3 ? parseOnOff(a[2]) : null;
            if (on == null) {
                reply(ctx, usage, ERR);
                return false;
            }
            Buffs.setHeal(who, on);
            return true;
        }
        if (what.equals("off")) {
            for (Buffs.Stat st : Buffs.Stat.values()) Buffs.setPercent(who, st, 0);
            return true;
        }
        int v = -1;
        try {
            if (a.length >= 3) v = Integer.parseInt(a[2].replace("%", ""));
        } catch (NumberFormatException ignored) {
        }
        if (what.equals("maxkills") && who == Buffs.Source.STREAK) {
            if (v < 1 || v > Buffs.MAX_STREAK) {
                reply(ctx, usage, ERR);
                return false;
            }
            Buffs.streakMax = v;
            return true;
        }
        if (what.equals("maxstacks") && who == Buffs.Source.WAND) {
            if (v < 1 || v > Buffs.MAX_WAND) {
                reply(ctx, usage, ERR);
                return false;
            }
            Buffs.wandMax = v;
            return true;
        }
        Buffs.Stat stat = Buffs.Stat.of(what);
        if (!what.equals("all") && stat == null || v < 0 || v > (stat != null ? stat.max : 1000)) {
            reply(ctx, usage, ERR);
            return false;
        }
        // all: each stat capped at its own maximum (size 200, defense 90).
        for (Buffs.Stat st : Buffs.Stat.values()) {
            if (stat == null || st == stat) Buffs.setPercent(who, st, v);
        }
        return true;
    }

    private static Sub battleKind(String name, String desc) {
        return new Sub(name, desc, new Sub("on", "On"), new Sub("off", "Off"));
    }

    /**
     * Chat suggestions of a KindFx option: the global values, one node per battle kind (its values and
     * "default") and "all" (its values). A value with the name of a kind (cameraStyle duel) becomes that
     * kind's node, which still lists the values.
     */
    private static Sub kindFxSub(String name, String desc, String... values) {
        java.util.Map<String, Sub> nodes = new java.util.LinkedHashMap<>();
        for (String v : values) {
            nodes.put(v, new Sub(v, "Global value"));
        }
        for (String kind : new String[] {"duel", "allvsone", "royale", "teams"}) {
            Sub[] kids = new Sub[values.length + 1];
            for (int i = 0; i < values.length; i++) {
                kids[i] = new Sub(values[i], "Only " + kind + " battles");
            }
            kids[values.length] = new Sub("default", "Back to the global value");
            nodes.put(kind, new Sub(kind, "Only " + kind + " battles", kids));
        }
        Sub[] everyKids = new Sub[values.length];
        for (int i = 0; i < values.length; i++) {
            everyKids[i] = new Sub(values[i], "Every kind at once");
        }
        nodes.put("all", new Sub("all", "Every kind at once", everyKids));
        Sub[] children = nodes.values().toArray(new Sub[0]);
        return new Sub(name, desc, children);
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

    /** The KindFx option of a switch key (the RENAMED internal keys: title, laser, area...). */
    private static KindFx.Opt optFor(String key) {
        return switch (key) {
            case "title" -> KindFx.Opt.START_TITLE;
            case "wintitle" -> KindFx.Opt.WIN_TITLE;
            case "laser" -> KindFx.Opt.LASER;
            case "area" -> KindFx.Opt.AREA_STYLE;
            case "countdown" -> KindFx.Opt.COUNTDOWN;
            case "camera" -> KindFx.Opt.CAMERA;
            case "music" -> KindFx.Opt.MUSIC;
            case "camerastyle" -> KindFx.Opt.CAMERA_STYLE;
            case "cameratime" -> KindFx.Opt.CAMERA_TIME;
            case "camerashots" -> KindFx.Opt.CAMERA_SHOTS;
            case "endcamera" -> KindFx.Opt.END_CAMERA;
            case "endcameratime" -> KindFx.Opt.END_CAMERA_TIME;
            case "autowatch" -> KindFx.Opt.AUTO_WATCH;
            case "runes" -> KindFx.Opt.RUNES;
            default -> throw new IllegalArgumentException("Not a KindFx option: " + key);
        };
    }

    /**
     * /mobbattle &lt;option&gt; &lt;value&gt; sets the global value; &lt;option&gt; &lt;kind&gt; &lt;value&gt; overrides it for
     * one battle kind; &lt;option&gt; &lt;kind&gt; default drops that override; &lt;option&gt; all &lt;value&gt; sets the global
     * value and drops every override. Returns false (after an error reply) if the input is invalid.
     */
    private static boolean setKindFx(CommandContext ctx, KindFx.Opt opt) {
        String[] a = argsAfter(ctx, settingOf(ctx));
        String usage = "Usage: /mobbattle " + opt.word + " [duel|allVsOne|royale|teams|all] " + KindFx.usage(opt)
                + "  (with a kind: that kind only; <kind> default: back to the global value)";
        BattleManager.Kind kind = null;
        boolean every = false;
        String raw;
        if (a.length == 1) {
            raw = a[0];
        } else if (a.length == 2) {
            String kindWord = a[0].toLowerCase(java.util.Locale.ROOT);
            every = "all".equals(kindWord);
            if (!every) {
                kind = FxSettings.kindOf(kindWord);
                if (kind == null) {
                    reply(ctx, usage, ERR);
                    return false;
                }
                if ("default".equalsIgnoreCase(a[1]) || "global".equalsIgnoreCase(a[1])) {
                    KindFx.setOverride(kind, opt, null);
                    if (opt == KindFx.Opt.MUSIC) BattleMusic.stopSilenced();
                    return true;
                }
            }
            raw = a[1];
        } else {
            reply(ctx, usage, ERR);
            return false;
        }
        String v = KindFx.parse(opt, raw);
        if (v == null) {
            reply(ctx, usage, ERR);
            return false;
        }
        if (kind != null) {
            KindFx.setOverride(kind, opt, v);
        } else {
            KindFx.setGlobal(opt, v);
            if (every) KindFx.clearOverrides(opt);
        }
        if (opt == KindFx.Opt.MUSIC) BattleMusic.stopSilenced();
        return true;
    }

    public MobBattleCommand() {
        super("mobbattle", "Dackoro's Mob Battle: wands that make mobs fight. Settings, teams, summon list and morphs.");
        requireNoPermission();
        setAllowsExtraArguments(true);
        addSubCommand(new Sub("settings", "Show the current settings"));
        addSubCommand(new Sub("help", "Explain the wands and commands: help [topic]",
                words("Help topic", "wands", "titles", "chaos", "battles", "teams", "summon", "morph", "presets", "config", "server")));
        addSubCommand(new Sub("config", "Open the read-only public configuration panel"));
        addSubCommand(new Sub("panel", "Alias for config: open the read-only public configuration panel"));
        addSubCommand(new Sub("permissions", "Set Mob Battle access policy: open|server"));
        addSubCommand(new Sub("reload", "Reload fx.properties from the server's plugin data directory"));
        addSubCommand(new Sub("event", "Admin event commands for server-launched battles"));
        Sub give = new Sub("give", "Receive one of each of the thirteen Mob Battle wands");
        addSubCommand(give);
        addSubCommand(kindFxSub("starttitle", "Fight-start title on/off (per battle kind too)", "on", "off"));
        addSubCommand(kindFxSub("wintitle", "Winner title on/off (per battle kind too)", "on", "off"));
        addSubCommand(kindFxSub("music", "Battle music: on, off, or a music track ID (default " + BattleMusic.DEFAULT_TRACK + ")",
                "on", "off", "Track_Z1D_Goblin_Boss_Battle", "Track_Void_Escalation", "Track_Void_Tension"));
        addSubCommand(kindFxSub("camera", "Intro camera during the countdown: orbit of the Chaos Wand area, western shots of a duel (per battle kind too)",
                "on", "off"));
        addSubCommand(new Sub("titlescope", "Who sees fight titles: owner, nearby (default, 64 blocks) or world",
                words("Title scope", "owner", "nearby", "world")));
        addSubCommand(new Sub("camerascope", "Who sees the fight cameras: owner (default), nearby (64 blocks) or world; a fighter never gets the intro",
                words("Camera scope", "owner", "nearby", "world")));
        addSubCommand(new Sub("titlestyle", "Title style", words("Title style", "default", "major", "goblinbreach", "voideviction", "random")));
        addSubCommand(kindFxSub("fightlaser", "Laser line between fighters at fight start on/off (per battle kind too)", "on", "off"));
        addSubCommand(new Sub("fightlasercolor", "Color of the fighter laser: a palette color or random", colorWords("random")));
        addSubCommand(new Sub("arearadius", "Chaos Wand area radius in blocks (" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ")"));
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
        addSubCommand(new Sub("selfdefense", "A battle mob hit by another enemy turns on it: <kind|all> on|off",
                battleKind("duel", "Battle Wand duels"),
                battleKind("allvsone", "Chaos Wand everyone vs one"),
                battleKind("royale", "Chaos Wand battle royale"),
                battleKind("teams", "Team Wand battles"),
                battleKind("all", "Every kind at once")));
        addSubCommand(new Sub("battleisolation", "Outside mobs can't hurt battle mobs and vice versa: <kind|all> on|off",
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
        addSubCommand(new Sub("crown", "Everyone vs one: crown with rays of light over the lone target, 20 s: gold (default), a palette color or off",
                crownWords()));
        addSubCommand(new Sub("buffcolor", "Buff Wand look (tint, swirl, burst, aura, rune): a palette color (default yellow = gold)",
                colorWords()));
        addSubCommand(new Sub("cleansecolor", "Cleanse Wand look (light from above, burst, aura, rune): a palette color (default white)",
                colorWords()));
        addSubCommand(new Sub("mobfx", "Look on battle mobs per role: <duel|allVsOne|attackers|royale|all> <palette color|default|off>",
                new Sub("duel", "Both duel fighters", colorWords("default", "off")),
                new Sub("allvsone", "The lone target", colorWords("default", "off")),
                new Sub("attackers", "Everyone attacking the lone target", colorWords("default", "off")),
                new Sub("royale", "Battle royale fighters", colorWords("default", "off")),
                new Sub("all", "Every role at once", colorWords("default", "off"))));
        addSubCommand(new Sub("wave", "Ground shockwave color per action: <summon|snap|slam|allVsOne|royale|all> <palette color|off>",
                actionSubs("off")));
        addSubCommand(new Sub("bordercolor", "Border glow color per action: <summon|snap|slam|allVsOne|royale|all> <palette color|off|area>",
                actionSubs("off", "area")));
        addSubCommand(new Sub("scoreboard", "Scoreboard HUD per battle kind: <duel|allVsOne|royale|teams|all> on|off",
                battleKind("duel", "Duel: each fighter's health"),
                battleKind("allvsone", "Everyone vs one: target health and attackers left"),
                battleKind("royale", "Battle royale: fighters left and top killers"),
                battleKind("teams", "Team battles: alive and kills per side (default on)"),
                battleKind("all", "Every kind at once")));
        addSubCommand(new Sub("arenashrinkto", "Final radius of a closing arena in blocks (" + FxSettings.MIN_SHRINK_TO + "-"
                + FxSettings.MAX_ARENA + ", default 15)"));
        addSubCommand(new Sub("arenashrinktime", "How long a closing arena takes when the battle has no timer: 90, 2m, 1m30s (default 2m)")
                .withDuration());
        addSubCommand(new Sub("unstick", "Battle mobs stuck on another level than their opponent (cave, mountain) appear next to it",
                new Sub("on", "Bring them together after 10 s (default)"), new Sub("off", "Leave them")));
        addSubCommand(new Sub("duelcard", "Read-only card with both fighters' stats (health, attack, defense, size) in a 1 vs 1 duel (default off)",
                new Sub("on", "Show the card from the countdown to the end of the duel"), new Sub("off", "No card")));
        addSubCommand(new Sub("tiebreak", "timeUp health/judge when the judgeBy score is even: damage (default), kills, random or draw",
                new Sub("damage", "The side that dealt more damage wins"), new Sub("kills", "The side with more kills wins"),
                new Sub("random", "A coin toss"), new Sub("draw", "It stays a draw")));
        addSubCommand(new Sub("snap", "Half of the mobs within snapRadius of you, fighting or not, turn to dust"));
        addSubCommand(new Sub("snappercent", "Snap: share of the fighters that turn to dust, 1-100 (default 50)"));
        addSubCommand(new Sub("snaptime", "Snap: seconds over which they turn to dust, 1-" + FxSettings.MAX_SNAP_SECONDS + " (default 5)"));
        addSubCommand(new Sub("killhitdelay", "Kill Wand: ms from the click to the cast's hit, when the Hakai sound and the disintegration start, 0-" + FxSettings.MAX_SNAP_HIT_DELAY_MS + " (default " + FxSettings.DEFAULT_KILL_HIT_DELAY_MS + ")"));
        addSubCommand(new Sub("snaphitdelay", "Snap Wand: ms from the click to the slam's hit, when the snap fires, 0-" + FxSettings.MAX_SNAP_HIT_DELAY_MS + " (default " + FxSettings.DEFAULT_SNAP_HIT_DELAY_MS + ")"));
        addSubCommand(new Sub("snapradius", "Snap without battles: reach in blocks (default 60, separate from areaRadius)"));
        addSubCommand(new Sub("snapmode", "Snap: global (half of everyone, default) or team (half of each side)",
                new Sub("global", "Half of everyone, at random"), new Sub("team", "Half of each side")));
        addSubCommand(new Sub("timerstyle", "Timer box: custom (our own, default) or portal (the game's Void portal box)",
                words("Timer style", "custom", "portal")));
        addSubCommand(new Sub("timeup", "When the timer ends: <kind|all> draw|kill|continue|health|judge",
                timeUpKind("duel"), timeUpKind("allvsone"), timeUpKind("royale"), timeUpKind("teams"), timeUpKind("all")));
        addSubCommand(new Sub("judgeby", "What timeUp health/judge scores: <kind|all> health|damage|kills|mix",
                judgeByKind("duel"), judgeByKind("allvsone"), judgeByKind("royale"), judgeByKind("teams"), judgeByKind("all")));
        java.util.List<String> areaColors = new java.util.ArrayList<>();
        Teams.PALETTE.keySet().forEach(n -> areaColors.add(n.toLowerCase(java.util.Locale.ROOT)));
        areaColors.add("random");
        addSubCommand(new Sub("areaonlywand", "Area only for you, only while you hold the Chaos Wand",
                new Sub("on", "Only you, only while holding the Chaos Wand"),
                new Sub("off", "Everyone sees it for 6 s (default)")));
        addSubCommand(new Sub("areacolor", "Area color from the palette, or random",
                words("Area color", areaColors.toArray(new String[0]))));
        addSubCommand(kindFxSub("areastyle", "What areas and arenas draw: border (default), disc, discBorder, wall or off (per battle kind too)",
                "border", "disc", "discborder", "wall", "off"));
        addSubCommand(new Sub("areaheight", "Height of the zone wall in blocks (3-128)"));
        addSubCommand(new Sub("skiptimer", "Every running battle timer jumps to 0: the battles end by their timeUp rule"));
        addSubCommand(new Sub("buff", "Battle buffs: buff <lone|streak|champion> <size|health|damage|defense|all|off> <percent>",
                buffWho("lone", "Everyone vs one: the lone target, for that battle"),
                buffWho("streak", "Per kill in a battle (times its kills, up to maxKills), for that battle"),
                buffWho("champion", "The winners of a battle, until they die or get the Cleanse Wand"),
                buffWho("wand", "Buff Wand: per click (stacks up to maxStacks), mobs and players, until they die or get cleansed"),
                new Sub("crown", "Crown on streak, champion and Buff Wand mobs: gold, a palette color or off", crownWords()),
                new Sub("sparks", "Replay the crown's sparks and rays every N seconds on buffed mobs (default 3, off = only once)",
                        words("Seconds", "2", "3", "5", "10", "off"))));
        addSubCommand(new Sub("skipcamera", "End your intro or end camera now (same as crouching while it plays)"));
        addSubCommand(new Sub("watch","Spectate a running battle: right click = nearest fighter, left click = previous, mouse turns the camera, Q exits",
                new Sub("exit", "Stop watching: back to your game mode and position")));
        addSubCommand(new Sub("stop", "End every battle, countdown, timer box, arena, camera shot and battle music"));
        addSubCommand(new Sub("reset", "Settings of one group back to default",
                new Sub("effects", "Waves, border glow, mob looks, crown, runes, area, lasers, team look, wand effects"),
                new Sub("cameras", "Intro and end cameras"),
                new Sub("titles", "Countdown, titles and music"),
                new Sub("arenas", "Arenas, timers, time up, closing zone, scoreboards, tie break, unstick"),
                new Sub("rules", "Isolation, self defense, friendly fire, player fights, PvP, duel mode, radii, max mobs, team by type"),
                new Sub("wands", "Kill, Snap, Morph and Effect Wand settings"),
                new Sub("all", "Everything (like /mobbattle preset default)")));
        addSubCommand(new Sub("resetall", "Stop every battle, empty every team and army and undo every morph"));
        addSubCommand(new Sub("army", "Army Wand armies",
                new Sub("clearall", "Disband every player's army")));
        addSubCommand(kindFxSub("countdown", "Seconds of 3, 2, 1 titles before a fight starts (0-10, 0 = instant; per battle kind too)",
                "0", "3", "5", "10"));
        addSubCommand(kindFxSub("camerastyle", "Intro camera: auto, orbit (area), duel (western), showcase (close-ups), teams (team sweep), focus (the one, then around through the crowd), drone (flight over the battle)",
                "auto", "orbit", "duel", "showcase", "teams", "focus", "drone"));
        addSubCommand(kindFxSub("cameratime", "Intro camera seconds, 0 = as long as the countdown; >0 films even with no countdown",
                "0", "3", "5", "10"));
        addSubCommand(kindFxSub("camerashots", "Showcase camera: how many fighters it shows (1 to maxMobs)", "3", "4", "6", "12", "25"));
        addSubCommand(kindFxSub("endcamera", "Camera at the end: off, winner, or lastHit (who landed the final kill), with its kill count",
                "off", "winner", "lasthit"));
        addSubCommand(kindFxSub("endcameratime", "End camera seconds", "3", "5", "8"));
        addSubCommand(kindFxSub("autowatch", "Start watching (spectator camera on the fighters) by yourself as soon as the battle starts, after the countdown; it ends any intro camera",
                "on", "off"));
        addSubCommand(kindFxSub("runes", "Summoning runes and fighter marks at the start", "on", "off"));
        addSubCommand(new Sub("preset", "Presets: built-in or your own (preset list, save, load, delete)",
                new Sub("list", "Built-in and saved presets"),
                new Sub("default", "Every setting back to default (keeps team and summon lists)"),
                new Sub("fight", "Just the fights: no countdown, cameras, titles, music, lasers, runes, drawings or timers"),
                new Sub("effects", "Countdown 3, 2 min timer (most health wins), titles, music, lasers, runes, area, scoreboards; no cameras"),
                new Sub("debug", "Everything visible: area wall, waves, borders, mob looks, lasers, runes, arenas; no cinematics (the debug log is admin only: debugLog)"),
                new Sub("cinematic", "Countdown, full camera work, end camera with kill count, music; no lasers or drawings"),
                new Sub("save", "Save your current settings: save <name>"),
                new Sub("load", "Load a saved preset: load <name>"),
                new Sub("delete", "Delete a saved preset: delete <name>")));
        addSubCommand(new Sub("teambytype", "Team Wand: clicking a mob brings every mob of the same type within areaRadius into that team (default off)",
                words("Team by type", "on", "off")));
        addSubCommand(new Sub("snapplayers", "Snap also turns players in reach to dust (not Creative, never you; default off)",
                words("Snap players", "on", "off")));
        addSubCommand(new Sub("morphplayers", "Morph Wand works on players too (default on)",
                words("Morph players", "on", "off")));
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
        addSubCommand(new Sub("killplayers", "Kill Wand on players: they disintegrate, then die a normal death",
                new Sub("on", "The Kill Wand works on players"),
                new Sub("off", "Players are not affected (default)")));
        addSubCommand(new Sub("killloot", "Kill Wand: the disintegrated mob drops its loot",
                new Sub("on", "Drop its normal loot (default)"),
                new Sub("off", "No drops")));
        addSubCommand(new Sub("summonradius", "Summon area radius in blocks (1-64), or auto (default: grows with the count)",
                words("Radius", "auto")));
        addSubCommand(new Sub("summondespawn", "Summons leave on their own: never (default, they stay), battleEnd (the surviving summons of a battle go when it ends), or timer (summonLifetime after spawn)",
                words("Leave", "never", "battleEnd", "timer")));
        addSubCommand(new Sub("summonlifetime", "Seconds a summon lives with summonDespawn timer (5-3600, default 60)"));
        addSubCommand(new Sub("summonexit", "How summons leave: disintegrate (the Kill Wand's soul rip, no loot, default) or vanish (removed at once, no loot)",
                words("Exit", "disintegrate", "vanish")));
        addSubCommand(new Sub("maxmobs", "Max mobs per Chaos Wand click and per summon (2-200)"));
        addSubCommand(new Sub("wandeffect", "Effect Wand effect: any effect ID, e.g. Burn or Potion_Morph_Frog; A>B gives B when A ends; jade[:color] or shimmer[:color] (default green, purple)",
                words("Effect", EffectWand.PRESETS)));
        addSubCommand(new Sub("teamlook", "Team look: both, tint, mist, off, or time <seconds|infinite>",
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
        addSubCommand(new Sub("eyetest", "Experimental: recolor the eyes of the nearest mob (8 blocks) with a runtime texture: [<color>|off]",
                new Sub("off", "Put back the original model of every mob eyeTest changed")));
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

    /** Raw arguments after the root command, preserving the caller's argument casing. */
    private static String[] commandArguments(CommandContext ctx) {
        String input = ctx.getInputString();
        if (input == null) return new String[0];
        String[] words = input.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            if (words[i].replace("/", "").equalsIgnoreCase("mobbattle")) {
                return java.util.Arrays.copyOfRange(words, i + 1, words.length);
            }
        }
        return new String[0];
    }

    /** Every command path is classified before dispatch; read-only list/help paths remain public. */
    private static String requiredPermission(String[] args) {
        if (args.length == 0) return null;
        String command = args[0].toLowerCase(java.util.Locale.ROOT);
        String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        if (command.equals("help") || command.equals("settings") || command.equals("show")) return null;
        if (command.equals("config") || command.equals("panel")) return null;
        if (command.equals("teams")) {
            return switch (sub) {
                case "list" -> null;
                case "auto", "start", "join", "leave", "clear" -> Perms.USE;
                case "clearall", "reset" -> Perms.ADMIN;
                case "add", "color", "rename", "remove" -> Perms.CONFIG;
                default -> Perms.CONFIG;
            };
        }
        if (command.equals("summon")) {
            if (sub.equals("list")) return null;
            if (sub.isEmpty() || sub.equals("spawn") || sub.equals("go")) return Perms.USE;
            return Perms.CONFIG;
        }
        if (command.equals("morph")) {
            if (sub.equals("list")) return null;
            if (sub.equals("reset")) return Perms.ADMIN;
            return Perms.USE;
        }
        if (command.equals("preset")) return sub.isEmpty() || sub.equals("list") ? null : Perms.CONFIG;
        if (command.equals("permissions") || command.equals("reload") || command.equals("event")
                || command.equals("give") || command.equals("stop")
                || command.equals("resetall") || command.equals("debuglog") || command.equals("skiptimer")) return Perms.ADMIN;
        if (command.equals("army")) return Perms.ADMIN;
        if (command.equals("chaos")) return Perms.USE;
        if (command.equals("watch")) return sub.equals("exit") ? null : Perms.USE;
        if (command.equals("skipcamera")) return null;
        if (command.equals("eyetest")) return Perms.ADMIN;
        if (command.equals("snap") || command.equals("debug")) return Perms.USE;
        return Perms.CONFIG;
    }

    private static void dispatch(CommandContext ctx) {
        String key = settingOf(ctx);
        String val = valueOf(ctx, key);
        // The old short words (show, title, area...) were removed: only the full names are commands.
        if (RENAMED.containsValue(key)) {
            reply(ctx, USAGE, ERR);
            return;
        }
        key = RENAMED.getOrDefault(key, key);

        String required = requiredPermission(commandArguments(ctx));
        if (required != null && !Perms.allowed(ctx.sender(), required)) {
            reply(ctx, "No permission (needs " + required + ").", ERR);
            return;
        }

        switch (key) {
            case "help" -> {
                String[] lines = HELP.get(val == null ? "" : val.toLowerCase());
                if (lines == null) {
                    reply(ctx, "Unknown help topic. Topics: wands, titles, chaos, battles, teams, summon, morph, presets, config, server", ERR);
                    return;
                }
                reply(ctx, lines[0], "#ffd23e");
                for (int i = 1; i < lines.length; i++) {
                    reply(ctx, lines[i], lines[i].startsWith("  ") ? "#c8e6ff" : OK);
                }
                return;
            }
            case "config", "panel" -> {
                if (argsAfter(ctx, key).length != 0) {
                    reply(ctx, "Usage: /mobbattle config (or /mobbattle panel)", ERR);
                    return;
                }
                if (!ctx.isPlayer()) {
                    reply(ctx, "Only a player can open the configuration panel.", ERR);
                    return;
                }
                var ref = ctx.senderAsPlayerRef();
                if (ref == null || !ref.isValid()) return;
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    if (store.isShutdown() || !ref.isValid() || ref.getStore() != store) return;
                    var playerRef = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (playerRef != null) ConfigPage.open(playerRef, ref, store);
                });
                return;
            }
            case "give" -> {
                giveWands(ctx);
                return;
            }
            case "permissions" -> {
                if (!Perms.admin(ctx.sender())) {
                    reply(ctx, "No permission (needs " + Perms.ADMIN + "); changing permissions mode requires a real admin grant even while access is open.", ERR);
                    return;
                }
                String[] a = argsAfter(ctx, "permissions");
                if (a.length != 1) {
                    reply(ctx, "Usage: /mobbattle permissions open|server", ERR);
                    return;
                }
                String error = FxSettings.setPermissionsMode(a[0]);
                if (error != null) {
                    reply(ctx, error, ERR);
                    return;
                }
                reply(ctx, "Permissions mode saved: " + FxSettings.permissionsMode + ".", OK);
                return;
            }
            case "reload" -> {
                if (argsAfter(ctx, "reload").length != 0) {
                    reply(ctx, "Usage: /mobbattle reload", ERR);
                    return;
                }
                String error = FxSettings.reload();
                if (error != null) {
                    reply(ctx, error, ERR);
                    return;
                }
                reply(ctx, "Reloaded server settings from fx.properties (permissions " + FxSettings.permissionsMode + ").", OK);
                return;
            }
            case "event" -> {
                ServerEvents.handle(ctx, commandArguments(ctx));
                return;
            }
            case "show" -> {
                reply(ctx, "Dackoro's Mob Battle settings", "#ffd23e");
                for (String line : FxSettings.describeLines()) {
                    reply(ctx, line, OK);
                }
                return;
            }
            case "laser", "title", "wintitle", "music", "camera", "countdown", "area",
                 "camerastyle", "cameratime", "camerashots", "endcamera", "endcameratime", "autowatch", "runes" -> {
                if (!setKindFx(ctx, optFor(key))) return;
            }
            case "color" -> {
                String laser = FxSettings.parseLaserColor(val);
                if (laser == null) {
                    reply(ctx, "Usage: /mobbattle fightLaserColor <color>|random  (palette: " + String.join(", ", Teams.PALETTE.keySet()) + ")", ERR);
                    return;
                }
                FxSettings.laserColor = laser;
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
            case "duelcard" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle duelCard on|off", ERR);
                    return;
                }
                FxSettings.duelCard = on;
                if (!on) DuelCard.clearAll();
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
            case "judgeby" -> {
                var kv = kindAndValue(ctx, "judgeby", "judgeBy <duel|allVsOne|royale|teams|all> health|damage|kills|mix");
                if (kv == null) return;
                String by = FxSettings.parseJudgeBy(kv.getValue());
                if (by == null) {
                    reply(ctx, "Usage: /mobbattle judgeBy <kind|all> health|damage|kills|mix", ERR);
                    return;
                }
                kv.getKey().forEach(k -> FxSettings.JUDGE_BY.put(k, by));
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
            case "snaphitdelay" -> {
                try {
                    int v = Integer.parseInt(val == null ? "" : val.replace("ms", "").trim());
                    if (v < 0 || v > FxSettings.MAX_SNAP_HIT_DELAY_MS) throw new NumberFormatException();
                    FxSettings.snapHitDelayMs = v;
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle snapHitDelay <0-" + FxSettings.MAX_SNAP_HIT_DELAY_MS + "> (milliseconds)", ERR);
                    return;
                }
            }
            case "killhitdelay" -> {
                try {
                    int v = Integer.parseInt(val == null ? "" : val.replace("ms", "").trim());
                    if (v < 0 || v > FxSettings.MAX_SNAP_HIT_DELAY_MS) throw new NumberFormatException();
                    FxSettings.killHitDelayMs = v;
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle killHitDelay <0-" + FxSettings.MAX_SNAP_HIT_DELAY_MS + "> (milliseconds)", ERR);
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
            case "skipcamera" -> {
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (ref == null || !ref.isValid()) {
                    reply(ctx, "Only a player has a camera to skip.", ERR);
                    return;
                }
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var pr = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    reply(ctx, pr != null && FightCamera.cancel(pr) ? "Camera skipped." : "No camera to skip.", OK);
                });
                return;
            }
            case "watch" -> {
                String[] w = argsAfter(ctx, "watch");
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                boolean exit = w.length == 1 && "exit".equalsIgnoreCase(w[0]);
                if (ref == null || !ref.isValid() || w.length > 1 || (w.length == 1 && !exit)) {
                    reply(ctx, "Usage (player): /mobbattle watch [exit]", ERR);
                    return;
                }
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var pr = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (pr == null) return;
                    if (exit) BattleWatch.exit(pr, ref, store);
                    else BattleWatch.start(pr, ref, store);
                });
                return;
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
            case "selfdefense" -> {
                if (!setBattleRule(ctx, "selfdefense", FxSettings.SELF_DEFENSE, BattleManager.Kind.values())) {
                    return;
                }
            }
            case "isolation" -> {
                if (!setBattleRule(ctx, settingOf(ctx), FxSettings.ISOLATION, BattleManager.Kind.values())) {
                    return;
                }
            }
            case "titlescope" -> {
                String scope = val == null ? "" : val.toLowerCase();
                if (!java.util.Arrays.asList(FxSettings.TITLE_SCOPES).contains(scope)) {
                    reply(ctx, "Usage: /mobbattle titleScope owner|nearby|world", ERR);
                    return;
                }
                FxSettings.titleScope = scope;
            }
            case "camerascope" -> {
                String scope = val == null ? "" : val.toLowerCase();
                if (!java.util.Arrays.asList(FxSettings.TITLE_SCOPES).contains(scope)) {
                    reply(ctx, "Usage: /mobbattle cameraScope owner|nearby|world", ERR);
                    return;
                }
                FxSettings.cameraScope = scope;
            }
            case "scoreboard" -> {
                if (!setBattleRule(ctx, "scoreboard", FxSettings.SCOREBOARD, BattleManager.Kind.values())) {
                    return;
                }
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
            case "preset" -> {
                preset(ctx);
                return;
            }
            case "reset" -> {
                String[] a = argsAfter(ctx, "reset");
                if (a.length != 1) {
                    reply(ctx, "Usage: /mobbattle reset <" + String.join("|", Presets.GROUPS) + "> - only that group of"
                            + " settings back to default (all = everything; teams and summon list are kept)", ERR);
                    return;
                }
                String err = Presets.reset(a[0]);
                if (err != null) {
                    reply(ctx, err, ERR);
                    return;
                }
                reply(ctx, "Settings of " + a[0].toLowerCase() + " back to default.", OK);
                for (String line : FxSettings.describeLines()) reply(ctx, line, OK);
                return;
            }
            case "summon" -> {
                summon(ctx);
                return;
            }
            case "morph" -> {
                morph(ctx);
                return;
            }
            case "eyetest" -> {
                EyeRecolor.command(ctx, argsAfter(ctx, "eyetest"));
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
            case "buff" -> {
                if (!setBuff(ctx)) return;
            }
            case "skiptimer" -> {
                int n = Arena.skipTimers();
                if (n == 0) {
                    reply(ctx, "No battle has a running timer (/mobbattle timer).", ERR);
                } else {
                    reply(ctx, "Timer skipped: " + n + (n == 1 ? " battle ends" : " battles end")
                            + " now by its timeUp rule.", OK);
                }
                return;
            }
            case "stop", "resetall" -> {
                reset(ctx, key.equals("resetall"));
                return;
            }
            case "effect" -> {
                // A>B>C: a chain, each effect given when the previous one ends (EffectWand.chain).
                StringBuilder ids = new StringBuilder();
                for (String part : (val == null ? "" : val).split(">")) {
                    var found = EffectWand.find(part.trim());
                    if (found == null) {
                        reply(ctx, "Unknown effect " + part.trim() + ". Examples: " + String.join(", ", EffectWand.PRESETS), ERR);
                        return;
                    }
                    if (ids.length() > 0) ids.append('>');
                    ids.append(found.getId());
                }
                FxSettings.effect = ids.toString();
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
            case "summondespawn" -> {
                String mode = SummonDespawn.parseMode(val);
                if (mode == null) {
                    reply(ctx, "Usage: /mobbattle summonDespawn never|battleEnd|timer", ERR);
                    return;
                }
                FxSettings.summonDespawn = mode;
            }
            case "summonlifetime" -> {
                int sec = SummonDespawn.parseLifetime(val);
                if (sec < 0) {
                    reply(ctx, "Usage: /mobbattle summonLifetime <" + SummonDespawn.MIN_LIFETIME + "-" + SummonDespawn.MAX_LIFETIME + " seconds>", ERR);
                    return;
                }
                FxSettings.summonLifetime = sec;
            }
            case "summonexit" -> {
                String exit = SummonDespawn.parseExit(val);
                if (exit == null) {
                    reply(ctx, "Usage: /mobbattle summonExit disintegrate|vanish", ERR);
                    return;
                }
                FxSettings.summonExit = exit;
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
            case "teambytype" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle teamByType on|off", ERR);
                    return;
                }
                FxSettings.teamByType = on;
            }
            case "snapplayers" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle snapPlayers on|off", ERR);
                    return;
                }
                FxSettings.snapPlayers = on;
            }
            case "mobfx" -> {
                String[] a = argsAfter(ctx, "mobfx");
                if (a.length < 2 || !ActionFx.setMob(a[0], a[1])) {
                    reply(ctx, "Usage: /mobbattle mobFx <" + ActionFx.roles() + "> <palette color|default|off>", ERR);
                    return;
                }
            }
            case "crown" -> {
                String c = Buffs.parseCrown(val);
                if (c == null) {
                    reply(ctx, "Usage: /mobbattle crown gold|<palette color>|off", ERR);
                    return;
                }
                ActionFx.crown = c;
            }
            case "cleansecolor" -> {
                String c = Buffs.parseCrown(val);
                if (c == null || c.equals("off") || c.equals("Gold")) {
                    reply(ctx, "Usage: /mobbattle cleanseColor <palette color>  (default white)", ERR);
                    return;
                }
                FxSettings.cleanseColor = c;
            }
            case "buffcolor" -> {
                String c = Buffs.parseCrown(val);
                if (c == null || c.equals("off") || c.equals("Gold")) {
                    reply(ctx, "Usage: /mobbattle buffColor <palette color>  (default yellow = gold)", ERR);
                    return;
                }
                FxSettings.buffColor = c;
            }
            case "wave", "glow" -> {
                boolean glow = key.equals("glow");
                String[] a = argsAfter(ctx, glow ? "bordercolor" : key);
                if (a.length < 2 || !ActionFx.set(glow, a[0], a[1])) {
                    reply(ctx, "Usage: /mobbattle " + (glow ? "borderColor" : "wave") + " <" + ActionFx.keys() + "> <" + ActionFx.colors(glow) + ">", ERR);
                    return;
                }
            }
            case "morphplayers" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle morphPlayers on|off", ERR);
                    return;
                }
                FxSettings.morphPlayers = on;
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
            case "list" -> {
                reply(ctx, Teams.describe(), OK);
                return true;
            }
            case "add" -> {
                String hex = a.length > 2 ? Teams.parseColor(a[2]) : null;
                if (hex == null || !a[1].matches("[A-Za-z0-9_]{1,16}")) {
                    reply(ctx, "Usage: /mobbattle teams add <name> <color>. Name: letters, numbers, _ (max 16)." + colors, ERR);
                    return true;
                }
                int taken = Teams.withColor(hex, -1);
                if (taken >= 0) {
                    reply(ctx, "Team " + Teams.name(taken) + " already has that color: pick another one (two teams of one color look the same in game).", ERR);
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
                int taken = Teams.withColor(hex, slot);
                if (taken >= 0) {
                    reply(ctx, "Team " + Teams.name(taken) + " already has that color: pick another one.", ERR);
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

    private static final String PRESET_USAGE = "Usage: /mobbattle preset [list | default|fight|debug|cinematic|<name> | save|load|delete <name>]";

    /** /mobbattle preset [list | <name> | load|save|delete <name>]: whole looks at once (see Presets). */
    private static void preset(CommandContext ctx) {
        String[] a = argsAfter(ctx, "preset");
        String sub = a.length > 0 ? a[0].toLowerCase(java.util.Locale.ROOT) : "list";
        if (a.length == 0 || a.length == 1 && sub.equals("list")) {
            java.util.List<String> saved = Presets.saved();
            reply(ctx, "Presets: " + String.join(", ", Presets.BUILT_IN)
                    + (saved.isEmpty() ? "" : " | saved: " + String.join(", ", saved)), OK);
            return;
        }
        switch (sub) {
            case "load" -> {
                if (a.length != 2) {
                    reply(ctx, PRESET_USAGE, ERR);
                    return;
                }
                applyPreset(ctx, a[1]);
            }
            case "save" -> {
                if (a.length != 2) {
                    reply(ctx, PRESET_USAGE, ERR);
                    return;
                }
                String err = Presets.save(a[1]);
                if (err != null) {
                    reply(ctx, err, ERR);
                    return;
                }
                reply(ctx, "Saved preset " + a[1] + ". Load it with /mobbattle preset load " + a[1], OK);
            }
            case "delete" -> {
                if (a.length != 2) {
                    reply(ctx, PRESET_USAGE, ERR);
                    return;
                }
                String err = Presets.delete(a[1]);
                if (err != null) {
                    reply(ctx, err, ERR);
                    return;
                }
                reply(ctx, "Deleted preset " + a[1] + ".", OK);
            }
            default -> {
                if (a.length != 1) {
                    reply(ctx, PRESET_USAGE, ERR);
                    return;
                }
                applyPreset(ctx, a[0]);
            }
        }
    }

    /** Applies a built-in or saved preset, then lists the settings it leaves. */
    private static void applyPreset(CommandContext ctx, String name) {
        String err = Presets.apply(name);
        if (err != null) {
            reply(ctx, err, ERR);
            return;
        }
        reply(ctx, "Preset " + name + " applied.", OK);
        for (String line : FxSettings.describeLines()) {
            reply(ctx, line, OK);
        }
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
            Scoreboard.clearAll();
            DuelCard.clearAll();
            BattleWatch.exitAll();
            var sender = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
            FightFx.clearAreas(sender != null && sender.isValid() ? sender.getStore().getExternalData().getWorld() : null);
            String msg = BattleManager.stopAll() + " battles stopped, " + countdowns + " countdowns cancelled"
                    + (shots > 0 ? ", " + shots + " camera shots ended" : "");
            if (all) {
                msg += ", " + BattleManager.clearAllDrafts() + " mobs out of their teams";
                msg += ", " + Army.clearAll() + " mobs out of their armies";
                msg += ", " + Buffs.clearEverything() + " buffs removed";
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
        if (sub.equals("clearall")) {
            reply(ctx, "Every team cleared (" + BattleManager.clearAllDrafts() + " mobs).", OK);
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
                    // Summon list team rules first (summon team <mob> <team>); the other mobs go by type: each
                    // type its own team, in team order (more types than teams: they share). One type only: split
                    // evenly over the first two teams, so there is still a fight.
                    var draft = BattleManager.draft(player);
                    java.util.List<Integer> slots = new java.util.ArrayList<>();
                    for (int i = 0; i < Teams.MAX; i++) if (Teams.exists(i)) slots.add(i);
                    var around = ChaosWand.npcsAround(new org.joml.Vector3d(t.getPosition()), store);
                    java.util.Map<String, java.util.List<com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>>> byType = new java.util.LinkedHashMap<>();
                    int n = 0, ruled = 0;
                    for (var npc : around) {
                        String type = ChaosWand.nameOf(npc, store);
                        Integer team = SummonList.TEAMS.get(type);
                        if (team != null && Teams.exists(team)) {
                            BattleManager.assign(draft, npc, team);
                            n++;
                            ruled++;
                        } else {
                            byType.computeIfAbsent(type, k -> new java.util.ArrayList<>()).add(npc);
                        }
                    }
                    if (!byType.isEmpty() && slots.size() >= 2) {
                        if (byType.size() == 1 && ruled == 0) {
                            int i = 0;
                            for (var npc : byType.values().iterator().next()) {
                                BattleManager.assign(draft, npc, slots.get(i++ % 2));
                                n++;
                            }
                        } else {
                            int i = 0;
                            for (var group : byType.values()) {
                                int team = slots.get(i++ % slots.size());
                                for (var npc : group) {
                                    BattleManager.assign(draft, npc, team);
                                    n++;
                                }
                            }
                        }
                    }
                    ChaosWand.warnLeftOut(player);
                    FightFx.area(ref, new org.joml.Vector3d(t.getPosition()), FxSettings.radius, store);
                    player.sendMessage(com.hypixel.hytale.server.core.Message.raw(n + " mobs within " + FxSettings.radius
                            + " blocks put in teams" + (ruled > 0 ? " (" + ruled + " by summon team rules, the rest by type)" : " by type")
                            + ". Start with /mobbattle teams start or the Team Wand.").color(n > 0 ? OK : ERR));
                }
                case "start" -> TeamActionInteraction.start(player, ref, BattleManager.draft(player), store);
                case "join" -> PlayerFighters.joinTeam(player, ref, a.length > 1 ? a[1] : null, store);
                case "leave" -> PlayerFighters.leaveTeam(player, ref);
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
