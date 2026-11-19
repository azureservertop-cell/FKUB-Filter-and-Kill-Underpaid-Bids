"""
Synthesize FKUB's alert sounds from scratch (no samples, no third-party assets).

    python3 tools/make_sounds.py   # needs numpy + ffmpeg; writes app/src/main/res/raw/*.ogg

- sfx_coin  (green): two-note square-wave "coin" chime
- sfx_horn  (blue):  three-blast air-horn "doot doot dooot"
- sfx_poop  (red):   sputtering wet low-frequency raspberry
"""
import os, subprocess, tempfile, wave
import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
rng = np.random.default_rng(7)


def t(sec):
    return np.arange(int(SR * sec)) / SR


def env(n, a=0.005, r=0.05):
    e = np.ones(n)
    na, nr = int(SR * a), int(SR * r)
    if na: e[:na] = np.linspace(0, 1, na)
    if nr: e[-nr:] *= np.linspace(1, 0, nr)
    return e


def square(f, sec, duty=0.5):
    x = t(sec) * f % 1.0
    return np.where(x < duty, 1.0, -1.0)


def saw(f, tt):
    return 2 * (tt * f % 1.0) - 1


def lowpass(x, cutoff):
    # one-pole low-pass
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x); acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def coin():
    n1 = square(988, 0.075) * env(int(SR * 0.075), 0.002, 0.005)
    tt = t(0.45)
    n2 = square(1319, 0.45) * np.exp(-tt * 7)
    return np.concatenate([n1, n2]) * 0.5


def horn():
    def blast(sec):
        tt = t(sec)
        f0 = 440 * (1 + 0.004 * np.sin(2 * np.pi * 6 * tt))
        x = sum(saw(f * 1.0, tt) for f in (f0, f0 * 1.26, f0 * 1.5, f0 * 2.01)) / 4
        x = np.tanh(3.0 * x)          # brassy distortion
        x = lowpass(x, 3500)
        return x * env(len(tt), 0.02, 0.06)
    gap = np.zeros(int(SR * 0.07))
    return np.concatenate([blast(0.17), gap, blast(0.17), gap, blast(0.6)]) * 0.9


def poop():
    sec = 1.5
    tt = t(sec)
    # wobbling low buzz, pitch drifting down
    f = 95 + 35 * np.sin(2 * np.pi * 3.3 * tt) * np.exp(-tt) - 25 * tt
    f += rng.normal(0, 8, len(tt))
    phase = 2 * np.pi * np.cumsum(f) / SR
    buzz = np.sign(np.sin(phase)) * 0.6 + np.sin(phase) * 0.4
    # sputter: random on/off bursts, faster near the end
    gate = np.zeros(len(tt)); i = 0
    while i < len(tt):
        on = int(SR * rng.uniform(0.03, 0.14)); off = int(SR * rng.uniform(0.005, 0.05 if i < len(tt) * 0.6 else 0.02))
        gate[i:i + on] = rng.uniform(0.5, 1.0); i += on + off
    gate = lowpass(gate, 60)
    wet = lowpass(rng.normal(0, 1, len(tt)), 900) * 0.6
    bubbles = np.zeros(len(tt))
    for _ in range(18):
        s = rng.integers(0, len(tt) - 3000); bf = rng.uniform(180, 420); bt = t(0.04)
        bubbles[s:s + len(bt)] += np.sin(2 * np.pi * bf * bt * (1 + 3 * bt)) * np.exp(-bt * 90)
    x = lowpass(buzz * gate + wet * gate, 1600) + bubbles * 0.5
    # final splat
    sp = t(0.18); splat = lowpass(rng.normal(0, 1, len(sp)), 700) * np.exp(-sp * 25) * 1.6
    x = np.concatenate([x, splat])
    return np.tanh(1.5 * x) * env(len(x), 0.01, 0.08)


def write(name, x):
    x = x / max(1e-9, np.max(np.abs(x))) * 0.89
    pcm = (x * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        tmp = f.name
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    out = os.path.abspath(os.path.join(OUT, name + ".ogg"))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp, "-c:a", "libvorbis", "-q:a", "4", out], check=True)
    os.unlink(tmp)
    print(out, os.path.getsize(out), "bytes", round(len(x) / SR, 2), "s")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    write("sfx_coin", coin())
    write("sfx_horn", horn())
    write("sfx_poop", poop())
