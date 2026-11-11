/* Core of the launcher: the TV stage, remote input, UI sounds, springs, colours.

   The page must contain <div id="stage"></div>. It is a fixed 1920x1080 "TV screen" scaled to fit the window,
   so everything is laid out in TV pixels exactly as on the real 1080p set.

   window.TV:
     stage                         the 1920x1080 element
     on(fn)                        fn(action, event) for remote actions:
                                   up down left right ok hold back home control screensaver wallpapers help
                                   ('hold' = OK held 550 ms, like a long press on the remote)
     sound.tick() / select() / back() / bump() / key() / del() / slide(v) / open() / close() / pop() / launch()
                                   / fling() / chime() / error()   tvOS-like UI sounds (M toggles all);
                                   sound.nav, sound.fx (on/off groups) and sound.volume (0..1) are settable
     capture(fn|null)              raw keydown hook for text entry: fn(event) returns true when it used the key
     spring(stiffness, damping, mass)  -> {easing, duration} for CSS/WAAPI (a real spring via linear())
     color(url)                    Promise<[r,g,b]> saturated average colour of an image (for glows)
     idle(ms, fn)                  calls fn after ms without input (screensaver), re-armed by any key;
                                   returns {set(ms)} (0 = never)
     hint(html, ms)                a small hint pill at the bottom-left
     pickFile()                    Promise<File|null> (opens the file dialog; for "своё фото")
     store.get(k, def) / store.set(k, v)
   Keys (desktop browser): arrows = D-pad, Enter/Space = OK (hold = long press), Esc/Backspace = Back, H = Home,
         C = Control Center, S = screensaver, W = wallpapers, G = settings (the gear key), Tab = app switcher,
         M = sound on/off, ? = help.
   On the TV the Android app turns remote keys that a web page never sees (Back, Home, Menu, Settings) into
   TV.emit(action).
*/
(function () {
  const stage = document.getElementById('stage');
  function fit() {
    // Android's WebView reports innerWidth in device pixels; the visual viewport is the real CSS size.
    const vv = window.visualViewport, W = (vv && vv.width) || document.documentElement.clientWidth || innerWidth,
      H = (vv && vv.height) || document.documentElement.clientHeight || innerHeight;
    const s = Math.min(W / 1920, H / 1080);
    stage.style.transform = `translate(${(W - 1920 * s) / 2}px, ${(H - 1080 * s) / 2}px) scale(${s})`;
  }
  addEventListener('resize', fit); fit();

  const handlers = [];
  const store = {
    get(k, d) { try { const v = localStorage.getItem('tvc.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { localStorage.setItem('tvc.' + k, JSON.stringify(v)); },
  };

  // ---------- sound ----------
  // Real sound files come first: assets/sounds/sounds.json lists the files (made by tools/import-sounds.sh) and
  // each one listed replaces the synthesised sound of the same name. Files are decoded ahead of time, while the
  // TV is idle, so the first key press never waits for audio.
  // Without files, the tvOS palette is synthesised (Apple's own sound files are not shipped). tvOS sounds are tiny, dry and
  // physical, like tapping a small piece of glass or hard plastic, so each one is modelled that way: a very short
  // filtered-noise transient (the "contact") plus a few exponentially damped sine modes (the "body"), rendered once
  // into AudioBuffers with a little random variation, then played with a slight pitch jitter so that fast
  // scrolling never sounds like a machine gun. Two groups, as in tvOS (Settings → Video and Audio):
  //   'nav' — navigation clicks: focus tick, select, back, edge bump, keyboard keys, slider steps;
  //   'fx'  — sound effects: panels opening/closing, context-menu pop, app launch whoosh, chimes.
  let ac = null, bus = null, bank = null, lastNav = 0, files = {}, filesGain = {};
  const SOUND_DIR = (document.currentScript ? document.currentScript.src.replace(/core\/core\.js.*$/, '') : '') + 'assets/sounds/';
  let navOn = store.get('sound', true), fxOn = store.get('sound.fx', true), vol = store.get('sound.vol', 0.8), style = store.get('sound.style', 'soft');

  // Recipes. noise: {bp|lp|hp: [freq, Q], sweep: target freq, amp, att, dec}; mode: {f, amp, dec, att, glide, at}.
  // glide = frequency ratio reached at the end of the sound; at = start delay (s). gain = playback level.
  const R = {
    tick: { cat: 'nav', dur: 0.06, gain: 0.24, vary: 0.035, jitter: 0.012, variants: 4, parts: [
      ['noise', { bp: [3300, 1.0], amp: 0.55, dec: 0.0028 }], ['mode', { f: 2250, amp: 0.30, dec: 0.008 }],
      ['mode', { f: 4500, amp: 0.07, dec: 0.003 }], ['mode', { f: 1080, amp: 0.15, dec: 0.014 }]] },
    select: { cat: 'nav', dur: 0.13, gain: 0.32, vary: 0.02, jitter: 0.008, variants: 2, parts: [
      ['noise', { bp: [2100, 0.8], amp: 0.5, dec: 0.0038 }], ['mode', { f: 1360, amp: 0.32, dec: 0.02 }],
      ['mode', { f: 2720, amp: 0.09, dec: 0.008 }], ['mode', { f: 540, amp: 0.30, dec: 0.034 }],
      ['mode', { f: 150, amp: 0.30, dec: 0.03, glide: 0.72, att: 0.0015 }]] },
    back: { cat: 'nav', dur: 0.12, gain: 0.23, vary: 0.02, jitter: 0.008, variants: 2, parts: [
      ['noise', { bp: [1500, 0.8], amp: 0.42, dec: 0.0034 }], ['mode', { f: 990, amp: 0.30, dec: 0.018 }],
      ['mode', { f: 440, amp: 0.28, dec: 0.03 }], ['mode', { f: 128, amp: 0.24, dec: 0.028, glide: 0.78, att: 0.0015 }]] },
    bump: { cat: 'nav', dur: 0.2, gain: 0.32, vary: 0.02, jitter: 0.01, variants: 2, parts: [
      ['mode', { f: 182, amp: 0.7, dec: 0.05, glide: 0.76, att: 0.002 }], ['mode', { f: 392, amp: 0.2, dec: 0.022, glide: 0.86 }],
      ['noise', { lp: [720, 0.7], amp: 0.36, dec: 0.011, att: 0.0012 }]] },
    key: { cat: 'nav', dur: 0.05, gain: 0.25, vary: 0.04, jitter: 0.015, variants: 4, parts: [
      ['noise', { bp: [4300, 1.3], amp: 0.55, dec: 0.0024 }], ['mode', { f: 3050, amp: 0.24, dec: 0.005 }],
      ['mode', { f: 1520, amp: 0.14, dec: 0.01 }]] },
    del: { cat: 'nav', dur: 0.06, gain: 0.25, vary: 0.03, jitter: 0.01, variants: 2, parts: [
      ['noise', { bp: [2500, 1.1], amp: 0.5, dec: 0.003 }], ['mode', { f: 1720, amp: 0.22, dec: 0.008 }],
      ['mode', { f: 760, amp: 0.17, dec: 0.015 }]] },
    pop: { cat: 'fx', dur: 0.12, gain: 0.17, vary: 0.02, jitter: 0.01, variants: 2, parts: [
      ['mode', { f: 290, amp: 0.62, dec: 0.042, glide: 1.65, att: 0.003 }], ['mode', { f: 880, amp: 0.1, dec: 0.014, glide: 1.25 }],
      ['noise', { bp: [1300, 0.9], amp: 0.16, dec: 0.003 }]] },
    open: { cat: 'fx', dur: 0.28, gain: 0.15, vary: 0.03, jitter: 0, variants: 2, parts: [
      ['noise', { bp: [460, 1.5], sweep: 2900, amp: 1, att: 0.09, dec: 0.06 }], ['mode', { f: 1900, amp: 0.05, dec: 0.03, at: 0.11 }]] },
    close: { cat: 'fx', dur: 0.24, gain: 0.13, vary: 0.03, jitter: 0, variants: 2, parts: [
      ['noise', { bp: [2600, 1.5], sweep: 430, amp: 1, att: 0.05, dec: 0.07 }]] },
    zoom: { cat: 'fx', dur: 0.4, gain: 0.13, vary: 0.02, jitter: 0, variants: 1, parts: [
      ['noise', { bp: [300, 1.2], sweep: 1500, amp: 1, att: 0.12, dec: 0.11 }], ['mode', { f: 92, amp: 0.22, dec: 0.1, glide: 1.4, att: 0.05 }]] },
    fling: { cat: 'fx', dur: 0.2, gain: 0.13, vary: 0.03, jitter: 0, variants: 1, parts: [
      ['noise', { bp: [800, 1.4], sweep: 3600, amp: 1, att: 0.04, dec: 0.05 }]] },
    launch: { cat: 'fx', dur: 0.13, gain: 0.32, vary: 0.02, jitter: 0.008, variants: 1, parts: [
      ['noise', { bp: [2100, 0.8], amp: 0.5, dec: 0.0038 }], ['mode', { f: 1360, amp: 0.32, dec: 0.02 }], ['mode', { f: 540, amp: 0.30, dec: 0.034 }]] },
    error: { cat: 'nav', dur: 0.2, gain: 0.32, vary: 0.02, jitter: 0, variants: 1, parts: [
      ['mode', { f: 182, amp: 0.7, dec: 0.05, glide: 0.76, att: 0.002 }], ['noise', { lp: [720, 0.7], amp: 0.36, dec: 0.011, att: 0.0012 }]] },
    chime: { cat: 'fx', dur: 0.9, gain: 0.13, vary: 0, jitter: 0, variants: 1, parts: [
      ['mode', { f: 1318.5, amp: 0.5, dec: 0.22, att: 0.004 }], ['mode', { f: 2637, amp: 0.07, dec: 0.08 }],
      ['mode', { f: 1975.5, amp: 0.5, dec: 0.3, att: 0.004, at: 0.11 }], ['mode', { f: 3951, amp: 0.06, dec: 0.09, at: 0.11 }]] },
  };

  // 'soft' style (default): the same events, muffled and shorter: low-passed contact noise and lower body modes,
  // closer to the quiet, rounded clicks of tvOS. 'crisp' uses the brighter recipes above.
  const SOFT = {
    tick: { cat: 'nav', dur: 0.045, gain: 0.18, vary: 0.03, jitter: 0.01, variants: 4, parts: [
      ['noise', { lp: [2200, 0.7], amp: 0.5, dec: 0.0025 }], ['mode', { f: 1250, amp: 0.35, dec: 0.009 }],
      ['mode', { f: 2500, amp: 0.08, dec: 0.004 }], ['mode', { f: 620, amp: 0.12, dec: 0.012 }]] },
    select: { cat: 'nav', dur: 0.1, gain: 0.3, vary: 0.02, jitter: 0.006, variants: 2, parts: [
      ['noise', { lp: [1800, 0.7], amp: 0.45, dec: 0.003 }], ['mode', { f: 820, amp: 0.38, dec: 0.02 }],
      ['mode', { f: 1640, amp: 0.08, dec: 0.007 }], ['mode', { f: 300, amp: 0.22, dec: 0.028, att: 0.0012 }]] },
    back: { cat: 'nav', dur: 0.09, gain: 0.26, vary: 0.02, jitter: 0.006, variants: 2, parts: [
      ['noise', { lp: [1500, 0.7], amp: 0.4, dec: 0.003 }], ['mode', { f: 640, amp: 0.34, dec: 0.018 }],
      ['mode', { f: 260, amp: 0.2, dec: 0.024, att: 0.0012 }]] },
    bump: { cat: 'nav', dur: 0.16, gain: 0.3, vary: 0.02, jitter: 0.008, variants: 2, parts: [
      ['mode', { f: 150, amp: 0.6, dec: 0.045, glide: 0.85, att: 0.003 }], ['mode', { f: 310, amp: 0.15, dec: 0.02 }],
      ['noise', { lp: [500, 0.7], amp: 0.25, dec: 0.01, att: 0.0015 }]] },
    key: { cat: 'nav', dur: 0.04, gain: 0.22, vary: 0.035, jitter: 0.012, variants: 4, parts: [
      ['noise', { lp: [2600, 0.7], amp: 0.5, dec: 0.002 }], ['mode', { f: 1500, amp: 0.25, dec: 0.006 }], ['mode', { f: 760, amp: 0.12, dec: 0.009 }]] },
    del: { cat: 'nav', dur: 0.05, gain: 0.22, vary: 0.03, jitter: 0.01, variants: 2, parts: [
      ['noise', { lp: [2000, 0.7], amp: 0.5, dec: 0.0025 }], ['mode', { f: 1050, amp: 0.24, dec: 0.008 }], ['mode', { f: 520, amp: 0.14, dec: 0.012 }]] },
  };
  const recipe = (k) => (style === 'soft' && SOFT[k]) || R[k];

  // A tiny DSP kit: seeded noise, RBJ biquads (with an optional exponential sweep), damped sine modes.
  function rng(seed) { return () => { seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return (((t ^ (t >>> 14)) >>> 0) / 4294967296) * 2 - 1; }; }
  function coefs(type, f, q, sr) {
    const w = (2 * Math.PI * Math.min(f, sr * 0.45)) / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q); let b0, b1, b2;
    if (type === 'lp') { b0 = (1 - c) / 2; b1 = 1 - c; b2 = b0; } else if (type === 'hp') { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = b0; } else { b0 = al; b1 = 0; b2 = -al; }
    const a0 = 1 + al; return [b0 / a0, b1 / a0, b2 / a0, (-2 * c) / a0, (1 - al) / a0];
  }
  function filter(x, type, f, q, sr, sweep) {
    let x1 = 0, x2 = 0, y1 = 0, y2 = 0, k = coefs(type, f, q, sr);
    for (let i = 0; i < x.length; i++) {
      if (sweep && i % 32 === 0) k = coefs(type, f * Math.pow(sweep / f, i / x.length), q, sr);
      const v = k[0] * x[i] + k[1] * x1 + k[2] * x2 - k[3] * y1 - k[4] * y2; x2 = x1; x1 = x[i]; y2 = y1; y1 = v; x[i] = v;
    }
    return x;
  }
  function render(r, seed) {
    const sr = ac.sampleRate, n = Math.ceil(r.dur * sr), out = new Float32Array(n), rnd = rng(seed * 7919 + 13), vary = () => 1 + rnd() * r.vary;
    for (const [kind, p] of r.parts) {
      const at = Math.round((p.at || 0) * sr), att = p.att || (kind === 'mode' ? 0.0004 : 0.0005), m = n - at;
      if (kind === 'mode') {
        const f0 = p.f * vary(); let ph = rnd() * 0.3;
        for (let i = 0; i < m; i++) {
          const t = i / sr, f = p.glide ? f0 * Math.pow(p.glide, i / m) : f0; ph += (2 * Math.PI * f) / sr;
          out[at + i] += p.amp * (1 - Math.exp(-t / att)) * Math.exp(-t / p.dec) * Math.sin(ph);
        }
      } else {
        const x = new Float32Array(m); for (let i = 0; i < m; i++) x[i] = rnd();
        const [type, fq] = p.bp ? ['bp', p.bp] : p.lp ? ['lp', p.lp] : ['hp', p.hp];
        filter(x, type, fq[0] * vary(), fq[1], sr, p.sweep);
        const swell = att > 0.01; // a swell (whoosh): rise for `att`, then fall with `dec`
        for (let i = 0; i < m; i++) {
          const t = i / sr, e = swell ? (t < att ? Math.pow(t / att, 1.6) : Math.exp(-(t - att) / p.dec)) : (1 - Math.exp(-t / att)) * Math.exp(-t / p.dec);
          out[at + i] += p.amp * x[i] * e;
        }
      }
    }
    // Normalise, then a short fade at the very end so nothing clicks.
    let peak = 0; for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(out[i]));
    const fade = Math.round(0.004 * sr), g = peak ? 0.9 / peak : 1;
    for (let i = 0; i < n; i++) out[i] *= g * (i > n - fade ? (n - i) / fade : 1);
    const b = ac.createBuffer(1, n, sr); b.getChannelData(0).set(out); return b;
  }
  function audio() {
    if (ac) return ac;
    ac = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
    const comp = ac.createDynamicsCompressor(); comp.threshold.value = -14; comp.knee.value = 8; comp.ratio.value = 4; comp.attack.value = 0.001; comp.release.value = 0.1;
    bus = ac.createGain(); bus.gain.value = Math.pow(vol, 1.5); bus.connect(comp); comp.connect(ac.destination);
    bank = {};
    return ac;
  }
  // Synthesised buffers are rendered one per idle slice; real files are fetched and decoded the same way.
  function synth(k) { if (!bank[k]) { const r = recipe(k); bank[k] = Array.from({ length: r.variants }, (_, i) => render(r, i + 1)); } return bank[k]; }
  const idleCb = window.requestIdleCallback || ((f) => setTimeout(() => f({ timeRemaining: () => 8 }), 60));
  function prepare() {
    try { audio(); } catch { return; }
    const todo = Object.keys(R);
    const step = (d) => { while (todo.length && d.timeRemaining() > 4) synth(todo.shift()); if (todo.length) idleCb(step); };
    idleCb(step);
    fetch(SOUND_DIR + 'sounds.json', { cache: 'no-cache' }).then((r) => (r.ok ? r.json() : {})).then((m) => {
      Object.entries(m.files || {}).forEach(([k, f]) => {
        fetch(SOUND_DIR + f).then((r) => r.arrayBuffer()).then((b) => ac.decodeAudioData(b)).then((buf) => { files[k] = buf; filesGain[k] = (m.gain && m.gain[k]) || 1; }).catch(() => {});
      });
    }).catch(() => {});
  }
  addEventListener('load', () => setTimeout(prepare, 300));
  function play(name, o = {}) {
    const r = recipe(name); if (!r || !(r.cat === 'nav' ? navOn : fxOn) || vol <= 0) return;
    try {
      audio(); if (ac.state === 'suspended') ac.resume();
      const file = files[name], s = ac.createBufferSource(), g = ac.createGain();
      if (file) { s.buffer = file; s.playbackRate.value = o.rate || 1; g.gain.value = filesGain[name] * (o.gain == null ? 1 : o.gain); }
      else {
        const vs = synth(name); s.buffer = vs[(Math.random() * vs.length) | 0];
        s.playbackRate.value = (o.rate || 1) * (1 + (Math.random() * 2 - 1) * r.jitter);
        g.gain.value = r.gain * (o.gain == null ? 1 : o.gain);
      }
      s.connect(g); g.connect(bus); s.start(ac.currentTime + (o.delay || 0));
    } catch { /* no audio */ }
  }
  // Navigation sounds get softer when keys repeat quickly (holding an arrow), as on tvOS.
  function nav(name, o = {}) { const now = performance.now(), fast = now - lastNav < 125; lastNav = now; play(name, { ...o, gain: (o.gain == null ? 1 : o.gain) * (fast ? 0.62 : 1) }); }
  const sound = {
    play,
    tick() { nav('tick'); },                  // focus moved
    select() { play('select'); },             // OK / press
    back() { play('back'); },                 // Back / Menu
    bump() { nav('bump'); },                  // focus hit an edge
    key() { nav('key'); },                    // a key typed on the on-screen keyboard
    del() { nav('del'); },                    // delete on the keyboard
    slide(v) { nav('tick', { rate: 0.8 + Math.max(0, Math.min(1, v)) * 0.5 }); }, // slider step, pitch follows the value
    open() { play('open'); },                 // a panel or sheet appears
    close() { play('close'); },               // ... and goes away
    pop() { play('pop'); },                   // long press: context menu, pick up an icon
    launch() { if (files.launch) play('launch'); else { play('select'); play('zoom', { delay: 0.03 }); } },
    fling() { play('fling'); },               // a card flicked away (app switcher)
    chime() { play('chime'); },               // done / connected
    error() { if (files.error) play('error'); else { play('bump'); play('bump', { delay: 0.11, gain: 0.75, rate: 0.92 }); } },
    sources() { const out = {}; for (const k in R) out[k] = files[k] ? 'file' : 'synth'; return out; },
    names: () => Object.keys(R),
    toggle() { const on = !(navOn || fxOn); navOn = fxOn = on; store.set('sound', on); store.set('sound.fx', on); hint(on ? 'Звуки интерфейса включены' : 'Звуки интерфейса выключены', 1500); if (on) sound.select(); window.dispatchEvent(new Event('soundprefs')); },
    isOn: () => navOn,
    get nav() { return navOn; }, set nav(v) { navOn = !!v; store.set('sound', navOn); },
    get fx() { return fxOn; }, set fx(v) { fxOn = !!v; store.set('sound.fx', fxOn); },
    stats() { // for checking the synthesis: duration, peak and RMS (dBFS) of every rendered sound
      audio(); Object.keys(R).forEach(synth); const out = {};
      for (const k in bank) { const d = bank[k][0].getChannelData(0); let pk = 0, sq = 0; for (const x of d) { pk = Math.max(pk, Math.abs(x)); sq += x * x; } out[k] = { ms: Math.round((d.length / ac.sampleRate) * 1000), peak: +pk.toFixed(3), rmsDb: +(10 * Math.log10(sq / d.length)).toFixed(1), tail: +Math.abs(d[d.length - 1]).toFixed(5) }; }
      return out;
    },
    get style() { return style; }, set style(v) { style = v === 'crisp' ? 'crisp' : 'soft'; store.set('sound.style', style); if (bank) bank = {}; },
    get volume() { return vol; }, set volume(v) { vol = Math.max(0, Math.min(1, v)); store.set('sound.vol', vol); if (bus) bus.gain.value = Math.pow(vol, 1.5); },
  };

  // ---------- spring easing (CSS linear()) ----------
  function spring(stiffness = 170, damping = 22, mass = 1) {
    const w0 = Math.sqrt(stiffness / mass), zeta = damping / (2 * Math.sqrt(stiffness * mass));
    const pos = (t) => {
      if (zeta < 1) { const wd = w0 * Math.sqrt(1 - zeta * zeta); return 1 - Math.exp(-zeta * w0 * t) * (Math.cos(wd * t) + (zeta * w0 / wd) * Math.sin(wd * t)); }
      return 1 - Math.exp(-w0 * t) * (1 + w0 * t);
    };
    // Settled once within 0.3% of the target: the invisible tail is cut, so animations end sooner (less GPU work).
    let T = 0; while (T < 4 && !(Math.abs(1 - pos(T)) < 0.003 && Math.abs(1 - pos(T + 0.05)) < 0.003)) T += 0.01;
    T = Math.max(T, 0.1);
    const N = 48, pts = []; for (let i = 0; i <= N; i++) pts.push(+pos((T * i) / N).toFixed(4));
    let easing = `linear(${pts.join(', ')})`;
    if (!CSS.supports('transition-timing-function', 'linear(0, 1)')) easing = zeta < 1 ? 'cubic-bezier(.2,1.35,.4,1)' : 'cubic-bezier(.2,.9,.3,1)';
    return { easing, duration: Math.round(T * 1000) };
  }

  // ---------- colour ----------
  const colorCache = new Map();
  function color(url) {
    if (colorCache.has(url)) return colorCache.get(url);
    const p = new Promise((res) => {
      const img = new Image(); img.crossOrigin = 'anonymous';
      img.onload = () => {
        const cv = document.createElement('canvas'); cv.width = 32; cv.height = 18; const c = cv.getContext('2d');
        let d;
        try { c.drawImage(img, 0, 0, 32, 18); d = c.getImageData(0, 0, 32, 18).data; } catch { res([120, 120, 140]); return; }
        let r = 0, g = 0, b = 0, n = 0;
        for (let i = 0; i < d.length; i += 4) {
          const R = d[i], G = d[i + 1], B = d[i + 2], mx = Math.max(R, G, B), mn = Math.min(R, G, B), sat = mx ? (mx - mn) / mx : 0;
          const wgt = sat * sat * (mx / 255) + 0.002; r += R * wgt; g += G * wgt; b += B * wgt; n += wgt;
        }
        let out = [r / n, g / n, b / n]; const mx = Math.max(...out) || 1; out = out.map((v) => Math.round(Math.min(255, (v / mx) * 235)));
        res(out);
      };
      img.onerror = () => res([120, 120, 140]); img.src = url;
    });
    colorCache.set(url, p); return p;
  }

  // ---------- hint ----------
  const hintEl = document.createElement('div'); hintEl.className = 'tvc-hint'; document.body.appendChild(hintEl);
  let hintT = 0;
  function hint(html, ms = 3500) { hintEl.innerHTML = html; hintEl.classList.add('on'); clearTimeout(hintT); if (ms) hintT = setTimeout(() => hintEl.classList.remove('on'), ms); }
  const HELP = '<b>Пульт:</b> ← → ↑ ↓ &nbsp;·&nbsp; <b>OK</b> Enter (удерживать — меню) &nbsp;·&nbsp; <b>Назад</b> Esc &nbsp;·&nbsp; <b>Home</b> H<br>' +
    '<b>W</b> фоны &nbsp;·&nbsp; <b>C</b> пункт управления &nbsp;·&nbsp; <b>S</b> заставка &nbsp;·&nbsp; <b>M</b> звук';

  // ---------- idle ----------
  const idles = [];
  function idle(ms, fn) { const o = { ms, fn, t: 0 }; idles.push(o); arm(o); return { set(v) { o.ms = v; arm(o); } }; }
  function arm(o) { clearTimeout(o.t); if (o.ms > 0) o.t = setTimeout(o.fn, o.ms); }

  // ---------- input ----------
  const MAP = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Escape: 'back', Backspace: 'back',
    h: 'home', H: 'home', р: 'home', c: 'control', C: 'control', с: 'control', s: 'screensaver', S: 'screensaver', ы: 'screensaver',
    w: 'wallpapers', W: 'wallpapers', ц: 'wallpapers', g: 'settings', G: 'settings', п: 'settings', Tab: 'switcher', '?': 'help', '/': 'help' };
  let lastRepeat = 0, okDown = 0, holdT = 0, held = false, capture = null;
  function emit(a, e) { idles.forEach(arm); for (const h of handlers) h(a, e); }
  addEventListener('keydown', (e) => {
    if (e.target && /INPUT|TEXTAREA/.test(e.target.tagName)) return;
    if (capture && !e.metaKey && !e.ctrlKey && capture(e) === true) { e.preventDefault(); idles.forEach(arm); return; }
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault(); if (e.repeat) return;
      okDown = performance.now(); held = false;
      holdT = setTimeout(() => { held = true; emit('hold', e); }, 550);
      return;
    }
    if (e.key === 'm' || e.key === 'M' || e.key === 'ь') { sound.toggle(); return; }
    const a = MAP[e.key]; if (!a) return;
    e.preventDefault();
    if (a === 'help') { hint(api.helpText || HELP, 9000); return; }
    if (e.repeat) { const now = performance.now(); if (now - lastRepeat < 95) return; lastRepeat = now; }
    emit(a, e);
  });
  addEventListener('keyup', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { clearTimeout(holdT); if (!held && okDown) emit('ok', e); okDown = 0; }
  });
  if (!window.Android && !window.TV_NO_HINT) setTimeout(() => hint(api.helpText || HELP, 7000), 600);

  function pickFile() {
    return new Promise((res) => {
      const i = document.createElement('input'); i.type = 'file'; i.accept = 'image/*';
      i.onchange = () => res(i.files[0] || null); i.oncancel = () => res(null); i.click();
    });
  }

  const api = window.TV = { stage, on: (fn) => handlers.push(fn), sound, spring, color, idle, hint, pickFile, store, HELP,
    capture(fn) { capture = fn || null; }, helpText: '',
    emit(a) { emit(a, {}); } };
})();
