package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Equipment kits (2026-10-16): named sets for the Equipment Wand's six slots, used by the summon list, armies and
 * battle teams. Each slot holds a list of options and one is picked at random per mob (playtest: random items); an empty
 * list leaves that slot as the mob has it, {@link #EMPTY} empties it. Saved in fx.properties as
 * {@code kit.<name> = hand|hand2;offhand;head;chest;hands;legs}; they are list keys (no preset or reset touches them).
 *
 * <p>Combat profiles (2026-10-29): a kit can also hold runes ({@code kit.<name>.runes = Rune_Fireball,Rune_Enrage}, cast
 * in fights by {@link RuneCaster}) and stats ({@code kit.<name>.stats = health:500,damage:30,defense:20,speed:10,size:50};
 * health in hit points, the rest in percent), put on as direct buffs (Buffs.setDirect, so Cleanse/unbuff take them
 * back). Kits without them read as before. A few default kits are added once ({@link #DEFAULTS}, never over a kit of
 * the same name; {@code kit.defaults.seeded} remembers which, so a deleted default stays deleted). Training dummies
 * (MobBattle_Dummy_*) keep their hands: their combat config is written for the weapon they spawn with.
 */
final class Kits {

    static final String PREFIX = "kit.";
    /** A slot option that empties the slot. */
    static final String EMPTY = "-";
    static final int MAX_KITS = 200;
    static final String RUNES_SUFFIX = ".runes", STATS_SUFFIX = ".stats";
    /** Which default kits were added already (a deleted one is not added again). */
    static final String SEEDED_KEY = PREFIX + "defaults.seeded";
    /** Stats a kit can hold, in the order they are written and shown. */
    static final List<Buffs.Stat> STAT_ORDER = List.of(Buffs.Stat.HEALTH, Buffs.Stat.DAMAGE, Buffs.Stat.DEFENSE,
            Buffs.Stat.SPEED, Buffs.Stat.SIZE);
    /** Highest health a kit can give, in hit points. */
    static final int MAX_HEALTH = 100000;

    /**
     * A kit: its name, per slot (EquipWand.SLOTS order) the options (empty = untouched), its runes (item ids, empty =
     * the mob's runes untouched) and its stats (health in hit points, the rest in percent; missing = untouched).
     */
    record Kit(String name, List<List<String>> slots, List<String> runes, Map<Buffs.Stat, Integer> stats) {
        Kit(String name, List<List<String>> slots) {
            this(name, slots, List.of(), Map.of());
        }

        /** "Weapon_Sword_Iron|Weapon_Axe_Iron;-;Armor_Iron_Head;;;" */
        String encode() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < slots.size(); i++) {
                if (i > 0) b.append(';');
                b.append(String.join("|", slots.get(i)));
            }
            return b.toString();
        }

        /** "health:500,defense:20" (stat order), "" when none. */
        String encodeStats() {
            List<String> parts = new ArrayList<>();
            for (Buffs.Stat st : STAT_ORDER) {
                Integer v = stats.get(st);
                if (v != null) parts.add(st.key + ":" + v);
            }
            return String.join(",", parts);
        }

        /** "hand Sword_Iron or Axe_Iron, offhand empty, head Iron_Head, runes ..., health 500 hp" for chat (ids). */
        String describe() {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                List<String> opts = slots.get(i);
                if (opts.isEmpty()) continue;
                parts.add(EquipWand.SLOTS[i] + " " + (opts.equals(List.of(EMPTY)) ? "empty" : String.join(" or ", opts)));
            }
            if (!runes.isEmpty()) parts.add("runes " + String.join(", ", runes));
            for (Buffs.Stat st : STAT_ORDER) {
                Integer v = stats.get(st);
                if (v != null) parts.add(st == Buffs.Stat.HEALTH ? "health " + v + " hp" : st.key + " +" + v + "%");
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

    /** Highest value of a stat in a kit (health in hit points, the rest in percent as Buffs caps them). */
    static int statMax(Buffs.Stat st) {
        return st == Buffs.Stat.HEALTH ? MAX_HEALTH : st.max;
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
        return new Kit(kit.name(), List.copyOf(slots), kit.runes(), kit.stats());
    }

    /** The runes replaced (empty = the kit leaves runes alone). */
    static Kit withRunes(Kit kit, List<String> runes) {
        return new Kit(kit.name(), kit.slots(), List.copyOf(runes), kit.stats());
    }

    /** One stat set (value null = removed from the kit). */
    static Kit withStat(Kit kit, Buffs.Stat st, Integer value) {
        EnumMap<Buffs.Stat, Integer> stats = new EnumMap<>(Buffs.Stat.class);
        stats.putAll(kit.stats());
        if (value == null) stats.remove(st);
        else stats.put(st, value);
        return new Kit(kit.name(), kit.slots(), kit.runes(), Collections.unmodifiableMap(stats));
    }

    /** The kit with these runes and stats (the panel's Save as kit, loading). */
    static Kit withExtras(Kit kit, List<String> runes, Map<Buffs.Stat, Integer> stats) {
        EnumMap<Buffs.Stat, Integer> copy = new EnumMap<>(Buffs.Stat.class);
        copy.putAll(stats);
        return new Kit(kit.name(), kit.slots(), List.copyOf(runes), Collections.unmodifiableMap(copy));
    }

    /** A new empty kit (every slot untouched). */
    static Kit blank(String name) {
        List<List<String>> slots = new ArrayList<>();
        for (int i = 0; i < EquipWand.SLOTS.length; i++) slots.add(List.of());
        return new Kit(name, List.copyOf(slots));
    }

    /**
     * "Rune_Fireball,enrage" -> exact rune ids (at most RuneCaster.MAX_RUNES); null when one is not a rune
     * ({@code bad[0]} says which). "-" or "none" = no runes.
     */
    static List<String> parseRunes(String value, String[] bad) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank() || value.trim().equals(EMPTY) || value.trim().equalsIgnoreCase("none")) return out;
        List<String> cores = new ArrayList<>();
        // "Rune_Fireball+Rune_Damage_Up,Rune_Enrage": a main rune and its supports (RuneCaster.normalize)
        for (String w : value.split("[,|]")) {
            if (w.isBlank()) continue;
            String entry = RuneCaster.normalize(w.trim(), bad);
            if (entry == null) return null;
            String core = RuneCaster.coreOf(entry);
            if (!cores.contains(core) && out.size() < RuneCaster.MAX_RUNES) {
                out.add(entry);
                cores.add(core);
            }
        }
        return out;
    }

    /** "health:500,damage:30" -> stats (unknown names, bad numbers and 0 left out, values clamped). */
    static Map<Buffs.Stat, Integer> parseStats(String value) {
        EnumMap<Buffs.Stat, Integer> out = new EnumMap<>(Buffs.Stat.class);
        if (value == null) return out;
        for (String part : value.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            Buffs.Stat st = Buffs.Stat.of(kv[0].trim());
            if (st == null) continue;
            try {
                int v = Integer.parseInt(kv[1].trim());
                if (v > 0) out.put(st, Math.min(statMax(st), v));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** What a kit did on one mob: {@code handsKept} = a training dummy kept its own hands. */
    record Applied(boolean handsKept) {
    }

    /**
     * Puts a kit on a mob: per slot one option at random, untouched slots left alone. Hands its role's attacks need
     * keep their item (as the wand's paste), the off hand is skipped on mobs without one or behind a two-handed item,
     * and a training dummy keeps both hands. Then its runes (RuneCaster) and stats (direct buffs). Returns null when it
     * is not a mob. World thread.
     */
    static Applied apply(Ref<EntityStore> mob, Kit kit, ComponentAccessor<EntityStore> acc) {
        if (kit == null || mob == null || !mob.isValid() || EquipWand.inventory(mob, acc) == null) return null;
        String role = EquipWand.nameOf(mob, acc);
        List<String>[] need = EquipWand.needed(role);
        boolean fixed = EquipWand.fixedHands(role);
        boolean kept = false;
        boolean off = EquipWand.hasOffHand(mob, acc);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < EquipWand.SLOTS.length; i++) {
            List<String> opts = i < kit.slots().size() ? kit.slots().get(i) : List.of();
            if (opts.isEmpty()) continue;
            if (i < 2 && fixed) {
                kept = true;
                continue;
            }
            String pick = opts.get(rnd.nextInt(opts.size()));
            String item = pick.equals(EMPTY) ? null : pick;
            if (i == 1 && (!off || item != null && EquipWand.twoHanded(EquipWand.read(mob, acc)[0]))) continue;
            if (i < 2 && !need[i].isEmpty() && (item == null || !need[i].contains(item))) continue;
            EquipWand.set(mob, i, item, acc);
        }
        if (!kit.runes().isEmpty()) RuneCaster.set(mob, kit.runes());
        applyStats(mob, kit.stats(), acc);
        if (kept && MobBattlePlugin.debugging()) MobBattlePlugin.debug("kit " + kit.name() + " on " + role + ": hands kept (training dummy)");
        return new Applied(kept);
    }

    /**
     * The kit's stats as direct buffs (as TrainingDummies does): health in hit points over the mob's own max health
     * (its max without our buff modifier; at or under it = no health buff), the rest in percent.
     */
    static void applyStats(Ref<EntityStore> mob, Map<Buffs.Stat, Integer> stats, ComponentAccessor<EntityStore> acc) {
        for (Map.Entry<Buffs.Stat, Integer> e : stats.entrySet()) {
            int v = e.getValue();
            if (e.getKey() == Buffs.Stat.HEALTH) {
                float base = Buffs.baseMaxHealth(mob, acc);
                if (base <= 0) continue;
                v = Math.round((v / base - 1f) * 100f);
                if (v <= 0) continue;
            }
            Buffs.setDirect(mob, e.getKey(), v);
        }
    }

    /** "Kits: a, b" (or none yet) for usage notes and kit list. */
    static com.hypixel.hytale.server.core.Message listMessage() {
        List<String> names = names();
        return names.isEmpty() ? I18n.tr("cmd.kit.none") : I18n.tr("cmd.kit.list").param("kits", String.join(", ", names));
    }

    // ---- team kits in battles ----

    /** Team battle members that got their team's kit -> what they wore before (restored when the battle ends). */
    private static final Map<Ref<EntityStore>, String[]> ORIGINAL = new java.util.concurrent.ConcurrentHashMap<>();
    /** Team battle members -> the kit whose runes and stats they got, and what they had before (put back at the end). */
    private static final Map<Ref<EntityStore>, Before> EXTRAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The runes and direct stat amounts a mob had before its team kit (audit 2026-10-29: the end zeroed them all). */
    private record Before(Kit kit, List<String> runes, Map<Buffs.Stat, Integer> direct) {
    }

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
            if (!kit.runes().isEmpty() || !kit.stats().isEmpty()) {
                Map<Buffs.Stat, Integer> had = new EnumMap<>(Buffs.Stat.class);
                for (Buffs.Stat st : kit.stats().keySet()) had.put(st, Buffs.directOf(npc, st));
                EXTRAS.putIfAbsent(npc, new Before(kit, List.copyOf(RuneCaster.runesOf(npc)), had));
            }
            apply(npc, kit, acc);
        }
    }

    /**
     * A team battle ended: the survivors get back what they wore, and lose the kit's runes and stats. Any thread; the
     * gear goes back on the battle's world thread (stopAll can run from a command thread).
     */
    static void teamBattleEnd(List<Ref<EntityStore>> mobs, com.hypixel.hytale.component.Store<EntityStore> store) {
        if (ORIGINAL.isEmpty() && EXTRAS.isEmpty() || store == null || store.isShutdown()) return;
        Map<Ref<EntityStore>, String[]> back = new LinkedHashMap<>();
        Map<Ref<EntityStore>, Before> extras = new LinkedHashMap<>();
        for (Ref<EntityStore> r : mobs) {
            if (r == null) continue;
            String[] set = ORIGINAL.remove(r);
            if (set != null && r.isValid()) back.put(r, set);
            Before k = EXTRAS.remove(r);
            if (k != null && r.isValid()) extras.put(r, k);
        }
        if (back.isEmpty() && extras.isEmpty()) return;
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown()) return;
            back.forEach((r, set) -> {
                if (r.isValid()) EquipWand.apply(r, set, store);
            });
            // The kit's runes and stats go, and what the mob had before them comes back (dummyHealth, a summon kit).
            extras.forEach((r, b) -> {
                if (!r.isValid()) return;
                if (!b.kit().runes().isEmpty()) {
                    if (b.runes().isEmpty()) RuneCaster.remove(r);
                    else RuneCaster.set(r, b.runes());
                }
                for (Buffs.Stat st : b.kit().stats().keySet()) Buffs.setDirect(r, st, b.direct().getOrDefault(st, 0));
            });
        });
    }

    /** Drops gone mobs (BattleManager.maintain, once a second). */
    static void prune() {
        if (!ORIGINAL.isEmpty()) ORIGINAL.keySet().removeIf(r -> !r.isValid());
        if (!EXTRAS.isEmpty()) EXTRAS.keySet().removeIf(r -> !r.isValid());
    }

    // ---- saving ----

    /**
     * Kits skipped at load (an item not loaded: a removed mod, or the items before BootEvent), kept as their raw lines
     * so a save writes them back unchanged (audit 2026-10-29: the next save erased them for good, and a save before the
     * boot reload erased every kit). Name (lower case) -> {slots, runes, stats}, nulls for missing lines.
     */
    private static final Map<String, String[]> SKIPPED = new java.util.concurrent.ConcurrentHashMap<>();

    static void load(Properties p) {
        synchronized (KITS) {
            KITS.clear();
            SKIPPED.clear();
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith(PREFIX)) continue;
                String name = key.substring(PREFIX.length());
                // kit.<name>.runes, kit.<name>.stats and kit.defaults.seeded are read with their kit (names have no dot).
                if (!validName(name)) continue;
                // Items of a mod that is not loaded now keep the kit out (logged), rather than silently dropping slots.
                String[] bad = new String[1];
                Kit kit = decode(name, p.getProperty(key), bad);
                if (kit == null) {
                    MobBattlePlugin.log("Kit " + name + " skipped: unknown item " + bad[0]);
                    SKIPPED.put(name.toLowerCase(Locale.ROOT), new String[] {p.getProperty(key),
                            p.getProperty(key + RUNES_SUFFIX), p.getProperty(key + STATS_SUFFIX), name});
                    continue;
                }
                kit = withExtras(kit, readRunes(p.getProperty(key + RUNES_SUFFIX)), parseStats(p.getProperty(key + STATS_SUFFIX)));
                KITS.put(name.toLowerCase(Locale.ROOT), kit);
            }
            seedDefaults(p.getProperty(SEEDED_KEY, ""));
        }
    }

    /**
     * Saved runes kept as written: a rune of a mod that is not loaded now stays in the kit (RuneCaster skips it), so a
     * save never drops it. Known ones get their exact id.
     */
    private static List<String> readRunes(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String w : value.split(",")) {
            if (w.isBlank()) continue;
            String id = RuneCaster.normalize(w.trim(), null);
            String r = id != null ? id : w.trim();
            if (!out.contains(r) && out.size() < RuneCaster.MAX_RUNES) out.add(r);
        }
        return out;
    }

    static void save(Properties p) {
        SKIPPED.forEach((lower, raw) -> {
            if (get(lower) != null) return;
            p.setProperty(PREFIX + raw[3], raw[0]);
            if (raw[1] != null) p.setProperty(PREFIX + raw[3] + RUNES_SUFFIX, raw[1]);
            if (raw[2] != null) p.setProperty(PREFIX + raw[3] + STATS_SUFFIX, raw[2]);
        });
        for (Kit k : all()) {
            p.setProperty(PREFIX + k.name(), k.encode());
            if (!k.runes().isEmpty()) p.setProperty(PREFIX + k.name() + RUNES_SUFFIX, String.join(",", k.runes()));
            if (!k.stats().isEmpty()) p.setProperty(PREFIX + k.name() + STATS_SUFFIX, k.encodeStats());
        }
        p.setProperty(SEEDED_KEY, String.join(",", SEEDED));
    }

    // ---- default kits ----

    /**
     * Default combat profiles, added once so the summon list's kit dropdown is not empty on a fresh install. Hands are
     * left alone (any mob keeps attacking with its own weapon); health in hit points, the rest in percent.
     * {name, slots (encode format), runes, stats}.
     */
    static final String[][] DEFAULTS = {
            {"tank", ";;Armor_Iron_Head;Armor_Iron_Chest;Armor_Iron_Hands;Armor_Iron_Legs", "Rune_Enrage", "health:300,defense:30"},
            {"berserker", ";;Armor_Leather_Light_Head;Armor_Leather_Light_Chest;Armor_Leather_Light_Hands;Armor_Leather_Light_Legs",
                    "Rune_Enrage,Rune_GroundSlam", "damage:40,speed:20"},
            {"mage", ";;-;-;-;-", "Rune_Fireball,Rune_WindStrike", ""},
            {"rogue", ";;Armor_Leather_Soft_Head;Armor_Leather_Soft_Chest;Armor_Leather_Soft_Hands;Armor_Leather_Soft_Legs",
                    "Rune_PoisonImbue", "damage:15,speed:30"},
    };

    /** Default kits added so far (lower case), as saved under SEEDED_KEY. */
    private static final List<String> SEEDED = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Adds every default not added before and not already a kit of that name. Inside load (KITS locked). */
    private static void seedDefaults(String already) {
        SEEDED.clear();
        for (String w : already.split(",")) {
            String n = w.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty() && !SEEDED.contains(n)) SEEDED.add(n);
        }
        for (String[] d : DEFAULTS) {
            String key = d[0].toLowerCase(Locale.ROOT);
            if (SEEDED.contains(key)) continue;
            if (KITS.containsKey(key)) {
                SEEDED.add(key);
                continue;
            }
            String[] bad = new String[1];
            Kit kit = decode(d[0], d[1], bad);
            if (kit == null) {
                // Items not loaded (yet): tried again at the next load, not marked.
                MobBattlePlugin.log("Default kit " + d[0] + " not added: unknown item " + bad[0]);
                continue;
            }
            SEEDED.add(key);
            KITS.put(key, withExtras(kit, readRunes(d[2]), parseStats(d[3])));
        }
    }
}
