package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /mobbattle apply &lt;who&gt; &lt;action&gt; [value] (2026-10-13, user: wand actions on a chosen player or mob, for quick
 * setting tests and for server admins). Admin only, with a real grant: it acts on other players.
 * <ul>
 *   <li>who: a player name, {@code me}, {@code look} (the mob or player you look at, up to LOOK_RANGE), {@code near
 *   <radius>} (every mob and player around you, not you) or {@code players} (every player in your world).</li>
 *   <li>actions: buff &lt;clicks&gt; (Buff Wand stacks, 0 = off), size|health|damage|defense|speed &lt;value&gt; (direct amount in
 *   the buff unit, no shimmer; 0 = off), unbuff, heal, cleanse, effect &lt;id[&gt;id]&gt;, look &lt;jade|shimmer&gt;[:color],
 *   morph &lt;model|player&gt;, unmorph, army add|remove (your army, mobs only), team &lt;team|none&gt; (your Team Wand
 *   draft). No kill or snap on purpose.</li>
 * </ul>
 * Every change runs on the target's world thread with the wands' own code, so it looks and behaves as a click.
 */
final class ApplyCommand {

    static final String USAGE_SYNTAX = "/mobbattle apply <me|look|players|near <radius>|player <name>|id:<uuid>|last> <buff <clicks>|size|health|damage|defense|speed <value>"
            + "|unbuff|heal|cleanse|effect <id>|look <jade|shimmer>[:color]|morph <model|player>|unmorph|army add [player|id:<uuid>|last]|army remove|team <team|none>>";
    static final String USAGE = "Usage: " + USAGE_SYNTAX;
    static final List<String> ACTIONS = List.of("buff", "size", "health", "damage", "defense", "speed", "unbuff", "heal", "cleanse",
            "effect", "look", "morph", "unmorph", "army", "team");
    static final double LOOK_RANGE = 64;
    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";

    private ApplyCommand() {
    }

