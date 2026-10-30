# Settings reference

Every Mob Battle setting: the chat command that changes it, its key in `fx.properties`, the values it takes, its default and the reset group it belongs to. The settings are grouped like the tabs of the in-game [settings panel](settings-panel.md), which can change all of them without typing commands.

## Basics

### Where settings are stored

- Settings live in **`fx.properties`** in the plugin's data directory (a folder named `Dackoro_Mob Battle`; in a single-player world it is inside that world's save, under `mods/`). Each world or server therefore has its own settings.
- Every change from a command or from the panel's **Save changes** is written to the file at once, and the file is read when the plugin starts.
- The same directory holds your saved presets (`presets/<name>.properties`) and debug files (`debug/`).
- You can edit `fx.properties` by hand while the server is stopped, or edit it and then run **`/mobbattle reload`** (admin). Reload reads the whole file again: a key missing from the file goes back to its default, a value that isn't valid keeps the current one.
- Files from older versions load fine: old values are converted (an old free laser color becomes the closest palette color, old area styles become `border`, one old global isolation/friendly-fire value becomes the value of every kind, an old `fx.crown=true/false` becomes `Gold`/`off`).

### Commands

- `/mobbattle settings` shows the current settings, one line per group (Titles, Fighter laser, Battles, Arenas, Chaos area, Team look, Wands, Permissions). After you change a setting, the reply shows only its group's line.
- `/mobbattle help [topic]` lists the commands by topic (`wands`, `titles`, `chaos`, `battles`, `teams`, `summon`, `morph`, `presets`, `config`, `server`).
- Commands are written in camelCase here (`areaRadius`), but any case works; the chat suggestions show them in lowercase (the game lowercases every command name).
- Every word is a real subcommand, so the chat suggests the next word as you type (`/mobbattle te` shows `teams`, `teamLook`).
- The value goes right after the setting: `/mobbattle areaRadius 20`. The `--value=20` style is not needed.
- Only the full names work. The old short names `show`, `title`, `style`, `laser`, `color`, `radius`, `area`, `height`, `effect`, `teamfx`, `isolation` and `glow` (the old name of `borderColor`) were removed.
- In Creative, joining a world shows a one-line hint about `/mobbattle help` and the settings panel, once per session.
- On/off values accept `on`/`off`, `true`/`false` and `yes`/`no`.
- Times (`timer`, `arenaShrinkTime`) accept `90`, `90s`, `2m`, `1m30s`, `--minutes=2 --seconds=30` or `off`.
- Changing settings needs `dackoro.mob_battle.config` when the permission mode is `server` (anyone in `open` mode). See [server-setup.md](server-setup.md).

### Palette colors

Every color setting takes a palette name: `red`, `blue`, `green`, `yellow`, `orange`, `purple`, `pink`, `cyan`, `white`, `black`, `brown`, `lime`, `gray`, `magenta`. Free hex colors are not accepted. Some settings also take `random`, `off`, `gold`, `area` or `default`, as noted. See [colors-and-looks.md](colors-and-looks.md).

### Battle kinds

Per-kind settings use these words: `duel` (Battle Wand), `allVsOne` (Chaos Wand left click), `royale` (Chaos Wand right click), `teams` (Team Wand), or `all` for every kind at once. In `fx.properties` the kind is lowercase: `duel`, `allvsone`, `royale`, `teams`. See [battles.md](battles.md).

## Per-kind overrides

Fourteen presentation options and the two tie breaks have a **global value** and an optional **override per battle kind**:

| Command | Sets |
|---|---|
| `/mobbattle <option> <value>` | The global value. |
| `/mobbattle <option> <kind> <value>` | An override for that kind only. |
| `/mobbattle <option> <kind> default` | Removes that kind's override (`global` works too): the kind follows the global value again. |
| `/mobbattle <option> all <value>` | The global value, and removes every kind's override for that option. |

Examples: `/mobbattle countdown royale 5`, `/mobbattle cameraStyle royale showcase`, `/mobbattle camera duel off`, `/mobbattle endCamera all winner`.

By default no kind has an override: every kind follows the global value.

Overrides are saved as `kind.<kind>.<option>` with the option word in lowercase, for example `kind.royale.countdown=5` or `kind.duel.camera=off`. On/off overrides are stored as `on`/`off`. In the panel they are the Presentation, Cameras and Ties sections of each battle-kind tab, where **Global** (or an empty number box) means no override. `/mobbattle settings` lists them after "per kind".

