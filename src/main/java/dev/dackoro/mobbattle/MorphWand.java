package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Morph Wand: copy a mob's model (right click) and put it on other mobs (and players, /mobbattle morphPlayers)
 * (left click), or pick any model by ID with /mobbattle morph. Same mechanism the vanilla Potion_Morph_Frog effect uses
 * (a new ModelComponent; NPCSystems.ModelChangeSystem updates hitbox and movement), but it lasts
 * until restored. The mob keeps its role: AI, weapon, attacks and health.
 * <p>
 * Skins (2026-10-12, user: a copied player came out as the bare "Player" body): a player's look is a PlayerSkin, and
 * their model is built from it (vanilla CosmeticsModule.createModel(skin, scale), as /model reset does). A copy of an
 * entity with a PlayerSkinComponent (players, random-skin NPCs) keeps the skin; applying it puts that skin and its
 * model on the target. The tracker sends PlayerSkinUpdate for any entity with the component (EntityTrackerSystems
 * EntitySkin), so a mob shows it too.
 * <p>
 * Players' size: the client draws a player's body at the entity scale but puts their camera by the model's scale
 * (user, 2026-10-12: entity scale alone grew the body only, model scale alone the camera only). So a size buff on a
 * player sets both: Buffs keeps the entity scale and {@link #setPlayerSize} the model's.
 */
public final class MorphWand {

    /** A copied model: asset ID, scale, and the skin it wears (null: a plain model asset). */
    public record Copy(String modelId, float scale, PlayerSkin skin, String from) {
        public Copy(String modelId, float scale) {
            this(modelId, scale, null, null);
        }

        /** What chat calls it: "Dackoro's look" for a skin, else the model ID. */
        public String label() {
            return skin != null ? (from != null ? from + "'s look" : "a player look") : modelId;
        }

        boolean sameLook(Copy o) {
            return o != null && Objects.equals(modelId, o.modelId) && Objects.equals(skin, o.skin);
        }
    }

    /** What an entity had before its first morph: model and skin (null: none). */
    private record Original(Model model, PlayerSkin skin) {
    }

    /** Per player: the model the wand will apply. */
    private static final Map<UUID, Copy> COPIES = new ConcurrentHashMap<>();
    /** Morphed entity -> what it had before its first morph (for restore). */
    private static final Map<Ref<EntityStore>, Original> ORIGINALS = new HashMap<>();
    /** Morphed entity -> the copy it wears now (a player's size buff rebuilds it at the new scale). */
    private static final Map<Ref<EntityStore>, Copy> MORPHS = new HashMap<>();
    /** Players with a size buff -> its factor, carried in their model's scale. */
    private static final Map<Ref<EntityStore>, Float> PLAYER_SIZE = new HashMap<>();

    private MorphWand() {
    }

    /** Drops entries of removed entities (BattleManager.maintain): each Ref keeps its whole world reachable. */
    static synchronized void prune() {
        ORIGINALS.keySet().removeIf(r -> !r.isValid());
        MORPHS.keySet().removeIf(r -> !r.isValid());
        PLAYER_SIZE.keySet().removeIf(r -> !r.isValid());
    }

    /** RoleChange: the same mob under a new Ref keeps its morph (Cleanse and morph reset can still undo it). */
    static synchronized void carry(Ref<EntityStore> old, Ref<EntityStore> neu) {
        Original o = ORIGINALS.remove(old);
        if (o != null) ORIGINALS.put(neu, o);
        Copy c = MORPHS.remove(old);
        if (c != null) MORPHS.put(neu, c);
    }

    /** A player's size buff factor (1 = none), carried in their model's scale. */
    static synchronized float playerSize(Ref<EntityStore> entity) {
        return PLAYER_SIZE.getOrDefault(entity, 1f);
    }

    public static Copy copyOf(UUID player) {
        return COPIES.get(player);
    }

    public static void setCopy(UUID player, Copy copy) {
        COPIES.put(player, copy);
        MorphHologram.refresh(player);
    }

    /** Whether the entity wears a Morph Wand copy now (its model is not its own). */
    static synchronized boolean wearsMorph(Ref<EntityStore> entity) {
        return MORPHS.containsKey(entity);
    }

    /** Model asset ID matching ignoring case, or null. */
    public static String findModel(String name) {
        if (name == null) {
            return null;
        }
        var map = ModelAsset.getAssetMap().getAssetMap();
        if (map.containsKey(name)) {
            return name;
        }
        for (String id : map.keySet()) {
            if (id.equalsIgnoreCase(name)) {
                return id;
            }
        }
        return null;
    }

    /** Model IDs containing the text (ignoring case), sorted, at most {@code limit}. */
    public static List<String> search(String text, int limit) {
        String needle = text == null ? "" : text.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String id : ModelAsset.getAssetMap().getAssetMap().keySet()) {
            if (id.toLowerCase().contains(needle)) {
                out.add(id);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /**
     * The entity's current look as a copy, or null. A morphed entity gives the copy it wears; one with a skin of its
     * own (a player, a random-skin NPC) gives that skin. A player's size buff is not part of it.
     */
    public static synchronized Copy read(Ref<EntityStore> entity, ComponentAccessor<EntityStore> accessor) {
        Copy worn = MORPHS.get(entity);
        if (worn != null) {
            return worn;
        }
        ModelComponent mc = accessor.getComponent(entity, ModelComponent.getComponentType());
        if (mc == null || mc.getModel() == null || mc.getModel().getModelAssetId() == null) {
            return null;
        }
        float scale = mc.getModel().getScale() / PLAYER_SIZE.getOrDefault(entity, 1f);
        PlayerSkinComponent skin = accessor.getComponent(entity, PlayerSkinComponent.getComponentType());
        // A player under the frog potion or /model copies as that model, not as their own body (audit 2026-10-14);
        // random-skin NPCs keep copying as their skin.
        boolean ownBody = PLAYER_MODEL.equals(mc.getModel().getModelAssetId())
                || accessor.getComponent(entity, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()) == null;
        if (skin != null && skin.getPlayerSkin() != null && ownBody) {
            return new Copy(mc.getModel().getModelAssetId(), scale, skin.getPlayerSkin(), ChaosWand.nameOf(entity, accessor));
        }
        // The player body without a skin is not a look (a mob that wore a copied player look across a restart: the
        // skin is not saved with it). Copied, it made a player bald (user, 2026-10-13, Morph swap wave with
        // morphBurstSelf).
        if (PLAYER_MODEL.equals(mc.getModel().getModelAssetId())) return null;
        return new Copy(mc.getModel().getModelAssetId(), scale);
    }

    /** The model asset vanilla builds every skin on (CosmeticsModule.createModel, javap). */
    static final String PLAYER_MODEL = "Player";

    /**
     * A mob wearing the player body without a skin (it wore a copied player look when the server stopped: the model is
     * saved, the skin and our ORIGINALS are not) gets its role's own model back (NPCEntity.setAppearance with the
     * role's appearance). Roles whose own appearance is the player body (random-skin NPCs) are left alone. From
     * NPC load (LookLoadSystem) and Cleanse. Returns true if it fixed one. World thread.
     */
    static boolean fixBald(Ref<EntityStore> entity, ComponentAccessor<EntityStore> accessor) {
        if (!entity.isValid() || isPlayer(entity, accessor)) return false;
        ModelComponent mc = accessor.getComponent(entity, ModelComponent.getComponentType());
        if (mc == null || mc.getModel() == null || !PLAYER_MODEL.equals(mc.getModel().getModelAssetId())) return false;
        PlayerSkinComponent skin = accessor.getComponent(entity, PlayerSkinComponent.getComponentType());
        if (skin != null && skin.getPlayerSkin() != null) return false;
        com.hypixel.hytale.server.npc.entities.NPCEntity npc =
                accessor.getComponent(entity, com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
        String own = npc != null && npc.getRole() != null ? npc.getRole().getAppearanceName() : null;
        if (own == null || PLAYER_MODEL.equals(own)) return false;
        if (!com.hypixel.hytale.server.npc.entities.NPCEntity.setAppearance(entity, own, accessor)) return false;
        MobBattlePlugin.log("Morph: " + ChaosWand.nameOf(entity, accessor) + " had the bare player body (copied look lost in a restart), back to " + own);
        modelChanged(entity);
        return true;
    }

    /** The model the entity had before its first morph (read-only, for the Debug Wand), or null. */
    static synchronized Model originalOf(Ref<EntityStore> entity) {
        Original o = ORIGINALS.get(entity);
        return o != null ? o.model() : null;
    }

    /**
     * The copy's model at its scale times {@code factor} (a player's size buff), or null if its asset is gone or the game
     * refuses its copied skin (CosmeticsModule.createModel; callers check). Its boxes
     * scale with it, as vanilla expects: the hitbox is the model box times entity scale / model scale
     * (EntityScaleBoundingBoxSystem.resolveBoxScale, javap), so it ends up at the entity scale.
     */
    private static Model build(Copy copy, float factor) {
        float scale = (copy.scale() > 0 ? copy.scale() : 1f) * factor;
        if (copy.skin() != null) {
            return CosmeticsModule.get().createModel(copy.skin(), scale);
        }
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(copy.modelId());
        return asset != null ? Model.createScaledModel(asset, scale) : null;
    }

    /** A player's own look at {@code factor} (as vanilla /model reset with a scale); null for a skin the game refuses. */
    private static Model skinModel(PlayerSkin skin, float factor) {
        return CosmeticsModule.get().createModel(skin, factor);
    }

    private static boolean isPlayer(Ref<EntityStore> entity, ComponentAccessor<EntityStore> accessor) {
        return accessor.getComponent(entity, PlayerRef.getComponentType()) != null;
    }

    /** Puts the copy's look on the entity. Returns false if the model asset or the entity's model is missing. */
    public static synchronized boolean apply(Ref<EntityStore> entity, Copy copy, ComponentAccessor<EntityStore> accessor) {
        if (copy.skin() == null && PLAYER_MODEL.equals(copy.modelId())) return false; // a bald player body, see read
        ModelComponent current = accessor.getComponent(entity, ModelComponent.getComponentType());
        boolean player = isPlayer(entity, accessor);
        Model model = current != null ? build(copy, player ? PLAYER_SIZE.getOrDefault(entity, 1f) : 1f) : null;
        if (model == null) {
            return false;
        }
        PlayerSkinComponent skin = accessor.getComponent(entity, PlayerSkinComponent.getComponentType());
        ORIGINALS.putIfAbsent(entity, new Original(current.getModel(), skin != null ? skin.getPlayerSkin() : null));
        MORPHS.put(entity, copy);
        if (copy.skin() != null) {
            accessor.putComponent(entity, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(copy.skin()));
        } else if (skin != null && ORIGINALS.get(entity).skin() == null) {
            // From a copied player look to a plain model: the copied skin goes, or the client keeps drawing it.
            dropSkin(entity, accessor);
        }
        keepScaleRatio(entity, current.getModel(), model, accessor);
        putModel(entity, model, copy.skin() != null, accessor);
        modelChanged(entity);
        return true;
    }

    /**
     * A mob's entity scale (a size buff, builder tools) replaces its model's own scale when drawn: across a model swap
     * it keeps the same growth over the new model's size (audit 2026-10-17: a goblin morphed into a 2.5x Nightlord with
     * a size buff, then cleansed, stayed 2.5x big for good, the scale saved with it). Players: Buffs and setPlayerSize.
     */
    private static void keepScaleRatio(Ref<EntityStore> entity, Model from, Model to, ComponentAccessor<EntityStore> accessor) {
        if (from == null || to == null || isPlayer(entity, accessor)) return;
        var es = accessor.getComponent(entity,
                com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent.getComponentType());
        if (es == null) return;
        float a = from.getScale(), b = to.getScale();
        if (!(a > 0) || !(b > 0) || !Float.isFinite(a) || !Float.isFinite(b) || Math.abs(a - b) < 0.0001f) return;
        es.setScale(es.getScale() / a * b);
        Buffs.modelRescaled(entity, b);
    }

    /** Gives the entity back the look it had before its first morph. Returns false if it wasn't morphed. */
    public static synchronized boolean restore(Ref<EntityStore> entity, ComponentAccessor<EntityStore> accessor) {
        Original original = ORIGINALS.remove(entity);
        MORPHS.remove(entity);
        if (original == null || !entity.isValid()) {
            return original == null && entity.isValid() && fixBald(entity, accessor);
        }
        PlayerSkinComponent now = accessor.getComponent(entity, PlayerSkinComponent.getComponentType());
        if (original.skin() != null) {
            if (now == null || now.getPlayerSkin() != original.skin()) {
                accessor.putComponent(entity, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(original.skin()));
            }
        } else if (now != null) {
            // A copied skin on a mob that had none.
            dropSkin(entity, accessor);
        }
        // A player is rebuilt from their own skin at their size; anything else gets its saved model.
        Model model = original.skin() != null && isPlayer(entity, accessor)
                ? skinModel(original.skin(), PLAYER_SIZE.getOrDefault(entity, 1f))
                : original.model();
        // createModel is null for a skin the game no longer accepts (validateSkin, decompiled 0.7.2): the saved model.
        if (model == null) model = original.model();
        if (model == null) return true;
        ModelComponent before = accessor.getComponent(entity, ModelComponent.getComponentType());
        keepScaleRatio(entity, before != null ? before.getModel() : null, model, accessor);
        putModel(entity, model, original.skin() != null, accessor);
        modelChanged(entity);
        return true;
    }

    /**
     * Removes a copied skin from a mob. Vanilla only ever sends PlayerSkin updates (EntityTrackerSystems.EntitySkin,
     * javap), never a removal, so the client kept the old skin and the cleansed mob came out as a bare player body
     * (user, 2026-10-13). ❌ Sending the removal (EntityViewer.queueRemove PlayerSkin) crashed every client that saw it:
     * "Removing PlayerSkin component isn't supported!" (client log, 2026-10-13). So the clients get the mob again from
     * scratch instead ({@link SkinRespawnSystem}).
     */
    private static void dropSkin(Ref<EntityStore> entity, ComponentAccessor<EntityStore> accessor) {
        accessor.removeComponent(entity, PlayerSkinComponent.getComponentType());
        SkinRespawnSystem.respawn(entity);
    }

    /**
     * A player's size buff (Buffs): rebuilds their model at {@code factor}, so their camera grows with them. Their
     * morph if they wear one, else their own skin's model (as vanilla /model reset with a scale). Returns true if
     * their size changed. Does nothing for anything but players.
     */
    static synchronized boolean setPlayerSize(Ref<EntityStore> entity, float factor, ComponentAccessor<EntityStore> accessor) {
        if (!entity.isValid() || !isPlayer(entity, accessor)) {
            return false;
        }
        float before = PLAYER_SIZE.getOrDefault(entity, 1f);
        if (Math.abs(before - factor) < 0.001f) {
            return false;
        }
        Copy worn = MORPHS.get(entity);
        Model model;
        if (worn != null) {
            model = build(worn, factor);
        } else {
            PlayerSkinComponent skin = accessor.getComponent(entity, PlayerSkinComponent.getComponentType());
            model = skin != null && skin.getPlayerSkin() != null ? skinModel(skin.getPlayerSkin(), factor) : null;
        }
        if (model == null) {
            return false;
        }
        // Only once the model carries it: particleScale and lightHeight divide by it.
        if (Math.abs(factor - 1f) < 0.001f) PLAYER_SIZE.remove(entity);
        else PLAYER_SIZE.put(entity, factor);
        putModel(entity, model, worn == null || worn.skin() != null, accessor);
        return true;
    }

    /**
     * New model on the entity. One built from a skin ({@code fromSkin}) flags the skin so it is resent with the model
     * (as vanilla /model reset); a plain model asset on a player leaves their skin alone, as the Morph Wand always did.
     */
    private static void putModel(Ref<EntityStore> entity, Model model, boolean fromSkin, ComponentAccessor<EntityStore> accessor) {
        accessor.putComponent(entity, ModelComponent.getComponentType(), new ModelComponent(model));
        if (!fromSkin) return;
        PlayerSkinComponent skin = accessor.getComponent(entity, PlayerSkinComponent.getComponentType());
        if (skin != null) skin.setNetworkOutdated();
    }

    /**
     * The new model is rebuilt on the client, which drops the ModelVFX of a Jade/Shimmer look and the particles of the
     * buff look and crown: put them on again once the model reached the client (as after a size buff).
     */
    private static void modelChanged(Ref<EntityStore> entity) {
        LookKeeper.resized(entity);
        Buffs.modelChanged(entity);
    }

    /**
     * Morph Wand charged click (held, vanilla Charging interaction): a green shockwave from the player out to
     * {@code morphBurstRadius}. {@code shuffle} false (left hold): every mob in reach (and player, with morphPlayers,
     * never the caster) gets the caster's copied look. {@code shuffle} true (right hold): the looks of those in reach are
     * swapped in random pairs ("one for one"; an odd one out makes the last three trade in a ring). Cleanse Wand or
     * /mobbattle morph reset gives each its own look back. Returns how many changed look, or -1 if nothing could start.
     */
    static int burst(Ref<EntityStore> caster, PlayerRef player, boolean shuffle, ComponentAccessor<EntityStore> buffer) {
        org.joml.Vector3d center = ChaosWand.positionOf(caster, buffer);
        if (center == null) return -1;
        Copy copy = shuffle ? null : copyOf(player.getUuid());
        if (!shuffle && copy == null) {
            player.sendMessage(I18n.tr("chat.morph.nocopy").color("#ffcc55"));
            return -1;
        }
        return burstAt(center, caster, copy, shuffle, buffer).size();
    }

    /**
     * The wave itself, from {@code center}: {@code caster} may be null (a wave from a spot, /mobbattle test wave,
     * 2026-10-13); {@code copy} is the look to give (ignored with {@code shuffle}). Returns who changes look.
     */
    static List<Ref<EntityStore>> burstAt(org.joml.Vector3d center, Ref<EntityStore> caster, Copy copy, boolean shuffle,
                                          ComponentAccessor<EntityStore> buffer) {
        int radius = FxSettings.morphBurstRadius;
        List<Ref<EntityStore>> around = new ArrayList<>(ChaosWand.npcsAround(center, radius, buffer));
        if (FxSettings.morphPlayers) {
            // getAllEntitiesInSphere returns a thread-local list: read it right away.
            for (Ref<EntityStore> r : com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInSphere(center, radius, buffer)) {
                if (!r.equals(caster) && PlayerFighters.inWave(r, buffer)) around.add(r);
            }
        }
        // The caster asked to be in their own wave (morphBurstSelf), even with morphPlayers off.
        if (caster != null && FxSettings.morphBurstSelf && !around.contains(caster)) around.add(caster);
        around = WaveHit.filter(center, around, buffer);
        FightFx.shockwave(center, radius, Teams.PALETTE.get("Green"), buffer);
        WandFx.burst(null, "SFX_MobBattle_OmnitrixTransform", center, buffer);
        Map<Ref<EntityStore>, Copy> plan = new HashMap<>();
        if (!shuffle) {
            for (Ref<EntityStore> r : around) {
                if (r.isValid() && !copy.sameLook(read(r, buffer))) plan.put(r, copy);
            }
            land(center, radius, plan, buffer);
            return new ArrayList<>(plan.keySet());
        }
        // Shuffle: read every look first, then trade them in random pairs.
        List<Ref<EntityStore>> who = new ArrayList<>();
        List<Copy> looks = new ArrayList<>();
        for (Ref<EntityStore> r : around) {
            Copy c = r.isValid() ? read(r, buffer) : null;
            if (c != null) {
                who.add(r);
                looks.add(c);
            }
        }
        if (who.size() < 2) return List.of();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < who.size(); i++) order.add(i);
        java.util.Collections.shuffle(order, java.util.concurrent.ThreadLocalRandom.current());
        int[] gets = new int[who.size()];
        int n = order.size();
        int pairsEnd = n % 2 == 0 ? n : n - 3;
        for (int i = 0; i < pairsEnd; i += 2) {
            gets[order.get(i)] = order.get(i + 1);
            gets[order.get(i + 1)] = order.get(i);
        }
        if (n % 2 == 1) {
            // The last three trade in a ring: a gets b's look, b gets c's, c gets a's.
            int a = order.get(n - 3), b = order.get(n - 2), c = order.get(n - 1);
            gets[a] = b;
            gets[b] = c;
            gets[c] = a;
        }
        for (int i = 0; i < who.size(); i++) {
            Copy next = looks.get(gets[i]);
            if (!next.sameLook(looks.get(i))) plan.put(who.get(i), next);
        }
        land(center, radius, plan, buffer);
        return new ArrayList<>(plan.keySet());
    }

    /** Each planned look goes on as the wave reaches its mob (WaveHit: waveTravel / waveGroundOnly). */
    private static void land(org.joml.Vector3d center, double radius, Map<Ref<EntityStore>, Copy> plan,
                             ComponentAccessor<EntityStore> buffer) {
        WaveHit.hit(center, radius, new ArrayList<>(plan.keySet()), buffer, (group, acc) -> {
            for (Ref<EntityStore> r : group) morphWithFx(r, plan.get(r), acc);
        });
    }

    /** {@link #apply} plus the Morph Wand's look on the mob (green burst in the world, the model swap drops attached ones). */
    private static boolean morphWithFx(Ref<EntityStore> r, Copy copy, ComponentAccessor<EntityStore> buffer) {
        if (!apply(r, copy, buffer)) return false;
        WandFx.onTarget(r, WandFx.MORPH, buffer);
        org.joml.Vector3d at = ChaosWand.positionOf(r, buffer);
        if (at != null) WandFx.burst("MobBattle_Morph_Burst", null, at.add(0, 0.8, 0), buffer);
        return true;
    }

    /** Restores every morphed mob still loaded. Returns how many. */
    public static synchronized int restoreAll(ComponentAccessor<EntityStore> accessor) {
        int n = 0;
        for (Ref<EntityStore> entity : new ArrayList<>(ORIGINALS.keySet())) {
            if (!entity.isValid()) {
                ORIGINALS.remove(entity);
                MORPHS.remove(entity);
                continue;
            }
            // Instances are separate entity stores. Never restore one through another world's accessor.
            if (entity.getStore().getExternalData() != accessor.getExternalData()) {
                continue;
            }
            if (restore(entity, accessor)) {
                n++;
            }
        }
        return n;
    }
}
