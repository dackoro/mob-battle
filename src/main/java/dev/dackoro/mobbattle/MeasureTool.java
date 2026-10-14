package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * /mobbattle measure [look|id:&lt;uuid&gt;|last] [seconds] (admin, 2026-10-13, user: calibrate the size/speed buffs on a 150-block track):
 * samples the position of you (or the mob you look at) every SAMPLE_MS for some seconds and tells in chat, once a
 * second, the speed over the last second and the height above the start; at the end the top speed (over
 * WINDOW_MS), the average while moving, the distance and the highest point. /mobbattle measure stop ends it early. For players it also shows the movement
 * settings the server sent (base speed, jump force) against the game's defaults. One measure per player; a new one
 * replaces it.
 */
final class MeasureTool {

    private static final long SAMPLE_MS = 50;
    /** Top speed is taken over this window, so one jittery sample doesn't count. */
    private static final long WINDOW_MS = 500;
    /** Moving = faster than this (blocks/s). */
    private static final double MOVING = 0.5;
    static final int MAX_SECONDS = 60;
    /** A jump is measured from the lowest point of the last 1.5 s. */
    private static final int JUMP_WINDOW = (int) (1500 / SAMPLE_MS);
    /** Cruising speed: the seconds at least this share of the fastest second. */
    private static final double CRUISE_SHARE = 0.85;
    private static final String INFO = "#c8e6ff";
    private static final String OK = "#7dff8a";

    /** A running measure: its task, and a stop asked by /mobbattle measure stop (the next sample ends it). */
    private static final class Run {
        volatile ScheduledFuture<?> task;
        volatile boolean stop;
    }

    private static final Map<UUID, Run> RUNNING = new ConcurrentHashMap<>();

    private MeasureTool() {
    }

    /** Starts a measure of {@code target} for {@code seconds}, reported to {@code viewer}. World thread. */
    static void start(PlayerRef viewer, Ref<EntityStore> target, int seconds, Store<EntityStore> store) {
        start(viewer.getUuid(), viewer::sendMessage, target, seconds, store);
    }

