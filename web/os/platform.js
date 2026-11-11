/* Platform layer: everything the launcher needs to know about the device.

   On the TV the page runs inside the Android app (android/), which exposes window.Android (Bridge.java) and
   real values come from the system: installed apps with their banners, the network, the device, TV inputs,
   disabled packages. In a desktop browser (preview, screenshots) the same calls return demo data.

   window.Platform:
     tv                         true inside the Android app
     apps()                     [{id, pkg, name, banner, icon, hideByDefault}] launchable apps (no banner: an icon-only
                                app; hideByDefault: a phone-style app or another home screen)
     launch(pkg)                start an app -> bool
     open(what, arg)            open an Android settings screen ('wifi', 'bluetooth', 'display', 'sound', 'apps',
                                'appinfo' + package, 'accounts', 'about', 'update', 'language', 'date', 'developer',
                                'accessibility', 'storage', 'settings') -> bool
     device()                   {name, maker, model, android, sdk, cores, abi, hardware, ramMB, ramFreeMB,
                                 storageTotalGB, storageFreeGB, display, webview, appVersion}
     network()                  {connected, type: 'wifi'|'ethernet'|'other'|'none', ssid, ip, signal 0..3, linkMbps, freqMHz}
     inputs()                   [{id, name, connected}]       TV inputs (HDMI, AV, tuner)
     openInput(id)              -> bool
     disabled()                 [{pkg, name}]                 packages disabled on this TV
     system()                   {adb, animScale, home}
     aerial(file)               URL of an Aerial film
   Also window.TVApps (apps() plus the launcher's own Settings tile). */
