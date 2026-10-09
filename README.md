# Dackoro's Mob Battle

A Hytale mod by **Dackoro**: wands that make mobs fight using their existing AI. Supports vanilla NPCs and modded NPCs with compatible combat behavior; player-only attacks and encounter-specific mechanics may need adaptations.

Patchline: **pre-release** (`ServerVersion >=0.7.0-pre <0.8.0`).

## Items

Each wand's tooltip explains what it does (English and Spanish). Get all ten at once with `/mobbattle give`, or individually from the creative inventory or with `/give <item ID>`. The command gives one of each using Hytale's normal inventory placement and requires the same permission as `/give` for yourself. If some do not fit, it reports which ones were not delivered.

### Fights
| Item ID | Name | Left click | Right click |
|---|---|---|---|
| `MobBattle_Wand` | Battle Wand | Click mob A, then mob B: they fight each other. | Clear the selection. |
| `MobBattle_ChaosWand` | Chaos Wand | Every mob within the radius attacks the clicked mob. | Battle royale: every mob within the radius fights; when a mob kills its target it goes for the nearest survivor, until one is left (centered on the mob you aim at, or on you). |
| `MobBattle_TeamWand` | Team Wand | Add the mob to the current team (click again to remove it). | In the air: switch team. On a mob: start the battle. Last team standing wins. |

### Mobs
| Item ID | Name | Left click | Right click |
|---|---|---|---|
| `MobBattle_SummonWand` | Summon Wand | Add the mob's type to the summon list (click again to remove it). | Summon the whole list around you. |
| `MobBattle_MorphWand` | Morph Wand | Give the mob the copied model. It keeps its own AI, weapon, attacks and health. | On a mob: copy its model. In the air: show the copied model. |

### Health and effects
| Item ID | Name | Left click | Right click |
|---|---|---|---|
| `MobBattle_HealWand` | Heal Wand | Restore the entity to 100% health. | — |
| `MobBattle_KillWand` | Kill Wand | Kill the entity (any amount of health). Mobs disintegrate first for `killDelay` and drop their loot (`killLoot`); players and other entities die at once. | — |
| `MobBattle_EffectWand` | Effect Wand | Give the entity the selected effect (default `Burn`). | Next effect: Burn, Poison_T2, Freeze, Slow, Stun, Root, Vulnerable, Ability_Enrage_Buff, Potion_Health_Regen, Immune. |
| `MobBattle_CleanseWand` | Cleanse Wand | Remove every effect from the entity and undo a morph. | — |
| `MobBattle_ArmyWand` | Army Wand | Add the mob to your army (click it again to remove it). Your army follows you, defends you and attacks what you hit. | Disband your army. |

### Notes
- A passive mob (no attack) only runs away. The aggressive one still attacks.
- Boss roles with a `Main` combat substate (such as `Bear_Voidtaken`) enter that substate when ordered to fight. Abilities tied to players or a specific Hunt site may still require their original encounter.
- Mobs of the same species and the same pack also hurt each other.
- A new target replaces the old one: the mob ignores its previous target for 30 s.
- Fights last up to 10 minutes, or until one of the two dies.
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

