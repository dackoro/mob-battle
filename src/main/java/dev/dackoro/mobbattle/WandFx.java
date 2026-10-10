package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a wand does to its target when the action succeeds (/mobbattle wandfx): a short entity effect
 * {@code MobBattle_Fx_<name>} (Server/Entity/Effects/MobBattle/Fx: particles on the mob, tint, sound), or a
 * world particle burst. The player's own cast animation and sound are plain JSON "Effects" on each wand's
 * interactions. Also the Kill Wand's delayed death (/mobbattle killdelay), run by FightTickSystem.
 */
final class WandFx {

    static final String HEAL = "Heal";
    static final String SOUL_RIP = "SoulRip";
    /** Snap Wand: the mob turns to ash (light, so dark Void mobs show it too), slower than the soul rip. */
    static final String SNAP_DUST = "SnapDust";
    static final int SNAP_DUST_MS = 1500;
    static final String CHARM = "Charm";
    static final String RAGE = "Rage";
    static final String LONE = "Lone";
    static final String BRAWL = "Brawl";
    static final String MORPH = "Morph";
    /** Cleanse Wand look in a palette color (/mobbattle cleanseColor): MobBattle_Fx_Cleanse_<Color>. */
    static final String CLEANSE = "Cleanse_";
    static final String EFFECT = "Effect";
    static final String COPY = "Copy";
    static final String UNMORPH = "Unmorph";
    static final String SUMMON_ADD = "SummonAdd";
    static final String SUMMON_REMOVE = "SummonRemove";
    static final String FAIL = "Fail";
    static final String UNSELECT = "Unselect";
    /** Unselect without its sound (Buff Wand right click plays the sound itself, see BuffTakeInteraction). */
    static final String UNSELECT_QUIET = "UnselectQuiet";
    static final String ARMY_ADD = "ArmyAdd";
    static final String ARMY_REMOVE = "ArmyRemove";
    /**
     * Crown over the lone target, 20 s (/mobbattle crown): MobBattle_Fx_Crown_<Gold|Color>. Buffed mobs wear
     * CrownBuff_<Color> until their buff goes (Buffs). No tint, not in ALL_FX: new wand tints keep them.
     */
    static final String CROWN = "Crown_";
    /** Every wand effect except SOUL_RIP, removed before a new one is put on (their tints would mix). */
    /** Swirl (remolino) in a palette color, no tint: MobBattle_Fx_Swirl_<Color> (Team Wand pick). */
    static final String SWIRL = "Swirl_";
    /** Battle mark in a palette color (/mobbattle mobFx): MobBattle_Fx_Mark_<Color>. */
    static final String MARK = "Mark_";
    private static final String[] ALL_FX = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(HEAL, CHARM, RAGE, LONE, BRAWL, MORPH, EFFECT,
                    COPY, UNMORPH, SUMMON_ADD, SUMMON_REMOVE, FAIL, UNSELECT, UNSELECT_QUIET, ARMY_ADD, ARMY_REMOVE),
            Teams.PALETTE.keySet().stream().flatMap(c -> java.util.stream.Stream.of(MARK + c, CLEANSE + c)))
            .toArray(String[]::new);

    /** The Cleanse Wand look in the current cleanseColor. */
    static String cleanse() {
        return CLEANSE + FxSettings.cleanseColor;
    }

    /** The lone target's crown in the current crown color, or null when off. */
    static String crown() {
        return "off".equals(ActionFx.crown) ? null : CROWN + ActionFx.crown;
    }

    static final String SUMMON_PARTICLE = "Praetorian_Summon_Spawn";
    /** Summon Wand right click, played at the player when the mobs appear. Vanilla magic sound until we have our own. */
    static final String SUMMON_SOUND = "SFX_Divine_Respawn";
    /**
     * Chaos Wand (battle royale and everyone vs one): the ritual circle opening at the area center when the
     * countdown starts. Vanilla portal sound until we have our own.
     */
    static final String CHAOS_SOUND = "SFX_Portal_Neutral_Open";

    /** Effects that have no tint: they only restart themselves instead of clearing the other wand tints. */
    private static final java.util.Set<String> TINTLESS = java.util.Set.of(UNMORPH, EFFECT);

    /** Mob -> time its soul rip ends (then it is removed). */
    private static final Map<Ref<EntityStore>, Long> KILLS = new ConcurrentHashMap<>();

    private WandFx() {
    }

    /** Puts the short {@code MobBattle_Fx_<fx>} effect on {@code target} (no-op with wandfx off). */
    static void onTarget(Ref<EntityStore> target, String fx, ComponentAccessor<EntityStore> buffer) {
        onTarget(target, fx, 0f, buffer);
    }

    /** Same, lasting at least {@code minSeconds} (e.g. the whole countdown and its camera shot). */
    static void onTarget(Ref<EntityStore> target, String fx, float minSeconds, ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.wandFx || target == null || !target.isValid()) {
            return;
        }
        // A mob already disintegrating (Kill Wand soul rip, Snap ash) keeps that look: any newer tint (e.g. the
        // duel's Charm, a battle mark) would show over the black/ash and hide it until it is removed.
        if (!SOUL_RIP.equals(fx) && !SNAP_DUST.equals(fx) && (isDying(target) || PLAYER_KILLS.containsKey(target))) {
            return;
        }
        EffectControllerComponent effects = buffer.getComponent(target, EffectControllerComponent.getComponentType());
        String id = "MobBattle_Fx_" + fx;
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(id);
        if (effects != null && effect != null) {
            // Overwrite on an effect still running only refreshes its time: the client does not replay its
            // particles (a quick second click showed nothing). Remove it first so it starts again.
            // Two tints at once mix (e.g. green + red = a washed-out white): remove every other wand tint
            // first, so only the newest one shows (tintless ones, e.g. Unmorph on top of Cleanse, only restart
            // themselves). The soul rip (pending kill) is never replaced.
            boolean tinted = !TINTLESS.contains(fx) && !fx.startsWith(SWIRL) && !fx.startsWith("Crown");
            for (String other : ALL_FX) {
                if (!tinted && !other.equals(fx)) continue;
                int index = EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + other);
                if (index >= 0 && effects.hasEffect(index)) {
                    // COMPLETE: the asset's own removal behavior could only shorten it (DURATION), leaving
                    // both tints on for a moment.
                    effects.removeEffect(target, index,
                            com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior.COMPLETE, buffer);
                }
            }
            if (minSeconds > effect.getDuration()) {
                effects.addEffect(target, effect, minSeconds,
                        com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior.OVERWRITE, buffer);
            } else {
                effects.addEffect(target, effect, buffer);
            }
        }
    }

    /** Gap between taking a running swirl off and putting it on again, so the client sees a new one. */
    private static final long SWIRL_RESTART_MS = 60;

    /**
     * The swirl effect (Swirl_&lt;color&gt;, follows the mob) on every click. A swirl still running (1 s) only
     * refreshes its time on a new add, and a remove plus add in the same tick shows nothing either, so a running one
     * is taken off now and put on again a moment later. Only the swirl: other wand effects are untouched.
     */
    static void swirlNow(Ref<EntityStore> target, String color, ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.wandFx || target == null || !target.isValid()) return;
        String fx = SWIRL + color;
        EffectControllerComponent effects = buffer.getComponent(target, EffectControllerComponent.getComponentType());
        int index = EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + fx);
        if (effects == null || index < 0 || !effects.hasEffect(index)) {
            onTarget(target, fx, buffer);
            return;
        }
        effects.removeEffect(target, index,
                com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior.COMPLETE, buffer);
        com.hypixel.hytale.component.Store<EntityStore> store = target.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (!store.isShutdown() && target.isValid()) onTarget(target, fx, store);
        }), SWIRL_RESTART_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Time for a size change (EntityScaleComponent) to reach the client before particles go on the mob. */
    static final long MODEL_SETTLE_MS = 200;

    /**
     * {@link #swirlNow} once a size change made in this tick has reached the client: a new scale rebuilds the mob
     * there and drops the particles already on it (Buff Wand give/take changed the size and lost the swirl).
     */
    static void swirlLater(Ref<EntityStore> target, String color) {
        if (!FxSettings.wandFx || target == null || !target.isValid()) return;
        com.hypixel.hytale.component.Store<EntityStore> store = target.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (!store.isShutdown() && target.isValid()) swirlNow(target, color, store);
        }), MODEL_SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** The swirl in the palette color {@code hex} (#RRGGBB of Teams.PALETTE); nothing for other colors. */
    static void swirl(Ref<EntityStore> target, String hex, ComponentAccessor<EntityStore> buffer) {
        for (var e : Teams.PALETTE.entrySet()) {
            if (e.getValue().equalsIgnoreCase(hex)) {
                onTarget(target, SWIRL + e.getKey(), buffer);
                return;
            }
        }
    }

    /** Puts wand effect {@code fx} on the entity as it is (no tint clearing, no restart). World thread. */
    static void addFx(Ref<EntityStore> target, String fx, ComponentAccessor<EntityStore> accessor) {
        EffectControllerComponent effects = accessor.getComponent(target, EffectControllerComponent.getComponentType());
        EntityEffect effect = EntityEffect.getAssetMap().getAsset("MobBattle_Fx_" + fx);
        if (effects != null && effect != null) effects.addEffect(target, effect, accessor);
    }

    /** Takes wand effect {@code fx} off the entity if it has it. World thread. */
    static void removeFx(Ref<EntityStore> target, String fx, ComponentAccessor<EntityStore> accessor) {
        EffectControllerComponent effects = accessor.getComponent(target, EffectControllerComponent.getComponentType());
        int index = EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + fx);
        if (effects != null && index >= 0 && effects.hasEffect(index)) {
            effects.removeEffect(target, index,
                    com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior.COMPLETE, accessor);
        }
    }

    /** Removes the lone target crown (any color) from those entities that have it, on their world thread. */
    static void removeCrownsLater(java.util.List<Ref<EntityStore>> entities) {
        java.util.List<Integer> indexes = new java.util.ArrayList<>();
        for (String c : java.util.stream.Stream.concat(java.util.stream.Stream.of("Gold"), Teams.PALETTE.keySet().stream()).toList()) {
            int index = EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + CROWN + c);
            if (index >= 0) indexes.add(index);
        }
        if (indexes.isEmpty()) return;
        // One task per world, not one per entity (an everyone vs one can hold 200 mobs).
        Map<com.hypixel.hytale.component.Store<EntityStore>, java.util.List<Ref<EntityStore>>> byStore = new java.util.HashMap<>();
        for (Ref<EntityStore> ref : entities) {
            if (ref == null || !ref.isValid() || ref.getStore().isShutdown()) continue;
            byStore.computeIfAbsent(ref.getStore(), k -> new java.util.ArrayList<>()).add(ref);
        }
        byStore.forEach((store, refs) -> store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown()) return;
            for (Ref<EntityStore> ref : refs) {
                if (!ref.isValid()) continue;
                // Picked again meanwhile (the lone target of a new everyone vs one): it keeps its new crown.
                BattleManager.Battle now = BattleManager.battleOf(ref);
                if (now != null && now.kind == BattleManager.Kind.ALL_VS_ONE && now.team(ref) == 1) continue;
                EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
                if (effects == null) continue;
                for (int index : indexes) if (effects.hasEffect(index)) effects.removeEffect(ref, index, store);
            }
        }));
    }

    /** A one-shot particle system and sound at {@code pos} (either may be null). */
    static void burst(String particleId, String soundId, Vector3d pos, ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.wandFx || pos == null) {
            return;
        }
        if (particleId != null) {
            ParticleUtil.spawnParticleEffect(particleId, pos, buffer);
        }
        if (soundId != null) {
            int sound = SoundEvent.getAssetMap().getIndex(soundId);
            if (sound >= 0) {
                SoundUtil.playSoundEvent3d(sound, SoundCategory.SFX, pos.x, pos.y, pos.z, buffer);
            }
        }
    }

    // ---- Kill Wand: soul rip, then death ----

    /** The Kill Wand's sound when its effect lands, at the player (Common/Sounds/MobBattle/Hakai.ogg, random pitch). */
    static final String HAKAI_SOUND = "SFX_MobBattle_Hakai";

    /** Snap: played at the player when the snap is activated (Common/Sounds/MobBattle/Snap.ogg, random pitch). */
    static final String SNAP_SOUND = "SFX_MobBattle_Snap";

    /** Snap: played at each mob or player that fades to dust (Common/Sounds/MobBattle/SnapFade.ogg, random pitch). */
    static final String SNAP_FADE_SOUND = "SFX_MobBattle_SnapFade";

    /** Kills {@code target} now, or after /mobbattle killdelay with the soul rip effect (NPCs only). */
    /** Snap Wand: an NPC turns to ash over {@link #SNAP_DUST_MS}, then is removed (loot per killLoot). */
    static void snapKill(Ref<EntityStore> target, CommandBuffer<EntityStore> buffer) {
        if (!FxSettings.wandFx) {
            KillInteraction.killNow(target, null, buffer);
            return;
        }
        onTarget(target, SNAP_DUST, buffer);
        if (FxSettings.debug) {
            // Why a mob might not show the ash: no effect controller, or other effects (spawn VFX...) on it.
            EffectControllerComponent fx = buffer.getComponent(target, EffectControllerComponent.getComponentType());
            StringBuilder active = new StringBuilder();
            if (fx != null) {
                for (int i : fx.getActiveEffectIndexes()) {
                    var e = EntityEffect.getAssetMap().getAsset(i);
                    active.append(e != null ? e.getId() : String.valueOf(i)).append(' ');
                }
            }
            MobBattlePlugin.debug("snap dust on " + ChaosWand.nameOf(target, buffer) + ": effect controller "
                    + (fx != null ? "yes, active [" + active.toString().trim() + "]" : "NO"));
        }
        burst(null, SNAP_FADE_SOUND, ChaosWand.positionOf(target, buffer), buffer);
        deathSound(target, buffer);
        KILLS.put(target, System.currentTimeMillis() + SNAP_DUST_MS);
        ashDrift(target, buffer);
    }

    /** Snap: the ash cloud left behind, carried off by the wind (Server/Particles/MobBattle/MobBattle_Ash_Drift). */
    static final String ASH_DRIFT = "MobBattle_Ash_Drift";
    /** It starts this long before the mob is gone, so the cloud is already rising while the last of the body crumbles
     * (the particles take a moment to build up: at 500 ms it only showed once the mob was gone). */
    private static final long ASH_LEAD_MS = 900;

    /** Spawns the ash cloud at {@code target}'s position (re-read then) shortly before it is removed. */
    static void ashDrift(Ref<EntityStore> target, ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.wandFx) return;
        var store = target.getStore();
        var world = buffer.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown() || !target.isValid()) return;
            burst(ASH_DRIFT, null, ChaosWand.positionOf(target, store), store);
        }), Math.max(0, SNAP_DUST_MS - ASH_LEAD_MS), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static void kill(Ref<EntityStore> target, Ref<EntityStore> killer, boolean npc, ComponentAccessor<EntityStore> buffer) {
        // The wand's own sound, from the player, when its effect lands (the mob keeps its own death sound).
        if (killer != null && killer.isValid()) {
            burst(null, HAKAI_SOUND, ChaosWand.positionOf(killer, buffer), buffer);
        }
        boolean player = !npc && FxSettings.killPlayers
                && buffer.getComponent(target, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()) != null;
        if (!FxSettings.wandFx || FxSettings.killDelayMs <= 0 || (!npc && !player)) {
            KillInteraction.killNow(target, killer, buffer);
            return;
        }
        onTarget(target, SOUL_RIP, buffer);
        deathSound(target, buffer);
        long dueAt = System.currentTimeMillis() + FxSettings.killDelayMs;
        if (player) {
            PLAYER_KILLS.put(target, new PlayerKill(dueAt, killer));
        } else {
            KILLS.put(target, dueAt);
        }
        soulStream(target, killer, buffer);
    }

    /**
     * /mobbattle killPlayers on: players also disintegrate, then die a normal death (the world's own rules
     * decide what they drop). Run by BattleLifecycleSystem, which ticks every world (FightTickSystem only
     * ticks NPCs).
     */
    private record PlayerKill(long dueAt, Ref<EntityStore> killer) {
    }

    private static final Map<Ref<EntityStore>, PlayerKill> PLAYER_KILLS = new ConcurrentHashMap<>();

    static void applyPlayerKills(com.hypixel.hytale.component.Store<EntityStore> store, long now) {
        if (PLAYER_KILLS.isEmpty()) {
            return;
        }
        java.util.List<Map.Entry<Ref<EntityStore>, PlayerKill>> due = new java.util.ArrayList<>();
        for (Map.Entry<Ref<EntityStore>, PlayerKill> e : PLAYER_KILLS.entrySet()) {
            Ref<EntityStore> ref = e.getKey();
            if (!ref.isValid()) {
                PLAYER_KILLS.remove(ref);
                continue;
            }
            if (ref.getStore() == store && now >= e.getValue().dueAt() && PLAYER_KILLS.remove(ref, e.getValue())) {
                due.add(e);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        // Called from BattleLifecycleSystem.tick: damage and effect changes go outside the system iteration,
        // on the world thread (as Arena.tick and Army.follow do).
        store.getExternalData().getWorld().execute(() -> killPlayers(store, due));
    }

    private static void killPlayers(com.hypixel.hytale.component.Store<EntityStore> store,
                                    java.util.List<Map.Entry<Ref<EntityStore>, PlayerKill>> due) {
        for (Map.Entry<Ref<EntityStore>, PlayerKill> e : due) {
            Ref<EntityStore> ref = e.getKey();
            if (!ref.isValid() || ref.getStore() != store) {
                continue;
            }
            // Visible again before respawn: drop the disintegration, then a normal death.
            var effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
            for (String fx : new String[] {SOUL_RIP, SNAP_DUST}) {
                int index = EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + fx);
                if (effects != null && index >= 0 && effects.hasEffect(index)) {
                    effects.removeEffect(ref, index, store);
                }
            }
            KillInteraction.killNow(ref, e.getValue().killer(), store);
        }
    }

    /** Soul wisps travelling from the mob to the wand during the kill delay (positions re-read every step). */
    static final String SOUL_PARTICLE = "MobBattle_Soul_Absorb";
    private static final long SOUL_STEP_MS = 40;

    private static void soulStream(Ref<EntityStore> target, Ref<EntityStore> killer, ComponentAccessor<EntityStore> buffer) {
        if (killer == null) {
            return;
        }
        var world = buffer.getExternalData().getWorld();
        Vector3d initial = ChaosWand.positionOf(target, buffer);
        if (initial == null) return;
        Vector3d initialFrom = new Vector3d(initial).add(0, 1, 0);
        int duration = FxSettings.killDelayMs;
        int steps = (int) Math.max(3, (duration + SOUL_STEP_MS - 1) / SOUL_STEP_MS);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
                if (!FxSettings.wandFx || !killer.isValid() || killer.getStore() != world.getEntityStore().getStore()) {
                    return;
                }
                var store = world.getEntityStore().getStore();
                Vector3d from = target.isValid() ? ChaosWand.positionOf(target, store) : null;
                Vector3d to = ChaosWand.positionOf(killer, store);
                if (to == null) {
                    return;
                }
                from = from == null ? new Vector3d(initialFrom) : new Vector3d(from).add(0, 1, 0);
                // Approximation: the server does not expose the animated hand's client-side bone transform.
                var head = store.getComponent(killer, com.hypixel.hytale.server.core.modules.entity.component.HeadRotation.getComponentType());
                Vector3d forward = head != null ? new Vector3d(head.getDirection()) : new Vector3d(0, 0, 1);
                Vector3d right = new Vector3d(forward).cross(0, 1, 0);
                if (right.lengthSquared() < 0.0001) right.set(1, 0, 0);
                else right.normalize();
                to = new Vector3d(to).add(0, 1.35, 0).fma(0.85, forward).fma(0.25, right);
                Vector3d p = soulPoint(from, to, t);
                float scale = (float) (0.75 - 0.55 * t);
                com.hypixel.hytale.server.core.universe.world.PlayerUtil.broadcastPacketToPlayers(store,
                        new com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem(SOUL_PARTICLE,
                                new com.hypixel.hytale.protocol.Position(p.x, p.y, p.z), null, scale, null, 0.2f));
            }), Math.round(t * duration), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** Small spiral and upward arc, both converging exactly onto the moving destination. */
    static Vector3d soulPoint(Vector3d from, Vector3d to, double t) {
        double envelope = Math.sin(Math.PI * t);
        double angle = t * Math.PI * 4;
        return new Vector3d(from).lerp(to, t).add(
                Math.cos(angle) * 0.10 * envelope,
                0.18 * envelope,
                Math.sin(angle) * 0.10 * envelope);
    }

    /** Fallbacks when a mob's model has no death sound. */
    private static final String[] DEATH_SOUNDS = {
            "SFX_Spawn_Void_Death", "SFX_Skeleton_Death_1", "SFX_Fireball_Death", "SFX_Skeleton_Despawn_1"};

    /** The mob's own death sound (its model's "Death" animation set), else a random dark one. */
    static void deathSound(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.wandFx) {
            return;
        }
        String id = null;
        var mc = buffer.getComponent(mob, com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getComponentType());
        var sets = mc != null && mc.getModel() != null ? mc.getModel().getAnimationSetMap() : null;
        var death = sets != null ? sets.get("Death") : null;
        if (death != null && death.getAnimations() != null) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (var a : death.getAnimations()) {
                if (a != null && a.getSoundEventId() != null) ids.add(a.getSoundEventId());
            }
            if (!ids.isEmpty()) id = ids.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(ids.size()));
        }
        if (id == null) {
            id = DEATH_SOUNDS[java.util.concurrent.ThreadLocalRandom.current().nextInt(DEATH_SOUNDS.length)];
        }
        burst(null, id, ChaosWand.positionOf(mob, buffer), buffer);
    }

    /**
     * Snap Wand and timeUp kill/judge on a player fighter: they disintegrate ({@code fx}, e.g. SNAP_DUST or
     * SOUL_RIP, for {@code ms}) and then die a normal death, whatever killPlayers says (applyPlayerKills). With
     * wandFx off or no delay: dead at once.
     */
    static void killPlayer(Ref<EntityStore> target, String fx, long ms, ComponentAccessor<EntityStore> buffer) {
        if (target == null || !target.isValid() || PLAYER_KILLS.containsKey(target)) {
            return;
        }
        if (!FxSettings.wandFx || ms <= 0) {
            KillInteraction.killNow(target, null, buffer);
            return;
        }
        onTarget(target, fx, buffer);
        deathSound(target, buffer);
        PLAYER_KILLS.put(target, new PlayerKill(System.currentTimeMillis() + ms, null));
    }

    /** True while a player's disintegration runs (their death is pending). */
    static boolean isPlayerDying(Ref<EntityStore> target) {
        return PLAYER_KILLS.containsKey(target);
    }

    /** True while {@code target}'s soul rip runs (its death is pending). */
    static boolean isDying(Ref<EntityStore> target) {
        return KILLS.containsKey(target);
    }

    static boolean hasPendingKills() {
        return !KILLS.isEmpty();
    }

    /** From FightTickSystem: runs {@code self}'s pending death once its delay is over. */
    static void applyPendingKill(Ref<EntityStore> self, long now, CommandBuffer<EntityStore> buffer) {
        Long dueAt = KILLS.get(self);
        if (dueAt != null && now >= dueAt && KILLS.remove(self, dueAt) && self.isValid()) {
            // Already disintegrated: remove it instead of dying (no death animation), dropping its loot first
            // unless /mobbattle killLoot is off.
            if (FxSettings.killLoot) dropLoot(self, buffer);
            buffer.removeEntity(self, com.hypixel.hytale.component.RemoveReason.REMOVE);
        }
    }

    /**
     * The loot a normal death would drop, as vanilla NPCDamageSystems.DropDeathItems does it: the role's drop
     * list (ItemModule.getRandomItemDrops), plus its inventory when the role picks up items, 1 block above the
     * feet. Marks the role as dropped so nothing drops twice.
     */
    private static void dropLoot(Ref<EntityStore> mob, CommandBuffer<EntityStore> buffer) {
        var npc = buffer.getComponent(mob, com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
        var role = npc != null ? npc.getRole() : null;
        var transform = buffer.getComponent(mob,
                com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        if (role == null || transform == null || role.hasDroppedDeathItems()) {
            return;
        }
        role.setDeathItemsDropped();
        java.util.List<com.hypixel.hytale.server.core.inventory.ItemStack> items = new java.util.ArrayList<>();
        if (role.isPickupDropOnDeath()) {
            var storage = buffer.getComponent(mob,
                    com.hypixel.hytale.server.core.inventory.InventoryComponent.Storage.getComponentType());
            if (storage != null) items.addAll(storage.getInventory().dropAllItemStacks());
        }
        String dropList = role.getDropListId();
        var itemModule = com.hypixel.hytale.server.core.modules.item.ItemModule.get();
        if (dropList != null && itemModule.isEnabled()) {
            items.addAll(itemModule.getRandomItemDrops(dropList));
        }
        if (items.isEmpty()) {
            return;
        }
        var head = buffer.getComponent(mob,
                com.hypixel.hytale.server.core.modules.entity.component.HeadRotation.getComponentType());
        var rotation = head != null ? new com.hypixel.hytale.math.vector.Rotation3f(head.getRotation())
                : new com.hypixel.hytale.math.vector.Rotation3f(0f, 0f, 0f);
        Vector3d at = new Vector3d(transform.getPosition()).add(0, 1, 0);
        buffer.addEntities(com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.generateItemDrops(
                buffer, items, at, rotation), com.hypixel.hytale.component.AddReason.SPAWN);
    }

    /** From BattleManager.maintain: a Ref keeps its whole world alive. */
    static void prune() {
        KILLS.keySet().removeIf(npc -> !npc.isValid());
        PLAYER_KILLS.keySet().removeIf(p -> !p.isValid());
    }
}
