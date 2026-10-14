# Settings panel

`/mobbattle config` (or `/mobbattle panel`) opens an in-game window with every Mob Battle setting, the team and summon lists, presets, resets, quick tools and admin actions. Everything the panel changes can also be done with chat commands; see [settings.md](settings.md) for the full reference with commands, `fx.properties` keys and defaults.

## How it works

### Opening and moving around

- Type `/mobbattle config` or `/mobbattle panel` (players only; the server console has no panel).
- The menu on the left lists the tabs. Only the open tab is built, so switching tabs is quick and **keeps your unsaved changes**.
- The panel **reopens on the tab you had open last** (remembered per player until the server restarts).
- Every setting has a short line under it saying what it does, and number fields show their range.
- The X in the corner closes the panel. Closing it drops any changes you have not saved.

### Draft, Save, Discard

Changing a value does **not** apply it yet:

- The setting's name turns **gold** with a `*` in front, and the bottom bar counts the unsaved changes.
- **Save changes** applies every change at once and writes `fx.properties` (the same path a preset takes).
- **Discard changes** drops them all and puts every control back to the saved value.
- A value the field can't take (half-typed text, a word that isn't valid) shows a warning in the bottom bar and is not kept. A number out of range is clamped and shown clamped.
- Save, Discard and Undo update only the rows that changed, so the page keeps its scroll position.

### Undo last change

**Undo last change** goes back to how things were right before the last change made from the panel:

- It covers the last **Save changes** and the last team or summon list action (add, remove, clear, team reset) and **Reload** on the Admin tab.
- The team and summon lists come back **at once**. The settings go **into the form** (gold), so you can check them and then **Save changes**.
- It is one level only and shared by everyone: the last change from any player's panel since the server started. Changes made with chat commands or wands are not tracked.
- The button is greyed out when there is nothing to undo or the form already holds the undo. Once an undo is saved it is used up: a second Undo does not bring the change back.
- Mobs taken out of a team that was removed stay out after an undo.

### Rows that wait for Save (teams and summon list)

- **Summon list:** each mob's **count** and **team** stay in the draft (the mob's name turns gold with `*`) until **Save changes**.
- **Teams:** each team's **name** and **color** stay in the draft (a gold `*` next to the row, the swatch shows the new color) until **Save changes**. Two teams can swap names or colors in one save, but no two teams may end up with the same name or the same color; if they would, nothing is saved and the bottom bar says why.
- **Add**, **Remove**, **Clear list**, **Reset teams** and the other list buttons apply **at once** (they run the matching `/mobbattle` command, and chat shows the result).

### Buttons that act at once

List buttons, the Tools tab, the Admin tab and the Morph Wand **Use** button run the matching `/mobbattle` command as you, with its normal permission checks and chat reply. Your unsaved draft is kept. Buttons that clear or delete something (remove a team, reset teams, clear the summon list, delete a preset, Stop all, Disband armies, Clear all teams, Undo morphs, Reset all) ask for a **second click within 5 seconds** to confirm: after the first click the button turns red and reads **Confirm?**. Watch, Stop watching, Snap, Chaos me, Debug NPCs, Start, Join and Summon close the panel first so you can see the world.

### Range preview

Editing a range draws it **around you right away**, before you save: only you see it, for about 6 seconds, and each change replaces the previous circle. It uses the look and color currently in the form. This works for:

- **Area radius**, **Area look** and **Area color** (Chaos area tab)
- **Arena size (duels, teams)** and **Closing arena ends at** (Battles tab)
- **Snap reach**, **Wave reach** and **Cleanse wave reach** (Wands tab), **Summon area** (Summon list tab)
- **Area look** on each battle-kind tab

A value of 0 (auto) has no fixed size, so nothing is drawn.

### Searchable lists

Lists with more than 15 entries (effects, music tracks, mobs, models) have a **search box**. Entries are labelled by category and mod, for example `Potion - Morph Frog` or `Undead - Skeleton [SomeMod]`. Mob and model rows show the game's own model icon; a mob without one shows a close relative's icon (Skeleton shows Skeleton Soldier) or a `?` icon.

