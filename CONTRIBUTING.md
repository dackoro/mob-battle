# Contributing

This page covers building Dackoro's Mob Battle from source, installing your build, regenerating the generated assets, and the conventions the project follows. The mod is a Hytale server plugin (Java) with an asset pack, and both ship in one jar.

For what the mod does, see the [README](README.md) and the pages in [docs/](docs/). Start with [settings.md](docs/settings.md), [settings-panel.md](docs/settings-panel.md), [wands.md](docs/wands.md), [battles.md](docs/battles.md), [colors-and-looks.md](docs/colors-and-looks.md), [teams-summon-army.md](docs/teams-summon-army.md), [server-setup.md](docs/server-setup.md) and [plugin-api.md](docs/plugin-api.md).

## Requirements

- **JDK 25.** `build.gradle.kts` pins the Java toolchain to version 25.
- **Gradle.** Use the included wrapper (`gradlew` / `gradlew.bat`), so you don't need a separate install.
- **A Hytale install** (optional but recommended), so you can compile against the exact server you run.
- **Python 3**, only if you regenerate assets (see [Asset generator scripts](#asset-generator-scripts)). `gen-looks.py` also needs **Pillow**.

## Building

```sh
./gradlew build
```

By default the plugin compiles against `com.hypixel.hytale:Server` (latest release) from `https://maven.hytale.com/release`.

To compile against the server jar of your own Hytale install instead, for example a pre-release patchline or a build not yet on Maven, pass its path:

```sh
./gradlew build "-PhytaleServerJar=<Hytale install>/install/<patchline>/package/game/latest/Server/HytaleServer.jar"
```

The jar is written to `build/libs/MobBattle-<version>.jar`. It contains:

- the compiled plugin classes;
- `manifest.json` (`Group` `Dackoro`, `Name` `Mob Battle`, `Main` `dev.dackoro.mobbattle.MobBattlePlugin`, `IncludesAssetPack: true`);
- `Common/` (client assets: models, textures, icons, particle textures, sounds, UI);
- `Server/` (server definitions: items, effects, particles, model VFX, game mode, audio, languages);
- `icon-256.png`, the icon in Hytale's mod list (found next to `manifest.json`);
- `README.md`.

`-PcodeOnly` builds a jar without `Common/` and `Server/`. It is only used by the asset dev mode below.

### Server version

`manifest.json` declares `"ServerVersion": ">=0.7.0-pre <0.8.0"`. That range accepts the 0.7.0 pre-releases and the 0.7.0 release. Hytale refuses a mod whose range doesn't match the running server, so widen or move the range when you target another version.

### Installing a build

Copy the jar into your Hytale Mods folder as `MobBattle.jar`, then enable **Mob Battle** in the world's Mods list.

**Never overwrite the installed jar while a world is loaded.** Hytale keeps it open and fails with `ZipException: invalid LOC header` if it changes under it. Exiting to the main menu frees it; you don't have to close the game.

## deploy.ps1 (Windows)

`deploy.ps1` is a PowerShell helper that builds the jar, waits until it can be installed, and copies it into your Mods folder as `MobBattle.jar`.

```powershell
.\deploy.ps1
```

If a world is open, it builds and then waits: exit to the main menu and it installs right away.

| Option | Effect |
|---|---|
| `-Patchline release\|pre-release` | Which Hytale patchline to build against and install into, using the launcher's default folders. Default `release`. |
| `-ServerJar <path>` / `-ModsDir <path>` | Explicit server jar and Mods folder. These take precedence over everything else. |
| `-NoWait` | Build, and install only if the jar is free right now. Otherwise just build. |
| `-Dev` | Asset dev mode (below). |
| `-Force` | Install even while the game holds the jar. This is an experimental hot reload meant for use after `/plugin unload "Dackoro:Mob Battle"`. In practice the jar stays locked after an unload, so exit to the main menu instead. An unload also removes the plugin from the world's boot list; re-enable it with `/plugin load "Dackoro:Mob Battle"` or in the world's Mods list. |

To keep your own paths without passing parameters every time, copy `deploy.local.example.json` to `deploy.local.json`. That file is git-ignored. Set any of:

```json
{
  "patchline": "release",
  "serverJar": "<Hytale install>/install/release/package/game/latest/Server/HytaleServer.jar",
  "modsDir": "<Hytale user data>/Mods"
}
```

The order is: parameters, then `deploy.local.json`, then the `-Patchline` defaults. If the server jar isn't found, the build falls back to the Maven artifact. The script prints the patchline and Mods folder it uses.

### Asset dev mode (`-Dev`)

```powershell
.\deploy.ps1 -Dev
```

This mode installs two things:
- a **code-only** jar;
- a **folder pack** `MobBattle.AssetsDev`, named "Mob Battle Assets DEV". It depends on `Dackoro:Mob Battle` and needs a different name, because a duplicate `Group:Name` is rejected.

Enable both mods in the world. In the folder pack:
- `Server/` is a junction to this repository.
- `Common/` is a real copy, kept in sync by a hidden background helper (`scripts/sync-common-assets.ps1`). The helper mirrors changes about every 0.75 s and skips Blockbench `.bbmodel` files. Hytale doesn't follow a junction for `Common/`, which is why it's a copy. `deploy.ps1 -Dev` starts the helper; you never run it yourself (see its header). Running `-Dev` again replaces the old helper's sync token, so only one runs.

The server's asset monitor watches folder packs. Edits to models, textures, icons, particles, item and effect JSON, and translations in this repository therefore hot-reload in game, with no restart. Only Java changes need a new deploy. The repository stays the only source of truth: always edit here, never in the Mods folder.

Run plain `.\deploy.ps1` to go back to the single full jar. It stops the helper and removes the dev pack, deleting only the links and never the repository's files. Do this before publishing.

Caveats:
- **`.ui` files go live before the code.** A UI layout changes at once, but the Java that binds it changes only on the next deploy. If you remove or rename a `.ui` element that the code still binds, opening that page disconnects the player ("Failed to apply CustomUI event bindings"). Reinstall the jar (`.\deploy.ps1 -Dev`) before opening the page.
- **Create a new asset before the JSON that references it.** If you add a new file (for example a `.blockyanim`) and its referencing JSON at the same moment, the JSON may reload first. It then fails ("Common Asset ... doesn't exist!") and can drop a whole asset set. Create the asset, then edit the JSON a few seconds later. To recover, save the JSON again.

## Wand assets

- **Models and textures.** Each wand has its own model and texture under `Common/Items/MobBattle/<wand>/`, first copied from the game's wood wand as an editing template. Files are named after the item ID, e.g. `MobBattle_KillWand.blockymodel` and `MobBattle_KillWand_Texture.png`. Export from Blockbench to those paths (with `-Dev` they hot-reload); the item JSON's `Model`, `Texture` and `Icon` fields point at them. Put any new custom model or texture under `Common/` too.
- **Icons.** Each wand has its own inventory icon in `Common/Icons/Items/MobBattle/<wand>.png`.
- **Source art.** `art-source/` is git-ignored: local Blockbench projects and reference models. Only what is in `Common/` ships.

Wand effects are plain JSON, so they hot-reload too:
- **Cast animation and sound:** the `Effects` block of each interaction in `Server/Item/Items/MobBattle_*.json` (`ItemAnimationId`, `ItemPlayerAnimationsId`, `WorldSoundEventId`, `CameraEffect`).
- **On the mob:** `Server/Entity/Effects/MobBattle/Fx/MobBattle_Fx_*.json` (particles, tint, sound, duration).
- **A part of the wand that lights up** (e.g. an orb): name that part `Orb` in Blockbench and add to the interaction's `Effects`:
  `"Particles": [{ "SystemId": "Magic_Hit", "TargetEntityPart": "PrimaryItem", "TargetNodeName": "Orb" }]`.
- **An always-on wand animation:** the item's root `"Animation"` field, e.g. `"Animation": "Items/MobBattle/ArmyWand/idle.blockyanim"`, like the vanilla Crystal Flame staff. Every wand has an idle animation this way. It animates the wand's own model nodes, not the player skeleton (that is `Server/Item/Animations/`). See the allowed asset roots under [Project layout](#project-layout).
- **Timer box layout:** `Common/UI/Custom/MobBattle/TimerHud.ui`, edited like any `.ui` file (the dev pack reloads it).

## Asset generator scripts

Many assets come in every palette color. They are generated by Python scripts in `scripts/`; the generated files are committed, so you only need these scripts to change or regenerate them. Run them from the repository root, for example `python scripts/gen-looks.py`. They overwrite their own output.

| Script | Reads | Generates |
|---|---|---|
| `gen-crowns.py` | The game's gold crown (`Crown_Gold` particles) in `Assets.zip` | Crown and light particle systems per color, plus the effects that use them: the lone target crown, the buff crown (with `_S150` to `_S300` sizes), the Cleanse Wand look and the Buff Wand light (both with `_H1` to `_H8` heights). |
| `gen-looks.py` | Vanilla particles, spawners and the `LightningVoid.png` texture in `Assets.zip` (needs Pillow) | The Jade and Shimmer looks per color: model VFX, infinite effects and their "twin" copies, appear variant, death looks (jade shatter, shimmer turn and burst), the Buff Wand shimmer and rune looks in every size, eye flame and rune particles, and recolored rune textures in `Common/Particles/Textures/MobBattle/`. |
| `gen-summonrunes.py` | The game's `Praetorian_Summon_Spawn` particles in `Assets.zip` | The summon rune (the circle a summoned mob rises from) per color. |
| `gen-bufflook.py` | This repository's mark effects (`Server/Entity/Effects/MobBattle/Fx/Mark/`), not `Assets.zip` | The old Buff Wand tint and aura looks (`MobBattle_Fx_BuffLook_<Color>`). The current buff look comes from `gen-looks.py`, and these are kept only for mobs saved with them. |

Some practical notes:
- **Where `Assets.zip` comes from.** The scripts read it from the Hytale launcher's install folder: the pre-release patchline under `%APPDATA%` on Windows. If yours is elsewhere, edit the path constant near the top of the script (`ASSETS` or `ASSETS_ZIP`). The scripts only read the archive; they never change it.
- **Palette.** The colors are copied from `Teams.PALETTE` at the top of each script. Keep them in sync if the palette changes.

## Project layout

| Path | Contents |
|---|---|
| `src/main/java/dev/dackoro/mobbattle/` | The plugin. `MobBattlePlugin` registers the interactions, systems and the `/mobbattle` command (`MobBattleCommand`); `FxSettings` holds the settings (`fx.properties`); `ConfigPage` / `ConfigSchema` are the settings panel; `MobBattleApi` is the public API. |
| `Common/` | Client assets: `Items/MobBattle/<wand>/` (wand models and textures), `Icons/Items/MobBattle/` (item icons), `Particles/`, `Sounds/`, `Characters/`, `UI/Custom/MobBattle/` (HUDs and panel layouts). |
| `Server/` | Server definitions: `Item/Items/MobBattle_*.json` (the wands), `Item/Animations/`, `Entity/Effects/MobBattle/`, `Entity/ModelVFX/MobBattle/`, `Entity/GameMode/`, `Particles/MobBattle/`, `Audio/`, `Languages/<locale>/server.lang`. |
| `scripts/` | Asset generators and the dev-mode sync helper. |
| `docs/` | Public documentation. |
| `manifest.json`, `icon-256.png` | Mod identity and mod-list icon. |

Asset paths follow Hytale's rules:
- **Item icons** must live under `Common/Icons/Items/`. Any other `Icon` path makes the item fail validation ("Invalid Item").
- **An item's `Animation` file** must live under one of the allowed asset roots: `Items/`, `Blocks/`, `NPC/`, `VFX/`, `Resources/` or `Consumable/`.
- **Paths inside the jar are case-sensitive.** Keep folder names exactly as the vanilla ones (`Server/Item/Items/`, not `server/item/items/`).

## Conventions

### New commands and settings

Every new command or setting must be added in all of these places:

1. **In-game help:** `MobBattleCommand.HELP`, under the right topic.
2. **Settings description:** `FxSettings.describeLines()`, which drives `/mobbattle settings`. Persist the setting in `FxSettings` load/save.
3. **Reset group:** `Presets.groupOf(key)`, so that `/mobbattle reset <group>` covers it.
4. **Settings panel:** a field in `ConfigSchema`. The panel logs any `fx.properties` key that has no field the first time it opens.
5. **Docs:** the setting's row (command, key, values, default, reset group) in [docs/settings.md](docs/settings.md), plus its description in the fitting page under `docs/`. The README only lists the main command groups; update it when a new group or feature appears.

Two more rules:
- **Security keys.** The permission mode and the debug toggles must never be changed by presets, resets or Undo; see `Presets.isSecurityKey`. A new setting of that kind belongs there too.
- **Command spelling.** In docs, help and chat messages, write commands in camelCase (`/mobbattle debugLog`, `/mobbattle skipTimer`). The engine lowercases command names, so chat suggestions always appear in lowercase.

### Permissions

Classify every new command path in `MobBattleCommand.requiredPermission` as public, `use`, `config` or `admin`. Gate every wand interaction with `Perms.interactionAllowed`. See [server-setup.md](docs/server-setup.md).

### Code rules

- **Keep per-tick work cheap.** `FightTickSystem` runs for every NPC on every tick, so code for NPCs that aren't in a battle must stay O(1). Check `BattleManager.isManaged` / `FightManager.isFighter` before scanning any list.
- **Change components on the world thread.** Code that changes components from a system's per-second tick (damage, effects, teleports, HUDs) must queue the work with `world.execute(...)`.
- **Key long-lived maps by the `Ref` itself.** Never use `Ref.getIndex()`: indexes move when entities are removed.
- **Prune maps and objects that hold a `Ref` or a `Store`.** Drop invalid refs and stores that have shut down, as `BattleManager.maintain()` does once per second. A `Ref` keeps its whole world alive.
- **Store player UUIDs, not `PlayerRef`s.** Look players up again with `Universe.get().getPlayer(uuid)`, because a reconnect creates a new `PlayerRef`.

### Translations

Client-visible names live in `Server/Languages/en-US/server.lang`. A new item, title or HUD label needs its key there. See the README's Translations section for adding a language.
