package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * /mobbattle config: the settings panel. A left menu of pages ({@link ConfigSchema#PAGES}); every setting is a row
 * built from the templates in Common/UI/Custom/MobBattle/Config/. Changing a row only changes this page's draft
 * (the label turns gold); "Save changes" applies the whole draft at once through {@link FxSettings#applyAll} (like a
 * preset) and "Discard changes" drops it. Presets and resets fill the draft too, so they can be checked first.
 * Lists (teams, summon list), tools and admin actions run their /mobbattle command right away, so the command's
 * permission check, validation and chat feedback stay the only implementation.
 * <p>
 * Events arrive as raw JSON ({@link #handleDataEvent(Ref, Store, String)}): every row sends its own keys, so the
 * page reads them as a document instead of one fixed codec. Every value from the client is checked again here.
 */
final class ConfigPage extends InteractiveCustomUIPage<ConfigPage.Unused> {

    /** Not used: events are parsed from the raw JSON. */
    static final class Unused {
        static final BuilderCodec<Unused> CODEC = BuilderCodec.builder(Unused.class, Unused::new).build();
    }

    private static final String UI = "MobBattle/ConfigPage.ui";
    private static final String DIR = "MobBattle/Config/";
    private static final String GOLD = "#ffd23e";
    private static final String GRAY = "#96a9be";
    private static final String WARN = "#ffcc55";
    private static final long CONFIRM_MS = 5000;
    /** Dropdowns with more entries than this get a search box. */
    private static final int SEARCH_FROM = 15;
    private static final AtomicBoolean SCHEMA_CHECKED = new AtomicBoolean();
    /** The page each player had open last (by UUID, never a PlayerRef), so the panel reopens there. */
    private static final Map<java.util.UUID, Integer> LAST_PAGE = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Each player's last "Add a mob" pick {role, count, team} (2026-10-13, playtest: after Add the row went back to the
     * first mob, so adding Skeleton_Archer 10 red then 10 green meant searching again). Kept until the server stops.
     */
    private static final Map<java.util.UUID, String[]> LAST_SUMMON_PICK = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each player's last pick list filters (2026-10-13): list key -> {mod, type}. Memory only. */
    private static final Map<java.util.UUID, Map<String, String[]>> LAST_FILTER = new java.util.concurrent.ConcurrentHashMap<>();
    /** Pick lists with a filter row: the summon mob row, the Morph Wand model row and these setting fields. */
    private static final java.util.Set<String> FILTERED_FIELDS = java.util.Set.of("effect.id");
    /** On the open page: filtered list key -> the row whose #Input it fills. */
    private final Map<String, String> filterTarget = new HashMap<>();
    /** Every setting as it was right before the last panel save (any player), for Undo; null until a save. */
    private static volatile Properties beforeLastSave;
    private static volatile String lastSaver;

    private final Ref<EntityStore> entity;
    private final Store<EntityStore> store;
    private int page;
    /**
     * The search box's text (2026-10-16, playtest: find "countdown" without knowing its page): two or more characters show
     * every matching setting (#SearchResults, grouped by page, editable as on their page) instead of the open page.
     */
    private String query = "";
    /** Matches shown for the current search (for the line under the title). */
    private int searchCount;
    private static final int MAX_RESULTS = 60;
    private static final int MIN_QUERY = 2;
    /** Field index -> new value (canonical), only for values that differ from the saved settings. */
    private final Map<Integer, String> draft = new LinkedHashMap<>();
    /** Field index -> its row selector in the current build. */
    private final Map<Integer, String> rowOf = new HashMap<>();
    /**
     * Summon list rows edited but not saved: role -> {count, team} (null = that part unchanged). Saved with the
     * settings (Save changes), so editing a row never rebuilds the page (scroll and the other rows' inputs stay).
     */
    private final Map<String, String[]> summonDraft = new LinkedHashMap<>();
    /** Role -> its summon row selector in the current build. */
    private final Map<String, String> summonRowOf = new HashMap<>();
    /** The "remove every team" row under the summon list, null when not shown (shown only while a row has a team). */
    private String noTeamsRow;
    /** Team rows edited but not saved: slot -> {name, palette color} (null = unchanged), same as summonDraft. */
    private final Map<Integer, String[]> teamDraft = new LinkedHashMap<>();
    /** Slot -> its team row selector in the current build. */
    private final Map<Integer, String> teamRowOf = new HashMap<>();
    /** The line before the change count in the footer (a Message: translated for each player's language). */
    private Message notice;
    private String noticeColor = GRAY;
    /** The area preview drawn last for this player ({x, y, z, radius}), so the next one replaces it. */
    private double[] lastPreview;
    private String confirmAction;
    private long confirmUntil;
    /**
     * Confirm key (action, or action + name) -> the button (selector, its normal text) on the page shown now. An
     * armed button turns red and reads "Confirm?" until the second click or the 5 s are over (playtest 2026-10-13: only
     * the status line said so, and it was missed).
     */
    private final Map<String, Confirm> confirmButtons = new HashMap<>();

    /** A button that asks for a second click: its selector and a supplier of its normal text. */
    private record Confirm(String selector, Supplier<Message> text) {
    }
    private int confirmGeneration;

    private ConfigPage(PlayerRef player, Ref<EntityStore> entity, Store<EntityStore> store) {
        super(player, CustomPageLifetime.CanDismiss, Unused.CODEC);
        this.entity = entity;
        this.store = store;
        int last = LAST_PAGE.getOrDefault(player.getUuid(), 0);
        this.page = last >= 0 && last < ConfigSchema.PAGES.size() ? last : 0;
    }

    /** From the command, on the player's world thread. */
    static void open(PlayerRef player, Ref<EntityStore> entity, Store<EntityStore> store) {
        if (player == null || entity == null || store == null || !entity.isValid() || entity.getStore() != store
                || store.isShutdown()) return;
        Player current = store.getComponent(entity, Player.getComponentType());
        PlayerRef live = store.getComponent(entity, PlayerRef.getComponentType());
        if (current == null || live == null || live != player) return;
        current.getPageManager().openCustomPage(entity, store, new ConfigPage(player, entity, store));
    }

    private boolean canEdit() {
        return Perms.allowed(playerRef, Perms.CONFIG);
    }

    private boolean admin() {
        return Perms.admin(playerRef);
    }

    // ---- building ----

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder ev, Store<EntityStore> st) {
        ui.append(UI);
        // The mob gallery popup over the panel (hidden until "Show pictures"), as a second page overlay.
        ui.append(DIR + "GalleryPopup.ui");
        galleryBindings(ev);
        rowOf.clear();
        previewOf.clear();
        swatchOf.clear();
        summonRowOf.clear();
        noTeamsRow = null;
        filterTarget.clear();
        filterRowOf.clear();
        galleryShown = false;
        galleryShownIds = null;
        confirmButtons.clear();
        teamRowOf.clear();
        Properties live = FxSettings.toProperties();
        if (SCHEMA_CHECKED.compareAndSet(false, true)) {
            List<String> missing = ConfigSchema.missingKeys(live);
            if (!missing.isEmpty()) MobBattlePlugin.log("Settings panel: no field for " + missing);
        }
        boolean admin = admin();
        if (ConfigSchema.PAGES.get(page).adminOnly() && !admin) page = 0;
        int nav = 0;
        for (int i = 0; i < ConfigSchema.PAGES.size(); i++) {
            ConfigSchema.Page p = ConfigSchema.PAGES.get(i);
            if (p.adminOnly() && !admin) continue;
            ui.append("#Nav", DIR + "Nav.ui");
            String sel = "#Nav[" + nav++ + "]";
            I18n.text(ui, sel + " #Button.Text", p.title());
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button", data("Nav").append("P", String.valueOf(i)), false);
            // Only the open page gets its rows: a page change rebuilds (the draft lives here, not in the client).
            // All 14 at once was ~3500 dropdown entries (music tracks in 5 lists, NPC roles, models) per build.
            if (i != page || searching()) continue;
            pageRows(new Rows(ui, ev, "#Page" + i), p, live);
        }
        if (searching()) searchRows(new Rows(ui, ev, "#SearchResults"), live);
        ui.set("#Search.Value", query);
        ev.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Search", data("Search").append("@V", "#Search.Value"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton", data("Save"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#DiscardButton", data("Discard"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#UndoButton", data("Undo"), false);
        // The frame's X (Common.ui @DecoratedContainer #CloseButton) is a plain button: nothing closes the page unless we bind it.
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", data("Close"), false);
        showPage(ui);
        status(ui);
    }

    /** The rows of one page (fields, then its custom list). */
    private void pageRows(Rows rows, ConfigSchema.Page p, Properties live) {
        for (ConfigSchema.Item item : p.items()) {
            switch (item) {
                case ConfigSchema.Section s -> {
                    rows.section(s.title());
                    // Film shots: one button puts every percent back to its default (2026-10-16).
                    if ("filmshots".equals(s.id())) {
                        // Film styles: every film setting at once, into the draft (2026-10-16).
                        String style = FilmStyles.current();
                        rows.note(style == null ? I18n.tr("panel.note.filmstyle.custom")
                                : I18n.tr("panel.note.filmstyle").param("style", styleName(style)));
                        String[] names = FilmStyles.NAMES;
                        Message[] labels = new Message[names.length];
                        EventData[] data = new EventData[names.length];
                        for (int i = 0; i < names.length; i++) {
                            labels[i] = I18n.tr("panel.button.filmstyle." + names[i]);
                            data[i] = data("FilmStyle").append("N", names[i]);
                        }
                        rows.buttons(labels, data);
                        rows.action(I18n.tr("panel.filmshots.reset.label"), I18n.tr("panel.filmshots.reset.button"), data("FilmShotsDefault"));
                    }
                }
                case ConfigSchema.Note n -> rows.note(n.text());
                case ConfigSchema.FieldItem f -> fieldRow(rows, f.field(), live);
            }
        }
        if (p.custom() != null) {
            switch (p.custom()) {
                case "wands" -> wandsRows(rows);
                case "teams" -> teamRows(rows);
                case "summon" -> summonRows(rows);
                case "presets" -> presetRows(rows);
                case "tools" -> toolRows(rows);
                case "admin" -> adminRows(rows);
                default -> { }
            }
        }
    }

    /**
     * Redraws only the open page's rows (a list row added or removed), not the whole panel: rebuild() sends the
     * page with clear = true and the scroll goes back to the top (playtest 2026-10-12). Here the page container is
     * cleared and refilled in one update without clear, so the nav, the bottom bar and their bindings stay; the
     * old rows' bindings go with their elements.
     */
    private void refreshPage() {
        ConfigSchema.Page p = ConfigSchema.PAGES.get(page);
        if (p.adminOnly() && !admin()) {
            rebuild();
            return;
        }
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder ev = new UIEventBuilder();
        if (searching()) {
            // The results only hold fields: draw them again as they are.
            ui.clear("#SearchResults");
            clearRowMaps();
            searchRows(new Rows(ui, ev, "#SearchResults"), FxSettings.toProperties());
            showPage(ui);
            status(ui);
            sendUpdate(ui, ev, false);
            return;
        }
        String container = "#Page" + page;
        ui.clear(container);
        rowOf.clear();
        previewOf.clear();
        swatchOf.clear();
        summonRowOf.clear();
        noTeamsRow = null;
        filterTarget.clear();
        filterRowOf.clear();
        confirmButtons.clear();
        teamRowOf.clear();
        pageRows(new Rows(ui, ev, container), p, FxSettings.toProperties());
        status(ui);
        sendUpdate(ui, ev, false);
    }

    /** Appends rows to one page container and hands out their selectors. */
    private static final class Rows {
        final UICommandBuilder ui;
        final UIEventBuilder ev;
        final String container;
        int n;

        Rows(UICommandBuilder ui, UIEventBuilder ev, String container) {
            this.ui = ui;
            this.ev = ev;
            this.container = container;
        }

        String add(String template) {
            ui.append(container, DIR + template);
            return container + "[" + n++ + "]";
        }

        void section(Message title) {
            I18n.text(ui, add("Section.ui") + " #Label.Text", title);
        }

        void note(Message text) {
            I18n.text(ui, add("Note.ui") + " #Label.Text", text);
        }

        /** A row with a label and one button. */
        String action(Message label, Message button, EventData data) {
            String sel = add("Action.ui");
            I18n.text(ui, sel + " #Label.Text", label);
            I18n.text(ui, sel + " #Button.Text", button);
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button", data, false);
            return sel;
        }

        /** Up to four buttons in a row; returns the row's selector (buttons #B0..#B3). */
        String buttons(Message[] labels, EventData[] data) {
            String sel = add("Buttons.ui");
            for (int i = 0; i < Math.min(4, labels.length); i++) {
                ui.set(sel + " #B" + i + ".Visible", true);
                I18n.text(ui, sel + " #B" + i + ".Text", labels[i]);
                ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #B" + i, data[i], false);
            }
            return sel;
        }
    }

    private void fieldRow(Rows rows, ConfigSchema.Field f, Properties live) {
        String template = switch (f.type) {
            case TOGGLE -> "Toggle.ui";
            case NUMBER -> f.buffStat != null ? "Decimal.ui" : "Number.ui";
            case CHOICE -> f.options.get().size() > SEARCH_FROM ? "ChoiceSearch.ui" : "Choice.ui";
            case TEXT -> "Text.ui";
        };
        boolean filtered = FILTERED_FIELDS.contains(f.key);
        if (filtered) filterRow(rows, f.key);
        String sel = rows.add(template);
        rowOf.put(f.index, sel);
        if (filtered) filterTarget.put(f.key, sel);
        UICommandBuilder ui = rows.ui;
        I18n.text(ui, sel + " #Tip.Text", f.tip());
        if (f.buffStat != null) I18n.text(ui, sel + " #Hint.Text", ConfigSchema.buffHint(f, multiplier(live)));
        else if (f.hasHint()) I18n.text(ui, sel + " #Hint.Text", f.hint());
        else ui.set(sel + " #Hint.Text", "");
        label(ui, f);
        setInput(ui, sel, f, value(f, live));
        rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Input",
                data("Set").append("F", String.valueOf(f.index)).append("@V", sel + " #Input.Value"), false);
    }

    private void setInput(UICommandBuilder ui, String sel, ConfigSchema.Field f, String v) {
        switch (f.type) {
            case TOGGLE -> ui.set(sel + " #Input.Value", f.on.equals(v));
            case NUMBER -> {
                // Buff values (Decimal.ui, float as vanilla's decimal fields): percent stored, shown in the buff unit.
                if (f.buffStat == null) ui.set(sel + " #Input.Value", parseInt(v, f.min));
                else if (multiplier(FxSettings.toProperties())) ui.set(sel + " #Input.Value",
                        (float) (Math.round(Buffs.toMultiplier(f.buffStat, parseInt(v, f.min)) * 100) / 100.0));
                else ui.set(sel + " #Input.Value", (float) parseInt(v, f.min));
            }
            case CHOICE -> {
                // A filtered list (effects) shows what its filter row keeps; the current value is always there.
                List<ConfigSchema.Opt> opts = new ArrayList<>(filterTarget.containsKey(f.key) ? filtered(f.key) : f.options.get());
                if (opts.stream().noneMatch(o -> o.value().equals(v))) opts.add(new ConfigSchema.Opt(v, v.isEmpty() ? "-" : v));
                ui.set(sel + " #Input.Entries", entries(opts));
                ui.set(sel + " #Input.Value", v);
            }
            case TEXT -> ui.set(sel + " #Input.Value", v);
        }
    }

    /** The buff unit the panel shows now: the draft's "Buff values as", else the saved one. */
    private boolean multiplier(Properties live) {
        return Buffs.UNIT_MULTIPLIER.equals(valueOf("buff.unit", live));
    }

    /** "Buff values as" changed (draft, Discard, Undo): every buff value row shown again in that unit, in place. */
    private void refreshBuffRows(UICommandBuilder ui, Properties live) {
        boolean mult = multiplier(live);
        for (ConfigSchema.Field b : ConfigSchema.FIELDS) {
            String sel = b.buffStat != null ? rowOf.get(b.index) : null;
            if (sel == null) continue;
            setInput(ui, sel, b, value(b, live));
            I18n.text(ui, sel + " #Hint.Text", ConfigSchema.buffHint(b, mult));
        }
    }

    /** Gold label = changed and not saved yet. */
    private void label(UICommandBuilder ui, ConfigSchema.Field f) {
        String sel = rowOf.get(f.index);
        if (sel == null) return;
        boolean changed = draft.containsKey(f.index);
        // "* " + the translated name, each part with the color (TextSpans with a translated Message: untested in game).
        String color = changed ? GOLD : GRAY;
        ui.set(sel + " #Label.TextSpans", changed ? Message.join(Message.raw("* ").color(color), f.label().color(color))
                : f.label().color(color));
    }

    /** The value shown: the draft's, else the saved one ("" = no per-kind override). */
    private String value(ConfigSchema.Field f, Properties live) {
        String d = draft.get(f.index);
        return d != null ? d : saved(f, live);
    }

    private static String saved(ConfigSchema.Field f, Properties live) {
        String raw = live.getProperty(f.key);
        if (raw == null) return f.type == ConfigSchema.Type.NUMBER ? String.valueOf(f.min) : "";
        String c = f.canonical(raw);
        return c != null ? c : raw;
    }

    private void showPage(UICommandBuilder ui) {
        boolean admin = admin();
        boolean searching = searching();
        ui.set("#SearchResults.Visible", searching);
        int nav = 0;
        for (int i = 0; i < ConfigSchema.PAGES.size(); i++) {
            ConfigSchema.Page p = ConfigSchema.PAGES.get(i);
            boolean open = i == page && !searching;
            ui.set("#Page" + i + ".Visible", open);
            if (p.adminOnly() && !admin) continue;
            String sel = "#Nav[" + nav++ + "]";
            ui.set(sel + " #Sel.Visible", open);
            ui.set(sel + " #Button.Style", Value.ref("Common.ui", open ? "SmallSecondaryTextButtonStyle" : "SmallTertiaryTextButtonStyle"));
        }
        if (searching) {
            I18n.text(ui, "#PageTitle.Text", I18n.tr("panel.search.title").param("query", query));
            I18n.text(ui, "#PageHint.Text", I18n.tr("panel.search.count").param("n", searchCount));
            return;
        }
        ConfigSchema.Page p = ConfigSchema.PAGES.get(page);
        I18n.text(ui, "#PageTitle.Text", p.title());
        I18n.text(ui, "#PageHint.Text", p.hint());
    }

    // ---- search ----

    private boolean searching() {
        return query.length() >= MIN_QUERY;
    }

    /** Typed in the search box: the results replace the open page in place (no rebuild, so the box keeps focus). */
    private void search(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.length() > 60) q = q.substring(0, 60);
        boolean was = searching();
        if (q.equals(query)) return;
        query = q;
        if (!was && !searching()) return;   // one letter: nothing changes yet
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder ev = new UIEventBuilder();
        clearRowMaps();
        ui.clear("#SearchResults");
        ui.clear("#Page" + page);
        Properties live = FxSettings.toProperties();
        if (searching()) searchRows(new Rows(ui, ev, "#SearchResults"), live);
        else pageRows(new Rows(ui, ev, "#Page" + page), ConfigSchema.PAGES.get(page), live);
        showPage(ui);
        status(ui);
        sendUpdate(ui, ev, false);
    }

    /** Every row map the page rows fill (refilled by whoever draws rows next). */
    private void clearRowMaps() {
        rowOf.clear();
        previewOf.clear();
        swatchOf.clear();
        summonRowOf.clear();
        noTeamsRow = null;
        filterTarget.clear();
        filterRowOf.clear();
        confirmButtons.clear();
        teamRowOf.clear();
    }

    private record Hit(int page, int order, int score, ConfigSchema.Field field) {
    }

    /** The matching settings, each group under its page's title, with the same controls as on its page. */
    private void searchRows(Rows rows, Properties live) {
        List<Hit> hits = searchHits();
        searchCount = hits.size();
        if (hits.isEmpty()) {
            rows.note(I18n.tr("panel.search.none").param("query", query));
            return;
        }
        int shown = -1;
        for (Hit h : hits) {
            if (h.page() != shown) {
                rows.section(ConfigSchema.PAGES.get(h.page()).title());
                shown = h.page();
            }
            fieldRow(rows, h.field(), live);
        }
    }

    /**
     * Fields whose name (in the player's language and in English), setting key or description holds every word of
     * the search, accents and case ignored. At most MAX_RESULTS, the best first (name, then key, then description),
     * then shown in page order. Admin-only pages only for admins.
     */
    private List<Hit> searchHits() {
        String[] words = norm(query).split("\\s+");
        String lang = playerRef.getLanguage();
        if (lang == null || lang.isBlank()) lang = "en-US";
        boolean admin = admin();
        List<Hit> hits = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < ConfigSchema.PAGES.size(); i++) {
            ConfigSchema.Page p = ConfigSchema.PAGES.get(i);
            if (p.adminOnly() && !admin) continue;
            int order = 0;
            for (ConfigSchema.Item item : p.items()) {
                if (!(item instanceof ConfigSchema.FieldItem fi)) continue;
                ConfigSchema.Field f = fi.field();
                order++;
                if (!seen.add(f.index)) continue;
                String labelKey = I18n.id("panel.field." + f.id + ".label");
                String label = f.labelSupplier != null ? "" : norm(translated(lang, labelKey) + " " + translated("en-US", labelKey));
                String key = norm(f.key.replace('.', ' '));
                String tip = f.tipSupplier != null ? "" : norm(translated(lang, I18n.id("panel.field." + f.tipId + ".tip")));
                int score = score(words, label, key, tip);
                if (score >= 0) hits.add(new Hit(i, order, score, f));
            }
        }
        if (hits.size() > MAX_RESULTS) {
            hits.sort(java.util.Comparator.comparingInt(Hit::score));
            hits = new ArrayList<>(hits.subList(0, MAX_RESULTS));
        }
        hits.sort(java.util.Comparator.comparingInt(Hit::page).thenComparingInt(Hit::order));
        return hits;
    }

    /** Every word somewhere: 0 = all in the name, 1 = some only in the key, 2 = some only in the description; -1 = no. */
    private static int score(String[] words, String label, String key, String tip) {
        int worst = 0;
        for (String w : words) {
            if (w.isEmpty()) continue;
            int s = label.contains(w) ? 0 : key.contains(w) ? 1 : tip.contains(w) ? 2 : -1;
            if (s < 0) return -1;
            worst = Math.max(worst, s);
        }
        return worst;
    }

    /** Lowercase, without accents (camara finds cámara). */
    private static String norm(String s) {
        if (s == null) return "";
        String d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return d.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * A film style's name as its button says it, in the player's language, as a plain String (a Message param inside a
     * .Text label showed its raw form; the English label read "Estilo de la película: Battle"; audit 2026-10-17).
     */
    private String styleName(String style) {
        String key = "server.mobbattle.panel.button.filmstyle." + style;
        String v = translated(playerRef.getLanguage(), key);
        if (v.isEmpty()) v = translated("en-US", key);
        return v.isEmpty() ? ConfigSchema.label(style) : v;
    }

    /** A translation as the game's I18nModule holds it ("" if none or unreadable). */
    private static String translated(String lang, String key) {
        try {
            var i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
            if (i18n == null) return "";
            String v = i18n.getMessage(lang, key);
            if (v == null && key.startsWith("server.")) v = i18n.getMessage(lang, key.substring("server.".length()));
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void status(UICommandBuilder ui) {
        boolean edit = canEdit();
        ui.set("#ReadOnly.Visible", !edit);
        int n = draft.size() + summonDraft.size() + teamDraft.size();
        Message changes = !edit ? I18n.tr("panel.status.readonly") : n == 0 ? I18n.tr("panel.status.nochanges")
                : I18n.tr("panel.status.changes").param("count", n);
        if (notice != null) {
            ui.set("#Status.TextSpans", Message.join(notice.color(noticeColor), Message.raw("  ").color(noticeColor),
                    changes.color(noticeColor)));
        } else {
            ui.set("#Status.TextSpans", changes.color(n > 0 ? GOLD : GRAY));
        }
        ui.set("#SaveButton.Disabled", !edit || n == 0);
        ui.set("#DiscardButton.Disabled", n == 0);
        ui.set("#UndoButton.Disabled", !edit || !canUndo());
    }

    private void say(String text, String color) {
        say(Message.raw(text), color);
    }

    private void say(Message text, String color) {
        notice = text;
        noticeColor = color;
        UICommandBuilder ui = new UICommandBuilder();
        status(ui);
        sendUpdate(ui);
    }

    // ---- custom rows ----

    private void wandsRows(Rows rows) {
        rows.section(I18n.tr("panel.section.morphmodel"));
        filterRow(rows, "morph");
        String sel = rows.add("ActionPick.ui");
        filterTarget.put("morph", sel);
        I18n.text(rows.ui, sel + " #Label.Text", I18n.tr("panel.row.morph.label"));
        List<ConfigSchema.Opt> models = filtered("morph");
        rows.ui.set(sel + " #Input.Entries", entries(models));
        rows.ui.set(sel + " #Button.Disabled", models.isEmpty());
        // Start on the model the wand has now (set by Use, /mobbattle morph or a right click copy), else the first.
        MorphWand.Copy copy = MorphWand.copyOf(playerRef.getUuid());
        String held = copy == null ? null : copy.modelId();
        String current = held;
        if (held == null || models.stream().noneMatch(o -> o.value().equals(held))) {
            current = models.isEmpty() ? null : models.get(0).value();
        }
        if (current != null) rows.ui.set(sel + " #Input.Value", current);
        preview(rows, sel, "morph", current);
        I18n.text(rows.ui, sel + " #Button.Text", I18n.tr("panel.button.use"));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "morph").append("@V", sel + " #Input.Value"), false);
        rows.note(I18n.tr("panel.note.morphmodel"));
    }

    /** A team row's "*" mark: gold while it has unsaved changes. */
    private void teamMark(UICommandBuilder ui, int slot) {
        String sel = teamRowOf.get(slot);
        if (sel == null) return;
        ui.set(sel + " #Mark.TextSpans", Message.raw(teamDraft.containsKey(slot) ? "*" : " ").color(GOLD));
    }

    /**
     * A team row's name or color changed: kept in the draft (row marked, swatch shows the new color) until Save
     * changes, no rebuild. Names and colors are checked together on Save, so two teams can swap them.
     */
    private void teamEdit(int slot, String kind, String raw) {
        String sel = teamRowOf.get(slot);
        if (sel == null || !Teams.exists(slot) || raw == null || !"name".equals(kind) && !"color".equals(kind)) return;
        UICommandBuilder ui = new UICommandBuilder();
        boolean isName = "name".equals(kind);
        String saved = isName ? Teams.name(slot) : paletteName(Teams.color(slot));
        if (!canEdit()) {
            // Read only: put the control back.
            ui.set(sel + (isName ? " #Name.Value" : " #Color.Value"), saved);
            notice = I18n.tr("panel.status.readonly");
            noticeColor = WARN;
            status(ui);
            sendUpdate(ui);
            return;
        }
        String v = isName ? (validName(raw.trim()) ? raw.trim().toUpperCase(Locale.ROOT) : null) : paletteOf(raw);
        String[] pending = teamDraft.getOrDefault(slot, new String[2]);
        int part = isName ? 0 : 1;
        // An echo of the value already shown (Discard): nothing changed, so the notice stays.
        if (v != null && v.equals(pending[part] != null ? pending[part] : saved) && !WARN.equals(noticeColor)) return;
        if (v == null) {
            // Half typed or not valid: not kept (Save leaves that part as it is).
            pending[part] = null;
            notice = isName ? I18n.tr("panel.notice.teamnames") : null;
            noticeColor = WARN;
        } else {
            pending[part] = v.equals(saved) ? null : v;
            notice = null;
        }
        if (pending[0] == null && pending[1] == null) teamDraft.remove(slot);
        else teamDraft.put(slot, pending);
        if (!isName) {
            String shown = pending[1] != null ? pending[1] : saved;
            ui.setObject(sel + " #Swatch.Background", new PatchStyle().setColor(Value.of(Teams.PALETTE.get(shown))));
        }
        teamMark(ui, slot);
        status(ui);
        sendUpdate(ui);
    }

    /** Why the team rows can't be saved as they are (two teams with one name or one color), or null. */
    private Message teamDraftConflict() {
        Map<String, Integer> names = new HashMap<>();
        Map<String, Integer> colors = new HashMap<>();
        for (int slot = 0; slot < Teams.MAX; slot++) {
            if (!Teams.exists(slot)) continue;
            String[] pending = teamDraft.get(slot);
            String name = pending != null && pending[0] != null ? pending[0] : Teams.name(slot);
            String color = pending != null && pending[1] != null ? pending[1] : paletteName(Teams.color(slot));
            Integer other = names.put(name, slot);
            if (other != null) return I18n.tr("panel.notice.team.samename").param("name", name);
            other = colors.put(color, slot);
            if (other != null) return I18n.tr("panel.notice.team.samecolor").param("color", color);
        }
        return null;
    }

    /** Save changes: the pending team names and colors (checked by teamDraftConflict first). Returns how many rows. */
    private int applyTeamDraft() {
        int n = 0;
        // A swap (A <-> B) is valid as a whole but not one rename at a time: free the names first.
        for (var e : teamDraft.entrySet()) {
            if (Teams.exists(e.getKey()) && e.getValue()[0] != null) Teams.rename(e.getKey(), "MOBBATTLE_TMP" + e.getKey());
        }
        for (var e : teamDraft.entrySet()) {
            int slot = e.getKey();
            if (!Teams.exists(slot)) continue;
            String[] pending = e.getValue();
            if (pending[0] != null) Teams.rename(slot, pending[0]);
            if (pending[1] != null) Teams.recolor(slot, Teams.PALETTE.get(pending[1]));
            n++;
        }
        teamDraft.clear();
        return n;
    }

    private static String paletteName(String hex) {
        for (var e : Teams.PALETTE.entrySet()) if (e.getValue().equalsIgnoreCase(hex)) return e.getKey();
        return Teams.nearestPalette(hex);
    }

    /** First palette color no team uses (the add row starts there), else Red. */
    private static String freeColor() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int slot = 0; slot < Teams.MAX; slot++) if (Teams.exists(slot)) used.add(paletteName(Teams.color(slot)));
        for (String c : Teams.PALETTE.keySet()) if (!used.contains(c)) return c;
        return "Red";
    }

    private void teamRows(Rows rows) {
        rows.section(I18n.tr("panel.section.teamlist").param("count", Teams.count()).param("max", Teams.MAX));
        for (int slot = 0; slot < Teams.MAX; slot++) {
            if (!Teams.exists(slot)) continue;
            String name = Teams.name(slot);
            String sel = rows.add("TeamRow.ui");
            teamRowOf.put(slot, sel);
            String[] pending = teamDraft.get(slot);
            String color = pending != null && pending[1] != null ? pending[1] : paletteName(Teams.color(slot));
            rows.ui.setObject(sel + " #Swatch.Background", new PatchStyle().setColor(Value.of(Teams.PALETTE.get(color))));
            rows.ui.set(sel + " #Name.Value", pending != null && pending[0] != null ? pending[0] : name);
            rows.ui.set(sel + " #Color.Entries", entries(ConfigSchema.palette()));
            rows.ui.set(sel + " #Color.Value", color);
            teamMark(rows.ui, slot);
            rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Name",
                    data("TeamEdit").append("N", String.valueOf(slot)).append("K", "name").append("@V", sel + " #Name.Value"), false);
            rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Color",
                    data("TeamEdit").append("N", String.valueOf(slot)).append("K", "color").append("@V", sel + " #Color.Value"), false);
            rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Remove",
                    data("Act").append("A", "teamRemove").append("N", name), false);
            kitPicker(rows, sel, Teams.kit(slot), "teamKit", name);
            confirmButtons.put("teamRemove" + name, new Confirm(sel + " #Remove", () -> I18n.tr("panel.button.remove")));
        }
        rows.section(I18n.tr("panel.section.addteam"));
        String sel = rows.add("ActionPick.ui");
        I18n.text(rows.ui, sel + " #Label.Text", I18n.tr("panel.row.addteam.label"));
        rows.ui.set(sel + " #Name.Visible", true);
        rows.ui.set(sel + " #Input.Entries", entries(ConfigSchema.palette()));
        rows.ui.set(sel + " #Input.Value", freeColor());
        I18n.text(rows.ui, sel + " #Button.Text", I18n.tr("panel.button.addteam"));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "teamAdd").append("@N", sel + " #Name.Value").append("@V", sel + " #Input.Value"), false);
        rows.note(I18n.tr("panel.note.teamnames"));
        if (admin()) confirmable(rows, "teamReset", I18n.tr("panel.row.teamreset.label"), () -> I18n.tr("panel.button.teamreset"));
        galleryRows(rows);
    }

    /** Most mob types shown per team in the gallery (the rest are counted in the header). */
    private static final int GALLERY_TYPES = 24;

    /**
     * The player's Team Wand teams (their draft in this world): per team its color, name and size, and one chip per
     * mob type with its icon and how many ("x5"), so a big team stays one short row.
     */
    private void galleryRows(Rows rows) {
        rows.section(I18n.tr("panel.section.gallery"));
        BattleManager.Draft draft = BattleManager.existingDraft(playerRef.getUuid());
        boolean any = false;
        if (draft != null && draft.entityStore == store.getExternalData()) {
            for (int slot = 0; slot < draft.teams().size(); slot++) {
                if (!Teams.exists(slot)) continue;
                Map<String, Integer> types = new LinkedHashMap<>();
                int total = 0;
                for (Ref<EntityStore> r : draft.teams().get(slot)) {
                    if (r == null || !r.isValid() || r.getStore() != store) continue;
                    types.merge(ChaosWand.nameOf(r, store), 1, Integer::sum);
                    total++;
                }
                if (total == 0) continue;
                any = true;
                String sel = rows.add("TeamGallery.ui");
                rows.ui.setObject(sel + " #Swatch.Background", new PatchStyle().setColor(Value.of(Teams.color(slot))));
                I18n.text(rows.ui, sel + " #Label.Text", types.size() > GALLERY_TYPES
                        ? I18n.tr("panel.gallery.headtypes").param("name", Teams.name(slot)).param("count", total)
                                .param("types", types.size()).param("shown", GALLERY_TYPES)
                        : I18n.tr("panel.gallery.head").param("name", Teams.name(slot)).param("count", total));
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(types.entrySet());
                sorted.sort((a, b) -> b.getValue() - a.getValue());
                int k = 0;
                for (Map.Entry<String, Integer> e : sorted) {
                    if (k >= GALLERY_TYPES) break;
                    rows.ui.append(sel + " #Chips", DIR + "GalleryChip.ui");
                    String chip = sel + " #Chips[" + k++ + "]";
                    icon(rows.ui, chip + " #Icon", e.getKey());
                    rows.ui.set(chip + " #Count.Text", "x" + e.getValue());
                    String shortName = MobNames.text(e.getKey(), playerRef);
                    rows.ui.set(chip + " #Name.Text", shortName.length() > 12 ? shortName.substring(0, 11) + "." : shortName);
                }
            }
        }
        if (!any) rows.note(I18n.tr("panel.note.nogallery"));
    }

    private static List<ConfigSchema.Opt> teamOptions(boolean none) {
        List<ConfigSchema.Opt> out = new ArrayList<>();
        if (none) out.add(new ConfigSchema.Opt("none", "No team"));
        for (int slot = 0; slot < Teams.MAX; slot++) {
            if (Teams.exists(slot)) out.add(new ConfigSchema.Opt(Teams.name(slot), Teams.name(slot)));
        }
        return out;
    }

    /** More summon rows than this go into a scrolling box. */
    private static final int SUMMON_BOX_ROWS = 8;

    private void summonRows(Rows rows) {
        Map<String, String> list = SummonList.snapshot();
        rows.section(I18n.tr("panel.section.summonlist").param("count", list.size()));
        if (list.isEmpty()) rows.note(I18n.tr("panel.note.summonempty"));
        List<ConfigSchema.Opt> teams = teamOptions(true);
        // A long list scrolls in its own box (SummonBox.ui #List), as the Teams gallery's chips do.
        Rows into = list.size() > SUMMON_BOX_ROWS ? new Rows(rows.ui, rows.ev, rows.add("SummonBox.ui") + " #List") : rows;
        for (var e : list.entrySet()) {
            String role = e.getKey();
            String sel = into.add("SummonRow.ui");
            summonRowOf.put(role, sel);
            String[] pending = summonDraft.get(role);
            icon(rows.ui, sel + " #Icon", SummonList.roleOf(role));
            summonLabel(rows.ui, role);
            rows.ui.set(sel + " #Count.Value", pending != null && pending[0] != null ? pending[0] : e.getValue());
            rows.ui.set(sel + " #Team.Entries", entries(teams));
            rows.ui.set(sel + " #Team.Value", pending != null && pending[1] != null ? pending[1] : summonTeam(role));
            summonSwatch(rows.ui, sel, pending != null && pending[1] != null ? pending[1] : summonTeam(role));
            rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Count",
                    data("SumEdit").append("N", role).append("K", "count").append("@V", sel + " #Count.Value"), false);
            rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Team",
                    data("SumEdit").append("N", role).append("K", "team").append("@V", sel + " #Team.Value"), false);
            rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Remove",
                    data("Act").append("A", "summonRemove").append("N", role), false);
            kitPicker(rows, sel, SummonList.KIT_OF.get(role), "summonKit", role);
        }
        // under the list (outside its scrolling box): every row loses its team, the mobs stay (2026-10-17).
        // Always there with a list, shown while a row has a team, saved or picked (2026-10-18, playtest: after removing
        // the teams and picking new ones it never came back until the panel was reopened)
        if (!list.isEmpty()) {
            noTeamsRow = rows.action(I18n.tr("panel.row.summonnoteams.label"), I18n.tr("panel.button.summonnoteams"),
                    data("Act").append("A", "summonNoTeams"));
            rows.ui.set(noTeamsRow + ".Visible", anyTeam());
        }
        rows.section(I18n.tr("panel.section.addmob"));
        filterRow(rows, "summonAdd");
        String sel = rows.add("ActionPick.ui");
        filterTarget.put("summonAdd", sel);
        I18n.text(rows.ui, sel + " #Label.Text", I18n.tr("panel.row.addmob.label"));
        List<ConfigSchema.Opt> roles = filtered("summonAdd");
        rows.ui.set(sel + " #Input.Entries", entries(roles));
        // Nothing left by the filter: nothing to add (a dropdown has no proven Disabled property, the button has).
        rows.ui.set(sel + " #Button.Disabled", roles.isEmpty());
        // Starts on this player's last pick, else the plain test dummy (it never fights back: good for trying a mob),
        // else the first role.
        String[] last = LAST_SUMMON_PICK.get(playerRef.getUuid());
        String pick = last != null ? last[0] : DEFAULT_SUMMON_ROLE;
        String first = roles.isEmpty() ? null : roles.stream().map(ConfigSchema.Opt::value)
                .filter(pick::equals).findFirst().orElse(roles.stream().map(ConfigSchema.Opt::value)
                        .filter(DEFAULT_SUMMON_ROLE::equals).findFirst().orElse(roles.get(0).value()));
        if (first != null) rows.ui.set(sel + " #Input.Value", first);
        preview(rows, sel, "summonAdd", first);
        rows.ui.set(sel + " #Count.Visible", true);
        rows.ui.set(sel + " #Count.Value", last != null && last[1] != null ? last[1] : "1");
        rows.ui.set(sel + " #Extra.Visible", true);
        rows.ui.set(sel + " #Extra.Entries", entries(teams));
        String lastTeam = last != null && last[2] != null && teamOf(last[2]) != null ? teamOf(last[2]) : "none";
        rows.ui.set(sel + " #Extra.Value", lastTeam);
        bindSwatch(rows, "summonAdd", sel + " #Extra", sel + " #ExtraSwatch", lastTeam);
        I18n.text(rows.ui, sel + " #Button.Text", I18n.tr("panel.button.add"));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "summonAdd").append("@V", sel + " #Input.Value").append("@C", sel + " #Count.Value")
                        .append("@E", sel + " #Extra.Value"), false);
        // Mob gallery (2026-10-16, playtest: pictures instead of a text list, as a popup over the panel): tiles load only
        // when it opens; a tile puts its mob in the row above.
        rows.action(I18n.tr("panel.row.gallery.label"), I18n.tr("panel.button.gallery.show"), data("Act").append("A", "galleryOpen"));
        // summon fill (2026-10-14): random mobs that fight, of similar strength; replaces the list (Undo restores it).
        fillRow(rows);
        if (!list.isEmpty()) confirmable(rows, "summonClear", I18n.tr("panel.row.summonclear.label"), () -> I18n.tr("panel.button.summonclear"));
        rows.action(I18n.tr("panel.row.summonspawn.label"), I18n.tr("panel.button.summon"), data("Act").append("A", "summonSpawn"));
        // Saved lists (SummonLists, 2026-10-16, playtest: save my list 1, 2 with counts, colors and teams), as the presets
        // page: one row per list (name = load, Delete with confirm), then a name field + Save.
        // Newest first by default, or by name (2026-10-29, playtest: they came sorted by name only); past
        // SUMMON_BOX_ROWS they scroll in their own box, as a long summon list does.
        boolean byName = Boolean.TRUE.equals(LIST_SORT_BY_NAME.get(playerRef.getUuid()));
        List<String> saved = SummonLists.saved(!byName);
        rows.section(I18n.tr("panel.section.summonlists").param("count", saved.size()));
        rows.note(I18n.tr(saved.isEmpty() ? "panel.note.nosummonlists" : "panel.note.summonlistclick"));
        if (saved.size() > 1) {
            rows.action(I18n.tr(byName ? "panel.row.summonlistsort.name" : "panel.row.summonlistsort.date"),
                    I18n.tr(byName ? "panel.button.summonlistsort.date" : "panel.button.summonlistsort.name"),
                    data("Act").append("A", "summonListSort").append("N", byName ? "date" : "name"));
        }
        Rows listInto = saved.size() > SUMMON_BOX_ROWS ? new Rows(rows.ui, rows.ev, rows.add("SummonBox.ui") + " #List") : rows;
        for (String name : saved) {
            String row = listInto.buttons(new Message[] {Message.raw(name), I18n.tr("panel.button.delete")},
                    new EventData[] {data("Act").append("A", "summonListLoad").append("N", name),
                            data("Act").append("A", "summonListDelete").append("N", name)});
            confirmButtons.put("summonListDelete" + name, new Confirm(row + " #B1", () -> I18n.tr("panel.button.delete")));
        }
        String save = rows.add("ActionPick.ui");
        I18n.text(rows.ui, save + " #Label.Text", I18n.tr("panel.row.summonlistsave.label"));
        rows.ui.set(save + " #Name.Visible", true);
        rows.ui.set(save + " #Input.Visible", false);
        I18n.text(rows.ui, save + " #Button.Text", I18n.tr("panel.button.summonlistsave"));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, save + " #Button",
                data("Act").append("A", "summonListSave").append("@N", save + " #Name.Value"), false);
    }

    /** The mob the panel's "Add a mob" row starts on. */
    private static final String DEFAULT_SUMMON_ROLE = "Test_Dummy";

    /** The saved team of a listed role: its name, or "none". */
    private static String summonTeam(String role) {
        Integer now = SummonList.TEAMS.get(role);
        return now != null && Teams.exists(now) ? Teams.name(now) : "none";
    }

    /** A summon row's team color square, as on the Teams page (playtest 2026-10-13); hidden for no team. */
    private static void summonSwatch(UICommandBuilder ui, String sel, String team) {
        teamSwatch(ui, sel + " #Swatch", team);
    }

    /** A team's color square ({@code swatch} = its selector); hidden for no team. */
    private static void teamSwatch(UICommandBuilder ui, String swatch, String team) {
        int slot = team == null || "none".equalsIgnoreCase(team) ? -1 : Teams.find(team);
        ui.set(swatch + ".Visible", slot >= 0);
        if (slot >= 0) ui.setObject(swatch + ".Background", new PatchStyle().setColor(Value.of(Teams.color(slot))));
    }

    /** A team picker's color square, kept in step with the pick (playtest 2026-10-13: the color wherever a team is chosen). */
    private void bindSwatch(Rows rows, String key, String dropdown, String swatch, String team) {
        swatchOf.put(key, swatch);
        teamSwatch(rows.ui, swatch, team);
        rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, dropdown,
                data("Swatch").append("K", key).append("@V", dropdown + ".Value"), false);
    }

    /** Characters a summon row's name label holds in its two lines. */
    private static final int ROLE_LABEL_CHARS = 30;

    /** A summon row's mob name: gold with "* " while it has unsaved changes. */
    private void summonLabel(UICommandBuilder ui, String role) {
        String sel = summonRowOf.get(role);
        if (sel == null) return;
        boolean changed = summonDraft.containsKey(role);
        String id = SummonList.roleOf(role);
        String name = MobNames.text(id, playerRef);
        // The game's name, and the ID beside it in grey when it differs (Dungeon_Goblin_Burner is also "Goblin Burner").
        // Two lines at most (the row is 44 high, the label 170 wide): the ID only when it fits, a long name cut
        // (playtest screenshot, 2026-10-29: "Caballero de entrenamiento" + its ID ran over the next rows).
        String star = changed ? "* " : "";
        if (star.length() + name.length() > ROLE_LABEL_CHARS) name = name.substring(0, ROLE_LABEL_CHARS - star.length() - 1).trim() + "…";
        String also = name.equalsIgnoreCase(MobNames.pretty(id)) ? "" : "  " + MobNames.pretty(id);
        if (star.length() + name.length() + also.length() > ROLE_LABEL_CHARS) also = "";
        Message shown = changed ? Message.raw(star + name).color(GOLD) : Message.raw(name);
        ui.set(sel + " #Role.TextSpans", also.isEmpty() ? shown : Message.join(shown, Message.raw(also).color("#8a9bb0")));
    }

    /**
     * A summon row's count or team changed: kept in the draft (gold until Save changes), no rebuild. A value equal to
     * the saved one drops that part, so a dropdown echo or typing the old count back clears the mark.
     */
    /** A summon row has a team, saved or picked and not saved yet. */
    private boolean anyTeam() {
        for (String role : SummonList.snapshot().keySet()) {
            String[] pending = summonDraft.get(role);
            String team = pending != null && pending[1] != null ? pending[1] : summonTeam(role);
            if (team != null && !"none".equalsIgnoreCase(team)) return true;
        }
        return false;
    }

    /** The "remove every team" row shown or hidden as {@link #anyTeam} says. */
    private void noTeamsVisible(UICommandBuilder ui) {
        if (noTeamsRow != null) ui.set(noTeamsRow + ".Visible", anyTeam());
    }

    private void summonEdit(String name, String kind, String raw) {
        String role = listedRole(name);
        String sel = role != null ? summonRowOf.get(role) : null;
        if (sel == null || raw == null || !"count".equals(kind) && !"team".equals(kind)) return;
        UICommandBuilder ui = new UICommandBuilder();
        boolean isCount = "count".equals(kind);
        String saved = isCount ? SummonList.snapshot().get(role) : summonTeam(role);
        if (!canEdit()) {
            // Read only: put the control back.
            ui.set(sel + (isCount ? " #Count.Value" : " #Team.Value"), saved);
            if (!isCount) summonSwatch(ui, sel, saved);
            notice = I18n.tr("panel.status.readonly");
            noticeColor = WARN;
            status(ui);
            sendUpdate(ui);
            return;
        }
        String v = isCount ? count(raw) : "none".equalsIgnoreCase(raw) ? "none" : teamOf(raw);
        String[] pending = summonDraft.getOrDefault(role, new String[2]);
        int part = isCount ? 0 : 1;
        // An echo of the value already shown (Discard): nothing changed, so the notice stays.
        if (v != null && v.equals(pending[part] != null ? pending[part] : saved) && !WARN.equals(noticeColor)) return;
        if (v == null) {
            // Half typed or not valid: not kept (Save leaves that part as it is).
            pending[part] = null;
            notice = isCount ? I18n.tr("panel.notice.count").param("max", String.valueOf(FxSettings.MAX_MOBS_LIMIT)) : null;
            noticeColor = WARN;
        } else {
            pending[part] = v.equals(saved) ? null : v;
            notice = null;
        }
        if (pending[0] == null && pending[1] == null) summonDraft.remove(role);
        else summonDraft.put(role, pending);
        summonLabel(ui, role);
        if (!isCount) {
            summonSwatch(ui, sel, pending[1] != null ? pending[1] : saved);
            noTeamsVisible(ui);
        }
        status(ui);
        sendUpdate(ui);
    }

    /** Save changes: the pending summon rows go into the list (roles removed meanwhile are skipped). Returns how many. */
    private int applySummonDraft() {
        int n = 0;
        for (var e : summonDraft.entrySet()) {
            String role = listedRole(e.getKey());
            if (role == null) continue;
            String[] pending = e.getValue();
            if (pending[0] != null) SummonList.ENTRIES.put(role, pending[0]);
            if (pending[1] != null) {
                int team = "none".equals(pending[1]) ? -1 : BattleManager.teamIndex(pending[1]);
                if (team < 0) SummonList.TEAMS.remove(role);
                else SummonList.TEAMS.put(role, team);
            }
            n++;
        }
        summonDraft.clear();
        return n;
    }

    // ---- pick list filters (mod, and type for mobs; PickLists builds the lists once) ----

    private static List<PickLists.Entry> listOf(String key) {
        return switch (key) {
            case "summonAdd" -> PickLists.roles();
            case "morph" -> PickLists.models();
            default -> PickLists.effects();
        };
    }

    /** This player's filter of a list: {mod, type, group}, every mod, type and group at first. */
    private String[] filterOf(String key) {
        Map<String, String[]> mine = LAST_FILTER.get(playerRef.getUuid());
        String[] f = mine != null ? mine.get(key) : null;
        if (f == null) return new String[] {PickLists.ALL, PickLists.ALL, PickLists.ALL};
        return f.length >= 3 ? f : new String[] {f[0], f[1], PickLists.ALL};
    }

    /** The type filter of a list (mobs only; null for models and effects). */
    private static String typeOf(String key, String[] f) {
        return "summonAdd".equals(key) ? f[1] : null;
    }

    /** The options a list shows with this player's filter. */
    private List<ConfigSchema.Opt> filtered(String key) {
        String[] f = filterOf(key);
        return PickLists.filter(listOf(key), f[0], typeOf(key, f), f[2], "summonAdd".equals(key) ? playerRef.getLanguage() : null);
    }

    /** Filter row selector per list, to refill its group choices when the mod or type changes. */
    private final Map<String, String> filterRowOf = new HashMap<>();

    /**
     * The "Show" row above a long pick list: mod, for mobs what they do, and the group (the "Aquatic" of "Aquatic -
     * Bluegill"; its choices follow the other two, playtest 2026-10-13). Starts on the player's last filter.
     */
    private void filterRow(Rows rows, String key) {
        List<PickLists.Entry> all = listOf(key);
        List<ConfigSchema.Opt> mods = PickLists.modChoices(all);
        String[] f = filterOf(key);
        // A mod that is gone since (unloaded): back to every mod.
        if (mods.stream().noneMatch(o -> o.value().equalsIgnoreCase(f[0]))) f[0] = PickLists.ALL;
        boolean mobs = "summonAdd".equals(key);
        // Types follow the mod, groups follow both: a pick they no longer have goes back to "every".
        List<ConfigSchema.Opt> types = mobs ? PickLists.kindChoices(all, f[0]) : List.of();
        if (mobs && types.stream().noneMatch(o -> o.value().equalsIgnoreCase(f[1]))) f[1] = PickLists.ALL;
        List<ConfigSchema.Opt> groups = PickLists.groupChoices(all, f[0], typeOf(key, f));
        if (groups.stream().noneMatch(o -> o.value().equalsIgnoreCase(f[2]))) f[2] = PickLists.ALL;
        String sel = rows.add("PickFilter.ui");
        filterRowOf.put(key, sel);
        rows.ui.set(sel + " #Mod.Entries", entries(mods));
        rows.ui.set(sel + " #Mod.Value", f[0]);
        rows.ui.set(sel + " #Type.Visible", mobs);
        if (mobs) {
            rows.ui.set(sel + " #Type.Entries", entries(types));
            rows.ui.set(sel + " #Type.Value", f[1]);
        }
        rows.ui.set(sel + " #Group.Entries", entries(groups));
        rows.ui.set(sel + " #Group.Value", f[2]);
        for (String part : mobs ? new String[] {" #Mod", " #Type", " #Group"} : new String[] {" #Mod", " #Group"}) {
            rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + part,
                    data("Filter").append("K", key).append("@M", sel + " #Mod.Value")
                            .append("@Y", mobs ? sel + " #Type.Value" : sel + " #Mod.Value")
                            .append("@G", sel + " #Group.Value"), false);
        }
    }

    /** A filter changed: the list below shows what it keeps now, in place (no rebuild). */
    private void filterChanged(String key, String mod, String type, String group) {
        String sel = key != null ? filterTarget.get(key) : null;
        if (sel == null || mod == null) return;
        boolean mobs = "summonAdd".equals(key);
        String kind = mobs && type != null ? type : PickLists.ALL;
        String[] before = filterOf(key);
        // The client sends a refilled dropdown's value back: nothing changed, nothing to refill (audit 2026-10-17: each
        // echo resent the whole list, up to ~1000 gallery tiles).
        if (mod.equals(before[0]) && kind.equals(before[1]) && (group != null ? group : PickLists.ALL).equals(before[2])) return;
        UICommandBuilder ui = new UICommandBuilder();
        String rowSel = filterRowOf.get(key);
        // The type choices follow the mod: refilled when it changed, and a type it has no mobs of goes back to every
        // type (playtest 2026-10-16).
        if (mobs && !mod.equals(before[0])) {
            List<ConfigSchema.Opt> types = PickLists.kindChoices(listOf(key), mod);
            String wanted = kind;
            if (types.stream().noneMatch(o -> o.value().equalsIgnoreCase(wanted))) kind = PickLists.ALL;
            if (rowSel != null) {
                ui.set(rowSel + " #Type.Entries", entries(types));
                ui.set(rowSel + " #Type.Value", kind);
            }
        }
        // The group choices follow mod and type: refilled when one of them changed, and a group they no longer have
        // goes back to every group.
        String g = group != null ? group : PickLists.ALL;
        if (!mod.equals(before[0]) || !kind.equals(before[1])) {
            List<ConfigSchema.Opt> groups = PickLists.groupChoices(listOf(key), mod, mobs ? kind : null);
            String wanted = g;
            if (groups.stream().noneMatch(o -> o.value().equalsIgnoreCase(wanted))) g = PickLists.ALL;
            if (rowSel != null) {
                ui.set(rowSel + " #Group.Entries", entries(groups));
                ui.set(rowSel + " #Group.Value", g);
            }
        }
        LAST_FILTER.computeIfAbsent(playerRef.getUuid(), u -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(key, new String[] {mod, kind, g});
        List<ConfigSchema.Opt> opts = new ArrayList<>(filtered(key));
        ConfigSchema.Field field = FILTERED_FIELDS.contains(key)
                ? ConfigSchema.FIELDS.stream().filter(x -> x.key.equals(key)).findFirst().orElse(null) : null;
        if (field != null) {
            // A setting: its value stays as it is, listed even when the filter leaves it out.
            setInput(ui, sel, field, value(field, FxSettings.toProperties()));
        } else {
            ui.set(sel + " #Input.Entries", entries(opts));
            ui.set(sel + " #Button.Disabled", opts.isEmpty());
            // Keep the pick when the filter still lists it, else the first one left.
            String keep = mobs ? (LAST_SUMMON_PICK.containsKey(playerRef.getUuid()) ? LAST_SUMMON_PICK.get(playerRef.getUuid())[0] : null)
                    : (MorphWand.copyOf(playerRef.getUuid()) != null ? MorphWand.copyOf(playerRef.getUuid()).modelId() : null);
            String pick = opts.stream().map(ConfigSchema.Opt::value).filter(v -> v.equals(keep)).findFirst()
                    .orElse(opts.isEmpty() ? null : opts.get(0).value());
            if (pick != null) {
                ui.set(sel + " #Input.Value", pick);
                icon(ui, sel + " #Preview", pick);
            }
            notice = opts.isEmpty() ? I18n.tr("panel.notice.nomatch") : I18n.tr("panel.notice.matches").param("count", opts.size());
            noticeColor = opts.isEmpty() ? WARN : GRAY;
        }
        status(ui);
        if (mobs && galleryShown) {
            UIEventBuilder ev = new UIEventBuilder();
            if (rowSel != null) ui.set(rowSel + " #Mod.Value", mod);
            galleryFilters(ui);
            galleryFill(ui, ev);
            sendUpdate(ui, ev, false);
        } else {
            sendUpdate(ui);
        }
    }

    // ---- Summon list random fill (Config/FillRow.ui) ----

    /** Each player's last fill {types, per type, teams} (memory only); default 6 types of 3, no teams. */
    private static final Map<java.util.UUID, int[]> LAST_FILL = new java.util.concurrent.ConcurrentHashMap<>();
    /** Saved summon lists sorted by name (true) or newest first (absent/false), per player (memory only). */
    private static final Map<java.util.UUID, Boolean> LIST_SORT_BY_NAME = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One row instead of the old two Fill buttons (2026-10-16, playtest: "they do the same; let me set how many types, how
     * many of each, and a team count"): types, per type and teams (0 = no teams); the Show filters above (mod, type,
     * group) go with it. Runs /mobbattle summon fill, which replaces the list (Undo restores it).
     */
    private void fillRow(Rows rows) {
        int[] last = LAST_FILL.getOrDefault(playerRef.getUuid(), new int[] {6, 3, 0});
        String sel = rows.add("FillRow.ui");
        I18n.text(rows.ui, sel + " #Label.Text", I18n.tr("panel.row.summonfill.label"));
        I18n.text(rows.ui, sel + " #TypesLabel.Text", I18n.tr("panel.fill.types"));
        I18n.text(rows.ui, sel + " #EachLabel.Text", I18n.tr("panel.fill.each"));
        I18n.text(rows.ui, sel + " #TeamsLabel.Text", I18n.tr("panel.fill.teams"));
        I18n.text(rows.ui, sel + " #Button.Text", I18n.tr("panel.button.summonfill"));
        rows.ui.set(sel + " #Types.Value", String.valueOf(last[0]));
        rows.ui.set(sel + " #Each.Value", String.valueOf(last[1]));
        rows.ui.set(sel + " #Teams.Value", String.valueOf(last[2]));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "summonFill").append("@V", sel + " #Types.Value").append("@C", sel + " #Each.Value")
                        .append("@E", sel + " #Teams.Value"), false);
    }

    private void summonFill(String types, String each, String teams) {
        int t = parseInt(types, -1), e = parseInt(each, -1), k = parseInt(teams, 0);
        if (t < 1 || e < 1 || k < 0 || k == 1) {
            say(I18n.tr("panel.notice.fillnumbers"), WARN);
            return;
        }
        t = Math.min(t, MobBattleCommand.SUMMON_FILL_MAX);
        k = Math.min(k, 14);
        LAST_FILL.put(playerRef.getUuid(), new int[] {t, e, k});
        StringBuilder cmd = new StringBuilder("summon fill " + t + " each:" + e);
        if (k >= 2) cmd.append(" teams:").append(k);
        // The Show filters above narrow the pool too (mod, type, group).
        // Spaces go as '+' (mod names such as "Mob Battle" have them; the command splits words on spaces).
        String[] f = filterOf("summonAdd");
        if (f[0] != null && !PickLists.ALL.equals(f[0])) cmd.append(" mod:").append(f[0].replace(' ', '+'));
        if (f[1] != null && !PickLists.ALL.equals(f[1])) cmd.append(" type:").append(f[1]);
        if (f[2] != null && !PickLists.ALL.equals(f[2])) cmd.append(" group:").append(f[2].replace(' ', '+'));
        // The rows the fill replaces: their unsaved count/team edits would land on the new entries of the same key.
        summonDraft.clear();
        runList(cmd.toString());
    }

    // ---- Summon list gallery popup (Config/GalleryPopup.ui + SummonCell.ui) ----

    /** Whether the popup shows now, and its search text (kept while the panel is open). */
    private boolean galleryShown;
    private String gallerySearch = "";
    /** Filter events before this time are the client's echo of a refill of the popup's dropdowns. */
    private long galleryFilterEchoUntil;
    /** Longest name on a tile; longer ones are cut at a word with "..." so the tile never grows. */
    private static final int GALLERY_NAME_CHARS = 24;

    private void galleryBindings(UIEventBuilder ev) {
        ev.addEventBinding(CustomUIEventBindingType.ValueChanged, "#GallerySearch",
                data("GallerySearch").append("@V", "#GallerySearch.Value"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#GalleryClose", data("GalleryClose"), false);
        for (String part : new String[] {"#GMod", "#GType", "#GGroup"}) {
            ev.addEventBinding(CustomUIEventBindingType.ValueChanged, part, data("GalleryFilter").append("@M", "#GMod.Value")
                    .append("@Y", "#GType.Value").append("@G", "#GGroup.Value"), false);
        }
    }

    private void galleryOpen() {
        if (filterTarget.get("summonAdd") == null) return;
        galleryShown = true;
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder ev = new UIEventBuilder();
        ui.set("#GallerySearch.Value", gallerySearch);
        galleryFilters(ui);
        galleryFill(ui, ev);
        ui.set("#GalleryPopup.Visible", true);
        sendUpdate(ui, ev, false);
    }

    private void galleryClose() {
        galleryShown = false;
        galleryShownIds = null;
        UICommandBuilder ui = new UICommandBuilder();
        ui.set("#GalleryPopup.Visible", false);
        ui.clear("#GalleryGrid");
        sendUpdate(ui);
    }

    /** The popup's mod / type / group lists, as the Summon list's Show row (the same filter, shared). */
    private void galleryFilters(UICommandBuilder ui) {
        galleryFilterEchoUntil = System.currentTimeMillis() + 600;
        List<PickLists.Entry> all = listOf("summonAdd");
        String[] f = filterOf("summonAdd");
        ui.set("#GMod.Entries", entries(PickLists.modChoices(all)));
        ui.set("#GMod.Value", f[0]);
        ui.set("#GType.Entries", entries(PickLists.kindChoices(all, f[0])));
        ui.set("#GType.Value", f[1]);
        ui.set("#GGroup.Entries", entries(PickLists.groupChoices(all, f[0], f[1])));
        ui.set("#GGroup.Value", f[2]);
    }

    /** Every mob the Show filters keep and the search matches (ID, its words, the game's name), no cap. */
    private void galleryFill(UICommandBuilder ui, UIEventBuilder ev) {
        galleryFill(ui, ev, false);
    }

    /** The mobs the grid shows now (null = grid empty), so a search key that changes nothing sends nothing. */
    private List<String> galleryShownIds;

    /**
     * {@code onlyIfChanged}: false when the result is the one already shown (each key typed in the search resent up to
     * ~1000 tiles even when the list stayed the same; audit 2026-10-17).
     */
    private boolean galleryFill(UICommandBuilder ui, UIEventBuilder ev, boolean onlyIfChanged) {
        String q = norm(gallerySearch.trim());
        String lang = playerRef.getLanguage();
        List<String[]> hits = new ArrayList<>();
        for (ConfigSchema.Opt o : filtered("summonAdd")) {
            String id = o.value();
            String name = MobNames.text(id, lang);
            if (!q.isEmpty() && !galleryMatches(q, id, name, o.label())) continue;
            hits.add(new String[] {id, name});
        }
        List<String> ids = new ArrayList<>(hits.size());
        for (String[] h : hits) ids.add(h[0]);
        if (onlyIfChanged && ids.equals(galleryShownIds)) return false;
        galleryShownIds = ids;
        ui.clear("#GalleryGrid");
        int n = 0;
        for (String[] h : hits) {
            String id = h[0], name = h[1];
            ui.append("#GalleryGrid", DIR + "SummonCell.ui");
            String cell = "#GalleryGrid[" + n++ + "]";
            icon(ui, cell + " #Icon", id);
            ui.set(cell + " #Name.Text", cut(name, GALLERY_NAME_CHARS));
            ev.addEventBinding(CustomUIEventBindingType.Activating, cell + " #Button", data("GalleryPick").append("R", id), false);
        }
        // A plain String param (a Message param or an ICU plural inside .Text showed its raw form).
        I18n.text(ui, "#GalleryCount.Text", I18n.tr("gallery.count").param("total", String.valueOf(n)));
        return true;
    }

    private static boolean galleryMatches(String q, String id, String name, String label) {
        String hay = norm(id) + " " + norm(MobNames.pretty(id)) + " " + norm(name) + " " + norm(label);
        for (String w : q.split(" +")) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    /** "Goblin Gobliterator Phase One" -> "Goblin Gobliterator..." within {@code max} characters. */
    private static String cut(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int sp = s.lastIndexOf(' ', max - 3);
        return (sp > max / 2 ? s.substring(0, sp) : s.substring(0, max - 3)) + "...";
    }

    /** A tile clicked: its mob goes into the Add a mob row (count and team as they are), in place. */
    private void galleryPick(String picked) {
        String role = picked != null ? SummonList.findRole(picked) : null;
        String sel = filterTarget.get("summonAdd");
        if (role == null || sel == null) return;
        String[] last = LAST_SUMMON_PICK.get(playerRef.getUuid());
        LAST_SUMMON_PICK.put(playerRef.getUuid(), new String[] {role, last != null ? last[1] : null, last != null ? last[2] : null});
        UICommandBuilder ui = new UICommandBuilder();
        ui.set(sel + " #Input.Value", role);
        icon(ui, sel + " #Preview", role);
        galleryShown = false;
        galleryShownIds = null;
        ui.set("#GalleryPopup.Visible", false);
        ui.clear("#GalleryGrid");
        notice = I18n.tr("panel.notice.gallerypick").param("name", MobNames.text(role, playerRef));
        noticeColor = GOLD;
        status(ui);
        sendUpdate(ui);
    }

    // ---- icons (Icons/ModelsGenerated, as the Memories page) ----

    /** Preview icon rows by key ("morph", "summonAdd") -> the row's selector, for the Preview events. */
    private final Map<String, String> previewOf = new HashMap<>();
    /** Team pickers with a color square beside them (key -> the square's selector), updated as the pick changes. */
    private final Map<String, String> swatchOf = new HashMap<>();

    /** Shows the row's #Preview icon for {@code name} and updates it when another entry is picked. */
    private void preview(Rows rows, String sel, String key, String name) {
        previewOf.put(key, sel);
        rows.ui.set(sel + " #Preview.Visible", true);
        icon(rows.ui, sel + " #Preview", name);
        rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Input",
                data("Preview").append("K", key).append("@V", sel + " #Input.Value"), false);
    }

    /**
     * The game's generated icon of a mob or model (Common/Icons/ModelsGenerated/&lt;name&gt;.png, the path the
     * Memories page gives its AssetImage), or null: the AssetImage then shows its fallback.
     */
    /** The game's or our icon of exactly this model, never a relative's guess (for the VS screen); null if none. */
    static String ownIconOf(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_\\-]+")) return null;
        String declared = declaredIcon(name);
        if (declared != null) return declared;
        if (iconNames().contains(name)) return ICON_DIR + name + ".png";
        String own = ThumbnailCache.iconFor(name);
        return own != null ? own : VsScreen.iconFor(name);
    }

    static String iconOf(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_\\-]+")) return null;
        String declared = declaredIcon(name);
        if (declared != null) return declared;
        java.util.NavigableSet<String> icons = iconNames();
        String[] tries = {name, name.replaceFirst("^(Tamed|Template)_", ""), name.replaceFirst("_(Calf|Piglet|Cub|Baby|Juvenile)$", "")};
        for (String t : tries) if (icons.contains(t)) return ICON_DIR + t + ".png";
        // No game icon (modded mobs such as SLB_Nightlord, Test_Dummy...): our own, drawn from its model and
        // registered at boot for every model without one (ThumbnailCache icons, 2026-10-16, playtest: all mobs); else its
        // VS screen picture.
        String own = ThumbnailCache.iconFor(name);
        if (own != null) return own;
        String vs = VsScreen.iconFor(name);
        if (vs != null) return vs;
        // No icon of its own (the game has none for e.g. Skeleton): a relative's, dropping name parts from the end.
        // Skeleton -> Skeleton_Soldier, Goblin_Boss -> a Goblin_*. Still none: the AssetImage fallback.
        String t = tries[1];
        while (t != null) {
            if (icons.contains(t)) return ICON_DIR + t + ".png";
            for (String s : RELATIVES) if (icons.contains(t + "_" + s)) return ICON_DIR + t + "_" + s + ".png";
            String next = icons.ceiling(t + "_");
            if (next != null && next.startsWith(t + "_")) return ICON_DIR + next + ".png";
            int cut = t.lastIndexOf('_');
            t = cut > 0 ? t.substring(0, cut) : null;
        }
        return null;
    }

    /** Whether the game has an icon of this model itself (declared, or Icons/ModelsGenerated/&lt;id&gt;.png). */
    static boolean hasGameIcon(String model) {
        return model != null && (declaredIcon(model) != null || iconNames().contains(model));
    }

    /**
     * The icon the model asset declares itself (ModelAsset.getIcon, what the vanilla Capture Crate shows), when it
     * exists in Common. {@code name} is tried as a model id, then without its Tamed_/Template_ prefix.
     */
    private static String declaredIcon(String name) {
        // A role name (summon rows, team gallery) usually differs from its model id: also try the role's Appearance.
        String[] ids = {name, name.replaceFirst("^(Tamed|Template)_", ""), RoleAppearance.of(name)};
        try {
            for (String id : ids) {
                if (id == null) continue;
                ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
                if (asset == null) continue;
                String icon = asset.getIcon();
                if (icon == null || icon.isEmpty()) continue;
                if (icon.startsWith("Common/")) icon = icon.substring("Common/".length());
                if (com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.hasCommonAsset(icon)) return icon;
            }
        } catch (RuntimeException ignored) {
            // assets not loaded yet: the name guessing below still runs
        }
        return null;
    }

    private static final String ICON_DIR = "Icons/ModelsGenerated/";
    /** No icon of any kind: the VS screen's big "?" (2026-10-17, playtest: one fallback everywhere, not the small red one). */
    private static final String MISSING_ICON = VsScreen.UNKNOWN;
    /** Preferred relatives when a model has no icon (Skeleton -> Skeleton_Soldier rather than Skeleton_Archer). */
    private static final String[] RELATIVES = {"Soldier", "Fighter", "Adult", "Male", "Female", "Default", "Warrior", "Grunt", "Scout"};
    private static volatile java.util.NavigableSet<String> iconCache;

    /** Names of every model icon (Icons/ModelsGenerated/*.png), read once from the loaded common assets. */
    private static java.util.NavigableSet<String> iconNames() {
        java.util.NavigableSet<String> cached = iconCache;
        if (cached != null) return cached;
        java.util.NavigableSet<String> out = new java.util.TreeSet<>();
        try {
            for (var list : com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.getAllAssets()) {
                for (var pa : list) {
                    String n = pa.asset().getName();
                    if (n != null && n.startsWith(ICON_DIR) && n.endsWith(".png")) {
                        out.add(n.substring(ICON_DIR.length(), n.length() - 4));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // assets not loaded yet: try again next time
        }
        if (!out.isEmpty()) iconCache = out;
        return out;
    }

    static void icon(UICommandBuilder ui, String selector, String name) {
        String path = iconOf(name);
        // Nothing found: the VS screen's "?" picture. setNull left an empty box in game (the fallback did not show).
        ui.set(selector + ".AssetPath", path != null ? path : MISSING_ICON);
    }

    private void presetRows(Rows rows) {
        rows.section(I18n.tr("panel.section.builtin"));
        rows.note(I18n.tr("panel.note.builtin"));
        String[] builtIn = Presets.BUILT_IN;
        for (int i = 0; i < builtIn.length; i += 4) {
            int n = Math.min(4, builtIn.length - i);
            Message[] labels = new Message[n];
            EventData[] data = new EventData[n];
            for (int j = 0; j < n; j++) {
                // The preset names are what the player types (/mobbattle preset fight): not translated.
                labels[j] = Message.raw(ConfigSchema.label(builtIn[i + j]));
                data[j] = data("Act").append("A", "presetLoad").append("N", builtIn[i + j]);
            }
            rows.buttons(labels, data);
        }
        List<String> saved = Presets.saved();
        rows.section(I18n.tr("panel.section.yourpresets").param("count", saved.size()));
        if (saved.isEmpty()) rows.note(I18n.tr("panel.note.nopresets"));
        else rows.note(I18n.tr("panel.note.presetclick"));
        // Button texts: the name as plain text and a plain "Delete" (audit 2026-10-14: a translation with parameters is
        // only proven on labels, and a .Text the client can't apply disconnects the player).
        for (String name : saved) {
            String row = rows.buttons(new Message[] {Message.raw(name), I18n.tr("panel.button.delete")},
                    new EventData[] {data("Act").append("A", "presetLoad").append("N", name),
                            data("Act").append("A", "presetDelete").append("N", name)});
            confirmButtons.put("presetDelete" + name, new Confirm(row + " #B1", () -> I18n.tr("panel.button.delete")));
        }
        String sel = rows.add("ActionPick.ui");
        I18n.text(rows.ui, sel + " #Label.Text", I18n.tr("panel.row.presetsave.label"));
        rows.ui.set(sel + " #Name.Visible", true);
        rows.ui.set(sel + " #Input.Visible", false);
        I18n.text(rows.ui, sel + " #Button.Text", I18n.tr("panel.button.presetsave"));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "presetSave").append("@N", sel + " #Name.Value"), false);
        rows.note(I18n.tr("panel.note.presetholds"));
        rows.section(I18n.tr("panel.section.backdefaults"));
        String[] groups = Presets.GROUPS;
        for (int i = 0; i < groups.length; i += 4) {
            int n = Math.min(4, groups.length - i);
            Message[] labels = new Message[n];
            EventData[] data = new EventData[n];
            for (int j = 0; j < n; j++) {
                labels[j] = I18n.tr("all".equals(groups[i + j]) ? "panel.button.resetall" : "panel.button.reset." + groups[i + j]);
                data[j] = data("Act").append("A", "resetLoad").append("N", groups[i + j]);
            }
            rows.buttons(labels, data);
        }
    }

    private void toolRows(Rows rows) {
        rows.section(I18n.tr("panel.section.toolswatch"));
        rows.action(I18n.tr("panel.row.watch.label"), I18n.tr("panel.button.watch"), data("Act").append("A", "watch"));
        rows.action(I18n.tr("panel.row.watchexit.label"), I18n.tr("panel.button.watchexit"), data("Act").append("A", "watchExit"));
        rows.action(I18n.tr("panel.row.skipcamera.label"), I18n.tr("panel.button.skipcamera"), data("Act").append("A", "skipCamera"));
        rows.section(I18n.tr("panel.section.toolsbattles"));
        rows.action(I18n.tr("panel.row.snap.label"), I18n.tr("panel.button.snap"), data("Act").append("A", "snap"));
        rows.action(I18n.tr("panel.row.chaosme.label"), I18n.tr("panel.button.chaosme"), data("Act").append("A", "chaosMe"));
        rows.action(I18n.tr("panel.row.debugnpcs.label"), I18n.tr("panel.button.debugnpcs"), data("Act").append("A", "debugNpcs"));
        rows.section(I18n.tr("panel.section.toolsteams"));
        rows.action(I18n.tr("panel.row.teamsauto.label"), I18n.tr("panel.button.teamsauto"), data("Act").append("A", "teamsAuto"));
        rows.action(I18n.tr("panel.row.teamsstart.label"), I18n.tr("panel.button.teamsstart"), data("Act").append("A", "teamsStart"));
        String sel = rows.add("ActionPick.ui");
        I18n.text(rows.ui, sel + " #Label.Text", I18n.tr("panel.row.teamsjoin.label"));
        rows.ui.set(sel + " #Input.Entries", entries(teamOptions(false)));
        rows.ui.set(sel + " #Input.Value", Teams.name(firstTeam()));
        bindSwatch(rows, "teamsJoin", sel + " #Input", sel + " #InputSwatch", Teams.name(firstTeam()));
        I18n.text(rows.ui, sel + " #Button.Text", I18n.tr("panel.button.teamsjoin"));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "teamsJoin").append("@V", sel + " #Input.Value"), false);
        rows.action(I18n.tr("panel.row.teamsleave.label"), I18n.tr("panel.button.teamsleave"), data("Act").append("A", "teamsLeave"));
        rows.action(I18n.tr("panel.row.teamsclear.label"), I18n.tr("panel.button.teamsclear"), data("Act").append("A", "teamsClear"));
    }

    private static int firstTeam() {
        for (int slot = 0; slot < Teams.MAX; slot++) if (Teams.exists(slot)) return slot;
        return 0;
    }

    /** Admin tab: /mobbattle apply from the panel (who, action, value). */
    private void applyRow(Rows rows) {
        rows.section(I18n.tr("panel.section.apply"));
        String sel = rows.add("ActionPick.ui");
        I18n.text(rows.ui, sel + " #Label.Text", I18n.tr("panel.row.apply.label"));
        List<ConfigSchema.Opt> who = new ArrayList<>(List.of(new ConfigSchema.Opt("me", "Me"),
                new ConfigSchema.Opt("look", "What I look at"), new ConfigSchema.Opt("near 12", "Everyone within 12 blocks"),
                new ConfigSchema.Opt("players", "Every player in this world")));
        for (var p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
            who.add(new ConfigSchema.Opt(p.getUsername(), "Player - " + p.getUsername()));
        }
        rows.ui.set(sel + " #Input.Entries", entries(who));
        rows.ui.set(sel + " #Input.Value", "me");
        rows.ui.set(sel + " #Extra.Visible", true);
        List<ConfigSchema.Opt> actions = new ArrayList<>();
        for (String a : ApplyCommand.ACTIONS) actions.add(new ConfigSchema.Opt(a, a));
        rows.ui.set(sel + " #Extra.Entries", entries(actions));
        rows.ui.set(sel + " #Extra.Value", "heal");
        rows.ui.set(sel + " #Count.Visible", true);
        rows.ui.set(sel + " #Count.Value", "");
        I18n.text(rows.ui, sel + " #Button.Text", I18n.tr("panel.button.apply"));
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "apply").append("@V", sel + " #Input.Value").append("@C", sel + " #Count.Value")
                        .append("@E", sel + " #Extra.Value"), false);
    }

    private void adminRows(Rows rows) {
        rows.section(I18n.tr("panel.section.permissions").param("mode", FxSettings.permissionsMode));
        rows.note(I18n.tr("panel.note.permissions"));
        // The modes are what the player types (/mobbattle permissions open): not translated.
        rows.buttons(new Message[] {Message.raw("Open"), Message.raw("Server")}, new EventData[] {
                data("Act").append("A", "permissions").append("N", "open"),
                data("Act").append("A", "permissions").append("N", "server")});
        rows.section(I18n.tr("panel.section.server"));
        rows.action(I18n.tr("panel.row.reload.label"), I18n.tr("panel.button.reload"), data("Act").append("A", "reload"));
        rows.action(I18n.tr("panel.row.give.label"), I18n.tr("panel.button.give"), data("Act").append("A", "give"));
        rows.action(I18n.tr("panel.row.skiptimer.label"), I18n.tr("panel.button.skiptimer"), data("Act").append("A", "skipTimer"));
        rows.action(I18n.tr("panel.row.eventroyale.label"), I18n.tr("panel.button.eventroyale"), data("Act").append("A", "eventRoyale"));
        rows.action(I18n.tr("panel.row.debugdump.label"), I18n.tr("panel.button.debugdump"), data("Act").append("A", "debugDump"));
        // mob pictures (2026-10-17): what is cached and sent, and redraw them with progress in chat
        rows.action(I18n.tr("panel.row.picturesstatus.label"), I18n.tr("panel.button.picturesstatus"), data("Act").append("A", "picturesStatus"));
        rows.action(I18n.tr("panel.row.picturesrebuild.label"), I18n.tr("panel.button.picturesrebuild"), data("Act").append("A", "picturesRebuild"));
        rows.action(I18n.tr("panel.row.measure.label"), I18n.tr("panel.button.measure"), data("Act").append("A", "measure"));
        applyRow(rows);
        rows.section(I18n.tr("panel.section.cleanup"));
        confirmable(rows, "stop", I18n.tr("panel.row.stop.label"), () -> I18n.tr("panel.button.stop"));
        confirmable(rows, "armyClear", I18n.tr("panel.row.armyclear.label"), () -> I18n.tr("panel.button.armyclear"));
        confirmable(rows, "teamsClearAll", I18n.tr("panel.row.teamsclearall.label"), () -> I18n.tr("panel.button.teamsclearall"));
        confirmable(rows, "morphReset", I18n.tr("panel.row.morphreset.label"), () -> I18n.tr("panel.button.morphreset"));
        confirmable(rows, "resetAll", I18n.tr("panel.row.resetall.label"), () -> I18n.tr("panel.button.resetall"));
    }

    // ---- events ----

    private static EventData data(String type) {
        return EventData.of("T", type);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> st, String raw) {
        if (ref != entity || st != store || store.isShutdown() || !entity.isValid()) return;
        Player p = store.getComponent(entity, Player.getComponentType());
        if (p == null || p.getPageManager().getCustomPage() != this
                || store.getComponent(entity, PlayerRef.getComponentType()) != playerRef) return;
        BsonDocument doc;
        try {
            doc = BsonDocument.parse(raw);
        } catch (RuntimeException e) {
            return;
        }
        String type = str(doc, "T");
        if (type == null) return;
        switch (type) {
            case "Nav" -> nav(parseInt(str(doc, "P"), -1));
            case "Search" -> search(str(doc, "@V"));
            case "Preview" -> {
                String sel = previewOf.get(str(doc, "K"));
                if (sel == null) return;
                if ("summonAdd".equals(str(doc, "K"))) {
                    // A mob picked but not added yet also stays picked when the page refreshes.
                    String role = SummonList.findRole(str(doc, "@V"));
                    String[] last = LAST_SUMMON_PICK.get(playerRef.getUuid());
                    if (role != null) LAST_SUMMON_PICK.put(playerRef.getUuid(),
                            new String[] {role, last != null ? last[1] : null, last != null ? last[2] : null});
                }
                UICommandBuilder ui = new UICommandBuilder();
                icon(ui, sel + " #Preview", str(doc, "@V"));
                sendUpdate(ui);
            }
            case "Swatch" -> {
                String swatch = swatchOf.get(str(doc, "K"));
                if (swatch == null) return;
                UICommandBuilder ui = new UICommandBuilder();
                teamSwatch(ui, swatch, str(doc, "@V"));
                sendUpdate(ui);
            }
            case "Set" -> set(parseInt(str(doc, "F"), -1), str(doc, "@V"));
            case "FilmShotsDefault" -> filmShotsDefault();
            case "FilmStyle" -> filmStyle(str(doc, "N"));
            case "GalleryPick" -> galleryPick(str(doc, "R"));
            case "GallerySearch" -> {
                String v = str(doc, "@V");
                if (v == null || v.equals(gallerySearch) || !galleryShown) return;
                gallerySearch = v;
                UICommandBuilder ui = new UICommandBuilder();
                UIEventBuilder ev = new UIEventBuilder();
                if (galleryFill(ui, ev, true)) sendUpdate(ui, ev, false);
            }
            case "Filter" -> {
                // While the gallery popup covers it, the Show row only echoes the popup's refills (which set it too):
                // an echo with its old mod switched the popup's pick straight back (audit 2026-10-17).
                if (galleryShown && "summonAdd".equals(str(doc, "K"))) return;
                filterChanged(str(doc, "K"), str(doc, "@M"), str(doc, "@Y"), str(doc, "@G"));
            }
            case "GalleryClose" -> galleryClose();
            case "GalleryFilter" -> {
                // Refilling the popup's dropdowns makes the client send their first entry back: not a real pick.
                if (!galleryShown || System.currentTimeMillis() < galleryFilterEchoUntil) return;
                filterChanged("summonAdd", str(doc, "@M"), str(doc, "@Y"), str(doc, "@G"));
            }
            case "Save" -> save();
            case "SumEdit" -> summonEdit(str(doc, "N"), str(doc, "K"), str(doc, "@V"));
            case "TeamEdit" -> teamEdit(parseInt(str(doc, "N"), -1), str(doc, "K"), str(doc, "@V"));
            case "Undo" -> undo();
            case "Close" -> close();
            case "Discard" -> {
                if (draft.isEmpty() && summonDraft.isEmpty() && teamDraft.isEmpty()) return;
                Map<Integer, String> fields = new LinkedHashMap<>(draft);
                List<String> roles = new ArrayList<>(summonDraft.keySet());
                List<Integer> slots = new ArrayList<>(teamDraft.keySet());
                draft.clear();
                summonDraft.clear();
                teamDraft.clear();
                notice = I18n.tr("panel.notice.discarded");
                noticeColor = GRAY;
                refreshInPlace(fields, roles, slots, true);
            }
            case "Act" -> act(str(doc, "A"), str(doc, "N"), str(doc, "@V"), str(doc, "@N"), str(doc, "@C"), str(doc, "@E"));
            default -> { }
        }
    }

    /** Any JSON value as text (checkbox true/false, number fields 12 or 12.0, strings). */
    private static String str(BsonDocument doc, String key) {
        BsonValue v = doc.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isString()) return v.asString().getValue();
        if (v.isBoolean()) return String.valueOf(v.asBoolean().getValue());
        if (v.isInt32()) return String.valueOf(v.asInt32().getValue());
        if (v.isInt64()) return String.valueOf(v.asInt64().getValue());
        if (v.isDouble()) return String.valueOf(v.asDouble().getValue());
        return null;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? (int) Math.round(d) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void nav(int target) {
        if (target < 0 || target >= ConfigSchema.PAGES.size()) return;
        if (ConfigSchema.PAGES.get(target).adminOnly() && !admin()) return;
        if (target == page && !searching()) return;
        page = target;
        query = "";
        LAST_PAGE.put(playerRef.getUuid(), target);
        notice = null;
        rebuild();
    }

    private void set(int index, String raw) {
        ConfigSchema.Field f = ConfigSchema.field(index);
        String sel = f != null ? rowOf.get(index) : null;
        if (sel == null || raw == null) return;
        Properties live = FxSettings.toProperties();
        UICommandBuilder ui = new UICommandBuilder();
        if (!canEdit() || f.admin && !admin()) {
            // Read only: put the control back.
            setInput(ui, sel, f, value(f, live));
            notice = f.admin ? I18n.tr("panel.notice.adminonly").param("label", f.label()) : I18n.tr("panel.status.readonly");
            noticeColor = WARN;
            status(ui);
            sendUpdate(ui);
            return;
        }
        boolean badMultiplier = false;
        if (f.buffStat != null && multiplier(live)) {
            // Typed as a multiplier: kept as percent like every other buff value. Below x1 is refused (it read as a
            // percent of 0.5 before).
            int pct = Buffs.parseValue(f.buffStat, raw.trim().startsWith("x") ? raw : "x" + raw.trim());
            if (pct >= 0) raw = String.valueOf(pct);
            else badMultiplier = true;
        }
        String v = badMultiplier ? null : f.canonical(raw);
        if (v == null) {
            draft.remove(index);
            Message shownHint = f.buffStat != null ? ConfigSchema.buffHint(f, multiplier(live)) : f.hasHint() ? f.hint() : null;
            notice = (shownHint == null ? I18n.tr("panel.notice.invalid.nohint") : I18n.tr("panel.notice.invalid").param("hint", shownHint))
                    .param("label", f.label()).param("value", raw.trim());
            noticeColor = WARN;
        } else {
            boolean clamped = f.type == ConfigSchema.Type.NUMBER && parseInt(raw, Integer.MIN_VALUE) != parseInt(v, 0);
            // The control echoing a value the panel put there itself (Discard, Undo): nothing changed, so the notice stays.
            if (!clamped && v.equals(value(f, live)) && !WARN.equals(noticeColor)) return;
            if (v.equals(saved(f, live))) draft.remove(index);
            else draft.put(index, v);
            notice = null;
            // The size warning is a text made in Buffs (English, shared with the command).
            String sizeWarn = "buff.size.max".equals(f.key) ? Buffs.sizeWarning(parseInt(v, 0)) : null;
            Message warn = sizeWarn != null ? I18n.tr("panel.notice.warning").param("text", sizeWarn)
                    : Limits.warning(f.key, v);
            if (warn != null) {
                notice = warn;
                noticeColor = WARN;
            }
            // A number out of range is shown clamped.
            if (clamped) setInput(ui, sel, f, v);
            previewArea(f, v, live);
            if ("buff.unit".equals(f.key)) refreshBuffRows(ui, live);
        }
        label(ui, f);
        status(ui);
        sendUpdate(ui);
    }

    /** Radius fields drawn around the player as soon as they change (the draft value, before Save). */
    private static final java.util.Set<String> AREA_PREVIEW = java.util.Set.of("chaos.radius", "battle.arena.size",
            "battle.shrink.to", "summon.radius", "snap.radius", "morph.burst.radius", "cleanse.area.radius", "effect.area.radius", "heal.area.radius", "kill.area.radius", "chaos.area", "chaos.area.color");

    /**
     * Area radius, arena sizes, Snap reach, summon area, area look and color: the circle is drawn around the player
     * right away (only for them, 6 s, replacing the last one), so a range is seen while editing it, without saving
     * (as /mobbattle areaRadius does). Look and color come from the draft too. World thread (handleDataEvent).
     */
    private void previewArea(ConfigSchema.Field f, String v, Properties live) {
        boolean kindStyle = KindFx.isOverrideKey(f.key) && f.key.endsWith("." + KindFx.Opt.AREA_STYLE.key());
        if (!AREA_PREVIEW.contains(f.key) && !kindStyle) return;
        int radius = parseInt(f.key.startsWith("chaos.") || kindStyle ? valueOf("chaos.radius", live) : v, 0);
        if (radius <= 0) return; // 0 = auto: no fixed circle to show
        String style = kindStyle && !v.isEmpty() ? v : valueOf("chaos.area", live);
        String color = valueOf("chaos.area.color", live);
        if (!Teams.PALETTE.containsKey(color)) color = FxSettings.pickAreaColorName();
        PlayerRef viewer = store.getComponent(entity, PlayerRef.getComponentType());
        TransformComponent t = store.getComponent(entity, TransformComponent.getComponentType());
        if (viewer == null || t == null) return;
        lastPreview = FightFx.previewArea(viewer, new org.joml.Vector3d(t.getPosition()), radius, style, color, lastPreview, store);
    }

    /** The value shown for the field with {@code key} (draft, else saved). */
    private String valueOf(String key, Properties live) {
        for (ConfigSchema.Field f : ConfigSchema.FIELDS) {
            if (f.key.equals(key)) return value(f, live);
        }
        return live.getProperty(key, "");
    }

    private void save() {
        if (draft.isEmpty() && summonDraft.isEmpty() && teamDraft.isEmpty()) return;
        if (!canEdit()) {
            say(I18n.tr("panel.notice.cantsave"), WARN);
            return;
        }
        Message conflict = teamDraftConflict();
        if (conflict != null) {
            say(Message.join(conflict, Message.raw(" "), I18n.tr("panel.notice.nothingsaved")), WARN);
            return;
        }
        Properties before = FxSettings.toProperties();
        Properties undoTarget = beforeLastSave;
        Map<Integer, String> fields = new LinkedHashMap<>(draft);
        List<String> roles = new ArrayList<>(summonDraft.keySet());
        // A team rename changes the Remove bindings and every team dropdown: that one needs a rebuild.
        boolean teams = !teamDraft.isEmpty();
        // List rows first, so the settings below are written with the new lists. Summon rows before the teams: their
        // team names are the ones shown when they were picked.
        int rows = applySummonDraft() + applyTeamDraft();
        if (draft.isEmpty()) {
            FxSettings.save();
            rememberSave(before, undoTarget);
            MobBattlePlugin.log("Settings panel: " + playerRef.getUsername() + " saved " + rows + " list row(s)");
            notice = I18n.tr("panel.notice.saved").param("count", rows);
            noticeColor = "#7dff8a";
            if (teams) rebuild();
            else refreshInPlace(fields, roles, List.of(), false);
            return;
        }
        boolean admin = admin();
        Properties p = FxSettings.toProperties();
        int n = 0;
        for (var e : draft.entrySet()) {
            ConfigSchema.Field f = ConfigSchema.field(e.getKey());
            if (f == null || f.admin && !admin) continue;
            if (e.getValue().isEmpty() && KindFx.isOverrideKey(f.key)) p.remove(f.key);
            else p.setProperty(f.key, e.getValue());
            n++;
        }
        // Never through the panel: the permission mode has its own admin action.
        p.setProperty(FxSettings.PERMISSIONS_KEY, FxSettings.permissionsMode);
        FxSettings.applyAll(p);
        rememberSave(before, undoTarget);
        draft.clear();
        n += rows;
        MobBattlePlugin.log("Settings panel: " + playerRef.getUsername() + " saved " + n + " change(s)");
        notice = I18n.tr("panel.notice.saved").param("count", n);
        noticeColor = "#7dff8a";
        if (teams) rebuild();
        else refreshInPlace(fields, roles, List.of(), false);
    }

    /**
     * After Save, Discard or Undo: only the rows that changed are put back to what they show now (draft, else saved)
     * and the labels lose or get their gold, with no rebuild, so the page keeps its scroll (a rebuild sends it back to
     * the top). {@code fields} maps each field to the value the client shows now (null = unknown): a control already
     * showing the right value is not set again. {@code roles} and {@code slots} get their summon/team controls set
     * only with {@code listInputs} (Discard); after a Save they already show the saved values, so only the marks go.
     * Rows not on the open page are skipped.
     */
    /** The film shot fields back to FxSettings.FILM_DEFAULT_WEIGHTS in the draft (Save applies them); other edits stay. */
    private void filmShotsDefault() {
        if (!canEdit()) {
            say(I18n.tr("panel.status.readonly"), WARN);
            return;
        }
        Properties live = FxSettings.toProperties();
        Map<Integer, String> shown = new LinkedHashMap<>();
        for (ConfigSchema.Field f : ConfigSchema.FIELDS) {
            if (!f.key.startsWith(FILM_WEIGHT)) continue;
            String v = String.valueOf(FxSettings.FILM_DEFAULT_WEIGHTS.getOrDefault(f.key.substring(FILM_WEIGHT.length()), 100));
            shown.put(f.index, "");
            if (v.equals(saved(f, live))) draft.remove(f.index);
            else draft.put(f.index, v);
        }
        notice = I18n.tr("panel.notice.filmshots.default");
        noticeColor = GOLD;
        refreshInPlace(shown, List.of(), List.of(), false);
    }

    /** A film style's settings into the draft (Save applies them); other edits stay. */
    private void filmStyle(String name) {
        if (!canEdit()) {
            say(I18n.tr("panel.status.readonly"), WARN);
            return;
        }
        Map<String, String> style = FilmStyles.of(name);
        if (style == null) return;
        Properties live = FxSettings.toProperties();
        Map<Integer, String> shown = new LinkedHashMap<>();
        for (ConfigSchema.Field f : ConfigSchema.FIELDS) {
            String v = style.get(f.key);
            if (v == null) continue;
            shown.put(f.index, "");
            if (v.equals(saved(f, live))) draft.remove(f.index);
            else draft.put(f.index, v);
        }
        notice = I18n.tr("panel.notice.filmstyle").param("style", styleName(name.toLowerCase(java.util.Locale.ROOT)));
        noticeColor = GOLD;
        refreshInPlace(shown, List.of(), List.of(), false);
    }

    private static final String FILM_WEIGHT = "camera.film.weight.";

    private void refreshInPlace(Map<Integer, String> fields, java.util.Collection<String> roles, java.util.Collection<Integer> slots,
            boolean listInputs) {
        Properties live = FxSettings.toProperties();
        UICommandBuilder ui = new UICommandBuilder();
        for (var e : fields.entrySet()) {
            ConfigSchema.Field f = ConfigSchema.field(e.getKey());
            String sel = f != null ? rowOf.get(f.index) : null;
            if (sel == null) continue;
            String v = value(f, live);
            if (!v.equals(e.getValue())) setInput(ui, sel, f, v);
            label(ui, f);
            if ("buff.unit".equals(f.key)) refreshBuffRows(ui, live);
        }
        Map<String, String> list = SummonList.snapshot();
        for (String role : roles) {
            String sel = summonRowOf.get(role);
            if (sel == null || !list.containsKey(role)) continue;
            if (listInputs) {
                String[] pending = summonDraft.get(role);
                ui.set(sel + " #Count.Value", pending != null && pending[0] != null ? pending[0] : list.get(role));
                ui.set(sel + " #Team.Value", pending != null && pending[1] != null ? pending[1] : summonTeam(role));
                summonSwatch(ui, sel, pending != null && pending[1] != null ? pending[1] : summonTeam(role));
            }
            summonLabel(ui, role);
        }
        for (int slot : slots) {
            String sel = teamRowOf.get(slot);
            if (sel == null || !Teams.exists(slot)) continue;
            if (listInputs) {
                String[] pending = teamDraft.get(slot);
                String color = pending != null && pending[1] != null ? pending[1] : paletteName(Teams.color(slot));
                ui.set(sel + " #Name.Value", pending != null && pending[0] != null ? pending[0] : Teams.name(slot));
                ui.set(sel + " #Color.Value", color);
                ui.setObject(sel + " #Swatch.Background", new PatchStyle().setColor(Value.of(Teams.PALETTE.get(color))));
            }
            teamMark(ui, slot);
        }
        noTeamsVisible(ui);
        status(ui);
        sendUpdate(ui);
    }

    /**
     * After a save: {@code before} becomes what Undo goes back to, unless this save put everything back as it was
     * before the last change (Undo + Save): then the undo is used up, else the next Undo would be a redo.
     */
    private void rememberSave(Properties before, Properties undoTarget) {
        if (undoTarget != null && diffFrom(undoTarget, true).isEmpty() && !listsDiffer(undoTarget)) {
            beforeLastSave = null;
            lastSaver = null;
        } else {
            beforeLastSave = before;
            lastSaver = playerRef.getUsername();
        }
    }

    /** True when the team or summon lists in {@code target} differ from the current ones. */
    private static boolean listsDiffer(Properties target) {
        Properties live = FxSettings.toProperties();
        java.util.Set<String> keys = new java.util.HashSet<>(target.stringPropertyNames());
        keys.addAll(live.stringPropertyNames());
        for (String k : keys) {
            if (Presets.isListKey(k) && !k.startsWith(Kits.PREFIX)
                    && !java.util.Objects.equals(target.getProperty(k), live.getProperty(k))) return true;
        }
        return false;
    }

    /**
     * Puts the team and summon lists of {@code target} back at once (they never go through the form), keeping every
     * setting as it is. A team that only exists now is removed the normal way first, so no draft keeps its slot.
     */
    static void restoreLists(Properties target) {
        Properties q = FxSettings.toProperties();
        // Kits stay as they are (audit 2026-10-29): a summon list saved before a kit was made would delete it, and one
        // saved before the default kits would bring back the ones deleted since.
        for (String k : q.stringPropertyNames()) if (Presets.isListKey(k) && !k.startsWith(Kits.PREFIX)) q.remove(k);
        for (String k : target.stringPropertyNames()) {
            if (Presets.isListKey(k) && !k.startsWith(Kits.PREFIX)) q.setProperty(k, target.getProperty(k));
        }
        Teams.load(q);
        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        for (int i = 0; i < Teams.MAX; i++) if (Teams.exists(i)) wanted.add(i);
        // Teams.load already rebuilt the slots; slots it dropped still need their drafts cleared.
        for (int i = 0; i < Teams.MAX; i++) if (!wanted.contains(i)) BattleManager.removeConfiguredTeam(i);
        SummonList.load(q);
        FxSettings.save();
    }

    /**
     * Undo the last change: the team and summon lists go back at once (they apply at once anyway), and the settings
     * from right before it go into the form (like a preset), so they are checked and then saved.
     */
    private void undo() {
        Properties before = beforeLastSave;
        if (before == null) {
            say(I18n.tr("panel.notice.nothingundo"), WARN);
            return;
        }
        if (!canEdit()) {
            say(I18n.tr("panel.notice.cantchange"), WARN);
            return;
        }
        boolean lists = listsDiffer(before);
        if (lists) {
            restoreLists(before);
            summonDraft.clear();
            teamDraft.clear();
            MobBattlePlugin.log("Settings panel: " + playerRef.getUsername() + " undid the team/summon list change by " + lastSaver);
        }
        String by = lastSaver;
        // What each field shows now, so the in-place refresh below only sets the ones the undo changes.
        Properties live = FxSettings.toProperties();
        Map<Integer, String> shown = new LinkedHashMap<>(draft);
        int n = loadIntoDraft(before);
        for (int index : draft.keySet()) shown.computeIfAbsent(index, i -> saved(ConfigSchema.field(i), live));
        if (n == 0 && !lists) {
            say(I18n.tr("panel.notice.alreadymatch"), GRAY);
            return;
        }
        if (n == 0) {
            // Everything is back already: nothing left to save, so the undo is used up.
            beforeLastSave = null;
            lastSaver = null;
            notice = I18n.tr("panel.notice.undone.lists").param("by", by);
        } else {
            notice = lists
                    ? I18n.tr("panel.notice.undone.both").param("by", by).param("count", n)
                    : I18n.tr("panel.notice.undone.form").param("by", by).param("count", n);
        }
        noticeColor = GOLD;
        // Restored lists can add or drop rows: rebuild. Settings only: rows in place, the scroll stays.
        if (lists) rebuild();
        else refreshInPlace(shown, List.of(), List.of(), false);
    }

    /** The draft becomes the difference between {@code target} and the saved settings (preset / reset preview). */
    private int loadIntoDraft(Properties target) {
        Map<Integer, String> diff = diffFrom(target, admin());
        draft.clear();
        draft.putAll(diff);
        return draft.size();
    }

    /** Field index -> canonical value of every field (admin ones only with {@code admin}) where {@code target} differs from the saved settings. */
    private static Map<Integer, String> diffFrom(Properties target, boolean admin) {
        Properties live = FxSettings.toProperties();
        Map<Integer, String> diff = new LinkedHashMap<>();
        for (ConfigSchema.Field f : ConfigSchema.FIELDS) {
            if (f.admin && !admin) continue;
            String raw = target.getProperty(f.key);
            if (raw == null && !KindFx.isOverrideKey(f.key)) continue;
            String v = raw == null ? "" : f.canonical(raw);
            if (v != null && !v.equals(saved(f, live))) diff.put(f.index, v);
        }
        return diff;
    }

    /**
     * Undo has something to do: there is a last change, the settings still differ from how they were before it, and
     * the form doesn't hold that undo already (a second click would change nothing).
     */
    private boolean canUndo() {
        Properties before = beforeLastSave;
        if (before == null) return false;
        // Worked out again only when something it depends on changed (audit 2026-10-14: every panel event of every
        // player rebuilt the diff of ~260 fields, dropdown option lists included, once a save had happened).
        boolean admin = admin();
        Properties live = FxSettings.toProperties();
        Object[] key = {before, draft.hashCode(), live.hashCode(), admin};
        if (undoKey != null && undoKey[0] == before && java.util.Arrays.equals(key, undoKey)) return undoCached;
        boolean result = listsDiffer(before);
        if (!result) {
            Map<Integer, String> diff = diffFrom(before, admin);
            result = !diff.isEmpty() && !diff.equals(draft);
        }
        undoKey = key;
        undoCached = result;
        return result;
    }

    /** canUndo's last answer and what it was worked out from {beforeLastSave, draft hash, settings hash, admin}. */
    private Object[] undoKey;
    private boolean undoCached;

    /** An action row whose button asks for a second click (see {@link #confirmed}). */
    private void confirmable(Rows rows, String action, Message label, Supplier<Message> button) {
        String sel = rows.action(label, button.get(), data("Act").append("A", action));
        confirmButtons.put(action, new Confirm(sel + " #Button", button));
    }

    private boolean confirmed(String action, Message what) {
        long now = System.currentTimeMillis();
        UICommandBuilder ui = new UICommandBuilder();
        String before = confirmAction;
        if (action.equals(before) && now < confirmUntil) {
            confirmAction = null;
            disarm(ui, before);
            sendUpdate(ui);
            return true;
        }
        if (before != null) disarm(ui, before);
        confirmAction = action;
        confirmUntil = now + CONFIRM_MS;
        Confirm b = confirmButtons.get(action);
        if (b != null) {
            I18n.text(ui, b.selector() + ".Text", I18n.tr("panel.button.confirm"));
            ui.set(b.selector() + ".Style", Value.ref("Common.ui", "CancelTextButtonStyle"));
        }
        notice = I18n.tr("panel.notice.confirm").param("what", what);
        noticeColor = WARN;
        status(ui);
        sendUpdate(ui);
        // Not clicked in time: the button goes back to normal.
        int generation = ++confirmGeneration;
        if (store.isShutdown()) return false;
        store.getExternalData().getWorld().scheduleAfter(() -> {
            if (generation != confirmGeneration || !action.equals(confirmAction)) return;
            if (!entity.isValid() || store.isShutdown()) return;
            Player p = store.getComponent(entity, Player.getComponentType());
            if (p == null || p.getPageManager().getCustomPage() != this) return;
            confirmAction = null;
            UICommandBuilder back = new UICommandBuilder();
            disarm(back, action);
            sendUpdate(back);
        }, CONFIRM_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        return false;
    }

    /** An armed confirm button back to its text and style. */
    private void disarm(UICommandBuilder ui, String action) {
        Confirm b = confirmButtons.get(action);
        if (b == null) return;
        I18n.text(ui, b.selector() + ".Text", b.text().get());
        ui.set(b.selector() + ".Style", Value.ref("Common.ui", "SmallSecondaryTextButtonStyle"));
    }

    private static boolean validName(String s) {
        return s != null && s.matches("[A-Za-z0-9_]{1,16}");
    }

    private static String paletteOf(String s) {
        if (s == null) return null;
        for (String c : Teams.PALETTE.keySet()) if (c.equalsIgnoreCase(s.trim())) return c;
        return null;
    }

    private static String teamOf(String s) {
        if (s == null) return null;
        int slot = Teams.find(s.trim());
        return slot >= 0 ? Teams.name(slot) : null;
    }

    private static String listedRole(String s) {
        if (s == null) return null;
        for (String r : SummonList.snapshot().keySet()) if (r.equalsIgnoreCase(s.trim())) return r;
        return null;
    }

    private static String count(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        return SummonList.isCount(v) ? SummonList.normalCount(v) : null;
    }

    /**
     * A row's equipment kit dropdown ("-" = none + every kit). A pick runs summon kit / teams kit at once (lists
     * apply right away on this page, like Remove). Picks right after the rows were sent are ignored: refilling a
     * dropdown can echo its first entry, which would clear the kit (the Equipment Wand panel's type filter did).
     */
    private void kitPicker(Rows rows, String sel, String current, String action, String name) {
        List<ConfigSchema.Opt> opts = new ArrayList<>();
        opts.add(new ConfigSchema.Opt("", "-"));
        for (String k : Kits.names()) opts.add(new ConfigSchema.Opt(k, k));
        rows.ui.set(sel + " #Kit.Entries", entries(opts));
        rows.ui.set(sel + " #Kit.Value", current != null ? current : "");
        rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Kit",
                data("Act").append("A", action).append("N", name).append("@V", sel + " #Kit.Value"), false);
        kitEchoUntil = System.currentTimeMillis() + 800;
    }

    private long kitEchoUntil;

    private void act(String action, String name, String value, String text, String count, String extra) {
        if (action == null) return;
        switch (action) {
            case "summonKit", "teamKit" -> {
                if (name == null || value == null || System.currentTimeMillis() < kitEchoUntil) return;
                boolean summon = action.equals("summonKit");
                String role = summon ? listedRole(name) : name;
                if (role == null) return;
                int slot = summon ? -1 : Teams.find(name);
                String now = summon ? SummonList.KIT_OF.get(role) : Teams.kit(slot);
                if (value.equals(now == null ? "" : now)) return;
                runList((summon ? "summon kit " : "teams kit ") + role + " " + (value.isEmpty() ? "none" : value));
            }
            // Teams (config)
            case "teamRemove" -> {
                String team = teamOf(name);
                if (team != null && confirmed(action + team, I18n.tr("panel.confirm.teamremove").param("name", team))) runList("teams remove " + team);
            }
            case "teamAdd" -> {
                String color = paletteOf(value);
                String n = text != null ? text.trim() : null;
                if (!validName(n) || color == null) { say(I18n.tr("panel.notice.pickteam"), WARN); return; }
                runList("teams add " + n + " " + color.toLowerCase(Locale.ROOT));
            }
            case "teamReset" -> {
                if (confirmed(action, I18n.tr("panel.confirm.teamreset"))) runList("teams reset");
            }
            // Summon list (config)
            case "summonRemove" -> {
                String role = listedRole(name);
                if (role != null) runList("summon remove " + role);
            }
            case "measure" -> run("measure 10", true);
            case "galleryOpen" -> galleryOpen();
            case "apply" -> {
                String who = value != null ? value.trim() : "";
                String act = extra != null ? extra.trim() : "";
                String v = count != null ? count.trim() : "";
                if (who.isEmpty() || !ApplyCommand.ACTIONS.contains(act) || !who.matches("[A-Za-z0-9_ ]{1,32}") || !v.matches("[A-Za-z0-9_:>.%#x ]{0,64}")) {
                    say(I18n.tr("panel.notice.pickapply"), WARN);
                    return;
                }
                run("apply " + who + " " + act + (v.isEmpty() ? "" : " " + v), false, false);
            }
            case "summonAdd" -> {
                String role = value != null ? SummonList.findRole(value.trim()) : null;
                String c = count(count == null || count.isBlank() ? "1" : count);
                String team = extra == null || "none".equalsIgnoreCase(extra) ? null : teamOf(extra);
                if (role == null || !role.matches("[A-Za-z0-9_\\-]+") || c == null) { say(I18n.tr("panel.notice.pickmob"), WARN); return; }
                // The row comes back on this mob, count and team after the page refresh.
                LAST_SUMMON_PICK.put(playerRef.getUuid(), new String[] {role, c, team});
                runList("summon add " + role + " " + c + (team != null ? " " + team : ""));
            }
            case "summonClear" -> {
                if (confirmed(action, I18n.tr("panel.confirm.summonclear"))) {
                    summonDraft.clear();
                    runList("summon clear");
                }
            }
            case "summonNoTeams" -> {
                // unsaved team picks on the rows go too (their counts stay pending)
                summonDraft.values().forEach(p -> p[1] = null);
                summonDraft.values().removeIf(p -> p[0] == null && p[1] == null);
                runList("summon team all none");
            }
            case "summonFill" -> summonFill(value, count, extra);
            case "summonSpawn" -> run("summon spawn", true);
            case "summonListLoad" -> {
                if (name != null && SummonLists.validName(name)) {
                    // Unsaved row edits belong to the list being replaced (a team slot rename/recolor too).
                    summonDraft.clear();
                    teamDraft.clear();
                    runList("summon load " + name);
                }
            }
            case "summonListDelete" -> {
                if (name != null && SummonLists.validName(name)
                        && confirmed(action + name, I18n.tr("panel.confirm.summonlistdelete").param("name", name))) run("summon delete " + name, false);
            }
            case "summonListSort" -> {
                LIST_SORT_BY_NAME.put(playerRef.getUuid(), "name".equals(name));
                refreshPage();
            }
            case "summonListSave" -> {
                String n = text != null ? text.trim() : "";
                if (!SummonLists.validName(n)) { say(I18n.tr("cmd.summon.saved.badname").param("name", n), WARN); return; }
                run("summon save " + n, false);
            }
            // Presets and resets: into the form
            case "presetLoad" -> {
                if (name == null || !Presets.isBuiltIn(name) && !Presets.validName(name)) return;
                Presets.Preview pv = Presets.preview(name);
                if (pv.error() != null) { say(pv.error(), WARN); return; }
                int n = loadIntoDraft(pv.props());
                notice = I18n.tr("panel.notice.presetloaded").param("name", name).param("count", n);
                noticeColor = GOLD;
                rebuild();
            }
            case "resetLoad" -> {
                if (name == null) return;
                Presets.Preview pv = Presets.previewReset(name);
                if (pv.error() != null) { say(pv.error(), WARN); return; }
                int n = loadIntoDraft(pv.props());
                notice = I18n.tr("panel.notice.resetloaded").param("group", name).param("count", n);
                noticeColor = GOLD;
                rebuild();
            }
            case "presetSave" -> {
                if (!canEdit()) { say(I18n.tr("panel.notice.cantsavepreset"), WARN); return; }
                String n = text != null ? text.trim() : "";
                Properties p = FxSettings.toProperties();
                boolean admin = admin();
                for (var e : draft.entrySet()) {
                    ConfigSchema.Field f = ConfigSchema.field(e.getKey());
                    if (f == null || f.admin && !admin) continue;
                    if (e.getValue().isEmpty() && KindFx.isOverrideKey(f.key)) p.remove(f.key);
                    else p.setProperty(f.key, e.getValue());
                }
                String err = Presets.save(n, p);
                if (err != null) { say(err, WARN); return; }
                notice = I18n.tr("panel.notice.presetsaved").param("name", n);
                noticeColor = "#7dff8a";
                rebuild();
            }
            case "presetDelete" -> {
                if (name != null && Presets.validName(name) && !Presets.isBuiltIn(name)
                        && confirmed(action + name, I18n.tr("panel.confirm.presetdelete").param("name", name))) run("preset delete " + name, false);
            }
            // Tools (use)
            case "morph" -> {
                String model = value != null ? value.trim() : null;
                if (model != null && model.matches("[A-Za-z0-9_\\-]+")) run("morph " + model, false, false);
            }
            case "watch" -> run("watch", true);
            case "watchExit" -> run("watch exit", true);
            case "skipCamera" -> run("skipcamera", false, false);
            case "snap" -> run("snap", true);
            case "chaosMe" -> run("chaos me", true);
            case "debugNpcs" -> run("debug", true);
            case "teamsAuto" -> run("teams auto", false, false);
            case "teamsStart" -> run("teams start", true);
            case "teamsJoin" -> {
                String team = teamOf(value);
                if (team != null) run("teams join " + team, true);
            }
            case "teamsLeave" -> run("teams leave", false, false);
            case "teamsClear" -> run("teams clear", false, false);
            // Admin
            case "permissions" -> {
                if ("open".equals(name) || "server".equals(name)) run("permissions " + name, false);
            }
            case "reload" -> {
                if (admin()) draft.clear();
                runList("reload");
            }
            case "give" -> run("give", false, false);
            case "skipTimer" -> run("skiptimer", false, false);
            case "debugDump" -> run("debugdump", false, false);
            case "picturesStatus" -> run("pictures status", false, false);
            case "picturesRebuild" -> run("pictures rebuild", false, false);
            case "eventRoyale" -> {
                TransformComponent t = store.getComponent(entity, TransformComponent.getComponentType());
                if (t == null) return;
                var pos = t.getPosition();
                run(String.format(Locale.ROOT, "event royale %.1f %.1f %.1f %d", pos.x, pos.y, pos.z, FxSettings.radius), false, false);
            }
            case "stop" -> {
                if (confirmed(action, I18n.tr("panel.confirm.stop"))) run("stop", false, false);
            }
            case "armyClear" -> {
                if (confirmed(action, I18n.tr("panel.confirm.armyclear"))) run("army clearall", false, false);
            }
            case "teamsClearAll" -> {
                if (confirmed(action, I18n.tr("panel.confirm.teamsclearall"))) run("teams clearall", false, false);
            }
            case "morphReset" -> {
                if (confirmed(action, I18n.tr("panel.confirm.morphreset"))) run("morph reset", false, false);
            }
            case "resetAll" -> {
                if (confirmed(action, I18n.tr("panel.confirm.resetall"))) run("resetall", false, false);
            }
            default -> { }
        }
    }

    /**
     * Runs /mobbattle {@code args} as this player (its permission check, validation and chat reply). {@code close}:
     * the panel closes first (watch, snap, chaos me: the player wants to see the world). Otherwise the panel is built
     * again when the command is done, so lists and values show the result; the draft is kept.
     */
    /**
     * A team or summon list change (applies at once): the settings and lists from right before it become what Undo
     * goes back to, as a save does.
     */
    private void runList(String args) {
        if (canEdit()) {
            beforeLastSave = FxSettings.toProperties();
            lastSaver = playerRef.getUsername();
        }
        run(args, false);
    }

    private void run(String args, boolean close) {
        run(args, close, true);
    }

    /**
     * Runs /mobbattle {@code args} as the player. {@code refresh}: rebuild the page when it finishes (the page shows
     * what changed); otherwise only the status line is updated, so the scroll and typed inputs stay.
     */
    private void run(String args, boolean close, boolean refresh) {
        if (confirmAction != null && !close) {
            // Another button while one waited for its second click: that one goes back to normal.
            UICommandBuilder ui = new UICommandBuilder();
            disarm(ui, confirmAction);
            sendUpdate(ui);
        }
        confirmAction = null;
        if (close) close();
        java.util.concurrent.CompletableFuture<Void> done;
        try {
            done = CommandManager.get().handleCommand(playerRef, "mobbattle " + args);
        } catch (RuntimeException e) {
            if (!close) say(I18n.tr("panel.notice.runfail").param("args", args), WARN);
            return;
        }
        if (close) return;
        done.whenComplete((ignored, error) -> {
            if (store.isShutdown()) return;
            store.getExternalData().getWorld().execute(() -> {
                if (!entity.isValid() || store.isShutdown()) return;
                Player p = store.getComponent(entity, Player.getComponentType());
                if (p == null || p.getPageManager().getCustomPage() != this) return;
                notice = error != null ? I18n.tr("panel.notice.failed").param("args", args) : I18n.tr("panel.notice.done").param("args", args);
                noticeColor = error != null ? WARN : GRAY;
                if (refresh) {
                    refreshPage();
                    return;
                }
                UICommandBuilder ui = new UICommandBuilder();
                status(ui);
                sendUpdate(ui);
            });
        });
    }

    static List<DropdownEntryInfo> entries(List<ConfigSchema.Opt> opts) {
        List<DropdownEntryInfo> out = new ArrayList<>(opts.size());
        for (ConfigSchema.Opt o : opts) out.add(new DropdownEntryInfo(LocalizableString.fromString(o.label()), o.value()));
        return out;
    }
}