### Presets and resets fill the form

On the **Presets & reset** tab, loading a preset or pressing a reset button **does not apply it**: it fills the form with what it would set (the changed rows turn gold). Look through the tabs, then **Save changes** or **Discard changes**.

### Permissions

- Anyone can open the panel and read it, in either permission mode.
- In `open` mode (the default) anyone can save. In `server` mode, saving, list changes and preset saves need `dackoro.mob_battle.config`; without it the panel is read only (the bottom bar says "Read only." and edits are put back).
- Tools need `dackoro.mob_battle.use` in `server` mode.
- The **Admin** tab, the **Reset teams** button and the Debug log / Debug recorder fields need a real `dackoro.mob_battle.admin` grant in **both** modes. Players without it don't see the Admin tab.
- The permission mode is never saved from the form; it has its own buttons on the Admin tab.

See [server-setup.md](server-setup.md) for the permission nodes and how to grant them.

## Tabs

Tabs appear in this order. Field names below are exactly as the panel shows them. "Per kind" means the battle-kind tabs can override the value.

### Presentation

How fights start and end. Each battle kind can override these on its own tab.

| Section | Field | What it does |
|---|---|---|
| Start | Countdown | 3, 2, 1 titles before a fight starts (seconds, 0 = start at once). Per kind. |
| | Start title | Big title when a fight starts (FIGHT!, BATTLE ROYALE!). Per kind. |
| | Winner title | Big title when a battle ends (X WINS!). Per kind. |
| | Title style | Look of the big titles: Default, Major, Goblin breach, Void eviction or Random. |
| | Who sees the titles | Owner (who started it), Nearby (64 blocks around) or World. |
| Music and effects | Battle music | Music from the countdown to the end of the battle. Per kind. |
| | Music track | Track used when battle music is on (searchable list of the loaded music). |
| | Fighter laser | A laser between the fighters when a fight starts. Per kind. |
| | Laser color | A palette color, or Random (a new bright one each fight). |
| | Summoning runes | Runes on the ground during the countdown and rage marks at the start. Per kind. |

### Cameras

Intro camera before the fight, end camera on the winner, watch mode and the duel card.

| Section | Field | What it does |
|---|---|---|
| Intro camera | Intro camera | Camera shots of the fighters during the countdown. Per kind. |
| | Camera style | Auto, Orbit, Duel, Showcase, Teams, Focus or Drone. Auto = duel shots for duels, focus for all vs one, sweep for teams, orbit for royale. Per kind. |
| | Camera time | How long the intro films (seconds, 0 = as long as the countdown). Per kind. |
| | Showcase shots | How many fighters the Showcase style shows (up to Max mobs). Per kind. |
| | Who sees the cameras | Owner, players nearby (64 blocks) or the whole world. Fighters never get an intro. |
| End camera | End camera | Off, Winner, or Last hit (who landed the final kill). Per kind. |
| | End camera time | How long the end camera films (seconds). Per kind. |
| Watch & HUD | Auto watch | Camera viewers start watching (spectator camera) as soon as a battle starts. Per kind. |
| | Duel card | Card with both fighters' stats during 1 vs 1 duels. |
| | All vs one card | Card with the lone target's stats and the attackers left during an all vs one. |

Crouching (or `/mobbattle skipCamera`) skips a running camera. `/mobbattle watch` spectates a running battle (right click = next fighter, left click = previous, Q or `/mobbattle watch exit` = exit).

### Battles

What every battle kind shares. Each kind has its own tab for its presentation, rules, arena and timer.

