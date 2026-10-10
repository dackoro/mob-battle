package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.packets.interface_.PortalDef;
import com.hypixel.hytale.protocol.packets.interface_.PortalState;
import com.hypixel.hytale.protocol.packets.interface_.UpdatePortal;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Battle arenas, per battle kind (/mobbattle arena, timer, timeUp):
 * <ul>
 *   <li>show: the area stays drawn for the whole fight (the Chaos Wand area style and color);</li>
 *   <li>wall: fighters that reach its edge are pushed back in (the game's knockback, as the server's
 *       "/knockback apply" debug command sets it); far outside (e.g. flung away), teleported back in;</li>
 *   <li>timer: the game's portal timer box (UpdatePortal, as PortalWorld sends it), and what happens at 0.</li>
 * </ul>
 * The radius is the area's own: areaRadius for the Chaos Wand, {@link #around} (or arenaSize) for duels and teams.
 */
final class Arena {

    /** /mobbattle arena &lt;kind&gt; on|show|wall|off. */
    enum Mode {
        OFF, SHOW, WALL, ON;

        boolean shows() { return this == SHOW || this == ON; }
        boolean walls() { return this == WALL || this == ON; }
    }

    /** /mobbattle timeUp &lt;kind&gt;: what happens when the timer reaches 0. */
    enum TimeUp { DRAW, KILL, CONTINUE, HEALTH, JUDGE }

    private static final long DRAW_MS = 1000;
    private static final float DRAW_SECONDS = 1.05f;
    /**
     * The border glow of each redraw emits this long. Arena.tick runs every 250 ms, so a redraw can land up to
     * ~1.25 s after the last one: with DRAW_SECONDS the columns stopped emitting for a moment every second and
     * looked cut into pulses. Particles overlap without showing it; the floor disc would get darker, so it keeps
     * DRAW_SECONDS. Still HELD_AREA_SECONDS at most, so the short glow is used.
     */
    private static final float GLOW_SECONDS = FightFx.HELD_AREA_SECONDS;
    private static final long WALL_MS = 250;
    /** Pushed back from this close to the edge; teleported back beyond this far outside it. */
    private static final double EDGE_MARGIN = 1.0;
    private static final double TELEPORT_BEYOND = 8.0;
    /** Pushes in a row (one per 250 ms) that didn't bring a fighter in (wedged in rock, a cave pocket): teleport it. */
    private static final int MAX_PUSHES = 8;
    /** Push toward the center (horizontal and up), like a strong hit's knockback. */
    private static final double PUSH = 7.0;
    private static final double PUSH_UP = 2.5;

    private static final class State {
        final BattleManager.Battle battle;
        /** Fighter -> pushes in a row while still outside (world thread only): stuck ones get teleported in. */
        final java.util.Map<Ref<EntityStore>, Integer> pushes = new java.util.HashMap<>();
        final Store<EntityStore> store;
        final Vector3d center;
        /** Radius at the start; with arenaShrink it closes linearly to endRadius between shrinkFrom and shrinkUntil. */
        final double startRadius;
        final double endRadius;
        final long shrinkFrom;
        final long shrinkUntil;
        /** Glow columns for the whole battle (those of the starting radius), so a closing edge keeps its angles. */
        final int glowPoints;
        final boolean show;
        final boolean wall;
        final String style;
        final String color;
        final String glowColor;
        final int seconds;
        final String titleKey;
        /** timerStyle portal: the game's portal box instead of our TimerHud. */
        final boolean portal = "portal".equals(FxSettings.timerStyle);
        /** Who sees the timer: the title viewers at the start (/mobbattle titleScope). */
        final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
        volatile long endsAt;
        long nextDraw;
        long nextWall;
        volatile long nextTimer;

