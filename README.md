# Mob Battle

A Hytale mod by **Dackoro**: wands that make mobs fight using their existing AI. Supports vanilla NPCs and modded NPCs with compatible combat behavior; player-only attacks and encounter-specific mechanics may need adaptations.

Patchline: **pre-release** (`ServerVersion >=0.7.0-pre <0.8.0`).

## Items

Get all nine at once with `/mobbattle give`, or individually from the creative inventory or with `/give <item ID>`. The command gives one of each using Hytale's normal inventory placement and requires the same permission as `/give` for yourself. If some do not fit, it reports which ones were not delivered.

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
| `MobBattle_KillWand` | Kill Wand | Kill the entity instantly (any amount of health). | — |
| `MobBattle_EffectWand` | Effect Wand | Give the entity the selected effect (default `Burn`). | Next effect: Burn, Poison_T2, Freeze, Slow, Stun, Root, Vulnerable, Ability_Enrage_Buff, Potion_Health_Regen, Immune. |
| `MobBattle_CleanseWand` | Cleanse Wand | Remove every effect from the entity and undo a morph. | — |

### Notes
- A passive mob (no attack) only runs away. The aggressive one still attacks.
- Boss roles with a `Main` combat substate (such as `Bear_Voidtaken`) enter that substate when ordered to fight. Abilities tied to players or a specific Hunt site may still require their original encounter.
- Mobs of the same species and the same pack also hurt each other.
- A new target replaces the old one: the mob ignores its previous target for 30 s.
- Fights last up to 10 minutes, or until one of the two dies.
- The opening "provocation" hit is 0.01 damage and is healed back 0.3 s later.
- The Chaos Wand affects at most the **40 nearest** mobs, and one summon attempts to spawn at most that many too (`/mobbattle maxmobs`). Failed spawns also count toward the limit.
- Summoned mobs appear in a ring 3 blocks or more around you; the list is saved with the settings.
- While you hold the Team Wand, each team's mobs show a ring in the team color at their feet (only you see it).
- Duels (Battle Wand) and group battles (royale and teams) announce the winner with a title. They stop after 15 minutes if nobody wins.
- **The newest combat order wins:** a fight order or team assignment on mobs that are in a battle royale or team battle pulls only those mobs out of it; the rest keep fighting. If fewer than 2 sides are left, the battle ends without a winner title. Healing, effects and morphs do not cancel a fight. Mobs don't rejoin a battle on their own.
- A trork's pet (same flock) joins its owner's side in group battles.
- **Morphs** work like the vanilla frog potion, but have no time limit. A morph is saved with the world, while the original model is only remembered while the server runs: use `/mobbattle morph reset` or the Cleanse Wand before leaving the world, or the mob keeps the new look. Animations of the new model may not match the old role's attacks.

### Effects
- **Fight start (Battle Wand, Chaos Wand, Team Wand):** a big title (`FIGHT!`, `EVERYONE vs X`, `BATTLE ROYALE!`, `TEAM BATTLE!`) and, with `/mobbattle laser on`, a laser linking each mob to its first opponent (team color in team battles).
- **Titles:** each lasts about 3 s. Repeated fight-start titles keep only the newest pending one. Every victory is queued in order and takes priority over pending starts; a new fight cannot overwrite a victory. Winner titles fall back to Major if the selected style is GoblinBreach.
- **Chaos Wand area:** for 6 s, in the area color (`/mobbattle areacolor`): columns of light about 20 blocks tall along the edge plus a translucent floor and see-through wall like a boss attack zone (`both`, default), or one of them (`/mobbattle area`, `/mobbattle height`).
- **Team look:** tint and/or a mist in the team color (`/mobbattle teamfx`).
- **Battle end:** `X WINS!`, `RED TEAM WINS!`, `Duel won` or `DRAW!`.
- **Independent battles:** two team battles can use the same names and colors without sharing targets, damage, members or winners. Each battle keeps the team names and colors it started with. Allies never damage each other. With `/mobbattle isolation on` (default) NPCs from outside the battle cannot damage its participants either. At the end, the mod clears its forced target/hostility and returns compatible NPCs to Idle.
- **Victory timing:** no minimum fight duration. Winners and the 15-minute safety timeout are checked once per second per world, even without NPC combat ticks. A single surviving side wins; several surviving sides at the deadline produce DRAW. The winner title may wait for an already visible title to finish.