| Section | Field | What it does |
|---|---|---|
| Every battle | Battle PvP | Enemy players of the same battle can hurt each other even where the world's PvP is off. |
| | Unstick stalled fighters | A mob stuck on another level than its target (10 s, fliers 20 s) is moved next to it. |
| | Stand-off shove | A mob on its target's level that gets no closer, hits nothing and isn't hit for this long is shoved toward it (seconds, 0 = never). |
| | Win against passive mobs | When only one side can still fight back, it wins after this long without a hit; if nobody can fight, it's a draw. An alert shows bottom left (seconds, 0 = never). |
| | Never ends against | Roles that keep Win against passive mobs from starting while one is alive (default the test dummies). |
| | End when nobody fights | A battle where no fighter hurts an enemy for this long ends in a draw (seconds, 0 = never, default 150 = 2.5 min). |
| | Longest battle | A battle with no winner ends anyway after this long (minutes, default 15). 0 = infinite: only a winner, End when nobody fights, the passive rule or Stop ends it. |
| Battle waves | Reach as the ring arrives | Snap Wand (without a battle): the dust starts when the ring gets to each mob. Royale and all vs one: each mob's mark comes with the ring (default off). |
| | Ground only | Only mobs within 3 blocks above or below the wave are snapped or join; fliers and caves stay out (default off). |
| Arenas & timers | Timer box | Custom (Mob Battle's timer box) or Portal (the game's portal timer). |
| | Arena size (duels, teams) | Radius of duel and team arenas (blocks; 0 = auto, around the fighters). Range preview. |
| | Closing arena ends at | Final radius of a closing arena (blocks). Range preview. |
| | Closing time without timer | How long a closing arena takes when its battle has no timer (seconds). |
| Ties | Winner when even | When the Judge by score is even at time up: Damage, Kills, Random (coin toss) or Draw (default Damage). If it equals Judge by, the other one of damage/kills is used. Per kind. |
| | Kill table order | Fighters with the same kills: Damage (more damage dealt first, default), Alive (standing fighters first, then damage), First (who got there first) or Shared (they share the place: 1, 2, 2, 4). Then always who got there first. Used by the timer box, the scoreboard, the chat top 3, the end camera and watch mode. Per kind. |

### Duel, All vs one, Royale, Team battles

One tab per battle kind: **Duel** (Battle Wand), **All vs one** (Chaos Wand left click), **Royale** (Chaos Wand right click) and **Team battles** (Team Wand; the teams themselves are on the Teams tab). Each tab has the same layout.

**Presentation and Cameras** (overrides): every field starts on **Global (value)**, meaning this kind uses the value from the Presentation or Cameras tab. Pick a value to override it for this kind only. Number fields (Countdown, Camera time, Showcase shots, End camera time) are text boxes: leave them **empty** for Global.

| Section | Field | Override of |
|---|---|---|
| Presentation | Countdown | Countdown |
| | Start title | Start title |
| | Winner title | Winner title |
| | Music | Battle music and track: Global, On, Off, or a specific track |
| | Fighter laser | Fighter laser |
| | Runes | Summoning runes |
| | Area look | Area look (Chaos area tab). Range preview. |
| Cameras | Intro camera | Intro camera |
| | Camera style | Camera style |
| | Camera time | Camera time |
| | Showcase shots | Showcase shots |
| | End camera | End camera |
| | End camera time | End camera time |
| | Auto watch | Auto watch |

**Rules, Arena & timer** (always per kind):

