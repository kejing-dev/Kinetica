"""Planet globes for the gravity selector (user 2026-11-13: pick the planet by its model + ◀ ▶; add Mars).

  ~/.factory-venv/bin/python tools/build_planets.py      (Pillow + numpy)

User 2026-11-14: the whole solar system (Mercury, Venus, Saturn with its rings, Uranus with a thin ring, Neptune, the
dwarf planet Pluto added), same style.

Writes app/src/main/assets/models/planet_<name>.glb: a unit-diameter UV sphere (64 x 48, smooth
normals, OPAQUE) with procedural PBR maps (equirectangular 1024 x 512 base colour + normal from a height field, rough
dielectric), and tools/planet-maps.png (the four maps side by side, for ui-audit). Procedural, not copied from any image:
Moon = grey highlands + dark maria + craters; Mars = rust plains, dark albedo regions, a canyon streak, white polar caps;
Earth = blue oceans, hand-drawn rough continents (green / desert), ice caps, light clouds; Jupiter = cream / tan / brown
cloud belts with turbulence and the Great Red Spot. The same sphere layout as the bobs: +Y = north pole.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_bench import Lib, Mesh, OUT, sphere  # noqa: E402

W, H = 1024, 512
RNG = np.random.default_rng(20261113)


def noise(scale, octaves=5, seed=0, persistence=0.5):
    """Fractal value noise in [0, 1], tiles horizontally (W x H)."""
    rng = np.random.default_rng(seed)
    out = np.zeros((H, W), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        gw = max(2, int(scale * (2 ** o)))
        gh = max(2, gw // 2)
        g = rng.random((gh + 1, gw + 1)).astype(np.float32)
        g[:, -1] = g[:, 0]  # wrap in longitude
        im = Image.fromarray(g, mode="F").resize((W, H), Image.BICUBIC)
        out += np.asarray(im) * amp
        total += amp
        amp *= persistence
    out /= total
    return (out - out.min()) / (out.max() - out.min() + 1e-9)


def blur(src, r):
    """Gaussian blur of a float layer (an 'F' image or an array in 0..1), via 16-bit precision."""
    a = np.asarray(src, np.float32)
    hi = Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(r))
    return np.asarray(hi).astype(np.float32) / 255


def lat_grid():
    v = (np.arange(H) + 0.5) / H
    return np.repeat((90 - v * 180)[:, None], W, axis=1).astype(np.float32)


def lon_grid():
    u = (np.arange(W) + 0.5) / W
    return np.repeat((u * 360 - 180)[None, :], H, axis=0).astype(np.float32)


def to_img(rgb):
    return Image.fromarray(np.clip(rgb * 255, 0, 255).astype(np.uint8), "RGB")


def normal_map(height, strength):
    gy, gx = np.gradient(height.astype(np.float32))
    nx, ny, nz = -gx * strength, gy * strength, np.ones_like(height)
    l = np.sqrt(nx * nx + ny * ny + nz * nz)
    return to_img(np.stack([nx / l * 0.5 + 0.5, ny / l * 0.5 + 0.5, nz / l * 0.5 + 0.5], -1))


def mix(a, b, t):
    t = t[..., None]
    return np.asarray(a, np.float32) * (1 - t) + np.asarray(b, np.float32) * t


def hexc(h):
    return np.array([int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)], np.float32)


def stamp_ellipses(layer, items):
    """Draws (lon, lat, radius_deg, value) discs on an 'F' layer, widened by 1 / cos(lat) as on the globe."""
    d = ImageDraw.Draw(layer)
    for lon, lat, r, val in items:
        cx, cy = (lon + 180) / 360 * W, (90 - lat) / 180 * H
        rx = r / 360 * W / max(0.15, math.cos(math.radians(lat))); ry = r / 180 * H
        for dx in (-W, 0, W):
            d.ellipse([cx - rx + dx, cy - ry, cx + rx + dx, cy + ry], fill=val)


# ---------------------------------------------------------------- Moon

def moon():
    n = noise(6, 6, 1)
    h = 0.5 * n
    base = mix(hexc("#8E8C88"), hexc("#B9B6B0"), n)
    maria = Image.new("F", (W, H), 0.0)
    stamp_ellipses(maria, [(-20, 20, 18, 1), (5, 25, 12, 1), (20, 5, 14, 1), (-45, -5, 16, 1), (-10, -20, 10, 1), (35, 15, 9, 1),
                           (-60, 30, 12, 1), (50, -15, 8, 1), (-30, 45, 9, 1)])
    m = blur(maria, 10)
    m = np.clip(m * (0.7 + 0.6 * noise(10, 4, 2)), 0, 1)
    base = mix(base, hexc("#4E4D4C"), m * 0.85)
    h -= m * 0.3
    # craters: dark floor, bright rim (power-law sizes)
    floor = Image.new("F", (W, H), 0.0); rim = Image.new("F", (W, H), 0.0)
    items_f, items_r = [], []
    for _ in range(420):
        lon = RNG.uniform(-180, 180); lat = math.degrees(math.asin(RNG.uniform(-1, 1)))
        r = 0.6 + 7.0 * RNG.random() ** 3
        items_r.append((lon, lat, r * 1.15, 1.0)); items_f.append((lon, lat, r, 1.0))
    stamp_ellipses(rim, items_r); stamp_ellipses(floor, items_f)
    fa = blur(floor, 1.2); ra = np.clip(blur(rim, 1.2) - fa, 0, 1)
    base = mix(base, hexc("#D8D5CE"), ra * 0.55)
    base = mix(base, hexc("#6E6C69"), fa * 0.35)
    h += ra * 0.25 - fa * 0.2
    return base, h, 0.95


# ---------------------------------------------------------------- Mars

def mars():
    lat = lat_grid(); lon = lon_grid()
    n = noise(5, 6, 3); n2 = noise(3, 5, 4)
    base = mix(hexc("#B35A31"), hexc("#D08A52"), n)
    dark = np.clip((n2 - 0.52) * 4, 0, 1)
    # Syrtis-like dark wedge and a southern dark belt
    wedge = Image.new("F", (W, H), 0.0)
    stamp_ellipses(wedge, [(70, 10, 14, 1), (60, -5, 12, 1), (20, -20, 22, 1), (-30, -25, 20, 1), (-120, -20, 18, 1), (-20, 45, 16, 1)])
    wa = blur(wedge, 14)
    dark = np.clip(dark + wa * (0.4 + 0.8 * n2), 0, 1)
    base = mix(base, hexc("#5C2E1C"), dark * 0.75)
    # canyon streak near the equator
    canyon = np.exp(-((lat + 8 + 3 * np.sin(np.radians(lon) * 3)) / 3.0) ** 2) * ((lon > -110) & (lon < -40))
    base = mix(base, hexc("#4A2416"), canyon * 0.8)
    # polar caps with a ragged edge
    edge = 76 + 6 * noise(8, 4, 5)
    cap = np.clip((np.abs(lat) - edge) / 3, 0, 1)
    base = mix(base, hexc("#F3EFE8"), cap)
    h = n * 0.5 - dark * 0.15 - canyon * 0.3 + cap * 0.1
    return base, h, 0.9


# ---------------------------------------------------------------- Earth

CONTINENTS = [
    # rough outlines (lon, lat), drawn by hand for a recognisable globe; coasts are roughened by the noise warp below
    [(-168, 66), (-140, 70), (-95, 72), (-80, 63), (-62, 58), (-55, 50), (-66, 44), (-75, 35), (-81, 25), (-82, 30), (-90, 29), (-97, 26),
     (-97, 20), (-87, 21), (-83, 10), (-78, 8), (-90, 15), (-105, 20), (-112, 30), (-117, 33), (-124, 40), (-124, 48), (-135, 58), (-152, 58), (-165, 60)],
    [(-55, 60), (-42, 60), (-20, 70), (-18, 80), (-40, 83), (-60, 80), (-70, 77), (-55, 70)],
    [(-80, 8), (-72, 12), (-62, 10), (-50, 0), (-35, -5), (-38, -13), (-41, -22), (-48, -27), (-58, -38), (-65, -42), (-68, -52), (-74, -52),
     (-73, -40), (-71, -30), (-70, -18), (-76, -14), (-81, -5), (-80, 0), (-77, 7)],
    [(-10, 36), (-9, 43), (-1, 44), (-4, 48), (2, 51), (8, 54), (10, 58), (5, 62), (15, 69), (28, 71), (40, 68), (60, 70), (70, 73), (90, 76),
     (110, 77), (140, 72), (160, 70), (180, 68), (180, 65), (170, 60), (160, 58), (156, 51), (143, 59), (135, 54), (140, 47), (130, 42), (129, 35),
     (122, 40), (122, 30), (118, 24), (108, 21), (106, 10), (100, 14), (103, 1), (98, 8), (94, 16), (90, 22), (80, 15), (78, 8), (73, 20),
     (67, 25), (57, 25), (52, 28), (48, 30), (56, 24), (59, 22), (52, 16), (44, 12), (43, 15), (35, 28), (33, 31), (36, 36), (28, 37), (26, 40),
     (23, 36), (21, 40), (19, 42), (14, 45), (12, 44), (16, 40), (16, 38), (12, 38), (10, 44), (3, 43), (-1, 37), (-6, 36)],
    [(-17, 21), (-17, 15), (-12, 8), (-5, 5), (5, 4), (9, 4), (10, -2), (13, -8), (12, -17), (15, -27), (18, -34), (25, -34), (32, -28), (35, -22),
     (40, -15), (40, -5), (43, 0), (51, 11), (44, 11), (38, 18), (33, 31), (25, 32), (20, 31), (10, 34), (10, 37), (-2, 35), (-6, 36), (-10, 30), (-13, 27)],
    [(114, -22), (114, -34), (123, -34), (131, -31), (138, -35), (140, -38), (147, -39), (150, -37), (153, -28), (153, -25), (146, -19), (142, -11),
     (136, -12), (131, -11), (122, -17)],
    [(-5, 50), (1, 51), (0, 53), (-2, 56), (-5, 58), (-6, 55), (-5, 52)],
    [(130, 31), (134, 34), (140, 36), (142, 40), (141, 45), (140, 41), (136, 37), (132, 35)],
    [(44, -25), (47, -25), (50, -15), (49, -12), (44, -17)],
    [(172, -41), (178, -38), (175, -42), (170, -46), (167, -46)],
]
ISLANDS = [(114, 1, 5), (140, -5, 4), (102, -1, 3), (110, -7, 2.5), (121, -2, 2.5), (122, 12, 2.5), (-78, 22, 1.5), (80, 7, 1.5)]


def earth():
    lat = lat_grid(); lon = lon_grid()
    hi = Image.new("L", (W * 2, H * 2), 0)
    d = ImageDraw.Draw(hi)
    def px(lo, la):
        return ((lo + 180) / 360 * W * 2, (90 - la) / 180 * H * 2)
    for poly in CONTINENTS:
        d.polygon([px(lo, la) for lo, la in poly], fill=255)
    for lo, la, r in ISLANDS:
        x, y = px(lo, la); rx = r / 360 * W * 2 / max(0.2, math.cos(math.radians(la))); ry = r / 180 * H * 2
        d.ellipse([x - rx, y - ry, x + rx, y + ry], fill=255)
    land = np.asarray(hi.resize((W, H), Image.LANCZOS)).astype(np.float32) / 255
    # roughen the coasts: warp the sampling with noise, then threshold softly
    wx = (noise(12, 4, 7) - 0.5) * 10; wy = (noise(12, 4, 8) - 0.5) * 6
    yy, xx = np.mgrid[0:H, 0:W]
    sx = np.clip((xx + wx).astype(int), 0, W - 1); sy = np.clip((yy + wy).astype(int), 0, H - 1)
    land = land[sy, sx]
    land = blur(land, 1.5)
    land = np.clip((land - 0.45) * 6, 0, 1)
    # Antarctica: a ragged band
    ant = np.clip((-lat - (68 + 5 * noise(9, 4, 9))) / 2, 0, 1)
    land = np.maximum(land, ant)
    n = noise(8, 6, 10)
    coast = blur(land, 6)
    ocean = mix(hexc("#0E2F5E"), hexc("#1F5E9E"), np.clip(coast * 1.6 + n * 0.2, 0, 1))
    desert = np.clip(1 - np.abs(np.abs(lat) - 24) / 12, 0, 1) * np.clip(n * 1.6 - 0.2, 0, 1)
    green = mix(hexc("#2F5E2A"), hexc("#5B7F3A"), n)
    landc = mix(green, hexc("#C2A36E"), desert * 0.9)
    ice = np.clip((np.abs(lat) - 66) / 4, 0, 1) * land + ant
    landc = mix(landc, hexc("#F1F4F6"), np.clip(ice, 0, 1))
    base = mix(ocean, landc, land)
    # clouds: light swirls, kept thin so the continents read
    c = noise(10, 6, 11)
    clouds = np.clip((c - 0.58) * 3.2, 0, 1) * 0.75
    base = mix(base, hexc("#FFFFFF"), clouds)
    h = land * 0.4 + n * 0.1
    return base, h, 0.7


# ---------------------------------------------------------------- Jupiter

def jupiter():
    lat = lat_grid(); lon = lon_grid()
    turb = (noise(10, 6, 12) - 0.5) * 7 + np.sin(np.radians(lon) * 6 + lat * 0.3) * 1.2
    la = lat + turb
    bands = [(-90, "#C9B79C"), (-60, "#B39C80"), (-42, "#C7AE8A"), (-30, "#9C6E4C"), (-22, "#E2D3BA"), (-12, "#A2704A"), (-5, "#E9DDC6"),
             (5, "#EADFCB"), (10, "#9A6340"), (18, "#B88660"), (24, "#E6D7BE"), (32, "#A87A56"), (42, "#D9C6A6"), (60, "#B8A184"), (90, "#C6B49A")]
    lats = np.array([b[0] for b in bands], np.float32); cols = np.stack([hexc(b[1]) for b in bands])
    idx = np.clip(np.searchsorted(lats, la) - 1, 0, len(bands) - 2)
    t = np.clip((la - lats[idx]) / (lats[idx + 1] - lats[idx]), 0, 1)
    t = t * t * (3 - 2 * t)
    base = cols[idx] * (1 - t[..., None]) + cols[idx + 1] * t[..., None]
    base = mix(base, hexc("#F4EBDD"), np.clip((noise(20, 4, 13) - 0.7) * 3, 0, 1) * 0.5)
    # Great Red Spot
    dx = ((lon - 40 + 180) % 360 - 180) / 13; dy = (lat + 22) / 6.5
    r = np.sqrt(dx * dx + dy * dy)
    spot = np.clip(1.2 - r, 0, 1)
    base = mix(base, hexc("#B8553A"), np.clip(spot * 1.4, 0, 1) * 0.9)
    base = mix(base, hexc("#F0E2CC"), np.clip(1 - np.abs(r - 1.05) * 6, 0, 1) * 0.6)
    h = noise(14, 4, 14) * 0.2
    return base, h, 0.85


# ---------------------------------------------------------------- Mercury

def mercury():
    """Grey-brown and heavily cratered, a few smooth plains, bright young rays (no maria as dark as the Moon's)."""
    n = noise(7, 6, 21)
    base = mix(hexc("#7C746C"), hexc("#A39A90"), n)
    h = 0.5 * n
    plains = Image.new("F", (W, H), 0.0)
    stamp_ellipses(plains, [(30, 30, 16, 1), (-70, -10, 12, 1), (120, 20, 10, 1), (-150, 40, 14, 1)])  # Caloris-like basin first
    pa = blur(plains, 12) * (0.6 + 0.6 * noise(9, 4, 22))
    base = mix(base, hexc("#8E877F"), np.clip(pa, 0, 1) * 0.6)
    floor = Image.new("F", (W, H), 0.0); rim = Image.new("F", (W, H), 0.0)
    items_f, items_r = [], []
    rng = np.random.default_rng(20261114)
    for _ in range(650):
        lon = rng.uniform(-180, 180); lat = math.degrees(math.asin(rng.uniform(-1, 1)))
        r = 0.5 + 6.0 * rng.random() ** 3
        items_r.append((lon, lat, r * 1.18, 1.0)); items_f.append((lon, lat, r, 1.0))
    stamp_ellipses(rim, items_r); stamp_ellipses(floor, items_f)
    fa = blur(floor, 1.0); ra = np.clip(blur(rim, 1.0) - fa, 0, 1)
    base = mix(base, hexc("#D2CABF"), ra * 0.6)
    base = mix(base, hexc("#5E5852"), fa * 0.4)
    # bright ray craters
    lat = lat_grid(); lon = lon_grid()
    for clon, clat in ((-20, -40), (95, 15), (-120, 5)):
        dlon = ((lon - clon + 180) % 360 - 180) * np.cos(np.radians(lat)); dlat = lat - clat
        d = np.sqrt(dlon * dlon + dlat * dlat) + 1e-3
        ang = np.arctan2(dlat, dlon)
        rays = np.clip(np.cos(ang * 7 + clon) ** 16, 0, 1) * np.exp(-d / 12) * (0.4 + 0.6 * noise(24, 3, 23)) + np.exp(-(d / 2.0) ** 2)
        base = mix(base, hexc("#E6DFD4"), np.clip(rays, 0, 1) * 0.35)
    h += ra * 0.25 - fa * 0.2
    return base, h, 0.95


# ---------------------------------------------------------------- Venus

def venus():
    """Thick pale-yellow cloud deck: soft, low-contrast bands and the sideways Y / chevron of the upper clouds."""
    lat = lat_grid(); lon = lon_grid()
    n = noise(6, 6, 31); n2 = noise(14, 4, 32)
    warp = (n - 0.5) * 14
    chevron = np.sin(np.radians(lat * 3.0 + np.abs(lat) * 2.0 + warp + lon * 0.0) )
    y = np.clip(np.cos(np.radians(lon - 40)) * np.exp(-((np.abs(lat) - 20 - 8 * np.cos(np.radians(lon - 40))) / 14) ** 2), 0, 1)
    base = mix(hexc("#D9B977"), hexc("#F1DFAE"), np.clip(0.5 + 0.35 * chevron + 0.25 * (n2 - 0.5), 0, 1))
    base = mix(base, hexc("#B98F50"), y * 0.45)
    base = mix(base, hexc("#FBF1D2"), np.clip((np.abs(lat) - 60) / 25, 0, 1) * 0.5)
    h = n * 0.08
    return base, h, 0.9


# ---------------------------------------------------------------- Saturn

def saturn():
    """Pale gold and butterscotch bands, softer than Jupiter's, no big spot; a blue-grey northern cap."""
    lat = lat_grid(); lon = lon_grid()
    turb = (noise(12, 5, 41) - 0.5) * 3 + np.sin(np.radians(lon) * 5 + lat * 0.2) * 0.6
    la = lat + turb
    bands = [(-90, "#9C8F78"), (-62, "#B9A47C"), (-45, "#D5BE8C"), (-30, "#C8A86E"), (-18, "#E6D3A3"), (-8, "#D9BB80"),
             (0, "#EEDDB2"), (8, "#E2C88F"), (18, "#CDAA6C"), (30, "#E3CE9C"), (45, "#C9AE78"), (62, "#A9A28C"), (90, "#8E9A9E")]
    lats = np.array([b[0] for b in bands], np.float32); cols = np.stack([hexc(b[1]) for b in bands])
    idx = np.clip(np.searchsorted(lats, la) - 1, 0, len(bands) - 2)
    t = np.clip((la - lats[idx]) / (lats[idx + 1] - lats[idx]), 0, 1)
    t = t * t * (3 - 2 * t)
    base = cols[idx] * (1 - t[..., None]) + cols[idx + 1] * t[..., None]
    base = mix(base, hexc("#F6EBCB"), np.clip((noise(18, 4, 42) - 0.72) * 3, 0, 1) * 0.35)
    h = noise(14, 4, 43) * 0.1
    return base, h, 0.85


def saturn_ring():
    """Radial colour of the rings (u = inner -> outer): dim C ring, bright B ring, (Cassini gap = no geometry), A ring."""
    u = np.linspace(0, 1, 512, dtype=np.float32)
    r = 0.372 + u * (0.68 - 0.372)  # model units, see build(): body radius 0.30
    col = np.zeros((512, 3), np.float32)
    c_ring = r < 0.459
    b_ring = (r >= 0.459) & (r < 0.585)
    a_ring = r >= 0.585
    fine = noise_1d(512, 7)
    col[c_ring] = mix(hexc("#6E6559")[None, :].repeat(c_ring.sum(), 0), hexc("#8F826C")[None, :].repeat(c_ring.sum(), 0), fine[c_ring])
    col[b_ring] = mix(hexc("#D9C6A0")[None, :].repeat(b_ring.sum(), 0), hexc("#F0E2C0")[None, :].repeat(b_ring.sum(), 0), fine[b_ring])
    col[a_ring] = mix(hexc("#B8A888")[None, :].repeat(a_ring.sum(), 0), hexc("#CDBE9E")[None, :].repeat(a_ring.sum(), 0), fine[a_ring])
    # Encke gap: a thin dark line in the A ring
    col[np.abs(r - 0.655) < 0.0025] *= 0.35
    img = np.repeat(col[None, :, :], 16, axis=0)
    return to_img(img)


def noise_1d(n, seed):
    rng = np.random.default_rng(seed)
    out = np.zeros(n, np.float32)
    amp, tot = 1.0, 0.0
    for o in range(6):
        k = 4 * 2 ** o
        g = rng.random(k + 1).astype(np.float32)
        out += np.interp(np.linspace(0, k, n), np.arange(k + 1), g) * amp
        tot += amp; amp *= 0.55
    out /= tot
    return (out - out.min()) / (out.max() - out.min() + 1e-9)


# ---------------------------------------------------------------- Uranus

def uranus():
    """Pale cyan, nearly featureless: faint bands and a brighter polar cap (we see it pole-on)."""
    lat = lat_grid(); lon = lon_grid()
    n = noise(8, 5, 51)
    band = np.sin(np.radians(lat * 4 + (n - 0.5) * 10))
    base = mix(hexc("#8CCFD6"), hexc("#A9E0E3"), np.clip(0.5 + 0.25 * band + 0.2 * (n - 0.5), 0, 1))
    base = mix(base, hexc("#D2F0EE"), np.clip((lat - 45) / 30, 0, 1) * 0.7)
    base = mix(base, hexc("#7DBFCB"), np.clip((-lat - 50) / 30, 0, 1) * 0.4)
    h = n * 0.05
    return base, h, 0.85


def uranus_ring():
    u = np.linspace(0, 1, 256, dtype=np.float32)
    col = mix(hexc("#9AA3A8")[None, :].repeat(256, 0), hexc("#D6DADC")[None, :].repeat(256, 0), np.exp(-((u - 0.7) / 0.18) ** 2))
    return to_img(np.repeat(col[None, :, :], 16, axis=0))


# ---------------------------------------------------------------- Neptune

def neptune():
    """Deep blue with darker bands, the Great Dark Spot and bright white companion clouds."""
    lat = lat_grid(); lon = lon_grid()
    n = noise(8, 6, 61)
    band = np.sin(np.radians(lat * 5 + (n - 0.5) * 16))
    base = mix(hexc("#2747A8"), hexc("#3F6AD8"), np.clip(0.5 + 0.3 * band + 0.3 * (n - 0.5), 0, 1))
    base = mix(base, hexc("#1B2F7A"), np.exp(-((lat + 50) / 6) ** 2) * 0.45)
    # Great Dark Spot
    dx = ((lon + 30 + 180) % 360 - 180) / 20; dy = (lat + 20) / 9
    r = np.sqrt(dx * dx + dy * dy)
    base = mix(base, hexc("#142463"), np.clip(1.15 - r, 0, 1) * 0.95)
    # white streaks (scooter + companion)
    streaks = np.zeros_like(lat)
    for clon, clat, w in ((-25, -12, 20), (60, -42, 26), (150, 25, 18), (-100, 35, 14)):
        ddx = ((lon - clon + 180) % 360 - 180) / w; ddy = (lat - clat) / 1.8
        streaks = np.maximum(streaks, np.exp(-(ddx * ddx + ddy * ddy)))
    base = mix(base, hexc("#EEF4FF"), np.clip(streaks * (0.6 + 0.6 * noise(20, 3, 62)), 0, 1) * 0.9)
    h = n * 0.06
    return base, h, 0.8


# ---------------------------------------------------------------- Pluto

def pluto():
    """Tan and beige with the bright heart (Tombaugh Regio), the dark red-brown equatorial belt and a light north."""
    lat = lat_grid(); lon = lon_grid()
    n = noise(7, 6, 71); n2 = noise(16, 4, 72)
    base = mix(hexc("#B99877"), hexc("#D6BC9A"), n)
    # dark equatorial belt (Cthulhu-like), broken up
    belt = np.exp(-((lat + 12 + 6 * np.sin(np.radians(lon * 2))) / 16) ** 2) * np.clip((n2 - 0.1) * 2.2, 0, 1)
    belt *= np.clip(np.abs((lon + 180) % 360 - 180) / 40, 0, 1)  # the belt breaks where the heart is
    base = mix(base, hexc("#4E2A1E"), np.clip(belt * 1.3, 0, 1) * 0.9)
    # the heart: two lobes, the left smooth and bright
    heart = Image.new("F", (W, H), 0.0)
    stamp_ellipses(heart, [(-12, 22, 15, 1), (8, 24, 14, 1), (-6, 10, 13, 1), (2, 8, 12, 1), (-1, -4, 8, 1), (0, -12, 4, 1)])
    ha = np.clip(blur(heart, 4) * 1.4, 0, 1)
    base = mix(base, hexc("#F2E8DA"), ha * 0.95)
    base = mix(base, hexc("#E8D9C6"), np.clip((lat - 55) / 20, 0, 1) * 0.6)
    h = n * 0.25 - belt * 0.1 + ha * 0.05
    return base, h, 0.9


def ring(m, mi, r0, r1, seg=128):
    """A flat opaque annulus in the XZ plane (y = 0), both faces, u = radial (r0 -> r1). The planet's own axis = +Y."""
    for side in (1, -1):
        verts, norms, uvs, idx = [], [], [], []
        for i in range(seg + 1):
            a = 2 * math.pi * i / seg
            c, s = math.cos(a), math.sin(a)
            verts += [(r0 * c, 0.0, r0 * s), (r1 * c, 0.0, r1 * s)]
            norms += [(0.0, float(side), 0.0)] * 2
            uvs += [(0.0, i / seg), (1.0, i / seg)]
        for i in range(seg):
            a, b, cc, d = 2 * i, 2 * i + 1, 2 * i + 2, 2 * i + 3
            idx += ([a, cc, b, b, cc, d] if side > 0 else [a, b, cc, b, d, cc])
        m.smooth(mi, verts, norms, uvs, idx)


# body radius in model units (unit-diameter globe = 0.5); Saturn / Uranus are smaller so their rings fit the same slot
# (Layout.globeReachM: Saturn 0.68, Uranus 0.62)
BODIES = (
    ("mercury", mercury, 6.0, 0.5, None), ("venus", venus, 0.6, 0.5, None), ("earth", earth, 2.0, 0.5, None), ("moon", moon, 6.0, 0.5, None),
    ("mars", mars, 4.0, 0.5, None), ("jupiter", jupiter, 1.0, 0.5, None),
    ("saturn", saturn, 0.8, 0.30, (saturn_ring, ((0.372, 0.585), (0.609, 0.68)))),
    ("uranus", uranus, 0.5, 0.42, (uranus_ring, ((0.528, 0.538), (0.575, 0.62)))),
    ("neptune", neptune, 0.8, 0.5, None), ("pluto", pluto, 5.0, 0.5, None),
)


def build(only=None):
    os.makedirs(OUT, exist_ok=True)
    sheet = []
    total = 0
    for name, fn, strength, radius, rings in BODIES:
        if only and name not in only:
            continue
        base, h, rough = fn()
        # the sphere's u runs from +X toward +Z (west on a globe seen from outside): mirror the maps so east is east
        base = base[:, ::-1]; h = np.ascontiguousarray(h[:, ::-1])
        img = to_img(np.ascontiguousarray(base))
        sheet.append((name, img))
        lib = Lib()
        mi = lib.mat(name, base=img, normal=normal_map(h, strength), rough=rough, metal=0.0, nscale=1.0)
        m = Mesh()
        sphere(m, mi, (0, 0, 0), radius, seg=64, rings=48, k=1.0)
        if rings:
            tex_fn, spans = rings
            ri = lib.mat(name + "_ring", base=tex_fn(), rough=0.8, metal=0.0)
            lo, hi = spans[0][0], spans[-1][1]
            for r0, r1 in spans:
                # the ring texture spans lo..hi; each annulus samples its own part of it
                before = len(m.uv)
                ring(m, ri, r0, r1)
                for i in range(before, len(m.uv)):
                    u, v = m.uv[i]
                    m.uv[i] = (((r0 if u == 0.0 else r1) - lo) / (hi - lo), v)
        tris = m.write("planet_" + name, lib.mats, lib.images)
        print(name, tris, "triangles")
        total += tris
    if not only:
        out = Image.new("RGB", (W * 5, H * 2))
        for i, (_, im) in enumerate(sheet):
            out.paste(im, ((i % 5) * W, (i // 5) * H))
        out.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "planet-maps.png"))
    print("planets:", total, "triangles")


if __name__ == "__main__":
    build(sys.argv[1:] or None)
