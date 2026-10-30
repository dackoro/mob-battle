package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Equipment Wand panel (2026-10-16; four tabs since 2026-10-29, playtest: the gear, runes, stats and kits on one page were
 * cluttered). Left, by tab: Gear (the clicked mob's six slots drawn as the game draws items, a click picks which slot
 * the gallery edits, plus copy/paste/restore/clear and a random set), Runes (rune bench lines), Stats (its own kit
 * stats, applied at once) and Kits (saved kits: apply, overwrite, delete, or save this mob as a new one). Right: a text
 * search (id and translated name), mod/type/group filters and a gallery grid of items; in the Kits tab the grid shows
 * what the picked kit holds. A pick is put on at once. Hands a role's own attacks need ({@link EquipWand#needed}) are
 * locked, as is the off hand of mobs without one or holding a two-handed item. Each player's tab, slot and rune hole
 * are kept for the next mob they open (playtest: open on the tab I was editing). Events as raw JSON, like ConfigPage.
 */
final class EquipPage extends InteractiveCustomUIPage<EquipPage.Unused> {

    static final class Unused {
        static final BuilderCodec<Unused> CODEC = BuilderCodec.builder(Unused.class, Unused::new).build();
    }

    private static final String UI = "MobBattle/EquipPage.ui";
    private static final String ROW = "MobBattle/Equip/EquipRow.ui";
    private static final String CELL = "MobBattle/Equip/GridCell.ui";
    private static final String FILTER = "MobBattle/Equip/EquipFilter.ui";
    private static final String RUNE_LINE = "MobBattle/Equip/RuneLine.ui";
    private static final String KIT_ROW = "MobBattle/Equip/KitRow.ui";
    /** The "no rune" cell of the rune gallery (the game's empty runestone, drawn as an item). */
    private static final String EMPTY_RUNE = "Rune_Empty";
    private static final String F = "#Filters[0]";
    /** Cells sent at most; the count line asks to search or filter for the rest. */
    private static final int GRID_MAX = 120;
    /** Each player's last {mod, type, group, search} (memory only). */
    private static final Map<UUID, String[]> LAST_FILTER = new ConcurrentHashMap<>();
    /** Each player's last {tab, slot, rune hole} and kit picked (memory only). */
    private static final Map<UUID, int[]> LAST_TAB = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_KIT = new ConcurrentHashMap<>();

    private static final int GEAR = 0, RUNES = 1, STATS = 2, KITS = 3;
    private static final String[] TAB_IDS = {"#TabGear", "#TabRunes", "#TabStats", "#TabKits"};
    private static final String[] TAB_BOXES = {"#GearBox", "#RuneBox", "#StatBox", "#KitBox"};
    private static final String[] TAB_WORDS = {"gear", "runes", "stats", "kits"};

    /** Filters of the hand lists: "" = all; mod "hytale" = the game's own. Weapons by default. */
    private String mod = "", type = "Weapon", group = "", search = "";
    /** The slot the gallery edits (EquipWand.SLOTS index). */
    private int selected = 0;
    private int tab = GEAR;
    /** The rune hole the gallery fills: line x 3 + (0 main rune, 1-2 supports). */
    private int hole = 0;
    /** The kit picked in the Kits tab (null = none). */
    private String kitPicked;
    /** Kit names shown in the list, in row order (the rows' bindings carry the index). */
    private List<String> kitRows = List.of();

    private final Ref<EntityStore> entity;
    private final Ref<EntityStore> mob;
    private final Store<EntityStore> store;
    private final String lang;

    private EquipPage(PlayerRef player, Ref<EntityStore> entity, Ref<EntityStore> mob, Store<EntityStore> store) {
        super(player, CustomPageLifetime.CanDismiss, Unused.CODEC);
        this.entity = entity;
        this.mob = mob;
        this.store = store;
        this.lang = player.getLanguage();
        String[] f = LAST_FILTER.get(player.getUuid());
        if (f != null) {
            mod = f[0];
            search = f[3];
        }
        int[] t = LAST_TAB.get(player.getUuid());
        if (t != null) {
            tab = Math.max(GEAR, Math.min(KITS, t[0]));
            selected = Math.max(0, Math.min(EquipWand.SLOTS.length - 1, t[1]));
            hole = Math.max(0, Math.min(RuneCaster.MAX_RUNES * 3 - 1, t[2]));
        }
        kitPicked = LAST_KIT.get(player.getUuid());
    }

    /** World thread. */
    static void open(PlayerRef player, Ref<EntityStore> entity, Ref<EntityStore> mob, Store<EntityStore> store) {
        if (store.isShutdown() || !entity.isValid() || !mob.isValid() || mob.getStore() != store) return;
        Player p = store.getComponent(entity, Player.getComponentType());
        if (p == null) return;
        p.getPageManager().openCustomPage(entity, store, new EquipPage(player, entity, mob, store));
    }

