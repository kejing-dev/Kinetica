"""All Kinetica sounds (profile D51 / B20, ui_gates sound-source): real CC0 recordings only (Kenney impact / interface
packs, CC0), no synthesis.

  python3 tools/import_cc0_sounds.py [~/Downloads/cc0-sounds]

Processing chain (design sound.processing): decode with macOS afconvert (22.05 kHz mono) -> cut the event -> high-pass
120 Hz -> low-pass 6 kHz (two passes) -> noise gate (spectral subtraction against the clip's own quietest 10 %, then a
-48 dB gate) -> 3 ms fade-in / 40 ms fade-out -> peak normalise to -1 dBFS (no reverb, no thickening). Writes
app/src/main/assets/sounds/<event>[_n].wav; the app plays them with a +-5 % pitch, gain by impact speed, one instance per event.
"""
import math, os, subprocess, sys, tempfile, wave, struct
import numpy as np

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/cc0-sounds")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
RATE = 22050
IMP = "kenney_impact-sounds/Audio/"
UI = "kenney_interface-sounds/Audio/"

# event -> list of (file, start s, end s or None, peak dBFS); design assets.list
MAP = {
    # bob x bob with a wooden ball involved
    "wood": [(IMP + "impactWood_light_%03d.ogg" % i, 0, 0.35, -1.0) for i in range(4)],
    # steel-steel / steel-brass
    "metal": [(IMP + "impactMetal_light_%03d.ogg" % i, 0, 0.35, -1.0) for i in range(4)],
    # brass-brass
    "plate": [(IMP + "impactPlate_light_%03d.ogg" % i, 0, 0.35, -1.0) for i in range(4)],
    # bob x post / crossbar / arm / another station's rod or spring (the app plays it 3 dB down)
    "post": [(IMP + "impactMetal_light_%03d.ogg" % i, 0, 0.35, -1.0) for i in (4, 1)],
    # bob lands on the felt (soft, short)
    "soft": [(IMP + "impactSoft_medium_%03d.ogg" % i, 0, 0.30, -1.0) for i in range(4)],
    # tag swap / size step / dial / spring coil stop (the app plays it 12 dB down)
    "click": [(UI + "click_%03d.ogg" % i, 0, 0.2, -1.0) for i in (1, 2, 3, 4)],
}


def decode(path):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "x.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@%d" % RATE, "-c", "1", path, out], check=True)
        data = open(out, "rb").read()
    i = 12
    while i < len(data):
        cid = data[i:i + 4]; n = struct.unpack("<I", data[i + 4:i + 8])[0]
        if cid == b"data":
            return np.frombuffer(data[i + 8:i + 8 + n], dtype="<i2").astype(float) / 32768
        i += 8 + n + (n & 1)
    raise ValueError(path)


def biquad(x, f, kind, passes=2):
    w = 2 * math.pi * f / RATE; cw, sw = math.cos(w), math.sin(w); al = sw / (2 * 0.707)
    if kind == "lp":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    else:
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    a = [1 + al, -2 * cw, 1 - al]
    b = [v / a[0] for v in b]; a1, a2 = a[1] / a[0], a[2] / a[0]
    for _ in range(passes):
        y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
        for i, v in enumerate(x):
            o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
            x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
        x = y
    return x


def denoise(x):
    """Spectral subtraction against the quietest 10 % of frames, then a -48 dB (relative to peak) gate."""
    n = 512; hop = 128
    if len(x) < 2 * n:
        return x
    win = np.hanning(n)
    frames = [x[i:i + n] * win for i in range(0, max(1, len(x) - n), hop)]
    if len(frames) < 4:
        return x
    S = np.array([np.fft.rfft(f) for f in frames])
    mag = np.abs(S)
    energy = mag.sum(axis=1)
    noise = mag[energy <= np.percentile(energy, 10)].mean(axis=0)
    clean = np.maximum(mag - 1.5 * noise, 0.05 * mag)
    S2 = clean * np.exp(1j * np.angle(S))
    y = np.zeros(len(x)); norm = np.zeros(len(x))
    for k, f in enumerate(S2):
        i = k * hop
        y[i:i + n] += np.fft.irfft(f, n) * win; norm[i:i + n] += win * win
    y = y / np.maximum(norm, 1e-6)
    thr = np.abs(y).max() * 10 ** (-48 / 20)
    env = np.convolve(np.abs(y), np.ones(64) / 64, mode="same")
    return y * np.clip(env / thr, 0, 1)


def process(x, peak_db):
    x = biquad(x, 120, "hp")
    x = biquad(x, 6000, "lp")
    x = denoise(x)
    thr = np.abs(x).max() * 10 ** (-42 / 20)
    idx = np.where(np.abs(x) > thr)[0]
    if len(idx):
        x = x[max(0, idx[0] - 30):idx[-1] + 1]
    k = min(len(x), int(0.04 * RATE)); x[-k:] *= np.linspace(1, 0, k)
    k = min(len(x), int(0.003 * RATE)); x[:k] *= np.linspace(0, 1, k)
    return x * (10 ** (peak_db / 20) / max(1e-9, np.abs(x).max()))


def write(name, x):
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith(".wav"):
            os.remove(os.path.join(OUT, f))
    for event, items in MAP.items():
        for i, (rel, a, b, peak) in enumerate(items):
            x = decode(os.path.join(SRC, rel))
            x = x[int(a * RATE):(int(b * RATE) if b else None)]
            name = event if i == 0 else f"{event}_{i + 1}"
            write(name, process(x, peak))
            print(f"{name}.wav <- {rel} [{a}-{b}s]")