| Option | Global key | Override key | Override values | Reset group |
|---|---|---|---|---|
| `countdown` | `fight.countdown` | `kind.<kind>.countdown` | 0-10 | titles |
| `readyTime` | `fight.ready` | `kind.<kind>.readytime` | 0-60 | titles |
| `startTitle` | `title.enabled` | `kind.<kind>.starttitle` | on, off | titles |
| `winTitle` | `wintitle.enabled` | `kind.<kind>.wintitle` | on, off | titles |
| `music` | `battle.music`, `battle.music.track` | `kind.<kind>.music` | on (global track), off, or a track ID | titles |
| `fightLaser` | `laser.enabled` | `kind.<kind>.fightlaser` | on, off | effects |
| `runes` | `fx.runes` | `kind.<kind>.runes` | on, off | effects |
| `areaStyle` | `chaos.area` | `kind.<kind>.areastyle` | border, disc, discBorder, wall, off | effects |
| `camera` | `fight.camera` | `kind.<kind>.camera` | on, off | cameras |
| `vsScreen` | `camera.vsscreen` | `kind.<kind>.vsscreen` | on, off | cameras |
| `cameraStyle` | `camera.style` | `kind.<kind>.camerastyle` | auto, orbit, duel, showcase, teams, focus, drone, spotlight | cameras |
| `cameraTime` | `camera.time` | `kind.<kind>.cameratime` | 0-600 (above 30 warns) | cameras |
| `cameraShots` | `camera.shots` | `kind.<kind>.camerashots` | 1 to maxMobs | cameras |
| `endCamera` | `camera.end` | `kind.<kind>.endcamera` | off, winner, lastHit | cameras |
| `endCameraTime` | `camera.end.time` | `kind.<kind>.endcameratime` | 1-600 (above 15 warns) | cameras |
| `autoWatch` | `camera.autowatch` | `kind.<kind>.autowatch` | on, off | cameras |
| `cameraBars` | `camera.bars` | `kind.<kind>.camerabars` | off, slim, classic, wide | cameras |
| `cameraBlur` | `camera.blur` | `kind.<kind>.camerablur` | off, soft, strong | cameras |
| `autoFilm` | `camera.autofilm` | `kind.<kind>.autofilm` | on, off | cameras |
| `tieBreak` | `battle.tiebreak` | `kind.<kind>.tiebreak` | damage, kills, random, draw | arenas |
| `killTieBreak` | `battle.killtiebreak` | `kind.<kind>.killtiebreak` | damage, alive, first, shared | arenas |

The battle rules, arena and timer settings (isolation, arena, timer and so on) are not overrides: they always have one value per kind, with no global value. `all` sets every kind.

## Presets

A preset changes many settings at once. In the panel, loading a preset only fills the form; with the command it applies at once.

| Command | What it does |
|---|---|
| `/mobbattle preset list` | Built-in presets and your saved ones. |
| `/mobbattle preset <name>` | Apply a built-in or saved preset (same as `preset load <name>`). |
| `/mobbattle preset save <name>` | Save the current settings as your own preset. |
| `/mobbattle preset load <name>` | Apply a saved preset. |
| `/mobbattle preset delete <name>` | Delete a saved preset. |

### Built-in presets

| Preset | What it sets |
|---|---|
| `default` | Every setting back to its default and every per-kind override removed. |
| `clean` (old name `fight`) | Only what the wands do: no countdown, VS screen, intro or end camera, duel or all vs one card, scoreboard, titles, music, laser, runes, area drawing, crown, waves, border glow or mob looks; every timer off; arenas that were on keep only their invisible wall. The wands' own effects (smoke, soul rip, dust, swirls) and the team colors stay. A closing royale arena is still drawn: use `arenaShrink royale off` to hide it. |
| `cinematic` | The show without numbers: 8 seconds to get in position (`readyTime`), VS screen, countdown 5, intro camera (`auto`), the battle film for the camera viewers (`autoFilm`), end camera `winner` (`lastHit` for royale and all vs one), slow motion x4 for 3 s on the final blow, `cameraBars wide`, titles and music; no duel or all vs one card, scoreboard, countdown in chat, laser, runes or border glow; area look, waves and arenas at their defaults. Mob looks and the crown stay. |
| `spectator` | Like a shooter's spectator mode: VS screen, the camera viewers watch the battle from its start (`autoWatch`: a fighter followed, the killer next when it falls, a click changes fighter, `watchTransition glide`), a scoreboard for every kind, end camera `winner`, slow motion x4 for 3 s on the final blow, titles and music. |
| `stats` (old name `effects`) | The `spectator` preset without the watch, plus every number on screen: the duel and all vs one cards, a scoreboard for every kind, the countdown in chat too; duels, all vs one and teams get a 2 minute timer with `timeUp health`, the royale keeps its 3 minutes and `judge`; VS screen, end camera `winner` and slow motion x4 for 3 s on the final blow, titles and music. |
| `debug` | Command only. Everything visible for testing: no countdown or cameras, fighter laser (only this preset turns it on), runes, area `wall`, team look `both` (tint and mist on team members), the duel card, the all vs one card and every scoreboard; every arena drawn (`show`, or `on` where it had a wall). |

Every built-in preset except `default` first puts the presentation settings back to their defaults (countdown, cameras, film, watch, slow motion, titles, music, laser, area look, runes, crown, waves, border colors, mob looks, duel card, all vs one card, scoreboards) and removes every per-kind override, then applies its own values. Settings it doesn't name (area radius, max mobs, battle rules, buffs, wands and so on) stay as they are.

### Saved presets

- Saved in the plugin data directory as `presets/<name>.properties`. Names use letters, digits, `-` and `_` (up to 32) and can't be a built-in name.
- A saved preset holds every setting **except** the team list, the summon list and the security settings (permission mode, debug log, debug recorder). Loading one keeps your current teams and summon list, and replaces every per-kind override with the preset's own.
- The panel's **Save preset** button saves the form, including changes you have not saved yet.

**No preset or reset ever changes** the permission mode, the debug log or the debug recorder, nor the team list and summon list. Only their own commands change them.

## Reset groups

`/mobbattle reset <group>` puts only one group of settings back to default. In the panel, the reset buttons on the Presets & reset tab fill the form instead.

