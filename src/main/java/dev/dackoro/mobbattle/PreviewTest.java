package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.protocol.packets.interface_.BlockChange;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.EditorSelection;
import com.hypixel.hytale.protocol.packets.interface_.FluidChange;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * /mobbattle test preview [two] [spin N] [tilt N] [scale N] (dev spike, 2026-10-16, user: a Smash-like VS screen with
 * the mobs' real models, since modded mobs often have no icon). Opens PreviewTest.ui, whose vanilla
 * PrefabPreviewComponent (the prefab editor's browser preview) shows what a BuilderToolPrefabPreview packet holds; its
 * entityChanges (ClipboardEntityChange: model, position, body/look orientation, scale) are filled like vanilla
 * BlockSelection.toClipboardEntityChange, from the mob you look at ("two": also the nearest other one, side by side
 * and facing each other). The editor sends tilt 23, spin 27, scale 100 (javap). Closing the page clears the preview.
 * ❓ Untested: whether models show outside the prefab editor, posed or animated, and for non-builders.
 */
final class PreviewTest {

    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";
    /** "two": the second mob is looked for this far around the first. */
    private static final double PAIR_RANGE = 24;
    /** "two": distance between the two in the preview (blocks, plus their widths). */
    private static final float PAIR_GAP = 1.5f;
    /** The packet is sent again this late, in case the page's component wasn't there for the first one. */
    private static final long RESEND_MS = 300;

    private PreviewTest() {
    }

    static void command(CommandContext ctx, String[] a) {
        if (!ctx.isPlayer()) {
            TestTools.say(ctx.sender(), "Only a player can open the preview.", ERR);
            return;
        }
        boolean two = false;
        int spin = 0, tilt = 10, scale = 100;
        for (int i = 1; i < a.length; i++) {
            String w = a[i].toLowerCase(Locale.ROOT);
            try {
                switch (w) {
                    case "two" -> two = true;
                    case "spin" -> spin = Integer.parseInt(a[++i]);
                    case "tilt" -> tilt = Integer.parseInt(a[++i]);
                    case "scale" -> scale = Integer.parseInt(a[++i]);
                    default -> {
                        TestTools.say(ctx.sender(), "Usage: /mobbattle test preview [two] [spin N] [tilt N] [scale N]", ERR);
                        return;
                    }
                }
            } catch (RuntimeException e) {
                TestTools.say(ctx.sender(), "Usage: /mobbattle test preview [two] [spin N] [tilt N] [scale N]", ERR);
                return;
            }
        }
        Ref<EntityStore> me = ctx.senderAsPlayerRef();
        if (me == null || !me.isValid()) return;
        Store<EntityStore> store = me.getStore();
        boolean pair = two;
        int s = spin, t = tilt, sc = scale;
        store.getExternalData().getWorld().execute(() -> open(ctx, me, store, pair, s, t, sc));
    }

    private static void open(CommandContext ctx, Ref<EntityStore> me, Store<EntityStore> store, boolean two,
                             int spin, int tilt, int scale) {
        if (!me.isValid()) return;
        Ref<EntityStore> first = ApplyCommand.lookedAt(me, store);
        if (first == null) {
            TestTools.say(ctx.sender(), "Look at a mob first (up to " + (int) ApplyCommand.LOOK_RANGE + " blocks).", ERR);
            return;
        }
        List<Ref<EntityStore>> shown = new ArrayList<>();
        shown.add(first);
        if (two) {
            Ref<EntityStore> second = nearestOther(first, me, store);
            if (second == null) {
                TestTools.say(ctx.sender(), "No second mob within " + (int) PAIR_RANGE + " blocks of the first.", ERR);
                return;
            }
            shown.add(second);
        }
        BuilderToolPrefabPreview packet = packet(shown, store, spin, tilt, scale);
        if (packet == null) {
            TestTools.say(ctx.sender(), "That mob has no model to show.", ERR);
            return;
        }
        PlayerRef player = store.getComponent(me, PlayerRef.getComponentType());
        Player p = store.getComponent(me, Player.getComponentType());
        if (player == null || p == null) return;
        p.getPageManager().openCustomPage(me, store, new Page(player));
        player.getPacketHandler().write(packet);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (player.isValid()) player.getPacketHandler().write(packet);
        }, RESEND_MS, TimeUnit.MILLISECONDS);
        StringBuilder names = new StringBuilder();
        for (Ref<EntityStore> r : shown) {
            if (names.length() > 0) names.append(" + ");
            names.append(nameOf(r, store));
        }
        TestTools.say(ctx.sender(), "Preview: " + names + " (spin " + spin + ", tilt " + tilt + ", scale " + scale
                + "). Tell whether the model shows, and if it moves.", OK);
        MobBattlePlugin.log("[test] preview of " + names + " for " + player.getUsername());
    }

    /** The preview packet: no blocks, one entity per mob, side by side along X and facing each other. */
    private static BuilderToolPrefabPreview packet(List<Ref<EntityStore>> mobs, Store<EntityStore> store,
                                                   int spin, int tilt, int scale) {
        List<ClipboardEntityChange> changes = new ArrayList<>();
        double[][] sizes = new double[mobs.size()][];
        double width = 0;
        for (int i = 0; i < mobs.size(); i++) {
            sizes[i] = sizeOf(mobs.get(i), store);
            width += sizes[i][1] * 2;
        }
        width += PAIR_GAP * (mobs.size() - 1);
        double x = -width / 2;
        double top = 1;
        for (int i = 0; i < mobs.size(); i++) {
            Ref<EntityStore> r = mobs.get(i);
            double half = sizes[i][1];
            ClipboardEntityChange c = change(r, store);
            if (c == null) return null;
            c.x = (float) (x + half);
            c.y = 0;
            c.z = 0;
            if (mobs.size() > 1) {
                // Facing the other one: the left one looks toward +X, the right one toward -X (yaw = atan2(-dx, -dz)).
                float yaw = (float) (i == 0 ? Math.atan2(-1, 0) : Math.atan2(1, 0));
                c.bodyOrientation = new Direction(yaw, 0f, 0f);
                c.lookOrientation = new Direction(yaw, 0f, 0f);
            }
            changes.add(c);
            x += half * 2 + PAIR_GAP;
            top = Math.max(top, sizes[i][0]);
        }
        BuilderToolPrefabPreview p = new BuilderToolPrefabPreview();
        p.tilt = tilt;
        p.spinSpeed = spin;
        p.previewScale = scale;
        p.blocksOmitted = false;
        p.blocksChange = new BlockChange[0];
        p.fluidsChange = new FluidChange[0];
        p.entityChanges = changes.toArray(new ClipboardEntityChange[0]);
        EditorSelection bounds = new EditorSelection();
        int halfWidth = (int) Math.ceil(width / 2);
        bounds.minX = -halfWidth;
        bounds.minY = 0;
        bounds.minZ = -1;
        bounds.maxX = halfWidth;
        bounds.maxY = (int) Math.ceil(top);
        bounds.maxZ = 1;
        p.bounds = bounds;
        return p;
    }

    /** As vanilla BlockSelection.toClipboardEntityChange, from a live entity: model, body and head rotation, scale. */
    private static ClipboardEntityChange change(Ref<EntityStore> r, Store<EntityStore> store) {
        ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
        if (mc == null || mc.getModel() == null) return null;
        ClipboardEntityChange c = new ClipboardEntityChange();
        c.model = mc.getModel().toPacket();
        TransformComponent t = store.getComponent(r, TransformComponent.getComponentType());
        if (t != null) {
            var rot = t.getRotation();
            c.bodyOrientation = new Direction(rot.yaw(), rot.pitch(), rot.roll());
        }
        HeadRotation head = store.getComponent(r, HeadRotation.getComponentType());
        if (head != null) {
            var rot = head.getRotation();
            c.lookOrientation = new Direction(rot.yaw(), rot.pitch(), rot.roll());
        }
        EntityScaleComponent es = store.getComponent(r, EntityScaleComponent.getComponentType());
        c.scale = es != null ? es.getScale() : 0f;   // 0 = the model's own scale, as vanilla
        return c;
    }

    /** {height, half width} of the hitbox; a normal mob's when unknown. */
    private static double[] sizeOf(Ref<EntityStore> r, Store<EntityStore> store) {
        BoundingBox bb = store.getComponent(r, BoundingBox.getComponentType());
        if (bb == null || bb.getBoundingBox() == null) return new double[] {2, 0.5};
        var box = bb.getBoundingBox();
        return new double[] {box.max.y - box.min.y, Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2};
    }

    private static Ref<EntityStore> nearestOther(Ref<EntityStore> first, Ref<EntityStore> me, Store<EntityStore> store) {
        Vector3d at = ChaosWand.positionOf(first, store);
        if (at == null) return null;
        Ref<EntityStore> best = null;
        double bestD = Double.MAX_VALUE;
        for (Ref<EntityStore> r : new ArrayList<>(com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInSphere(at, PAIR_RANGE, store))) {
            if (r.equals(first) || r.equals(me) || store.getComponent(r, NPCEntity.getComponentType()) == null) continue;
            if (!BattleManager.isAlive(r, store)) continue;
            Vector3d q = ChaosWand.positionOf(r, store);
            if (q == null) continue;
            double d = q.distanceSquared(at);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    private static String nameOf(Ref<EntityStore> r, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(r, NPCEntity.getComponentType());
        if (npc != null) return npc.getRoleName();
        PlayerRef pr = store.getComponent(r, PlayerRef.getComponentType());
        return pr != null ? pr.getUsername() : "?";
    }

    /** The page: only the preview; closing it clears the preview, as the prefab browser does. */
    private static final class Page extends BasicCustomUIPage {
        Page(PlayerRef player) {
            super(player, CustomPageLifetime.CanDismiss);
        }

        @Override
        public void build(UICommandBuilder ui) {
            ui.append("MobBattle/PreviewTest.ui");
        }

        @Override
        public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
            super.onDismiss(ref, store);
            BuilderToolPrefabPreview empty = new BuilderToolPrefabPreview();
            empty.blocksChange = new BlockChange[0];
            empty.fluidsChange = new FluidChange[0];
            empty.entityChanges = new ClipboardEntityChange[0];
            if (playerRef.isValid()) playerRef.getPacketHandler().write(empty);
        }
    }
}
