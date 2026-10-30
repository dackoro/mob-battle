package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.gamemode.GameModeTypes;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The battle film's own game mode (2026-10-28, playtest: Q leaves the watch, the film should have it too). Q only reaches
 * the server through a game mode's Ability1 binding (our wands have none that the client sends), so while a film runs
 * the viewer is in Server/Entity/GameMode/MobBattle_FilmViewer.json: Q = {@link Exit}, clicks and the inventory do
 * nothing, invulnerable and intangible meanwhile. Not a Spectator child (as the watch's is): that one's "Spectator"
 * effect drew the player as a ghost in the film's shots of them, its flag added the game's "Watching" label, and its
 * HUD set hid the hotbar (playtest 2026-10-28). Every end of the film's camera goes
 * through FightCamera.reset, which calls {@link #leave}: their own game mode back and the spot they stood on (spectators
 * fly through blocks). A viewer who disconnects mid-film comes back in the saved mode and gets theirs back on ready.
 * ❓ Untested in game.
 */
final class FilmMode {
    static final String GAME_MODE = "MobBattle_FilmViewer";

    /** The film's game mode. */
    static boolean isFilmMode(String id) {
        return GAME_MODE.equals(id);
    }

    /** Where they stood and whether they were flying before the film (Flight). */
    private record Home(Store<EntityStore> store, Vector3d at, Rotation3f look, boolean flying) {
    }

    /** Viewers put in the film game mode, with where they stood. */
    private static final Map<UUID, Home> HOMES = new ConcurrentHashMap<>();

    private FilmMode() {
    }

    /** World thread, as the film starts: into the film game mode, unless already in a spectator mode (the watch). */
    static void enter(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store) {
        // already in it (an intro cut film A, then handed over to film B): A's home stays (audit 2026-10-29)
        if (isFilmMode(GameModeTypes.getCurrentTypeId(me, store))) return;
        var mode = GameModeTypes.getCurrentType(me, store);
        if (mode != null && mode.isSpectator()) {
            MobBattlePlugin.debug("film: " + player.getUsername() + " stays in " + GameModeTypes.getCurrentTypeId(me, store) + " (a spectator mode): no Q");
            return;
        }
        TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
        if (t == null) return;
        Home home = new Home(store, new Vector3d(t.getPosition()), new Rotation3f(t.getRotation()), Flight.of(me, store));
        String into = GAME_MODE;
        if (!GameModeTypes.enter(me, store, into)) {
            MobBattlePlugin.log("Film: " + player.getUsername() + " could not enter " + into + " (Q won't leave the film).");
            return;
        }
        HOMES.put(player.getUuid(), home);
        MobBattlePlugin.debug("film: " + player.getUsername() + " in " + into);
    }

    /** Any end of a camera (FightCamera.reset): back from the film game mode, if the player is in it. */
    static void leave(PlayerRef player) {
        if (player == null || !player.isValid() || HOMES.isEmpty() || !HOMES.containsKey(player.getUuid())) return;
        Ref<EntityStore> me = player.getReference();
        if (me == null || !me.isValid()) {
            HOMES.remove(player.getUuid());
            return;
        }
        Store<EntityStore> store = me.getStore();
        store.getExternalData().getWorld().execute(() -> {
            // a newer film already put them back in (an end camera then a new film): leave it alone
            if (!me.isValid() || FilmCamera.isFilming(player)) return;
            exitNow(player, me, store);
        });
    }

    /**
     * World thread, before another mode is entered on top (the watch): out of the film game mode now, without the trip
     * home (audit 2026-10-29: the watch's spectator body was teleported there twice while it followed a fighter).
     * Returns where they stood before the film (null if not in it), for the watch to bring them back to.
     */
    static Vector3d leaveNow(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        Home home = HOMES.get(player.getUuid());
        if (home == null) return null;
        exitNow(player, me, acc, false);
        return home.store() == me.getStore() ? home.at() : null;
    }

    /** Whether the player was flying before the film, null if not in the film game mode (the watch keeps it). */
    static Boolean flyingBefore(PlayerRef player) {
        Home home = HOMES.get(player.getUuid());
        return home != null ? home.flying() : null;
    }

    /** Plugin shutdown: everyone in the film game mode back to their own, on their world thread. */
    static void exitAll() {
        for (UUID id : new java.util.ArrayList<>(HOMES.keySet())) {
            PlayerRef p = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id);
            Ref<EntityStore> me = p != null && p.isValid() ? p.getReference() : null;
            if (me == null || !me.isValid()) {
                HOMES.remove(id);
                continue;
            }
            Store<EntityStore> store = me.getStore();
            try {
                store.getExternalData().getWorld().execute(() -> {
                    if (me.isValid()) exitNow(p, me, store, true);
                });
            } catch (RuntimeException ignored) {
                // world already stopping: onReady gives the mode back on the next join
            }
        }
    }

    /** World thread: own game mode back and, in the same world and if {@code teleport}, the spot they stood on. */
    private static void exitNow(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        exitNow(player, me, acc, true);
    }

    private static void exitNow(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc, boolean teleport) {
        Home home = HOMES.remove(player.getUuid());
        if (!isFilmMode(GameModeTypes.getCurrentTypeId(me, acc))) return;
        if (!GameModeTypes.exit(me, acc)) {
            MobBattlePlugin.log("Film: " + player.getUsername() + " could not leave the film game mode.");
            return;
        }
        if (teleport && home != null && home.store() == me.getStore()) {
            // flying or walking as before the film (not when the watch takes over: it gives it back when it ends)
            Flight.restoreLater(me, home.store(), home.flying());
            // The spot comes a moment after the mode, and again if they ended up under it (2026-10-29, playtest screenshot:
            // after a film the player was left flying under the arena floor; the teleport went out in the same tick as
            // the game mode change, while the client still had the film mode's free, intangible flight).
            var world = home.store().getExternalData().getWorld();
            world.scheduleAfter(() -> sendHome(me, home, false), HOME_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            world.scheduleAfter(() -> sendHome(me, home, true), HOME_CHECK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** Back to the film start spot (world thread); {@code onlyIfBelow}: only when they ended up under it. */
    private static void sendHome(Ref<EntityStore> me, Home home, boolean onlyIfBelow) {
        // not skipped while the film mode is still on: the mode change can land after this (2026-10-29, playtest: still
        // left under the floor after the first fix); a new film started meanwhile has its own home
        if (!me.isValid() || me.getStore() != home.store()) return;
        PlayerRef p = home.store().getComponent(me, PlayerRef.getComponentType());
        if (p != null && (FilmCamera.isFilming(p) || BattleWatch.isWatching(p))) return;
        if (onlyIfBelow) {
            TransformComponent t = home.store().getComponent(me, TransformComponent.getComponentType());
            if (t == null || t.getPosition().y > home.at().y - HOME_BELOW) return;
        }
        home.store().putComponent(me, Teleport.getComponentType(), Teleport.createForPlayer(home.at(), home.look()));
    }

    /** sendHome: the delay after the game mode change, the later check, and how far under the spot counts as lost. */
    private static final long HOME_DELAY_MS = 150, HOME_CHECK_MS = 900;
    private static final double HOME_BELOW = 1.5;

    /** PlayerReadyEvent: left mid-film (disconnect, server stop) and came back in the saved film game mode. */
    static void onReady(Ref<EntityStore> me) {
        if (me == null || !me.isValid()) return;
        Store<EntityStore> store = me.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!me.isValid() || !isFilmMode(GameModeTypes.getCurrentTypeId(me, store))) return;
            PlayerRef p = store.getComponent(me, PlayerRef.getComponentType());
            if (p != null && !FilmCamera.isFilming(p)) exitNow(p, me, store);
        });
    }

    /** BattleManager.maintain: players who left the server. */
    static void prune() {
        if (!HOMES.isEmpty()) {
            HOMES.keySet().removeIf(id -> com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id) == null);
        }
    }


    /** Q in the film game mode: the film ends (its camera reset brings the player's own game mode back). */
    public static final class Exit extends SimpleInstantInteraction {
        public static final String TYPE = "MobBattle_FilmExit";
        public static final BuilderCodec<Exit> CODEC = BuilderCodec
                .builder(Exit.class, Exit::new, SimpleInstantInteraction.CODEC)
                .documentation("/mobbattle film: stop the battle film, back to your game mode and position.")
                .build();

        @Override
        protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
            CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
            PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
            if (player == null) {
                context.getState().state = InteractionState.Failed;
                return;
            }
            MobBattlePlugin.debug("film: " + type + " from " + player.getUsername() + (FilmCamera.isFilming(player) ? "" : " (no film running)"));
            if (FilmCamera.isFilming(player)) {
                FilmCamera.exit(player);
            } else {
                // stuck in the mode without a film (it ended between two checks): out now
                exitNow(player, context.getEntity(), buffer);
            }
        }
    }
}
