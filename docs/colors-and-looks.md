# Colors and looks

Almost everything Dackoro's Mob Battle draws can be recolored or switched off: area drawings, shockwaves, the marks on battle mobs, the wands' lights, team and army colors, and the Jade and Shimmer looks with their death effects. This page explains each visual option and what it looks like in game.

Every option is a chat command (`/mobbattle <setting>`) described in [settings.md](settings.md). They are also on the **Colors & looks**, **Chaos area**, **Buffs** and **Wands** pages of the settings panel ([settings-panel.md](settings-panel.md)). What each wand does is in [wands.md](wands.md).

## The palette

Every color option takes one of these 14 names (any case). The visual effects are prebuilt per color, so free `#RRGGBB` colors are not accepted. Older saves with a custom hex color are moved to the closest palette color.

| Name | Hex | Name | Hex |
|---|---|---|---|
| Red | `#FF4B3E` | Cyan | `#3EF0FF` |
| Blue | `#4B9BFF` | White | `#F2F2F2` |
| Green | `#4BFF6A` | Black | `#2A2A2A` |
| Yellow | `#FFD23E` | Brown | `#8B5A2B` |
| Orange | `#FF8C1A` | Lime | `#B6FF3E` |
| Purple | `#A24BFF` | Gray | `#808080` |
| Pink | `#FF6BD5` | Magenta | `#FF00FF` |

- `random` is accepted by `areaColor` (any palette color, picked each time) and `fightLaserColor` (one of the eight bright ones: red, cyan, yellow, purple, orange, green, pink, blue).
- `gold` is the game's own golden crown for `crown` and `buff crown`. For jade and shimmer looks, `gold` means yellow.
- Light effects (border glow, runes, eye flames) add light. Black, gray and brown border glows are drawn with normal blending so they really look dark. Black runes and eye flames, however, are almost invisible.
- Tints are saturated on purpose: a pale tint would make a mob look washed out or white.

## The master switch: `wandFx`

[`/mobbattle wandFx off`](settings.md) turns off every effect put on mobs and players: tints, bursts, auras, runes, lights from the sky, the soul rip and death looks. Wand casts and area drawings still play. With it off, lights and buffs land at once and the Kill Wand and Snap kill at once. Default: on.

## Areas

When a Chaos Wand battle or a summon starts, the area is drawn on the ground. Arenas with `arena show` keep it drawn for the whole fight (see [battles.md](battles.md)).

| Setting | Values | What it does |
|---|---|---|
| [`areaStyle`](settings.md) | `border` (default), `disc`, `discBorder`, `wall`, `off` | `border`: only the border glow, columns of rising light about 20 blocks tall every 2 blocks or so along the edge (at most 120 columns). `disc`: a translucent flat disc of color (no outlines) on the ground, like a boss attack zone. `discBorder`: both. `wall`: the disc plus a see-through cylinder wall. `off`: nothing (an arena with `arena show` still gets its border). It can also be set per battle kind. |
| [`areaColor`](settings.md) | palette color or `random` (default red) | Color of the disc and the wall. The border has its own colors (below). |
| [`areaHeight`](settings.md) | 3-128 (default 30) | Height of the `wall` style's wall. |
| [`areaOnlyWand`](settings.md) | `on` / `off` (default off) | `off`: everyone sees the area for 6 s (a summon area for 3 s). `on`: only you see it, and only while you hold the Chaos Wand, like the Team Wand rings. Switching item hides it within about a second; holding the wand again shows your last area again. |

- Changing any of these draws the area around you as a preview, visible only to you.
- The disc is flat and sits on the ground under the center (even if you are flying), so it cuts into hills and floats over dips.
- Border columns start on the top collision surface in loaded terrain (including roofs and tree tops). Unloaded parts use the center's height; no chunks are loaded for it.
- A new disc clears the previous ones, and a new area cancels the border glow of your previous one (and any glow in its spot), so casting or summoning again and again doesn't pile up columns of light.
- Mobs are picked by the 3D radius around the clicked mob or player, so height differences decide which mobs are in.

## Shockwaves and border colors per action

Five actions have their own ground shockwave and border glow color:

- `summon`: Summon Wand right click
- `snap`: Snap Wand
- `slam`: Battle Wand crouch + click the air
- `allVsOne`: Chaos Wand left click
- `royale`: Chaos Wand right click

| Action | [`wave`](settings.md) default | [`borderColor`](settings.md) default |
|---|---|---|
| `summon` | green | green |
| `snap` | white | off |
| `slam` | purple | off |
| `allVsOne` | yellow | yellow |
| `royale` | purple | purple |

