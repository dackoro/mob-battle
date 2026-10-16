# Server setup

This guide is for people running Dackoro's Mob Battle on a server: who may use and configure it, where its settings live, the admin and debug tools, and how to start battles from the server console.

Commands are shown with a leading `/`. Leave it out if your server console does not use one. `/mobbattle help server` prints a short in-game version of this page.

For the settings themselves see [settings.md](settings.md) and [settings-panel.md](settings-panel.md).

## Permission modes

Mob Battle has two permission modes:

| Mode | Behavior |
|---|---|
| `open` (default) | Anyone can use the wands and change settings, teams, summon lists and presets. |
| `server` | Wand use and configuration are checked against permission nodes (below). |

Admin actions **always** need a real `dackoro.mob_battle.admin` grant, in both modes (the Debug Wand too). That includes switching the mode. Switch it from the server console or as an admin:

```text
/mobbattle permissions server
/mobbattle permissions open
```

The mode is saved as `permissions.mode=open|server` in `fx.properties` (see [Settings file](#settings-file)).

### Permission nodes

| Node | Grants | Default group |
|---|---|---|
| `dackoro.mob_battle.use` | Using the wands, starting battles from player commands, and personal tools. | `hytale:Adventurer` |
| `dackoro.mob_battle.config` | Changing settings, presets, resets, team and summon lists. | `hytale:Admin` |
| `dackoro.mob_battle.admin` | Server-wide and destructive actions, debug logging, server events. Checked in both modes. | `hytale:Admin` |

Mob Battle registers these defaults with the server, so in `server` mode an ordinary Adventurer can use the wands but can't change settings or run admin commands. The server console has every permission.

### What each command needs

**No permission (always available):**
- `help`, `settings`, `config` / `panel`. Anyone can open the settings panel and read it.
- The read-only queries `teams list`, `summon list`, `morph list` and `preset list`.
- Personal exits: `watch exit` and `skipCamera`. Q in Watch mode also works without a node. This way a player can always leave a spectator view or a camera.

**`use` (only checked in `server` mode):**
- Every wand action, except the two listed under `config` below and the Debug Wand (`admin`).
- `chaos me`, `snap`, `watch`, `debug` (a short combat snapshot of nearby NPCs).
- `summon` / `summon spawn` / `summon go` (summon the list now).
- `teams auto|start|join|leave|clear` (your own Team Wand teams).
- `morph` commands other than `list` and `reset`.

**`config` (only checked in `server` mode):**
- Every setting command, `preset save|load|delete|<name>` and `reset <group>`.
- `teams add|color|rename|remove` and the other team list edits.
- Summon list edits (`summon add`, `remove`, `clear`...).
- Two wand clicks that edit saved lists rather than cast something. These need both `use` and `config`:
  - the Summon Wand left click (adds or removes a mob type in the summon list);
  - the Effect Wand right click (picks the next effect).
- Saving in the settings panel.

**`admin` (checked in both modes):**
- `permissions open|server`, `reload`, `give`.
- `stop`, `resetAll`, `skipTimer`.
- `army clearAll`, `teams clearAll`, `teams reset`, `morph reset`.
- `apply` (wand actions on chosen players or mobs, see below).
- `measure [look|id:<uuid>|last] [seconds]` (`measure stop` ends it early with its summary): for 10 s (up to 60) the chat shows the speed (blocks/s) and height above the start of you, of the mob you look at, or of any entity by its id (this form works from the server console), every second, then the cruising speed (the steady seconds, without starting and stopping), the top speed, the average while moving, the distance, the best jump (from where it took off) and the highest point. It starts by saying the size and speed buff of what it measures and the speed they should give (square root of the size × speed buff). For a player it also shows the base speed and jump force the server sent, against the game's defaults. Useful to check size and speed buffs on a measured track.
- `debugLog`, `debugRecord`, `debugDump`.
- `event duel|royale|focus|list`.
- `test ...`, the test tools below.
- `eyeTest` (experimental).
- The Debug Wand (both clicks).
- The settings panel's Admin page.

### Test tools (admin, console too)

`/mobbattle test` works with coordinates and entity ids, so the server console can run it without a player looking at anything. Every reply also goes to the server log as `[test] ...`. `last` is the newest mob these tools spawned; `apply` and `measure` also take `id:<uuid>` or `last`.

| Command | What it does |
|---|---|
| `test spawn <role> <here\|x y z> [count] [world] [calm]` | Spawns test mobs (up to 50) and prints their ids. `calm` puts them in the calm set as summons (needs `summonCalm summons\|all`). |
| `test list` / `test clear` | The test mobs still alive, with ids, position and health / removes them all. |
| `test chase <id\|last> <here\|x y z>` | Puts a still training dummy there and the mob runs to attack it: a way to move any mob that can attack from A to B (e.g. to measure its speed). |
| `test walk <id\|last> <x> <z>` | A path to that block, as the game's `/npc path`. Only the few roles with a path behaviour follow it (mostly bosses). |
| `test duel <roleA> <roleB> <distance> <here\|x y z> [world]` | Spawns both facing each other, starts a duel, logs every hit (damage, cause, distance between centers and between body edges) and ends with a summary: winner, time, hits, damage and reach of each side. |
| `test royale <role:count+...> <here\|x y z> <radius> [world]` | Spawns the mix (up to 200) and starts a battle royale there; a summary when it ends. |
| `test wave <heal\|cleanse\|effect\|morph\|shuffle> <here\|x y z> [world] [effect\|model]` | A wand's held-click wave from that spot with the current settings (no player needed): how many it reached, and when the ring got to the first and the last. `morph` needs a model. |
| `test roles [mod:<name>\|mod:hytale] [type:<fighters\|defend\|noattack\|avoid>] [text]` | The mob roles the settings panel's **Show** filters keep, with their mod and type (read from each role's definition, a guess), to pick mobs for tests by what they are. |
| `test verify <here\|x y z> [mod:..] [type:..] [text] [time:<s>] [batch:<n>]` | Checks the **Type** guesses with real hits: each role (Avoid ones skipped) fights a copy of itself in a silent Mob Battle duel (no countdown, camera or titles), 24 pairs at a time (`batch:`, up to 60) in a grid centred on the spot, 12 blocks apart (stand near it so those chunks are loaded). Only its copy can hurt it (area attacks of other pairs and loose mobs are cancelled); anything left near a pair after its turn (a summoned pet, a tentacle) is removed. Fighters and Defend only should land a hit, No attack should not. Mismatches go to chat, every result to `tests/verify_<time>.txt`. It tests whether a mob can fight, not whether it starts fights on its own. (A still training dummy was used before: mobs with the intelligent combat AI never take it as an enemy.) |
| `test speed <role> <here\|x y z> [sizes like 1+2+4]` | How fast a mob runs at each size: one lane per size side by side, each mob runs 25 blocks along +X to a still dummy (inside a zombie's 28-block sight), all at once. Reports the time, the average and the top speed over half a second per lane, in chat and `tests/speed_<time>.txt`. |
| `test hit <player> <id\|last> [damage]` | That player hits the mob through the normal damage pipeline (as a sword hit, default 1 damage), so the Army Wand's owner's order can be tested from the console. |
| `test tick [world]` | The world's average and highest tick time over the last 10 seconds, to read while a big battle runs. |
| `test calls <here\|x y z> [mod:..] [type:..] [text] [time:<s>] [batch:<n>] [watchers:<n>]` | Every role that fights duels a copy of itself while onlookers of the same kind stand 6 blocks away. Fails when a group call (goblins, Trork, skeletons...) pulls an onlooker into the duel or a fighter off its opponent. Report in `tests/calls_<time>.txt`. |
| `test wand <owner> <duel\|focus\|brawl\|kill\|snap> [target] [target2]` | Does what that player's wand click would do, with the same checks: targets are player names or mobs (`last`, `id:<uuid>`). Tests battles with players from the console. |
| `test wipe <here\|x y z> [radius]` | Cleans a test field: removes every entity that is not a player (mobs, dropped items, projectiles) within the radius (default 96). Unlike `sudo <player> entity clean`, it runs from the console without a player. |
| `test jump <role> <here\|x y z> [sizes like 1+2+3] [time:<s>]` | How high a mob climbs at each size: builds steps 1 to 8 blocks high (3x3 platforms, 8 blocks apart along +X) with a still dummy on each, then one mob per step and size, with the size buff, is ordered at it. Reports the highest step it stood on per size and puts the blocks back after. Mobs only jump when their path needs it, which is why it uses steps. Report in `tests/jump_<time>.txt`. |
| `test ai <here\|x y z> [mod:..] [type:..] [text]` | Reads each role's AI: every role the filters keep is spawned for one tick and removed. It reports the chase Mob Battle uses to keep far fighters walking in (state, range) and the soft leash ranges that would send a fighting mob home. Mods' mobs too. Fighters with no chase found go to chat, every role to `tests/ai_<time>.txt`. |
| `test stats <id\|last>` | One line: health, size, buffs, effects, battle and team, calm, army, model. |
| `test expect <id\|last> <field> <op> <value>` | A check, ✅ or ❌ in the log; a script counts them in its summary. Fields: `alive`, `health`, `maxhealth`, `healthpct`, `size`, `buff.<size\|health\|damage\|defense\|speed>`, `effect` (has an effect whose id contains the value; `!=` = hasn't), `battle` (none or the kind), `team`, `calm`, `army`, `morphed`, `model`, `x`, `y`, `z`. Operators `= != > < >= <=`. |
| `test camera <orbit\|duel\|showcase\|teams\|focus\|drone\|spotlight> <player> [seconds]` | That intro camera over the living test mobs, for that player, whatever the camera settings say. |
| `test snapshot [name]` / `test restore [name]` | Saves `fx.properties` (settings, teams and summon list) to `tests/snapshots/<name>.properties` and puts it back (as `/mobbattle reload`). Start a script with a snapshot and end it with a restore, so tests never keep their changes. |
| `test perf <role> <count> <here\|x y z> [world]` | The world's tick time (average and longest over 10 s, against the budget) before and after spawning up to 300 mobs. |
| `test run <script>` / `test stop` | Runs `<plugin data dir>/tests/<script>.txt`: one command per line (with or without `/`), `wait <seconds>`, `say <text>`, `#` comments. It runs as whoever started it. |

Example script:

```
# smoke.txt
mobbattle test snapshot before
mobbattle test spawn Goblin_Scrapper 200 124 300
mobbattle apply last speed x2
mobbattle test chase last 200 124 330
mobbattle measure last 8
wait 9
mobbattle test expect last buff.speed = 100
mobbattle test duel Skeleton_Soldier Trork_Warrior 6 215 124 300
wait 40
mobbattle test clear
mobbattle test restore before
```

### Assigning a custom group in `permissions.json`

Hytale's permission provider reads `permissions.json`, which has `userPermissions`, `groupData` and `userGroups` fields. Merge entries like these into the existing file instead of replacing it. Replace the example UUID with the player's UUID.

```json
{
  "userPermissions": {},
  "groupData": {
    "MobBattleUsers": {
      "parent": "hytale:Adventurer",
      "permissions": ["dackoro.mob_battle.use"]
    }
  },
  "userGroups": {
    "00000000-0000-0000-0000-000000000000": ["MobBattleUsers"]
  }
}
```

This group inherits the normal Adventurer permissions and adds wand use. Because it inherits `hytale:Adventurer`, its members would also get `use` from the default grant. A group that does not inherit from Adventurer needs `dackoro.mob_battle.use` explicitly.

The example doesn't grant `config` or `admin`. Give those nodes only to the groups or users who should have them. Inheriting `hytale:Admin` grants full administrator access, so limit it to trusted operators.

## Settings panel

`/mobbattle config` (or `/mobbattle panel`) opens the settings window for any player. Anyone can read it. In `server` mode, **Save changes** needs `dackoro.mob_battle.config`. The list buttons (teams, summon list) and the Tools buttons run the matching command as the player, so the same permissions apply. The panel is described in [settings-panel.md](settings-panel.md).

### Admin page

The Admin page appears only for players with a real `dackoro.mob_battle.admin` grant, in either mode. It holds:

| Section | Contents |
|---|---|
| Server log | **Debug log** and **Debug recorder** toggles (see [Debug tools](#debug-tools)). Only admins can change them. |
| Permissions | The current mode, with **Open** and **Server** buttons. |
| Server | **Reload** (reads `fx.properties` again and drops unsaved changes in the form), **Give wands**, **End timers** (every running timer ends now by its time-up rule), **Royale event** (an ownerless royale around you, using the area radius and max mobs), **Save debug record**. |
| Measure | **Measure**: your speed and jump for 10 s in chat (`/mobbattle measure`); closes the panel so you can move. |
| Apply to players or mobs | Who, action and value, then **Apply**: the same as `/mobbattle apply` (see below). |
| Clean up | **Stop all**, **Disband armies**, **Clear all teams**, **Undo morphs**, **Reset all**. These ask for a second click to confirm. |

### Wand actions on players and mobs (`apply`)

`/mobbattle apply <who> <action> [value]` does what a wand click does, on chosen players or mobs, without the wand. It is for testing settings quickly and for running a server. Admin only (a real `dackoro.mob_battle.admin` grant, in both permission modes). The Admin tab of the settings panel has the same thing as one row.

| Who | Reaches |
|---|---|
| `player <name>` (or just the name) | That online player, in any world. With `player`, the chat suggests the names. |
| `me` | You. |
| `look` | The mob or player you are looking at (up to 64 blocks). |
| `near <radius>` | Every mob and player within that many blocks of you, not you. |
| `players` | Every player in your world. |

| Action | What it does |
|---|---|
| `buff <clicks>` | Sets the Buff Wand stacks (0 = takes them off), as that many clicks. |
| `size\|health\|damage\|defense <value>` | A direct amount, in the buff unit (`x3`, `200`, `50%`); 0 = off. No shimmer or crown. It adds to the other buffs and lasts until Cleanse, `unbuff`, death or a restart. |
| `unbuff` | Takes every buff off (Buff Wand, battle buffs and direct amounts), but not the effects. |
| `heal` | Full health, as the Heal Wand. |
| `cleanse` | As the Cleanse Wand: effects, buffs and morph go. |
| `effect <id>` | Gives an effect, as the Effect Wand (`A>B` gives B when A ends). |
| `look jade\|shimmer[:color]` | Puts a jade or shimmer look on. |
| `morph <model\|player>` | Puts a model, or a player's current look, on the target. |
| `unmorph` | Its own model back. |
| `army add\|remove` | Puts a mob in your army or takes it out (mobs only). |
| `team <team\|none>` | Puts the target in one of your Team Wand teams, or takes it out. |

The chat suggests the targets, the actions and their usual values (effects of the Effect Wand's quick list, teams, x2/x3...). The reply says what was applied, e.g. `size x3 (+200%)` or `2/5 stacks (size +20% (x1.2), ...)`. There is no kill or snap on purpose. Examples: `/mobbattle apply Steve size x3`, `/mobbattle apply near 20 effect Burn`, `/mobbattle apply look team Red`.


**Reset all** stops everything and clears teams, buffs, armies and marks. Settings stay.

## Settings file

Mob Battle keeps its settings in `fx.properties` inside the plugin's data directory on the server. Saved presets are stored as `presets/<name>.properties` in the same directory, and debug files go in `debug/`.

- Run `/mobbattle settings` to see the settings in effect.
- You can edit `fx.properties` by hand while the server is stopped.
- To apply a hand-edited file while the server runs, an admin can run `/mobbattle reload`. A file that can't be read is reported, and the current settings are kept.
- Every setting change (command, panel, preset, reset) writes the file right away.

### Security keys

Three settings decide who can do what or what gets logged:
- `permissions.mode`;
- `debug` (`debugLog`);
- `debug.record` (`debugRecord`).

These are kept out of everything that bulk-changes settings, so loading a preset can never quietly open the server or switch logging on:
- Built-in presets, saved presets and `reset <group>` never change them.
- Saved presets never store them.
- The panel's Undo never restores the permission mode.
- The two debug toggles are admin-only fields, so they only show up in an admin's form.

To change them, use the admin commands (`permissions`, `debugLog`, `debugRecord`), the Admin page, or edit the file and `reload`.

## Debug tools

These tools help admins investigate a battle that went wrong. Files are written to `debug/` inside the plugin data directory.

| Tool | Permission | What it does |
|---|---|---|
| `/mobbattle debugLog on\|off` | admin | Writes `[debug]` lines to the server log: battle start and rules, wall events, target choices, time-up scores, tie breaks, winners, snaps and rejected duels. Saved setting, default off. |
| `/mobbattle debugRecord on\|off` | admin | Keeps the last 5 minutes of Mob Battle's log and `[debug]` lines in memory, up to 20,000 lines. Saved setting, default off. |
| `/mobbattle debugDump` | admin | Writes what the recorder kept to `debug/record_<time>.txt`, with the current settings at the top. The same action is **Save debug record** on the Admin page. Useful when a bug is noticed only after it happened. |
| Debug Wand (`MobBattle_DebugWand`) | admin | Left click a mob for a full report: identity, model, stats, effects, battle, buffs and AI. The report goes to chat (short), the server log and `debug/<role>_<time>.txt`. It then follows the mob for `debugTrack` seconds (default 30, `0` = report only) and adds its changes and every hit to the same file. Right click stops following. |
| `/mobbattle debug` | use | A combat snapshot of the 4 nearest NPCs within 20 blocks, plus a hit log for 10 seconds. |

`debugTrack <seconds>` (0–600) is an ordinary setting and needs `config`.

**Debug log details.** With `debugLog on`, each line starts with `[debug]`: the settings when it is turned on; each battle start (kind, fighters and sides, arena mode, radius, center, timer, time-up rule, tie break, isolation, friendly fire); arena pushes and teleports (one line per mob every 5 s, with its count); the timer reaching 0; each side's health % and damage at time up; tie breaks; the winner; rejected duels and snaps; and target choices (who each mob goes for, defends itself against, gives up on, or ignores).

**Debug recorder details.** `debugRecord` is saved with the world's settings like every other setting, so it stays on in that world across restarts until you turn it off; another world has its own value. The recorded lines themselves live only in memory, and it records even with `debugLog off`, without writing anything until `debugDump`.

**`/mobbattle debug` details.** It changes nothing. It takes read-only combat snapshots of the four nearest NPCs within 20 blocks, now and after 2 and 4 seconds: role and entity index, AI state, alive / frozen / attacking, battle identity, ordered vs locked target, distance, attitude and the vanilla damage permission. For 10 seconds it also logs up to 100 battle hit events involving those NPCs, including whether vanilla had cancelled the hit before Mob Battle restored it; this does not confirm the final damage after later systems. The snapshots go to chat and to the world's server log. A false `vanillaCanDamage` alone does not prove the final hit is blocked, because Mob Battle overrides that filter for opponents.

### Experimental: eyeTest

| Command | What it does |
|---|---|
| `/mobbattle eyeTest [<color>]` | (admin) Recolors the eyes of the nearest mob within 8 blocks. The new texture is generated at runtime, in memory, from the mob's own texture, and registered as a common asset so connected clients receive it. Only the eye shapes are recolored. Palette color (same names as team colors), default `Purple`. |
| `/mobbattle eyeTest off` | Puts the original model back on every mob that `eyeTest` changed in your world. The generated textures stay registered. |

## Server events

Admins can start NPC battles that have no player owner, from the console or in game. The `event` commands need `dackoro.mob_battle.admin`.

```text
/mobbattle event duel <uuidA> <uuidB> [world]
/mobbattle event royale <x> <y> <z> <radius> [maxMobs] [world]
/mobbattle event focus <uuid> [world]
/mobbattle event list [radius]
```

- `world` accepts a world name or UUID. When you leave it out, a player's command uses that player's current world and a console command uses the server's default world.
- `duel` starts a duel between two different living NPCs, given by their UUIDs.
- `royale` gathers the living NPCs within `radius` of the center and starts a battle royale.
  - `radius` is 1–2048 blocks.
  - `maxMobs` caps the fighters (2–100000) and defaults to the configured maximum (`maxMobs` setting).
  - To give a world without `maxMobs`, put the world name or UUID right after `radius`. A whole number in that position is read as `maxMobs`.
  - At least two living NPCs must be in the area.
- `focus` starts an everyone-versus-one battle with the given NPC as the lone target. The attackers are the living NPCs within the configured Chaos area radius, capped at the configured max mobs.
- `list` only works for a player. It lists living NPCs near you with their UUID and name, so you can target them:
  - the default radius is 32 blocks and the accepted range is 1–128;
  - at most 20 are shown, with a note when there are more.

Every accepted event replies with its event ID. A rejected one replies with the reason, such as an unknown world, a dead or missing NPC, or not enough NPCs.

Plugins can start the same events from code. See [plugin-api.md](plugin-api.md).

### How ownerless events are shown

An event has no owner, so viewers are chosen around its world and center:
- `titleScope nearby` (the default) reaches players within 64 blocks.
- `world` reaches the whole world.
- `owner` falls back to the same 64-block range.
- `cameraScope` uses the same rules and defaults to `owner`.

Countdown, titles, cameras, timers and music follow the usual settings for the battle kind (see [battles.md](battles.md)).

### Known limitations

- Ownerless events don't draw the fighter laser. Its emitter must be a player, and an NPC emitter hasn't been verified.
- How ownerless events look in game (viewers, titles, cameras, timers, music) has had limited testing so far.
- Vanilla commands may not print NPC UUIDs. Use `/mobbattle event list` to find them.
