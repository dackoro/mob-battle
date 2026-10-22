# Changelog

Every version of Dackoro's Mob Battle, newest first. Each one is tagged in git (`v1.0.0`, ...) and matches the file uploaded to CurseForge.

## Unreleased

### New: cameras
- **VS screen before battles** (`vsScreen on|off`, default on, per kind): a Smash-like screen with both sides' fighters and a big VS before the countdown. It adds 3 s to the countdown, and the intro camera keeps its full time after it. Up to three fighters a side show big, bigger sides as a crowd, a battle royale as one crowd across the screen, and 3 or more teams as one column per team (`vsTeamReps 1|2`). The pictures are drawn by the server from each mob's model, mods' mobs too; players show in their own skin.
- **Film look for the intro and end cameras.** Black film bands slide in at the top and the bottom of the screen while a camera films (`cameraBars off|slim|classic|wide`, default classic), and each shot stays sharp on what it films with the background blurred (`cameraBlur off|soft|strong`, default soft). Both can be set per battle kind and in the settings panel; the `cinematic` preset uses wide bands.
- **Battle film.** `/mobbattle film` films a running battle with slow, smooth movie shots until it ends: mostly wide pans and arcs framed to fit the fight's area, low tracking shots, push-ins on the action, close-ups on the fighter worth watching (more of them as fewer are left), a landscape pan that ends on the battle, and shots of you watching (over your shoulder, or close on you with the battle blurred behind). The camera stays out of hills, trees and roofs. Everything is a setting: how many shots show you, shot length and its random variation, camera pace, cut or glide between shots, and which shots it uses. `autoFilm` gives it to the camera viewers from the battle start.
- **Western duel intro.** The duel camera now opens on a low wide shot panning past both fighters, goes over each one's shoulder at the other, zooms in slowly on each one's eyes and ends with both from the side. Short intros keep fewer shots.

