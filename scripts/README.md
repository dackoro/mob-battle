# scripts/

Helper scripts for working on Dackoro's Mob Battle. None of them is needed to build or play the mod: the files they
write are already in the repo. Run them from the repo root. Python scripts need Python 3 and, where noted, Pillow
(`pip install pillow`). Scripts that read the game's `Assets.zip` look for it in the release install
(`%APPDATA%\Hytale\install\release\package\game\latest\Assets.zip`).

## Generators (write files into the repo)

Run one after changing what it generates, then build.

| Script | Writes | Notes |
|---|---|---|
| `gen-crowns.py` | Crowns and Cleanse lights in every team color (`Server/Particles/MobBattle/...`, `Server/Entity/Effects/MobBattle/Fx/Crown|Cleanse/`) | Recolors the game's gold crown. Reads Assets.zip. |
| `gen-looks.py` | Jade and Shimmer looks and their deaths in every color, the buff looks per size | Pillow. Reads Assets.zip. |
| `gen-effectburst.py` | The Effect Wand burst in every color | Reads Assets.zip. |
| `gen-summonrunes.py` | The summon rune in every color | Reads Assets.zip. |
| `gen-bufflook.py` | The old Buff Wand look per color (kept for mobs saved with it) | Not used by new buffs. |
| `gen-hologram-anims.py` | The Morph Wand hologram's motion (`Server/Item/Animations/MobBattle_MorphWand.json` and its animations) | Reads Assets.zip. |
| `gen-vs-assets.py` | VS screen art: half backgrounds per color, the VS logo, battle kind titles, the "?" picture (`Common/Icons/MobBattle/Vs/`) | Pillow. `--unknown` writes only the "?". |
| `gen-vs-ui.py` | The VS screen layouts (`Common/UI/Custom/MobBattle/VsScreen.ui`, `VsCols3.ui` to `VsCols8.ui`) | Slot positions live here; `--depth` prints the back-to-front slot orders VsScreen.java uses. |
| `gen-creative-tab-icon.py` | The creative inventory tab icon, grey and gold (`Common/Icons/ItemCategories/MobBattle.png`, `MobBattleActive.png`) | Draws the Battle Wand in the style of the game's tab icons. Pillow + numpy. `--preview <png>` shows both. |
| `gen-observed-kinds.py` | `observed-kinds.properties` (which game mobs really fight) from `/mobbattle test verify` reports | `python scripts/gen-observed-kinds.py report1.txt [report2.txt ...]` |
| `hide-first-person.py` | Adds the invisible first-person particles to every effect | Run after any generator above or a new effect. Safe to run again. |

## Checks and previews (write nothing into the repo)

| Script | What it does |
|---|---|
| `vs-preview.py` | Run with `python scripts/vs-preview.py columns all` (PowerShell does not run a `.py` by its name). VS screens out of the game, from the server's picture cache: `columns [N\|all]` (every mob as a team column, N per screen; `all` = 3 to 8, a folder each), `halves [K]` (K mobs a side: 1-3 a duel, 4-9 medium, 10+ a battle royale crowd; `--clip` cuts each side at the slanted split), `sheet [N]` (64 columns per image), `all` (every one of those, each in its own folder: halves of 3, 6, 9, 12 and 24, and columns of 3 to 8). `--out <folder>` (default `vs-preview/` in the repo root, ignored by git), `--folder <pictures>` (instead of the cache, e.g. what `/mobbattle test vsframes` drew), `--only <text>`, `--mobs a,b,c` (exact mobs, in order), `--labels`, `--scale`. Sizes follow the game (each mob's real height from Assets.zip and the mods folder); to try other rules without the game: `--tiers 1,0.75,0.55,0.38` and `--size-power 0.5` (halves), `--column-size 740` and `--small 1.5` (columns). Prints its progress in %. It only lays out pictures the server already drew (the mod draws them from the 3D models inside the game); Pillow. The server must have started once so the cache has pictures. |
| `sim-film.ps1` + `FilmPickerSim.java` | Plays thousands of simulated battles with the battle film's shot picker and prints how often each shot came and how often shots repeated. Needs a JDK. |

## Development

| Script | What it does |
|---|---|
| `sync-common-assets.ps1` | Keeps the dev asset pack's `Common/` in step with the repo. `deploy.ps1 -Dev` starts it; you never run it by hand. |
