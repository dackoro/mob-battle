package dev.dackoro.mobbattle;

import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>
 * Texts (2026-10-13): nothing visible is written here any more. A field's name, hint and tip are
 * {@code panel.field.<id>.label|hint|tip} in the .lang files (id = the setting key in lowercase, see {@link Field#id}),
 * a page's {@code panel.page.<id>.title|hint}, a section {@code panel.section.<id>}, a note {@code panel.note.<id>}.
 * The values of the dropdowns (option labels) are still English: {@link Opt}.
 */
final class ConfigSchema {

    enum Type { TOGGLE, NUMBER, CHOICE, TEXT }

    record Opt(String value, String label) {
    }

    static final class Field {
        final String key;
        final Type type;
        /**
         * Language id: panel.field.&lt;id&gt;.label|hint|tip in the .lang files. The lowercase setting key, unless the
         * text is shared (the same setting on each battle kind).
         */
        String id;
        /** Id of the tip when it differs from {@link #id} (texts that change per battle kind or action). */
        String tipId;
        boolean hasHint;
        /** Parameters of the label, hint and tip texts ({max}, {default}...), all as text. */
        final Map<String, String> params = new LinkedHashMap<>();
        /** Texts built by hand (a label that names an action, a tip that names a battle kind). */
        Supplier<Message> labelSupplier;
        Supplier<Message> tipSupplier;
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
        /** A buff value (percent stored): shown and typed in the buff unit (Buffs.multiplier), decimal input. */
        Buffs.Stat buffStat;
        int index;

        Field(String key, Type type) {
            this.key = key;
            this.type = type;
            this.id = key.toLowerCase(Locale.ROOT);
            this.tipId = this.id;
            this.hasHint = type == Type.NUMBER;
        }

        private Message text(String part, String textId) {
            Message m = I18n.tr("panel.field." + textId + "." + part);
            for (Map.Entry<String, String> e : params.entrySet()) m = m.param(e.getKey(), e.getValue());
            return m;
        }

        /** The setting's name. */
        Message label() {
            return labelSupplier != null ? labelSupplier.get() : text("label", id);
        }

        /** What it does (the small line under the row). */
        Message tip() {
            return tipSupplier != null ? tipSupplier.get() : text("tip", tipId);
        }

        /** Units and range next to the control; buff values use {@link ConfigSchema#buffHint}. */
        Message hint() {
            return text("hint", id);
        }

        boolean hasHint() {
            return hasHint || buffStat != null;
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

    /** A section header: panel.section.&lt;id&gt;. */
    record Section(String id) implements Item {
        Message title() {
            return I18n.tr("panel.section." + id);
        }
    }

    /** A line of explanation: panel.note.&lt;id&gt;. */
    record Note(String id) implements Item {
        Message text() {
            return I18n.tr("panel.note." + id);
        }
    }

    record FieldItem(Field field) implements Item {
        /** A parameter of this field's texts ({name} in the .lang). */
        FieldItem p(String name, Object value) {
            field.params.put(name, String.valueOf(value));
            return this;
        }

        /** Another language id than the setting key (texts shared by the same setting of every battle kind). */
        FieldItem id(String id) {
            field.id = id;
            field.tipId = id;
            return this;
        }

        /** The tip lives under another id than the label and hint. */
        FieldItem tipId(String id) {
            field.tipId = id;
            return this;
        }

        FieldItem hint() {
            field.hasHint = true;
            return this;
        }
    }

    /** {@code custom}: extra rows ConfigPage adds after the fields (lists, presets, tools, admin). Texts: panel.page.&lt;id&gt;. */
    record Page(String id, List<Item> items, String custom, boolean adminOnly) {
        Message title() {
            return I18n.tr("panel.page." + id + ".title");
        }

        Message hint() {
            return I18n.tr("panel.page." + id + ".hint");
        }
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

    /** musicTracks() kept until the music assets or the chosen track change (5 fields ask for it on every panel event). */
    private record MusicCache(int assets, String track, List<Opt> opts) {
    }

    private static volatile MusicCache musicCache;

    private static List<Opt> musicTracks() {
        int assets;
        try {
            assets = MusicContainer.getAssetMap().getAssetMap().size();
        } catch (RuntimeException e) {
            assets = -1;
        }
        MusicCache c = musicCache;
        if (c != null && c.assets() == assets && java.util.Objects.equals(c.track(), FxSettings.musicTrack)) return c.opts();
        List<Opt> opts = buildMusicTracks();
        musicCache = new MusicCache(assets, FxSettings.musicTrack, opts);
        return opts;
    }

    private static List<Opt> buildMusicTracks() {
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

    /** Every effect (PickLists, built once); the panel adds the current one if missing and filters by mod. */
    static List<Opt> effects() {
        return PickLists.filter(PickLists.effects(), PickLists.ALL, null);
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

    // ---- field builders ----

    private static FieldItem add(Field f) {
        f.index = FIELDS.size();
        FIELDS.add(f);
        return new FieldItem(f);
    }

    private static FieldItem toggle(String key) {
        return add(new Field(key, Type.TOGGLE));
    }

    private static FieldItem number(String key, int min, int max) {
        Field f = new Field(key, Type.NUMBER);
        f.min = min;
        f.max = max;
        return add(f);
    }

    /** A buff value field (percent stored, shown in the buff unit); the hint is made by {@link #buffHint}. */
    private static FieldItem buffNumber(String key, int min, int max, Buffs.Stat stat) {
        Field f = new Field(key, Type.NUMBER);
        f.min = min;
        f.max = max;
        f.buffStat = stat;
        return add(f);
    }

    /** Range hint of a buff value field in percent or multiplier. */
    static Message buffHint(Field f, boolean multiplier) {
        boolean limit = "buff.size.max".equals(f.key);
        if (!multiplier) {
            return limit ? I18n.tr("panel.buffhint.pct.limit").param("size", String.valueOf(Buffs.SIZE_SAFE))
                    : I18n.tr("panel.buffhint.pct").param("max", String.valueOf(f.max));
        }
        String max = Buffs.multText(Buffs.toMultiplier(f.buffStat, f.max));
        if (limit) {
            return I18n.tr("panel.buffhint.mult.limit")
                    .param("min", Buffs.multText(Buffs.toMultiplier(f.buffStat, f.min))).param("max", max);
        }
        return f.buffStat == Buffs.Stat.DEFENSE
                ? I18n.tr("panel.buffhint.mult.defense").param("max", max).param("div", max.substring(1))
                : I18n.tr("panel.buffhint.mult").param("max", max);
    }

    private static FieldItem choice(String key, Supplier<List<Opt>> options) {
        Field f = new Field(key, Type.CHOICE);
        f.options = options;
        return add(f);
    }

    private static FieldItem choice(String key, String... values) {
        List<Opt> list = opts(values);
        return choice(key, () -> list);
    }

    /** passiveWinIgnore: free text, a comma list of role ids (* = any ending), empty = none. */
    private static FieldItem passiveIgnore() {
        Field f = new Field("battle.passivewin.ignore", Type.TEXT);
        f.blankOk = true;
        f.check = FxSettings::parsePassiveWinIgnore;
        f.hasHint = true;
        return add(f);
    }

    /** A per-kind presentation override: empty / "Global" = follow the global value. */
    private static FieldItem override(BattleManager.Kind kind, KindFx.Opt opt) {
        String key = KindFx.propKey(kind, opt);
        boolean tie = opt == KindFx.Opt.TIE_BREAK || opt == KindFx.Opt.KILL_TIE_BREAK;
        // "Only for duel battles. Global = ...": one plain translation per kind (a joined Message must not go in .Text).
        Supplier<Message> tip = () -> I18n.tr("panel.kindtip." + (tie ? "ties." : "global.") + kind.key);
        switch (opt) {
            case COUNTDOWN, READY_TIME, CAMERA_TIME, CAMERA_SHOTS, END_CAMERA_TIME -> {
                Field f = new Field(key, Type.TEXT);
                f.blankOk = true;
                f.check = v -> KindFx.parse(opt, v);
                f.hasHint = true;
                f.id = "kind." + opt.key();
                f.tipSupplier = tip;
                f.params.put("max", String.valueOf(switch (opt) {
                    case COUNTDOWN -> FxSettings.MAX_COUNTDOWN;
                    case READY_TIME -> FxSettings.MAX_READY;
                    case CAMERA_TIME -> KindFx.MAX_CAMERA_TIME;
                    case CAMERA_SHOTS -> KindFx.maxCameraShots();
                    default -> KindFx.MAX_END_CAMERA_TIME;
                }));
                return add(f);
            }
            default -> {
                Field f = new Field(key, Type.CHOICE);
                f.id = "kind." + opt.key();
                f.tipSupplier = tip;
                f.options = () -> {
                    List<Opt> out = new ArrayList<>();
                    out.add(new Opt("", "Global (" + label(KindFx.global(opt)) + ")"));
                    switch (opt) {
                        case CAMERA_STYLE -> out.addAll(opts(KindFx.CAMERA_STYLES));
                        case END_CAMERA -> out.addAll(opts(KindFx.END_CAMERAS));
                        case CAMERA_BARS -> out.addAll(opts(KindFx.CAMERA_BARS));
                        case CAMERA_BLUR -> out.addAll(opts(KindFx.CAMERA_BLURS));
                        case AREA_STYLE -> out.addAll(opts(FxSettings.AREA_STYLES));
                        case TIE_BREAK -> out.addAll(opts(KindFx.TIE_BREAKS));
                        case KILL_TIE_BREAK -> out.addAll(opts(KindFx.KILL_TIE_BREAKS));
                        case MUSIC -> {
                            out.addAll(opts("on", "off"));
                            out.addAll(musicTracks());
                        }
                        default -> out.addAll(opts("on", "off"));
                    }
                    return out;
                };
                return add(f);
            }
        }
    }

    private static Item section(String id) {
        return new Section(id);
    }

    private static Item note(String id) {
        return new Note(id);
    }

    // ---- the pages ----

    static {
        List<Page> pages = new ArrayList<>();

        pages.add(new Page("presentation", List.of(
                section("start"),
                number("fight.countdown", 0, FxSettings.MAX_COUNTDOWN),
                number("fight.ready", 0, FxSettings.MAX_READY),
                toggle("title.enabled"),
                toggle("wintitle.enabled"),
                choice("title.style", ConfigSchema::titleStyles),
                choice("title.scope", FxSettings.TITLE_SCOPES),
                section("musicfx"),
                toggle("battle.music"),
                choice("battle.music.track", ConfigSchema::musicTracks),
                toggle("laser.enabled"),
                choice("laser.color", ConfigSchema::laserColors),
                toggle("fx.runes")
        ), null, false));

        pages.add(new Page("cameras", List.of(
                section("introcamera"),
                toggle("fight.camera"),
                toggle("camera.vsscreen"),
                number("camera.vs.teamreps", 1, 2),
                choice("camera.style", KindFx.CAMERA_STYLES),
                number("camera.time", 0, KindFx.MAX_CAMERA_TIME),
                number("camera.shots", 1, FxSettings.MAX_MOBS_LIMIT),
                choice("camera.scope", FxSettings.TITLE_SCOPES),
                choice("camera.hud", FxSettings.CAMERA_HUDS),
                toggle("camera.face"),
                choice("camera.bars", KindFx.CAMERA_BARS),
                choice("camera.blur", KindFx.CAMERA_BLURS),
                section("endcamera"),
                choice("camera.end", KindFx.END_CAMERAS),
                number("camera.end.time", 1, KindFx.MAX_END_CAMERA_TIME),
                section("film"),
                toggle("camera.autofilm"),
                number("camera.film.player", 0, 100),
                number("camera.film.wide", 0, 100),
                number("camera.film.shot", FxSettings.MIN_FILM_SHOT, FxSettings.MAX_FILM_SHOT),
                number("camera.film.variation", 0, 90),
                number("camera.film.pace", FxSettings.MIN_FILM_PACE, FxSettings.MAX_FILM_PACE),
                choice("camera.film.transition", FxSettings.FILM_TRANSITIONS),
                section("filmshots"),
                number("camera.film.weight.wide", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.track", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.arc", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.pushin", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.closeup", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.landscape", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.playerback", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.playerclose", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.zenith", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.spiral", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.telephoto", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.descent", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.groundrise", 0, FxSettings.MAX_FILM_WEIGHT),
                number("camera.film.weight.profile", 0, FxSettings.MAX_FILM_WEIGHT),
                section("watchhud"),
                toggle("camera.autowatch"),
                choice("camera.watch.transition", FxSettings.WATCH_TRANSITIONS),
                number("camera.watch.autoswitch", 0, FxSettings.MAX_WATCH_AUTO_SWITCH),
                toggle("battle.duelCard"),
                toggle("battle.focusCard"),
                note("cameras")
        ), null, false));

        pages.add(new Page("battles", List.of(
                section("everybattle"),
                toggle("battle.pvp"),
                toggle("army.friendlyfire"),
                toggle("battle.unstick"),
                number("battle.standoff", 0, FxSettings.MAX_STANDOFF_SECONDS),
                number("battle.passivewin", 0, FxSettings.MAX_PASSIVE_WIN_SECONDS),
                number("battle.below", 0, FxSettings.MAX_BATTLE_DEPTH),
                number("battle.above", 0, FxSettings.MAX_BATTLE_DEPTH),
                number("battle.arena.vertical", 0, FxSettings.MAX_BATTLE_DEPTH),
                passiveIgnore(),
                number("battle.idleend", 0, FxSettings.MAX_IDLE_END_SECONDS),
                number("battle.maxtime", 0, FxSettings.MAX_BATTLE_MINUTES),
                section("battlewaves"),
                note("battlewaves"),
                toggle("battle.wave.travel"),
                toggle("battle.wave.groundonly"),
                section("arenastimers"),
                choice("battle.timer.style", "custom", "portal"),
                number("battle.arena.size", 0, FxSettings.MAX_ARENA).p("min", FxSettings.MIN_ARENA).p("max", FxSettings.MAX_ARENA),
                number("battle.shrink.to", FxSettings.MIN_SHRINK_TO, FxSettings.MAX_ARENA),
                number("battle.shrink.time", 10, FxSettings.MAX_TIMER),
                section("ties"),
                note("ties"),
                choice("battle.tiebreak", KindFx.TIE_BREAKS),
                choice("battle.killtiebreak", KindFx.KILL_TIE_BREAKS)
        ), null, false));

        // One tab per battle kind: its presentation overrides, rules, arena and timer together (user, 2026-10-12:
        // a royale took three tabs to set up). The settings of every kind share their texts (id without the kind).
        for (BattleManager.Kind k : BattleManager.Kind.values()) {
            List<Item> tab = new ArrayList<>();
            tab.add(note("kindtab"));
            tab.add(section("kindpresentation"));
            tab.add(override(k, KindFx.Opt.COUNTDOWN));
            tab.add(override(k, KindFx.Opt.READY_TIME));
            tab.add(override(k, KindFx.Opt.START_TITLE));
            tab.add(override(k, KindFx.Opt.WIN_TITLE));
            tab.add(override(k, KindFx.Opt.MUSIC));
            tab.add(override(k, KindFx.Opt.LASER));
            tab.add(override(k, KindFx.Opt.RUNES));
            tab.add(override(k, KindFx.Opt.AREA_STYLE));
            tab.add(section("kindcameras"));
            tab.add(override(k, KindFx.Opt.CAMERA));
            tab.add(override(k, KindFx.Opt.VS_SCREEN));
            tab.add(override(k, KindFx.Opt.CAMERA_STYLE));
            tab.add(override(k, KindFx.Opt.CAMERA_TIME));
            tab.add(override(k, KindFx.Opt.CAMERA_SHOTS));
            tab.add(override(k, KindFx.Opt.END_CAMERA));
            tab.add(override(k, KindFx.Opt.END_CAMERA_TIME));
            tab.add(override(k, KindFx.Opt.AUTO_WATCH));
            tab.add(override(k, KindFx.Opt.CAMERA_BARS));
            tab.add(override(k, KindFx.Opt.CAMERA_BLUR));
            tab.add(override(k, KindFx.Opt.AUTO_FILM));
            tab.add(section("rules"));
            if (k == BattleManager.Kind.DUEL) {
                tab.add(choice("duel.mode", "exclusive", "accumulate"));
            }
            tab.add(toggle("battle.isolation." + k.key).id("battle.isolation"));
            tab.add(toggle("battle.selfdefense." + k.key).id("battle.selfdefense"));
            if (k == BattleManager.Kind.ALL_VS_ONE || k == BattleManager.Kind.TEAMS) {
                tab.add(toggle("battle.friendlyfire." + k.key).id("battle.friendlyfire"));
            }
            // The tip differs per kind: battle.players.<kind>.tip.
            tab.add(toggle("battle.players." + k.key).id("battle.players").tipId("battle.players." + k.key));
            tab.add(section("arenatimer"));
            tab.add(choice("battle.arena." + k.key, "on", "show", "wall", "off").id("battle.arena"));
            tab.add(number("battle.timer." + k.key, 0, FxSettings.MAX_TIMER).id("battle.timer"));
            tab.add(choice("battle.timeup." + k.key, "draw", "kill", "continue", "health", "judge").id("battle.timeup"));
            tab.add(choice("battle.judgeby." + k.key, FxSettings.JUDGE_BY_VALUES).id("battle.judgeby"));
            tab.add(toggle("battle.shrink." + k.key).id("battle.shrink"));
            tab.add(section("ties"));
            tab.add(override(k, KindFx.Opt.TIE_BREAK));
            // A duel has no kill table (one fighter per side).
            if (k != BattleManager.Kind.DUEL) tab.add(override(k, KindFx.Opt.KILL_TIE_BREAK));
            tab.add(toggle("battle.scoreboard." + k.key).id("battle.scoreboard"));
            pages.add(new Page("kind." + k.key, tab, null, false));
        }

        pages.add(new Page("chaosarea", List.of(
                section("area"),
                choice("chaos.area", FxSettings.AREA_STYLES),
                number("chaos.radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS),
                number("chaos.maxmobs", 2, FxSettings.MAX_MOBS_LIMIT).p("warn", FxSettings.MAX_MOBS_WARN),
                choice("chaos.area.color", () -> with(List.of(new Opt(FxSettings.RANDOM, "Random")), palette())),
                number("chaos.height", FxSettings.MIN_HEIGHT, FxSettings.MAX_HEIGHT),
                toggle("chaos.area.onlywand")
        ), null, false));

        List<Item> looks = new ArrayList<>(List.of(
                section("teamlook"),
                choice("team.fx", "both", "tint", "mist", "off"),
                number("team.fx.seconds", 0, FxSettings.TEAM_FX_MAX_SECONDS),
                toggle("team.fx.onlywand"),
                section("armylook"),
                toggle("army.tint"),
                choice("army.tint.color", ConfigSchema::palette),
                section("crownglow"),
                choice("fx.crown", () -> with(opts("Gold", "off"), palette())),
                choice("wand.cleanse.color", ConfigSchema::palette),
                number("fx.glow.look", 0, FxSettings.MAX_GLOW)));
        looks.add(section("wavesborders"));
        for (ActionFx.Action a : ActionFx.Action.values()) {
            // Label: panel.field.wave.<action>.label / glow.<action>.label; the tip is shared.
            looks.add(choice("wave." + a.key, () -> with(opts(ActionFx.OFF), palette())).tipId("wave"));
            looks.add(choice("glow." + a.key, () -> with(opts(ActionFx.OFF, ActionFx.AREA), palette()))
                    .tipId(a == ActionFx.Action.SUMMON ? "glow.summon" : "glow"));
        }
        looks.add(section("moblooks"));
        for (ActionFx.Role r : ActionFx.Role.values()) {
            looks.add(choice("mobfx." + r.key, () -> with(opts(ActionFx.DEFAULT, ActionFx.OFF), palette())).tipId("mobfx"));
        }
        pages.add(new Page("looks", looks, null, false));

        List<Item> buffs = new ArrayList<>();
        buffs.add(note("buffs"));
        buffs.add(choice("buff.unit", Buffs.UNIT_PERCENT, Buffs.UNIT_MULTIPLIER));
        buffs.add(choice("buff.size.speed", Buffs.SIZE_EFFECTS.toArray(new String[0])));
        buffs.add(choice("buff.size.jump", Buffs.SIZE_EFFECTS.toArray(new String[0])));
        buffs.add(buffNumber("buff.size.max", Buffs.MIN_SIZE, Buffs.MAX_SIZE, Buffs.Stat.SIZE).p("size", Buffs.SIZE_SAFE));
        // Buff Wand first (user, 2026-10-13: the lone target section on top was taken for the wand's).
        for (Buffs.Source s : List.of(Buffs.Source.WAND, Buffs.Source.LONE, Buffs.Source.STREAK, Buffs.Source.CHAMPION)) {
            buffs.add(section("buff." + s.key));
            // How clicks add up: on the mob's base value, never on what it already has (user, 2026-10-13).
            if (s == Buffs.Source.WAND) buffs.add(note("buffwand.stack"));
            for (Buffs.Stat st : Buffs.Stat.values()) {
                buffs.add(buffNumber("buff." + s.key + "." + st.key, 0, st.max, st).id("buff.stat." + st.key));
            }
            buffs.add(toggle("buff." + s.key + ".heal").id("buff.heal"));
            if (s == Buffs.Source.STREAK) {
                buffs.add(number("buff.streak.maxkills", 1, Buffs.MAX_STREAK));
            }
            if (s == Buffs.Source.WAND) {
                // Everything about the Buff Wand in one place (user, 2026-10-12).
                buffs.add(number("buff.wand.maxstacks", 1, Buffs.MAX_WAND));
                buffs.add(choice("wand.buff.color", ConfigSchema::palette));
                buffs.add(choice("wand.buff.look", "shimmer", "runes", "off"));
                buffs.add(choice("wand.buff.light", () -> with(opts("buff", "off"), palette())));
            }
        }
        buffs.add(section("buffcrown"));
        buffs.add(choice("buff.crown", () -> with(opts("Gold", "off"), palette())));
        pages.add(new Page("buffs", buffs, null, false));

        pages.add(new Page("wands", List.of(
                section("everywand"),
                toggle("wand.fx"),
                section("wandwaves"),
                note("wandwaves"),
                toggle("wand.wave.travel"),
                toggle("wand.wave.groundonly"),
                section("healwand"),
                choice("wand.heal.light", () -> with(opts("off"), palette())),
                toggle("heal.area"),
                number("heal.area.radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS).p("default", FxSettings.DEFAULT_HEAL_RADIUS),
                toggle("heal.area.self"),
                section("effectwand"),
                choice("effect.id", ConfigSchema::effects),
                toggle("effect.area"),
                number("effect.area.radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS).p("default", FxSettings.DEFAULT_EFFECT_RADIUS),
                toggle("effect.area.self"),
                section("killwand"),
                number("kill.delay.millis", 0, FxSettings.MAX_KILL_DELAY_MS),
                number("kill.hitdelay", 0, FxSettings.MAX_SNAP_HIT_DELAY_MS).p("default", FxSettings.DEFAULT_KILL_HIT_DELAY_MS),
                toggle("kill.loot"),
                number("fx.glow.death", 0, FxSettings.MAX_GLOW),
                toggle("kill.players"),
                toggle("kill.area"),
                number("kill.area.radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS).p("default", FxSettings.DEFAULT_KILL_RADIUS),
                toggle("kill.area.players"),
                section("snapwand"),
                choice("snap.mode", "global", "team"),
                number("snap.percent", 1, 100),
                number("snap.time", 1, FxSettings.MAX_SNAP_SECONDS),
                number("snap.hitdelay", 0, FxSettings.MAX_SNAP_HIT_DELAY_MS).p("default", FxSettings.DEFAULT_SNAP_HIT_DELAY_MS),
                number("snap.radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS),
                toggle("snap.players"),
                note("snapwave"),
                section("cleansewand"),
                toggle("cleanse.area"),
                number("cleanse.area.radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS).p("default", FxSettings.DEFAULT_CLEANSE_RADIUS),
                toggle("cleanse.area.self"),
                section("morphwand"),
                toggle("morph.players"),
                toggle("morph.burst"),
                toggle("morph.shuffle"),
                number("morph.burst.radius", FxSettings.MIN_RADIUS, FxSettings.MAX_RADIUS).p("default", FxSettings.DEFAULT_MORPH_BURST_RADIUS),
                toggle("morph.burst.self"),
                section("debugwand"),
                number("wand.debug.track", 0, FxSettings.MAX_DEBUG_TRACK_SECONDS)
        ), "wands", false));

        pages.add(new Page("teams", List.of(
                section("teamwand"),
                toggle("team.bytype")
        ), "teams", false));

        pages.add(new Page("summon", List.of(
                section("whereappear"),
                number("summon.radius", 0, FxSettings.MAX_SUMMON_RADIUS),
                section("randomcounts"),
                // Same range as /mobbattle summon random (audit 2026-10-14: the panel cut a saved 500 to 100 on save).
                number("summon.min", 0, FxSettings.MAX_MOBS_LIMIT).p("warn", FxSettings.MAX_MOBS_WARN),
                number("summon.max", 0, FxSettings.MAX_MOBS_LIMIT).p("warn", FxSettings.MAX_MOBS_WARN),
                toggle("summon.atleastone"),
                section("leaving"),
                choice("summon.despawn", "never", "battleEnd", "timer"),
                number("summon.lifetime", SummonDespawn.MIN_LIFETIME, SummonDespawn.MAX_LIFETIME),
                choice("summon.exit", "disintegrate", "vanish", "shatter"),
                section("whenspawn"),
                choice("summon.calm", "off", "summons", "all"),
                toggle("summon.army"),
                toggle("summon.jade"),
                choice("summon.jade.color", ConfigSchema::palette)
        ), "summon", false));

        pages.add(new Page("presets", List.of(), "presets", false));

        pages.add(new Page("tools", List.of(), "tools", false));

        List<Item> admin = new ArrayList<>();
        admin.add(section("serverlog"));
        FieldItem debug = toggle("debug");
        debug.field().admin = true;
        admin.add(debug);
        FieldItem record = toggle("debug.record");
        record.field().admin = true;
        admin.add(record);
        // server features (2026-10-18): admin only, pictures follow at the next restart
        admin.add(section("serverfeatures"));
        admin.add(note("serverfeatures"));
        for (String key : FxSettings.SERVER_KEYS) {
            FieldItem f = toggle(key);
            f.field().admin = true;
            admin.add(f);
        }
        pages.add(new Page("admin", admin, "admin", true));

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
