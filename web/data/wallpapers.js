/* Wallpapers: photos listed in assets/wallpapers/list.js, gradients drawn right here, and the user's own photos.

   Photos are not shipped in the repository (they belong to their authors). tools/aerials.py fetches stills of
   Apple's Aerial screen-saver films from Apple's server on your own computer and writes them to
   assets/wallpapers/ with list.js (window.TVWallpapers = [{id, name, place, cat}]), 1920x1080 plus ready-made
   thumb/ (480x270) and preview/ (720x405) copies. Without them the launcher has its gradients only.

   Performance: thumbnails, blurred backgrounds and brightness are all made from a small image, so the full-size
   photo is only ever decoded by the browser for the one wallpaper on screen.

   API (window.Wallpapers):
     list()                 -> [{id, name, place, src}]   photos + gradients + own photos
     url(id)                -> Promise<url>               full image (cover-cropped to 16:9 by the page)
     blurred(id, px, dim, look) -> Promise<objectURL>     pre-blurred, small (cheap on a TV: no live blur);
                                                          look = 'glass' also bakes in saturation and brightness
     thumb(id) / preview(id) -> Promise<url>              480x270 / 720x405
     current() / set(id)    -> persisted in localStorage 'tvc.wallpaper'; fires window 'wallpaperchange'
     addCustom(file)        -> Promise<id>                a photo from disk, cover-cropped to 1920x1080
     removeCustom(id)
     luma(id)               -> Promise<number 0..1>       average brightness (for adaptive text/shadows)
*/
(function () {
  const W = 1920, H = 1080, P = 'assets/wallpapers/';
  const PHOTOS = (window.TVWallpapers || []).map((p) => ({ ...p, src: P + p.id + '.jpg' }));

  // Gradients: soft colour fields, drawn on a canvas (no files), with a fine grain against banding on TV panels.
  const GRADS = [
    { id: 'g-dusk', name: 'Сумерки', bg: '#2b1055', blobs: [['#7597de', 0.1, 0.1, 0.8], ['#d53a9d', 0.85, 0.3, 0.7], ['#ff9a5a', 0.6, 1, 0.6], ['#4a148c', 0.3, 0.8, 0.6]] },
    { id: 'g-ocean', name: 'Океан', bg: '#0b3d5c', blobs: [['#1fa2ff', 0.15, 0.2, 0.8], ['#12d8fa', 0.9, 0.25, 0.6], ['#0a1f44', 0.6, 1, 0.9], ['#3a7bd5', 0.35, 0.75, 0.6]] },
    { id: 'g-dawn', name: 'Рассвет', bg: '#fde2d4', blobs: [['#ff8a80', 0.1, 0.15, 0.75], ['#ffd180', 0.9, 0.1, 0.65], ['#b39ddb', 0.75, 0.95, 0.8], ['#f8bbd0', 0.15, 0.95, 0.6]] },
    { id: 'g-aurora', name: 'Сияние', bg: '#071a2b', blobs: [['#00e5a0', 0.2, 0.35, 0.6], ['#00bcd4', 0.65, 0.25, 0.55], ['#6a1b9a', 0.85, 0.8, 0.7], ['#0d47a1', 0.3, 0.95, 0.7]] },
    { id: 'g-dunes', name: 'Дюны', bg: '#f3e1c7', blobs: [['#f6b78c', 0.2, 0.8, 0.7], ['#e8a87c', 0.9, 0.6, 0.6], ['#fff1d6', 0.5, 0.1, 0.8], ['#d4a373', 0.1, 0.2, 0.5]] },
    { id: 'g-graphite', name: 'Графит', bg: '#1c1c1e', blobs: [['#3a3a3c', 0.2, 0.2, 0.8], ['#4a4f5a', 0.85, 0.3, 0.6], ['#1f2a3a', 0.7, 0.9, 0.8], ['#2c2c2e', 0.3, 0.85, 0.6]] },
  ].map((g) => ({ ...g, place: 'Градиент', grad: true }));

  const cache = new Map();
  const KEY = 'tvc.wallpaper', CUSTOM = 'tvc.custom';
  function customs() { try { return JSON.parse(localStorage.getItem(CUSTOM) || '[]'); } catch { return []; } }
  function canvas(w = W, h = H) { const cv = document.createElement('canvas'); cv.width = w; cv.height = h; return cv; }
  function blobURL(cv, type = 'image/jpeg', q = 0.9) { return new Promise((res) => cv.toBlob((b) => res(URL.createObjectURL(b)), type, q)); }
  function loadImg(src) { return new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = src; }); }
  const photo = (id) => PHOTOS.find((x) => x.id === id), grad = (id) => GRADS.find((x) => x.id === id);
  function cover(img, w, h) {
    const cv = canvas(w, h), c = cv.getContext('2d'), s = Math.max(w / img.width, h / img.height);
    c.drawImage(img, (w - img.width * s) / 2, (h - img.height * s) / 2, img.width * s, img.height * s); return cv;
  }
  const rgba = (hex, a) => { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`; };
  function paint(g, w, h, grain) {
    const cv = canvas(w, h), c = cv.getContext('2d'), m = Math.max(w, h);
    c.fillStyle = g.bg; c.fillRect(0, 0, w, h);
    for (const [col, x, y, r] of g.blobs) {
      const gr = c.createRadialGradient(x * w, y * h, 0, x * w, y * h, r * m);
      gr.addColorStop(0, rgba(col, 0.95)); gr.addColorStop(0.55, rgba(col, 0.4)); gr.addColorStop(1, rgba(col, 0));
      c.fillStyle = gr; c.fillRect(0, 0, w, h);
    }
    const sheen = c.createLinearGradient(0, 0, w, h); sheen.addColorStop(0, 'rgba(255,255,255,.10)'); sheen.addColorStop(0.45, 'rgba(255,255,255,0)'); c.fillStyle = sheen; c.fillRect(0, 0, w, h);
    if (grain) { const d = c.getImageData(0, 0, w, h), p = d.data; for (let i = 0; i < p.length; i += 4) { const n = (Math.random() - 0.5) * 6; p[i] += n; p[i + 1] += n; p[i + 2] += n; } c.putImageData(d, 0, 0); }
    return cv;
  }
  function custom(id) { return customs().find((x) => x.id === id); }
  function srcOf(id) { const p = photo(id); if (p) return p.src; const c = custom(id); return c ? c.data : null; }
  // Small source image: the ready-made thumbnail for photos, a small render for gradients, the photo itself for own photos.
  function small(id) {
    const k = 'small:' + id; if (cache.has(k)) return cache.get(k);
    const p = photo(id), g = grad(id);
    const pr = g ? Promise.resolve(paint(g, 480, 270)) : p ? loadImg(P + 'thumb/' + id + '.jpg').catch(() => loadImg(p.src)) : loadImg(srcOf(id) || '');
    cache.set(k, pr); return pr;
  }
  const rendered = (id, w, h, grain) => { const k = `r:${id}:${w}`; if (!cache.has(k)) cache.set(k, blobURL(paint(grad(id), w, h, grain), 'image/jpeg', 0.92)); return cache.get(k); };

  const API = window.Wallpapers = {
    W, H,
    list() {
      return [...PHOTOS, ...GRADS,
        ...customs().map((x, i) => ({ id: x.id, name: x.name || 'Моё фото ' + (i + 1), place: 'Своё фото', src: x.data, custom: true }))];
    },
    async url(id) { return grad(id) ? rendered(id, 1280, 720, true) : srcOf(id) || rendered(GRADS[0].id, 1280, 720, true); },
    async blurred(id, px = 40, dim = 0, look = '') {
      const k = `blur:${id}:${px}:${dim}:${look}`; if (cache.has(k)) return cache.get(k);
      const p = small(id).then((img) => {
        const s = 0.25, cv = canvas(W * s, H * s), c = cv.getContext('2d'), src = cover(img, W * s, H * s);
        c.filter = `blur(${px * s}px)` + (look === 'glass' ? ' saturate(1.6) brightness(1.06)' : '');
        c.drawImage(src, -px * s, -px * s, W * s + px * s * 2, H * s + px * s * 2); c.filter = 'none';
        if (dim) { c.fillStyle = `rgba(0,0,0,${dim})`; c.fillRect(0, 0, cv.width, cv.height); }
        return blobURL(cv, 'image/jpeg', 0.9);
      });
      cache.set(k, p); return p;
    },
    async preview(id) {
      if (grad(id)) return rendered(id, 720, 405);
      const p = photo(id); return p && p.thumb !== false ? P + 'preview/' + id + '.jpg' : API.thumb(id);
    },
    async thumb(id) {
      if (grad(id)) return rendered(id, 480, 270);
      const p = photo(id); if (p && p.thumb !== false) return P + 'thumb/' + id + '.jpg';
      const k = 'thumb:' + id; if (cache.has(k)) return cache.get(k);
      const pr = small(id).then((img) => blobURL(cover(img, 480, 270), 'image/jpeg', 0.86));
      cache.set(k, pr); return pr;
    },
    async luma(id) {
      const k = 'luma:' + id; if (cache.has(k)) return cache.get(k);
      const p = small(id).then((img) => {
        const d = cover(img, 32, 18).getContext('2d').getImageData(0, 0, 32, 18).data; let s = 0;
        for (let i = 0; i < d.length; i += 4) s += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
        return s / (d.length / 4) / 255;
      });
      cache.set(k, p); return p;
    },
    async accent(id) {
      const img = await small(id), d = cover(img, 48, 27).getContext('2d').getImageData(0, 0, 48, 27).data;
      let best = [120, 120, 160], score = -1;
      for (let i = 0; i < d.length; i += 4) { const R = d[i], G = d[i + 1], B = d[i + 2], mx = Math.max(R, G, B), mn = Math.min(R, G, B), s = (mx - mn) * (mx / 255); if (s > score) { score = s; best = [R, G, B]; } }
      return best;
    },
    current() {
      const id = localStorage.getItem(KEY);
      return id && (photo(id) || grad(id) || custom(id)) ? id : (PHOTOS[0] || GRADS[0]).id;
    },
    set(id) { localStorage.setItem(KEY, id); window.dispatchEvent(new CustomEvent('wallpaperchange', { detail: id })); },
    canAddCustom: () => !(window.Platform && window.Platform.tv), // a TV has no file picker
    async addCustom(file) {
      const img = await loadImg(URL.createObjectURL(file));
      const data = cover(img, W, H).toDataURL('image/jpeg', 0.82), id = 'custom-' + Date.now();
      const list = customs(); list.push({ id, name: file.name.replace(/\.[^.]+$/, ''), data });
      try { localStorage.setItem(CUSTOM, JSON.stringify(list)); } catch { list.pop(); alert('Фото слишком большое для хранения в браузере'); return null; }
      return id;
    },
    removeCustom(id) { localStorage.setItem(CUSTOM, JSON.stringify(customs().filter((x) => x.id !== id))); [...cache.keys()].forEach((k) => k.includes(id) && cache.delete(k)); },
  };
})();
