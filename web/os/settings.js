/* Settings app of the launcher, laid out like tvOS Settings: the page title on top, a big picture of the focused
   section on the left, the list on the right. Pages push in from the right and Back pops them.

   OS.Settings:
     show({rect, path, instant})   open (zooming out of `rect`, the icon); path = row ids to open, e.g. ['network', 'wifi']
     hide({rect, instant})         close (back into `rect`) -> Promise
     key(action)                   remote input while open
     refresh()                     re-read the values of the visible page
     snapshot()                    a static copy of the screen (for the app switcher)
     reset()                       forget the navigation (next open starts at the root)

   Page: {title, art, rows: () => [row], focus?: (rows) => index, onOpen?: (entry) => void}
     art: spec | (focusedRow, entry) => spec; pages without art inherit the parent page's picture.
       spec: {g: glyph, c: [colour1, colour2]} | {img: url|() => Promise<url>, key} | {tv: true} | {ava: [letter, c1, c2]}
             plus cap / sub (caption under the picture).
   Row: {t, v, sub, two, go: pageId | () => page, act: (entry) => void|Promise, range: {min, max, step, get, set, fmt},
         ck (check at the end), ckl (check at the start), ico (image), gi (glyph), gl2 (html at the end), spin,
         red, blue, dim, ctr, id}  or  {type: 'head'|'foot'|'gap', text}.  Functions are allowed for t / v / sub / ck / dim. */