- `/mobbattle wave <action|all> <color|off>` sets the shockwave that runs along the ground from the center out to the edge. It starts slowly and speeds up toward the edge, and it grows thicker and lasts longer the bigger its reach: 0.7 s up to 30 blocks, 2.2 s at 130 blocks or more, so a large reach stays visible.
- `/mobbattle borderColor <action|all> <color|off|area>` sets the border glow. A color uses that color, `area` uses `areaColor`, and `off` draws no border for that action. Snap and slam have no area of their own, so a color draws a ring of light at their reach for a few seconds.
- The rune each summoned mob rises from takes the summon border color. If the summon border is off, it uses the summon wave color, or green if both are off.

## Marks on battle mobs

When a battle is called, its mobs get a mark: a tint, an aura, a burst and a small rune turning at their feet. [`/mobbattle mobFx <role|all> <color|default|off>`](settings.md) picks it per role.

| Role | Who | `default` look | Default setting |
|---|---|---|---|
| `duel` | Battle Wand fighters | purple charm | `default` |
| `allVsOne` | the lone target of everyone vs one | gold glow | `default` |
| `attackers` | everyone attacking the lone target | red glow | `default` |
| `royale` | battle royale fighters | orange glow | **purple** |

- A palette color gives the mark in that color. `off` gives no mark.
- [`/mobbattle runes on|off`](settings.md) (default on, also per battle kind) controls the summoning rune and the marks of Chaos Wand battles. The **summoning rune** is a big rune glowing at the area center during the countdown, with a portal-opening sound. With runes off, the everyone-vs-one and royale marks don't show either. The rune particles are `MobBattle_Rune_Center` (area center) and `MobBattle_Rune_Feet_<Color>` (under the fighters), in `Server/Particles/MobBattle/`.

## Crowns

- [`/mobbattle crown gold|<color>|off`](settings.md): the crown with rays of light that hangs over the lone target of everyone vs one for 20 s once the fight starts. `gold` (default) is the game's own crown; palette colors are copies of it.
- [`/mobbattle buff crown gold|<color>|off`](settings.md): a crown on mobs with a streak, champion or Buff Wand buff, for as long as the buff lasts. Default: off.
- Crowns grow with the mob when a size buff makes it bigger.

## Wand lights and colors

The Heal, Buff and Cleanse Wands bring a light down from the sky onto the target. The light is sized to the target's height and moves with it, and the wand's action lands with the light, about 0.4 s after the click.

| Setting | Values (default) | What it colors |
|---|---|---|
| [`healLight`](settings.md) | palette color or `off` (green) | The Heal Wand's light. `off`: no light, the heal lands at once. |
| [`buffColor`](settings.md) | palette color (purple) | The Buff Wand's swirl and mark, and the shimmer that buffed mobs wear. The swirl also plays when a champion buff is given. Taking a buff off is always grey. |
| [`buffLight`](settings.md) | `buff`, palette color or `off` (`buff`) | The Buff Wand's light. `buff` uses `buffColor`. `off`: no light, the stack lands at once. |
| [`cleanseColor`](settings.md) | palette color (white) | The whole Cleanse Wand look: the light, burst, aura and rune at its feet. |

The other wands have fixed colors:

| Wand | Look |
|---|---|
| Battle Wand | purple charm on select, grey when cleared |
| Morph Wand | green flash and burst |
| Army Wand | blue when joining, red when leaving |
| Summon Wand | green when adding a type, red when removing it |
| Effect Wand | purple burst |
| Kill Wand | black disintegration with purple soul smoke |
| Snap Wand | ash |
| Errors | red flash |

## Team and army looks

- [`/mobbattle teamLook tint|mist|both|off`](settings.md) sets how team members look: `tint` colors the whole mob in its team color (default), `mist` wraps it in a mist of that color (like the Enrage effect), and `both` does both. Everyone sees it. It is removed when the battle ends or the mob gets a new order.
- `/mobbattle teamLook time <seconds>|infinite`: how long the look stays after a mob joins a team (default infinite), e.g. `5` to see the color only for a moment. Max 600. For no look at all use `teamLook off`.
- `/mobbattle teamLook onlyWand on|off`: show it only while the team's owner holds the Team Wand; it goes away within about a second when they switch item. Everyone sees it while it is on.
- Team colors use prebuilt effects, with no runtime asset loading.
- When a team battle ends, the winning team keeps its look 8 more seconds.
- Adding a mob with the Team Wand plays a swirl in its team color.
- While you hold the Team Wand, a ring in the team color shows at each team mob's feet. Only you can see it. Team colors are set per team with `/mobbattle teams color` (see [teams-summon-army.md](teams-summon-army.md)).
- [`/mobbattle armyTint on|off`](settings.md) (default off): army mobs wear a tint for as long as they are in your army. [`/mobbattle armyTintColor <color>`](settings.md) sets its color (default blue).
- While you hold the Army Wand, each army mob shows a blue ring at its feet. Only you can see it.

