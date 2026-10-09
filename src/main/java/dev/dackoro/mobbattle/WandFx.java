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
    static final String CHARM = "Charm";
    static final String RAGE = "Rage";
    static final String LONE = "Lone";
    static final String BRAWL = "Brawl";
    static final String MORPH = "Morph";
    static final String CLEANSE = "Cleanse";
    static final String EFFECT = "Effect";
    static final String COPY = "Copy";
    static final String UNMORPH = "Unmorph";
    static final String SUMMON_ADD = "SummonAdd";
    static final String SUMMON_REMOVE = "SummonRemove";
    static final String FAIL = "Fail";
    static final String UNSELECT = "Unselect";
    static final String ARMY_ADD = "ArmyAdd";
    static final String ARMY_REMOVE = "ArmyRemove";
    /** Floating marker over the lone target (no tint: not in ALL_FX, so new wand tints keep it). */
    static final String LONE_MARK = "LoneMark";
    /** Every wand effect except SOUL_RIP, removed before a new one is put on (their tints would mix). */
    private static final String[] ALL_FX = {HEAL, CHARM, RAGE, LONE, BRAWL, MORPH, CLEANSE, EFFECT,
            COPY, UNMORPH, SUMMON_ADD, SUMMON_REMOVE, FAIL, UNSELECT, ARMY_ADD, ARMY_REMOVE};

    static final String SUMMON_PARTICLE = "Praetorian_Summon_Spawn";
    static final String SUMMON_SOUND = "SFX_Eye_Void_Attack_Summon";

    /** Effects that have no tint: they only restart themselves instead of clearing the other wand tints. */
    private static final java.util.Set<String> TINTLESS = java.util.Set.of(UNMORPH, EFFECT, LONE_MARK);

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
        EffectControllerComponent effects = buffer.getComponent(target, EffectControllerComponent.getComponentType());
        String id = "MobBattle_Fx_" + fx;
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(id);
        if (effects != null && effect != null) {
            // Overwrite on an effect still running only refreshes its time: the client does not replay its
            // particles (a quick second click showed nothing). Remove it first so it starts again.
            // Two tints at once mix (e.g. green + red = a washed-out white): remove every other wand tint
            // first, so only the newest one shows (tintless ones, e.g. Unmorph on top of Cleanse, only restart
            // themselves). The soul rip (pending kill) is never replaced.
            boolean tinted = !TINTLESS.contains(fx);
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

    /** Kills {@code target} now, or after /mobbattle killdelay with the soul rip effect (NPCs only). */
    static void kill(Ref<EntityStore> target, Ref<EntityStore> killer, boolean npc, CommandBuffer<EntityStore> buffer) {
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
        for (Map.Entry<Ref<EntityStore>, PlayerKill> e : PLAYER_KILLS.entrySet()) {
            Ref<EntityStore> ref = e.getKey();
            if (!ref.isValid()) {
                PLAYER_KILLS.remove(ref);
                continue;
            }
            if (ref.getStore() != store || now < e.getValue().dueAt() || !PLAYER_KILLS.remove(ref, e.getValue())) {
                continue;
            }
            // Visible again before respawn: drop the disintegration, then a normal death.
            var effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
            int rip = EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + SOUL_RIP);
            if (effects != null && rip >= 0 && effects.hasEffect(rip)) {
                effects.removeEffect(ref, rip, store);
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
