# Changelog / 更新记录

## v0.1.3 (2026-11-14)

中文
- 设置里的音量条点哪就到哪：点 0 / 50 / 100% 直接换档，轻轻拖一下也会换一档，松手吸附，换档时响一声让你听到新音量。
- 星球选择器扩到整个太阳系：水星 3.70、金星 8.87、地球 9.81、月球 1.62、火星 3.71、木星 24.79、土星 10.44、天王星 8.69、海王星 11.15、冥王星 0.62（矮行星），单位 m/s²，按离太阳远近排，◀ ▶ 循环。土星带环，天王星带细环。
- 换星球时球从一边滑出缩小、新的从另一边滑进放大，约 1/3 秒。
- 换星球时三个挂位回到新重力下的平衡位置、速度清零：弹簧不再过 35 s 自己弹，也不再留下 ±2 mm 侧晃。
- 设置里加「怎么操作」：每个主要动作一行，手势和手柄的做法并排写。

English
- The volume slider in Settings responds to taps: tap 0 / 50 / 100 % to jump there, a small drag moves one stop, it snaps on release and clicks so you hear the new level.
- The planet selector now covers the solar system: Mercury 3.70, Venus 8.87, Earth 9.81, the Moon 1.62, Mars 3.71, Jupiter 24.79, Saturn 10.44, Uranus 8.69, Neptune 11.15, Pluto 0.62 (dwarf planet), in m/s², ordered from the Sun, ◀ ▶ wrap around. Saturn has its rings, Uranus a thin ring.
- Changing planet slides the old globe out and grows the new one in (about 1/3 s).
- A planet change puts every hanger on its new rest with zero velocity: no spring waking up 35 s later, no ±2 mm sideways sway.
- New "How to" screen in Settings: one row per main action, hand gesture and controller side by side.

## v0.1.2 (2026-11-13)

中文
- 平视就能看到、点到所有东西：控制窗改成 800 × 420 dp 两栏，台子退到眼前 0.66 m；读数铜牌和 ▶ 键放左翼板，星球 ◀ ▶ 和铭牌放右翼板。
- 挂牌文字不再闪烁破面，低头时第二行不再被牌身切掉。
- 停下以后铜牌保留最后一次实测周期（摆杆 0.86 s / 理想 0.85 s，弹簧 0.28 s / 0.28 s）。
- 双摆停住后「这次放了 N s」不再继续计时。
- 星球选择器的 ◀ 从座位上看是向左箭头。

English
- Everything you read or tap is in the level default view: the control window is now 800 × 420 dp in two columns, the bench sits 0.66 m ahead; readout plates and ▶ keys on the left wing, planet ◀ ▶ and the plaque on the right wing.
- Hanger tags no longer z-fight; their second line is no longer cut when you look down.
- After stopping, plates keep the last measured period (rod 0.86 s vs 0.85 s ideal, spring 0.28 s vs 0.28 s).
- The double pendulum's "released N s ago" stops counting once it is stopped.
- The planet selector's ◀ key reads as a left arrow from the seat.

## v0.1.1 (2026-11-13)

- Full Space bench within reach (was a Shared Space volume); a ▶ swing key in front of every station (tap again to stop); Mars added (Moon / Mars / Earth / Jupiter); a 3D planet selector with ◀ ▶; all 3D text resized to ≥ 0.5°.
- Full Space 台子伸手可抓；每个挂位前一颗 ▶ 摆起来键；加火星；3D 星球选择器；3D 字全部按 0.5° 重算加大。

## v0.1.0 (2026-11-11)

- First build: rod, spring and double pendulum stations, swap hangers / sizes / balls / gravity, measured vs ideal period, replay, slow-mo, Chinese and English.
- 第一版：摆杆、弹簧、双摆三挂位，换挂法 / 长短 / 球 / 重力，实测与理想周期，再放一次，慢放，中英双语。