(function () {
  const H = () => window.Home;
  const val = (x, ...a) => (typeof x === 'function' ? x(...a) : x);
  const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;');

  const root = OS.el('osapp', '<div class="sbg"><div class="layer"></div><div class="layer"></div><div class="sfrost"></div></div>'
    + '<div class="sbody"><div class="stitle"><span></span><span class="out"></span></div><div class="sart"><div class="sa"></div><div class="sa"></div></div><div class="spages"></div></div>', '#shade');
  const body = root.querySelector('.sbody'), titleEl = root.querySelector('.stitle'), artEl = root.querySelector('.sart'), pagesEl = root.querySelector('.spages');

  // ---------- small builders ----------
  const head = (text) => ({ type: 'head', text });
  const foot = (text) => ({ type: 'foot', text });
  const gap = () => ({ type: 'gap' });
  const onoff = (b) => (b ? 'Вкл.' : 'Выкл.');
  const tog = (t, get, set, x = {}) => ({ t, v: () => onoff(get()), act: () => set(!get()), ...x });
  const ptog = (t, k, x) => tog(t, () => OS.pref(k), (v) => OS.setPref(k, v), x);
  function choice(t, get, set, opts, x = {}) {
    const O = typeof opts === 'function' ? opts : () => opts;
    const label = () => { const o = O().find(([v]) => v === get()); return o ? o[1] : ''; };
    return {
      t, v: label, ...x,
      go: () => ({
        title: t, art: x.art,
        focus: (rows) => rows.findIndex((r) => val(r.ck)),
        rows: () => [...(x.head ? [head(x.head)] : []),
          ...O().map(([v, l, sub, ico]) => ({ t: l, v: sub, ico, ck: () => get() === v, act: () => { if (get() !== v) set(v); } })),
          ...(x.foot ? [foot(x.foot)] : [])],
      }),
    };
  }
  const pchoice = (t, k, opts, x) => choice(t, () => OS.pref(k), (v) => OS.setPref(k, v), opts, x);
  const C = {
    gray: ['#b4bac3', '#6b717a'], graphite: ['#9a9aa0', '#48484c'], blue: ['#5aaeff', '#0a6cf0'], pink: ['#ff7896', '#e3244f'],
    teal: ['#62d6ea', '#138db0'], indigo: ['#9897f7', '#4c4ad6'], green: ['#6ae394', '#1fa457'], orange: ['#ffb84d', '#f27b0c'],
    purple: ['#c99bff', '#8a45e6'], access: ['#45a2ff', '#005fdd'],
  };
  const G = (g, c, cap, sub) => ({ g, c, cap, sub });
  const AVA = { blue: C.blue, green: C.green, orange: C.orange, pink: C.pink, purple: C.purple, graphite: C.graphite };
  const userArt = () => { const n = OS.pref('userName'), c = AVA[OS.pref('userColor')] || C.blue; return { ava: [(n[0] || '?').toUpperCase(), c[0], c[1]], cap: n, sub: 'Основной пользователь' }; };
  const dev = () => Platform.device(), net = () => Platform.network();
  const tvArt = () => { const d = dev(); return { tv: true, key: 'tv:' + Wallpapers.current(), cap: d.name || d.model || 'Этот ТВ', sub: [d.android ? 'Android ' + d.android : '', d.display].filter(Boolean).join(' · ') }; };
  const apps = () => window.TVApps || [];
  const appById = (id) => apps().find((a) => a.id === id);
  const isHidden = (id) => OS.pref('hidden').includes(id);
  const tzName = () => {
    try {
      const z = Intl.DateTimeFormat().resolvedOptions().timeZone, n = new Intl.DateTimeFormat('ru-RU', { timeZone: z, timeZoneName: 'long' }).formatToParts(new Date()).find((p) => p.type === 'timeZoneName');
      return n ? n.value.replace(/, стандартное время$/, '') : z;
    } catch { return 'Автоматически'; }
  };
  const langName = () => { try { const l = navigator.language || 'ru'; const n = new Intl.DisplayNames(['ru'], { type: 'language' }).of(l); return n[0].toUpperCase() + n.slice(1); } catch { return navigator.language; } };
  const gb = (mb) => (mb >= 1024 ? `${(mb / 1024).toFixed(mb % 1024 ? 1 : 0).replace('.', ',')} ГБ` : `${Math.round(mb)} МБ`);
  const SIG = ['слабый', 'средний', 'хороший', 'отличный'];
  // A row that opens an Android settings screen (on the TV) or says what would open (in the browser).
  const sys = (t, what, arg, x = {}) => ({ t, blue: true, ...x, act: () => {
    if (!Platform.tv) { H().toast(t.replace(/…$/, ''), 'На ТВ здесь откроется экран настроек Android', OS.svg('gear')); return; }
    if (!Platform.open(what, arg)) { TV.sound.error(); H().toast('Не открывается', 'На этом ТВ такого экрана настроек нет', OS.svg('gear')); }
  } });

  // Aerial themes: Apple's own category names when tools/aerials.py has made the list.
  const CATN = window.TVAerialCats || {};
  const CATS = [['Landscapes', CATN.Landscapes || 'Природа'], ['Cities', CATN.Cities || 'Города'], ['Underwater', CATN.Underwater || 'Под водой'], ['Space', CATN.Space || 'Планета Земля']]
    .filter(([k]) => !(window.TVAerials || []).length || (window.TVAerials || []).some((a) => a.cat === k));
  const aerCount = (cats) => (window.TVAerials || []).filter((a) => cats.includes(a.cat)).length;
  const catPic = (k) => { const w = (window.TVWallpapers || []).find((x) => x.cat === k); return w ? w.id : ({ Landscapes: 'g-aurora', Cities: 'g-dusk', Underwater: 'g-ocean', Space: 'g-graphite' })[k]; };

  // ---------- page state (checks in progress) ----------
  let netTest = false;
  const later = (ms, fn) => setTimeout(() => { fn(); refresh(); }, ms);

  // ---------- pages ----------
  const P = {};
  P.root = () => ({
    title: 'Настройки', art: (r) => r && val(r.art),
    rows: () => [
      { t: 'Основные', go: 'general', art: G('gear', C.gray) },
      { t: 'Пользователи и учётные записи', go: 'users', art: G('user', C.blue) },
      { t: 'Видео и аудио', go: 'av', art: G('av', C.pink) },
      { t: 'Заставка', go: 'saver', art: G('saver', C.teal) },
      { t: 'Пульты и устройства', go: 'remotes', art: G('remote', C.graphite) },
      { t: 'Приложения', go: 'apps', art: G('apps', C.indigo) },
      { t: 'Сеть', go: 'network', art: G('wifi', C.blue) },
      { t: 'Универсальный доступ', go: 'access', art: G('access', C.access) },
      { t: 'Конфиденциальность и безопасность', go: 'privacy', art: G('hand', C.blue) },
      { t: 'Система', go: 'system', art: tvArt },
      { t: 'Разработчик', go: 'dev', art: G('dev', C.graphite) },
      gap(),
      { t: 'Режим сна', act: () => H().sleep(), art: G('moon', C.indigo, 'Режим сна', 'Экран гаснет, любая кнопка его будит') },
    ],
  });

  // General
  P.general = () => ({
    title: 'Основные', art: G('gear', C.gray),
    rows: () => [
      head('Главный экран'),
      pchoice('Оформление', 'look', [['light', 'Светлое'], ['dark', 'Тёмное'], ['auto', 'Автоматически', 'с 19:00 до 7:00']],
        { art: G('look', C.graphite), foot: 'Автоматически: светлое днём и тёмное вечером и ночью.' }),
      pchoice('Часы', 'clock', [['center', 'По центру'], ['left', 'Слева']], { art: G('clock', C.graphite) }),
      { t: 'Фон', v: () => (Wallpapers.list().find((w) => w.id === Wallpapers.current()) || {}).name, go: 'wallpapers' },
      { t: 'Вернуть исходный порядок приложений', act: () => OS.alert({ title: 'Вернуть исходный порядок?', text: 'Приложения встанут на главном экране в исходном порядке.', buttons: [{ t: 'Вернуть' }, { t: 'Отменить', cancel: true }] })
        .then((i) => { if (i === 0) { H().resetOrder(); H().toast('Порядок приложений', 'Исходный порядок восстановлен', OS.svg('apps')); } }) },
      head('Сон'),
      pchoice('Гасить экран', 'sleepAfter', [[0, 'Никогда'], [15, 'Через 15 минут'], [30, 'Через 30 минут'], [60, 'Через 1 час'], [300, 'Через 5 часов'], [600, 'Через 10 часов']],
        { art: G('moon', C.indigo), foot: 'Если долго не нажимать кнопки, главный экран погаснет. Выключение самого ТВ настраивается в Android.' }),
      head('Язык и регион'),
      sys('Язык', 'language', '', { v: langName, blue: false }),
      { t: 'Часовой пояс', v: tzName },
      ptog('24-часовой формат', 'hour24'),
      sys('Дата и время…', 'date'),
      { t: 'Клавиатура', v: 'Русская, English', go: 'keyboard' },
    ],
  });
  P.wallpapers = () => ({
    title: 'Фон',
    art: (r) => (r && r.wp ? { img: () => Wallpapers.preview(r.wp), key: 'wp:' + r.wp, cap: r.t, sub: r.place } : G('photo', C.teal, 'Своё фото', 'Любой снимок с этого компьютера')),
    focus: (rows) => rows.findIndex((r) => val(r.ck)),
    rows: () => [
      ...Wallpapers.list().map((w) => ({ t: w.name, wp: w.id, place: w.place, ck: () => Wallpapers.current() === w.id, act: () => Wallpapers.set(w.id) })),
      ...(Wallpapers.canAddCustom() ? [{ t: 'Добавить своё фото…', blue: true, act: () => TV.pickFile().then((f) => f && Wallpapers.addCustom(f)).then((id) => { if (id) Wallpapers.set(id); }) }] : []),
      foot('Фон меняется сразу. Снимки из заставки Apple TV добавляет tools/aerials.py при установке.'),
    ],
  });
  P.keyboard = () => ({
    title: 'Клавиатура', art: G('keyboard', C.graphite),
    rows: () => [
      head('Раскладки'), { t: 'Русская', ck: true }, { t: 'English', ck: true },
      head('Проверка'),
      { t: 'Попробовать клавиатуру…', act: () => OS.keyboard({ title: 'Проверка клавиатуры', sub: 'Стрелки выбирают букву, OK печатает', placeholder: 'Текст' })
        .then((v) => v && H().toast('Вы напечатали', v, OS.svg('keyboard'))) },
      foot('Клавиатура на экране — как в tvOS: один ряд букв и ряд кнопок под ним. «Глобус» переключает язык.'),
    ],
  });


  // Users and accounts
  P.users = () => ({
    title: 'Пользователи и учётные записи', art: G('user', C.blue),
    rows: () => [
      head('Профиль на главном экране'),
      { t: () => OS.pref('userName'), sub: 'Имя и цвет для лаунчера', two: true, ava: () => { const c = AVA[OS.pref('userColor')] || C.blue; return [OS.pref('userName')[0] || '?', c[0], c[1]]; }, go: 'user' },
      head('Учётные записи'),
      sys('Учётные записи Android…', 'accounts'),
      foot('Аккаунты (Google и другие) хранит сам Android: лаунчер их не видит и не трогает.'),
    ],
  });
  P.user = () => ({
    title: () => OS.pref('userName'), art: userArt,
    rows: () => [
      { t: 'Имя', v: () => OS.pref('userName'), act: () => OS.keyboard({ title: 'Имя пользователя', value: OS.pref('userName'), max: 24, placeholder: 'Имя' })
        .then((v) => { if (v && v.trim()) OS.setPref('userName', v.trim()); }) },
      pchoice('Цвет', 'userColor', [['blue', 'Синий'], ['green', 'Зелёный'], ['orange', 'Оранжевый'], ['pink', 'Розовый'], ['purple', 'Фиолетовый'], ['graphite', 'Графит']]),
    ],
  });


  // Video and audio
  P.av = () => ({
    title: 'Видео и аудио', art: G('av', C.pink),
    rows: () => [
      head('Видео'),
      { t: 'Экран', v: () => dev().display || '—' },
      sys('Изображение и яркость ТВ…', 'display'),
      { t: 'Яркость интерфейса', range: { min: 10, max: 100, step: 5, get: () => OS.pref('bright'), set: (v) => OS.setPref('bright', v), fmt: (v) => v + '%' } },
      foot('«Яркость интерфейса» приглушает только главный экран и меню лаунчера. Подсветка самого ТВ — в «Изображение и яркость ТВ».'),
      head('Аудио'),
      sys('Звук ТВ…', 'sound'),
      head('Звуки интерфейса'),
      tog('Щелчки при навигации', () => TV.sound.nav, (v) => { TV.sound.nav = v; }),
      tog('Звуковые эффекты', () => TV.sound.fx, (v) => { TV.sound.fx = v; if (v) setTimeout(() => TV.sound.open(), 120); }),
      { t: 'Набор звуков', v: () => { const n = Object.values(TV.sound.sources()).filter((x) => x === 'file').length; return n ? `Файлы: ${n} из ${TV.sound.names().length}` : TV.sound.native() ? 'Системные Android' : 'Синтез'; }, go: 'sounds' },
      { t: 'Громкость звуков', range: { min: 0, max: 100, step: 10, get: () => Math.round(TV.sound.volume * 100), set: (v) => { TV.sound.volume = v / 100; }, fmt: (v) => (v ? v + '%' : 'Выкл.') } },
      foot('Щелчки звучат при перемещении фокуса, нажатиях и вводе текста. Эффекты — когда открываются панели, меню и приложения.'),
    ],
  });

  const SND = [['tick', 'Перемещение фокуса'], ['select', 'Нажатие OK'], ['back', 'Назад'], ['bump', 'Упор в край'], ['key', 'Клавиша'], ['del', 'Стереть'],
    ['pop', 'Долгое нажатие'], ['open', 'Панель открылась'], ['close', 'Панель закрылась'], ['launch', 'Запуск приложения'], ['zoom', 'Звук запуска'],
    ['fling', 'Смахнуть приложение'], ['chime', 'Готово'], ['error', 'Ошибка']];
  P.sounds = () => ({
    title: 'Набор звуков', art: G('sound', C.pink),
    rows: () => [
      ...(TV.sound.native() ? [foot('На ТВ звучат системные звуки Android, как у обычного лаунчера: перемещение и нажатие. Панели и меню открываются без звука.')] : []),
      ...(TV.sound.native() ? [] : [choice('Стиль синтеза', () => TV.sound.style, (v) => { TV.sound.style = v; setTimeout(() => { TV.sound.tick(); setTimeout(() => TV.sound.select(), 260); }, 150); },
        [['soft', 'Мягкий', 'приглушённый, как в tvOS'], ['crisp', 'Чёткий', 'звонче и ярче']],
        { foot: 'Стиль меняет только синтезированные звуки. Где лежит настоящий файл, играет он.' })]),
      head('Звуки (OK — прослушать)'),
      ...SND.map(([k, n]) => ({ t: n, v: () => ({ file: 'Файл', system: 'Системный', none: 'Без звука', synth: 'Синтез' })[TV.sound.sources()[k]], quiet: true,
        act: () => (TV.sound[k] && k !== 'play' ? TV.sound[k]() : TV.sound.play(k)) })),
      foot('Синтез только похож на звуки Apple TV. Чтобы звучало один в один, положите настоящие файлы: tools/import-sounds.sh <папка>, имена файлов — в assets/sounds/README.md.'),
    ],
  });

  // Screen saver
  const saverArt = () => (OS.pref('saverType') === 'aerial' && (window.TVAerials || []).length
    ? { img: () => Wallpapers.preview(catPic(OS.pref('saverCats')[0] || 'Landscapes')), key: 'saver:a', cap: 'Аэросъёмка Apple', sub: `${aerCount(OS.pref('saverCats'))} видео с подписями мест`, badge: 'Apple TV Aerial' }
    : { img: () => Wallpapers.preview(Wallpapers.current()), key: 'saver:p:' + Wallpapers.current(), cap: 'Фоны главного экрана', sub: `${Wallpapers.list().length} фонов с медленным движением камеры` });
  P.saver = () => ({
    title: 'Заставка', art: saverArt,
    rows: () => [
      pchoice('Тип', 'saverType', [['aerial', 'Аэросъёмка Apple'], ['photos', 'Фоны главного экрана']]),
      pchoice('Начинать через', 'saverAfter', [[0, 'Никогда'], [2, '2 минуты'], [5, '5 минут'], [10, '10 минут'], [15, '15 минут'], [30, '30 минут']]),
      { t: 'Просмотр', act: () => H().previewSaver() },
      head('Аэросъёмка'),
      { t: 'Темы', v: () => { const on = CATS.filter(([k]) => OS.pref('saverCats').includes(k)); return on.length === CATS.length ? 'Все' : on.map(([, n]) => n).join(', '); }, go: 'themes', dim: () => OS.pref('saverType') !== 'aerial' },
      ptog('Показывать время', 'saverClock'),
      ptog('Показывать названия мест', 'saverCaps'),
      foot((window.TVAerials || []).length ? 'Видео Apple TV Aerial идут прямо с сервера Apple. Без интернета заставка показывает фоны главного экрана.'
        : 'Список видео Apple ещё не скачан: его делает tools/aerials.py. Пока заставка показывает фоны главного экрана.'),
    ],
  });
  P.themes = () => ({
    title: 'Темы',
    art: (r) => (r && r.cat ? { img: () => Wallpapers.preview(catPic(r.cat)), key: 'cat:' + r.cat, cap: r.t, sub: `${aerCount([r.cat])} видео` } : null),
    rows: () => [
      ...CATS.map(([k, n]) => ({ t: n, cat: k, v: `${aerCount([k])} видео`, ck: () => OS.pref('saverCats').includes(k),
        act: () => {
          const on = OS.pref('saverCats');
          if (on.includes(k) && on.filter((x) => CATS.some(([c]) => c === x)).length === 1) { TV.sound.error(); H().toast('Нужна хотя бы одна тема', 'Иначе заставке нечего показывать', OS.svg('saver')); return; }
          OS.setPref('saverCats', on.includes(k) ? on.filter((x) => x !== k) : [...on, k]);
        } })),
      foot('Заставка случайно перебирает видео выбранных тем.'),
    ],
  });

  // Remotes and devices
  P.remotes = () => ({
    title: 'Пульты и устройства', art: G('remote', C.graphite),
    rows: () => [
      head('Пульт'),
      { t: 'Кнопка «Домой»', v: 'Главный экран' },
      { t: 'Двойное нажатие «Домой»', v: 'Переключатель приложений' },
      { t: 'Долгое нажатие OK', v: 'Меню значка' },
      { t: 'Кнопка «Меню»', v: 'Пункт управления' },
      head('Устройства'),
      sys('Bluetooth, пульты и геймпады…', 'bluetooth'),
      foot('Подключение пультов, наушников и геймпадов делает Android.'),
    ],
  });

  // Apps
  const appArt = (a) => ({ img: H().art(a), key: 'app:' + a.id, cap: a.name, sub: a.tagline || a.pkg || '' });
  P.apps = () => ({
    title: 'Приложения', art: (r) => (r && r.app ? appArt(r.app) : G('apps', C.indigo)),
    rows: () => [
      head('На этом ТВ'),
      ...H().appOrder().map((id) => { const a = appById(id); return { id: 'app:' + id, t: a.name, ico: H().art(a), v: () => (isHidden(id) ? 'Скрыто' : ''), app: a, go: () => P.app(id) }; }),
      foot('Скрытые приложения не видны на главном экране, но остаются на ТВ. Порядок меняется на главном экране: удерживайте OK на значке.'),
      sys('Все приложения Android…', 'apps'),
    ],
  });
  P.app = (id) => {
    const a = appById(id);
    return {
      title: a.name, art: appArt(a),
      rows: () => [
        ...(id === 'settings' ? [] : [{ t: 'Открыть', act: () => H().launchFromSettings(id) }]),
        tog('Показывать на главном экране', () => !isHidden(id), (v) => H().setHidden(id, !v)),
        { t: 'Поставить первым', act: () => { H().moveFirst(id); H().toast(a.name, 'Теперь первое на главном экране', H().art(a)); } },
        ...(a.pkg ? [head('Сведения'), { t: 'Пакет', v: a.pkg }, sys('О приложении в Android…', 'appinfo', a.pkg)] : []),
        ...(a.pkg ? [foot('В Android можно остановить приложение, очистить его кэш или удалить его.')] : []),
      ],
    };
  };

  // Network
  async function testNet() {
    netTest = true; refresh();
    const t0 = performance.now(); let ok = false;
    const first = (window.TVAerials || [])[0];
    try {
      const c = new AbortController(), tm = setTimeout(() => c.abort(), 6000);
      // On the TV Apple's server answers without CORS headers: an opaque reply still proves it is reachable.
      const r = await fetch(first ? Platform.aerial(first.file) : 'index.html', { method: 'HEAD', signal: c.signal, cache: 'no-store', mode: Platform.tv ? 'no-cors' : 'cors' });
      clearTimeout(tm); ok = r.ok || r.type === 'opaque';
    } catch { ok = false; }
    const ms = Math.round(performance.now() - t0);
    await new Promise((r) => setTimeout(r, Math.max(0, 1200 - ms)));
    netTest = false; refresh();
    if (ok && first) { TV.sound.chime(); OS.alert({ title: 'Подключение работает', text: `Интернет доступен, сервер Apple ответил за ${ms} мс.`, icon: OS.svg('wifi') }); }
    else if (ok) { TV.sound.chime(); OS.alert({ title: 'Сеть работает', text: navigator.onLine ? 'Устройство в сети.' : 'Сеть недоступна.', icon: OS.svg('wifi') }); }
    else { TV.sound.error(); OS.alert({ title: 'Нет подключения к интернету', text: net().connected ? 'Локальная сеть есть, но сервер Apple не ответил.' : 'Устройство не подключено к сети.', icon: OS.svg('wifi') }); }
  }
  const netValue = () => { const n = net(); return !n.connected ? 'Нет подключения' : n.type === 'ethernet' ? 'Ethernet' : n.ssid || 'Подключено'; };
  P.network = () => ({
    title: 'Сеть', art: G('wifi', C.blue),
    rows: () => {
      const n = net();
      return [
        head('Подключение'),
        { t: n.type === 'ethernet' ? 'Ethernet' : 'Wi-Fi', v: netValue, go: 'wifi', id: 'wifi' },
        head('Сведения'),
        { t: 'IP-адрес', v: n.ip || '—' },
        ...(n.type === 'wifi' && n.connected ? [{ t: 'Сигнал', v: SIG[Math.max(0, Math.min(3, n.signal | 0))], gl2: OS.wifiSig((n.signal | 0) + 0) },
          { t: 'Частота', v: n.freqMHz ? (n.freqMHz > 4000 ? '5 ГГц' : '2,4 ГГц') : '—' }, ...(n.linkMbps ? [{ t: 'Скорость канала', v: n.linkMbps + ' Мбит/с' }] : [])] : []),
        netTest ? { t: 'Проверка подключения…', spin: true } : { t: 'Проверить подключение', act: testNet },
        sys('Настройки сети Android…', 'wifi'),
      ];
    },
  });
  P.wifi = () => ({
    title: 'Wi-Fi', art: G('wifi', C.blue),
    rows: () => {
      const n = net();
      return [
        head('Подключено'),
        { t: netValue(), ckl: n.connected, gl2: n.type === 'wifi' && n.connected ? OS.wifiSig(n.signal | 0) : '' },
        ...(n.connected && n.type === 'wifi' ? [{ t: 'Сигнал', v: SIG[Math.max(0, Math.min(3, n.signal | 0))] }] : []),
        gap(),
        sys('Выбрать другую сеть…', 'wifi'),
        foot('Сети, пароли и прокси настраивает Android: так надёжнее, и лаунчеру не нужны лишние разрешения. Имя сети видно, если лаунчеру разрешён доступ к местоположению (tools/tv.sh делает это при установке).'),
      ];
    },
  });

  // Accessibility
  P.access = () => ({
    title: 'Универсальный доступ', art: G('access', C.access),
    rows: () => [
      head('Зрение'),
      tog('VoiceOver', () => OS.pref('voice'), (v) => { OS.setPref('voice', v); if (v) setTimeout(() => OS.speak('VoiceOver включён'), 50); }),
      ptog('Жирный шрифт', 'bold'),
      ptog('Понижение прозрачности', 'solid'),
      ptog('Увеличение контраста', 'contrast'),
      foot('VoiceOver читает вслух выбранный пункт. Понижение прозрачности делает «жидкое стекло» сплошным: так быстрее работает ТВ с 2 ГБ памяти.'),
      head('Движение'),
      ptog('Уменьшение движения', 'still'),
      foot('Значки не покачиваются и не наклоняются, экраны сменяются плавным затуханием.'),
      head('Слух'),
      ptog('Субтитры по умолчанию', 'subs'),
    ],
  });


  // Privacy and security
  P.privacy = () => ({
    title: 'Конфиденциальность и безопасность', art: G('hand', C.blue),
    rows: () => {
      const off = Platform.disabled();
      return [
        head('Отключено на этом ТВ'),
        ...(!Platform.tv ? [{ t: 'Список появится на ТВ', dim: true }] : off.length ? off.map((p) => ({ t: p.name || p.pkg, sub: p.pkg, two: true, v: 'Выкл.' })) : [{ t: 'Ничего не отключено', dim: true }]),
        foot('Отключённые пакеты не запускаются и не занимают память. Как убрать рекламу, телеметрию и лишние службы — в optimization/README.md репозитория.'),
        sys('Разрешения приложений…', 'apps'),
      ];
    },
  });

  // System
  async function restart() {
    const i = await OS.alert({ title: 'Перезапустить лаунчер?', text: 'Главный экран загрузится заново. Настройки и порядок приложений сохранятся.', icon: OS.svg('reset'), buttons: [{ t: 'Перезапустить' }, { t: 'Отменить', cancel: true }] });
    if (i === 0) OS.reboot();
  }
  P.system = () => ({
    title: 'Система', art: tvArt,
    rows: () => [
      { t: 'Об этом устройстве', go: 'about' },
      { t: 'Производительность', v: perfLabel, go: 'perf' },
      sys('Обновление ПО…', 'update'),
      head('Обслуживание'),
      { t: 'Перезапустить лаунчер', act: restart },
      { t: 'Сбросить', go: 'reset' },
      sys('Все настройки Android…', 'settings'),
      gap(),
      { t: 'Режим сна', act: () => H().sleep() },
    ],
  });
  const perfLabel = () => { const p = OS.pref('perf'); return p === 'auto' ? `Авто · ${OS.isFast() ? 'быстрый' : 'качество'}` : p === 'fast' ? 'Быстрый' : 'Качество'; };
  P.perf = () => ({
    title: 'Производительность',
    art: () => G('speed', C.green, OS.isFast() ? 'Быстрый режим' : 'Полное качество', OS.weakDevice ? 'Устройство похоже на ТВ: «Автоматически» выбирает быстрый режим' : 'Устройство мощное: «Автоматически» выбирает полное качество'),
    focus: (rows) => rows.findIndex((r) => val(r.ck)),
    rows: () => [
      head('Отрисовка'),
      ...[['auto', 'Автоматически', OS.weakDevice ? 'сейчас быстрый' : 'сейчас качество'], ['fast', 'Быстрый', ''], ['full', 'Максимальное качество', '']]
        .map(([v, l, sub]) => ({ t: l, v: sub, ck: () => OS.pref('perf') === v, act: () => OS.setPref('perf', v) })),
      foot('Быстрый режим не размывает экран на лету: «стекло» рисуется из заранее размытого фона, без масок и смешивания слоёв, а приложения открываются только масштабом и прозрачностью. На вид почти так же, а видеочипу ТВ работы заметно меньше. «Автоматически» включает его на ТВ и других слабых устройствах.'),
      head('Это устройство'),
      { t: 'Ядра процессора', v: String(navigator.hardwareConcurrency || '—') },
      { t: 'Память (по данным браузера)', v: navigator.deviceMemory ? `≈ ${navigator.deviceMemory} ГБ` : '—' },
      head('Проверка'),
      ptog('Счётчик кадров', 'fps'),
      foot('В углу экрана: кадры в секунду, самый долгий кадр и число рывков (кадров дольше 25 мс).'),
    ],
  });

  P.about = () => ({
    title: 'Об этом устройстве', art: tvArt,
    rows: () => {
      const d = dev(), n = net();
      return [
        { t: 'Название', v: d.name || '—' },
        { t: 'Модель', v: [d.maker, d.model].filter(Boolean).join(' ') || '—' },
        { t: 'Система', v: d.android ? `Android ${d.android}` : '—' },
        { t: 'Процессор', v: d.cores ? `${d.cores} ${d.cores % 10 === 1 && d.cores % 100 !== 11 ? 'ядро' : d.cores % 10 >= 2 && d.cores % 10 <= 4 && (d.cores % 100 < 12 || d.cores % 100 > 14) ? 'ядра' : 'ядер'}${d.hardware ? ' · ' + d.hardware : ''}` : '—' },
        { t: 'Оперативная память', v: d.ramMB ? gb(d.ramMB) + (d.ramFreeMB ? ` · свободно ${gb(d.ramFreeMB)}` : '') : '—' },
        ...(d.storageTotalGB ? [{ t: 'Хранилище', v: `свободно ${String(d.storageFreeGB).replace('.', ',')} из ${String(d.storageTotalGB).replace('.', ',')} ГБ` }] : []),
        { t: 'Экран', v: d.display || '—' },
        { t: 'Движок WebView', v: d.webview || '—' },
        head('Сеть'),
        { t: 'Подключение', v: netValue },
        { t: 'IP-адрес', v: n.ip || '—' },
        head('Лаунчер'),
        { t: 'Версия', v: d.appVersion || 'просмотр в браузере' },
        sys('Об устройстве в Android…', 'about'),
      ];
    },
  });
  P.reset = () => ({
    title: 'Сбросить', art: G('reset', C.gray),
    rows: () => [
      { t: 'Сбросить настройки лаунчера', act: () => OS.alert({ title: 'Сбросить настройки?', text: 'Порядок и видимость приложений, фон, оформление и все параметры вернутся к исходным. Свои фото останутся.', buttons: [{ t: 'Сбросить', red: true }, { t: 'Отменить', cancel: true }], focus: 1 })
        .then((i) => { if (i !== 0) return; Object.keys(localStorage).filter((k) => k.startsWith('tvc.') && k !== 'tvc.custom').forEach((k) => localStorage.removeItem(k)); OS.reboot(); }) },
      { t: 'Удалить свои фото', dim: () => !Wallpapers.list().some((w) => w.custom), act: () => {
        const mine = Wallpapers.list().filter((w) => w.custom);
        if (!mine.length) { TV.sound.bump(); H().toast('Своих фото нет', 'Их можно добавить в Основные → Фон (в браузере)', OS.svg('photo')); return; }
        return OS.alert({ title: `Удалить свои фото (${mine.length})?`, text: 'Фото удалятся из этого браузера без возможности восстановления.', buttons: [{ t: 'Удалить', red: true }, { t: 'Отменить', cancel: true }], focus: 1 })
          .then((i) => { if (i !== 0) return; const cur = Wallpapers.current(); mine.forEach((w) => Wallpapers.removeCustom(w.id)); if (mine.some((w) => w.id === cur)) Wallpapers.set(Wallpapers.list()[0].id); window.dispatchEvent(new CustomEvent('wallpaperlist')); });
      } },
      foot('Сам ТВ и его приложения это не затрагивает.'),
    ],
  });


  // Developer
  P.dev = () => ({
    title: 'Разработчик', art: G('dev', C.graphite),
    rows: () => {
      const s = Platform.system();
      return [
        head('Android'),
        { t: 'Отладка по ADB', v: s.adb == null ? '—' : onoff(s.adb) },
        { t: 'Скорость анимаций', v: s.animScale == null ? '—' : String(s.animScale).replace('.', ',') + 'x' },
        { t: 'Главный экран', v: s.home || '—' },
        sys('Параметры разработчика…', 'developer'),
        foot('Анимации Android быстрее всего на 0,5x. Как это включить и что ещё ускоряет ТВ — в optimization/README.md.'),
        ...(Platform.tv ? [] : [head('Браузер'), { t: 'Сочетания клавиш', go: 'shortcuts' }]),
      ];
    },
  });
  P.shortcuts = () => ({
    title: 'Сочетания клавиш', art: G('keyboard', C.graphite),
    rows: () => [
      ['Стрелки', 'Кнопки направления'], ['Enter', 'OK (удерживать — меню)'], ['Esc', 'Назад'], ['H', 'Домой'], ['H дважды или Tab', 'Переключатель приложений'],
      ['C', 'Пункт управления'], ['G', 'Настройки'], ['S', 'Заставка'], ['W', 'Фоны'], ['M', 'Звуки вкл./выкл.'], ['?', 'Подсказка'],
    ].map(([k, d]) => ({ t: k, v: d })),
  });

  // ---------- rows ----------
  const focusable = (r) => r && !r.type;
  function rowClass(r) {
    if (r.type === 'head') return 'sh'; if (r.type === 'foot') return 'sf'; if (r.type === 'gap') return 'sgap';
    return 'sr' + (r.two && val(r.sub) ? ' two' : '') + (r.red ? ' red' : '') + (r.blue ? ' blue' : '') + (val(r.dim) ? ' dim' : '') + (r.ctr ? ' ctr' : '') + (r.range ? ' range' : '');
  }
  function rowInner(r) {
    if (r.type) return esc(val(r.text));
    let s = '';
    if (r.ckl !== undefined) s += `<span class="ck">${val(r.ckl) ? OS.svg('check') : ''}</span>`;
    const ava = val(r.ava); if (ava) s += `<span class="ava" style="--gc1:${ava[1]};--gc2:${ava[2]}">${esc(ava[0].toUpperCase())}</span>`;
    const ico = val(r.ico); if (ico) s += `<span class="ico" style="background-image:url('${ico}')"></span>`;
    if (r.gi) s += `<span class="gi">${OS.svg(r.gi)}</span>`;
    if (val(r.spin)) s += '<span class="spin"></span>';
    const t = esc(val(r.t)), sub = val(r.sub);
    if (r.two && sub) s += `<span class="stx"><span class="st">${t}</span><span class="ss">${esc(sub)}</span></span>`;
    else s += `<span class="st">${t}</span>${sub ? `<span class="ss">${esc(sub)}</span>` : ''}`;
    if (r.range) { const R = r.range, v = R.get(); s += `<span class="sv">${esc(R.fmt(v))}</span><span class="arr">‹ ›</span><div class="sbar"><i style="width:${((v - R.min) / (R.max - R.min)) * 100}%"></i></div>`; }
    else { const v = val(r.v); if (v != null && v !== '') s += `<span class="sv">${esc(v)}</span>`; }
    const g2 = val(r.gl2); if (g2) s += `<span class="gl2">${g2}</span>`;
    if (r.ck !== undefined) s += `<span class="ck end">${val(r.ck) ? OS.svg('check') : ''}</span>`;
    return s;
  }
  const sig = (rows) => rows.map((r) => (r.type || 'r') + (r.range ? 'R' : '') + ':' + (r.type ? '' : r.id || '')).join('|');

  // ---------- navigation stack ----------
  let stack = [], isOpen = false;
  const top = () => stack[stack.length - 1];
  function build(e) {
    e.rows = e.page.rows(); e.sig = sig(e.rows);
    e.inner.innerHTML = e.rows.map((r) => `<div class="${rowClass(r)}">${rowInner(r)}</div>`).join('');
    e.els = [...e.inner.children]; e.geo = null;
  }
  // Row positions are measured once per build (one layout), not on every key press.
  function geo(e) {
    if (!e.geo) e.geo = { tops: e.els.map((el) => el.offsetTop), hs: e.els.map((el) => el.offsetHeight), total: e.inner.scrollHeight };
    return e.geo;
  }
  const firstFocusable = (e) => e.rows.findIndex(focusable);
  function clampFocus(e, i) {
    if (focusable(e.rows[i])) return i;
    for (let d = 1; d < e.rows.length; d++) { if (focusable(e.rows[i - d])) return i - d; if (focusable(e.rows[i + d])) return i + d; }
    return -1;
  }
  function make(page, parent) {
    const el = document.createElement('div'); el.className = 'spage'; el.innerHTML = '<div class="sinner"></div>'; pagesEl.appendChild(el);
    const e = { page, el, inner: el.firstChild, rows: [], els: [], idx: -1, scroll: 0, parent };
    build(e);
    const f = page.focus ? page.focus(e.rows) : -1; e.idx = f >= 0 ? f : firstFocusable(e);
    return e;
  }
  function scroll(e) {
    if (!e.els[e.idx]) return;
    const g = geo(e), V = 942 - 30, top = g.tops[e.idx], h = g.hs[e.idx], total = g.total; let s = e.scroll;
    if (top - s < 160) s = top - 160;
    if (top + h - s > V - 200) s = top + h - (V - 200);
    s = Math.max(0, Math.min(s, Math.max(0, total - V + 90)));
    if (e.idx === firstFocusable(e)) s = 0;
    e.scroll = s; e.inner.style.transform = `translateY(${-s}px)`;
  }
  function paint(e, quiet) {
    e.els.forEach((el, i) => el.classList.toggle('on', i === e.idx));
    scroll(e);
    if (e === top()) { drawArt(e); if (!quiet) say(e); }
  }
  function say(e) {
    const r = e.rows[e.idx]; if (!r) return;
    const v = r.range ? r.range.fmt(r.range.get()) : val(r.v); const ck = val(r.ck) || val(r.ckl);
    OS.speak(`${val(r.t)}${v ? ', ' + v : ''}${ck ? ', выбрано' : ''}`);
  }
  let tA = 0;
  function setTitle(t) {
    const sp = titleEl.children, cur = sp[tA], nxt = sp[1 - tA];
    if (cur.textContent === t) return;
    nxt.textContent = t; nxt.classList.remove('out'); cur.classList.add('out'); tA = 1 - tA;
  }
  let aA = 0, artKey = null;
  function resolveArt(e) { for (let x = e; x; x = x.parent) { const a = x.page.art; if (a) { const s = typeof a === 'function' ? a(x.rows[x.idx], x) : a; if (s) return val(s); } } return null; }
  function artHTML(s) {
    if (!s) return '';
    const cap = s.cap ? `<div class="sacap"><b>${esc(s.cap)}</b>${s.sub ? `<span>${esc(s.sub)}</span>` : ''}</div>` : '';
    if (s.g) return `<div class="sglyph" style="--gc1:${s.c[0]};--gc2:${s.c[1]}">${OS.svg(s.g)}</div>${cap}`;
    if (s.ava) return `<div class="sglyph" style="--gc1:${s.ava[1]};--gc2:${s.ava[2]};border-radius:50%"><span style="position:relative;z-index:1;font-size:190px;font-weight:600">${esc(s.ava[0])}</span></div>${cap}`;
    if (s.tv) return `<div class="stv"><div class="bez"><div class="scr"></div></div><div class="logo">${esc((Platform.device().maker || '').toUpperCase())}</div><div class="leg l"></div><div class="leg r"></div></div>${cap}`;
    if (s.img !== undefined) return `<div class="sprev">${s.badge ? `<div class="badge">${esc(s.badge)}</div>` : ''}</div>${cap}`;
    return '';
  }
  function drawArt(e, force) {
    const s = resolveArt(e), key = s ? s.key || JSON.stringify(s) : '';
    if (key === artKey && !force) return; artKey = key;
    const hs = artEl.children, nxt = hs[1 - aA], cur = hs[aA];
    nxt.innerHTML = artHTML(s);
    const target = s && (s.tv ? nxt.querySelector('.scr') : nxt.querySelector('.sprev'));
    if (target) Promise.resolve(s.tv ? Wallpapers.preview(Wallpapers.current()) : val(s.img)).then((u) => { if (u) target.style.backgroundImage = `url("${u}")`; });
    nxt.classList.add('on'); cur.classList.remove('on'); aA = 1 - aA;
  }
  function refresh(e = top()) {
    if (!e) return;
    const rows = e.page.rows();
    if (sig(rows) !== e.sig) { const keep = e.idx; build(e); e.idx = clampFocus(e, Math.min(keep, e.rows.length - 1)); paint(e, true); }
    else {
      e.rows = rows;
      rows.forEach((r, i) => {
        const el = e.els[i], cls = rowClass(r) + (i === e.idx ? ' on' : '');
        if (el.className !== cls) { if (el.className.replace(' on', '') !== cls.replace(' on', '')) e.geo = null; el.className = cls; }
        if (r.range && el.querySelector('.sbar i')) { // keep the bar element so its width animates
          const R = r.range, v = R.get(); el.querySelector('.sv').textContent = R.fmt(v); el.querySelector('.sbar i').style.width = ((v - R.min) / (R.max - R.min)) * 100 + '%'; return;
        }
        const h = rowInner(r); if (el._h !== h) { el.innerHTML = h; el._h = h; }
      });
      scroll(e);
    }
    if (e === top()) { setTitle(val(e.page.title)); drawArt(e); }
  }
  function push(page, instant) {
    const cur = top(), e = make(page, cur);
    stack.push(e);
    if (cur) cur.el.classList.add('left');
    if (!instant) { e.el.classList.add('right'); getComputedStyle(e.el).opacity; e.el.classList.remove('right'); }
    setTitle(val(page.title)); paint(e);
    if (page.onOpen) page.onOpen(e);
  }
  function pop() {
    const e = stack.pop(); e.el.classList.add('right'); setTimeout(() => e.el.remove(), 500);
    const p = top(); p.el.classList.remove('left'); refresh(p); paint(p);
  }
  const resolvePage = (go) => (typeof go === 'function' ? go() : P[go]());
  function press(el) { el.classList.add('press'); setTimeout(() => el.classList.remove('press'), 110); }
  function nudge(el, dx, dy) {
    if (!el || OS.pref('still')) return;
    el.animate([{ transform: 'scale(1.055)' }, { transform: `translate(${dx * 12}px, ${dy * 8}px) scale(1.055)` }, { transform: 'scale(1.055)' }], { duration: 320, easing: 'cubic-bezier(.3,1.6,.5,1)' });
  }
  function key(a) {
    const e = top(); if (!e) return;
    const r = e.rows[e.idx], el = e.els[e.idx];
    if (a === 'up' || a === 'down') {
      const d = a === 'up' ? -1 : 1; let n = e.idx + d;
      while (n >= 0 && n < e.rows.length && !focusable(e.rows[n])) n += d;
      if (n < 0 || n >= e.rows.length) { TV.sound.bump(); nudge(el, 0, d); return; }
      e.idx = n; TV.sound.tick(); paint(e); return;
    }
    if (a === 'left' || a === 'right') {
      const d = a === 'left' ? -1 : 1;
      if (r && r.range) {
        const R = r.range, v = R.get(), n = Math.max(R.min, Math.min(R.max, v + d * R.step));
        if (n !== v) { R.set(n); TV.sound.slide((n - R.min) / (R.max - R.min)); refresh(e); OS.speak(R.fmt(n)); } else { TV.sound.bump(); nudge(el, d, 0); }
        return;
      }
      TV.sound.bump(); nudge(el, d, 0); return;
    }
    if (a === 'ok') {
      if (!r) return;
      if (r.go) { TV.sound.select(); press(el); push(resolvePage(r.go)); return; }
      if (r.act) { if (!r.quiet) TV.sound.select(); press(el); Promise.resolve(r.act(e)).then(() => { if (isOpen && stack.includes(e)) refresh(e); }); refresh(e); return; }
      return; // information rows do nothing, as on tvOS
    }
    if (a === 'back') { if (stack.length > 1) { TV.sound.back(); pop(); } else H().exitSettings(); }
  }

  // ---------- open / close ----------
  function syncBg() {
    Wallpapers.blurred(Wallpapers.current(), 48, 0, 'glass').then((u) => {
      const ls = [...root.querySelectorAll('.sbg .layer')], now = ls.find((l) => l.classList.contains('on'));
      if (now && now.dataset.src === u) return;
      const next = ls.find((l) => l !== now); next.dataset.src = u; next.style.backgroundImage = `url("${u}")`; next.classList.add('on'); if (now) now.classList.remove('on');
    });
  }
  addEventListener('wallpaperchange', () => { syncBg(); if (isOpen) refresh(); });
  addEventListener('wallpaperlist', () => { if (isOpen) refresh(); });
  const clipOf = (r) => (r ? `inset(${r.y}px ${1920 - r.x - r.w}px ${1080 - r.y - r.h}px ${r.x}px round 28px)` : 'inset(18% 18% 18% 18% round 60px)');
  const FULL = 'inset(0px 0px 0px 0px round 0px)';
  function anim(open, rect) {
    root.getAnimations().forEach((x) => x.cancel()); body.getAnimations().forEach((x) => x.cancel());
    if (OS.pref('still') || OS.isFast()) return OS.zoom(root, rect, open); // one composited layer: transform + opacity
    const a = root.animate([{ clipPath: open ? clipOf(rect) : FULL }, { clipPath: open ? FULL : clipOf(rect) }],
      { duration: open ? 520 : 420, easing: open ? 'cubic-bezier(.3,.1,.1,1)' : 'cubic-bezier(.4,0,.2,1)', fill: 'forwards' });
    if (!rect) root.animate([{ opacity: open ? 0 : 1 }, { opacity: open ? 1 : 0 }], { duration: open ? 300 : 360, fill: 'forwards' });
    body.animate(open ? [{ opacity: 0, transform: 'scale(.9)' }, { opacity: 1, transform: 'none' }] : [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.94)' }],
      { duration: open ? 560 : 260, delay: open ? 70 : 0, easing: open ? 'cubic-bezier(.2,.9,.3,1)' : 'ease-in', fill: 'forwards' });
    return a.finished;
  }
  const S = OS.Settings = {
    get isOpen() { return isOpen; },
    show(o = {}) {
      syncBg();
      if (o.path) S.reset();
      if (!stack.length) push(P.root(), true);
      if (o.path) {
        for (const id of o.path) {
          const e = top(), i = e.rows.findIndex((r) => r.id === id || r.go === id); if (i < 0) break;
          e.idx = i; paint(e, true); push(resolvePage(e.rows[i].go), true);
        }
      }
      stack.forEach((e, i) => { e.el.classList.toggle('left', i < stack.length - 1); e.el.classList.remove('right'); });
      isOpen = true; root.classList.add('on'); stack.forEach((e) => refresh(e));
      const t = top(); setTitle(val(t.page.title)); artKey = null; paint(t, true);
      OS.recent('settings');
      if (o.instant) { root.getAnimations().forEach((x) => x.cancel()); body.getAnimations().forEach((x) => x.cancel()); } else anim(true, o.rect);
      OS.speak(`${val(t.page.title)}. ${val(t.rows[t.idx] && t.rows[t.idx].t) || ''}`);
    },
    hide(o = {}) {
      isOpen = false;
      const done = () => { if (isOpen) return; root.classList.remove('on'); root.getAnimations().forEach((x) => x.cancel()); body.getAnimations().forEach((x) => x.cancel()); };
      if (o.instant) { done(); return Promise.resolve(); }
      return anim(false, o.rect).then(done, done);
    },
    key, refresh: () => refresh(),
    reset() { stack.forEach((e) => e.el.remove()); stack = []; artKey = null; },
    snapshot() {
      if (!stack.length) { push(P.root(), true); }
      const c = root.cloneNode(true); c.classList.add('on'); c.style.cssText = ''; c.querySelector('.sbody').style.cssText = '';
      return c;
    },
    pages: P,
  };
})();