    /**
     * Same, reported to any receiver (the server console with /mobbattle measure id:&lt;uuid&gt;, 2026-10-13); one measure
     * per {@code key}. World thread.
     */
    static void start(UUID key, java.util.function.Consumer<Message> viewer, Ref<EntityStore> target, int seconds, Store<EntityStore> store) {
        Run old = RUNNING.remove(key);
        if (old != null && old.task != null) old.task.cancel(false);
        TransformComponent tf = store.getComponent(target, TransformComponent.getComponentType());
        if (tf == null) return;
        String name = ChaosWand.nameOf(target, store);
        int samples = (int) (seconds * 1000L / SAMPLE_MS);
        double[][] pos = new double[samples + 1][];
        Vector3d p0 = tf.getPosition();
        pos[0] = new double[] {p0.x, p0.y, p0.z};
        int[] n = {1};
        double[] stats = {0, 0, 0, 0, 0}; // distance, moving time (s), top speed, highest above start, best jump
        java.util.List<Double> perSecondSpeeds = new java.util.ArrayList<>();
        say(viewer, I18n.tr("cmd.measure.start").param("name", name).param("seconds", seconds)
                .param("buffs", buffsMsg(target, store)).param("settings", settingsMsg(target, store)),
                "Measuring " + name + " for " + seconds + " s (measure stop ends it): move now. "
                + buffs(target, store) + " " + settings(target, store), INFO);
        var world = store.getExternalData().getWorld();
        int perWindow = (int) (WINDOW_MS / SAMPLE_MS), perSecond = (int) (1000 / SAMPLE_MS);
        Run run = new Run();
        run.task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            // A world that shut down runs no more tasks: stop here instead of queueing into it 20 times a second.
            if (store.isShutdown()) {
                if (run.task != null) run.task.cancel(false);
                RUNNING.remove(key, run);
                return;
            }
            world.execute(() -> {
            if (run.task == null || run.task.isCancelled()) return;
            if (run.stop || store.isShutdown() || !target.isValid() || n[0] > samples) {
                finish(key, viewer, name, n[0], stats, perSecondSpeeds, run);
                return;
            }
            TransformComponent t = store.getComponent(target, TransformComponent.getComponentType());
            if (t == null) return;
            Vector3d p = t.getPosition();
            int i = n[0]++;
            pos[i] = new double[] {p.x, p.y, p.z};
            double step = Math.hypot(p.x - pos[i - 1][0], p.z - pos[i - 1][2]);
            stats[0] += step;
            if (step / (SAMPLE_MS / 1000.0) > MOVING) stats[1] += SAMPLE_MS / 1000.0;
            stats[3] = Math.max(stats[3], p.y - pos[0][1]);
            // A jump: how far above the lowest point of the last JUMP_WINDOW_MS (the take-off ground) it got, so a slope
            // or a step down doesn't count and each jump is measured from where it started.
            double low = p.y;
            for (int k = Math.max(0, i - JUMP_WINDOW); k < i; k++) low = Math.min(low, pos[k][1]);
            stats[4] = Math.max(stats[4], p.y - low);
            if (i >= perWindow) {
                double d = Math.hypot(p.x - pos[i - perWindow][0], p.z - pos[i - perWindow][2]);
                stats[2] = Math.max(stats[2], d / (WINDOW_MS / 1000.0));
            }
            if (i % perSecond == 0 && i >= perSecond) {
                double d = Math.hypot(p.x - pos[i - perSecond][0], p.z - pos[i - perSecond][2]);
                if (d > MOVING) perSecondSpeeds.add(d);
                say(viewer, I18n.tr("cmd.measure.tick").param("second", i / perSecond).param("speed", f2(d))
                        .param("height", f2(p.y - pos[0][1])),
                        String.format(java.util.Locale.ROOT, "%d s: %.2f blocks/s, %.2f blocks above the start",
                        i / perSecond, d, p.y - pos[0][1]), INFO);
            }
            });
        }, SAMPLE_MS, SAMPLE_MS, TimeUnit.MILLISECONDS);
        RUNNING.put(key, run);
    }

    /** /mobbattle measure stop: the viewer's measure ends at its next sample, with its summary. False if none runs. */
    static boolean stop(UUID viewer) {
        Run run = RUNNING.get(viewer);
        if (run == null) return false;
        run.stop = true;
        return true;
    }

    private static void finish(UUID key, java.util.function.Consumer<Message> viewer, String name, int count, double[] stats,
                               java.util.List<Double> seconds, Run run) {
        run.task.cancel(false);
        RUNNING.remove(key, run);
        double avg = stats[1] > 0 ? stats[0] / stats[1] : 0;
        say(viewer, I18n.tr("cmd.measure.end").param("name", name).param("cruise", f2(cruising(seconds)))
                .param("top", f2(stats[2])).param("window", String.format(java.util.Locale.ROOT, "%.1f", WINDOW_MS / 1000.0))
                .param("avg", f2(avg)).param("dist", String.format(java.util.Locale.ROOT, "%.1f", stats[0]))
                .param("time", String.format(java.util.Locale.ROOT, "%.1f", (count - 1) * SAMPLE_MS / 1000.0))
                .param("jump", f2(stats[4])).param("high", f2(stats[3])), String.format(java.util.Locale.ROOT,
                "%s: cruising %.2f blocks/s, top %.2f (over %.1f s), average while moving %.2f, %.1f blocks in %.1f s; best jump %.2f blocks, highest %.2f above the start.",
                name, cruising(seconds), stats[2], WINDOW_MS / 1000.0, avg, stats[0], (count - 1) * SAMPLE_MS / 1000.0, stats[4], stats[3]), OK);
    }

    /**
     * The steady speed: the median of the one-second speeds within CRUISE_SHARE of the fastest one, so starting and
     * stopping don't pull it down (user test 2026-10-13: the average while moving came out ~5% under the steady
     * seconds). 0 if it never moved.
     */
    private static double cruising(java.util.List<Double> seconds) {
        if (seconds.isEmpty()) return 0;
        double max = java.util.Collections.max(seconds);
        java.util.List<Double> steady = new java.util.ArrayList<>();
        for (double v : seconds) if (v >= max * CRUISE_SHARE) steady.add(v);
        java.util.Collections.sort(steady);
        int m = steady.size();
        return m % 2 == 1 ? steady.get(m / 2) : (steady.get(m / 2 - 1) + steady.get(m / 2)) / 2;
    }

    /**
     * Its size and speed buff, and the speed they should give (user, 2026-10-13: the measure should say what it is
     * measured against): size^speedExponent (sizeSpeed) x speed buff, as BuffJump applies it.
     */
    private static String buffs(Ref<EntityStore> target, Store<EntityStore> store) {
        float size = Buffs.sizeScale(target, store);
        float speed = 1 + Buffs.totalOf(target, Buffs.Stat.SPEED) / 100f;
        double fromSize = Math.pow(size, Buffs.speedExponent());
        return String.format(java.util.Locale.ROOT, "Size x%.2f (speed from size x%.2f, sizeSpeed %s), speed buff x%.2f: expected speed x%.2f of normal.",
                size, fromSize, Buffs.sizeSpeed, speed, fromSize * speed);
    }

    private static String f2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** The player-facing version of {@link #buffs}. */
    private static Message buffsMsg(Ref<EntityStore> target, Store<EntityStore> store) {
        float size = Buffs.sizeScale(target, store);
        float speed = 1 + Buffs.totalOf(target, Buffs.Stat.SPEED) / 100f;
        double fromSize = Math.pow(size, Buffs.speedExponent());
        return I18n.tr("cmd.measure.buffs").param("size", f2(size)).param("fromsize", f2(fromSize))
                .param("mode", String.valueOf(Buffs.sizeSpeed)).param("speed", f2(speed)).param("expected", f2(fromSize * speed));
    }

    /** The player-facing version of {@link #settings}; empty for mobs. */
    private static Message settingsMsg(Ref<EntityStore> target, Store<EntityStore> store) {
        MovementManager mm = store.getComponent(target, MovementManager.getComponentType());
        if (mm == null || mm.getSettings() == null || mm.getDefaultSettings() == null) return Message.raw("");
        var now = mm.getSettings();
        var def = mm.getDefaultSettings();
        return I18n.tr("cmd.measure.settings").param("now", f2(now.baseSpeed)).param("def", f2(def.baseSpeed))
                .param("ratio", f2(now.baseSpeed / def.baseSpeed)).param("jnow", f2(now.jumpForce))
                .param("jdef", f2(def.jumpForce)).param("jratio", f2(now.jumpForce / def.jumpForce));
    }

    /** A player's movement settings now and the game's defaults; empty for mobs. */
    private static String settings(Ref<EntityStore> target, Store<EntityStore> store) {
        MovementManager mm = store.getComponent(target, MovementManager.getComponentType());
        if (mm == null || mm.getSettings() == null || mm.getDefaultSettings() == null) return "";
        var now = mm.getSettings();
        var def = mm.getDefaultSettings();
        return String.format(java.util.Locale.ROOT, "Base speed %.2f (default %.2f, x%.2f), jump force %.2f (default %.2f, x%.2f).",
                now.baseSpeed, def.baseSpeed, now.baseSpeed / def.baseSpeed, now.jumpForce, def.jumpForce, now.jumpForce / def.jumpForce);
    }

    /**
     * To the viewer and the server log ("[measure] ..."): run through /sudo from the console (tests driven by Claude
     * through the dev server's command inbox, 2026-10-13) the chat line only reaches the player.
     */
    private static void say(java.util.function.Consumer<Message> viewer, Message chat, String log, String color) {
        viewer.accept(chat.color(color));
        MobBattlePlugin.log("[measure] " + log);
    }

    /** Plugin shutdown / stop: every measure ends. */
    static void stopAll() {
        RUNNING.values().forEach(r -> {
            if (r.task != null) r.task.cancel(false);
        });
        RUNNING.clear();
    }
}