    // ---- building ----

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder ev, Store<EntityStore> st) {
        ui.append(UI);
        // the game's name in the viewer's language, not the role id (2026-10-30, playtest: it said "Skeleton_Fighter")
        ui.set("#Mob.Text", MobNames.text(EquipWand.nameOf(mob, st), playerRef));
        ui.append("#Filters", FILTER);
        for (String k : new String[] {"mod", "type", "group"}) {
            String el = "#" + Character.toUpperCase(k.charAt(0)) + k.substring(1);
            ev.addEventBinding(CustomUIEventBindingType.ValueChanged, F + " " + el,
                    data("Filter").append("K", k).append("@V", F + " " + el + ".Value"), false);
        }
        ui.set("#Search.Value", search);
        ev.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Search", data("Search").append("@V", "#Search.Value"), false);
        for (int i = 0; i < EquipWand.SLOTS.length; i++) {
            ui.append("#Rows", ROW);
            String sel = "#Rows[" + i + "]";
            I18n.text(ui, sel + " #Label.Text", I18n.tr("equip.slot." + EquipWand.SLOTS[i]));
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Pick", data("Slot").append("S", String.valueOf(i)), false);
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Clear", data("Clear").append("S", String.valueOf(i)), false);
        }
        for (int t = 0; t < TAB_IDS.length; t++) {
            ev.addEventBinding(CustomUIEventBindingType.Activating, TAB_IDS[t], data("Tab").append("V", TAB_WORDS[t]), false);
        }
        for (int l = 0; l < RuneCaster.MAX_RUNES; l++) {
            ui.append("#RuneLines", RUNE_LINE);
            String sel = "#RuneLines[" + l + "]";
            I18n.text(ui, sel + " #Label.Text", I18n.tr("equip.rune.line").param("n", String.valueOf(l + 1)));
            for (int k = 0; k < 3; k++) {
                ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #H" + k,
                        data("Hole").append("H", String.valueOf(l * 3 + k)), false);
            }
        }
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#Copy", data("Copy"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#Paste", data("Paste"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#Restore", data("Restore"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#ClearAll", data("ClearAll"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#Random", data("Random"), false);
        // Stats are read only when applied (no change events, so no echoes to sort out).
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#StatApply", data("Stats")
                .append("@SH", "#StHealth.Value").append("@SD", "#StDamage.Value").append("@SF", "#StDefense.Value")
                .append("@SS", "#StSpeed.Value").append("@SZ", "#StSize.Value"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#SaveKit", data("SaveKit").append("@N", "#KitName.Value"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyKit", data("ApplyKit"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#OverwriteKit", data("OverwriteKit"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteKit", data("DeleteKit"), false);
        kitList(ui, ev);
        statFields(ui);
        String[] now = EquipWand.read(mob, st);
        if (locked(selected, now)) selected = firstOpen(now);
        rowsOnly(ui, now);
        runeLines(ui);
        tabs(ui);
        fillFilters(ui);
        grid(ui, ev);
        notice(ui, now);
        status(ui, null);
    }

    /** Which tab shows, its mark, and the right side for it. */
    private void tabs(UICommandBuilder ui) {
        for (int t = 0; t < TAB_BOXES.length; t++) {
            ui.set(TAB_BOXES[t] + ".Visible", t == tab);
            ui.set(TAB_IDS[t] + "Mark.Visible", t == tab);
        }
        boolean gallery = tab == GEAR || tab == RUNES;
        ui.set("#SearchRow.Visible", gallery);
        ui.set("#Filters.Visible", gallery);
        ui.set("#SideHelp.Visible", !gallery);
        if (tab == STATS) I18n.text(ui, "#SideHelp.Text", I18n.tr("equip.stats.side"));
        if (tab == KITS) {
            Kits.Kit k = kitPicked != null ? Kits.get(kitPicked) : null;
            I18n.text(ui, "#SideHelp.Text", k != null ? kitSummary(k) : I18n.tr("equip.kits.side"));
        }
        editing(ui);
    }

    /** "Choosing for: Head", "Choosing for: Ability 2, support", or the Stats / Kits title. */
    private void editing(UICommandBuilder ui) {
        switch (tab) {
            // One key per slot: a translated param inside a .Text label showed "{slot}" (2026-10-16 screenshot).
            case GEAR -> I18n.text(ui, "#Editing.Text", I18n.tr("equip.editing." + EquipWand.SLOTS[selected]));
            case RUNES -> I18n.text(ui, "#Editing.Text", I18n.tr(hole % 3 == 0 ? "equip.editing.rune.core"
                    : "equip.editing.rune.support").param("n", String.valueOf(hole / 3 + 1)));
            case STATS -> I18n.text(ui, "#Editing.Text", I18n.tr("equip.editing.stats"));
            default -> I18n.text(ui, "#Editing.Text", kitPicked != null && Kits.get(kitPicked) != null
                    ? I18n.tr("equip.editing.kit").param("kit", kitPicked) : I18n.tr("equip.editing.kits"));
        }
    }

    /** The mob's rune lines: main rune and supports drawn, supports locked without a main rune, the hole marked. */
    private void runeLines(UICommandBuilder ui) {
        List<String> entries = RuneCaster.runesOf(mob);
        for (int l = 0; l < RuneCaster.MAX_RUNES; l++) {
            String sel = "#RuneLines[" + l + "]";
            String entry = l < entries.size() ? entries.get(l) : null;
            List<String> sups = RuneCaster.supportsOf(entry);
            for (int k = 0; k < 3; k++) {
                String id = entry == null ? null : k == 0 ? RuneCaster.coreOf(entry) : k - 1 < sups.size() ? sups.get(k - 1) : null;
                // a rune of a mod that is not loaded now (kept as written in a kit): not sent to the client
                if (id != null && com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(id) == null) id = null;
                if (id != null) ui.set(sel + " #I" + k + ".ItemId", id);
                ui.set(sel + " #I" + k + ".Visible", id != null);
                // a support needs its main rune; a line after the first empty one waits for it
                boolean open = k == 0 ? l <= entries.size() : entry != null;
                ui.set(sel + " #H" + k + ".Disabled", !open);
                ui.set(sel + " #M" + k + ".Visible", tab == RUNES && hole == l * 3 + k);
            }
        }
    }

    /** Every slot row: item drawn, its name, locked or not, and the mark on the slot being edited. */
    private void rowsOnly(UICommandBuilder ui, String[] now) {
        for (int i = 0; i < now.length; i++) {
            String sel = "#Rows[" + i + "]";
            if (now[i] != null) ui.set(sel + " #Icon.ItemId", now[i]);
            ui.set(sel + " #Icon.Visible", now[i] != null);
            ui.set(sel + " #Item.Text", now[i] != null ? nameOf(now[i]) : "-");
            boolean lock = locked(i, now);
            ui.set(sel + " #Pick.Disabled", lock);
            ui.set(sel + " #Clear.Disabled", lock || now[i] == null);
            ui.set(sel + " #Mark.Visible", i == selected);
        }
    }

    /** The right side's grid for the tab (clears and refills #Grid). */
    private void grid(UICommandBuilder ui, UIEventBuilder ev) {
        ui.clear("#Grid");
        switch (tab) {
            case RUNES -> runeGrid(ui, ev);
            case KITS -> kitGrid(ui);
            case STATS -> {
            }
            default -> gearGrid(ui, ev);
        }
    }

    /** The gallery for the selected slot with the filters and search. */
    private void gearGrid(UICommandBuilder ui, UIEventBuilder ev) {
        List<EquipWand.ItemInfo> all = EquipWand.listFor(selected, mod, selected < 2 ? type : "", selected < 2 ? group : "");
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (EquipWand.ItemInfo i : all) {
            if (!q.isEmpty() && !i.id().toLowerCase(Locale.ROOT).contains(q) && !nameOf(i.id()).toLowerCase(Locale.ROOT).contains(q)) continue;
            ids.add(i.id());
        }
        int shown = Math.min(GRID_MAX, ids.size());
        for (int j = 0; j < shown; j++) {
            String id = ids.get(j);
            ui.append("#Grid", CELL);
            String sel = "#Grid[" + j + "]";
            ui.set(sel + " #Slot.ItemId", id);
            ui.set(sel + " #Name.Text", nameOf(id));
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button", data("Pick").append("I", id), false);
        }
        // Plain numbers: an ICU plural in a .Text label showed "# objetos".
        I18n.text(ui, "#Count.Text", shown < ids.size()
                ? I18n.tr("equip.count.more").param("shown", String.valueOf(shown)).param("total", String.valueOf(ids.size()))
                : I18n.tr("equip.count").param("total", String.valueOf(ids.size())));
    }

    /**
     * The rune gallery: main runes for a main hole, else the supports that go with that line's main rune (the rune
     * bench's own keyword rules); first a "no rune" cell when the hole holds one.
     */
    private void runeGrid(UICommandBuilder ui, UIEventBuilder ev) {
        List<String> entries = RuneCaster.runesOf(mob);
        int line = hole / 3, k = hole % 3;
        String entry = line < entries.size() ? entries.get(line) : null;
        String core = RuneCaster.coreOf(entry);
        List<String> pool = new ArrayList<>();
        if (k == 0) pool.addAll(RuneCaster.runeIds());
        else if (core != null) for (String s : RuneCaster.supportIds()) if (RuneCaster.supports(s, core)) pool.add(s);
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (String id : pool) {
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q) && !nameOf(id).toLowerCase(Locale.ROOT).contains(q)) continue;
            ids.add(id);
        }
        boolean filled = entry != null && (k == 0 || k - 1 < RuneCaster.supportsOf(entry).size());
        int j = 0;
        if (filled && Item.getAssetMap().getAsset(EMPTY_RUNE) != null) {
            ui.append("#Grid", CELL);
            ui.set("#Grid[0] #Slot.ItemId", EMPTY_RUNE);
            I18n.text(ui, "#Grid[0] #Name.Text", I18n.tr("equip.rune.remove"));
            ev.addEventBinding(CustomUIEventBindingType.Activating, "#Grid[0] #Button", data("Pick").append("I", ""), false);
            j = 1;
        }
        for (String id : ids) {
            ui.append("#Grid", CELL);
            String sel = "#Grid[" + j + "]";
            ui.set(sel + " #Slot.ItemId", id);
            ui.set(sel + " #Name.Text", nameOf(id));
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button", data("Pick").append("I", id), false);
            j++;
        }
        I18n.text(ui, "#Count.Text", I18n.tr("equip.count").param("total", String.valueOf(ids.size())));
    }

    /** What the picked kit holds, drawn as items (each slot's options, then its runes); no clicks. */
    private void kitGrid(UICommandBuilder ui) {
        Kits.Kit k = kitPicked != null ? Kits.get(kitPicked) : null;
        if (k == null) return;
        List<String> ids = new ArrayList<>();
        for (List<String> opts : k.slots()) for (String o : opts) if (!Kits.EMPTY.equals(o) && !ids.contains(o)) ids.add(o);
        for (String r : k.runes()) {
            String core = RuneCaster.coreOf(r);
            if (core != null && !ids.contains(core)) ids.add(core);
            for (String s : RuneCaster.supportsOf(r)) if (!ids.contains(s)) ids.add(s);
        }
        int j = 0;
        for (String id : ids) {
            if (Item.getAssetMap().getAsset(id) == null) continue;
            ui.append("#Grid", CELL);
            String sel = "#Grid[" + j++ + "]";
            ui.set(sel + " #Slot.ItemId", id);
            ui.set(sel + " #Name.Text", nameOf(id));
        }
    }

    /** "3 pieces · 2 runes · health 500, defense +20%" under a kit's name (side panel). */
    private Message kitSummary(Kits.Kit k) {
        int pieces = 0;
        for (List<String> opts : k.slots()) if (!opts.isEmpty() && !opts.equals(List.of(Kits.EMPTY))) pieces++;
        List<String> stats = new ArrayList<>();
        for (Buffs.Stat st : Kits.STAT_ORDER) {
            Integer v = k.stats().get(st);
            if (v != null) stats.add(st == Buffs.Stat.HEALTH ? v + " hp" : st.key + " +" + v + "%");
        }
        return I18n.tr("equip.kit.info").param("pieces", String.valueOf(pieces)).param("runes", String.valueOf(k.runes().size()))
                .param("stats", stats.isEmpty() ? "-" : String.join(", ", stats));
    }

    /** A gallery pick in the Runes tab ("" = take the rune out of the hole), on the mob at once. Returns what to say. */
    private Message runePick(String id) {
        List<String> entries = new ArrayList<>(RuneCaster.runesOf(mob));
        int line = hole / 3, k = hole % 3;
        boolean empty = id == null || id.isEmpty();
        if (k == 0) {
            if (empty) {
                if (line < entries.size()) entries.remove(line);
            } else {
                for (int l = 0; l < entries.size(); l++) {
                    if (l != line && id.equals(RuneCaster.coreOf(entries.get(l)))) return I18n.tr("equip.rune.twice");
                }
                if (line < entries.size()) {
                    // a new main rune keeps the supports that still go with it
                    List<String> keep = new ArrayList<>();
                    for (String s : RuneCaster.supportsOf(entries.get(line))) if (RuneCaster.supports(s, id)) keep.add(s);
                    entries.set(line, RuneCaster.entry(id, keep));
                } else {
                    entries.add(RuneCaster.entry(id, List.of()));
                    hole = (entries.size() - 1) * 3;
                }
            }
        } else {
            if (line >= entries.size()) return I18n.tr("equip.rune.corefirst");
            String core = RuneCaster.coreOf(entries.get(line));
            List<String> sups = new ArrayList<>(RuneCaster.supportsOf(entries.get(line)));
            if (empty) {
                if (k - 1 < sups.size()) sups.remove(k - 1);
            } else {
                if (!RuneCaster.supports(id, core)) return I18n.tr("equip.rune.nofit");
                int other = sups.indexOf(id);
                if (other >= 0 && other != k - 1) return I18n.tr("equip.rune.twice");
                if (k - 1 < sups.size()) sups.set(k - 1, id);
                else sups.add(id);
            }
            entries.set(line, RuneCaster.entry(core, sups));
        }
        RuneCaster.set(mob, entries);
        if (!empty) EquipWand.feedback(mob, true, store);
        return I18n.tr(empty ? "equip.rune.removed" : "equip.rune.set");
    }

    /**
     * A random set (2026-10-29): a weapon, maybe an off hand item, a matching armor set when the game has one
     * (same name before the slot word, e.g. Armor_Iron_*), and one or two rune lines with supports that fit. Hands the
     * mob can't change (training dummy, needed weapons) are left alone.
     */
    private Message randomSet() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String[] now = EquipWand.read(mob, store);
        if (!locked(0, now)) {
            List<EquipWand.ItemInfo> weapons = EquipWand.listFor(0, "", "Weapon", "");
            if (!weapons.isEmpty()) EquipWand.set(mob, 0, weapons.get(rnd.nextInt(weapons.size())).id(), store);
            now = EquipWand.read(mob, store);
        }
        if (!locked(1, now)) {
            List<EquipWand.ItemInfo> shields = EquipWand.listFor(1, "", "Weapon", "Shield");
            EquipWand.set(mob, 1, shields.isEmpty() || rnd.nextBoolean() ? null : shields.get(rnd.nextInt(shields.size())).id(), store);
        }
        String family = null;
        for (int slot = 2; slot < EquipWand.SLOTS.length; slot++) {
            List<EquipWand.ItemInfo> list = EquipWand.listFor(slot, "", "", "");
            if (list.isEmpty()) continue;
            String pick = null;
            if (family != null) {
                List<String> same = new ArrayList<>();
                for (EquipWand.ItemInfo i : list) if (i.id().startsWith(family)) same.add(i.id());
                if (!same.isEmpty()) pick = same.get(rnd.nextInt(same.size()));
            }
            if (pick == null) pick = list.get(rnd.nextInt(list.size())).id();
            if (family == null && pick.lastIndexOf('_') > 0) family = pick.substring(0, pick.lastIndexOf('_') + 1);
            EquipWand.set(mob, slot, pick, store);
        }
        List<String> cores = new ArrayList<>(RuneCaster.runeIds());
        Collections.shuffle(cores);
        List<String> entries = new ArrayList<>();
        int lines = 1 + rnd.nextInt(2);
        for (String core : cores) {
            if (entries.size() >= lines) break;
            List<String> fit = new ArrayList<>();
            for (String s : RuneCaster.supportIds()) if (RuneCaster.supports(s, core)) fit.add(s);
            Collections.shuffle(fit);
            entries.add(RuneCaster.entry(core, fit.subList(0, Math.min(fit.size(), rnd.nextInt(RuneCaster.MAX_SUPPORTS + 1)))));
        }
        if (!entries.isEmpty()) RuneCaster.set(mob, entries);
        EquipWand.feedback(mob, true, store);
        return I18n.tr(EquipWand.fixedHands(EquipWand.nameOf(mob, store)) ? "equip.random.dummy" : "equip.random.done");
    }

    // ---- events ----

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> st, String raw) {
        if (ref != entity || st != store || store.isShutdown() || !entity.isValid()) return;
        if (!mob.isValid()) {
            close();
            return;
        }
        BsonDocument doc;
        try {
            doc = BsonDocument.parse(raw);
        } catch (RuntimeException e) {
            return;
        }
        String kind = str(doc, "T");
        if (kind == null) return;
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder ev = new UIEventBuilder();
        boolean regrid = false, rekits = false;
        Message msg = null;
        switch (kind) {
            case "Slot" -> {
                int slot = parseInt(str(doc, "S"));
                if (slot < 0 || slot >= EquipWand.SLOTS.length || locked(slot, EquipWand.read(mob, store))) return;
                selected = slot;
                fillFilters(ui);
                regrid = true;
            }
            case "Tab" -> {
                int t = List.of(TAB_WORDS).indexOf(str(doc, "V"));
                if (t < 0) return;
                tab = t;
                if (tab == STATS) statFields(ui);
                fillFilters(ui);
                regrid = true;
            }
            case "Hole" -> {
                int h = parseInt(str(doc, "H"));
                if (h < 0 || h >= RuneCaster.MAX_RUNES * 3) return;
                hole = h;
                tab = RUNES;
                fillFilters(ui);
                regrid = true;
            }
            case "Pick", "Clear" -> {
                if (tab == RUNES && kind.equals("Pick")) {
                    msg = runePick(str(doc, "I"));
                    regrid = true;
                    break;
                }
                if (tab != GEAR && kind.equals("Pick")) return;
                int slot = kind.equals("Pick") ? selected : parseInt(str(doc, "S"));
                if (slot < 0 || slot >= EquipWand.SLOTS.length || locked(slot, EquipWand.read(mob, store))) return;
                String item = kind.equals("Pick") ? str(doc, "I") : null;
                String why = EquipWand.set(mob, slot, item, store);
                if (why != null) msg = I18n.tr("equip.why." + why.replace(' ', '_').replace("'", ""));
                else if (slot == 0 && EquipWand.twoHanded(item)) msg = I18n.tr("equip.twohanded");
                if (why == null) EquipWand.feedback(mob, true, store);
            }
            case "Search" -> {
                String v = str(doc, "@V");
                if (v == null || v.equals(search)) return;
                search = v;
                regrid = true;
            }
            case "Filter" -> {
                String k = str(doc, "K"), v = str(doc, "@V");
                if (k == null || v == null) return;
                if (tab != GEAR || selected >= 2 && !k.equals("mod")) return;
                // Refilling a dropdown's entries makes the client send a change to its first entry ("All ...")
                // before the value we set arrives: that reset the type to all types (2026-10-16 screenshot).
                if (System.currentTimeMillis() < ignoreFiltersUntil) return;
                switch (k) {
                    case "mod" -> mod = v;
                    case "type" -> type = v;
                    case "group" -> group = v;
                    default -> {
                        return;
                    }
                }
                // A mod or type change keeps the group only if it still has items.
                if (!k.equals("group") && !group.isEmpty() && !EquipWand.groupsFor(mod, type).contains(group)) group = "";
                if (k.equals("mod") && !type.isEmpty() && !EquipWand.typesFor(mod).contains(type)) type = "";
                fillFilters(ui);
                regrid = true;
            }
            case "Copy" -> {
                EquipWand.copy(playerRef.getUuid(), EquipWand.read(mob, store));
                EquipWand.feedback(mob, false, store);
                msg = I18n.tr("equip.copied");
            }
            case "Paste" -> {
                String[] set = EquipWand.copied(playerRef.getUuid());
                if (set == null) msg = I18n.tr("chat.equip.nothingcopied");
                else {
                    msg = I18n.tr(EquipWand.apply(mob, set, store).isEmpty() ? "equip.pastedhere" : "equip.pastedkept");
                    EquipWand.feedback(mob, true, store);
                }
            }
            case "Restore" -> msg = I18n.tr(EquipWand.restore(mob, store) ? "equip.restored" : "equip.norestore");
            case "ClearAll" -> {
                boolean kept = !EquipWand.apply(mob, new String[EquipWand.SLOTS.length], store).isEmpty();
                msg = kept ? I18n.tr("equip.clearedkept") : null;
            }
            case "Random" -> {
                msg = randomSet();
                regrid = tab == RUNES;
            }
            case "Stats" -> {
                applyStats(statsOf(doc));
                EquipWand.feedback(mob, true, store);
                msg = I18n.tr("equip.stats.applied");
            }
            case "PickKit" -> {
                int i = parseInt(str(doc, "K"));
                if (i < 0 || i >= kitRows.size()) return;
                kitPicked = kitRows.get(i);
                LAST_KIT.put(playerRef.getUuid(), kitPicked);
                rekits = true;
                regrid = true;
            }
            case "ApplyKit" -> {
                Kits.Kit kit = kitPicked != null ? Kits.get(kitPicked) : null;
                if (kit == null) msg = I18n.tr("equip.kitpickfirst");
                else {
                    Kits.Applied done = Kits.apply(mob, kit, store);
                    EquipWand.feedback(mob, true, store);
                    msg = I18n.tr(done != null && done.handsKept() ? "equip.kitapplied.dummy" : "equip.kitapplied").param("kit", kit.name());
                }
            }
            case "OverwriteKit" -> {
                Kits.Kit kit = kitPicked != null ? Kits.get(kitPicked) : null;
                if (!kitsAllowed()) msg = I18n.tr("chat.perms.denied").param("perm", Perms.CONFIG);
                else if (kit == null) msg = I18n.tr("equip.kitpickfirst");
                else {
                    Kits.put(fromMob(kit.name()));
                    FxSettings.save();
                    msg = I18n.tr("equip.kitoverwritten").param("kit", kit.name());
                    rekits = true;
                    regrid = true;
                }
            }
            case "DeleteKit" -> {
                Kits.Kit kit = kitPicked != null ? Kits.get(kitPicked) : null;
                if (!kitsAllowed()) msg = I18n.tr("chat.perms.denied").param("perm", Perms.CONFIG);
                else if (kit == null) msg = I18n.tr("equip.kitpickfirst");
                else {
                    Kits.remove(kit.name());
                    FxSettings.save();
                    msg = I18n.tr("equip.kitdeleted").param("kit", kit.name());
                    kitPicked = null;
                    LAST_KIT.remove(playerRef.getUuid());
                    rekits = true;
                    regrid = true;
                }
            }
            case "SaveKit" -> {
                String name = str(doc, "@N");
                name = name == null ? "" : name.trim();
                // Only a new name: overwriting is its own button, so a kit is never replaced by accident.
                if (!kitsAllowed()) msg = I18n.tr("chat.perms.denied").param("perm", Perms.CONFIG);
                else if (!Kits.validName(name)) msg = I18n.tr("cmd.kit.name");
                else if (Kits.get(name) != null) msg = I18n.tr("equip.kitexists").param("kit", name);
                else if (!Kits.put(fromMob(name))) msg = I18n.tr("cmd.kit.full").param("max", String.valueOf(Kits.MAX_KITS));
                else {
                    FxSettings.save();
                    kitPicked = name;
                    LAST_KIT.put(playerRef.getUuid(), name);
                    ui.set("#KitName.Value", "");
                    msg = I18n.tr("equip.kitsaved").param("kit", name);
                    rekits = true;
                    regrid = true;
                }
            }
            default -> {
                return;
            }
        }
        LAST_FILTER.put(playerRef.getUuid(), new String[] {mod, type, group, search});
        LAST_TAB.put(playerRef.getUuid(), new int[] {tab, selected, hole});
        String[] now = EquipWand.read(mob, store);
        if (locked(selected, now)) {
            selected = firstOpen(now);
            fillFilters(ui);
            regrid = true;
        }
        rowsOnly(ui, now);
        runeLines(ui);
        if (rekits) kitList(ui, ev);
        tabs(ui);
        notice(ui, now);
        status(ui, msg);
        if (regrid) grid(ui, ev);
        sendUpdate(ui, ev, false);
    }

    /** A kit named {@code name} from this mob as it is now: its gear, its runes and its own stats. */
    private Kits.Kit fromMob(String name) {
        Map<Buffs.Stat, Integer> stats = new EnumMap<>(Buffs.Stat.class);
        for (Buffs.Stat s : Kits.STAT_ORDER) {
            int v = statValue(s);
            if (v > 0) stats.put(s, v);
        }
        return Kits.withExtras(Kits.fromSet(name, EquipWand.read(mob, store)), RuneCaster.runesOf(mob), stats);
    }

    /** Saved kits as rows (name and what they hold), the picked one marked. */
    private void kitList(UICommandBuilder ui, UIEventBuilder ev) {
        ui.clear("#KitList");
        List<String> names = Kits.names();
        if (kitPicked != null && Kits.get(kitPicked) == null) kitPicked = null;
        kitRows = List.copyOf(names);
        for (int i = 0; i < names.size(); i++) {
            Kits.Kit k = Kits.get(names.get(i));
            if (k == null) continue;
            ui.append("#KitList", KIT_ROW);
            String sel = "#KitList[" + i + "]";
            ui.set(sel + " #Name.Text", k.name());
            I18n.text(ui, sel + " #Info.Text", kitSummary(k));
            ui.set(sel + " #Mark.Visible", k.name().equalsIgnoreCase(kitPicked == null ? "" : kitPicked));
            ev.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Pick", data("PickKit").append("K", String.valueOf(i)), false);
        }
        boolean picked = kitPicked != null;
        ui.set("#ApplyKit.Disabled", !picked);
        ui.set("#OverwriteKit.Disabled", !picked);
        ui.set("#DeleteKit.Disabled", !picked);
    }

    /** The five stat fields, in Kits.STAT_ORDER (health in hit points, the rest in percent). */
    private static final String[] STAT_FIELDS = {"#StHealth", "#StDamage", "#StDefense", "#StSpeed", "#StSize"};
    private static final String[] STAT_KEYS = {"@SH", "@SD", "@SF", "@SS", "@SZ"};

    /** The Stats tab shows the mob's own stats now. */
    private void statFields(UICommandBuilder ui) {
        for (int i = 0; i < STAT_FIELDS.length; i++) ui.set(STAT_FIELDS[i] + ".Value", statValue(Kits.STAT_ORDER.get(i)));
    }

    /** The mob's direct amount of a stat, health as its max hit points with it (0 = none). */
    private int statValue(Buffs.Stat s) {
        int pct = Buffs.directOf(mob, s);
        if (s != Buffs.Stat.HEALTH || pct <= 0) return pct;
        float base = Buffs.baseMaxHealth(mob, store);
        return base > 0 ? Math.round(base * (1 + pct / 100f)) : 0;
    }

    /** Stats from the tab on the mob: a value over 0 is put on (as a kit's), 0 takes that stat off. */
    private void applyStats(Map<Buffs.Stat, Integer> values) {
        Map<Buffs.Stat, Integer> on = new EnumMap<>(Buffs.Stat.class);
        for (Buffs.Stat s : Kits.STAT_ORDER) {
            Integer v = values.get(s);
            if (v != null && v > 0 && !(s == Buffs.Stat.HEALTH && v <= Buffs.baseMaxHealth(mob, store))) on.put(s, v);
            else Buffs.setDirect(mob, s, 0);
        }
        Kits.applyStats(mob, on, store);
    }

    /** The stat fields of the Stats tab: 0 or empty = none. */
    private static Map<Buffs.Stat, Integer> statsOf(BsonDocument doc) {
        Map<Buffs.Stat, Integer> out = new EnumMap<>(Buffs.Stat.class);
        for (int i = 0; i < STAT_KEYS.length; i++) {
            BsonValue v = doc.get(STAT_KEYS[i]);
            double d = v == null ? 0 : v.isInt32() ? v.asInt32().getValue() : v.isInt64() ? v.asInt64().getValue()
                    : v.isDouble() ? v.asDouble().getValue() : v.isString() ? parseDouble(v.asString().getValue()) : 0;
            Buffs.Stat st = Kits.STAT_ORDER.get(i);
            int n = (int) Math.round(d);
            if (n > 0) out.put(st, Math.min(Kits.statMax(st), n));
        }
        return out;
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- slots ----

    /** A hand the role's attacks need, an off hand the mob lacks or a two-handed item blocks. */
    private boolean locked(int slot, String[] now) {
        if (slot >= 2) return false;
        if (EquipWand.fixedHands(EquipWand.nameOf(mob, store))) return true;
        if (!EquipWand.needed(EquipWand.nameOf(mob, store))[slot].isEmpty()) return true;
        return slot == 1 && (!EquipWand.hasOffHand(mob, store) || EquipWand.twoHanded(now[0]));
    }

    private int firstOpen(String[] now) {
        for (int i = 0; i < EquipWand.SLOTS.length; i++) if (!locked(i, now)) return i;
        return 2;
    }

    /** One line under the name: why a hand is locked (empty when none is). */
    private void notice(UICommandBuilder ui, String[] now) {
        Message m = null;
        if (EquipWand.fixedHands(EquipWand.nameOf(mob, store))) m = I18n.tr("equip.notice.dummy");
        else {
            List<String>[] need = EquipWand.needed(EquipWand.nameOf(mob, store));
            List<String> all = new ArrayList<>(need[0]);
            for (String s : need[1]) if (!all.contains(s)) all.add(s);
            if (!all.isEmpty()) m = I18n.tr("equip.notice.needs").param("items", String.join(", ", all));
            else if (!EquipWand.hasOffHand(mob, store)) m = I18n.tr("equip.nooffhand");
            else if (EquipWand.twoHanded(now[0])) m = I18n.tr("equip.twohanded");
        }
        ui.set("#Notice.Visible", m != null);
        if (m != null) I18n.text(ui, "#Notice.Text", m);
    }

    private static void status(UICommandBuilder ui, Message m) {
        I18n.text(ui, "#Status.Text", m != null ? m : Message.raw(""));
    }

    // ---- filters and names ----

    /** Filter changes before this time are the client's echo of a refill, not the player's pick. */
    private long ignoreFiltersUntil;

    /**
     * Mod, type and group choices with the values picked now (types follow the mod, groups the mod and type). A list
     * with nothing to choose is hidden; an armor slot shows only the mod (2026-10-16, playtest: a mob with locked hands
     * opened on the weapons view).
     */
    private void fillFilters(UICommandBuilder ui) {
        ignoreFiltersUntil = System.currentTimeMillis() + 600;
        // Lists with nothing to choose are hidden, not disabled: a disabled dropdown made a click on the type list
        // open beside it (2026-10-16 screenshot).
        if (tab != GEAR) {
            ui.set(F + " #Mod.Visible", false);
            ui.set(F + " #Type.Visible", false);
            ui.set(F + " #Group.Visible", false);
            return;
        }
        // Only mods with something for this slot (playtest 2026-10-29: the head's list offered mods with no helmet).
        List<String> modList = EquipWand.modsFor(selected);
        if (!mod.isEmpty() && !modList.contains(mod)) mod = "";
        List<DropdownEntryInfo> mods = new ArrayList<>();
        mods.add(entry("All mods", ""));
        for (String m : modList) mods.add(entry(m.equals("hytale") ? "Hytale" : m, m));
        ui.set(F + " #Mod.Entries", mods);
        ui.set(F + " #Mod.Value", mod);
        ui.set(F + " #Mod.Visible", true);
        if (selected >= 2) {
            ui.set(F + " #Type.Visible", false);
            ui.set(F + " #Group.Visible", false);
            return;
        }
        List<String> typeList = EquipWand.typesFor(mod);
        List<DropdownEntryInfo> types = new ArrayList<>();
        types.add(entry("All types", ""));
        for (String t : typeList) types.add(entry(t, t));
        ui.set(F + " #Type.Entries", types);
        ui.set(F + " #Type.Value", type);
        ui.set(F + " #Type.Visible", !typeList.isEmpty());
        List<String> groupList = EquipWand.groupsFor(mod, type);
        List<DropdownEntryInfo> groups = new ArrayList<>();
        groups.add(entry("All groups", ""));
        for (String g : groupList) groups.add(entry(g, g));
        ui.set(F + " #Group.Entries", groups);
        ui.set(F + " #Group.Value", group);
        ui.set(F + " #Group.Visible", !groupList.isEmpty());
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    /** Translated item names per language (the server's own lang files), cached. */
    private static final Map<String, Map<String, String>> NAMES = new ConcurrentHashMap<>();

    /**
     * The item's name in the player's language, else a readable form of its id. A translation with a placeholder left
     * in it (another mod's "{slot} ..." name, 2026-10-29 screenshot) falls back to the id form too.
     */
    private String nameOf(String id) {
        return NAMES.computeIfAbsent(lang == null ? "" : lang, l -> new ConcurrentHashMap<>()).computeIfAbsent(id, k -> {
            try {
                Item item = Item.getAssetMap().getAsset(k);
                String key = item != null ? item.getTranslationKey() : null;
                if (key != null) {
                    var i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
                    String language = lang != null ? lang : com.hypixel.hytale.server.core.modules.i18n.I18nModule.DEFAULT_LANGUAGE;
                    String text = i18n.getMessage(language, key);
                    if ((text == null || text.isEmpty()) && key.startsWith("server.")) text = i18n.getMessage(language, key.substring(7));
                    if (text != null && !text.isEmpty() && !text.equals(key) && !text.contains("{")) return text;
                }
            } catch (RuntimeException ignored) {
                // no translation: the id form below
            }
            return k.replaceFirst("^(Weapon|Tool|Armor)_", "").replace('_', ' ');
        });
    }

    private static EventData data(String type) {
        return EventData.of("T", type);
    }

    private static String str(BsonDocument doc, String key) {
        BsonValue v = doc.get(key);
        return v != null && v.isString() ? v.asString().getValue() : null;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Saving, overwriting or deleting a server kit needs the kit commands' permission (audit 2026-10-29: the panel
     * only needs the wand's USE, so anyone with the wand could delete every kit). */
    private boolean kitsAllowed() {
        return Perms.allowed(playerRef, Perms.CONFIG);
    }
}
