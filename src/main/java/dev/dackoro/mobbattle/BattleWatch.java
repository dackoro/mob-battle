package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.gamemode.GameModeTypes;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /mobbattle watch: spectate a battle that is already running, fighter by fighter. Built on the game's own spectator
 * (0.7.0-pre): our game mode {@code MobBattle_Spectator} (Server/Entity/GameMode, Parent Spectator: flying, invisible,
 * invulnerable) and the vanilla {@link Spectating} component, whose OnSpectatingChange system attaches the camera to
 * the target in third person (the mouse turns it around the target) and FollowTarget keeps the player's body with it.
 * Our game mode binds right click = nearest next fighter, left click = back to the previous one, Q = exit ({@link WatchInteraction}),
 * where the vanilla HardcoreSpectator cycles players only.
 * <p>
 * When the watched fighter is killed, the camera goes to its killer ({@link #onKill}, from KillTracker); when it is
 * gone some other way (disintegrated, unloaded) or the vanilla system let go of it, {@link #tick} picks the battle's
 * latest killer or the nearest fighter left. A card with the watched fighter's stats is on the left
 * (Common/UI/Custom/MobBattle/WatchCard.ui). When the battle ends the camera stays on the winner for
 * {@link #END_MS}, then the player gets their game mode and position back.
 */
final class BattleWatch extends CustomUIHud {

    static final String GAME_MODE = "MobBattle_Spectator";
    static final String KEY = "MobBattleWatch";
    private static final String UI = "MobBattle/WatchCard.ui";
    private static final long END_MS = 5000;
    private static final long CARD_MS = 1000;
    private static final String WARN = "#ffcc55";

    private static final class Watch {
        final BattleManager.Battle battle;
        final Store<EntityStore> store;
        final Vector3d home;
        final Rotation3f homeLook;
        volatile long endsAt;
        long nextCard;
        /** Fighters watched before, latest last (left click goes back). */
        final java.util.ArrayDeque<Ref<EntityStore>> history = new java.util.ArrayDeque<>();
        /** Fighters already shown in this round of right clicks (identity set; pruned of the gone ones each tick). */
        final java.util.Set<Ref<EntityStore>> seen = ConcurrentHashMap.newKeySet();

        Watch(BattleManager.Battle battle, Store<EntityStore> store, Vector3d home, Rotation3f homeLook) {
            this.battle = battle;
            this.store = store;
            this.home = home;
            this.homeLook = homeLook;
        }
    }

    /** Watching players by UUID (never a PlayerRef: a reconnect makes a new one). */
    private static final Map<UUID, Watch> WATCHING = new ConcurrentHashMap<>();

    private BattleWatch(PlayerRef player) {
        super(player, KEY);
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI);
    }

    static boolean any() {
        return !WATCHING.isEmpty();
    }

    static boolean isWatching(PlayerRef player) {
        return player != null && !WATCHING.isEmpty() && WATCHING.containsKey(player.getUuid());
    }

    /** /mobbattle watch (world thread): the player's own battle in this world, else the nearest one. */
    static void start(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store) {
        if (WATCHING.containsKey(player.getUuid())) {
            player.sendMessage(Message.raw("You are already watching. Q or /mobbattle watch exit to leave.").color(WARN));
            return;
        }
        if (BattleManager.isPlayerFighter(me)) {
            player.sendMessage(Message.raw("You are fighting in a battle: you can't watch it.").color(WARN));
            return;
        }
        if (Spectating.isSpectating(me, store)) {
            player.sendMessage(Message.raw("You are already spectating (/spectate exit first).").color(WARN));
            return;
        }
        TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
        if (t == null) return;
        Vector3d at = new Vector3d(t.getPosition());
        BattleManager.Battle battle = null;
        double best = Double.MAX_VALUE;
        for (BattleManager.Battle b : BattleManager.battles()) {
            if (b.over || b.store != store) continue;
            Ref<EntityStore> near = nearestAlive(b, at, null, store);
            if (near == null) continue;
            double d = distSq(near, at, store);
            // Your own battle first, whatever the distance.
            if (b.owner() != null && b.owner().getUuid().equals(player.getUuid())) d -= 1e12;
            if (d < best) {
                best = d;
                battle = b;
            }
        }
        if (battle == null) {
            player.sendMessage(Message.raw("No battle to watch in this world.").color(WARN));
            return;
        }
        watch(player, me, store, battle, at, t);
    }

    /**
     * /mobbattle autoWatch: a battle just started (Arena.setup, after its countdown). The camera viewers of its
     * owner (cameraScope) start watching it, unless they fight, already watch or spectate; it ends their intro.
     */
    static void auto(BattleManager.Battle battle) {
        if (battle == null || battle.owner() == null || battle.store == null || !KindFx.autoWatch(battle.kind)) return;
        Store<EntityStore> store = battle.store;
        // Game mode and camera changes outside the system iteration that started the battle.
        store.getExternalData().getWorld().execute(() -> {
            if (battle.over || store.isShutdown()) return;
            for (PlayerRef p : FightFx.cameraViewers(battle.owner())) {
                Ref<EntityStore> me = p.isValid() ? p.getReference() : null;
                if (me == null || !me.isValid() || me.getStore() != store || WATCHING.containsKey(p.getUuid())
                        || battle.has(me) || BattleManager.isPlayerFighter(me) || Spectating.isSpectating(me, store)
                        || !BattleManager.isAlive(me, store)) continue;
                TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
                if (t != null) watch(p, me, store, battle, new Vector3d(t.getPosition()), t);
            }
        });
    }

    private static void watch(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store, BattleManager.Battle battle,
                              Vector3d at, TransformComponent t) {
        Ref<EntityStore> target = firstTarget(battle, at, store);
        if (target == null) {
            player.sendMessage(Message.raw("No battle to watch in this world.").color(WARN));
            return;
        }
        HeadRotation head = store.getComponent(me, HeadRotation.getComponentType());
        Rotation3f look = head != null ? new Rotation3f(head.getRotation()) : new Rotation3f(t.getRotation());
        // An intro still playing (cameraTime longer than the countdown) or an orbit gives way to the watch camera.
        FightCamera.cancel(player);
        if (!GameModeTypes.enter(me, store, GAME_MODE)) {
            player.sendMessage(Message.raw("Can't start watching right now.").color(WARN));
            return;
        }
        store.putComponent(me, Spectating.getComponentType(), new Spectating(target));
        WATCHING.put(player.getUuid(), new Watch(battle, store, at, look));
        MobBattlePlugin.debug("watch: " + player.getUsername() + " watches battle #" + battle.id.toString().substring(0, 8));
        player.sendMessage(Message.raw("Watching the battle: right click = nearest fighter, left click = back to the previous one, "
                + "mouse = turn the camera, Q = exit (or /mobbattle watch exit).").color("#ffd23e"));
    }

    /**
     * Right click (dir +1): the fighter still standing nearest to the one watched, skipping the ones already seen
     * in this round (so two fighters side by side don't bounce back and forth; when everyone was seen, a new round
     * starts). Left click (-1): back to the fighter watched before (world thread).
     */
    static void cycle(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc, int dir) {
        Watch w = WATCHING.get(player.getUuid());
        if (w == null) {
            player.sendMessage(Message.raw("Not watching a battle. Q leaves the spectator mode.").color(WARN));
            return;
        }
        Spectating sp = acc.getComponent(me, Spectating.getComponentType());
        Ref<EntityStore> current = sp != null ? sp.getTargetRef() : null;
        Ref<EntityStore> next = null;
        if (dir < 0) {
            synchronized (w.history) {
                while (!w.history.isEmpty() && next == null) {
                    Ref<EntityStore> r = w.history.removeLast();
                    if (r != current && r.isValid() && w.battle.has(r) && BattleManager.isAlive(r, acc)) next = r;
                }
            }
            if (next == null) {
                player.sendMessage(Message.raw("No earlier fighter to go back to: right click for the nearest one.").color("#aaaaaa"));
                return;
            }
        } else {
            Vector3d from = current != null && current.isValid() ? ChaosWand.positionOf(current, acc) : null;
            if (from == null) {
                TransformComponent t = acc.getComponent(me, TransformComponent.getComponentType());
                from = t != null ? new Vector3d(t.getPosition()) : new Vector3d();
            }
            next = nearestAlive(w.battle, from, current, w.seen, acc);
            if (next == null) {
                // Everyone was seen: a new round, from the one watched now.
                w.seen.clear();
                next = nearestAlive(w.battle, from, current, w.seen, acc);
            }
            if (next == null) return;
            if (current != null) {
                synchronized (w.history) {
                    w.history.addLast(current);
                    if (w.history.size() > HISTORY) w.history.removeFirst();
                }
            }
        }
        if (current != null) w.seen.add(current);
        w.seen.add(next);
        acc.putComponent(me, Spectating.getComponentType(), new Spectating(next));
    }

    /** How many fighters left click can go back through. */
    private static final int HISTORY = 32;

    /** Q, /mobbattle watch exit: back to the player's own game mode, camera and position (world thread). */
    static void exit(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        Watch w = WATCHING.remove(player.getUuid());
        hide(player, acc);
        var mode = GameModeTypes.getCurrentType(me, acc);
        if (mode == null || !mode.isSpectator()) {
            if (w == null) player.sendMessage(Message.raw("You are not watching a battle.").color(WARN));
            return;
        }
        if (!GameModeTypes.exit(me, acc)) {
            player.sendMessage(Message.raw("Can't stop watching right now.").color(WARN));
            return;
        }
        acc.tryRemoveComponent(me, Spectating.getComponentType());
        // Spectators fly through blocks: back where they started watching, not inside a hill.
        if (w != null && w.store == me.getStore()) {
            acc.putComponent(me, Teleport.getComponentType(), Teleport.createForPlayer(w.home, w.homeLook));
        }
        player.sendMessage(Message.raw("Stopped watching.").color("#aaaaaa"));
    }

    /** /mobbattle stop: everyone stops watching (each on their world thread). */
    static int exitAll() {
        int n = 0;
        for (UUID id : new ArrayList<>(WATCHING.keySet())) {
            PlayerRef p = Universe.get().getPlayer(id);
            Ref<EntityStore> ref = p != null && p.isValid() ? p.getReference() : null;
            if (ref == null || !ref.isValid()) {
                WATCHING.remove(id);
                continue;
            }
            Store<EntityStore> st = ref.getStore();
            st.getExternalData().getWorld().execute(() -> exit(p, ref, st));
            n++;
        }
        return n;
    }

    /** KillTracker: the camera of whoever watched {@code victim} goes to its {@code killer}. */
    static void onKill(Ref<EntityStore> victim, Ref<EntityStore> killer, ComponentAccessor<EntityStore> acc) {
        if (WATCHING.isEmpty() || killer == null || !killer.isValid() || killer.getStore() != victim.getStore()) return;
        for (UUID id : WATCHING.keySet()) {
            PlayerRef p = Universe.get().getPlayer(id);
            Ref<EntityStore> ref = p != null && p.isValid() ? p.getReference() : null;
            if (ref == null || !ref.isValid() || ref.getStore() != victim.getStore()) continue;
            Spectating sp = acc.getComponent(ref, Spectating.getComponentType());
            if (sp != null && sp.getTargetRef() == victim) {
                acc.putComponent(ref, Spectating.getComponentType(), new Spectating(killer));
            }
        }
    }

    /** Every 250 ms per world (BattleLifecycleSystem, on the world thread). */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Watch> e : WATCHING.entrySet()) {
            Watch w = e.getValue();
            if (w.store != store && !w.store.isShutdown()) continue;
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            Ref<EntityStore> me = p != null && p.isValid() ? p.getReference() : null;
            if (me == null || !me.isValid() || me.getStore() != w.store) {
                // Left the server or the world: nothing to give back here.
                WATCHING.remove(e.getKey());
                continue;
            }
            // Left our game mode some other way (/gamemode, /spectate exit): forget it.
            if (!GAME_MODE.equals(GameModeTypes.getCurrentTypeId(me, store))) {
                WATCHING.remove(e.getKey());
                hide(p, store);
                continue;
            }
            w.seen.removeIf(r -> !r.isValid());
            synchronized (w.history) {
                w.history.removeIf(r -> !r.isValid());
            }
            Spectating sp = store.getComponent(me, Spectating.getComponentType());
            Ref<EntityStore> target = sp != null ? sp.getTargetRef() : null;
            if (w.battle.over) {
                if (w.endsAt == 0) {
                    w.endsAt = now + END_MS;
                    Ref<EntityStore> winner = winner(w.battle, store);
                    if (winner != null && winner != target) {
                        store.putComponent(me, Spectating.getComponentType(), new Spectating(winner));
                        target = winner;
                    }
                } else if (now >= w.endsAt) {
                    exit(p, me, store);
                    continue;
                }
            } else if (target == null || !target.isValid() || !w.battle.has(target) || !BattleManager.isAlive(target, store)) {
                Ref<EntityStore> next = replacement(w.battle, target, me, store);
                if (next != null) {
                    store.putComponent(me, Spectating.getComponentType(), new Spectating(next));
                    target = next;
                }
            }
            if (now >= w.nextCard) {
                w.nextCard = now + CARD_MS;
                show(p, me, store, w, target);
            }
        }
    }

    // ---- picking fighters ----

    /** The battle's top killer still standing, else the fighter nearest to the player. */
    private static Ref<EntityStore> firstTarget(BattleManager.Battle b, Vector3d at, Store<EntityStore> store) {
        Ref<EntityStore> top = topKillerAlive(b, store);
        return top != null ? top : nearestAlive(b, at, null, store);
    }

    /** The watched fighter is gone: the latest killer if standing, else the fighter nearest to the camera. */
    private static Ref<EntityStore> replacement(BattleManager.Battle b, Ref<EntityStore> old, Ref<EntityStore> me,
                                                Store<EntityStore> store) {
        Ref<EntityStore> last = b.lastKiller;
        if (last != null && last != old && b.has(last) && BattleManager.isAlive(last, store)) return last;
        TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
        return nearestAlive(b, t != null ? t.getPosition() : new Vector3d(), old, store);
    }

    private static Ref<EntityStore> winner(BattleManager.Battle b, Store<EntityStore> store) {
        Ref<EntityStore> top = topKillerAlive(b, store);
        if (top != null) return top;
        List<Ref<EntityStore>> alive = aliveSorted(b, store);
        return alive.isEmpty() ? null : alive.get(0);
    }

    private static Ref<EntityStore> topKillerAlive(BattleManager.Battle b, ComponentAccessor<EntityStore> acc) {
        Ref<EntityStore> best = null;
        int most = 0;
        for (Map.Entry<Ref<EntityStore>, BattleManager.Kills> k : b.kills.entrySet()) {
            Ref<EntityStore> r = k.getKey();
            if (k.getValue().count() > most && b.has(r) && BattleManager.isAlive(r, acc)) {
                most = k.getValue().count();
                best = r;
            }
        }
        return best;
    }

    private static Ref<EntityStore> nearestAlive(BattleManager.Battle b, org.joml.Vector3dc at, Ref<EntityStore> skip,
                                                 ComponentAccessor<EntityStore> acc) {
        return nearestAlive(b, at, skip, java.util.Set.of(), acc);
    }

    /** The fighter still standing nearest to {@code at}, other than {@code skip} and the ones in {@code exclude}. */
    private static Ref<EntityStore> nearestAlive(BattleManager.Battle b, org.joml.Vector3dc at, Ref<EntityStore> skip,
                                                 java.util.Set<Ref<EntityStore>> exclude, ComponentAccessor<EntityStore> acc) {
        Ref<EntityStore> best = null;
        double bestD = Double.MAX_VALUE;
        for (Ref<EntityStore> r : b.members()) {
            if (r == skip || exclude.contains(r) || !r.isValid() || !BattleManager.isAlive(r, acc)) continue;
            double d = distSq(r, at, acc);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    private static double distSq(Ref<EntityStore> r, org.joml.Vector3dc at, ComponentAccessor<EntityStore> acc) {
        TransformComponent t = acc.getComponent(r, TransformComponent.getComponentType());
        return t != null ? t.getPosition().distanceSquared(at) : Double.MAX_VALUE;
    }

    /** Fighters still standing, side by side (team order). */
    private static List<Ref<EntityStore>> aliveSorted(BattleManager.Battle b, ComponentAccessor<EntityStore> acc) {
        List<Ref<EntityStore>> alive = new ArrayList<>();
        for (Ref<EntityStore> r : b.members()) if (r.isValid() && BattleManager.isAlive(r, acc)) alive.add(r);
        alive.sort((x, y) -> {
            int c = Integer.compare(b.team(x), b.team(y));
            return c != 0 ? c : Integer.compare(System.identityHashCode(x), System.identityHashCode(y));
        });
        return alive;
    }

    // ---- the card ----

    private static void show(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store, Watch w,
                             Ref<EntityStore> mob) {
        Player p = store.getComponent(me, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof BattleWatch)) {
            hud = new BattleWatch(player);
            huds.addCustomHud(player, hud);
        }
        BattleManager.Battle b = w.battle;
        UICommandBuilder ui = new UICommandBuilder();
        ui.set("#Title.TextSpans", Message.translation("server.mobbattle.timer." + b.kind.key));
        int left = aliveSorted(b, store).size();
        line(ui, "#Left", b.over ? "Battle over" : left + (left == 1 ? " fighter left" : " fighters left"), "#c8d4e0");
        if (mob == null || !mob.isValid()) {
            line(ui, "#Name", "?", "#888888");
            for (String id : new String[] {"#Side", "#Health", "#Attack", "#Defense", "#Size", "#Kills"}) line(ui, id, "", "#888888");
            hud.update(false, ui);
            return;
        }
        boolean up = BattleManager.isAlive(mob, store);
        String name = ChaosWand.nameOf(mob, store);
        int team = b.team(mob);
        line(ui, "#Name", DuelCard.shorten(name == null ? "?" : name.replace('_', ' ')),
                b.isTeams() ? b.color(team) : up ? "#ffd23e" : "#888888");
        line(ui, "#Side", sideText(b, team), b.isTeams() ? b.color(team) : "#96a9be");
        line(ui, "#Health", DuelCard.healthText(mob, store, up), up ? "#ff6b5e" : "#888888");
        line(ui, "#Attack", "Attack  " + DuelCard.stat(mob, Buffs.Stat.DAMAGE), DuelCard.color(mob, Buffs.Stat.DAMAGE));
        line(ui, "#Defense", "Defense  " + DuelCard.stat(mob, Buffs.Stat.DEFENSE), DuelCard.color(mob, Buffs.Stat.DEFENSE));
        float scale = DuelCard.scaleOf(mob, store);
        boolean scaled = Math.abs(scale - 1f) > 0.01f;
        line(ui, "#Size", "Size  " + (scaled ? "x" + String.format(java.util.Locale.ROOT, "%.2f", scale) : "base"),
                scaled ? "#7dff8a" : "#c8d4e0");
        BattleManager.Kills k = b.kills.get(mob);
        line(ui, "#Kills", "Kills  " + (k != null ? k.count() : 0), k != null ? "#ffd23e" : "#c8d4e0");
        hud.update(false, ui);
    }

    private static String sideText(BattleManager.Battle b, int team) {
        return switch (b.kind) {
            case TEAMS -> "Team " + b.name(team);
            case ALL_VS_ONE -> team == 1 ? "The lone target" : "Attacker";
            case ROYALE -> "Every one for themselves";
            case DUEL -> "Duelist";
        };
    }

    private static void line(UICommandBuilder b, String id, String text, String color) {
        b.set(id + ".TextSpans", Message.raw(text).color(color));
    }

    private static void hide(PlayerRef player, ComponentAccessor<EntityStore> acc) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        Player p = acc.getComponent(ref, Player.getComponentType());
        if (p != null && p.getHudManager().getCustomHud(KEY) != null) {
            p.getHudManager().removeCustomHud(player, KEY);
        }
    }
}
