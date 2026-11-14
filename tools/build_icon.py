"""Kinetica icon (design appIcon, background moved from felt #23412F to moss-felt #3F5A1A: the darker greens are taken by
spatial-calc / cam-coverage / keycap-studio): a brass rod hanging from a brass crossbar at a slant, a bright brass bob,
a warm-white arc behind it for the swing. No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same shapes).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
FELT = (0x3F, 0x5A, 0x1A)
BRASS = (0xC9, 0xA2, 0x5A)
BOB = (0xE0, 0xB4, 0x62)
SHINE = (0xF8, 0xE3, 0xB0)
ARC = (0xFF, 0xE9, 0xC4)

PIVOT = (54.0, 30.0)
L = 42.0
ANG = math.radians(32)
BOBC = (PIVOT[0] + L * math.sin(ANG), PIVOT[1] + L * math.cos(ANG))
BOBR = 10.5
BAR = [(28, 26.5), (80, 26.5), (80, 31.5), (28, 31.5)]
ARC_R, ARC_W, ARC_A0, ARC_A1 = 48.0, 3.0, -38, 38


def rod_poly(w=2.4):
    dx, dy = BOBC[0] - PIVOT[0], BOBC[1] - PIVOT[1]
    l = math.hypot(dx, dy); nx, ny = -dy / l * w / 2, dx / l * w / 2
    return [(PIVOT[0] + nx, PIVOT[1] + ny), (BOBC[0] + nx, BOBC[1] + ny), (BOBC[0] - nx, BOBC[1] - ny), (PIVOT[0] - nx, PIVOT[1] - ny)]


def arc_pts(r, a0, a1, n=40):
    return [(PIVOT[0] + r * math.sin(math.radians(a)), PIVOT[1] + r * math.cos(math.radians(a))) for a in [a0 + (a1 - a0) * i / n for i in range(n + 1)]]


def arc_poly():
    outer = arc_pts(ARC_R + ARC_W / 2, ARC_A0, ARC_A1)
    inner = arc_pts(ARC_R - ARC_W / 2, ARC_A0, ARC_A1)
    return outer + list(reversed(inner))


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def circle(dr, c, r, k, fill):
    dr.ellipse(((c[0] - r) * k, (c[1] - r) * k, (c[0] + r) * k, (c[1] + r) * k), fill=fill)


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=FELT + (255,)))


def layer_frame():
    def d(dr, k):
        dr.polygon(sc(arc_poly(), k), fill=ARC + (255,))
        dr.polygon(sc(BAR, k), fill=BRASS + (255,))
        dr.polygon(sc(rod_poly(), k), fill=BRASS + (255,))
        circle(dr, PIVOT, 2.6, k, BRASS + (255,))
    return big(d)


def layer_bob():
    def d(dr, k):
        circle(dr, BOBC, BOBR, k, BOB + (255,))
        circle(dr, (BOBC[0] - 3.4, BOBC[1] - 3.6), 3.2, k, SHINE + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def circ_path(c, r):
    return "M%.2f,%.2fa%.2f,%.2f 0 1,0 %.2f,0a%.2f,%.2f 0 1,0 %.2f,0Z" % (c[0] - r, c[1], r, r, 2 * r, r, r, -2 * r)


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Kinetica launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(ARC), path(arc_poly())),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(BRASS), path(BAR) + path(rod_poly()) + circ_path(PIVOT, 2.6)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(BOB), circ_path(BOBC, BOBR)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(SHINE), circ_path((BOBC[0] - 3.4, BOBC[1] - 3.6), 3.2)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_frame(), layer_bob()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
