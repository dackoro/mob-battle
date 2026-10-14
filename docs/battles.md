# Battles

Everything about how Mob Battle fights work: the four battle kinds, how fighters choose who to attack, the rules you can set per kind, arenas and timers, how a battle is won, and what you see while it runs (titles, cameras, spectating, cards and music).

Every command here starts with `/mobbattle`. Most settings are also in the settings panel (see [settings-panel.md](settings-panel.md)); the full list of settings and defaults is in [settings.md](settings.md), and the wands themselves are described in [wands.md](wands.md).

## Battle kinds

| Kind | Key in commands | Started with | Who fights | Who wins |
|---|---|---|---|---|
| Duel | `duel` | Battle Wand: click mob A, then mob B | Two fighters | The one left standing |
| All vs one | `allVsOne` | Chaos Wand left click on a mob (or player) | Every mob within `areaRadius` against the clicked one | The lone target if it survives, else the attackers |
| Battle royale | `royale` | Chaos Wand right click | Every mob within `areaRadius`, each on its own (an army is one side) | The last one standing |
| Team battle | `teams` | Team Wand: build teams, then right click a mob (or `teams start`) | Two or more teams | The last team standing |

The key is what you type when a setting works per kind, e.g. `/mobbattle arena royale wall` or `/mobbattle countdown duel 3`. `all` sets every kind at once.

### Duels (Battle Wand)

