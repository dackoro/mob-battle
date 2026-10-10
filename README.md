# Dackoro's Mob Battle

A Hytale mod by **Dackoro**: wands that make mobs fight using their existing AI. Supports vanilla NPCs and modded NPCs with compatible combat behavior; player-only attacks and encounter-specific mechanics may need adaptations.

Patchline: **pre-release** (`ServerVersion >=0.7.0-pre <0.8.0`).

## Items

Each wand's tooltip explains what it does (English and Spanish). Get all eleven at once with `/mobbattle give`, or individually from the creative inventory or with `/give <item ID>`. The command gives one of each using Hytale's normal inventory placement and requires the same permission as `/give` for yourself. If some do not fit, it reports which ones were not delivered.

### Fights
| Item ID | Name | Left click | Right click |
|---|---|---|---|
| `MobBattle_Wand` | Battle Wand | Click mob A, then mob B: they fight each other (click the selected mob again to deselect it). With `/mobbattle playerFights duel on`, players can be picked like mobs, and crouching + clicking a mob makes it fight you (or select one, then crouch and click the air). | Clear the selection. |
| `MobBattle_ChaosWand` | Chaos Wand | Every mob within the radius attacks the clicked mob (or the clicked player, with `playerFights allVsOne on`). | Battle royale: every mob within the radius fights (and the players there, with `playerFights royale on`); each one starts on one of its 3 closest opponents, picked at random; when a mob kills its target it goes for the nearest survivor, until one is left (centered on the mob you aim at, or on you). |
| `MobBattle_TeamWand` | Team Wand | Add the mob (or a player, with `playerFights teams on`) to the current team (click again to remove it). | In the air: switch team. On a mob: start the battle. Last team standing wins. |

### Mobs
| Item ID | Name | Left click | Right click |
|---|---|---|---|
| `MobBattle_SummonWand` | Summon Wand | Add the mob's type to the summon list (click again to remove it). | Summon the whole list around you. |
| `MobBattle_MorphWand` | Morph Wand | Give the mob the copied model. It keeps its own AI, weapon, attacks and health. Players too, with `morphPlayers`. | On a mob or player: copy its model. In the air: show the copied model. |