## Fight laser

- [`/mobbattle fightLaser on|off`](settings.md) (default off, also per battle kind): at fight start, a laser line links each mob to its first opponent and follows them as they move.
- [`/mobbattle fightLaserColor <color>|random`](settings.md) sets its color (default red). In team battles, the laser uses the team colors instead.

## Titles

[`/mobbattle titleStyle Default|Major|GoblinBreach|VoidEviction|random`](settings.md) picks the game's banner style for the countdown, start and winner titles. The default is `Major`; `random` picks a style each fight. These are the client's built-in `EventTitleStyle` banners; the mod only picks which one to show. The titles themselves are covered in [battles.md](battles.md).

## Jade and Shimmer looks

![Skeletons in jade, one per color: blue, red, green and yellow](images/looks-colors.jpg)
![Skeletons in shimmer at night, one per color: purple, red, green and yellow](images/looks-shimmer.jpg)

Two special looks change the whole body of a mob. Both come in every palette color.

| Look | What it looks like | Default color |
|---|---|---|
| **Jade** | The mob turns to jade with glowing eyes. It grows into it over about a second, then holds it. | green |
| **Shimmer** | Glowing veins under the skin, flames in the eyes and runes turning over the head. | purple |

### Getting them

| Way | How |
|---|---|
| Effect Wand | `/mobbattle wandEffect jade`, `jade:<color>`, `shimmer` or `shimmer:<color>`, then left click a mob. `jade` and `shimmer` are also in the wand's right-click list. See [wands.md](wands.md#effect-wand-mobbattle_effectwand). |
| Summons | [`/mobbattle summonJade on`](settings.md) makes summoned mobs come out in jade, in [`/mobbattle summonJadeColor`](settings.md) (default green). |
| Buffs | Mobs with a Buff Wand, streak or champion buff wear the shimmer in `buffColor`. A mob that already has its own jade or shimmer look keeps it and only gets the runes and eye flames. |

### How they behave

- **They stay.** A look lasts until the Cleanse Wand or death. It is put back on whenever the game would drop it: when the mob loses another effect, gets hit, changes size or model, or when a player comes into view.
- **They are saved with the world.** A mob that unloads and loads again, or survives a server restart, still has its look.
- **One look per mob.** Giving a new jade or shimmer look replaces the old one.
- **They glow.** [`/mobbattle lookGlow <0-15>`](settings.md) (default 6) puts real light around the mob in its look's color, like block light, so it shows at night and in caves. Jade glows at 60% of that, since the jade itself already shines. Black looks give no light, and `0` turns it off.

### Death looks

A mob wearing a jade or shimmer look doesn't die the normal way:

- **Jade** shatters into jade shards (about 0.9 s).
- **Shimmer**, including the buff's shimmer, turns its color from the feet up, then bursts into liquid (about 0.85 s).

Both happen in the look's color, and then the mob is gone, with no corpse. This happens:

| Death | What happens |
|---|---|
| Kill Wand | Replaces the black disintegration. The removal waits for the look's animation, even if `killDelay` is shorter. Loot follows `killLoot`. |
| Snap Wand | Replaces the ash (no ash cloud). Loot follows `killLoot`. |
| Any other death (another mob, a fall, lava...) | The death animation plays under the look, then the mob is removed and drops its normal loot. |
| Summons leaving with [`summonExit shatter`](settings.md) | Every summon shatters like jade, in its own look's color or else `summonJadeColor`. No loot. |

Death looks need `wandFx on`. A Kill Wand kill with `killDelay 0` is instant, so it has no death look.

### Death light

[`/mobbattle deathGlow <0-15>`](settings.md) (default 12) makes a dying mob give off a flash of light that fades out until it is removed:

- **Kill Wand:** purple for the soul rip, or the look's color for jade and shimmer mobs.
- **Jade and shimmer mobs** dying any other way, and summons that shatter: the look's color.
- **Snap Wand:** no light, on purpose.

`0` turns it off.
