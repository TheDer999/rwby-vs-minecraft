# GrimmConvert

Reads the player's own copy of RWBY: Grimm Eclipse (read-only) and writes the data the Minecraft mod needs into a private folder.
No game asset is shipped; everything is converted on the player's PC. Version 0.1.0, character: Ruby.

```
GrimmConvert --ge <Grimm Eclipse folder> --out <output folder> [--force] [--check] [--character ruby]
```

| Flag | Meaning |
|---|---|
| `--ge` | Grimm Eclipse install folder (or its `rwby-ge_Data` folder) |
| `--out` | Output folder (created if missing) |
| `--force` | Convert even if `ready.json` says the output is current |
| `--check` | Do nothing except report: exit 0 if up to date, 10 if conversion is needed (about 0.1 s) |
| `--character` | `ruby` (only one for now) |

## Output (`--out`)
`ruby_rig.json`, `ruby_texture.png`, `crescent_rose.json`, `crescent_texture.png`, and `ready.json` (written last: its presence means the rest is complete;
it records the converter version, the source file size, timestamp and SHA-256, and what was converted).
Files are built in a temp subfolder and moved into place, so a crash never leaves a half-written set that looks ready.

## stdout protocol (for the mod's progress screen)
`PROGRESS n/6 text`, `INFO text`, `DONE folder`, `UPTODATE`, `ERROR code text`.

## Exit codes
0 ok, 1 unexpected, 2 game not found, 3 asset missing or unrecognised build, 4 unsupported texture format (Crunch), 5 io, 10 (`--check`) not ready, 64 usage.

## How it finds things (by name, not by id)
- Body mesh `RubyBody_GEO` via the SkinnedMeshRenderer that draws it, its bones via the renderer's transforms, its texture via the first material's `_MainTex`.
- Animations: every AnimationClip with a rotation curve whose path is exactly `Ruby`, Idle first.
- Weapon: meshes `crescentRose_mesh` and `crescentRose_gun_mesh` grouped by the texture their material uses; the group with the largest texture this converter can decode wins.
- Known build (Steam depot 5951204, `sharedassets0.assets` 444,620,024 bytes) is recorded in `ready.json`; other builds still convert by name and are flagged `knownBuild: false`.

## Verified (this PC, 2026-10-19)
Output of the dotnet build and of the self-contained single-file publish is byte-identical to the files extracted earlier by hand and played in the Fabric mod.
Conversion takes about 37 s. Not tested: an unrecognised build, a damaged install, or an unsupported (Crunch) texture; the error paths for those exist but have only been exercised for missing/wrong folder and bad arguments.

## Build
.NET 8 SDK, AssetsTools.NET 3.0.5 (MIT), `classdata.tpk` from the UABEA project (MIT).
`dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true`
produces a 33.6 MB `GrimmConvert.exe`; ship it with `classdata.tpk` beside it.
Build from a short path: the scratch folder path is too long for Windows.

## Sounds
The converter also exports Ruby's sound effects and voice lines (AudioClips named ruby_*, Ruby - * and weap_crescentrose_*) as sound_*.wav. It cuts each FMOD FSB5 clip out of the game's resource file and decodes it with vgmstream-cli (ISC licence, https://github.com/vgmstream/vgmstream, release r2117, the vgmstream-win64.zip build, SHA-256 6c4a8a3813864fefed081bbd337dbc0ad93bf88e0b92f5db98d7ab258b22dc6c), which must sit in a gmstream folder beside GrimmConvert.exe. The decoder is not stored in this repository; download that release and unpack it there. Without it the sounds are skipped and the rest of the conversion still works.
