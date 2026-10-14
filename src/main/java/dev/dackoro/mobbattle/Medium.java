package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import org.joml.Vector3d;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a battle mob can move and whom it can reach (2026-10-14, user: the unstick pulled fish out of the water, and
 * walkers that chase a fish into a lake drown). Test on server-mobbattle: Zombie, wolf, Trork and Golem_Firesteel sank
 * 66 blocks and drowned within 15 s, a Skeleton_Soldier walked the bottom unharmed, a Scarak_Seeker hovered over it.
 * <ul>
 *   <li>What a mob can do ({@link Can}) comes from its role: the motion controllers "Walk", "Fly" and "Dive" (the
 *   game's swimmers), {@code Role.isBreathesInWater()} (what the game's own breathing check asks) and whether it was
 *   seen hitting with a projectile ({@link #sawProjectile}; game roles start from observed-ranged.txt).</li>
 *   <li>Where a target is right now ({@link Where}): flying, on the ground, in shallow water (a column of 2 blocks or
 *   less), at the surface of deep water, or under it.</li>
 * </ul>
 */
final class Medium {

    private Medium() {
    }

    /** What a mob can do. Players can do everything: they decide for themselves. */
    record Can(boolean walks, boolean flies, boolean swims, boolean underwater, boolean ranged) {
        static final Can ALL = new Can(true, true, true, true, true);

        /** The same mover that also attacks from afar (one copy kept per Can, not one per call). */
        Can withRanged() {
            return ranged ? this : RANGED_OF.computeIfAbsent(this, c -> new Can(c.walks, c.flies, c.swims, c.underwater, true));
        }
    }

    /** Where a target is right now. */
    enum Where { GROUND, AIR, SHALLOW, SURFACE, UNDER }

    /** Where.values() clones its array on every call: one copy for the cached lookups. */
    private static final Where[] WHERES = Where.values();
    /** Can -> the same with ranged on (Can.withRanged). */
    private static final Map<Can, Can> RANGED_OF = new ConcurrentHashMap<>();

    private static final Map<String, Can> BY_ROLE = new ConcurrentHashMap<>();
    /** Declared by other mods through MobBattleApi (they win over what the role says and what was learned). */
    private static final Map<String, Can> DECLARED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> DECLARED_RANGED = new ConcurrentHashMap<>();

    /** MobBattleApi.declareMedium: how a role moves, whatever its motion controllers say. */
    static void declare(String role, boolean walks, boolean flies, boolean swims, boolean underwater) {
        if (role != null && !role.isBlank()) DECLARED.put(role, new Can(walks, flies, swims, underwater || swims, false));
    }

    /** MobBattleApi.declareRanged: whether a role attacks from afar, without waiting for its first projectile hit. */
    static void declareRanged(String role, boolean ranged) {
        if (role != null && !role.isBlank()) DECLARED_RANGED.put(role, ranged);
    }
    /** Roles seen hitting with a projectile, this session and from observed-ranged.txt (game roles, test verify). */
    private static final Set<String> RANGED = ConcurrentHashMap.newKeySet();
    private static volatile boolean seeded;

    /** What {@code ref} can do; null for something that is neither an NPC nor a player. */
    static Can of(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (ref == null || !ref.isValid()) return null;
        NPCEntity npc = accessor.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) return accessor.getComponent(ref, PlayerRef.getComponentType()) != null ? Can.ALL : null;
        Role role = npc.getRole();
        String name = npc.getRoleName();
        if (role == null || name == null) return Can.ALL;
        Can base = DECLARED.get(name);
        if (base == null) base = BY_ROLE.computeIfAbsent(name, k -> new Can(role.hasMotionController("Walk"), role.hasMotionController("Fly"),
                role.hasMotionController("Dive"), role.isBreathesInWater() || role.hasMotionController("Dive"), false));
        return isRanged(name) ? base.withRanged() : base;
    }

    static boolean isRanged(String role) {
        if (role == null) return false;
        Boolean declared = DECLARED_RANGED.get(role);
        if (declared != null) return declared;
        if (!seeded) seed();
        return RANGED.contains(role);
    }

    /** Damage systems: {@code shooter} hit something with a projectile, so its role fights at range. */
    static void sawProjectile(Ref<EntityStore> shooter, ComponentAccessor<EntityStore> accessor) {
        if (shooter == null || !shooter.isValid()) return;
        NPCEntity npc = accessor.getComponent(shooter, NPCEntity.getComponentType());
        String name = npc != null ? npc.getRoleName() : null;
        if (name != null && !isRanged(name) && RANGED.add(name)) {
            MobBattlePlugin.debug("medium: " + name + " fights at range (projectile hit)");
            remember(name);
        }
    }

    /** Learned roles survive restarts (any mod's too): one per line in &lt;data dir&gt;/ranged-learned.txt. */
    private static final String LEARNED_FILE = "ranged-learned.txt";

    private static void remember(String role) {
        try {
            java.nio.file.Path file = FxSettings.dataDirectory().resolve(LEARNED_FILE);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, role + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            MobBattlePlugin.log("Could not save " + LEARNED_FILE + ": " + e);
        }
    }

    /** The roles known to fight at range (test verify writes them to tests/ranged_*.txt). */
    static java.util.List<String> rangedRoles() {
        if (!seeded) seed();
        java.util.List<String> out = new java.util.ArrayList<>(RANGED);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static synchronized void seed() {
        if (seeded) return;
        try (var in = Medium.class.getResourceAsStream("observed-ranged.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                    String role = line.trim();
                    if (!role.isEmpty() && !role.startsWith("#")) RANGED.add(role);
                }
            }
        } catch (java.io.IOException ignored) {
        }
        try {
            java.nio.file.Path file = FxSettings.dataDirectory().resolve(LEARNED_FILE);
            if (java.nio.file.Files.isRegularFile(file)) {
                for (String line : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                    String role = line.trim();
                    if (!role.isEmpty() && !role.startsWith("#")) RANGED.add(role);
                }
            }
        } catch (Exception e) {
            MobBattlePlugin.log("Could not read " + LEARNED_FILE + ": " + e);
        }
        seeded = true;
    }

    /** Roles were reloaded: their controllers may have changed (PickLists.rolesChanged). */
    static void rolesChanged() {
        BY_ROLE.clear();
    }

    /** where() answers kept WHERE_MS (time << 3 | ordinal): target picks in a 400-mob royale ask for every enemy. */
    private static final Map<Ref<EntityStore>, Long> WHERE = new ConcurrentHashMap<>();
    private static final long WHERE_MS = 500;

    /** Where {@code ref} is right now (fluid lookups at its feet and above, kept half a second). */
    static Where where(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        long now = System.currentTimeMillis();
        Long kept = WHERE.get(ref);
        if (kept != null && now - (kept >>> 3) < WHERE_MS) return WHERES[(int) (kept & 7)];
        Where w = whereNow(ref, accessor);
        WHERE.put(ref, now << 3 | w.ordinal());
        return w;
    }

    /** BattleManager.maintain: drops removed entities and old answers (a Ref keeps its world reachable). */
    static void prune() {
        if (WHERE.isEmpty()) return;
        long old = System.currentTimeMillis() - WHERE_MS * 4;
        WHERE.entrySet().removeIf(e -> !e.getKey().isValid() || (e.getValue() >>> 3) < old);
    }

    private static Where whereNow(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (Unstick.flyingNow(ref, accessor)) return Where.AIR;
        Vector3d p = ChaosWand.positionOf(ref, accessor);
        if (p == null) return Where.GROUND;
        int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y + 0.2), z = (int) Math.floor(p.z);
        if (!water(x, y, z, accessor)) return Where.GROUND;
        int below = 0, above = 0;
        while (below < 3 && water(x, y - below - 1, z, accessor)) below++;
        while (above < 3 && water(x, y + above + 1, z, accessor)) above++;
        if (below + above + 1 <= 2) return Where.SHALLOW;
        // Two blocks of water over its feet: under the surface (a fish one block down is still at the surface).
        return above >= 2 ? Where.UNDER : Where.SURFACE;
    }

    /** Can {@code me} get at something {@code there}? */
    static boolean reaches(Can me, Where there) {
        if (me == null) return true;
        return switch (there) {
            case AIR -> me.flies || me.ranged;
            case GROUND -> me.walks || me.flies || me.ranged;
            case SHALLOW -> true;
            case SURFACE -> me.swims || me.flies || me.ranged;
            case UNDER -> me.swims || me.underwater;
        };
    }

    /** Is {@code there} the mob's own medium (preferred targets)? */
    static boolean same(Can me, Where there) {
        if (me == null) return true;
        return switch (there) {
            case AIR -> me.flies;
            case GROUND, SHALLOW -> me.walks;
            case SURFACE -> me.swims;
            case UNDER -> me.swims || me.underwater;
        };
    }

    /** A water mob only: it lives in water and can't walk out of it (Dive without Walk). */
    static boolean waterOnly(Can me) {
        return me != null && me.swims && !me.walks && !me.flies;
    }

    /** A mob that drowns: it walks, breathes air and can't fly (sinks to the bottom of deep water). */
    static boolean drowns(Can me) {
        return me != null && !me.swims && !me.flies && !me.underwater;
    }

    /** Where a teleport may put this mob: water for water mobs, dry land for those that drown, else as before. */
    static FightFx.Spot spotFor(Can me) {
        return waterOnly(me) ? FightFx.Spot.WATER : drowns(me) ? FightFx.Spot.LAND : FightFx.Spot.ANY;
    }

    /** True if the block holds a harmless fluid (water, mud...). Unloaded = false. */
    static boolean water(int x, int y, int z, ComponentAccessor<EntityStore> accessor) {
        var chunks = accessor.getExternalData().getWorld().getChunkStore();
        var ref = chunks.getChunkSectionReference(x >> 5, y >> 5, z >> 5);
        if (ref == null || !ref.isValid()) return false;
        var fluids = chunks.getStore().getComponent(ref,
                com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());
        if (fluids == null) return false;
        int id = fluids.getFluidId(x & 31, y & 31, z & 31);
        if (id == 0) return false;
        var fluid = com.hypixel.hytale.server.core.asset.type.fluid.Fluid.getAssetMap().getAsset(id);
        return fluid != null && fluid.getDamageToEntities() <= 0 && (fluid.getInteractions() == null || fluid.getInteractions().isEmpty());
    }
}
