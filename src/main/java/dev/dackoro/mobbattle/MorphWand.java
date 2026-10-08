package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Morph Wand: copy a mob's model (right click) and put it on other mobs (left click), or pick any
 * model by ID with /mobbattle morph. Same mechanism the vanilla Potion_Morph_Frog effect uses
 * (a new ModelComponent; NPCSystems.ModelChangeSystem updates hitbox and movement), but it lasts
 * until restored. The mob keeps its role: AI, weapon, attacks and health.
 */
public final class MorphWand {

    /** A copied model: asset ID and scale. */
    public record Copy(String modelId, float scale) {
    }

    /** Per player: the model the wand will apply. */
    private static final Map<UUID, Copy> COPIES = new ConcurrentHashMap<>();
    /** Morphed NPC -> model it had before its first morph (for restore). */
    private static final Map<Ref<EntityStore>, Model> ORIGINALS = new HashMap<>();

    private MorphWand() {
    }

    public static Copy copyOf(UUID player) {
        return COPIES.get(player);
    }

    public static void setCopy(UUID player, Copy copy) {
        COPIES.put(player, copy);
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

    /** The entity's current model as a copy, or null. */
    public static Copy read(Ref<EntityStore> entity, ComponentAccessor<EntityStore> accessor) {
        ModelComponent mc = accessor.getComponent(entity, ModelComponent.getComponentType());
        if (mc == null || mc.getModel() == null || mc.getModel().getModelAssetId() == null) {
            return null;
        }
        return new Copy(mc.getModel().getModelAssetId(), mc.getModel().getScale());
    }

    /** Puts the model on the entity. Returns false if the model asset or the entity's model is missing. */
    public static synchronized boolean apply(Ref<EntityStore> entity, Copy copy, ComponentAccessor<EntityStore> accessor) {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(copy.modelId());
        ModelComponent current = accessor.getComponent(entity, ModelComponent.getComponentType());
        if (asset == null || current == null) {
            return false;
        }
        ORIGINALS.keySet().removeIf(r -> !r.isValid());
        ORIGINALS.putIfAbsent(entity, current.getModel());
        float scale = copy.scale() > 0 ? copy.scale() : 1f;
        accessor.putComponent(entity, ModelComponent.getComponentType(),
                new ModelComponent(Model.createScaledModel(asset, scale)));
        return true;
    }

    /** Gives the entity back the model it had before its first morph. Returns false if it wasn't morphed. */
    public static synchronized boolean restore(Ref<EntityStore> entity, ComponentAccessor<EntityStore> accessor) {
        Model original = ORIGINALS.remove(entity);
        if (original == null || !entity.isValid()) {
            return false;
        }
        accessor.putComponent(entity, ModelComponent.getComponentType(), new ModelComponent(original));
        return true;
    }

    /** Restores every morphed mob still loaded. Returns how many. */
    public static synchronized int restoreAll(ComponentAccessor<EntityStore> accessor) {
        int n = 0;
        for (Ref<EntityStore> entity : new ArrayList<>(ORIGINALS.keySet())) {
            if (!entity.isValid()) {
                ORIGINALS.remove(entity);
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
