package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real light around glowing mobs: the vanilla {@link DynamicLight} component (what Spirit_Ember's model light and the
 * builder tools' entity light use), in the look's color.
 * <ul>
 *   <li>Steady: every mob LookKeeper guards (Jade/Shimmer looks and the buff's shimmer) glows with
 *       {@code FxSettings.lookGlow} (0-15, like block light), in the look's color. It goes when the look goes.</li>
 *   <li>Flash: Kill Wand disintegrations start at {@code FxSettings.deathGlow} and fade to nothing by the time the
 *       mob is removed, in the death look's color (soul rip purple otherwise). Snap's ash gives no flash (user).</li>
 * </ul>
 * A light the entity already had (builder tools) is put back when ours goes. Everything runs on the mob's world
 * thread, from {@link LookKeeper#tick} (every tick while {@link #any()} or a look is kept).
 */
final class LookLight {

    /** A guarded mob's light: its look's hex (null = none, e.g. Black) and whether the look is jade. */
    record Want(String hex, boolean jade) {
    }

    /**
     * Jade lights at this share of lookGlow (rounded, at least 1): the jade look is already fullbright with bloom over
     * the whole model, so the same light made it glare (user, 2026-10-12). Shimmer and the buff keep the full level.
     */
    static final float JADE_GLOW = 0.6f;

    /** Soul rip flash color (MobBattle_Disintegrate highlight). */
    static final String SOUL_RIP_HEX = "#9b4dff";

    private static final class Lit {
        /** The light the entity had before ours (builder tools), or null. Only read on the world thread. */
        DynamicLight before;
        boolean hadBefore;
        /** Light sent now: hex + level, so an unchanged light is never resent. */
        String hex;
        int level = -1;
        /** Flash: fades from {@code peak} at {@code start} to 0 at {@code end}; 0 = steady. */
        volatile long start, end;
        volatile int peak;
        volatile String flashHex;
    }

    private static final Map<Ref<EntityStore>, Lit> LIT = new ConcurrentHashMap<>();

    private LookLight() {
    }

    /** BattleLifecycleSystem keeps the per-tick pass going while this is true (a light to fade or take off). */
    static boolean any() {
        return !LIT.isEmpty();
    }

    /**
     * Palette color name -> hex; null or unknown names fall back to {@code fallback}. Black gives null (no light: a
     * light can only add, so a black look would glow grey-white).
     */
    static String hexOf(String color, String fallback) {
        if ("Black".equals(color)) return null;
        String hex = color == null ? null : Teams.PALETTE.get(color);
        return hex != null ? hex : fallback;
    }

    /**
     * Disintegration flash on {@code mob} (any thread): from deathGlow down to 0 over {@code durationMs}. Replaces a
     * steady look light. Off with deathGlow 0.
     */
    static void flash(Ref<EntityStore> mob, String hex, long durationMs) {
        int peak = FxSettings.deathGlow;
        if (mob == null || !mob.isValid() || hex == null || peak <= 0 || durationMs <= 0) return;
        Lit lit = LIT.computeIfAbsent(mob, r -> new Lit());
        long now = System.currentTimeMillis();
        lit.flashHex = hex;
        lit.peak = peak;
        lit.start = now;
        lit.end = now + durationMs;
    }

    /**
     * One pass for {@code store} (world thread): {@code wanted} = the mobs LookKeeper guards there, with their look's
     * hex. Steady lights follow it, flashes fade, the rest goes back to how it was.
     */
    static void tick(Store<EntityStore> store, Map<Ref<EntityStore>, Want> wanted) {
        if (store.isShutdown()) return;
        int steady = FxSettings.lookGlow;
        if (steady > 0) {
            for (Map.Entry<Ref<EntityStore>, Want> e : wanted.entrySet()) {
                Want want = e.getValue();
                if (want == null || want.hex() == null) continue;
                // `wanted` was built a tick earlier (LookKeeper.lightLater): the mob may be gone since (despawn,
                // removal after a death look), and getComponent on it throws and aborted the whole pass.
                Ref<EntityStore> mob = e.getKey();
                if (!mob.isValid() || mob.getStore() != store) continue;
                Lit lit = LIT.get(e.getKey());
                if (lit != null && lit.end != 0) continue; // flashing
                if (lit == null) {
                    lit = new Lit();
                    LIT.put(e.getKey(), lit);
                }
                int level = want.jade() ? Math.max(1, Math.round(steady * JADE_GLOW)) : steady;
                show(e.getKey(), lit, want.hex(), level, store);
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Ref<EntityStore>, Lit> e : LIT.entrySet()) {
            Ref<EntityStore> ref = e.getKey();
            Lit lit = e.getValue();
            if (!ref.isValid()) {
                LIT.remove(ref, lit);
                continue;
            }
            if (ref.getStore() != store) continue;
            if (lit.end != 0) {
                if (now >= lit.end) {
                    off(ref, lit, store);
                    continue;
                }
                float left = (lit.end - now) / (float) Math.max(1, lit.end - lit.start);
                int level = Math.round(lit.peak * left);
                if (level <= 0) off(ref, lit, store);
                else show(ref, lit, lit.flashHex, level, store);
            } else if (steady <= 0 || wanted.get(ref) == null || wanted.get(ref).hex() == null) {
                off(ref, lit, store);
            }
        }
    }

    /** Puts or updates our light on {@code ref}: {@code hex} scaled so its strongest channel is {@code level}. */
    private static void show(Ref<EntityStore> ref, Lit lit, String hex, int level, Store<EntityStore> store) {
        DynamicLight light = store.getComponent(ref, DynamicLight.getComponentType());
        if (lit.level < 0) {
            // First time: keep a light the entity had of its own, to put it back later.
            lit.hadBefore = light != null;
            lit.before = light != null ? (DynamicLight) light.clone() : null;
        } else if (level == lit.level && hex.equals(lit.hex) && light != null) {
            return;
        }
        ColorLight color = colorLight(hex, level);
        if (light == null) {
            store.putComponent(ref, DynamicLight.getComponentType(), new DynamicLight(color));
        } else {
            light.setColorLight(color);
        }
        lit.hex = hex;
        lit.level = level;
    }

    /** Takes our light off {@code ref} and puts back the one it had, if any. */
    private static void off(Ref<EntityStore> ref, Lit lit, Store<EntityStore> store) {
        LIT.remove(ref, lit);
        if (lit.level < 0) return; // never shown
        if (lit.hadBefore && lit.before != null) {
            store.putComponent(ref, DynamicLight.getComponentType(), lit.before);
        } else if (store.getComponent(ref, DynamicLight.getComponentType()) != null) {
            store.removeComponent(ref, DynamicLight.getComponentType());
        }
    }

    /** "#RRGGBB" scaled to light levels 0-15 with the strongest channel at {@code level}. */
    static ColorLight colorLight(String hex, int level) {
        int rgb;
        try {
            rgb = Integer.parseInt(hex.substring(1), 16);
        } catch (RuntimeException e) {
            rgb = 0xFFFFFF;
        }
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        int max = Math.max(1, Math.max(r, Math.max(g, b)));
        level = Math.max(0, Math.min(15, level));
        return new ColorLight((byte) 0, (byte) Math.round(r * level / (float) max),
                (byte) Math.round(g * level / (float) max), (byte) Math.round(b * level / (float) max));
    }

    /**
     * RoleChange: the holder kept our DynamicLight, so the record (and the light it had before ours) follows the new
     * Ref; else the next pass would take our own light as "the one it had" and put it back for good.
     */
    static void carry(Ref<EntityStore> old, Ref<EntityStore> neu) {
        Lit lit = LIT.remove(old);
        if (lit != null) LIT.put(neu, lit);
    }

    /** From WandFx.prune (1/s): a Ref keeps its whole world alive. */
    static void prune() {
        LIT.keySet().removeIf(ref -> !ref.isValid() || ref.getStore().isShutdown());
    }
}