### Health and effects
| Item ID | Name | Left click | Right click |
|---|---|---|---|
| `MobBattle_HealWand` | Heal Wand | Restore the entity to 100% health. | — |
| `MobBattle_KillWand` | Kill Wand | Kill the entity (any amount of health). Mobs disintegrate first for `killDelay` and drop their loot (`killLoot`); players and other entities die at once. | — |
| `MobBattle_SnapWand` | Snap Wand | Left click: half of the fighters (`snapPercent`), picked at random, turn to dust (the Kill Wand's soul rip, `killDelay`, `killLoot`), one after another over up to 5 s (`snapTime`). Click a mob in a battle: that battle. Click the air (or a mob in no battle): every mob within `snapRadius` (default 60) of the clicked mob or of you, fighting or not (and players, with `snapPlayers`), horizontally and at any height (fliers and mobs in caves below too), up to `maxMobs`; a white shockwave on the ground shows how far it reached (it leaves you slowly and speeds up toward the edge, and lasts longer the bigger the radius: 0.7 s up to 30 blocks, 2.2 s at 130 or more, so a large reach stays visible). `/mobbattle snapMode global` (default): half of all of them; `team`: half of each side. An odd count rounds up or down at random. Same as `/mobbattle snap`. The chat messages say how far it reached ("Snap! 45 of 45 within 60 blocks will turn to dust...", or "in that battle" when you click a mob in one). Sounds (random pitch, both skipped with `wandFx off`): `SFX_MobBattle_Snap` plays from you when the snap is activated, and `SFX_MobBattle_SnapFade` plays at each mob or player as it fades to dust. Uses the Kill Wand model for now. | — |
| `MobBattle_EffectWand` | Effect Wand | Give the entity the selected effect (default `Burn`). | Next effect: Burn, Poison_T2, Freeze, Slow, Stun, Root, Vulnerable, Ability_Enrage_Buff, Potion_Health_Regen, Immune. |
| `MobBattle_CleanseWand` | Cleanse Wand | The light comes down from above (`cleanseColor`); when it lands (about 0.4 s) every effect and buff goes (size and max health back) and a morph is undone. | — |
| `MobBattle_BuffWand` | Buff Wand | Left click a mob or player: one more buff stack, with the `/mobbattle buff wand` percents (default per click: size 10, health 20, damage 10, defense 5), up to `buff wand maxStacks` (default 5); a click at the max shows the error flash and says so in chat. Right click: takes the wand's buff off (other buffs stay), with a grey tint and a grey swirl. Look: `buffColor` tint, swirl, burst, aura and rune (default yellow = gold); the tint and the aura stay while the buff lasts. It lasts until it dies or gets the Cleanse Wand, inside and outside battles, and its damage and defense count against anyone. Buffed entities wear the `buff crown`. Uses the Heal Wand model for now. | — |
| `MobBattle_ArmyWand` | Army Wand | Add the mob to your army (click it again to remove it). Your army follows you, defends you and attacks what you hit. | Disband your army. |

### Notes
- A passive mob (no attack) only runs away. The aggressive one still attacks.
- Boss roles with a `Main` combat substate (such as `Bear_Voidtaken`) enter that substate when ordered to fight. Abilities tied to players or a specific Hunt site may still require their original encounter.
- Mobs of the same species and the same pack also hurt each other.
- A new target replaces the old one: the mob ignores its previous target for 30 s.
- Fights last up to 15 minutes, or until one of the two dies.
- The opening "provocation" hit is 0.01 damage and is healed back 0.3 s later.
- The Chaos Wand affects at most the **200 nearest** mobs, and one summon attempts to spawn at most that many too (`/mobbattle maxMobs`). Failed spawns also count toward the limit. When the area has more mobs than the limit, the chat says how many were left out.
- Summoned mobs appear on the floor close around you (the ring grows with how many); the list is saved with the settings.
- While you hold the Team Wand, each team's mobs show a solid ring in the team color on the ground at their feet (only you see it). It is redrawn every 0.25 s, so it trails a moving mob by up to a quarter second.
- Duels (Battle Wand), everyone vs one (Chaos Wand left click) and group battles (royale and teams) announce the winner with a title. They stop after 15 minutes if nobody wins.
- `/mobbattle debug`: read-only combat snapshots of the four nearest NPCs within 20 blocks, now and after 2/4 seconds. Reports role/entity index, AI state, alive/frozen/attacking, battle identity, ordered vs locked target, distance, attitude and the vanilla damage permission. Logs at most 100 battle hit events involving those NPCs for 10 seconds, including whether vanilla had cancelled the hit before Mob Battle restores it; this is not confirmation of final damage after later systems. The snapshots appear in chat and the world's server log. A false `vanillaCanDamage` alone does not prove the final hit is blocked, because Mob Battle overrides that filter for opponents.
- `/mobbattle duelMode exclusive` (default): starting B vs C ends A vs B and stops A. `accumulate`: connected Battle Wand duels merge; A keeps targeting B while B targets C and C targets B. Existing targets of the other participants remain. If a target dies, the survivor picks another enemy in the merged group, until one side wins. There is one final winner for the group, announced to the owner of the newest duel, and its 15-minute timer restarts when another duel is added. Only connected duels merge; unrelated duels, team and Chaos battles stay separate. Changing the mode affects new orders, not fights already running. The saved property is `duel.mode=exclusive` or `duel.mode=accumulate` in `fx.properties`.
- **The newest combat order wins:** a fight order or team assignment on mobs that are in a battle royale or team battle pulls only those mobs out of it; the rest keep fighting. If fewer than 2 sides are left, the battle ends without a winner title. Healing, effects and morphs do not cancel a fight. Mobs don't rejoin a battle on their own.
- A trork's pet (same flock) that spawns during a group battle joins its owner's side. If you put an owner and its pet on different sides (teams or battle royale), they leave their flock when the battle starts so they really fight; this is permanent, as in a duel.
- **Morphs** work like the vanilla frog potion, but have no time limit. A morph is saved with the world, while the original model is only remembered while the server runs: use `/mobbattle morph reset` or the Cleanse Wand before leaving the world, or the mob keeps the new look. Animations of the new model may not match the old role's attacks.

### Players in battles
With `/mobbattle playerFights <kind> on`, players are fighters too: nobody controls them, the mobs of the other sides attack them and their own side's mobs ignore them.
- **Ways in:** the wands work on players like on mobs (Battle Wand duel, Chaos Wand left click = everyone vs that player, Team Wand = joins the current team); Chaos Wand right click takes in the players in the area (`royale`); crouching + Battle Wand on a mob = that mob vs you; `/mobbattle chaos me`; `/mobbattle teams join <team>` / `leave`.
- **Creative players never count:** they can't be put in a battle (the chat says why), and a fighter who switches to Creative is out of it at once (their side may lose); during a countdown, they are left out when the fight starts. Use Adventure to fight.
- **Your side's mobs** never pick you as a target and can't hurt you (unless `friendlyFire` is on for that kind); your hits on them are cancelled the same way. With `battleIsolation` on, mobs from outside the battle can't hurt you.
- **PvP:** enemy players of the same battle follow the world's PvP rule; `/mobbattle battlePvp on` lets their hits through even where PvP is off (spawn protection and invulnerability still apply).
- **Titles, timer and music** reach the players fighting in a battle, whatever `titleScope` says.
- **Dying** takes you out of the battle for good (also during its countdown): you respawn outside it, and if your side has nobody left it loses. `teams leave` during a battle works the same way. If you win, the title shows your name.
- **Disintegration:** the Snap Wand turns players to ash like mobs, and `timeUp kill` / `judge` disintegrate the players of the losing sides; then they die (an instant kill, whatever their health). The Kill Wand does the same with `killPlayers on` (default off: it does not affect players).

### Effects
- **Fight start (Battle Wand, Chaos Wand, Team Wand):** a big title (`FIGHT!`, `EVERYONE vs X`, `BATTLE ROYALE!`, `TEAM BATTLE!`) and, with `/mobbattle fightLaser on`, a laser linking each mob to its first opponent (team color in team battles).
- **Titles:** each lasts about 3 s. Repeated fight-start titles keep only the newest pending one. Every victory is queued in order and takes priority over pending starts; a new fight cannot overwrite a victory.
- **Chaos Wand area:** for 6 s, as `/mobbattle areaStyle` says: only the border glow (`border`, default: columns of light about 20 blocks tall along the edge), a translucent disc on the ground like a boss attack zone (`disc`), both (`discBorder`), the disc with a see-through wall (`wall`, `/mobbattle areaHeight`) or nothing (`off`). The disc and wall use the area color (`/mobbattle areaColor`); the border uses each action's `/mobbattle borderColor`.
- **Team look:** tint and/or a mist in the team color (`/mobbattle teamLook`).
- **Clicking the same mob again** replays its wand effect (it is restarted, not just extended).
- **Army Wand orders**, most urgent first: (1) an army mob that gets hit fights back, and the nearest free mate helps (one helper: further hits from the same attacker don't pull in more); (2) whoever hits you is attacked by every free army mob; (3) whoever you hit is attacked by every free army mob; if nobody is free in (2) or (3) and no army mob is on that enemy yet, the nearest half switches and the rest finish their fight (hitting the same enemy again does not pull more); (4) a mob whose target died joins the nearest enemy the army still fights, or follows you again when there is none. Mobs that were just following you stay with you. "Free" = not fighting, or its target is dead. Army mobs never ignore an old target that keeps hitting them. While you hold the Army Wand, each of your army mobs shows a green ring at its feet (only you see it), like the Team Wand rings. **PvP:** rules 1-3 also count players: a player who hits you or your army mobs is attacked by your army, and a player you hit is attacked by it too. Hits that the world's PvP rules cancel don't count (the army check runs after the game's PvP filter). Several players each have their own army; army mobs of different owners can fight each other.
- **Army Wand:** your army mobs never attack you and can't be hurt by you or by each other. Joining an army takes the mob out of any battle, fight or team. Leaving or disbanding gives each mob its own AI back, and it ignores you for a while instead of attacking at once. Your army follows you: each mob's home (leash point) moves to you every second, so mobs whose role returns home walk after you, and any army mob more than 20 blocks away that isn't fighting is teleported next to you with a summon burst. Armies are kept in memory only: gone after a server restart. Joining flashes the mob green with a green burst ring, leaving flashes it red with a red ring.
- **Tint colors** are saturated on purpose: a light (pastel) tint color makes the mob look washed out or white.
- **One wand tint at a time:** a new wand effect removes the previous wand tint on that mob (two tints mixed into a pale white). Effects without a tint (Unmorph, Effect Wand) stack on top. The Kill Wand soul rip and team colors are never removed.
- **Wand casts:** wands swing like a sword, alternating left and right on quick clicks (a combo), and each swing finishes before the next one (Summon Wand: the staff summon pose; Chaos Wand right click: a spellbook push with a light camera shake), some with a sound.
- **On the clicked mob** (only when the action works; `/mobbattle wandFx off` turns them off):
  - Battle Wand: on each picked mob, the Heal/Cleanse burst ring in purple, plus a short (1 s) purple tint and aura (it does not stun). Clearing the selection (right click) flashes the selected mob grey with a grey burst ring.
  - Chaos Wand: everyone vs one, the lone target glows gold and every attacker red; battle royale, every fighter glows orange. A portal-opening sound plays at the area center when the summoning rune appears (`SFX_Portal_Neutral_Open`, vanilla, for both modes; `wandFx off` mutes it). All with the burst ring (as Heal) in that color, an aura and a small summoning rune turning under their feet; a big rune also glows on the ground at the area's center for the countdown, and a shockwave ring in the area color runs along the ground from the center out to the area edge (`areaRadius`, the same reach as the arena) (`MobBattle_Rune_Center` / `MobBattle_Rune_Feet_<Color>` in `Server/Particles/MobBattle`). The lone target also gets a small golden crown over its head for 20 s once the fight starts. With no mobs in the area: only the cast and the chat message, no area.
  - Heal Wand: a green burst (the Morph Wand one, recolored), the vanilla green healing aura, the Healing Totem particles and sound, and a green glow.
  - Kill Wand: you channel with the wand while the mob keeps moving, animating and attacking as it turns black and disintegrates. Its own death sound plays at the start (a random dark one if its model has none), without repeating on removal. A small purple smoke cloud travels in a slight spiral and shrinks toward an approximate wand-tip position, updated from the player's position and look direction. This is not the exact animated hand bone, so custom models may need offset adjustments. The wand itself plays a "hakai" sound from the player when the kill lands (`SFX_MobBattle_Hakai`, random pitch so it never sounds the same; also on instant kills). The kill lands on the cast's hit, `/mobbattle killHitDelay` ms after the click (default 175, the hit at 0.175 s of `KillCast.blockyanim`; 0 = on the click; skipped with `wandFx off`): the hakai sound, the disintegration and `killDelay` start then. Then the mob vanishes, no death animation, dropping its normal loot (`/mobbattle killLoot off`: no drops), after `/mobbattle killDelay` (0.5 s by default; players and other non-NPCs die at once, unless `/mobbattle killPlayers on`). The disintegration effect does not block its damage.
  - Summon Wand: mobs appear on the floor under them (also under roofs), close to you (`/mobbattle summonRadius`, by default it grows with how many), inside an area drawn like the Chaos Wand one, each with a summon burst and its model's own spawn animation when it has one (e.g. skeletons rising from the ground); adding a type to the list flashes the mob green with the burst ring (as Heal) and an aura, removing it does the same in red.
  - Cleanse Wand: the light coming down from above of the everyone-vs-one crown, with a burst, aura and rune at the feet, in `cleanseColor` (default white); on a morphed mob the Morph Wand blue swirl also plays as it gets its model back.
  - Effect Wand: the same burst in purple when the effect lands.
  - Morph Wand: copying (right click) and transforming both flash the mob like the Cleanse Wand, in blue (blue tint + the burst ring in blue). The transform burst also plays in the world at the mob, since the model swap can drop particles attached to the old model. Applying the model the mob already has does nothing: an error flash (grey-red tint, fizzle, error sound) and a chat message.
- **Battle end:** `X WINS!`, `RED TEAM WINS!`, `Duel won`, `DRAW!`, or for the Chaos Wand left click (everyone vs one mob) `X WINS!` (it survived) / `X IS DEFEATED!`.
- **Independent battles:** two team battles can use the same names and colors without sharing targets, damage, members or winners. Each battle keeps the team names and colors it started with. Allies never damage each other. With `/mobbattle battleIsolation` on (default for every kind) NPCs from outside the battle cannot damage its participants either. At the end, the mod clears its forced target/hostility and returns compatible NPCs to Idle.
- **Victory timing:** no minimum fight duration. Winners and the 15-minute safety timeout are checked once per second per world, even without NPC combat ticks. A single surviving side wins; several surviving sides at the deadline produce DRAW. The winner title may wait for an already visible title to finish.
- **Kill count:** a battle fighter gets a kill when its hit kills an enemy of the same battle. The timer box shows the leader ("28 fighters left | top: Trork 5"), the chat shows the top 3 when the battle ends ("Most kills: 1. Trork (5), 2. ..."), and `tieBreak kills` uses it. Only one line each, however many mobs fight. Disintegrations (Kill Wand, Snap Wand, `timeUp kill`) are not kills.
- **Arena teleports** put a fighter on a spot it can stand on (solid ground with room above) at about its own height: a mob in a cave stays in the cave, one on a mountain stays up, and nobody lands inside a hill or in lava, fire or poison (water is fine). If there is no such spot there, it is only pushed back.

## Command: `/mobbattle`

Settings are saved per world (`Saves/<world>/mods/Dackoro_Mob Battle/fx.properties`) and survive restarts. Every word is a real subcommand, so the chat suggests the next word as you type (`/mobbattle te` shows `teams`, `teamLook`). Values go right after the setting (`/mobbattle areaRadius 20`); the `--value=20` style is not needed.

Commands are written in camelCase in help (`areaRadius`, `teamLook onlyWand`) to read better; any case works (`arearadius` too). The chat suggestions always show them in lowercase: the game lowercases every command name. Old command names still work as aliases: `show`=`settings`, `title`=`startTitle`, `style`=`titleStyle`, `laser`=`fightLaser`, `color`=`fightLaserColor`, `radius`=`areaRadius`, `area`=`areaStyle`, `height`=`areaHeight`, `effect`=`wandEffect`, `teamfx`=`teamLook`, `isolation`=`battleIsolation`.

| Command | What it does |
|---|---|
| `/mobbattle help [topic]` | Without a topic: the list of topics. Topics: `wands`, `titles`, `chaos`, `battles`, `teams`, `summon`, `morph`; each shows its commands one per line. In Creative, joining a world shows a one-line hint about it (once per session). |
| `/mobbattle settings` | Show the current settings, one line per group. After changing a setting, only its group line is shown. |

### Titles
Every option in this table works globally (`/mobbattle countdown 5`) or for one battle kind (`/mobbattle countdown royale 5`; kinds: `duel`, `allVsOne`, `royale`, `teams`). `/mobbattle <option> <kind> default` makes that kind follow the global value again; `/mobbattle <option> all <value>` sets the global value and clears every kind's own value. `/mobbattle settings` lists the per-kind values under "per kind". The options that work this way are countdown, camera, cameraStyle, cameraTime, cameraShots, endCamera, endCameraTime, autoWatch, startTitle, winTitle, music, fightLaser, areaStyle and runes (the last two are in the Chaos Wand section below).

| Command | What it does |
|---|---|
| `/mobbattle camera on` / `off` | During the intro (the countdown, or `cameraTime`) your camera films the fight. Orbit (the `auto` shot for battle royale; everyone vs one: focus): a wide shot circling near the edge of the area (`areaRadius`) around its center, looking at the center (their tints last the whole shot), played with the game's own camera sequences (`CameraSequenceBuilder`, as vanilla `/tpcinematic`). Duel, western-style (the `auto` shot for duels): a close shot of each fighter, then a wide side shot of both, following them as they move (server camera frames every 50 ms from their current positions). Then it glides back to your view as the fight starts. Your input does nothing meanwhile, but your physics keep running, so you keep flying if you were. It is skipped when you are one of the fighters. `cameraStyle` picks the shot (default `auto`). With `countdown 0` it only plays if `cameraTime` is above 0 (the fight starts at once while it films). `/camera reset` gives the normal camera back if anything goes wrong. (default on) |
| `/mobbattle cameraStyle auto\|orbit\|duel\|showcase\|teams\|focus\|drone` | `auto`: duel = western shots, everyone vs one = focus, teams = team sweep, battle royale = orbit. `orbit`: circle around the area. `duel`: the western standoff (needs two sides; else showcase). `showcase`: close-ups of `cameraShots` fighters picked at random, each seen from the front with a slow drift (fewer if the time is short, at least 1.2 s each). `teams`: one shot per side (up to 6), from between that side and the battle's center: it holds on one member and then sweeps across the others, left to right. `focus`: a close-up of the lone target (about a third of the time), then the camera circles it at the crowd's distance, low and just outside the crowd, about 200 degrees, always looking at the attacker in front of it (one after another as it turns), with the target behind. Other kinds use the first fighter as the center (battle royale: falls back to showcase). `drone`: one flight across the battle from your side to the far side, high above the fighters; it dives past a few fighters picked at random (one every ~3 s, up to 4), passing beside each and looking at it, then climbs again. It keeps at least 3 blocks over the ground under it and just ahead (hills); tree leaves can still get in the way. Works in every kind. All server camera shots follow the fighters as they move. |
| `/mobbattle cameraTime <seconds>` | Intro length, 0-30 seconds. `0` (default) = as long as the countdown; above 0 it films even with `countdown 0`. |
| `/mobbattle cameraShots <n>` | `showcase` only: how many fighters it shows, from 1 up to `maxMobs` (default 4). Each shot lasts at least 1.2 s, so many shots need a long `cameraTime` (12 shots: about 15 s); with less time it shows fewer. |
| `/mobbattle endCamera off\|winner\|lastHit` | When a battle ends with a winner, your camera films a fighter in a slow half circle for `endCameraTime`, then comes back. `winner`: the last one standing (duel, royale) or the winning side's top killer still standing (everyone vs one, teams). `lastHit`: who scored the final kill (the last kill of a royale, the blow that took down the lone target, or the target's last kill if it won), if still standing; else the winner. The winner title and chat line add that fighter's name and kill count, e.g. `Last one standing \| Skeleton, 4 kills` / `Final blow: Trork, 2 kills`. Not on draws or time up. Skipped if you are dead or in another world. Default off. |
| `/mobbattle endCameraTime <seconds>` | Length of the end camera, 1-15 seconds (default 5). |
| `/mobbattle autoWatch on\|off` | (global or per kind) As soon as a battle starts, after its countdown, the players who get its cameras (`cameraScope`, by default only you) start watching it by themselves, like `/mobbattle watch` (see **Watch a battle**): an intro camera still playing ends and the spectator camera takes over. Not for players fighting in it, already watching, spectating or dead. When the battle ends they come back after 5 s, as with `watch`. Default off. |
| `/mobbattle skipCamera` | Ends your intro or end camera at once and gives you your normal camera back. **Crouching** while a camera plays does the same (a crouch you were already holding when it started has to be let go first, so the crouch + Battle Wand that started a fight doesn't skip it); the first camera of each session tells you so in chat. An intro camera also ends by itself when its battle ends (e.g. a duel won before the intro is over); the end camera (`endCamera`) is the only one that plays after the battle. |
| `/mobbattle music on` / `off` / `<track>` | Battle music from the countdown (or the start, with `countdown 0`) until the battle ends, for the players who see the titles (`titleScope`). Any music container ID; game battle tracks: `Track_Z1D_Goblin_Boss_Battle` (default), `Track_Void_Escalation`, `Track_Void_Tension`. Works like the game's `/audio music force`. (global or per kind) |
| `/mobbattle titleScope owner\|nearby\|world` | Who sees the big fight titles (countdown, start, winner): `owner` only the player who started the fight, `nearby` (default) every player in the same world within 64 blocks, `world` every player in that world. Each player has their own title queue, so fights of different players never cancel each other's titles: start titles are merged, winner titles all play. A countdown number shows at once and replaces whatever title that player had on screen. |
| `/mobbattle cameraScope owner\|nearby\|world` | Who gets the intro and end cameras: only you (default), every player within 64 blocks in the same world, or everyone in the world. Each one gets the shot from their own side; a player who is fighting never gets the intro. |
| `/mobbattle countdown <seconds>` | Countdown before any fight starts (duel, everyone vs one, battle royale, teams): a `3`, `2`, `1` title, one per second, then the fight and its start title. 0-10; `0` (default) starts at once. The chat also counts `[DUEL: A vs B] 3`, `2`, `1`. Several countdowns can run at once, like fights without one; a new order cancels only the countdowns that share a mob with it, and the big titles follow your newest countdown. If mobs die during the countdown the fight starts without them, or is cancelled. (global or per kind) |
| `/mobbattle startTitle on` / `off` | Fight-start title on/off. (global or per kind) |
| `/mobbattle winTitle on` / `off` | Winner title (`X WINS!`, `DRAW!`) on/off. The chat message always shows. (global or per kind) |
| `/mobbattle titleStyle <name>` | Title style: `Default`, `Major` (default), `GoblinBreach`, `VoidEviction`. |
| `/mobbattle titleStyle random` | Random style each fight. |
| `/mobbattle runes on\|off` | The summoning rune at the area center and the fighters' rage/lone/brawl marks (glow and rune under their feet) at the start of Chaos Wand battles (default on; `wandFx off` also hides them). |

### Presets

| Command | What it does |
|---|---|
| `/mobbattle preset list` | Built-in presets and your saved ones. |
| `/mobbattle preset default` | Every setting back to its default (your team list and summon list stay). |
| `/mobbattle preset fight` | Just the fights: no countdown, cameras, titles, music, lasers, runes, waves, border glow, mob looks, crown or area drawings, no timers; arenas that were on keep only their invisible wall. (A closing royale arena is still drawn: `arenaShrink royale off` to hide it.) |
| `/mobbattle preset effects` | Countdown 3, 2 min timer (most health wins), titles, music, lasers, runes, area disc + border (`areaStyle discBorder`), waves, borders, mob looks, crown, scoreboards; no cameras. |
| `/mobbattle preset debug` | Everything visible to test: area disc + wall (`areaStyle wall`), waves, borders, mob looks and crown, fighter lasers, runes, every arena drawn (walls kept where they were), team look tint + mist, `debugLog on`; no countdown and no cameras. |
| `/mobbattle preset cinematic` | Countdown 5, intro cameras (duel western, everyone vs one focus, royale drone, teams sweep), end camera (`winner`; `lastHit` for royale and everyone vs one), music and titles; no lasers, runes or area drawings (arenas keep their wall). Mob looks and crown stay; no waves or border glow. |
| `/mobbattle preset save <name>` / `load <name>` / `delete <name>` | Your own presets: every setting except the team and summon lists, saved in `<plugin data dir>/presets/<name>.properties`. Names: letters, digits, `-`, `_` (up to 32). |
| `/mobbattle reset <effects\|cameras\|titles\|arenas\|rules\|wands\|all>` | Only that group of settings back to default. `effects`: waves, border glow, mob looks, crown, runes, area, lasers, team look, wandFx, debugLog. `cameras`: intro and end cameras. `titles`: countdown, titles, music. `arenas`: arena, timer, timeUp, closing zone, scoreboard, tieBreak, unstick, duelCard. `rules`: isolation, friendly fire, playerFights, battlePvp, duelMode, areaRadius, maxMobs, summonRadius, teamByType. `wands`: Kill, Snap, Morph and Effect Wand settings. `all`: everything (= `preset default`). Per-kind values go with their group. Team and summon lists are kept. |

Built-in presets other than `default` only change the presentation settings (and the timers/arenas they mention); areaRadius, maxMobs, battle rules and lists stay as they are.

### Chaos Wand area and fighter laser
Changing `areaRadius`, `areaStyle`, `areaColor`, `areaOnlyWand` or `areaHeight` draws the area around you as a preview.

| Command | What it does |
|---|---|
| `/mobbattle fightLaser on` / `off` | Laser line between fighters at fight start (default off). Team rings always show. (global or per kind) |
| `/mobbattle fightLaserColor #RRGGBB` / `random` | Color of the fighter laser, e.g. `#2EFFD3`, or a random vivid color each fight. |
| `/mobbattle areaRadius <2-430>` | Area radius in blocks (default 60). Bigger radii search a much larger sphere on every Chaos Wand click; up to 430 is allowed. Existing worlds keep their saved radius; use `/mobbattle areaRadius 60` to update it. Only loaded mobs are considered. |
| `/mobbattle areaStyle border\|disc\|discBorder\|wall\|off` | What areas and arenas draw (the Chaos Wand area and summon area for a few seconds, an arena for the whole fight). `border` (default): only the border glow, columns of rising light about 20 blocks tall, about every 2 blocks of the edge (max 120), in each action's `borderColor`. `disc`: a translucent flat disc of color (no outlines) on the ground under the center, like boss attack zones. `discBorder`: the disc and the border. `wall`: the disc plus a see-through cylinder wall up to `areaHeight`. `off`: nothing (a shown arena still gets its border). A new disc clears the previous ones (e.g. several summons in a row). Worlds saved with the old styles (`floor`, `zone`, `off`) load as `border`. (global or per kind) |
| `/mobbattle areaOnlyWand on` / `off` | `off` (default): everyone sees the area for 6 s. `on`: only you see it, and only while you hold the Chaos Wand (like the Team Wand rings); switching item hides it within about a second, holding the wand again shows your last area again. A new area replaces the previous one. |
| `/mobbattle areaColor <color>` / `random` | Area color from the palette: red (default), blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta. `random` picks one each time. This is separate from `/mobbattle fightLaserColor`, which only colors the fighter laser. Black, gray and brown glow use normal blending, so they really look dark. |
| `/mobbattle areaHeight <3-128>` | Height in blocks of the `wall` style's wall (default 30). |
| `/mobbattle maxMobs <2-200>` | Max mobs per Chaos Wand click and per summon (default 200). Above ~60 the server may lag. Mobs left out are reported in chat. |

The `disc` / `wall` disc is flat and sits on the ground under the center (even if you are flying): on slopes it goes into hills and floats over dips. Border columns start on the top collision surface in loaded terrain (including roofs and tree tops); unloaded parts use the center height, without loading chunks. Mob selection uses the 3D radius around the clicked mob or player, so height differences affect which mobs are included.

### Battle rules
| Command | What it does |
|---|---|
| `/mobbattle battleIsolation <duel\|allVsOne\|royale\|teams\|all> on\|off` | Per battle kind. `on` (default for all): the battle's mobs only hurt their opponents, mobs outside it (wild mobs, other battles) can't hurt them, and their hits on players are cancelled. `off`: outside mobs can join in and battle mobs can hurt players and other mobs. Players can always hit battle mobs. Kinds: `duel` (Battle Wand), `allVsOne` (Chaos Wand left click, everyone vs one), `royale` (Chaos Wand right click), `teams` (Team Wand). `all` sets every kind; a bare `on`/`off` means `all`. |
| `/mobbattle duelMode exclusive\|accumulate` | Battle Wand: `exclusive` (default) replaces a mob's duel, `accumulate` merges connected duels (see Notes). |
| `/mobbattle debug` | Combat snapshots of the 4 nearest NPCs and a 10 s hit log (see Notes). Changes nothing. |
| `/mobbattle friendlyFire <allVsOne\|teams\|all> on\|off` | Hits between allies of the same battle. `allVsOne`: the attackers ganging up on one mob (default `on`: stray hits and area attacks hurt each other). `teams`: teammates (default `off`: their hits on each other are cancelled). Either way allies never pick each other as targets. Duels and royales have no allies. If the game itself cancels a hit (for example between mobs of the same pack), it stays cancelled. |
| `/mobbattle playerFights <duel\|allVsOne\|royale\|teams\|all> on\|off` | Players can fight in that kind of battle (default `off` for every kind; never players in Creative). See **Players in battles** above. |
| `/mobbattle battlePvp on\|off` | Enemy players in the same battle hurt each other even where the world's PvP is off. Default `off` (the world's rule). |
| `/mobbattle buff <lone\|streak\|champion\|wand> <size\|health\|damage\|defense\|all\|off> <percent>` | Buffs. `size`: bigger (0-200 %, mobs and players). `health`: more max health (0-1000 %). `damage`: harder hits on battle enemies (0-1000 %). `defense`: takes less damage from them (0-90 %). `all`: the four at once (size stops at 200, defense at 90). `off`: all to 0. Who: `lone` = the target of everyone vs one, for that battle; `streak` = per kill in a battle (the % times its kills, up to `buff streak maxKills`, default 5), for that battle; `champion` = the winners of a battle, until they die or get the Cleanse Wand; `wand` = the Buff Wand, per click (up to `buff wand maxStacks`, default 5), mobs and players, until they die or get the Cleanse Wand. `size` is the entity scale (mobs and players; a morph stays). Health only raises the max: `buff <who> heal on` also fills it, per source (default off); `buff lone heal on` heals the everyone-vs-one target when it is picked, even with no lone buff. Battle buffs' damage and defense count against battle enemies; the wand's against anyone. The sources add up. `buff crown gold\|<color>\|off`: crown on streak, champion and Buff Wand mobs (default gold). `buff sparks <seconds>\|off`: the lasting buff look of wand, streak and champion mobs (tint + aura) is put on again every N seconds so its aura particles repeat; the crown is put on once and stays (default 3; `off` = once). Default 0 for lone, streak and champion; the wand: size 10, health 20, damage 10, defense 5 per click. If the server stops mid-battle, the size and health stay on that mob. |
| `/mobbattle chaos me` | Everyone vs you: every mob within `areaRadius` attacks you, like the Chaos Wand left click on a mob (countdown, area, arena and timer included). Needs `playerFights allVsOne on`. |
| `/mobbattle arena <duel\|allVsOne\|royale\|teams\|all> on\|show\|wall\|off` | Per battle kind, the fight's arena. `show`: the area stays drawn for the whole fight (areaStyle and the color it was drawn with, plus the border glow: the allVsOne / royale `glow` color, the area color for duels and teams; a floor if there would be nothing). `wall`: fighters that reach its edge are pushed back in (the game's knockback), one flung far outside (8+ blocks) or still outside after 2 s of pushes (wedged in rock, a cave pocket, the closing wall passing it) is teleported back in, so big mobs or a big group stay in. `on`: both. Its radius is always the area's own: `areaRadius` for the Chaos Wand, `arenaSize` for duels and team battles, centered on the fighters. Default `on` for allVsOne and royale, `off` for duel and teams. Everyone sees the arena. |
| `/mobbattle arenaSize <8-128>\|auto` | Arena radius of duels and team battles. `auto` (default): around the fighters, their spread plus 6 blocks, at least 8. |
| `/mobbattle timer <kind\|all> <time>\|off` | Per battle kind, a time limit of up to 60 minutes. The time can be written `90` or `90s` (seconds), `2m`, `1m30s`, or with the game's named arguments `--minutes=2`, `--seconds=30`, `--minutes=1 --seconds=30` (summed; `--seconds 30` works too). A timer longer than the 15 min safety limit extends it. The players who see the titles (`titleScope`) get a timer box at the top right: the battle kind, who is fighting ("Skeleton vs Trork", "vs X: 5 attackers", "6 left") and the time left (`m:ss`). Default `off`. If the timer is too short for the arena (fighters at opposite edges could not even reach each other: about 0.8 s per block of radius), the chat warns you and suggests a timer. |
| `/mobbattle scoreboard <kind\|all> on\|off` | A box under the timer box with one row per side, refreshed every second, for the players who see the titles (`titleScope`): teams `RED 4/6 · 3 kills` in team colors; everyone vs one: target health % and `Attackers 12/20`; royale: `15/40 left` and the top killers; duel: each fighter's health. Default on for teams only. With a timer, team battles also show alive per team in the timer box (`RED 5 · BLUE 3`). |
| `/mobbattle arenaShrink <duel\|allVsOne\|royale\|teams\|all> on\|off` | The arena closes in like a battle royale zone: its wall moves steadily toward the center until `arenaShrinkTo`, over the battle's timer (smallest when time is up) or over `arenaShrinkTime` when there is no timer. A closing arena is always drawn and always a wall, even with `arena off`: fighters at its edge are pushed in, and the ones left far outside are teleported in, so spread-out fighters end up meeting. Default: `on` for royale, `off` for the rest. |
| `/mobbattle arenaShrinkTo <blocks>` | Final radius of a closing arena (4-128, default 15). An arena already smaller does not close. |
| `/mobbattle arenaShrinkTime <time>` | How long a closing arena takes when the battle has no timer: `90`, `2m`, `1m30s` (default `2m`). |
| `/mobbattle unstick on\|off` | A battle mob that can't reach its opponent because they are on different levels (one in a cave, the other on the surface or up a mountain) appears next to it, at the opponent's height, after 10 s without getting closer and without fighting. Fighting = it hit an enemy (a mob shooting from a cliff is left alone) or something on its own level hit it (being shot at from another level does not count: that is the stuck case). Fliers (birds, bats...) can reach any height, but a cave roof or a wall can still block them: they are moved after 20 s whatever the height. A walker is never moved toward an opponent that is flying (it would land under it); the flier comes down to attack. Default `on`. Players are never moved. |
| `/mobbattle duelCard on\|off` | A read-only card on the left of the screen with both fighters of a 1 vs 1 duel: health now / max (with its buff), attack, defense and size (the buff percentages, `base` when none). It shows from the countdown to the end of the duel, for the players who see the titles (`titleScope`), and refreshes once per second, so a Buff Wand click shows up at once. Only duels with exactly two fighters (not teams, not everyone vs one). `/mobbattle stop` clears it. Default `off`. |
| `/mobbattle timerStyle custom\|portal` | Which timer box. `custom` (default): the mod's own box at the top right, with the game's container header and panel textures; its layout is the asset `Common/UI/Custom/MobBattle/TimerHud.ui` (edit it like any `.ui` file; the dev pack reloads it). `portal`: the game's Void portal box; only its title is ours, the client keeps its own "Fragment of Orbis" / "until the Veil closes" text. |
| `/mobbattle tieBreak damage\|kills\|random\|draw` | `timeUp health` / `judge` when sides are even on health (within 0.5%): `damage` (default) the side that dealt more damage, `kills` the side with more kills, `random` a coin toss, `draw` no winner. |
| `/mobbattle debugLog on\|off` | Writes the battle decisions to the server log, each line starting with `[debug]`: the settings when turned on, each battle start (kind, fighters and sides, arena mode, radius, center, timer, timeUp, tieBreak, isolation, friendly fire), arena pushes and teleports, the timer reaching 0, each side's health % and damage at time up, tie-breaks, the winner, rejected duels and snaps. Default off. |
| `/mobbattle snap` | Same as the Snap Wand clicking the air: half of the mobs within `snapRadius` of you, fighting or not, turn to dust. |
| `/mobbattle snapMode global\|team` | Snap Wand and `snap`. `global` (default): half of everyone, at random. `team`: half of each side (sides of one mob, as in a royale, count together). |
| `/mobbattle snapPercent <1-100>` | Snap Wand and `snap`: share of the fighters that turn to dust (default 50). A fractional count rounds up or down at random. |
| `/mobbattle snapHitDelay <0-2000>` | Snap Wand: milliseconds from the click until the slam animation hits the ground, when the sound, the shockwave and the snap fire (default 310: frame ~30 of 60 at `Speed` 1.6 in `Server/Item/Animations/MobBattle_SnapWand.json`). Raise it if the effect lands before the hit, lower it if after; if you change the animation or its `Speed`, retune with `delay = hit frame / 60 / Speed * 1000`. |
| `/mobbattle snapTime <1-30>` | Seconds over which the chosen ones turn to dust, each at a random moment (default 5). When the last one is gone you get "The snap is over: X of Y turned to dust". |
| `/mobbattle snapRadius <blocks>` | Snap with no battle clicked: reach around the clicked mob or you, shown by the shockwave. Its own value (default 60), separate from `areaRadius`: a big battle area does not make the snap reach farther. |
| `/mobbattle snapPlayers on\|off` | The snap (Snap Wand and `/mobbattle snap`) also takes the players within `snapRadius`, fighting or not: they turn to dust and die a normal death. Never you, never players in Creative. Default off. |
| `/mobbattle timeUp <kind\|all> draw\|kill\|continue\|health\|judge` | What happens when the timer reaches 0. `draw` (default): the fight stops, "DRAW!". `kill`: every fighter left is disintegrated (soul rip, killDelay, killLoot), no winner. `continue`: the timer goes away and they keep fighting until the end, with no winner title. `health`: the side with the most health left wins, counted in percent so a big mob doesn't win just for having more health: each side's score is the average of its members' health in percent of their own max, the fallen counting as 0% (within half a percent: draw). `judge`: decided the same way, but then every fighter of the losing sides is disintegrated (soul rip) and the winners are healed to full (with the Heal Wand effect); on a tie, a draw. |