## Command: `/mobbattle`

Settings are saved per world (`Saves/<world>/mods/Dackoro_Mob Battle/fx.properties`) and survive restarts. Every word is a real subcommand, so the chat suggests the next word as you type (`/mobbattle te` shows `teams`, `teamfx`). Values go right after the setting (`/mobbattle radius 20`); the `--value=20` style is not needed.

| Command | What it does |
|---|---|
| `/mobbattle show` | Show the current settings. |

### Titles
| Command | What it does |
|---|---|
| `/mobbattle title on` / `off` | Fight-start title on/off. |
| `/mobbattle wintitle on` / `off` | Winner title (`X WINS!`, `DRAW!`) on/off. The chat message always shows. |
| `/mobbattle style <name>` | Title style: `Default`, `Major` (default), `GoblinBreach`, `VoidEviction`. Note: `GoblinBreach` shows nothing on 0.7.0-pre.5.1 (even with vanilla `/eventtitle`). |
| `/mobbattle style random` | Random style each fight (skips `GoblinBreach`). |

### Laser and area (Chaos Wand)
Changing `radius`, `area`, `areacolor` or `height` draws the area around you as a preview.

| Command | What it does |
|---|---|
| `/mobbattle laser on` / `off` | Laser line between fighters at fight start (default off). Team rings always show. |
| `/mobbattle color #RRGGBB` / `random` | Color of the fighter laser, e.g. `#2EFFD3`, or a random vivid color each fight. |
| `/mobbattle radius <2-430>` | Area radius in blocks (default 430; diameter 860). Existing worlds keep their saved radius; use `/mobbattle radius 430` to update it. Only loaded mobs are considered. |
| `/mobbattle area floor\|zone\|glow\|both\|off` | How the area is shown, for 6 seconds. `floor`: a translucent flat disc on the ground at the center height, like boss attack zones. `zone`: the disc plus a see-through cylinder wall up to `height`. `glow`: columns of rising light about 20 blocks tall, about every 2 blocks of the edge (max 120), visible over hills and trees. `both` (default): `zone` + `glow`. `off`: no marker. Worlds saved with a removed laser style switch to `both`. |
| `/mobbattle areacolor <color>` / `random` | Area color from the palette: red (default), blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta. `random` picks one each time. |
| `/mobbattle height <3-128>` | Height in blocks of the `zone` wall (default 30). |
| `/mobbattle maxmobs <2-200>` | Max mobs per Chaos Wand click and per summon (default 40). Above ~60 the server may lag. |

The `floor` / `zone` disc is flat: on slopes it goes into hills and floats over dips. `glow` columns start on the top collision surface in loaded terrain (including roofs and tree tops); unloaded parts use the center height, without loading chunks. Mob selection uses the 3D radius around the clicked mob or player, so height differences affect which mobs are included.

### Battle rules
| Command | What it does |
|---|---|
| `/mobbattle isolation on` / `off` | `on` (default): mobs in a duel, royale or team battle only hurt their opponents, and mobs outside the battle (wild mobs, other battles) can't hurt them; their hits on players are cancelled too. `off`: outside mobs can join in and battle mobs can hurt players and other mobs. Allies of the same battle never hurt each other, and players can always hit battle mobs. |