| Group | What it covers |
|---|---|
| `effects` | Fighter laser and its color, runes, area look, color, height and "only with the wand", waves, border colors, mob looks, lone-target crown, team look (mode, time, only with the wand), army tint and color, wand effects, Cleanse/Buff/Heal Wand colors and lights, look glow, death glow, summon jade and its color; per-kind `fightLaser`, `runes`, `areaStyle`. |
| `cameras` | Intro camera, camera style, time, showcase shots, who sees the cameras, end camera and its time, auto watch; their per-kind overrides. |
| `titles` | Countdown, time to get in position, start and winner titles, title style, who sees the titles, battle music and track; per-kind `countdown`, `readyTime`, `startTitle`, `winTitle`, `music`. |
| `arenas` | Arena, timer, at time up, judge by, closing arena (per kind, plus its final radius and time), scoreboard, arena size, timer box, tie break, unstick, stand-off shove, passive win and its "never ends against" list, duel card, all vs one card. |
| `rules` | Isolation, self defense, friendly fire, players can fight, battle PvP, duel mode, area radius, max mobs, team by type, every buff setting, summon area, random counts, at least one, summons leave, lifetime, how they leave, summons join my army. |
| `wands` | Heal Wand wave and reach, Effect Wand effect, its wave and reach, Kill Wand (soul rip time, cast hit delay, loot, players), Snap Wand (mode, share, time, hit delay, reach, players), Cleanse Wand wave and its reach, Morph Wand (players, held-click waves and their reach), Debug Wand follow time. |
| `all` | Everything (the same as `preset default`). |

Teams, the summon list and the security settings are never reset. The reset group of each setting is also in the tables below.

## All settings

Defaults are the values the plugin starts with when `fx.properties` doesn't set them.

### Presentation

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Countdown | `/mobbattle countdown <seconds>` | `fight.countdown` | 0-10, 0 = start at once | 3 | titles |
| Countdown in chat | `/mobbattle countdownChat on\|off` | `fight.countdown.chat` | on, off | off | titles |
| Time to get in position | `/mobbattle readyTime <seconds>\|off` | `fight.ready` | 0-60, 0 = none. Seconds to get to your spot before the VS screen and the countdown; crouch to start at once. Never when you fight in the battle yourself | 0 | titles |
| Start title | `/mobbattle startTitle on\|off` | `title.enabled` | true, false | on | titles |
| Winner title | `/mobbattle winTitle on\|off` | `wintitle.enabled` | true, false | on | titles |
| Title style | `/mobbattle titleStyle <style>` | `title.style` | `Default`, `Major`, `GoblinBreach`, `VoidEviction`, `random` | `Major` | titles |
| Who sees the titles | `/mobbattle titleScope owner\|nearby\|world` | `title.scope` | owner, nearby (`scopeRadius` blocks), world | nearby | titles |
| How far "nearby" reaches | `/mobbattle scopeRadius <8-1024>` | `title.scope.radius` | blocks, for titleScope, cameraScope and vsScope | 64 | titles |
| Battle music | `/mobbattle music on\|off\|<track>` | `battle.music` | true, false | on | titles |
| Music track | `/mobbattle music <track>` | `battle.music.track` | any music container ID | `MobBattle_Battle_Music` | titles |
| Fighter laser | `/mobbattle fightLaser on\|off` | `laser.enabled` | true, false | off | effects |
| Laser color | `/mobbattle fightLaserColor <color>\|random` | `laser.color` | palette color (stored as its hex) or `random` | red (`#FF4B3E`) | effects |
| Summoning runes | `/mobbattle runes on\|off` | `fx.runes` | true, false | on | effects |