### Teams
| Command | What it does |
|---|---|
| `/mobbattle teams auto` | Put every mob within the radius into teams following the team rules (e.g. all skeletons red, all goblins green). |
| `/mobbattle teamByType on\|off` | Team Wand: clicking a mob also brings every mob of the same type (NPC role) within `areaRadius` into that team (moving them out of other teams); clicking it again takes them all out. Default off. |
| `/mobbattle teams start` | Start the team battle (same as Team Wand right click on a mob). |
| `/mobbattle teams clear` | Empty your teams and remove their team look. |
| `/mobbattle teams clearAll` | Empty every player's teams and remove their team look. |
| `/mobbattle teams join <team>` / `teams leave` | You fight with that team yourself: you get its team look (tint/mist) and, while you hold the Team Wand, its ring under your feet. Start the battle as usual (Team Wand or `teams start`). `leave` takes you out of your team, or out of the team battle you are in. Needs `playerFights teams on`. |
| `/mobbattle teams list` | Show the teams and their colors. Defaults: RED, BLUE, GREEN, YELLOW. |
| `/mobbattle teams add <name> <color>` | Add a team with a custom name (up to 14), e.g. `/mobbattle teams add Undead purple`. Fixed colors only: red, blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta. Custom hex colors are disabled. |
| `/mobbattle teams color <team> <color>` | Choose a color from the fixed palette. |
| `/mobbattle teams rename <team> <name>` | Change only the team's name, e.g. `/mobbattle teams rename RED Orcs`. Names use letters, numbers and underscores (max 16), and must be unique. Members, color and summon rules stay with the team. |
| `/mobbattle teams remove <team>` | Remove a team, its draft/battle members, team effects and summon rules. At least 2 teams stay. |
| `/mobbattle teams reset` | Back to the 4 default teams; clears current team drafts and team battle membership. |
| `/mobbattle teamLook both\|tint\|mist\|off` | Team look, visible to everyone: `tint` colors the whole mob, `mist` adds a mist of the team color (like the Enrage effect), `both` = tint + mist. Default `tint` (no mist). Removed when the battle ends or the mob gets a new order. |
| `/mobbattle teamLook time <seconds>\|infinite` | How long the team look stays after a mob joins a team, e.g. `5` to see the color only for a moment, then the mob looks normal again. `infinite` (default) = the whole battle. Max 600. For no look at all use `teamLook off`. When a team battle ends, the winning team keeps its look 8 more seconds so you can see who won. |
| `/mobbattle teamLook onlyWand on` / `off` | `off` (default): the team look shows as soon as a mob joins a team. `on`: the look shows only while the team's owner holds the Team Wand, like the team rings, and goes away within about a second when they switch item. Everyone sees it while it is on (it is an effect on the mob). |
| `/mobbattle summon team <role> <team>\|none` | Team rule for a mob type, used by summons and `teams auto`. |

