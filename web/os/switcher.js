/* App switcher (tvOS: press the TV button twice). Recent apps as big cards with the app icon and name above;
   left/right to browse, OK to return to the app, up to close it (flick the card away), Back/Home to leave.

   OS.Switcher.open()  — Home calls it; the app opens through Home.resume(id). */
(function () {
  const H = () => window.Home;
  const el = OS.el('osswitch', '<div class="swbg"></div><div class="swtrack"></div><div class="swempty">Нет открытых приложений<span>Здесь появятся приложения, которые вы открывали</span></div>'
    + '<div class="swhint">← → выбрать · OK открыть · ↑ закрыть приложение · Назад — выйти</div>', '#toast');
  const track = el.querySelector('.swtrack'), empty = el.querySelector('.swempty'), hint = el.querySelector('.swhint');
  const W = 1040, GAP = 1120;
  let ids = [], cards = [], i = 0, layer = null;

  function card(id) {
    const a = (window.TVApps || []).find((x) => x.id === id), c = document.createElement('div'); c.className = 'swc';
    c.innerHTML = `<div class="hd"><i style="background-image:url('${H().art(a)}')"></i>${a.name}</div><div class="scr"></div>`;
    const scr = c.querySelector('.scr');
    if (id === 'settings') { const s = document.createElement('div'); s.className = 'snap'; s.appendChild(OS.Settings.snapshot()); scr.appendChild(s); }
    else {
      scr.innerHTML = `<div class="lban" style="background-image:url('${H().art(a)}')"></div>`;
      TV.color(a.banner).then(([r, g, b]) => { scr.style.background = `radial-gradient(900px 600px at 50% 40%, rgb(${r * 0.36 | 0},${g * 0.36 | 0},${b * 0.36 | 0}), #050506 75%)`; });
    }
    return c;
  }
  function place(instant) {
    cards.forEach((c, j) => { c.style.left = `${960 - W / 2 + (j - i) * GAP}px`; c.classList.toggle('on', j === i); });
    if (instant) cards.forEach((c) => { c.style.transition = 'none'; c.offsetWidth; c.style.transition = ''; });
    empty.style.display = ids.length ? 'none' : ''; hint.style.display = ids.length ? '' : 'none';
    if (ids[i]) OS.speak((window.TVApps || []).find((x) => x.id === ids[i]).name);
  }
  function close(then) {
    OS.dropLayer(layer); layer = null; el.classList.remove('on');
    cards.forEach((c) => c.animate([{ opacity: 1 }, { opacity: 0, transform: 'scale(.8) translateY(60px)' }], { duration: 260, fill: 'forwards' }));
    setTimeout(() => { if (!layer) { track.innerHTML = ''; cards = []; } }, 300);
    if (then) then();
  }
  function openApp() {
    const id = ids[i], c = cards[i]; if (!c) return;
    TV.sound.launch();
    // The card grows to fill the screen, then the real app takes over.
    const k = 1920 / W, dx = 960 - (960 - W / 2 + W / 2), dy = 540 - (300 + 585 / 2);
    c.style.transition = 'transform .42s cubic-bezier(.3,.1,.1,1)'; c.style.transform = `translate(${dx}px, ${dy}px) scale(${k})`;
    c.querySelector('.hd').style.opacity = 0;
    cards.forEach((x) => x !== c && (x.style.opacity = 0));
    el.querySelector('.swbg').style.transition = 'opacity .3s'; el.querySelector('.swbg').style.opacity = 0;
    OS.dropLayer(layer); layer = null;
    setTimeout(() => { H().resume(id); el.classList.remove('on'); track.innerHTML = ''; cards = []; el.querySelector('.swbg').style.opacity = ''; }, 430);
  }
  function kill() {
    const id = ids[i], c = cards[i]; if (!c) return;
    TV.sound.fling(); c.classList.add('gone'); OS.forget(id); if (id === 'settings') OS.Settings.reset();
    setTimeout(() => {
      c.remove(); ids.splice(i, 1); cards.splice(i, 1); i = Math.max(0, Math.min(i, ids.length - 1)); place();
    }, 380);
  }
  OS.Switcher = {
    open() {
      if (layer) return;
      ids = OS.recents(); cards = ids.map(card); track.innerHTML = ''; cards.forEach((c) => track.appendChild(c));
      i = Math.max(0, ids.length - 1); place(true);
      el.classList.add('on'); TV.sound.open();
      cards.forEach((c, j) => c.animate([{ transform: `translateY(${240 + Math.abs(j - i) * 40}px) scale(${j === i ? 0.9 : 0.8})`, opacity: 0 }, { transform: j === i ? 'scale(1)' : 'scale(.84)', opacity: j === i ? 1 : 0.62 }],
        { duration: 520, delay: Math.abs(j - i) * 40, easing: 'cubic-bezier(.2,1.1,.35,1)', fill: 'backwards' }));
      layer = {
        key(a) {
          if (a === 'left' || a === 'right') { const n = i + (a === 'left' ? -1 : 1); if (n >= 0 && n < ids.length) { i = n; TV.sound.tick(); place(); } else TV.sound.bump(); }
          else if (a === 'up') { if (ids.length) kill(); else TV.sound.bump(); }
          else if (a === 'down') TV.sound.bump();
          else if (a === 'ok') { if (ids.length) openApp(); else TV.sound.bump(); }
          else if (a === 'back' || a === 'home' || a === 'switcher') { TV.sound.back(); close(() => H().switcherClosed()); }
        },
      };
      OS.pushLayer(layer);
    },
    get isOpen() { return !!layer; },
  };
})();
