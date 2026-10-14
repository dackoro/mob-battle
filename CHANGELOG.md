# Changelog

Every version of Dackoro's Mob Battle, newest first. Each one is tagged in git (`v1.0.0`, ...) and matches the file uploaded to CurseForge.

## 1.1.0 (2026-10-14)

### New: wands
- **Held-click waves.** Hold the click 1.5 s and let go: you slam the ground and a ring grows from you.
  - Morph Wand: hold left = every mob around takes your copied look; hold right = the mobs around swap looks in random pairs.
  - Cleanse Wand: hold left = cleanses every mob and player around you.
  - Effect Wand: hold left = gives the effect to every mob and player around you.
  - Heal Wand: hold left = heals every mob and player around you, you too.
  - Each one can be turned off (`morphBurst`, `morphShuffle`, `cleanseArea`, `effectArea`, `healArea`) and has its own reach (default 12 blocks).
  - Each one can include you or not (`healAreaSelf` default on; `cleanseAreaSelf`, `effectAreaSelf`, `morphBurstSelf` default off).
- **Waves that travel.** Wand waves hit each mob when the ring reaches it, not all at once (`waveTravel`, default on). `waveGroundOnly` spares fliers and mobs in caves, like the game's own slams. The Snap, battle royale and all vs one waves can do the same (`battleWaveTravel`, `battleWaveGroundOnly`, default off).
- **Army Wand: armies for other players and mob leaders.** Crouch + left click a mob and your next clicks recruit for it: it leads its own army, which follows it, guards it and attacks what it attacks (gold ring on the leader). Crouch + click a player to recruit for them (with server permissions on, that needs `dackoro.mob_battle.config`), the air for your own army again; right click disbands the army you recruit for. Leaders can serve in another army (a chain of command, loops are refused). `armyFriendlyFire` lets hits inside an army do damage without turning anyone against the others. By command: `/mobbattle apply <who> army add <player|id:<uuid>|last>`.
- **Morph Wand copies player looks**: right click a player (or a mob wearing a player look) and give that look to mobs. The game does not save a skin on a mob, so after a restart those mobs go back to their own model.
- New animations: the Effect Wand slams for its wave and spins its gem when you cycle effects (fast, nonstop, while you charge the held click); the Team Wand spins its color wheel on a right click.
- Effect Wand messages and its burst on the mob come in the effect's color.

### New: battles
- **Land, air and water.** Each mob fights in its own medium: fish stay in the water, walkers that would drown don't follow a target into deep water, and only mobs that fly or attack from afar go for fliers (archers and mages also hit fish at the surface). Which mobs attack from afar is learned the first time they hit with a projectile, mods' mobs too. Mobs prefer enemies in their own medium, then ones they can reach elsewhere. Teleports (stuck mobs, arena wall, Army Wand) put fish in water and walkers on dry land. When nobody can reach anybody, passiveWin ends the battle and the side with the most health left wins. A closing arena that leaves a fish's water outside takes it out after 10 s if no water is left inside.
- **Battle height band:** a royale or all vs one takes mobs only from 50 blocks below its center to 40 above (`battleBelow`, `battleAbove`, `off` = no limit), so a battle on the surface leaves a deep cave's mobs alone. `arenaVertical` (default off) makes the arena wall also bring back fighters that fall or climb that far from the arena's level.
- **Battles end when nobody fights**: `idleEnd` (default 2.5 min) ends a battle in a draw when no fighter hurts an enemy for that long (a royale of boars and bison used to stand still until its timer ran out).
- **Longest battle** is a setting: `battleMaxTime <minutes>|off` (default 15 min, `off` = a battle only ends with a winner, idleEnd, passiveWin or `/mobbattle stop`).
- **Kill table ties**: `killTieBreak damage|alive|first|shared` decides the order of fighters with the same kills (timer box, scoreboard, chat top 3, end camera, watch mode). `tieBreak` and `killTieBreak` can now be set per battle kind.
- **All vs one card** (`focusCard`, default off): the lone target's health, attack, defense, size and kills, the attackers left and their total health, and the top damage dealer. The duel and all vs one cards now take the scoreboard's place on the right.
- **Cameras for big mobs**: duel, showcase, team, focus and end shots, and the watch camera, step back and up for bosses and size-buffed mobs. The drone intro is a new low flight that weaves past the fighters, flies over hills and tree tops, ends in an orbit, and in an indoor arena stays under the roof and out of the stands.
- **Bigger limits.** Area radius, arena size, mob count (`maxMobs`), summon counts, camera times, timers and buffs go much higher now; past a sensible value the command and the settings panel warn what may lag or stop looking right instead of refusing.

### New: buffs
- **Real size buff for players**: their camera grows with them, and jumps and walking speed grow with the size (mobs too). New size limit for all buffs together (`buff sizeMax`, default x3). Buff runes, crowns and lights no longer fill the screen at big sizes.
- **Speed and jump from size** are settings: `buff sizeSpeed` / `buff sizeJump off|natural|strong|full` (natural = x4 runs twice as fast).
- **Speed buff** and higher caps: a new `speed` stat for every buff source (on top of the speed a size buff gives); health and damage go up to x100, defense up to 99 % (takes 1/100 of the damage).
- **Buff values as multipliers**: `buff unit multiplier` shows and takes values like x2 or x1.5 (defense x2 = takes half the damage), in commands and in the settings panel; chat messages show both ("x2 (+100%)").
- **Buffed mob look is a setting**: `buffLook shimmer|runes|off` (default shimmer) shows the full shimmer, only the runes and eye flames, or nothing while a buff lasts.