        State(BattleManager.Battle battle, Store<EntityStore> store, Vector3d center, double radius, Arena.Mode mode,
              String color, int seconds) {
            this.battle = battle;
            this.store = store;
            this.center = center;
            this.startRadius = radius;
            this.glowPoints = FightFx.glowPoints(radius);
            boolean shrink = FxSettings.shrinks(battle.kind) && radius > FxSettings.shrinkTo;
            this.endRadius = shrink ? FxSettings.shrinkTo : radius;
            long now = System.currentTimeMillis();
            this.shrinkFrom = now;
            // Over the timer, so the zone is smallest when time is up; with no timer, over arenaShrinkTime.
            this.shrinkUntil = now + 1000L * (seconds > 0 ? seconds : FxSettings.shrinkSeconds);
            // A closing zone is always drawn and always a wall: that is what brings the fighters together.
            this.show = mode.shows() || shrink;
            this.wall = mode.walls() || shrink;
            // The ground per areaStyle in the area color; the border glow (if the style has one) in the action's
            // borderColor: allVsOne and royale use theirs, duels and team battles the area color. A shown arena
            // whose style draws nothing still gets its border, in the area color.
            this.color = color;
            String borderColor = switch (battle.kind) {
                case ALL_VS_ONE -> ActionFx.glowColor(ActionFx.Action.ALL_VS_ONE, color);
                case ROYALE -> ActionFx.glowColor(ActionFx.Action.ROYALE, color);
                default -> color;
            };
            String areaStyle = KindFx.areaStyle(battle.kind);
            String ground = FxSettings.groundOf(areaStyle);
            String border = FxSettings.hasBorder(areaStyle) ? borderColor : null;
            if ("off".equals(ground) && border == null) border = color;
            this.style = ground;
            this.glowColor = border;
            this.seconds = seconds;
            this.titleKey = "server.mobbattle.timer." + battle.kind.key;
            this.endsAt = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : 0;
        }

        /** The arena's radius now (closing with arenaShrink). */
        double radius(long now) {
            if (endRadius >= startRadius || now <= shrinkFrom) return startRadius;
            if (now >= shrinkUntil) return endRadius;
            double t = (now - shrinkFrom) / (double) (shrinkUntil - shrinkFrom);
            return startRadius + (endRadius - startRadius) * t;
        }
    }

    private static final List<State> ARENAS = new CopyOnWriteArrayList<>();

    private Arena() {
    }

    static boolean any() {
        return !ARENAS.isEmpty();
    }

