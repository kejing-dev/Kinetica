"""Loudness evidence (B20 / ui_gates loudness, design sound.loudness / M8), rendered from the app's own physics:
app/build/sound-events.csv is written by the unit test SoundScriptTest (30 s of the real BenchFlow: the left rod pulled
into the middle bob, the spring bounced, the double thrown, every 6 s). This script mixes those events with the app's
SoundPlayer rules at the default volume (100 %): the event's speed gain x the event trim (posts -3 dB, clicks -12 dB),
2-4 variants with a +-5 % rate, one instance per event (a new knock stops the previous one of that event), and the mix
headroom (a new stream is scaled so the summed peaks stay <= -1 dBFS). Rendered, not recorded: kj-mbp-m5 has no audio
loopback from the emulator.

  ./gradlew :app:testDebugUnitTest --tests '*SoundScriptTest*' && python3 tools/loudness.py OUT.wav
"""
import csv, math, os, random, sys, wave
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
EVENTS = os.path.join(ROOT, "app", "build", "sound-events.csv")
SR = 22050
TRIM = {"POST": 0.708, "CLICK": 0.251}
CEIL = 0.891


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def variants(name):
    return [n for n in (name, name + "_2", name + "_3", name + "_4") if os.path.exists(os.path.join(SND, n + ".wav"))]


def rate(x, r):
    idx = (np.arange(int(len(x) / r)) * r).astype(int)
    return x[np.minimum(idx, len(x) - 1)]


def main(out):
    rnd = random.Random(5)
    rows = list(csv.DictReader(open(EVENTS)))
    total = int(30.5 * SR)
    mix = np.zeros(total)
    playing = {}  # sound -> (start, end) of its current instance
    active = []   # (end sample, peak contribution)
    for r in rows:
        s = int(int(r["ms"]) / 1000 * SR)
        if s >= total:
            continue
        name = r["sound"]
        x = rate(load(rnd.choice(variants(name.lower()))), 0.95 + rnd.random() * 0.10)
        vol = float(r["gain"]) * TRIM.get(name, 1.0)
        # one instance per event: cut the previous one of this sound
        if name in playing:
            ps, pe = playing[name]
            if pe > s:
                mix[s:pe] -= prev_buf[name][s - ps:pe - ps]
        active = [a for a in active if a[0] > s]
        others = sum(a[1] for a in active)
        peak = float(np.abs(x).max())
        g = min(1.0, max(0.15, (CEIL - others) / max(1e-6, vol * peak)))
        y = x * vol * g
        e = min(total, s + len(y))
        mix[s:e] += y[:e - s]
        playing[name] = (s, e)
        prev_buf[name] = y
        active.append((e, vol * g * peak))
    mix = np.clip(mix, -1, 1)
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((mix * 32767).astype("<i2").tobytes())
    # the ui_gates measure: 20 ms windows above peak -30 dB
    pk = float(np.abs(mix).max())
    win = int(0.02 * SR)
    en = [float((mix[k:k + win] ** 2).mean()) for k in range(0, len(mix) - win, win)]
    thr = (pk * 0.0316) ** 2
    voiced = [v for v in en if v >= thr] or en
    rms = math.sqrt(sum(voiced) / len(voiced))
    print("events %d  voiced RMS %.1f dBFS  peak %.1f dBFS  (rendered from the app's collision events, default volume)" % (len(rows), 20 * math.log10(rms), 20 * math.log10(pk)))
    # D51: the same knock 10 times in a row stays under -1 dBFS
    for ev in ("metal", "wood", "plate", "post", "soft"):
        burst = np.zeros(int(1.5 * SR)); prev = None
        for k in range(10):
            x = load(variants(ev)[k % len(variants(ev))]) * TRIM.get(ev.upper(), 1.0)
            s = int(k * 0.06 * SR)
            if prev is not None:
                ps, py = prev
                end = min(ps + len(py), len(burst))
                if end > s:
                    burst[s:end] -= py[s - ps:end - ps]
            e = min(len(burst), s + len(x)); burst[s:e] += x[:e - s]; prev = (s, x)
        print("burst x10 %-5s peak %.1f dBFS" % (ev, 20 * math.log10(np.abs(burst).max())))


prev_buf = {}
main(sys.argv[1])
