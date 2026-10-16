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
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Equipment Wand panel (2026-10-16). Left: the clicked mob's six slots drawn as the game draws items (ItemSlot); a
 * click picks which slot the gallery edits. Right: a text search (id and translated name), mod/type/group filters and
 * a gallery grid of items (user: names were hard to read in a dropdown, a grid with the item drawn is clearer). A
 * pick is put on at once. Hands a role's own attacks need ({@link EquipWand#needed}) are locked, as is the off hand
 * of mobs without one or holding a two-handed item. Events as raw JSON, like {@link ConfigPage}.
 */
final class EquipPage extends InteractiveCustomUIPage<EquipPage.Unused> {

    static final class Unused {
        static final BuilderCodec<Unused> CODEC = BuilderCodec.builder(Unused.class, Unused::new).build();
    }

    private static final String UI = "MobBattle/EquipPage.ui";
    private static final String ROW = "MobBattle/Equip/EquipRow.ui";
    private static final String CELL = "MobBattle/Equip/GridCell.ui";
    private static final String FILTER = "MobBattle/Equip/EquipFilter.ui";
    private static final String F = "#Filters[0]";
    /** Cells sent at most; the count line asks to search or filter for the rest. */
    private static final int GRID_MAX = 120;
    /** Each player's last {mod, type, group, search} (memory only). */
    private static final Map<UUID, String[]> LAST_FILTER = new ConcurrentHashMap<>();

    /** Filters of the hand lists: "" = all; mod "hytale" = the game's own. Weapons by default. */
    private String mod = "", type = "Weapon", group = "", search = "";
    /** The slot the gallery edits (EquipWand.SLOTS index). */
    private int selected = 0;

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
        ui.set("#Mob.Text", EquipWand.nameOf(mob, st));
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
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#Copy", data("Copy"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#Paste", data("Paste"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#Restore", data("Restore"), false);
        ev.addEventBinding(CustomUIEventBindingType.Activating, "#ClearAll", data("ClearAll"), false);
        String[] now = EquipWand.read(mob, st);
        if (locked(selected, now)) selected = firstOpen(now);
        rows(ui, now);
        fillFilters(ui);
        grid(ui, ev);
        status(ui, defaultStatus(now));
    }

    /** Every slot row: item drawn, its name, locked or not, and the mark on the slot being edited. */
    private void rows(UICommandBuilder ui, String[] now) {
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
        // One key per slot: a translated param inside a .Text label showed "{slot}" (2026-10-16 screenshot).
        I18n.text(ui, "#Editing.Text", I18n.tr("equip.editing." + EquipWand.SLOTS[selected]));
    }

    /** The gallery for the selected slot with the filters and search (clears and refills #Grid). */
    private void grid(UICommandBuilder ui, UIEventBuilder ev) {
        ui.clear("#Grid");
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
        boolean regrid = false;
        Message msg = null;
        switch (kind) {
            case "Slot" -> {
                int slot = parseInt(str(doc, "S"));
                if (slot < 0 || slot >= EquipWand.SLOTS.length || locked(slot, EquipWand.read(mob, store))) return;
                selected = slot;
                fillFilters(ui);
                regrid = true;
            }
            case "Pick", "Clear" -> {
                int slot = kind.equals("Pick") ? selected : parseInt(str(doc, "S"));
                if (slot < 0 || slot >= EquipWand.SLOTS.length || locked(slot, EquipWand.read(mob, store))) return;
                String item = kind.equals("Pick") ? str(doc, "I") : null;
                String why = EquipWand.set(mob, slot, item, store);
                if (why != null) msg = I18n.tr("equip.why." + why.replace(' ', '_').replace("'", ""));
                else if (slot == 0 && EquipWand.twoHanded(item)) msg = I18n.tr("equip.twohanded");
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
                if (selected >= 2 && !k.equals("mod")) return;
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
                msg = I18n.tr("equip.copied");
            }
            case "Paste" -> {
                String[] set = EquipWand.copied(playerRef.getUuid());
                if (set == null) msg = I18n.tr("chat.equip.nothingcopied");
                else msg = I18n.tr(EquipWand.apply(mob, set, store).isEmpty() ? "equip.pastedhere" : "equip.pastedkept");
            }
            case "Restore" -> msg = I18n.tr(EquipWand.restore(mob, store) ? "equip.restored" : "equip.norestore");
            case "ClearAll" -> {
                boolean kept = !EquipWand.apply(mob, new String[EquipWand.SLOTS.length], store).isEmpty();
                msg = kept ? I18n.tr("equip.clearedkept") : null;
            }
            default -> {
                return;
            }
        }
        LAST_FILTER.put(playerRef.getUuid(), new String[] {mod, type, group, search});
        String[] now = EquipWand.read(mob, store);
        if (locked(selected, now)) {
            selected = firstOpen(now);
            fillFilters(ui);
            regrid = true;
        }
        rows(ui, now);
        status(ui, msg != null ? msg : defaultStatus(now));
        if (regrid) grid(ui, ev);
        sendUpdate(ui, ev, false);
    }

    // ---- slots ----

    /** A hand the role's attacks need, an off hand the mob lacks or a two-handed item blocks. */
    private boolean locked(int slot, String[] now) {
        if (slot >= 2) return false;
        if (!EquipWand.needed(EquipWand.nameOf(mob, store))[slot].isEmpty()) return true;
        return slot == 1 && (!EquipWand.hasOffHand(mob, store) || EquipWand.twoHanded(now[0]));
    }

    private int firstOpen(String[] now) {
        for (int i = 0; i < EquipWand.SLOTS.length; i++) if (!locked(i, now)) return i;
        return 2;
    }

    /** Why a hand is locked, or null: shown when nothing else was said. */
    private Message defaultStatus(String[] now) {
        List<String>[] need = EquipWand.needed(EquipWand.nameOf(mob, store));
        List<String> all = new ArrayList<>(need[0]);
        for (String s : need[1]) if (!all.contains(s)) all.add(s);
        if (!all.isEmpty()) return I18n.tr("equip.locked").param("items", String.join(", ", all));
        if (!EquipWand.hasOffHand(mob, store)) return I18n.tr("equip.nooffhand");
        if (EquipWand.twoHanded(now[0])) return I18n.tr("equip.twohanded");
        return null;
    }

    private static void status(UICommandBuilder ui, Message m) {
        I18n.text(ui, "#Status.Text", m != null ? m : Message.raw(""));
    }

    // ---- filters and names ----

    /**
     * Mod, type and group choices with the values picked now (types follow the mod, groups the mod and type). A list
     * with nothing to choose is disabled; an armor slot shows its type and slot fixed (2026-10-16, user: a mob with
     * locked hands opened on the weapons view).
     */
    /** Filter changes before this time are the client's echo of a refill, not the player's pick. */
    private long ignoreFiltersUntil;

    private void fillFilters(UICommandBuilder ui) {
        ignoreFiltersUntil = System.currentTimeMillis() + 600;
        // Lists with nothing to choose are hidden, not disabled: a disabled dropdown made a click on the type list
        // open beside it (2026-10-16 screenshot). An armor slot shows only the mod (its items are that slot's armor).
        List<DropdownEntryInfo> mods = new ArrayList<>();
        mods.add(entry("All mods", ""));
        mods.add(entry("Hytale", "hytale"));
        for (String m : EquipWand.mods()) mods.add(entry(m, m));
        ui.set(F + " #Mod.Entries", mods);
        ui.set(F + " #Mod.Value", mod);
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

    /** The item's name in the player's language, else a readable form of its id. */
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
                    if (text != null && !text.isEmpty() && !text.equals(key)) return text;
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
}
