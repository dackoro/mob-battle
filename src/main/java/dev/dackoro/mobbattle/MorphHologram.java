package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
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
 * After a playtest (2026-10-16): (1) the player lost their clothes: a player's look comes from their skin, so
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
    /**
     * The panel model, one per yaw of the panel around the wand (2026-10-16, playtest: in first person the panel was seen at
     * an angle). The first one shipped copied the wand's own 34 degree turn; MobBattle_Hologram_Y{m90,m60,m56,m30,0,34}
     * (m = minus) turn the panel quad that many degrees about Y. Default {@link #DEFAULT_YAW}; `test hologram yaw <deg>`
     * tries the others live (per player, memory only). ❓ Which one faces the first-person camera is a guess.
     */
    static final String HOLO_PREFIX = "Items/MobBattle/MorphWand/MobBattle_Hologram";
    static final int[] YAWS = {-90, -60, -56, -30, 0, 34};
    static final int DEFAULT_YAW = -30;   // 2026-10-16: -30 faces the first-person camera
    static final String HOLO_MODEL = modelForYaw(DEFAULT_YAW);
    private static final Map<UUID, Integer> YAW = new ConcurrentHashMap<>();
    /**
     * Player -> {when the panel was last put by the 1 s check because it had gone from the model, wanted texture}
     * (2026-10-16: every ModelComponent put on a player makes vanilla reset and re-send their MovementSettings, so the
     * model is never re-put on a loop: a panel that keeps vanishing with nothing changed is put back at most once per
     * REPUT_MS).
     */
    private static final Map<UUID, Object[]> LAST_PUT = new ConcurrentHashMap<>();
    private static final long REPUT_MS = 5000;

    static String modelForYaw(int deg) {
        return HOLO_PREFIX + "_Y" + (deg < 0 ? "m" + -deg : String.valueOf(deg)) + ".blockymodel";
    }

    /** Any of our panel models (the old single one included). */
    static boolean isHoloModel(String model) {
        return model != null && model.startsWith(HOLO_PREFIX);
    }

    private static String holoModelOf(UUID player) {
        Integer deg = player != null ? YAW.get(player) : null;
        return deg != null ? modelForYaw(deg) : HOLO_MODEL;
    }
    static final String HOLO_FOLDER = "Items/MobBattle/MorphWand/Hologram/";
    static final String WAND = "MobBattle_MorphWand";
    /** A texture Mob Battle ships, to learn whether the registry names common files with a "Common/" prefix. */
    private static final String KNOWN_TEXTURE = "Items/MobBattle/MorphWand/MobBattle_MorphWand_Texture.png";
    /**
     * Hologram texture size, the panel quad's units (MobBattle_Hologram_Y*.blockymodel). 256 from 2026-10-16 (128 looked
     * blocky), back to 128 on 2026-10-28 to try it: each one is a quarter of the client's shared entity texture atlas.
     */
    static final int TEX = 128;
    private static final long LIVE_APPLY_DELAY_MS = 1500;
    private static final long CHECK_MS = 1000;

    /** Players who turned the hologram on (memory only). */
    /** Players who turned it off (memory only; on by default since 2026-10-16: an "on" list was lost at every restart). */
    private static final Set<UUID> DISABLED = ConcurrentHashMap.newKeySet();
    /** Player -> texture of the panel they wear now. */
    private static final Map<UUID, String> SHOWN = new ConcurrentHashMap<>();
    /** Live-registered textures this session (a model the boot cache did not have). */
    private static final Set<String> LIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean checking;
    /** Plugin shutdown happened: a picture finishing late must not start the 1 s loop again (audit 2026-10-29). */
    private static volatile boolean stopped;

    /*
     * How the panel reaches the client (2026-10-16, playtest tests): the game's player Model is the bare "Player" body and
     * the client puts the clothes on from the PlayerSkin; a Model with attachments of its own replaces them (panel
     * without clothes), and sending the skin again replaces the panel (clothes without panel). So "wand" (default)
     * sends a Model that carries every skin part as an attachment (PlayerLook, built on the server, cached per skin)
     * plus the panel, and never the skin. "hud" = no panel, the picture in a HUD corner (works, playtest test).
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
                DISABLED.remove(id);
                STYLE.put(id, style);
                startChecks();
                TestTools.say(ctx.sender(), "Hologram on (" + style + "): " + (HUD.equals(style)
                        ? "the copied mob shows in the bottom right corner while you hold the Morph Wand."
                        : "a panel over the Morph Wand while you hold it, your clothes rebuilt on the server. Look in third person."), OK);
            }
            case "off" -> {
                DISABLED.add(id);
                TestTools.say(ctx.sender(), "Hologram off.", OK);
            }
            case "yaw" -> {
                int deg;
                try {
                    deg = Integer.parseInt(style);
                } catch (NumberFormatException e) {
                    deg = Integer.MIN_VALUE;
                }
                final int want = deg;
                if (java.util.Arrays.stream(YAWS).noneMatch(y -> y == want)) {
                    TestTools.say(ctx.sender(), "Usage: /mobbattle test hologram yaw <-90|-60|-56|-30|0|34> (now "
                            + YAW.getOrDefault(id, DEFAULT_YAW) + ", default " + DEFAULT_YAW + ")", ERR);
                    return;
                }
                YAW.put(id, deg);
                TestTools.say(ctx.sender(), "Hologram panel turned " + deg + " degrees. Hold the Morph Wand and compare first and third person.", OK);
            }
            default -> {
                TestTools.say(ctx.sender(), "Usage: /mobbattle test hologram on [wand|hud] | off | yaw <degrees>", ERR);
                return;
            }
        }
        refresh(id);
    }

    // ---- when it shows ----

    /** Whatever changed for this player (turned on/off, new copy, item in hand): shows or hides the panel. */
    static void refresh(UUID player) {
        if (off(player) && !SHOWN.containsKey(player)) return;
        startChecks();
        refreshNow(player);
    }

    private static void refreshNow(UUID player) {
        PlayerRef pr = Universe.get().getPlayer(player);
        Ref<EntityStore> ref = pr != null ? pr.getReference() : null;
        if (ref == null || !ref.isValid()) {
            SHOWN.remove(player);
            return;
        }
        Store<EntityStore> store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (broken) return;
            try {
                update(player, ref, store);
            } catch (LinkageError e) {
                // a game update changed the Model API: the world's task loop only catches Exceptions, so this would
                // have stopped the world; the hologram turns off instead (audit 2026-10-17)
                broken = true;
                MobBattlePlugin.log("Morph Wand hologram off (game API changed): " + e);
            }
        });
    }

    /** The game's model API did not match (LinkageError once): no hologram until a restart. */
    private static volatile boolean broken;

    /** World thread: the panel the player should wear now, put on or taken off when it differs. */
    private static void update(UUID player, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!ref.isValid()) {
            SHOWN.remove(player);
            return;
        }
        String want = null;
        String modelId = null;
        if (!off(player) && holdsWand(ref, store)) {
            MorphWand.Copy copy = MorphWand.copyOf(player);
            // a copied player look gets its own hologram, keyed by its skin (2026-10-29, playtest: copied players had none)
            modelId = copy == null ? null : copy.skin() == null ? copy.modelId()
                    : VsScreen.PLAYER_PREFIX + PlayerSkins.idOf(copy.skin());
            want = modelId != null ? ThumbnailCache.hologramTexture(modelId) : null;
            if (want == null && modelId != null && copy.skin() != null) {
                onDemandSkin(player, modelId, copy.skin());
                modelId = null;
            }
        }
        if (want == null && modelId != null && !off(player)) {
            onDemand(player, modelId);   // registered the first time someone copies this mob, put on when ready
        }
        String style = STYLE.getOrDefault(player, PANEL);
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String now = panelOf(ref, store, player);
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
        // nothing changed on our side (same wanted texture, already put) but the panel is gone or stale: something
        // rebuilt the model; put it back, but never more often than REPUT_MS
        boolean same = want != null && want.equals(SHOWN.get(player));
        Object[] last = LAST_PUT.get(player);
        long t = System.currentTimeMillis();
        if (same && last != null && want.equals(last[1]) && t - (long) last[0] < REPUT_MS) return;
        if (MobBattlePlugin.debugging()) {
            MobBattlePlugin.debug("hologram: " + (want == null ? "panel off" : same ? "panel re-put (gone from the model: " + now + ")"
                    : "panel on " + want) + " for " + (pr != null ? pr.getUsername() : player));
        }
        LAST_PUT.put(player, new Object[] {t, want});
        setPanel(ref, store, want, style);
        if (want == null) SHOWN.remove(player);
        else SHOWN.put(player, want);
    }

    /** The texture of the panel on the player's model now, or null. */
    /** The panel's texture, prefixed with "~" when it hangs on another yaw's model than the player's (re-put then). */
    private static String panelOf(Ref<EntityStore> ref, Store<EntityStore> store, UUID player) {
        ModelComponent mc = store.getComponent(ref, ModelComponent.getComponentType());
        Model m = mc != null ? mc.getModel() : null;
        if (m == null || m.getAttachments() == null) return null;
        for (ModelAttachment att : m.getAttachments()) {
            if (att != null && isHoloModel(att.getModel())) {
                return holoModelOf(player).equals(att.getModel()) ? att.getTexture() : "~" + att.getTexture();
            }
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
        if (checking || stopped) return;
        checking = true;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(MorphHologram::check, CHECK_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Every online player once a second (cheap: one hotbar read each), from the plugin start on: a player who joins with
     * the wand already in hand gets the panel without a hotbar change. Stops on plugin shutdown ({@link #clear}).
     */
    static void startAtBoot() {
        startChecks();
    }

    /** No panel for this player: turned off for them, or the server feature is off (/mobbattle serverHolograms). */
    private static boolean off(UUID player) {
        return DISABLED.contains(player) || !FxSettings.serverHolograms;
    }

    private static void check() {
        if (!checking) return;
        // players who left: their HUD mark would keep a reconnect from getting the corner picture again, and the rest
        // grows by one entry per player (audit 2026-10-17)
        if (!HUD_SHOWN.isEmpty()) HUD_SHOWN.keySet().removeIf(id -> Universe.get().getPlayer(id) == null);
        if (!LAST_PUT.isEmpty()) LAST_PUT.keySet().removeIf(id -> Universe.get().getPlayer(id) == null);
        Set<UUID> ids = new java.util.HashSet<>(SHOWN.keySet());
        for (PlayerRef p : Universe.get().getPlayers()) if (!off(p.getUuid())) ids.add(p.getUuid());
        for (UUID id : ids) {
            try {
                refreshNow(id);
            } catch (RuntimeException e) {
                MobBattlePlugin.log("Hologram check failed: " + e);
            }
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
            if (texture != null) {
                List<ModelAttachment> clothes = PlayerLook.attachments(skin.getPlayerSkin());
                // No parts built (cosmetics not loaded, a read error): no panel at all rather than a bare, bald body
                // (audit 2026-10-17); the skin the client already has stays.
                if (clothes.isEmpty()) return;
                next.addAll(clothes);
            }
        } else if (base.getAttachments() != null) {
            for (ModelAttachment att : base.getAttachments()) {
                if (att != null && !isHoloModel(att.getModel())) next.add(att);
            }
        }
        if (texture != null) {
            PlayerRef owner = store.getComponent(me, PlayerRef.getComponentType());
            next.add(new ModelAttachment(holoModelOf(owner != null ? owner.getUuid() : null), texture, null, null, 1.0));
        }
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
     * On demand (2026-10-28, playtest: players with many mods): no hologram is registered at boot any more. Every model's
     * texture went to every joining client and into the client's entity texture atlas, which the textures of ~45 mods
     * already fill (rejected textures, broken items). Now a mob's hologram is registered the first time someone
     * copies it: read from the picture cache (drawn now if missing), put in the registry without the per-file toast,
     * pushed to the players online with one asset rebuild (a short hitch, once per model per session) and added to
     * the list clients joining later get. Then the player is refreshed. One try per model per session.
     */
    private static void onDemand(UUID player, String modelId) {
        if (!LIVE.add(modelId)) return;
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(modelId);
        if (asset == null) return;
        Model model = ThumbnailCache.modelOf(asset);
        List<ThumbnailCache.Piece> pieces = ThumbnailCache.piecesOf(model);
        ThumbnailCache.PoseChoice pose = ThumbnailCache.poseOf(model, "auto", null);
        CompletableFuture.runAsync(() -> {
            try {
                long t0 = System.nanoTime();
                java.nio.file.Path file = ThumbnailCache.holoFileOf(modelId);
                byte[] png = file != null && java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
                if (png == null) {
                    BufferedImage shot = ThumbnailCache.render(pieces, ThumbnailCache.load(pose), ModelThumbnail.THREE_QUARTER, TEX - 8, true);
                    if (shot == null) return;
                    png = encode(hologram(shot));
                    if (file != null) PictureCache.write(file, png);
                }
                String plain = texturePath(modelId);
                CommonAsset made = file != null && java.nio.file.Files.exists(file)
                        ? new RuntimeCommonAsset(registryPrefix() + plain, png, file)
                        : new RuntimeAsset(registryPrefix() + plain, png);
                ThumbnailCache.registerOnline(made);
                ThumbnailCache.markHologram(modelId, plain, png.length);
                MobBattlePlugin.log("Hologram texture for " + modelId + " registered on demand (" + MobBattlePlugin.size(png.length)
                        + ", " + MobBattlePlugin.took(t0) + ").");
                HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> refresh(player), LIVE_APPLY_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                MobBattlePlugin.log("Hologram texture for " + modelId + " failed: " + e);
            }
        });
    }

    /** onDemand for a copied player look: the skin drawn as VS pictures draw it (bare body + every skin part). */
    private static void onDemandSkin(UUID player, String id, com.hypixel.hytale.protocol.PlayerSkin skin) {
        if (off(player) || !LIVE.add(id)) return;
        Model model = com.hypixel.hytale.server.core.cosmetics.CosmeticsModule.get().createModel(skin, 1f);
        if (model == null) return;
        List<ThumbnailCache.Piece> pieces = new java.util.ArrayList<>();
        for (var a : PlayerLook.attachments(skin)) ThumbnailCache.addPiece(pieces, a);
        if (pieces.isEmpty()) return;
        ThumbnailCache.PoseChoice pose = ThumbnailCache.poseOf(model, "idle", null);
        CompletableFuture.runAsync(() -> {
            try {
                long t0 = System.nanoTime();
                java.nio.file.Path file = ThumbnailCache.holoFileOf(id);
                byte[] png = file != null && java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
                if (png == null) {
                    BufferedImage shot = ThumbnailCache.render(pieces, ThumbnailCache.load(pose), ModelThumbnail.THREE_QUARTER, TEX - 8, true);
                    if (shot == null) return;
                    png = encode(hologram(shot));
                    if (file != null) PictureCache.write(file, png);
                }
                String plain = texturePath(id);
                CommonAsset made = file != null && java.nio.file.Files.exists(file)
                        ? new RuntimeCommonAsset(registryPrefix() + plain, png, file)
                        : new RuntimeAsset(registryPrefix() + plain, png);
                ThumbnailCache.registerOnline(made);
                ThumbnailCache.markHologram(id, plain, png.length);
                MobBattlePlugin.log("Hologram texture for a player look registered on demand (" + MobBattlePlugin.size(png.length)
                        + ", " + MobBattlePlugin.took(t0) + ").");
                HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> refresh(player), LIVE_APPLY_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                MobBattlePlugin.log("Hologram texture for a player look failed: " + e);
            }
        });
    }

    /** TEX x TEX: the picture fitted at the bottom, tinted green by its brightness, faint scanlines, a glow around it. */
    static BufferedImage hologram(BufferedImage shot) {
        BufferedImage out = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        int max = TEX - TEX * 24 / 256;   // margins tuned at 256, kept in proportion
        double k = Math.min((double) max / shot.getWidth(), (double) max / shot.getHeight());
        int w = Math.max(1, (int) Math.round(shot.getWidth() * k)), h = Math.max(1, (int) Math.round(shot.getHeight() * k));
        int ox = (TEX - w) / 2, oy = TEX - TEX * 12 / 256 - h;
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
        // soft glow around the silhouette first (GLOW_PX, lime fading out), then the body over it
        for (int y = -GLOW_PX; y < h + GLOW_PX; y++) {
            for (int x = -GLOW_PX; x < w + GLOW_PX; x++) {
                if (y >= 0 && y < h && x >= 0 && x < w && solid[y][x]) continue;
                double d = nearestSolid(solid, x, y, GLOW_PX);
                if (d > GLOW_PX) continue;
                int a = (int) Math.round(GLOW_ALPHA * Math.pow(1 - d / (GLOW_PX + 0.5), 1.5));
                int tx = ox + x, ty = oy + y;
                if (a > 0 && tx >= 0 && ty >= 0 && tx < TEX && ty < TEX) out.setRGB(tx, ty, a << 24 | GLOW_RGB);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!solid[y][x]) continue;
                // green-scale (playtest 2026-10-16: like the Omnitrix hologram, not old-TV lines): the picture's own
                // brightness range mapped from deep green to bright lime
                double v = Math.pow((lum[y][x] - lo) / span, 0.85);
                boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || !solid[y - 1][x] || !solid[y + 1][x] || !solid[y][x - 1] || !solid[y][x + 1];
                if (edge) v = 1.08;   // rim light, past the lime toward white
                double line = (oy + y) % SCANLINE_EVERY == 0 ? SCANLINE_DIM : 1.0;   // faint, wide apart
                int r = clamp((DARK[0] + (LIME[0] - DARK[0]) * v) * line);
                int g = clamp((DARK[1] + (LIME[1] - DARK[1]) * v) * line);
                int b = clamp((DARK[2] + (LIME[2] - DARK[2]) * v) * line);
                out.setRGB(ox + x, oy + y, BODY_ALPHA << 24 | r << 16 | g << 8 | b);
            }
        }
        return out;
    }

    // brighter than the first green-scale (playtest 2026-10-16: glow like the wand's gem): bright colors on the fullbright
    // panel are what makes it glow
    private static final int[] DARK = {26, 124, 34}, LIME = {214, 255, 128};
    private static final int GLOW_RGB = 0xB4FF6E, GLOW_ALPHA = 150, BODY_ALPHA = 240, GLOW_PX = Math.max(2, 5 * TEX / 256);
    private static final int SCANLINE_EVERY = Math.max(4, 8 * TEX / 256);
    private static final double SCANLINE_DIM = 0.9;

    /** Distance to the nearest solid pixel within {@code r} (r + 1 when none). */
    private static double nearestSolid(boolean[][] solid, int x, int y, int r) {
        double best = r + 1;
        for (int dy = -r; dy <= r; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= solid.length) continue;
            for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= solid[0].length || !solid[yy][xx]) continue;
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
            }
        }
        return best;
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

    /** Palette PNG (IndexedPng, as the VS pictures): ~3x smaller than ImageIO's full-color one for every joining client. */
    static byte[] encode(BufferedImage img) throws IOException {
        return IndexedPng.encode(img);
    }

    private static String safe(String id) {
        return id == null ? "mob" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Plugin shutdown: forget who had it on. */
    static void clear() {
        DISABLED.clear();
        stopped = true;
        checking = false;
        SHOWN.clear();
        LIVE.clear();
        STYLE.clear();
        HUD_SHOWN.clear();
        LAST_PUT.clear();
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