All team colors use prebuilt effects, with no runtime asset loading. Older saved custom colors automatically become the closest fixed palette color for rings, chat, tint and mist; team names and slots are preserved.

### Summon
| Command | What it does |
|---|---|
| `/mobbattle summonRadius <1-64>` / `auto` | Size of the summon area in blocks. `auto` (default) grows with how many mobs: about 4 blocks for a few, ~19 for 100, never more than half of `areaRadius`. The area is drawn with the same `areaStyle` and `areaColor` as the Chaos Wand area. The summon area lasts 3 s (the Chaos Wand's 6 s). A new area cancels the glow of your previous one (and any glow in its spot), so summoning or casting again and again doesn't pile up columns of light (the floor disc can't be cancelled, only its time runs out). |
| `/mobbattle summon` | Summon the list around you (same as the Summon Wand right click). Mobs with a team rule spawn already in their team. |
| `/mobbattle summon list` | Show the summon list. |
| `/mobbattle summon add <role> [count\|random] [team]` | Add a mob type, e.g. `/mobbattle summon add Skeleton 5`, `/mobbattle summon add Trork_Warrior random blue`. Role IDs are case sensitive. |
| `/mobbattle summon remove <role>` / `clear` | Remove one type / empty the list. |
| `/mobbattle summon modify <role> <count\|random>` | Change the count of an existing entry, preserving its team and list order. Affects future summons. Example: `/mobbattle summon modify Skeleton 10`. |
| `/mobbattle summon random <min> <max>` | Range for `random` counts (default 1-5). Both ends are bounded to 0-100 and reversed ranges are reordered. |
| `/mobbattle summon atLeastOne true\|false` | `true`: every `random` type spawns at least 1 (default). `false`: it may spawn 0. |

### Effects and morph
| Command | What it does |
|---|---|
| `/mobbattle wandFx on` / `off` | Effects on the clicked mob (heal glow, soul rip, daze...). Default on. The cast animations always play. |
| `/mobbattle killDelay <seconds>` | Kill Wand: seconds of soul rip before the mob dies, 0-3 (`0` = instant). Default 0.5. |
| `/mobbattle killPlayers on` / `off` | Kill Wand on players. `on`: they freeze, turn black and disintegrate, then die (an instant kill) after `killDelay`; what they drop follows the world's own death rules. `off` (default): players are not affected (the wand's error look and a message). |
| `/mobbattle killLoot on` / `off` | Kill Wand: the disintegrated mob drops the loot a normal death would (its drop list, plus picked-up items). Default on. With `killDelay 0` the mob dies normally and always drops its loot. |
| `/mobbattle wandEffect <id>` | Effect Wand effect: any loaded effect ID, including other mods' (e.g. `Potion_Morph_Frog`). |
| `/mobbattle morph <model>` | Morph Wand model by ID, e.g. `/mobbattle morph Frog_Green`. |
| `/mobbattle morph list [text]` | Search model IDs, e.g. `/mobbattle morph list skeleton` (first 40). |
| `/mobbattle morph reset` | Give loaded morphed mobs in your current world/instance their own models back. Originals from other instances remain available when you return there. |
| `/mobbattle morphPlayers on\|off` | The Morph Wand works on players too (left click a player); Cleanse Wand or `/mobbattle morph reset` gives them their own look back. Default on. |

### Watch a battle

| Command | What it does |
|---|---|
| `/mobbattle watch` | Spectate a battle that is already running (any kind): your own battle in this world, else the nearest one. You become a spectator (the game's own spectator mode: you fly, nobody sees you and nothing hurts you) and the camera follows one fighter in third person, starting with the top killer still standing (or the nearest fighter). The mouse turns the camera around the fighter. **Right click**: the fighter nearest to the one you watch, skipping the ones already shown in this round (so two fighters side by side don't bounce back and forth; once everyone was shown a new round starts); **left click**: back to the fighter you watched before (up to 32 back); **Q**: stop watching. The game's controls box shows these keys. While you watch, the items in your hands and your other ability keys do nothing (the spectator mode overrides every interaction); they come back when you stop. When the fighter you watch is killed, the camera goes to its killer; if it disappears some other way (Kill Wand, Snap, unloaded), to the latest killer or the nearest fighter left. A card on the left shows the watched fighter: name, side, health, attack and defense buffs, size, kills, and how many fighters are left. When the battle ends the camera stays on the winner for 5 s, then you get your game mode, camera and position back. The intro and end cameras skip you while you watch, and starting to watch ends an intro you still had playing (e.g. a `cameraTime` longer than the countdown). It only works once a battle has started: during its countdown there is no battle to watch yet. Each player watches on their own: several can follow the same fighter or different ones, in the same battle or not. Player fighters (`playerFights`) are fighters like the mobs, so the clicks also reach them. A player fighting in a battle can't watch, and a spectator is never picked as a fighter. |
| `/mobbattle watch exit` | Stop watching (same as Q): back to your game mode, camera and the spot where you started watching. |

### Reset

| Command | What it does |
|---|---|
| `/mobbattle stop` | End every battle in progress (duels, Chaos Wand and team battles, no winner) and cancel pending countdowns. Everything they show goes too: timer boxes, arenas and the areas already drawn in your world, camera shots (you get your normal camera back at once), battle music, and every `watch` (spectators get their game mode back). |
| `/mobbattle skipTimer` | Every running battle timer jumps to 0: each battle ends by its own `timeUp` rule (draw, kill, continue, health, judge), as if its time had run out. Battles without a timer are not touched. |
| `/mobbattle army clearAll` | Disband every player's army. |
| `/mobbattle resetAll` | `stop` + `teams clearAll` + `army clearAll` + `morph reset` in one command. |

### Waves, border colors and mob looks per action

All colors are palette names (red, blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta): every border and mob look is a prebuilt particle set per color.

- `/mobbattle wave <summon|snap|slam|allVsOne|royale|all> <color|off>`: the ground shockwave of each action (slam = Battle Wand crouch + air). Its width grows with its radius (thin for a small one, thick for a big one).
- `/mobbattle borderColor <summon|snap|slam|allVsOne|royale|all> <color|off|area>`: the color of the border glow (columns of light along the edge) when `areaStyle` shows it. A color = that color; `area` = the area color (`areaColor`); `off` = no border for that action. Defaults: summon green, allVsOne yellow, royale purple, snap and slam off (they have no area: a color draws a ring of light at their reach).
- `/mobbattle crown gold|<color>|off`: the crown with rays of light over the lone target of everyone vs one, 20 s. `gold` (default) is the game's; the palette colors are copies of it.
- `/mobbattle buffColor <color>`: the Buff Wand look (tint, swirl, burst, aura and rune) in a palette color (default yellow = gold). Taking the buff off is always grey. The swirl also plays when the champion buff is given (a duel win).
- `/mobbattle cleanseColor <color>`: the Cleanse Wand look (the crown's light from above, burst, aura and rune at the feet) in a palette color (default white).
- `/mobbattle mobFx <duel|allVsOne|attackers|royale|all> <color|default|off>`: the tint, aura, burst and rune at the feet put on the battle mobs when the battle is called. `default` = the original look (duel charm, lone target gold, attackers red).

Defaults: countdown 0, titleScope nearby, cameraScope owner, camera on, cameraStyle auto, cameraTime 0 (the countdown), cameraShots 4, endCamera off, endCameraTime 5, autoWatch off, runes on, no per-kind values, music Track_Z1D_Goblin_Boss_Battle, duelMode exclusive, fightLaser off, fightLaserColor `#FF4B3E`, startTitle on, winTitle on, titleStyle `Major`, areaRadius 60, areaStyle border, areaColor red, areaOnlyWand off, battleIsolation on (all kinds), friendlyFire on for allVsOne / off for teams, playerFights off (all kinds), battlePvp off, buff off (lone, streak, champion), buff wand size 10 health 20 damage 10 defense 5, buff wand maxStacks 5, buff sparks 3, buff <who> heal off, buffColor yellow, buff streak maxKills 5, buff crown gold, arena on for allVsOne and royale / off for duel and teams, arenaSize auto, timer off (royale 3 min), scoreboard on for teams / off for the rest, timerStyle custom, timeUp draw (royale judge), arenaShrink on for royale / off for the rest, arenaShrinkTo 15, arenaShrinkTime 2m, unstick on, duelCard off, tieBreak damage, snapMode global, snapPercent 50, snapTime 5, snapHitDelay 310 ms, snapRadius 60, snapPlayers off, debugLog off, areaHeight 30, maxMobs 200, summonRadius auto, wandEffect `Burn`, wandFx on, killDelay 0.5, killHitDelay 175 ms, wave summon green / snap white / slam, royale purple / allVsOne yellow, borderColor summon green / allVsOne yellow / royale purple / snap, slam off, crown gold, cleanseColor white, mobFx default (royale purple), killLoot on, killPlayers off, morphPlayers on, teamByType off, teamLook tint, teamLook time infinite, teamLook onlyWand off.

## Install

1. Download `MobBattle.jar` from the releases.
2. Put it in your Hytale mods folder:
   - Pre-release: `%APPDATA%\Hytale\data\pre-release\Mods\`
   - Release: `%APPDATA%\Hytale\UserData\Mods\` (only once a release build matches the mod's `ServerVersion`)
3. Enable **Mob Battle** in the world's Mods list.

Don't replace the jar while a world is open: Hytale keeps it locked and can't reload plugins on the fly. Exit to the main menu first.

## Build from source

Requirements: JDK 25 and a Hytale install (the plugin compiles against the game's `HytaleServer.jar`).

```powershell
.\gradlew build "-PhytaleServerJar=$env:APPDATA\Hytale\install\pre-release\package\game\latest\Server\HytaleServer.jar"
```

Without `-PhytaleServerJar` the build uses `com.hypixel.hytale:Server` from `https://maven.hytale.com/release` (release patchline). The jar ends up in `build/libs/` and bundles `manifest.json`, `README.md`, `Common/` and `Server/`, so assets and plugin ship as one file.

`deploy.ps1` (Windows PowerShell) builds, waits until the game releases the jar (exit to the main menu) and copies it into your Mods folder as `MobBattle.jar`. Never overwrite the jar while a world uses it: the script waits, or with `-NoWait` only builds.

### Where it builds against and installs

By default it uses the **pre-release** patchline at the Hytale launcher's default folders. Change that in any of these ways (first match wins):

| Way | Example |
|---|---|
| Parameters | `.\deploy.ps1 -Patchline release` or `.\deploy.ps1 -ServerJar D:\Hytale\Server\HytaleServer.jar -ModsDir D:\Hytale\Mods` |
| `deploy.local.json` (git-ignored, per developer) | Copy `deploy.local.example.json` to `deploy.local.json` and set `patchline`, `serverJar` and/or `modsDir`. |
| Default (`-Patchline`) | `pre-release`: `%APPDATA%\Hytale\install\pre-release\...\HytaleServer.jar` and `%APPDATA%\Hytale\data\pre-release\Mods`. `release`: `%APPDATA%\Hytale\install\release\...\HytaleServer.jar` and `%APPDATA%\Hytale\UserData\Mods`. |

- If the server jar doesn't exist, Gradle compiles against the release artifact from Maven.
- `manifest.json` `ServerVersion` must match the server you run (`>=0.7.0-pre <0.8.0` targets the 0.7 pre-release). For release, change it to the release version, or the game refuses the mod.
- The script prints the patchline and Mods folder it uses before building.

### Repository layout notes

- `art-source/` is git-ignored: local Blockbench projects and reference models (e.g. attachments). Only what is in `Common/` ships.
- `scripts/sync-common-assets.ps1` is the helper `deploy.ps1 -Dev` starts in the background; you never run it yourself (see its header).

### Asset hot reload (models, textures, effects)

| Command | What it does |
|---|---|
| `.\deploy.ps1 -Dev` | Installs a code-only jar (`-PcodeOnly`) plus the folder pack `Mods\MobBattle.AssetsDev` ("Mob Battle Assets DEV"). Both mods must be enabled in the world. `Common/` is a real folder automatically synced from this repo by a hidden PowerShell helper every 750 ms; `Server/` is a junction. Hytale does not traverse a `Common/` junction in this version. Export models/textures to this repo's `Common/`; the helper mirrors them and Hytale's asset watcher can pick up changes. Blockbench `.bbmodel` projects are not copied. Only Java changes need a new deploy. Running `-Dev` again replaces the old sync token; a normal deploy stops the helper and removes the dev pack. |
| `.\deploy.ps1` | Back to one file: the full jar (code + `Common/` + `Server/`), and removes the dev pack (only the links, never the repo's assets). Run it before publishing. |

Put custom wand models and textures in `Common/` (e.g. `Common/Items/MobBattle/`), and point the item JSON `Model`, `Texture` and `Icon` fields at them.

Wand effects are plain JSON, so they hot-reload too:
- **Cast animation and sound:** the `Effects` block of each interaction in `Server/Item/Items/MobBattle_*.json` (`ItemAnimationId`, `ItemPlayerAnimationsId`, `WorldSoundEventId`, `CameraEffect`).
- **On the mob:** `Server/Entity/Effects/MobBattle/Fx/MobBattle_Fx_*.json` (particles, tint, sound, duration).
- **A part of the wand that lights up** (e.g. an orb): name that part `Orb` in Blockbench and add to the interaction's `Effects`:
  `"Particles": [{ "SystemId": "Magic_Hit", "TargetEntityPart": "PrimaryItem", "TargetNodeName": "Orb" }]`.
- **An always-on wand animation:** the item's root `"Animation": "Items/MobBattle/<wand>.blockyanim"` (loops, like the vanilla Crystal Flame staff).

## Game assets

Each wand has an independent model and texture under `Common/Items/MobBattle/<wand>/`, initially copied from the game's wood wand as an editing template. Files use their item ID, e.g. `MobBattle_KillWand.blockymodel` and `MobBattle_KillWand_Texture.png`. Export from Blockbench to those paths for asset hot reload; the item JSON points to them. The inventory icons still reference the game's wood wand icon. The fight titles are the client's built-in `EventTitleStyle` banners; the mod only picks which one to show.

## License

TBD.


git remote add origin git@github-hytale:dackoro/mob-battle.git
git branch -M main
git push -u origin main