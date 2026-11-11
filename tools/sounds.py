#!/usr/bin/env python3
"""Renders the launcher's interface sounds into android/app/src/main/res/raw/ui_*.wav (48 kHz, 16-bit, mono).

Original sounds made for this project (no recordings, nothing of Apple's): each one is modelled as a small,
hard object being tapped — a very short contact transient plus a few damped resonant modes — the way tvOS clicks
feel: soft, short, rounded, never hissy. Tuned for TV speakers: the energy sits between 400 Hz and 3 kHz (TV
speakers cannot play deep bass and make the 4-8 kHz band harsh), the attack is rounded off over a fraction of a
millisecond so nothing crackles, and a gentle low-pass takes the edge off the top.

    python3 -I tools/sounds.py          # standard library only
"""
import math
import os
import random
import struct
import wave

SR = 48000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'android', 'app', 'src', 'main', 'res', 'raw')


def biquad(x, kind, f, q):
    """RBJ biquad filter (band-pass with 0 dB peak, or low-pass) over a list of samples."""
    w = 2 * math.pi * min(f, SR * 0.45) / SR
    c, al = math.cos(w), math.sin(w) / (2 * q)
    if kind == 'lp':
        b0, b1, b2 = (1 - c) / 2, 1 - c, (1 - c) / 2
    else:
        b0, b1, b2 = al, 0.0, -al
    a0, a1, a2 = 1 + al, -2 * c, 1 - al
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y, x1, x2, y1, y2 = [], 0.0, 0.0, 0.0, 0.0
    for v in x:
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y.append(o)
    return y


def render(dur, modes=(), noise=(), lp=7000, at=0.0006, seed=1, delay=0.0):
    """modes: (freq, amp, decay s, glide ratio at the end); noise: (centre freq, Q, amp, decay s)."""
    rnd = random.Random(seed)
    n = int(dur * SR)
    out = [0.0] * n
    d0 = int(delay * SR)
    for f, amp, dec, glide in modes:
        ph = rnd.random() * 0.2
        m = n - d0
        for i in range(m):
            t = i / SR
            fr = f * (glide ** (i / m)) if glide != 1 else f
            ph += 2 * math.pi * fr / SR
            env = (0.5 - 0.5 * math.cos(math.pi * min(1.0, t / at))) * math.exp(-t / dec)
            out[d0 + i] += amp * env * math.sin(ph)
    for fc, q, amp, dec in noise:
        m = n - d0
        x = biquad([rnd.uniform(-1, 1) for _ in range(m)], 'bp', fc, q)
        for i in range(m):
            t = i / SR
            env = (0.5 - 0.5 * math.cos(math.pi * min(1.0, t / (at * 0.6)))) * math.exp(-t / dec)
            out[d0 + i] += amp * 2.2 * env * x[i]
    if lp:
        out = biquad(out, 'lp', lp, 0.62)
    fade = int(0.006 * SR)
    for i in range(fade):
        out[n - 1 - i] *= i / fade
    return out


def mix(*parts):
    n = max(len(p) for p in parts)
    return [sum(p[i] for p in parts if i < len(p)) for i in range(n)]


def save(name, x, peak_db):
    pk = max(abs(v) for v in x) or 1.0
    g = (10 ** (peak_db / 20)) / pk
    path = os.path.join(OUT, 'ui_%s.wav' % name)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-32767, min(32767, round(v * g * 32767))))) for v in x))
    rms = math.sqrt(sum(v * v for v in x) / len(x)) * g
    print('%-7s %4d ms  peak %5.1f dBFS  rms %5.1f dBFS' % (name, len(x) * 1000 // SR, peak_db, 20 * math.log10(rms)))


def main():
    os.makedirs(OUT, exist_ok=True)
    # Focus moved: a tiny rounded "tick" — a light tap on a small piece of glass.
    save('tick', render(0.05, modes=[(1720, 1.0, 0.0085, 0.975), (3990, 0.16, 0.0032, 1), (860, 0.30, 0.012, 0.97)],
                        noise=[(2900, 0.9, 0.30, 0.0011)], lp=7200, at=0.0005, seed=3), -9.5)
    # OK: a fuller "tock" with a soft body.
    save('select', render(0.09, modes=[(930, 1.0, 0.020, 0.95), (2510, 0.22, 0.0055, 1), (465, 0.50, 0.026, 0.92)],
                          noise=[(2200, 0.8, 0.28, 0.0014)], lp=6200, at=0.0007, seed=5), -7.0)
    # Back: the same family, lower and a little softer, falling slightly.
    save('back', render(0.085, modes=[(720, 1.0, 0.017, 0.92), (1940, 0.18, 0.005, 1), (360, 0.45, 0.022, 0.88)],
                        noise=[(1800, 0.8, 0.22, 0.0013)], lp=5600, at=0.0008, seed=7), -8.5)
    # On-screen keyboard: a very light, quick tap.
    save('key', render(0.042, modes=[(2150, 1.0, 0.0055, 0.98), (4700, 0.12, 0.0022, 1), (1075, 0.24, 0.008, 1)],
                       noise=[(3400, 1.0, 0.32, 0.0009)], lp=8000, at=0.0004, seed=11), -10.5)
    # Delete: like a key, a step lower.
    save('del', render(0.048, modes=[(1480, 1.0, 0.0075, 0.95), (740, 0.34, 0.011, 0.96)],
                       noise=[(2500, 0.9, 0.28, 0.0010)], lp=7000, at=0.0005, seed=13), -10.5)
    # Done / connected: two soft glass notes (E6 then B6) with a quiet octave below for warmth.
    a = render(0.7, modes=[(1318.5, 1.0, 0.16, 1), (2637, 0.10, 0.05, 1), (659.3, 0.22, 0.12, 1)], lp=7000, at=0.004, seed=17)
    b = render(0.7, modes=[(1975.5, 0.9, 0.24, 1), (3951, 0.07, 0.06, 1), (987.8, 0.20, 0.16, 1)], lp=7000, at=0.004, seed=19, delay=0.11)
    save('chime', mix(a, b), -11.0)
    # Error: two muted low knocks.
    e1 = render(0.24, modes=[(330, 1.0, 0.035, 0.9), (700, 0.28, 0.012, 0.95)], noise=[(600, 0.7, 0.25, 0.006)], lp=2600, at=0.0015, seed=23)
    e2 = render(0.24, modes=[(310, 0.8, 0.035, 0.9), (660, 0.22, 0.012, 0.95)], noise=[(560, 0.7, 0.2, 0.006)], lp=2600, at=0.0015, seed=29, delay=0.11)
    save('error', mix(e1, e2), -9.0)


if __name__ == '__main__':
    main()
