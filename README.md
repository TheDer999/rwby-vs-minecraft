# RWBY VS Minecraft

Play Ruby from *RWBY: Grimm Eclipse* inside Minecraft: Java Edition (Fabric 1.21.4), with Minecraft's own inputs and Grimm Eclipse's move timings and numbers.

**Install and play:** [melty.gg/m/rwby-vs-minecraft](https://melty.gg/m/rwby-vs-minecraft) (free, one click through Melty). You need your own copy of Grimm Eclipse (Steam app 418340) and a Minecraft: Java Edition account.

**This repository contains no game assets.** Ruby's model, outfit, Crescent Rose, animations and combat numbers are converted on your own PC from your own Grimm Eclipse install by the converter below, and are never uploaded or redistributed.

| Folder | What it is |
|---|---|
| `mod/` | The Fabric mod (Java 21): rendering, possession, combat, HUD, Melty test mode. See `mod/README.md` |
| `converter/` | `GrimmConvert` (.NET 8): reads the Grimm Eclipse install read-only and writes the data the mod needs. See `converter/README.md` |
| `melty-recipe.json` | The install recipe used on Melty |

## Controls (singleplayer, press O to become Ruby)
Left click light combo, hold right click heavy, Shift dodge, Q ranged, F counter, middle click special once the Special bar is full, Ctrl (standing still next to an enemy) Reap.

## Building
- Mod: JDK 21, Gradle 8.12, Fabric Loom 1.9, Yarn 1.21.4+build.8, Fabric API 0.119.4+1.21.4 (`gradle build` in `mod/`). Build from a short path on Windows.
- Converter: .NET 8 SDK, `dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true` in `converter/`.

## Status
Version 0.5.0: light, heavy, ranged, counter, dodge, the Crescendo special and Reap, auto-targeting, aura/health/special meters, sounds and effects built from Minecraft's own, aimed ranged shots, sprint in any direction and swimming. Not yet: Grimm Eclipse's own audio, taunt, multiplayer.

## Credits and licence
Made by Der999 with AI assistance (Claude, by Anthropic, through Claude Code). Source code: MIT, see `LICENSE`. RWBY and RWBY: Grimm Eclipse belong to their rights holders; this is an unofficial fan project, not affiliated with them. The move timings and numbers in `Moves.java` were ported by reading how the game behaves.
Third-party: AssetsTools.NET and the UABEA class database (MIT), Mono.Cecil (MIT), Fabric (Apache-2.0).