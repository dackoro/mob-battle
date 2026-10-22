package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /mobbattle pictures status | rebuild [force] (admin, console too; 2026-10-17, user: redraw every picture from a
 * running server, progress in chat, and at the end how long it took and how much each group of pictures weighs).
 * The drawing itself is the boot run (ThumbnailCache.rebuildAll), on its own low-priority threads: the game keeps
 * running.
 */
final class PicturesCommand {

    private static final String OK = "#5fe35f", INFO = "#9ad7ff", ERR = "#ff6b5e";

    /** How often the progress line comes while drawing. */
    private static final long PROGRESS_EVERY_S = 3;

    private PicturesCommand() {
    }

    static void run(CommandContext ctx, String[] a) {
        CommandSender to = ctx.sender();
        if (!Perms.admin(to)) {
            say(to, I18n.tr("cmd.pictures.denied"), ERR);
            return;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "status" -> status(to);
            case "rebuild" -> rebuild(to, a.length > 1 && a[1].equalsIgnoreCase("force"));
            default -> say(to, I18n.tr("cmd.pictures.usage"), ERR);
        }
    }

    private static void status(CommandSender to) {
        Path[] f = ThumbnailCache.folders();
        Path vs = VsScreen.picturesFolder();
        Path root = PictureCache.root();
        say(to, I18n.tr("cmd.pictures.status.title").param("folder", root == null ? "?" : root.toString()), INFO);
        say(to, I18n.tr("cmd.pictures.status.thumbs").param("count", pngs(f[0])).param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(f[0])))
                .param("when", when(to, f[0])), INFO);
        say(to, I18n.tr("cmd.pictures.status.vs").param("count", VsScreen.registeredCount())
                .param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(vs))).param("when", when(to, vs)), INFO);
        say(to, I18n.tr("cmd.pictures.status.holo").param("count", ThumbnailCache.hologramCount())
                .param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(f[1]))), INFO);
        say(to, I18n.tr("cmd.pictures.status.icons").param("count", ThumbnailCache.iconCount())
                .param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(f[2]))), INFO);
        say(to, download("cmd.pictures.status.download"), INFO);
        if (ThumbnailCache.running()) {
            say(to, progress("cmd.pictures.status.running", PictureProgress.now()), INFO);
        } else {
            say(to, I18n.tr("cmd.pictures.status.idle"), INFO);
        }
    }

    private static void rebuild(CommandSender to, boolean force) {
        AtomicBoolean finished = new AtomicBoolean();
        boolean started = ThumbnailCache.rebuildAll(force, s -> {
            finished.set(true);
            summary(to, s, force);
        });
        if (!started) {
            PictureProgress.Now now = PictureProgress.now();
            say(to, now.phase().isEmpty() ? I18n.tr("cmd.pictures.busy.plain") : progress("cmd.pictures.busy", now), ERR);
            return;
        }
        MobBattlePlugin.log("[pictures] rebuild" + (force ? " force" : "") + " started");
        say(to, I18n.tr(force ? "cmd.pictures.started.force" : "cmd.pictures.started"), INFO);
        // a line every few seconds while it draws, only when the count moved
        String[] last = {""};
        ScheduledFuture<?>[] tick = new ScheduledFuture<?>[1];
        tick[0] = com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            if (finished.get() || !ThumbnailCache.running()) {
                ScheduledFuture<?> t = tick[0];
                if (t != null) t.cancel(false);
                return;
            }
            PictureProgress.Now now = PictureProgress.now();
            String key = now.phase() + "|" + now.done();
            if (now.phase().isEmpty() || key.equals(last[0])) return;
            last[0] = key;
            say(to, progress("cmd.pictures.progress", now), INFO);
        }, PROGRESS_EVERY_S, PROGRESS_EVERY_S, TimeUnit.SECONDS);
    }

    private static void summary(CommandSender to, ThumbnailCache.Summary s, boolean force) {
        Path[] f = ThumbnailCache.folders();
        ThumbnailCache.Report r = s.thumbs();
        VsScreen.Prepared vs = s.vs();
        say(to, I18n.tr("cmd.pictures.done").param("time", MobBattlePlugin.millis(s.millis())), OK);
        say(to, I18n.tr("cmd.pictures.done.thumbs").param("drawn", r.rendered()).param("cached", r.cached()).param("failed", r.failed())
                .param("time", MobBattlePlugin.millis(r.millis())).param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(f[0]))), OK);
        say(to, I18n.tr("cmd.pictures.done.vs").param("drawn", vs.drawn()).param("drawnSize", MobBattlePlugin.size(vs.bytes()))
                .param("time", MobBattlePlugin.millis(vs.millis()))
                .param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(VsScreen.picturesFolder()))), OK);
        say(to, I18n.tr("cmd.pictures.done.holo").param("count", ThumbnailCache.hologramCount())
                .param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(f[1]))), OK);
        say(to, I18n.tr("cmd.pictures.done.icons").param("count", ThumbnailCache.iconCount())
                .param("size", MobBattlePlugin.size(MobBattlePlugin.sizeOf(f[2]))), OK);
        say(to, download("cmd.pictures.done.download"), OK);
        if (s.sent() > 0) say(to, I18n.tr("cmd.pictures.done.sent").param("count", s.sent()), INFO);
        if (force) say(to, I18n.tr("cmd.pictures.done.restart"), INFO);
        MobBattlePlugin.log("[pictures] rebuild done in " + MobBattlePlugin.millis(s.millis()) + ": thumbnails "
                + ThumbnailCache.describe(r) + "; VS " + vs.drawn() + " drawn (" + MobBattlePlugin.size(vs.bytes()) + ") in "
                + MobBattlePlugin.millis(vs.millis()) + "; " + s.sent() + " sent at once");
    }

    private static Message download(String key) {
        long vs = VsScreen.registeredBytes(), holo = ThumbnailCache.hologramBytes(), icons = ThumbnailCache.iconBytes();
        return I18n.tr(key).param("total", MobBattlePlugin.size(vs + holo + icons)).param("vs", MobBattlePlugin.size(vs))
                .param("holo", MobBattlePlugin.size(holo)).param("icons", MobBattlePlugin.size(icons));
    }

    private static Message progress(String key, PictureProgress.Now now) {
        return I18n.tr(key).param("phase", I18n.tr("cmd.pictures.phase." + (now.phase().isEmpty() ? "thumbnails" : now.phase())))
                .param("done", now.done()).param("total", now.total()).param("pct", now.percent());
    }

    /** "2026-10-17 14:51", or "never" when the folder has no file. */
    private static Message when(CommandSender to, Path dir) {
        long t = MobBattlePlugin.newestFile(dir);
        if (t == 0) return I18n.tr("cmd.pictures.never");
        return Message.raw(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), java.time.ZoneId.systemDefault())));
    }

    private static int pngs(Path dir) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return 0;
        try (var list = java.nio.file.Files.list(dir)) {
            return (int) list.filter(p -> p.getFileName().toString().endsWith(".png")).count();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /** To the sender, from any thread; a player who left meanwhile just misses it. */
    private static void say(CommandSender to, Message m, String color) {
        try {
            to.sendMessage(m.color(color));
        } catch (RuntimeException e) {
            MobBattlePlugin.log("[pictures] message not sent: " + e);
        }
    }
}
