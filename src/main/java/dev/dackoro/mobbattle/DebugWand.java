package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Debug Wand. Left click a mob: a full report (chat summary, server log, and a file in the plugin data folder under
 * debug/), then the mob is followed for {@link FxSettings#debugTrackSeconds} (0 = report only): once a second its
 * target, AI state, health and effect changes, every hit it gives or takes (DebugHitSystem: amount, cause, cancelled),
 * plus where it is every {@link #HEARTBEAT_MS}, go into the same file,
 * the server log and chat (playtest 2026-10-12: one log with the snapshot and what came after). One followed mob per
 * player; a new left click replaces it, a right click stops it. Read-only apart from the tracking state.
 * Everything that reads components runs on the mob's world thread (the interactions and BattleLifecycleSystem.tick).
 * Tracks are keyed by the player's UUID, never a PlayerRef; a track whose mob is gone or dead is dropped.
 */
final class DebugWand {

    /** While following, a line with position, health and distance to the target this often, even with no change. */
    static final long HEARTBEAT_MS = 5000;
    private static final String INFO = "#7fd3ff";
    private static final String WARN = "#ffcc55";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Player UUID -> the mob they follow. */
    private static final Map<UUID, Track> TRACKS = new ConcurrentHashMap<>();

    private DebugWand() {
    }

    /** One followed mob: what was seen last second (effects id -> remaining seconds, -1 = infinite; health; AI). */
    private static final class Track {
        final UUID owner;
        final Ref<EntityStore> mob;
        final String name;
        final long startMs;
        final long untilMs;
        /** The report file the lines are appended to (null: it could not be written). */
        final Path file;
        Map<String, Float> effects = new HashMap<>();
        float health = Float.NaN;
        String ai = "";
        long nextHeartbeat;
        /** Hits given/taken since the last tick (DebugHitSystem, world thread), written by the next tick. */
        final List<String> hits = new ArrayList<>();
        int hitsDropped;

        Track(UUID owner, Ref<EntityStore> mob, String name, long startMs, long untilMs, Path file) {
            this.owner = owner;
            this.mob = mob;
            this.name = name;
            this.startMs = startMs;
            this.untilMs = untilMs;
            this.file = file;
            this.nextHeartbeat = startMs + HEARTBEAT_MS;
        }
    }

    /** BattleLifecycleSystem checks this once per second: nothing tracked means nothing to do. */
    static boolean any() {
        return !TRACKS.isEmpty();
    }

    /** At most this many hit lines per followed mob and second; the rest are counted. */
    private static final int MAX_HITS_PER_TICK = 12;

    /**
     * A hit (DebugHitSystem, world thread, after our damage filters): noted when the attacker or the victim is
     * followed, with the amount, cause and whether it ends up cancelled.
     */
    static void onHit(Ref<EntityStore> attacker, Ref<EntityStore> victim, float amount, String cause, boolean cancelled,
                      ComponentAccessor<EntityStore> accessor) {
        for (Track t : TRACKS.values()) {
            boolean gave = attacker != null && t.mob.equals(attacker);
            boolean took = t.mob.equals(victim);
            if (!gave && !took) continue;
            if (t.hits.size() >= MAX_HITS_PER_TICK) {
                t.hitsDropped++;
                continue;
            }
            String other = gave ? nameOrNone(victim, accessor) : nameOrNone(attacker, accessor);
            t.hits.add((gave && took ? "hit itself" : gave ? "hit " + other : "hit by " + other) + " for " + f1(amount)
                    + " (" + cause + ")" + (cancelled ? ", CANCELLED (no damage)" : ""));
        }
    }

    private static String nameOrNone(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (ref == null || !ref.isValid()) return "nothing (no entity source)";
        try {
            return ChaosWand.nameOf(ref, accessor) + "#" + ref.getIndex();
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    // ---- follow (after a left click) ----

    /** Starts following {@code mob} for debugTrack seconds, appending to {@code file}; replaces the player's last one. */
    private static void startTrack(PlayerRef player, Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer,
                                   String name, Path file) {
        UUID owner = player.getUuid();
        long now = System.currentTimeMillis();
        Track old = TRACKS.remove(owner);
        if (old != null) finish(old, "the Debug Wand clicked " + (old.mob.equals(mob) ? "it again" : "another mob"));
        Track track = new Track(owner, mob, name, now, now + FxSettings.debugTrackSeconds * 1000L, file);
        track.effects = effectsOf(mob, buffer);
        track.health = healthOf(mob, buffer);
        track.ai = aiOf(mob);
        TRACKS.put(owner, track);
        append(track, "\n[Follow for " + FxSettings.debugTrackSeconds + " s]\n" + stamp(track, now) + "start: "
                + track.ai + where(mob, buffer) + "\n");
        MobBattlePlugin.log("[debug wand] follow started by " + player.getUsername() + ": " + name);
    }

    /** Right click: stops the player's follow, if any. */
    static void stopTrack(PlayerRef player) {
        Track old = TRACKS.remove(player.getUuid());
        if (old == null) {
            say(player, "Not following any mob. Left click one: report, then " + FxSettings.debugTrackSeconds
                    + " s of follow (debugTrack).", WARN);
            return;
        }
        finish(old, "stopped by " + player.getUsername());
    }

    /** Once per second per world (BattleLifecycleSystem, on the world thread): reports changes and prunes. */
    static void tick(Store<EntityStore> store) {
        if (TRACKS.isEmpty() || store.isShutdown()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Track> entry : TRACKS.entrySet()) {
            Track t = entry.getValue();
            if (!t.mob.isValid()) {
                // Any world's tick may find it gone; remove() makes sure only one of them reports it.
                if (TRACKS.remove(entry.getKey(), t)) finish(t, "the mob is gone (died or unloaded)");
                continue;
            }
            if (t.mob.getStore() != store) continue;
            if (store.getComponent(t.mob, DeathComponent.getComponentType()) != null) {
                if (TRACKS.remove(entry.getKey(), t)) finish(t, "the mob died");
                continue;
            }
            if (now >= t.untilMs) {
                if (TRACKS.remove(entry.getKey(), t)) finish(t, (t.untilMs - t.startMs) / 1000 + " s are up");
                continue;
            }
            PlayerRef owner = Universe.get().getPlayer(t.owner);
            if (owner == null) {
                if (TRACKS.remove(entry.getKey(), t)) finish(t, "owner offline");
                continue;
            }
            List<String> changes = new ArrayList<>(t.hits);
            if (t.hitsDropped > 0) changes.add("(+" + t.hitsDropped + " more hits this second)");
            t.hits.clear();
            t.hitsDropped = 0;
            String ai = aiOf(t.mob);
            if (!ai.equals(t.ai)) changes.add(ai + where(t.mob, store));
            Map<String, Float> effects = effectsOf(t.mob, store);
            for (Map.Entry<String, Float> e : effects.entrySet()) {
                if (!t.effects.containsKey(e.getKey())) {
                    changes.add("+ " + e.getKey() + " (" + remaining(e.getValue()) + ")");
                }
            }
            for (String id : t.effects.keySet()) {
                if (!effects.containsKey(id)) changes.add("- " + id);
            }
            float health = healthOf(t.mob, store);
            if (!Float.isNaN(health) && !Float.isNaN(t.health) && Math.abs(health - t.health) > 0.05f) {
                changes.add("health " + f1(t.health) + " -> " + f1(health) + maxOf(t.mob, store));
            }
            t.ai = ai;
            t.effects = effects;
            if (!Float.isNaN(health)) t.health = health;
            StringBuilder file = new StringBuilder();
            for (String line : changes) {
                say(owner, "[follow " + t.name + "] " + line, INFO);
                MobBattlePlugin.log("[debug wand] follow " + t.name + ": " + line);
                file.append(stamp(t, now)).append(line).append('\n');
            }
            if (now >= t.nextHeartbeat) {
                // File only: where it is and how far its target is, so a mob wandering off shows even with no change.
                t.nextHeartbeat = now + HEARTBEAT_MS;
                file.append(stamp(t, now)).append("still: health ").append(Float.isNaN(health) ? "n/a" : f1(health))
                        .append(where(t.mob, store)).append('\n');
            }
            if (file.length() > 0) append(t, file.toString());
        }
    }

    private static void finish(Track t, String reason) {
        PlayerRef owner = Universe.get().getPlayer(t.owner);
        String text = "Follow of " + t.name + " ended: " + reason + ".";
        if (owner != null) say(owner, text, WARN);
        MobBattlePlugin.log("[debug wand] " + text);
        append(t, stamp(t, System.currentTimeMillis()) + text + "\n");
    }

    /** "[+12s] " from the start of the follow. */
    private static String stamp(Track t, long now) {
        return "[+" + (now - t.startMs) / 1000 + "s] ";
    }

    /** Appends to the follow's report file; a failure goes to the server log and is otherwise ignored. */
    private static void append(Track t, String text) {
        if (t.file == null) return;
        try {
            Files.writeString(t.file, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            MobBattlePlugin.log("[debug wand] could not append to " + t.file.getFileName() + ": " + ex);
        }
    }

    /** AI state, target, locked target and attitude: what the follow reports when it changes. */
    private static String aiOf(Ref<EntityStore> mob) {
        try {
            return BattleDebug.followFields(mob, mob.getStore());
        } catch (RuntimeException ex) {
            return "AI n/a (" + ex.getClass().getSimpleName() + ")";
        }
    }

    /** ", at x y z, target 4.2 blocks away". */
    private static String where(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        Vector3d p = ChaosWand.positionOf(mob, buffer);
        String at = p == null ? "" : String.format(Locale.ROOT, ", at %.1f %.1f %.1f", p.x, p.y, p.z);
        String d;
        try {
            d = BattleDebug.targetDistance(mob, mob.getStore());
        } catch (RuntimeException ex) {
            d = null;
        }
        return at + (d == null ? "" : ", target " + d + " blocks away");
    }

    // ---- left click: inspect ----

    /** Full report of {@code mob}: chat summary, server log and debug/&lt;role&gt;_&lt;time&gt;.txt. */
    static void inspect(PlayerRef player, Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        String name = ChaosWand.nameOf(mob, buffer);
        String role = roleOf(mob, buffer);
        String report = report(player, mob, buffer, name);

        String fileName = sanitize(role != null ? role : name) + "_" + LocalDateTime.now().format(STAMP) + ".txt";
        String where;
        Path written = null;
        Path dir = FxSettings.dataDirectory();
        if (dir == null) {
            where = "not written (the plugin data folder is not known yet)";
        } else {
            try {
                Path folder = dir.resolve("debug");
                Files.createDirectories(folder);
                Path file = folder.resolve(fileName);
                Files.writeString(file, report, StandardCharsets.UTF_8);
                written = file;
                where = "written to debug/" + fileName;
            } catch (IOException | RuntimeException ex) {
                where = "not written (" + ex + ")";
            }
        }
        MobBattlePlugin.log("[debug wand] inspect " + name + ": report " + where + "\n" + report);

        say(player, "Debug: " + name + (role != null ? " (role " + role + ")" : "") + ".", INFO);
        say(player, "Health " + healthText(mob, buffer), INFO);
        Map<String, Float> effects = effectsOf(mob, buffer);
        say(player, effects.isEmpty() ? "Effects: none"
                : "Effects (" + effects.size() + "): " + String.join(", ", effects.keySet()), INFO);
        BattleManager.Battle battle = safeBattle(mob);
        say(player, battle == null ? "Battle: none"
                : "Battle: " + battle.kind.key + ", team " + battle.name(battle.team(mob)), INFO);
        String buffs = buffsText(mob);
        say(player, "Buffs: " + buffs, INFO);
        say(player, "Report " + (where.startsWith("written") ? fileName + " (debug/ in the plugin data folder)"
                : where), where.startsWith("written") ? INFO : WARN);
        if (FxSettings.debugTrackSeconds > 0) {
            startTrack(player, mob, buffer, name, written);
            say(player, "Following " + name + " for " + FxSettings.debugTrackSeconds + " s (target, AI state, health, effects"
                    + (written != null ? ", in the same file" : "") + "). Right click stops it.", INFO);
        }
    }

    private static String report(PlayerRef player, Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer,
                                 String name) {
        Store<EntityStore> store = mob.getStore();
        StringBuilder sb = new StringBuilder();
        sb.append("Debug Wand inspect of ").append(name).append(" at ")
                .append(LocalDateTime.now().format(STAMP)).append(" (inspected by ")
                .append(player.getUsername()).append(")\n");

        section(sb, "Identity");
        line(sb, "name", () -> ChaosWand.nameOf(mob, buffer));
        line(sb, "role", () -> String.valueOf(roleOf(mob, buffer)));
        line(sb, "uuid", () -> {
            UUIDComponent u = buffer.getComponent(mob, UUIDComponent.getComponentType());
            return u == null ? "none" : u.getUuid().toString();
        });
        line(sb, "world", () -> store.getExternalData().getWorld().getName());
        line(sb, "position", () -> {
            Vector3d p = ChaosWand.positionOf(mob, buffer);
            return p == null ? "n/a" : String.format(Locale.ROOT, "%.2f %.2f %.2f", p.x, p.y, p.z);
        });
        line(sb, "rotation", () -> {
            TransformComponent t = buffer.getComponent(mob, TransformComponent.getComponentType());
            return t == null ? "n/a" : "yaw " + f2(t.getRotation().yaw()) + ", pitch " + f2(t.getRotation().pitch());
        });
        line(sb, "player", () -> {
            PlayerRef p = buffer.getComponent(mob, PlayerRef.getComponentType());
            return p == null ? "no" : "yes (" + p.getUsername() + ")";
        });

        section(sb, "Model");
        line(sb, "model asset", () -> modelOf(mob, buffer).getModelAssetId());
        line(sb, "model", () -> modelOf(mob, buffer).getModel());
        line(sb, "texture", () -> modelOf(mob, buffer).getTexture());
        line(sb, "scale", () -> f2(modelOf(mob, buffer).getScale()));
        line(sb, "attachments", () -> {
            var attachments = modelOf(mob, buffer).getAttachments();
            return String.valueOf(attachments == null ? 0 : attachments.length);
        });
        line(sb, "entity scale", () -> {
            EntityScaleComponent c = buffer.getComponent(mob, EntityScaleComponent.getComponentType());
            return c == null ? "none (base)" : f2(c.getScale());
        });

        section(sb, "Stats");
        for (String id : new TreeSet<>(EntityStatType.getAssetMap().getAssetMap().keySet())) {
            line(sb, id, () -> statText(mob, buffer, id));
        }

        section(sb, "Effects");
        line(sb, "invulnerable", () -> String.valueOf(
                buffer.getComponent(mob, EffectControllerComponent.getComponentType()).isInvulnerable()));
        ActiveEntityEffect[] active = activeOf(mob, buffer);
        line(sb, "active", () -> active == null ? "n/a" : String.valueOf(active.length));
        if (active != null) {
            for (ActiveEntityEffect e : active) {
                String id = idOf(e.getEntityEffectIndex());
                line(sb, "effect " + id, () -> describeEffect(e));
            }
        }

        section(sb, "MobBattle");
        line(sb, "battle", () -> {
            BattleManager.Battle b = BattleManager.battleOf(mob);
            if (b == null) return "none";
            int team = b.team(mob);
            return b.kind.key + " id " + b.id.toString().substring(0, 8) + ", team " + team + " (" + b.name(team)
                    + ", " + b.colors[team < 0 || team >= b.colors.length ? 0 : team] + "), "
                    + b.teamOf.size() + " members, owner " + (b.owner == null ? "server" : b.owner.getUsername())
                    + (b.over ? ", over" : "");
        });
        line(sb, "managed", () -> String.valueOf(BattleManager.isManaged(mob)));
        line(sb, "wand fight", () -> {
            if (!FightManager.isFighter(mob)) return "no";
            Ref<EntityStore> enemy = FightManager.enemyOf(mob);
            return "yes, vs " + (enemy == null ? "none" : ChaosWand.nameOf(enemy, buffer));
        });
        line(sb, "army owner", () -> {
            var owner = Army.ownerOf(mob);
            if (owner == null) return "none";
            PlayerRef p = Universe.get().getPlayer(owner);
            return owner + (p == null ? "" : " (" + p.getUsername() + ")") + ", member " + Army.isMember(owner, mob);
        });
        for (Buffs.Stat st : Buffs.Stat.values()) {
            line(sb, "buff " + st.key, () -> {
                int pct = Buffs.totalOf(mob, st);
                return pct > 0 ? Buffs.both(st, pct) : "none";
            });
        }
        line(sb, "buff wand stacks", () -> String.valueOf(Buffs.wandStacks(mob)));
        line(sb, "morph original", () -> {
            Model original = MorphWand.originalOf(mob);
            return original == null ? "none" : original.getModelAssetId();
        });
        line(sb, "summon origin", () -> "not tracked");

        section(sb, "AI");
        line(sb, "state", () -> BattleDebug.aiFields(mob, store).trim());

        return sb.toString();
    }

    // ---- helpers ----

    /** Runs one report line; a failing read writes "n/a" and the reason goes to the server log. */
    private static void line(StringBuilder sb, String label, Supplier<String> value) {
        String v;
        try {
            v = value.get();
        } catch (RuntimeException ex) {
            v = "n/a";
            MobBattlePlugin.log("[debug wand] report line '" + label + "' failed: " + ex);
        }
        sb.append("  ").append(label).append(": ").append(v == null ? "n/a" : v).append('\n');
    }

    private static void section(StringBuilder sb, String title) {
        sb.append('\n').append('[').append(title).append("]\n");
    }

    private static Model modelOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        ModelComponent mc = buffer.getComponent(mob, ModelComponent.getComponentType());
        return mc.getModel();
    }

    private static String roleOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        NPCEntity npc = buffer.getComponent(mob, NPCEntity.getComponentType());
        return npc == null ? null : npc.getRoleName();
    }

    private static String statText(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer, String id) {
        EntityStatMap stats = buffer.getComponent(mob, EntityStatMap.getComponentType());
        if (stats == null) return "n/a";
        int index = EntityStatType.getAssetMap().getIndex(id);
        EntityStatValue v = stats.get(index);
        if (v == null) return "absent";
        return f2(v.get()) + " (min " + f2(v.getMin()) + ", max " + f2(v.getMax()) + ")"
                + (v.getModifiers() == null || v.getModifiers().isEmpty() ? "" : ", modifiers " + v.getModifiers().keySet());
    }

    private static ActiveEntityEffect[] activeOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        EffectControllerComponent c = buffer.getComponent(mob, EffectControllerComponent.getComponentType());
        // getAllActiveEntityEffects is null (not empty) on a mob with no effect: a Spider crashed the click (2026-10-12).
        ActiveEntityEffect[] all = c != null ? c.getAllActiveEntityEffects() : null;
        return all != null ? all : new ActiveEntityEffect[0];
    }

    /** Effect id -> remaining seconds (-1 = infinite). An effect applied twice keeps the longer one. */
    private static Map<String, Float> effectsOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        Map<String, Float> map = new HashMap<>();
        for (ActiveEntityEffect e : activeOf(mob, buffer)) {
            float rem = e.isInfinite() ? -1f : e.getRemainingDuration();
            map.merge(idOf(e.getEntityEffectIndex()), rem, Math::max);
        }
        return map;
    }

    private static String idOf(int index) {
        EntityEffect asset = EntityEffect.getAssetMap().getAsset(index);
        return asset != null ? asset.getId() : "effect#" + index;
    }

    private static String describeEffect(ActiveEntityEffect e) {
        EntityEffect asset = EntityEffect.getAssetMap().getAsset(e.getEntityEffectIndex());
        String vfx = "n/a", bottom = "n/a", top = "n/a";
        if (asset != null && asset.getApplicationEffects() != null) {
            var packet = asset.getApplicationEffects().toPacket();
            vfx = packet.modelVFXId == null ? "none" : packet.modelVFXId;
            bottom = colorText(packet.entityBottomTint);
            top = colorText(packet.entityTopTint);
        }
        return (e.isInfinite() ? "infinite" : "remaining " + f1(e.getRemainingDuration()) + " s of "
                + f1(e.getInitialDuration()) + " s")
                + ", debuff " + e.isDebuff() + ", invulnerable " + e.isInvulnerable()
                + ", modelVFX " + vfx + ", tint bottom " + bottom + ", top " + top;
    }

    private static String colorText(com.hypixel.hytale.protocol.Color c) {
        return c == null ? "none" : "rgb(" + (c.red & 0xff) + "," + (c.green & 0xff) + "," + (c.blue & 0xff) + ")";
    }

    /** Health as a float, NaN if the mob has no health stat. */
    private static float healthOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        EntityStatMap stats = buffer.getComponent(mob, EntityStatMap.getComponentType());
        EntityStatValue v = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        return v == null ? Float.NaN : v.get();
    }

    private static String healthText(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        EntityStatMap stats = buffer.getComponent(mob, EntityStatMap.getComponentType());
        EntityStatValue v = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        if (v == null) return "n/a";
        float max = v.getMax();
        return f1(v.get()) + " / " + f1(max) + (max > 0 ? " (" + Math.round(100f * v.get() / max) + "%)" : "");
    }

    private static String maxOf(Ref<EntityStore> mob, ComponentAccessor<EntityStore> buffer) {
        EntityStatMap stats = buffer.getComponent(mob, EntityStatMap.getComponentType());
        EntityStatValue v = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        return v == null ? "" : " (max " + f1(v.getMax()) + ")";
    }

    private static BattleManager.Battle safeBattle(Ref<EntityStore> mob) {
        try {
            return BattleManager.battleOf(mob);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String buffsText(Ref<EntityStore> mob) {
        StringBuilder sb = new StringBuilder();
        for (Buffs.Stat st : Buffs.Stat.values()) {
            int pct = Buffs.totalOf(mob, st);
            if (pct > 0) sb.append(sb.length() > 0 ? ", " : "").append(st.key).append(" +").append(pct).append('%');
        }
        int stacks = Buffs.wandStacks(mob);
        if (stacks > 0) sb.append(sb.length() > 0 ? ", " : "").append("wand stacks ").append(stacks);
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String remaining(float seconds) {
        return seconds < 0 ? "infinite" : f1(seconds) + "s";
    }

    private static String sanitize(String text) {
        return text == null || text.isBlank() ? "mob" : text.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String f1(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void say(PlayerRef player, String text, String color) {
        player.sendMessage(Message.raw(text).color(color));
    }
}
