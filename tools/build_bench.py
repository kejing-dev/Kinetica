"""Builds every model of the Kinetica bench as GLB in pure Python + Pillow (no Blender on this machine).

  python3 tools/build_bench.py

Writes app/src/main/assets/models/ (numbers = domain/Layout.kt + BenchConfig, design spatialLayout / container.volume.math;
origin of the bench parts = the volume centre, +Y up, +Z toward the user, metres):
  base.glb      walnut base 0.68 x 0.05 x 0.32 with the 30° front bevel, felt inset, brass feet, both ears, plate bezels,
                the nameplate bezel, the dial disc + stand
  frame.glb     two brass posts (Ø 24 mm, 32 segments), crossbar (Ø 16 mm), three arms (Ø 10 mm) with pivot knuckles,
                tag pedestals + tag backings (tilted up 20°)
  rod.glb       solid brass rod Ø 5 mm, 1 m long from the origin down -Y (24 segments); the app scales Y to the length
  hinge.glb     the double's middle ring (torus)
  bob_<mat>.glb sphere of diameter 1 m (32 x 24), maple / steel / brass; the app scales to the visual diameter
  plaque.glb    KINETICA nameplate plaque (brass backing + walnut foot), key.glb the ▶ swing key cap,
  selkey.glb    the round ◀ / ▶ planet key, selector.glb the planet shelf (plinth, globe cradle, label backing)
  planet_*.glb  (tools/build_planets.py) Moon / Mars / Earth / Jupiter globes, procedural PBR maps
Surfaces: CC0 scanned PBR from ambientCG (Wood066 walnut, Wood092 maple, Metal009 brushed brass, Fabric034 felt; copies in
third_party/cc0-textures, extracted from the design sample). All materials OPAQUE (profile D24); round parts have true round
sections with smooth normals (>= 24 segments, B12).
"""
import io
import json
import math
import os
import struct

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")
TEX = os.path.join(ROOT, "third_party", "cc0-textures")

PIVOT_X = (-0.12, 0.0, 0.12)
PIVOT_Y = 0.05
BASE_X, BASE_Y0, BASE_Y1, BASE_Z = 0.34, -0.30, -0.25, 0.16
BEVEL = math.radians(30)
BEVEL_TOP_Z = BASE_Z - (BASE_Y1 - BASE_Y0) * math.tan(BEVEL)
EAR_X0, EAR_X1, EAR_Y0 = 0.325, 0.395, -0.28
POST_X, POST_Z, POST_R, BAR_Y, BAR_R, ARM_R = 0.36, -0.05, 0.012, 0.06, 0.008, 0.005
TAG_Y, TAG_Z, TAG_TILT = 0.095, -0.05, math.radians(20)
PLATE_W, PLATE_H, PLATE_Y = 0.11, 0.045, -0.275
# v0.1.1 (Full Space): nameplate plaque on the left ear, ▶ keys on the felt, planet selector shelf on the right ear
NAMEPLATE_W, NAMEPLATE_H = 0.09, 0.034
KEY_W, KEY_H = 0.056, 0.040
SEL_W, SEL_Z0, SEL_Z1, SEL_T = 0.10, -0.045, 0.055, 0.014
SEL_GLOBE, SEL_GLOBE_R = (0.0, 0.065, -0.005), 0.032
SEL_PREV, SEL_NEXT, SEL_KEY_R, SEL_KEY_TILT = (-0.072, 0.045, 0.010), (0.072, 0.045, 0.010), 0.023, math.radians(38)
SEL_LABEL, SEL_LABEL_W, SEL_LABEL_H, SEL_LABEL_TILT = (0.0, 0.026, 0.042), 0.12, 0.040, math.radians(46)


def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3", 4: "VEC4"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "JPEG", quality=90)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "kinetica tools/build_bench.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


def norm(v):
    l = math.sqrt(sum(x * x for x in v)) or 1.0
    return tuple(x / l for x in v)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def add(a, b):
    return tuple(a[i] + b[i] for i in range(3))


def mul(a, k):
    return tuple(x * k for x in a)


