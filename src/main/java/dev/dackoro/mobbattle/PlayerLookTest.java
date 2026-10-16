package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinGradientSet;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * /mobbattle test playerlook [n] (dev tool, 2026-10-16, user: check PlayerLook against every cosmetic of the game
 * without trying them by hand). The game's own server model of a player is only the bare body (createModel), the client
 * dresses it, so there is no vanilla server result to compare with. What this checks instead, for every part of every
 * slot (each variant, each color) on a base skin, plus {@code n} random skins (CosmeticsModule.generateRandomSkin) and
 * the caller's own skin:
 * <ul>
 *   <li>the skin is valid for vanilla (CosmeticsModule.validateSkin), so the value format we build is the game's;</li>
 *   <li>PlayerLook gives one attachment per part shown (hat rules: FullyCovering hides ears, haircut, ear accessory);</li>
 *   <li>each attachment's model and texture exist as common assets;</li>
 *   <li>a tinted one names a gradient set that exists and a gradient id inside it (the white face had none).</li>
 * </ul>
 * Report: {@code <data dir>/tests/playerlook_<time>.txt}. What it can't see: how the client draws them (layering, a
 * hat's real hair rule, first person), only in game.
 */
final class PlayerLookTest {

    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";
    private static final int MAX_LINES = 400;

    /** Skin field -> its parts in the registry. */
    private static final Map<String, Function<CosmeticRegistry, Map<String, PlayerSkinPart>>> SLOTS = new LinkedHashMap<>();

    static {
        SLOTS.put("bodyCharacteristic", CosmeticRegistry::getBodyCharacteristics);
        SLOTS.put("underwear", CosmeticRegistry::getUnderwear);
        SLOTS.put("face", CosmeticRegistry::getFaces);
        SLOTS.put("eyes", CosmeticRegistry::getEyes);
        SLOTS.put("ears", CosmeticRegistry::getEars);
        SLOTS.put("mouth", CosmeticRegistry::getMouths);
        SLOTS.put("eyebrows", CosmeticRegistry::getEyebrows);
        SLOTS.put("facialHair", CosmeticRegistry::getFacialHairs);
        SLOTS.put("haircut", CosmeticRegistry::getHaircuts);
        SLOTS.put("skinFeature", CosmeticRegistry::getSkinFeatures);
        SLOTS.put("headAccessory", CosmeticRegistry::getHeadAccessories);
        SLOTS.put("faceAccessory", CosmeticRegistry::getFaceAccessories);
        SLOTS.put("earAccessory", CosmeticRegistry::getEarAccessories);
        SLOTS.put("undertop", CosmeticRegistry::getUndertops);
        SLOTS.put("overtop", CosmeticRegistry::getOvertops);
        SLOTS.put("pants", CosmeticRegistry::getPants);
        SLOTS.put("overpants", CosmeticRegistry::getOverpants);
        SLOTS.put("shoes", CosmeticRegistry::getShoes);
        SLOTS.put("gloves", CosmeticRegistry::getGloves);
        SLOTS.put("cape", CosmeticRegistry::getCapes);
    }

    /** Slots the skin names by id alone (vanilla validateSkin checks containsKey): they take the body's skin tone. */
    private static final List<String> ID_ONLY = List.of("face", "ears", "mouth");

    private PlayerLookTest() {
    }

    static void command(CommandContext ctx, String[] a) {
        int n = 200;
        if (a.length > 1) {
            try {
                n = Math.max(0, Math.min(100000, Integer.parseInt(a[1])));
            } catch (NumberFormatException e) {
                TestTools.say(ctx.sender(), "Usage: /mobbattle test playerlook [random skins, default 200]", ERR);
                return;
            }
        }
        int count = n;
        Ref<EntityStore> me = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (me == null || !me.isValid()) {
            CompletableFuture.runAsync(() -> run(ctx, count, null));
            return;
        }
        Store<EntityStore> store = me.getStore();
        store.getExternalData().getWorld().execute(() -> {
            PlayerSkinComponent c = me.isValid() ? store.getComponent(me, PlayerSkinComponent.getComponentType()) : null;
            PlayerSkin own = c != null ? c.getPlayerSkin() : null;
            CompletableFuture.runAsync(() -> run(ctx, count, own));
        });
    }

    private static final class Report {
        final List<String> lines = new ArrayList<>();
        int skins, vanillaRejects, problems, attachments;

        void line(String s) {
            if (lines.size() < MAX_LINES) lines.add(s);
            else if (lines.size() == MAX_LINES) lines.add("... (more lines left out)");
        }
    }

