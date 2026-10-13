package dev.dackoro.mobbattle;

import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every fx.properties setting the settings panel (ConfigPage) edits: its key, how it is shown and what it accepts.
 * The panel keeps a draft of these keys and saves them in one go through {@link FxSettings#applyAll}, the same path
 * as presets. Team and summon lists (teams.list, summon.list, summon.teams) and the permission mode are not fields:
 * the lists have their own rows, the permission mode is an admin action.
 * <p>
 * A new setting must also be added here (the panel logs keys that have no field, see {@link #missingKeys}).
 */
final class ConfigSchema {

    enum Type { TOGGLE, NUMBER, CHOICE, TEXT }

    record Opt(String value, String label) {
    }

    static final class Field {
        final String key;
        final String label;
        final String tip;
        final Type type;
        String hint = "";
        int min;
        int max;
        Supplier<List<Opt>> options = List::of;
        /** Toggle values as stored ("true"/"false", or "on"/"off" for per-kind overrides). */
        String on = "true";
        String off = "false";
        /** TEXT: canonical value or null if not accepted; "" is always accepted when {@link #blankOk}. */
        Function<String, String> check = v -> v;
        boolean blankOk;
        /** Only admins can change it (debugLog). */
        boolean admin;
        int index;

        Field(String key, String label, String tip, Type type) {
            this.key = key;
            this.label = label;
            this.tip = tip;
            this.type = type;
        }

        Field hint(String text) {
            hint = text;
            return this;
        }

        /** The value as the panel stores it, or null when {@code raw} is not one this field takes. */
        String canonical(String raw) {
            if (raw == null) return null;
            String v = raw.trim();
            return switch (type) {
                case TOGGLE -> v.equalsIgnoreCase("true") || v.equalsIgnoreCase(on) ? on
                        : v.equalsIgnoreCase("false") || v.equalsIgnoreCase(off) ? off : null;
                case NUMBER -> {
                    try {
                        double d = Double.parseDouble(v);
                        if (!Double.isFinite(d)) yield null;
                        yield String.valueOf((int) Math.max(min, Math.min(max, Math.round(d))));
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
                case CHOICE -> {
                    for (Opt o : options.get()) if (o.value().equals(v)) yield v;
                    for (Opt o : options.get()) if (o.value().equalsIgnoreCase(v)) yield o.value();
                    yield null;
                }
                case TEXT -> v.isEmpty() ? (blankOk ? "" : null) : check.apply(v);
            };
        }
    }

    sealed interface Item permits Section, Note, FieldItem {
    }

    record Section(String title) implements Item {
    }

    record Note(String text) implements Item {
    }

    record FieldItem(Field field) implements Item {
    }

    /** {@code custom}: extra rows ConfigPage adds after the fields (lists, presets, tools, admin). */
    record Page(String title, String hint, List<Item> items, String custom, boolean adminOnly) {
    }

    static final List<Page> PAGES;
    static final List<Field> FIELDS = new ArrayList<>();

    private ConfigSchema() {
    }

    // ---- option lists ----

    private static List<Opt> opts(String... values) {
        List<Opt> out = new ArrayList<>();
        for (String v : values) out.add(new Opt(v, label(v)));
        return out;
    }

    /** "discBorder" -> "Disc border", "allvsone" stays readable. */
    static String label(String v) {
        if (v.isEmpty()) return v;
        if (v.equalsIgnoreCase("allvsone")) return "All vs one";
        if (v.equalsIgnoreCase("lasthit")) return "Last hit";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(v.charAt(i - 1))) b.append(' ').append(Character.toLowerCase(c));
            else b.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        return b.toString().replace('_', ' ');
    }

    static List<Opt> palette() {
        List<Opt> out = new ArrayList<>();
        for (String c : Teams.PALETTE.keySet()) out.add(new Opt(c, c));
        return out;
    }

    private static List<Opt> with(List<Opt> head, List<Opt> tail) {
        List<Opt> out = new ArrayList<>(head);
        out.addAll(tail);
        return out;
    }

    private static List<Opt> musicTracks() {
        Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            for (Object k : MusicContainer.getAssetMap().getAssetMap().keySet()) ids.add(String.valueOf(k));
        } catch (RuntimeException ignored) {
            // assets not loaded yet
        }
        ids.addAll(BattleMusic.SUGGESTED);
        ids.add(FxSettings.musicTrack);
        return categorized(MusicContainer.getAssetMap(), ids, null);
    }

    // ---- categories (long lists: "Category - Name [mod]", sorted, searchable) ----

    /** The folder right under {@code root} in an asset path (Server/Entity/Effects/Potion/x.json -> Potion), or null. */
    static String folderAfter(java.nio.file.Path path, String root) {
        if (path == null) return null;
        for (int i = 0; i < path.getNameCount() - 2; i++) {
            if (path.getName(i).toString().equals(root)) return path.getName(i + 1).toString();
        }
        return null;
    }

    /** "Potion - Morph Frog", plus " [Mod]" when it comes from a mod's pack. */
    static String categoryLabel(String category, String id, String pack) {
        String name = id.replace('_', ' ');
        if (category != null && !category.isBlank()) {
            String c = category.replace('_', ' ');
            // "Potion - Potion Morph Frog" reads twice: drop the repeated start.
            if (name.toLowerCase(Locale.ROOT).startsWith(c.toLowerCase(Locale.ROOT) + " ")) name = name.substring(c.length() + 1);
            name = c + " - " + name;
        }
        if (pack != null && !pack.isBlank() && !pack.startsWith("Hytale")) {
            int colon = pack.indexOf(':');
            name += " [" + (colon >= 0 ? pack.substring(colon + 1) : pack) + "]";
        }
        return name;
    }

    /**
     * Options for asset ids, labelled with the folder they live in (under {@code root}, or their parent folder when
     * null) and their mod, sorted by that label. Unknown ids (not loaded) keep their plain id.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<Opt> categorized(com.hypixel.hytale.assetstore.AssetMap map, java.util.Collection<String> ids, String root) {
        List<Opt> out = new ArrayList<>();
        for (String id : ids) {
            String category = null, pack = null;
            try {
                java.nio.file.Path path = map.getPath(id);
                if (path != null) {
                    category = root != null ? folderAfter(path, root)
                            : path.getParent() != null && path.getParent().getFileName() != null ? path.getParent().getFileName().toString() : null;
                }
                pack = map.getAssetPack(id);
            } catch (RuntimeException ignored) {
            }
            out.add(new Opt(id, categoryLabel(category, id, pack)));
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label(), b.label()));
        return out;
    }

    private static List<Opt> effects() {
        Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            ids.addAll(EntityEffect.getAssetMap().getAssetMap().keySet());
        } catch (RuntimeException ignored) {
        }
        // Look twins are LookKeeper's internal copies, not choices.
        ids.removeIf(id -> id.startsWith("MobBattle_LookTwin_"));
        Collections.addAll(ids, EffectWand.PRESETS);
        ids.add(FxSettings.effect);
        return categorized(EntityEffect.getAssetMap(), ids, "Effects");
    }

    private static List<Opt> titleStyles() {
        List<Opt> out = new ArrayList<>();
        for (EventTitleStyle s : EventTitleStyle.VALUES) out.add(new Opt(s.name(), label(s.name())));
        out.add(new Opt(FxSettings.RANDOM, "Random"));
        return out;
    }

    /** Palette colors (free colors caused problems; the palette is the supported set) or random. */
    private static List<Opt> laserColors() {
        List<Opt> out = new ArrayList<>();
        out.add(new Opt(FxSettings.RANDOM, "Random"));
        for (var e : Teams.PALETTE.entrySet()) out.add(new Opt(e.getValue(), e.getKey()));
        return out;
    }

    static String kindLabel(BattleManager.Kind k) {
        return switch (k) {
            case DUEL -> "Duel";
            case ALL_VS_ONE -> "All vs one";
            case ROYALE -> "Royale";
            case TEAMS -> "Teams";
        };
    }

    // ---- field builders ----

    private static Field add(Field f) {
        f.index = FIELDS.size();
        FIELDS.add(f);
        return f;
    }

    private static Item toggle(String key, String label, String tip) {
        return new FieldItem(add(new Field(key, label, tip, Type.TOGGLE)));
    }

    private static Item number(String key, String label, int min, int max, String hint, String tip) {
        Field f = new Field(key, label, tip, Type.NUMBER);
        f.min = min;
        f.max = max;
        f.hint = hint;
        return new FieldItem(add(f));
    }

    private static Item choice(String key, String label, String tip, Supplier<List<Opt>> options) {
        Field f = new Field(key, label, tip, Type.CHOICE);
        f.options = options;
        return new FieldItem(add(f));
    }

    private static Item choice(String key, String label, String tip, String... values) {
        List<Opt> list = opts(values);
        return choice(key, label, tip, () -> list);
    }

    /** passiveWinIgnore: free text, a comma list of role ids (* = any ending), empty = none. */
    private static Item passiveIgnore() {
        Field f = new Field("battle.passivewin.ignore", "Never ends against",
                "Roles that keep a battle going while one is alive, e.g. a training dummy you test a mob against (default Test_Dummy*).",
                Type.TEXT);
        f.blankOk = true;
        f.check = FxSettings::parsePassiveWinIgnore;
        f.hint = "role ids, comma separated, * = any ending; empty = none";
        return new FieldItem(add(f));
    }

    /** A per-kind presentation override: empty / "Global" = follow the global value. */
    private static Item override(BattleManager.Kind kind, KindFx.Opt opt, String label) {
        String key = KindFx.propKey(kind, opt);
        String tip = "Only for " + kindLabel(kind).toLowerCase(Locale.ROOT) + " battles. Global = use the value from the Presentation/Cameras tabs.";
        switch (opt) {
            case COUNTDOWN, CAMERA_TIME, CAMERA_SHOTS, END_CAMERA_TIME -> {
                Field f = new Field(key, label, tip, Type.TEXT);
                f.blankOk = true;
                f.check = v -> KindFx.parse(opt, v);
                f.hint = "empty = global (" + KindFx.usage(opt).replace("<", "").replace(">", "") + ")";
                return new FieldItem(add(f));
            }
            default -> {
                Field f = new Field(key, label, tip, Type.CHOICE);
                f.options = () -> {
                    List<Opt> out = new ArrayList<>();
                    out.add(new Opt("", "Global (" + label(KindFx.global(opt)) + ")"));
                    switch (opt) {
                        case CAMERA_STYLE -> out.addAll(opts(KindFx.CAMERA_STYLES));
                        case END_CAMERA -> out.addAll(opts(KindFx.END_CAMERAS));
                        case AREA_STYLE -> out.addAll(opts(FxSettings.AREA_STYLES));
                        case MUSIC -> {
                            out.addAll(opts("on", "off"));
                            out.addAll(musicTracks());
                        }
                        default -> out.addAll(opts("on", "off"));
                    }
                    return out;
                };
                return new FieldItem(add(f));
            }
        }
    }

    private static Item section(String title) {
        return new Section(title);
    }

    private static Item note(String text) {
        return new Note(text);
    }

    // ---- the pages ----

    static {
        List<Page> pages = new ArrayList<>();

        pages.add(new Page("Presentation", "How fights start and end: countdown, titles, music, laser and runes. "
                + "Each battle kind can override these on its own tab.", List.of(
                section("Start"),
                number("fight.countdown", "Countdown", 0, FxSettings.MAX_COUNTDOWN, "seconds, 0 = start at once",
                        "3, 2, 1 titles before a fight starts."),
                toggle("title.enabled", "Start title", "Big title when a fight starts (FIGHT!, BATTLE ROYALE!)."),
                toggle("wintitle.enabled", "Winner title", "Big title when a battle ends (X WINS!)."),
                choice("title.style", "Title style", "Look of the big titles.", ConfigSchema::titleStyles),
                choice("title.scope", "Who sees the titles", "owner = who started it, nearby = 64 blocks around, world = the whole world.",
                        FxSettings.TITLE_SCOPES),
                section("Music and effects"),
                toggle("battle.music", "Battle music", "Music from the countdown to the end of the battle."),
                choice("battle.music.track", "Music track", "Track used when battle music is on.", ConfigSchema::musicTracks),
                toggle("laser.enabled", "Fighter laser", "A laser between the fighters when a fight starts."),
                choice("laser.color", "Laser color", "A palette color, or random (a new one each fight).", ConfigSchema::laserColors),
                toggle("fx.runes", "Summoning runes", "Runes on the ground during the countdown and rage marks at the start.")
        ), null, false));

        pages.add(new Page("Cameras", "Intro camera before the fight, end camera on the winner, watch mode and the duel card.", List.of(
                section("Intro camera"),
                toggle("fight.camera", "Intro camera", "Camera shots of the fighters during the countdown."),
                choice("camera.style", "Camera style", "auto = duel shots for duels, focus for all vs one, sweep for teams, orbit for royale.",
                        KindFx.CAMERA_STYLES),
                number("camera.time", "Camera time", 0, KindFx.MAX_CAMERA_TIME, "seconds, 0 = as long as the countdown",
                        "How long the intro camera films."),
                number("camera.shots", "Showcase shots", 1, FxSettings.MAX_MOBS_LIMIT, "fighters shown (up to Max mobs)",
                        "How many fighters the showcase style shows."),
                choice("camera.scope", "Who sees the cameras", "owner, players nearby (64 blocks) or the whole world. Fighters never get an intro.",
                        FxSettings.TITLE_SCOPES),
                section("End camera"),
                choice("camera.end", "End camera", "Film the winner or who landed the last hit when a battle ends.", KindFx.END_CAMERAS),
                number("camera.end.time", "End camera time", 1, KindFx.MAX_END_CAMERA_TIME, "seconds", "How long the end camera films."),
                section("Watch & HUD"),
                toggle("camera.autowatch", "Auto watch", "Camera viewers start watching (spectator camera) as soon as a battle starts."),
                toggle("battle.duelCard", "Duel card", "Card with both fighters' stats during 1 vs 1 duels."),
                note("Crouch skips a running camera. /mobbattle watch spectates a running battle (right click = next fighter, left = previous, Q = exit).")
        ), null, false));

        pages.add(new Page("Battles", "What every battle kind shares. Each kind has its own tab for its presentation, rules, arena and timer.", List.of(
                section("Every battle"),
                toggle("battle.pvp", "Battle PvP", "Enemy players of the same battle can hurt each other even where the world's PvP is off."),
                toggle("battle.unstick", "Unstick stalled fighters", "A mob stuck on another level than its target (10 s, fliers 20 s) is moved next to it."),
                number("battle.standoff", "Stand-off shove", 0, FxSettings.MAX_STANDOFF_SECONDS, "seconds, 0 = never",
                        "A mob on its target's level that this long gets no closer, hits nothing and isn't hit is shoved toward it (default 15)."),
                number("battle.passivewin", "Win against passive mobs", 0, FxSettings.MAX_PASSIVE_WIN_SECONDS, "seconds, 0 = never",
                        "When only one side can still fight back, it wins after this long without a hit (nobody can fight: a draw). An alert shows bottom left (default 15)."),
                passiveIgnore(),
                section("Arenas & timers"),
                choice("battle.timer.style", "Timer box", "custom = Mob Battle's timer box, portal = the game's portal timer.", "custom", "portal"),
                number("battle.arena.size", "Arena size (duels, teams)", 0, FxSettings.MAX_ARENA,
                        "blocks, 0 = auto (around the fighters), else " + FxSettings.MIN_ARENA + "-" + FxSettings.MAX_ARENA,
                        "Radius of duel and team arenas."),
                number("battle.shrink.to", "Closing arena ends at", FxSettings.MIN_SHRINK_TO, FxSettings.MAX_ARENA, "blocks",
                        "Final radius of a closing arena."),
                number("battle.shrink.time", "Closing time without timer", 10, FxSettings.MAX_TIMER, "seconds",
                        "How long a closing arena takes when its battle has no timer."),
                choice("battle.tiebreak", "Tie break", "When the Judge by score is even at time up: damage dealt, kills, a coin toss or a draw. If it is the same as Judge by (damage or kills), the other one is used.",
                        "damage", "kills", "random", "draw")
        ), null, false));

        // One tab per battle kind: its presentation overrides, rules, arena and timer together (user, 2026-10-12:
        // a royale took three tabs to set up).
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            List<Item> tab = new ArrayList<>();
            tab.add(note("Presentation and cameras: Global = the value on the Presentation / Cameras tabs (number fields: leave empty)."));
            tab.add(section("Presentation"));
            tab.add(override(k, KindFx.Opt.COUNTDOWN, "Countdown"));
            tab.add(override(k, KindFx.Opt.START_TITLE, "Start title"));
            tab.add(override(k, KindFx.Opt.WIN_TITLE, "Winner title"));
            tab.add(override(k, KindFx.Opt.MUSIC, "Music"));
            tab.add(override(k, KindFx.Opt.LASER, "Fighter laser"));
            tab.add(override(k, KindFx.Opt.RUNES, "Runes"));
            tab.add(override(k, KindFx.Opt.AREA_STYLE, "Area look"));
            tab.add(section("Cameras"));
            tab.add(override(k, KindFx.Opt.CAMERA, "Intro camera"));
            tab.add(override(k, KindFx.Opt.CAMERA_STYLE, "Camera style"));
            tab.add(override(k, KindFx.Opt.CAMERA_TIME, "Camera time"));
            tab.add(override(k, KindFx.Opt.CAMERA_SHOTS, "Showcase shots"));
            tab.add(override(k, KindFx.Opt.END_CAMERA, "End camera"));
            tab.add(override(k, KindFx.Opt.END_CAMERA_TIME, "End camera time"));
            tab.add(override(k, KindFx.Opt.AUTO_WATCH, "Auto watch"));
            tab.add(section("Rules"));
            if (k == BattleManager.Kind.DUEL) {
                tab.add(choice("duel.mode", "Battle Wand duels", "exclusive = a new duel replaces the old pair; accumulate = connected duels merge.",
                        "exclusive", "accumulate"));
            }
            tab.add(toggle("battle.isolation." + k.key, "Isolated", "Its mobs only fight their opponents and outside NPCs can't hurt them."));
            tab.add(toggle("battle.selfdefense." + k.key, "Self defense",
                    "A mob hit by an enemy that isn't its target turns on it (keeps it at least 3 s), like Army Wand mobs."));
            if (k == BattleManager.Kind.ALL_VS_ONE || k == BattleManager.Kind.TEAMS) {
                tab.add(toggle("battle.friendlyfire." + k.key, "Friendly fire", "Hits between allies hurt (allies still never target each other)."));
            }
            tab.add(toggle("battle.players." + k.key, "Players can fight", switch (k) {
                case DUEL -> "Crouch + Battle Wand puts you in a duel.";
                case ALL_VS_ONE -> "/mobbattle chaos me: every mob around attacks you.";
                case ROYALE -> "Players can be fighters in a royale.";
                case TEAMS -> "/mobbattle teams join <team> puts you in a team.";
            }));
            tab.add(section("Arena & timer"));
            tab.add(choice("battle.arena." + k.key, "Arena", "on = drawn + wall, show = only drawn, wall = invisible wall, off = none.",
                    "on", "show", "wall", "off"));
            tab.add(number("battle.timer." + k.key, "Timer", 0, FxSettings.MAX_TIMER, "seconds, 0 = no time limit",
                    "Fight time limit with a timer box."));
            tab.add(choice("battle.timeup." + k.key, "At time up",
                    "draw, kill everyone, continue, health (the best score by Judge by wins) or judge (same, and the losers die). An even score goes to Tie break (Battles tab).",
                    "draw", "kill", "continue", "health", "judge"));
            tab.add(choice("battle.judgeby." + k.key, "Judge by",
                    "What At time up health/judge scores: health % left (fallen = 0%), share of the damage or of the kills (per fighter, so a bigger side doesn't win for size), or mix (the three averaged). An even score goes to Tie break (Battles tab).",
                    FxSettings.JUDGE_BY_VALUES));
            tab.add(toggle("battle.shrink." + k.key, "Closing arena", "The arena closes in like a battle royale zone."));
            tab.add(toggle("battle.scoreboard." + k.key, "Scoreboard", "A row per side with fighters left and kills."));
            pages.add(new Page(switch (k) {
                case DUEL -> "Duel";
                case ALL_VS_ONE -> "All vs one";
                case ROYALE -> "Royale";
                case TEAMS -> "Team battles";
            }, switch (k) {
                case DUEL -> "Battle Wand duels: presentation, rules, arena and timer.";
                case ALL_VS_ONE -> "Chaos Wand left click (everyone vs one): presentation, rules, arena and timer.";
                case ROYALE -> "Chaos Wand right click (everyone vs everyone): presentation, rules, arena and timer.";
                case TEAMS -> "Team Wand battles: presentation, rules, arena and timer. The teams themselves are on the Teams tab.";
            }, tab, null, false));
        }

        pages.add(new Page("Chaos area", "The Chaos Wand area (all vs one, royale): look, size, color and height.", List.of(
                section("Area"),
                choice("chaos.area", "Area look", "What areas and arenas draw: border glow, disc on the ground, both, a wall, or nothing.",
                        FxSettings.AREA_STYLES),
                number("chaos.radius", "Area radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS, "blocks", "Mobs within this radius join a Chaos Wand battle."),
                number("chaos.maxmobs", "Max mobs", 2, FxSettings.MAX_MOBS_LIMIT, "more than 60 can lag the server",
                        "Most mobs one Chaos Wand click affects."),
                choice("chaos.area.color", "Area color", "Palette color or random.", () -> with(List.of(new Opt(FxSettings.RANDOM, "Random")), palette())),
                number("chaos.height", "Area height", FxSettings.MIN_HEIGHT, FxSettings.MAX_HEIGHT, "blocks", "Height of the area pillars and wall."),
                toggle("chaos.area.onlywand", "Area only while holding the wand", "Only its owner sees the area, and only while holding the Chaos Wand.")
        ), null, false));

        List<Item> looks = new ArrayList<>(List.of(
                section("Team look"),
                choice("team.fx", "Team look", "Tint, mist, both or nothing on team mobs.", "both", "tint", "mist", "off"),
                number("team.fx.seconds", "Team look time", 0, FxSettings.TEAM_FX_MAX_SECONDS, "seconds, 0 = while in the team",
                        "How long the team look stays after a mob joins a team."),
                toggle("team.fx.onlywand", "Team look only with the Team Wand", "Show it only to the owner holding the Team Wand."),
                section("Army look"),
                toggle("army.tint", "Army tint", "Army mobs wear a tint for as long as they are in your army (default off)."),
                choice("army.tint.color", "Army tint color", "Color of the army tint (default blue).", ConfigSchema::palette),
                section("Crown, Cleanse and glow"),
                choice("fx.crown", "Crown on the lone target", "Gold, a palette color or off.",
                        () -> with(opts("Gold", "off"), palette())),
                choice("wand.cleanse.color", "Cleanse Wand color", "Light and look of the Cleanse Wand.", ConfigSchema::palette),
                number("fx.glow.look", "Look glow", 0, FxSettings.MAX_GLOW, "light level 0-15, 0 = off",
                        "Light around mobs with a jade or shimmer look or the buff's shimmer, in the look's color.")));
        looks.add(section("Waves and borders per action"));
        for (ActionFx.Action a : ActionFx.Action.values()) {
            looks.add(choice("wave." + a.key, label(a.key) + " wave", "Shockwave color when it happens (off = none).",
                    () -> with(opts(ActionFx.OFF), palette())));
            looks.add(choice("glow." + a.key, label(a.key) + " border", "Border glow color of its area (area = the area color)." + (a == ActionFx.Action.SUMMON ? " Also the color of the rune each summon rises from." : ""),
                    () -> with(opts(ActionFx.OFF, ActionFx.AREA), palette())));
        }
        looks.add(section("Mob looks per role"));
        for (ActionFx.Role r : ActionFx.Role.values()) {
            String who = switch (r) {
                case DUEL -> "Duel fighters";
                case LONE -> "All vs one target";
                case ATTACKERS -> "All vs one attackers";
                case ROYALE -> "Royale fighters";
            };
            looks.add(choice("mobfx." + r.key, who, "Mark on these mobs at the start (default = its own look).",
                    () -> with(opts(ActionFx.DEFAULT, ActionFx.OFF), palette())));
        }
        pages.add(new Page("Colors & looks", "Every palette color the mod draws with (the Buff Wand's are on the Buffs tab).", looks, null, false));

        List<Item> buffs = new ArrayList<>();
        buffs.add(note("Percent per source; sources add up on a mob. Streak counts per kill (up to Max kills), Buff Wand per click."));
        buffs.add(number("buff.size.max", "Size limit", Buffs.MIN_SIZE, Buffs.MAX_SIZE, "% (default " + Buffs.SIZE_SAFE + " = x3)",
                "Most extra size on one mob or player, all buffs together. Above " + Buffs.SIZE_SAFE + " (x3) is not recommended: "
                        + "big players don't fit under roofs and their camera ends up in blocks; runes and crowns can sink into big heads."));
        for (Buffs.Source s : Buffs.Source.values()) {
            buffs.add(section(switch (s) {
                case LONE -> "Lone target (all vs one)";
                case STREAK -> "Kill streak (per kill)";
                case CHAMPION -> "Champion (winners)";
                case WAND -> "Buff Wand (per click)";
            }));
            for (Buffs.Stat st : Buffs.Stat.values()) {
                buffs.add(number("buff." + s.key + "." + st.key, label(st.key), 0, st.max, "% (0 = off, up to " + st.max + ")",
                        "Extra " + st.key + " for this source."));
            }
            buffs.add(toggle("buff." + s.key + ".heal", "Heal when given", "Fill the new max health when the buff is given."));
            if (s == Buffs.Source.STREAK) {
                buffs.add(number("buff.streak.maxkills", "Streak max kills", 1, Buffs.MAX_STREAK, "kills", "Kills that count at most for the streak buff."));
            }
            if (s == Buffs.Source.WAND) {
                // Everything about the Buff Wand in one place (user, 2026-10-12).
                buffs.add(number("buff.wand.maxstacks", "Buff Wand max stacks", 1, Buffs.MAX_WAND, "clicks", "Most Buff Wand clicks on one mob."));
                buffs.add(choice("wand.buff.color", "Buff color", "Buff Wand mark and swirl, and the shimmer of buffed mobs (default purple).", ConfigSchema::palette));
                buffs.add(choice("wand.buff.light", "Buff Wand light", "Light from the sky that brings the buff: buff = in the buff color (default), a color, or off (the buff lands at once).",
                        () -> with(opts("buff", "off"), palette())));
            }
        }
        buffs.add(section("Crown"));
        buffs.add(choice("buff.crown", "Buffed mobs' crown", "Crown of streak, champion and Buff Wand mobs (default off).",
                () -> with(opts("Gold", "off"), palette())));
        pages.add(new Page("Buffs", "Battle buffs: size, health, damage and defense, and the Buff Wand.", buffs, null, false));

        pages.add(new Page("Wands", "Wand feedback and what each wand does.", List.of(
                section("Every wand"),
                toggle("wand.fx", "Wand effects", "Glow, soul rip and sounds on the clicked mob."),
                section("Heal Wand"),
                choice("wand.heal.light", "Heal Wand light", "Light from the sky that brings the heal: a color (default green) or off (heals at once).",
                        () -> with(opts("off"), palette())),
                section("Effect Wand"),
                choice("effect.id", "Effect Wand effect", "The effect the Effect Wand gives (right click cycles the quick list).",
                        ConfigSchema::effects),
                section("Kill Wand"),
                number("kill.delay.millis", "Soul rip time", 0, FxSettings.MAX_KILL_DELAY_MS, "ms, 0 = instant", "Time before the mob dies."),
                number("kill.hitdelay", "Cast hit delay", 0, FxSettings.MAX_SNAP_HIT_DELAY_MS, "ms (default " + FxSettings.DEFAULT_KILL_HIT_DELAY_MS + ")",
                        "From the click to the cast's hit."),
                toggle("kill.loot", "Drop loot", "A mob killed by the Kill Wand or Snap drops its normal loot (default off)."),
                number("fx.glow.death", "Death glow", 0, FxSettings.MAX_GLOW, "light level 0-15, 0 = off",
                        "Kill Wand: the disintegrating mob flashes in its color and fades out."),
                toggle("kill.players", "Works on players", "Players disintegrate too, then die normally."),
                section("Snap Wand"),
                choice("snap.mode", "Snap mode", "global = half of everyone, team = half of each side.", "global", "team"),
                number("snap.percent", "Share that turns to dust", 1, 100, "%", "Share of the fighters that turn to dust."),
                number("snap.time", "Dust time", 1, FxSettings.MAX_SNAP_SECONDS, "seconds", "Seconds over which they turn to dust."),
                number("snap.hitdelay", "Slam hit delay", 0, FxSettings.MAX_SNAP_HIT_DELAY_MS, "ms (default " + FxSettings.DEFAULT_SNAP_HIT_DELAY_MS + ")",
                        "From the click to the slam's hit."),
                number("snap.radius", "Snap reach", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS, "blocks (without battles)", "Reach of a snap with no battle running."),
                toggle("snap.players", "Snap players", "Players in reach (not Creative or invulnerable, not you) can turn to dust."),
                section("Cleanse Wand"),
                toggle("cleanse.area", "Hold left: cleanse wave", "Hold left click: a wave cleanses every mob and player around you."),
                number("cleanse.area.radius", "Cleanse wave reach", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS, "blocks (default " + FxSettings.DEFAULT_CLEANSE_RADIUS + ")",
                        "Reach of the held-click cleanse."),
                section("Morph Wand"),
                toggle("morph.players", "Morph players", "Players can be morphed too."),
                toggle("morph.burst", "Hold left: morph wave", "Hold left click: a wave gives every mob around your copied model."),
                toggle("morph.shuffle", "Hold right: swap wave", "Hold right click: a wave swaps the models of the mobs around in random pairs."),
                number("morph.burst.radius", "Wave reach", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS, "blocks (default " + FxSettings.DEFAULT_MORPH_BURST_RADIUS + ")",
                        "Reach of the held-click morph and swap waves."),
                section("Debug Wand"),
                number("wand.debug.track", "Follow time", 0, FxSettings.MAX_DEBUG_TRACK_SECONDS, "seconds, 0 = report only",
                        "After the left-click report, the mob's target, AI state, health and effects keep going into the same file (default 30).")
        ), "wands", false));

        pages.add(new Page("Teams", "Team Wand teams. Names and colors (marked * until saved) and the setting below need Save changes; add, remove and reset apply at once (Undo last change reverts them).", List.of(
                section("Team Wand"),
                toggle("team.bytype", "Team by type", "Clicking a mob brings every mob of its type within the area radius into the team.")
        ), "teams", false));

        pages.add(new Page("Summon list", "Summon Wand list. Add, remove and clear apply at once; counts and teams (gold until saved) and the settings below need Save changes.", List.of(
                section("Where they appear"),
                number("summon.radius", "Summon area", 0, FxSettings.MAX_SUMMON_RADIUS, "blocks, 0 = auto (grows with the mobs)",
                        "Radius the Summon Wand spawns in."),
                section("Random counts"),
                number("summon.min", "Random count from", 0, 100, "mobs", "Lowest count for entries set to random."),
                number("summon.max", "Random count to", 0, 100, "mobs", "Highest count for entries set to random."),
                toggle("summon.atleastone", "At least one", "A random entry never rolls 0."),
                section("Leaving"),
                choice("summon.despawn", "Summons leave", "never: they stay (default). battleEnd: the surviving summons of a battle go when it ends. timer: each one goes summonLifetime seconds after it spawned.",
                        "never", "battleEnd", "timer"),
                number("summon.lifetime", "Summon lifetime", SummonDespawn.MIN_LIFETIME, SummonDespawn.MAX_LIFETIME, "seconds", "How long a summon lives with Summons leave = timer."),
                choice("summon.exit", "How they leave", "disintegrate: the Kill Wand's soul rip, no loot (default). vanish: removed at once, no loot. shatter: breaks like jade glass (its look's color, else the summon jade color), no loot.",
                        "disintegrate", "vanish", "shatter"),
                section("When they spawn"),
                toggle("summon.army", "Summons join my army", "Summoned mobs join your army, as if added with the Army Wand (default off). Not when they spawn into a team."),
                toggle("summon.jade", "Summons in jade", "Summons come out wearing the jade look, as if given by the Effect Wand (default off)."),
                choice("summon.jade.color", "Summon jade color", "Color of the summons' jade (default green).", ConfigSchema::palette)
        ), "summon", false));

        pages.add(new Page("Presets & reset", "A preset or reset fills the form: check it, then Save changes (or Discard).", List.of(), "presets", false));

        pages.add(new Page("Tools", "Things you can do right now (same as the commands).", List.of(), "tools", false));

        List<Item> admin = new ArrayList<>();
        admin.add(section("Server log"));
        Item debug = toggle("debug", "Debug log", "[debug] lines in the server log for battle decisions.");
        ((FieldItem) debug).field().admin = true;
        admin.add(debug);
        Item record = toggle("debug.record", "Debug recorder", "Keeps the last 5 minutes of log and [debug] lines in memory; "
                + "Save debug record writes them to debug/ (default off).");
        ((FieldItem) record).field().admin = true;
        admin.add(record);
        pages.add(new Page("Admin", "Needs dackoro.mob_battle.admin in every permission mode.", admin, "admin", true));

        PAGES = List.copyOf(pages);
    }

    /** fx.properties keys with no field here (logged by the panel so a new setting is not forgotten). */
    static List<String> missingKeys(Properties p) {
        Set<String> known = new java.util.HashSet<>();
        for (Field f : FIELDS) known.add(f.key);
        List<String> out = new ArrayList<>();
        for (String k : p.stringPropertyNames()) {
            if (known.contains(k) || k.equals(FxSettings.PERMISSIONS_KEY) || k.equals(FxSettings.AREA_STYLES_KEY)
                    || k.equals("teams.list") || k.equals("summon.list") || k.equals("summon.teams")) continue;
            out.add(k);
        }
        Collections.sort(out);
        return out;
    }

    static Field field(int index) {
        return index >= 0 && index < FIELDS.size() ? FIELDS.get(index) : null;
    }
}
