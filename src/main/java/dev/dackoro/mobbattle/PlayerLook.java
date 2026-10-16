package dev.dackoro.mobbattle;

import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPartTexture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A player's look as model attachments, built on the server from their PlayerSkin (2026-10-16, Morph Wand hologram).
 * <p>
 * Why: the game's player Model is only the bare "Player" body (CosmeticsModule.createModel, javap); the client puts the
 * clothes, hair and face on from the PlayerSkin. A Model with attachments of its own (the hologram panel) makes the
 * client show those instead of the skin's (user test: panel without clothes), and sending the skin again brings the
 * clothes back without the panel. So the panel goes in a Model that also carries every skin part as an attachment,
 * and only the model is sent. Same idea as Adventure time's sheath (feature/vaina-cosmetic), which worked in game;
 * this resolver is our own, written from the vanilla data (CosmeticRegistry, PlayerSkinPart, Cosmetics/CharacterCreator
 * JSON in Assets.zip).
 * <p>
 * A skin part is {@code "<part id>.<color>[.<variant>]"}. The variant (if the part has it) gives the model and
 * textures; the color is a gradient of the part's GradientSet (greyscale texture tinted by it) or, without a
 * GradientSet, the key of its textures. Hats: a FullyCovering head accessory hides the haircut, a HalfCovering one
 * swaps a haircut that RequiresGenericHaircut for the Generic one of its HairType (ids GenericShort/Medium/Long, as
 * HaircutFallbacks.json). ❓ The hat rules are read from the data, not from the client: untested in game.
 * Results are cached per skin (LRU), so equipping the wand again costs no registry lookups.
 */
final class PlayerLook {

    private static final int CACHE = 64;
    private static final Map<PlayerSkin, List<ModelAttachment>> BY_SKIN = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PlayerSkin, List<ModelAttachment>> eldest) {
            return size() > CACHE;
        }
    };

    private PlayerLook() {
    }

    /** Every part of this skin as attachments (body first), cached; empty when the cosmetics are not loaded. */
    static List<ModelAttachment> attachments(PlayerSkin skin) {
        if (skin == null) return List.of();
        synchronized (BY_SKIN) {
            List<ModelAttachment> cached = BY_SKIN.get(skin);
            if (cached != null) return cached;
        }
        List<ModelAttachment> built;
        try {
            built = List.copyOf(build(skin));
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Player look not built: " + e);
            return List.of();
        }
        synchronized (BY_SKIN) {
            BY_SKIN.put(skin, built);
        }
        return built;
    }

    private static List<ModelAttachment> build(PlayerSkin skin) {
        CosmeticRegistry reg = CosmeticsModule.get().getRegistry();
        List<ModelAttachment> out = new ArrayList<>();
        add(out, reg.getBodyCharacteristics(), skin.bodyCharacteristic);
        add(out, reg.getUnderwear(), skin.underwear);
        add(out, reg.getFaces(), skin.face);
        add(out, reg.getEyes(), skin.eyes);
        add(out, reg.getMouths(), skin.mouth);
        add(out, reg.getEyebrows(), skin.eyebrows);
        add(out, reg.getFacialHairs(), skin.facialHair);
        add(out, reg.getSkinFeatures(), skin.skinFeature);

        PlayerSkinPart hat = partOf(reg.getHeadAccessories(), skin.headAccessory);
        PlayerSkinPart.HeadAccessoryType hatType = hat != null ? hat.getHeadAccessoryType() : null;
        if (hatType != PlayerSkinPart.HeadAccessoryType.FullyCovering) {
            add(out, reg.getEars(), skin.ears);
            String haircut = skin.haircut;
            PlayerSkinPart hair = partOf(reg.getHaircuts(), haircut);
            if (hair != null && hatType == PlayerSkinPart.HeadAccessoryType.HalfCovering && hair.doesRequireGenericHaircut()
                    && hair.getHairType() != null) {
                // the hat squashes this haircut: the generic one of the same length, same color
                String[] bits = haircut.split("\\.");
                String generic = "Generic" + hair.getHairType().name();
                if (reg.getHaircuts().containsKey(generic)) haircut = generic + (bits.length > 1 ? "." + bits[1] : "");
            }
            add(out, reg.getHaircuts(), haircut);
            add(out, reg.getEarAccessories(), skin.earAccessory);
        }
        add(out, reg.getHeadAccessories(), skin.headAccessory);
        add(out, reg.getFaceAccessories(), skin.faceAccessory);
        add(out, reg.getUndertops(), skin.undertop);
        add(out, reg.getOvertops(), skin.overtop);
        add(out, reg.getPants(), skin.pants);
        add(out, reg.getOverpants(), skin.overpants);
        add(out, reg.getShoes(), skin.shoes);
        add(out, reg.getGloves(), skin.gloves);
        add(out, reg.getCapes(), skin.cape);
        return out;
    }

    private static PlayerSkinPart partOf(Map<String, PlayerSkinPart> parts, String value) {
        if (value == null || value.isEmpty() || parts == null) return null;
        return parts.get(value.split("\\.")[0]);
    }

    /** One part as an attachment; nothing when it is empty or unknown. */
    private static void add(List<ModelAttachment> out, Map<String, PlayerSkinPart> parts, String value) {
        PlayerSkinPart part = partOf(parts, value);
        if (part == null) return;
        String[] bits = value.split("\\.");
        String color = bits.length > 1 ? bits[1] : null;
        String model = part.getModel();
        String greyscale = part.getGreyscaleTexture();
        Map<String, PlayerSkinPartTexture> textures = part.getTextures();
        if (bits.length > 2 && part.getVariants() != null) {
            PlayerSkinPart.Variant v = part.getVariants().get(bits[2]);
            if (v != null) {
                if (v.getModel() != null) model = v.getModel();
                if (v.getGreyscaleTexture() != null) greyscale = v.getGreyscaleTexture();
                if (v.getTextures() != null) textures = v.getTextures();
            }
        } else if (model == null && part.getVariants() != null && !part.getVariants().isEmpty()) {
            // a part with only variants (capes) and none named: its first one
            PlayerSkinPart.Variant v = part.getVariants().values().iterator().next();
            model = v.getModel();
            if (v.getGreyscaleTexture() != null) greyscale = v.getGreyscaleTexture();
            if (v.getTextures() != null) textures = v.getTextures();
        }
        if (model == null || model.isEmpty()) return;
        String gradientSet = part.getGradientSet();
        if (gradientSet != null && greyscale != null) {
            out.add(new ModelAttachment(model, greyscale, gradientSet, color, 1.0));
            return;
        }
        PlayerSkinPartTexture tex = textures != null && color != null ? textures.get(color) : null;
        if (tex == null && textures != null && !textures.isEmpty()) tex = textures.values().iterator().next();
        String texture = tex != null ? tex.getTexture() : greyscale;
        if (texture == null) return;
        out.add(new ModelAttachment(model, texture, null, null, 1.0));
    }
}