| Section | Field | What it does |
|---|---|---|
| Rules | Battle Wand duels | (Duel tab only) Exclusive = a new duel replaces the old pair; Accumulate = connected duels merge. |
| | Isolated | Its mobs only fight their opponents, and outside NPCs can't hurt them. |
| | Self defense | A mob hit by an enemy that isn't its target turns on it (keeps it at least 3 s), like Army Wand mobs. |
| | Friendly fire | (All vs one and Team battles only) Hits between allies hurt. Allies still never target each other. |
| | Players can fight | Duel: crouch + Battle Wand puts you in a duel. All vs one: `/mobbattle chaos me`. Royale: players can be fighters. Teams: `/mobbattle teams join <team>`. |
| Arena & timer | Arena | On = drawn + wall, Show = only drawn, Wall = invisible wall, Off = none. |
| | Timer | Fight time limit with a timer box (seconds, 0 = no limit). |
| | At time up | Draw, Kill (everyone), Continue, Health (best Judge by score wins) or Judge (same, and the losers die). An even score goes to Winner when even. |
| | Judge by | What Health/Judge score: Health (% left, fallen = 0%), Damage or Kills (share per fighter, so a bigger side doesn't win for its size), or Mix (the three averaged). |
| | Closing arena | The arena closes in like a battle royale zone. |
| Ties | Winner when even | Override of Winner when even (Battles tab). |
| | Kill table order | (Not on the Duel tab: a duel has no kill table.) Override of Kill table order (Battles tab). |
| | Scoreboard | A row per side with fighters left and kills. |

See [battles.md](battles.md) for how arenas, timers and judging work.

### Chaos area

The Chaos Wand area (all vs one, royale): look, size, color and height.

| Field | What it does |
|---|---|
| Area look | What areas and arenas draw: Border (glow), Disc (on the ground), Disc border (both), Wall, or Off. Range preview. |
| Area radius | Mobs within this radius join a Chaos Wand battle (blocks). Range preview. |
| Max mobs | Most mobs one Chaos Wand click, Snap or summon affects (default 200, up to 100000; more than 60 shows a warning, since it can lag the server). |
| Area color | Palette color or Random. Range preview. |
| Area height | Height of the area pillars and wall (blocks). |
| Area only while holding the wand | Only its owner sees the area, and only while holding the Chaos Wand. |

### Colors & looks

Every palette color the mod draws with (the Buff Wand's colors are on the Buffs tab). See [colors-and-looks.md](colors-and-looks.md).

| Section | Field | What it does |
|---|---|---|
| Team look | Team look | Tint, mist, both or nothing on team mobs. |
| | Team look time | How long the team look stays after a mob joins a team (seconds, 0 = while in the team). |
| | Team look only with the Team Wand | Show it only while the owner holds the Team Wand. |
| Army look | Army tint | Army mobs wear a tint for as long as they are in your army. |
| | Army tint color | Color of the army tint. |
| Crown, Cleanse and glow | Crown on the lone target | Gold, a palette color or off. |
| | Cleanse Wand color | Light and look of the Cleanse Wand. |
| | Look glow | Light around mobs with a jade or shimmer look or the buff's shimmer, in the look's color (light level 0-15, 0 = off). |
| Waves and borders per action | Summon / Snap / Slam / All vs one / Royale wave | Shockwave color when that action happens (off = none). |
| | Summon / Snap / Slam / All vs one / Royale border | Border glow color of its area (Area = the area color, off = none). The summon border also colors the rune each summon rises from. |
| Mob looks per role | Duel fighters, All vs one target, All vs one attackers, Royale fighters | Mark put on these mobs at the start: a palette color, Default (its own look) or off. |

Slam is the Battle Wand's crouch + click in the air.

### Buffs

Battle buffs (size, health, damage, defense and speed) and the Buff Wand. Values are per source, and sources add up on a mob. The Buff Wand counts per click, the streak per kill (up to Streak max kills). Clicks and sources add to the mob's base value, they never multiply what it already has: with x2 per click, 10 health becomes 20, then 30, then 40 (not 20, 40, 80). Defense stops at 99% (x100).

**Buff values as** (top of the tab): Percent (default: +100 = double) or Multiplier (x2 = double, decimals allowed such as x1.5; defense x2 = takes half the damage). It only changes how the values below are shown and typed; the buffs themselves stay the same, and sources still add up as percent (two x2 sources = x3). Changing it shows every value again in the new unit at once.

Each source has a section, in this order: **Buff Wand (per click)**, **Lone target (all vs one)**, **Kill streak (per kill)** and **Champion (winners)**. Each has:

| Field | What it does |
|---|---|
| Size | Extra size (0 = off, up to +900 % = x10; all sources together stop at Size limit). |
| Health | Extra max health (up to +9900 % = x100). |
| Damage | Extra damage (up to +9900 % = x100). |
| Defense | Less damage taken (up to 99 % = x100: takes a hundredth; never 100 %, that would be unkillable). |
| Speed | Extra walking speed (up to +900 % = x10), on top of what a size buff already gives. |
| Heal when given | Fill the new max health when the buff is given (for the lone target: heal it when it is picked). |

Extra fields:

| Section | Field | What it does |
|---|---|---|
| (top of the tab) | Speed from size / Jump from size | How much a size buff speeds up and raises the jump: off, natural (default; x4 = twice as fast), strong, full (x4 = 4 times as fast). |
| (top of the tab) | Size limit | Most extra size on one mob or player, all buffs together (50-9900 %, default 200 = x3). Above 200 the panel warns: big players don't fit under roofs and their camera ends up in blocks. |
| Kill streak | Streak max kills | Kills that count at most for the streak buff. |
| Buff Wand | Buff Wand max stacks | Most Buff Wand clicks on one mob. |
| | Buff color | Buff Wand mark and swirl, and the shimmer of buffed mobs. |
| | Buffed mob look | While the buff lasts: shimmer (veins, eye flames and runes), runes (only eye flames and runes) or off. |
| | Buff Wand light | Light from the sky that brings the buff: Buff (in the buff color), a color, or off (the buff lands at once). |
| Crown | Buffed mobs' crown | Crown on streak, champion and Buff Wand mobs: Gold, a palette color or off. |

### Wands

Wand feedback and what each wand does. See [wands.md](wands.md).

| Section | Field | What it does |
|---|---|---|
| Every wand | Wand effects | Glow, soul rip and sounds on the clicked mob. |
| Wand waves | Hit as the ring arrives | The held-click waves of the Morph, Cleanse, Effect and Heal wands hit each mob when the visible ring reaches it, not all at once (fliers and caves too, as the ring passes). Default on. |
| | Ground only | Those waves only hit mobs within 3 blocks above or below the wave, like the game's slams: fliers and caves are spared. Default off. |
| Heal Wand | Heal Wand light | Light from the sky that brings the heal: a color, or off (heals at once). |
| | Hold left: heal wave | Hold left click: a ground slam heals every mob and player around you, you too (off: a held click works like a quick one). |
| | Heal wave reach | Reach of the heal wave (blocks, default 12). Range preview. |
| | Heal wave heals you | The heal wave heals you too (default on). |
| Effect Wand | Effect Wand effect | The effect the Effect Wand gives (searchable list; the wand's right click cycles a quick list). The **Show** row above it filters the list by mod and by group. |
| | Hold left: effect wave | Hold left click: a wave gives the effect to every mob and player around you (off: a held click works like a quick one). |
| | Effect wave reach | Reach of the effect wave (blocks, default 12). Range preview. |
| | Effect wave hits you | The effect wave gives you the effect too (default off). |
| Kill Wand | Soul rip time | Time before the mob dies (milliseconds, 0 = instant). |
| | Cast hit delay | Milliseconds from the click to the cast's hit. |
| | Drop loot | A mob killed by the Kill Wand or Snap drops its normal loot. |
| | Death glow | The disintegrating mob flashes in its color and fades out (light level 0-15, 0 = off). |
| | Works on players | Players disintegrate too, then die normally. |
| Snap Wand | Snap mode | Global = half of everyone, Team = half of each side. |
| | Share that turns to dust | Share of the fighters that turn to dust (%). |
| | Dust time | Seconds over which they turn to dust. |
| | Slam hit delay | Milliseconds from the click to the slam's hit. |
| | Snap reach | Reach of a snap with no battle running (blocks). Range preview. |
| | Snap players | Players in reach (not Creative or invulnerable, not you) can turn to dust. |
| | (note) | The snap's wave options (dust when the ring arrives, ground only) are on the Battles tab, Battle waves. |
| Morph Wand | Morph players | Players can be morphed too. |
| | Hold left: morph wave | Hold left click: a wave gives every mob around your copied model (off: a held click works like a quick one). |
| | Hold right: swap wave | Hold right click: a wave swaps the models of the mobs around in random pairs. |
| | Wave reach | Reach of both waves (blocks, default 12). Range preview. |
| | Waves change you | Both waves change your model too (default off). |
| Cleanse Wand | Hold left: cleanse wave | Hold left click: a wave cleanses every mob and player around you (off: a held click works like a quick one). |
| | Cleanse wave reach | Reach of the cleanse wave (blocks, default 12). Range preview. |
| | Cleanse wave cleanses you | The cleanse wave cleanses you too (default off). |
| Debug Wand | Follow time | After the left-click report, the mob's target, AI state, health and effects keep going into the same file (seconds, 0 = report only). |
| Morph Wand model | Your Morph Wand turns mobs into | Pick a model (searchable, with an icon preview; the **Show** row above filters it by mod and by group) and press **Use**. It starts on the model your wand holds now. This is yours only and lasts until the server restarts; it is not a saved setting. A right click on a mob with the Morph Wand copies its model instead. |

### Teams

![The Teams tab: team names and colors, add a team, reset](images/panel-teams.jpg)

Team Wand teams. See [teams-summon-army.md](teams-summon-army.md).

- **Team by type** (setting, needs Save changes): clicking a mob brings every mob of its type within the area radius into the team.
- **Teams (n of 14):** one row per team with its color swatch, name, color and a **Remove** button. Name and color wait for Save changes (marked `*`); Remove applies at once (confirm). There are always at least 2 teams.
- **Add a team:** a name (letters, numbers and `_`, up to 16) and a color (starts on the first color no team uses), then **Add team**. Each team needs its own color.
- **Reset teams** (admins only): back to the four default teams (Red, Blue, Green, Yellow), with confirmation.
- **Your Team Wand teams:** a gallery of the mobs you have put in your teams in this world, one icon per mob type with a count (`x5`).

### Summon list

![The Summon list tab: each mob with its icon, count and team, add a mob, summon now](images/panel-summon-list.jpg)

The Summon Wand list. Add, remove and clear apply at once; counts, teams and the settings need Save changes. Each mob row shows its team's color square next to the team (none for no team). A list of more than 7 mobs scrolls in its own box, so Add a mob stays close.

| Section | Field | What it does |
|---|---|---|
| Where they appear | Summon area | Radius the Summon Wand spawns in (blocks, 0 = auto: grows with the mobs). Range preview. |
| Random counts | Random count from | Lowest count for entries set to random. |
| | Random count to | Highest count for entries set to random. |
| | At least one | A random entry never rolls 0. |
| Leaving | Summons leave | Never (they stay), Battle end (the surviving summons of a battle go when it ends) or Timer (each goes Summon lifetime seconds after it spawned). |
| | Summon lifetime | How long a summon lives with Summons leave = Timer (seconds). |
| | How they leave | Disintegrate (the Kill Wand's soul rip), Vanish (removed at once) or Shatter (breaks like jade glass). None drop loot. |
| When they spawn | Calm until a battle | Off (default): they act as usual. Summons: summons ignore each other until a battle, wand fight or army takes them. All: they also ignore players and other mobs. A hit lets one fight back its attacker only. Only summons made after it is turned on. |
| | Summons join my army | Summoned mobs join your army, as if added with the Army Wand (not when they spawn into a team). |
| | Summons in jade | Summons come out wearing the jade look, as if given by the Effect Wand. |
| | Summon jade color | Color of the summons' jade. |

Below the settings:

- **Mobs in the list:** one row per entry with its icon, a **count** (a number 0-100000 or `random`), a **team** (or No team) and **Remove**. The same mob can have several entries, one per team (10 archers in red and 10 in green).
- **Add a mob:** pick a mob (searchable, with an icon preview; the list opens wide so long names fit). The **Show** row above it filters the list: by mod (every mod, Hytale only, or one mod) and by type (Fighters, Defend only, No attack, Avoid: immortal, scenery and test roles), and by group (the first part of the name: Aquatic, Undead...). The group list only offers groups that the chosen mod and type still have. The type is read from each mob's definition, so a mod's unusual mob can land in the wrong one. With every mod shown, mobs from mods are marked ★. Each player's filters are remembered until the server restarts. Then pick a count and a team, then **Add**. The same mob and team as a listed entry changes its count; another team adds an entry. The row starts on your last pick (else the test dummy), so adding the same mob for another team is one click.
- **Clear list** (confirm) and **Summon** (summons the list around you now).

### Presets & reset

A preset or reset fills the form: check it, then Save changes (or Discard).

- **Built-in presets:** Default, Fight, Effects, Debug, Cinematic.
- **Your presets:** one row per saved preset: its name (click it to load it into the form) and **Delete** (asks for a second click).
- **Save the form as a preset:** type a name and press **Save preset**. It saves the form *including unsaved changes*. A preset holds every setting except the team list, the summon list, the permission mode and the debug log/recorder.
- **Back to defaults (into the form):** Reset effects, Reset cameras, Reset titles, Reset arenas, Reset rules, Reset wands, Reset all.

What each preset and reset group contains: [settings.md](settings.md#presets).

### Tools

Things you can do right now (same as the commands).

| Section | Button | Same as |
|---|---|---|
| Watch and cameras | Watch | `/mobbattle watch` (spectate the nearest battle, your own first) |
| | Stop watching | `/mobbattle watch exit` |
| | Skip camera | `/mobbattle skipCamera` |
| Battles | Snap | `/mobbattle snap` |
| | Chaos me | `/mobbattle chaos me` (needs Players can fight for All vs one) |
| | Debug NPCs | `/mobbattle debug` (combat state of NPCs near you for 10 s) |
| Your teams (Team Wand) | Auto teams | `/mobbattle teams auto` |
| | Start | `/mobbattle teams start` |
| | Join (pick a team) | `/mobbattle teams join <team>` |
| | Leave | `/mobbattle teams leave` |
| | Clear my teams | `/mobbattle teams clear` |

### Admin

Only shown to players with `dackoro.mob_battle.admin`, in every permission mode.

| Section | Field or button | What it does |
|---|---|---|
| Server log | Debug log | `[debug]` lines in the server log for battle decisions (a setting: Save changes). |
| | Debug recorder | Keeps the last 5 minutes of log and `[debug]` lines in memory (a setting: Save changes). |
| Permissions | Open / Server | Switch the permission mode at once (`/mobbattle permissions open|server`). |
| Server | Reload | Read `fx.properties` again after editing it by hand; drops your unsaved changes. |
| | Give wands | One of each wand into your inventory. |
| | End timers | Every running timer ends now by its time-up rule (`/mobbattle skipTimer`). |
| | Royale event | An ownerless royale around you (area radius, max mobs). |
| | Save debug record | Writes what the recorder kept to `debug/` in the plugin data folder (`/mobbattle debugDump`). |
| Measure | Measure | Your speed (blocks/s) and jump height for 10 s, in chat (`/mobbattle measure`). Closes the panel. |
| Apply to players or mobs | Who / action / value, Apply | A wand action on chosen players or mobs (`/mobbattle apply`, see [server-setup.md](server-setup.md)). Who: me, what you look at, everyone within 12 blocks, every player in this world, or one online player. |
| Clean up (asks to confirm) | Stop all | Stop every battle, countdown, camera and music. |
| | Disband armies | Disband every army. |
| | Clear all teams | Empty every player's teams. |
| | Undo morphs | Every morphed mob gets its own model back. |
| | Reset all | Stop everything and clear teams, buffs, armies and marks (settings stay). |
