# Grimm Eclipse in Minecraft (Fabric 1.21.4, client only)

Ruby from RWBY: Grimm Eclipse, played inside Minecraft, built from the player's own copy of Grimm Eclipse.
The jar ships no game assets. A converter (`GrimmConvert`, see `../GrimmConvert`) reads the player's game on first start.

## First start
The mod runs `GrimmConvert --check` in the background when Minecraft starts. If the data is missing or the game changed, it
runs the full conversion (about 40 s). A panel on the title screen and in the world shows progress, a "ready" message, or a
plain-language error (game not found, unsupported build, unreadable texture, disk problem). Pressing O or R after a failure retries.
`title-converting.png` and `title-ready.png` show it.

## Configuration
Melty writes `%LOCALAPPDATA%\GrimmMC\config.properties` before every Play (forward slashes):
```
ge_dir=<Grimm Eclipse folder>
converter=<path to GrimmConvert.exe>
data_dir=<where converted data goes, default %LOCALAPPDATA%\GrimmMC\data>
```
`GRIMMMC_HOME` moves the whole folder (used for tests). With no `ge_dir` or `converter`, the mod only uses data already in `data_dir` (development).

## Playing
Press **O** to become Ruby (third person, vanilla model and hand hidden); O again releases her. She follows your position and facing,
plays Idle / Walk / Run / Jump / JumpFall / JumpLand from how you move, and fights with Minecraft's own inputs, ported from
Grimm Eclipse's PC controls and move timings (singleplayer only; hostile mobs are her enemies):

| Input | Ruby |
|---|---|
| Left click | Light combo (press again inside the combo window to chain; ground and air versions) |
| Right click (hold) | Heavy attack: hold to charge, release to swing; after a light hit it becomes the matching heavy combo move |
| Shift (tap) | Dodge (invulnerable 0.7 s on the ground, 0.5 s in the air); attack right after it for the dash attack. Hold Shift while moving forward to sprint |
| Q (hold) | Ranged shot (Crescent Rose hitscan, 3rd shot in a combo is a heavier one) |
| F | Counter an enemy that is attacking you; with nothing to counter she plays the failed-counter animation |
| Middle click | Special: Crescendo, once the Special bar is full (24 landed hits) |
| Ctrl (tap, standing still) | Reap: warps to a nearby enemy (within 9 blocks), spins three hits and a smashing finish. Grimm Eclipse's team attack, here as a solo move |
| WASD / Space | Move and jump; locked while a move is playing, as in Grimm Eclipse |

Moves auto-target the best enemy in front of you (Grimm Eclipse's scoring), turn toward it and stop short of it. Damage, knockback and launch use
Grimm Eclipse's numbers through `damage_scale` (default 0.15) and `knockback_scale` (default 0.35) in `config.properties`.
The HUD (top left) shows Aura (4,000, soaks damage before your health; recharges after 3 s, 5 s if emptied), Health (1,000 = 20 hearts) and Special.
Sound and effects use Minecraft's own sounds and particles (rose petals are red dust); Grimm Eclipse's own audio and effects are not used. Not ported: taunt, the upgraded Crescendo variants' selection, sniper form.

**Developer keys** (only with `debug=true` in `config.properties`): `[` places Ruby, `,` / `.` browse all 46 clips.
## How it works
- CPU skinning each frame, 5,869 vertices x 4 weights. Unity left-handed Y-up data is mirrored on X for Minecraft.
- Submesh 2 is a glow sprite and is not drawn. The cape is submesh 1.
- Crescent Rose parts ride on the clip's `Bip001 L Hand` child nodes; the clip's scale curves show one form and shrink the other to 0.01.
- Facing: Unity +Z is Ruby's forward. In possess mode it follows the player's body yaw; in placed mode she is turned toward the player.
  Clips are not re-aligned: combat clips carry their own stance twist, locomotion clips face +Z exactly.

## Melty test mode (unattended)
While `%LOCALAPPDATA%\Melty\test\<slug>.json` exists (`{testId, run, runs, seed, checks}`), the mod runs this scenario by itself and appends
`pass <name>` / `fail <name>: why` lines to the file named by `checks`. It never quits the game; Melty closes it.
Scenario: wait for the data conversion, open the world screen, create a flat creative world with Melty's seed, load Ruby, possess her,
walk forward, stop, then fight a husk that stands still: four left clicks must start her light combo, damage the husk and finish. Anything not reached when the scenario ends is reported as failed, so a run cannot pass by omission.

| Check | Passes when |
|---|---|
| `data-ready` | the converter finished and `ready.json` and every file it lists exist |
| `rig-loaded` | Ruby's rig and texture loaded (clips and bind poses present) |
| `ruby-rendered` | at least 5 frames drew over 1,000 triangles and no drawing error occurred |
| `possess-moves` | the clip became Walk or Run while the player walked, then Idle after stopping |
| `attack-played` | the light attack clip played and the avatar returned to the Idle loop |

Local runs: `GRIMMMC_TEST_DIR` moves the test folder, `GRIMMMC_TEST_QUIT=1` quits after the last check (never set by Melty).
Set `MeltyTest.SLUG` to the mashup's slug after `create_mod` to ignore other mashups' test files.
Results on this PC (2026-10-19): warm run 5/5 pass in 50 s; first run with fresh options and empty data (conversion included) 5/5 pass in 83 s;
with `ruby_texture.png` deleted all five fail with reasons, as they should.

## Self-tests (opt-in, never set for players)
`GRIMMMC_AUTO=1` with `GRIMMMC_MODE`:
- `title`: starts at the title screen, shoots the conversion panel mid-conversion and at its end (ready or failed), then quits.
- `placed` (default): places Ruby, shoots clips at fixed times (`GRIMMMC_CLIPS="Walk:0,Run:0.15"` to choose). Needs `--quickPlaySingleplayer <save>`.
- `possess`: possesses, walks, sprints, jumps and attacks with the real movement keys, with screenshots. Needs `--quickPlaySingleplayer <save>`.
Use a fresh copy of the save each run (the player's saved position changes).

## Build tools (temp, not in this folder)
JDK 21, Gradle 8.12, Fabric Loom 1.9, Yarn 1.21.4+build.8, Fabric API 0.119.4+1.21.4. Build from a short path: the scratch folder path is too long for Windows.
Files written with Windows PowerShell 5.1 `Set-Content -Encoding utf8` get a byte-order mark, which Gradle rejects: write without one.

## Known gaps
- Verified by scripted self-tests only (plus one manual play-through before the rename): the wrong-build and unsupported-texture error paths have not been exercised.
- Not yet run by Melty itself (`test_release`), only by a fake test file; no hit detection, sound or sniper-form switching.
- Vanilla hand and held item still show in first person; Ruby is only drawn in third person.
- Fixed full-bright lighting.