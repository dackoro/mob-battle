package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Omnitrix-like hologram of the copied mob over the held Morph Wand (prototype, 2026-10-16; turned on per player with
 * /mobbattle test hologram on|off). A flat panel ({@code MobBattle_Hologram.blockymodel}: a fullbright, double-sided
 * quad over the wand's gem, hanging on {@code R-Attachment}, the bone the held item's root sits on, so it moves with
 * the wand) is one more ModelAttachment on the player's model, with the mob's hologram texture.
 * <p>
 * After the user's test (2026-10-16): (1) the player lost their clothes: a player's look comes from their skin, so
 * the model is rebuilt from it ({@code CosmeticsModule.createModel}, as vanilla /model reset, then the skin is sent
 * again); (2) the panel showed with any item: it is now only there while the Morph Wand is in hand (hotbar change in
 * WandEquipSoundSystem, a new copy in MorphWand.setCopy, and a check every second); (3) the wrist variant was hard to
 * see and is gone; (4) registering a texture while playing made every client reload its assets (a visible hitch):
 * hologram textures are made with the thumbnails and registered when the server boots, before anyone joins
 * (ThumbnailCache), and only a model missing from that cache still goes through a live registration; (5) the panel
 * floats and sways through the wand's own player animation set ({@code MobBattle_MorphWand}, Staff's animations with
 * a "Hologram" node track, scripts/gen-hologram-anims.py).
 * ❓ Untested in game after these fixes.
 */
final class MorphHologram {

    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";
    static final String HOLO_MODEL = "Items/MobBattle/MorphWand/MobBattle_Hologram.blockymodel";
    static final String HOLO_FOLDER = "Items/MobBattle/MorphWand/Hologram/";
    static final String WAND = "MobBattle_MorphWand";
    /** A texture Mob Battle ships, to learn whether the registry names common files with a "Common/" prefix. */
    private static final String KNOWN_TEXTURE = "Items/MobBattle/MorphWand/MobBattle_MorphWand_Texture.png";
    static final int TEX = 128;
    private static final long LIVE_APPLY_DELAY_MS = 1500;
    private static final long CHECK_MS = 1000;

    /** Players who turned the hologram on (memory only). */
    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();
    /** Player -> texture of the panel they wear now. */
    private static final Map<UUID, String> SHOWN = new ConcurrentHashMap<>();
    /** Live-registered textures this session (a model the boot cache did not have). */
    private static final Set<String> LIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean checking;

    /*
     * How the panel reaches the client (2026-10-16, user tests): the game's player Model is the bare "Player" body and
     * the client puts the clothes on from the PlayerSkin; a Model with attachments of its own replaces them (panel
     * without clothes), and sending the skin again replaces the panel (clothes without panel). So "wand" (default)
     * sends a Model that carries every skin part as an attachment (PlayerLook, built on the server, cached per skin)
     * plus the panel, and never the skin. "hud" = no panel, the picture in a HUD corner (works, user test).
     */
    static final String PANEL = "wand", HUD = "hud";
    private static final Set<String> STYLES = Set.of(PANEL, HUD);
    private static final Map<UUID, String> STYLE = new ConcurrentHashMap<>();
    /** Player -> texture shown in the HUD style now. */
    private static final Map<UUID, String> HUD_SHOWN = new ConcurrentHashMap<>();

    private MorphHologram() {
    }

    // ---- command ----

    static void command(CommandContext ctx, String[] a) {
        if (!ctx.isPlayer()) {
            TestTools.say(ctx.sender(), "Only a player can wear the hologram.", ERR);
            return;
        }
        String mode = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "on";
        String style = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : PANEL;
        UUID id = ctx.sender().getUuid();
        switch (mode) {
            case "on" -> {
                if (!STYLES.contains(style)) {
                    TestTools.say(ctx.sender(), "Usage: /mobbattle test hologram on [wand|hud] | off", ERR);
                    return;
                }
                ENABLED.add(id);
                STYLE.put(id, style);
                startChecks();
                TestTools.say(ctx.sender(), "Hologram on (" + style + "): " + (HUD.equals(style)
                        ? "the copied mob shows in the bottom right corner while you hold the Morph Wand."
                        : "a panel over the Morph Wand while you hold it, your clothes rebuilt on the server. Look in third person."), OK);
            }
            case "off" -> {
                ENABLED.remove(id);
                TestTools.say(ctx.sender(), "Hologram off.", OK);
            }
            default -> {
                TestTools.say(ctx.sender(), "Usage: /mobbattle test hologram on [wand|hud] | off", ERR);
                return;
            }
        }
        refresh(id);
    }

    // ---- when it shows ----