▶ Watch: [duel](https://youtu.be/TV-EqNu036Y) · [against a test dummy](https://youtu.be/NU3Tpd9qQdU)

Left click mob A, then mob B: they fight. Click a selected mob again to deselect it; right click clears the selection. A pair where neither mob can attack is refused before anything starts.

With players allowed in duels (`playerFights duel on`, the default), players can be picked like mobs, and **crouch + click a mob** makes that mob fight you (or select a mob, then crouch and click the air).

`duelMode` decides what happens when a mob already in a duel gets a new one:

| Value | Effect |
|---|---|
| `exclusive` (default) | The new duel replaces the old one. Starting B vs C ends A vs B, and A stops. |
| `accumulate` | Connected duels merge into one group: A keeps targeting B while B and C fight each other. When a target dies, the survivor picks another enemy of the group, until one side wins. The group has one final winner, announced to the owner of the newest duel, and its 15-minute limit restarts whenever a duel is added. Unrelated duels stay separate. |

Changing the mode affects new orders, not fights already running.

### All vs one (Chaos Wand left click)

▶ [Watch it](https://youtu.be/VkftCvat9ng)

Every mob within `areaRadius` of the clicked mob attacks it; the lone target fights back against the nearest attacker. The area holds at most `maxMobs` mobs (default 200, nearest first); the chat says how many were left out. Only loaded mobs count, and the radius is a 3D sphere, so a mob in a cave or on a roof can be in or out.

`/mobbattle chaos me` does the same with you as the target (needs `playerFights allVsOne on`).

### Battle royale (Chaos Wand right click)

▶ [Watch it](https://youtu.be/qorX35UQVe0)

Every mob within `areaRadius` fights on its own, centered on the mob you aim at (or on you). An Army Wand army (its whole chain of command, and its owner if they fight) is one side: its mobs fight together and the last army or mob standing wins. When a mob or leader of an army unloads with its chunk (you leave or walk away), it is back in its army when it loads again. Each fighter starts on one of its 3 closest opponents, picked at random; when its target falls it goes for the nearest survivor. Players in the area join too with `playerFights royale on`.

### Team battles (Team Wand)

▶ [Watch it](https://youtu.be/jvpsDgs0lXM)

Build the teams with the Team Wand, then right click a mob (or run `teams start`). You need at least 2 teams with living members. Each fighter starts on its nearest enemy. Team building, names, colors and the team commands are in [teams-summon-army.md](teams-summon-army.md).

Two team battles can use the same team names and colors without sharing targets, damage, members or winners: each battle keeps the names and colors it started with.

### When battles overlap

- **The newest order wins.** A fight order, team assignment or army join on mobs that are already in a royale or team battle takes only those mobs out of it; the rest keep fighting. If fewer than 2 sides are left, the battle ends without a winner title. Healing, effects and morphs never cancel a fight, and mobs never rejoin a battle on their own.
- **One chaos battle per group of mobs.** A new all vs one or royale ends, whole and without a winner, any other all vs one or royale that shares a mob with it. Duels and team battles only lose the mobs that move to the new battle.
- A mob that gets a new target ignores its previous one for 30 s.

## Battle waves

The Snap Wand (without a battle), the battle royale and all vs one send a ring out from where they start. By default it is only a look: everyone in reach is taken at once, at any height.
- [`/mobbattle battleWaveTravel on`](settings.md): each mob is reached when the ring gets there. A snap's dust starts then; royale and all vs one marks come with the ring (the battle itself still starts for everyone after the countdown).
- [`/mobbattle battleWaveGroundOnly on`](settings.md): only mobs within 3 blocks above or below the wave are snapped or join the battle. Fliers and mobs in caves stay out.

Both are off by default.

## Countdown

`countdown <seconds>` (0-10, default 0 = start at once) shows a big `3`, `2`, `1` before any fight, one per second, then the start title. The chat counts too (`[DUEL: A vs B] 3`, `2`, `1`). It works globally or per kind (`countdown royale 5`).

- Several countdowns can run at once. A new order cancels only the countdowns that share a mob with it.
- If mobs die during the countdown, the fight starts without them, or is cancelled if there are not enough left.
- In all vs one, the lone target's buff (if any) is applied at the start of the countdown, so it is already big and healthy when the attackers arrive.

## How fighters pick targets

Fighters use their own AI to fight; Mob Battle only tells each one who to attack.

- **Best enemy first.** When a fighter needs a new target, it takes the nearest living enemy of the best kind available:
  1. enemies in its own medium (land, air or water) that can fight back (players always count here);
  2. passive mobs in its own medium (no attack: they only run away);
  3. enemies in another medium it can still reach, the same two kinds (an archer shooting a fish at the surface);
  4. last, enemies it can't reach right now (see Land, air and water below), or one outside a walled arena.
- **Out of reach now.** If its current target flies off or leaves the walled arena, the fighter switches at once to an enemy it can reach, if there is one.
- **Chase limit.** A fighter that goes 8 seconds without landing a hit gives up its target for the best other enemy it can reach. In a duel, and for the attackers in all vs one, there is no other enemy, so they keep it.
- **Self defense** (per kind, default on). A fighter hit by an enemy that isn't its target turns on that enemy, and keeps it at least 3 seconds so two attackers can't pull it back and forth. A fighter ignores hits from an enemy it can't reach that is more than 4 blocks away (a flier, a fish under water); one that comes in close is answered. With self defense off, every fighter keeps its target until that target falls.
- **All vs one keeps its focus.** Every attacker stays on the lone target. A stray hit from a fellow attacker (with friendly fire on, the default for all vs one) still does its damage, but the attacker goes straight back to the target and ignores that ally for a while.
- Allies never pick each other as targets. Players always choose their own target.

Other things to know:

- Mobs of the same species or pack fight each other normally.
- The opening "provocation" hit that starts each fight does 0.01 damage and is healed back 0.3 s later.
- Bosses with a `Main` combat state (such as `Bear_Voidtaken`) enter it when ordered to fight. Abilities tied to players or to a specific encounter may still need their original encounter.
- A Trork's pet that spawns during a group battle joins its owner's side. If an owner and its pet are put on different sides, they leave their flock when the battle starts (permanently).

### Land, air and water

Each mob fights in its own medium, read from the game's own data for its role (so mobs from other mods work too):

| Mob | Can reach |
|---|---|
| Walks (most mobs) | the ground and shallow water (2 blocks deep or less) |
| Flies | the ground, the air and the water surface |
| Swims (fish and sea creatures) | water only, at the surface or under it |
| Breathes under water (skeletons and other undead) | also the bottom of deep water |
| Attacks from afar (archers, mages, throwers) | also fliers and the water surface |

- Which mobs attack from afar is learned the first time one of their kind hits something with a projectile. The game's mobs start from a list made by tests.
- A mob that would drown doesn't follow a target into deep water, and a fish doesn't try to reach one on land: it waits and picks someone it can reach.
- When nobody left can reach anybody (a fish in a lake and a wolf on land), the passiveWin countdown starts and the side with the most health left wins.
- Teleports (stuck fighters, the arena wall, the Army Wand) put fish in water and walkers on dry land, never at the bottom of a lake.
- A closing arena that leaves a fish's water outside moves the fish into water still inside the arena. If no water is left inside, the fish has 10 seconds and is then taken out, like a mob that can't come back into the arena.

### Battle height band

A royale or all vs one takes the mobs inside its area, but only from 50 blocks below its center to 40 above (`battleBelow`, `battleAbove`; `off` = the whole sphere as before): a battle on the surface leaves a deep cave's mobs alone, and fliers not too high still join. The band only chooses who joins. Separately, `arenaVertical <blocks>` (default off) makes the arena wall bring back a fighter that falls that far below the arena's level (a cave shaft) or climbs that far above it; fliers are never brought back for height.

## Rules per kind

| Setting | Values | What it does | Default |
|---|---|---|---|
| `battleIsolation <kind\|all> on\|off` | on/off | `on`: battle mobs only hurt their opponents, mobs from outside the battle can't hurt them, and their hits on players outside it are cancelled. `off`: outside mobs can join in, and battle mobs can hurt players and other mobs. Players can always hit battle mobs. | on for every kind |
| `selfDefense <kind\|all> on\|off` | on/off | See [How fighters pick targets](#how-fighters-pick-targets). | on for every kind |
| `friendlyFire <allVsOne\|teams\|all> on\|off` | on/off | Hits between allies. Allies never target each other either way; this only decides whether stray hits and area attacks hurt. A hit the game itself cancels (e.g. same pack) stays cancelled. Duels and royales have no allies. | on for allVsOne, off for teams |
| `playerFights <kind\|all> on\|off` | on/off | Players can be fighters in that kind. See [Players in battles](#players-in-battles). | on for every kind |
| `battlePvp on\|off` | on/off | Enemy players in the same battle hurt each other even where the world's PvP is off. Global. | off |

On the panel, each battle kind has its own tab with its rules, arena and timer.

## Arenas

An arena keeps a battle in one place. Per kind: `arena <kind|all> on|show|wall|off`.

| Mode | Effect |
|---|---|
| `show` | The area stays drawn for the whole fight, in the `areaStyle` and color it was drawn with, plus the border glow (the `allVsOne` / `royale` border color for Chaos Wand battles, the area color for duels and teams; a floor if there would be nothing). |
| `wall` | Fighters that reach the edge are pushed back in. One flung far outside (8+ blocks) or still outside after 2 s of pushes (wedged in rock, a cave pocket, a closing wall passing it) is teleported back in. |
| `on` | Both. |
| `off` | No arena. |

Default: `on` for allVsOne and royale, `off` for duels and teams. Everyone sees a drawn arena; how it is drawn follows `areaStyle` (see [colors-and-looks.md](colors-and-looks.md)).

- **Size.** Chaos Wand battles use `areaRadius`. Duels and team battles use `arenaSize <8-128>|auto`, centered on the fighters; `auto` (default) covers their spread plus 6 blocks, at least 8.
- **Teleports** put a fighter on a spot it can stand on (solid ground with room above) near its own height: a mob in a cave stays in the cave, one on a mountain stays up, and nobody lands inside a hill or in lava, fire or poison (water is fine). With no such spot, it is only pushed.
- **Closing arena.** `arenaShrink <kind|all> on|off` makes the wall move steadily toward the center like a battle royale zone, down to `arenaShrinkTo` (4-1024, default 15), over the battle's timer, or over `arenaShrinkTime` (default `2m`) when there is no timer. A closing arena is always drawn and always a wall, even with `arena off`, so spread-out fighters end up meeting. An arena already smaller than the final size does not close. Default: on for royale only.

## Timers and time up

`timer <kind|all> <time>|off` gives a battle a time limit of up to 24 hours. Times can be written `90`, `90s`, `2m`, `1m30s`, or `--minutes=1 --seconds=30`. Default: 3 minutes for royale, off for the rest.

- Players who see the titles get a **timer box** at the top right: the battle kind, who is fighting ("Skeleton vs Trork", "vs X: 5 attackers", "6 left"), the kill leader, and the time left. `timerStyle custom|portal` picks the mod's own box (default, with the game's container header and panel textures; its layout is the asset `Common/UI/Custom/MobBattle/TimerHud.ui`) or the game's Void portal box (only its title is the mod's; the client keeps its own "Fragment of Orbis" / "until the Veil closes" text).
- If the timer is too short for the arena (fighters at opposite edges could not even meet, about 0.8 s per block of radius), the chat warns you and suggests a time.
- A timer longer than the 15-minute safety limit extends that limit.

When the timer reaches 0, `timeUp <kind|all>` decides:

| Value | What happens |
|---|---|
| `draw` | The fight stops: `DRAW!`. Default for every kind except royale. |
| `kill` | Every fighter left is disintegrated. No winner. |
| `continue` | The timer goes away and they keep fighting to the end, without a winner title. |
| `health` | The side with the best `judgeBy` score wins. |
| `judge` | Same as `health`, then every fighter of the losing sides is disintegrated and the winners are healed to full. Default for royale. |

`judgeBy <kind|all>` sets what `health` and `judge` score:

| Value | Score |
|---|---|
| `health` (default) | Average health left, in percent of each fighter's own max; the fallen count as 0%. A big mob doesn't win just for having more health. |
| `damage` | The side's share of all damage dealt. |
| `kills` | The side's share of all kills. |
| `mix` | The three above averaged. |

Damage and kills are divided by each side's size first, so 20 attackers don't beat one target just for being 20. Scores within 0.5% are even and go to `tieBreak`:

| `tieBreak` | Even scores go to |
|---|---|
| `damage` (default) | The side that dealt more damage. |
| `kills` | The side with more kills. |
| `random` | A coin toss. |
| `draw` | No winner. |

If `tieBreak` is the same as the kind's `judgeBy` (damage with damage, kills with kills), it would only tie again, so the other one is used.

`/mobbattle skipTimer` (admin) makes every running timer jump to 0, so each battle ends by its own `timeUp` rule.

## Scoreboard

`scoreboard <kind|all> on|off` adds a box under the timer box, one row per side, refreshed every second:

| Kind | Shows |
|---|---|
| Teams | `RED 4/6 · 3 kills`, in team colors |
| All vs one | The target's health % and `Attackers 12/20` |
| Royale | `15/40 left` and the top killers |
| Duel | Each fighter's health |

Default: on for teams only. With a timer, team battles also show the living members per team in the timer box (`RED 5 · BLUE 3`).

## Stuck fighters: unstick and stand-off

Two separate helpers for fights that would otherwise never finish. Players are never moved.

**Unstick** (`unstick on|off`, default on). A fighter that can't reach its opponent because they are on different levels (one in a cave, the other on the surface or up a mountain) is moved next to it, at the opponent's height, after 10 s without getting closer and without fighting.

- "Fighting" means it hit an enemy (a mob shooting from a cliff is left alone) or something on its own level hit it. Being shot at from another level doesn't count: that is exactly the stuck case.
- Fliers (birds, bats...) can reach any height, but a cave roof or a wall can still block them: they are moved after 20 s, whatever the height.
- A walker is never moved toward an opponent that is flying (it would land under it); the flier comes down to attack.

**Stand-off shove** (`standoffTime <seconds>|off`, 0-120, default 15). A fighter on its opponent's level that spends this long without getting closer, without hitting and without being hit, while out of melee reach, gets a shove (a hop) toward its opponent, and again after the same time while the stand-off lasts. It is never teleported, and mobs that can't attack are left alone. Some mobs keep their distance on purpose (e.g. two Goblin Feastmasters stay 8 blocks apart and only close in with a charge they don't use with other goblins nearby). Works with `unstick off` too.

## Passive mobs: passiveWin

▶ Watch: [a mob against a test dummy, which never ends the battle](https://youtu.be/NU3Tpd9qQdU)

A skeleton can never finish off a duck flying out of its reach. `passiveWin <seconds>|off` (0-120, default 15) handles that:

- When only one side can still fight back (the others are left with passive mobs only), that side wins after this many seconds without a hit.
- If nobody left can fight, the battle ends in a draw after the same time.
- Meanwhile an alert at the bottom left of the screen says who wins (or that it will be a draw) and the seconds left. It goes to the players who see the titles.
- Any hit between enemies of the battle starts the time again.
- When a side wins this way, only its members count as standing; the end title says so ("The rest can't fight back").

`0` / `off` turns it off: the battle goes on until its timer or the 15-minute limit.

**Training dummies.** While a mob on the `passiveWinIgnore <role,role*,...>|none` list is alive in the battle, `passiveWin` never ends the battle (its time never starts), so you can test a mob against an immortal dummy for as long as you like. The list is comma separated and case-insensitive, and a trailing `*` matches any ending. The default is `Test_Dummy*`, which covers the game's six test dummies. `none` removes every exception. In the panel it is "Never ends against" on the Battles tab.

## Nobody fights: idleEnd

Some mobs can fight but never start a fight: a battle royale of boars and bison can stand still for its whole timer. `idleEnd <seconds>|off` (0-300, default 150 = 2.5 minutes) ends a battle in a draw when no fighter hurts an enemy for that long, counted from the start or from the last hit. The end title says how long, e.g. "Nobody fought for 2m 30s". While a `passiveWinIgnore` mob (a training dummy) is alive, it never ends the battle either. In the panel it is "End when nobody fights" on the Battles tab.

## Winners, titles and end messages

The winner is checked once per second per world. There is no minimum fight length. A single surviving side wins; if several sides are still alive at the 15-minute safety limit, it is a draw.

| Kind | Start title | End title |
|---|---|---|
| Duel | `FIGHT!` | `X WINS!` with "Duel won" |
| All vs one | `EVERYONE vs X` | `X WINS!` (it survived) or `X IS DEFEATED!` |
| Royale | `BATTLE ROYALE!` | `X WINS!` with "Last one standing" |
| Teams | `TEAM BATTLE!` | `RED TEAM WINS!` (in the team color) |
| Any, no winner | | `DRAW!` ("Time is up", "Nobody is left standing", "Nobody left can fight") |

- The same line always goes to chat, even with titles off.
- `startTitle on|off` and `winTitle on|off` (global or per kind) turn the titles on or off. `titleStyle` picks the banner style (`Default`, `Major` (default), `GoblinBreach`, `VoidEviction`, `random`).
- `titleScope owner|nearby|world` decides who sees titles, timer boxes, scoreboards and alerts: only you, players within 64 blocks (default), or the whole world. Players fighting in a battle always see its titles.
- Each title lasts about 3 s. Victories are queued in order and take priority over pending start titles; a new fight never overwrites a victory, and a winner title may wait for a title already on screen to finish. Repeated start titles keep only the newest pending one.
- Each player has their own title queue, so fights started by different players never cancel each other's titles: start titles are merged, winner titles all play. A countdown number shows at once and replaces whatever title that player had on screen, and the big titles follow your newest countdown.
- The titles are the client's built-in event title banners (`EventTitleStyle`); the mod only picks which style to show.
- When a team battle ends, the winning team keeps its team look 8 more seconds.
- When the battle ends, the mod clears the forced targets and hostility it set, and returns the mobs to their normal behavior.

## Kill counts

A fighter gets a kill when its hit kills an enemy of the same battle. Disintegrations (Kill Wand, Snap Wand, `timeUp kill`) are not kills.

- The timer box shows the leader ("28 fighters left | top: Trork 5").
- At the end, the chat shows the top 3 ("Most kills: 1. Trork (5), 2. ...").
- Fighters with the same kills are ordered by `killTieBreak` (panel: Battles > Ties > "Kill table order", or each battle tab for its own battles):
  - `damage` (default): more damage dealt to enemies goes first;
  - `alive`: fighters still standing first, then more damage;
  - `first`: whoever reached that number of kills first;
  - `shared`: they share the place ("1. Trork 5, 1. Skeleton 5, 3. ..."), and the timer box says "top: 2 tied 5".
  After that, whoever got there first. The timer box, the scoreboard, the chat top 3, the end camera and spectating all use this order.
- `tieBreak` is a different tie: who **wins** when sides have the same Judge by score at time up. Both have a global value and a per-kind override (see [settings.md](settings.md)).
- `tieBreak kills` and `judgeBy kills` use them, and the end camera can film the top killer.

## Battle buffs

Battles can make fighters bigger and stronger. Three battle sources, all off (0%) by default, plus the Buff Wand:

| Source | Who gets it | How long |
|---|---|---|
| `lone` | The lone target of all vs one, from the start of the countdown | Until that battle ends |
| `streak` | A fighter, once per kill (up to `buff streak maxKills`, default 5) | Until that battle ends |
| `champion` | The surviving members of the winning side | Until they die or get the Cleanse Wand |

Each source can raise size, max health, damage, defense and speed by a percentage, e.g. `/mobbattle buff lone size 50`, `/mobbattle buff champion all 10`. Sources add up.

With `/mobbattle buff unit multiplier` (or "Buff values as" in the panel), values are shown and typed as multipliers instead, with decimals: `/mobbattle buff lone damage 2` = x2 damage (+100%), `x1.5` = +50%. Defense x2 means the mob takes half the damage (50%), x100 is the most (99%). `x2` and `50%` always work, whatever the unit. Values below x1 are refused: a buff never shrinks or weakens. Only the way values are shown changes: sources and Buff Wand clicks still add up (two x2 sources = x3).
 Size is the entity's scale (mobs and players), so a morph stays; all sources together stop at `buff sizeMax` (default +200%, x3). A player over x2 is told that weapon and tool reach does not grow with size: the game measures it from the eyes, so mobs at a giant's feet can be out of reach. Jumps and speed grow with the size (the speed stat adds to it): a player's jump force and walking speed, a mob's jump and climb height and its top speed (speed by the square root of the size, so x4 = twice as fast). Health raises the max and keeps the same share of health; `buff <who> heal on` fills it to the new max. If the server stops mid-battle, the size and health stay on that mob (the Cleanse Wand resets them). Battle buffs' damage and defense only count against enemies of the battle. Damage counts for every hit the mob or player deals itself: fists, weapons, its own attacks (a boss's slam), arrows and other projectiles it shoots. Damage it does to itself (its own burn, a fall) is not raised. All buff settings, the Buff Wand and the buff looks are in [settings.md](settings.md) and [colors-and-looks.md](colors-and-looks.md).

## Cameras

▶ Watch: [duel intro and winner](https://youtu.be/eYbRIGjnRyw) · [battle royale orbit and spectator mode](https://youtu.be/urVVfprhp3o)

### Intro camera

`camera on|off` (default on, global or per kind): during the countdown, or for `cameraTime` seconds, your camera films the fight, then glides back to your view as it starts. Your input does nothing meanwhile, but your physics keep running (you keep flying if you were). It is skipped when you are one of the fighters, and when you are watching (see below). With `countdown 0` it only plays if `cameraTime` is above 0.

| `cameraStyle` | Shot |
|---|---|
| `auto` (default) | Duel = `duel`, all vs one = `focus`, teams = `teams`, royale = `orbit`. |
| `orbit` | A wide shot circling near the edge of the area (`areaRadius`) around its center, looking at the center. The fighters' tints last the whole shot. |
| `duel` | Western standoff: a close shot of each fighter, then a wide side shot of both. Needs two sides (else `showcase`). |
| `showcase` | Close-ups of `cameraShots` fighters picked at random (default 4, up to `maxMobs`), each from the front with a slow drift, at least 1.2 s each. With a short time it shows fewer: 12 shots need about 15 s of `cameraTime`. |
| `teams` | One shot per side (up to 6), from between that side and the battle's center: it holds on one member, then sweeps across the others, left to right. |
| `focus` | A close-up of the lone target (about a third of the time), then the camera circles it about 200 degrees, low and just outside the crowd, always looking at the attacker in front of it (one after another as it turns), with the target behind. In other kinds it centers on the first fighter (royale falls back to `showcase`). |
| `drone` | One low, smooth flight across the battle from your side to the far side, weaving past a few fighters picked at random (one every ~1.5 s of flight, up to 6; left of one, right of the next) without stopping. It flies about 4 blocks over the ground or the tree tops, climbing before hills and trees ahead, looks ahead along its path and turns toward each fighter as it comes up to it. The last third of the shot swings around the battle, looking at its center, like the orbit. In an arena with a roof it stays under the roof and out of the stands. Works in every kind. |

`cameraTime <0-30>`: intro length in seconds; `0` (default) = as long as the countdown. Shots follow the fighters as they move: `orbit` uses the game's own camera sequences (like the vanilla `/tpcinematic`), and every other style sends server camera frames every 50 ms from the fighters' current positions.

`cameraScope owner|nearby|world`: who gets the cameras: only you (default), players within 64 blocks, or everyone in the world. Each player gets the shot from their own side.

### End camera

`endCamera off|winner|lastHit` (default off): when a battle ends with a winner, your camera films one fighter in a slow half circle for `endCameraTime` (1-15 s, default 5).

- `winner`: the last one standing (duel, royale), or the winning side's top killer still standing (all vs one, teams).
- `lastHit`: whoever scored the final kill, if still standing; else the winner.

The end title and chat line add that fighter's name and kills, e.g. `Last one standing | Skeleton, 4 kills` or `Final blow: Trork, 2 kills`. No end camera on draws or time up, or if you are dead or in another world.

### Skipping a camera

- **Crouch** while a camera plays to end it. A crouch you were already holding when it started must be released first, so the crouch + Battle Wand that started a fight doesn't skip it. The first camera of each session tells you so in chat.
- **Esc can't** end it: the pause menu only exists on your side, the server never learns it was opened.
- `/mobbattle skipCamera` does the same.
- An intro also ends by itself when its battle ends (e.g. a duel won before the intro is over).
- If anything goes wrong, the game's `/camera reset` gives the normal camera back.

## Watching a battle

▶ [Watch it](https://youtu.be/urVVfprhp3o)

`/mobbattle watch` lets you spectate a running battle of any kind: your own battle in this world, else the nearest one. You enter the game's spectator mode (you fly, nobody sees you, nothing hurts you) and the camera follows one fighter in third person, starting with the top killer still standing (or the nearest fighter). The mouse turns the camera around the fighter.

| Control | Action |
|---|---|
| Right click | The fighter nearest to the one you watch, skipping fighters already shown this round (a new round starts once everyone was shown). |
| Left click | Back to the fighter you watched before (up to 32 back). |
| Q (or `/mobbattle watch exit`) | Stop watching: back to your game mode, camera and the spot where you started watching. |

- The game's controls box shows these keys.
- While you watch, held items and other ability keys do nothing (the spectator mode overrides every interaction); they come back when you stop.
- The intro and end cameras skip you while you watch, and starting to watch ends an intro you still had playing (e.g. a `cameraTime` longer than the countdown).
- Player fighters (`playerFights`) are fighters like the mobs, so the clicks reach them too.
- When the watched fighter is killed, the camera moves to its killer. If it disappears another way (Kill Wand, Snap, unloaded), to the latest killer or the nearest fighter left.
- When the battle ends, the camera stays on the winner for 5 s, then you get your game mode, camera and position back.
- You can only watch once a battle has started (not during its countdown). A player fighting in a battle can't watch it, and a spectator is never picked as a fighter.
- Several players can watch at once, the same fighter or different ones.
- If you disconnect while watching, your game mode is given back when you return.

**autoWatch** (`autoWatch on|off`, global or per kind, default off): as soon as a battle starts (after its countdown), the players who get its cameras (`cameraScope`) start watching it automatically. An intro camera still playing ends, and the spectator camera takes over. Players fighting, already watching, spectating or dead are left alone.

## Duel card and watch cards

- **Duel card** (`duelCard on|off`, default off): a read-only card on the right of the screen, in the scoreboard's place (it replaces that duel's scoreboard), for a 1 vs 1 duel, with both fighters' health (now / max), attack, defense and size, including their buffs. It shows from the countdown to the end of the duel, for the players who see the titles, and refreshes once a second.
- **All vs one card** (`focusCard on|off`, default off): the same kind of card for an all vs one, also on the right in place of its scoreboard. On top, the lone target with its health (now / max), attack, defense, size (with its buffs) and kills; below, how many attackers are left out of the most there were, their summed health, and the attacker that dealt the most damage. It shows from the start of the battle (not during the countdown) until the end, for the players who see the titles, and refreshes once a second.
- **Watch card**: while you watch, a card on the left shows the watched fighter's name, side, health, attack and defense buffs, size, kills, and how many fighters are left.
- **Watch duel card**: in a 1 vs 1 duel the watch card shows both fighters instead, and tags the one you are watching with "Watching".

## Battle music

`music on|off|<track>` (global or per kind): battle music from the countdown (or the start) until the battle ends, for the players who see the titles. Any music container ID works; the game's battle tracks are `Track_Z1D_Goblin_Boss_Battle` (default), `Track_Void_Escalation` and `Track_Void_Tension`. It works like the game's `/audio music force`. A kind with its own track keeps playing if the global music is turned off.

## Players in battles

With `playerFights <kind> on` (default on for every kind), players are fighters too. Nobody controls them: the mobs of the other sides attack them and their own side's mobs ignore them.

- **Ways in:** the wands work on players like on mobs (Battle Wand duel, Chaos Wand left click = everyone vs that player, Team Wand = joins the current team); Chaos Wand right click takes in the players in the area; crouch + Battle Wand on a mob = that mob vs you; `/mobbattle chaos me`; `/mobbattle teams join <team>` / `teams leave`.
- **Creative players never count.** They can't be put in a battle (the chat says why), and a fighter who switches to Creative is out at once (their side may lose); during a countdown, they are left out when the fight starts. Use Adventure mode to fight.
- **Your side's mobs** never target you and can't hurt you, and your hits on them are cancelled (unless `friendlyFire` is on for that kind). With `battleIsolation` on, mobs from outside the battle can't hurt you.
- **PvP:** enemy players in the same battle follow the world's PvP rule; `battlePvp on` lets their hits through even where PvP is off (spawn protection and invulnerability still apply).
- **Titles, timer and music** always reach players fighting in a battle, whatever `titleScope` says.
- **Dying** takes you out of the battle for good (also during its countdown): you respawn outside it, and if your side has nobody left, it loses. `teams leave` during a battle works the same way. If you win, the title shows your name.
- **Disintegration:** the Snap Wand, `timeUp kill` and `timeUp judge` turn player fighters to ash like mobs, and then they die.
- Players are never moved by unstick or the stand-off shove.

## What ends a battle

| Cause | Result |
|---|---|
| Only one side left | That side wins. |
| No side left | Draw. |
| `passiveWin` time without a hit | The only side that can fight wins, or a draw if none can. |
| `idleEnd` time without any hit between enemies | Draw. |
| The timer reaches 0 | Whatever `timeUp` says. |
| `battleMaxTime` minutes without a winner (default 15; `off` = infinite battle; extended by a longer timer) | Draw. |
| A newer order takes mobs out and fewer than 2 sides remain | Ends without a winner title. |
| Fighters unloaded with their chunks (no player near) | Cancelled: no winner, no champion, with a chat notice. Mobs killed by the Kill Wand or Snap still count as fallen. |
| `/mobbattle stop`, `resetAll`, or the server shutting down | Ends with no winner. |

**Bosses that change role** between stages (the game re-creates them under a new role) stay in the battle: their side, kills, buffs, army membership and looks are carried over to the new form.

## Stopping battles

| Command | What it does |
|---|---|
| `/mobbattle stop` | Ends every battle in progress (no winner) and cancels pending countdowns, along with everything they show: timer boxes, arenas and drawn areas in your world, camera shots (your normal camera comes back at once), battle music, duel cards, and every watch (spectators get their game mode back). Admin. |
| `/mobbattle skipTimer` | Every running timer jumps to 0; each battle ends by its own `timeUp` rule. Battles without a timer are not touched. Admin. |
| `/mobbattle resetAll` | `stop` + clear every player's teams + disband every army + undo every morph. Admin. |
