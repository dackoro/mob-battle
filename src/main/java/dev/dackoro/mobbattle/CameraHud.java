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
 * /mobbattle cameraHud hide|show (2026-10-16, playtest: the hotbar, compass and boss bar covered the film, as the AFK
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

    /** Players whose chat a VS screen hid (it was showing then): it comes back when the VS ends. */
    private static final Set<UUID> CHAT_HIDDEN = ConcurrentHashMap.newKeySet();

    /**
     * Hides the chat while a VS screen shows (playtest 2026-10-17: the countdown's chat lines sat over the VS). Only when
     * it shows now, so a player who hid their chat keeps it hidden. Any thread; queued after an earlier hide/restore.
     */
    static void hideChat(PlayerRef player) {
        if (player == null) return;
        onWorld(player, p -> {
            var huds = p.getHudManager();
            Set<HudComponent> now = huds.getVisibleHudComponents();
            if (now == null || !now.contains(HudComponent.Chat)) return;
            Set<HudComponent> less = copy(now);
            less.remove(HudComponent.Chat);
            huds.setVisibleHudComponents(player, less);
            CHAT_HIDDEN.add(player.getUuid());
        });
    }

    /** Puts back the chat hideChat took (after the VS end's restore, which may have brought it back already). */
    static void showChat(PlayerRef player) {
        if (player == null || !CHAT_HIDDEN.remove(player.getUuid())) return;
        onWorld(player, p -> {
            var huds = p.getHudManager();
            Set<HudComponent> now = huds.getVisibleHudComponents();
            Set<HudComponent> more = now == null ? EnumSet.noneOf(HudComponent.class) : copy(now);
            if (more.add(HudComponent.Chat)) huds.setVisibleHudComponents(player, more);
        });
    }

    /** A player who left during a VS: nothing to give back. */
    static void forgetChat(UUID player) {
        CHAT_HIDDEN.remove(player);
    }

    /** Players whose restore came while a VS screen covered them: the VS gives the HUD back when it ends. */
    private static final Set<UUID> DEFERRED = ConcurrentHashMap.newKeySet();

    /** Puts back what hide took away; no-op when nothing is hidden. Any thread. */
    static void restore(PlayerRef player) {
        if (player == null || !SAVED.containsKey(player.getUuid())) return;
        // a camera ending under a VS screen (another battle's intro, 2026-10-16: the HUD came back over the VS)
        if (VsScreen.showing(player)) {
            DEFERRED.add(player.getUuid());
            return;
        }
        DEFERRED.remove(player.getUuid());
        onWorld(player, p -> {
            Set<HudComponent> saved = SAVED.remove(player.getUuid());
            // A watch that began meanwhile set the spectator's own HUD (GameModeTypes.enter); the saved one would undo
            // it (audit 2026-10-17: a cut intro's restore landed after /mobbattle watch).
            if (saved != null && !BattleWatch.isWatching(player)) p.getHudManager().setVisibleHudComponents(player, saved);
        });
    }

    /** Whether a restore was held back while a VS screen showed (then the VS restores at its end). Clears the mark. */
    static boolean takeDeferred(PlayerRef player) {
        return player != null && DEFERRED.remove(player.getUuid());
    }

    private static Set<HudComponent> copy(Set<HudComponent> s) {
        return s.isEmpty() ? EnumSet.noneOf(HudComponent.class) : EnumSet.copyOf(s);
    }

    private static void onWorld(PlayerRef player, java.util.function.Consumer<Player> task) {
        onWorld(player, task, 3);
    }

    /**
     * On the player's world thread. A player who changed world meanwhile gets it on the new one (a few tries): the game
     * keeps the hidden HUD set across worlds, so a lost restore left it hidden there (audit 2026-10-17).
     */
    private static void onWorld(PlayerRef player, java.util.function.Consumer<Player> task, int tries) {
        if (tries <= 0 || !player.isValid()) return;
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onWorld(player, task, tries - 1),
                    500, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        try {
            ref.getStore().getExternalData().getWorld().execute(() -> {
                if (player.getReference() != ref || !ref.isValid()) {
                    onWorld(player, task, tries - 1);
                    return;
                }
                try {
                    Player p = ref.getStore().getComponent(ref, Player.getComponentType());
                    if (p != null) task.accept(p);
                } catch (RuntimeException e) {
                    MobBattlePlugin.log("Camera HUD failed: " + e);
                }
            });
        } catch (RuntimeException e) {   // the world stopped taking tasks
            MobBattlePlugin.log("Camera HUD: world task refused: " + e);
        }
    }

    /** FightCamera.prune: forgets players who left (a reconnect gets the game's default HUD anyway). */
    static void prune() {
        if (!DEFERRED.isEmpty()) DEFERRED.removeIf(id -> Universe.get().getPlayer(id) == null);
        if (SAVED.isEmpty()) return;
        SAVED.keySet().removeIf(id -> Universe.get().getPlayer(id) == null);
    }
}
