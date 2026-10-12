package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
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
 * Each pass also hands the guarded mobs to {@link LookLight}, which makes them glow in their look's color.
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
        /** Remaining seconds of each {@link #seen} effect at that pass (-1 = infinite or gone). World thread only. */
        float[] seenLeft;
        /** Some effect was removed and put back within one tick (same index set): re-send anyway. */
        volatile boolean touched;
        /** The buff's shimmer (Buffs), not a look of its own. */
        final boolean buff;
        /** Taken off by the guardian, going back on in RESEND_GAP_MS. Only touched on the world thread. */
        boolean pending;
        /** Same look under another id (MobBattle_LookTwin_*), or null: a re-send swaps to it in one tick. */
        final EntityEffect twin;
        final int twinIndex;
        /** Players that saw the mob at the last pass, or null before the first one. World thread only. */
        Set<Ref<EntityStore>> viewers;

        Tracked(Style style, String color, EntityEffect effect, int effectIndex, int index, boolean buff) {
            this.style = style;
            this.buff = buff;
            this.color = color;
            this.effect = effect;
            this.effectIndex = effectIndex;
            this.index = index;
            this.twin = twinOf(effect);
            this.twinIndex = twin != null ? EntityEffect.getAssetMap().getIndex(twin.getId()) : -1;
        }
    }

    private static final String JADE_APPEAR = "MobBattle_Look_JadeAppear_";
    private static final String JADE = "MobBattle_Look_Jade_";
    private static final String LOOK = "MobBattle_Look_";
    private static final String TWIN = "MobBattle_LookTwin_";

    /** The twin of a lasting look (MobBattle_Look_Jade_Red -> MobBattle_LookTwin_Jade_Red), or null if it has none. */
    private static EntityEffect twinOf(EntityEffect effect) {
        String id = effect == null ? null : effect.getId();
        if (id == null || !id.startsWith(LOOK)) return null;
        EntityEffect twin = EntityEffect.getAssetMap().getAsset(TWIN + id.substring(LOOK.length()));
        return twin != null && EntityEffect.getAssetMap().getIndex(twin.getId()) >= 0 ? twin : null;
    }

    private static final Map<Ref<EntityStore>, Tracked> TRACKED = new ConcurrentHashMap<>();
    /** Stores with a LookLight pass already queued on their world thread (one at a time per world). */
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

    /** The palette color an effect id ends with ("_Red", also before a size suffix: "_Red_S200"), or null. */
    static String colorOf(String effectId) {
        if (effectId != null && effectId.matches(".*_S\\d+")) effectId = effectId.substring(0, effectId.lastIndexOf('_'));
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
        if (id.startsWith(TWIN)) return LOOK + id.substring(TWIN.length());
        return null;
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

    /**
     * Before the Effect Wand gives a new look: takes the tracked look off (both the appearing and the lasting id), so
     * the mob never wears two look effects (the old one held the ModelVFX slot and the guardian's re-send of the new
     * one was swallowed until the next change). Returns true when {@code next} itself was removed: the caller must
     * add it back a moment later, since a remove + add in the same tick reaches the client as nothing new.
     */
    static boolean dropLook(Ref<EntityStore> ref, EntityEffect next, EffectControllerComponent effects,
                            com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor) {
        if (ref == null || next == null || styleOf(next.getId()) == null) return false;
        Tracked t = TRACKED.get(ref);
        if (t != null && !t.buff) TRACKED.remove(ref, t);
        // Every look on the mob goes, not only the tracked one: a look saved with the mob (world left and entered
        // again) is not tracked, and a second look on top of it broke the re-send (user, 2026-10-12).
        int nextIndex = EntityEffect.getAssetMap().getIndex(next.getId());
        boolean same = false;
        for (int index : activeLooks(effects)) {
            effects.removeEffect(ref, index, RemovalBehavior.COMPLETE, accessor);
            if (index == nextIndex) same = true;
        }
        return same;
    }

    /** Indexes of every own look effect active on the mob (looks and twins; not the buff's Fx shimmer). */
    private static java.util.List<Integer> activeLooks(EffectControllerComponent effects) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int index : effects.getActiveEffectIndexes()) {
            EntityEffect e = EntityEffect.getAssetMap().getAsset(index);
            if (e != null && styleOf(e.getId()) != null) out.add(index);
        }
        return out;
    }

    /** True when the mob wears a look effect of its own (checked when it loads). */
    static boolean hasLookEffect(EffectControllerComponent effects) {
        return effects != null && !activeLooks(effects).isEmpty();
    }

    /**
     * A mob loaded with a look saved on it (infinite effects are saved; TRACKED only lives in memory): it is guarded
     * again, so the look comes back when another effect ends and it glows (LookLight). Extra looks (two saved at once)
     * are taken off. World thread.
     */
    static void adopt(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (store.isShutdown() || !ref.isValid() || TRACKED.containsKey(ref)) return;
        EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effects == null) return;
        java.util.List<Integer> looks = activeLooks(effects);
        if (looks.isEmpty()) return;
        for (int i = 1; i < looks.size(); i++) effects.removeEffect(ref, looks.get(i), RemovalBehavior.COMPLETE, store);
        track(ref, EntityEffect.getAssetMap().getAsset(looks.get(0)));
        // Nobody has seen it get the look: whoever already sees it counts as a new viewer on the first pass.
        Tracked t = TRACKED.get(ref);
        if (t != null) t.viewers = new java.util.HashSet<>();
    }

    /**
     * The client shows a look's ModelVFX only when the effect is added while it already sees the mob, never from the
     * effect list sent when the mob comes into view (user, 2026-10-12: jade goblins came back plain after re-entering
     * the world, while the server still had the look). So a player starting to see a tracked mob (vanilla tracker
     * Visible.visibleTo) gets the look re-sent once the mob reached their client. True when someone new sees it.
     */
    private static boolean newViewer(Ref<EntityStore> ref, Tracked t, Store<EntityStore> store) {
        EntityTrackerSystems.Visible visible = store.getComponent(ref, EntityTrackerSystems.Visible.getComponentType());
        Map<Ref<EntityStore>, ?> now = visible != null ? visible.visibleTo : Map.of();
        if (t.viewers == null) {
            // First pass after the Effect Wand gave the look: those viewers saw it being added.
            t.viewers = new java.util.HashSet<>(now.keySet());
            return false;
        }
        boolean added = false;
        for (Ref<EntityStore> viewer : now.keySet()) {
            if (!t.viewers.contains(viewer)) {
                added = true;
                break;
            }
        }
        if (added || now.size() != t.viewers.size()) t.viewers = new java.util.HashSet<>(now.keySet());
        return added;
    }

    /** How long after a player starts seeing a mob its look is re-sent (the mob must be on their client first). */
    private static final long NEW_VIEWER_MS = 300;

    /** Delay before a look removed by {@link #dropLook} goes back on. */
    static final long REAPPLY_GAP_MS = 100;

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
        touchLater(ref, WandFx.MODEL_SETTLE_MS);
    }

    private static void touchLater(Ref<EntityStore> ref, long ms) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> touched(ref),
                ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** BattleLifecycleSystem checks this every tick: with nothing tracked there is nothing to do. */
    static boolean any() {
        return !TRACKED.isEmpty();
    }

    /**
     * The guardian pass for the mobs of {@code store}, every tick from {@link LookGuardSystem}: on the world thread,
     * right before the tracker sends this tick's effect changes, so a re-send goes in the same packet as the change
     * that dropped the look and the client never shows the normal skin (a pass queued with world.execute ran a tick
     * late: hit jade mobs blinked, user 2026-10-12). Only effect changes here; the lights go on the world thread.
     */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        // The guarded mobs of this world, with their look's light color (LookLight). Only built with lookGlow on: this
        // pass runs every tick while a look is kept.
        boolean glow = FxSettings.lookGlow > 0;
        Map<Ref<EntityStore>, LookLight.Want> glowing = glow ? new java.util.HashMap<>() : Map.of();
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
            if (glow) {
                boolean jade = t.style == Style.JADE;
                glowing.put(ref, new LookLight.Want(LookLight.hexOf(t.color, Teams.PALETTE.get(jade ? "Green" : "Purple")), jade));
            }
            if (newViewer(ref, t, store)) {
                touchLater(ref, NEW_VIEWER_MS);
                if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("look re-sent for a new viewer of " + ChaosWand.nameOf(ref, store));
            }
            // Taken off by the guardian itself, waiting to go back on (resendLater).
            if (t.pending) continue;
            int[] now = effects.getActiveEffectIndexes().clone();
            if (!contains(now, t.index)) {
                // The look is gone: it was removed on purpose (e.g. Cleanse), so it is not kept any more.
                TRACKED.remove(ref, t);
                continue;
            }
            float[] left = remaining(effects, now);
            // A renewed effect (Extend/Overwrite) goes to the client as another Add with no removal, and drops the
            // look too: Red_Flash on every hit, Hit_Interrupt_Eligible refreshed by the role every 0.25 s in combat
            // (user, 2026-10-12: hit jade mobs lost the look). Vanilla addEffect on an active effect only resets its
            // duration (javap), so a remaining time that went up means it was renewed. Only visual effects count
            // (visual): Hit_Interrupt_Eligible has no ApplicationEffects and re-sending for it restarted the shimmer.
            boolean lost = t.touched || t.seen != null && (anyMissing(t.seen, now) || renewed(t.seen, t.seenLeft, now, left));
            if (lost && MobBattlePlugin.debugging()) {
                MobBattlePlugin.debug("look lost on " + ChaosWand.nameOf(ref, store) + ": " + why(t, now, left));
            }
            t.touched = false;
            t.seen = now;
            t.seenLeft = left;
            if (lost && swap(ref, t, effects, store)) continue;
            if (lost) {
                // Another effect ended: the client dropped the look's ModelVFX. Send the look again: off now, back on
                // RESEND_GAP_MS later. A remove + add in the same tick may reach the client as nothing new (the
                // swirl showed nothing that way, WandFx.swirlNow), so the look would stay lost.
                effects.removeEffect(ref, t.index, RemovalBehavior.COMPLETE, store);
                t.pending = true;
                resendLater(ref, t, store);
            }
        }
        if (!glowing.isEmpty() || LookLight.any()) lightLater(store, glowing);
    }

    /**
     * Queues the LookLight pass on the world thread, outside the system iteration (it puts components). At most one is
     * queued per world, so a slow tick never piles them up.
     */
    private static void lightLater(Store<EntityStore> store, Map<Ref<EntityStore>, LookLight.Want> glowing) {
        if (store.isShutdown() || !QUEUED.add(store)) return;
        store.getExternalData().getWorld().execute(() -> {
            QUEUED.remove(store);
            if (!store.isShutdown()) LookLight.tick(store, glowing);
        });
    }

    /**
     * Re-sends the look in one tick by switching ids: the lasting look and its twin take turns (an appearing look
     * goes to the lasting one). A different id reaches the client as a new effect, so there is no gap with the normal
     * skin showing, unlike a remove + add of the same id, which needs {@link #resendLater}. False when there is no
     * other id to switch to (no twin: the buff's shimmer).
     */
    private static boolean swap(Ref<EntityStore> ref, Tracked t, EffectControllerComponent effects, Store<EntityStore> store) {
        EntityEffect next;
        int nextIndex;
        if (t.index != t.effectIndex) {
            // On the twin or the appearing look: back to the lasting one.
            next = t.effect;
            nextIndex = t.effectIndex;
        } else if (t.twin != null) {
            next = t.twin;
            nextIndex = t.twinIndex;
        } else {
            return false;
        }
        effects.removeEffect(ref, t.index, RemovalBehavior.COMPLETE, store);
        if (!effects.addEffect(ref, next, store)) {
            TRACKED.remove(ref, t);
            return true;
        }
        t.index = nextIndex;
        t.seen = effects.getActiveEffectIndexes().clone();
        t.seenLeft = remaining(effects, t.seen);
        t.touched = false;
        return true;
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
            t.seenLeft = remaining(effects, t.seen);
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

    /** Debug: which effects made the guardian re-send (gone, renewed, or our own code touched it). */
    private static String why(Tracked t, int[] now, float[] left) {
        StringBuilder out = new StringBuilder();
        if (t.touched) out.append("touched ");
        if (t.seen != null) {
            for (int i = 0; i < t.seen.length; i++) {
                int j = indexOf(now, t.seen[i]);
                if (j < 0) out.append("gone ").append(idOf(t.seen[i])).append(' ');
                else if (t.seenLeft != null && t.seenLeft[i] >= 0 && left[j] > t.seenLeft[i] + 0.001f && visual(t.seen[i])) {
                    out.append("renewed ").append(idOf(t.seen[i])).append(' ');
                }
            }
        }
        return out.toString().trim();
    }

    private static int indexOf(int[] indexes, int value) {
        for (int i = 0; i < indexes.length; i++) {
            if (indexes[i] == value) return i;
        }
        return -1;
    }

    private static String idOf(int index) {
        EntityEffect e = EntityEffect.getAssetMap().getAsset(index);
        return e != null ? e.getId() : String.valueOf(index);
    }

    /** Remaining seconds of each effect in {@code indexes} (-1 for infinite ones). */
    private static float[] remaining(EffectControllerComponent effects, int[] indexes) {
        var active = effects.getActiveEffects();
        float[] out = new float[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            var e = active.get(indexes[i]);
            out[i] = e == null || e.isInfinite() ? -1f : e.getRemainingDuration();
        }
        return out;
    }

    /**
     * True when the effect shows something on the client (tint, particles, ModelVFX, model). Only those count when
     * renewed: Skeleton roles refresh the invisible Hit_Interrupt_Eligible every 0.25 s even idle, and re-sending the
     * look for each restarted the shimmer's veins four times a second (user, 2026-10-12, debugLog).
     */
    private static boolean visual(int index) {
        EntityEffect e = EntityEffect.getAssetMap().getAsset(index);
        return e != null && (e.getApplicationEffects() != null || e.getModelOverride() != null || e.getModelChange() != null);
    }

    /** True when a visual effect active at both passes has more time left now than before (it was renewed). */
    private static boolean renewed(int[] before, float[] beforeLeft, int[] now, float[] nowLeft) {
        if (beforeLeft == null) return false;
        for (int j = 0; j < now.length; j++) {
            if (nowLeft[j] < 0 || !visual(now[j])) continue;
            for (int i = 0; i < before.length; i++) {
                if (before[i] == now[j]) {
                    if (beforeLeft[i] >= 0 && nowLeft[j] > beforeLeft[i] + 0.001f) return true;
                    break;
                }
            }
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
