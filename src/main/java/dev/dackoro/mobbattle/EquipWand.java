package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Equipment Wand (2026-10-16, user): right click a mob opens {@link EquipPage} (hand, off hand, four armor slots),
 * crouch + right click copies its set, left click pastes it, held left pastes it on every mob in {@link #RADIUS}. Mobs only. Weapons are
 * looks only (attacks come from the role); armor protects (vanilla resistances), measured with test duel.
 */
final class EquipWand {

    static final double RADIUS = 12;

    /** Slot order of a set: hand, off hand, then the armor slots in ItemArmorSlot order. */
    static final String[] SLOTS = {"hand", "offhand", "head", "chest", "hands", "legs"};

    /** Each player's copied set (UUID -> item ids by slot, null = empty). Memory only. */
    private static final Map<UUID, String[]> COPIED = new ConcurrentHashMap<>();

    private EquipWand() {
    }

    static void copy(UUID player, String[] set) {
        COPIED.put(player, set.clone());
    }

    static String[] copied(UUID player) {
        String[] s = COPIED.get(player);
        return s == null ? null : s.clone();
    }

    static Inventory inventory(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        NPCEntity npc = acc.getComponent(mob, NPCEntity.getComponentType());
        return npc != null ? npc.getInventory() : null;
    }

    /** What the mob wears now, by slot. */
    static String[] read(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        String[] out = new String[SLOTS.length];
        Inventory inv = inventory(mob, acc);
        if (inv == null) return out;
        out[0] = idOf(inv.getActiveHotbarItem());
        String off = idOf(inv.getUtilityItem());
        // No active off hand slot (a role never set one): what sits in slot 0, where the wand puts it.
        if (off == null && inv.getUtility() != null && inv.getUtility().getCapacity() > 0) off = idOf(inv.getUtility().getItemStack((short) 0));
        out[1] = off;
        @SuppressWarnings("removal")
        var armor = inv.getArmor();
        for (int i = 0; i < 4 && i < armor.getCapacity(); i++) out[2 + i] = idOf(armor.getItemStack((short) i));
        return out;
    }

    private static String idOf(ItemStack s) {
        return s == null || s.getItemId() == null || s.getItemId().isEmpty() ? null : s.getItemId();
    }

    /**
     * Does this main hand item take both hands? The game's rule for players: only items with
     * {@code "Utility": {"Compatible": true}} (swords, axes, clubs, crossbows, wands...) let the off hand be used;
     * battleaxes, longswords, maces, spears, staffs, bows, daggers and spellbooks don't. Empty hand = no.
     */
    static boolean twoHanded(String item) {
        if (item == null) return false;
        var asset = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(item);
        if (asset == null || asset.getArmor() != null) return false;
        var utility = asset.getUtility();
        return utility == null || !utility.isCompatible();
    }

    /** Can this mob hold something in its off hand (its role gives it off hand slots)? */
    static boolean hasOffHand(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        Inventory inv = inventory(mob, acc);
        return inv != null && inv.getUtility() != null && inv.getUtility().getCapacity() > 0;
    }

    /**
     * Puts one slot (item null = empty it). Returns null, or a skip reason in English (the chat translates it).
     * World thread.
     */
    static String set(Ref<EntityStore> mob, int slot, String item, ComponentAccessor<EntityStore> acc) {
        String why = setSlot(mob, slot, item, acc);
        if (why == null) markEquipment(mob, acc);
        return why;
    }

    /**
     * Viewers get a mob's equipment (EquipmentUpdate: armor, right hand, left hand = active off hand item) only when
     * its Hotbar/Utility/Armor component is flagged outdated (vanilla InventorySystems.SyncEquipmentSystem, javap);
     * writing an off hand slot did not flag it, so a shield put on by the wand never showed (user, 2026-10-16).
     */
    static void markEquipment(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        var util = acc.getComponent(mob, com.hypixel.hytale.server.core.inventory.InventoryComponent.Utility.getComponentType());
        if (util != null) util.setOutdatedEquipment(true);
        var bar = acc.getComponent(mob, com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar.getComponentType());
        if (bar != null) bar.setOutdatedEquipment(true);
        var armor = acc.getComponent(mob, com.hypixel.hytale.server.core.inventory.InventoryComponent.Armor.getComponentType());
        if (armor != null) armor.setOutdatedEquipment(true);
    }

    @SuppressWarnings("removal")
    private static String setSlot(Ref<EntityStore> mob, int slot, String item, ComponentAccessor<EntityStore> acc) {
        Inventory inv = inventory(mob, acc);
        if (inv == null) return "not a mob";
        switch (slot) {
            case 0 -> {
                String why = fillHand(mob, inv.getHotbar(), item, false, acc);
                // A two-handed item empties the off hand, as a player's does.
                if (why == null && twoHanded(item) && inv.getUtility() != null && inv.getUtility().getCapacity() > 0) {
                    fillHand(mob, inv.getUtility(), null, true, acc);
                }
                return why;
            }
            case 1 -> {
                var util = inv.getUtility();
                if (util == null || util.getCapacity() == 0) return "no off hand";
                if (item != null && twoHanded(idOf(inv.getActiveHotbarItem()))) return "two handed";
                return fillHand(mob, util, item, true, acc);
            }
            default -> {
                var armor = inv.getArmor();
                short index = (short) (slot - 2);
                if (index >= armor.getCapacity()) return "can't hold it";
                if (item == null) {
                    armor.setItemStackForSlot(index, null);
                    return null;
                }
                var asset = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(item);
                if (asset == null || asset.getArmor() == null || asset.getArmor().getArmorSlot() != ItemArmorSlot.VALUES[index]) {
                    return "wrong slot";
                }
                return InventoryHelper.useArmor(armor, item) ? null : "can't hold it";
            }
        }
    }

    /**
     * One hand: the item goes into EVERY slot of that container that holds something (and slot 0), and slot 0 turns
     * active; null empties them all. Roles switch hotbar/off hand slots themselves (2026-10-16, test equip: a
     * Skeleton_Soldier given a sword was back on its Weapon_Axe_Iron_Rusty slot 6 s later, and it holds a
     * Furniture_Crude_Torch in another off hand slot), so filling only one slot lasted until its next switch.
     */
    private static String fillHand(Ref<EntityStore> mob, com.hypixel.hytale.server.core.inventory.container.ItemContainer box,
                                   String item, boolean offHand, ComponentAccessor<EntityStore> acc) {
        if (box == null || box.getCapacity() == 0) return "can't hold it";
        ItemStack stack = null;
        if (item != null) {
            stack = InventoryHelper.createItem(item);
            if (stack == null) return "can't hold it";
        }
        for (short s = 0; s < box.getCapacity(); s++) {
            if (s == 0 || box.getItemStack(s) != null) box.setItemStackForSlot(s, stack);
        }
        if (offHand) InventoryHelper.setOffHandSlot(mob, (byte) 0, acc);
        else InventoryHelper.setHotbarSlot(mob, (byte) 0, acc);
        return null;
    }

    /** Puts a whole set on a mob: every slot, empty ones emptied. Off hand skipped on mobs without one. */
    /**
     * Puts a whole set on a mob: every slot, empty ones emptied. Off hand skipped on mobs without one. A hand whose
     * item the role's attacks check ({@link #needed}) keeps its item unless the set has one of those. Returns the
     * slots kept that way (none = empty list).
     */
    static List<Integer> apply(Ref<EntityStore> mob, String[] set, ComponentAccessor<EntityStore> acc) {
        boolean off = hasOffHand(mob, acc);
        List<String>[] need = needed(nameOf(mob, acc));
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < SLOTS.length; i++) {
            if (i == 1 && !off) continue;
            // The hand slot went first: a two-handed item there (pasted or kept) leaves the off hand empty.
            if (i == 1 && set[1] != null && twoHanded(read(mob, acc)[0])) continue;
            if (i < 2 && !need[i].isEmpty() && (set[i] == null || !need[i].contains(set[i]))) {
                kept.add(i);
                continue;
            }
            set(mob, i, set[i], acc);
        }
        return kept;
    }

    // ---- weapon power (weapon damage bonus) ----

    /**
     * The breakdown the game computes for a weapon's player attacks (ItemWeapon.getBasicDamageBreakdown: one entry per
     * hit with min/max damage, the item tooltip's numbers), as "label min-max" lines; empty when it has none.
     */
    static List<String> breakdown(String itemId) {
        List<String> out = new ArrayList<>();
        var item = itemId == null ? null : com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(itemId);
        var weapon = item != null ? item.getWeapon() : null;
        var basic = weapon != null ? weapon.getBasicDamageBreakdown() : null;
        if (basic != null && basic.entries() != null) {
            for (var e : basic.entries()) out.add(e.labelKey() + " " + e.min() + "-" + e.max());
        }
        return out;
    }

    /** A bare hand's power: mobs without a role weapon, and items with no basic hit (tools, torches, shields). */
    static final float FIST_POWER = 15f;
    static final float MIN_FACTOR = 0.25f, MAX_FACTOR = 3f;
    private static final Map<String, Float> POWER = new ConcurrentHashMap<>();
    private static final Map<String, String> ROLE_WEAPON = new ConcurrentHashMap<>();

    /**
     * A weapon's power: the average of its basic hit (the first breakdown entry without a label, else the first
     * one), e.g. rusty iron axe 19, adamantite sword 27.5, crude sword 11, Trork stone battleaxe 30.5, adamantite
     * battleaxe 43 (test equip power, 2026-10-16). -1 = no basic hit. Cached per item id.
     */
    static float power(String itemId) {
        if (itemId == null) return -1f;
        return POWER.computeIfAbsent(itemId, id -> {
            var item = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(id);
            var weapon = item != null ? item.getWeapon() : null;
            var basic = weapon != null ? weapon.getBasicDamageBreakdown() : null;
            if (basic == null || basic.entries() == null || basic.entries().isEmpty()) return -1f;
            var pick = basic.entries().get(0);
            for (var e : basic.entries()) {
                if (e.labelKey() == null) {
                    pick = e;
                    break;
                }
            }
            return (pick.min() + pick.max()) / 2f;
        });
    }

    /** Staffs, wands and spellbooks: their basic hit (about 5) says nothing about their spells, so no bonus. */
    private static boolean casterItem(String id) {
        if (id == null) return false;
        String low = id.toLowerCase(java.util.Locale.ROOT);
        return low.contains("staff") || low.contains("wand") || low.contains("spellbook");
    }

    /**
     * equipWeaponDamage: the held weapon's power over the role's own weapon's (its first hotbar item), clamped to
     * MIN_FACTOR..MAX_FACTOR. A missing or hitless item counts as a fist (FIST_POWER); the role's own weapon, or a
     * caster item on either side, gives 1.
     */
    static float weaponFactor(NPCEntity npc) {
        Inventory inv = npc.getInventory();
        if (inv == null) return 1f;
        String held = idOf(inv.getActiveHotbarItem());
        String base = roleWeapon(npc);
        if (java.util.Objects.equals(held, base) || casterItem(held) || casterItem(base)) return 1f;
        float h = power(held), b = power(base);
        if (h <= 0) h = FIST_POWER;
        if (b <= 0) b = FIST_POWER;
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, h / b));
    }

    /** The role's first hotbar item (Role.hotbarItems), cached per role name; null = none. */
    private static String roleWeapon(NPCEntity npc) {
        String role = npc.getRoleName();
        if (role == null) return null;
        String w = ROLE_WEAPON.computeIfAbsent(role, r -> {
            try {
                if (hotbarF == null) {
                    java.lang.reflect.Field h = com.hypixel.hytale.server.npc.role.Role.class.getDeclaredField("hotbarItems");
                    h.setAccessible(true);
                    hotbarF = h;
                }
                String[] items = npc.getRole() != null ? (String[]) hotbarF.get(npc.getRole()) : null;
                return items != null && items.length > 0 && items[0] != null ? items[0] : "";
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "";
            }
        });
        return w.isEmpty() ? null : w;
    }

    // ---- the role's own gear ----

    private static volatile java.lang.reflect.Field hotbarF, offHandF, armorF, defaultOffF;
    private static volatile boolean reflectFailed;

    /**
     * Puts back the gear the mob's role gives it (Role fields hotbarItems, offHandItems, armor, defaultOffHandSlot,
     * read by reflection: no getters): hotbar items from slot 0 (slot 0 active), off hand items from slot 0 (the
     * role's default off hand slot active), armor pieces; everything else emptied. False when the role can't be read.
     */
    static boolean restore(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        NPCEntity npc = acc.getComponent(mob, NPCEntity.getComponentType());
        Inventory inv = npc != null ? npc.getInventory() : null;
        if (inv == null || npc.getRole() == null || reflectFailed) return false;
        String[] hotbar, off, armorIds;
        byte defaultOff;
        try {
            if (offHandF == null) {
                Class<?> c = com.hypixel.hytale.server.npc.role.Role.class;
                java.lang.reflect.Field h = c.getDeclaredField("hotbarItems"), o = c.getDeclaredField("offHandItems"),
                        a = c.getDeclaredField("armor"), d = c.getDeclaredField("defaultOffHandSlot");
                for (var f : new java.lang.reflect.Field[] {h, o, a, d}) f.setAccessible(true);
                offHandF = o;
                armorF = a;
                defaultOffF = d;
                hotbarF = h;
            }
            Object role = npc.getRole();
            hotbar = (String[]) hotbarF.get(role);
            off = (String[]) offHandF.get(role);
            armorIds = (String[]) armorF.get(role);
            defaultOff = defaultOffF.getByte(role);
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectFailed = true;
            MobBattlePlugin.log("Equipment Wand: can't read the role's gear (" + e + ")");
            return false;
        }
        var bar = inv.getHotbar();
        for (short i = 0; i < bar.getCapacity(); i++) {
            String id = hotbar != null && i < hotbar.length ? hotbar[i] : null;
            bar.setItemStackForSlot(i, id != null && !id.isEmpty() ? InventoryHelper.createItem(id) : null);
        }
        InventoryHelper.setHotbarSlot(mob, (byte) 0, acc);
        var util = inv.getUtility();
        if (util != null && util.getCapacity() > 0) {
            for (short i = 0; i < util.getCapacity(); i++) {
                String id = off != null && i < off.length ? off[i] : null;
                util.setItemStackForSlot(i, id != null && !id.isEmpty() ? InventoryHelper.createItem(id) : null);
            }
            if (defaultOff >= 0 && defaultOff < util.getCapacity()) InventoryHelper.setOffHandSlot(mob, defaultOff, acc);
        }
        @SuppressWarnings("removal")
        var armor = inv.getArmor();
        for (short i = 0; i < armor.getCapacity(); i++) armor.setItemStackForSlot(i, null);
        if (armorIds != null) for (String id : armorIds) if (id != null && !id.isEmpty()) InventoryHelper.useArmor(armor, id);
        markEquipment(mob, acc);
        return true;
    }

    // ---- hands a role's AI checks ----

    @SuppressWarnings("unchecked")
    private static final List<String>[] NONE_NEEDED = new List[] {List.of(), List.of()};
    /** Role name -> {main hand items, off hand items} its own sensors check. Cleared by PickLists.rolesChanged. */
    private static final Map<String, List<String>[]> NEEDED = new ConcurrentHashMap<>();

    /**
     * The items a role's own sensors want in each hand: a "Self" sensor with an "ItemInHand" filter listing item ids
     * (2026-10-16, Debug Wand: an equipped Goblin_Guardian chased a skeleton and never attacked; its attacks run only
     * with Weapon_Club_Scrap_Washboard / Weapon_Shield_Scrap_Spiked in hand). Filters on the Target (food a player
     * holds, items a spider fears) and computed lists (goblins' FoodItems) are left out. Every Reference in the role's
     * files is followed (templates and components), mods' roles too. {main, off}; empty = no need.
     */
    @SuppressWarnings("unchecked")
    static List<String>[] needed(String role) {
        if (role == null || role.equals("?")) return NONE_NEEDED;
        return NEEDED.computeIfAbsent(role, r -> {
            List<String> main = new ArrayList<>(), off = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(r));
            while (!todo.isEmpty() && seen.size() < 64) {
                String name = todo.poll();
                if (!seen.add(name)) continue;
                com.google.gson.JsonObject json = PickLists.roleJson(name);
                if (json != null) walk(json, main, off, todo);
            }
            if (main.isEmpty() && off.isEmpty()) return NONE_NEEDED;
            return new List[] {List.copyOf(main), List.copyOf(off)};
        });
    }

    static void rolesChanged() {
        NEEDED.clear();
        ROLE_WEAPON.clear();
        POWER.clear();
    }

    private static void walk(com.google.gson.JsonElement e, List<String> main, List<String> off, java.util.ArrayDeque<String> todo) {
        if (e == null || e.isJsonNull() || e.isJsonPrimitive()) return;
        if (e.isJsonArray()) {
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) walk(x, main, off, todo);
            return;
        }
        com.google.gson.JsonObject o = e.getAsJsonObject();
        for (String key : new String[] {"Reference", "Parent"}) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) todo.add(o.get(key).getAsString());
        }
        if (o.has("Type") && o.get("Type").isJsonPrimitive() && "Self".equals(o.get("Type").getAsString())
                && o.has("Filters") && o.get("Filters").isJsonArray()) {
            for (com.google.gson.JsonElement f : o.getAsJsonArray("Filters")) {
                if (!f.isJsonObject()) continue;
                com.google.gson.JsonObject fo = f.getAsJsonObject();
                if (!fo.has("Type") || !"ItemInHand".equals(fo.get("Type").getAsString())) continue;
                if (!fo.has("Items") || !fo.get("Items").isJsonArray()) continue;
                boolean offHand = fo.has("Hand") && "OffHand".equalsIgnoreCase(fo.get("Hand").getAsString());
                for (com.google.gson.JsonElement id : fo.getAsJsonArray("Items")) {
                    if (!id.isJsonPrimitive()) continue;
                    List<String> to = offHand ? off : main;
                    if (!to.contains(id.getAsString())) to.add(id.getAsString());
                }
            }
        }
        for (var entry : o.entrySet()) walk(entry.getValue(), main, off, todo);
    }

    // ---- what a click does (the interactions and /mobbattle test wand equip* call these) ----

    /** Copies the mob's set for this player. World thread. Returns false when it is not a mob. */
    static boolean copyFrom(PlayerRef player, Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        if (mob == null || !mob.isValid() || inventory(mob, acc) == null) {
            player.sendMessage(I18n.tr("chat.equip.notmob").color("#ff7766"));
            return false;
        }
        String[] set = read(mob, acc);
        copy(player.getUuid(), set);
        player.sendMessage(I18n.tr("chat.equip.copiedfrom").param("name", nameOf(mob, acc)).param("set", describe(set)).color("#55ff77"));
        return true;
    }

    /** The copied set on one mob. */
    static boolean pasteOn(PlayerRef player, Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        String[] set = copied(player.getUuid());
        if (set == null) {
            player.sendMessage(I18n.tr("chat.equip.nothingcopied").color("#ffcc55"));
            return false;
        }
        if (mob == null || !mob.isValid() || inventory(mob, acc) == null) {
            player.sendMessage(I18n.tr("chat.equip.notmob").color("#ff7766"));
            return false;
        }
        List<Integer> kept = apply(mob, set, acc);
        player.sendMessage(I18n.tr("chat.equip.pasted").param("name", nameOf(mob, acc)).color("#55ff77"));
        if (!kept.isEmpty()) player.sendMessage(I18n.tr("chat.equip.kept").param("name", nameOf(mob, acc)).color("#ffcc55"));
        return true;
    }

    /** The copied set on every mob within RADIUS of the player (never players). Returns how many, -1 = none copied. */
    static int pasteArea(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        String[] set = copied(player.getUuid());
        if (set == null) {
            player.sendMessage(I18n.tr("chat.equip.nothingcopied").color("#ffcc55"));
            return -1;
        }
        Vector3d center = ChaosWand.positionOf(me, acc);
        if (center == null) return 0;
        List<Ref<EntityStore>> mobs = new ArrayList<>();
        // Thread-local list: copy it right away.
        for (Ref<EntityStore> r : TargetUtil.getAllEntitiesInSphere(center, RADIUS, acc)) {
            if (r != null && r.isValid() && inventory(r, acc) != null) mobs.add(r);
        }
        int kept = 0;
        for (Ref<EntityStore> r : mobs) if (!apply(r, set, acc).isEmpty()) kept++;
        FightFx.shockwave(center, RADIUS, "#ffd23e", acc);
        player.sendMessage(I18n.tr("chat.equip.area").param("n", mobs.size()).color("#55ff77"));
        if (kept > 0) player.sendMessage(I18n.tr("chat.equip.areakept").param("n", kept).color("#ffcc55"));
        return mobs.size();
    }

    /** Every hotbar and off hand slot with the active ones marked (test equip read). */
    static String dump(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        Inventory inv = inventory(mob, acc);
        if (inv == null) return "no inventory";
        NPCEntity npc = acc.getComponent(mob, NPCEntity.getComponentType());
        return "hotbar " + slots(inv.getHotbar(), inv.getActiveHotbarSlot()) + " | off " + slots(inv.getUtility(), inv.getActiveUtilitySlot())
                + " | role weapon " + (npc != null ? roleWeapon(npc) : null) + ", weapon factor x" + (npc != null ? weaponFactor(npc) : 1f);
    }

    private static String slots(com.hypixel.hytale.server.core.inventory.container.ItemContainer box, int active) {
        if (box == null) return "none";
        StringBuilder b = new StringBuilder("[");
        for (short s = 0; s < box.getCapacity(); s++) {
            if (s > 0) b.append(", ");
            if (s == active) b.append('*');
            String id = idOf(box.getItemStack(s));
            b.append(id == null ? "-" : id);
        }
        return b.append("] active ").append(active).toString();
    }

    static String nameOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        NPCEntity npc = acc.getComponent(mob, NPCEntity.getComponentType());
        return npc != null ? npc.getRoleName() : "?";
    }

    /** "Weapon_Sword_Iron, -, Armor_Iron_Head, ..." in slot order (chat and test lines; ids are not translated). */
    static String describe(String[] set) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < set.length; i++) {
            if (i > 0) b.append(", ");
            b.append(set[i] == null ? "-" : set[i]);
        }
        return b.toString();
    }

    // ---- interactions ----

    /** Right click a mob: its equipment panel; crouching: copy its set. */
    public static class EditInteraction extends SimpleInstantInteraction {
        public static final String TYPE = "MobBattle_EquipEdit";
        public static final BuilderCodec<EditInteraction> CODEC = BuilderCodec
                .builder(EditInteraction.class, EditInteraction::new, SimpleInstantInteraction.CODEC)
                .documentation("Equipment Wand: opens the clicked mob's equipment panel, or copies its set while crouching.")
                .build();

        @Override
        protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
            if (!Perms.interactionAllowed(context)) return;
            CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
            if (buffer == null) return;
            Ref<EntityStore> me = context.getEntity();
            PlayerRef player = buffer.getComponent(me, PlayerRef.getComponentType());
            if (player == null) return;
            Ref<EntityStore> target = context.getTargetEntity();
            if (PlayerFighters.crouching(me, buffer)) {
                copyFrom(player, target, buffer);
                return;
            }
            if (target == null || !target.isValid() || inventory(target, buffer) == null) {
                player.sendMessage(I18n.tr("chat.equip.notmob").color("#ff7766"));
                return;
            }
            // Pages are opened outside the system iteration, as the panel command does.
            Store<EntityStore> store = me.getStore();
            store.getExternalData().getWorld().execute(() -> EquipPage.open(player, me, target, store));
        }
    }

    /** Left click a mob: the copied set. */
    public static class PasteInteraction extends SimpleInstantInteraction {
        public static final String TYPE = "MobBattle_EquipPaste";
        public static final BuilderCodec<PasteInteraction> CODEC = BuilderCodec
                .builder(PasteInteraction.class, PasteInteraction::new, SimpleInstantInteraction.CODEC)
                .documentation("Equipment Wand: puts the copied set on the clicked mob.")
                .build();

        @Override
        protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
            if (!Perms.interactionAllowed(context)) return;
            CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
            if (buffer == null) return;
            PlayerRef player = buffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
            if (player != null) pasteOn(player, context.getTargetEntity(), buffer);
        }
    }

    /** Held left: the copied set on every mob in RADIUS (never players). */
    public static class AreaInteraction extends SimpleInstantInteraction {
        public static final String TYPE = "MobBattle_EquipArea";
        public static final BuilderCodec<AreaInteraction> CODEC = BuilderCodec
                .builder(AreaInteraction.class, AreaInteraction::new, SimpleInstantInteraction.CODEC)
                .documentation("Equipment Wand: puts the copied set on every mob around.")
                .build();

        @Override
        protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
            if (!Perms.interactionAllowed(context)) return;
            CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
            if (buffer == null) return;
            Ref<EntityStore> me = context.getEntity();
            PlayerRef player = buffer.getComponent(me, PlayerRef.getComponentType());
            if (player != null) pasteArea(player, me, buffer);
        }
    }

    // ---- item catalog (panel lists and filters) ----

    /** An item the panel can list: id, mod (null = the game), type = first folder under Item/Items, group = second. */
    record ItemInfo(String id, String mod, String type, String group, int armorSlot) {
    }

    private static volatile List<ItemInfo> catalog = List.of();
    private static volatile int catalogSize = -1;

    /** Every item, cached until the item count changes. Folders starting with "_", EditorTool and VFX are left out. */
    static List<ItemInfo> catalog() {
        var map = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap();
        int size = map.getAssetMap().size();
        if (size == catalogSize) return catalog;
        List<ItemInfo> out = new ArrayList<>();
        for (var e : map.getAssetMap().entrySet()) {
            var item = e.getValue();
            if (item == null || item == com.hypixel.hytale.server.core.asset.type.item.config.Item.UNKNOWN) continue;
            String id = e.getKey();
            String type = null, group = null, mod = null;
            try {
                java.nio.file.Path path = map.getPath(id);
                if (path != null) {
                    List<String> parts = new ArrayList<>();
                    for (java.nio.file.Path p : path) parts.add(p.toString());
                    int at = parts.lastIndexOf("Items");
                    if (at >= 0 && at + 2 < parts.size()) type = parts.get(at + 1);
                    if (at >= 0 && at + 3 < parts.size()) group = parts.get(at + 2);
                }
                String pack = map.getAssetPack(id);
                if (pack != null && !pack.isBlank() && !pack.startsWith("Hytale")) {
                    int colon = pack.indexOf(':');
                    mod = colon >= 0 ? pack.substring(colon + 1) : pack;
                }
            } catch (RuntimeException ignored) {
            }
            if (type == null) type = "Other";
            // "*Id" are generated state variants (filled buckets...), not items of their own; debug and editor items
            // are dev tools (test equip lists, 2026-10-16).
            if (id.startsWith("*") || id.startsWith("Debug_") || id.startsWith("Template_") || type.startsWith("_") || group != null && group.startsWith("_")
                    || type.equals("EditorTool") || type.equals("Editor") || type.equals("Categories") || type.equals("VFX")) continue;
            int armor = -1;
            if (item.getArmor() != null && item.getArmor().getArmorSlot() != null) armor = item.getArmor().getArmorSlot().ordinal();
            if (armor < 0 && !battleItem(id, mod, type)) continue;
            out.add(new ItemInfo(id, mod, type, group, armor));
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.id(), b.id()));
        catalog = List.copyOf(out);
        catalogSize = size;
        return catalog;
    }

    /** The game's item folders that make sense in a mob's hands in a battle (2026-10-16, user: no blocks or decor). */
    private static final java.util.Set<String> BATTLE_TYPES = java.util.Set.of(
            "Weapon", "Tool", "Potion", "Food", "Fish", "Rune", "MISC", "Utility", "Gadget", "Glider", "Hypixel");

    /**
     * A hand item worth listing: the game's own only from {@link #BATTLE_TYPES} plus torches and lanterns (potions,
     * food and torches are placeable blocks too, so "not a block" alone would drop them); a mod's item when it is not
     * a block (mods put their items in any folder, BattleGauntlets in none).
     */
    private static boolean battleItem(String id, String mod, String type) {
        if (mod == null) {
            String low = id.toLowerCase(java.util.Locale.ROOT);
            // Hand-held lights: standing torches and lanterns, not ceiling/wall ones or the lantern spawner egg.
            if ((low.contains("torch") || low.contains("lantern")) && (low.startsWith("furniture_") || low.startsWith("deco_lantern"))
                    && !low.contains("ceiling") && !low.contains("wall")) return true;
            if (!BATTLE_TYPES.contains(type)) return false;
            // Potions and food are placeable blocks too; any other block in these folders (Hypixel/Barrier,
            // MISC/Block_Spawner_Block) is a dev or building block (test equip lists, 2026-10-16).
            return type.equals("Potion") || type.equals("Food") || !isBlock(id);
        }
        return !isBlock(id);
    }

    private static boolean isBlock(String id) {
        try {
            return InventoryHelper.itemKeyIsBlockType(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "" = every mod, "hytale" = the game's own, else that mod's name (case-insensitive). */
    static boolean modMatches(ItemInfo i, String mod) {
        if (mod == null || mod.isEmpty()) return true;
        if (mod.equals("hytale")) return i.mod() == null;
        return mod.equalsIgnoreCase(i.mod());
    }

    /** Items a slot lists with these filters (hands: any non-armor item of the type/group; armor: that slot only). */
    static List<ItemInfo> listFor(int slot, String mod, String type, String group) {
        List<ItemInfo> out = new ArrayList<>();
        for (ItemInfo i : catalog()) {
            if (!modMatches(i, mod)) continue;
            if (slot >= 2) {
                if (i.armorSlot() == slot - 2) out.add(i);
                continue;
            }
            if (i.armorSlot() >= 0) continue;
            if (type != null && !type.isEmpty() && !type.equals(i.type())) continue;
            if (group != null && !group.isEmpty() && !group.equals(i.group())) continue;
            out.add(i);
        }
        return out;
    }

    /** Types (and with type set, groups) present among the hand items of a mod, sorted. */
    static List<String> typesFor(String mod) {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ItemInfo i : catalog()) if (i.armorSlot() < 0 && modMatches(i, mod)) s.add(i.type());
        return new ArrayList<>(s);
    }

    static List<String> groupsFor(String mod, String type) {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ItemInfo i : catalog()) {
            if (i.armorSlot() < 0 && i.group() != null && modMatches(i, mod) && (type == null || type.isEmpty() || type.equals(i.type()))) s.add(i.group());
        }
        return new ArrayList<>(s);
    }

    static List<String> mods() {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ItemInfo i : catalog()) if (i.mod() != null) s.add(i.mod());
        return new ArrayList<>(s);
    }
}
