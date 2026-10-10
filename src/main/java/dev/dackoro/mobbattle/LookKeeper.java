package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lasting looks (Jade, Shimmer: infinite effects whose ModelVFX shows the look) kept on the mobs that got them
 * from the Effect Wand. The client drops a ModelVFX as soon as the entity loses ANY effect, although the look is
 * still active on the server, so the guardian re-sends the look (remove + add) whenever another effect ends.
 * A look removed on purpose (Cleanse) or a dead mob is untracked. The Buff Wand's shimmer (Buffs, an Fx effect) is
 * guarded the same way ({@link #trackBuff}) unless the mob already has its own look, which always wins.
 */
final class LookKeeper {

    enum Style { JADE, SHIMMER }

    /** One tracked mob: its look, and the active effect indexes seen at the last guardian pass. */
    private static final class Tracked {
        final Style style;
        /** Palette color of the look (MobBattle_Look_Jade_Red: Red), or null (test looks). */
        final String color;
        /** The effect re-sent by the guardian (the lasting look). */
        final EntityEffect effect;
        final int effectIndex;
        /** The look effect active now: the one given (e.g. an appearing variant) until the first re-send. */
        int index;
        /** Null until the first guardian pass. Only touched on the world thread of the mob's store. */
        int[] seen;
        /** Some effect was removed and put back within one tick (same index set): re-send anyway. */
        volatile boolean touched;
        /** The buff's shimmer (Buffs), not a look of its own. */
        final boolean buff;
        /** Taken off by the guardian, going back on in RESEND_GAP_MS. Only touched on the world thread. */
        boolean pending;

        Tracked(Style style, String color, EntityEffect effect, int effectIndex, int index, boolean buff) {
            this.style = style;
            this.buff = buff;
            this.color = color;
            this.effect = effect;
            this.effectIndex = effectIndex;
            this.index = index;
        }
    }

    /** Appearing look -> the lasting look the guardian re-sends instead (MobBattle_Look_JadeAppear_<C>: see resendAs). */
    private static final Map<String, String> RESEND_AS = Map.of(
            "MobBattle_Test_JadeAppearHold", "MobBattle_Test_JadeOnce_C");
    private static final String JADE_APPEAR = "MobBattle_Look_JadeAppear_";
    private static final String JADE = "MobBattle_Look_Jade_";

    private static final Map<Ref<EntityStore>, Tracked> TRACKED = new ConcurrentHashMap<>();
    /** Stores with a guardian pass already queued on their world thread (one at a time per world). */
    private static final Set<Store<EntityStore>> QUEUED = ConcurrentHashMap.newKeySet();

    private LookKeeper() {
    }

    /**
     * The look style of an effect id: "Jade" in the id means JADE, "Shimmer" SHIMMER, only for MobBattle_ ids.
     * MobBattle_Fx_ effects are wand feedback, never looks (Fx_Death_Jade must not be guarded).
     */
    static Style styleOf(String effectId) {
        if (effectId == null || !effectId.startsWith("MobBattle_") || effectId.startsWith("MobBattle_Fx_")) {
            return null;
        }
        if (effectId.contains("Jade")) return Style.JADE;
        if (effectId.contains("Shimmer")) return Style.SHIMMER;
        return null;
    }

    /** The palette color an effect id ends with ("_Red"), or null. */
    static String colorOf(String effectId) {
        int cut = effectId == null ? -1 : effectId.lastIndexOf('_');
        if (cut < 0) return null;
        String c = effectId.substring(cut + 1);
        return Teams.PALETTE.containsKey(c) ? c : null;
    }

    /** The palette color of a tracked mob's look, or null (not tracked, or a test look). */
    static String colorOf(Ref<EntityStore> ref) {
        Tracked t = ref == null ? null : TRACKED.get(ref);
        return t == null ? null : t.color;
    }

    /** The lasting look the guardian re-sends for an appearing one, or null when it is lasting already. */
    private static String resendAs(String id) {
        if (id.startsWith(JADE_APPEAR)) return JADE + id.substring(JADE_APPEAR.length());
        return RESEND_AS.get(id);
    }

    /** The look style of a tracked mob, or null when it is not tracked. */
    static Style styleOf(Ref<EntityStore> ref) {
        Tracked t = ref == null ? null : TRACKED.get(ref);
        return t == null ? null : t.style;
    }

    /** Starts guarding {@code effect} on {@code ref} (called after the Effect Wand's addEffect succeeded). */
    static void track(Ref<EntityStore> ref, EntityEffect effect) {
        if (ref == null || !ref.isValid() || effect == null) return;
        Style style = styleOf(effect.getId());
        if (style == null) return;
        int index = EntityEffect.getAssetMap().getIndex(effect.getId());
        if (index < 0) return;
        // An appearing look (plays its growth once, then holds) is re-sent as its plain lasting look, so the
        // growth does not replay every time the mob loses another effect.
        EntityEffect keep = effect;
        String keepId = resendAs(effect.getId());
        if (keepId != null && EntityEffect.getAssetMap().getAsset(keepId) != null) {
            keep = EntityEffect.getAssetMap().getAsset(keepId);
        }
        int keepIndex = EntityEffect.getAssetMap().getIndex(keep.getId());
        if (keepIndex < 0) return;
        TRACKED.put(ref, new Tracked(style, colorOf(effect.getId()), keep, keepIndex, index, false));
    }

    /** True when {@code ref} wears a look of its own (Effect Wand), not just the buff's shimmer. */
    static boolean hasOwnLook(Ref<EntityStore> ref) {
        Tracked t = ref == null ? null : TRACKED.get(ref);
        return t != null && !t.buff;
    }

    /** Guards the buff's shimmer {@code effect} (already added) on {@code ref}, unless it has its own look. */
    static void trackBuff(Ref<EntityStore> ref, EntityEffect effect) {
        if (ref == null || !ref.isValid() || effect == null || hasOwnLook(ref)) return;
        int index = EntityEffect.getAssetMap().getIndex(effect.getId());
        if (index < 0) return;
        TRACKED.put(ref, new Tracked(Style.SHIMMER, colorOf(effect.getId()), effect, index, index, true));
    }

    /** Stops guarding the buff's shimmer on {@code ref} (buff gone or turned into runes); an own look stays. */
    static void untrackBuff(Ref<EntityStore> ref) {
        Tracked t = ref == null ? null : TRACKED.get(ref);
        if (t != null && t.buff) TRACKED.remove(ref, t);
    }

    /** Stops guarding {@code ref} (Cleanse, death). */
    static void untrack(Ref<EntityStore> ref) {
        if (ref != null) TRACKED.remove(ref);
    }

    /**
     * Our code removed an effect from {@code ref} (e.g. WandFx.onTarget restarting a wand effect). If it is put back
     * in the same tick the guardian sees the same indexes, but the client already dropped the look: re-send it.
     */
    static void touched(Ref<EntityStore> ref) {
        Tracked t = ref == null ? null : TRACKED.get(ref);
        if (t != null) t.touched = true;
    }

    /**
     * The mob's size changed (Buffs): the client rebuilds the model and drops the look's ModelVFX without any effect
     * going away, so the guardian can't see it. Re-sent once the new size reached the client (MODEL_SETTLE_MS), as
     * Buffs does with the crown.
     */
    static void resized(Ref<EntityStore> ref) {
        if (ref == null || !TRACKED.containsKey(ref)) return;
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> touched(ref),
                WandFx.MODEL_SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** BattleLifecycleSystem checks this every tick: with nothing tracked there is nothing to do. */
    static boolean any() {
        return !TRACKED.isEmpty();
    }

    /**
     * Queues one guardian pass for {@code store} on its world thread (components are read there). At most one
     * pass is queued per world, so a slow tick never piles them up.
     */
    static void schedule(Store<EntityStore> store) {
        if (store.isShutdown() || !QUEUED.add(store)) return;
        store.getExternalData().getWorld().execute(() -> {
            QUEUED.remove(store);
            tick(store);
        });
    }

    /** The guardian pass for the mobs of {@code store}. World thread. */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        for (Map.Entry<Ref<EntityStore>, Tracked> e : TRACKED.entrySet()) {
            Ref<EntityStore> ref = e.getKey();
            Tracked t = e.getValue();
            if (!ref.isValid()) {
                TRACKED.remove(ref, t);
                continue;
            }
            if (ref.getStore() != store) continue;
            if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                TRACKED.remove(ref, t);
                continue;
            }
            EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
            if (effects == null) {
                TRACKED.remove(ref, t);
                continue;
            }
            // Taken off by the guardian itself, waiting to go back on (resendLater).
            if (t.pending) continue;
            int[] now = effects.getActiveEffectIndexes().clone();
            if (!contains(now, t.index)) {
                // The look is gone: it was removed on purpose (e.g. Cleanse), so it is not kept any more.
                TRACKED.remove(ref, t);
                continue;
            }
            boolean lost = t.touched || t.seen != null && anyMissing(t.seen, now);
            t.touched = false;
            t.seen = now;
            if (lost) {
                // Another effect ended: the client dropped the look's ModelVFX. Send the look again: off now, back on
                // RESEND_GAP_MS later. A remove + add in the same tick may reach the client as nothing new (the
                // swirl showed nothing that way, WandFx.swirlNow), so the look would stay lost.
                effects.removeEffect(ref, t.index, RemovalBehavior.COMPLETE, store);
                t.pending = true;
                resendLater(ref, t, store);
            }
        }
    }

    /** Gap between taking the look off and putting it back (two ticks; the swirl restart works with 60 ms). */
    private static final long RESEND_GAP_MS = 100;

    /** Second half of a re-send: the look goes back on, if the mob is still tracked with it and alive. */
    private static void resendLater(Ref<EntityStore> ref, Tracked t, Store<EntityStore> store) {
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            t.pending = false;
            if (store.isShutdown() || !ref.isValid() || TRACKED.get(ref) != t
                    || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                TRACKED.remove(ref, t);
                return;
            }
            EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
            if (effects == null || !effects.addEffect(ref, t.effect, store)) {
                TRACKED.remove(ref, t);
                return;
            }
            t.index = t.effectIndex;
            // What is active now: not a loss on the next pass. Something removed during the gap is covered: the
            // look was off then and goes on fresh now.
            t.seen = effects.getActiveEffectIndexes().clone();
            t.touched = false;
        }), RESEND_GAP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** From WandFx.prune (BattleManager.maintain, 1/s): a Ref keeps its whole world alive. */
    static void prune() {
        TRACKED.keySet().removeIf(ref -> !ref.isValid() || ref.getStore().isShutdown());
        QUEUED.removeIf(Store::isShutdown);
    }

    private static boolean contains(int[] indexes, int value) {
        for (int i : indexes) {
            if (i == value) return true;
        }
        return false;
    }

    /** True when some index of {@code before} is not in {@code now} any more. */
    private static boolean anyMissing(int[] before, int[] now) {
        for (int i : before) {
            if (!contains(now, i)) return true;
        }
        return false;
    }
}
