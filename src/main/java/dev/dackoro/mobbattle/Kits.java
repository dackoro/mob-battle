package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Equipment kits (2026-10-16, user): named sets for the Equipment Wand's six slots, used by the summon list, armies and
 * battle teams. Each slot holds a list of options and one is picked at random per mob (user: random items); an empty
 * list leaves that slot as the mob has it, {@link #EMPTY} empties it. Saved in fx.properties as
 * {@code kit.<name> = hand|hand2;offhand;head;chest;hands;legs}; they are list keys (no preset or reset touches them).
 */
final class Kits {

    static final String PREFIX = "kit.";
    /** A slot option that empties the slot. */
    static final String EMPTY = "-";
    static final int MAX_KITS = 200;

    /** A kit: its name and, per slot (EquipWand.SLOTS order), the options (empty = untouched). */
    record Kit(String name, List<List<String>> slots) {
        /** "Weapon_Sword_Iron|Weapon_Axe_Iron;-;Armor_Iron_Head;;;" */
        String encode() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < slots.size(); i++) {
                if (i > 0) b.append(';');
                b.append(String.join("|", slots.get(i)));
            }
            return b.toString();
        }

        /** "hand Sword_Iron or Axe_Iron, offhand empty, head Iron_Head" for chat (ids, not translated). */
        String describe() {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                List<String> opts = slots.get(i);
                if (opts.isEmpty()) continue;
                parts.add(EquipWand.SLOTS[i] + " " + (opts.equals(List.of(EMPTY)) ? "empty" : String.join(" or ", opts)));
            }
            return parts.isEmpty() ? "(nothing)" : String.join(", ", parts);
        }
    }

    /** Lower-case name -> kit, in creation order. */
    private static final Map<String, Kit> KITS = Collections.synchronizedMap(new LinkedHashMap<>());

    private Kits() {
    }

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,24}");
    }

    static Kit get(String name) {
        return name == null ? null : KITS.get(name.toLowerCase(Locale.ROOT));
    }

    static List<Kit> all() {
        synchronized (KITS) {
            return new ArrayList<>(KITS.values());
        }
    }

    static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Kit k : all()) out.add(k.name());
        return out;
    }

    /** Adds or replaces a kit. False when the name is bad or the kit count is at MAX_KITS. */
    static boolean put(Kit kit) {
        if (kit == null || !validName(kit.name())) return false;
        String key = kit.name().toLowerCase(Locale.ROOT);
        synchronized (KITS) {
            if (!KITS.containsKey(key) && KITS.size() >= MAX_KITS) return false;
            KITS.put(key, kit);
        }
        return true;
    }

    static boolean remove(String name) {
        return name != null && KITS.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    /** A kit with exactly what a mob wears now (each slot one option; an empty slot is emptied). */
    static Kit fromSet(String name, String[] set) {
        List<List<String>> slots = new ArrayList<>();
        for (String s : set) slots.add(List.of(s == null ? EMPTY : s));
        return new Kit(name, slots);
    }

    /** Parses "a|b;c;;-;;" (missing slots untouched). Unknown item ids make it null; why goes to {@code bad[0]}. */
    static Kit decode(String name, String value, String[] bad) {
        List<List<String>> slots = new ArrayList<>();
        String[] parts = value == null ? new String[0] : value.split(";", -1);
        for (int i = 0; i < EquipWand.SLOTS.length; i++) {
            List<String> opts = new ArrayList<>();
            if (i < parts.length) {
                for (String o : parts[i].split("\\|")) {
                    o = o.trim();
                    if (o.isEmpty()) continue;
                    if (o.equals(EMPTY)) {
                        opts.add(EMPTY);
                        continue;
                    }
                    String id = ApplyCommand.findItem(o);
                    if (id == null) {
                        if (bad != null) bad[0] = o;
                        return null;
                    }
                    opts.add(id);
                }
            }
            slots.add(List.copyOf(opts));
        }
        return new Kit(name, List.copyOf(slots));
    }

    /** One slot's options replaced (slot index in EquipWand.SLOTS). */
    static Kit withSlot(Kit kit, int slot, List<String> options) {
        List<List<String>> slots = new ArrayList<>(kit.slots());
        slots.set(slot, List.copyOf(options));
        return new Kit(kit.name(), List.copyOf(slots));
    }

    /** A new empty kit (every slot untouched). */
    static Kit blank(String name) {
        List<List<String>> slots = new ArrayList<>();
        for (int i = 0; i < EquipWand.SLOTS.length; i++) slots.add(List.of());
        return new Kit(name, List.copyOf(slots));
    }

    /**
     * Puts a kit on a mob: per slot one option at random, untouched slots left alone. Hands its role's attacks need
     * keep their item (as the wand's paste), the off hand is skipped on mobs without one or behind a two-handed item.
     * Returns false when it is not a mob. World thread.
     */
    static boolean apply(Ref<EntityStore> mob, Kit kit, ComponentAccessor<EntityStore> acc) {
        if (kit == null || mob == null || !mob.isValid() || EquipWand.inventory(mob, acc) == null) return false;
        List<String>[] need = EquipWand.needed(EquipWand.nameOf(mob, acc));
        boolean off = EquipWand.hasOffHand(mob, acc);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < EquipWand.SLOTS.length; i++) {
            List<String> opts = i < kit.slots().size() ? kit.slots().get(i) : List.of();
            if (opts.isEmpty()) continue;
            String pick = opts.get(rnd.nextInt(opts.size()));
            String item = pick.equals(EMPTY) ? null : pick;
            if (i == 1 && (!off || item != null && EquipWand.twoHanded(EquipWand.read(mob, acc)[0]))) continue;
            if (i < 2 && !need[i].isEmpty() && (item == null || !need[i].contains(item))) continue;
            EquipWand.set(mob, i, item, acc);
        }
        return true;
    }

    /** "Kits: a, b" (or none yet) for usage notes and kit list. */
    static com.hypixel.hytale.server.core.Message listMessage() {
        List<String> names = names();
        return names.isEmpty() ? I18n.tr("cmd.kit.none") : I18n.tr("cmd.kit.list").param("kits", String.join(", ", names));
    }

    // ---- team kits in battles ----

    /** Team battle members that got their team's kit -> what they wore before (restored when the battle ends). */
    private static final Map<Ref<EntityStore>, String[]> ORIGINAL = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A team battle started: each mob (not players) whose team has a kit (Teams.kit) gets it, after its set is kept.
     * World thread (TeamActionInteraction.startNow, inside the countdown's buffer).
     */
    static void teamBattleStart(BattleManager.Battle battle, ComponentAccessor<EntityStore> acc) {
        for (Ref<EntityStore> npc : battle.members()) {
            if (npc == null || !npc.isValid() || BattleManager.isPlayerFighter(npc)) continue;
            Kit kit = get(Teams.kit(battle.team(npc)));
            if (kit == null || EquipWand.inventory(npc, acc) == null) continue;
            ORIGINAL.putIfAbsent(npc, EquipWand.read(npc, acc));
            apply(npc, kit, acc);
        }
    }

    /**
     * A team battle ended: the survivors get back what they wore. Any thread; the gear goes back on the battle's
     * world thread (stopAll can run from a command thread).
     */
    static void teamBattleEnd(List<Ref<EntityStore>> mobs, com.hypixel.hytale.component.Store<EntityStore> store) {
        if (ORIGINAL.isEmpty() || store == null || store.isShutdown()) return;
        Map<Ref<EntityStore>, String[]> back = new LinkedHashMap<>();
        for (Ref<EntityStore> r : mobs) {
            String[] set = r == null ? null : ORIGINAL.remove(r);
            if (set != null && r.isValid()) back.put(r, set);
        }
        if (back.isEmpty()) return;
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown()) return;
            back.forEach((r, set) -> {
                if (r.isValid()) EquipWand.apply(r, set, store);
            });
        });
    }

    /** Drops gone mobs (BattleManager.maintain, once a second). */
    static void prune() {
        if (!ORIGINAL.isEmpty()) ORIGINAL.keySet().removeIf(r -> !r.isValid());
    }

    // ---- saving ----

    static void load(Properties p) {
        synchronized (KITS) {
            KITS.clear();
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith(PREFIX)) continue;
                String name = key.substring(PREFIX.length());
                if (!validName(name)) continue;
                // Items of a mod that is not loaded now keep the kit out (logged), rather than silently dropping slots.
                String[] bad = new String[1];
                Kit kit = decode(name, p.getProperty(key), bad);
                if (kit == null) {
                    MobBattlePlugin.log("Kit " + name + " skipped: unknown item " + bad[0]);
                    continue;
                }
                KITS.put(name.toLowerCase(Locale.ROOT), kit);
            }
        }
    }

    static void save(Properties p) {
        for (Kit k : all()) p.setProperty(PREFIX + k.name(), k.encode());
    }
}