    static void run(CommandContext ctx, String[] a) {
        if (!Perms.admin(ctx.sender())) {
            reply(ctx, I18n.tr("cmd.noperm").param("perm", Perms.ADMIN), "No permission (needs " + Perms.ADMIN + ").", ERR);
            return;
        }
        if (a.length < 2) {
            usage(ctx);
            return;
        }
        int i = 0;
        String who = a[i++].toLowerCase(Locale.ROOT);
        // "player <name>" (the suggested form) is the same as a plain name.
        String playerName = a[0];
        if (who.equals("player")) {
            if (a.length < 3) {
                usage(ctx);
                return;
            }
            playerName = a[i++];
            who = playerName.toLowerCase(Locale.ROOT);
        }
        // id:<uuid>, a bare UUID or last (a /mobbattle test spawn): that one entity, from the console too.
        Ref<EntityStore> byId = null;
        if (TestTools.isIdWord(who)) {
            // playerName: a[1] for "player id:<uuid>", a[0] otherwise.
            byId = TestTools.resolve(playerName);
            if (byId == null) {
                reply(ctx, I18n.tr("cmd.measure.noentity").param("id", playerName), "No entity " + playerName + ".", ERR);
                return;
            }
            who = "id";
        }
        int radius = 0;
        if (who.equals("near")) {
            try {
                radius = Math.max(1, Math.min(FxSettings.MAX_RADIUS, Integer.parseInt(a[i++])));
            } catch (RuntimeException e) {
                reply(ctx, I18n.tr("cmd.usage").param("usage", "/mobbattle apply near <radius> <action> [value]"),
                        "Usage: /mobbattle apply near <radius> <action> [value]", ERR);
                return;
            }
        }
        if (i >= a.length) {
            usage(ctx);
            return;
        }
        String action = a[i++].toLowerCase(Locale.ROOT);
        String value = i < a.length ? String.join(" ", java.util.Arrays.copyOfRange(a, i, a.length)) : null;
        if (!ACTIONS.contains(action)) {
            reply(ctx, Message.join(I18n.tr("cmd.apply.unknownaction").param("action", action), Message.raw(" "), usageMsg()),
                    "Unknown action " + action + ". " + USAGE, ERR);
            return;
        }
        Problem problem = check(action, value);
        if (problem != null) {
            reply(ctx, problem.chat(), problem.log(), ERR);
            return;
        }
        // army add <owner> (2026-10-14, user): into another player's army, or a mob's (that mob leads it). Looked up
        // here, on the command thread: TestTools.resolve blocks on the world thread.
        Ref<EntityStore> owner = null;
        String[] armyWords = action.equals("army") && value != null ? value.trim().split("\s+") : new String[0];
        if (armyWords.length > 1) {
            PlayerRef op = onlinePlayer(armyWords[1]);
            owner = op != null ? op.getReference()
                    : TestTools.isIdWord(armyWords[1].toLowerCase(Locale.ROOT)) ? TestTools.resolve(armyWords[1]) : null;
            if (owner == null || !owner.isValid()) {
                reply(ctx, I18n.tr("cmd.apply.noleader").param("name", armyWords[1]), "No player or mob " + armyWords[1] + ".", ERR);
                return;
            }
        }
        Ref<EntityStore> armyOwner = owner;
        Ref<EntityStore> me = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        boolean needsMe = who.equals("me") || who.equals("look") || who.equals("near") || who.equals("players")
                || action.equals("army") && armyOwner == null && armyWords.length > 0 && armyWords[0].equalsIgnoreCase("add")
                || action.equals("team");
        if (needsMe && (me == null || !me.isValid())) {
            String what = action.equals("army") || action.equals("team") ? action : who;
            reply(ctx, I18n.tr("cmd.apply.needsplayer").param("what", what), "Only a player in a world can use " + what + ".", ERR);
            return;
        }
        Ref<EntityStore> anchor;
        if (byId != null) {
            anchor = byId;
        } else if (who.equals("me") || who.equals("look") || who.equals("near") || who.equals("players")) {
            anchor = me;
        } else {
            PlayerRef target = onlinePlayer(playerName);
            Ref<EntityStore> ref = target != null ? target.getReference() : null;
            if (ref == null || !ref.isValid()) {
                reply(ctx, I18n.tr("cmd.apply.noplayer").param("name", playerName), "No player online named " + playerName + ".", ERR);
                return;
            }
            anchor = ref;
        }
        Store<EntityStore> store = anchor.getStore();
        int r = radius;
        String whom = who;
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown() || !anchor.isValid()) return;
            List<Ref<EntityStore>> targets = targets(whom, r, anchor, me, store);
            if (targets.isEmpty()) {
                reply(ctx, whom.equals("look") ? I18n.tr("cmd.apply.notlooking").param("range", (int) LOOK_RANGE) : I18n.tr("cmd.apply.nobody"),
                        whom.equals("look") ? "You are not looking at a mob or player (up to " + (int) LOOK_RANGE + " blocks)." : "Nobody there.", ERR);
                return;
            }
            PlayerRef sender = me != null && me.isValid() && me.getStore() == store
                    ? store.getComponent(me, PlayerRef.getComponentType()) : null;
            int done = 0;
            List<String> skipped = new ArrayList<>();
            List<Message> skippedMsgs = new ArrayList<>();
            List<Ref<EntityStore>> cleansed = new ArrayList<>();
            List<String> cleanseFx = new ArrayList<>();
            for (Ref<EntityStore> t : targets) {
                if (!t.isValid()) continue;
                String why = apply(t, action, value, sender, me, armyOwner, store, cleansed, cleanseFx);
                if (why == null) done++;
                else {
                    String nm = ChaosWand.nameOf(t, store);
                    skipped.add(nm + " (" + why + ")");
                    skippedMsgs.add(Message.join(Message.raw(nm + " ("), whyMsg(why), Message.raw(")")));
                }
            }
            CleanseInteraction.landLater(cleansed, cleanseFx);
            String names = targets.size() == 1 ? ChaosWand.nameOf(targets.get(0), store) : done + " of " + targets.size();
            Message namesMsg = targets.size() == 1 ? Message.raw(names)
                    : I18n.tr("cmd.apply.ofcount").param("done", done).param("total", targets.size());
            Message chat = done > 0
                    ? I18n.tr("cmd.apply.done").param("action", action).param("value", value != null ? " " + value : "")
                            .param("names", namesMsg).param("detail", detailMsg(action, value))
                    : I18n.tr("cmd.apply.nothing");
            if (!skippedMsgs.isEmpty()) {
                List<Message> list = new ArrayList<>();
                for (int k = 0; k < Math.min(5, skippedMsgs.size()); k++) {
                    if (k > 0) list.add(Message.raw(", "));
                    list.add(skippedMsgs.get(k));
                }
                if (skippedMsgs.size() > 5) list.add(Message.raw("..."));
                chat = Message.join(chat, Message.raw(" "), I18n.tr("cmd.apply.skipped").param("list", Message.join(list.toArray(new Message[0]))));
            }
            reply(ctx, chat, (done > 0 ? "Applied " + action + (value != null ? " " + value : "") + " to " + names + detail(action, value) + "." : "Nothing applied.")
                    + (skipped.isEmpty() ? "" : " Skipped: " + String.join(", ", skipped.subList(0, Math.min(5, skipped.size())))
                    + (skipped.size() > 5 ? "..." : "")), done > 0 ? OK : ERR);
        });
    }

    /**
     * What the buff actions amount to, as the Buff Wand's chat line does (user, 2026-10-13: "applied buff 1" said
     * nothing about how much): ": 1/5 stacks (size x2 (+100%), ...)" or ": size x3 (+200%)". Empty for the rest.
     */
    private static Message detailMsg(String action, String value) {
        if (action.equals("buff")) {
            int n = Math.min(Buffs.wandMax, Integer.parseInt(value));
            return n == 0 ? I18n.tr("cmd.apply.detail.buffoff")
                    : I18n.tr("cmd.apply.detail.stacks").param("n", n).param("max", Buffs.wandMax).param("desc", Buffs.describeWand(n));
        }
        Buffs.Stat st = Buffs.Stat.of(action);
        if (st == null) return Message.raw("");
        int pct = Math.min(st.totalMax(), Buffs.parseValue(st, value));
        return pct <= 0 ? I18n.tr("cmd.apply.detail.off").param("action", action)
                : I18n.tr("cmd.apply.detail.amount").param("action", action).param("amount", Buffs.both(st, pct));
    }

    private static String detail(String action, String value) {
        if (action.equals("buff")) {
            int n = Math.min(Buffs.wandMax, Integer.parseInt(value));
            return n == 0 ? ": Buff Wand stacks off" : ": " + n + "/" + Buffs.wandMax + " stacks (" + Buffs.describeWand(n) + ")";
        }
        Buffs.Stat st = Buffs.Stat.of(action);
        if (st == null) return "";
        int pct = Math.min(st.totalMax(), Buffs.parseValue(st, value));
        return pct <= 0 ? ": " + action + " off" : ": " + action + " " + Buffs.both(st, pct);
    }

    /**
     * An online player by name, any case. Not Universe.getPlayerByUsername: it reads components from the command
     * thread and the server logs a SEVERE "called async" stack trace for every call (seen with vanilla /sudo).
     */
    static PlayerRef onlinePlayer(String name) {
        if (name == null) return null;
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (name.equalsIgnoreCase(p.getUsername())) return p;
        }
        return null;
    }

    /** One line per action, for the command suggestions. */
    static String describe(String action) {
        return switch (action) {
            case "buff" -> "Buff Wand stacks, as that many clicks (0 = off)";
            case "size", "health", "damage", "defense", "speed" -> "A direct " + action + " amount in the buff unit (x2, 100, 50%; 0 = off), no shimmer";
            case "unbuff" -> "Every buff off (not the effects)";
            case "heal" -> "Full health";
            case "cleanse" -> "As the Cleanse Wand: effects, buffs and morph go";
            case "effect" -> "An effect, as the Effect Wand";
            case "look" -> "A jade or shimmer look (jade:red...)";
            case "morph" -> "A model or a player's look";
            case "unmorph" -> "Its own model back";
            case "army" -> "Into or out of an army: yours, or add <player|id:<uuid>|last> for another player's or a leader mob's";
            case "team" -> "Into one of your Team Wand teams, or none";
            default -> action;
        };
    }

    /** A problem with the action's value: the chat text and the log text. */
    private record Problem(Message chat, String log) {
    }

    private static Problem usageProblem(String syntax, String log, Message note) {
        Message u = I18n.tr("cmd.usage").param("usage", syntax);
        return new Problem(note == null ? u : Message.join(u, Message.raw(" "), note), log);
    }

    /** A problem with the action's value, or null. */
    private static Problem check(String action, String value) {
        switch (action) {
            case "buff" -> {
                if (value == null || !value.matches("\\d{1,3}")) {
                    return usageProblem("apply <who> buff <clicks>", "Usage: apply <who> buff <clicks> (0 = off, up to " + Buffs.wandMax + ").",
                            I18n.tr("cmd.apply.note.buff").param("max", Buffs.wandMax));
                }
            }
            case "size", "health", "damage", "defense", "speed" -> {
                Buffs.Stat st = Buffs.Stat.of(action);
                if (value == null || st == null || Buffs.parseValue(st, value) < 0) {
                    String examples = Buffs.multiplier ? "x2, 1.5" : "100, 50%";
                    return usageProblem("apply <who> " + action + " <value>", "Usage: apply <who> " + action + " <value> (" + examples + "; 0 = off).",
                            I18n.tr("cmd.apply.note.stat").param("examples", examples));
                }
            }
            case "effect", "look" -> {
                String setting = action.equals("look") && value != null && !value.contains(":") && !value.toLowerCase(Locale.ROOT).matches("jade|shimmer")
                        ? null : value;
                if (setting == null || EffectWand.chain(setting).isEmpty()) {
                    if (action.equals("look")) {
                        return usageProblem("apply <who> look <jade|shimmer>[:color]", "Usage: apply <who> look <jade|shimmer>[:color]", null);
                    }
                    Problem p = usageProblem("apply <who> effect <id>", "Unknown effect. Usage: apply <who> effect <id> (A>B gives B when A ends).",
                            I18n.tr("cmd.apply.note.effect"));
                    return new Problem(Message.join(I18n.tr("cmd.apply.unknowneffect"), Message.raw(" "), p.chat()), p.log());
                }
            }
            case "morph" -> {
                if (value == null) return usageProblem("apply <who> morph <model|player name>", "Usage: apply <who> morph <model|player name>", null);
                if (MorphWand.findModel(value) == null && onlinePlayer(value) == null) {
                    return new Problem(I18n.tr("cmd.apply.unknownmodel").param("value", value),
                            "Unknown model or player: " + value + ". Search models with /mobbattle morph list <text>.");
                }
            }
            case "army" -> {
                String[] w = value == null ? new String[0] : value.trim().split("\s+");
                boolean ok = w.length == 1 && (w[0].equalsIgnoreCase("add") || w[0].equalsIgnoreCase("remove"))
                        || w.length == 2 && w[0].equalsIgnoreCase("add");
                if (!ok) return usageProblem("apply <who> army add [player|id:<uuid>|last] | army remove",
                        "Usage: apply <who> army add [player|id:<uuid>|last] | army remove", null);
            }
            case "team" -> {
                if (value == null || !value.equalsIgnoreCase("none") && BattleManager.teamIndex(value) < 0) {
                    return usageProblem("apply <who> team <team|none>", "Usage: apply <who> team <team|none>. " + Teams.describe(),
                            Teams.describeMessage());
                }
            }
            default -> {
                if (value != null) {
                    return usageProblem("apply <who> " + action, "Usage: apply <who> " + action + " (no value).", I18n.tr("cmd.apply.note.novalue"));
                }
            }
        }
        return null;
    }

    /** Who the command reaches, in the anchor's world. World thread. */
    private static List<Ref<EntityStore>> targets(String who, int radius, Ref<EntityStore> anchor, Ref<EntityStore> me,
                                                  Store<EntityStore> store) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        switch (who) {
            case "look" -> {
                Ref<EntityStore> seen = lookedAt(anchor, store);
                if (seen != null) out.add(seen);
            }
            case "near" -> {
                Vector3d c = ChaosWand.positionOf(anchor, store);
                if (c == null) break;
                // getAllEntitiesInSphere returns a thread-local list: copy it at once.
                for (Ref<EntityStore> r : new ArrayList<>(TargetUtil.getAllEntitiesInSphere(c, radius, store))) {
                    if (!r.equals(anchor) && isLiving(r, store)) out.add(r);
                }
            }
            case "players" -> {
                for (PlayerRef p : Universe.get().getPlayers()) {
                    Ref<EntityStore> r = p.getReference();
                    if (r != null && r.isValid() && r.getStore() == store) out.add(r);
                }
            }
            default -> out.add(anchor);
        }
        return out;
    }

    private static boolean isLiving(Ref<EntityStore> r, Store<EntityStore> store) {
        return (store.getComponent(r, NPCEntity.getComponentType()) != null || PlayerFighters.isPlayer(r, store))
                && BattleManager.isAlive(r, store);
    }

    /**
     * The mob or player whose body is nearest to the line from your eye to what you look at (the block it hits, else
     * LOOK_RANGE ahead), within its half width + 0.5. Null if none.
     */
    static Ref<EntityStore> lookedAt(Ref<EntityStore> me, Store<EntityStore> store) {
        // The game's own eye and look direction (TargetUtil.getLook: eye height + HeadRotation). The ray ends at the
        // block it hits, else LOOK_RANGE away: looking level over flat ground hits nothing, and a mob with only sky
        // behind it was never found (2026-10-13, tests driven from the console).
        var look = TargetUtil.getLook(me, store);
        if (look == null) return null;
        Vector3d eye = new Vector3d(look.getPosition());
        Vector3d dir = new Vector3d(look.getDirection());
        if (dir.length() < 1e-6) return null;
        dir.normalize();
        Vector3d hit = TargetUtil.getTargetLocation(me, LOOK_RANGE, store);
        double len = hit != null ? new Vector3d(hit).sub(eye).length() : LOOK_RANGE;
        Ref<EntityStore> best = null;
        double bestT = Double.MAX_VALUE;
        for (Ref<EntityStore> r : new ArrayList<>(TargetUtil.getAllEntitiesInSphere(eye, LOOK_RANGE, store))) {
            if (r.equals(me) || !isLiving(r, store)) continue;
            Vector3d q = ChaosWand.positionOf(r, store);
            if (q == null) continue;
            var bb = store.getComponent(r, com.hypixel.hytale.server.core.modules.entity.component.BoundingBox.getComponentType());
            double h = 1.8, w = 0.4;
            if (bb != null && bb.getBoundingBox() != null) {
                var box = bb.getBoundingBox();
                h = box.max.y - box.min.y;
                w = Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2;
            }
            Vector3d c = new Vector3d(q.x, q.y + h / 2, q.z).sub(eye);
            double t = c.dot(dir);
            if (t <= 0 || t > len + w + 1) continue;
            double off = new Vector3d(dir).mul(t).sub(c).length();
            if (off <= Math.max(w, h / 2) + 0.5 && t < bestT) {
                bestT = t;
                best = r;
            }
        }
        return best;
    }

    /** One target. Null when done, else why it was skipped. World thread. */
    private static String apply(Ref<EntityStore> t, String action, String value, PlayerRef sender, Ref<EntityStore> me,
                                Ref<EntityStore> armyOwner, Store<EntityStore> store, List<Ref<EntityStore>> cleansed,
                                List<String> cleanseFx) {
        boolean npc = store.getComponent(t, NPCEntity.getComponentType()) != null;
        switch (action) {
            case "buff" -> {
                Buffs.cancelFalling(t);
                Buffs.set(t, Buffs.Source.WAND, Math.min(Buffs.wandMax, Integer.parseInt(value)));
            }
            case "size", "health", "damage", "defense", "speed" -> {
                Buffs.Stat st = Buffs.Stat.of(action);
                Buffs.setDirect(t, st, Buffs.parseValue(st, value));
            }
            case "unbuff" -> {
                Buffs.cancelFalling(t);
                Buffs.clearNow(t, store);
            }
            case "heal" -> HealInteraction.healNow(t, store);
            case "cleanse" -> {
                cleanseFx.add(CleanseInteraction.start(t, true, store));
                cleansed.add(t);
            }
            case "effect", "look" -> {
                EffectControllerComponent effects = store.getComponent(t, EffectControllerComponent.getComponentType());
                if (effects == null) return "takes no effects";
                if (!EffectGiveInteraction.give(t, EffectWand.chain(value), effects, null, store)) return "immune";
            }
            case "morph" -> {
                MorphWand.Copy copy = null;
                PlayerRef other = onlinePlayer(value);
                Ref<EntityStore> otherRef = other != null ? other.getReference() : null;
                if (otherRef != null && otherRef.isValid() && otherRef.getStore() == store) copy = MorphWand.read(otherRef, store);
                if (copy == null) {
                    String id = MorphWand.findModel(value);
                    if (id != null) copy = new MorphWand.Copy(id, 1f);
                }
                if (copy == null) return "no such model here";
                if (!MorphWand.apply(t, copy, store)) return "can't wear it";
                WandFx.onTarget(t, WandFx.MORPH, store);
            }
            case "unmorph" -> {
                if (!MorphWand.restore(t, store)) return "not morphed";
                WandFx.onTarget(t, WandFx.UNMORPH, store);
            }
            case "army" -> {
                if (!npc) return "not a mob";
                boolean add = value.trim().toLowerCase(Locale.ROOT).startsWith("add");
                if (!add) return Army.remove(t, store) ? null : "in no army";
                java.util.UUID ownerId;
                Ref<EntityStore> ownerRef;
                boolean leader = false;
                if (armyOwner != null) {
                    // Another player's army, or a mob's: that mob leads it.
                    // It may have died or unloaded since the command thread found it.
                    if (!armyOwner.isValid()) return "the leader is gone";
                    if (armyOwner.getStore() != store) return "the leader is in another world";
                    if (armyOwner == t) return "can't lead itself";
                    PlayerRef op = store.getComponent(armyOwner, PlayerRef.getComponentType());
                    var uuid = store.getComponent(armyOwner, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                    if (op == null && (uuid == null || store.getComponent(armyOwner, NPCEntity.getComponentType()) == null)) return "the leader is not a player or a mob";
                    leader = op == null;
                    ownerId = op != null ? op.getUuid() : uuid.getUuid();
                    ownerRef = armyOwner;
                } else {
                    if (sender == null) return "you are in another world";
                    ownerId = sender.getUuid();
                    ownerRef = me;
                }
                if (Army.isMember(ownerId, t)) return "already in that army";
                if (!Army.add(ownerId, ownerRef, leader, t, store)) return "it leads that army (a loop)";
            }
            case "team" -> {
                if (sender == null) return "you are in another world";
                var draft = BattleManager.draft(sender);
                if (value.equalsIgnoreCase("none")) {
                    if (draft.teamOf(t) < 0) return "in no team";
                    draft.remove(t);
                    TeamFx.clear(t);
                } else {
                    // A player goes in as the Team Wand puts one: allowed to fight (playerFights teams, not in
                    // Creative) and marked as a fighter, else the battle start left them out (test 2026-10-14).
                    if (PlayerFighters.isPlayer(t, store)) {
                        if (!PlayerFighters.eligible(sender, t, BattleManager.Kind.TEAMS, store)) return "can't fight in a team now";
                        BattleManager.markPlayer(t, sender.getUuid());
                    }
                    BattleManager.assign(draft, t, BattleManager.teamIndex(value));
                }
            }
            default -> {
                return "unknown action";
            }
        }
        return null;
    }

    /** To the sender and the server log (an admin action; also readable when run through /sudo). */
    private static void reply(CommandContext ctx, Message chat, String log, String color) {
        ctx.sendMessage(chat.color(color));
        MobBattlePlugin.log("[apply] " + ctx.sender().getUsername() + ": " + log);
    }

    private static Message usageMsg() {
        return I18n.tr("cmd.usage").param("usage", USAGE_SYNTAX);
    }

    private static void usage(CommandContext ctx) {
        reply(ctx, usageMsg(), USAGE, ERR);
    }

    /** The chat text of a skip reason (apply() returns it in English, which the log keeps). */
    private static Message whyMsg(String why) {
        String key = switch (why) {
            case "takes no effects" -> "noeffects";
            case "immune" -> "immune";
            case "no such model here" -> "nomodel";
            case "can't wear it" -> "cantwear";
            case "not morphed" -> "notmorphed";
            case "not a mob" -> "notmob";
            case "you are in another world" -> "otherworld";
            case "already in that army" -> "inarmy";
            case "in no army" -> "notinarmy";
            case "in no team" -> "noteam";
            case "the leader is gone" -> "leadergone";
            case "the leader is in another world" -> "leaderworld";
            case "can't lead itself" -> "leaditself";
            case "the leader is not a player or a mob" -> "leaderkind";
            case "it leads that army (a loop)" -> "loop";
            case "can't fight in a team now" -> "noteamfight";
            default -> "unknown";
        };
        return I18n.tr("cmd.apply.why." + key);
    }
}