(function () {
  const A = window.Android;
  const json = (fn, d) => { try { const v = JSON.parse(fn()); return v == null ? d : v; } catch { return d; } };

  // ---------- demo apps for the browser: original, drawn here (no third-party logos) ----------
  const GLYPH = {
    play: 'M-38 -48 L52 0 L-38 48 Z',
    film: 'M-60 -40 h120 v80 h-120 Z M-60 -20 h120 M-60 20 h120 M-30 -40 v80 M30 -40 v80',
    tv: 'M-64 -40 h128 v76 h-128 Z M-24 56 h48 M-30 -64 L0 -40 L30 -64',
    note: 'M-20 40 a16 16 0 1 1 0 -0.1 M-4 40 V-48 L52 -60 V24 M52 24 a16 16 0 1 1 0 -0.1',
    live: 'M0 0 m-14 0 a14 14 0 1 0 28 0 a14 14 0 1 0 -28 0 M-40 -40 a56 56 0 0 0 0 80 M40 -40 a56 56 0 0 1 0 80 M-62 -62 a88 88 0 0 0 0 124 M62 -62 a88 88 0 0 1 0 124',
    folder: 'M-64 -36 h48 l12 14 h68 v72 h-128 Z',
    bag: 'M-52 -24 h104 l-8 80 h-88 Z M-24 -24 v-12 a24 24 0 0 1 48 0 v12',
    photo: 'M-64 -44 h128 v88 h-128 Z M-64 28 l40 -36 l32 28 l24 -20 l32 28 M28 -20 m-10 0 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0',
    game: 'M-60 -24 h120 a28 28 0 0 1 0 56 h-120 a28 28 0 0 1 0 -56 Z M-40 4 h24 M-28 -8 v24 M30 0 h0.1 M44 12 h0.1',
  };
  const DEMO = [
    ['demo.video', 'Видео', 'play', ['#ff5f6d', '#c31432']], ['demo.cinema', 'Кино', 'film', ['#f7971e', '#d35400']],
    ['demo.tv', 'Телеканалы', 'tv', ['#4facfe', '#1e5bd8']], ['demo.music', 'Музыка', 'note', ['#43e97b', '#11998e']],
    ['demo.live', 'Трансляции', 'live', ['#b06ab3', '#6a3093']], ['demo.files', 'Файлы', 'folder', ['#fbc531', '#e1a100']],
    ['demo.store', 'Магазин', 'bag', ['#2bc0e4', '#0e7c9b']], ['demo.photos', 'Фото', 'photo', ['#ff9a9e', '#e05b8f']],
    ['demo.games', 'Игры', 'game', ['#667eea', '#4b3fa8']],
  ];
  function demoBanner(name, glyph, [c1, c2]) {
    const cv = document.createElement('canvas'); cv.width = 630; cv.height = 378; const c = cv.getContext('2d');
    const g = c.createLinearGradient(0, 0, 630, 378); g.addColorStop(0, c1); g.addColorStop(1, c2); c.fillStyle = g; c.fillRect(0, 0, 630, 378);
    const hl = c.createRadialGradient(150, 40, 0, 150, 40, 420); hl.addColorStop(0, 'rgba(255,255,255,.28)'); hl.addColorStop(1, 'rgba(255,255,255,0)'); c.fillStyle = hl; c.fillRect(0, 0, 630, 378);
    c.save(); c.translate(315, 160); c.strokeStyle = '#fff'; c.fillStyle = '#fff'; c.lineWidth = 12; c.lineJoin = 'round'; c.lineCap = 'round';
    const p = new Path2D(GLYPH[glyph]); if (glyph === 'play') c.fill(p); else c.stroke(p); c.restore();
    c.fillStyle = '#fff'; c.font = '600 44px -apple-system, "SF Pro Display", Roboto, sans-serif'; c.textAlign = 'center'; c.fillText(name, 315, 320);
    return cv.toDataURL('image/png');
  }
  let demoApps = null;
  const demo = () => demoApps || (demoApps = DEMO.map(([id, name, glyph, col]) => ({ id, pkg: id, name, banner: demoBanner(name, glyph, col), icon: null, demo: true })));

  const P = window.Platform = {
    tv: !!A,
    apps: () => (A ? json(() => A.apps(), []).map((a) => ({ id: a.pkg, pkg: a.pkg, name: a.name, banner: a.banner || null, icon: a.icon || null, hideByDefault: a.tv === false })) : demo()),
    launch: (pkg) => (A ? !!A.launch(pkg) : false),
    open: (what, arg) => (A ? !!A.openSettings(what, arg || '') : false),
    device() {
      if (A) return json(() => A.device(), {});
      const ua = navigator.userAgent, chrome = (ua.match(/Chrome\/([\d.]+)/) || [])[1];
      return { name: 'Браузер (демо)', maker: '', model: 'Просмотр на компьютере', android: '', cores: navigator.hardwareConcurrency || 0,
        ramMB: navigator.deviceMemory ? navigator.deviceMemory * 1024 : 0, display: `${screen.width * devicePixelRatio} × ${screen.height * devicePixelRatio}`,
        webview: chrome ? 'Chromium ' + chrome.split('.')[0] : '', appVersion: '' };
    },
    network() {
      if (A) return json(() => A.network(), { connected: false, type: 'none' });
      return { connected: navigator.onLine, type: 'wifi', ssid: 'Демо-сеть', ip: '', signal: 3, linkMbps: 0, freqMHz: 5180 };
    },
    inputs: () => (A ? json(() => A.inputs(), []) : [{ id: 'demo-hdmi1', name: 'HDMI 1', connected: true }, { id: 'demo-hdmi2', name: 'HDMI 2' }, { id: 'demo-hdmi3', name: 'HDMI 3' }, { id: 'demo-tuner', name: 'Антенна' }]),
    openInput: (id) => (A ? !!A.openInput(id) : false),
    disabled: () => (A ? json(() => A.disabled(), []) : []),
    system: () => (A ? json(() => A.system(), {}) : {}),
    reload: () => (A ? A.reload() : location.reload()),
    // Apple's Aerial films: on the TV straight from Apple (the WebView plays them as they are; Apple's root
    // certificate is added for that one server in android/.../res/xml/network_security.xml); in a desktop browser
    // through tools/serve.py, which relabels Apple's video/quicktime as video/mp4 for Chromium.
    aerial: (file) => (A ? 'https://sylvan.apple.com/Videos/' : '/aerial/') + file,
  };

  // The launcher's own Settings is an app tile too (its banner is drawn here).
  const SETTINGS_BANNER = 'data:image/svg+xml;utf8,' + encodeURIComponent(`
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 360">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#9aa0a8"/><stop offset="1" stop-color="#5e646c"/></linearGradient>
    <linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f4f5f7"/><stop offset="1" stop-color="#c9ccd2"/></linearGradient>
  </defs>
  <rect width="640" height="360" fill="url(#bg)"/>
  <g transform="translate(320 180)">
    <g fill="url(#g)">
      ${Array.from({ length: 12 }, (_, i) => `<rect x="-14" y="-118" width="28" height="40" rx="6" transform="rotate(${i * 30})"/>`).join('')}
      <circle r="92"/>
    </g>
    <circle r="62" fill="#6f757d"/>
    <circle r="36" fill="url(#g)"/>
    <circle r="20" fill="#7b8189"/>
  </g>
</svg>`);
  window.TVApps = [...P.apps(), { id: 'settings', pkg: '', name: 'Настройки', banner: SETTINGS_BANNER, icon: null, tagline: 'Настройки лаунчера и ТВ' }];
})();
