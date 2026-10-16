package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /mobbattle cameraHud hide|show (2026-10-16, user: the hotbar, compass and boss bar covered the film, as the AFK
 * Experience mod hides them): while an intro, end camera or battle film has the player's camera, the game's own HUD
 * pieces are hidden (vanilla HudManager.setVisibleHudComponents, as AFK Experience does), keeping the chat and the
 * big titles; the ones they had come back when the camera ends (FightCamera.reset). Our own CustomUIHuds (cinema bars,
 * timer, cards) are not HUD components and stay. World thread for the HudManager.
 */
final class CameraHud {

    /** Kept during a camera: the chat and event titles (battle start / win titles). */
    private static final Set<HudComponent> KEEP = EnumSet.of(HudComponent.Chat, HudComponent.EventTitle);

    /** Per player: the HUD pieces they had before the first hide (restored once). */
    private static final Map<UUID, Set<HudComponent>> SAVED = new ConcurrentHashMap<>();

    private CameraHud() {
    }

    /** Hides the HUD for this player (cameraHud hide); a second hide while hidden keeps the first saved set. Any thread. */
    static void hide(PlayerRef player) {
        if (player == null || !"hide".equals(FxSettings.cameraHud)) return;
        hideAlways(player);
    }

    /** Whether the HUD is hidden now by a hide (so a later caller must not restore it under someone else). */
    static boolean hidden(PlayerRef player) {
        return player != null && SAVED.containsKey(player.getUuid());
    }

    /** Hides the HUD whatever cameraHud says (the VS screen always covers it). Any thread. */
    static void hideAlways(PlayerRef player) {
        if (player == null) return;
        onWorld(player, p -> {
            var huds = p.getHudManager();
            Set<HudComponent> now = huds.getVisibleHudComponents();
            if (SAVED.putIfAbsent(player.getUuid(), now == null ? EnumSet.noneOf(HudComponent.class) : copy(now)) != null) return;
            Set<HudComponent> keep = EnumSet.noneOf(HudComponent.class);
            if (now != null) for (HudComponent c : now) if (KEEP.contains(c)) keep.add(c);
            huds.setVisibleHudComponents(player, keep);
        });
    }

    /** Puts back what hide took away; no-op when nothing is hidden. Any thread. */
    static void restore(PlayerRef player) {
        if (player == null || !SAVED.containsKey(player.getUuid())) return;
        onWorld(player, p -> {
            Set<HudComponent> saved = SAVED.remove(player.getUuid());
            if (saved != null) p.getHudManager().setVisibleHudComponents(player, saved);
        });
    }

    private static Set<HudComponent> copy(Set<HudComponent> s) {
        return s.isEmpty() ? EnumSet.noneOf(HudComponent.class) : EnumSet.copyOf(s);
    }

    private static void onWorld(PlayerRef player, java.util.function.Consumer<Player> task) {
        if (!player.isValid()) return;
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        ref.getStore().getExternalData().getWorld().execute(() -> {
            try {
                Player p = ref.isValid() ? ref.getStore().getComponent(ref, Player.getComponentType()) : null;
                if (p != null) task.accept(p);
            } catch (RuntimeException e) {
                MobBattlePlugin.log("Camera HUD failed: " + e);
            }
        });
    }

    /** FightCamera.prune: forgets players who left (a reconnect gets the game's default HUD anyway). */
    static void prune() {
        if (SAVED.isEmpty()) return;
        SAVED.keySet().removeIf(id -> Universe.get().getPlayer(id) == null);
    }
}
