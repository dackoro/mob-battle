package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AbilityCostType;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.item.config.CoreItemAbility;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.SupportItemAbility;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.projectile.component.ImpactModifiers;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.interactions.NPCInteractionSimulationHandler;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runes on any mob (combat profiles, 2026-10-29). A mob given runes (a kit's runes, Kits.apply) casts them in a fight
 * the way a player casts an ability from the rune bench: the rune item's Ability (CoreItemAbility: Cast root
 * interaction, Cost, CostType, Cooldown) is read from the item asset, the cost comes off its own stat (Mana for every
 * vanilla rune; NPCs have the Mana stat and regenerate it, 1 per 0.3 s) and the root interaction is started on the mob
 * through its InteractionManager, as the NPC combat evaluator starts a self-targeted ability (decompiled 0.7.4
 * AbilityCombatAction.execute / ActionAttack: InteractionContext.forInteraction + initChain + queueExecuteChain).
 *
 * <p>Started outside a rune bench (held item section -11), the cast chain's "UseAbilityCost" checks find no ability
 * (AbilityCastUtil.castAbility returns null): StatsCondition passes, ChangeStat spends nothing and TriggerCooldown
 * only arms the 0.35 s default. So cost and cooldown are kept here. Projectiles fly along the caster's look
 * (AbilityProjectileLauncher -> TargetUtil.getLook = eye + HeadRotation): the head is turned toward the target first.
 *
 * <p>When: the mob fights (battle member with a living target, or a wand fight), the target is within the rune's
 * range (projectiles 16 blocks, melee 6, buffs 8), its cooldown passed, it has the mana, and its interaction manager
 * can run an Ability1 chain now (not in the middle of one of its own attacks: InteractionManager.canRun). One cast per
 * mob per pass, at most every MIN_GAP_MS. Ticked every TICK_MS per world from BattleLifecycleSystem, on the world
 * thread (the chain start queue is a plain list); nothing runs while no mob has runes.
 *
 * <p>Supports (2026-10-29, playtest: the rune bench's lines): an entry "Rune_Fireball+Rune_Damage_Up+Rune_Fork" is a main
 * rune with up to two support runes; their modifiers go into the cast's ImpactModifiers snapshot and their Cost and
 * Cooldown factors into ours. Each rune's cooldown is stretched by {@code FxSettings.runeCooldown} (playtest: mobs cast as
 * soon as they can, too often at a player's pace).
 * ❓ Untested in game: animations on non-humanoid models (the cast anims are player Sword/Mace sets), the charged
 * shot's charge time, whether the head turn holds until the projectile leaves (0.87 s wind-up for the fireball).
 */
final class RuneCaster {
    static final long TICK_MS = 250;
    /** Least time between two casts of one mob, so runes don't chain into each other. */
    private static final long MIN_GAP_MS = 1200;
    /** A rune it couldn't pay for is looked at again after this long (also limits the debug line). */
    private static final long NO_MANA_RETRY_MS = 1500;
    static final double RANGE_PROJECTILE = 16, RANGE_MELEE = 6, RANGE_BUFF = 8;
    /** Charge held for runes that charge (ChargedShot: release 3 of 4 is at 2.1 s). */
    private static final float CHARGE_SECONDS = 2.2f;
    /** The interaction slot runes use: not the Primary/Secondary the role's own attacks use. */
    private static final InteractionType TYPE = InteractionType.Ability1;
    /** Runes of the game, used when the item assets can't be listed (command suggestions at boot). */
    static final List<String> KNOWN = List.of("Rune_ChargedShot", "Rune_Enrage", "Rune_Fireball",
            "Rune_GroundSlam", "Rune_PoisonImbue", "Rune_WindStrike");
    static final int MAX_RUNES = 4;

    /**
     * One rune read from its item (and its supports): what it starts and what it costs. {@code attributes},
     * {@code onHit} and {@code element} are what its supports add (null when none), as ImpactModifiers.collect builds
     * them for a player's rune line.
     */
    record Rune(String item, String root, float cost, int costStat, float cooldownS, double range, float charge,
                Map<String, Float> attributes, String[] onHit, String element) {
        /** A fresh ImpactModifiers for one cast (the cast's own copy), or null when it has no supports. */
        ImpactModifiers modifiers() {
            if (attributes == null && onHit == null && element == null) return null;
            return new ImpactModifiers(attributes != null ? new java.util.HashMap<>(attributes) : null,
                    onHit != null ? onHit.clone() : null, element);
        }
    }

    /** Joins a main rune and its supports in one entry: "Rune_Fireball+Rune_Damage_Up+Rune_Fork". */
    static final String SUPPORT_JOIN = "+";
    /** Support runes per main rune, as the rune bench's lines (a main rune + two supports). */
    static final int MAX_SUPPORTS = 2;

    private static final class Caster {
        final List<String> runes;
        final long[] readyAt;
        int next;
        long lastCast;

        Caster(List<String> runes) {
            this.runes = List.copyOf(runes);
            this.readyAt = new long[runes.size()];
        }
    }

    private static final Map<Ref<EntityStore>, Caster> CASTERS = new ConcurrentHashMap<>();
    private static volatile long nextPrune;

    private RuneCaster() {
    }

    static boolean any() {
        return !CASTERS.isEmpty();
    }

    /** The mob's runes are now these (empty = none). Any thread. */
    static void set(Ref<EntityStore> mob, List<String> runes) {
        if (mob == null) return;
        if (runes == null || runes.isEmpty()) {
            CASTERS.remove(mob);
            return;
        }
        List<String> ok = new ArrayList<>();
        List<String> cores = new ArrayList<>();
        for (String r : runes) {
            if (r == null || cores.contains(coreOf(r)) || ok.size() >= MAX_RUNES) continue;
            ok.add(r);
            cores.add(coreOf(r));
        }
        CASTERS.put(mob, new Caster(ok));
    }

    static void remove(Ref<EntityStore> mob) {
        if (mob != null) CASTERS.remove(mob);
    }

    static void clearAll() {
        CASTERS.clear();
    }

    /** Every mob with runes now (MobExtras saves them). */
    static java.util.Set<Ref<EntityStore>> mobs() {
        return CASTERS.keySet();
    }

    static List<String> runesOf(Ref<EntityStore> mob) {
        Caster c = mob != null ? CASTERS.get(mob) : null;
        return c != null ? c.runes : List.of();
    }

    /** RoleChange: the same mob under a new Ref keeps its runes. */
    static void carry(Ref<EntityStore> old, Ref<EntityStore> neu) {
        Caster c = CASTERS.remove(old);
        if (c != null) CASTERS.put(neu, c);
    }

    // ---- rune items ----

    private static volatile List<String> ids = List.of();
    private static volatile int idsSize = -1;

    /** Every item with a castable ability (the game's Rune_* and mods'), debug items left out. Cached per item count. */
    static List<String> runeIds() {
        try {
            var map = Item.getAssetMap();
            int size = map.getAssetMap().size();
            if (size == idsSize) return ids;
            List<String> out = new ArrayList<>();
            for (var e : map.getAssetMap().entrySet()) {
                Item item = e.getValue();
                if (item == null || e.getKey().startsWith("Debug_") || e.getKey().startsWith("*")) continue;
                if (item.getAbility() instanceof CoreItemAbility a && a.getCastRootId() != null && !beamCast(a)) out.add(e.getKey());
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            ids = List.copyOf(out);
            idsSize = size;
            return ids;
        } catch (RuntimeException e) {
            return KNOWN;
        }
    }

    /**
     * Runes whose projectile carries a beam back to the caster (the game's Chain Hook: its projectile config attaches a
     * BeamComponent on ProjectileSpawn). Cast by a mob, that beam was added twice and the game's AttachBeamInteraction
     * does not check: "Entity already contains component type ... BeamComponent" crashed the world one second after a
     * single cast (2026-10-29, a mace with only Chain Hook against a Test_Dummy). Mobs never get them; mods' runes that
     * only throw hooks the same way need adding here.
     */
    private static boolean beamCast(CoreItemAbility a) {
        String root = a.getCastRootId();
        return root != null && root.toLowerCase(Locale.ROOT).contains("chainhook");
    }

    /** The exact id of a rune item (any case), or null. */
    static String find(String word) {
        return findIn(runeIds(), word);
    }

    private static String findIn(List<String> list, String word) {
        if (word == null || word.isBlank()) return null;
        String w = word.trim();
        for (String id : list) if (id.equalsIgnoreCase(w)) return id;
        // "Fireball" for Rune_Fireball
        for (String id : list) if (id.equalsIgnoreCase("Rune_" + w)) return id;
        return null;
    }

    private static volatile List<String> supportIds = List.of();
    private static volatile int supportSize = -1;

    /** Every support rune item (the rune bench's modifier runes: Rune_Damage_Up, Rune_Fork...), debug items left out. */
    static List<String> supportIds() {
        try {
            var map = Item.getAssetMap();
            int size = map.getAssetMap().size();
            if (size == supportSize) return supportIds;
            List<String> out = new ArrayList<>();
            for (var e : map.getAssetMap().entrySet()) {
                Item item = e.getValue();
                if (item == null || e.getKey().startsWith("Debug_") || e.getKey().startsWith("*")) continue;
                if (item.getAbility() instanceof SupportItemAbility) out.add(e.getKey());
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            supportIds = List.copyOf(out);
            supportSize = size;
            return supportIds;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The exact id of a support rune (any case, "Damage_Up" for Rune_Damage_Up), or null. */
    static String findSupport(String word) {
        return findIn(supportIds(), word);
    }

    /** Whether the support rune can go with this main rune (its keyword rules, as the rune bench checks them). */
    static boolean supports(String supportId, String coreId) {
        Item s = supportId != null ? Item.getAssetMap().getAsset(supportId) : null;
        Item c = coreId != null ? Item.getAssetMap().getAsset(coreId) : null;
        return s != null && c != null && s.getAbility() instanceof SupportItemAbility sup
                && c.getAbility() instanceof CoreItemAbility core && sup.supports(core);
    }

    /** The main rune of an entry ("Rune_Fireball+Rune_Fork" -> "Rune_Fireball"). */
    static String coreOf(String entry) {
        if (entry == null) return null;
        int at = entry.indexOf(SUPPORT_JOIN);
        return at < 0 ? entry : entry.substring(0, at);
    }

    /** The supports of an entry, in order (none = empty). */
    static List<String> supportsOf(String entry) {
        if (entry == null || !entry.contains(SUPPORT_JOIN)) return List.of();
        List<String> out = new ArrayList<>();
        String[] parts = entry.split("\\" + SUPPORT_JOIN);
        for (int i = 1; i < parts.length; i++) if (!parts[i].isBlank()) out.add(parts[i].trim());
        return out;
    }

    /** One entry from a main rune and its supports. */
    static String entry(String core, List<String> supports) {
        StringBuilder b = new StringBuilder(core);
        for (String s : supports) if (s != null && !s.isBlank()) b.append(SUPPORT_JOIN).append(s);
        return b.toString();
    }

    /**
     * An entry with exact ids ("fireball+damage_up" -> "Rune_Fireball+Rune_Damage_Up"): null when the main rune is not
     * one ({@code bad[0]} says what), a support that is not one or can't go with it ({@code bad[0]}, {@code bad[1]} =
     * "support"/"fit"), at most MAX_SUPPORTS and no repeats.
     */
    static String normalize(String entry, String[] bad) {
        String core = find(coreOf(entry));
        if (core == null) {
            if (bad != null) bad[0] = coreOf(entry);
            return null;
        }
        List<String> sups = new ArrayList<>();
        for (String w : supportsOf(entry)) {
            String s = findSupport(w);
            if (s == null || !supports(s, core)) {
                if (bad != null) {
                    bad[0] = w;
                    if (bad.length > 1) bad[1] = s == null ? "support" : "fit";
                }
                return null;
            }
            if (!sups.contains(s) && sups.size() < MAX_SUPPORTS) sups.add(s);
        }
        return entry(core, sups);
    }

    /**
     * The rune's ability read from its item and its supports' (an entry: "Rune_Fireball" or
     * "Rune_Fireball+Rune_Damage_Up"), or null (not a rune, or its root interaction is missing).
     */
    static Rune info(String entry) {
        if (entry == null) return null;
        int assets = Item.getAssetMap().getAssetMap().size();
        if (assets != infoAssets) {
            INFO.clear();
            infoAssets = assets;
        }
        return INFO.computeIfAbsent(entry, id -> readInfo(id)).orElse(null);
    }

    /** info() by item id (audit 2026-10-29: two asset lookups and a keyword list per rune per pass), until items change. */
    private static final Map<String, java.util.Optional<Rune>> INFO = new ConcurrentHashMap<>();
    private static volatile int infoAssets = -1;

    private static java.util.Optional<Rune> readInfo(String itemId) {
        return java.util.Optional.ofNullable(readRune(itemId));
    }

    private static Rune readRune(String entry) {
        String itemId = coreOf(entry);
        Item item = itemId != null ? Item.getAssetMap().getAsset(itemId) : null;
        if (item == null || !(item.getAbility() instanceof CoreItemAbility a) || a.getCastRootId() == null || beamCast(a)) return null;
        if (RootInteraction.getAssetMap().getAsset(a.getCastRootId()) == null) return null;
        int stat = Integer.MIN_VALUE;
        float cost = a.getCost();
        if (cost > 0 && a.getCostType() != null && a.getCostType() != AbilityCostType.None) {
            stat = EntityStatType.getAssetMap().getIndex(a.getCostType().name());
        }
        if (stat == Integer.MIN_VALUE) cost = 0;
        List<String> words = new ArrayList<>();
        if (a.getAbilityKeywords() != null) for (String k : a.getAbilityKeywords()) words.add(k.toLowerCase(Locale.ROOT));
        double range = words.contains("projectile") ? RANGE_PROJECTILE : words.contains("melee") ? RANGE_MELEE : RANGE_BUFF;
        float charge = words.contains("charged") ? CHARGE_SECONDS : 0f;
        // The supports, as ImpactModifiers.collect does for a player's rune line (decompiled 0.7.4): the main rune's own
        // attributes, each support's modifiers on top (missing = 1), its on-hit effects and its element. Outside a rune
        // bench nobody collects them, so the cast gets them as its CAST_SNAPSHOT (tryCast); Cost and Cooldown are ours.
        Map<String, Float> attributes = null;
        List<String> onHit = null;
        String element = null;
        for (String s : supportsOf(entry)) {
            Item sup = Item.getAssetMap().getAsset(s);
            if (sup == null || !(sup.getAbility() instanceof SupportItemAbility support) || !support.supports(a)) continue;
            if (attributes == null) attributes = a.getAttributes() != null ? new java.util.HashMap<>(a.getAttributes()) : new java.util.HashMap<>();
            if (support.getAttributeModifiers() != null) {
                for (Map.Entry<String, StaticModifier[]> m : support.getAttributeModifiers().entrySet()) {
                    float v = attributes.getOrDefault(m.getKey(), 1.0f);
                    for (StaticModifier mod : m.getValue()) {
                        v = switch (mod.getCalculationType()) {
                            case ADDITIVE -> v + mod.getAmount();
                            case MULTIPLICATIVE -> v * mod.getAmount();
                        };
                    }
                    attributes.put(m.getKey(), v);
                }
            }
            if (support.getOnHitEffects() != null && support.getOnHitEffects().length > 0) {
                if (onHit == null) onHit = new ArrayList<>();
                java.util.Collections.addAll(onHit, support.getOnHitEffects());
            }
            if (support.getConvertElement() != null) element = support.getConvertElement();
        }
        float cooldown = Math.max(0f, a.getCooldownS());
        if (attributes != null) {
            cost *= attributes.getOrDefault("Cost", 1.0f);
            cooldown *= attributes.getOrDefault("Cooldown", 1.0f);
            if (attributes.isEmpty()) attributes = null;
        }
        return new Rune(entry, a.getCastRootId(), cost, stat, cooldown, range, charge,
                attributes, onHit != null ? onHit.toArray(String[]::new) : null, element);
    }

    /** "Rune_Fireball+Rune_Fork: 37.5 Mana, 2.4 s, 16 blocks" (ids and numbers, for chat and the help). */
    static String describe(String entry) {
        Rune r = info(entry);
        if (r == null) return entry + ": ?";
        String costName = r.costStat() != Integer.MIN_VALUE ? EntityStatType.getAssetMap().getAsset(r.costStat()).getId() : "";
        return entry + ": " + (r.cost() > 0 ? fmt(r.cost()) + " " + costName + ", " : "") + fmt(r.cooldownS()) + " s, "
                + (int) r.range() + " blocks";
    }

    /** "Rune_Fork: fits Projectile" (a support rune and the main runes it goes with, for chat). */
    static String describeSupport(String supportId) {
        List<String> fits = new ArrayList<>();
        for (String core : runeIds()) if (supports(supportId, core)) fits.add(core);
        return supportId + " -> " + (fits.isEmpty() ? "-" : String.join(", ", fits));
    }

    private static String fmt(float f) {
        float r = Math.round(f * 10f) / 10f;
        return r == Math.rint(r) ? String.valueOf((int) r) : String.valueOf(r);
    }

    // ---- casting ----

    /** Seconds the mob's own combat evaluator waits after a rune cast (plus the rune's charge). */
    private static final double CAST_PAUSE_S = 1.0;

    /** One pass over this world's mobs with runes. World thread (BattleLifecycleSystem, every TICK_MS). */
    static void tick(Store<EntityStore> store) {
        if (CASTERS.isEmpty() || store.isShutdown()) return;
        long now = System.currentTimeMillis();
        if (now >= nextPrune) {
            nextPrune = now + 1000;
            CASTERS.keySet().removeIf(r -> !r.isValid());
        }
        for (Map.Entry<Ref<EntityStore>, Caster> e : CASTERS.entrySet()) {
            Ref<EntityStore> mob = e.getKey();
            if (!mob.isValid() || mob.getStore() != store) continue;
            try {
                tryCast(mob, e.getValue(), now, store);
            } catch (RuntimeException ex) {
                // One mob's broken rune (a mod's) never stops the others.
                if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("rune: " + ChaosWand.nameOf(mob, store) + " failed: " + ex);
            }
        }
    }

    private static void tryCast(Ref<EntityStore> mob, Caster c, long now, Store<EntityStore> store) {
        if (now - c.lastCast < MIN_GAP_MS) return;
        if (store.getComponent(mob, NPCEntity.getComponentType()) == null) return;
        if (store.getComponent(mob, DeathComponent.getComponentType()) != null) return;
        Ref<EntityStore> target = targetOf(mob, store);
        if (target == null) return;
        Vector3d from = ChaosWand.positionOf(mob, store), to = ChaosWand.positionOf(target, store);
        if (from == null || to == null) return;
        double dist = from.distance(to);
        InteractionManager im = store.getComponent(mob, InteractionModule.get().getInteractionManagerComponent());
        EntityStatMap stats = store.getComponent(mob, EntityStatMap.getComponentType());
        if (im == null || stats == null) return;
        // In the middle of its own attack, guard or charge: never cut it (audit 2026-10-29: the cast reset the charge
        // value a guard or a charged swing was holding); try again next pass.
        var combat = store.getComponent(mob, com.hypixel.hytale.server.npc.role.support.CombatSupport.getComponentType());
        if (combat != null && combat.isExecutingAttack()) return;
        // A beam still on it (Chain Hook, Endgame's Singularity...): the game's AttachBeamInteraction ADDS a
        // BeamComponent without checking, and a second one crashed the whole world (2026-10-29, server log:
        // "Entity already contains component type ... BeamComponent" right after a Chain Hook cast). Wait it out.
        if (store.getComponent(mob, com.hypixel.hytale.builtin.beam.BeamComponent.getComponentType()) != null) return;
        int n = c.runes.size();
        for (int k = 0; k < n; k++) {
            int i = (c.next + k) % n;
            if (now < c.readyAt[i]) continue;
            Rune r = info(c.runes.get(i));
            if (r == null || dist > r.range()) continue;
            if (r.cost() > 0) {
                EntityStatValue pay = stats.get(r.costStat());
                if (pay == null || pay.get() < r.cost()) {
                    c.readyAt[i] = now + NO_MANA_RETRY_MS;
                    if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("rune: " + ChaosWand.nameOf(mob, store) + " skips "
                            + r.item() + " (mana " + (pay == null ? "none" : fmt(pay.get())) + " < " + fmt(r.cost()) + ")");
                    continue;
                }
            }
            RootInteraction root = RootInteraction.getAssetMap().getAsset(r.root());
            if (root == null) continue;
            // In the middle of one of its own attacks: try again next pass.
            if (!im.canRun(TYPE, root)) return;
            aim(mob, from, target, to, store);
            if (r.charge() > 0 && im.getInteractionSimulationHandler() instanceof NPCInteractionSimulationHandler sim) {
                sim.requestChargeTime(r.charge());
            }
            InteractionContext ctx = InteractionContext.forInteraction(im, mob, TYPE, store);
            // Its supports: damage, size, splits, element and on-hit effects reach the cast as a player's rune line
            // would give them (ImpactModifiers.resolve reads this snapshot first).
            ImpactModifiers mods = r.modifiers();
            if (mods != null) ctx.getMetaStore().putMetaObject(ImpactModifiers.CAST_SNAPSHOT, mods);
            InteractionChain chain = im.initChain(TYPE, ctx, root, false);
            im.queueExecuteChain(chain);
            // the combat evaluator waits for it, as for the game's own ability actions (AbilityCombatAction)
            if (combat != null) combat.setExecutingAttack(chain, false, CAST_PAUSE_S + r.charge());
            if (r.cost() > 0) stats.subtractStatValue(r.costStat(), r.cost());
            // runeCooldown: how much longer than a player a mob waits between casts of one rune (default x2)
            c.readyAt[i] = now + (long) (r.cooldownS() * 10 * FxSettings.runeCooldown) + (long) (r.charge() * 1000);
            c.lastCast = now;
            c.next = (i + 1) % n;
            if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("rune: " + ChaosWand.nameOf(mob, store) + " casts " + r.item()
                    + " at " + ChaosWand.nameOf(target, store) + " (" + String.format(Locale.ROOT, "%.1f", dist) + " blocks, -"
                    + fmt(r.cost()) + " mana)");
            return;
        }
    }

    /** Its living enemy: the battle's target for it, else its wand fight's opponent. */
    private static Ref<EntityStore> targetOf(Ref<EntityStore> mob, Store<EntityStore> store) {
        BattleManager.Battle battle = BattleManager.battleOf(mob);
        Ref<EntityStore> target = null;
        if (battle != null) {
            Ref<EntityStore> t = battle.targets.get(mob);
            if (t != null && battle.has(t) && battle.team(t) != battle.team(mob)) target = t;
        } else {
            target = FightManager.enemyOf(mob);
        }
        if (target == null || !target.isValid() || target.getStore() != store) return null;
        return BattleManager.isAlive(target, store) ? target : null;
    }

    /** Turns its head toward the target's chest, so a projectile leaves toward it (the launch follows the look). */
    private static void aim(Ref<EntityStore> mob, Vector3d from, Ref<EntityStore> target, Vector3d to, Store<EntityStore> store) {
        HeadRotation head = store.getComponent(mob, HeadRotation.getComponentType());
        if (head == null) return;
        double eye = eyeHeight(mob, store, 1.6), chest = eyeHeight(target, store, 1.6) * 0.75;
        double dx = to.x - from.x, dy = to.y + chest - (from.y + eye), dz = to.z - from.z;
        if (dx * dx + dz * dz < 1e-6) return;
        head.setRotation(new Rotation3f(PhysicsMath.pitchFromDirection(dx, dy, dz), PhysicsMath.headingFromDirection(dx, dz), 0f));
    }

    private static double eyeHeight(Ref<EntityStore> r, Store<EntityStore> store, double fallback) {
        ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
        if (mc == null || mc.getModel() == null) return fallback;
        float h = mc.getModel().getEyeHeight(r, store);
        return h > 0 && Float.isFinite(h) ? h : fallback;
    }
}