`music <track>` also turns the music on. Default: `MobBattle_Battle_Music` (the game's goblin boss battle song, louder and looping). The game's own `Track_Z1D_Goblin_Boss_Battle` is quiet and plays once, so naming it plays `MobBattle_Battle_Music` instead. Other game battle tracks: `Track_Void_Escalation`, `Track_Void_Tension`.

### Cameras

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Intro camera | `/mobbattle camera on\|off` | `fight.camera` | true, false | on | cameras |
| VS screen | `/mobbattle vsScreen on\|off` | `camera.vsscreen` | true, false | on | cameras |
| VS fighters per team | `/mobbattle vsTeamReps 1\|2` | `camera.vs.teamreps` | 1, 2 (3-4 teams; 5+ always 1) | 2 | cameras |
| Camera style | `/mobbattle cameraStyle <style>` | `camera.style` | auto, orbit, duel, showcase, teams, focus, drone, spotlight | auto | cameras |
| Camera time | `/mobbattle cameraTime <seconds>` | `camera.time` | 0-600, 0 = as long as the countdown (above 30 warns: viewers have no controls meanwhile) | 0 | cameras |
| Close-up shots (showcase, spotlight) | `/mobbattle cameraShots <n>` | `camera.shots` | 1 to maxMobs | 4 | cameras |
| Who sees the cameras | `/mobbattle cameraScope owner\|nearby\|world` | `camera.scope` | owner, nearby, world | owner | cameras |
| Who sees the VS screen | `/mobbattle vsScope owner\|nearby\|world` | `camera.vs.scope` | owner, nearby, world; players fighting always see it | nearby | cameras |
| Game HUD while filming | `/mobbattle cameraHud hide\|show` | `camera.hud` | hide, show | hide | cameras |
| Timer in the film bands | `/mobbattle bandTimer off\|top\|bottom` | `camera.bands.timer` | off, top, bottom | top | cameras |
| Title in the film bands | `/mobbattle bandTitle off\|top\|bottom` | `camera.bands.title` | off, top, bottom | top | cameras |
| Rows when they share a band | `/mobbattle bandRows 1\|2` | `camera.bands.rows` | 1, 2 | 2 | cameras |
| Fighters left in the film bands | `/mobbattle bandInfo off\|top\|bottom\|box` | `camera.bands.info` | off, top, bottom, box | bottom | cameras |
| End camera | `/mobbattle endCamera off\|winner\|lastHit` | `camera.end` | off, winner, lasthit | winner | cameras |
| End camera time | `/mobbattle endCameraTime <seconds>` | `camera.end.time` | 1-600 (above 15 warns) | 5 | cameras |
| Auto watch | `/mobbattle autoWatch on\|off` | `camera.autowatch` | true, false | off | cameras |
| Watch: changing fighter | `/mobbattle watchTransition cut\|glide` | `camera.watch.transition` | cut, glide | glide | cameras |
| Watch: next fighter every | `/mobbattle watchAutoSwitch <seconds>\|off` | `camera.watch.autoswitch` | 0 (off), 3-600 | 0 | cameras |
| Slow motion after the final blow (times slower) | `/mobbattle endSlowMo <times>\|off` | `camera.slowmo.times` | 0 (off), 2-20 | 0 | cameras |
| Slow motion lasts | `/mobbattle endSlowMoTime <seconds>` | `camera.slowmo.time` | 1-20 | 3 | cameras |
| Film bands | `/mobbattle cameraBars off\|slim\|classic\|wide` | `camera.bars` | off, slim, classic, wide | classic | cameras |
| Background blur | `/mobbattle cameraBlur off\|soft\|strong` | `camera.blur` | off, soft, strong | soft | cameras |
| Auto film | `/mobbattle autoFilm on\|off` | `camera.autofilm` | true, false | off | cameras |
| Shots of you | `/mobbattle filmPlayerShots <0-100>` | `camera.film.player` | 0-100 (percent, 0 = only the battle) | 5 | cameras |
| Far wide shots | `/mobbattle filmWideShots <0-100>` | `camera.film.wide` | 0-100 (percent, +15 with a mob over 4 blocks tall) | 30 | cameras |
| Close-ups face the mob | `/mobbattle cameraFace on\|off` | `camera.face` | true, false | on | cameras |
| Shot length | `/mobbattle filmShotTime <seconds>` | `camera.film.shot` | 3-60 | 6 | cameras |
| Shot length variation | `/mobbattle filmShotVariation <0-90>` | `camera.film.variation` | 0-90 (percent) | 25 | cameras |
| Camera pace | `/mobbattle filmPace <25-300>` | `camera.film.pace` | 25-300 (percent) | 100 | cameras |
| Between shots | `/mobbattle filmTransition cut\|glide` | `camera.film.transition` | cut, glide | cut | cameras |
| Film shots | `/mobbattle filmShot <shot> <0-300>` (`filmShots <list>` = those on, the rest off; `filmShots default` = all back to default; panel: Defaults button) | `camera.film.weight.<shot>` | 0-300 percent of normal per shot: wide, track, arc, pushin, closeup, landscape, playerback, playerclose, zenith, spiral, telephoto, descent, groundrise, profile | 100; spiral 80, telephoto and descent 70, groundrise 50, zenith 40, landscape 10 | cameras |
| Film style | `/mobbattle filmStyle default\|battle\|action\|cinematic` (panel: the four buttons above the film shots) | (sets every `camera.film.*` key) | battle = fight always in view; action = close on the fighters; cinematic = open, slow, gliding shots | default | cameras |
| Duel card | `/mobbattle duelCard on\|off` | `battle.duelCard` | true, false | on | arenas |
| All vs one card | `/mobbattle focusCard on\|off` | `battle.focusCard` | true, false | on | arenas |

Not settings: `/mobbattle skipCamera` ends your running camera, `/mobbattle watch [exit]` spectates a battle, `/mobbattle film [exit]` films one.

### Battles (every kind)

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Battle PvP | `/mobbattle battlePvp on\|off` | `battle.pvp` | true, false | off | rules |
| Unstick stalled fighters | `/mobbattle unstick on\|off` | `battle.unstick` | true, false | on | arenas |
| Stand-off shove | `/mobbattle standoffTime <seconds>\|off` | `battle.standoff` | 0-3600, 0 = never | 15 | arenas |
| Win against passive mobs | `/mobbattle passiveWin <seconds>\|off` | `battle.passivewin` | 0-3600, 0 = never | 15 | arenas |
| Loot from battle deaths | `/mobbattle battleLoot on\|off` | `battle.loot` | on, off | off | arenas |
| Battle depth (below) | `/mobbattle battleBelow <blocks>\|off` | `battle.below` | 0-512 (0 = no limit) | 50 | arenas |
| Battle height (above) | `/mobbattle battleAbove <blocks>\|off` | `battle.above` | 0-512 (0 = no limit) | 40 | arenas |
| Arena vertical limit | `/mobbattle arenaVertical <blocks>\|off` | `battle.arena.vertical` | 0-512 (0 = no limit) | off | arenas |
| End when nobody fights | `/mobbattle idleEnd <seconds>\|off` | `battle.idleend` | 0-3600, 0 = never | 150 (2.5 min) | arenas |
| Longest battle | `/mobbattle battleMaxTime <minutes>\|off` | `battle.maxtime` | 0-10080 minutes, 0 = infinite (only a winner, idleEnd, passiveWin or `/mobbattle stop` ends it) | 15 | arenas |
| Never ends against | `/mobbattle passiveWinIgnore <role,role*,...>\|none` | `battle.passivewin.ignore` | Role IDs, comma separated, `*` = any ending, case-insensitive; empty/`none` = no exception | `Test_Dummy*` | arenas |
| Timer box | `/mobbattle timerStyle custom\|portal` | `battle.timer.style` | custom, portal | custom | arenas |
| Arena size (duels, teams) | `/mobbattle arenaSize <blocks>\|auto` | `battle.arena.size` | 0 = auto, or 8-1024 (above 128 warns) | auto (0) | arenas |
| Closing arena ends at | `/mobbattle arenaShrinkTo <blocks>` | `battle.shrink.to` | 4-1024 | 15 | arenas |
| Closing time without timer | `/mobbattle arenaShrinkTime <time>` | `battle.shrink.time` | 10 s to 24 h (seconds in the file) | 120 (2m) | arenas |
| Winner when even (Ties) | `/mobbattle tieBreak [kind] damage\|kills\|random\|draw` | `battle.tiebreak` | damage, kills, random, draw | damage | arenas |
| Kill table order (Ties) | `/mobbattle killTieBreak [kind] damage\|alive\|first\|shared` | `battle.killtiebreak` | damage, alive, first, shared | damage | arenas |

### Duel, All vs one, Royale, Team battles (per kind)

The presentation and camera overrides of these tabs are listed under [Per-kind overrides](#per-kind-overrides). These settings always have one value per kind (`<kind>` = `duel`, `allvsone`, `royale`, `teams` in the key; `all` in a command sets every kind; a bare `on`/`off` also means all).

| Setting (panel) | Command | Key | Values | Default (duel / allVsOne / royale / teams) | Reset group |
|---|---|---|---|---|---|
| Battle Wand duels (Duel tab) | `/mobbattle duelMode exclusive\|accumulate` | `duel.mode` | exclusive, accumulate | exclusive (duels only) | rules |
| Isolated | `/mobbattle battleIsolation <kind\|all> on\|off` | `battle.isolation.<kind>` | true, false | on / on / on / on | rules |
| Self defense | `/mobbattle selfDefense <kind\|all> on\|off` | `battle.selfdefense.<kind>` | true, false | on / on / on / on | rules |
| Friendly fire (All vs one, Team battles) | `/mobbattle friendlyFire <allVsOne\|teams\|all> on\|off` | `battle.friendlyfire.<kind>` | true, false | - / on / - / off | rules |
| Players can fight | `/mobbattle playerFights <kind\|all> on\|off` | `battle.players.<kind>` | true, false | on / on / on / on | rules |
| Arena | `/mobbattle arena <kind\|all> on\|show\|wall\|off` | `battle.arena.<kind>` | on, show, wall, off | off / on / on / on | arenas |
| Timer | `/mobbattle timer <kind\|all> <time>\|off` | `battle.timer.<kind>` | 0-86400 seconds (24 h), 0 = none | off / off / 3 min (180) / off | arenas |
| At time up | `/mobbattle timeUp <kind\|all> <rule>` | `battle.timeup.<kind>` | draw, kill, continue, health, judge | draw / draw / judge / draw | arenas |
| Judge by | `/mobbattle judgeBy <kind\|all> <score>` | `battle.judgeby.<kind>` | health, damage, kills, mix | mix for royale, health for the rest | arenas |
| Closing arena | `/mobbattle arenaShrink <kind\|all> on\|off` | `battle.shrink.<kind>` | true, false | off / off / on / off | arenas |
| Scoreboard | `/mobbattle scoreboard <kind\|all> on\|off` | `battle.scoreboard.<kind>` | true, false | off / off / off / on | arenas |

Creative players never fight, whatever Players can fight says.

### Chaos area

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Area look | `/mobbattle areaStyle <style>` | `chaos.area` | border, disc, discBorder, wall, off | border | effects |
| Area radius | `/mobbattle areaRadius <blocks>` | `chaos.radius` | 2-2048 (above 128 warns) | 60 | rules |
| Max mobs | `/mobbattle maxMobs <n>` | `chaos.maxmobs` | 2-100000 (more than 60 warns: it can lag the server) | 200 | rules |
| Area color | `/mobbattle areaColor <color>\|random` | `chaos.area.color` | palette color, random | Red | effects |
| Area height | `/mobbattle areaHeight <blocks>` | `chaos.height` | 3-512 (above 64 warns) | 30 | effects |
| Area only while holding the wand | `/mobbattle areaOnlyWand on\|off` | `chaos.area.onlywand` | true, false | off | effects |

`areaStyle` is also a per-kind override. A bigger `areaRadius` searches a much larger sphere on every Chaos Wand click, and only loaded mobs are considered. A world keeps the radius it saved, so a world saved before the current default keeps its old value until you set it (`/mobbattle areaRadius 60`). Above about 60 mobs (`maxMobs`) the server may lag. Changing `areaRadius`, `areaStyle`, `areaColor`, `areaHeight` or `areaOnlyWand` by command draws the area around you as a preview. `maxMobs` also caps one summon. The file also holds `chaos.area.styles=2`, an internal marker for the area style format; leave it as it is.

### Colors & looks

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Team look | `/mobbattle teamLook both\|tint\|mist\|off` | `team.fx` | both, tint, mist, off | tint | effects |
| Team look time | `/mobbattle teamLook time <seconds>\|infinite` | `team.fx.seconds` | 0-600, 0 = while in the team | 0 (infinite) | effects |
| Team look only with the Team Wand | `/mobbattle teamLook onlyWand on\|off` | `team.fx.onlywand` | true, false | off | effects |
| Army tint | `/mobbattle armyTint on\|off` | `army.tint` | true, false | off | effects |
| Army tint color | `/mobbattle armyTintColor <color>` | `army.tint.color` | palette color | Blue | effects |
| Army friendly fire | `/mobbattle armyFriendlyFire on\|off` | `army.friendlyfire` | true, false | off | rules |
| Weapon damage bonus | `/mobbattle equipWeaponDamage on\|off` | `equip.weapon.damage` | true, false | on | rules |
| Training dummies: health | `/mobbattle dummyHealth <hit points>` | `dummy.health` | 100-10000 (never under their own 100) | 100 | rules |
| Training dummies: extra damage | `/mobbattle dummyDamage <percent>` | `dummy.damage` | 0-9900 | 0 | rules |
| Training dummies: defense | `/mobbattle dummyDefense <percent>` | `dummy.defense` | 0-99 | 0 | rules |
| Training dummies: extra speed | `/mobbattle dummySpeed <percent>` | `dummy.speed` | 0-900 | 0 | rules |
| Training dummies: extra size | `/mobbattle dummySize <percent>` | `dummy.size` | 0-900 | 0 | rules |
| Mob runes: time between two casts of one rune | `/mobbattle runeCooldown <percent>` | `rune.cooldown` | 50-1000 (% of the rune's own cooldown; 100 = as a player) | 200 | rules |
| Crown on the lone target | `/mobbattle crown gold\|<color>\|off` | `fx.crown` | Gold, palette color, off | Gold | effects |
| Cleanse Wand color | `/mobbattle cleanseColor <color>` | `wand.cleanse.color` | palette color | White | effects |
| Look glow | `/mobbattle lookGlow <0-15>` | `fx.glow.look` | 0-15, 0 = off | 6 | effects |

**Waves and borders per action.** Actions: `summon` (Summon Wand), `snap` (Snap Wand), `slam` (Battle Wand crouch + click in the air), `allVsOne` and `royale` (Chaos Wand). `all` sets every action.

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Summon / Snap / Slam / All vs one / Royale wave | `/mobbattle wave <action\|all> <color\|off>` | `wave.summon`, `wave.snap`, `wave.slam`, `wave.allvsone`, `wave.royale` | palette color, off | summon Green, snap White, slam Purple, allVsOne Yellow, royale Purple | effects |
| Summon / Snap / Slam / All vs one / Royale border | `/mobbattle borderColor <action\|all> <color\|off\|area>` | `glow.summon`, `glow.snap`, `glow.slam`, `glow.allvsone`, `glow.royale` | palette color, off, area (= the area color) | summon Green, snap off, slam off, allVsOne Yellow, royale Purple | effects |

**Mob looks per role.** Roles: `duel` (duel fighters), `allVsOne` (the all vs one target), `attackers` (the all vs one attackers), `royale` (royale fighters), `all`.

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Duel fighters, All vs one target, All vs one attackers, Royale fighters | `/mobbattle mobFx <role\|all> <color\|default\|off>` | `mobfx.duel`, `mobfx.allvsone`, `mobfx.attackers`, `mobfx.royale` | palette color, default (the original look), off | default, default, default, Purple | effects |

### Buffs

Sources (`<who>`): `lone` (the all vs one target, for that battle), `streak` (per kill in a battle), `champion` (the winners of a battle), `wand` (the Buff Wand, per click). Stats: `size`, `health`, `damage`, `defense`. See [wands.md](wands.md) and [battles.md](battles.md).

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Size | `/mobbattle buff <who> size <percent>` | `buff.<who>.size` | 0-9900 | 0; wand 10 | rules |
| Buff values as | `/mobbattle buff unit percent\|multiplier` | `buff.unit` | percent, multiplier | percent | rules |
| Size limit | `/mobbattle buff sizeMax <percent>` | `buff.size.max` | 50-9900 (above 200 warns) | 200 (x3) | rules |
| Speed from size | `/mobbattle buff sizeSpeed off\|natural\|strong\|full` | `buff.size.speed` | off, natural, strong, full | natural | rules |
| Jump from size | `/mobbattle buff sizeJump off\|natural\|strong\|full` | `buff.size.jump` | off, natural, strong, full | natural | rules |
| Health | `/mobbattle buff <who> health <percent>` | `buff.<who>.health` | 0-99900 | 0; wand 20 | rules |
| Damage | `/mobbattle buff <who> damage <percent>` | `buff.<who>.damage` | 0-99900 | 0; wand 10 | rules |
| Defense | `/mobbattle buff <who> defense <percent>` | `buff.<who>.defense` | 0-99 | 0; wand 5 | rules |
| Speed | `/mobbattle buff <who> speed <percent>` | `buff.<who>.speed` | 0-4900 (above 300 warns) | 0 | rules |
| Heal when given | `/mobbattle buff <who> heal on\|off` | `buff.<who>.heal` | true, false | off; wand on | rules |
| Streak max kills | `/mobbattle buff streak maxKills <n>` | `buff.streak.maxkills` | 1-1000 | 5 | rules |
| Buff Wand max stacks | `/mobbattle buff wand maxStacks <n>` | `buff.wand.maxstacks` | 1-1000 | 5 | rules |
| Buff color | `/mobbattle buffColor <color>` | `wand.buff.color` | palette color | Purple | effects |
| Buffed mob look | `/mobbattle buffLook shimmer\|runes\|off` | `wand.buff.look` | shimmer (veins, eye flames, runes), runes (only eye flames and runes), off | shimmer | effects |
| Buff Wand light | `/mobbattle buffLight buff\|<color>\|off` | `wand.buff.light` | buff (= in the buff color), palette color, off | buff | effects |
| Buffed mobs' crown | `/mobbattle buff crown gold\|<color>\|off` | `buff.crown` | Gold, palette color, off | off | rules |

Speed from size: how much faster a size buff makes a mob or player. natural (default) = x4 runs twice as fast (realistic; measured in game: 11 -> 22 blocks/s running); strong = x4 is 2.8 times as fast; full = x4 is 4 times as fast (the steps match the ground, but it is hard to steer); off = no change. Jump from size works the same way for the jump: natural = the jump height grows with the size. The speed stat multiplies on top of either.

Size limit: the most extra size one mob or player can get, all sources together. Above 200 (x3) the command and the panel warn: big players don't fit under roofs or in caves, their camera can end up inside blocks, and runes and crowns can sink into big heads. The buff looks grow less than the body (with the square root of the size: x4 body, x2 runes).

Shortcuts: `/mobbattle buff <who> all <percent>` sets every stat at once, speed too (defense stops at 99, the size total at the size limit); `/mobbattle buff <who> off` sets them all to 0. `buff lone heal on` heals the all vs one target when it is picked, even with no lone buff.

### Wands

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Wand effects | `/mobbattle wandFx on\|off` | `wand.fx` | true, false | on | effects |
| Heal Wand light | `/mobbattle healLight <color>\|off` | `wand.heal.light` | palette color, off (heals at once) | Green | effects |
| Effect Wand effect | `/mobbattle wandEffect <id>` | `effect.id` | any loaded effect ID; a chain `A>B>C`; `jade[:color]`, `shimmer[:color]` | `Burn` | wands |
| Wand waves: hit as the ring arrives | `/mobbattle waveTravel on\|off` | `wand.wave.travel` | true, false | on | wands |
| Wand waves: ground only | `/mobbattle waveGroundOnly on\|off` | `wand.wave.groundonly` | true, false | off | wands |
| Battle waves: reach as the ring arrives | `/mobbattle battleWaveTravel on\|off` | `battle.wave.travel` | true, false | on | arenas |
| Battle waves: ground only | `/mobbattle battleWaveGroundOnly on\|off` | `battle.wave.groundonly` | true, false | off | arenas |
| Heal wave (hold left click) | `/mobbattle healArea on\|off` | `heal.area` | true, false | on | wands |
| Heal wave reach | `/mobbattle healRadius <blocks>` | `heal.area.radius` | 2-2048 (above 128 warns) | 12 | wands |
| Heal wave heals you | `/mobbattle healAreaSelf on\|off` | `heal.area.self` | true, false | on | wands |
| Effect wave (hold left click) | `/mobbattle effectArea on\|off` | `effect.area` | true, false | on | wands |
| Effect wave reach | `/mobbattle effectRadius <blocks>` | `effect.area.radius` | 2-2048 (above 128 warns) | 12 | wands |
| Effect wave hits you | `/mobbattle effectAreaSelf on\|off` | `effect.area.self` | true, false | off | wands |
| Soul rip time | `/mobbattle killDelay <seconds>` | `kill.delay.millis` | 0-3 s in the command; 0-3000 ms in the file and panel; 0 = instant | 0.5 s (500) | wands |
| Cast hit delay | `/mobbattle killHitDelay <ms>` | `kill.hitdelay` | 0-2000, 0 = on the click | 175 | wands |
| Drop loot | `/mobbattle killLoot on\|off` | `kill.loot` | true, false | off | wands |
| Death glow | `/mobbattle deathGlow <0-15>` | `fx.glow.death` | 0-15, 0 = off | 12 | effects |
| Works on players (Kill Wand) | `/mobbattle killPlayers on\|off` | `kill.players` | true, false | on | wands |
| Kill wave (held left click) | `/mobbattle killArea on\|off` | `kill.area` | true, false | on | wands |
| Kill wave reach | `/mobbattle killRadius <blocks>` | `kill.area.radius` | 1-2048 (above 128 warns) | 12 | wands |
| Kill wave takes players | `/mobbattle killAreaPlayers on\|off` | `kill.area.players` | true, false | off | wands |
| Snap mode | `/mobbattle snapMode global\|team` | `snap.mode` | global, team | global | wands |
| Share that turns to dust | `/mobbattle snapPercent <1-100>` | `snap.percent` | 1-100 | 50 | wands |
| Dust time | `/mobbattle snapTime <seconds>` | `snap.time` | 1-600 seconds | 5 | wands |
| Slam hit delay | `/mobbattle snapHitDelay <ms>` | `snap.hitdelay` | 0-2000 | 310 | wands |
| Snap reach | `/mobbattle snapRadius <blocks>` | `snap.radius` | 2-2048 (above 128 warns) | 60 | wands |
| Snap players | `/mobbattle snapPlayers on\|off` | `snap.players` | true, false | on | wands |
| Morph players | `/mobbattle morphPlayers on\|off` | `morph.players` | true, false | on | wands |
| Morph wave (hold left click) | `/mobbattle morphBurst on\|off` | `morph.burst` | true, false | on | wands |
| Cleanse wave (hold left click) | `/mobbattle cleanseArea on\|off` | `cleanse.area` | true, false | on | wands |
| Cleanse wave reach | `/mobbattle cleanseRadius <blocks>` | `cleanse.area.radius` | 2-2048 (above 128 warns) | 12 | wands |
| Cleanse wave cleanses you | `/mobbattle cleanseAreaSelf on\|off` | `cleanse.area.self` | true, false | off | wands |
| Swap wave (hold right click) | `/mobbattle morphShuffle on\|off` | `morph.shuffle` | true, false | on | wands |
| Morph wave reach | `/mobbattle morphBurstRadius <blocks>` | `morph.burst.radius` | 2-2048 (above 128 warns) | 12 | wands |
| Morph waves change you | `/mobbattle morphBurstSelf on\|off` | `morph.burst.self` | true, false | off | wands |
| Follow time (Debug Wand) | `/mobbattle debugTrack <seconds>` | `wand.debug.track` | 0-600, 0 = report only | 30 | wands |

`killHitDelay` (default 175: the hit at 0.175 s of the Kill Wand's cast animation) and `snapHitDelay` (default 310: frame ~30 of 60 at `Speed` 1.6 in `Server/Item/Animations/MobBattle_SnapWand.json`) line the effect up with the wand's animation; with `wandFx off` they are skipped and the action fires on the click. Raise one if the effect lands before the hit, lower it if after. If you change an animation or its `Speed`, retune with `delay = hit frame / 60 / Speed * 1000`. `snapRadius` is separate from `areaRadius`, so a big battle area does not make the snap reach farther.

The Effect Wand's right click also changes `effect.id` (it cycles a quick list). The Morph Wand model (`/mobbattle morph <model>`, or the wand's right click) is not a saved setting: it is per player and lasts until the server restarts.

### Teams

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Team by type | `/mobbattle teamByType on\|off` | `team.bytype` | true, false | off | rules |

The team list itself (names and colors, at most 14 teams, at least 2) is saved as `teams.list` and changed with `/mobbattle teams add|color|rename|remove|reset`. Default: RED, BLUE, GREEN, YELLOW. Presets and resets never change it. See [teams-summon-army.md](teams-summon-army.md).

### Summon list

| Setting (panel) | Command | Key | Values | Default | Reset group |
|---|---|---|---|---|---|
| Summon area | `/mobbattle summonRadius <blocks>\|auto` | `summon.radius` | 0 = auto, 1-512 (above 64 warns) | auto (0) | rules |
| Random count from / to | `/mobbattle summon random <min> <max>` | `summon.min`, `summon.max` | 0-100000 each (a reversed range is reordered; above 60 warns) | 1 / 5 | rules |
| At least one | `/mobbattle summon atLeastOne true\|false` | `summon.atleastone` | true, false | true | rules |
| Summons leave | `/mobbattle summonDespawn never\|battleEnd\|timer` | `summon.despawn` | never, battleEnd, timer | never | rules |
| Summon lifetime | `/mobbattle summonLifetime <seconds>` | `summon.lifetime` | 5-3600 | 60 | rules |
| How they leave | `/mobbattle summonExit disintegrate\|vanish\|shatter` | `summon.exit` | disintegrate, vanish, shatter | disintegrate | rules |
| Calm until a battle | `/mobbattle summonCalm off\|summons\|all` | `summon.calm` | off, summons, all | summons | rules |
| Summons frozen | `/mobbattle summonFrozen on\|off` | `summon.frozen` | true, false | off | rules |
| Summons join my army | `/mobbattle summonArmy on\|off` | `summon.army` | true, false | off | rules |
| Summons in jade | `/mobbattle summonJade on\|off` | `summon.jade` | true, false | off | effects |
| Summon jade color | `/mobbattle summonJadeColor <color>` | `summon.jade.color` | palette color | Green | effects |

The list itself is saved as `summon.list` (mob type and count) and `summon.teams` (team rules), and changed with `/mobbattle summon add|modify|remove|clear|team` or the Summon Wand's left click. It starts empty. Presets and resets never change it.

### Admin and security

These are changed only by their own commands (admin), never by presets, resets or saved presets.

| Setting (panel) | Command | Key | Values | Default |
|---|---|---|---|---|
| Debug log | `/mobbattle debugLog on\|off` | `debug` | true, false | off |
| Debug recorder | `/mobbattle debugRecord on\|off` | `debug.record` | true, false | off |
| Server feature: VS screen | `/mobbattle serverVs on\|off` | `server.vs` | true, false | on |
| Server feature: VS pictures sent | `/mobbattle serverVsPictures boot\|battle` | `server.vs.pictures` | boot, battle | battle |
| Server feature: Morph Wand hologram | `/mobbattle serverHolograms on\|off` | `server.hologram` | true, false | on |
| Server feature: panel mob pictures | `/mobbattle serverIcons on\|off` | `server.icons` | true, false | on |
| Permission mode (Admin tab buttons) | `/mobbattle permissions open\|server` | `permissions.mode` | open, server | open |

The server features turn a part of the mod off for the whole server: off, it does nothing, and from the next server restart its pictures are neither drawn nor sent to joining players (the VS screen's are the biggest download). See [server-setup.md](server-setup.md#picture-cache).

The panel can change the debug log, the recorder and the server features (admins only), but never the permission mode through the form. Related admin commands that are not settings: `/mobbattle debugDump` (write the recorder's lines to `debug/`), `/mobbattle reload`. See [server-setup.md](server-setup.md).