### Effects
- **Fight start (Battle Wand, Chaos Wand, Team Wand):** a big title (`FIGHT!`, `EVERYONE vs X`, `BATTLE ROYALE!`, `TEAM BATTLE!`) and, with `/mobbattle fightLaser on`, a laser linking each mob to its first opponent (team color in team battles).
- **Titles:** each lasts about 3 s. Repeated fight-start titles keep only the newest pending one. Every victory is queued in order and takes priority over pending starts; a new fight cannot overwrite a victory.
- **Chaos Wand area:** for 6 s, in the area color (`/mobbattle areaColor`): columns of light about 20 blocks tall along the edge plus a translucent floor like a boss attack zone (`both`, default), with a see-through wall too (`all`), or one of them (`/mobbattle areaStyle`, `/mobbattle areaHeight`).
- **Team look:** tint and/or a mist in the team color (`/mobbattle teamLook`).
- **Clicking the same mob again** replays its wand effect (it is restarted, not just extended).
- **Army Wand orders**, most urgent first: (1) an army mob that gets hit fights back, and the nearest free mate helps (one helper: further hits from the same attacker don't pull in more); (2) whoever hits you is attacked by every free army mob; (3) whoever you hit is attacked by every free army mob; if nobody is free in (2) or (3) and no army mob is on that enemy yet, the nearest half switches and the rest finish their fight (hitting the same enemy again does not pull more); (4) a mob whose target died joins the nearest enemy the army still fights, or follows you again when there is none. Mobs that were just following you stay with you. "Free" = not fighting, or its target is dead. Army mobs never ignore an old target that keeps hitting them. **PvP:** rules 1-3 also count players: a player who hits you or your army mobs is attacked by your army, and a player you hit is attacked by it too. Hits that the world's PvP rules cancel don't count (the army check runs after the game's PvP filter). Several players each have their own army; army mobs of different owners can fight each other.
- **Army Wand:** your army mobs never attack you and can't be hurt by you or by each other. Joining an army takes the mob out of any battle, fight or team. Leaving or disbanding gives each mob its own AI back, and it ignores you for a while instead of attacking at once. Your army follows you: each mob's home (leash point) moves to you every second, so mobs whose role returns home walk after you, and any army mob more than 20 blocks away that isn't fighting is teleported next to you with a summon burst. Armies are kept in memory only: gone after a server restart. Joining flashes the mob green with a green burst ring, leaving flashes it red with a red ring.
- **Tint colors** are saturated on purpose: a light (pastel) tint color makes the mob look washed out or white.
- **One wand tint at a time:** a new wand effect removes the previous wand tint on that mob (two tints mixed into a pale white). Effects without a tint (Unmorph, Effect Wand) stack on top. The Kill Wand soul rip and team colors are never removed.
- **Wand casts:** wands swing like a sword, alternating left and right on quick clicks (a combo), and each swing finishes before the next one (Summon Wand: the staff summon pose; Chaos Wand right click: a spellbook push with a light camera shake), some with a sound.
- **On the clicked mob** (only when the action works; `/mobbattle wandFx off` turns them off):
  - Battle Wand: on each picked mob, the Heal/Cleanse burst ring in purple, plus a short (1 s) purple tint and aura (it does not stun). Clearing the selection (right click) flashes the selected mob grey with a grey burst ring.
  - Chaos Wand: everyone vs one, the lone target glows gold and every attacker red; battle royale, every fighter glows orange. All with the burst ring (as Heal) in that color and an aura. The lone target also gets a small golden crown over its head for 20 s once the fight starts. With no mobs in the area: only the cast and the chat message, no area.
  - Heal Wand: a green burst (the Morph Wand one, recolored), the vanilla green healing aura, the Healing Totem particles and sound, and a green glow.
  - Kill Wand: you channel with the wand while the mob keeps moving, animating and attacking as it turns black and disintegrates. Its own death sound plays at the start (a random dark one if its model has none), without repeating on removal. A small purple smoke cloud travels in a slight spiral and shrinks toward an approximate wand-tip position, updated from the player's position and look direction. This is not the exact animated hand bone, so custom models may need offset adjustments. Then the mob vanishes, no death animation, dropping its normal loot (`/mobbattle killLoot off`: no drops), after `/mobbattle killDelay` (0.5 s by default; players and other non-NPCs die at once). The disintegration effect does not block its damage.
  - Summon Wand: mobs appear on the floor under them (also under roofs), close to you (`/mobbattle summonRadius`, by default it grows with how many), inside an area drawn like the Chaos Wand one, each with a summon burst and its model's own spawn animation when it has one (e.g. skeletons rising from the ground); adding a type to the list flashes the mob green with the burst ring (as Heal) and an aura, removing it does the same in red.
  - Cleanse Wand: the same burst in light blue, with a white-blue flash; on a morphed mob the Morph Wand blue swirl also plays as it gets its model back.
  - Effect Wand: the same burst in purple when the effect lands.
  - Morph Wand: copying (right click) and transforming both flash the mob like the Cleanse Wand, in blue (blue tint + the burst ring in blue). The transform burst also plays in the world at the mob, since the model swap can drop particles attached to the old model. Applying the model the mob already has does nothing: an error flash (grey-red tint, fizzle, error sound) and a chat message.
