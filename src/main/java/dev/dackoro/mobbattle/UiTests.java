package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Dev tests of what the custom UI can do before redesigning the team columns and the player pictures (2026-10-17,
 * user: "vamos a testear esto"). Both show a full-screen HUD for {@code seconds} (default 25), then take it off.
 * <ul>
 *   <li>{@code /mobbattle test mask [seconds]} (MaskTest.ui): does {@code MaskTexturePath} hide what lies outside an
 *   element? Row A: 300-wide groups holding a picture twice as wide (it spills over unless the group clips it), with no
 *   mask / a full mask / a band mask. Row B: the mask on the picture itself. If a group clips, one wide picture per mob
 *   could replace the six pre-cut column widths (~38 MB of every client's download).</li>
 *   <li>{@code /mobbattle test send [seconds]} (SendTest.ui): pictures made now and sent to the caller only, never
 *   registered for everyone: 1 = the asset packets alone (AssetInitialize, AssetPart, AssetFinalize, as the game's
 *   sendAsset), 2 = the same plus a RequestCommonAssetsRebuild for that player only, 3 = a path never sent, 4 = sent
 *   by packets 1.5 s AFTER the screen opened (does a picture that arrives late show up?). ❌ The game's own
 *   sendAssetsToPlayer is not usable in game: it also sends WorldLoadProgress packets, and the client crashed with
 *   "Received WorldLoadProgress at Playing connection stage" (client log, 2026-10-17). If 1 shows, a player's picture can
 *   go only to the viewers of their VS, with no download at join and no reload for everyone.</li>
 * </ul>
 */
final class UiTests {

    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";
    private static final String KEY = "MobBattleUiTest";
    /** The game's own chunk size for asset parts (CommonAssetModule.sendAssetsToPlayer, javap 0.7.2). */
    private static final int PART = 2621440;

    private UiTests() {
    }

    static void command(CommandContext ctx, String[] a, boolean send) {
        if (!ctx.isPlayer()) {
            TestTools.say(ctx.sender(), "Only a player can see it.", ERR);
            return;
        }
        long seconds = 25;
        if (a.length > 1) {
            try {
                seconds = Math.max(3, Math.min(300, Long.parseLong(a[1])));
            } catch (NumberFormatException e) {
                TestTools.say(ctx.sender(), "Usage: /mobbattle test " + (send ? "send" : "mask") + " [seconds]", ERR);
                return;
            }
        }
        Ref<EntityStore> me = ctx.senderAsPlayerRef();
        if (me == null || !me.isValid()) return;
        Store<EntityStore> store = me.getStore();
        long ms = seconds * 1000;
        store.getExternalData().getWorld().execute(() -> {
            if (!me.isValid()) return;
            PlayerRef pr = store.getComponent(me, PlayerRef.getComponentType());
            if (pr == null) return;
            try {
                if (send) send(ctx, pr, me, store, ms);
                else mask(ctx, pr, me, store, ms);
            } catch (RuntimeException e) {
                TestTools.say(ctx.sender(), "UI test failed: " + e, ERR);
                MobBattlePlugin.log("UI test failed: " + e);
            }
        });
    }

    // ---- mask ----

    private static void mask(CommandContext ctx, PlayerRef pr, Ref<EntityStore> me, Store<EntityStore> store, long ms) {
        String pic = VsScreen.anyPicture();
        if (pic == null) {
            TestTools.say(ctx.sender(), "No VS picture is registered on this server yet (they are registered at boot).", ERR);
            return;
        }
        TestTools.say(ctx.sender(), "Mask test, picture " + pic + ". If you get disconnected now, MaskTexturePath is not accepted there.", OK);
        MobBattlePlugin.log("[uitest] mask test for " + pr.getUsername() + " with " + pic);
        show(pr, me, store, "MobBattle/MaskTest.ui", b -> {
            for (String id : new String[] {"A0", "A1", "A2", "A3", "B0", "B1", "B2", "B3"}) b.set("#" + id + "Img.AssetPath", pic);
            b.set("#Info.Text", "A: a picture spilling past its colored box = no clipping. B2/B3: only the middle half visible = the mask works there.");
        }, ms);
    }

    // ---- send to one player ----

    private static void send(CommandContext ctx, PlayerRef pr, Ref<EntityStore> me, Store<EntityStore> store, long ms) {
        long t = System.currentTimeMillis();
        String prefix = MorphHologram.registryPrefix();
        String dir = "Icons/MobBattle/Test/";
        String a = dir + "MobBattle_SendPackets_" + t + ".png";
        String b = dir + "MobBattle_SendRebuild_" + t + ".png";
        String late = dir + "MobBattle_SendLate_" + t + ".png";
        String never = dir + "MobBattle_NeverSent_" + t + ".png";
        try {
            var handler = pr.getPacketHandler();
            packets(handler, new RuntimeCommonAsset(prefix + a, picture(new Color(46, 157, 74), 0)));
            packets(handler, new RuntimeCommonAsset(prefix + b, picture(new Color(47, 111, 208), 1)));
            handler.writeNoCache(new RequestCommonAssetsRebuild());
        } catch (Exception e) {
            TestTools.say(ctx.sender(), "Send test failed while sending: " + e, ERR);
            return;
        }
        TestTools.say(ctx.sender(), "Sent 2 new pictures to you only; the screen opens in 1.5 s and a 4th picture arrives"
                + " 1.5 s after it. Did a freeze show?", OK);
        MobBattlePlugin.log("[uitest] send test for " + pr.getUsername() + ": " + a + ", " + b + " (+ rebuild), late " + late);
        World world = store.getExternalData().getWorld();
        world.scheduleAfter(() -> {
            if (!me.isValid()) return;
            show(pr, me, store, "MobBattle/SendTest.ui", ui -> {
                ui.set("#S0Img.AssetPath", a);
                ui.set("#S1Img.AssetPath", b);
                ui.set("#S2Img.AssetPath", never);
                ui.set("#S3Img.AssetPath", late);
                ui.set("#Info.Text", "A colored picture = that way works for one player. A red X or an empty box = it did not arrive.");
            }, ms);
            // the 4th picture only now, with the screen already showing its path
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    if (pr.isValid()) packets(pr.getPacketHandler(), new RuntimeCommonAsset(prefix + late, picture(new Color(224, 128, 32), 2)));
                } catch (Exception e) {
                    MobBattlePlugin.log("[uitest] late picture not sent: " + e);
                }
            }, 1500, TimeUnit.MILLISECONDS);
        }, 1500, TimeUnit.MILLISECONDS);
    }

    /** The asset packets of one file, to this player only (as the game's sendAsset, without its broadcast). */
    private static void packets(com.hypixel.hytale.server.core.io.PacketHandler handler, CommonAsset asset) {
        byte[] bytes = asset.getBlob().join();
        int parts = Math.max(1, (bytes.length + PART - 1) / PART);
        handler.write(new AssetInitialize(asset.toPacket(), bytes.length));
        for (int i = 0; i < parts; i++) {
            int from = i * PART, to = Math.min(bytes.length, from + PART);
            handler.write(new AssetPart(java.util.Arrays.copyOfRange(bytes, from, to)));
        }
        handler.write(new AssetFinalize());
    }

    /** 256x256: the color with a white circle (0), triangle (1) or square (2), so each slot is told apart at a glance. */
    private static byte[] picture(Color color, int shape) {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillRect(0, 0, 256, 256);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(18));
            switch (shape) {
                case 0 -> g.drawOval(48, 48, 160, 160);
                case 1 -> g.drawPolygon(new int[] {128, 218, 38}, new int[] {40, 210, 210}, 3);
                default -> g.drawRect(56, 56, 144, 144);
            }
        } finally {
            g.dispose();
        }
        try {
            return IndexedPng.encode(img);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the HUD ----

    private static void show(PlayerRef pr, Ref<EntityStore> me, Store<EntityStore> store, String file,
                             Consumer<UICommandBuilder> fill, long ms) {
        Player p = store.getComponent(me, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        if (huds.getCustomHud(KEY) != null) huds.removeCustomHud(pr, KEY);
        TestHud hud = new TestHud(pr, file, fill);
        huds.addCustomHud(pr, hud);
        World world = store.getExternalData().getWorld();
        world.scheduleAfter(() -> {
            if (!me.isValid()) return;
            Player now = store.getComponent(me, Player.getComponentType());
            if (now != null && now.getHudManager().getCustomHud(KEY) == hud) now.getHudManager().removeCustomHud(pr, KEY);
        }, ms, TimeUnit.MILLISECONDS);
    }

    private static final class TestHud extends CustomUIHud {
        private final String file;
        private final Consumer<UICommandBuilder> fill;

        TestHud(PlayerRef player, String file, Consumer<UICommandBuilder> fill) {
            super(player, KEY);
            this.file = file;
            this.fill = fill;
        }

        @Override
        protected void build(UICommandBuilder b) {
            b.append(file);
            fill.accept(b);
        }
    }
}
