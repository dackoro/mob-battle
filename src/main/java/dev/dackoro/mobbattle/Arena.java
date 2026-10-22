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
    /** Moved this much toward the center since the last wall pass (WALL_MS): not stuck. */
    private static final double PROGRESS = 0.2;
    /** A mob teleported back in lands at this share of the radius. */
    private static final double TELEPORT_INSIDE = 0.6;
    /** Push toward the center (horizontal and up), like a strong hit's knockback. */
    private static final double PUSH = 7.0;
    private static final double PUSH_UP = 2.5;
    /** Push at the edge itself, and how much stronger per block outside (up to PUSH). */
    private static final double PUSH_MIN = 2.5, PUSH_PER_BLOCK = 1.5;
    /** Upward speed for a blocked mob: about one block of climb. */
    private static final double STEP_LIFT = 1.6;

    private static final class State {
        final BattleManager.Battle battle;
        /** Fighter -> pushes in a row while still outside (world thread only): stuck ones get teleported in. */
        final java.util.Map<Ref<EntityStore>, Integer> pushes = new java.util.HashMap<>();
        /** Diagnostics (debug only): each mob's height at the last wall pass and what we did to it last. */
        final java.util.Map<Ref<EntityStore>, double[]> lastPos = new java.util.HashMap<>();
        final java.util.Map<Ref<EntityStore>, String> lastAction = new java.util.HashMap<>();
        /** Each mob's distance from the center at the last wall pass: moving in = not stuck, its pushes start over. */
        final java.util.Map<Ref<EntityStore>, Double> edgeDist = new java.util.HashMap<>();
        /** Water mobs outside the arena with no water left inside it, and since when (STRANDED_MS, then out). */
        final java.util.Map<Ref<EntityStore>, Long> stranded = new java.util.HashMap<>();
        /** [debug] "pushed in" lines: fighter -> {last line time, pushes since} (world thread only). */
        final java.util.Map<Ref<EntityStore>, long[]> pushLog = new java.util.HashMap<>();
        /** Fighters found inside a block on the last wall pass (moved out only when the next pass finds them there too). */
        final java.util.Set<Ref<EntityStore>> wedged = new java.util.HashSet<>();
        /**
         * Fighter -> when to look for a landing spot again after a search found none (wedged or brought back): the
         * search reads ~10000 blocks, so not every 250 ms pass (audit 2026-10-14).
         */
        final java.util.Map<Ref<EntityStore>, Long> noLanding = new java.util.HashMap<>();
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

    /** Margin past the arena edge that still counts as inside (the wall pushes back from the edge itself). */
    private static final double OUTSIDE_MARGIN = 3;

    /**
     * True when {@code pos} is clearly outside the walled arena of {@code battle} right now (horizontal distance).
     * False with no arena, a shown-only one or no position. Target picking (BattleManager.reach): a skeleton chasing
     * a duck flown off past the wall walked against it for the whole battle (user, 2026-10-12).
     */
    static boolean outside(BattleManager.Battle battle, Vector3d pos) {
        if (pos == null || ARENAS.isEmpty()) return false;
        for (State s : ARENAS) {
            if (s.battle != battle) continue;
            if (!s.wall) return false;
            double r = s.radius(System.currentTimeMillis()) + OUTSIDE_MARGIN;
            double dx = pos.x - s.center.x, dz = pos.z - s.center.z;
            double dy = pos.y - s.center.y;
            return dx * dx + dz * dz > r * r || beyondBand(dy < 0 ? dy + OUTSIDE_MARGIN : dy - OUTSIDE_MARGIN);
        }
        return false;
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
        // /mobbattle autoFilm and autoWatch: right after the battle starts (its countdown is over); film wins.
        FilmCamera.auto(battle);
        BattleWatch.auto(battle);
        Mode mode = FxSettings.arenaMode(battle.kind);
        int seconds = FxSettings.timerSeconds(battle.kind);
        ARENAS.removeIf(s -> s.battle == battle);
        if (MobBattlePlugin.debugging()) {
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
                    + ", judgeBy " + FxSettings.judgeBy(battle.kind) + ", tieBreak " + FxSettings.tieBreak(battle.kind) + ", isolation " + FxSettings.ISOLATION.get(battle.kind)
                    + ", friendlyFire " + FxSettings.friendlyFire(battle.kind) + ", timerStyle " + FxSettings.timerStyle);
        }
        boolean shrink = FxSettings.shrinks(battle.kind) && radius > FxSettings.shrinkTo;
        warnShortTimer(battle, radius, seconds, shrink);
        if (!mode.shows() && !mode.walls() && seconds <= 0 && !shrink) return;
        Store<EntityStore> store = buffer.getExternalData().getWorld().getEntityStore().getStore();
        State s = new State(battle, store, new Vector3d(center), radius, mode,
                color != null ? color : FxSettings.pickAreaColorName(), seconds);
        if (seconds > 0) {
            for (PlayerRef viewer : FightFx.titleViewers(battle)) s.viewers.add(viewer.getUuid());
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
        if (seconds <= 0) return;
        int needed = (int) Math.ceil(radius * 2 / CHASE_BLOCKS_PER_SECOND / 10.0) * 10;
        if (seconds >= needed) return;
        PlayerRef owner = battle.owner();
        if (owner != null && owner.isValid()) {
            owner.sendMessage(I18n.tr(shrink ? "battle.warn.short.timer" : "battle.warn.short.timer.shrink")
                    .param("kind", battle.kind.key)
                    .param("time", MobBattleCommand.formatDuration(seconds))
                    .param("radius", Math.round(radius))
                    .param("needed", MobBattleCommand.formatDuration(needed))
                    .color("#ffcc55"));
        }
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
            if (MobBattlePlugin.debugging()) watchFall(s, npc, p, store);
            double dx = p.x - s.center.x, dz = p.z - s.center.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            // Inside a block (2026-10-14, cave diagnostics: every deer that sank through the floor was first wedged
            // into the rock, and from there the game let it fall): out at once, wherever it is.
            // Two passes in a row and a body taller than a block (audit 2026-10-14: a chicken under a slab or a mob
            // walking through a door has a solid block at feet + 1 without being stuck).
            boolean wedged = !Unstick.flyingNow(npc, store) && !Medium.waterOnly(Medium.of(npc, store))
                    && tallerThanABlock(npc, store) && embedded(p, store);
            if (!wedged) s.wedged.remove(npc);
            if (wedged && s.wedged.add(npc)) wedged = false;
            if (wedged && !searchDue(s, npc)) wedged = false;
            if (wedged) {
                s.wedged.remove(npc);
                FightFx.Spot spot = Medium.spotFor(Medium.of(npc, store));
                Vector3d to = landing(s, d < 0.01 ? 1 : dx / d, d < 0.01 ? 0 : dz / d, Math.max(d + 1, 3), p.y, s.center.y, spot, store);
                // Nowhere roomy near it (a cave): the arena's center, where the battle started on solid ground.
                if (to == null) to = centerSpot(s, spot, store);
                if (to == null) s.noLanding.put(npc, System.currentTimeMillis() + NO_LANDING_RETRY_MS);
                if (to != null) {
                    s.pushes.remove(npc);
                    s.edgeDist.remove(npc);
                    if (MobBattlePlugin.debugging()) s.lastAction.put(npc, String.format(java.util.Locale.ROOT, "unwedged %.1f %.1f %.1f -> %.1f %.1f %.1f",
                            p.x, p.y, p.z, to.x, to.y, to.z));
                    if (FightFx.teleport(npc, to, new com.hypixel.hytale.math.vector.Rotation3f(0f, 0f, 0f), store)) {
                        MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": " + ChaosWand.nameOf(npc, store)
                                + " was inside a block, moved out");
                    }
                    continue;
                }
            }
            // Fallen to another cave level, or up a cliff, inside the circle (2026-10-14, cave test: animals ran down
            // a shaft to a lava level far below): out of the fight, so back at the arena's height. Not fliers or
            // water mobs (a lake can be deep).
            double dy = p.y - s.center.y;
            if (beyondBand(dy) && !Unstick.flyingNow(npc, store) && !Medium.waterOnly(Medium.of(npc, store))) {
                bringBack(s, npc, d < 0.01 ? 1 : dx / d, d < 0.01 ? 0 : dz / d, radius, p, store,
                        Math.round(Math.abs(dy)) + " blocks " + (dy < 0 ? "below" : "above") + " the arena");
                continue;
            }
            if (d <= radius - EDGE_MARGIN || d < 0.01) {
                s.pushes.remove(npc);
                s.stranded.remove(npc);
                s.edgeDist.remove(npc);
                continue;
            }
            double ox = dx / d, oz = dz / d;   // outward
            // Medium (2026-10-14, user): a water mob's lake is its zone. It is only moved into water inside the
            // arena; with none there it stays where it is, never pushed onto land (the others can't reach it either,
            // so passiveWin ends the battle on health). A walker that drowns is only moved onto dry land.
            Medium.Can can = Medium.of(npc, store);
            boolean fish = Medium.waterOnly(can);
            FightFx.Spot spot = Medium.spotFor(can);
            // Still outside the edge after MAX_PUSHES pushes: the knockback can't move it (e.g. the closing wall
            // passed a mob wedged in rock). An edge mob just inside the margin is never counted.
            // Only pushes that got it nowhere count (2026-10-14, user: with a closing arena the wall kept pushing a wolf
            // that was walking in with it, it hit MAX_PUSHES, was teleported 3 blocks inside, met the wall again: a
            // ping-pong). Moving in since the last pass starts the count over.
            Double before = s.edgeDist.put(npc, d);
            int pushed;
            if (d <= radius) pushed = 0;
            else if (before != null && d < before - PROGRESS) {
                s.pushes.remove(npc);
                pushed = 0;
            } else pushed = s.pushes.merge(npc, 1, Integer::sum);
            if (d > radius + TELEPORT_BEYOND || pushed > MAX_PUSHES) {
                s.pushes.remove(npc);
                s.edgeDist.remove(npc);
                // Well inside (60 % of the radius), so the closing wall does not reach it again right away. A spot to
                // stand on at about its own height (a cave mob stays low, a mountain one high), else at the arena's
                // height; an escapee (far out) comes back at the arena's height first (2026-10-14, cave test: an
                // antelope that fell 13 blocks out could land on the cave level below). None: only pushed this time.
                boolean escaped = d > radius + TELEPORT_BEYOND;
                double first = escaped ? s.center.y : p.y, second = escaped ? p.y : s.center.y;
                Vector3d to = landing(s, ox, oz, radius, first, second, spot, store);
                if (to == null && !fish) to = centerSpot(s, spot, store); // a narrow corridor: no roomy spot anywhere
                if (to == null) {
                    if (!fish) {
                        push(npc, ox, oz, d - (radius - EDGE_MARGIN), pushed > 1, store);
                    } else if (strandedOut(s, npc, store)) {
                        continue;
                    }
                    continue;
                }
                s.stranded.remove(npc);
                if (MobBattlePlugin.debugging()) s.lastAction.put(npc, String.format(java.util.Locale.ROOT, "arena teleport %.1f %.1f %.1f -> %.1f %.1f %.1f",
                        p.x, p.y, p.z, to.x, to.y, to.z));
                if (!FightFx.teleport(npc, to, new com.hypixel.hytale.math.vector.Rotation3f(0f, (float) Math.atan2(ox, oz), 0f), store)) continue;
                WandFx.burst(WandFx.SUMMON_PARTICLE, null, to, store);
                MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": " + ChaosWand.nameOf(npc, store) + " was "
                        + Math.round(d - radius) + " blocks outside, teleported back in");
                continue;
            }
            if (MobBattlePlugin.debugging() && !fish) {
                // Several pushes a second per mob filled the log (~130 lines a minute): one line per mob every
                // PUSH_LOG_MS, with how many pushes it got since.
                long now = System.currentTimeMillis();
                long[] pl = s.pushLog.computeIfAbsent(npc, k -> new long[] {0, 0});
                pl[1]++;
                if (now - pl[0] >= PUSH_LOG_MS) {
                    MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": " + ChaosWand.nameOf(npc, store)
                            + " at the edge (" + String.format(java.util.Locale.ROOT, "%.1f", d) + " of " + Math.round(radius) + "), pushed in"
                            + (pl[1] > 1 ? " x" + pl[1] : ""));
                    pl[0] = now;
                    pl[1] = 0;
                }
            }
            if (!fish && !wallAhead(p, -ox, -oz, store)) {
                push(npc, ox, oz, d - (radius - EDGE_MARGIN), pushed > 1, store);
                if (MobBattlePlugin.debugging()) s.lastAction.put(npc, String.format(java.util.Locale.ROOT, "push at %.1f %.1f %.1f (blocked %s)",
                        p.x, p.y, p.z, pushed > 1));
            }
        }
    }

    /**
     * The mob's body is in a solid block (sunk below the floor, pushed into a wall). Only the block above its feet: the
     * feet block itself is solid on stairs and slabs, where a mob stands half way up it.
     */
    private static boolean embedded(Vector3d p, Store<EntityStore> store) {
        int bx = (int) Math.floor(p.x), by = (int) Math.floor(p.y + 0.05), bz = (int) Math.floor(p.z);
        return FightFx.solidAt(bx, by + 1, bz, store);
    }

    /** How long a mob waits before another landing search after one found nothing. */
    private static final long NO_LANDING_RETRY_MS = 2000;

    /** False while {@code npc}'s last landing search found nothing, for NO_LANDING_RETRY_MS. */
    private static boolean searchDue(State s, Ref<EntityStore> npc) {
        Long at = s.noLanding.get(npc);
        if (at == null) return true;
        if (System.currentTimeMillis() < at) return false;
        s.noLanding.remove(npc);
        return true;
    }

    /** Hitbox taller than 1.1 blocks: a smaller mob fits under a block at feet + 1 without being stuck in it. */
    private static boolean tallerThanABlock(Ref<EntityStore> npc, Store<EntityStore> store) {
        var box = store.getComponent(npc, com.hypixel.hytale.server.core.modules.entity.component.BoundingBox.getComponentType());
        return box == null || box.getBoundingBox() == null || box.getBoundingBox().max.y - box.getBoundingBox().min.y > 1.1;
    }

    /**
     * Rock right where a push would send it (one block ahead, at its body): a knockback into it wedges the mob into
     * the block (cave diagnostics 2026-10-14), so no push; the pushes count as no progress and the teleport takes over.
     * A step (only the feet block solid, room above) is fine: the blocked lift hops it.
     */
    private static boolean wallAhead(Vector3d p, double dirX, double dirZ, Store<EntityStore> store) {
        int ax = (int) Math.floor(p.x + dirX * 0.9), az = (int) Math.floor(p.z + dirZ * 0.9), by = (int) Math.floor(p.y + 0.05);
        return FightFx.solidAt(ax, by + 1, az, store) || FightFx.solidAt(ax, by + 2, az, store) && FightFx.solidAt(ax, by, az, store);
    }

    /**
     * Diagnostics (debugLog, 2026-10-14: deer sank through solid cave floor): a fighter that dropped more than 3 blocks
     * since the last wall pass (250 ms) is logged with where it was, whether that spot had solid ground under it, and
     * the last thing the arena did to it.
     */
    private static void watchFall(State s, Ref<EntityStore> npc, Vector3d p, Store<EntityStore> store) {
        double[] before = s.lastPos.put(npc, new double[] {p.x, p.y, p.z});
        if (before == null || before[1] - p.y <= 3 || Unstick.flyingNow(npc, store)) return;
        int bx = (int) Math.floor(before[0]), by = (int) Math.floor(before[1]), bz = (int) Math.floor(before[2]);
        boolean ground = FightFx.solidAt(bx, by - 1, bz, store);
        boolean inside = FightFx.solidAt(bx, by, bz, store) || FightFx.solidAt(bx, by + 1, bz, store);
        MobBattlePlugin.debug(String.format(java.util.Locale.ROOT, "arena #%s: %s DROPPED %.1f blocks: %.1f %.1f %.1f -> %.1f %.1f %.1f, solid under the old spot %s, inside a block there %s, last: %s",
                s.battle.id.toString().substring(0, 8), ChaosWand.nameOf(npc, store), before[1] - p.y, before[0], before[1], before[2],
                p.x, p.y, p.z, ground, inside, s.lastAction.getOrDefault(npc, "nothing")));
    }

    /**
     * How long a water mob may wait outside the arena once no water is left inside it (2026-10-14, user: a zombie
     * can't leave the arena to get at it either, so hiding in a lake out there must not win the battle).
     */
    private static final long STRANDED_MS = 10_000;

    /** A water mob found no water inside the arena: true once STRANDED_MS went by and it was taken out (soul rip). */
    private static boolean strandedOut(State s, Ref<EntityStore> npc, Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        long since = s.stranded.computeIfAbsent(npc, k -> now);
        if (now - since < STRANDED_MS || WandFx.isDying(npc)) return false;
        s.stranded.remove(npc);
        WandFx.kill(npc, null, true, store);
        MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": " + ChaosWand.nameOf(npc, store)
                + " stayed outside with no water left inside for " + STRANDED_MS / 1000 + " s: out");
        return true;
    }

    /**
     * arenaVertical (default 0 = no limit): a fighter farther above or below the arena's center height is brought back
     * (cave shafts, cliffs). Separate from battleBelow/Above, which only choose who joins (user, 2026-10-14).
     */
    private static boolean beyondBand(double dy) {
        return FxSettings.arenaVertical > 0 && Math.abs(dy) > FxSettings.arenaVertical;
    }

    /** Teleports {@code npc} back inside at the arena's height, along its direction from the center (vertical escape). */
    private static void bringBack(State s, Ref<EntityStore> npc, double ox, double oz, double radius, Vector3d p,
                                  Store<EntityStore> store, String why) {
        if (!searchDue(s, npc)) return;
        FightFx.Spot spot = Medium.spotFor(Medium.of(npc, store));
        Vector3d to = landing(s, ox, oz, radius, s.center.y, s.center.y, spot, store);
        if (to == null) {
            s.noLanding.put(npc, System.currentTimeMillis() + NO_LANDING_RETRY_MS);
            return;
        }
        s.pushes.remove(npc);
        s.edgeDist.remove(npc);
        if (MobBattlePlugin.debugging()) s.lastAction.put(npc, String.format(java.util.Locale.ROOT, "bring back %.1f %.1f %.1f -> %.1f %.1f %.1f",
                p.x, p.y, p.z, to.x, to.y, to.z));
        if (!FightFx.teleport(npc, to, new com.hypixel.hytale.math.vector.Rotation3f(0f, (float) Math.atan2(ox, oz), 0f), store)) return;
        WandFx.burst(WandFx.SUMMON_PARTICLE, null, to, store);
        MobBattlePlugin.debug("arena #" + s.battle.id.toString().substring(0, 8) + ": " + ChaosWand.nameOf(npc, store) + " was "
                + why + ", teleported back in");
    }

    /** Directions tried around the mob's own (radians) and shares of the radius, for a landing spot with room. */
    private static final double[] LANDING_TURNS = {0, 0.5, -0.5, 1.0, -1.0, 1.6, -1.6, Math.PI};
    private static final double[] LANDING_SHARES = {TELEPORT_INSIDE, 0.35, 0.8};

    /**
     * Where a mob teleported back in lands: a standable spot (by medium) near height {@code first}, else
     * {@code second}, with room around it (FightFx.roomy), along its own direction first, then turning; null if none.
     */
    private static Vector3d landing(State s, double ox, double oz, double radius, double first, double second,
                                    FightFx.Spot spot, Store<EntityStore> store) {
        double base = Math.atan2(oz, ox);
        for (double share : LANDING_SHARES) {
            double r = Math.max(1, radius * share);
            for (double turn : LANDING_TURNS) {
                double a = base + turn, x = s.center.x + Math.cos(a) * r, z = s.center.z + Math.sin(a) * r;
                double y = FightFx.standableY(x, z, first, STAND_RANGE, spot, store);
                if (!Double.isFinite(y) || !landingHeight(s, y)) y = FightFx.standableY(x, z, second, STAND_RANGE, spot, store);
                // Within the arena's height band (2026-10-14, cave: the nearest standable spot was the surface 23 blocks
                // up, so the vertical limit sent it back every second), and with room around it: a tight spot is
                // often a pocket inside the rock, where the mob seemed to sink through the floor (recording).
                if (!Double.isFinite(y) || !landingHeight(s, y)) continue;
                if (spot == FightFx.Spot.WATER || FightFx.roomy(x, y, z, store)) return new Vector3d(x, y, z);
            }
        }
        // Nowhere with room: it stays and the wall keeps pushing.
        return null;
    }

    /** The arena's center on its ground (where the battle started), for a mob with nowhere roomier to go; null if none. */
    private static Vector3d centerSpot(State s, FightFx.Spot spot, Store<EntityStore> store) {
        double cy = FightFx.standableY(s.center.x, s.center.z, s.center.y, STAND_RANGE, spot, store);
        return Double.isFinite(cy) ? new Vector3d(s.center.x, cy, s.center.z) : null;
    }

    /** A landing height well inside the vertical limit, so a teleported mob is not brought back again at once. */
    private static boolean landingHeight(State s, double y) {
        double dy = y - s.center.y;
        // Inside the band with 2 blocks to spare; with no limit set, within the old 20-block band anyway.
        // Within the vertical limit with 2 blocks to spare; without one, 20 blocks anyway (the nearest standable spot of
        // a cave can be the surface far above).
        double band = FxSettings.arenaVertical > 0 ? FxSettings.arenaVertical : 20;
        return Math.abs(dy) <= band - 2;
    }

    /** At most one [debug] "pushed in" line per mob this often. */
    private static final long PUSH_LOG_MS = 5000;

    /** How far up or down a teleport looks for a spot to stand on. */
    private static final int STAND_RANGE = 24;

    /** Knockback toward the center (the game's own, as "/knockback apply" sets it). */
    private static void push(Ref<EntityStore> npc, double ox, double oz, double over, boolean blocked, Store<EntityStore> store) {
        // Gentle at the edge, harder the farther out it is (2026-10-14, user: the fixed push was too rough). One that
        // the last push did not move (a step of the colosseum stands) gets the full lift, to hop up instead of being
        // teleported after MAX_PUSHES (test 2026-10-14: 9 teleports on the stands).
        double strength = Math.max(PUSH_MIN, Math.min(PUSH, PUSH_MIN + PUSH_PER_BLOCK * over));
        // Blocked: just enough lift for one step and a gentle push, not the full strength (an antelope stuck at a cave
        // ledge was flung across the cave once it came free, recording 2026-10-14).
        if (blocked) strength = PUSH_MIN;
        double up = blocked ? STEP_LIFT : PUSH_UP * strength / PUSH;
        KnockbackComponent kb = store.ensureAndGetComponent(npc, KnockbackComponent.getComponentType());
        kb.setVelocity(new Vector3d(-ox * strength, up, -oz * strength));
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
        java.util.List<com.hypixel.hytale.server.core.Message> sub = subtitle(s.battle, store);
        for (UUID id : s.viewers) {
            PlayerRef p = Universe.get().getPlayer(id);
            if (p != null && p.isValid()) TimerHud.show(p, store, s.titleKey, sub, left);
        }
    }

    /** Characters that fit a line of the timer box (12 px bold uppercase in its 218 px wide body). */
    private static final int LINE_CHARS = 28;

    /**
     * Who is fighting, for the custom timer box, one Message per line (at most TimerHud.MAX_LINES): "A vs B",
     * "5 attackers vs X", "6 fighters left" + the top killer. A line that would not fit by its names goes on two.
     */
    private static java.util.List<com.hypixel.hytale.server.core.Message> subtitle(BattleManager.Battle battle,
                                                                                    ComponentAccessor<EntityStore> buffer) {
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
            String a = pretty(ChaosWand.nameOf(first, buffer));
            String b = pretty(ChaosWand.nameOf(second, buffer));
            if (a.length() + b.length() + 4 <= LINE_CHARS) {
                return java.util.List.of(I18n.tr("hud.timer.vs").param("a", a).param("b", b));
            }
            return java.util.List.of(com.hypixel.hytale.server.core.Message.raw(a), I18n.tr("hud.timer.vs.line").param("b", b));
        }
        if (battle.kind == BattleManager.Kind.ALL_VS_ONE && battle.focusName != null) {
            int n = Math.max(0, alive - 1);
            String name = pretty(battle.focusName);
            if (name.length() + 14 <= LINE_CHARS) {
                return java.util.List.of(I18n.tr("hud.timer.attackers.vs").param("count", n).param("name", name));
            }
            return java.util.List.of(I18n.tr("hud.timer.attackers").param("count", n),
                    I18n.tr("hud.timer.vs.line").param("b", name));
        }
        if (battle.kind == BattleManager.Kind.TEAMS && buffer instanceof Store<EntityStore> st) {
            // Compact: alive per team, "RED 5 · BLUE 3" (the scoreboard has the full rows), on as many lines as
            // the names need.
            java.util.List<com.hypixel.hytale.server.core.Message> lines = new java.util.ArrayList<>();
            java.util.List<com.hypixel.hytale.server.core.Message> row = new java.util.ArrayList<>();
            int[] used = {0};
            Scoreboard.sides(battle, st).forEach((team, c) -> {
                String part = battle.name(team) + " " + c[0];
                if (!row.isEmpty() && used[0] + 3 + part.length() > LINE_CHARS) {
                    lines.add(com.hypixel.hytale.server.core.Message.join(row.toArray(new com.hypixel.hytale.server.core.Message[0])));
                    row.clear();
                    used[0] = 0;
                }
                if (!row.isEmpty()) {
                    row.add(com.hypixel.hytale.server.core.Message.raw(" · "));
                    used[0] += 3;
                }
                row.add(com.hypixel.hytale.server.core.Message.raw(part));
                used[0] += part.length();
            });
            if (!row.isEmpty()) lines.add(com.hypixel.hytale.server.core.Message.join(row.toArray(new com.hypixel.hytale.server.core.Message[0])));
            return lines;
        }
        // Only the leader: one short line however many fight (hundreds of mobs would not fit).
        BattleManager.Kills leader = battle.topKiller();
        int tied = leader != null && battle.sharedPlaces() ? battle.tiedAtTop() : 1;
        com.hypixel.hytale.server.core.Message count = I18n.tr("hud.timer.fighters").param("count", alive);
        if (leader == null) return java.util.List.of(count);
        return java.util.List.of(count, tied > 1
                ? I18n.tr("hud.timer.top.tied").param("tied", tied).param("n", leader.count())
                : I18n.tr("hud.timer.top").param("name", pretty(leader.name())).param("n", leader.count()));
    }

    /** Role ids read better in the box without underscores: "Test_Dummy" -> "Test Dummy". */
    private static String pretty(String name) {
        return MobNames.shared(name);
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