    /**
     * /mobbattle skipTimer: every running timer jumps to 0, so on its next tick (world thread) each battle ends by
     * its own timeUp rule, as if the time had run out. Returns how many timers were skipped.
     */
    static int skipTimers() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (State s : ARENAS) {
            if (s.endsAt <= 0 || s.battle.over) continue;
            s.endsAt = now;
            s.nextTimer = 0;
            n++;
        }
        return n;
    }

    /**
     * Right after a battle starts: its arena and timer, per the settings of its kind (nothing if all are off).
     * {@code color}: the area color it was drawn with (null picks one).
     */
    static void setup(BattleManager.Battle battle, Vector3d center, double radius, String color,
                      ComponentAccessor<EntityStore> buffer) {
        if (battle == null || center == null) return;
        // /mobbattle autoWatch: right after the battle starts (its countdown is over).
        BattleWatch.auto(battle);
        Mode mode = FxSettings.arenaMode(battle.kind);
        int seconds = FxSettings.timerSeconds(battle.kind);
        ARENAS.removeIf(s -> s.battle == battle);
        if (FxSettings.debug) {
            StringBuilder who = new StringBuilder();
            for (Ref<EntityStore> m : battle.members()) {
                who.append(who.length() > 0 ? ", " : "").append(ChaosWand.nameOf(m, buffer)).append(" (side ")
                        .append(battle.team(m)).append(")");
            }
            MobBattlePlugin.debug("battle start: " + battle.kind.key + " #" + battle.id.toString().substring(0, 8) + " owner "
                    + (battle.owner() != null ? battle.owner().getUsername() : "-") + " | fighters: " + who);
            MobBattlePlugin.debug("battle rules: arena " + mode + ", radius " + Math.round(radius) + " at "
                    + Math.round(center.x) + " " + Math.round(center.y) + " " + Math.round(center.z)
                    + ", timer " + (seconds > 0 ? seconds + " s" : "off") + ", timeUp " + FxSettings.timeUp(battle.kind)
                    + ", tieBreak " + FxSettings.tieBreak + ", isolation " + FxSettings.ISOLATION.get(battle.kind)
                    + ", friendlyFire " + FxSettings.friendlyFire(battle.kind) + ", timerStyle " + FxSettings.timerStyle);
        }
        boolean shrink = FxSettings.shrinks(battle.kind) && radius > FxSettings.shrinkTo;
        warnShortTimer(battle, radius, seconds, shrink);
        if (!mode.shows() && !mode.walls() && seconds <= 0 && !shrink) return;
        Store<EntityStore> store = buffer.getExternalData().getWorld().getEntityStore().getStore();
        State s = new State(battle, store, new Vector3d(center), radius, mode,
                color != null ? color : FxSettings.pickAreaColorName(), seconds);
        if (seconds > 0 && battle.owner() != null && battle.owner().isValid()) {
            for (PlayerRef viewer : FightFx.titleViewers(battle.owner())) s.viewers.add(viewer.getUuid());
        }
        ARENAS.add(s);
        // The 15 min safety limit never ends a fight before its timer (timeUp continue: a minute more after it).
        if (seconds > 0) battle.expiresAt = Math.max(battle.expiresAt, s.endsAt + 60_000);
    }

    /** Mobs walk roughly this many blocks a second while chasing; used to tell when a timer is too short. */
    private static final double CHASE_BLOCKS_PER_SECOND = 2.5;

    /**
     * A timer too short for the arena: fighters at opposite edges can't even reach each other (2 radii at
     * CHASE_BLOCKS_PER_SECOND) before time is up. Tells the owner, with a timer that would do.
     */
    private static void warnShortTimer(BattleManager.Battle battle, double radius, int seconds, boolean shrink) {
        if (seconds <= 0 || battle.owner() == null || !battle.owner().isValid()) return;
        int needed = (int) Math.ceil(radius * 2 / CHASE_BLOCKS_PER_SECOND / 10.0) * 10;
        if (seconds >= needed) return;
        battle.owner().sendMessage(com.hypixel.hytale.server.core.Message.raw("The " + battle.kind.key + " timer ("
                + MobBattleCommand.formatDuration(seconds) + ") is short for an arena of radius " + Math.round(radius)
                + ": fighters far apart may not meet before time is up. Try /mobbattle timer " + battle.kind.key + " "
                + MobBattleCommand.formatDuration(needed) + (shrink ? "" : ", /mobbattle arenaShrink " + battle.kind.key + " on")
                + " or a smaller area.").color("#ffcc55"));
    }

    /**
     * Center and radius around these fighters (duels, team battles): arenaSize if set, else their spread plus a
     * margin, at least {@link FxSettings#MIN_ARENA}.
     */
    static double[] around(Collection<Ref<EntityStore>> mobs, ComponentAccessor<EntityStore> buffer) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        java.util.List<Vector3d> points = new java.util.ArrayList<>();
        for (Ref<EntityStore> mob : mobs) {
            Vector3d p = mob.isValid() ? ChaosWand.positionOf(mob, buffer) : null;
            if (p == null) continue;
            points.add(p);
            x += p.x;
            y += p.y;
            z += p.z;
            n++;
        }
        if (n == 0) return null;
        x /= n;
        y /= n;
        z /= n;
        double spread = 0;
        for (Vector3d p : points) spread = Math.max(spread, Math.hypot(p.x - x, p.z - z));
        double radius = FxSettings.arenaSize > 0 ? FxSettings.arenaSize
                : Math.max(FxSettings.MIN_ARENA, Math.ceil(spread + 6));
        return new double[] {x, y, z, radius};
    }

    /** Same as {@link #setup}, centered on the fighters (duels, team battles). */
    static void setupAround(BattleManager.Battle battle, ComponentAccessor<EntityStore> buffer) {
        if (battle == null) return;
        double[] a = around(battle.members(), buffer);
        if (a == null) return;
        setup(battle, new Vector3d(a[0], a[1], a[2]), a[3], null, buffer);
    }

    /** About 4 times a second per world (BattleLifecycleSystem), on the world thread, outside any system. */
    static void tick(Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        for (State s : ARENAS) {
            if (s.store != store) continue;
            if (s.battle.over || store.isShutdown()) {
                ARENAS.remove(s);
                clearTimer(s);
                continue;
            }
            if (s.show && now >= s.nextDraw) {
                s.nextDraw = now + DRAW_MS;
                double r = s.radius(now);
                FightFx.drawArea(s.center, r, s.style, s.color, null, DRAW_SECONDS, null, s.glowPoints, store);
                FightFx.drawArea(s.center, r, "off", s.color, s.glowColor, GLOW_SECONDS, null, s.glowPoints, store);
            }
            if (s.wall && now >= s.nextWall) {
                s.nextWall = now + WALL_MS;
                contain(s, store);
            }
            if (s.endsAt > 0 && now >= s.nextTimer) {
                s.nextTimer = now + 1000;
                int left = (int) Math.ceil((s.endsAt - now) / 1000.0);
                if (left <= 0) {
                    MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": timer reached 0, timeUp "
                            + FxSettings.timeUp(s.battle.kind));
                    s.endsAt = 0;
                    clearTimer(s);
                    BattleManager.timeUp(s.battle, FxSettings.timeUp(s.battle.kind), store);
                } else {
                    sendTimer(s, left, store);
                }
            }
        }
    }

    /** Pushes fighters at the edge back in; teleports the ones far outside back inside. */
    private static void contain(State s, Store<EntityStore> store) {
        double radius = s.radius(System.currentTimeMillis());
        for (Ref<EntityStore> npc : s.battle.members()) {
            if (!npc.isValid() || !BattleManager.isAlive(npc, store)) continue;
            Vector3d p = ChaosWand.positionOf(npc, store);
            if (p == null) continue;
            double dx = p.x - s.center.x, dz = p.z - s.center.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= radius - EDGE_MARGIN || d < 0.01) {
                s.pushes.remove(npc);
                continue;
            }
            double ox = dx / d, oz = dz / d;   // outward
            // Still outside the edge after MAX_PUSHES pushes: the knockback can't move it (e.g. the closing wall
            // passed a mob wedged in rock). An edge mob just inside the margin is never counted.
            int pushed = d > radius ? s.pushes.merge(npc, 1, Integer::sum) : 0;
            if (d > radius + TELEPORT_BEYOND || pushed > MAX_PUSHES) {
                s.pushes.remove(npc);
                double r = Math.max(1, radius - 3);
                double x = s.center.x + ox * r, z = s.center.z + oz * r;
                // A spot to stand on at about its own height (a cave mob stays low, a mountain one high), else at
                // the arena's height; none (e.g. solid rock there): only pushed this time.
                double y = FightFx.standableY(x, z, p.y, STAND_RANGE, store);
                if (!Double.isFinite(y)) y = FightFx.standableY(x, z, s.center.y, STAND_RANGE, store);
                if (!Double.isFinite(y)) {
                    push(npc, ox, oz, store);
                    continue;
                }
                Vector3d to = new Vector3d(x, y, z);
                store.putComponent(npc, com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.getComponentType(),
                        com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.createExact(to,
                                new com.hypixel.hytale.math.vector.Rotation3f(0f, (float) Math.atan2(ox, oz), 0f)));
                WandFx.burst(WandFx.SUMMON_PARTICLE, null, to, store);
                MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": " + ChaosWand.nameOf(npc, store) + " was "
                        + Math.round(d - radius) + " blocks outside, teleported back in");
                continue;
            }
            if (FxSettings.debug) {
                MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": " + ChaosWand.nameOf(npc, store)
                        + " at the edge (" + String.format(java.util.Locale.ROOT, "%.1f", d) + " of " + Math.round(radius) + "), pushed in");
            }
            push(npc, ox, oz, store);
        }
    }

    /** How far up or down a teleport looks for a spot to stand on. */
    private static final int STAND_RANGE = 24;

    /** Knockback toward the center (the game's own, as "/knockback apply" sets it). */
    private static void push(Ref<EntityStore> npc, double ox, double oz, Store<EntityStore> store) {
        KnockbackComponent kb = store.ensureAndGetComponent(npc, KnockbackComponent.getComponentType());
        kb.setVelocity(new Vector3d(-ox * PUSH, PUSH_UP, -oz * PUSH));
        kb.setVelocityType(ChangeVelocityType.Set);
        kb.setVelocityConfig(null);
        kb.setDuration(0f);
        kb.setTimer(0f);
        kb.setPreMultiplier(1f);
    }

    /** World thread. timerStyle custom: our TimerHud; portal: the game's portal timer box (UpdatePortal). */
    private static void sendTimer(State s, int left, Store<EntityStore> store) {
        if (s.portal) {
            UpdatePortal packet = new UpdatePortal(new PortalState(left, 0), new PortalDef(s.titleKey, s.seconds));
            for (UUID id : s.viewers) {
                PlayerRef p = Universe.get().getPlayer(id);
                if (p != null && p.isValid()) p.getPacketHandler().write(packet);
            }
            return;
        }
        String sub = subtitle(s.battle, store);
        for (UUID id : s.viewers) {
            PlayerRef p = Universe.get().getPlayer(id);
            if (p != null && p.isValid()) TimerHud.show(p, store, s.titleKey, sub, left);
        }
    }

    /** Who is fighting, for the custom timer box: "A vs B", "vs X: 5 attackers", "6 left". */
    private static String subtitle(BattleManager.Battle battle, ComponentAccessor<EntityStore> buffer) {
        // Counted, not collected: this runs every second for battles of hundreds of mobs.
        int alive = 0;
        Ref<EntityStore> first = null, second = null;
        for (Ref<EntityStore> m : battle.members()) {
            if (!BattleManager.isAlive(m, buffer)) continue;
            if (alive == 0) first = m;
            else if (alive == 1) second = m;
            alive++;
        }
        if (battle.kind == BattleManager.Kind.DUEL && alive == 2) {
            return pretty(ChaosWand.nameOf(first, buffer)) + " vs " + pretty(ChaosWand.nameOf(second, buffer));
        }
        if (battle.kind == BattleManager.Kind.ALL_VS_ONE && battle.focusName != null) {
            int n = Math.max(0, alive - 1);
            return n + (n == 1 ? " attacker" : " attackers") + " vs " + pretty(battle.focusName);
        }
        if (battle.kind == BattleManager.Kind.TEAMS && buffer instanceof Store<EntityStore> st) {
            // Compact: alive per team, "RED 5 · BLUE 3" (the scoreboard has the full rows).
            java.util.List<String> parts = new java.util.ArrayList<>();
            Scoreboard.sides(battle, st).forEach((team, c) -> parts.add(battle.name(team) + " " + c[0]));
            return String.join(" · ", parts);
        }
        // Only the leader: one short line however many fight (hundreds of mobs would not fit).
        BattleManager.Kills leader = battle.topKiller();
        String top = leader != null ? " | top: " + pretty(leader.name()) + " " + leader.count() : "";
        return alive + " fighters left" + top;
    }

    /** Role ids read better in the box without underscores: "Test_Dummy" -> "Test Dummy". */
    private static String pretty(String name) {
        return name == null ? "?" : name.replace('_', ' ');
    }

    /** Removes the timer box (portal: an UpdatePortal with no state and no definition, as PortalTrackerSystems does). */
    private static void clearTimer(State s) {
        if (s.seconds <= 0) return;
        for (UUID id : s.viewers) {
            PlayerRef p = Universe.get().getPlayer(id);
            if (p == null || !p.isValid()) continue;
            if (s.portal) {
                p.getPacketHandler().write(new UpdatePortal(null, null));
                continue;
            }
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) continue;
            // The HUD manager is a player component: change it on their world thread (clearAll runs from a command).
            Store<EntityStore> st = ref.getStore();
            st.getExternalData().getWorld().execute(() -> TimerHud.hide(p, st));
        }
        s.viewers.clear();
    }

    /**
     * BattleManager.maintain: an arena of an unloaded world (its tick never runs again) or of a battle that ended
     * keeps its Store and battle reachable; drop it and its timer box.
     */
    static void prune() {
        for (State s : ARENAS) {
            if (s.battle.over || s.store.isShutdown()) {
                ARENAS.remove(s);
                clearTimer(s);
            }
        }
    }

    /** /mobbattle stop and the like: drops every arena and its timer box. */
    static void clearAll() {
        for (State s : ARENAS) clearTimer(s);
        ARENAS.clear();
    }
}
