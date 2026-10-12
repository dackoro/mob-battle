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
    /** Every setting as it was right before the last panel save (any player), for Undo; null until a save. */
    private static volatile Properties beforeLastSave;
    private static volatile String lastSaver;

    private final Ref<EntityStore> entity;
    private final Store<EntityStore> store;
    private int page;
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
    /** Team rows edited but not saved: slot -> {name, palette color} (null = unchanged), same as summonDraft. */
    private final Map<Integer, String[]> teamDraft = new LinkedHashMap<>();
    /** Slot -> its team row selector in the current build. */
    private final Map<Integer, String> teamRowOf = new HashMap<>();
    private String notice;
    private String noticeColor = GRAY;
    /** The area preview drawn last for this player ({x, y, z, radius}), so the next one replaces it. */
    private double[] lastPreview;
    private String confirmAction;
    private long confirmUntil;

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
        rowOf.clear();
        previewOf.clear();
        summonRowOf.clear();
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
            ui.set(sel + " #Button.Text", p.title());
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button", data("Nav").append("P", String.valueOf(i)), false);
            // Only the open page gets its rows: a page change rebuilds (the draft lives here, not in the client).
            // All 14 at once was ~3500 dropdown entries (music tracks in 5 lists, NPC roles, models) per build.
            if (i != page) continue;
            pageRows(new Rows(ui, ev, "#Page" + i), p, live);
        }
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
                case ConfigSchema.Section s -> rows.section(s.title());
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
     * page with clear = true and the scroll goes back to the top (user, 2026-10-12). Here the page container is
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
        String container = "#Page" + page;
        ui.clear(container);
        rowOf.clear();
        previewOf.clear();
        summonRowOf.clear();
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

        void section(String title) {
            ui.set(add("Section.ui") + " #Label.Text", title);
        }

        void note(String text) {
            ui.set(add("Note.ui") + " #Label.Text", text);
        }

        /** A row with a label and one button. */
        String action(String label, String button, EventData data) {
            String sel = add("Action.ui");
            ui.set(sel + " #Label.Text", label);
            ui.set(sel + " #Button.Text", button);
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button", data, false);
            return sel;
        }

        /** Up to four buttons in a row. */
        void buttons(String[] labels, EventData[] data) {
            String sel = add("Buttons.ui");
            for (int i = 0; i < Math.min(4, labels.length); i++) {
                ui.set(sel + " #B" + i + ".Visible", true);
                ui.set(sel + " #B" + i + ".Text", labels[i]);
                ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #B" + i, data[i], false);
            }
        }
    }

    private void fieldRow(Rows rows, ConfigSchema.Field f, Properties live) {
        String template = switch (f.type) {
            case TOGGLE -> "Toggle.ui";
            case NUMBER -> "Number.ui";
            case CHOICE -> f.options.get().size() > SEARCH_FROM ? "ChoiceSearch.ui" : "Choice.ui";
            case TEXT -> "Text.ui";
        };
        String sel = rows.add(template);
        rowOf.put(f.index, sel);
        UICommandBuilder ui = rows.ui;
        ui.set(sel + " #Tip.Text", f.tip);
        ui.set(sel + " #Hint.Text", f.hint);
        label(ui, f);
        setInput(ui, sel, f, value(f, live));
        rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Input",
                data("Set").append("F", String.valueOf(f.index)).append("@V", sel + " #Input.Value"), false);
    }

    private void setInput(UICommandBuilder ui, String sel, ConfigSchema.Field f, String v) {
        switch (f.type) {
            case TOGGLE -> ui.set(sel + " #Input.Value", f.on.equals(v));
            case NUMBER -> ui.set(sel + " #Input.Value", parseInt(v, f.min));
            case CHOICE -> {
                List<ConfigSchema.Opt> opts = new ArrayList<>(f.options.get());
                if (opts.stream().noneMatch(o -> o.value().equals(v))) opts.add(new ConfigSchema.Opt(v, v.isEmpty() ? "-" : v));
                ui.set(sel + " #Input.Entries", entries(opts));
                ui.set(sel + " #Input.Value", v);
            }
            case TEXT -> ui.set(sel + " #Input.Value", v);
        }
    }

    /** Gold label = changed and not saved yet. */
    private void label(UICommandBuilder ui, ConfigSchema.Field f) {
        String sel = rowOf.get(f.index);
        if (sel == null) return;
        boolean changed = draft.containsKey(f.index);
        ui.set(sel + " #Label.TextSpans", Message.raw((changed ? "* " : "") + f.label).color(changed ? GOLD : GRAY));
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
        int nav = 0;
        for (int i = 0; i < ConfigSchema.PAGES.size(); i++) {
            ConfigSchema.Page p = ConfigSchema.PAGES.get(i);
            boolean open = i == page;
            ui.set("#Page" + i + ".Visible", open);
            if (p.adminOnly() && !admin) continue;
            String sel = "#Nav[" + nav++ + "]";
            ui.set(sel + " #Sel.Visible", open);
            ui.set(sel + " #Button.Style", Value.ref("Common.ui", open ? "SmallSecondaryTextButtonStyle" : "SmallTertiaryTextButtonStyle"));
        }
        ConfigSchema.Page p = ConfigSchema.PAGES.get(page);
        ui.set("#PageTitle.Text", p.title());
        ui.set("#PageHint.Text", p.hint());
    }

    private void status(UICommandBuilder ui) {
        boolean edit = canEdit();
        ui.set("#ReadOnly.Visible", !edit);
        int n = draft.size() + summonDraft.size() + teamDraft.size();
        String changes = !edit ? "Read only." : n == 0 ? "No unsaved changes." : n + (n == 1 ? " unsaved change." : " unsaved changes.");
        if (notice != null) {
            ui.set("#Status.TextSpans", Message.raw(notice + "  " + changes).color(noticeColor));
        } else {
            ui.set("#Status.TextSpans", Message.raw(changes).color(n > 0 ? GOLD : GRAY));
        }
        ui.set("#SaveButton.Disabled", !edit || n == 0);
        ui.set("#DiscardButton.Disabled", n == 0);
        ui.set("#UndoButton.Disabled", !edit || !canUndo());
    }

    private void say(String text, String color) {
        notice = text;
        noticeColor = color;
        UICommandBuilder ui = new UICommandBuilder();
        status(ui);
        sendUpdate(ui);
    }

    // ---- custom rows ----

    private void wandsRows(Rows rows) {
        rows.section("Morph Wand model");
        String sel = rows.add("ActionPick.ui");
        rows.ui.set(sel + " #Label.Text", "Your Morph Wand turns mobs into (type to search)");
        List<ConfigSchema.Opt> models = ConfigSchema.categorized(ModelAsset.getAssetMap(), MorphWand.search("", 5000), "Models");
        rows.ui.set(sel + " #Input.Entries", entries(models));
        // Start on the model the wand has now (set by Use, /mobbattle morph or a right click copy), else the first.
        MorphWand.Copy copy = MorphWand.copyOf(playerRef.getUuid());
        String held = copy == null ? null : copy.modelId();
        String current = held;
        if (held == null || models.stream().noneMatch(o -> o.value().equals(held))) {
            current = models.isEmpty() ? null : models.get(0).value();
        }
        if (current != null) rows.ui.set(sel + " #Input.Value", current);
        preview(rows, sel, "morph", current);
        rows.ui.set(sel + " #Button.Text", "Use");
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "morph").append("@V", sel + " #Input.Value"), false);
        rows.note("Right click a mob with the Morph Wand copies its model instead. Cleanse Wand or Admin > Undo morphs restore them.");
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
            notice = "Read only.";
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
            notice = isName ? "Team names: letters, numbers and _ (up to 16)." : null;
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
    private String teamDraftConflict() {
        Map<String, Integer> names = new HashMap<>();
        Map<String, Integer> colors = new HashMap<>();
        for (int slot = 0; slot < Teams.MAX; slot++) {
            if (!Teams.exists(slot)) continue;
            String[] pending = teamDraft.get(slot);
            String name = pending != null && pending[0] != null ? pending[0] : Teams.name(slot);
            String color = pending != null && pending[1] != null ? pending[1] : paletteName(Teams.color(slot));
            Integer other = names.put(name, slot);
            if (other != null) return "Two teams can't both be called " + name + ".";
            other = colors.put(color, slot);
            if (other != null) return "Two teams can't share the color " + color + ": pick another one.";
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
        rows.section("Teams (" + Teams.count() + " of " + Teams.MAX + ")");
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
        }
        rows.section("Add a team");
        String sel = rows.add("ActionPick.ui");
        rows.ui.set(sel + " #Label.Text", "Name and color (each team has its own color)");
        rows.ui.set(sel + " #Name.Visible", true);
        rows.ui.set(sel + " #Input.Entries", entries(ConfigSchema.palette()));
        rows.ui.set(sel + " #Input.Value", freeColor());
        rows.ui.set(sel + " #Button.Text", "Add team");
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "teamAdd").append("@N", sel + " #Name.Value").append("@V", sel + " #Input.Value"), false);
        rows.note("Names: letters, numbers and _ (up to 16). There are always at least 2 teams.");
        if (admin()) rows.action("Back to the four default teams (Red, Blue, Green, Yellow)", "Reset teams", data("Act").append("A", "teamReset"));
        galleryRows(rows);
    }

    /** Most mob types shown per team in the gallery (the rest are counted in the header). */
    private static final int GALLERY_TYPES = 24;

    /**
     * The player's Team Wand teams (their draft in this world): per team its color, name and size, and one chip per
     * mob type with its icon and how many ("x5"), so a big team stays one short row.
     */
    private void galleryRows(Rows rows) {
        rows.section("Your Team Wand teams");
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
                rows.ui.set(sel + " #Label.Text", Teams.name(slot) + "  -  " + total + (total == 1 ? " mob" : " mobs")
                        + (types.size() > GALLERY_TYPES ? " (" + types.size() + " types, first " + GALLERY_TYPES + " shown)" : ""));
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(types.entrySet());
                sorted.sort((a, b) -> b.getValue() - a.getValue());
                int k = 0;
                for (Map.Entry<String, Integer> e : sorted) {
                    if (k >= GALLERY_TYPES) break;
                    rows.ui.append(sel + " #Chips", DIR + "GalleryChip.ui");
                    String chip = sel + " #Chips[" + k++ + "]";
                    icon(rows.ui, chip + " #Icon", e.getKey());
                    rows.ui.set(chip + " #Count.Text", "x" + e.getValue());
                    String shortName = e.getKey().replace('_', ' ');
                    rows.ui.set(chip + " #Name.Text", shortName.length() > 12 ? shortName.substring(0, 11) + "." : shortName);
                }
            }
        }
        if (!any) rows.note("No mobs in your teams yet: Team Wand (left click a mob), or Tools > Auto teams.");
    }

    private static List<ConfigSchema.Opt> teamOptions(boolean none) {
        List<ConfigSchema.Opt> out = new ArrayList<>();
        if (none) out.add(new ConfigSchema.Opt("none", "No team"));
        for (int slot = 0; slot < Teams.MAX; slot++) {
            if (Teams.exists(slot)) out.add(new ConfigSchema.Opt(Teams.name(slot), Teams.name(slot)));
        }
        return out;
    }

    private void summonRows(Rows rows) {
        Map<String, String> list = SummonList.snapshot();
        rows.section("Mobs in the list (" + list.size() + ")");
        if (list.isEmpty()) rows.note("The list is empty: add mobs below or with the Summon Wand (right click a mob).");
        List<ConfigSchema.Opt> teams = teamOptions(true);
        for (var e : list.entrySet()) {
            String role = e.getKey();
            String sel = rows.add("SummonRow.ui");
            summonRowOf.put(role, sel);
            String[] pending = summonDraft.get(role);
            icon(rows.ui, sel + " #Icon", role);
            summonLabel(rows.ui, role);
            rows.ui.set(sel + " #Count.Value", pending != null && pending[0] != null ? pending[0] : e.getValue());
            rows.ui.set(sel + " #Team.Entries", entries(teams));
            rows.ui.set(sel + " #Team.Value", pending != null && pending[1] != null ? pending[1] : summonTeam(role));
            rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Count",
                    data("SumEdit").append("N", role).append("K", "count").append("@V", sel + " #Count.Value"), false);
            rows.ev.addEventBinding(CustomUIEventBindingType.ValueChanged, sel + " #Team",
                    data("SumEdit").append("N", role).append("K", "team").append("@V", sel + " #Team.Value"), false);
            rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Remove",
                    data("Act").append("A", "summonRemove").append("N", role), false);
        }
        rows.section("Add a mob");
        String sel = rows.add("ActionPick.ui");
        rows.ui.set(sel + " #Label.Text", "Mob (type to search), count (number or random), team");
        List<ConfigSchema.Opt> roles = roleOptions();
        rows.ui.set(sel + " #Input.Entries", entries(roles));
        String first = roles.isEmpty() ? null : roles.get(0).value();
        if (first != null) rows.ui.set(sel + " #Input.Value", first);
        preview(rows, sel, "summonAdd", first);
        rows.ui.set(sel + " #Count.Visible", true);
        rows.ui.set(sel + " #Count.Value", "1");
        rows.ui.set(sel + " #Extra.Visible", true);
        rows.ui.set(sel + " #Extra.Entries", entries(teams));
        rows.ui.set(sel + " #Extra.Value", "none");
        rows.ui.set(sel + " #Button.Text", "Add");
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "summonAdd").append("@V", sel + " #Input.Value").append("@C", sel + " #Count.Value")
                        .append("@E", sel + " #Extra.Value"), false);
        if (!list.isEmpty()) rows.action("Remove every mob from the list", "Clear list", data("Act").append("A", "summonClear"));
        rows.action("Summon the list around you now", "Summon", data("Act").append("A", "summonSpawn"));
    }

    /** The saved team of a listed role: its name, or "none". */
    private static String summonTeam(String role) {
        Integer now = SummonList.TEAMS.get(role);
        return now != null && Teams.exists(now) ? Teams.name(now) : "none";
    }

    /** A summon row's mob name: gold with "* " while it has unsaved changes. */
    private void summonLabel(UICommandBuilder ui, String role) {
        String sel = summonRowOf.get(role);
        if (sel == null) return;
        boolean changed = summonDraft.containsKey(role);
        String name = role.replace('_', ' ');
        ui.set(sel + " #Role.TextSpans", changed ? Message.raw("* " + name).color(GOLD) : Message.raw(name));
    }

    /**
     * A summon row's count or team changed: kept in the draft (gold until Save changes), no rebuild. A value equal to
     * the saved one drops that part, so a dropdown echo or typing the old count back clears the mark.
     */
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
            notice = "Read only.";
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
            notice = isCount ? "Count: a number (0-999) or random." : null;
            noticeColor = WARN;
        } else {
            pending[part] = v.equals(saved) ? null : v;
            notice = null;
        }
        if (pending[0] == null && pending[1] == null) summonDraft.remove(role);
        else summonDraft.put(role, pending);
        summonLabel(ui, role);
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

    /** Spawnable NPC roles, labelled "Category - Name [mod]" from the folder under NPC/Roles. */
    private static List<ConfigSchema.Opt> roleOptions() {
        List<ConfigSchema.Opt> out = new ArrayList<>();
        NPCPlugin npc;
        List<String> names;
        try {
            npc = NPCPlugin.get();
            names = npc.getRoleTemplateNames(true);
        } catch (RuntimeException e) {
            return out;
        }
        for (String r : names) {
            String category = null;
            try {
                var info = npc.getRoleBuilderInfo(npc.getIndex(r));
                category = info != null ? ConfigSchema.folderAfter(info.getPath(), "Roles") : null;
            } catch (RuntimeException ignored) {
            }
            out.add(new ConfigSchema.Opt(r, ConfigSchema.categoryLabel(category, r, null)));
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label(), b.label()));
        return out;
    }

    // ---- icons (Icons/ModelsGenerated, as the Memories page) ----

    /** Preview icon rows by key ("morph", "summonAdd") -> the row's selector, for the Preview events. */
    private final Map<String, String> previewOf = new HashMap<>();

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
    static String iconOf(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_\\-]+")) return null;
        String declared = declaredIcon(name);
        if (declared != null) return declared;
        java.util.NavigableSet<String> icons = iconNames();
        String[] tries = {name, name.replaceFirst("^(Tamed|Template)_", ""), name.replaceFirst("_(Calf|Piglet|Cub|Baby|Juvenile)$", "")};
        for (String t : tries) if (icons.contains(t)) return ICON_DIR + t + ".png";
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
    private static final String MISSING_ICON = "UI/Custom/Pages/Memories/MissingIcon.png";
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

    private static void icon(UICommandBuilder ui, String selector, String name) {
        String path = iconOf(name);
        // Nothing found: the Memories page's "?" icon. setNull left an empty box in game (the fallback did not show).
        ui.set(selector + ".AssetPath", path != null ? path : MISSING_ICON);
    }

    private void presetRows(Rows rows) {
        rows.section("Built-in presets");
        rows.note("Default: everything back to default. Fight: no show, just the fights. Effects: countdown, timer, titles, music, "
                + "lasers, runes. Debug: everything visible (the debug log stays as it is). Cinematic: full camera work, no lasers or area drawings.");
        String[] builtIn = Presets.BUILT_IN;
        for (int i = 0; i < builtIn.length; i += 4) {
            int n = Math.min(4, builtIn.length - i);
            String[] labels = new String[n];
            EventData[] data = new EventData[n];
            for (int j = 0; j < n; j++) {
                labels[j] = ConfigSchema.label(builtIn[i + j]);
                data[j] = data("Act").append("A", "presetLoad").append("N", builtIn[i + j]);
            }
            rows.buttons(labels, data);
        }
        List<String> saved = Presets.saved();
        rows.section("Your presets (" + saved.size() + ")");
        if (saved.isEmpty()) rows.note("No saved presets yet.");
        for (String name : saved) {
            rows.buttons(new String[] {"Load " + name, "Delete " + name},
                    new EventData[] {data("Act").append("A", "presetLoad").append("N", name),
                            data("Act").append("A", "presetDelete").append("N", name)});
        }
        String sel = rows.add("ActionPick.ui");
        rows.ui.set(sel + " #Label.Text", "Save the form as a preset");
        rows.ui.set(sel + " #Name.Visible", true);
        rows.ui.set(sel + " #Input.Visible", false);
        rows.ui.set(sel + " #Button.Text", "Save preset");
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "presetSave").append("@N", sel + " #Name.Value"), false);
        rows.note("A preset holds every setting except the team list, the summon list and the permission mode.");
        rows.section("Back to defaults (into the form)");
        String[] groups = Presets.GROUPS;
        for (int i = 0; i < groups.length; i += 4) {
            int n = Math.min(4, groups.length - i);
            String[] labels = new String[n];
            EventData[] data = new EventData[n];
            for (int j = 0; j < n; j++) {
                labels[j] = "Reset " + groups[i + j];
                data[j] = data("Act").append("A", "resetLoad").append("N", groups[i + j]);
            }
            rows.buttons(labels, data);
        }
    }

    private void toolRows(Rows rows) {
        rows.section("Watch and cameras");
        rows.action("Spectate the nearest battle (your own first)", "Watch", data("Act").append("A", "watch"));
        rows.action("Back to your game mode and position", "Stop watching", data("Act").append("A", "watchExit"));
        rows.action("End your running intro or end camera", "Skip camera", data("Act").append("A", "skipCamera"));
        rows.section("Battles");
        rows.action("Half of the fighters around you turn to dust (Snap)", "Snap", data("Act").append("A", "snap"));
        rows.action("Every mob around attacks you (needs Players can fight for all vs one)", "Chaos me", data("Act").append("A", "chaosMe"));
        rows.action("Show the combat state of NPCs near you for 10 s", "Debug NPCs", data("Act").append("A", "debugNpcs"));
        rows.section("Your teams (Team Wand)");
        rows.action("Split the mobs around you into teams", "Auto teams", data("Act").append("A", "teamsAuto"));
        rows.action("Start the battle of your teams", "Start", data("Act").append("A", "teamsStart"));
        String sel = rows.add("ActionPick.ui");
        rows.ui.set(sel + " #Label.Text", "Fight in a team yourself");
        rows.ui.set(sel + " #Input.Entries", entries(teamOptions(false)));
        rows.ui.set(sel + " #Input.Value", Teams.name(firstTeam()));
        rows.ui.set(sel + " #Button.Text", "Join");
        rows.ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                data("Act").append("A", "teamsJoin").append("@V", sel + " #Input.Value"), false);
        rows.action("Leave the team you joined", "Leave", data("Act").append("A", "teamsLeave"));
        rows.action("Empty your teams", "Clear my teams", data("Act").append("A", "teamsClear"));
    }

    private static int firstTeam() {
        for (int slot = 0; slot < Teams.MAX; slot++) if (Teams.exists(slot)) return slot;
        return 0;
    }

    private void adminRows(Rows rows) {
        rows.section("Permissions: " + FxSettings.permissionsMode);
        rows.note("open = anyone can use the wands and change settings. server = needs dackoro.mob_battle.use / .config grants. "
                + "Admin actions always need dackoro.mob_battle.admin.");
        rows.buttons(new String[] {"Open", "Server"}, new EventData[] {
                data("Act").append("A", "permissions").append("N", "open"),
                data("Act").append("A", "permissions").append("N", "server")});
        rows.section("Server");
        rows.action("Read fx.properties again (after editing it by hand); drops unsaved changes", "Reload", data("Act").append("A", "reload"));
        rows.action("One of each wand into your inventory", "Give wands", data("Act").append("A", "give"));
        rows.action("Every running timer ends now by its time-up rule", "End timers", data("Act").append("A", "skipTimer"));
        rows.action("Royale around you (area radius, max mobs), with no owner", "Royale event", data("Act").append("A", "eventRoyale"));
        rows.action("Write what the debug recorder kept (last 5 min) to debug/ in the plugin data folder", "Save debug record",
                data("Act").append("A", "debugDump"));
        rows.section("Clean up (asks to confirm)");
        rows.action("Stop every battle, countdown, camera and music", "Stop all", data("Act").append("A", "stop"));
        rows.action("Disband every army", "Disband armies", data("Act").append("A", "armyClear"));
        rows.action("Empty every player's teams", "Clear all teams", data("Act").append("A", "teamsClearAll"));
        rows.action("Every morphed mob gets its own model back", "Undo morphs", data("Act").append("A", "morphReset"));
        rows.action("Stop everything and clear teams, buffs, armies and marks (settings stay)", "Reset all", data("Act").append("A", "resetAll"));
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
            case "Preview" -> {
                String sel = previewOf.get(str(doc, "K"));
                if (sel == null) return;
                UICommandBuilder ui = new UICommandBuilder();
                icon(ui, sel + " #Preview", str(doc, "@V"));
                sendUpdate(ui);
            }
            case "Set" -> set(parseInt(str(doc, "F"), -1), str(doc, "@V"));
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
                notice = "Changes discarded.";
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
        if (target == page) return;
        page = target;
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
            notice = f.admin ? "Only admins can change " + f.label + "." : "Read only.";
            noticeColor = WARN;
            status(ui);
            sendUpdate(ui);
            return;
        }
        String v = f.canonical(raw);
        if (v == null) {
            draft.remove(index);
            notice = f.label + ": " + raw.trim() + " is not valid (" + (f.hint.isEmpty() ? "it won't be saved" : f.hint) + ").";
            noticeColor = WARN;
        } else {
            boolean clamped = f.type == ConfigSchema.Type.NUMBER && parseInt(raw, Integer.MIN_VALUE) != parseInt(v, 0);
            // The control echoing a value the panel put there itself (Discard, Undo): nothing changed, so the notice stays.
            if (!clamped && v.equals(value(f, live)) && !WARN.equals(noticeColor)) return;
            if (v.equals(saved(f, live))) draft.remove(index);
            else draft.put(index, v);
            notice = null;
            // A number out of range is shown clamped.
            if (clamped) setInput(ui, sel, f, v);
            previewArea(f, v, live);
        }
        label(ui, f);
        status(ui);
        sendUpdate(ui);
    }

    /** Radius fields drawn around the player as soon as they change (the draft value, before Save). */
    private static final java.util.Set<String> AREA_PREVIEW = java.util.Set.of("chaos.radius", "battle.arena.size",
            "battle.shrink.to", "summon.radius", "snap.radius", "chaos.area", "chaos.area.color");

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
            say("Read only: you can't save settings.", WARN);
            return;
        }
        String conflict = teamDraftConflict();
        if (conflict != null) {
            say(conflict + " Nothing was saved.", WARN);
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
            notice = "Saved " + rows + (rows == 1 ? " change." : " changes.");
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
        notice = "Saved " + n + (n == 1 ? " change." : " changes.");
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
        }
        Map<String, String> list = SummonList.snapshot();
        for (String role : roles) {
            String sel = summonRowOf.get(role);
            if (sel == null || !list.containsKey(role)) continue;
            if (listInputs) {
                String[] pending = summonDraft.get(role);
                ui.set(sel + " #Count.Value", pending != null && pending[0] != null ? pending[0] : list.get(role));
                ui.set(sel + " #Team.Value", pending != null && pending[1] != null ? pending[1] : summonTeam(role));
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
            if (Presets.isListKey(k) && !java.util.Objects.equals(target.getProperty(k), live.getProperty(k))) return true;
        }
        return false;
    }

    /**
     * Puts the team and summon lists of {@code target} back at once (they never go through the form), keeping every
     * setting as it is. A team that only exists now is removed the normal way first, so no draft keeps its slot.
     */
    private static void restoreLists(Properties target) {
        Properties q = FxSettings.toProperties();
        for (String k : q.stringPropertyNames()) if (Presets.isListKey(k)) q.remove(k);
        for (String k : target.stringPropertyNames()) if (Presets.isListKey(k)) q.setProperty(k, target.getProperty(k));
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
            say("Nothing to undo: no change from this panel since the server started.", WARN);
            return;
        }
        if (!canEdit()) {
            say("Read only: you can't change settings.", WARN);
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
            say("The settings already match how they were before the last change.", GRAY);
            return;
        }
        if (n == 0) {
            // Everything is back already: nothing left to save, so the undo is used up.
            beforeLastSave = null;
            lastSaver = null;
            notice = "Last change (by " + by + ") undone: team and summon lists restored.";
        } else {
            notice = lists
                    ? "Last change (by " + by + ") undone: lists restored; " + n + " settings in the form, Save changes to apply them."
                    : "Last change (by " + by + ") undone in the form (" + n + " changes): Save changes to apply it.";
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
        if (listsDiffer(before)) return true;
        Map<Integer, String> diff = diffFrom(before, admin());
        return !diff.isEmpty() && !diff.equals(draft);
    }

    private boolean confirmed(String action, String what) {
        long now = System.currentTimeMillis();
        if (action.equals(confirmAction) && now < confirmUntil) {
            confirmAction = null;
            return true;
        }
        confirmAction = action;
        confirmUntil = now + CONFIRM_MS;
        say("Click again to confirm: " + what + ".", WARN);
        return false;
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
        return v.equals(SummonList.RANDOM) || v.matches("\\d{1,3}") ? v : null;
    }

    private void act(String action, String name, String value, String text, String count, String extra) {
        if (action == null) return;
        switch (action) {
            // Teams (config)
            case "teamRemove" -> {
                String team = teamOf(name);
                if (team != null && confirmed(action + team, "remove team " + team)) runList("teams remove " + team);
            }
            case "teamAdd" -> {
                String color = paletteOf(value);
                String n = text != null ? text.trim() : null;
                if (!validName(n) || color == null) { say("Pick a name (letters, numbers, _) and a color.", WARN); return; }
                runList("teams add " + n + " " + color.toLowerCase(Locale.ROOT));
            }
            case "teamReset" -> {
                if (confirmed(action, "reset the teams to the defaults")) runList("teams reset");
            }
            // Summon list (config)
            case "summonRemove" -> {
                String role = listedRole(name);
                if (role != null) runList("summon remove " + role);
            }
            case "summonAdd" -> {
                String role = value != null ? SummonList.findRole(value.trim()) : null;
                String c = count(count == null || count.isBlank() ? "1" : count);
                String team = extra == null || "none".equalsIgnoreCase(extra) ? null : teamOf(extra);
                if (role == null || !role.matches("[A-Za-z0-9_\\-]+") || c == null) { say("Pick a mob and a count (number or random).", WARN); return; }
                runList("summon add " + role + " " + c + (team != null ? " " + team : ""));
            }
            case "summonClear" -> {
                if (confirmed(action, "empty the summon list")) runList("summon clear");
            }
            case "summonSpawn" -> run("summon spawn", true);
            // Presets and resets: into the form
            case "presetLoad" -> {
                if (name == null || !Presets.isBuiltIn(name) && !Presets.validName(name)) return;
                Presets.Preview pv = Presets.preview(name);
                if (pv.error() != null) { say(pv.error(), WARN); return; }
                int n = loadIntoDraft(pv.props());
                notice = "Preset " + name + " loaded into the form (" + n + " changes): Save changes to apply it.";
                noticeColor = GOLD;
                rebuild();
            }
            case "resetLoad" -> {
                if (name == null) return;
                Presets.Preview pv = Presets.previewReset(name);
                if (pv.error() != null) { say(pv.error(), WARN); return; }
                int n = loadIntoDraft(pv.props());
                notice = "Defaults of " + name + " loaded into the form (" + n + " changes): Save changes to apply them.";
                noticeColor = GOLD;
                rebuild();
            }
            case "presetSave" -> {
                if (!canEdit()) { say("Read only: you can't save presets.", WARN); return; }
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
                notice = "Preset " + n + " saved (with the unsaved changes of the form).";
                noticeColor = "#7dff8a";
                rebuild();
            }
            case "presetDelete" -> {
                if (name != null && Presets.validName(name) && !Presets.isBuiltIn(name)
                        && confirmed(action + name, "delete preset " + name)) run("preset delete " + name, false);
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
            case "eventRoyale" -> {
                TransformComponent t = store.getComponent(entity, TransformComponent.getComponentType());
                if (t == null) return;
                var pos = t.getPosition();
                run(String.format(Locale.ROOT, "event royale %.1f %.1f %.1f %d", pos.x, pos.y, pos.z, FxSettings.radius), false, false);
            }
            case "stop" -> {
                if (confirmed(action, "stop every battle")) run("stop", false, false);
            }
            case "armyClear" -> {
                if (confirmed(action, "disband every army")) run("army clearall", false, false);
            }
            case "teamsClearAll" -> {
                if (confirmed(action, "empty every player's teams")) run("teams clearall", false, false);
            }
            case "morphReset" -> {
                if (confirmed(action, "undo every morph")) run("morph reset", false, false);
            }
            case "resetAll" -> {
                if (confirmed(action, "reset all Mob Battle state")) run("resetall", false, false);
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
        confirmAction = null;
        if (close) close();
        java.util.concurrent.CompletableFuture<Void> done;
        try {
            done = CommandManager.get().handleCommand(playerRef, "mobbattle " + args);
        } catch (RuntimeException e) {
            if (!close) say("Could not run /mobbattle " + args + ".", WARN);
            return;
        }
        if (close) return;
        done.whenComplete((ignored, error) -> {
            if (store.isShutdown()) return;
            store.getExternalData().getWorld().execute(() -> {
                if (!entity.isValid() || store.isShutdown()) return;
                Player p = store.getComponent(entity, Player.getComponentType());
                if (p == null || p.getPageManager().getCustomPage() != this) return;
                notice = error != null ? "/mobbattle " + args + " failed." : "Done: /mobbattle " + args + " (see chat).";
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

    private static List<DropdownEntryInfo> entries(List<ConfigSchema.Opt> opts) {
        List<DropdownEntryInfo> out = new ArrayList<>(opts.size());
        for (ConfigSchema.Opt o : opts) out.add(new DropdownEntryInfo(LocalizableString.fromString(o.label()), o.value()));
        return out;
    }
}