- **Battle end:** `X WINS!`, `RED TEAM WINS!`, `Duel won`, `DRAW!`, or for the Chaos Wand left click (everyone vs one mob) `X WINS!` (it survived) / `X IS DEFEATED!`.
- **Independent battles:** two team battles can use the same names and colors without sharing targets, damage, members or winners. Each battle keeps the team names and colors it started with. Allies never damage each other. With `/mobbattle battleIsolation` on (default for every kind) NPCs from outside the battle cannot damage its participants either. At the end, the mod clears its forced target/hostility and returns compatible NPCs to Idle.
- **Victory timing:** no minimum fight duration. Winners and the 15-minute safety timeout are checked once per second per world, even without NPC combat ticks. A single surviving side wins; several surviving sides at the deadline produce DRAW. The winner title may wait for an already visible title to finish.

## Command: `/mobbattle`

Settings are saved per world (`Saves/<world>/mods/Dackoro_Mob Battle/fx.properties`) and survive restarts. Every word is a real subcommand, so the chat suggests the next word as you type (`/mobbattle te` shows `teams`, `teamLook`). Values go right after the setting (`/mobbattle areaRadius 20`); the `--value=20` style is not needed.

Commands are written in camelCase in help (`areaRadius`, `teamLook onlyWand`) to read better; any case works (`arearadius` too). The chat suggestions always show them in lowercase: the game lowercases every command name. Old command names still work as aliases: `show`=`settings`, `title`=`startTitle`, `style`=`titleStyle`, `laser`=`fightLaser`, `color`=`fightLaserColor`, `radius`=`areaRadius`, `area`=`areaStyle`, `height`=`areaHeight`, `effect`=`wandEffect`, `teamfx`=`teamLook`, `isolation`=`battleIsolation`.

| Command | What it does |
|---|---|
| `/mobbattle help [topic]` | Without a topic: the list of topics. Topics: `wands`, `titles`, `chaos`, `battles`, `teams`, `summon`, `morph`; each shows its commands one per line. In Creative, joining a world shows a one-line hint about it (once per session). |
| `/mobbattle settings` | Show the current settings, one line per group. After changing a setting, only its group line is shown. |

### Titles
| Command | What it does |
|---|---|
| `/mobbattle titleScope owner\|nearby\|world` | Who sees the big fight titles (countdown, start, winner): `owner` only the player who started the fight, `nearby` (default) every player in the same world within 64 blocks, `world` every player in that world. Each player has their own title queue, so fights of different players never cancel each other's titles: start titles are merged, winner titles all play. A countdown number shows at once and replaces whatever title that player had on screen. |
| `/mobbattle countdown <seconds>` | Countdown before any fight starts (duel, everyone vs one, battle royale, teams): a `3`, `2`, `1` title, one per second, then the fight and its start title. 0-10; `0` (default) starts at once. The chat also counts `[DUEL: A vs B] 3`, `2`, `1`. Several countdowns can run at once, like fights without one; a new order cancels only the countdowns that share a mob with it, and the big titles follow your newest countdown. If mobs die during the countdown the fight starts without them, or is cancelled. |
| `/mobbattle startTitle on` / `off` | Fight-start title on/off. |
| `/mobbattle winTitle on` / `off` | Winner title (`X WINS!`, `DRAW!`) on/off. The chat message always shows. |
| `/mobbattle titleStyle <name>` | Title style: `Default`, `Major` (default), `VoidEviction`. `GoblinBreach` is not offered: the client draws it tiny at the top of the screen; a saved `GoblinBreach` becomes `Major`. |
| `/mobbattle titleStyle random` | Random style each fight. |

### Chaos Wand area and fighter laser
Changing `areaRadius`, `areaStyle`, `areaColor`, `areaOnlyWand` or `areaHeight` draws the area around you as a preview.