    /** Whatever changed for this player (turned on/off, new copy, item in hand): shows or hides the panel. */
    static void refresh(UUID player) {
        if (!ENABLED.contains(player) && !SHOWN.containsKey(player)) return;
        PlayerRef pr = Universe.get().getPlayer(player);
        Ref<EntityStore> ref = pr != null ? pr.getReference() : null;
        if (ref == null || !ref.isValid()) {
            SHOWN.remove(player);
            return;
        }
        Store<EntityStore> store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> update(player, ref, store));
    }

    /** World thread: the panel the player should wear now, put on or taken off when it differs. */
    private static void update(UUID player, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!ref.isValid()) {
            SHOWN.remove(player);
            return;
        }
        String want = null;
        String modelId = null;
        if (ENABLED.contains(player) && holdsWand(ref, store)) {
            MorphWand.Copy copy = MorphWand.copyOf(player);
            modelId = copy != null && copy.skin() == null ? copy.modelId() : null;
            want = modelId != null ? ThumbnailCache.hologramTexture(modelId) : null;
        }
        if (want == null && modelId != null && ENABLED.contains(player)) {
            liveTexture(player, modelId);   // not in the boot cache: made now, put on when ready
        }
        String style = STYLE.getOrDefault(player, PANEL);
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String now = panelOf(ref, store);
        if (HUD.equals(style)) {
            if (now != null) setPanel(ref, store, null, style);
            showHud(pr, ref, store, want, modelId);
            if (want == null) SHOWN.remove(player);
            else SHOWN.put(player, want);
            return;
        }
        showHud(pr, ref, store, null, null);
        // the model itself, not SHOWN: a morph or size buff rebuilds the player's model without the panel
        if (want == null ? now == null : want.equals(now)) {
            if (want == null) SHOWN.remove(player);
            return;
        }
        setPanel(ref, store, want, style);
        if (want == null) SHOWN.remove(player);
        else SHOWN.put(player, want);
    }

    /** The texture of the panel on the player's model now, or null. */
    private static String panelOf(Ref<EntityStore> ref, Store<EntityStore> store) {
        ModelComponent mc = store.getComponent(ref, ModelComponent.getComponentType());
        Model m = mc != null ? mc.getModel() : null;
        if (m == null || m.getAttachments() == null) return null;
        for (ModelAttachment att : m.getAttachments()) {
            if (att != null && HOLO_MODEL.equals(att.getModel())) return att.getTexture();
        }
        return null;
    }

    private static boolean holdsWand(Ref<EntityStore> ref, Store<EntityStore> store) {
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        ItemStack held = hotbar != null ? hotbar.getActiveItem() : null;
        return held != null && WAND.equals(held.getItemId());
    }

    /** Every second while someone wears a panel: the wand may have left the hand without a hotbar change (drop, move). */
    private static void startChecks() {
        if (checking) return;
        checking = true;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(MorphHologram::check, CHECK_MS, TimeUnit.MILLISECONDS);
    }

    private static void check() {
        Set<UUID> ids = new java.util.HashSet<>(SHOWN.keySet());
        ids.addAll(ENABLED);
        for (UUID id : ids) refresh(id);
        if (SHOWN.isEmpty() && ENABLED.isEmpty()) {
            checking = false;
            return;
        }
        HytaleServer.SCHEDULED_EXECUTOR.schedule(MorphHologram::check, CHECK_MS, TimeUnit.MILLISECONDS);
    }

    // ---- the model ----

    /**
     * World thread: the player's model with the panel wearing {@code texture} (null = no panel).
     * <ul>
     *   <li>In a player look (a "Player" model with a skin: their own, or a copied player's): panel on = the bare body
     *   at their size plus every skin part as an attachment (PlayerLook) plus the panel, and the skin is NOT sent
     *   again (it would replace the attachments); panel off = the game's own model back (bare body + skin sent again,
     *   as vanilla /model reset).</li>
     *   <li>Morphed into a mob: that model with the panel added or taken off, every other attachment kept.</li>
     * </ul>
     */
    static void setPanel(Ref<EntityStore> me, Store<EntityStore> store, String texture, String style) {
        ModelComponent mc = store.getComponent(me, ModelComponent.getComponentType());
        Model current = mc != null ? mc.getModel() : null;
        if (current == null) return;
        PlayerSkinComponent skin = store.getComponent(me, PlayerSkinComponent.getComponentType());
        boolean look = skin != null && skin.getPlayerSkin() != null && PLAYER_MODEL.equals(current.getModelAssetId());
        Model base = look ? CosmeticsModule.get().createModel(skin.getPlayerSkin(), MorphWand.playerSize(me)) : current;
        if (base == null) base = current;
        List<ModelAttachment> next = new ArrayList<>();
        if (look) {
            if (texture != null) next.addAll(PlayerLook.attachments(skin.getPlayerSkin()));
        } else if (base.getAttachments() != null) {
            for (ModelAttachment att : base.getAttachments()) {
                if (att != null && !HOLO_MODEL.equals(att.getModel())) next.add(att);
            }
        }
        if (texture != null) next.add(new ModelAttachment(HOLO_MODEL, texture, null, null, 1.0));
        Model model = new Model(base.getModelAssetId(), base.getScale(), base.getRandomAttachmentIds(),
                next.toArray(new ModelAttachment[0]), base.getBoundingBox(), base.getModel(), base.getTexture(),
                base.getGradientSet(), base.getGradientId(), base.getEyeHeight(), base.getCrouchOffset(),
                base.getSittingOffset(), base.getSleepingOffset(), base.getAnimationSetMap(), base.getCamera(),
                base.getLight(), base.getParticles(), base.getTrails(), base.getPhysicsValues(), base.getDetailBoxes(),
                base.getPhobia(), base.getPhobiaModelAssetId());
        store.putComponent(me, ModelComponent.getComponentType(), new ModelComponent(model));
        // panel off in a player look: the client builds the clothes from the skin again
        if (look && texture == null) skin.setNetworkOutdated();
    }

    /** The game's player model asset id (CosmeticsModule.createModel). */
    private static final String PLAYER_MODEL = "Player";

    // ---- HUD style ----

    /** World thread: the hologram picture in the HUD corner ({@code texture} null = none). */
    private static void showHud(PlayerRef pr, Ref<EntityStore> ref, Store<EntityStore> store, String texture, String modelId) {
        if (pr == null) return;
        UUID id = pr.getUuid();
        String now = HUD_SHOWN.get(id);
        if (texture == null ? now == null : texture.equals(now)) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        if (huds.getCustomHud(HoloHud.KEY) != null) huds.removeCustomHud(pr, HoloHud.KEY);
        if (texture == null) {
            HUD_SHOWN.remove(id);
            return;
        }
        huds.addCustomHud(pr, new HoloHud(pr, texture, VsScreen.pretty(modelId)));
        HUD_SHOWN.put(id, texture);
    }

    private static final class HoloHud extends CustomUIHud {
        static final String KEY = "MobBattleMorphHolo";
        private final String texture;
        private final String name;

        HoloHud(PlayerRef player, String texture, String name) {
            super(player, KEY);
            this.texture = texture;
            this.name = name;
        }

        @Override
        protected void build(UICommandBuilder b) {
            b.append("MobBattle/MorphHolo.ui");
            b.set("#Img.AssetPath", texture);
            b.set("#Name.Text", name);
        }
    }

    // ---- textures ----

    /** The asset name of a model's hologram texture (without the registry's "Common/" prefix). */
    static String texturePath(String modelId) {
        return HOLO_FOLDER + "MobBattle_Holo_" + safe(modelId) + ".png";
    }

    /** The registry's prefix for common files ("Common/" or ""), learned from a texture the mod ships. */
    static String registryPrefix() {
        return CommonAssetRegistry.getByName("Common/" + KNOWN_TEXTURE) != null ? "Common/" : "";
    }

    /**
     * Registers a hologram PNG in the mod's asset pack, silently ({@code rebuild} false at boot: nobody is connected,
     * so no client reloads anything). Returns the plain path the model uses.
     */
    static String register(String modelId, byte[] png, boolean rebuild) {
        String plain = texturePath(modelId);
        CommonAssetModule.get().addCommonAsset(EyeRecolor.PACK, new RuntimeAsset(registryPrefix() + plain, png), false);
        if (rebuild) Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
        return plain;
    }

    /**
     * Fallback for a model the boot cache did not have: renders and registers its hologram now (the clients reload
     * their assets once), then refreshes the player. One per model per session.
     */
    private static void liveTexture(UUID player, String modelId) {
        if (!LIVE.add(modelId)) return;
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(modelId);
        if (asset == null) return;
        Model model = Model.createScaledModel(asset, 1f, Map.of());
        List<ThumbnailCache.Piece> pieces = ThumbnailCache.piecesOf(model);
        ThumbnailCache.PoseChoice pose = ThumbnailCache.poseOf(model, "auto", null);
        CompletableFuture.runAsync(() -> {
            try {
                BufferedImage shot = ThumbnailCache.render(pieces, ThumbnailCache.load(pose), ModelThumbnail.THREE_QUARTER, TEX - 16, true);
                if (shot == null) return;
                ThumbnailCache.markHologram(modelId, register(modelId, encode(hologram(shot)), true));
                MobBattlePlugin.log("Hologram texture for " + modelId + " made live (not in the boot cache): clients reloaded their assets.");
                HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> refresh(player), LIVE_APPLY_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                MobBattlePlugin.log("Hologram texture for " + modelId + " failed: " + e);
            }
        });
    }

    /** 128x128: the picture fitted at the bottom, tinted green by its brightness, scanlines, outline, corner brackets. */
    static BufferedImage hologram(BufferedImage shot) {
        BufferedImage out = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        int max = TEX - 16;
        double k = Math.min((double) max / shot.getWidth(), (double) max / shot.getHeight());
        int w = Math.max(1, (int) Math.round(shot.getWidth() * k)), h = Math.max(1, (int) Math.round(shot.getHeight() * k));
        int ox = (TEX - w) / 2, oy = TEX - 8 - h;
        if (k < 1) {
            // area average (a 384 thumbnail sampled one pixel in three lost its details)
            shot = shrink(shot, w, h);
            k = 1;
        }
        boolean[][] solid = new boolean[h][w];
        double[][] lum = new double[h][w];
        double lo = 1, hi = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = shot.getRGB(Math.min(shot.getWidth() - 1, (int) (x / k)), Math.min(shot.getHeight() - 1, (int) (y / k)));
                if ((argb >>> 24) < 128) continue;
                solid[y][x] = true;
                lum[y][x] = (0.3 * ((argb >> 16) & 255) + 0.59 * ((argb >> 8) & 255) + 0.11 * (argb & 255)) / 255.0;
                lo = Math.min(lo, lum[y][x]);
                hi = Math.max(hi, lum[y][x]);
            }
        }
        double span = Math.max(0.05, hi - lo);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!solid[y][x]) continue;
                // brightness stretched over the picture's own range, so dark mobs still show their details
                double v = Math.pow((lum[y][x] - lo) / span, 0.8);
                boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || !solid[y - 1][x] || !solid[y + 1][x] || !solid[y][x - 1] || !solid[y][x + 1];
                double line = ((oy + y) % 3 == 0) ? 0.72 : 1.0;
                int r, g, b;
                if (edge) {
                    r = 190; g = 255; b = 210;
                } else {
                    r = clamp((10 + 120 * v) * line);
                    g = clamp((90 + 165 * v) * line);
                    b = clamp((55 + 130 * v) * line);
                }
                out.setRGB(ox + x, oy + y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        int frame = 0xFF7DFF8A;
        for (int i = 0; i < 14; i++) {
            for (int t = 0; t < 2; t++) {
                out.setRGB(i, t, frame);
                out.setRGB(t, i, frame);
                out.setRGB(TEX - 1 - i, t, frame);
                out.setRGB(TEX - 1 - t, i, frame);
                out.setRGB(i, TEX - 1 - t, frame);
                out.setRGB(t, TEX - 1 - i, frame);
                out.setRGB(TEX - 1 - i, TEX - 1 - t, frame);
                out.setRGB(TEX - 1 - t, TEX - 1 - i, frame);
            }
        }
        return out;
    }

    /** Box-filter downscale (alpha-weighted colors), no AWT toolkit: the server is headless. */
    static BufferedImage shrink(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int sw = src.getWidth(), sh = src.getHeight();
        for (int y = 0; y < h; y++) {
            int y0 = y * sh / h, y1 = Math.max(y0 + 1, (y + 1) * sh / h);
            for (int x = 0; x < w; x++) {
                int x0 = x * sw / w, x1 = Math.max(x0 + 1, (x + 1) * sw / w);
                long a = 0, r = 0, g = 0, b = 0, n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = src.getRGB(xx, yy);
                        int pa = p >>> 24;
                        a += pa;
                        r += (long) ((p >> 16) & 255) * pa;
                        g += (long) ((p >> 8) & 255) * pa;
                        b += (long) (p & 255) * pa;
                        n++;
                    }
                }
                if (a == 0) continue;
                out.setRGB(x, y, (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
            }
        }
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String safe(String id) {
        return id == null ? "mob" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Plugin shutdown: forget who had it on. */
    static void clear() {
        ENABLED.clear();
        SHOWN.clear();
        LIVE.clear();
        STYLE.clear();
        HUD_SHOWN.clear();
    }

    /** Runtime PNG. The base class keeps only a WeakReference to the future, so the bytes stay here (as EyeRecolor). */
    private static final class RuntimeAsset extends CommonAsset {
        private final byte[] png;

        RuntimeAsset(String name, byte[] png) {
            super(name, png);
            this.png = png;
        }

        @Override
        protected java.util.concurrent.CompletableFuture<byte[]> getBlob0() {
            return java.util.concurrent.CompletableFuture.completedFuture(png);
        }
    }
}
