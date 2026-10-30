# Teams, summons and armies

The wands that build groups of mobs: the **Team Wand** sets up team battles, the **Summon Wand** spawns a saved list of mobs, the **Army Wand** gives you mobs that follow and defend you. This page also covers the **Morph Wand** on players and the **Snap Wand**.

Every command here starts with `/mobbattle`. How battles themselves run (targets, rules, arenas, winners) is in [battles.md](battles.md); all settings and defaults are in [settings.md](settings.md); the panel is described in [settings-panel.md](settings-panel.md); colors, team looks and the jade look are in [colors-and-looks.md](colors-and-looks.md); every wand's clicks are listed in [wands.md](wands.md).

## Teams

### Team Wand

▶ [Watch it](https://youtu.be/jvpsDgs0lXM)

| Click | Effect |
|---|---|
| Left click a mob | Adds it to your current team. Click it again to remove it. With `playerFights teams on` (default), players can be added too. |
| Right click in the air | Switches to the next team. The chat says which team you are filling and how many mobs it has. |
| Right click a mob | Starts the team battle with the teams built so far. You need at least 2 teams with living members. |

- While you hold the Team Wand, each team's mobs show a solid ring in the team color at their feet. Only you see them. The rings are redrawn every 0.25 s, so a ring trails a moving mob by up to a quarter second.
- Each player builds their own teams. Team members are kept in memory only: they are gone after a server restart. (The team list itself, with names and colors, is saved.)
- **teamByType** (`teamByType on|off`, default off): clicking a mob also brings every mob of the same type within `areaRadius` into that team (taking them out of other teams). Clicking it again takes them all out. Players are never pulled in this way.
- `teams start` starts the battle like a right click on a mob.

### Team commands

| Command | What it does |
|---|---|
| `teams list` | Shows the teams and their colors. Defaults: RED, BLUE, GREEN, YELLOW. |
| `teams add <name> <color>` | Adds a team, e.g. `teams add Undead purple`. Up to 14 teams. |
| `teams color <team> <color>` | Changes a team's color. |
| `teams rename <team> <name>` | Changes only the name. Members, color and summon rules stay with the team. |
| `teams remove <team>` | Removes a team, its members, team looks and summon rules. At least 2 teams always remain. |
| `teams reset` | Back to the 4 default teams; clears team drafts and team battle membership. Admin. |
| `teams auto` | Puts every mob within `areaRadius` into teams: mobs with a summon team rule go to that team, the rest one team per type in team order (more types than teams: they share). With a single type and no rules, they are split evenly over the first two teams. |
| `teams start` | Starts the team battle. |
| `teams clear` | Empties your teams and removes their team look. |
| `teams clearAll` | Empties every player's teams. Admin. |
| `teams join <team>` / `teams leave` | You fight in that team yourself: you get its team look and, while you hold the Team Wand, its ring. `leave` takes you out of your team, or out of the team battle you are in. Needs `playerFights teams on`. |

**Names and colors:**

- Names use letters, numbers and `_`, up to 16 characters, and must be unique. They are shown in capitals (`Undead` becomes `UNDEAD`).
- Colors come from the fixed palette: red, blue, green, yellow, orange, purple, pink, cyan, white, black, brown, lime, gray, magenta. Two teams can't share a color (they would look the same in game).
- Older saved custom colors turn into the closest palette color (for rings, chat, tint and mist); team names and slots are kept.

**Team look:** team mobs can wear a tint and/or a mist in the team color (`teamLook both|tint|mist|off`, default `tint`), for a limited time (`teamLook time`) or only while the owner holds the Team Wand (`teamLook onlyWand`). See [colors-and-looks.md](colors-and-looks.md).

**In the settings panel**, the Teams page has teamByType, the team list (name and color of each team, which wait for **Save changes**; two teams can swap names or colors) and a gallery of your current Team Wand teams: one icon per mob type with how many ("x5").

## Summons

### Summon Wand

▶ [Watch it](https://youtu.be/M21iEYmvxqM)

| Click | Effect |
|---|---|
| Left click a mob | Adds its type to the summon list. Click again to remove it. (Changes the saved list, so on servers with permissions it needs the `config` permission.) |
| Right click | Summons the whole list around you. Same as `/mobbattle summon`. |

The list is saved with the world settings. Presets never change it.

### The summon list

| Command | What it does |
|---|---|
| `summon` | Summons the list around you. |
| `summon list` | Shows the list. |
| `summon add <role> [count\|random] [team]` | Adds a mob type, e.g. `summon add Skeleton 5`, `summon add Trork_Warrior random blue`. Count defaults to 1. A new role ID must match the game's case; one already in the list matches in any case. Every add is an entry of its own, even the same mob and team: the second one is named `<role>#2`, then `#3`... (e.g. `summon add Skeleton_Archer 10 red` and `summon add Skeleton_Archer 10 green`). Use `summon modify` to change one entry's count. |
| `summon modify <entry> [team\|none] <count\|random>` | Sets the exact count of an entry, keeping its team rule and its place in the list. With a team it picks that mob's entry in that team (`summon modify Zombie red 5`). |
| `summon remove <entry>` / `summon clear` | Removes one entry / empties the list. An entry is its role (the first one) or `<role>#2`... |
| `summon team <entry> <team>\|none` | Team rule for an entry: its summons spawn straight into that team of your Team Wand teams, and `teams auto` uses it too (a type listed in several teams is dealt over them in turn). |
| `summon team all none` | Every entry loses its team rule; the mobs stay in the list. The settings panel has the same as **Clear teams** under the summon list. |
| `summon random <min> <max>` | Range for `random` counts (default 1-5, limits 0-100000, more than 60 warns; a reversed range is reordered). |
| `summon atLeastOne true\|false` | `true` (default): every `random` type spawns at least 1. `false`: it may spawn 0. |
| `summon lists` / `summon save <name>` / `summon load <name>` / `summon delete <name>` | Saved summon lists: the mobs, counts, team rules and the teams themselves (names and colors), in `<plugin data dir>/summonlists/<name>.properties`. Loading one replaces the current list and teams. Names: letters, digits, `-` and `_`, up to 32. |

In the panel, the Summon list page shows each mob with its icon, count and team (edits wait for **Save changes**), a searchable list to add mobs, the random range, the summon radius and the "summons leave" settings. Up to 8 mobs show before the list scrolls. Under "Add a mob", **Show pictures** opens a mob gallery over the panel: every mob the Show filters keep, as small pictures with their in-game names, with a search box (name or ID) and the same filters; clicking one puts it in the "Add a mob" row and closes the gallery. Mobs show their in-game name (in your language) wherever a player sees them; commands still take the ID (`goblin_burner`). Its **Saved lists** section saves the list under a name and loads or deletes saved ones (loading can be undone with Undo).

### Random battles

`/mobbattle summon fill <types> [each:<n>] [teams:<n>] [mod:<name>|mod:hytale] [type:fighters|defend] [group:<group>] [add]` fills the list with random mobs that fight, using the same mob classification as the settings panel: no passive mobs, no kickers, no turrets or patrol variants, and no water mobs or bosses unless you ask for that group. They are of similar strength (their max health), so a boss doesn't face frogs. `teams:2` deals them into two balanced teams (strongest first, in turns). It replaces the list unless you add `add`. The panel's Summon list tab has one row for it: how many mob types, how many of each, and how many teams (0 = no teams); it also follows the tab's Show filters (mod, type, group). Then summon them as usual.

### Where and how many

- Mobs appear on the floor under their spot (also under roofs), around you, in an area drawn like the Chaos Wand area for 3 s.
- `summonRadius <1-64>|auto`: the size of that area. `auto` (default) grows with the count: about 4 blocks for a few mobs, about 19 for 100, never more than half of `areaRadius`.
- One summon spawns at most `maxMobs` mobs (default 200). Failed spawns count too; the chat says how many were left out.
- Big summons are spread over a few ticks (about 20 mobs per tick) so the server doesn't freeze. The ring, shockwave and sound come at once; the chat summary comes when the last mob is out.
- Each mob comes out with a summon burst and its model's own spawn animation, if it has one (e.g. skeletons rising from the ground).

### Summons leaving

By default summons stay until you remove them. Global settings (not per battle kind):

| Setting | Values | Effect |
|---|---|---|
| `summonDespawn` | `never` (default) | Summons stay. |
| | `battleEnd` | When a battle ends, the summons that fought in it and survived leave. Summons that never fought stay. |
| | `timer` | Each summon leaves `summonLifetime` seconds after it spawned. |
| `summonLifetime` | 5-3600 (default 60) | Lifetime in seconds with `timer`. Checked once a second, so a summon can leave up to a second late. |
| `summonExit` | `disintegrate` (default) | The Kill Wand's soul rip, then removal. With `wandFx off` or `killDelay 0`: a plain death. |
| | `vanish` | Removed at once, no effect. |
| | `shatter` | Breaks like jade glass, in its look's color or else `summonJadeColor`. With `wandFx off`: a plain death. |

None of the exits drop loot. Summons are tracked while the server runs: a summon that was unloaded and loaded again, or one from before a restart, no longer leaves on its own.

### Jade summons and summons in your army

▶ [Watch it](https://youtu.be/Zg61ddWj5_w)

- `summonJade on|off` (default off): summons come out wearing the jade look, as if the Effect Wand gave it. It is kept the same way (it comes back when another effect ends), glows with `lookGlow`, and they shatter into jade when killed. `summonJadeColor <color>` picks its palette color (default green). See [colors-and-looks.md](colors-and-looks.md).
- `summonArmy on|off` (default off): summons join your army, as if you added each one with the Army Wand. Summons that spawn into a team (team rule) don't.

### Calm summons

`summonCalm off|summons|all` (default summons) keeps new summons from fighting as soon as they appear, so you can summon a Feastmaster and 100 skeletons and set up the battle first.
- `summons`: summons ignore each other.
- `all`: they also ignore players and other mobs nearby.
- A summon stays calm until a battle, a wand fight or your army takes it. Then the battle decides who it fights.
- If something hits a calm summon, it can fight back that attacker. It stays calm toward everyone else.
- Summons in your army (`summonArmy`) are never calm. Calm is only kept while the server runs: a summon that was unloaded and loaded again, or one from before a restart, acts as usual again.

## Armies

### Army Wand

▶ [Watch it](https://youtu.be/WdlFfEAAh0w)

| Click | Effect |
|---|---|
| Left click a mob | Adds it to your army (it flashes blue). Click it again to remove it (it flashes red). |
| Crouch + left click a mob | Recruit for that mob from now on: your next clicks put mobs in **its** army, and it leads them. It shows a gold ring while you hold the wand. |
| Crouch + left click a player | Recruit for that player: your next clicks put mobs in their army. With server permissions on (`/mobbattle permissions server`), this needs `dackoro.mob_battle.config`. |
| Crouch + left click the air (or yourself) | Recruit for your own army again. |
| Right click | Disbands the army you recruit for (yours, or the picked player's or leader's). |

Crouch clicks need you on the ground: while flying in Creative the crouch key flies you down and the game doesn't count it as crouching.

- Joining an army takes the mob out of any battle, fight or team.
- Leaving (or a disband) gives each mob its own AI back, and it ignores you for a while instead of attacking at once.
- Army mobs never attack you, and can't be hurt by you or by each other. If a battle royale or chaos battle is started on mobs of the same army, they are enemies in that battle and fight each other.
- While you hold the Army Wand, each of your army mobs shows a blue ring at its feet (only you see it).
- Each player has their own army; army mobs of different owners can fight each other.
- Armies survive server restarts and chunk unloads: each mob is remembered by its UUID (in `armies.txt`, next to `fx.properties`) and rejoins its army when it loads again. A mob that does not load again within 6 hours of server time is forgotten. `/mobbattle army clearAll` (admin) disbands every army.

### Orders

Army mobs follow these rules, most urgent first:

1. **Self-defense.** An army mob that gets hit fights back, and the nearest free mate helps it (one helper: more hits from the same attacker don't pull in more).
2. **Bodyguard.** Whoever hits you is attacked by every free army mob.
3. **Owner's order.** Whoever you hit is attacked by every free army mob.
   If nobody is free in 2 or 3 and no army mob is on that enemy yet, the nearest half switches to it and the rest finish their fight. Hitting the same enemy again doesn't pull in more.
4. **Regroup.** A mob whose target died joins the nearest enemy the army is still fighting, or follows you again when there is none. Only mobs that fought regroup this way: mobs that were just following you stay with you.

"Free" means not fighting, or its target is dead. Army mobs never ignore an old target that keeps hitting them.

**Players count too:** a player who hits you or your army mobs is attacked by your army, and so is a player you hit. Hits the world's PvP rule cancels don't count.

### Following

- Every second, each army mob's home point moves to you, so mobs that return home walk after you.
- An army mob that isn't fighting and is more than 20 blocks away is teleported back around you with a summon burst. They land spread over a ring that grows with the army: 4 blocks for a few mobs, up to 20 for a big one.

### Other owners and mob leaders

An army can belong to another player or be led by a mob (admin command, also from the server console):

- `/mobbattle apply <who> army add` puts mobs in your own army.
- `/mobbattle apply <who> army add <player>` puts them in that player's army.
- `/mobbattle apply <who> army add id:<uuid>` (or `last`) makes that mob their **leader**. The army follows the leader, defends itself and the leader, and attacks whatever the leader attacks, by the same four rules.
- `/mobbattle apply <who> army remove` takes mobs out of whatever army they are in.

A leader can serve in another army, which gives a chain of command: you lead a Trork chieftain, the chieftain leads goblins. A loop (a mob leading the army it serves in) is refused. The whole chain counts as one army: the goblins never attack you or the chieftain, and nobody in it hurts the others (see friendly fire below). When a leader dies or unloads, its army goes back to its own AI. Army mobs that fight in a battle (army summons in a royale) stay in it: they are not pulled back to their owner until it ends.

### Friendly fire in an army

`armyFriendlyFire on|off` (default off): with it on, hits inside one army (mates, a leader and its mobs, an owner and their mobs, and every army down a chain of command) do their damage, but nobody fights back or calls the others: they stay allies. With it off, those hits do nothing. In the panel: Battles, next to Battle PvP.

### Army tint

`armyTint on|off` (default off): army mobs wear a tint while they are in your army; it goes when they leave or the army is disbanded. `armyTintColor <color>` picks the palette color (default blue). In the panel: Colors & looks, "Army look".

## Morph Wand on players

With `morphPlayers on` (default), the Morph Wand works on players like on mobs:

- Right click a player to copy their model; left click a player to give them the copied model.
- Copying a player copies their whole look (skin, hair, clothes). A mob given that copy becomes a clone with their look, and a player given it wears that other player's look.
- The Cleanse Wand or `/mobbattle morph reset` gives them their own look back. Dying does too: they respawn as themselves.

Only the model changes: the player keeps their own controls, items and health. See [wands.md](wands.md) for the Morph Wand on mobs.

## Snap Wand

▶ [Watch it](https://youtu.be/ue_ffUmihbQ)

Left click: a share of the fighters (`snapPercent`, default 50%), picked at random, turn to dust, one after another over up to `snapTime` seconds (default 5). Same as `/mobbattle snap` when you click the air.

- **Click a mob in a battle:** only that battle.
- **Click the air, or a mob in no battle:** every mob within `snapRadius` (default 60) of the clicked mob or of you, fighting or not, at any height (fliers and mobs in caves too), up to `maxMobs`. A white shockwave on the ground shows how far it reached.
- `snapMode global` (default): the share of everyone. `team`: the share of each side. A fractional count rounds up or down at random.
- `snapPlayers on|off` (default on): the snap also takes players in reach (never you, never players in Creative or invulnerable). They turn to dust and then die a normal death.
- The dust is the Kill Wand's soul rip: `killDelay` and `killLoot` apply. Mobs with a jade look shatter and mobs with a shimmer look burst into liquid instead.
- Snapped mobs are not kills for the battle's kill count.
- The chat says how many will go ("Snap! 45 of 45 within 60 blocks will turn to dust...") and when it's over ("The snap is over: X of Y turned to dust").

`snapHitDelay` lines the effect up with the wand's slam animation; see [settings.md](settings.md).
