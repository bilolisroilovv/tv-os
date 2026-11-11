/* System layer of the launcher (Apple TV style).

   window.OS:
     pref(k) / setPref(k, v) / onPref(k, fn)   persisted settings ('tvos.' + k in localStorage) with change events
     I, svg(k)                                  the glyph set (24x24 line icons)
     speak(text)                                VoiceOver-style speech (only when the VoiceOver setting is on)
     alert({title, text, icon, buttons: [{t, red, cancel}], focus})  -> Promise<button index | -1>
     keyboard({title, sub, value, secure, placeholder, max, done})     -> Promise<string | null>
     top() / key(action)                        the topmost modal layer (alert, keyboard, app switcher) gets the keys
     pushLayer(l) / dropLayer(l)                l = {key(action)}
     recent(id) / recents() / forget(id)        apps in the app switcher (most recent last)
     reboot()                                   black screen with a spinner, then reload
     isFast() / weakDevice                      fast rendering mode (setting 'perf': auto | fast | full)
     zoom(el, rect, open)                       open/close a full-screen layer out of an icon rect -> Promise
     glassPos(els?)                             tell fast-mode glass where each .lg element sits on the stage
   Settings and the app switcher live in settings.js and switcher.js and hang off this object. */
(function () {
  const stage = TV.stage;
  const OS = window.OS = {};
  OS.el = (cls, html, before) => {
    const e = document.createElement('div'); e.className = cls; e.innerHTML = html || '';
    const ref = before && stage.querySelector(before); if (ref) stage.insertBefore(e, ref); else stage.appendChild(e); return e;
  };

  // ---------- preferences ----------
  const DEF = {
    look: 'light', clock: 'center', bright: 100, hidden: [],
    saverType: 'aerial', saverAfter: 2, saverClock: true, saverCaps: true, saverCats: ['Landscapes', 'Cities', 'Space', 'Underwater'],
    sleepAfter: 0, bold: false, solid: false, contrast: false, still: false, voice: false, subs: false,
    userName: 'Пользователь', userColor: 'blue', hour24: true, perf: 'auto', fps: false,
  };
  const subs = {};
  OS.pref = (k) => TV.store.get('tvos.' + k, DEF[k]);
  OS.setPref = (k, v) => { TV.store.set('tvos.' + k, v); (subs[k] || []).forEach((f) => f(v)); };
  OS.onPref = (k, f) => { (subs[k] = subs[k] || []).push(f); };
  OS.defaults = DEF;

  // ---------- glyphs ----------
  const gear = (() => { // an 8-tooth gear outline
    const p = [], n = 8, ro = 9.6, ri = 7.6;
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2, w = (Math.PI * 2) / n;
      [[a - w * 0.5, ri], [a - w * 0.2, ri], [a - w * 0.13, ro], [a + w * 0.13, ro], [a + w * 0.2, ri], [a + w * 0.5, ri]]
        .forEach(([t, r]) => p.push(`${(12 + Math.cos(t) * r).toFixed(2)} ${(12 + Math.sin(t) * r).toFixed(2)}`));
    }
    return `<path d="M${p.join('L')}Z"/><circle cx="12" cy="12" r="3.2"/>`;
  })();
  const I = OS.I = {
    gear,
    user: '<circle cx="12" cy="8.2" r="3.7"/><path d="M4.6 19.8c.9-3.7 3.9-5.8 7.4-5.8s6.5 2.1 7.4 5.8"/>',
    av: '<rect x="2.5" y="4.5" width="19" height="12.5" rx="2.4"/><path d="M8.5 20.2h7"/><path d="M7.6 9.4h1.7l2.3-1.9v6.5l-2.3-1.9H7.6z"/><path d="M14 9.2a2.7 2.7 0 0 1 0 3.6"/><path d="M15.9 7.7a5 5 0 0 1 0 6.6"/>',
    saver: '<rect x="2.5" y="4.5" width="19" height="15" rx="2.6"/><path d="M2.9 16.6l5.2-5.2 4.4 4.4 3-3 5.6 5.6"/><circle cx="16.2" cy="9" r="1.6"/>',
    remote: '<rect x="7.2" y="2.5" width="9.6" height="19" rx="3.4"/><circle cx="12" cy="7.6" r="2.6"/><circle cx="10" cy="13.2" r=".7"/><circle cx="14" cy="13.2" r=".7"/><circle cx="10" cy="16.6" r=".7"/><circle cx="14" cy="16.6" r=".7"/>',
    apps: '<rect x="3.5" y="3.5" width="7" height="7" rx="2.1"/><rect x="13.5" y="3.5" width="7" height="7" rx="2.1"/><rect x="3.5" y="13.5" width="7" height="7" rx="2.1"/><rect x="13.5" y="13.5" width="7" height="7" rx="2.1"/>',
    wifi: '<path d="M2.5 9.2a14 14 0 0 1 19 0"/><path d="M5.6 12.6a9.4 9.4 0 0 1 12.8 0"/><path d="M8.8 16a4.8 4.8 0 0 1 6.4 0"/><circle cx="12" cy="19.2" r=".9" fill="currentColor" stroke="none"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z"/>',
    access: '<circle cx="12" cy="12" r="9.2"/><circle cx="12" cy="7.3" r="1.35" fill="currentColor" stroke="none"/><path d="M7.2 9.9l4.8 1.1 4.8-1.1"/><path d="M12 11v3.3l-2.5 4.3M12 14.3l2.5 4.3"/>',
    shield: '<path d="M12 2.8l7.5 3v5.6c0 4.7-3.2 8.3-7.5 9.8-4.3-1.5-7.5-5.1-7.5-9.8V5.8z"/><path d="M8.8 12.2l2.3 2.3 4.3-4.6"/>',
    chip: '<rect x="6" y="6" width="12" height="12" rx="2.4"/><rect x="9.3" y="9.3" width="5.4" height="5.4" rx="1"/><path d="M9.5 3v3M14.5 3v3M9.5 18v3M14.5 18v3M3 9.5h3M3 14.5h3M18 9.5h3M18 14.5h3"/>',
    dev: '<path d="M8.5 7.5L4 12l4.5 4.5M15.5 7.5L20 12l-4.5 4.5M13.4 5.5l-2.8 13"/>',
    moon: '<path d="M20 14.6A8.5 8.5 0 0 1 9.4 4a8.5 8.5 0 1 0 10.6 10.6z"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.5"/><circle cx="12" cy="7.8" r=".95" fill="currentColor" stroke="none"/>',
    update: '<path d="M20 12a8 8 0 0 1-14.3 4.9"/><path d="M4 12a8 8 0 0 1 14.3-4.9"/><path d="M18.6 3.6v3.6H15M5.4 20.4v-3.6H9"/>',
    reset: '<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4 3.5v4h4"/>',
    look: '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5a8.5 8.5 0 0 1 0 17z" fill="currentColor"/>',
    home: '<rect x="2.5" y="4" width="19" height="16" rx="2.6"/><rect x="5.3" y="12.6" width="3.6" height="2.6" rx=".7"/><rect x="10.2" y="12.6" width="3.6" height="2.6" rx=".7"/><rect x="15.1" y="12.6" width="3.6" height="2.6" rx=".7"/><path d="M9 7.6h6"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 6.8V12l3.4 2"/>',
    keyboard: '<rect x="2.5" y="6" width="19" height="12" rx="2.3"/><path d="M6 9.6h.01M9 9.6h.01M12 9.6h.01M15 9.6h.01M18 9.6h.01M7.5 12.4h.01M10.5 12.4h.01M13.5 12.4h.01M16.5 12.4h.01M8 15.2h8"/>',
    bt: '<path d="M7 7.5l10 9-5 4.5V3l5 4.5-10 9"/>',
    sound: '<path d="M4 9.5h4l5-4v13l-5-4H4z"/><path d="M16.5 9a4.5 4.5 0 0 1 0 6"/><path d="M19 6.5a8 8 0 0 1 0 11"/>',
    lock: '<rect x="5.5" y="10.5" width="13" height="10" rx="2.3"/><path d="M8.5 10.5V7.8a3.5 3.5 0 0 1 7 0v2.7"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    eth: '<rect x="4" y="7" width="16" height="12" rx="2.2"/><path d="M8 19v-3.2M11 19v-3.2M14 19v-3.2M17 19v-3.2M9 7V4.5h6V7"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    del: '<path d="M9 5.5h10.5a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9L3 12z"/><path d="M12.5 9.5l5 5M17.5 9.5l-5 5"/>',
    play: '<path d="M8 5.5v13l10.5-6.5z"/>',
    power: '<path d="M12 3.5v8"/><path d="M6.5 7.2a7.8 7.8 0 1 0 11 0"/>',
    tv: '<rect x="2.5" y="4.5" width="19" height="13" rx="2.4"/><path d="M8 20h8"/>',
    hand: '<path d="M8 13V5.5a1.5 1.5 0 0 1 3 0V11"/><path d="M11 10V4a1.5 1.5 0 0 1 3 0v6"/><path d="M14 10.5V5.5a1.5 1.5 0 0 1 3 0V14c0 4-2.7 7-6.5 7-2.6 0-4.3-1.3-5.6-3.4L3.4 15a1.5 1.5 0 0 1 2.4-1.8L8 15"/>',
    photo: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><circle cx="9" cy="10" r="1.8"/><path d="M21 16l-5-5-8 8"/>',
    text: '<path d="M7 4.5h6a3.6 3.6 0 0 1 0 7.2H7zM7 11.7h7a3.9 3.9 0 0 1 0 7.8H7z"/>',
    motion: '<path d="M3 12h4l3-7 4 14 3-7h4"/>',
    contrast: '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5v17M12 8l5.5-3M12 12.5l8-3.5M12 17l7.3-3.5"/>',
    cc: '<rect x="3" y="4" width="18" height="7" rx="3.5"/><circle cx="7.5" cy="7.5" r="1.8"/><rect x="3" y="13" width="18" height="7" rx="3.5"/><circle cx="16.5" cy="16.5" r="1.8"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    speed: '<path d="M4.4 17a8.6 8.6 0 1 1 15.2 0"/><path d="M12 13.6l4.2-4.4"/><circle cx="12" cy="13.6" r="1.4" fill="currentColor" stroke="none"/><path d="M7.6 8.6l.9.9M12 6.3v1.3M16.4 8.6l-.9.9"/>',
  };
  OS.svg = (k) => `<svg viewBox="0 0 24 24">${I[k] || ''}</svg>`;
  // Wi-Fi strength: 1..3 arcs drawn, the rest faint.
  OS.wifiSig = (n) => `<svg viewBox="0 0 24 24"><path d="M2.5 9.2a14 14 0 0 1 19 0" opacity="${n >= 3 ? 1 : 0.28}"/><path d="M5.6 12.6a9.4 9.4 0 0 1 12.8 0" opacity="${n >= 2 ? 1 : 0.28}"/><path d="M8.8 16a4.8 4.8 0 0 1 6.4 0"/><circle cx="12" cy="19.2" r=".9" fill="currentColor" stroke="none"/></svg>`;

  // ---------- VoiceOver (speech) ----------
  OS.speak = (text) => {
    if (!OS.pref('voice') || !window.speechSynthesis || !text) return;
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(String(text).replace(/<[^>]+>/g, ' ')), v = speechSynthesis.getVoices().find((x) => /^ru/i.test(x.lang));
    u.lang = 'ru-RU'; if (v) u.voice = v; u.rate = 1.08; speechSynthesis.speak(u);
  };
  OS.onPref('voice', (on) => { if (!on && window.speechSynthesis) speechSynthesis.cancel(); });

  // ---------- modal layers ----------
  const layers = [];
  OS.top = () => layers[layers.length - 1] || null;
  OS.key = (a) => { const t = OS.top(); if (!t) return false; t.key(a); return true; };
  OS.pushLayer = (l) => layers.push(l);
  OS.dropLayer = (l) => { const i = layers.indexOf(l); if (i >= 0) layers.splice(i, 1); };

  // ---------- recents (app switcher) ----------
  let recents = [];
  OS.recent = (id) => { recents = recents.filter((x) => x !== id); recents.push(id); };
  OS.recents = () => recents.slice();
  OS.forget = (id) => { recents = recents.filter((x) => x !== id); };

  // ---------- alert ----------
  const alertEl = OS.el('osalert', '<div class="osveil"></div><div class="abox"><div class="aic"></div><h2></h2><p></p><div class="abtns"></div></div>', '#bright');
  OS.alert = (o) => new Promise((res) => {
    const btns = o.buttons || [{ t: 'OK' }], box = alertEl.querySelector('.abox'), list = box.querySelector('.abtns'), ic = box.querySelector('.aic');
    let i = o.focus || 0;
    box.querySelector('h2').textContent = o.title || '';
    const p = box.querySelector('p'); p.textContent = o.text || ''; p.style.display = o.text ? '' : 'none';
    ic.innerHTML = ''; ic.style.backgroundImage = '';
    if (o.icon && o.icon.startsWith('<')) ic.innerHTML = o.icon; else if (o.icon) { ic.style.backgroundImage = `url("${o.icon}")`; ic.innerHTML = '<i></i>'; ic.style.width = '200px'; ic.style.borderRadius = '24px'; }
    if (!o.icon || o.icon.startsWith('<')) { ic.style.width = ''; ic.style.borderRadius = ''; }
    list.innerHTML = btns.map((b) => `<div class="abtn${b.red ? ' red' : ''}">${b.t}</div>`).join('');
    const paint = () => [...list.children].forEach((e, j) => e.classList.toggle('on', j === i));
    paint(); alertEl.classList.add('on'); TV.sound.open();
    OS.speak(`${o.title || ''}. ${o.text || ''}. ${btns[i].t}`);
    const layer = {
      key(a) {
        if (a === 'up' || a === 'down') {
          const n = i + (a === 'up' ? -1 : 1);
          if (n >= 0 && n < btns.length) { i = n; TV.sound.tick(); paint(); OS.speak(btns[i].t); } else TV.sound.bump();
        } else if (a === 'left' || a === 'right') TV.sound.bump();
        else if (a === 'ok') { TV.sound.select(); done(i); }
        else if (a === 'back' || a === 'home') { TV.sound.back(); done(btns.findIndex((b) => b.cancel)); }
      },
    };
    function done(r) { OS.dropLayer(layer); alertEl.classList.remove('on'); res(r); }
    OS.pushLayer(layer);
  });

  // ---------- on-screen keyboard (tvOS linear keyboard: one row of letters, a row of controls) ----------
  const LAY = { ru: 'абвгдеёжзийклмнопрстуфхцчшщъыьэюя', en: 'abcdefghijklmnopqrstuvwxyz', num: '1234567890.,-_@!?#$%&*()/:;+=\'"' };
  const kbEl = OS.el('oskb', '<div class="osveil"></div><div class="ktitle"></div><div class="ksub"></div><div class="kfield"></div><div class="krow"></div><div class="kctl"></div>'
    + '<div class="khint">Можно печатать и с клавиатуры Mac · Esc — отмена</div>', '#bright');
  OS.keyboard = (o) => new Promise((res) => {
    let value = o.value || '', lay = o.layout || (/[a-z]/i.test(o.value || '') || o.secure ? 'en' : 'ru'), caps = false, num = false;
    let row = 0, ki = 0, ci = 0, reveal = -1, revealT = 0, show = false, typed = false;
    const max = o.max || 64;
    kbEl.querySelector('.ktitle').textContent = o.title || '';
    kbEl.querySelector('.ksub').textContent = o.sub || ''; kbEl.querySelector('.ksub').style.display = o.sub ? '' : 'none';
    const field = kbEl.querySelector('.kfield'), krow = kbEl.querySelector('.krow'), kctl = kbEl.querySelector('.kctl');
    const chars = () => { const s = num ? LAY.num : LAY[lay]; return [...(caps && !num ? s.toUpperCase() : s)]; };
    const ctls = () => [
      { id: 'space', h: 'Пробел' },
      { id: 'del', h: OS.svg('del') },
      { id: 'caps', h: num ? '' : (lay === 'ru' ? (caps ? 'абв' : 'АБВ') : (caps ? 'abc' : 'ABC')), cur: caps },
      { id: 'num', h: num ? (lay === 'ru' ? 'абв' : 'abc') : '123', cur: num },
      { id: 'lang', h: `${OS.svg('globe')}${lay === 'ru' ? 'English' : 'Русский'}` },
      ...(o.secure ? [{ id: 'show', h: `${OS.svg('eye')}${show ? 'Скрыть' : 'Показать'}` }] : []),
      { id: 'done', h: o.done || 'Готово', go: true },
    ].filter((c) => c.h !== '');
    function drawField() {
      const shown = o.secure && !show ? [...value].map((c, j) => (j === reveal ? c : '•')).join('') : value;
      field.innerHTML = (value ? `<span>${shown.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</span>` : `<span class="ph">${o.placeholder || ''}</span>`) + '<span class="car"></span>';
    }
    function drawKeys() {
      const cs = chars(); ki = Math.min(ki, cs.length - 1);
      krow.innerHTML = cs.map((c) => `<div class="kk">${c}</div>`).join('');
      const cl = ctls(); ci = Math.min(ci, cl.length - 1);
      kctl.innerHTML = cl.map((c) => `<div class="kc${c.go ? ' go' : ''}${c.cur ? ' cur' : ''}">${c.h}</div>`).join('');
      paint();
    }
    function paint() {
      [...krow.children].forEach((e, j) => e.classList.toggle('on', row === 0 && j === ki));
      [...kctl.children].forEach((e, j) => e.classList.toggle('on', row === 1 && j === ci));
    }
    const say = () => { if (row === 0) OS.speak(chars()[ki]); else OS.speak(kctl.children[ci].textContent || 'Стереть'); };
    function type(c) { if (value.length >= max) { TV.sound.bump(); return; } value += c; reveal = value.length - 1; clearTimeout(revealT); revealT = setTimeout(() => { reveal = -1; drawField(); }, 1100); drawField(); }
    function erase() { if (!value) { TV.sound.bump(); return false; } value = value.slice(0, -1); reveal = -1; drawField(); return true; }
    function nearest(fromEls, toEls, j) {
      const fe = fromEls[j]; if (!fe) return 0; const x = fe.offsetLeft + fe.offsetWidth / 2; let best = 0, bd = Infinity;
      toEls.forEach((e, k) => { const d = Math.abs(e.offsetLeft + e.offsetWidth / 2 - x); if (d < bd) { bd = d; best = k; } }); return best;
    }
    function act(id) {
      if (id === 'space') { type(' '); TV.sound.key(); }
      else if (id === 'del') { if (erase()) TV.sound.del(); }
      else if (id === 'caps') { caps = !caps; TV.sound.select(); drawKeys(); }
      else if (id === 'num') { num = !num; TV.sound.select(); drawKeys(); }
      else if (id === 'lang') { lay = lay === 'ru' ? 'en' : 'ru'; num = false; TV.sound.select(); drawKeys(); }
      else if (id === 'show') { show = !show; TV.sound.select(); drawField(); drawKeys(); }
      else if (id === 'done') { TV.sound.select(); finish(value); }
    }
    const layer = {
      key(a) {
        const cs = chars(), cl = ctls();
        if (a === 'left' || a === 'right') {
          const d = a === 'left' ? -1 : 1;
          if (row === 0) { const n = ki + d; if (n >= 0 && n < cs.length) { ki = n; TV.sound.tick(); } else TV.sound.bump(); }
          else { const n = ci + d; if (n >= 0 && n < cl.length) { ci = n; TV.sound.tick(); } else TV.sound.bump(); }
          paint(); say();
        } else if (a === 'down') { if (row === 0) { ci = nearest([...krow.children], [...kctl.children], ki); row = 1; TV.sound.tick(); paint(); say(); } else TV.sound.bump(); }
        else if (a === 'up') { if (row === 1) { ki = nearest([...kctl.children], [...krow.children], ci); row = 0; TV.sound.tick(); paint(); say(); } else TV.sound.bump(); }
        else if (a === 'ok') {
          if (row === 0) { type(cs[ki]); TV.sound.key(); const e = krow.children[ki]; e.animate([{ transform: 'scale(1.34)' }, { transform: 'scale(1.18)' }, { transform: 'scale(1.34)' }], { duration: 160 }); }
          else act(cl[ci].id);
        } else if (a === 'hold') { if (row === 0) { type(cs[ki].toUpperCase()); TV.sound.key(); } }
        else if (a === 'back' || a === 'home') { TV.sound.back(); finish(null); }
      },
    };
    function finish(v) { OS.dropLayer(layer); TV.capture(null); kbEl.classList.remove('on'); clearTimeout(revealT); res(v); }
    // Typing on the Mac keyboard goes straight into the field.
    TV.capture((e) => {
      if (e.key === 'Backspace') { if (erase()) TV.sound.del(); return true; }
      if (e.key === 'Enter' && typed) { TV.sound.select(); finish(value); return true; }
      if (e.key.length === 1 && !e.altKey) { typed = true; type(e.key); TV.sound.key(); return true; }
      return false;
    });
    drawField(); drawKeys(); kbEl.classList.add('on'); TV.sound.open(); OS.pushLayer(layer);
    OS.speak(`${o.title || ''}. ${o.sub || ''}`);
  });

  // ---------- performance ----------
  // Fast mode for weak TVs: no live backdrop blur, masks or blend modes (each one makes the GPU re-render the
  // pixels behind it on every frame). Glass draws a pre-blurred copy of the wallpaper instead, and apps open with
  // transform + opacity only, which the compositor animates without repainting anything.
  OS.weakDevice = /Android|AFT|BRAVIA|SmartTV|SMART-TV|Tizen|Web0S|CrKey/i.test(navigator.userAgent)
    || (navigator.deviceMemory || 8) <= 4 || (navigator.hardwareConcurrency || 8) <= 4;
  OS.isFast = () => { const p = OS.pref('perf'); return p === 'fast' || (p === 'auto' && OS.weakDevice); };
  const applyPerf = () => stage.classList.toggle('fast', OS.isFast());
  OS.onPref('perf', applyPerf); applyPerf();
  OS.glassPos = (els) => (els || stage.querySelectorAll('.lg')).forEach((e) => {
    let x = 0, y = 0; for (let n = e; n && n !== stage; n = n.offsetParent) { x += n.offsetLeft; y += n.offsetTop; }
    e.style.setProperty('--gx', -x + 'px'); e.style.setProperty('--gy', -y + 'px');
  });
  OS.zoom = (el, rect, open) => {
    el.getAnimations().forEach((a) => a.cancel());
    if (OS.pref('still')) return el.animate([{ opacity: open ? 0 : 1 }, { opacity: open ? 1 : 0 }], { duration: 240, fill: 'forwards' }).finished;
    const r = rect || { x: 660, y: 380, w: 600, h: 338 };
    const t = `translate(${r.x + r.w / 2 - 960}px, ${r.y + r.h / 2 - 540}px) scale(${r.w / 1920}, ${r.h / 1080})`;
    const kf = open ? [{ transform: t, opacity: 0 }, { opacity: 1, offset: 0.3 }, { transform: 'none', opacity: 1 }]
      : [{ transform: 'none', opacity: 1 }, { opacity: 1, offset: 0.55 }, { transform: t, opacity: 0 }];
    return el.animate(kf, { duration: open ? 480 : 400, easing: open ? 'cubic-bezier(.25,.1,.1,1)' : 'cubic-bezier(.4,0,.2,1)', fill: 'forwards' }).finished;
  };

  // Frame counter (Settings → System → Performance): frames per second, the longest frame and the hitches.
  const fpsEl = OS.el('osfps', '', '#bright');
  let fpsOn = false, frames = [], lastT = 0;
  const loop = (t) => { if (!fpsOn) return; if (lastT) frames.push(t - lastT); lastT = t; requestAnimationFrame(loop); };
  setInterval(() => {
    if (!fpsOn || !frames.length) return;
    const sum = frames.reduce((a, b) => a + b, 0), worst = Math.max(...frames), jank = frames.filter((d) => d > 25).length;
    fpsEl.innerHTML = `<b>${Math.round((frames.length * 1000) / sum)}</b> к/с · худший кадр ${Math.round(worst)} мс · рывков ${jank}`;
    fpsEl.classList.toggle('bad', jank > 2); frames = [];
  }, 1000);
  const applyFps = () => { fpsOn = !!OS.pref('fps'); fpsEl.classList.toggle('on', fpsOn); if (fpsOn) { lastT = 0; fpsEl.textContent = '…'; requestAnimationFrame(loop); } };
  OS.onPref('fps', applyFps); applyFps();

  // ---------- restart ----------
  const bootEl = OS.el('osboot', '<div class="spin"></div>', '#bright');
  OS.reboot = () => { bootEl.classList.add('on'); TV.sound.close(); setTimeout(() => location.reload(), 2200); };
})();