| Command | What it does |
|---|---|
| `/mobbattle fightLaser on` / `off` | Laser line between fighters at fight start (default off). Team rings always show. |
| `/mobbattle fightLaserColor #RRGGBB` / `random` | Color of the fighter laser, e.g. `#2EFFD3`, or a random vivid color each fight. |
| `/mobbattle areaRadius <2-430>` | Area radius in blocks (default 60). Bigger radii search a much larger sphere on every Chaos Wand click; up to 430 is allowed. Existing worlds keep their saved radius; use `/mobbattle areaRadius 60` to update it. Only loaded mobs are considered. |
| `/mobbattle areaStyle floor\|zone\|glow\|both\|all\|off` | How the area is shown, for 6 seconds. `floor`: a translucent flat disc of color (no outlines) on the ground under the center, like boss attack zones. `zone`: the disc plus a see-through cylinder wall up to `areaHeight`. `glow`: columns of rising light about 20 blocks tall, about every 2 blocks of the edge (max 120), visible over hills and trees. `both` (default): `floor` + `glow`. `all`: `zone` + `glow`. `off`: no marker. Worlds saved with a removed laser style switch to `both`. |
| `/mobbattle areaOnlyWand on` / `off` | `off` (default): everyone sees the area for 6 s. `on`: only you see it, and only while you hold the Chaos Wand (like the Team Wand rings); switching item hides it within about a second, holding the wand again shows your last area again. A new area replaces the previous one. |
| `/mobbattle areaColor <color>` / `random` | Area color from the palette: red (default), blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta. `random` picks one each time. This is separate from `/mobbattle fightLaserColor`, which only colors the fighter laser. Black, gray and brown glow use normal blending, so they really look dark. |
| `/mobbattle areaHeight <3-128>` | Height in blocks of the `zone` wall (default 30). |
| `/mobbattle maxMobs <2-200>` | Max mobs per Chaos Wand click and per summon (default 200). Above ~60 the server may lag. Mobs left out are reported in chat. |

The `floor` / `zone` disc is flat and sits on the ground under the center (even if you are flying): on slopes it goes into hills and floats over dips. `glow` columns start on the top collision surface in loaded terrain (including roofs and tree tops); unloaded parts use the center height, without loading chunks. Mob selection uses the 3D radius around the clicked mob or player, so height differences affect which mobs are included.

### Battle rules
| Command | What it does |
|---|---|
| `/mobbattle battleIsolation <duel\|allVsOne\|royale\|teams\|all> on\|off` | Per battle kind. `on` (default for all): the battle's mobs only hurt their opponents, mobs outside it (wild mobs, other battles) can't hurt them, and their hits on players are cancelled. `off`: outside mobs can join in and battle mobs can hurt players and other mobs. Players can always hit battle mobs. Kinds: `duel` (Battle Wand), `allVsOne` (Chaos Wand left click, everyone vs one), `royale` (Chaos Wand right click), `teams` (Team Wand). `all` sets every kind; a bare `on`/`off` means `all`. |
| `/mobbattle duelMode exclusive\|accumulate` | Battle Wand: `exclusive` (default) replaces a mob's duel, `accumulate` merges connected duels (see Notes). |
| `/mobbattle debug` | Combat snapshots of the 4 nearest NPCs and a 10 s hit log (see Notes). Changes nothing. |
| `/mobbattle friendlyFire <allVsOne\|teams\|all> on\|off` | Hits between allies of the same battle. `allVsOne`: the attackers ganging up on one mob (default `on`: stray hits and area attacks hurt each other). `teams`: teammates (default `off`: their hits on each other are cancelled). Either way allies never pick each other as targets. Duels and royales have no allies. If the game itself cancels a hit (for example between mobs of the same pack), it stays cancelled. |

