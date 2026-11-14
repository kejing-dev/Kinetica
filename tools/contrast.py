"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio), panel and 3D brass faces."""
def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def hexrgb(h): h = h.lstrip('#'); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def Y(h): r, g, b = (lin(v) for v in hexrgb(h)); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def ratio(a, b): ya, yb = sorted((Y(a), Y(b)), reverse=True); return (ya + 0.05) / (yb + 0.05)
GLASS = "#B4B3B1"  # darkest measured window glass (factory measure)
pairs = [
    ("标题 / 周期行 Ink / 窗口玻璃", "#1B1F23", GLASS, 4.5), ("重力行 / 灰键原因 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("关于 / 脚注 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("状态卡 Ink / Card", "#1B1F23", "#FBF6EC", 4.5), ("试试看标签 Secondary / Card", "#7A5A1E", "#FBF6EC", 4.5),
    ("试试看正文 Ink / Card", "#1B1F23", "#FBF6EC", 4.5), ("退出确认小字 InkSecondary / Card", "#3A3F44", "#FBF6EC", 4.5),
    ("主按钮 [再放一次] 白 / 毡绿 Primary", "#FFFFFF", "#23412F", 4.5), ("开关开（静音 / 慢放）白 / 毡绿", "#FFFFFF", "#23412F", 4.5),
    ("退出键 白 / Error", "#FFFFFF", "#B3261E", 4.5), ("次按钮字 Ink / fillSecondary≈玻璃", "#1B1F23", GLASS, 4.5),
    ("铜牌 / 挂牌 墨字 / 黄铜亮端", "#2A1E14", "#E2C280", 4.5), ("铜牌 / 挂牌 墨字 / 黄铜暗端", "#2A1E14", "#A88240", 4.5),
    ("拨盘 墨字 / 黄铜", "#2A1E14", "#C9A25A", 4.5),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |\n|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b); print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")
