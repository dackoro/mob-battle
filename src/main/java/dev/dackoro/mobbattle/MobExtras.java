package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mob's runes (RuneCaster) and kit stats (Buffs direct amounts) across unloads, reconnects, restarts and crashed
 * worlds (2026-10-29, playtest: they were lost on leaving and joining again; both live in memory keyed by Ref, while the
 * gear is saved with the mob by the game). Kept per mob UUID in {@code <data dir>/mob-extras.txt}, one line per mob:
 * {@code uuid<TAB>Core+Sup,Core|-<TAB>health:500,damage:30|-}. A pass per world on its thread every PASS_MS records
 * changes (and drops mobs that died or lost both); a mob that loads again gets them back.
 */
final class MobExtras {

    static final long PASS_MS = 2000;
    static final String FILE = "mob-extras.txt";

    private record Saved(List<String> runes, Map<Buffs.Stat, Integer> direct) {
        boolean empty() {
            return runes.isEmpty() && direct.isEmpty();
        }
    }

    private static final Map<UUID, Saved> SAVED = new ConcurrentHashMap<>();
    /** Mobs seen with runes or stats: Ref -> its UUID (an invalid Ref still here died or was removed). */
    private static final Map<Ref<EntityStore>, UUID> IDS = new ConcurrentHashMap<>();
    private static volatile boolean dirty;

    private MobExtras() {
    }

    static boolean any() {
        return !IDS.isEmpty() || dirty || !Buffs.withState().isEmpty();
    }

    /** Plugin start: what was saved last time waits for its mobs to load. */
    static void loadSaved(Path dataDir) {
        if (dataDir == null) return;
        Path file = dataDir.resolve(FILE);
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] w = line.split("\t");
                if (w.length < 3) continue;
                try {
                    List<String> runes = "-".equals(w[1]) ? List.of() : List.of(w[1].split(","));
                    Saved s = new Saved(runes, "-".equals(w[2]) ? Map.of() : parseDirect(w[2]));
                    if (!s.empty()) SAVED.put(UUID.fromString(w[0]), s);
                } catch (RuntimeException ignored) {
                    // a broken line: skipped
                }
            }
            if (!SAVED.isEmpty()) MobBattlePlugin.log("Mob runes and stats: " + SAVED.size() + " mobs saved, put back as they load");
        } catch (java.io.IOException | RuntimeException e) {
            MobBattlePlugin.log("Mob runes and stats: could not read " + file + " (" + e + ")");
        }
    }

    /** BattleUnloadSystem, UNLOAD: it comes back later, so its saved line stays (the pass would drop a vanished Ref). */
    static void unloaded(Ref<EntityStore> ref) {
        IDS.remove(ref);
    }

    /** BattleUnloadSystem, LOAD: its runes and stats again (on the world thread, outside the system iteration). */
    static void loaded(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (SAVED.isEmpty()) return;
        UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
        Saved s = id != null ? SAVED.get(id.getUuid()) : null;
        if (s == null) return;
        UUID uuid = id.getUuid();
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) return;
            if (!s.runes().isEmpty() && RuneCaster.runesOf(ref).isEmpty()) {
                List<String> ok = new ArrayList<>();
                // a rune whose mod is gone (or a support no longer fitting) is left out, as a kit's would be
                for (String e : s.runes()) {
                    String n = RuneCaster.normalize(e, new String[1]);
                    if (n != null) ok.add(n);
                }
                RuneCaster.set(ref, ok);
            }
            s.direct().forEach((st, v) -> {
                if (Buffs.directOf(ref, st) == 0) Buffs.setDirect(ref, st, v);
            });
            IDS.put(ref, uuid);
        });
    }

    /** World thread, every PASS_MS: record what changed on this world's mobs, write the file when anything did. */
    static void pass(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        List<Ref<EntityStore>> mobs = new ArrayList<>(RuneCaster.mobs());
        for (Ref<EntityStore> r : Buffs.withState()) if (!mobs.contains(r)) mobs.add(r);
        // mobs already saved that lost everything (Cleanse, unbuff, a team kit taken off) are in neither list any more:
        // without them their old line stayed and came back at the next load (audit 2026-10-29)
        for (Ref<EntityStore> r : IDS.keySet()) if (r.getStore() == store && !mobs.contains(r)) mobs.add(r);
        for (Ref<EntityStore> r : mobs) {
            if (!r.isValid() || r.getStore() != store) continue;
            Saved now = new Saved(RuneCaster.runesOf(r), Buffs.directAll(r));
            UUID id = IDS.get(r);
            if (id == null) {
                if (now.empty()) continue;
                UUIDComponent c = store.getComponent(r, UUIDComponent.getComponentType());
                if (c == null || store.getComponent(r, com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType()) == null) continue;
                // never saved with the chunk (test mobs): a line for it would never be read again
                if (store.getArchetype(r).contains(EntityStore.REGISTRY.getNonSerializedComponentType())) continue;
                IDS.put(r, id = c.getUuid());
            }
            if (now.empty()) {
                if (SAVED.remove(id) != null) dirty = true;
                IDS.remove(r);
            } else if (!now.equals(SAVED.get(id))) {
                SAVED.put(id, now);
                dirty = true;
            }
        }
        // a Ref of this world gone without an unload: it died or was removed, so its line goes
        IDS.entrySet().removeIf(e -> {
            Ref<EntityStore> r = e.getKey();
            // a closed world never runs another pass and never unloads its mobs one by one: its Refs go, their lines
            // stay for the next load (audit 2026-10-29: each closed instance kept its whole store alive here)
            if (r.getStore().isShutdown()) return true;
            if (r.isValid() || r.getStore() != store) return false;
            if (SAVED.remove(e.getValue()) != null) dirty = true;
            return true;
        });
        if (dirty) write();
    }

    /** "damage:30,defense:20": direct percents (health too: percent over the mob's own max, as Buffs.setDirect). */
    private static Map<Buffs.Stat, Integer> parseDirect(String value) {
        Map<Buffs.Stat, Integer> out = new EnumMap<>(Buffs.Stat.class);
        for (String part : value.split(",")) {
            String[] kv = part.split(":");
            Buffs.Stat st = kv.length == 2 ? Buffs.Stat.of(kv[0].trim()) : null;
            if (st == null) continue;
            try {
                int v = Integer.parseInt(kv[1].trim());
                if (v > 0) out.put(st, Math.min(st.totalMax(), v));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    private static synchronized void write() {
        dirty = false;
        Path dir = FxSettings.dataDirectory();
        if (dir == null) return;
        StringBuilder b = new StringBuilder("# Mob Battle: runes and kit stats per mob (uuid, runes, stats)\n");
        SAVED.forEach((id, s) -> {
            List<String> stats = new ArrayList<>();
            // EnumMap(Map) refuses an empty plain map (a mob with runes and no stats stopped every save)
            Map<Buffs.Stat, Integer> direct = new EnumMap<>(Buffs.Stat.class);
            direct.putAll(s.direct());
            direct.forEach((st, v) -> stats.add(st.name().toLowerCase(java.util.Locale.ROOT) + ":" + v));
            b.append(id).append('\t').append(s.runes().isEmpty() ? "-" : String.join(",", s.runes())).append('\t')
                    .append(stats.isEmpty() ? "-" : String.join(",", stats)).append('\n');
        });
        try {
            FxSettings.writeAtomically(dir.resolve(FILE), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            MobBattlePlugin.log("Mob runes and stats: could not save (" + e + ")");
        }
    }
}