- **Cameras no longer come back to you through a shot of yourself.** An intro now ends on a wide shot of the fighters running at each other, then gives you your view back; with Auto film on it hands over straight to the battle film.
- **The game's HUD hides while a camera films** (hotbar, compass, health, boss bar; chat and titles stay): `cameraHud hide|show`, default hide.
- **Escape ends the battle film** in single player (the pause menu).
- **The battle film frames big mobs.** Its general shots back off and aim higher for the tallest and widest fighters, so they no longer end up inside a boss; close-ups still get close to small mobs.
- **Far wide shots in the battle film** (`filmWideShots`, default 30%): some shots are filmed wide and from far away, so big attacks show whole; more of them when a big mob fights.
- **Film styles**: `/mobbattle filmStyle default|battle|action|cinematic` (four buttons in the panel) sets every battle film setting at once: the fight always in view, close on the fighters and their attacks, or open, slow, gliding shots.
- **Time to get in position** (`readyTime <seconds>|off`, default off, per battle kind; 8 s in the `cinematic` preset): seconds to walk or fly to your spot (an arena's stands) before the VS screen and the countdown start. A big number counts down for you and the fighters wait; crouch to start at once.
- **Watch glides between fighters.** Going to another fighter, the spectator camera now travels there, straight through hills and walls, and ends behind it (`watchTransition glide|cut`, default glide).
- **Watch auto switch** (`watchAutoSwitch <seconds>|off`, default off): the watch goes on to the next fighter by itself every few seconds.
- **Battle royale VS**: the fighters of a plain battle royale are no longer cut at the middle.
- **VS screen sizes**: every fighter on one scale for the whole screen, measured on its own picture (a fire golem is no longer smaller than a hawk, small mobs are not blown up, giants tower over the rest with their head in view); each half uses the room it really has by the slanted split, so the left side's inner fighter is no longer cut; pictures show the whole width of wide mobs.
- **Kill Wand: hold left click** to slam the ground and kill every mob around you (`killArea`, `killRadius` 12); your own army is spared, players only with `killAreaPlayers on` (default off).
- **The Snap spares your own army.**
- **Server features** (admin, Admin tab): `serverVs`, `serverHolograms`, `serverIcons on|off` turn the VS screen, the Morph Wand hologram or the panel's mob pictures off for the whole server; their pictures stop being sent to joining players from the next restart.

### New: settings panel
- **`/mobbattle pictures status | rebuild [force]`** (admin, console too, and two buttons on the Admin tab): what the picture cache holds and what each joining player downloads, or draw the pictures again with progress in chat and a summary of times and sizes.
- **Search box.** Click the magnifying glass in the title bar of the panel (`/mobbattle config`), type a word, and every matching setting from every tab shows up, ready to change, in English or Spanish.

### New: summon list
- **Team Wand: clear every team** with crouch + right click in the air, three times: a yellow warning, then a red one, then the teams are cleared.
- **Mob picture gallery.** **Show pictures** on the panel's Summon list tab opens a picture of every mob, with its in-game name, a search box and the Show filters; click one to pick it.
- **Saved summon lists.** `/mobbattle summon save|load|delete <name>` and `summon lists`, or the Saved lists section of the Summon list tab, keep several lists with their counts, teams and team colors. Loading one from the panel can be undone.
- **One Fill row** replaces the two random battle buttons on the Summon list tab: how many mob types, how many of each and how many teams (0 = none). It follows the tab's Show filters.
- **Clear teams.** `/mobbattle summon team all none`, or **Clear teams** under the list on the Summon list tab, takes every mob in the list out of its team and keeps the mobs.

### New: wands, names and pictures
- **Morph Wand hologram.** While you hold the Morph Wand, a green hologram of the mob it copied floats over its gem (not for a copied player look). On for everyone.
- **Mobs go by their in-game names.** Battle titles, chat, the HUD cards, the VS screen and the settings panel show each mob's name from the game, in your language, instead of its ID (`Dungeon_Goblin_Burner` shows as "Goblin Burner"). Commands still take the ID.
- **One picture cache per machine.** Mob pictures (VS screen, panel icons, Morph Wand hologram) are drawn once and shared by every world and server on the computer, so a new world no longer draws them all again. Pictures of mobs no world has used for 30 days are deleted. See docs/server-setup.md.

### New: spotlight intro camera
- **`cameraStyle spotlight`**, now the default for battle royales and team battles. With few fighters and enough countdown, each one gets a close-up, building up to the strongest. With many, only the strongest get one (each team's best first), then a wide shot of everyone else. Want the old orbit back? `/mobbattle cameraStyle royale orbit`.
- **Cards stay clear of the film bands.** While the black bands show, the timer and score cards move just under the top band and the "can't fight back" alert just over the bottom one. Without bands they stay where they always were.
- **Faces in the close-ups.** Every close-up (spotlight, duel eyes, the film) stands in front of the face, wherever the mob looks, and turns round when a wall, the ground or another mob would block the view.
- **Every film shot has its own percent** (`/mobbattle filmShot <shot> <0-300>`, one field per shot in the panel): 0 = never, 200 = twice as often.
- **Six new film shots**: zenith (straight down), spiral, telephoto, crane down, from the ground and profile, with defaults that keep the showy ones a surprise. "Shots of you" now defaults to 20 %.
- The landscape shot (from the land to the battle) is rarer and turns to the fight faster.

### Changes
- **Watch mode:** a left click on the first fighter goes round to the last one instead of saying there is no earlier fighter.
- **The chat hides while the VS screen shows** and comes back when it ends.
- **Every `summon add` makes its own entry**, even for a mob and team already listed (`Skeleton_Archer#2`); it no longer adds to that entry's count. Change a count with `summon modify` or in the panel row.

### Fixes
- VS screen: every fighter is drawn at one scale, so a mob with a banner, wings or a long body no longer looks smaller than a mob of the same height; wide mobs are only cut and faded on the side that does not fit. A "Preparing the battle..." notice shows while the screen loads.
- VS screen: the two sides slide in and out smoothly, also with a big battle royale crowd (they used to pop in at once with the clash already on, after a freeze). The game's HUD stays until the screen appears.
- The battle film's far shots no longer leave the fight as specks in the landscape.
- VS screen: each side is cut at the slanted split instead of spilling into the other half, small mobs (a chicken, a fish) show clearly smaller next to big ones, and a big crowd fills its half as a jumbled group instead of a triangle.
- VS screen: the smaller fighters stand in front of the bigger ones (a tall mob in the middle of a row used to cover its smaller neighbours).
- Mobs whose model has a bone inside another of the same name (BetterMobExpansion's leopard) showed a second head in their VS picture.
- Summon list rows with no team keep their team picker and Remove button in line with the others.
- The grizzly bear, the marsh snake, the black wolf and some mods' mobs (MajorDungeons' cult werewolf and cult knight) showed another mob's icon in the settings panel and had no team column picture on the VS screen.
- **Mobs wait for the start.** Fighters in a countdown no longer attack each other (or get hit by a player in it) before the fight starts.
- **Bosses that spare their own kind can hit them in a battle.** A Dungeon Feastmaster Boss never hurt goblins, the Gobliterator included, because its role turns off damage to its own group; in battles and wand fights it now can.
- **One card on the right per player.** With several battles running, the scoreboard, duel card and all vs one card of the nearest battle show, never one over another.
- **The battle film repeats itself less.** Shots of the same kind no longer come one after another, and each shot shows about as often as its percent says.
- **A countdown called off during the VS screen ends its camera too**: the VS screen goes and the intro camera after it no longer plays (a duellist killed early, a new order, `/mobbattle stop`).
- **Battles with no VS screen keep their countdown.** The 3 extra seconds are only added when someone is shown the VS screen (the Battle Wand's "mob vs you" duel used to wait 3 silent seconds).
- **The game's HUD comes back** when you change worlds, or the world closes, while a camera, the battle film or the VS screen covers it.
- **Morphed mobs keep their size right.** A size-buffed mob that changed model (Morph Wand, Cleanse) now keeps its growth over the new model's own size; a cleansed goblin no longer stayed as big as the boss it had been morphed into.
- **Battle royales with armies** show each army as a side on the VS screen.
- **Smaller download when joining.** Team columns on the VS screen now use one picture per mob instead of six pre-cut widths: about 27 MB less for every player who joins.
- **A big "?" instead of a broken icon.** A mob with no picture yet shows a "?" in the VS style (on the VS screen and in the settings panel), and a player shows a black silhouette, until the next server start draws theirs.
- **Mods' mob names in titles and chat.** Names from other mods' language files (MajorDungeons' Baron) showed as a raw text key in the countdown and the FIGHT title.
- **Battle titles wait for the VS screen** instead of showing over it, and a plain battle royale's VS has one "BATTLE ROYALE" heading instead of two lists of names.
- **Battle music is back.** The game's goblin boss battle track plays quietly and only once on 0.7.2; Mob Battle now ships its own version of that song, louder and looping until the battle ends, and uses it by default.
- **MajorDungeons bosses no longer reset mid-battle.** Its arenas removed and respawned a boss after 10 s with no player near, which ended Mob Battle fights at once (Baron vs Baron). Bosses fighting, or waiting in a countdown, now keep their place.
- **Big mobs no longer shrink with a size buff or the Cleanse Wand.** Mobs whose model is made bigger than normal (for example some modded bosses) went back to normal size; now a size buff grows them from their own size and Cleanse leaves them at it.
- **Settings panel filters only offer what exists.** In the mob list, the "type" filter only lists the types the chosen mod has, and the Add button is off while the filters leave no mob.

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
- The mob, Morph model and effect lists have a **Show** filter: by mod, by group (Aquatic, Avian, Undead...) and, for mobs, by type (fighters, defend only, no attack, avoid). Mods' entries are marked * instead of a [mod] tag on every name, and the lists are built once instead of on every page. Which game mobs never attack or only defend comes from real tests in game.
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
- Army mobs stay friendly: hostile mobs in your army no longer turn on you after 10 minutes, or after you leave and come back. Armies are no longer lost when their mobs unload with their chunk (you log off or walk away) or when the server restarts: each mob is back in its army when it loads again.
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