    private static void run(CommandContext ctx, int randomCount, PlayerSkin own) {
        try {
            long t0 = System.nanoTime();
            CosmeticRegistry reg = CosmeticsModule.get().getRegistry();
            Report r = new Report();
            List<String> head = new ArrayList<>();
            head.add("Mob Battle PlayerLook test " + LocalDateTime.now());
            if (own != null) {
                head.add("");
                head.add("== Your skin");
                for (Field f : PlayerSkin.class.getFields()) {
                    if (f.getType() == String.class) head.add("  " + f.getName() + " = " + f.get(own));
                }
                head.add("  attachments:");
                for (ModelAttachment att : PlayerLook.build(own)) head.add("    " + describe(att));
                check("your skin", own, reg, r);
            }
            PlayerSkin base = CosmeticsModule.get().generateRandomSkin(new Random(7));
            int swept = 0;
            for (Map.Entry<String, Function<CosmeticRegistry, Map<String, PlayerSkinPart>>> slot : SLOTS.entrySet()) {
                Map<String, PlayerSkinPart> parts = slot.getValue().apply(reg);
                if (parts == null) continue;
                Field field = PlayerSkin.class.getField(slot.getKey());
                for (PlayerSkinPart part : parts.values()) {
                    for (String value : valuesOf(slot.getKey(), part, reg)) {
                        PlayerSkin skin = new PlayerSkin(base);
                        // a hat would hide the part being checked: none while sweeping the head slots
                        if (!slot.getKey().equals("headAccessory")) skin.headAccessory = null;
                        field.set(skin, value);
                        check(slot.getKey() + "=" + value, skin, reg, r);
                        swept++;
                    }
                }
            }
            // the barest skin vanilla accepts (2026-10-16, user: a player with no cosmetics at all): every slot emptied
            // that validateSkin lets go empty, then each part of the slots left swept on it
            PlayerSkin minimal = new PlayerSkin(base);
            List<String> emptied = new ArrayList<>();
            for (String name : SLOTS.keySet()) {
                Field field = PlayerSkin.class.getField(name);
                Object was = field.get(minimal);
                if (was == null) continue;
                field.set(minimal, null);
                try {
                    CosmeticsModule.get().validateSkin(minimal);
                    emptied.add(name);
                } catch (Exception required) {
                    field.set(minimal, was);
                }
            }
            head.add("");
            head.add("== Minimal skin (emptied: " + String.join(", ", emptied) + ")");
            for (ModelAttachment att : PlayerLook.build(minimal)) head.add("    " + describe(att));
            check("minimal skin", minimal, reg, r);
            for (Map.Entry<String, Function<CosmeticRegistry, Map<String, PlayerSkinPart>>> slot : SLOTS.entrySet()) {
                if (emptied.contains(slot.getKey())) continue;
                Map<String, PlayerSkinPart> parts = slot.getValue().apply(reg);
                if (parts == null) continue;
                Field field = PlayerSkin.class.getField(slot.getKey());
                for (PlayerSkinPart part : parts.values()) {
                    for (String value : valuesOf(slot.getKey(), part, reg)) {
                        PlayerSkin skin = new PlayerSkin(minimal);
                        field.set(skin, value);
                        check("minimal " + slot.getKey() + "=" + value, skin, reg, r);
                        swept++;
                    }
                }
            }
            Random rng = new Random(11);
            for (int i = 0; i < randomCount; i++) {
                PlayerSkin skin = CosmeticsModule.get().generateRandomSkin(rng);
                check("random #" + (i + 1), skin, reg, r);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String summary = r.skins + " skins (" + swept + " part/variant/color sweeps, " + randomCount + " random"
                    + (own != null ? ", yours" : "") + "), " + r.attachments + " attachments, " + r.problems + " problems, "
                    + r.vanillaRejects + " rejected by vanilla validateSkin, " + ms + " ms";
            head.add(1, summary);
            head.add("");
            head.add("== Problems (first " + MAX_LINES + ")");
            head.addAll(r.lines.isEmpty() ? List.of("  none") : r.lines);
            Path dir = FxSettings.dataDirectory();
            String where = "";
            if (dir != null) {
                Path file = dir.resolve("tests").resolve("playerlook_"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
                Files.createDirectories(file.getParent());
                Files.write(file, head, StandardCharsets.UTF_8);
                where = " -> tests/" + file.getFileName();
            }
            TestTools.say(ctx.sender(), "PlayerLook: " + summary + where, r.problems == 0 ? OK : ERR);
            MobBattlePlugin.log("[test] playerlook " + summary);
        } catch (Exception e) {
            TestTools.say(ctx.sender(), "PlayerLook test failed: " + e, ERR);
        }
    }

    /** Every skin value of a part: id[.color][.variant], each color of its gradient set or textures, each variant. */
    private static List<String> valuesOf(String slot, PlayerSkinPart part, CosmeticRegistry reg) {
        List<String> out = new ArrayList<>();
        if (ID_ONLY.contains(slot)) {
            out.add(part.getId());
            return out;
        }
        List<String> variants = new ArrayList<>();
        if (part.getVariants() != null && !part.getVariants().isEmpty()) variants.addAll(part.getVariants().keySet());
        else variants.add(null);
        for (String v : variants) {
            List<String> colors = new ArrayList<>();
            if (part.getGradientSet() != null) {
                PlayerSkinGradientSet set = reg.getGradientSets().get(part.getGradientSet());
                if (set != null && set.getGradients() != null) colors.addAll(set.getGradients().keySet());
            } else {
                var tex = v != null && part.getVariants().get(v).getTextures() != null ? part.getVariants().get(v).getTextures() : part.getTextures();
                if (tex != null) colors.addAll(tex.keySet());
            }
            if (colors.isEmpty()) colors.add("");
            for (String c : colors) out.add(part.getId() + "." + c + (v != null ? "." + v : ""));
        }
        return out;
    }

    private static void check(String what, PlayerSkin skin, CosmeticRegistry reg, Report r) {
        r.skins++;
        try {
            CosmeticsModule.get().validateSkin(skin);
        } catch (Exception e) {
            r.vanillaRejects++;
            if (r.vanillaRejects <= 20) r.line("  [vanilla rejects] " + what + ": " + e.getMessage());
            return;
        }
        List<ModelAttachment> atts = PlayerLook.build(skin);
        r.attachments += atts.size();
        int expected = expectedParts(skin, reg);
        if (atts.size() != expected) {
            r.problems++;
            r.line("  " + what + ": " + atts.size() + " attachments, expected " + expected);
        }
        for (ModelAttachment att : atts) {
            String bad = badness(att, reg);
            if (bad != null) {
                r.problems++;
                r.line("  " + what + ": " + bad + " in " + describe(att));
            }
        }
    }

    /** Parts the skin shows: every slot with a known part, minus what a FullyCovering hat hides. */
    private static int expectedParts(PlayerSkin skin, CosmeticRegistry reg) throws RuntimeException {
        PlayerSkinPart hat = partOf(reg.getHeadAccessories(), skin.headAccessory);
        boolean covered = hat != null && hat.getHeadAccessoryType() == PlayerSkinPart.HeadAccessoryType.FullyCovering;
        int n = 0;
        for (Map.Entry<String, Function<CosmeticRegistry, Map<String, PlayerSkinPart>>> slot : SLOTS.entrySet()) {
            String key = slot.getKey();
            if (covered && (key.equals("ears") || key.equals("haircut") || key.equals("earAccessory"))) continue;
            try {
                if (partOf(slot.getValue().apply(reg), (String) PlayerSkin.class.getField(key).get(skin)) != null) n++;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return n;
    }

    private static PlayerSkinPart partOf(Map<String, PlayerSkinPart> parts, String value) {
        if (value == null || value.isEmpty() || parts == null) return null;
        return parts.get(value.split("\\.")[0]);
    }

    /** What is wrong with one attachment, or null. */
    private static String badness(ModelAttachment att, CosmeticRegistry reg) {
        if (!exists(att.getModel())) return "model missing";
        if (!exists(att.getTexture())) return "texture missing";
        String set = att.getGradientSet();
        if (set != null && !set.isEmpty()) {
            PlayerSkinGradientSet gs = reg.getGradientSets().get(set);
            if (gs == null) return "unknown gradient set " + set;
            if (att.getGradientId() == null || att.getGradientId().isEmpty()) return "no gradient id (drawn untinted, white)";
            if (gs.getGradients() == null || !gs.getGradients().containsKey(att.getGradientId())) {
                return "gradient " + att.getGradientId() + " not in set " + set;
            }
        }
        return null;
    }

    private static boolean exists(String path) {
        if (path == null || path.isEmpty()) return false;
        return CommonAssetRegistry.hasCommonAsset(path) || CommonAssetRegistry.hasCommonAsset("Common/" + path);
    }

    private static String describe(ModelAttachment att) {
        return att.getModel() + " | " + att.getTexture()
                + (att.getGradientSet() != null ? " | " + att.getGradientSet() + ":" + att.getGradientId() : "");
    }
}