### Teams
| Command | What it does |
|---|---|
| `/mobbattle teams auto` | Put every mob within the radius into teams following the team rules (e.g. all skeletons red, all goblins green). |
| `/mobbattle teams start` | Start the team battle (same as Team Wand right click on a mob). |
| `/mobbattle teams clear` | Empty your teams and remove their team look. |
| `/mobbattle teams clearAll` | Empty every player's teams and remove their team look. |
| `/mobbattle teams list` | Show the teams and their colors. Defaults: RED, BLUE, GREEN, YELLOW. |
| `/mobbattle teams add <name> <color>` | Add a team with a custom name (up to 14), e.g. `/mobbattle teams add Undead purple`. Fixed colors only: red, blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta. Custom hex colors are disabled. |
| `/mobbattle teams color <team> <color>` | Choose a color from the fixed palette. |
| `/mobbattle teams rename <team> <name>` | Change only the team's name, e.g. `/mobbattle teams rename RED Orcs`. Names use letters, numbers and underscores (max 16), and must be unique. Members, color and summon rules stay with the team. |
| `/mobbattle teams remove <team>` | Remove a team, its draft/battle members, team effects and summon rules. At least 2 teams stay. |
| `/mobbattle teams reset` | Back to the 4 default teams; clears current team drafts and team battle membership. |
| `/mobbattle teamLook both\|tint\|mist\|off` | Team look, visible to everyone: `tint` colors the whole mob, `mist` adds a mist of the team color (like the Enrage effect), `both` = tint + mist. Default `tint` (no mist). Removed when the battle ends or the mob gets a new order. |
| `/mobbattle teamLook time <seconds>\|infinite` | How long the team look stays after a mob joins a team, e.g. `5` to see the color only for a moment, then the mob looks normal again. `infinite` (default) = the whole battle. Max 600. For no look at all use `teamLook off`. |
| `/mobbattle teamLook onlyWand on` / `off` | `off` (default): the team look shows as soon as a mob joins a team. `on`: the look shows only while the team's owner holds the Team Wand, like the team rings, and goes away within about a second when they switch item. Everyone sees it while it is on (it is an effect on the mob). |
| `/mobbattle summon team <role> <team>\|none` | Team rule for a mob type, used by summons and `teams auto`. |

All team colors use prebuilt effects, with no runtime asset loading. Older saved custom colors automatically become the closest fixed palette color for rings, chat, tint and mist; team names and slots are preserved.

### Summon
| Command | What it does |
|---|---|
| `/mobbattle summonRadius <1-64>` / `auto` | Size of the summon area in blocks. `auto` (default) grows with how many mobs: about 4 blocks for a few, ~19 for 100, never more than half of `areaRadius`. The area is drawn with the same `areaStyle` and `areaColor` as the Chaos Wand area. |
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
| `/mobbattle killLoot on` / `off` | Kill Wand: the disintegrated mob drops the loot a normal death would (its drop list, plus picked-up items). Default on. With `killDelay 0` the mob dies normally and always drops its loot. |
| `/mobbattle wandEffect <id>` | Effect Wand effect: any loaded effect ID, including other mods' (e.g. `Potion_Morph_Frog`). |
| `/mobbattle morph <model>` | Morph Wand model by ID, e.g. `/mobbattle morph Frog_Green`. |
| `/mobbattle morph list [text]` | Search model IDs, e.g. `/mobbattle morph list skeleton` (first 40). |
| `/mobbattle morph reset` | Give loaded morphed mobs in your current world/instance their own models back. Originals from other instances remain available when you return there. |

### Reset

| Command | What it does |
|---|---|
| `/mobbattle stop` | End every battle in progress (duels, Chaos Wand and team battles, no winner) and cancel pending countdowns. |
| `/mobbattle army clearAll` | Disband every player's army. |
| `/mobbattle resetAll` | `stop` + `teams clearAll` + `army clearAll` + `morph reset` in one command. |

Defaults: countdown 0, titleScope owner, duelMode exclusive, fightLaser off, fightLaserColor `#FF4B3E`, startTitle on, winTitle on, titleStyle `Major`, areaRadius 60, areaStyle both, areaColor red, areaOnlyWand off, battleIsolation on (all kinds), friendlyFire on for allVsOne / off for teams, areaHeight 30, maxMobs 200, summonRadius auto, wandEffect `Burn`, wandFx on, killDelay 0.5, killLoot on, teamLook tint, teamLook time infinite, teamLook onlyWand off.

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
