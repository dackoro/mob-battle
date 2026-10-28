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
            "MobBattle_SummonWand", "MobBattle_MorphWand", "MobBattle_HealWand", "MobBattle_EquipWand",
            "MobBattle_KillWand", "MobBattle_EffectWand", "MobBattle_CleanseWand", "MobBattle_ArmyWand",
            "MobBattle_SnapWand", "MobBattle_BuffWand", "MobBattle_DebugWand"
    };

    /**
     * /mobbattle help [topic]: translation keys (mobbattle.help.&lt;topic&gt;.&lt;n&gt;), line 0 is the title. A key that starts
     * with two spaces is a detail line (printed indented, other color). Commands stay in camelCase (any case works).
     */
    static final java.util.Map<String, String[]> HELP = new java.util.LinkedHashMap<>();
    static {
        HELP.put("", new String[] {
                "help.main.1",
                "help.main.2",
                "help.main.3",
                "help.main.4",
                "  help.main.5",
                "  help.main.6",
                "  help.main.7",
                "  help.main.8",
                "  help.main.9",
                "  help.main.10",
                "  help.main.11",
                "  help.main.12",
                "  help.main.13",
                "  help.main.14",
        });
        HELP.put("wands", new String[] {
                "help.wands.1",
                "help.wands.2",
                "  help.wands.3",
                "help.wands.4",
                "  help.wands.5",
                "help.wands.6",
                "  help.wands.7",
                "help.wands.8",
                "  help.wands.9",
                "help.wands.10",
                "  help.wands.11",
                "help.wands.12",
                "help.wands.13",
                "  help.wands.14",
                "help.wands.15",
                "help.wands.16",
        });
        HELP.put("titles", new String[] {
                "help.titles.1",
                "help.titles.2",
                "help.titles.34",
                "help.titles.3",
                "help.titles.4",
                "help.titles.5",
                "help.titles.6",
                "help.titles.7",
                "help.titles.8",
                "help.titles.9",
                "help.titles.10",
                "help.titles.11",
                "help.titles.12",
                "help.titles.13",
                "  help.titles.14",
                "help.titles.15",
                "help.titles.16",
                "help.titles.20",
                "help.titles.21",
                "help.titles.22",
                "help.titles.30",
                "help.titles.31",
                "help.titles.23",
                "help.titles.24",
                "help.titles.25",
                "help.titles.26",
                "help.titles.27",
                "help.titles.32",
                "help.titles.28",
                "help.titles.29",
                "help.titles.33",
                "help.titles.17",
                "help.titles.18",
                "  help.titles.19",
        });
        HELP.put("chaos", new String[] {
                "help.chaos.1",
                "help.chaos.2",
                "help.chaos.3",
                "help.chaos.4",
                "help.chaos.5",
                "help.chaos.6",
                "help.chaos.7",
                "help.chaos.8",
                "help.chaos.9",
        });
        HELP.put("battles", new String[] {
                "help.battles.1",
                "help.battles.2",
                "help.battles.3",
                "help.battles.4",
                "help.battles.5",
                "help.battles.6",
                "help.battles.7",
                "help.battles.8",
                "help.battles.9",
                "help.battles.10",
                "help.battles.11",
                "help.battles.12",
                "help.battles.13",
                "help.battles.14",
                "help.battles.15",
                "help.battles.16",
                "help.battles.17",
                "help.battles.18",
                "help.battles.19",
                "help.battles.20",
                "  help.battles.21",
                "help.battles.22",
                "help.battles.23",
                "help.battles.24",
                "help.battles.25",
                "help.battles.26",
                "help.battles.27",
                "help.battles.28",
                "help.battles.29",
                "help.battles.30",
                "help.battles.31",
                "help.battles.32",
                "help.battles.33",
                "help.battles.34",
                "help.battles.35",
                "help.battles.36",
                "help.battles.37",
                "help.battles.38",
                "help.battles.39",
                "help.battles.40",
                "help.battles.41",
                "help.battles.42",
                "help.battles.43",
                "help.battles.44",
                "help.battles.45",
                "help.battles.46",
                "help.battles.maxtime",
                "help.battles.47",
                "help.battles.48",
                "help.battles.49",
                "help.battles.50",
                "help.battles.51",
                "help.battles.52",
                "help.battles.53",
                "help.battles.54",
                "help.battles.55",
                "help.battles.56",
                "help.battles.57",
                "help.battles.58",
                "help.battles.59",
                "help.battles.60",
                "help.battles.61",
                "help.battles.62",
                "  help.battles.63",
                "  help.battles.64",
                "help.battles.74",
                "help.battles.65",
                "help.battles.66",
                "help.battles.67",
                "help.battles.68",
                "help.battles.69",
                "help.battles.71",
                "help.battles.72",
                "help.battles.73",
                "help.battles.75",
                "help.battles.76",
                "help.battles.77",
                "help.battles.70",
        });
        HELP.put("teams", new String[] {
                "help.teams.1",
                "help.teams.2",
                "help.teams.3",
                "help.teams.4",
                "help.teams.5",
                "help.teams.6",
                "help.teams.7",
                "help.teams.8",
                "help.teams.9",
                "help.teams.10",
                "help.teams.11",
                "help.teams.12",
                "help.teams.13",
                "help.teams.14",
        });
        HELP.put("summon", new String[] {
                "help.summon.1",
                "help.summon.2",
                "help.summon.3",
                "help.summon.4",
                "help.summon.5",
                "help.summon.6",
                "help.summon.7",
                "help.summon.7b",
                "help.summon.8",
                "help.summon.9",
                "help.summon.10",
                "help.summon.11",
                "help.summon.12",
                "help.summon.13",
                "help.summon.14",
                "help.summon.15",
                "help.summon.16",
                "help.summon.17",
                "help.summon.18",
                "help.summon.19",
        });
        HELP.put("morph", new String[] {
                "help.morph.1",
                "help.morph.2",
                "help.morph.3",
                "help.morph.4",
                "help.morph.5",
                "help.morph.6",
                "help.morph.7",
                "help.morph.8",
                "help.morph.9",
                "help.morph.10",
                "help.morph.11",
                "help.morph.12",
                "help.morph.13",
                "help.morph.14",
                "help.morph.15",
                "help.morph.16",
                "help.morph.17",
                "help.morph.18",
                "help.morph.19",
                "help.morph.20",
                "help.morph.28",
                "help.morph.29",
                "help.morph.30",
                "help.morph.21",
                "help.morph.22",
                "help.morph.23",
                "help.morph.24",
                "help.morph.25",
                "help.morph.26",
                "help.morph.27",
        });
        HELP.put("presets", new String[] {
                "help.presets.1",
                "help.presets.2",
                "help.presets.3",
                "help.presets.4",
                "help.presets.5",
                "help.presets.6",
                "help.presets.7",
                "help.presets.8",
                "help.presets.9",
        });
        HELP.put("server", new String[] {
                "help.server.1",
                "help.server.2",
                "  help.server.3",
                "  help.server.4",
                "  help.server.5",
                "help.server.6",
                "help.server.7",
                "help.server.8",
                "help.server.9",
                "help.server.10",
                "help.server.11",
                "help.server.12",
                "help.server.13",
                "help.server.14",
                "help.server.15",
                "help.server.16",
                "help.server.17",
                "help.server.18",
                "help.server.19",
                "help.server.20",
                "help.server.21",
                "help.server.22",
                "help.server.23",
                "help.server.24",
                "help.server.25",
                "help.server.26",
                "help.server.27",
                "help.server.28",
                "help.server.29",
                "help.server.30",
                "help.server.31",
                "help.server.32",
                "help.server.33",
                "help.server.34",
                "help.server.35",
        });
        HELP.put("config", new String[] {
                "help.config.1",
                "help.config.2",
                "help.config.3",
                "  help.config.4",
                "help.config.5",
                "help.config.6",
                "help.config.7",
                "help.config.8",
                "help.config.9",
                "help.config.10",
        });
    }

    /** One help line, translated; maxMobs' line carries its limits. */
    private static Message helpLine(String key) {
        Message m = I18n.tr(key);
        if (key.equals("help.chaos.9")) {
            m = m.param("max", FxSettings.MAX_MOBS_LIMIT).param("warn", FxSettings.MAX_MOBS_WARN);
        }
        return m;
    }


    /** wave / glow: one subcommand per action plus all, each with its values (chat suggestions). */
    private static Sub[] actionSubs(String... extra) {
        return new Sub[] {new Sub("summon", I18n.id("cmd.desc.summon_wand_right_click"), colorWords(extra)),
                new Sub("snap", I18n.id("cmd.desc.snap_wand"), colorWords(extra)),
                new Sub("slam", I18n.id("cmd.desc.battle_wand_crouch_air"), colorWords(extra)),
                new Sub("allvsone", I18n.id("cmd.desc.chaos_wand_everyone_vs_one"), colorWords(extra)),
                new Sub("royale", I18n.id("cmd.desc.chaos_wand_battle_royale"), colorWords(extra)),
                new Sub("all", I18n.id("cmd.desc.every_action_at_once"), colorWords(extra))};
    }

    /** The palette colors (lowercase) plus {@code extra} words (off, area, default...), as suggestion nodes. */
    private static Sub[] colorWords(String... extra) {
        java.util.List<String> names = new java.util.ArrayList<>();
        Teams.PALETTE.keySet().forEach(n -> names.add(n.toLowerCase(java.util.Locale.ROOT)));
        names.addAll(java.util.List.of(extra));
        return words(I18n.id("cmd.desc.value"), names.toArray(new String[0]));
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
            withOptionalArg("minutes", I18n.id("cmd.desc.minutes_adds_to_seconds"),
                    com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.INTEGER);
            withOptionalArg("seconds", I18n.id("cmd.desc.seconds_adds_to_minutes"),
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

    /**
     * /mobbattle apply with suggestions (2026-10-13, user: no autocomplete for names, actions or effects): me, look and
     * players lead to the actions and their usual values; player takes an online name (PLAYER_REF suggests them);
     * near takes a radius. A plain name (apply Steve ...) still works, without suggestions.
     */
    private static Sub applySub() {
        java.util.List<Sub> who = new java.util.ArrayList<>();
        who.add(new Sub("me", I18n.id("cmd.desc.you_then_the_action"), applyActions()));
        who.add(new Sub("look", I18n.id("cmd.desc.the_mob_or_player_you_look_a"), applyActions()));
        who.add(new Sub("players", I18n.id("cmd.desc.every_player_in_your_world_t"), applyActions()));
        who.add(new Sub("near", I18n.id("cmd.desc.everyone_within_radius_block"), words(I18n.id("cmd.desc.radius"), "8", "16", "32")));
        Sub player = new Sub("player", I18n.id("cmd.desc.an_online_player_by_name_the"));
        player.withRequiredArg("name", I18n.id("cmd.desc.online_player"), com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.PLAYER_REF);
        who.add(player);
        return new Sub("apply", I18n.id("cmd.desc.admin_a_wand_action_on_playe"),
                who.toArray(new Sub[0]));
    }

    private static Sub[] applyActions() {
        java.util.List<Sub> out = new java.util.ArrayList<>();
        for (String a : ApplyCommand.ACTIONS) {
            Sub[] values = switch (a) {
                case "buff" -> words(I18n.id("cmd.desc.buff_wand_clicks_0_off"), "0", "1", "3", "5");
                case "size", "speed" -> words(I18n.id("cmd.desc.value_0_off"), "x2", "x3", "0");
                case "health", "damage" -> words(I18n.id("cmd.desc.value_0_off"), "x2", "x5", "x10", "0");
                case "defense" -> words(I18n.id("cmd.desc.value_0_off"), "x2", "x10", "0");
                case "effect" -> words(I18n.id("cmd.desc.effect_any_id_a_b_chains"), EffectWand.PRESETS);
                case "look" -> words(I18n.id("cmd.desc.look_add_color_e_g_jade_red"), "jade", "shimmer");
                case "army" -> words(I18n.id("cmd.desc.your_army"), "add", "remove");
                case "team" -> words(I18n.id("cmd.desc.team_name_or_none"), "Red", "Blue", "Green", "Yellow", "none");
                case "morph" -> words(I18n.id("cmd.desc.model_id_or_player_name"), "Frog_Green", "Skeleton", "Goblin_Scrapper");
                default -> new Sub[0];
            };
            out.add(new Sub(a, I18n.id("cmd.desc.apply." + a), values));
        }
        return out.toArray(new Sub[0]);
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
        return new Sub(name, I18n.id("cmd.desc.arena_of_this_battle_kind"), new Sub("on", I18n.id("cmd.desc.drawn_the_whole_fight_nobody")),
                new Sub("show", I18n.id("cmd.desc.only_drawn_the_whole_fight")), new Sub("wall", I18n.id("cmd.desc.only_nobody_leaves_it")),
                new Sub("off", I18n.id("cmd.desc.no_arena")));
    }

    private static Sub timerKind(String name) {
        return new Sub(name, I18n.id("cmd.desc.time_limit_of_this_battle_ki"),
                new Sub("off", I18n.id("cmd.desc.no_time_limit"))).withDuration();
    }

    private static Sub judgeByKind(String name) {
        return new Sub(name, I18n.id("cmd.desc.what_decides_this_battle_kin"),
                new Sub("health", I18n.id("cmd.desc.most_health_left_in_default")),
                new Sub("damage", I18n.id("cmd.desc.biggest_share_of_the_damage")),
                new Sub("kills", I18n.id("cmd.desc.biggest_share_of_the_kills_p")),
                new Sub("mix", I18n.id("cmd.desc.health_damage_and_kills_aver")));
    }

    private static Sub timeUpKind(String name) {
        return new Sub(name, I18n.id("cmd.desc.when_the_timer_of_this_battl"),
                new Sub("draw", I18n.id("cmd.desc.they_stop_draw_default_excep")), new Sub("kill", I18n.id("cmd.desc.every_fighter_dies")),
                new Sub("continue", I18n.id("cmd.desc.they_keep_fighting_no_winner")),
                new Sub("health", I18n.id("cmd.desc.the_best_judgeby_score_wins")),
                new Sub("judge", I18n.id("cmd.desc.most_health_left_in_wins_and")));
    }

    /** /mobbattle arena|timer|timeup [kind|all] value. Returns the kinds and the value, or null after an error reply. */
    private static java.util.Map.Entry<java.util.List<BattleManager.Kind>, String> kindAndValue(CommandContext ctx, String word,
                                                                                         String usage) {
        String[] a = argsAfter(ctx, word);
        String kindWord = a.length >= 2 ? a[0].toLowerCase(java.util.Locale.ROOT) : "all";
        String value = a.length >= 2 ? a[1] : a.length == 1 ? a[0] : null;
        BattleManager.Kind kind = FxSettings.kindOf(kindWord);
        if (value == null || !"all".equals(kindWord) && kind == null) {
            usage(ctx, "/mobbattle " + usage);
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
        return words(I18n.id("cmd.desc.crown"), names.toArray(new String[0]));
    }

    /** buff &lt;who&gt;: its stats, all and off (and maxKills for the streak), with example percents. */
    private static Sub buffWho(String who, String desc) {
        java.util.List<Sub> kids = new java.util.ArrayList<>();
        for (Buffs.Stat st : Buffs.Stat.values()) {
            kids.add(new Sub(st.key, I18n.id("cmd.desc.buffstat" + ("streak".equals(who) ? ".kill" : "wand".equals(who) ? ".click" : "")),
                    words(I18n.id("cmd.desc.percent"), "0", "10", "25", "50")));
        }
        kids.add(new Sub("all", I18n.id("cmd.desc.every_stat_to_the_same_defen"), words(I18n.id("cmd.desc.percent"), "0", "10", "25", "50")));
        kids.add(new Sub("off", I18n.id("cmd.desc.every_stat_back_to_0")));
        kids.add(new Sub("heal", I18n.id("lone".equals(who) ? "cmd.desc.heal.lone" : "cmd.desc.heal.other"),
                new Sub("on", I18n.id("cmd.desc.heal_to_full")), new Sub("off", I18n.id("cmd.desc.don_t_heal_default"))));
        if ("wand".equals(who)) {
            kids.add(new Sub("maxstacks", I18n.id("cmd.desc.most_clicks_that_stack_defau"), words(I18n.id("cmd.desc.stacks"), "1", "3", "5", "10")));
        }
        if ("streak".equals(who)) {
            kids.add(new Sub("maxkills", I18n.id("cmd.desc.most_kills_that_count_defaul"), words(I18n.id("cmd.desc.kills"), "3", "5", "10")));
        }
        return new Sub(who, desc, kids.toArray(new Sub[0]));
    }

    /** /mobbattle buff ...; false (after an error reply) if the input is invalid. */
    private static boolean setBuff(CommandContext ctx) {
        String[] a = argsAfter(ctx, "buff");
        String usage = "/mobbattle buff <lone|streak|champion|wand> <size|health|damage|defense|speed|all|off> <percent>, "
                + "buff streak maxKills <1-" + Buffs.MAX_STREAK + ">, buff wand maxStacks <1-" + Buffs.MAX_WAND + ">, "
                + "buff <who> heal on|off, buff crown gold|<color>|off, buff sizeMax <" + Buffs.MIN_SIZE + "-" + Buffs.MAX_SIZE + ">, "
                + "buff sizeSpeed|sizeJump off|natural|strong|full, buff unit percent|multiplier";
        if (a.length >= 2 && "crown".equalsIgnoreCase(a[0])) {
            String c = Buffs.parseCrown(a[1]);
            if (c == null) {
                usage(ctx, usage, I18n.tr("cmd.note.buff_values"));
                return false;
            }
            Buffs.crown = c;
            return true;
        }
        if (a.length >= 2 && ("sizespeed".equalsIgnoreCase(a[0]) || "sizejump".equalsIgnoreCase(a[0]))) {
            String v = Buffs.parseSizeEffect(a[1]);
            boolean speed = "sizespeed".equalsIgnoreCase(a[0]);
            if (v == null) {
                usage(ctx, "/mobbattle buff " + (speed ? "sizeSpeed" : "sizeJump") + " off|natural|strong|full");
                return false;
            }
            if (speed) Buffs.sizeSpeed = v;
            else Buffs.sizeJump = v;
            Buffs.refreshMovementAll();
            return true;
        }
        if (a.length >= 2 && "unit".equalsIgnoreCase(a[0])) {
            Boolean mult = Buffs.parseUnit(a[1]);
            if (mult == null) {
                usage(ctx, "/mobbattle buff unit percent|multiplier");
                return false;
            }
            Buffs.multiplier = mult;
            return true;
        }
        if (a.length >= 2 && "sizemax".equalsIgnoreCase(a[0])) {
            int v = Buffs.parseValue(Buffs.Stat.SIZE, a[1]);
            if (v < Buffs.MIN_SIZE || v > Buffs.MAX_SIZE) {
                usage(ctx, usage, I18n.tr("cmd.note.buff_values"));
                return false;
            }
            Buffs.sizeMax = v;
            Message warn = Buffs.sizeWarningMessage(v);
            if (warn != null) reply(ctx, I18n.tr("cmd.warning").param("text", warn), ERR);
            return true;
        }
        Buffs.Source who = a.length >= 2 ? Buffs.Source.of(a[0]) : null;
        if (who == null) {
            usage(ctx, usage, I18n.tr("cmd.note.buff_values"));
            return false;
        }
        String what = a[1].toLowerCase(java.util.Locale.ROOT);
        if (what.equals("heal")) {
            Boolean on = a.length >= 3 ? parseOnOff(a[2]) : null;
            if (on == null) {
                usage(ctx, usage, I18n.tr("cmd.note.buff_values"));
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
        String raw = a.length >= 3 ? a[2] : null;
        if (what.equals("maxkills") && who == Buffs.Source.STREAK) {
            if (v < 1 || v > Buffs.MAX_STREAK) {
                usage(ctx, usage, I18n.tr("cmd.note.buff_values"));
                return false;
            }
            Buffs.streakMax = v;
            return true;
        }
        if (what.equals("maxstacks") && who == Buffs.Source.WAND) {
            if (v < 1 || v > Buffs.MAX_WAND) {
                usage(ctx, usage, I18n.tr("cmd.note.buff_values"));
                return false;
            }
            Buffs.wandMax = v;
            return true;
        }
        Buffs.Stat stat = Buffs.Stat.of(what);
        // The value in the buff unit: 50 / 50% (percent) or 2 / x2 (multiplier; x2 = +100%, defense x2 = 50%).
        if (!what.equals("all") && stat == null || Buffs.parseValue(stat != null ? stat : Buffs.Stat.HEALTH, raw) < 0) {
            usage(ctx, usage, I18n.tr("cmd.note.buff_values"));
            return false;
        }
        if (stat != null && Buffs.parseValue(stat, raw) > stat.max) {
            reply(ctx, I18n.tr("cmd.buff.toomuch").param("stat", stat.key).param("max", Buffs.both(stat, stat.max)), ERR);
            return false;
        }
        // all: each stat converted on its own (x2 = defense 50%) and capped at its own maximum (defense 90; the size
        // total stops at sizeMax).
        for (Buffs.Stat st : Buffs.Stat.values()) {
            if (stat == null || st == stat) Buffs.setPercent(who, st, Buffs.parseValue(st, raw));
        }
        return true;
    }

    private static Sub battleKind(String name, String desc) {
        return new Sub(name, desc, new Sub("on", I18n.id("cmd.desc.on")), new Sub("off", I18n.id("cmd.desc.off")));
    }

    /**
     * Chat suggestions of a KindFx option: the global values, one node per battle kind (its values and
     * "default") and "all" (its values). A value with the name of a kind (cameraStyle duel) becomes that
     * kind's node, which still lists the values.
     */
    private static Sub kindFxSub(String name, String desc, String... values) {
        java.util.Map<String, Sub> nodes = new java.util.LinkedHashMap<>();
        for (String v : values) {
            nodes.put(v, new Sub(v, I18n.id("cmd.desc.global_value")));
        }
        for (String kind : new String[] {"duel", "allvsone", "royale", "teams"}) {
            Sub[] kids = new Sub[values.length + 1];
            for (int i = 0; i < values.length; i++) {
                kids[i] = new Sub(values[i], I18n.id("cmd.desc.only." + kind));
            }
            kids[values.length] = new Sub("default", I18n.id("cmd.desc.back_to_the_global_value"));
            nodes.put(kind, new Sub(kind, I18n.id("cmd.desc.only." + kind), kids));
        }
        Sub[] everyKids = new Sub[values.length];
        for (int i = 0; i < values.length; i++) {
            everyKids[i] = new Sub(values[i], I18n.id("cmd.desc.every_kind_at_once"));
        }
        nodes.put("all", new Sub("all", I18n.id("cmd.desc.every_kind_at_once"), everyKids));
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
            usage(ctx, "/mobbattle " + word + " <" + String.join("|", keys) + "|all> on|off");
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
            case "readytime" -> KindFx.Opt.READY_TIME;
            case "camera" -> KindFx.Opt.CAMERA;
            case "music" -> KindFx.Opt.MUSIC;
            case "camerastyle" -> KindFx.Opt.CAMERA_STYLE;
            case "cameratime" -> KindFx.Opt.CAMERA_TIME;
            case "camerashots" -> KindFx.Opt.CAMERA_SHOTS;
            case "endcamera" -> KindFx.Opt.END_CAMERA;
            case "endcameratime" -> KindFx.Opt.END_CAMERA_TIME;
            case "autowatch" -> KindFx.Opt.AUTO_WATCH;
            case "camerabars" -> KindFx.Opt.CAMERA_BARS;
            case "camerablur" -> KindFx.Opt.CAMERA_BLUR;
            case "autofilm" -> KindFx.Opt.AUTO_FILM;
            case "vsscreen" -> KindFx.Opt.VS_SCREEN;
            case "runes" -> KindFx.Opt.RUNES;
            case "tiebreak" -> KindFx.Opt.TIE_BREAK;
            case "killtiebreak" -> KindFx.Opt.KILL_TIE_BREAK;
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
        String usage = "/mobbattle " + opt.word + " [duel|allVsOne|royale|teams|all] " + KindFx.usage(opt);
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
                    usage(ctx, usage, I18n.tr("cmd.note.kind_scope"));
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
            usage(ctx, usage, I18n.tr("cmd.note.kind_scope"));
            return false;
        }
        String v = KindFx.parse(opt, raw);
        if (v == null) {
            usage(ctx, usage, I18n.tr("cmd.note.kind_scope"));
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
        addSubCommand(new Sub("settings", I18n.id("cmd.desc.show_the_current_settings")));
        addSubCommand(new Sub("help", I18n.id("cmd.desc.explain_the_wands_and_comman"),
                words(I18n.id("cmd.desc.help_topic"), "wands", "titles", "chaos", "battles", "teams", "summon", "morph", "presets", "config", "server")));
        addSubCommand(new Sub("config", I18n.id("cmd.desc.open_the_read_only_public_co")));
        addSubCommand(new Sub("panel", I18n.id("cmd.desc.alias_for_config_open_the_re")));
        addSubCommand(new Sub("permissions", I18n.id("cmd.desc.set_mob_battle_access_policy")));
        addSubCommand(new Sub("reload", I18n.id("cmd.desc.reload_fx_properties_from_th")));
        addSubCommand(new Sub("event", I18n.id("cmd.desc.admin_event_commands_for_ser")));
        Sub give = new Sub("give", I18n.id("cmd.desc.receive_one_of_each_of_the_t"));
        addSubCommand(give);
        addSubCommand(kindFxSub("starttitle", I18n.id("cmd.desc.fight_start_title_on_off_per"), "on", "off"));
        addSubCommand(kindFxSub("wintitle", I18n.id("cmd.desc.winner_title_on_off_per_batt"), "on", "off"));
        addSubCommand(kindFxSub("music", I18n.id("cmd.desc.battle_music_on_off_or_a_mus"),
                "on", "off", "Track_Z1D_Goblin_Boss_Battle", "Track_Void_Escalation", "Track_Void_Tension"));
        addSubCommand(kindFxSub("camera", I18n.id("cmd.desc.intro_camera_during_the_coun"),
                "on", "off"));
        addSubCommand(new Sub("titlescope", I18n.id("cmd.desc.who_sees_fight_titles_owner"),
                words(I18n.id("cmd.desc.title_scope"), "owner", "nearby", "world")));
        addSubCommand(new Sub("camerascope", I18n.id("cmd.desc.who_sees_the_fight_cameras_o"),
                words(I18n.id("cmd.desc.camera_scope"), "owner", "nearby", "world")));
        addSubCommand(new Sub("camerahud", I18n.id("cmd.desc.camera_hud"),
                words(I18n.id("cmd.desc.camera_hud"), FxSettings.CAMERA_HUDS)));
        addSubCommand(new Sub("cameraface", I18n.id("cmd.desc.camera_face"),
                words(I18n.id("cmd.desc.camera_face"), "on", "off")));
        addSubCommand(new Sub("titlestyle", I18n.id("cmd.desc.title_style"), words(I18n.id("cmd.desc.title_style"), "default", "major", "goblinbreach", "voideviction", "random")));
        addSubCommand(kindFxSub("fightlaser", I18n.id("cmd.desc.laser_line_between_fighters"), "on", "off"));
        addSubCommand(new Sub("fightlasercolor", I18n.id("cmd.desc.color_of_the_fighter_laser_a"), colorWords("random")));
        addSubCommand(new Sub("arearadius", I18n.id("cmd.desc.chaos_wand_area_radius_in_bl")));
        addSubCommand(new Sub("playerfights", I18n.id("cmd.desc.players_can_fight_in_battles"),
                battleKind("duel", I18n.id("cmd.desc.battle_wand_on_players_or_cr")),
                battleKind("allvsone", I18n.id("cmd.desc.chaos_wand_left_click_on_a_p")),
                battleKind("royale", I18n.id("cmd.desc.chaos_wand_right_click_takes")),
                battleKind("teams", I18n.id("cmd.desc.team_wand_on_players_or_team")),
                battleKind("all", I18n.id("cmd.desc.every_kind_at_once"))));
        addSubCommand(new Sub("battlepvp", I18n.id("cmd.desc.enemy_players_in_a_battle_hu"),
                new Sub("on", I18n.id("cmd.desc.pvp_inside_battles")), new Sub("off", I18n.id("cmd.desc.the_world_s_pvp_rule_default"))));
        addSubCommand(new Sub("chaos", I18n.id("cmd.desc.chaos_battles_with_you_in_th"),
                new Sub("me", I18n.id("cmd.desc.every_mob_within_arearadius"))));
        addSubCommand(new Sub("friendlyfire", I18n.id("cmd.desc.allies_hurt_each_other_allvs"),
                battleKind("allvsone", I18n.id("cmd.desc.chaos_wand_everyone_vs_one_a")),
                battleKind("teams", I18n.id("cmd.desc.team_wand_battles_teammates")),
                battleKind("all", I18n.id("cmd.desc.both_kinds_at_once"))));
        addSubCommand(new Sub("selfdefense", I18n.id("cmd.desc.a_battle_mob_hit_by_another"),
                battleKind("duel", I18n.id("cmd.desc.battle_wand_duels")),
                battleKind("allvsone", I18n.id("cmd.desc.chaos_wand_everyone_vs_one")),
                battleKind("royale", I18n.id("cmd.desc.chaos_wand_battle_royale")),
                battleKind("teams", I18n.id("cmd.desc.team_wand_battles")),
                battleKind("all", I18n.id("cmd.desc.every_kind_at_once"))));
        addSubCommand(new Sub("battleisolation", I18n.id("cmd.desc.outside_mobs_can_t_hurt_batt"),
                battleKind("duel", I18n.id("cmd.desc.battle_wand_duels")),
                battleKind("allvsone", I18n.id("cmd.desc.chaos_wand_everyone_vs_one")),
                battleKind("royale", I18n.id("cmd.desc.chaos_wand_battle_royale")),
                battleKind("teams", I18n.id("cmd.desc.team_wand_battles")),
                battleKind("all", I18n.id("cmd.desc.every_kind_at_once"))));
        addSubCommand(new Sub("arena", I18n.id("cmd.desc.arena_per_battle_kind_duel_a"),
                arenaKind("duel"), arenaKind("allvsone"), arenaKind("royale"), arenaKind("teams"), arenaKind("all")));
        addSubCommand(new Sub("arenasize", I18n.id("cmd.desc.arena_radius_of_duels_and_te"), words(I18n.id("cmd.desc.arena_size"), "auto")));
        addSubCommand(new Sub("timer", I18n.id("cmd.desc.fight_time_limit_with_a_time"),
                timerKind("duel"), timerKind("allvsone"), timerKind("royale"), timerKind("teams"), timerKind("all"))
                .withDuration());
        addSubCommand(new Sub("arenashrink", I18n.id("cmd.desc.closing_arena_battle_royale"),
                battleKind("duel", I18n.id("cmd.desc.battle_wand_duels")),
                battleKind("allvsone", I18n.id("cmd.desc.chaos_wand_everyone_vs_one")),
                battleKind("royale", I18n.id("cmd.desc.chaos_wand_battle_royale_def")),
                battleKind("teams", I18n.id("cmd.desc.team_wand_battles")),
                battleKind("all", I18n.id("cmd.desc.every_kind_at_once"))));
        addSubCommand(new Sub("crown", I18n.id("cmd.desc.everyone_vs_one_crown_with_r"),
                crownWords()));
        addSubCommand(new Sub("buffcolor", I18n.id("cmd.desc.buff_look_buff_wand_mark_and"),
                colorWords()));
        addSubCommand(new Sub("bufflook", I18n.id("cmd.desc.look_of_a_buffed_mob_while_t"),
                words(I18n.id("cmd.desc.look"), "shimmer", "runes", "off")));
        addSubCommand(new Sub("bufflight", I18n.id("cmd.desc.buff_wand_light_from_the_sky"),
                colorWords("buff", "off")));
        addSubCommand(new Sub("heallight", I18n.id("cmd.desc.heal_wand_light_from_the_sky"),
                colorWords("off")));
        addSubCommand(new Sub("cleansecolor", I18n.id("cmd.desc.cleanse_wand_look_light_from"),
                colorWords()));
        addSubCommand(new Sub("mobfx", I18n.id("cmd.desc.look_on_battle_mobs_per_role"),
                new Sub("duel", I18n.id("cmd.desc.both_duel_fighters"), colorWords("default", "off")),
                new Sub("allvsone", I18n.id("cmd.desc.the_lone_target"), colorWords("default", "off")),
                new Sub("attackers", I18n.id("cmd.desc.everyone_attacking_the_lone"), colorWords("default", "off")),
                new Sub("royale", I18n.id("cmd.desc.battle_royale_fighters"), colorWords("default", "off")),
                new Sub("all", I18n.id("cmd.desc.every_role_at_once"), colorWords("default", "off"))));
        addSubCommand(new Sub("wave", I18n.id("cmd.desc.ground_shockwave_color_per_a"),
                actionSubs("off")));
        addSubCommand(new Sub("bordercolor", I18n.id("cmd.desc.border_glow_color_per_action"),
                actionSubs("off", "area")));
        addSubCommand(new Sub("scoreboard", I18n.id("cmd.desc.scoreboard_hud_per_battle_ki"),
                battleKind("duel", I18n.id("cmd.desc.duel_each_fighter_s_health")),
                battleKind("allvsone", I18n.id("cmd.desc.everyone_vs_one_target_healt")),
                battleKind("royale", I18n.id("cmd.desc.battle_royale_fighters_left")),
                battleKind("teams", I18n.id("cmd.desc.team_battles_alive_and_kills")),
                battleKind("all", I18n.id("cmd.desc.every_kind_at_once"))));
        addSubCommand(new Sub("arenashrinkto", I18n.id("cmd.desc.final_radius_of_a_closing_ar")));
        addSubCommand(new Sub("arenashrinktime", I18n.id("cmd.desc.how_long_a_closing_arena_tak"))
                .withDuration());
        addSubCommand(new Sub("unstick", I18n.id("cmd.desc.battle_mobs_stuck_on_another"),
                new Sub("on", I18n.id("cmd.desc.bring_them_together_after_10")), new Sub("off", I18n.id("cmd.desc.leave_them"))));
        addSubCommand(new Sub("standofftime", I18n.id("cmd.desc.seconds_a_battle_mob_may_sta")));
        addSubCommand(new Sub("passivewinignore", I18n.id("cmd.desc.roles_that_never_let_passive")));
        addSubCommand(new Sub("idleend", I18n.id("cmd.desc.seconds_with_no_fighter_hurt")));
        addSubCommand(new Sub("battlemaxtime", I18n.id("cmd.desc.battlemaxtime"),
                new Sub("off", I18n.id("cmd.desc.battlemaxtime.off")), new Sub("15", I18n.id("cmd.desc.battlemaxtime.15")),
                new Sub("60", I18n.id("cmd.desc.battlemaxtime.60"))));
        addSubCommand(new Sub("passivewin", I18n.id("cmd.desc.seconds_without_a_hit_before")));
        addSubCommand(new Sub("battlebelow", I18n.id("cmd.desc.battle_below"), words(I18n.id("cmd.desc.battle_below"), "20", "50", "100", "off")));
        addSubCommand(new Sub("battleabove", I18n.id("cmd.desc.battle_above"), words(I18n.id("cmd.desc.battle_above"), "20", "40", "80", "off")));
        addSubCommand(new Sub("arenavertical", I18n.id("cmd.desc.arena_vertical"), words(I18n.id("cmd.desc.arena_vertical"), "10", "20", "40", "off")));
        addSubCommand(new Sub("duelcard", I18n.id("cmd.desc.read_only_card_with_both_fig"),
                new Sub("on", I18n.id("cmd.desc.show_the_card_from_the_count")), new Sub("off", I18n.id("cmd.desc.no_card"))));
        addSubCommand(new Sub("focuscard", I18n.id("cmd.desc.read_only_all_vs_one_card_th"),
                new Sub("on", I18n.id("cmd.desc.show_the_card_while_an_all_v")), new Sub("off", I18n.id("cmd.desc.no_card"))));
        addSubCommand(kindFxSub("tiebreak", I18n.id("cmd.desc.who_wins_when_the_judgeby_sc"),
                KindFx.TIE_BREAKS));
        addSubCommand(kindFxSub("killtiebreak", I18n.id("cmd.desc.kill_table_order_for_fighter"),
                KindFx.KILL_TIE_BREAKS));
        addSubCommand(new Sub("snap", I18n.id("cmd.desc.half_of_the_mobs_within_snap")));
        addSubCommand(new Sub("snappercent", I18n.id("cmd.desc.snap_share_of_the_fighters_t")));
        addSubCommand(new Sub("snaptime", I18n.id("cmd.desc.snap_seconds_over_which_they")));
        addSubCommand(new Sub("killhitdelay", I18n.id("cmd.desc.kill_wand_ms_from_the_click")));
        addSubCommand(new Sub("snaphitdelay", I18n.id("cmd.desc.snap_wand_ms_from_the_click")));
        addSubCommand(new Sub("snapradius", I18n.id("cmd.desc.snap_without_battles_reach_i")));
        addSubCommand(new Sub("snapmode", I18n.id("cmd.desc.snap_global_half_of_everyone"),
                new Sub("global", I18n.id("cmd.desc.half_of_everyone_at_random")), new Sub("team", I18n.id("cmd.desc.half_of_each_side"))));
        addSubCommand(new Sub("timerstyle", I18n.id("cmd.desc.timer_box_custom_our_own_def"),
                words(I18n.id("cmd.desc.timer_style"), "custom", "portal")));
        addSubCommand(new Sub("timeup", I18n.id("cmd.desc.when_the_timer_ends_kind_all"),
                timeUpKind("duel"), timeUpKind("allvsone"), timeUpKind("royale"), timeUpKind("teams"), timeUpKind("all")));
        addSubCommand(new Sub("judgeby", I18n.id("cmd.desc.what_timeup_health_judge_sco"),
                judgeByKind("duel"), judgeByKind("allvsone"), judgeByKind("royale"), judgeByKind("teams"), judgeByKind("all")));
        java.util.List<String> areaColors = new java.util.ArrayList<>();
        Teams.PALETTE.keySet().forEach(n -> areaColors.add(n.toLowerCase(java.util.Locale.ROOT)));
        areaColors.add("random");
        addSubCommand(new Sub("areaonlywand", I18n.id("cmd.desc.area_only_for_you_only_while"),
                new Sub("on", I18n.id("cmd.desc.only_you_only_while_holding")),
                new Sub("off", I18n.id("cmd.desc.everyone_sees_it_for_6_s_def"))));
        addSubCommand(new Sub("areacolor", I18n.id("cmd.desc.area_color_from_the_palette"),
                words(I18n.id("cmd.desc.area_color"), areaColors.toArray(new String[0]))));
        addSubCommand(kindFxSub("areastyle", I18n.id("cmd.desc.what_areas_and_arenas_draw_b"),
                "border", "disc", "discborder", "wall", "off"));
        addSubCommand(new Sub("areaheight", I18n.id("cmd.desc.height_of_the_zone_wall_in_b")));
        addSubCommand(new Sub("skiptimer", I18n.id("cmd.desc.every_running_battle_timer_j")));
        addSubCommand(new Sub("buff", I18n.id("cmd.desc.battle_buffs_buff_lone_strea"),
                buffWho("lone", I18n.id("cmd.desc.everyone_vs_one_the_lone_tar")),
                buffWho("streak", I18n.id("cmd.desc.per_kill_in_a_battle_times_i")),
                buffWho("champion", I18n.id("cmd.desc.the_winners_of_a_battle_unti")),
                buffWho("wand", I18n.id("cmd.desc.buff_wand_per_click_stacks_u")),
                new Sub("crown", I18n.id("cmd.desc.crown_on_streak_champion_and"), crownWords()),
                new Sub("sizemax", I18n.id("cmd.desc.most_extra_size_on_one_mob_a"), words(I18n.id("cmd.desc.percent"), "200", "400", "900")),
                new Sub("sizespeed", I18n.id("cmd.desc.how_much_a_size_buff_speeds"),
                        words(I18n.id("cmd.desc.speed_from_size"), "off", "natural", "strong", "full")),
                new Sub("sizejump", I18n.id("cmd.desc.how_much_a_size_buff_raises"),
                        words(I18n.id("cmd.desc.jump_from_size"), "off", "natural", "strong", "full")),
                new Sub("unit", I18n.id("cmd.desc.how_buff_values_are_shown_an"),
                        words(I18n.id("cmd.desc.unit"), "percent", "multiplier"))));
        addSubCommand(new Sub("skipcamera", I18n.id("cmd.desc.end_your_intro_or_end_camera")));
        addSubCommand(new Sub("watch",I18n.id("cmd.desc.spectate_a_running_battle_ri"),
                new Sub("exit", I18n.id("cmd.desc.stop_watching_back_to_your_g"))));
        addSubCommand(new Sub("film", I18n.id("cmd.desc.film"),
                new Sub("exit", I18n.id("cmd.desc.film_exit"))));
        addSubCommand(new Sub("stop", I18n.id("cmd.desc.end_every_battle_countdown_t")));
        addSubCommand(new Sub("reset", I18n.id("cmd.desc.settings_of_one_group_back_t"),
                new Sub("effects", I18n.id("cmd.desc.waves_border_glow_mob_looks")),
                new Sub("cameras", I18n.id("cmd.desc.intro_and_end_cameras")),
                new Sub("titles", I18n.id("cmd.desc.countdown_titles_and_music")),
                new Sub("arenas", I18n.id("cmd.desc.arenas_timers_time_up_closin")),
                new Sub("rules", I18n.id("cmd.desc.isolation_self_defense_frien")),
                new Sub("wands", I18n.id("cmd.desc.kill_snap_morph_and_effect_w")),
                new Sub("all", I18n.id("cmd.desc.everything_like_mobbattle_pr"))));
        addSubCommand(new Sub("resetall", I18n.id("cmd.desc.stop_every_battle_empty_ever")));
        addSubCommand(applySub());
        addSubCommand(new Sub("measure", I18n.id("cmd.desc.admin_measure_the_speed_and"),
                words(I18n.id("cmd.desc.who_or_seconds"), "look", "last", "10", "20", "stop")));
        addSubCommand(new Sub("test", I18n.id("cmd.desc.admin_test_tools_by_coordina"),
                new Sub("spawn", I18n.id("cmd.desc.spawn_role_here_x_y_z_count"), words(I18n.id("cmd.desc.where"), "here")),
                new Sub("list", I18n.id("cmd.desc.the_test_mobs_alive_with_ids")),
                new Sub("clear", I18n.id("cmd.desc.remove_every_test_mob")),
                new Sub("walk", I18n.id("cmd.desc.walk_id_last_x_z_a_test_mob"), words(I18n.id("cmd.desc.who"), "last")),
                new Sub("chase", I18n.id("cmd.desc.chase_id_last_here_x_y_z_it"), words(I18n.id("cmd.desc.who"), "last")),
                new Sub("duel", I18n.id("cmd.desc.duel_rolea_roleb_distance_he")),
                new Sub("perf", I18n.id("cmd.desc.perf_role_count_here_x_y_z_w")),
                new Sub("royale", I18n.id("cmd.desc.royale_role_count_here_x_y_z")),
                new Sub("wave", I18n.id("cmd.desc.wave_heal_cleanse_effect_mor"),
                        words(I18n.id("cmd.desc.wave"), "heal", "cleanse", "effect", "morph", "shuffle", "kill")),
                new Sub("stats", I18n.id("cmd.desc.stats_id_last_health_size_bu"), words(I18n.id("cmd.desc.who"), "last")),
                new Sub("verify", I18n.id("cmd.desc.verify_here_x_y_z_mod_type_t")),
                new Sub("calls", I18n.id("cmd.desc.calls_here_x_y_z"), words(I18n.id("cmd.desc.where"), "here")),
                new Sub("ai", I18n.id("cmd.desc.ai_here_x_y_z_mod_type_text"), words(I18n.id("cmd.desc.where"), "here")),
                new Sub("jump", I18n.id("cmd.desc.jump_role_here_x_y_z_sizes")),
                new Sub("wipe", I18n.id("cmd.desc.wipe_here_x_y_z_radius"), words(I18n.id("cmd.desc.where"), "here")),
                new Sub("speed", I18n.id("cmd.desc.speed_role_here_x_y_z_sizes")),
                new Sub("hit", I18n.id("cmd.desc.hit_player_id_last_damage")),
                new Sub("wand", I18n.id("cmd.desc.wand_owner_action_targets"), words(I18n.id("cmd.desc.wand_owner_action_targets"), "duel", "focus", "brawl", "kill", "snap")),
                new Sub("tick", I18n.id("cmd.desc.tick_world")),
                new Sub("roles", I18n.id("cmd.desc.roles_mod_name_mod_hytale_ty"),
                        words(I18n.id("cmd.desc.filter"), "mod:hytale", "type:fighters", "type:defend", "type:noattack", "type:avoid")),
                new Sub("expect", I18n.id("cmd.desc.expect_id_last_field_op_valu"), words(I18n.id("cmd.desc.who"), "last")),
                new Sub("camera", I18n.id("cmd.desc.camera_style_player_seconds"),
                        words(I18n.id("cmd.desc.style"), "orbit", "duel", "showcase", "teams", "focus", "drone", "spotlight")),
                new Sub("snapshot", I18n.id("cmd.desc.snapshot_name_save_fx_proper")),
                new Sub("restore", I18n.id("cmd.desc.restore_name_put_a_snapshot")),
                new Sub("run", I18n.id("cmd.desc.run_script_runs_data_dir_tes")),
                new Sub("stop", I18n.id("cmd.desc.stop_the_running_script"))));
        addSubCommand(new Sub("army", I18n.id("cmd.desc.army_wand_armies"),
                new Sub("clearall", I18n.id("cmd.desc.disband_every_player_s_army"))));
        addSubCommand(kindFxSub("countdown", I18n.id("cmd.desc.seconds_of_3_2_1_titles_befo"),
                "0", "3", "5", "10"));
        addSubCommand(kindFxSub("readytime", I18n.id("cmd.desc.ready_time"), "off", "5", "8", "15"));
        addSubCommand(kindFxSub("camerastyle", I18n.id("cmd.desc.intro_camera_auto_orbit_area"),
                "auto", "orbit", "duel", "showcase", "teams", "focus", "drone", "spotlight"));
        addSubCommand(kindFxSub("cameratime", I18n.id("cmd.desc.intro_camera_seconds_0_as_lo"),
                "0", "3", "5", "10"));
        addSubCommand(kindFxSub("camerashots", I18n.id("cmd.desc.showcase_camera_how_many_fig"), "3", "4", "6", "12", "25"));
        addSubCommand(kindFxSub("endcamera", I18n.id("cmd.desc.camera_at_the_end_off_winner"),
                "off", "winner", "lasthit"));
        addSubCommand(kindFxSub("endcameratime", I18n.id("cmd.desc.end_camera_seconds"), "3", "5", "8"));
        addSubCommand(kindFxSub("autowatch", I18n.id("cmd.desc.start_watching_spectator_cam"),
                "on", "off"));
        addSubCommand(kindFxSub("camerabars", I18n.id("cmd.desc.camera_cinema_bars"),
                "off", "slim", "classic", "wide"));
        addSubCommand(kindFxSub("camerablur", I18n.id("cmd.desc.camera_background_blur"),
                "off", "soft", "strong"));
        addSubCommand(kindFxSub("autofilm", I18n.id("cmd.desc.film_auto"), "on", "off"));
        addSubCommand(kindFxSub("vsscreen", I18n.id("cmd.desc.vs_screen"), "on", "off"));
        addSubCommand(new Sub("filmplayershots", I18n.id("cmd.desc.film_player_shots"),
                words(I18n.id("cmd.desc.film_player_shots"), "0", "15", "30", "50", "100")));
        addSubCommand(new Sub("vsteamreps", I18n.id("cmd.desc.vs_team_reps"),
                words(I18n.id("cmd.desc.vs_team_reps"), "1", "2")));
        addSubCommand(new Sub("filmwideshots", I18n.id("cmd.desc.film_wide_shots"),
                words(I18n.id("cmd.desc.film_wide_shots"), "0", "15", "30", "50", "100")));
        addSubCommand(new Sub("filmshot", I18n.id("cmd.desc.film_shot"),
                words(I18n.id("cmd.desc.film_shot"), FilmCamera.SHOT_WORDS)));
        addSubCommand(new Sub("filmshottime", I18n.id("cmd.desc.film_shot_time"),
                words(I18n.id("cmd.desc.film_shot_time"), "5", "8", "12", "20")));
        addSubCommand(new Sub("filmshotvariation", I18n.id("cmd.desc.film_shot_variation"),
                words(I18n.id("cmd.desc.film_shot_variation"), "0", "25", "50")));
        addSubCommand(new Sub("filmpace", I18n.id("cmd.desc.film_pace"),
                words(I18n.id("cmd.desc.film_pace"), "50", "100", "150", "200")));
        addSubCommand(new Sub("filmtransition", I18n.id("cmd.desc.film_transition"),
                words(I18n.id("cmd.desc.film_transition"), "cut", "glide")));
        addSubCommand(new Sub("watchtransition", I18n.id("cmd.desc.watch_transition"),
                words(I18n.id("cmd.desc.watch_transition"), "glide", "cut")));
        addSubCommand(new Sub("endslowmo", I18n.id("cmd.desc.end_slow_mo"),
                words(I18n.id("cmd.desc.end_slow_mo"), "off", "25", "40", "60")));
        addSubCommand(new Sub("endslowmotime", I18n.id("cmd.desc.end_slow_mo_time"),
                words(I18n.id("cmd.desc.end_slow_mo_time"), "2", "3", "5")));
        addSubCommand(new Sub("watchautoswitch", I18n.id("cmd.desc.watch_auto_switch"),
                words(I18n.id("cmd.desc.watch_auto_switch"), "off", "5", "10", "20", "30")));
        addSubCommand(new Sub("filmstyle", I18n.id("cmd.desc.film_style"),
                words(I18n.id("cmd.desc.film_style"), FilmStyles.NAMES)));
        addSubCommand(new Sub("filmshots", I18n.id("cmd.desc.film_shots"),
                words(I18n.id("cmd.desc.film_shots"), "default", "all", "battle", "wide,arc,landscape", "playerback,playerclose")));
        addSubCommand(kindFxSub("runes", I18n.id("cmd.desc.summoning_runes_and_fighter"), "on", "off"));
        addSubCommand(new Sub("preset", I18n.id("cmd.desc.presets_built_in_or_your_own"),
                new Sub("list", I18n.id("cmd.desc.built_in_and_saved_presets")),
                new Sub("default", I18n.id("cmd.desc.every_setting_back_to_defaul")),
                new Sub("fight", I18n.id("cmd.desc.just_the_fights_no_countdown")),
                new Sub("effects", I18n.id("cmd.desc.countdown_3_2_min_timer_most")),
                new Sub("debug", I18n.id("cmd.desc.everything_visible_area_wall")),
                new Sub("cinematic", I18n.id("cmd.desc.countdown_full_camera_work_e")),
                new Sub("save", I18n.id("cmd.desc.save_your_current_settings_s")),
                new Sub("load", I18n.id("cmd.desc.load_a_saved_preset_load_nam")),
                new Sub("delete", I18n.id("cmd.desc.delete_a_saved_preset_delete"))));
        addSubCommand(new Sub("teambytype", I18n.id("cmd.desc.team_wand_clicking_a_mob_bri"),
                words(I18n.id("cmd.desc.team_by_type"), "on", "off")));
        addSubCommand(new Sub("snapplayers", I18n.id("cmd.desc.snap_also_turns_players_in_r"),
                words(I18n.id("cmd.desc.snap_players"), "on", "off")));
        addSubCommand(new Sub("morphplayers", I18n.id("cmd.desc.morph_wand_works_on_players"),
                words(I18n.id("cmd.desc.morph_players"), "on", "off")));
        addSubCommand(new Sub("morphburst", I18n.id("cmd.desc.morph_wand_hold_left_click_e"),
                words(I18n.id("cmd.desc.morph_burst"), "on", "off")));
        addSubCommand(new Sub("morphshuffle", I18n.id("cmd.desc.morph_wand_hold_right_click"),
                words(I18n.id("cmd.desc.morph_shuffle"), "on", "off")));
        addSubCommand(new Sub("healarea", I18n.id("cmd.desc.heal_wand_hold_left_click_a"),
                words(I18n.id("cmd.desc.heal_area"), "on", "off")));
        addSubCommand(new Sub("healradius", I18n.id("cmd.desc.heal_wand_reach_of_the_held")));
        addSubCommand(new Sub("killarea", I18n.id("cmd.desc.kill_area_desc"),
                words(I18n.id("cmd.desc.kill_area"), "on", "off")));
        addSubCommand(new Sub("killradius", I18n.id("cmd.desc.kill_radius")));
        addSubCommand(new Sub("killareaplayers", I18n.id("cmd.desc.kill_area_players"),
                words(I18n.id("cmd.desc.kill_area_players"), "on", "off")));
        addSubCommand(new Sub("effectarea", I18n.id("cmd.desc.effect_wand_hold_left_click"),
                words(I18n.id("cmd.desc.effect_area"), "on", "off")));
        addSubCommand(new Sub("effectradius", I18n.id("cmd.desc.effect_wand_reach_of_the_hel")));
        addSubCommand(new Sub("cleansearea", I18n.id("cmd.desc.cleanse_wand_hold_left_click"),
                words(I18n.id("cmd.desc.cleanse_area"), "on", "off")));
        addSubCommand(new Sub("cleanseradius", I18n.id("cmd.desc.cleanse_wand_reach_of_the_he")));
        addSubCommand(new Sub("healareaself", I18n.id("cmd.desc.heal_wand_the_held_click_hea"), words(I18n.id("cmd.desc.you_too"), "on", "off")));
        addSubCommand(new Sub("cleanseareaself", I18n.id("cmd.desc.cleanse_wand_the_held_click"), words(I18n.id("cmd.desc.you_too"), "on", "off")));
        addSubCommand(new Sub("effectareaself", I18n.id("cmd.desc.effect_wand_the_held_click_w"), words(I18n.id("cmd.desc.you_too"), "on", "off")));
        addSubCommand(new Sub("morphburstself", I18n.id("cmd.desc.morph_wand_the_held_click_wa"), words(I18n.id("cmd.desc.you_too"), "on", "off")));
        addSubCommand(new Sub("wavetravel", I18n.id("cmd.desc.wand_waves_each_mob_is_hit_w"),
                words(I18n.id("cmd.desc.wave_travel"), "on", "off")));
        addSubCommand(new Sub("wavegroundonly", I18n.id("cmd.desc.wand_waves_only_hit_mobs_nea"),
                words(I18n.id("cmd.desc.wave_ground_only"), "on", "off")));
        addSubCommand(new Sub("battlewavetravel", I18n.id("cmd.desc.snap_royale_and_all_vs_one_w"),
                words(I18n.id("cmd.desc.battle_wave_travel"), "on", "off")));
        addSubCommand(new Sub("battlewavegroundonly", I18n.id("cmd.desc.snap_royale_and_all_vs_one_w.2"),
                words(I18n.id("cmd.desc.battle_wave_ground_only"), "on", "off")));
        addSubCommand(new Sub("morphburstradius", I18n.id("cmd.desc.morph_wand_reach_of_the_held")));
        addSubCommand(new Sub("debug", I18n.id("cmd.desc.read_nearby_npc_combat_state")));
        addSubCommand(new Sub("duelmode", I18n.id("cmd.desc.battle_wand_replace_duels_or"),
                words(I18n.id("cmd.desc.duel_mode"), "exclusive", "accumulate")));
        addSubCommand(new Sub("wandfx", I18n.id("cmd.desc.wand_effects_on_the_clicked"),
                new Sub("on", I18n.id("cmd.desc.show_them_default")),
                new Sub("off", I18n.id("cmd.desc.no_effects_on_the_mob"))));
        addSubCommand(new Sub("debuglog", I18n.id("cmd.desc.battle_decisions_config_aren"),
                new Sub("on", I18n.id("cmd.desc.log_them_prefixed_debug")),
                new Sub("off", I18n.id("cmd.desc.normal_log_default"))));
        addSubCommand(new Sub("servervs", I18n.id("cmd.desc.server_vs"), words(I18n.id("cmd.desc.server_vs"), "on", "off")));
        addSubCommand(new Sub("serverholograms", I18n.id("cmd.desc.server_holograms"), words(I18n.id("cmd.desc.server_holograms"), "on", "off")));
        addSubCommand(new Sub("servericons", I18n.id("cmd.desc.server_icons"), words(I18n.id("cmd.desc.server_icons"), "on", "off")));
        addSubCommand(new Sub("debugrecord", I18n.id("cmd.desc.keep_the_last_5_min_of_log_a"),
                new Sub("on", I18n.id("cmd.desc.record_saved_stays_on")),
                new Sub("off", I18n.id("cmd.desc.off_nothing_kept_default"))));
        addSubCommand(new Sub("debugdump", I18n.id("cmd.desc.write_what_the_debug_recorde")));
        addSubCommand(new Sub("pictures", I18n.id("cmd.desc.pictures"),
                new Sub("status", I18n.id("cmd.desc.pictures.status")),
                new Sub("rebuild", I18n.id("cmd.desc.pictures.rebuild"), new Sub("force", I18n.id("cmd.desc.pictures.force")))));
        addSubCommand(new Sub("debugtrack", I18n.id("cmd.desc.debug_wand_seconds_a_left_cl")));
        addSubCommand(new Sub("killdelay", I18n.id("cmd.desc.kill_wand_seconds_of_soul_ri")));
        addSubCommand(new Sub("lookglow", I18n.id("cmd.desc.light_around_mobs_with_a_jad")));
        addSubCommand(new Sub("deathglow", I18n.id("cmd.desc.kill_wand_disintegration_fla")));
        addSubCommand(new Sub("killplayers", I18n.id("cmd.desc.kill_wand_on_players_they_di"),
                new Sub("on", I18n.id("cmd.desc.the_kill_wand_works_on_playe")),
                new Sub("off", I18n.id("cmd.desc.players_are_not_affected"))));
        addSubCommand(new Sub("killloot", I18n.id("cmd.desc.kill_wand_and_snap_the_kille"),
                new Sub("on", I18n.id("cmd.desc.drop_its_normal_loot")),
                new Sub("off", I18n.id("cmd.desc.no_drops_default"))));
        addSubCommand(new Sub("summonradius", I18n.id("cmd.desc.summon_area_radius_in_blocks"),
                words(I18n.id("cmd.desc.radius"), "auto")));
        addSubCommand(new Sub("summondespawn", I18n.id("cmd.desc.summons_leave_on_their_own_n"),
                words(I18n.id("cmd.desc.leave"), "never", "battleEnd", "timer")));
        addSubCommand(new Sub("summonlifetime", I18n.id("cmd.desc.seconds_a_summon_lives_with")));
        addSubCommand(new Sub("summonexit", I18n.id("cmd.desc.how_summons_leave_disintegra"),
                words(I18n.id("cmd.desc.exit"), "disintegrate", "vanish", "shatter")));
        addSubCommand(new Sub("summoncalm", I18n.id("cmd.desc.summons_wait_calm_until_a_ba"),
                words(I18n.id("cmd.desc.calm"), "off", "summons", "all")));
        addSubCommand(new Sub("summonarmy", I18n.id("cmd.desc.summoned_mobs_join_your_army"),
                new Sub("on", I18n.id("cmd.desc.summons_join_your_army")),
                new Sub("off", I18n.id("cmd.desc.summons_stay_wild_default"))));
        addSubCommand(new Sub("summonjade", I18n.id("cmd.desc.summons_come_out_wearing_the"),
                new Sub("on", I18n.id("cmd.desc.summons_in_jade")),
                new Sub("off", I18n.id("cmd.desc.normal_summons_default"))));
        addSubCommand(new Sub("armytint", I18n.id("cmd.desc.army_mobs_wear_a_tint_while"),
                new Sub("on", I18n.id("cmd.desc.tinted_army")),
                new Sub("off", I18n.id("cmd.desc.no_tint_default"))));
        addSubCommand(new Sub("armytintcolor", I18n.id("cmd.desc.color_of_the_army_tint_a_pal")));
        addSubCommand(new Sub("equipweapondamage", I18n.id("cmd.desc.equip_weapon_damage"),
                new Sub("on", I18n.id("cmd.desc.equip_weapon_damage_on")),
                new Sub("off", I18n.id("cmd.desc.equip_weapon_damage_off"))));
        addSubCommand(new Sub("armyfriendlyfire", I18n.id("cmd.desc.army_friendly_fire"),
                new Sub("on", I18n.id("cmd.desc.army_friendly_fire_on")),
                new Sub("off", I18n.id("cmd.desc.army_friendly_fire_off"))));
        addSubCommand(new Sub("summonjadecolor", I18n.id("cmd.desc.color_of_the_summons_jade_a")));
        addSubCommand(new Sub("maxmobs", I18n.id("cmd.desc.max_mobs_per_chaos_wand_clic")));
        addSubCommand(new Sub("wandeffect", I18n.id("cmd.desc.effect_wand_effect_any_effec"),
                words(I18n.id("cmd.desc.effect"), EffectWand.PRESETS)));
        addSubCommand(new Sub("teamlook", I18n.id("cmd.desc.team_look_both_tint_mist_off"),
                new Sub("both", I18n.id("cmd.desc.tint_mist")), new Sub("tint", I18n.id("cmd.desc.color_the_whole_mob")),
                new Sub("mist", I18n.id("cmd.desc.mist_of_the_team_color")), new Sub("off", I18n.id("cmd.desc.no_team_look")),
                new Sub("time", I18n.id("cmd.desc.how_long_the_look_stays_seco"), words(I18n.id("cmd.desc.whole_battle"), "infinite")),
                new Sub("onlywand", I18n.id("cmd.desc.show_the_look_only_while_the"),
                        words(I18n.id("cmd.desc.only_with_the_team_wand"), "on", "off"))));
        addSubCommand(new Sub("teams", I18n.id("cmd.desc.team_battles_and_custom_team"),
                new Sub("auto", I18n.id("cmd.desc.put_mobs_in_range_into_teams")),
                new Sub("start", I18n.id("cmd.desc.start_the_team_battle")),
                new Sub("kit", I18n.id("cmd.desc.teams_kit")),
                new Sub("clear", I18n.id("cmd.desc.empty_your_teams")),
                new Sub("clearall", I18n.id("cmd.desc.empty_every_player_s_teams")),
                new Sub("list", I18n.id("cmd.desc.show_the_teams")),
                new Sub("add", I18n.id("cmd.desc.add_a_team_name_palette_colo")),
                new Sub("color", I18n.id("cmd.desc.recolor_a_team_team_palette")),
                new Sub("rename", I18n.id("cmd.desc.rename_a_team_team_new_name")),
                new Sub("remove", I18n.id("cmd.desc.remove_a_team_team")),
                new Sub("reset", I18n.id("cmd.desc.back_to_the_4_default_teams")),
                new Sub("join", I18n.id("cmd.desc.fight_with_a_team_yourself_t")),
                new Sub("leave", I18n.id("cmd.desc.leave_your_team_or_team_batt"))));
        addSubCommand(new Sub("kit", I18n.id("cmd.desc.kit"),
                new Sub("list", I18n.id("cmd.desc.kit_list")),
                new Sub("show", I18n.id("cmd.desc.kit_show")),
                new Sub("save", I18n.id("cmd.desc.kit_save")),
                new Sub("set", I18n.id("cmd.desc.kit_set")),
                new Sub("remove", I18n.id("cmd.desc.kit_remove"))));
        addSubCommand(new Sub("summon", I18n.id("cmd.desc.summon_the_list_around_you_o"),
                new Sub("list", I18n.id("cmd.desc.show_the_summon_list")),
                new Sub("add", I18n.id("cmd.desc.add_a_mob_type_role_count_ra")),
                new Sub("fill", I18n.id("cmd.desc.summon_fill"), words(I18n.id("cmd.desc.summon_fill"), "4", "6", "8", "12")),
                new Sub("modify", I18n.id("cmd.desc.change_a_listed_mob_s_count")),
                new Sub("remove", I18n.id("cmd.desc.remove_a_mob_type_role")),
                new Sub("clear", I18n.id("cmd.desc.empty_the_list")),
                new Sub("team", I18n.id("cmd.desc.team_rule_role_team_none"),
                        new Sub("all", I18n.id("cmd.desc.summon_team_all"), new Sub("none", I18n.id("cmd.desc.summon_team_all")))),
                new Sub("kit", I18n.id("cmd.desc.summon_kit")),
                new Sub("random", I18n.id("cmd.desc.range_for_random_counts_min")),
                new Sub("atleastone", I18n.id("cmd.desc.random_types_spawn_at_least"), words(I18n.id("cmd.desc.at_least_one"), "true", "false")),
                new Sub("lists", I18n.id("cmd.desc.summon_lists")),
                new Sub("save", I18n.id("cmd.desc.summon_save")),
                new Sub("load", I18n.id("cmd.desc.summon_load")),
                new Sub("delete", I18n.id("cmd.desc.summon_delete"))));
        addSubCommand(new Sub("morph", I18n.id("cmd.desc.morph_wand_model_model_id_li"),
                new Sub("list", I18n.id("cmd.desc.search_model_ids_text")),
                new Sub("reset", I18n.id("cmd.desc.give_every_morphed_mob_its_o"))));
        addSubCommand(new Sub("eyetest", I18n.id("cmd.desc.experimental_recolor_the_eye"),
                new Sub("off", I18n.id("cmd.desc.put_back_the_original_model"))));
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
                case "add", "color", "rename", "remove", "kit" -> Perms.CONFIG;
                default -> Perms.CONFIG;
            };
        }
        if (command.equals("summon")) {
            if (sub.equals("list") || sub.equals("lists")) return null;
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
                || command.equals("resetall") || command.equals("debuglog") || command.equals("skiptimer")
                || command.equals("debugrecord") || command.equals("debugdump")
                || command.equals("servervs") || command.equals("serverholograms") || command.equals("servericons")) return Perms.ADMIN;
        if (command.equals("army") && sub.equals("kit")) return Perms.USE;
        if (command.equals("kit")) return sub.isEmpty() || sub.equals("list") || sub.equals("show") ? null : Perms.CONFIG;
        if (command.equals("army") || command.equals("apply") || command.equals("measure") || command.equals("test")
                || command.equals("pictures")) return Perms.ADMIN;
        if (command.equals("chaos")) return Perms.USE;
        if (command.equals("watch") || command.equals("film")) return sub.equals("exit") ? null : Perms.USE;
        if (command.equals("skipcamera")) return null;
        if (command.equals("eyetest")) return Perms.ADMIN;
        if (command.equals("snap") || command.equals("debug")) return Perms.USE;
        return Perms.CONFIG;
    }

    /** Runs the command, then warns about any setting it pushed past its safe range ({@link Limits}). */
    private static void dispatch(CommandContext ctx) {
        java.util.Properties before = FxSettings.toProperties();
        dispatchSetting(ctx);
        for (Message m : Limits.changed(before, FxSettings.toProperties())) reply(ctx, m, ERR);
    }

    private static void dispatchSetting(CommandContext ctx) {
        String key = settingOf(ctx);
        String val = valueOf(ctx, key);
        // The old short words (show, title, area...) were removed: only the full names are commands.
        if (RENAMED.containsValue(key)) {
            reply(ctx, I18n.tr("cmd.unknown"), ERR);
            return;
        }
        key = RENAMED.getOrDefault(key, key);

        String required = requiredPermission(commandArguments(ctx));
        if (required != null && !Perms.allowed(ctx.sender(), required)) {
            reply(ctx, I18n.tr("cmd.noperm").param("perm", required), ERR);
            return;
        }

        switch (key) {
            case "help" -> {
                String[] lines = HELP.get(val == null ? "" : val.toLowerCase());
                if (lines == null) {
                    reply(ctx, I18n.tr("cmd.help.unknown"), ERR);
                    return;
                }
                reply(ctx, helpLine(lines[0]), "#ffd23e");
                for (int i = 1; i < lines.length; i++) {
                    boolean detail = lines[i].startsWith("  ");
                    Message line = helpLine(lines[i].trim());
                    reply(ctx, detail ? Message.join(Message.raw("  "), line) : line, detail ? "#c8e6ff" : OK);
                }
                return;
            }
            case "config", "panel" -> {
                if (argsAfter(ctx, key).length != 0) {
                    usage(ctx, "/mobbattle config", I18n.tr("cmd.note.or_panel"));
                    return;
                }
                if (!ctx.isPlayer()) {
                    reply(ctx, I18n.tr("cmd.config.onlyplayer"), ERR);
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
                    reply(ctx, I18n.tr("cmd.perm.adminonly").param("perm", Perms.ADMIN), ERR);
                    return;
                }
                String[] a = argsAfter(ctx, "permissions");
                if (a.length != 1) {
                    usage(ctx, "/mobbattle permissions open|server");
                    return;
                }
                String error = FxSettings.setPermissionsMode(a[0]);
                if (error != null) {
                    reply(ctx, error, ERR);
                    return;
                }
                reply(ctx, I18n.tr("cmd.perm.saved").param("mode", String.valueOf(FxSettings.permissionsMode)), OK);
                return;
            }
            case "reload" -> {
                if (argsAfter(ctx, "reload").length != 0) {
                    usage(ctx, "/mobbattle reload");
                    return;
                }
                String error = FxSettings.reload();
                if (error != null) {
                    reply(ctx, error, ERR);
                    return;
                }
                // The panel's mob, model and effect lists are built again too (a mod changed without changing counts).
                PickLists.clearAll();
                reply(ctx, I18n.tr("cmd.reload.done").param("mode", String.valueOf(FxSettings.permissionsMode)), OK);
                return;
            }
            case "event" -> {
                ServerEvents.handle(ctx, commandArguments(ctx));
                return;
            }
            case "show" -> {
                reply(ctx, I18n.tr("cmd.show.title"), "#ffd23e");
                for (String line : FxSettings.describeLines()) {
                    reply(ctx, line, OK);
                }
                return;
            }
            case "laser", "title", "wintitle", "music", "camera", "countdown", "readytime", "area",
                 "camerastyle", "cameratime", "camerashots", "endcamera", "endcameratime", "autowatch", "camerabars", "camerablur", "autofilm", "vsscreen", "runes",
                 "tiebreak", "killtiebreak" -> {
                if (!setKindFx(ctx, optFor(key))) return;
            }
            case "color" -> {
                String laser = FxSettings.parseLaserColor(val);
                if (laser == null) {
                    usage(ctx, "/mobbattle fightLaserColor <color>|random", I18n.tr("cmd.note.palette").param("colors", String.join(", ", Teams.PALETTE.keySet())));
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
                    usage(ctx, "/mobbattle battlePvp on|off");
                    return;
                }
                FxSettings.battlePvp = on;
            }
            case "chaos" -> {
                String[] w = argsAfter(ctx, "chaos");
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (w.length != 1 || !"me".equalsIgnoreCase(w[0]) || ref == null || !ref.isValid()) {
                    usagePlayer(ctx, "/mobbattle chaos me");
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
                    usage(ctx, "/mobbattle arena <duel|allVsOne|royale|teams|all> on|show|wall|off");
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
                    usage(ctx, "/mobbattle timer <duel|allVsOne|royale|teams|all> <90|2m|1m30s|--minutes=2 --seconds=30|off>",
                            I18n.tr("cmd.note.up_to").param("max", formatDuration(FxSettings.MAX_TIMER)));
                    return;
                }
                for (BattleManager.Kind k : BattleManager.Kind.values()) {
                    if (kind == null || k == kind) FxSettings.TIMER.put(k, seconds);
                }
            }
            case "unstick" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle unstick on|off");
                    return;
                }
                FxSettings.unstick = on;
                if (!Unstick.active()) Unstick.clear();
            }
            case "standofftime" -> {
                int n;
                if ("off".equalsIgnoreCase(val)) {
                    n = 0;
                } else {
                    try {
                        n = Integer.parseInt(val == null ? "" : val.replace("s", "").trim());
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle standoffTime <0-" + FxSettings.MAX_STANDOFF_SECONDS + " seconds>|off");
                        return;
                    }
                }
                FxSettings.standoffSeconds = Math.max(0, Math.min(FxSettings.MAX_STANDOFF_SECONDS, n));
                if (!Unstick.active()) Unstick.clear();
            }
            case "arenavertical" -> {
                int n;
                if ("off".equalsIgnoreCase(val)) {
                    n = 0;
                } else {
                    try {
                        n = Math.max(0, Math.min(FxSettings.MAX_BATTLE_DEPTH, Integer.parseInt(val == null ? "" : val.trim())));
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle arenaVertical <blocks>|off", defaultNote("off"));
                        return;
                    }
                }
                FxSettings.arenaVertical = n;
            }
            case "battlebelow", "battleabove" -> {
                int n;
                if ("off".equalsIgnoreCase(val)) {
                    n = 0;
                } else {
                    try {
                        n = Math.max(0, Math.min(FxSettings.MAX_BATTLE_DEPTH, Integer.parseInt(val == null ? "" : val.trim())));
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle " + (key.equals("battlebelow") ? "battleBelow" : "battleAbove") + " <blocks>|off",
                                defaultNote(key.equals("battlebelow") ? "50" : "40"));
                        return;
                    }
                }
                if (key.equals("battlebelow")) FxSettings.battleBelow = n;
                else FxSettings.battleAbove = n;
            }
            case "passivewin" -> {
                int n;
                if ("off".equalsIgnoreCase(val)) {
                    n = 0;
                } else {
                    try {
                        n = Integer.parseInt(val == null ? "" : val.replace("s", "").trim());
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle passiveWin <0-" + FxSettings.MAX_PASSIVE_WIN_SECONDS + " seconds>|off");
                        return;
                    }
                }
                FxSettings.passiveWinSeconds = Math.max(0, Math.min(FxSettings.MAX_PASSIVE_WIN_SECONDS, n));
            }
            case "idleend" -> {
                int n;
                if ("off".equalsIgnoreCase(val)) {
                    n = 0;
                } else {
                    try {
                        n = Integer.parseInt(val == null ? "" : val.replace("s", "").trim());
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle idleEnd <0-" + FxSettings.MAX_IDLE_END_SECONDS + " seconds>|off");
                        return;
                    }
                }
                FxSettings.idleEndSeconds = Math.max(0, Math.min(FxSettings.MAX_IDLE_END_SECONDS, n));
            }
            case "battlemaxtime" -> {
                int n;
                if ("off".equalsIgnoreCase(val) || "infinite".equalsIgnoreCase(val)) {
                    n = 0;
                } else {
                    try {
                        n = Integer.parseInt(val == null ? "" : val.replace("min", "").replace("m", "").trim());
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle battleMaxTime <1-" + FxSettings.MAX_BATTLE_MINUTES + " minutes>|off");
                        return;
                    }
                }
                FxSettings.battleMaxMinutes = Math.max(0, Math.min(FxSettings.MAX_BATTLE_MINUTES, n));
            }
            case "passivewinignore" -> {
                String[] a = argsAfter(ctx, "passivewinignore");
                String l = FxSettings.parsePassiveWinIgnore(String.join(",", a));
                if (a.length == 0 || l == null) {
                    usage(ctx, "/mobbattle passiveWinIgnore <role,role*,...>|none", defaultNote("Test_Dummy*"));
                    return;
                }
                FxSettings.passiveWinIgnore = l;
            }
            case "duelcard" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle duelCard on|off");
                    return;
                }
                FxSettings.duelCard = on;
                if (!on) DuelCard.clearAll();
            }
            case "focuscard" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle focusCard on|off");
                    return;
                }
                FxSettings.focusCard = on;
                if (!on) FocusCard.clearAll();
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
                    usage(ctx, "/mobbattle arenaShrinkTo <" + FxSettings.MIN_SHRINK_TO + "-" + FxSettings.MAX_ARENA + ">");
                    return;
                }
            }
            case "arenashrinktime" -> {
                int seconds = parseDuration(argsAfter(ctx, "arenashrinktime"));
                if (seconds < 10 || seconds > FxSettings.MAX_TIMER) {
                    usage(ctx, "/mobbattle arenaShrinkTime <90|2m|1m30s>", I18n.tr("cmd.note.time_range").param("max", formatDuration(FxSettings.MAX_TIMER)));
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
                    usage(ctx, "/mobbattle timeUp <kind|all> draw|kill|continue|health|judge");
                    return;
                }
                kv.getKey().forEach(k -> FxSettings.TIME_UP.put(k, rule));
            }
            case "judgeby" -> {
                var kv = kindAndValue(ctx, "judgeby", "judgeBy <duel|allVsOne|royale|teams|all> health|damage|kills|mix");
                if (kv == null) return;
                String by = FxSettings.parseJudgeBy(kv.getValue());
                if (by == null) {
                    usage(ctx, "/mobbattle judgeBy <kind|all> health|damage|kills|mix");
                    return;
                }
                kv.getKey().forEach(k -> FxSettings.JUDGE_BY.put(k, by));
            }
            case "snappercent", "snaptime" -> {
                boolean pct = key.equals("snappercent");
                int max = pct ? 100 : FxSettings.MAX_SNAP_SECONDS;
                try {
                    int v = Integer.parseInt(val == null ? "" : val.replace("%", "").replace("s", ""));
                    if (v < 1 || v > max) throw new NumberFormatException();
                    if (pct) FxSettings.snapPercent = v; else FxSettings.snapSeconds = v;
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle " + (pct ? "snapPercent" : "snapTime") + " <1-" + max + ">");
                    return;
                }
            }
            case "snaphitdelay" -> {
                try {
                    int v = Integer.parseInt(val == null ? "" : val.replace("ms", "").trim());
                    if (v < 0 || v > FxSettings.MAX_SNAP_HIT_DELAY_MS) throw new NumberFormatException();
                    FxSettings.snapHitDelayMs = v;
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle snapHitDelay <0-" + FxSettings.MAX_SNAP_HIT_DELAY_MS + ">", I18n.tr("cmd.note.ms"));
                    return;
                }
            }
            case "killhitdelay" -> {
                try {
                    int v = Integer.parseInt(val == null ? "" : val.replace("ms", "").trim());
                    if (v < 0 || v > FxSettings.MAX_SNAP_HIT_DELAY_MS) throw new NumberFormatException();
                    FxSettings.killHitDelayMs = v;
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle killHitDelay <0-" + FxSettings.MAX_SNAP_HIT_DELAY_MS + ">", I18n.tr("cmd.note.ms"));
                    return;
                }
            }
            case "snapradius" -> {
                try {
                    FxSettings.snapRadius = FxSettings.clampRadius(Integer.parseInt(val == null ? "" : val));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle snapRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">",
                            I18n.tr("cmd.note.snap_radius").param("value", FxSettings.DEFAULT_SNAP_RADIUS));
                    return;
                }
            }
            case "snapmode" -> {
                if (!"global".equalsIgnoreCase(val) && !"team".equalsIgnoreCase(val)) {
                    usage(ctx, "/mobbattle snapMode global|team");
                    return;
                }
                FxSettings.snapMode = val.toLowerCase(java.util.Locale.ROOT);
            }
            case "skipcamera" -> {
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (ref == null || !ref.isValid()) {
                    reply(ctx, I18n.tr("cmd.camera.onlyplayer"), ERR);
                    return;
                }
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var pr = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    reply(ctx, I18n.tr(pr != null && FightCamera.cancel(pr) ? "cmd.camera.skipped" : "cmd.camera.none"), OK);
                });
                return;
            }
            case "watch" -> {
                String[] w = argsAfter(ctx, "watch");
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                boolean exit = w.length == 1 && "exit".equalsIgnoreCase(w[0]);
                if (ref == null || !ref.isValid() || w.length > 1 || (w.length == 1 && !exit)) {
                    usagePlayer(ctx, "/mobbattle watch [exit]");
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
            case "film" -> {
                String[] w = argsAfter(ctx, "film");
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                boolean exit = w.length == 1 && "exit".equalsIgnoreCase(w[0]);
                if (ref == null || !ref.isValid() || w.length > 1 || (w.length == 1 && !exit)) {
                    usagePlayer(ctx, "/mobbattle film [exit]");
                    return;
                }
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var pr = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (pr == null) return;
                    if (exit) FilmCamera.exit(pr);
                    else FilmCamera.start(pr, ref, store);
                });
                return;
            }
            case "snap" -> {
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (ref == null || !ref.isValid()) {
                    reply(ctx, I18n.tr("cmd.snap.onlyplayer"), ERR);
                    return;
                }
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var pr = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    Snap.snap(pr, ref, null, store);
                });
                return;
            }
            case "filmshot" -> {
                // filmShot <shot> <0-300>: that shot's percent of its usual weight.
                String[] words = ctx.getInputString() == null ? new String[0] : ctx.getInputString().trim().split("\\s+");
                String shot = null, num = null;
                for (int i = 0; i < words.length - 2; i++) {
                    if (words[i].equalsIgnoreCase("filmshot")) {
                        shot = words[i + 1].toLowerCase(java.util.Locale.ROOT);
                        num = words[i + 2].replace("%", "");
                    }
                }
                int n;
                try {
                    n = num == null ? -1 : Integer.parseInt(num.trim());
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (shot == null || !java.util.Arrays.asList(FilmCamera.SHOT_WORDS).contains(shot) || n < 0) {
                    usage(ctx, "/mobbattle filmShot <" + String.join("|", FilmCamera.SHOT_WORDS) + "> <0-" + FxSettings.MAX_FILM_WEIGHT + ">");
                    return;
                }
                FxSettings.filmWeights.put(shot, Math.min(FxSettings.MAX_FILM_WEIGHT, n));
            }
            case "filmplayershots", "filmwideshots", "filmshottime", "filmshotvariation", "filmpace" -> {
                int min = switch (key) {
                    case "filmshottime" -> FxSettings.MIN_FILM_SHOT;
                    case "filmpace" -> FxSettings.MIN_FILM_PACE;
                    default -> 0;
                };
                int max = switch (key) {
                    case "filmshottime" -> FxSettings.MAX_FILM_SHOT;
                    case "filmpace" -> FxSettings.MAX_FILM_PACE;
                    case "filmshotvariation" -> 90;
                    default -> 100;
                };
                int n;
                try {
                    n = Integer.parseInt(val == null ? "" : val.replace("%", "").replace("s", "").trim());
                } catch (NumberFormatException e) {
                    String word = switch (key) {
                        case "filmshottime" -> "filmShotTime <seconds ";
                        case "filmpace" -> "filmPace <percent ";
                        case "filmshotvariation" -> "filmShotVariation <percent ";
                        case "filmwideshots" -> "filmWideShots <percent ";
                        default -> "filmPlayerShots <percent ";
                    };
                    usage(ctx, "/mobbattle " + word + min + "-" + max + ">");
                    return;
                }
                n = Math.max(min, Math.min(max, n));
                switch (key) {
                    case "filmshottime" -> FxSettings.filmShotSeconds = n;
                    case "filmpace" -> FxSettings.filmPace = n;
                    case "filmshotvariation" -> FxSettings.filmShotVariation = n;
                    case "filmwideshots" -> FxSettings.filmWideShots = n;
                    default -> FxSettings.filmPlayerShots = n;
                }
            }
            case "vsteamreps" -> {
                if (!"1".equals(val) && !"2".equals(val)) {
                    usage(ctx, "/mobbattle vsTeamReps 1|2");
                    return;
                }
                FxSettings.vsTeamReps = Integer.parseInt(val);
            }
            case "filmtransition" -> {
                if (!"cut".equalsIgnoreCase(val) && !"glide".equalsIgnoreCase(val)) {
                    usage(ctx, "/mobbattle filmTransition cut|glide");
                    return;
                }
                FxSettings.filmTransition = val.toLowerCase(java.util.Locale.ROOT);
            }
            case "watchtransition" -> {
                if (!"cut".equalsIgnoreCase(val) && !"glide".equalsIgnoreCase(val)) {
                    usage(ctx, "/mobbattle watchTransition cut|glide");
                    return;
                }
                FxSettings.watchTransition = val.toLowerCase(java.util.Locale.ROOT);
            }
            case "endslowmo" -> {
                int n;
                if (val != null && "off".equalsIgnoreCase(val.trim())) {
                    n = 0;
                } else {
                    try {
                        n = Integer.parseInt(val == null ? "" : val.replace("%", "").trim());
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle endSlowMo <speed percent 5-99>|off");
                        return;
                    }
                }
                FxSettings.endSlowMo = n <= 0 || n >= 100 ? 0 : Math.max(5, n);
            }
            case "endslowmotime" -> {
                try {
                    FxSettings.endSlowMoSeconds = Math.max(1, Math.min(FxSettings.MAX_SLOW_MO_SECONDS,
                            Integer.parseInt(val == null ? "" : val.replace("s", "").trim())));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle endSlowMoTime <1-" + FxSettings.MAX_SLOW_MO_SECONDS + " seconds>");
                    return;
                }
            }
            case "watchautoswitch" -> {
                int n;
                if (val != null && ("off".equalsIgnoreCase(val.trim()) || "0".equals(val.trim()))) {
                    n = 0;
                } else {
                    try {
                        n = Integer.parseInt(val == null ? "" : val.replace("s", "").trim());
                    } catch (NumberFormatException e) {
                        usage(ctx, "/mobbattle watchAutoSwitch <seconds " + FxSettings.MIN_WATCH_AUTO_SWITCH + "-"
                                + FxSettings.MAX_WATCH_AUTO_SWITCH + ">|off");
                        return;
                    }
                    n = n <= 0 ? 0 : Math.max(FxSettings.MIN_WATCH_AUTO_SWITCH, Math.min(FxSettings.MAX_WATCH_AUTO_SWITCH, n));
                }
                FxSettings.watchAutoSwitch = n;
            }
            case "filmstyle" -> {
                // filmStyle <default|battle|action|cinematic>: every film setting at once (FilmStyles).
                if (val == null || !FilmStyles.apply(val)) {
                    usage(ctx, "/mobbattle filmStyle " + String.join("|", FilmStyles.NAMES));
                    return;
                }
            }
            case "filmshots" -> {
                if ("default".equalsIgnoreCase(val)) {
                    FxSettings.filmWeights.clear();   // every shot back to FILM_DEFAULT_WEIGHTS
                    break;
                }
                String shots = FilmCamera.parseShots(val);
                if (shots == null) {
                    usage(ctx, "/mobbattle filmShots all|battle|<" + String.join(",", FilmCamera.SHOT_WORDS) + ">");
                    return;
                }
                // Shortcut: the listed shots on (100 % unless already set), the rest off.
                java.util.Set<String> on = java.util.Set.of(shots.split(","));
                for (String w : FilmCamera.SHOT_WORDS) {
                    if (!on.contains(w)) FxSettings.filmWeights.put(w, 0);
                    else if (FxSettings.filmWeight(w) == 0) FxSettings.filmWeights.put(w, 100);
                }
            }
            case "timerstyle" -> {
                if (!"custom".equalsIgnoreCase(val) && !"portal".equalsIgnoreCase(val)) {
                    usage(ctx, "/mobbattle timerStyle custom|portal");
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
                        usage(ctx, "/mobbattle arenaSize <" + FxSettings.MIN_ARENA + "-" + FxSettings.MAX_ARENA + ">|auto");
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
                    usage(ctx, "/mobbattle titleScope owner|nearby|world");
                    return;
                }
                FxSettings.titleScope = scope;
            }
            case "camerahud" -> {
                String hud = val == null ? "" : val.toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Arrays.asList(FxSettings.CAMERA_HUDS).contains(hud)) {
                    usage(ctx, "/mobbattle cameraHud hide|show");
                    return;
                }
                FxSettings.cameraHud = hud;
            }
            case "cameraface" -> {
                String v = val == null ? "" : val.toLowerCase(java.util.Locale.ROOT);
                if (!v.equals("on") && !v.equals("off")) {
                    usage(ctx, "/mobbattle cameraFace on|off");
                    return;
                }
                FxSettings.cameraFace = v.equals("on");
            }
            case "camerascope" -> {
                String scope = val == null ? "" : val.toLowerCase();
                if (!java.util.Arrays.asList(FxSettings.TITLE_SCOPES).contains(scope)) {
                    usage(ctx, "/mobbattle cameraScope owner|nearby|world");
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
                        usage(ctx, "/mobbattle titleStyle <" + names + ", random>");
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
                    usage(ctx, "/mobbattle areaRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">");
                    return;
                }
                FxSettings.radius = FxSettings.clampRadius(r);
            }
            case "areaonlywand" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle areaOnlyWand on|off");
                    return;
                }
                FxSettings.areaOnlyWand = on;
            }
            case "areacolor" -> {
                String color = FxSettings.parseAreaColor(val);
                if (color == null) {
                    usage(ctx, "/mobbattle areaColor <color>|random", I18n.tr("cmd.note.colors")
                            .param("colors", String.join(", ", Teams.PALETTE.keySet()).toLowerCase(java.util.Locale.ROOT)));
                    return;
                }
                FxSettings.areaColor = color;
            }
            case "teamfx" -> {
                if ("onlywand".equalsIgnoreCase(val)) {
                    String[] w = argsAfter(ctx, "onlywand");
                    Boolean on = parseOnOff(w.length > 0 ? w[0] : null);
                    if (on == null) {
                        usage(ctx, "/mobbattle teamLook onlyWand on|off");
                        return;
                    }
                    FxSettings.teamLookOnlyWand = on;
                    if (!on) {
                        reply(ctx, I18n.tr("cmd.teamlook.always"), OK);
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
                            usage(ctx, "/mobbattle teamLook time infinite|<1-" + FxSettings.TEAM_FX_MAX_SECONDS + ">",
                                    I18n.tr("cmd.note.teamlook_time"));
                            return;
                        }
                        if (n <= 0) {
                            reply(ctx, I18n.tr("cmd.teamlook.positive"), ERR);
                            return;
                        }
                    }
                    FxSettings.teamFxSeconds = Math.max(0, Math.min(FxSettings.TEAM_FX_MAX_SECONDS, n));
                } else if (!TeamFx.isMode(val)) {
                    usage(ctx, "/mobbattle teamLook both|tint|mist|off | /mobbattle teamLook time <seconds>");
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
                    usage(ctx, "/mobbattle reset <" + String.join("|", Presets.GROUPS) + ">", I18n.tr("cmd.note.reset_group"));
                    return;
                }
                String err = Presets.reset(a[0]);
                if (err != null) {
                    reply(ctx, err, ERR);
                    return;
                }
                reply(ctx, I18n.tr("cmd.reset.done").param("group", a[0].toLowerCase()), OK);
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
            case "apply" -> {
                ApplyCommand.run(ctx, argsAfter(ctx, "apply"));
                return;
            }
            case "test" -> {
                TestTools.run(ctx, argsAfter(ctx, "test"));
                return;
            }
            case "pictures" -> {
                PicturesCommand.run(ctx, argsAfter(ctx, "pictures"));
                return;
            }
            case "measure" -> {
                measure(ctx, argsAfter(ctx, "measure"));
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
            case "kit" -> {
                kitCommand(ctx);
                return;
            }
            case "army" -> {
                String[] a = argsAfter(ctx, "army");
                if (a.length == 2 && a[0].equalsIgnoreCase("kit")) {
                    armyKit(ctx, a[1]);
                    return;
                }
                if (a.length != 1 || !a[0].equalsIgnoreCase("clearall")) {
                    usage(ctx, "/mobbattle army clearAll | kit <kit>");
                    return;
                }
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                Runnable job = () -> reply(ctx, I18n.tr("cmd.army.cleared").param("count", Army.clearAll()), OK);
                if (ref != null && ref.isValid()) ref.getStore().getExternalData().getWorld().execute(job); else job.run();
                return;
            }
            case "buff" -> {
                if (!setBuff(ctx)) return;
            }
            case "skiptimer" -> {
                int n = Arena.skipTimers();
                if (n == 0) {
                    reply(ctx, I18n.tr("cmd.skiptimer.none"), ERR);
                } else {
                    reply(ctx, I18n.tr("cmd.skiptimer.done").param("count", n), OK);
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
                        reply(ctx, I18n.tr("cmd.effect.unknown").param("name", part.trim()).param("examples", String.join(", ", EffectWand.PRESETS)), ERR);
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
                    usage(ctx, "/mobbattle duelMode exclusive|accumulate");
                    return;
                }
                FxSettings.accumulateDuels = val.equalsIgnoreCase("accumulate");
            }
            case "debug" -> {
                if (!ctx.isPlayer() || argsAfter(ctx, "debug").length != 0) {
                    usagePlayer(ctx, "/mobbattle debug");
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
                        usage(ctx, "/mobbattle summonRadius <1-" + FxSettings.MAX_SUMMON_RADIUS + "|auto>");
                        return;
                    }
                }
            }
            case "summondespawn" -> {
                String mode = SummonDespawn.parseMode(val);
                if (mode == null) {
                    usage(ctx, "/mobbattle summonDespawn never|battleEnd|timer");
                    return;
                }
                FxSettings.summonDespawn = mode;
            }
            case "summonlifetime" -> {
                int sec = SummonDespawn.parseLifetime(val);
                if (sec < 0) {
                    usage(ctx, "/mobbattle summonLifetime <" + SummonDespawn.MIN_LIFETIME + "-" + SummonDespawn.MAX_LIFETIME + " seconds>");
                    return;
                }
                FxSettings.summonLifetime = sec;
            }
            case "summonarmy" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle summonArmy on|off");
                    return;
                }
                FxSettings.summonArmy = on;
            }
            case "summonjade" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle summonJade on|off");
                    return;
                }
                FxSettings.summonJade = on;
            }
            case "armytint" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle armyTint on|off");
                    return;
                }
                FxSettings.armyTint = on;
            }
            case "equipweapondamage" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle equipWeaponDamage on|off", defaultNote("off"));
                    return;
                }
                FxSettings.equipWeaponDamage = on;
            }
            case "armyfriendlyfire" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle armyFriendlyFire on|off", defaultNote("off"));
                    return;
                }
                FxSettings.armyFriendlyFire = on;
            }
            case "armytintcolor" -> {
                String color = SummonList.parseJadeColor(val);
                if (color == null) {
                    usage(ctx, "/mobbattle armyTintColor <palette color>", defaultNote("blue"));
                    return;
                }
                FxSettings.armyTintColor = color;
            }
            case "summonjadecolor" -> {
                String color = SummonList.parseJadeColor(val);
                if (color == null) {
                    usage(ctx, "/mobbattle summonJadeColor <palette color>", defaultNote("green"));
                    return;
                }
                FxSettings.summonJadeColor = color;
            }
            case "summoncalm" -> {
                String calm = SummonCalm.parseMode(val);
                if (calm == null) {
                    usage(ctx, "/mobbattle summonCalm off|summons|all");
                    return;
                }
                FxSettings.summonCalm = calm;
                if (SummonCalm.OFF.equals(calm)) SummonCalm.clear();
            }
            case "summonexit" -> {
                String exit = SummonDespawn.parseExit(val);
                if (exit == null) {
                    usage(ctx, "/mobbattle summonExit disintegrate|vanish|shatter");
                    return;
                }
                FxSettings.summonExit = exit;
            }
            case "debuglog" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle debugLog on|off");
                    return;
                }
                FxSettings.debug = on;
                if (on) {
                    for (String line : FxSettings.describeLines()) MobBattlePlugin.debug("settings: " + line);
                }
            }
            case "servervs", "serverholograms", "servericons" -> {
                Boolean on = parseOnOff(val);
                String name = switch (key) {
                    case "servervs" -> "serverVs";
                    case "serverholograms" -> "serverHolograms";
                    default -> "serverIcons";
                };
                if (on == null) {
                    usage(ctx, "/mobbattle " + name + " on|off");
                    return;
                }
                switch (key) {
                    case "servervs" -> FxSettings.serverVs = on;
                    case "serverholograms" -> FxSettings.serverHolograms = on;
                    default -> FxSettings.serverIcons = on;
                }
                // the pictures follow at the next server start (drawn and sent at boot, or not)
                ctx.sendMessage(I18n.tr("cmd.server.feature.restart").color("#ffcc55"));
            }
            case "debugrecord" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle debugRecord on|off");
                    return;
                }
                FxSettings.debugRecord = on;
                if (!on) DebugRecorder.clear();
            }
            case "debugdump" -> {
                DebugRecorder.Dump d = DebugRecorder.dump(ctx.isPlayer() ? ctx.sender().getUsername() : "console");
                if (d.file() != null) {
                    reply(ctx, "Debug record saved: debug/" + d.file() + " (" + d.lines() + " lines, plugin data folder).", OK);
                } else {
                    reply(ctx, "Debug record not saved: " + d.error() + ".", ERR);
                }
                return;
            }
            case "debugtrack" -> {
                int n;
                try {
                    n = Integer.parseInt(val == null ? "" : val.replace("s", "").trim());
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle debugTrack <0-" + FxSettings.MAX_DEBUG_TRACK_SECONDS + " seconds>", I18n.tr("cmd.note.zero_report"));
                    return;
                }
                FxSettings.debugTrackSeconds = Math.max(0, Math.min(FxSettings.MAX_DEBUG_TRACK_SECONDS, n));
            }
            case "wandfx" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle wandFx on|off");
                    return;
                }
                FxSettings.wandFx = on;
            }
            case "killplayers" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle killPlayers on|off");
                    return;
                }
                FxSettings.killPlayers = on;
            }
            case "killloot" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle killLoot on|off");
                    return;
                }
                FxSettings.killLoot = on;
            }
            case "killdelay" -> {
                float sec;
                try {
                    sec = Float.parseFloat(val == null ? "" : val.replace(',', '.'));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle killDelay <seconds 0-3>", I18n.tr("cmd.note.zero_instant"));
                    return;
                }
                FxSettings.killDelayMs = Math.max(0, Math.min(FxSettings.MAX_KILL_DELAY_MS, Math.round(sec * 1000)));
            }
            case "lookglow", "deathglow" -> {
                int n;
                try {
                    n = Integer.parseInt(val == null ? "" : val.trim());
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle " + ("lookglow".equals(key) ? "lookGlow" : "deathGlow") + " <0-15>", I18n.tr("cmd.note.zero_off"));
                    return;
                }
                n = Math.max(0, Math.min(FxSettings.MAX_GLOW, n));
                if ("lookglow".equals(key)) FxSettings.lookGlow = n;
                else FxSettings.deathGlow = n;
            }
            case "maxmobs" -> {
                int n;
                try {
                    n = Integer.parseInt(val == null ? "" : val);
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle maxMobs <2-" + FxSettings.MAX_MOBS_LIMIT + ">");
                    return;
                }
                FxSettings.maxMobs = FxSettings.clampMaxMobs(n);
            }
            case "height" -> {
                int h;
                try {
                    h = Integer.parseInt(val == null ? "" : val);
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle areaHeight <" + FxSettings.MIN_HEIGHT + "-" + FxSettings.MAX_HEIGHT + ">");
                    return;
                }
                FxSettings.pillarHeight = FxSettings.clampHeight(h);
            }
            case "teambytype" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle teamByType on|off");
                    return;
                }
                FxSettings.teamByType = on;
            }
            case "snapplayers" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle snapPlayers on|off");
                    return;
                }
                FxSettings.snapPlayers = on;
            }
            case "mobfx" -> {
                String[] a = argsAfter(ctx, "mobfx");
                if (a.length < 2 || !ActionFx.setMob(a[0], a[1])) {
                    usage(ctx, "/mobbattle mobFx <" + ActionFx.roles() + "> <palette color|default|off>");
                    return;
                }
            }
            case "crown" -> {
                String c = Buffs.parseCrown(val);
                if (c == null) {
                    usage(ctx, "/mobbattle crown gold|<palette color>|off");
                    return;
                }
                ActionFx.crown = c;
            }
            case "cleansecolor" -> {
                String c = Buffs.parseCrown(val);
                if (c == null || c.equals("off") || c.equals("Gold")) {
                    usage(ctx, "/mobbattle cleanseColor <palette color>", defaultNote("white"));
                    return;
                }
                FxSettings.cleanseColor = c;
            }
            case "bufflook" -> {
                String l = FxSettings.parseBuffLook(val);
                if (l == null) {
                    usage(ctx, "/mobbattle buffLook shimmer|runes|off", defaultNote("shimmer"));
                    return;
                }
                FxSettings.buffLook = l;
                Buffs.refreshLookAll();
            }
            case "bufflight" -> {
                String l = FxSettings.parseBuffLight(val);
                if (l == null) {
                    usage(ctx, "/mobbattle buffLight buff|<palette color>|off", I18n.tr("cmd.note.buff_default"));
                    return;
                }
                FxSettings.buffLight = l;
            }
            case "heallight" -> {
                String l = FxSettings.parseHealLight(val);
                if (l == null) {
                    usage(ctx, "/mobbattle healLight <palette color>|off", defaultNote("green"));
                    return;
                }
                FxSettings.healLight = l;
            }
            case "buffcolor" -> {
                String c = Buffs.parseCrown(val);
                if (c == null || c.equals("off") || c.equals("Gold")) {
                    usage(ctx, "/mobbattle buffColor <palette color>", defaultNote("purple"));
                    return;
                }
                FxSettings.buffColor = c;
            }
            case "wave", "glow" -> {
                boolean glow = key.equals("glow");
                String[] a = argsAfter(ctx, glow ? "bordercolor" : key);
                if (a.length < 2 || !ActionFx.set(glow, a[0], a[1])) {
                    usage(ctx, "/mobbattle " + (glow ? "borderColor" : "wave") + " <" + ActionFx.keys() + "> <" + ActionFx.colors(glow) + ">");
                    return;
                }
            }
            case "morphplayers" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle morphPlayers on|off");
                    return;
                }
                FxSettings.morphPlayers = on;
            }
            case "morphburst", "morphshuffle" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle " + (key.equals("morphburst") ? "morphBurst" : "morphShuffle") + " on|off");
                    return;
                }
                if (key.equals("morphburst")) FxSettings.morphBurst = on;
                else FxSettings.morphShuffle = on;
            }
            case "wavetravel", "wavegroundonly", "battlewavetravel", "battlewavegroundonly" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle " + switch (key) {
                        case "wavetravel" -> "waveTravel";
                        case "wavegroundonly" -> "waveGroundOnly";
                        case "battlewavetravel" -> "battleWaveTravel";
                        default -> "battleWaveGroundOnly";
                    } + " on|off");
                    return;
                }
                switch (key) {
                    case "wavetravel" -> FxSettings.waveTravel = on;
                    case "wavegroundonly" -> FxSettings.waveGroundOnly = on;
                    case "battlewavetravel" -> FxSettings.battleWaveTravel = on;
                    default -> FxSettings.battleWaveGroundOnly = on;
                }
            }
            case "healareaself", "cleanseareaself", "effectareaself", "morphburstself" -> {
                Boolean on = parseOnOff(val);
                String name = switch (key) {
                    case "healareaself" -> "healAreaSelf";
                    case "cleanseareaself" -> "cleanseAreaSelf";
                    case "effectareaself" -> "effectAreaSelf";
                    default -> "morphBurstSelf";
                };
                if (on == null) {
                    usage(ctx, "/mobbattle " + name + " on|off");
                    return;
                }
                switch (key) {
                    case "healareaself" -> FxSettings.healAreaSelf = on;
                    case "cleanseareaself" -> FxSettings.cleanseAreaSelf = on;
                    case "effectareaself" -> FxSettings.effectAreaSelf = on;
                    default -> FxSettings.morphBurstSelf = on;
                }
            }
            case "healarea" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle healArea on|off");
                    return;
                }
                FxSettings.healArea = on;
            }
            case "killarea", "killareaplayers" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle " + (key.equals("killarea") ? "killArea" : "killAreaPlayers") + " on|off");
                    return;
                }
                if (key.equals("killarea")) FxSettings.killArea = on;
                else FxSettings.killAreaPlayers = on;
            }
            case "killradius" -> {
                try {
                    FxSettings.killRadius = FxSettings.clampRadius(Integer.parseInt(val == null ? "" : val));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle killRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">",
                            defaultNote(String.valueOf(FxSettings.DEFAULT_KILL_RADIUS)));
                    return;
                }
            }
            case "healradius" -> {
                try {
                    FxSettings.healRadius = FxSettings.clampRadius(Integer.parseInt(val == null ? "" : val));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle healRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">",
                            defaultNote(String.valueOf(FxSettings.DEFAULT_HEAL_RADIUS)));
                    return;
                }
            }
            case "effectarea" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle effectArea on|off");
                    return;
                }
                FxSettings.effectArea = on;
            }
            case "effectradius" -> {
                try {
                    FxSettings.effectRadius = FxSettings.clampRadius(Integer.parseInt(val == null ? "" : val));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle effectRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">",
                            defaultNote(String.valueOf(FxSettings.DEFAULT_EFFECT_RADIUS)));
                    return;
                }
            }
            case "cleansearea" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    usage(ctx, "/mobbattle cleanseArea on|off");
                    return;
                }
                FxSettings.cleanseArea = on;
            }
            case "cleanseradius" -> {
                try {
                    FxSettings.cleanseRadius = FxSettings.clampRadius(Integer.parseInt(val == null ? "" : val));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle cleanseRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">",
                            defaultNote(String.valueOf(FxSettings.DEFAULT_CLEANSE_RADIUS)));
                    return;
                }
            }
            case "morphburstradius" -> {
                try {
                    FxSettings.morphBurstRadius = FxSettings.clampRadius(Integer.parseInt(val == null ? "" : val));
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle morphBurstRadius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">",
                            defaultNote(String.valueOf(FxSettings.DEFAULT_MORPH_BURST_RADIUS)));
                    return;
                }
            }
            default -> {
                reply(ctx, I18n.tr("cmd.unknown"), ERR);
                return;
            }
        }
        FxSettings.save();
        if (key.equals("radius") || key.equals("area") || key.equals("areacolor") || key.equals("height") || key.equals("areaonlywand")) {
            previewArea(ctx);
        }
        reply(ctx, I18n.tr("cmd.saved").param("line", FxSettings.describeLines()[FxSettings.groupOf(key)]), OK);
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

    /** /mobbattle measure [look|id:&lt;uuid&gt;|last] [seconds]: MeasureTool on you, the mob you look at or an entity by id. */
    private static void measure(CommandContext ctx, String[] a) {
        if (a.length == 1 && a[0].equalsIgnoreCase("stop")) {
            if (!MeasureTool.stop(ctx.sender().getUuid())) reply(ctx, I18n.tr("cmd.measure.none"), ERR);
            return;
        }
        boolean look = false;
        String id = null;
        int seconds = 10;
        for (String w : a) {
            if (w.equalsIgnoreCase("look")) look = true;
            else if (w.matches("\\d{1,2}")) seconds = Math.max(1, Math.min(MeasureTool.MAX_SECONDS, Integer.parseInt(w)));
            else if (TestTools.isIdWord(w)) id = w;
            else {
                usage(ctx, "/mobbattle measure [look|id:<uuid>|last] [seconds 1-" + MeasureTool.MAX_SECONDS + "] | measure stop");
                return;
            }
        }
        if (id != null) {
            // By id (2026-10-13, tests from the console): no player, no look needed.
            var target = TestTools.resolve(id);
            if (target == null) {
                reply(ctx, I18n.tr("cmd.measure.noentity").param("id", id), ERR);
                return;
            }
            var targetStore = target.getStore();
            var sender = ctx.sender();
            int secs = seconds;
            targetStore.getExternalData().getWorld().execute(() -> {
                if (targetStore.isShutdown() || !target.isValid()) return;
                MeasureTool.start(sender.getUuid(), sender::sendMessage, target, secs, targetStore);
            });
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, I18n.tr("cmd.measure.console"), ERR);
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) return;
        var store = ref.getStore();
        boolean onMob = look;
        int s = seconds;
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown() || !ref.isValid()) return;
            var me = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (me == null) return;
            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> target = onMob ? ApplyCommand.lookedAt(ref, store) : ref;
            if (target == null) {
                reply(ctx, I18n.tr("cmd.measure.notlooking"), ERR);
                return;
            }
            MeasureTool.start(me, target, s, store);
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
                reply(ctx, SummonList.describeMessage(), OK);
                return;
            }
            case "add" -> {
                if (a.length < 2) {
                    usage(ctx, "/mobbattle summon add <role> [count|random] [team]");
                    return;
                }
                String role = SummonList.findRole(a[1]);
                if (role == null) {
                    reply(ctx, I18n.tr("cmd.summon.unknownrole").param("role", a[1]), ERR);
                    return;
                }
                String count = a.length > 2 ? a[2] : "1";
                if (!SummonList.isCount(count)) {
                    reply(ctx, I18n.tr("cmd.summon.count"), ERR);
                    return;
                }
                int team = -1;
                if (a.length > 3) {
                    team = BattleManager.teamIndex(a[3]);
                    if (team < 0) {
                        reply(ctx, plus(I18n.tr("cmd.summon.unknownteam"), Teams.describeMessage()), ERR);
                        return;
                    }
                }
                // Every Add is an entry of its own, even the same mob and team (user, 2026-10-16: add the same mob N
                // times; before, the counts of the same mob and team added up into one row). summon modify sets an
                // exact count on one entry.
                String key = SummonList.newKey(role);
                SummonList.ENTRIES.put(key, SummonList.normalCount(count));
                if (team >= 0) {
                    SummonList.TEAMS.put(key, team);
                }
            }
            case "modify" -> {
                if (a.length != 3 && a.length != 4) {
                    usage(ctx, "/mobbattle summon modify <role in the list> [team|none] <count|random>");
                    return;
                }
                // With a team: the entry of that mob in that team (a mob listed once per team).
                String role;
                if (a.length == 4) {
                    int team = "none".equalsIgnoreCase(a[2]) ? -1 : BattleManager.teamIndex(a[2]);
                    String found = SummonList.findRole(a[1]);
                    role = found == null || (team < 0 && !"none".equalsIgnoreCase(a[2])) ? null : SummonList.entryOf(found, team);
                } else {
                    role = SummonList.listedKey(a[1]);
                }
                if (role == null) {
                    reply(ctx, I18n.tr(a.length == 4 ? "cmd.summon.notlisted.team" : "cmd.summon.notlisted").param("role", a[1]), ERR);
                    return;
                }
                String count = a[a.length - 1];
                if (!SummonList.isCount(count)) {
                    reply(ctx, I18n.tr("cmd.summon.count"), ERR);
                    return;
                }
                SummonList.ENTRIES.put(role, SummonList.normalCount(count));
            }
            case "remove" -> {
                String role = a.length > 1 ? SummonList.listedKey(a[1]) : null;
                if (role != null) SummonList.TEAMS.remove(role);
                if (role != null) SummonList.KIT_OF.remove(role);
                if (role == null || SummonList.ENTRIES.remove(role) == null) {
                    usage(ctx, "/mobbattle summon remove <role in the list>");
                    return;
                }
            }
            case "clear" -> {
                SummonList.ENTRIES.clear();
                SummonList.TEAMS.clear();
                SummonList.KIT_OF.clear();
            }
            case "kit" -> {
                // summon kit <role in the list> <kit|none>: the equipment its mobs spawn with.
                String role = a.length > 2 ? SummonList.listedKey(a[1]) : null;
                Kits.Kit kit = a.length > 2 ? Kits.get(a[2]) : null;
                if (role == null || kit == null && !"none".equalsIgnoreCase(a[2])) {
                    usage(ctx, "/mobbattle summon kit <role in the list> <kit|none>", Kits.listMessage());
                    return;
                }
                if (kit == null) SummonList.KIT_OF.remove(role); else SummonList.KIT_OF.put(role, kit.name());
            }
            case "team" -> {
                // summon team all none: every entry loses its team, the mobs stay (2026-10-17, user: a button that
                // clears the teams without removing the rows)
                if (a.length > 2 && "all".equalsIgnoreCase(a[1]) && "none".equalsIgnoreCase(a[2])) {
                    SummonList.TEAMS.clear();
                    break;
                }
                String role = a.length > 2 ? SummonList.listedKey(a[1]) : null;
                int team = a.length > 2 ? BattleManager.teamIndex(a[2]) : -1;
                if (role == null || (team < 0 && !"none".equalsIgnoreCase(a[2]))) {
                    usage(ctx, "/mobbattle summon team <role> <team|none> | summon team all none", Teams.describeMessage());
                    return;
                }
                if (team < 0) {
                    SummonList.TEAMS.remove(role);
                } else {
                    SummonList.TEAMS.put(role, team);
                }
            }
            case "fill" -> {
                // summon fill <types> [each:<n>] [teams:<n>] [mod:..] [type:fighters|defend] [group:..] [add]
                int types, each = 1, teams = 0;
                String mod = PickLists.ALL, group = null;
                PickLists.Kind kind = null;
                boolean add = false;
                try {
                    types = Math.max(1, Math.min(SUMMON_FILL_MAX, Integer.parseInt(a.length > 1 ? a[1] : "")));
                    for (int i = 2; i < a.length; i++) {
                        String w = a[i], low = w.toLowerCase(java.util.Locale.ROOT);
                        if (low.startsWith("each:")) each = Math.max(1, Math.min(FxSettings.MAX_MOBS_LIMIT, Integer.parseInt(w.substring(5))));
                        else if (low.startsWith("teams:")) teams = Math.max(0, Math.min(14, Integer.parseInt(w.substring(6))));
                        else if (low.startsWith("mod:")) mod = w.substring(4).replace('+', ' ');
                        else if (low.startsWith("type:")) {
                            // An unknown type word used to fall back to fighters silently.
                            kind = PickLists.kindOf(w.substring(5));
                            if (kind == null) throw new NumberFormatException(w);
                        }
                        else if (low.startsWith("group:")) group = w.substring(6).replace('+', ' ');
                        else if (low.equals("add")) add = true;
                        else throw new NumberFormatException(w);
                    }
                } catch (NumberFormatException e) {
                    usage(ctx, "/mobbattle summon fill <types 1-" + SUMMON_FILL_MAX + "> [each:<n>] [teams:<n>] [mod:<name>|mod:hytale] [type:fighters|defend] [group:<group>] [add]");
                    return;
                }
                SummonFill.Request req = new SummonFill.Request(types, each, teams, mod, kind, group, add);
                SummonFill.Result res = SummonFill.pick(req);
                if (res.error() != null) {
                    reply(ctx, I18n.tr(switch (res.error()) {
                        case "teams" -> "cmd.summon.fill.teams";
                        case "types" -> "cmd.summon.fill.types";
                        case "list" -> "cmd.summon.fill.nolist";
                        default -> "cmd.summon.fill.none";
                    }).param("teams", teams), ERR);
                    return;
                }
                int n = SummonFill.apply(req, res);
                reply(ctx, I18n.tr("cmd.summon.fill.done").param("count", n).param("each", each), OK);
            }
            case "random" -> {
                try {
                    int min = Integer.parseInt(a[1]);
                    int max = Integer.parseInt(a[2]);
                    SummonList.setRandomRange(min, max);
                } catch (RuntimeException e) {
                    usage(ctx, "/mobbattle summon random <min> <max>");
                    return;
                }
            }
            case "atleastone" -> {
                Boolean on = a.length > 1 ? parseOnOff(a[1]) : null;
                if (on == null) {
                    usage(ctx, "/mobbattle summon atLeastOne true|false");
                    return;
                }
                SummonList.atLeastOne = on;
            }
            case "lists" -> {
                // Saved lists (SummonLists, 2026-10-16, user: save my list 1, 2 with counts, colors and teams).
                java.util.List<String> names = SummonLists.saved();
                reply(ctx, names.isEmpty() ? I18n.tr("cmd.summon.saved.empty")
                        : I18n.tr("cmd.summon.saved.list").param("names", String.join(", ", names)), OK);
                return;
            }
            case "save", "load", "delete" -> {
                if (a.length != 2) {
                    usage(ctx, "/mobbattle summon " + sub + " <name>");
                    return;
                }
                String err = switch (sub) {
                    case "save" -> SummonLists.save(a[1]);
                    case "load" -> SummonLists.load(a[1]);
                    default -> SummonLists.delete(a[1]);
                };
                if (err != null) {
                    reply(ctx, I18n.tr(err).param("name", a[1]), ERR);
                    return;
                }
                reply(ctx, I18n.tr("cmd.summon.saved.did" + sub).param("name", a[1]), OK);
                if (sub.equals("delete") || sub.equals("save")) return;
                // load: restoreLists already saved fx.properties; show the list it put back.
                reply(ctx, SummonList.describeMessage(), OK);
                return;
            }
            default -> {
                usage(ctx, "/mobbattle summon [list | add <role> [count|random] [team] | fill <types> [each:<n>] [teams:<n>] [mod:..] [type:..] [group:..] [add] | modify <role> [team] <count|random> | team <role> <color|none> | team all none | kit <role> <kit|none> | remove <role> | clear"
                        + " | random <min> <max> | atLeastOne true|false | lists | save <name> | load <name> | delete <name>]");
                return;
            }
        }
        FxSettings.save();
        reply(ctx, SummonList.describeMessage(), OK);
    }

    /** Most mob types summon fill puts in the list at once. */
    static final int SUMMON_FILL_MAX = 40;

    private static final String TEAMS_USAGE = "/mobbattle teams auto | start | clear | join <team> | leave | list"
            + " | add <name> <color> | color <team> <color> | rename <team> <name> | kit <team> <kit|none> | remove <team> | reset";

    /**
     * /mobbattle kit list | show <kit> | save <kit> [copied|look] | set <kit> <slot> <item|item2|...|-|none>
     * | remove <kit>: equipment kits (Kits). save takes the Equipment Wand's copied set (default) or the mob looked at.
     */
    private static void kitCommand(CommandContext ctx) {
        String[] a = argsAfter(ctx, "kit");
        String sub = a.length > 0 ? a[0].toLowerCase(java.util.Locale.ROOT) : "list";
        final String usage = "/mobbattle kit list | show <kit> | save <kit> [copied|look] | set <kit> <slot> <item|item2|-|none> | remove <kit>";
        switch (sub) {
            case "list" -> reply(ctx, Kits.listMessage(), OK);
            case "show" -> {
                Kits.Kit kit = a.length > 1 ? Kits.get(a[1]) : null;
                if (kit == null) {
                    usage(ctx, "/mobbattle kit show <kit>", Kits.listMessage());
                    return;
                }
                reply(ctx, I18n.tr("cmd.kit.show").param("kit", kit.name()).param("slots", kit.describe()), OK);
            }
            case "remove" -> {
                if (a.length < 2 || !Kits.remove(a[1])) {
                    usage(ctx, "/mobbattle kit remove <kit>", Kits.listMessage());
                    return;
                }
                FxSettings.save();
                reply(ctx, I18n.tr("cmd.kit.removed").param("kit", a[1]), OK);
            }
            case "set" -> {
                // set <kit> <slot> <options>: one slot's options ("|" between them, "-" empties, "none" = untouched).
                int slot = a.length > 3 ? java.util.Arrays.asList(EquipWand.SLOTS).indexOf(a[2].toLowerCase(java.util.Locale.ROOT)) : -1;
                if (a.length < 4 || !Kits.validName(a[1]) || slot < 0) {
                    usage(ctx, "/mobbattle kit set <kit> <" + String.join("|", EquipWand.SLOTS) + "> <item|item2|-|none>");
                    return;
                }
                java.util.List<String> opts = new java.util.ArrayList<>();
                if (!a[3].equalsIgnoreCase("none")) {
                    for (String o : a[3].split("\\|")) {
                        if (o.isBlank()) continue;
                        if (o.equals(Kits.EMPTY)) {
                            opts.add(Kits.EMPTY);
                            continue;
                        }
                        String id = ApplyCommand.findItem(o);
                        if (id == null) {
                            reply(ctx, I18n.tr("cmd.apply.unknownitem").param("value", o), ERR);
                            return;
                        }
                        opts.add(id);
                    }
                }
                Kits.Kit kit = Kits.get(a[1]);
                if (kit == null) kit = Kits.blank(a[1]);
                if (!Kits.put(Kits.withSlot(kit, slot, opts))) {
                    reply(ctx, I18n.tr("cmd.kit.full").param("max", Kits.MAX_KITS), ERR);
                    return;
                }
                FxSettings.save();
                reply(ctx, I18n.tr("cmd.kit.show").param("kit", a[1]).param("slots", Kits.get(a[1]).describe()), OK);
            }
            case "save" -> {
                if (a.length < 2 || !Kits.validName(a[1])) {
                    usage(ctx, "/mobbattle kit save <kit> [copied|look]", I18n.tr("cmd.kit.name"));
                    return;
                }
                if (!ctx.isPlayer()) {
                    reply(ctx, I18n.tr("cmd.teams.onlyplayer"), ERR);
                    return;
                }
                var ref = ctx.senderAsPlayerRef();
                if (ref == null || !ref.isValid()) return;
                boolean look = a.length > 2 && a[2].equalsIgnoreCase("look");
                var store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (player == null) return;
                    String[] set = look ? null : EquipWand.copied(player.getUuid());
                    if (set == null) {
                        var mob = ApplyCommand.lookedAt(ref, store);
                        if (mob == null || EquipWand.inventory(mob, store) == null) {
                            reply(ctx, I18n.tr("cmd.kit.nosource"), ERR);
                            return;
                        }
                        set = EquipWand.read(mob, store);
                    }
                    if (!Kits.put(Kits.fromSet(a[1], set))) {
                        reply(ctx, I18n.tr("cmd.kit.full").param("max", Kits.MAX_KITS), ERR);
                        return;
                    }
                    FxSettings.save();
                    reply(ctx, I18n.tr("cmd.kit.saved").param("kit", a[1]).param("slots", Kits.get(a[1]).describe()), OK);
                });
            }
            default -> usage(ctx, usage);
        }
    }

    /** /mobbattle army kit <kit>: the kit on every mob of the sender's army. */
    private static void armyKit(CommandContext ctx, String name) {
        Kits.Kit kit = Kits.get(name);
        if (kit == null) {
            usage(ctx, "/mobbattle army kit <kit>", Kits.listMessage());
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, I18n.tr("cmd.teams.onlyplayer"), ERR);
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) return;
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (player == null) return;
            int n = 0;
            for (var mob : Army.members(player.getUuid())) {
                if (mob.getStore() == store && Kits.apply(mob, kit, store)) n++;
            }
            reply(ctx, I18n.tr("cmd.kit.army").param("kit", kit.name()).param("count", n), OK);
        });
    }

    /** Team setup: list, add, color, rename, remove, reset. */
    private static boolean teamConfig(CommandContext ctx, String sub, String[] a) {
        Message colors = I18n.tr("cmd.note.fixed_colors").param("colors", String.join(", ", Teams.PALETTE.keySet()).toLowerCase(java.util.Locale.ROOT));
        switch (sub) {
            case "list" -> {
                reply(ctx, Teams.describeMessage(), OK);
                return true;
            }
            case "add" -> {
                String hex = a.length > 2 ? Teams.parseColor(a[2]) : null;
                if (hex == null || !a[1].matches("[A-Za-z0-9_]{1,16}")) {
                    usage(ctx, "/mobbattle teams add <name> <color>", Message.join(I18n.tr("cmd.note.team_name"), Message.raw(" "), colors));
                    return true;
                }
                int taken = Teams.withColor(hex, -1);
                if (taken >= 0) {
                    reply(ctx, I18n.tr("cmd.team.colortaken.add").param("team", Teams.name(taken)), ERR);
                    return true;
                }
                int slot = Teams.add(a[1], hex);
                if (slot == -1) {
                    reply(ctx, I18n.tr("cmd.team.exists").param("name", a[1]), ERR);
                    return true;
                }
                if (slot == -2) {
                    reply(ctx, I18n.tr("cmd.team.full").param("max", Teams.MAX), ERR);
                    return true;
                }
                reply(ctx, plus(I18n.tr("cmd.team.added").param("team", Teams.name(slot)).param("hex", hex), Teams.describeMessage()), hex);
            }
            case "color" -> {
                int slot = a.length > 2 ? Teams.find(a[1]) : -1;
                String hex = a.length > 2 ? Teams.parseColor(a[2]) : null;
                if (slot < 0 || hex == null) {
                    usage(ctx, "/mobbattle teams color <team> <color>", Message.join(colors, Message.raw(" "), Teams.describeMessage()));
                    return true;
                }
                int taken = Teams.withColor(hex, slot);
                if (taken >= 0) {
                    reply(ctx, I18n.tr("cmd.team.colortaken").param("team", Teams.name(taken)), ERR);
                    return true;
                }
                Teams.recolor(slot, hex);
                reply(ctx, I18n.tr("cmd.team.recolored").param("team", Teams.name(slot)).param("hex", hex), hex);
            }
            case "rename" -> {
                int slot = a.length == 3 ? Teams.find(a[1]) : -1;
                if (slot < 0 || !Teams.rename(slot, a[2])) {
                    usage(ctx, "/mobbattle teams rename <team> <new name>", Message.join(I18n.tr("cmd.note.team_name_unique"), Message.raw(" "), Teams.describeMessage()));
                    return true;
                }
                reply(ctx, I18n.tr("cmd.team.renamed").param("team", Teams.name(slot)), Teams.color(slot));
            }
            case "remove" -> {
                int slot = a.length > 1 ? Teams.find(a[1]) : -1;
                if (slot < 0) {
                    usage(ctx, "/mobbattle teams remove <team>", Teams.describeMessage());
                    return true;
                }
                if (Teams.count() <= 2) {
                    reply(ctx, I18n.tr("cmd.team.min"), ERR);
                    return true;
                }
                String name = Teams.name(slot);
                Teams.remove(slot);
                reply(ctx, plus(I18n.tr("cmd.team.removed").param("team", name), Teams.describeMessage()), OK);
            }
            case "kit" -> {
                // teams kit <team> <kit|none>: the equipment its mobs wear in team battles (taken off at the end).
                int slot = a.length > 2 ? Teams.find(a[1]) : -1;
                Kits.Kit kit = a.length > 2 ? Kits.get(a[2]) : null;
                if (slot < 0 || kit == null && !"none".equalsIgnoreCase(a[2])) {
                    usage(ctx, "/mobbattle teams kit <team> <kit|none>", Message.join(Teams.describeMessage(), Message.raw(" "), Kits.listMessage()));
                    return true;
                }
                Teams.setKit(slot, kit == null ? null : kit.name());
                reply(ctx, kit == null ? I18n.tr("cmd.kit.team.none").param("team", Teams.name(slot))
                        : I18n.tr("cmd.kit.team").param("team", Teams.name(slot)).param("kit", kit.name()), Teams.color(slot));
            }
            case "reset" -> {
                BattleManager.clearConfiguredTeams();
                Teams.resetDefaults();
                SummonList.TEAMS.values().removeIf(t -> !Teams.exists(t));
                reply(ctx, plus(I18n.tr("cmd.team.reset"), Teams.describeMessage()), OK);
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
            usage(ctx, "/mobbattle morph <model> | list [text] | reset");
            return;
        }
        if (sub.equalsIgnoreCase("list")) {
            var found = MorphWand.search(a.length > 1 ? a[1] : "", 40);
            reply(ctx, found.isEmpty() ? I18n.tr("cmd.morph.nomatch") : I18n.tr("cmd.morph.models").param("list", String.join(", ", found)), OK);
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, I18n.tr("cmd.morph.onlyplayer"), ERR);
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        var store = ref.getStore();
        if (sub.equalsIgnoreCase("reset")) {
            store.getExternalData().getWorld().execute(() ->
                    reply(ctx, I18n.tr("cmd.morph.restored").param("count", MorphWand.restoreAll(store)), OK));
            return;
        }
        String id = MorphWand.findModel(sub);
        if (id == null) {
            reply(ctx, I18n.tr("cmd.morph.unknown"), ERR);
            return;
        }
        store.getExternalData().getWorld().execute(() -> {
            var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (player != null) {
                MorphWand.setCopy(player.getUuid(), new MorphWand.Copy(id, 1f));
                reply(ctx, I18n.tr("cmd.morph.set").param("id", id), OK);
            }
        });
    }

    private static final String PRESET_USAGE = "/mobbattle preset [list | default|fight|effects|debug|cinematic|<name> | save|load|delete <name>]";

    /** /mobbattle preset [list | <name> | load|save|delete <name>]: whole looks at once (see Presets). */
    private static void preset(CommandContext ctx) {
        String[] a = argsAfter(ctx, "preset");
        String sub = a.length > 0 ? a[0].toLowerCase(java.util.Locale.ROOT) : "list";
        if (a.length == 0 || a.length == 1 && sub.equals("list")) {
            java.util.List<String> saved = Presets.saved();
            Message list = I18n.tr("cmd.presets.list").param("list", String.join(", ", Presets.BUILT_IN));
            reply(ctx, saved.isEmpty() ? list
                    : Message.join(list, Message.raw(" "), I18n.tr("cmd.presets.saved").param("list", String.join(", ", saved))), OK);
            return;
        }
        switch (sub) {
            case "load" -> {
                if (a.length != 2) {
                    usage(ctx, PRESET_USAGE);
                    return;
                }
                applyPreset(ctx, a[1]);
            }
            case "save" -> {
                if (a.length != 2) {
                    usage(ctx, PRESET_USAGE);
                    return;
                }
                String err = Presets.save(a[1]);
                if (err != null) {
                    reply(ctx, err, ERR);
                    return;
                }
                reply(ctx, I18n.tr("cmd.preset.saved").param("name", a[1]), OK);
            }
            case "delete" -> {
                if (a.length != 2) {
                    usage(ctx, PRESET_USAGE);
                    return;
                }
                String err = Presets.delete(a[1]);
                if (err != null) {
                    reply(ctx, err, ERR);
                    return;
                }
                reply(ctx, I18n.tr("cmd.preset.deleted").param("name", a[1]), OK);
            }
            default -> {
                if (a.length != 1) {
                    usage(ctx, PRESET_USAGE);
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
        reply(ctx, I18n.tr("cmd.preset.applied").param("name", name), OK);
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
            SlowMo.stopAll();
            Scoreboard.clearAll();
            DuelCard.clearAll();
            FocusCard.clearAll();
            BattleWatch.exitAll();
            var sender = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
            FightFx.clearAreas(sender != null && sender.isValid() ? sender.getStore().getExternalData().getWorld() : null);
            java.util.List<Message> parts = new java.util.ArrayList<>();
            parts.add(I18n.tr("cmd.stop.battles").param("count", BattleManager.stopAll()));
            parts.add(I18n.tr("cmd.stop.countdowns").param("count", countdowns));
            if (shots > 0) parts.add(I18n.tr("cmd.stop.shots").param("count", shots));
            if (all) {
                parts.add(I18n.tr("cmd.stop.teams").param("count", BattleManager.clearAllDrafts()));
                parts.add(I18n.tr("cmd.stop.armies").param("count", Army.clearAll()));
                parts.add(I18n.tr("cmd.stop.buffs").param("count", Buffs.clearEverything()));
                var ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
                if (ref != null && ref.isValid()) {
                    parts.add(I18n.tr("cmd.stop.morphs").param("count", MorphWand.restoreAll(ref.getStore())));
                }
            }
            java.util.List<Message> joined = new java.util.ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) joined.add(Message.raw(", "));
                joined.add(parts.get(i));
            }
            joined.add(Message.raw("."));
            reply(ctx, Message.join(joined.toArray(new Message[0])), OK);
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
            reply(ctx, I18n.tr("cmd.teams.cleared.all").param("count", BattleManager.clearAllDrafts()), OK);
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, I18n.tr("cmd.teams.onlyplayer"), ERR);
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
                    // A type listed in several teams (summon entries) is dealt over them in turn.
                    java.util.Map<String, Integer> dealt = new java.util.HashMap<>();
                    for (var npc : around) {
                        String type = ChaosWand.nameOf(npc, store);
                        java.util.List<Integer> rules = SummonList.teamsOf(type);
                        Integer team = rules.isEmpty() ? null : rules.get((dealt.merge(type, 1, Integer::sum) - 1) % rules.size());
                        if (team != null) {
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
                    player.sendMessage((ruled > 0
                            ? I18n.tr("cmd.teams.auto.rules").param("ruled", ruled)
                            : I18n.tr("cmd.teams.auto.bytype")).param("count", n).param("radius", FxSettings.radius)
                            .color(n > 0 ? OK : ERR));
                }
                case "start" -> TeamActionInteraction.start(player, ref, BattleManager.draft(player), store);
                case "join" -> PlayerFighters.joinTeam(player, ref, a.length > 1 ? a[1] : null, store);
                case "leave" -> PlayerFighters.leaveTeam(player, ref);
                case "clear" -> player.sendMessage(I18n.tr("cmd.teams.cleared").param("count", BattleManager.clearDraftAndFx(player)).color(OK));
                default -> player.sendMessage(I18n.tr("cmd.usage").param("usage", TEAMS_USAGE).color(ERR));
            }
        });
    }

    private static void giveWands(CommandContext ctx) {
        if (argsAfter(ctx, "give").length != 0) {
            usage(ctx, "/mobbattle give");
            return;
        }
        if (!ctx.isPlayer()) {
            reply(ctx, I18n.tr("cmd.give.onlyplayer"), ERR);
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
            Message got = I18n.tr("cmd.give.received").param("received", received).param("total", WAND_IDS.length);
            reply(ctx, missing.isEmpty() ? got
                    : Message.join(got, Message.raw(" "), I18n.tr("cmd.give.nospace").param("list", String.join(", ", missing))),
                    missing.isEmpty() ? OK : ERR);
        });
    }

    private static void spawnAtSender(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            reply(ctx, I18n.tr("cmd.summon.onlyplayer"), ERR);
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

    private static void reply(CommandContext ctx, Message msg, String color) {
        ctx.sendMessage(msg.color(color));
    }

    /** "Usage: {syntax}": the syntax stays as typed, only the word is translated. */
    private static void usage(CommandContext ctx, String syntax) {
        reply(ctx, I18n.tr("cmd.usage").param("usage", syntax), ERR);
    }

    private static void usage(CommandContext ctx, String syntax, Message note) {
        reply(ctx, Message.join(I18n.tr("cmd.usage").param("usage", syntax), Message.raw("  "), note), ERR);
    }

    private static void usagePlayer(CommandContext ctx, String syntax) {
        reply(ctx, I18n.tr("cmd.usage.player").param("usage", syntax), ERR);
    }

    /** A translated message followed by plain text from a helper that still returns a String. */
    private static Message plus(Message msg, String extra) {
        return Message.join(msg, Message.raw(" " + extra));
    }

    private static Message plus(Message msg, Message extra) {
        return Message.join(msg, Message.raw(" "), extra);
    }

    private static Message defaultNote(String value) {
        return I18n.tr("cmd.note.default").param("value", value);
    }
}
