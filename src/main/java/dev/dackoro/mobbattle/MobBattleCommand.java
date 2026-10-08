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
            "MobBattle_KillWand", "MobBattle_EffectWand", "MobBattle_CleanseWand"
    };

    private static final String USAGE = "Usage: /mobbattle show | title | wintitle | style | laser | color | radius | area"
            + " | height | maxmobs | teams | teamfx | summon | effect | morph | give  (see the README)";

    /**
     * One word of the command tree. Every node is a real subcommand so the chat suggests the next word;
     * all of them run the same parser over the raw input, and free values (numbers, IDs, colors) are
     * allowed as extra arguments ("/mobbattle radius 20").
     */
    private static final class Sub extends CommandBase {
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

        @Override
        protected void executeSync(CommandContext ctx) {
            dispatch(ctx);
        }
    }

    private static Sub[] words(String description, String... names) {
        Sub[] out = new Sub[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = new Sub(names[i], description);
        }
        return out;
    }

    public MobBattleCommand() {
        super("mobbattle", "Mob Battle: wands that make mobs fight. Settings, teams, summon list and morphs.");
        setAllowsExtraArguments(true);
        addSubCommand(new Sub("show", "Show the current settings"));
        Sub give = new Sub("give", "Receive one of each of the nine Mob Battle wands");
        addSubCommand(give);
        addSubCommand(new Sub("title", "Fight-start title on/off", words("Fight-start title", "on", "off")));
        addSubCommand(new Sub("wintitle", "Winner title on/off", words("Winner title", "on", "off")));
        addSubCommand(new Sub("style", "Title style", words("Title style", "default", "major", "goblinbreach", "voideviction", "random")));
        addSubCommand(new Sub("laser", "Laser between fighters on/off", words("Laser", "on", "off")));
        addSubCommand(new Sub("color", "Laser/area color: #RRGGBB or random", words("Laser color", "random")));
        addSubCommand(new Sub("radius", "Chaos Wand area radius in blocks (" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ")"));
        addSubCommand(new Sub("isolation", "Battle mobs only fight their opponents; outside mobs can't hurt them",
                new Sub("on", "Isolated battles (default)"), new Sub("off", "Outside mobs and battle mobs can hurt each other")));
        java.util.List<String> areaColors = new java.util.ArrayList<>();
        Teams.PALETTE.keySet().forEach(n -> areaColors.add(n.toLowerCase(java.util.Locale.ROOT)));
        areaColors.add("random");
        addSubCommand(new Sub("areacolor", "Area color from the palette, or random",
                words("Area color", areaColors.toArray(new String[0]))));
        addSubCommand(new Sub("area", "How the area is drawn", words("Area style", "floor", "zone", "glow", "both", "off")));
        addSubCommand(new Sub("height", "Beam / wall height in blocks (3-128)"));
        addSubCommand(new Sub("maxmobs", "Max mobs per Chaos Wand click and per summon (2-200)"));
        addSubCommand(new Sub("effect", "Effect Wand effect: any effect ID, e.g. Burn or Potion_Morph_Frog"));
        addSubCommand(new Sub("teamfx", "Team look: both, tint, mist, off, or time <seconds|infinite>",
                new Sub("both", "Tint + mist"), new Sub("tint", "Color the whole mob"),
                new Sub("mist", "Mist of the team color"), new Sub("off", "No team look"),
                new Sub("time", "How long the look stays: seconds (1-600) or infinite", words("Whole battle", "infinite"))));
        addSubCommand(new Sub("teams", "Team battles and custom teams",
                new Sub("auto", "Put mobs in range into teams by the team rules"),
                new Sub("start", "Start the team battle"),
                new Sub("clear", "Empty your teams"),
                new Sub("list", "Show the teams"),
                new Sub("add", "Add a team: <name> <palette color>"),
                new Sub("color", "Recolor a team: <team> <palette color>"),
                new Sub("rename", "Rename a team: <team> <new name>"),
                new Sub("remove", "Remove a team: <team>"),
                new Sub("reset", "Back to the 4 default teams")));
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

        switch (key) {
            case "give" -> {
                giveWands(ctx);
                return;
            }
            case "show" -> {
                reply(ctx, FxSettings.describe(), OK);
                return;
            }
            case "laser" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle laser on|off", ERR);
                    return;
                }
                FxSettings.laserEnabled = on;
            }
            case "color" -> {
                if (val == null || !(FxSettings.RANDOM.equalsIgnoreCase(val) || FxSettings.isHexColor(val))) {
                    reply(ctx, "Usage: /mobbattle color #RRGGBB|random  (e.g. #FFD23E)", ERR);
                    return;
                }
                FxSettings.laserColor = val.toLowerCase().equals(FxSettings.RANDOM) ? FxSettings.RANDOM : val.toUpperCase();
            }
            case "title" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle title on|off", ERR);
                    return;
                }
                FxSettings.titleEnabled = on;
            }
            case "isolation" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle isolation on|off", ERR);
                    return;
                }
                FxSettings.battleIsolation = on;
            }
            case "wintitle" -> {
                Boolean on = parseOnOff(val);
                if (on == null) {
                    reply(ctx, "Usage: /mobbattle wintitle on|off", ERR);
                    return;
                }
                FxSettings.winTitleEnabled = on;
            }
            case "style" -> {
                if (val != null && FxSettings.RANDOM.equalsIgnoreCase(val)) {
                    FxSettings.titleStyle = FxSettings.RANDOM;
                } else {
                    EventTitleStyle style = val != null ? FxSettings.parseStyle(val) : null;
                    if (style == null) {
                        String names = Arrays.stream(EventTitleStyle.VALUES).map(Enum::name).collect(Collectors.joining(", "));
                        reply(ctx, "Usage: /mobbattle style <" + names + ", random>", ERR);
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
                    reply(ctx, "Usage: /mobbattle radius <" + FxSettings.MIN_RADIUS + "-" + FxSettings.MAX_RADIUS + ">", ERR);
                    return;
                }
                FxSettings.radius = FxSettings.clampRadius(r);
            }
            case "areacolor" -> {
                String color = FxSettings.parseAreaColor(val);
                if (color == null) {
                    reply(ctx, "Usage: /mobbattle areacolor <color>|random. Colors: "
                            + String.join(", ", Teams.PALETTE.keySet()).toLowerCase(java.util.Locale.ROOT) + ".", ERR);
                    return;
                }
                FxSettings.areaColor = color;
            }
            case "area" -> {
                if (!FxSettings.isAreaStyle(val)) {
                    reply(ctx, "Usage: /mobbattle area floor|zone|glow|both|off", ERR);
                    return;
                }
                FxSettings.areaStyle = FxSettings.parseAreaStyle(val);
            }
            case "teamfx" -> {
                if ("time".equalsIgnoreCase(val)) {
                    String[] a = argsAfter(ctx, "time");
                    String t = a.length > 0 ? a[0].toLowerCase() : "";
                    int n;
                    if (t.equals("infinite") || t.equals("inf") || t.equals("battle")) {
                        n = 0;
                    } else {
                        try {
                            n = Integer.parseInt(t);
                        } catch (NumberFormatException e) {
                            reply(ctx, "Usage: /mobbattle teamfx time infinite|<1-" + FxSettings.TEAM_FX_MAX_SECONDS
                                    + "> (infinite = whole battle). No team look at all: /mobbattle teamfx off", ERR);
                            return;
                        }
                        if (n <= 0) {
                            reply(ctx, "Use a positive duration or infinite. For no team look use /mobbattle teamfx off.", ERR);
                            return;
                        }
                    }
                    FxSettings.teamFxSeconds = Math.max(0, Math.min(FxSettings.TEAM_FX_MAX_SECONDS, n));
                } else if (!TeamFx.isMode(val)) {
                    reply(ctx, "Usage: /mobbattle teamfx both|tint|mist|off  or  /mobbattle teamfx time <seconds>", ERR);
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
            case "effect" -> {
                var found = EffectWand.find(val);
                if (found == null) {
                    reply(ctx, "Unknown effect. Examples: " + String.join(", ", EffectWand.PRESETS), ERR);
                    return;
                }
                FxSettings.effect = found.getId();
            }
            case "maxmobs" -> {
                int n;
                try {
                    n = Integer.parseInt(val == null ? "" : val);
                } catch (NumberFormatException e) {
                    reply(ctx, "Usage: /mobbattle maxmobs <2-" + FxSettings.MAX_MOBS_LIMIT + ">", ERR);
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
                    reply(ctx, "Usage: /mobbattle height <" + FxSettings.MIN_HEIGHT + "-" + FxSettings.MAX_HEIGHT + ">", ERR);
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
        if (key.equals("radius") || key.equals("area") || key.equals("areacolor") || key.equals("height")) {
            previewArea(ctx);
        }
        reply(ctx, "Saved: " + FxSettings.describe(), OK);
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
                String role = SummonList.ENTRIES.keySet().stream()
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
                    reply(ctx, "Usage: /mobbattle summon atleastone true|false", ERR);
                    return;
                }
                SummonList.atLeastOne = on;
            }
            default -> {
                reply(ctx, "Usage: /mobbattle summon [list | add <role> [count|random] [team] | modify <role> <count|random> | team <role> <color|none> | remove <role> | clear"
                        + " | random <min> <max> | atleastone true|false]", ERR);
                return;
            }
        }
        FxSettings.save();
        reply(ctx, SummonList.describe(), OK);
    }

    private static final String TEAMS_USAGE = "Usage: /mobbattle teams auto | start | clear | list"
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
                    FightFx.area(ref, new org.joml.Vector3d(t.getPosition()), FxSettings.radius, store);
                    player.sendMessage(com.hypixel.hytale.server.core.Message.raw(n + " mobs within " + FxSettings.radius
                            + " blocks put in teams. Start with /mobbattle teams start or the Team Wand.").color(OK));
                }
                case "start" -> TeamActionInteraction.start(player, ref, BattleManager.draft(player), store);
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
                SummonList.spawn(store, new org.joml.Vector3d(t.getPosition()), player);
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
