/* Screenshot mode for README pictures (tools/screenshots.sh): index.html?shot=<name>[&dark=1][&wp=<wallpaper>]
   Starts from clean settings with demo apps and a gradient wallpaper (never local photos), then presses keys. */
(function () {
  const q = new URLSearchParams(location.search), shot = q.get('shot');
  Object.keys(localStorage).filter((k) => k.startsWith('tvc.')).forEach((k) => localStorage.removeItem(k));
  localStorage.setItem('tvc.wallpaper', q.get('wp') || 'g-dusk');
  localStorage.setItem('tvc.tvos.look', JSON.stringify(q.get('dark') ? 'dark' : 'light'));
  localStorage.setItem('tvc.sound', 'false'); localStorage.setItem('tvc.sound.fx', 'false');
  window.TV_NO_HINT = true;
  Object.defineProperty(window, 'TVWallpapers', { get: () => [], set: () => {} });
  const K = { U: 'ArrowUp', D: 'ArrowDown', L: 'ArrowLeft', R: 'ArrowRight', E: 'Enter', B: 'Escape' };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  async function press(seq) {
    for (const t of seq.split(' ').filter(Boolean)) {
      if (t[0] === 'W' && t.length > 1 && /^\d+$/.test(t.slice(1))) { await wait(+t.slice(1)); continue; }
      if (t === 'HOLD') { dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); await wait(800); dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter' })); await wait(400); continue; }
      const k = K[t] || t; dispatchEvent(new KeyboardEvent('keydown', { key: k }));
      if (k === 'Enter') await wait(60);
      dispatchEvent(new KeyboardEvent('keyup', { key: k })); await wait(380);
    }
  }
  const SHOTS = {
    home: '', grid: 'D', cc: 'c', menu: 'R HOLD', alert: 'R HOLD D D E', picker: 'w',
    settings: 'g W700', general: 'g W700 E', saver: 'g W700 D D D E', apps: 'g W700 D D D D D E W600 D D',
    perf: 'g W700 D D D D D D D D D E W600 D E', about: 'g W700 D D D D D D D D D E W600 E', access: 'g W700 D D D D D D D E',
    keyboard: 'g W700 D E W600 E W600 E', switcher: 'E W1600 B W900 g W1000 h W900 Tab',
  };
  addEventListener('load', async () => { await wait(1500); await press(SHOTS[shot] || ''); await wait(1500); document.title = 'ready'; });
})();