### New: summons
- **Random battles:** `/mobbattle summon fill <types> [each:<n>] [teams:<n>] [mod:<name>] [type:...] [group:...] [add]` fills the summon list with random mobs that fight, of similar strength, optionally dealt into balanced teams. Mods' mobs are included. Two buttons for it on the panel's Summon list tab.
- **Calm summons**: `summonCalm summons|all` keeps summoned mobs from fighting each other (or anyone) until a battle, a wand fight or your army takes them. A hit lets one fight back its attacker.
- **Summon list per team**: the same mob can be listed once per team (10 archers in red and 10 in green). Adding a mob already listed with that team adds to its count instead of replacing it; `summon modify <mob> <team> <count>` sets an exact count. Mob names are no longer case-sensitive (`zombie` = `Zombie`).

### New: settings panel and language
- **Spanish translation.** Chat, battle titles, HUDs, command replies, help and the settings panel follow your game language (English and Spanish for now).
- The mob, Morph model and effect lists have a **Show** filter: by mod, by group (Aquatic, Avian, Undead...) and, for mobs, by type (fighters, defend only, no attack, avoid). Mods' entries are marked ★ instead of a [mod] tag on every name, and the lists are built once instead of on every page. Which game mobs never attack or only defend comes from real tests in game.
- The "Add a mob" row remembers your last pick, and the search lists open wider so long names fit. The Buff Wand comes first on the Buffs tab; summon list rows and every team picker show the team's color; a long summon list scrolls in its own box; buttons that ask for a second click turn red and read "Confirm?".

### New: admin, servers and mod makers
- **Wand actions by command**: `/mobbattle apply <player|me|look|near <radius>|players> <action> [value]` buffs, resizes, heals, cleanses, gives effects or looks, morphs, or puts in an army or a team, without the wand. Also on the panel's Admin tab.
- `/mobbattle measure`: your speed and jump height for a few seconds, to tune size and speed buffs.
- **Test tools** (server console too): `/mobbattle test ...` spawns mobs by coordinates, moves them, logs every hit of a duel, starts royales, fires wand waves from a spot, runs a player's wand click (`test wand`), checks every mob's group calls (`test calls`), climbing (`test jump`), speed (`test speed`), what each mob can do (`test verify`, `test ai`), plays intro cameras, saves and restores settings, measures the tick time and runs command scripts with checks. See docs/server-setup.md.
- **Plugin API 2**: `declareMedium` and `declareRanged` let other mods say how their mobs move and whether they attack from afar.

### Fixes
- `battlePvp` works: two players in the same battle can hurt each other where the world's PvP is off (before, their hits were dropped before Mob Battle could let them through).
- Mobs of the same group no longer join someone else's fight when one of them calls for help (Trork and Outlander "enemy sighted", skeletons "under attack", goblins, Kweebecs...): fighters keep their battle targets and onlookers stay out. Mobs outside an isolated battle no longer chase its fighters and swing at them.
- Mobs spawned together hurt each other in battles: ranged mobs (e.g. Outlander archers in a royale) no longer stay in one shared pack, and pack animals (wolves, zombies, bears, hyenas...) no longer join one pack again every second.
- Big battles: mobs far from their target (more than ~30 blocks) keep walking to it instead of wandering around with a "?" over their heads, mobs no longer give up a fight to walk back to where they spawned, and in a royale a mob goes for an enemy right beside it instead of walking past it to a far one. The stand-off shove no longer launches mobs that are far away.
- A mob no longer swaps its target for an enemy it can't reach and back every second (it never got a hit in).
- The arena wall pushes gently at the edge and harder farther out, and lifts a mob stuck at a step or slope; a mob that walks in with a closing wall is no longer teleported back and forth. The wall never pushes a mob into rock, a fighter wedged in a block is moved out at once (they used to sink through cave floors), and mobs brought back land on open ground at the arena's level.
- A battle with only mobs that never attack (cows, deer...) now ends after passiveWin instead of waiting for its timer. Cows, bison, boars, goats, horses, moose, rams, camels, stags and their tamed versions are listed as "Defend only": they kick whoever hits them from behind, but never start a fight.
- Army mobs inside a battle (e.g. army summons in a royale) follow the battle's targets: hitting a mob yourself no longer pulls them all off their fights for a second, and they are no longer pulled out of the arena to you every second. Army Wand mobs chasing an enemy no longer turn back to you halfway, and they come back to you when you fly high in Creative.
- Army mobs stay friendly: hostile mobs in your army no longer turn on you after 10 minutes, or after you leave and come back. An army is no longer lost when its mobs unload with their chunk (you log off or walk away): they are back in it when they load again.
- In a battle royale an army is one side: its mobs fight together instead of each other.
- `/mobbattle apply <player> team <team>` puts a player into the team battle like the Team Wand does (they were left out when it started).
- The Kill and Snap wands skip players who can't die (Creative, invulnerable, spawn protection) instead of playing a death that never comes.
- Players lose their Morph Wand look when they die. The Kill Wand's message names the player it killed (it said "target").
- A damage buff no longer raises the damage a mob does to itself (its own burn or fall).
- A size-buffed player who leaves and comes back loses the buff's leftovers (size, extra health), as on death.
- Mob Battle effects on you no longer cover your own first-person view.
- Battle cards no longer show "Health 0" for a fighter that is still standing with less than 1 health.
- The settings panel no longer lists the game's internal test mobs (Static...) as fighters.
- Guards against errors in some NPC attitude lookups and in lights on removed mobs.

## 1.0.0 (2026-10-12)

First CurseForge release.