class Mesh:
    """Positions / normals / tangents / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def tri(self, mat, a, b, c, n=None, uvs=((0, 0), (0, 0), (0, 0))):
        if n is None:
            n = norm(cross(sub(b, a), sub(c, a)))
        base = len(self.pos)
        for p, t in zip((a, b, c), uvs):
            self.pos.append(tuple(p)); self.nor.append(tuple(n)); self.uv.append(tuple(t))
        self.prims.setdefault(mat, []).extend([base, base + 1, base + 2])

    def quad(self, mat, a, b, c, d, n=None, uvs=((0, 0), (1, 0), (1, 1), (0, 1))):
        """Counter-clockwise a-b-c-d seen from the front (normal side)."""
        self.tri(mat, a, b, c, n, (uvs[0], uvs[1], uvs[2]))
        self.tri(mat, a, c, d, n, (uvs[0], uvs[2], uvs[3]))

    def smooth(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]; self.nor += [tuple(n) for n in norms]; self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def tangents(self):
        """Per-vertex tangents from the uv gradient (glTF needs them for the normal maps)."""
        tan = [[0.0, 0.0, 0.0] for _ in self.pos]
        for idx in self.prims.values():
            for k in range(0, len(idx), 3):
                i0, i1, i2 = idx[k], idx[k + 1], idx[k + 2]
                p0, p1, p2 = self.pos[i0], self.pos[i1], self.pos[i2]
                u0, u1, u2 = self.uv[i0], self.uv[i1], self.uv[i2]
                e1, e2 = sub(p1, p0), sub(p2, p0)
                du1, dv1, du2, dv2 = u1[0] - u0[0], u1[1] - u0[1], u2[0] - u0[0], u2[1] - u0[1]
                r = du1 * dv2 - du2 * dv1
                if abs(r) < 1e-12:
                    continue
                t = mul(sub(mul(e1, dv2), mul(e2, dv1)), 1.0 / r)
                for i in (i0, i1, i2):
                    for c in range(3):
                        tan[i][c] += t[c]
        out = []
        for t, n in zip(tan, self.nor):
            # Gram-Schmidt against the normal
            d = sum(t[c] * n[c] for c in range(3))
            t2 = [t[c] - n[c] * d for c in range(3)]
            l = math.sqrt(sum(x * x for x in t2))
            if l < 1e-9:
                a = (1.0, 0.0, 0.0) if abs(n[0]) < 0.9 else (0.0, 1.0, 0.0)
                t2 = list(norm(cross(cross(n, a), n)))
                l = 1.0
            out.append((t2[0] / l, t2[1] / l, t2[2] / l, 1.0))
        return out

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        attrs = {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}
        if any("normalTexture" in m for m in materials):
            attrs["TANGENT"] = g.floats(self.tangents(), 4)
        prims = [{"attributes": dict(attrs), "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/jpeg"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-14s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))
        return tris


# ---------------------------------------------------------------- textures (CC0 scans)

def tex(name):
    return Image.open(os.path.join(TEX, name))


def mr_from_rough(rough_l, metal):
    """glTF metallicRoughness: G = roughness, B = metallic."""
    r = rough_l.convert("L")
    m = Image.new("L", r.size, int(metal * 255))
    return Image.merge("RGB", (Image.new("L", r.size, 0), r, m))


class Lib:
    """Images + materials of one GLB."""

    def __init__(self):
        self.images, self.mats = [], []

    def img(self, im):
        self.images.append(im)
        return len(self.images) - 1

    def mat(self, name, color=(255, 255, 255), rough=0.6, metal=0.0, base=None, normal=None, mr=None, nscale=1.0):
        pbr = {"metallicFactor": metal if mr is None else 1.0, "roughnessFactor": rough if mr is None else 1.0,
               "baseColorFactor": [srgb_lin(c) for c in color] + [1.0]}
        if base is not None:
            pbr["baseColorTexture"] = {"index": self.img(base)}
        if mr is not None:
            pbr["metallicRoughnessTexture"] = {"index": self.img(mr)}
        m = {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}
        if normal is not None:
            m["normalTexture"] = {"index": self.img(normal), "scale": nscale}
        self.mats.append(m)
        return len(self.mats) - 1


def walnut(lib):
    return lib.mat("walnut", base=tex("walnutC.jpg"), normal=tex("walnutN.jpg"), mr=mr_from_rough(tex("walnutR.jpg"), 0.0))


def felt(lib):
    # the scan is a neutral grey cloth: tint it to the design felt #23412F (factor x scan mean 0.63)
    return lib.mat("felt", color=(0x3A, 0x6A, 0x4C), base=tex("feltC.jpg"), normal=tex("feltN.jpg"), rough=0.95)


def brass(lib):
    # brushed brass: colour factor, scanned normal + roughness (Metal009), fully metallic
    r = tex("brassR.jpg").convert("L").point(lambda v: int(60 + v * 0.45))
    return lib.mat("brass", color=(0xC9, 0xA2, 0x5A), base=None, normal=tex("brassN.jpg"), mr=mr_from_rough(r, 1.0), nscale=0.6)


# ---------------------------------------------------------------- primitives

def box(m, mi, x0, x1, y0, y1, z0, z1, k=4.0):
    """Axis-aligned box, planar uvs (k repeats per metre)."""
    m.quad(mi, (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0), (0, 1, 0), ((x0 * k, z1 * k), (x1 * k, z1 * k), (x1 * k, z0 * k), (x0 * k, z0 * k)))
    m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), ((x0 * k, z0 * k), (x1 * k, z0 * k), (x1 * k, z1 * k), (x0 * k, z1 * k)))
    m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (0, 0, 1), ((x0 * k, y0 * k), (x1 * k, y0 * k), (x1 * k, y1 * k), (x0 * k, y1 * k)))
    m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (0, 0, -1), ((x1 * k, y0 * k), (x0 * k, y0 * k), (x0 * k, y1 * k), (x1 * k, y1 * k)))
    m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (1, 0, 0), ((z1 * k, y0 * k), (z0 * k, y0 * k), (z0 * k, y1 * k), (z1 * k, y1 * k)))
    m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (-1, 0, 0), ((z0 * k, y0 * k), (z1 * k, y0 * k), (z1 * k, y1 * k), (z0 * k, y1 * k)))


def oriented_box(m, mi, c, right, up, w, h, d, k=8.0):
    """A thin box centred at c whose front face (normal = right x up) is w x h, depth d behind... centred."""
    n = norm(cross(right, up))
    hw, hh, hd = w / 2, h / 2, d / 2

    def P(a, b, e):
        return add(add(add(c, mul(right, a)), mul(up, b)), mul(n, e))
    corners = {(sa, sb, se): P(sa * hw, sb * hh, se * hd) for sa in (-1, 1) for sb in (-1, 1) for se in (-1, 1)}
    faces = [((1,), n, [(-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)]),
             ((2,), mul(n, -1), [(1, -1, -1), (-1, -1, -1), (-1, 1, -1), (1, 1, -1)]),
             ((3,), right, [(1, -1, 1), (1, -1, -1), (1, 1, -1), (1, 1, 1)]),
             ((4,), mul(right, -1), [(-1, -1, -1), (-1, -1, 1), (-1, 1, 1), (-1, 1, -1)]),
             ((5,), up, [(-1, 1, 1), (1, 1, 1), (1, 1, -1), (-1, 1, -1)]),
             ((6,), mul(up, -1), [(-1, -1, -1), (1, -1, -1), (1, -1, 1), (-1, -1, 1)])]
    for _, fn, q in faces:
        pts = [corners[t] for t in q]
        m.quad(mi, *pts, n=fn, uvs=((0, 0), (w * k, 0), (w * k, h * k), (0, h * k)))


def cylinder(m, mi, a, b, r, seg=32, caps=True, k=6.0):
    """True round cylinder from a to b, smooth side normals."""
    ax = norm(sub(b, a))
    ref = (1, 0, 0) if abs(ax[0]) < 0.9 else (0, 0, 1)
    u = norm(cross(ax, ref)); v = cross(ax, u)
    length = math.dist(a, b)
    verts, norms, uvs, idx = [], [], [], []
    for i in range(seg + 1):
        t = 2 * math.pi * i / seg
        dirv = add(mul(u, math.cos(t)), mul(v, math.sin(t)))
        for end, p in ((0, a), (1, b)):
            verts.append(add(p, mul(dirv, r))); norms.append(dirv); uvs.append((i / seg * 2 * math.pi * r * k, end * length * k))
    for i in range(seg):
        a0, a1, b0, b1 = 2 * i, 2 * i + 1, 2 * i + 2, 2 * i + 3
        idx += [a0, b0, a1, a1, b0, b1]
    m.smooth(mi, verts, norms, uvs, idx)
    if caps:
        for p, nn in ((a, mul(ax, -1)), (b, ax)):
            cv, cn, cu, ci = [p], [nn], [(0, 0)], []
            for i in range(seg + 1):
                t = 2 * math.pi * i / seg
                cv.append(add(p, add(mul(u, math.cos(t) * r), mul(v, math.sin(t) * r)))); cn.append(nn); cu.append((math.cos(t) * r * k, math.sin(t) * r * k))
            for i in range(seg):
                if nn == ax:
                    ci += [0, i + 1, i + 2]
                else:
                    ci += [0, i + 2, i + 1]
            m.smooth(mi, cv, cn, cu, ci)


def sphere(m, mi, c, r, seg=32, rings=24, k=1.0):
    verts, norms, uvs, idx = [], [], [], []
    for j in range(rings + 1):
        th = math.pi * j / rings
        for i in range(seg + 1):
            ph = 2 * math.pi * i / seg
            n = (math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph))
            verts.append(add(c, mul(n, r))); norms.append(n); uvs.append((i / seg * k, j / rings * k))
    w = seg + 1
    for j in range(rings):
        for i in range(seg):
            a, b, cc, d = j * w + i, j * w + i + 1, (j + 1) * w + i, (j + 1) * w + i + 1
            idx += [a, b, cc, b, d, cc]
    m.smooth(mi, verts, norms, uvs, idx)


def torus(m, mi, c, axis, R, r, seg=32, tube=16):
    ax = norm(axis)
    ref = (1, 0, 0) if abs(ax[0]) < 0.9 else (0, 0, 1)
    u = norm(cross(ax, ref)); v = cross(ax, u)
    verts, norms, uvs, idx = [], [], [], []
    for i in range(seg + 1):
        a = 2 * math.pi * i / seg
        d = add(mul(u, math.cos(a)), mul(v, math.sin(a)))
        cc = add(c, mul(d, R))
        for j in range(tube + 1):
            b = 2 * math.pi * j / tube
            n = add(mul(d, math.cos(b)), mul(ax, math.sin(b)))
            verts.append(add(cc, mul(n, r))); norms.append(n); uvs.append((i / seg, j / tube))
    w = tube + 1
    for i in range(seg):
        for j in range(tube):
            a0, a1, b0, b1 = i * w + j, i * w + j + 1, (i + 1) * w + j, (i + 1) * w + j + 1
            idx += [a0, b0, a1, a1, b0, b1]
    m.smooth(mi, verts, norms, uvs, idx)


# ---------------------------------------------------------------- models

def build_base():
    lib = Lib(); W = walnut(lib); F = felt(lib); B = brass(lib)
    m = Mesh(); k = 3.0
    x0, x1 = -BASE_X, BASE_X
    zb, zt = BASE_Z, BEVEL_TOP_Z
    y0, y1 = BASE_Y0, BASE_Y1
    # body: top, back, bottom, bevel, ends
    m.quad(W, (x0, y1, zt), (x1, y1, zt), (x1, y1, -zb), (x0, y1, -zb), (0, 1, 0), ((x0 * k, zt * k), (x1 * k, zt * k), (x1 * k, -zb * k), (x0 * k, -zb * k)))
    m.quad(W, (x1, y0, -zb), (x0, y0, -zb), (x0, y1, -zb), (x1, y1, -zb), (0, 0, -1), ((x1 * k, y0 * k), (x0 * k, y0 * k), (x0 * k, y1 * k), (x1 * k, y1 * k)))
    m.quad(W, (x0, y0, -zb), (x1, y0, -zb), (x1, y0, zb), (x0, y0, zb), (0, -1, 0))
    bn = (0, math.sin(BEVEL), math.cos(BEVEL))
    m.quad(W, (x0, y0, zb), (x1, y0, zb), (x1, y1, zt), (x0, y1, zt), bn, ((x0 * k, 0), (x1 * k, 0), (x1 * k, 0.058 * k), (x0 * k, 0.058 * k)))
    for sx in (-1, 1):
        x = sx * BASE_X
        n = (sx, 0, 0)
        pts = [(x, y0, -zb), (x, y0, zb), (x, y1, zt), (x, y1, -zb)]
        if sx < 0:
            pts = list(reversed(pts))
        uv = [(p[2] * k, p[1] * k) for p in pts]
        m.quad(W, *pts, n=n, uvs=uv)
    # felt inset (2 mm proud)
    box(m, F, -0.31, 0.31, y1, y1 + 0.002, -0.13, 0.13, k=8.0)
    # ears (walnut), same depth as the base (S-01)
    for sx in (-1, 1):
        box(m, W, min(sx * EAR_X0, sx * EAR_X1), max(sx * EAR_X0, sx * EAR_X1), EAR_Y0, y1, -BASE_Z, BASE_Z, k=k)
    # brass feet
    for fx in (-0.31, 0.31):
        for fz in (-0.13, 0.13):
            cylinder(m, B, (fx, -0.31, fz), (fx, y0, fz), 0.012, seg=24)
    # readout plate bezels on the bevel (plates float 3 mm in front, the bezel 2 mm proud of the bevel)
    up = (0, math.cos(BEVEL), -math.sin(BEVEL))
    for x in PIVOT_X:
        zmid = BASE_Z - (PLATE_Y - BASE_Y0) * math.tan(BEVEL)
        c = add((x, PLATE_Y, zmid), mul(bn, 0.001))
        oriented_box(m, B, c, (1, 0, 0), up, PLATE_W + 0.008, PLATE_H + 0.006, 0.002)
    return m.write("base", lib.mats, lib.images)


def tilt_frame(tilt):
    """(right, up) of a face leaning back by tilt (0 = upright, facing +Z)."""
    return (1, 0, 0), (0, math.cos(tilt), -math.sin(tilt))


def build_plaque():
    """Nameplate plaque (own frame: face centre at the origin, facing +Z): brass backing + a walnut foot down to the ear.
    The app places it at Layout.NAMEPLATE, yawed 40° and leaning back 48° (the canvas face floats 1 mm in front)."""
    lib = Lib(); B = brass(lib); W = walnut(lib)
    m = Mesh()
    oriented_box(m, B, (0, 0, -0.0025), (1, 0, 0), (0, 1, 0), NAMEPLATE_W + 0.008, NAMEPLATE_H + 0.008, 0.004)
    # foot: a walnut block behind the lower edge, reaching back (it sinks into the ear top once tilted)
    oriented_box(m, W, (0, -0.010, -0.018), (1, 0, 0), (0, 1, 0), NAMEPLATE_W * 0.7, 0.020, 0.026)
    return m.write("plaque", lib.mats, lib.images)


def build_key():
    """▶ swing key (own frame: face centre at the origin, facing +Z): a brass key cap with a rounded bezel, 22 mm deep so
    it sinks into the felt once tilted back 50°. The canvas face (Swing / Stop) floats 0.8 mm in front."""
    lib = Lib(); B = brass(lib); W = walnut(lib)
    m = Mesh()
    oriented_box(m, B, (0, 0, -0.004), (1, 0, 0), (0, 1, 0), KEY_W + 0.006, KEY_H + 0.006, 0.008)
    oriented_box(m, W, (0, 0, -0.018), (1, 0, 0), (0, 1, 0), KEY_W + 0.012, KEY_H + 0.012, 0.020)
    return m.write("key", lib.mats, lib.images)


def build_selkey():
    """◀ / ▶ planet key (own frame: face centre at the origin, facing +Z): a round brass key Ø 46 mm on a short stem."""
    lib = Lib(); B = brass(lib)
    m = Mesh()
    cylinder(m, B, (0, 0, -0.010), (0, 0, -0.0004), SEL_KEY_R + 0.002, seg=48)
    cylinder(m, B, (0, 0, -0.010), (0, -0.040, -0.030), 0.005, seg=24)
    return m.write("selkey", lib.mats, lib.images)


def build_selector():
    """Planet shelf (own frame = the selector group: origin on the right ear top, +Z toward the eye after the app's yaw):
    a walnut plinth, the globe's brass cradle and the label's brass backing."""
    lib = Lib(); B = brass(lib); W = walnut(lib)
    m = Mesh()
    box(m, W, -SEL_W, SEL_W, 0.0, SEL_T, SEL_Z0, SEL_Z1, k=3.0)
    # cradle: stem + cup under the globe
    gx, gy, gz = SEL_GLOBE
    cylinder(m, B, (gx, SEL_T, gz), (gx, gy - SEL_GLOBE_R + 0.004, gz), 0.006, seg=24)
    cylinder(m, B, (gx, gy - SEL_GLOBE_R - 0.002, gz), (gx, gy - SEL_GLOBE_R + 0.006, gz), 0.016, seg=48)
    # label backing (the canvas face floats 1 mm in front)
    r, up = tilt_frame(SEL_LABEL_TILT)
    n = norm(cross(r, up))
    oriented_box(m, B, add(SEL_LABEL, mul(n, -0.0025)), r, up, SEL_LABEL_W + 0.008, SEL_LABEL_H + 0.008, 0.004)
    return m.write("selector", lib.mats, lib.images)


def build_frame():
    lib = Lib(); B = brass(lib)
    m = Mesh()
    for sx in (-1, 1):
        x = sx * POST_X
        cylinder(m, B, (x, BASE_Y1, POST_Z), (x, BAR_Y + 0.012, POST_Z), POST_R, seg=32)
        sphere(m, B, (x, BAR_Y + 0.014, POST_Z), 0.010, seg=32, rings=16)
    cylinder(m, B, (-POST_X, BAR_Y, POST_Z), (POST_X, BAR_Y, POST_Z), BAR_R, seg=32)
    up = (0, math.cos(TAG_TILT), -math.sin(TAG_TILT))
    for x in PIVOT_X:
        cylinder(m, B, (x, PIVOT_Y, POST_Z), (x, PIVOT_Y, 0.0), ARM_R, seg=24)
        sphere(m, B, (x, PIVOT_Y, 0.0), 0.0065, seg=24, rings=16)
        # tag pedestal on the bar and the tag's brass backing (the canvas face floats 1.6 mm in front)
        box(m, B, x - 0.008, x + 0.008, BAR_Y + BAR_R - 0.002, TAG_Y - 0.018, POST_Z - 0.005, POST_Z + 0.005, k=20)
        oriented_box(m, B, add((x, TAG_Y, TAG_Z), mul(norm(cross((1, 0, 0), up)), -0.0015)), (1, 0, 0), up, 0.086, 0.046, 0.003)
    return m.write("frame", lib.mats, lib.images)


def build_rod():
    lib = Lib(); B = brass(lib)
    m = Mesh()
    cylinder(m, B, (0, 0, 0), (0, -1, 0), 0.0025, seg=24, k=40)
    return m.write("rod", lib.mats, lib.images)


def build_hinge():
    lib = Lib(); B = brass(lib)
    m = Mesh()
    torus(m, B, (0, 0, 0), (0, 0, 1), 0.006, 0.0018, seg=32, tube=12)
    return m.write("hinge", lib.mats, lib.images)


def build_bobs():
    total = 0
    for name in ("wood", "steel", "brass"):
        lib = Lib()
        if name == "wood":
            mi = lib.mat("maple", base=tex("mapleC.jpg"), rough=0.55)
        elif name == "steel":
            mi = lib.mat("steel", color=(0xB9, 0xC0, 0xC8), rough=0.22, metal=1.0)
        else:
            mi = lib.mat("brass_bob", color=(0xD4, 0xA8, 0x5C), rough=0.28, metal=1.0)
        m = Mesh()
        sphere(m, mi, (0, 0, 0), 0.5, seg=32, rings=24, k=1.0)
        total += m.write("bob_" + name, lib.mats, lib.images)
    return total


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    t = build_base() + build_frame() + build_rod() + build_hinge() + build_bobs() + build_plaque() + build_key() + build_selkey() + build_selector()
    old = os.path.join(OUT, "pointer.glb")
    if os.path.exists(old):
        os.remove(old)  # the dial is gone (v0.1.1 planet selector)
    print("TOTAL static triangles (one of each):", t)
