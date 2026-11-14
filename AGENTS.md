# 动感力学 Kinetica: project guidance

A walnut-and-brass physics bench in a PICO OS 6 **Full Space Mixed Stage** (Spatial SDK BOM 6.1.9), package
`io.embodit.kinetica`, v0.1.3. Design contract: spatial-app-factory `apps/kinetica/design-package.json` (A skeleton,
container STAGE_MIXED, user 2026-11-12 profile D53; Gate B 2026-11-13 = defaults + the user's changes: ▶ swing keys,
Mars, a 3D planet selector with ◀ ▶, 3D text >= 0.5°). The physics, the bench scene and the window pages are this app's own.

## Surfaces
- **Stage** (default container `KineticaStage`, style Mixed): the bench placed 0.66 m ahead of the eye, origin 0.35 m
  under it (S8, waits for a level and still head, 3 s timeout; v0.1.2 DV-K13), facing you; settings [叫回面前] places it
  again. Walnut base (grab it to move the bench, S7; stick left / right turns it while the ray is on the base; snaps to
  the dashed default frame within 6 cm / 10°), two brass posts, crossbar, three stations (rod / spring / double) with
  tags (two lines, canvas 2.5 mm off the backing). **Two walnut wings** on the ears, beside the control window and in the
  level default view (QA #2 q2-1): the **left wing** has one row per station (top = left station): readout plate + ▶
  swing key (tap = default swing, tap again = stop; the key names its hanger); the **right wing** has the planet
  selector (globe of the current planet between ◀ ▶ keys, name + g label; tap the globe = next) and the KINETICA plaque
  (tap = reopen the control window). Head inside the bench -> 40 % opacity (D14).
- **Control window** (`KineticaControl`, Planar 800 x 420 dp landscape, two columns, opened by the system ahead of the
  head through the level gate; it ends ~15° under the eye, the hangers sit below it, the wings beside it): native
  SpatialUI `TabBar`, two pages 玩 / 设置; the play page has the same planet ◀ ▶ row.
- Input: board-input template (system tap / drag; controller ray, hand ray, eye-hand with the distance gain, direct
  pinch) + a hand-joint direct-grab fallback (`platform/Trackers.kt` DirectGrab). No app-drawn ray (D41 更正).

## Code
| Path | What |
|---|---|
| `domain/Bench.kt` | the physics (Planet, ordered from the Sun: Mercury 3.70 / Venus 8.87 / Earth 9.81 / Moon 1.62 / Mars 3.71 / Jupiter 24.79 / Saturn 10.44 / Uranus 8.69 / Neptune 11.15 / Pluto 0.62 dwarf; g in BenchConfig.G_*; a change rests every station on the new g), autoSwing / stopStation |
| `domain/BenchFlow.kt` | the single state source: input entry points, phases S0-S4 / S7 / S8, tips, coach, sounds, prefs |
| `domain/StagePose.kt` | bench pose in the Stage: default pose, drag clamp, snap, stick turn, near-head fade, PlaceWait |
| `domain/Layout.kt` | fixed positions (tags, the two wings: plates, keys, selector, plaque), design eye, occlusion / default-view helpers |
| `ui/bench/*` | `BenchScene` (Stage entities, per-frame placement in the ECS system), `BenchScreen` (board-input wiring, names), `BenchTextures` |
| `ui/control/*` | the window pages (Settings has a 怎么操作 / How to sub-screen); `VolumeDrag` = the three-stop slider logic (free Float, taps and short drags change the stop); every Text logs `OVERFLOW <tag>` when cut |
| `platform/*` | `WindowCtl` (level-gated window), `Trackers` (head, controller stick, hands, DirectGrab) |
| `tools/` | `build_bench.py` (GLBs + CC0 PBR), `build_planets.py` (procedural globes for all ten bodies; Saturn / Uranus with opaque ring geometry), `import_cc0_sounds.py`, `loudness.py`, `build_icon.py`, `contrast.py`, `kt.sh` |

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest
python3 tools/build_bench.py ; ~/.factory-venv/bin/python tools/build_planets.py
DEV=emulator-5562 tools/kt.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow kinetica` in the factory, return after.
Cold start, level camera = the default view (everything you read or tap is in it). Low / side views: `pico-cli camera move -d <serial> --text "pitch down 30 degrees"` / `"turn left 25 degrees"`.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.kinetica.DEBUG -p io.embodit.kinetica --es cmd <cmd> --es a "<arg>"` (or
`tools/kt.sh cmd <cmd> [arg]`). Log tag `Kinetica`. Every input command goes through the same listener / BenchFlow calls as
a real pinch (D29).

| State | Command | Notes |
|---|---|---|
| S0 准备中 | `s0hold on`, force-stop + launch, capture within 10 s, `s0hold off` | the plain slab stand-in + spinner |
| S1 静止 | `preset default` · `coach on|off` · `planet mercury|venus|earth|moon|mars|jupiter|saturn|uranus|neptune|pluto` | coach = left rod ball glows |
| S2 捏住 | `hold S2` | left rod held at 60°, spring stretched |
| S3 运动中 | `hold S3` (= preset moving + 3.0 s, frozen) · `preset collide` (left ball hits the middle one) | `hold off` unfreezes |
| S4 停下来 | `hold S4` | settling frozen at 0.3 s |
| S5 换挂法 | `hold S5` | middle station mid-swap (150 ms frame) |
| S6 改长短 | `hold S6` · `sizes rod|spring|double` | three sizes side by side |
| P1 / P2 | `page play|settings` · `howto on|off` (Settings → 怎么操作) · `lang zh|en|system` | |
| P3 退出确认 | `exitask` · `cancel` · `confirm` | |
| P4 面板已关 | `closepanel` · `tap nameplate` reopens | |
| S7 挪台子 | `drag "bench 0.2 0 0 hold"` · `rotate "bench 30"` · `release-bench` | dashed frame shows while held |
| S8 摆到面前 | `recall` (settings [叫回面前]) · `place` logs the placement | 「看正前方」card while waiting |
Other: `tap "bob <i> <k>"|"tag <i>"|"go <i>"|prev|next|globe|nameplate`, `drag "bob <i> <k> <dx> <dy> <dz> [hold]"`, `drag "tag <i> <dy>"`,
`release "<vx> <vy> <vz>"`, `replay`, `stop`, `slow on|off`, `restore`, `preset chaos`, `size "<i> <s>"`,
`mat "<i> <k> wood|steel|brass"`, `type "<i> rod|spring|double"`, `tip <0-6>`, `answer "<tip> <station>"`, `ff <s>`, `stick <x|off>`,
`freeze on|off`, `ftest on|off` (F↑ on every brass face, D10), `mute on|off`, `volume 0-2`, `dump`, `instances`.
Stations: 0 left, 1 middle, 2 right; bob k = 0 (upper) / 1 (the double's lower bob).

## Screenshot list (QA)
S0; S1 zh/en, coach on/off, each of the ten planets (zh + en), ▶ keys still / ■ moving; S2; S3 + collide frame; S4; S5 x3 hangers; S6 sizes;
P1 with S1/S2/S3, tip 1 + answered, [再放一次] grey; P2 zh/en; P2 怎么操作 zh/en; P3 zh/en; P4 closed + reopened.

## Known
- 3D pinches / drags are not reachable by `pico-cli interact` on the Mac emulator: driven by the debug broadcast through
  the same listener calls; the three inputs (direct grab, controller ray, eye-hand) and the 3D pull feel need the headset.
- The SpatialView drag axes are taken as the Stage's (beat-pad did the same); confirm on the headset that a drag right
  moves the ball right after the bench was turned.
- Sounds are SoundPlayer (not spatial); all CC0 Kenney recordings.
- One hand at a time; "two hands, one ball each" is not built (deviation DV-K3).
