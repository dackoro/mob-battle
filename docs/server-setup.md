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
- `debugLog`, `debugRecord`, `debugDump`.
- `event duel|royale|focus|list`.
- `eyeTest` (experimental).
- The Debug Wand (both clicks).
- The settings panel's Admin page.

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
| Clean up | **Stop all**, **Disband armies**, **Clear all teams**, **Undo morphs**, **Reset all**. These ask for a second click to confirm. |

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
  - `radius` is 1–512 blocks.
  - `maxMobs` caps the fighters (2–200) and defaults to the configured maximum (`maxMobs` setting).
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