### Teams
| Command | What it does |
|---|---|
| `/mobbattle teams auto` | Put every mob within the radius into teams following the team rules (e.g. all skeletons red, all goblins green). |
| `/mobbattle teams start` | Start the team battle (same as Team Wand right click on a mob). |
| `/mobbattle teams clear` | Empty your teams and remove their team look. |
| `/mobbattle teams list` | Show the teams and their colors. Defaults: RED, BLUE, GREEN, YELLOW. |
| `/mobbattle teams add <name> <color>` | Add a team with a custom name (up to 14), e.g. `/mobbattle teams add Undead purple`. Fixed colors only: red, blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta. Custom hex colors are disabled. |
| `/mobbattle teams color <team> <color>` | Choose a color from the fixed palette. |
| `/mobbattle teams rename <team> <name>` | Change only the team's name, e.g. `/mobbattle teams rename RED Orcs`. Names use letters, numbers and underscores (max 16), and must be unique. Members, color and summon rules stay with the team. |
| `/mobbattle teams remove <team>` | Remove a team, its draft/battle members, team effects and summon rules. At least 2 teams stay. |
| `/mobbattle teams reset` | Back to the 4 default teams; clears current team drafts and team battle membership. |
| `/mobbattle teamfx both\|tint\|mist\|off` | Team look, visible to everyone: `tint` colors the whole mob, `mist` adds a mist of the team color (like the Enrage effect), `both` (default). Removed when the battle ends or the mob gets a new order. |
| `/mobbattle teamfx time <seconds>\|infinite` | How long the team look stays after a mob joins a team, e.g. `5` to see the color only for a moment, then the mob looks normal again. `infinite` (default) = the whole battle. Max 600. For no look at all use `teamfx off`. |
| `/mobbattle summon team <role> <team>\|none` | Team rule for a mob type, used by summons and `teams auto`. |

All team colors use prebuilt effects, with no runtime asset loading. Older saved custom colors automatically become the closest fixed palette color for rings, chat, tint and mist; team names and slots are preserved.

### Summon
| Command | What it does |
|---|---|
| `/mobbattle summon` | Summon the list around you (same as the Summon Wand right click). Mobs with a team rule spawn already in their team. |
| `/mobbattle summon list` | Show the summon list. |
| `/mobbattle summon add <role> [count\|random] [team]` | Add a mob type, e.g. `/mobbattle summon add Skeleton 5`, `/mobbattle summon add Trork_Warrior random blue`. Role IDs are case sensitive. |
| `/mobbattle summon remove <role>` / `clear` | Remove one type / empty the list. |
| `/mobbattle summon modify <role> <count\|random>` | Change the count of an existing entry, preserving its team and list order. Affects future summons. Example: `/mobbattle summon modify Skeleton 10`. |
| `/mobbattle summon random <min> <max>` | Range for `random` counts (default 1-5). Both ends are bounded to 0-100 and reversed ranges are reordered. |
| `/mobbattle summon atleastone true\|false` | `true`: every `random` type spawns at least 1 (default). `false`: it may spawn 0. |

### Effects and morph
| Command | What it does |
|---|---|
| `/mobbattle effect <id>` | Effect Wand effect: any loaded effect ID, including other mods' (e.g. `Potion_Morph_Frog`). |
| `/mobbattle morph <model>` | Morph Wand model by ID, e.g. `/mobbattle morph Frog_Green`. |
| `/mobbattle morph list [text]` | Search model IDs, e.g. `/mobbattle morph list skeleton` (first 40). |
| `/mobbattle morph reset` | Give loaded morphed mobs in your current world/instance their own models back. Originals from other instances remain available when you return there. |

Defaults: laser off, color `#FF4B3E`, title on, win title on, style `Major`, radius 430, area both, areacolor red, isolation on, height 30, maxmobs 40, effect `Burn`, teamfx both, teamfx time infinite.

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

Without `-PhytaleServerJar` the build uses `com.hypixel.hytale:Server` from `https://maven.hytale.com/release` (release patchline). The jar ends up in `build/libs/` and bundles `manifest.json`, `README.md` and `Server/`, so assets and plugin ship as one file.

`deploy.ps1` (Windows) builds, waits until the game releases the jar (exit to the main menu) and copies it into the pre-release Mods folder.

## Game assets

This repository contains **no Hytale game files**. The wands reuse the vanilla wood wand model and icon **by path** (`Items/Weapons/Wand/Wood.blockymodel`), and the game provides them at runtime. The fight titles are the client's built-in `EventTitleStyle` banners; the mod only picks which one to show.

## License

TBD.
