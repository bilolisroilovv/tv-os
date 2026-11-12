package app.tvos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The launcher's Settings app, laid out like tvOS Settings (web/os/settings.js + os.css, fast mode): the page
 * title on top, a big picture of the focused section on the left, the list on the right. Pages push in from the
 * right and Back pops them.
 *
 * Every row is one small View that draws itself; the list scrolls by moving its container (a RenderNode
 * transform), so nothing is redrawn while it scrolls and nothing at all while it rests.
 */
final class Settings {
    final Home h;
    final Views.Abs root;
    private final Views.Box bg;
    private Views.Box titleV; // not final: the title animations below are created before the constructor sets it
    private final ArtView[] arts = new ArtView[2];
    private final Views.Abs pagesHost;
    private final ArrayList<Entry> stack = new ArrayList<>();
    private boolean open;
    private Motion.Run run;
    static boolean ccoverOn;

    // =====================================================================================================
    // model
    // =====================================================================================================

    static final int ROW = 0, HEAD = 1, FOOT = 2, GAP = 3;

    static final class Range {
        int min, max, step;
        IntSupplier get;
        IntConsumer set;
        IntFunction<String> fmt;

        Range(int min, int max, int step, IntSupplier get, IntConsumer set, IntFunction<String> fmt) {
            this.min = min; this.max = max; this.step = step; this.get = get; this.set = set; this.fmt = fmt;
        }
    }

    /** The picture on the left: a glyph tile, a preview image, the TV itself or an avatar, with a caption. */
    static final class Art {
        String g, cap, sub, key, wp, badge, avaL;
        int c1, c2;
        Apps.App app;
        boolean tv;
    }

    static final class Row {
        int type;
        Supplier<String> t, v, sub;
        String text, goId, id, wp, place, cat;
        boolean two, red, blue, quiet, ctr;
        Supplier<Page> go;
        Runnable act;
        Range range;
        BooleanSupplier ck, ckl, dim, spin;
        Supplier<Bitmap> ico;
        Supplier<String[]> ava;
        IntSupplier gl2;
        Supplier<Art> art;
        Apps.App app;

        Row val(Supplier<String> s) { v = s; return this; }
        Row vs(String s) { v = () -> s; return this; }
        Row sub(String s) { sub = () -> s; return this; }
        Row go(String page) { goId = page; return this; }
        Row goP(Supplier<Page> p) { go = p; return this; }
        Row act(Runnable r) { act = r; return this; }
        Row ck(BooleanSupplier b) { ck = b; return this; }
        Row ckl(BooleanSupplier b) { ckl = b; return this; }
        Row dim(BooleanSupplier b) { dim = b; return this; }
        Row red() { red = true; return this; }
        Row blue(boolean b) { blue = b; return this; }
        Row two() { two = true; return this; }
        Row quiet() { quiet = true; return this; }
        Row id(String s) { id = s; return this; }
        Row art(Supplier<Art> a) { art = a; return this; }
        Row range(Range r) { range = r; return this; }
        Row ico(Supplier<Bitmap> b) { ico = b; return this; }
        Row ava(Supplier<String[]> a) { ava = a; return this; }
        Row gl2(IntSupplier n) { gl2 = n; return this; }
        Row spin() { spin = () -> true; return this; }
    }

    static final class Page {
        Supplier<String> title;
        Function<Row, Art> art;
        Supplier<List<Row>> rows;
        ToIntFunction<List<Row>> focus;

        Page(String title, Function<Row, Art> art, Supplier<List<Row>> rows) { this(() -> title, art, rows); }

        Page(Supplier<String> title, Function<Row, Art> art, Supplier<List<Row>> rows) {
            this.title = title; this.art = art; this.rows = rows;
        }

        Page focusChecked() {
            focus = rs -> { for (int i = 0; i < rs.size(); i++) if (rs.get(i).ck != null && rs.get(i).ck.getAsBoolean()) return i; return -1; };
            return this;
        }
    }

    static Row R(String t) { Row r = new Row(); r.t = () -> t; return r; }

    static Row R(Supplier<String> t) { Row r = new Row(); r.t = t; return r; }

    static Row head(String s) { Row r = new Row(); r.type = HEAD; r.text = s; return r; }

    static Row foot(String s) { Row r = new Row(); r.type = FOOT; r.text = s; return r; }

    static Row gap() { Row r = new Row(); r.type = GAP; return r; }

    static String onoff(boolean b) { return b ? "Вкл." : "Выкл."; }

    static Row tog(String t, BooleanSupplier get, Consumer<Boolean> set) {
        return R(t).val(() -> onoff(get.getAsBoolean())).act(() -> set.accept(!get.getAsBoolean()));
    }

    static Row ptog(String t, String k) { return tog(t, () -> Prefs.b(k), v -> Prefs.set(k, v)); }

    /** A row whose value is one of opts ({value, label, sub?}); it opens a page of checked choices. */
    static Row choice(String t, Supplier<Object> get, Consumer<Object> set, Object[][] opts, Art art, String headText, String footText) {
        Supplier<String> label = () -> {
            Object cur = get.get();
            for (Object[] o : opts) if (o[0].equals(cur)) return (String) o[1];
            return "";
        };
        return R(t).val(label).goP(() -> new Page(t, art == null ? null : r -> art, () -> {
            List<Row> rs = new ArrayList<>();
            if (headText != null) rs.add(head(headText));
            for (Object[] o : opts) {
                Row r = R((String) o[1]).ck(() -> o[0].equals(get.get())).act(() -> { if (!o[0].equals(get.get())) set.accept(o[0]); });
                if (o.length > 2 && o[2] != null) r.vs((String) o[2]);
                rs.add(r);
            }
            if (footText != null) rs.add(foot(footText));
            return rs;
        }).focusChecked());
    }

    static Row pchoiceS(String t, String k, Object[][] opts, Art art, String footText) {
        return choice(t, () -> Prefs.s(k), v -> Prefs.set(k, v), opts, art, null, footText);
    }

    static Row pchoiceI(String t, String k, Object[][] opts, Art art, String footText) {
        return choice(t, () -> Prefs.i(k), v -> Prefs.set(k, v), opts, art, null, footText);
    }

    /** A row that opens an Android settings screen. */
    Row sys(String t, String what, String arg) {
        return R(t).blue(true).act(() -> {
            if (!Device.open(h.act, what, arg)) {
                Sounds.error();
                h.toast("Не открывается", "На этом ТВ такого экрана настроек нет", null, "gear");
            }
        });
    }

    static List<Row> rows(Object... items) {
        List<Row> l = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof Row) l.add((Row) o);
            else if (o instanceof List) for (Object x : (List<?>) o) l.add((Row) x);
        }
        return l;
    }

    // ---------- colours of the section pictures ----------
    static final int[] C_GRAY = {0xffb4bac3, 0xff6b717a}, C_GRAPHITE = {0xff9a9aa0, 0xff48484c}, C_BLUE = {0xff5aaeff, 0xff0a6cf0},
            C_PINK = {0xffff7896, 0xffe3244f}, C_TEAL = {0xff62d6ea, 0xff138db0}, C_INDIGO = {0xff9897f7, 0xff4c4ad6},
            C_GREEN = {0xff6ae394, 0xff1fa457}, C_ORANGE = {0xffffb84d, 0xfff27b0c}, C_PURPLE = {0xffc99bff, 0xff8a45e6}, C_ACCESS = {0xff45a2ff, 0xff005fdd};

    static Art G(String g, int[] c) { return G(g, c, null, null); }

    static Art G(String g, int[] c, String cap, String sub) {
        Art a = new Art();
        a.g = g; a.c1 = c[0]; a.c2 = c[1]; a.cap = cap; a.sub = sub;
        a.key = "g:" + g + ":" + c[0] + ":" + cap + ":" + sub;
        return a;
    }

    static int[] avaColor(String k) {
        switch (k) {
            case "green": return C_GREEN;
            case "orange": return C_ORANGE;
            case "pink": return C_PINK;
            case "purple": return C_PURPLE;
            case "graphite": return C_GRAPHITE;
            default: return C_BLUE;
        }
    }

    Art userArt() {
        String n = Prefs.s("userName");
        int[] c = avaColor(Prefs.s("userColor"));
        Art a = new Art();
        a.avaL = n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
        a.c1 = c[0]; a.c2 = c[1]; a.cap = n; a.sub = "Основной пользователь";
        a.key = "ava:" + n + ":" + c[0];
        return a;
    }

    private Device.Info info;

    Device.Info dev() {
        if (info == null) info = Device.info(h.act);
        return info;
    }

    Device.Net net() { return Device.net(h.act); }

    Art tvArt() {
        Device.Info d = dev();
        Art a = new Art();
        a.tv = true;
        a.cap = d.name != null && !d.name.isEmpty() ? d.name : d.model != null ? d.model : "Этот ТВ";
        List<String> s = new ArrayList<>();
        if (d.android != null && !d.android.isEmpty()) s.add("Android " + d.android);
        if (d.display != null) s.add(d.display);
        a.sub = String.join(" · ", s);
        a.key = "tv:" + Wallpapers.current() + ":" + a.cap;
        return a;
    }

    static Art imgArt(String wp, String key, String cap, String sub) {
        Art a = new Art();
        a.wp = wp; a.key = key; a.cap = cap; a.sub = sub;
        return a;
    }

    static Art appArt(Apps.App a) {
        Art s = new Art();
        s.app = a;
        s.key = "app:" + a.id;
        s.cap = a.name;
        s.sub = a.tagline != null ? a.tagline : a.pkg;
        return s;
    }

    static String tzName() {
        try {
            TimeZone tz = TimeZone.getDefault();
            String n = tz.getDisplayName(tz.inDaylightTime(new Date()), TimeZone.LONG, new Locale("ru"));
            return n.replaceAll(", стандартное время$", "");
        } catch (Exception e) {
            return "Автоматически";
        }
    }

    static String langName() {
        String n = Locale.getDefault().getDisplayLanguage(new Locale("ru"));
        return n.isEmpty() ? Locale.getDefault().toString() : n.substring(0, 1).toUpperCase() + n.substring(1);
    }

    static String gb(long mb) {
        if (mb >= 1024) {
            String s = mb % 1024 != 0 ? String.format(Locale.ROOT, "%.1f", mb / 1024.0) : String.valueOf(mb / 1024);
            return s.replace('.', ',') + " ГБ";
        }
        return mb + " МБ";
    }

    static String num(double d) {
        String s = d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        return s.replace('.', ',');
    }

    static final String[] SIG = {"слабый", "средний", "хороший", "отличный"};

    // Aerial themes: Apple's own category names when tools/aerials.py has made the list.
    static List<String[]> cats() {
        String[][] all = {{"Landscapes", "Природа"}, {"Cities", "Города"}, {"Underwater", "Под водой"}, {"Space", "Планета Земля"}};
        List<String[]> out = new ArrayList<>();
        for (String[] c : all) {
            String n = Wallpapers.CATS.get(c[0]);
            boolean has = Wallpapers.AERIALS.isEmpty();
            for (Wallpapers.Aerial a : Wallpapers.AERIALS) if (c[0].equals(a.cat)) { has = true; break; }
            if (has) out.add(new String[]{c[0], n != null ? n : c[1]});
        }
        return out;
    }

    static int aerCount(List<String> cats) {
        int n = 0;
        for (Wallpapers.Aerial a : Wallpapers.AERIALS) if (cats.contains(a.cat)) n++;
        return n;
    }

    private boolean netTest;

    // =====================================================================================================
    // pages
    // =====================================================================================================

    Page page(String id) {
        switch (id) {
            case "general": return general();
            case "wallpapers": return wallpapers();
            case "keyboard": return keyboard();
            case "users": return users();
            case "user": return user();
            case "av": return av();
            case "sounds": return sounds();
            case "saver": return saver();
            case "themes": return themes();
            case "remotes": return remotes();
            case "apps": return apps();
            case "network": return network();
            case "wifi": return wifi();
            case "access": return access();
            case "privacy": return privacy();
            case "system": return system();
            case "perf": return perf();
            case "about": return about();
            case "reset": return resetPage();
            case "dev": return devPage();
            default: return rootPage();
        }
    }

    Page rootPage() {
        return new Page("Настройки", r -> r != null && r.art != null ? r.art.get() : null, () -> rows(
                R("Основные").go("general").art(() -> G("gear", C_GRAY)),
                R("Пользователи и учётные записи").go("users").art(() -> G("user", C_BLUE)),
                R("Видео и аудио").go("av").art(() -> G("av", C_PINK)),
                R("Заставка").go("saver").art(() -> G("saver", C_TEAL)),
                R("Пульты и устройства").go("remotes").art(() -> G("remote", C_GRAPHITE)),
                R("Приложения").go("apps").art(() -> G("apps", C_INDIGO)),
                R("Сеть").go("network").art(() -> G("wifi", C_BLUE)),
                R("Универсальный доступ").go("access").art(() -> G("access", C_ACCESS)),
                R("Конфиденциальность и безопасность").go("privacy").art(() -> G("hand", C_BLUE)),
                R("Система").go("system").art(this::tvArt),
                R("Разработчик").go("dev").art(() -> G("dev", C_GRAPHITE)),
                gap(),
                R("Режим сна").act(h::sleepNow).art(() -> G("moon", C_INDIGO, "Режим сна", "Экран гаснет, любая кнопка его будит"))));
    }

    Page general() {
        return new Page("Основные", r -> G("gear", C_GRAY), () -> rows(
                head("Главный экран"),
                pchoiceS("Оформление", "look", new Object[][]{{"light", "Светлое"}, {"dark", "Тёмное"}, {"auto", "Автоматически", "с 19:00 до 7:00"}},
                        G("look", C_GRAPHITE), "Автоматически: светлое днём и тёмное вечером и ночью."),
                pchoiceS("Часы", "clock", new Object[][]{{"center", "По центру"}, {"left", "Слева"}}, G("clock", C_GRAPHITE), null),
                R("Фон").val(() -> { Wallpapers.W w = Wallpapers.get(Wallpapers.current()); return w == null ? "" : w.name; }).go("wallpapers"),
                R("Вернуть исходный порядок приложений").act(() -> Modal.alert("Вернуть исходный порядок?", "Приложения встанут на главном экране в исходном порядке.", null, null,
                        new String[]{"Вернуть", "Отменить"}, new boolean[]{false, false}, 1, 0, i -> {
                            if (i == 0) { h.resetOrder(); h.toast("Порядок приложений", "Исходный порядок восстановлен", null, "apps"); }
                            refreshTop();
                        })),
                head("Сон"),
                pchoiceI("Гасить экран", "sleepAfter", new Object[][]{{0, "Никогда"}, {15, "Через 15 минут"}, {30, "Через 30 минут"}, {60, "Через 1 час"}, {300, "Через 5 часов"}, {600, "Через 10 часов"}},
                        G("moon", C_INDIGO), "Если долго не нажимать кнопки, главный экран погаснет. Выключение самого ТВ настраивается в Android."),
                head("Язык и регион"),
                sys("Язык", "language", "").val(Settings::langName).blue(false),
                R("Часовой пояс").val(Settings::tzName),
                ptog("24-часовой формат", "hour24"),
                sys("Дата и время…", "date", ""),
                R("Клавиатура").vs("Русская, English").go("keyboard")));
    }

    Page wallpapers() {
        return new Page("Фон", r -> r != null && r.wp != null ? imgArt(r.wp, "wp:" + r.wp, r.t.get(), r.place) : G("photo", C_TEAL), () -> {
            List<Row> rs = new ArrayList<>();
            for (Wallpapers.W w : Wallpapers.list()) {
                Row r = R(w.name).ck(() -> w.id.equals(Wallpapers.current())).act(() -> Wallpapers.set(w.id));
                r.wp = w.id;
                r.place = w.place;
                rs.add(r);
            }
            rs.add(foot("Фон меняется сразу. Снимки из заставки Apple TV добавляет tools/aerials.py при установке."));
            return rs;
        }).focusChecked();
    }

    Page keyboard() {
        return new Page("Клавиатура", r -> G("keyboard", C_GRAPHITE), () -> rows(
                head("Раскладки"), R("Русская").ck(() -> true), R("English").ck(() -> true),
                head("Проверка"),
                R("Попробовать клавиатуру…").act(() -> Modal.keyboard("Проверка клавиатуры", "Стрелки выбирают букву, OK печатает", "", "Текст", 64, false, null,
                        v -> { if (v != null && !v.isEmpty()) h.toast("Вы напечатали", v, null, "keyboard"); })),
                foot("Клавиатура на экране — как в tvOS: один ряд букв и ряд кнопок под ним. «Глобус» переключает язык.")));
    }

    Page users() {
        return new Page("Пользователи и учётные записи", r -> G("user", C_BLUE), () -> rows(
                head("Профиль на главном экране"),
                R(() -> Prefs.s("userName")).sub("Имя и цвет для лаунчера").two().ava(() -> {
                    int[] c = avaColor(Prefs.s("userColor"));
                    String n = Prefs.s("userName");
                    return new String[]{n.isEmpty() ? "?" : n.substring(0, 1), String.valueOf(c[0]), String.valueOf(c[1])};
                }).go("user"),
                head("Учётные записи"),
                sys("Учётные записи Android…", "accounts", ""),
                foot("Аккаунты (Google и другие) хранит сам Android: лаунчер их не видит и не трогает.")));
    }

    Page user() {
        return new Page(() -> Prefs.s("userName"), r -> userArt(), () -> rows(
                R("Имя").val(() -> Prefs.s("userName")).act(() -> Modal.keyboard("Имя пользователя", null, Prefs.s("userName"), "Имя", 24, false, null, v -> {
                    if (v != null && !v.trim().isEmpty()) Prefs.set("userName", v.trim());
                    refreshTop();
                })),
                pchoiceS("Цвет", "userColor", new Object[][]{{"blue", "Синий"}, {"green", "Зелёный"}, {"orange", "Оранжевый"}, {"pink", "Розовый"}, {"purple", "Фиолетовый"}, {"graphite", "Графит"}}, null, null)));
    }

    Page av() {
        return new Page("Видео и аудио", r -> G("av", C_PINK), () -> rows(
                head("Видео"),
                R("Экран").val(() -> dev().display != null ? dev().display : "—"),
                sys("Изображение и яркость ТВ…", "display", ""),
                R("Яркость интерфейса").range(new Range(10, 100, 5, () -> Prefs.i("bright"), v -> Prefs.set("bright", v), v -> v + "%")),
                foot("«Яркость интерфейса» приглушает только главный экран и меню лаунчера. Подсветка самого ТВ — в «Изображение и яркость ТВ»."),
                head("Аудио"),
                sys("Звук ТВ…", "sound", ""),
                head("Звуки интерфейса"),
                tog("Щелчки при навигации", () -> Prefs.b("sound"), v -> Prefs.set("sound", v)),
                tog("Звуковые эффекты", () -> Prefs.b("sound.fx"), v -> { Prefs.set("sound.fx", v); if (v) Tv.later(120, Sounds::chime); }),
                R("Набор звуков").val(Sounds::setName).go("sounds"),
                R("Громкость звуков").range(new Range(0, 100, 10, () -> Prefs.i("sound.vol"), v -> Prefs.set("sound.vol", v), v -> v > 0 ? v + "%" : "Выкл.")),
                foot("Щелчки звучат при перемещении фокуса, нажатиях и вводе текста. Эффекты — короткие сигналы «готово» и «ошибка».")));
    }

    Page sounds() {
        return new Page("Набор звуков", r -> G("sound", C_PINK), () -> {
            List<Row> rs = new ArrayList<>();
            rs.add(head("Набор"));
            for (String[] st : Sounds.SETS) {
                String id = st[0];
                rs.add(R(st[2]).vs(st[3]).ck(() -> id.equals(Prefs.s("sound.set")) || "tvos".equals(id) && !isSet(Prefs.s("sound.set")))
                        .act(() -> setSoundSet(id)));
            }
            rs.add(head("Звуки (OK — прослушать)"));
            for (String[] s : Sounds.NAMES) {
                String k = s[0];
                rs.add(R(s[1]).val(() -> Sounds.system() && !("tick".equals(k) || "select".equals(k) || "key".equals(k) || "del".equals(k)) ? "Без звука" : "")
                        .quiet().act(() -> Sounds.play(k)));
            }
            rs.add(foot("Наборы TV OS, «Стекло» и «Мягкий» сделаны для этого лаунчера: короткие, как на Apple TV, и не нагружают ТВ. Выберите набор и послушайте его в списке выше. Панели и меню открываются без звука."));
            return rs;
        });
    }

    private static boolean isSet(String v) {
        for (String[] s : Sounds.SETS) if (s[0].equals(v)) return true;
        return false;
    }

    private void setSoundSet(String v) {
        if (v.equals(Prefs.s("sound.set"))) return;
        Prefs.set("sound.set", v);
        Tv.later(150, () -> { Sounds.tick(); Tv.later(260, Sounds::select); });
    }

    Art saverArt() {
        List<String> on = Prefs.list("saverCats");
        if ("aerial".equals(Prefs.s("saverType")) && !Wallpapers.AERIALS.isEmpty()) {
            Art a = imgArt(Wallpapers.catPic(on.isEmpty() ? "Landscapes" : on.get(0)), "saver:a:" + on, "Аэросъёмка Apple", aerCount(on) + " видео с подписями мест");
            a.badge = "Apple TV Aerial";
            return a;
        }
        String cur = Wallpapers.current();
        return imgArt(cur, "saver:p:" + cur, "Фоны главного экрана", Wallpapers.list().size() + " фонов с медленным движением камеры");
    }

    Page saver() {
        return new Page("Заставка", r -> saverArt(), () -> rows(
                pchoiceS("Тип", "saverType", new Object[][]{{"aerial", "Аэросъёмка Apple"}, {"photos", "Фоны главного экрана"}}, null, null),
                pchoiceI("Начинать через", "saverAfter", new Object[][]{{0, "Никогда"}, {2, "2 минуты"}, {5, "5 минут"}, {10, "10 минут"}, {15, "15 минут"}, {30, "30 минут"}}, null, null),
                R("Просмотр").act(() -> h.startSaver(false)),
                head("Аэросъёмка"),
                pchoiceI("Сменять видео", "saverEvery", new Object[][]{{60, "Каждую минуту"}, {120, "Каждые 2 минуты"}, {300, "Каждые 5 минут"}, {0, "Когда видео закончится"}}, null, null),
                R("Темы").val(() -> {
                    List<String> on = Prefs.list("saverCats");
                    List<String[]> cs = cats();
                    List<String> names = new ArrayList<>();
                    for (String[] c : cs) if (on.contains(c[0])) names.add(c[1]);
                    return names.size() == cs.size() ? "Все" : String.join(", ", names);
                }).go("themes").dim(() -> !"aerial".equals(Prefs.s("saverType"))),
                ptog("Показывать время", "saverClock"),
                ptog("Показывать названия мест", "saverCaps"),
                foot(!Wallpapers.AERIALS.isEmpty() ? "Видео Apple TV Aerial идут прямо с сервера Apple. Без интернета заставка показывает фоны главного экрана."
                        : "Список видео Apple ещё не скачан: его делает tools/aerials.py. Пока заставка показывает фоны главного экрана.")));
    }

    Page themes() {
        return new Page("Темы", r -> {
            if (r == null || r.cat == null) return null;
            List<String> one = new ArrayList<>();
            one.add(r.cat);
            return imgArt(Wallpapers.catPic(r.cat), "cat:" + r.cat, r.t.get(), aerCount(one) + " видео");
        }, () -> {
            List<Row> rs = new ArrayList<>();
            List<String[]> cs = cats();
            for (String[] c : cs) {
                String k = c[0];
                List<String> one = new ArrayList<>();
                one.add(k);
                Row r = R(c[1]).vs(aerCount(one) + " видео").ck(() -> Prefs.list("saverCats").contains(k)).act(() -> {
                    List<String> on = Prefs.list("saverCats");
                    int known = 0;
                    for (String x : on) for (String[] cc : cs) if (cc[0].equals(x)) known++;
                    if (on.contains(k) && known == 1) {
                        Sounds.error();
                        h.toast("Нужна хотя бы одна тема", "Иначе заставке нечего показывать", null, "saver");
                        return;
                    }
                    if (on.contains(k)) on.remove(k); else on.add(k);
                    Prefs.set("saverCats", on);
                });
                r.cat = k;
                rs.add(r);
            }
            rs.add(foot("Заставка случайно перебирает видео выбранных тем."));
            return rs;
        });
    }

    Page remotes() {
        return new Page("Пульты и устройства", r -> G("remote", C_GRAPHITE), () -> rows(
                head("Пульт"),
                R("Кнопка «Домой»").vs("Главный экран"),
                R("Двойное нажатие «Домой»").vs("Переключатель приложений"),
                R("Долгое нажатие OK").vs("Меню значка"),
                R("Кнопка «Меню»").vs("Пункт управления"),
                head("Устройства"),
                sys("Bluetooth, пульты и геймпады…", "bluetooth", ""),
                foot("Подключение пультов, наушников и геймпадов делает Android.")));
    }

    boolean isHidden(String id) { return Prefs.list("hidden").contains(id); }

    Page apps() {
        return new Page("Приложения", r -> r != null && r.app != null ? appArt(r.app) : G("apps", C_INDIGO), () -> {
            List<Row> rs = new ArrayList<>();
            rs.add(head("На этом ТВ"));
            for (String id : h.appOrder()) {
                Apps.App a = h.byId.get(id);
                if (a == null) continue;
                Row r = R(a.name).id("app:" + id).ico(() -> h.art(a)).val(() -> isHidden(id) ? "Скрыто" : "").goP(() -> app(id));
                r.app = a;
                rs.add(r);
            }
            rs.add(foot("Скрытые приложения не видны на главном экране, но остаются на ТВ. Порядок меняется на главном экране: удерживайте OK на значке."));
            rs.add(sys("Все приложения Android…", "apps", ""));
            return rs;
        });
    }

    Page app(String id) {
        Apps.App a = h.byId.get(id);
        if (a == null) return apps();
        return new Page(a.name, r -> appArt(a), () -> {
            List<Row> rs = new ArrayList<>();
            if (!"settings".equals(id)) rs.add(R("Открыть").act(() -> h.launchFromSettings(id)));
            rs.add(tog("Показывать на главном экране", () -> !isHidden(id), v -> h.setHidden(id, !v)));
            rs.add(R("Поставить первым").act(() -> { h.moveFirst(id); h.toast(a.name, "Теперь первое на главном экране", h.art(a), null); }));
            if (a.pkg != null && !a.pkg.isEmpty()) {
                rs.add(head("Сведения"));
                rs.add(R("Пакет").vs(a.pkg));
                rs.add(sys("О приложении в Android…", "appinfo", a.pkg));
                rs.add(foot("В Android можно остановить приложение, очистить его кэш или удалить его."));
            }
            return rs;
        });
    }

    String netValue() {
        Device.Net n = net();
        return !n.connected ? "Нет подключения" : "ethernet".equals(n.type) ? "Ethernet" : n.ssid != null ? n.ssid : "Подключено";
    }

    void testNet() {
        netTest = true;
        refreshTop();
        long t0 = SystemClock.uptimeMillis();
        Wallpapers.Aerial first = Wallpapers.AERIALS.isEmpty() ? null : Wallpapers.AERIALS.get(0);
        new Thread(() -> {
            boolean ok;
            try {
                URL u = new URL(first != null ? "https://sylvan.apple.com/Videos/" + first.file : "https://sylvan.apple.com/");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                ok = c.getResponseCode() > 0;
                c.disconnect();
            } catch (Exception e) {
                ok = false;
            }
            boolean res = ok;
            long ms = SystemClock.uptimeMillis() - t0;
            Tv.later(Math.max(0, 1200 - ms), () -> {
                netTest = false;
                refreshTop();
                if (res && first != null) { Sounds.chime(); Modal.info("Подключение работает", "Интернет доступен, сервер Apple ответил за " + ms + " мс.", "wifi"); }
                else if (res) { Sounds.chime(); Modal.info("Сеть работает", "Устройство в сети.", "wifi"); }
                else {
                    Sounds.error();
                    Modal.info("Нет подключения к интернету", net().connected ? "Локальная сеть есть, но сервер Apple не ответил." : "Устройство не подключено к сети.", "wifi");
                }
            });
        }, "tvos-nettest").start();
    }

    Page network() {
        return new Page("Сеть", r -> G("wifi", C_BLUE), () -> {
            Device.Net n = net();
            List<Row> rs = new ArrayList<>();
            rs.add(head("Подключение"));
            rs.add(R("ethernet".equals(n.type) ? "Ethernet" : "Wi-Fi").val(this::netValue).go("wifi").id("wifi"));
            rs.add(head("Сведения"));
            rs.add(R("IP-адрес").vs(n.ip != null ? n.ip : "—"));
            if ("wifi".equals(n.type) && n.connected) {
                int s = Math.max(0, Math.min(3, n.signal));
                rs.add(R("Сигнал").vs(SIG[s]).gl2(() -> s));
                rs.add(R("Частота").vs(n.freqMHz > 0 ? (n.freqMHz > 4000 ? "5 ГГц" : "2,4 ГГц") : "—"));
                if (n.linkMbps > 0) rs.add(R("Скорость канала").vs(n.linkMbps + " Мбит/с"));
            }
            rs.add(netTest ? R("Проверка подключения…").spin() : R("Проверить подключение").act(this::testNet));
            rs.add(sys("Настройки сети Android…", "wifi", ""));
            return rs;
        });
    }

    Page wifi() {
        return new Page("Wi-Fi", r -> G("wifi", C_BLUE), () -> {
            Device.Net n = net();
            boolean w = "wifi".equals(n.type) && n.connected;
            int s = Math.max(0, Math.min(3, n.signal));
            List<Row> rs = new ArrayList<>();
            rs.add(head("Подключено"));
            Row cur = R(netValue()).ckl(() -> n.connected);
            if (w) cur.gl2(() -> s);
            rs.add(cur);
            if (w) rs.add(R("Сигнал").vs(SIG[s]));
            rs.add(gap());
            rs.add(sys("Выбрать другую сеть…", "wifi", ""));
            rs.add(foot("Сети, пароли и прокси настраивает Android: так надёжнее, и лаунчеру не нужны лишние разрешения. Имя сети видно, если лаунчеру разрешён доступ к местоположению (tools/tv.sh делает это при установке)."));
            return rs;
        });
    }

    Page access() {
        return new Page("Универсальный доступ", r -> G("access", C_ACCESS), () -> rows(
                head("Зрение"),
                tog("VoiceOver", () -> Prefs.b("voice"), v -> { Prefs.set("voice", v); if (v) Tv.later(50, () -> OS.speak("VoiceOver включён")); }),
                ptog("Жирный шрифт", "bold"),
                ptog("Понижение прозрачности", "solid"),
                ptog("Увеличение контраста", "contrast"),
                foot("VoiceOver читает вслух выбранный пункт. Понижение прозрачности делает «жидкое стекло» сплошным: так быстрее работает ТВ с 2 ГБ памяти."),
                head("Движение"),
                ptog("Уменьшение движения", "still"),
                foot("Значки не покачиваются и не наклоняются, экраны сменяются плавным затуханием."),
                head("Слух"),
                ptog("Субтитры по умолчанию", "subs")));
    }

    private List<Device.Pkg> disabledPkgs;

    Page privacy() {
        // the disabled packages are read in the background (hundreds of them take a while); the page fills in after
        Tv.bg(() -> Device.disabled(h.act), l -> { if (l != null) { disabledPkgs = l; refreshTop(); } });
        return new Page("Конфиденциальность и безопасность", r -> G("hand", C_BLUE), () -> {
            List<Device.Pkg> off = disabledPkgs != null ? disabledPkgs : new ArrayList<>();
            List<Row> rs = new ArrayList<>();
            rs.add(head("Отключено на этом ТВ"));
            if (disabledPkgs == null) rs.add(R("Загрузка…").dim(() -> true));
            else if (off.isEmpty()) rs.add(R("Ничего не отключено").dim(() -> true));
            for (Device.Pkg p : off) rs.add(R(p.name != null && !p.name.isEmpty() ? p.name : p.pkg).sub(p.pkg).two().vs("Выкл."));
            rs.add(foot("Отключённые пакеты не запускаются и не занимают память. Как убрать рекламу, телеметрию и лишние службы — в optimization/README.md репозитория."));
            rs.add(sys("Разрешения приложений…", "apps", ""));
            return rs;
        });
    }

    void restart() {
        Modal.alert("Перезапустить лаунчер?", "Главный экран загрузится заново. Настройки и порядок приложений сохранятся.", null, "reset",
                new String[]{"Перезапустить", "Отменить"}, new boolean[]{false, false}, 1, 0, i -> { if (i == 0) h.act.restart(); });
    }

    Page system() {
        return new Page("Система", r -> tvArt(), () -> rows(
                R("Об этом устройстве").go("about"),
                R("Производительность").vs("Нативный интерфейс").go("perf"),
                sys("Обновление ПО…", "update", ""),
                head("Обслуживание"),
                R("Перезапустить лаунчер").act(this::restart),
                R("Сбросить").go("reset"),
                sys("Все настройки Android…", "settings", ""),
                gap(),
                R("Режим сна").act(h::sleepNow)));
    }

    Page perf() {
        return new Page("Производительность", r -> G("speed", C_GREEN, "Нативный интерфейс", "Рисует сам Android, без браузера внутри"), () -> {
            Device.Info d = dev();
            return rows(
                    head("Отрисовка"),
                    R("Интерфейс").vs("Нативный Android"),
                    foot("Лаунчер рисуется обычными средствами Android, без встроенного браузера: перерисовывается только то, что меняется, «стекло» сделано из заранее размытого фона, а в простое лаунчер не рисует ни одного кадра. Пока открыто другое приложение, он отпускает фото фона и останавливает свои таймеры."),
                    head("Это устройство"),
                    R("Ядра процессора").vs(d.cores > 0 ? String.valueOf(d.cores) : "—"),
                    R("Оперативная память").vs(d.ramMB > 0 ? gb(d.ramMB) + (d.ramFreeMB > 0 ? " · свободно " + gb(d.ramFreeMB) : "") : "—"),
                    head("Проверка"),
                    ptog("Счётчик кадров", "fps"),
                    foot("В углу экрана: кадры в секунду, самый долгий кадр и число рывков (кадров дольше 25 мс)."));
        });
    }

    static String cores(int n) {
        String w = n % 10 == 1 && n % 100 != 11 ? "ядро" : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? "ядра" : "ядер";
        return n + " " + w;
    }

    Page about() {
        return new Page("Об этом устройстве", r -> tvArt(), () -> {
            info = null;
            Device.Info d = dev();
            Device.Net n = net();
            List<Row> rs = new ArrayList<>();
            rs.add(R("Название").vs(d.name != null ? d.name : "—"));
            String model = ((d.maker != null ? d.maker : "") + " " + (d.model != null ? d.model : "")).trim();
            rs.add(R("Модель").vs(model.isEmpty() ? "—" : model));
            rs.add(R("Система").vs(d.android != null ? "Android " + d.android : "—"));
            rs.add(R("Процессор").vs(d.cores > 0 ? cores(d.cores) + (d.hardware != null ? " · " + d.hardware : "") : "—"));
            rs.add(R("Оперативная память").vs(d.ramMB > 0 ? gb(d.ramMB) + (d.ramFreeMB > 0 ? " · свободно " + gb(d.ramFreeMB) : "") : "—"));
            if (d.storageTotalGB > 0) rs.add(R("Хранилище").vs("свободно " + num(d.storageFreeGB) + " из " + num(d.storageTotalGB) + " ГБ"));
            rs.add(R("Экран").vs(d.display != null ? d.display : "—"));
            rs.add(R("Интерфейс").vs("Нативный Android"));
            rs.add(head("Сеть"));
            rs.add(R("Подключение").val(this::netValue));
            rs.add(R("IP-адрес").vs(n.ip != null ? n.ip : "—"));
            rs.add(head("Лаунчер"));
            rs.add(R("Версия").vs(d.appVersion != null && !d.appVersion.isEmpty() ? d.appVersion : "—"));
            rs.add(sys("Об устройстве в Android…", "about", ""));
            return rs;
        });
    }

    Page resetPage() {
        return new Page("Сбросить", r -> G("reset", C_GRAY), () -> rows(
                R("Сбросить настройки лаунчера").act(() -> Modal.alert("Сбросить настройки?",
                        "Порядок и видимость приложений, фон, оформление и все параметры вернутся к исходным.", null, null,
                        new String[]{"Сбросить", "Отменить"}, new boolean[]{true, false}, 1, 1, i -> {
                            if (i != 0) return;
                            Prefs.reset();
                            h.act.restart();
                        })),
                foot("Сам ТВ и его приложения это не затрагивает.")));
    }

    Page devPage() {
        return new Page("Разработчик", r -> G("dev", C_GRAPHITE), () -> {
            Device.Sys s = Device.system(h.act);
            return rows(
                    head("Android"),
                    R("Отладка по ADB").vs(s.adb == null ? "—" : onoff(s.adb)),
                    R("Скорость анимаций").vs(s.anim == null ? "—" : num(s.anim) + "x"),
                    R("Главный экран").vs(s.home != null ? s.home : "—"),
                    sys("Параметры разработчика…", "developer", ""),
                    foot("Анимации Android быстрее всего на 0,5x. Как это включить и что ещё ускоряет ТВ — в optimization/README.md."));
        });
    }

    // =====================================================================================================
    // views
    // =====================================================================================================

    static final int LIST_X = 960, LIST_Y = 138, LIST_W = 880, LIST_H = Tv.H - 138, IN_X = 34, IN_Y = 30, ROW_W = 812, RM = 90;
    private static final float V = 942 - 30;

    Settings(Home h) {
        this.h = h;
        Context c = h.act;
        root = new Views.Abs(c);
        root.setPivotX(Tv.W / 2f);
        root.setPivotY(Tv.H / 2f);
        bg = root.add(new Views.Box(c, (cv, v) -> drawBg(cv)), 0, 0, Tv.W, Tv.H);
        titleV = root.add(new Views.Box(c, (cv, v) -> drawTitle(cv, v)), 0, 40, Tv.W, 90);
        for (int i = 0; i < 2; i++) {
            arts[i] = root.add(new ArtView(c), 90, 150, 820, 860);
            arts[i].setAlpha(0);
            arts[i].setVisibility(View.INVISIBLE);
            arts[i].setScaleX(.94f);
            arts[i].setScaleY(.94f);
        }
        pagesHost = root.add(new Views.Abs(c), LIST_X, LIST_Y, LIST_W, LIST_H);
        pagesHost.setClipChildren(false); // each page clips itself, leaving room for the focused row's shadow
        root.setVisibility(View.INVISIBLE);
    }

    boolean isOpen() { return open; }

    boolean covers() { return open && run == null && root.getAlpha() >= 0.999f; }

    // ---------- background: the blurred wallpaper under a frost, baked small ----------

    private Bitmap bgA, bgB;
    private float bgFade = 1;
    private Motion.Run bgRun;
    private Bitmap bgSrc;
    private boolean bgDark;

    private void syncBg(boolean animate) {
        Bitmap src = h.blur48;
        boolean dark = Theme.dark;
        if (src == bgSrc && dark == bgDark && bgA != null) return;
        bgSrc = src;
        bgDark = dark;
        Tv.bg(() -> bakeBg(src, dark), b -> {
            if (b == null || src != bgSrc || dark != bgDark) return;
            if (bgRun != null) bgRun.cancel();
            if (!animate || bgA == null || !open) {
                bgA = b;
                bgB = null;
                bgFade = 1;
                bg.invalidate();
                return;
            }
            if (bgB != null) bgA = bgB;
            bgB = b;
            bgFade = 0;
            bgRun = Motion.run(800, Tv.EASE, k -> { bgFade = k; bg.invalidate(); }).then(() -> { bgA = bgB; bgB = null; bgFade = 1; bg.invalidate(); });
        });
    }

    private static Bitmap bakeBg(Bitmap blur, boolean dark) {
        int w = 480, ht = 270;
        Bitmap b = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(dark ? 0xff111114 : 0xffd9dbe0);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (blur != null) {
            c.save();
            c.scale(1.08f, 1.08f, w / 2f, ht / 2f);
            Wallpapers.drawCover(c, blur, 0, 0, w, ht, p);
            c.restore();
        }
        p.setColor(0xff000000);

        p.setShader(new LinearGradient(0, 0, 0, ht, dark ? Tv.rgba(14, 14, 18, .62f) : Tv.rgba(246, 246, 250, .66f),
                dark ? Tv.rgba(8, 8, 10, .76f) : Tv.rgba(236, 237, 242, .78f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, ht, p);
        b.setHasAlpha(false);
        return b;
    }

    private final Paint bgP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF FULL = new RectF(0, 0, Tv.W, Tv.H);

    private void drawBg(Canvas c) {
        if (bgA == null) { c.drawColor(Theme.dark ? 0xff111114 : 0xffd9dbe0); return; }
        bgP.setAlpha(255);
        c.drawBitmap(bgA, null, FULL, bgP);
        if (bgB != null) {
            bgP.setAlpha(Math.round(255 * bgFade));
            c.drawBitmap(bgB, null, FULL, bgP);
        }
    }

    // ---------- title: two lines cross-fading ----------

    private final String[] titles = {"", ""};
    private final float[] titleA = {0, 0};
    private final Motion.Val[] titleV2 = {
            new Motion.Val(0, 0.004f, v -> { titleA[0] = v; if (this.titleV != null) this.titleV.invalidate(); }),
            new Motion.Val(0, 0.004f, v -> { titleA[1] = v; if (this.titleV != null) this.titleV.invalidate(); })};
    private int tA;
    private final TextPaint titleP = Tv.text(46, 600, -.4f, 0);

    private void setTitle(String t, boolean instant) {
        if (t == null) t = "";
        if (t.equals(titles[tA]) && titleA[tA] > 0) return;
        int n = 1 - tA;
        titles[n] = t;
        if (instant || !open) {
            titleV2[n].snap(1);
            titleV2[tA].snap(0);
        } else {
            titleV2[n].tween(300, Tv.EASE, 1);
            titleV2[tA].tween(300, Tv.EASE, 0);
        }
        tA = n;
    }

    private void drawTitle(Canvas c, Views.Box v) {
        titleP.setTypeface(Tv.font(600));
        titleP.setTextAlign(Paint.Align.CENTER);
        int col = Theme.label();
        for (int i = 0; i < 2; i++) {
            if (titleA[i] <= 0.004f || titles[i].isEmpty()) continue;
            titleP.setColor(Tv.alpha(col, titleA[i]));
            c.drawText(titles[i], Tv.W / 2f, Tv.baseline(titleP, 54 - 40, Tv.lineNormal(titleP)), titleP);
        }
    }

    // ---------- the picture on the left ----------

    /** The big banners shown on the left, the last few only (each is about 1 MB). */
    private final java.util.LinkedHashMap<String, Bitmap> bigBanners = new java.util.LinkedHashMap<String, Bitmap>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            if (size() <= 4) return false;
            Bitmap b = e.getValue();
            boolean shown = false;
            for (ArtView a : arts) if (a != null && a.pic == b) shown = true;
            if (!shown) Tv.free(b);
            return true;
        }
    };
    private static final Gfx.Shadow TILE_SHADOW = new Gfx.Shadow(0, 30, 60, -20, 104, Tv.black(.45f));
    private static final Gfx.Shadow PREV_SHADOW1 = new Gfx.Shadow(0, 40, 70, -24, 34, Tv.black(.55f));
    private static final Gfx.Shadow PREV_SHADOW2 = new Gfx.Shadow(0, 10, 22, 0, 34, Tv.black(.14f));
    private static final Gfx.Shadow TV_SHADOW = new Gfx.Shadow(0, 40, 70, -24, 14, Tv.black(.6f));

    final class ArtView extends View {
        Art spec;
        Bitmap pic, screen;
        private StaticLayout capL, subL;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final TextPaint capP = Tv.text(38, 600, -.3f, 0), subP = Tv.text(25, 400, 0, 0), letterP = Tv.text(190, 600, 0, 0xffffffff),
                badgeP = Tv.text(21, 600, 0, 0xffffffff), logoP = Tv.text(11, 700, 3, 0xff9a9aa0);
        private final RectF r = new RectF();
        private final Path path = new Path();

        ArtView(Context c) {
            super(c);
            setPivotX(410);
            setPivotY(430);
        }

        @Override
        public boolean hasOverlappingRendering() { return false; } // its cross-fade needs no offscreen copy

        void set(Art s) {
            spec = s;
            pic = null;
            screen = null;
            build();
            if (s != null) {
                if (s.wp != null) Wallpapers.previewAsync(s.wp, b -> { if (spec == s) { pic = b; invalidate(); } });
                else if (s.app != null) {
                    Bitmap b = bigBanners.get(s.app.id);
                    if (b != null) pic = b;
                    else {
                        pic = s.app.banner;
                        Apps.App a = s.app;
                        Tv.bg(() -> Apps.big(a), x -> {
                            if (x == null) return;
                            bigBanners.put(a.id, x);
                            if (spec == s) { pic = x; invalidate(); }
                        });
                    }
                } else if (s.tv) Wallpapers.previewAsync(Wallpapers.current(), b -> { if (spec == s) { screen = b; invalidate(); } });
            }
            invalidate();
        }

        void build() {
            capL = subL = null;
            if (spec == null) return;
            capP.setTypeface(Tv.font(600));
            if (spec.cap != null && !spec.cap.isEmpty()) capL = centered(spec.cap, capP, 760, 0);
            if (spec.sub != null && !spec.sub.isEmpty()) subL = centered(spec.sub, subP, 760, 25 * 1.35f - Tv.lineNormal(subP));
        }

        private float picH() {
            if (spec == null) return 0;
            if (spec.g != null || spec.avaL != null) return 400;
            if (spec.tv) return 470;
            return 405;
        }

        @Override
        protected void onDraw(Canvas c) {
            Art s = spec;
            if (s == null) return;
            float ph = picH();
            float capH = capL == null ? 0 : 46 + capL.getHeight() + (subL == null ? 0 : 10 + subL.getHeight());
            float top = (860 - ph - capH) / 2;
            if (s.g != null || s.avaL != null) drawTile(c, s, (820 - 400) / 2f, top);
            else if (s.tv) drawTv(c, (820 - 700) / 2f, top);
            else drawPreview(c, s, (820 - 720) / 2f, top);
            if (capL != null) {
                float y = top + ph + 46;
                capL.getPaint().setColor(Theme.label());
                c.save();
                c.translate(410 - 380, y);
                capL.draw(c);
                c.restore();
                if (subL != null) {
                    subL.getPaint().setColor(Theme.sec());
                    c.save();
                    c.translate(410 - 380, y + capL.getHeight() + 10);
                    subL.draw(c);
                    c.restore();
                }
            }
        }

        private void drawTile(Canvas c, Art s, float x, float y) {
            boolean round = s.avaL != null;
            float rad = round ? 200 : 104;
            TILE_SHADOW.draw(c, x, y, x + 400, y + 400, 1, true);
            r.set(x, y, x + 400, y + 400);
            p.setColor(0xff000000);

            p.setShader(Gfx.linear(160, x, y, 400, 400, new int[]{s.c1, s.c2}, null));
            c.drawRoundRect(r, rad, rad, p);
            // radial highlight (120% 80% at 30% -10%)
            p.setColor(0xff000000);

            p.setShader(Gfx.radial(x + 120, y - 40, 480, 320, new int[]{Tv.white(.42f), Tv.white(0)}, new float[]{0, .55f}));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            // inset 0 3px 0 rgba(255,255,255,.45) and an inner 2px line
            path.reset();
            Path outer = new Path(), moved = new Path();
            outer.addRoundRect(r, rad, rad, Path.Direction.CW);
            moved.addRoundRect(new RectF(x, y + 3, x + 400, y + 403), rad, rad, Path.Direction.CW);
            path.op(outer, moved, Path.Op.DIFFERENCE);
            p.setColor(Tv.white(.45f));
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Tv.white(.2f));
            RectF in = new RectF(r);
            in.inset(1, 1);
            c.drawRoundRect(in, rad - 1, rad - 1, p);
            p.setStyle(Paint.Style.FILL);
            if (round) {
                letterP.setTypeface(Tv.font(600));
                letterP.setTextAlign(Paint.Align.CENTER);
                c.drawText(s.avaL, x + 200, Tv.center(letterP, y + 200), letterP);
            } else Glyphs.draw(c, s.g, x + 90, y + 90, 220, 0xffffffff, 1.35f);
        }

        private void drawPreview(Canvas c, Art s, float x, float y) {
            r.set(x, y, x + 720, y + 405);
            PREV_SHADOW1.draw(c, r.left, r.top, r.right, r.bottom, 1, true);
            PREV_SHADOW2.draw(c, r.left, r.top, r.right, r.bottom, 1, true);
            p.setShader(null);
            p.setColor(0xff222222);
            c.drawRoundRect(r, 34, 34, p);
            if (pic != null && !pic.isRecycled()) {
                p.setColor(0xff000000);

                p.setShader(Gfx.coverShader(pic, r.left, r.top, r.width(), r.height()));
                c.drawRoundRect(r, 34, 34, p);
                p.setShader(null);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Tv.white(.18f));
            RectF in = new RectF(r);
            in.inset(1, 1);
            c.drawRoundRect(in, 33, 33, p);
            p.setStyle(Paint.Style.FILL);
            if (s.badge != null) {
                badgeP.setTypeface(Tv.font(600));
                float bw = Tv.width(badgeP, s.badge) + 36, bh = Tv.lineNormal(badgeP) + 16;
                RectF b = new RectF(r.left + 24, r.bottom - 22 - bh, r.left + 24 + bw, r.bottom - 22);
                p.setColor(Tv.black(.62f));
                c.drawRoundRect(b, 20, 20, p);
                c.drawText(s.badge, b.left + 18, Tv.center(badgeP, b.centerY()), badgeP);
            }
        }

        private void drawTv(Canvas c, float x, float y) {
            // legs
            p.setColor(0xff000000);

            p.setShader(new LinearGradient(0, y + 404, 0, y + 466, 0xff2c2c30, 0xff18181a, Shader.TileMode.CLAMP));
            for (int side = 0; side < 2; side++) {
                float lx = side == 0 ? x + 110 : x + 700 - 110 - 16;
                c.save();
                c.translate(lx + 8, y + 404);
                c.skew(side == 0 ? -(float) Math.tan(Math.toRadians(14)) : (float) Math.tan(Math.toRadians(14)), 0);
                RectF leg = new RectF(-8, 0, 8, 62);
                path.reset();
                path.addRoundRect(leg, new float[]{0, 0, 0, 0, 6, 6, 6, 6}, Path.Direction.CW);
                c.drawPath(path, p);
                c.restore();
            }
            p.setShader(null);
            r.set(x, y, x + 700, y + 404);
            TV_SHADOW.draw(c, r.left, r.top, r.right, r.bottom, 1, true);
            p.setColor(0xff0c0c0e);
            c.drawRoundRect(r, 14, 14, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0xff333333);
            RectF in = new RectF(r);
            in.inset(.75f, .75f);
            c.drawRoundRect(in, 13, 13, p);
            p.setStyle(Paint.Style.FILL);
            RectF scr = new RectF(x + 9, y + 9, x + 691, y + 395);
            p.setColor(0xff1a1a1d);
            c.drawRoundRect(scr, 6, 6, p);
            if (screen != null && !screen.isRecycled()) {
                p.setColor(0xff000000);

                p.setShader(Gfx.coverShader(screen, scr.left, scr.top, scr.width(), scr.height()));
                c.drawRoundRect(scr, 6, 6, p);
            }
            p.setColor(0xff000000);

            p.setShader(Gfx.linear(120, scr.left, scr.top, scr.width(), scr.height(), new int[]{Tv.white(.16f), Tv.white(0)}, new float[]{0, .4f}));
            c.drawRoundRect(scr, 6, 6, p);
            p.setShader(null);
            Device.Info d = dev();
            logoP.setTextAlign(Paint.Align.CENTER);
            c.drawText(d.maker != null ? d.maker.toUpperCase() : "", x + 350, Tv.baseline(logoP, y + 409, Tv.lineNormal(logoP)), logoP);
        }
    }

    static StaticLayout centered(String text, TextPaint p, int width, float extra) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, width).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(Math.max(0, extra), 1).setIncludePad(false).build();
    }

    private int aA;
    private String artKey;

    private Art resolveArt(Entry e) {
        for (Entry x = e; x != null; x = x.parent) {
            if (x.page.art == null) continue;
            Row r = x.idx >= 0 && x.idx < x.rows.size() ? x.rows.get(x.idx) : null;
            Art s = x.page.art.apply(r);
            if (s != null) return s;
        }
        return null;
    }

    private void drawArt(Entry e, boolean force) {
        Art s = resolveArt(e);
        String key = s == null ? "" : s.key;
        if (key.equals(artKey) && !force) return;
        artKey = key;
        ArtView nxt = arts[1 - aA], cur = arts[aA];
        nxt.set(s);
        if (!open) {
            Motion.snap(nxt, Motion.ALPHA, 1);
            Motion.snap(nxt, Motion.SCALE, 1);
            Motion.snap(cur, Motion.ALPHA, 0);
            Motion.snap(cur, Motion.SCALE, .94f);
        } else {
            Motion.fade(nxt, 350, 1);
            Motion.spring(nxt, Motion.SCALE, Motion.PANEL, 1);
            Motion.fade(cur, 350, 0);
            Motion.spring(cur, Motion.SCALE, Motion.PANEL, .94f);
        }
        aA = 1 - aA;
    }

    // =====================================================================================================
    // rows
    // =====================================================================================================

    private static final Gfx.Shadow ROW_SH1 = new Gfx.Shadow(0, 22, 44, -10, 22, Tv.black(.34f));
    private static final Gfx.Shadow ROW_SH2 = new Gfx.Shadow(0, 6, 14, 0, 22, Tv.black(.1f));
    private static final Gfx.Shadow ROW_SH_C = new Gfx.Shadow(0, 20, 40, -10, 22, Tv.black(.4f));
    private static final Gfx.Shadow ICO_SH = new Gfx.Shadow(0, 2, 6, 0, 10, Tv.black(.18f));

    static final class RowView extends View implements Motion.Anim {
        final Row row;
        final int type;
        final boolean first;
        float hgt, f, rangeP;
        boolean on, pressed;
        final Motion.Val vf, vr;
        // bound content
        String title = "", value, sub, key;
        Boolean ck, ckl;
        boolean dim, spin;
        Bitmap ico;
        String[] ava;
        int gl2 = -1;
        StaticLayout foot;
        // layout
        float cklX, avaX, icoX, spinX, titleX, titleW, subX, valueR, gl2X, ckX;
        String titleFit, subFit, valueFit, valueFitOn;
        float valueROn;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rc = new RectF();
        private boolean spinning;

        static final TextPaint T = Tv.text(30, 500, -.2f, 0), SS = Tv.text(22, 400, 0, 0), SV = Tv.text(28, 400, 0, 0),
                HD = Tv.text(25, 500, 0, 0), FT = Tv.text(23, 400, 0, 0), AR = Tv.text(24, 400, 0, 0), AV = Tv.text(26, 600, 0, 0xffffffff);

        RowView(Context c, Row r, boolean first) {
            super(c);
            row = r;
            type = r.type;
            this.first = first;
            setOutlineProvider((ViewOutlineProvider) null);
            vf = new Motion.Val(0, 0.004f, v -> { f = v; invalidate(); });
            vr = new Motion.Val(0, 0.001f, v -> { rangeP = v; invalidate(); });
            bind(r, true);
        }

        float height() {
            switch (type) {
                case HEAD: return (first ? 6 : 34) + Tv.lineNormal(HD) + 10;
                case FOOT: return 6 + (foot == null ? 0 : foot.getHeight()) + 20;
                case GAP: return 34;
                default: return row.two && sub != null && !sub.isEmpty() ? 92 : 74;
            }
        }

        float slot() { return height() + (type == ROW ? 6 : 0); }

        /** Reads the row's values (only here, never while drawing) and lays the row out; true if it changed. */
        boolean bind(Row r, boolean force) {
            if (type == HEAD || type == FOOT) {
                String k = r.text + Tv.bold + Theme.contrast;
                if (!force && k.equals(key)) return false;
                key = k;
                title = r.text;
                if (type == FOOT) {
                    FT.setTypeface(Tv.font(400));
                    foot = StaticLayout.Builder.obtain(title, 0, title.length(), FT, ROW_W - 60).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(23 * 1.38f - Tv.lineNormal(FT), 1).setIncludePad(false).build();
                }
                hgt = height();
                invalidate();
                return true;
            }
            if (type == GAP) { hgt = 34; return false; }
            title = r.t == null ? "" : r.t.get();
            value = r.range != null ? r.range.fmt.apply(r.range.get.getAsInt()) : r.v == null ? null : r.v.get();
            if (value != null && value.isEmpty()) value = null;
            sub = r.sub == null ? null : r.sub.get();
            ck = r.ck == null ? null : r.ck.getAsBoolean();
            ckl = r.ckl == null ? null : r.ckl.getAsBoolean();
            dim = r.dim != null && r.dim.getAsBoolean();
            spin = r.spin != null && r.spin.getAsBoolean();
            ico = r.ico == null ? null : r.ico.get();
            ava = r.ava == null ? null : r.ava.get();
            gl2 = r.gl2 == null ? -1 : r.gl2.getAsInt();
            String k = title + "|" + value + "|" + sub + "|" + ck + "|" + ckl + "|" + dim + "|" + spin + "|" + System.identityHashCode(ico) + "|"
                    + (ava == null ? "" : ava[0] + ava[1]) + "|" + gl2 + "|" + Tv.bold;
            if (r.range != null) {
                float p0 = (r.range.get.getAsInt() - r.range.min) / (float) (r.range.max - r.range.min);
                if (force) vr.snap(p0); else vr.tween(300, Tv.SCROLL, p0);
            }
            if (!force && k.equals(key)) return false;
            key = k;
            hgt = height();
            layoutRow();
            if (spin) startSpin();
            invalidate();
            return true;
        }

        private void layoutRow() {
            T.setTypeface(Tv.font(500));
            float x = 30, right = ROW_W - 30;
            if (ckl != null) { cklX = x; x += 30 + 20; }
            if (ava != null) { avaX = x; x += 56 + 20; }
            if (ico != null) { icoX = x; x += 76 + 20; }
            if (spin) { spinX = x; x += 30 + 20; }
            titleX = x;
            // right side: [value][‹ › when focused][gl2][check]
            float r0 = right;
            if (ck != null) { ckX = r0 - 30; r0 = ckX - (value != null ? 36 : 20); }
            boolean glAfterValue = value != null;
            if (gl2 >= 0 && glAfterValue) { gl2X = r0 - 30; r0 = gl2X - 34; }
            float vw = value == null ? 0 : Math.min(420, Tv.width(SV, value));
            valueR = r0;
            valueFit = value == null ? null : Tv.fit(SV, value, 420);
            float arrW = row.range != null ? Tv.width(AR, "‹ ›") + 32 : 0;
            valueROn = r0 - arrW;
            valueFitOn = valueFit;
            float limit = value != null ? r0 - vw - arrW - 20 - 20 : r0;
            if (gl2 >= 0 && !glAfterValue) limit -= 30 + 34;
            titleW = Math.max(40, limit - titleX);
            if (row.two && sub != null) {
                titleFit = Tv.fit(T, title, titleW);
                subFit = Tv.fit(SS, sub, titleW);
            } else {
                titleFit = Tv.fit(T, title, titleW);
                subFit = null;
                if (sub != null) {
                    float tw = Tv.width(T, titleFit);
                    subX = titleX + tw + 14;
                    float rest = titleX + titleW - subX;
                    subFit = rest > 40 ? Tv.fit(SS, sub, rest) : null;
                }
            }
            if (gl2 >= 0 && !glAfterValue) gl2X = titleX + Tv.width(T, titleFit) + 34;
        }

        void setOn(boolean o, boolean instant) {
            if (on == o) return;
            on = o;
            setTranslationZ(o ? 1 : 0);
            float s = o && !ccoverOn ? 1.055f : 1;
            if (instant) { vf.snap(o ? 1 : 0); Motion.snap(this, Motion.SCALE, s); }
            else { vf.tween(180, Tv.EASE, o ? 1 : 0); Motion.spring(this, Motion.SCALE, Motion.FOCUS, s); }
        }

        void press() {
            Motion.tween(this, Motion.SCALE, 90, Tv.EASE, 1f);
            Tv.later(110, () -> Motion.spring(this, Motion.SCALE, Motion.FOCUS, on && !ccoverOn ? 1.055f : 1));
        }

        void nudge(int dx, int dy) {
            if (Motion.still) return;
            Motion.run(320, Tv.BUMP, k -> {
                float t = Motion.there(k);
                setTranslationX(-RM + dx * 12 * t);
                setTranslationY(dy * 8 * t);
            }).then(() -> { setTranslationX(-RM); setTranslationY(0); });
        }

        private void startSpin() {
            if (spinning) return;
            spinning = true;
            Motion.start(this);
        }

        @Override
        public boolean step(float dt) {
            if (!spin || !isAttachedToWindow() || !isShown()) { spinning = false; return false; }
            invalidate();
            return true;
        }

        @Override
        public boolean hasOverlappingRendering() { return false; }

        @Override
        protected void onDraw(Canvas c) {
            c.translate(RM, RM);
            float W = ROW_W, H = hgt;
            if (type == HEAD) {
                HD.setTypeface(Tv.font(500));
                HD.setColor(Theme.sec());
                c.drawText(title, 30, Tv.baseline(HD, first ? 6 : 34, Tv.lineNormal(HD)), HD);
                return;
            }
            if (type == FOOT) {
                if (foot == null) return;
                foot.getPaint().setColor(Theme.sec());
                c.save();
                c.translate(30, 6 + (23 * 1.38f - Tv.lineNormal(FT)) / 2);
                foot.draw(c);
                c.restore();
                return;
            }
            if (type == GAP) return;
            float fo = f;
            if (fo > 0.004f) {
                if (!ccoverOn) {
                    if (Theme.contrast) ROW_SH_C.draw(c, 0, 0, W, H, fo, true);
                    else { ROW_SH1.draw(c, 0, 0, W, H, fo, true); ROW_SH2.draw(c, 0, 0, W, H, fo, true); }
                }
                p.setColor(Tv.white(fo * (ccoverOn ? .45f : 1)));
                rc.set(0, 0, W, H);
                c.drawRoundRect(rc, 22, 22, p);
                if (Theme.contrast && !ccoverOn) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(4);
                    p.setColor(Tv.alpha(Theme.dark ? 0xffffffff : 0xff1d1d1f, fo));
                    rc.inset(-2, -2);
                    c.drawRoundRect(rc, 24, 24, p);
                    p.setStyle(Paint.Style.FILL);
                }
            }
            float tf = ccoverOn ? 0 : fo;
            int dark = 0xff1d1d1f;
            int tCol = row.red ? Tv.mix(Theme.red(), 0xffd70015, tf) : row.blue ? Tv.mix(Theme.blue(), 0xff0062cc, tf)
                    : dim ? Tv.mix(Theme.sec(), Tv.rgba(29, 29, 31, .6f), tf) : Tv.mix(Theme.label(), dark, tf);
            int secCol = Tv.mix(Theme.sec(), Tv.rgba(29, 29, 31, .56f), tf);
            int ckCol = Tv.mix(Theme.blue(), 0xff007aff, tf);
            float cy = row.range != null ? (H - 8) / 2 : H / 2;
            if (ckl != null && ckl) Glyphs.draw(c, "check", cklX, cy - 15, 30, ckCol, 2.6f);
            if (ava != null) {
                int c1 = Integer.parseInt(ava[1]), c2 = Integer.parseInt(ava[2]);
                p.setColor(0xff000000);

                p.setShader(Gfx.linear(160, avaX, cy - 28, 56, 56, new int[]{c1, c2}, null));
                c.drawCircle(avaX + 28, cy, 28, p);
                p.setShader(null);
                AV.setTypeface(Tv.font(600));
                AV.setTextAlign(Paint.Align.CENTER);
                c.drawText(ava[0].toUpperCase(), avaX + 28, Tv.center(AV, cy), AV);
            }
            if (ico != null && !ico.isRecycled()) {
                rc.set(icoX, cy - 23, icoX + 76, cy + 23);
                ICO_SH.draw(c, rc.left, rc.top, rc.right, rc.bottom, 1, true);
                p.setColor(0xff000000);

                p.setShader(Gfx.coverShader(ico, rc.left, rc.top, rc.width(), rc.height()));
                c.drawRoundRect(rc, 10, 10, p);
                p.setShader(null);
            }
            if (spin) {
                float a = (SystemClock.uptimeMillis() % 1000) / 1000f * 360;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                p.setColor(tf > 0.5f ? Tv.black(.14f) : Theme.ter());
                c.drawCircle(spinX + 15, cy, 13.5f, p);
                p.setColor(tf > 0.5f ? dark : Theme.label());
                rc.set(spinX + 1.5f, cy - 13.5f, spinX + 28.5f, cy + 13.5f);
                c.drawArc(rc, a - 90 - 45, 90, false, p);
                p.setStyle(Paint.Style.FILL);
            }
            T.setTypeface(Tv.font(500));
            T.setColor(tCol);
            if (row.two && sub != null) {
                float lt = Tv.lineNormal(T), ls = Tv.lineNormal(SS), top = cy - (lt + 3 + ls) / 2;
                c.drawText(titleFit, titleX, Tv.baseline(T, top, lt), T);
                SS.setColor(secCol);
                c.drawText(subFit, titleX, Tv.baseline(SS, top + lt + 3, ls), SS);
            } else {
                if (row.ctr) {
                    T.setTextAlign(Paint.Align.CENTER);
                    c.drawText(titleFit, W / 2, Tv.center(T, cy), T);
                    T.setTextAlign(Paint.Align.LEFT);
                } else c.drawText(titleFit, titleX, Tv.center(T, cy), T);
                if (subFit != null) {
                    SS.setColor(secCol);
                    c.drawText(subFit, subX, Tv.center(SS, cy), SS);
                }
            }
            boolean showArr = row.range != null && fo > 0.5f && !ccoverOn;
            if (valueFit != null) {
                SV.setColor(secCol);
                SV.setTextAlign(Paint.Align.RIGHT);
                c.drawText(valueFit, showArr ? valueROn : valueR, Tv.center(SV, cy), SV);
                SV.setTextAlign(Paint.Align.LEFT);
            }
            if (showArr) {
                AR.setColor(Tv.rgba(29, 29, 31, .4f));
                AR.setTextAlign(Paint.Align.RIGHT);
                c.drawText("‹ ›", valueR, Tv.center(AR, cy), AR);
                AR.setTextAlign(Paint.Align.LEFT);
            }
            if (gl2 >= 0) Glyphs.draw(c, Glyphs.wifiSig(gl2), gl2X, cy - 15, 30, secCol, 2);
            if (ck != null && ck) Glyphs.draw(c, "check", ckX, cy - 15, 30, ckCol, 2.6f);
            if (row.range != null) {
                rc.set(30, H - 11 - 6, W - 30, H - 11);
                p.setColor(Tv.alpha(Tv.mix(Theme.fill(), Tv.black(.1f), tf), .9f));
                c.drawRoundRect(rc, 3, 3, p);
                rc.right = rc.left + (W - 60) * Tv.clamp(rangeP, 0, 1);
                p.setColor(Tv.alpha(Tv.mix(Theme.label(), dark, tf), .9f));
                if (rc.width() > 0) c.drawRoundRect(rc, 3, 3, p);
            }
        }
    }

    // =====================================================================================================
    // navigation stack
    // =====================================================================================================

    final class Entry {
        final Page page;
        final Entry parent;
        final Views.Abs view, inner;
        List<Row> rows = new ArrayList<>();
        final List<RowView> els = new ArrayList<>();
        float[] tops = new float[0], hs = new float[0];
        float total, scroll;
        int idx = -1;
        String sig;

        Entry(Page page, Entry parent) {
            this.page = page;
            this.parent = parent;
            view = pagesHost.add(new Views.Abs(h.act), 0, 0, LIST_W, LIST_H);
            view.overlapping = false;
            view.setClipBounds(new android.graphics.Rect(-130, 0, LIST_W + 90, LIST_H));
            inner = view.add(new Views.Abs(h.act), IN_X, IN_Y, ROW_W, 4000);
            inner.overlapping = false;
        }
    }

    private static String sig(List<Row> rows) {
        StringBuilder b = new StringBuilder();
        for (Row r : rows) {
            b.append(r.type).append(r.range != null ? 'R' : '-').append(':').append(r.id == null ? "" : r.id).append(':');
            if (r.type == FOOT || r.type == HEAD) b.append(r.text);
            if (r.type == ROW && r.two && r.sub != null) { String s = r.sub.get(); b.append(s != null && !s.isEmpty() ? '2' : '1'); }
            b.append('|');
        }
        return b.toString();
    }

    private void build(Entry e, List<Row> rows) {
        e.rows = rows;
        e.sig = sig(e.rows);
        e.inner.removeAllViews();
        e.els.clear();
        for (int i = 0; i < e.rows.size(); i++) e.els.add(new RowView(h.act, e.rows.get(i), i == 0));
        geo(e);
        Views.Abs.place(e.inner, IN_X, IN_Y, ROW_W, Math.round(e.total) + 200);
        for (int i = 0; i < e.els.size(); i++) {
            RowView v = e.els.get(i);
            e.inner.add(v, 0, Math.round(e.tops[i]) - RM, ROW_W + 2 * RM, Math.round(e.hs[i]) + 2 * RM);
            v.setTranslationX(-RM);
            v.setPivotX(RM + ROW_W / 2f);
            v.setPivotY(RM + e.hs[i] / 2f);
        }
    }

    /** Row positions are measured once per build, not on every key press. */
    private void geo(Entry e) {
        int n = e.els.size();
        e.tops = new float[n];
        e.hs = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            RowView v = e.els.get(i);
            e.tops[i] = y;
            e.hs[i] = v.height();
            y += v.slot();
        }
        e.total = y;
    }

    private static boolean focusable(Row r) { return r != null && r.type == ROW; }

    private static int firstFocusable(Entry e) {
        for (int i = 0; i < e.rows.size(); i++) if (focusable(e.rows.get(i))) return i;
        return -1;
    }

    private static int clampFocus(Entry e, int i) {
        if (i >= 0 && i < e.rows.size() && focusable(e.rows.get(i))) return i;
        for (int d = 1; d < e.rows.size(); d++) {
            if (i - d >= 0 && i - d < e.rows.size() && focusable(e.rows.get(i - d))) return i - d;
            if (i + d >= 0 && i + d < e.rows.size() && focusable(e.rows.get(i + d))) return i + d;
        }
        return -1;
    }

    private Entry make(Page page, Entry parent) {
        Entry e = new Entry(page, parent);
        build(e, page.rows.get());
        int f = page.focus != null ? page.focus.applyAsInt(e.rows) : -1;
        e.idx = f >= 0 ? f : firstFocusable(e);
        return e;
    }

    private Entry top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    private void scroll(Entry e, boolean instant) {
        if (e.idx < 0 || e.idx >= e.els.size()) return;
        float t = e.tops[e.idx], hh = e.hs[e.idx], s = e.scroll;
        if (t - s < 160) s = t - 160;
        if (t + hh - s > V - 200) s = t + hh - (V - 200);
        s = Math.max(0, Math.min(s, Math.max(0, e.total - V + 90)));
        if (e.idx == firstFocusable(e)) s = 0;
        e.scroll = s;
        if (instant) Motion.snap(e.inner, Motion.TY, -s);
        else Motion.spring(e.inner, Motion.TY, Motion.SCROLL, -s);
    }

    private void paint(Entry e, boolean quiet, boolean instant) {
        for (int i = 0; i < e.els.size(); i++) e.els.get(i).setOn(i == e.idx, instant);
        scroll(e, instant);
        if (e == top()) {
            drawArt(e, false);
            if (!quiet) say(e);
        }
    }

    private void say(Entry e) {
        if (!open || e.idx < 0 || e.idx >= e.rows.size()) return; // not while pages are built in the background
        Row r = e.rows.get(e.idx);
        RowView v = e.els.get(e.idx);
        String val = v.value;
        boolean ck = Boolean.TRUE.equals(v.ck) || Boolean.TRUE.equals(v.ckl);
        OS.speak(v.title + (val != null ? ", " + val : "") + (ck ? ", выбрано" : ""));
    }

    private void refresh(Entry e) {
        if (e == null) return;
        List<Row> rows = e.page.rows.get();
        if (!sig(rows).equals(e.sig)) {
            int keep = e.idx;
            build(e, rows);
            e.idx = clampFocus(e, Math.min(keep, e.rows.size() - 1));
            paint(e, true, true);
        } else {
            e.rows = rows;
            for (int i = 0; i < rows.size(); i++) {
                RowView v = e.els.get(i);
                v.bind(rows.get(i), false);
            }
            scroll(e, false);
        }
        if (e == top()) {
            setTitle(e.page.title.get(), false);
            drawArt(e, false);
        }
    }

    void refreshTop() { if (open) refresh(top()); }

    private void push(Page page, boolean instant) {
        Entry cur = top(), e = make(page, cur);
        stack.add(e);
        if (cur != null) {
            if (instant) { Motion.snap(cur.view, Motion.TX, -70); Motion.snap(cur.view, Motion.ALPHA, 0); }
            else { Motion.spring(cur.view, Motion.TX, Motion.PANEL, -70); Motion.fade(cur.view, 300, 0); }
        }
        if (instant) { Motion.snap(e.view, Motion.TX, 0); Motion.snap(e.view, Motion.ALPHA, 1); }
        else {
            Motion.snap(e.view, Motion.TX, 70);
            Motion.snap(e.view, Motion.ALPHA, 0);
            Motion.spring(e.view, Motion.TX, Motion.PANEL, 0);
            Motion.fade(e.view, 300, 1);
        }
        setTitle(page.title.get(), instant);
        paint(e, false, true);
    }

    private void pop() {
        Entry e = stack.remove(stack.size() - 1);
        Motion.spring(e.view, Motion.TX, Motion.PANEL, 70);
        Motion.fade(e.view, 300, 0);
        Tv.later(500, () -> pagesHost.removeView(e.view));
        Entry p = top();
        Motion.spring(p.view, Motion.TX, Motion.PANEL, 0);
        Motion.fade(p.view, 300, 1);
        refresh(p);
        paint(p, false, false);
    }

    private Page resolve(Row r) { return r.go != null ? r.go.get() : page(r.goId); }

    void key(String a) {
        Entry e = top();
        if (e == null) return;
        Row r = e.idx >= 0 && e.idx < e.rows.size() ? e.rows.get(e.idx) : null;
        RowView el = e.idx >= 0 && e.idx < e.els.size() ? e.els.get(e.idx) : null;
        switch (a) {
            case "up":
            case "down": {
                int d = "up".equals(a) ? -1 : 1, n = e.idx + d;
                while (n >= 0 && n < e.rows.size() && !focusable(e.rows.get(n))) n += d;
                if (n < 0 || n >= e.rows.size()) { Sounds.bump(); if (el != null) el.nudge(0, d); return; }
                e.idx = n;
                Sounds.tick();
                paint(e, false, false);
                return;
            }
            case "left":
            case "right": {
                int d = "left".equals(a) ? -1 : 1;
                if (r != null && r.range != null) {
                    Range R = r.range;
                    int v = R.get.getAsInt(), n = Math.max(R.min, Math.min(R.max, v + d * R.step));
                    if (n != v) {
                        R.set.accept(n);
                        Sounds.slide((n - R.min) / (float) (R.max - R.min));
                        refresh(e);
                        OS.speak(R.fmt.apply(n));
                    } else { Sounds.bump(); if (el != null) el.nudge(d, 0); }
                    return;
                }
                Sounds.bump();
                if (el != null) el.nudge(d, 0);
                return;
            }
            case "ok": {
                if (r == null) return;
                if (r.go != null || r.goId != null) {
                    Sounds.select();
                    if (el != null) el.press();
                    push(resolve(r), false);
                    return;
                }
                if (r.act != null) {
                    if (!r.quiet) Sounds.select();
                    if (el != null) el.press();
                    r.act.run();
                    if (open && stack.contains(e)) refresh(e);
                }
                return; // information rows do nothing, as on tvOS
            }
            case "back":
                if (stack.size() > 1) { Sounds.back(); pop(); } else h.exitSettings();
                return;
            default:
        }
    }

    // =====================================================================================================
    // open / close
    // =====================================================================================================

    private static final RectF DEFAULT = new RectF(660, 380, 1260, 718);

    /** Builds the root page while the home screen is idle, so the first opening does not wait for it. */
    void prewarm() {
        if (open || !stack.isEmpty()) return;
        syncBg(false);
        push(rootPage(), true);
    }

    void show(RectF rect, String[] path, boolean instant) {
        info = null;
        syncBg(false);
        if (path != null) reset();
        if (stack.isEmpty()) push(rootPage(), true);
        if (path != null) {
            for (String id : path) {
                Entry e = top();
                int i = -1;
                for (int k = 0; k < e.rows.size(); k++) {
                    Row r = e.rows.get(k);
                    if (id.equals(r.id) || id.equals(r.goId)) { i = k; break; }
                }
                if (i < 0) break;
                e.idx = i;
                paint(e, true, true);
                push(resolve(e.rows.get(i)), true);
            }
        }
        for (int i = 0; i < stack.size(); i++) {
            Entry e = stack.get(i);
            boolean last = i == stack.size() - 1;
            Motion.snap(e.view, Motion.TX, last ? 0 : -70);
            Motion.snap(e.view, Motion.ALPHA, last ? 1 : 0);
        }
        boolean was = open;
        open = true;
        Views.show(root);
        for (Entry e : stack) if (e != top()) refresh(e);
        Entry t = top();
        refresh(t);
        setTitle(t.page.title.get(), true);
        artKey = null;
        drawArt(t, true);
        Motion.snap(arts[aA], Motion.ALPHA, 1);
        Motion.snap(arts[aA], Motion.SCALE, 1);
        Motion.snap(arts[1 - aA], Motion.ALPHA, 0);
        paint(t, true, true);
        OS.recent("settings");
        if (instant || was) {
            stopRun();
            root.setTranslationX(0);
            root.setTranslationY(0);
            root.setScaleX(1);
            root.setScaleY(1);
            root.setAlpha(1);
            h.updateCover();
        } else anim(true, rect, null);
        Row fr = t.idx >= 0 && t.idx < t.rows.size() ? t.rows.get(t.idx) : null;
        OS.speak(t.page.title.get() + ". " + (fr != null && fr.t != null ? fr.t.get() : ""));
    }

    void hide(RectF rect, boolean instant) {
        open = false;
        Runnable done = () -> {
            if (open) return;
            Views.hide(root);
            root.setLayerType(View.LAYER_TYPE_NONE, null);
            h.updateCover();
            trim();
            Wallpapers.trim();
        };
        if (instant) { stopRun(); done.run(); return; }
        anim(false, rect, done);
        h.updateCover();
    }

    private void stopRun() {
        if (run != null) { run.cancel(); run = null; }
        root.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    /** OS.zoom: the whole screen opens out of (or closes into) a rectangle — transform and opacity only. */
    private void anim(boolean opening, RectF rect, Runnable done) {
        stopRun();
        RectF r = rect != null ? rect : DEFAULT;
        float tx = r.centerX() - 960, ty = r.centerY() - 540, sx = r.width() / 1920f, sy = r.height() / 1080f;
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null); // drawn once, then only moved while it zooms
        Runnable end = () -> {
            run = null;
            root.setLayerType(View.LAYER_TYPE_NONE, null);
            if (opening) {
                root.setTranslationX(0); root.setTranslationY(0); root.setScaleX(1); root.setScaleY(1); root.setAlpha(1);
                h.updateCover();
            }
            if (done != null) done.run();
        };
        if (Motion.still) {
            root.setTranslationX(0); root.setTranslationY(0); root.setScaleX(1); root.setScaleY(1);
            run = Motion.run(240, Tv.LINEAR, k -> root.setAlpha(opening ? k : 1 - k)).then(end);
            return;
        }
        Tv.Ease e = opening ? new Tv.Ease(.25f, .1f, .1f, 1) : new Tv.Ease(.4f, 0, .2f, 1);
        run = Motion.run(opening ? 480 : 400, Tv.LINEAR, k -> {
            float p = e.at(k), q = opening ? p : 1 - p;
            root.setTranslationX(tx * (1 - q));
            root.setTranslationY(ty * (1 - q));
            root.setScaleX(sx + (1 - sx) * q);
            root.setScaleY(sy + (1 - sy) * q);
            float a = opening ? Math.min(1, k / .3f) : (k < .55f ? 1 : 1 - (k - .55f) / .45f);
            root.setAlpha(Tv.clamp(a, 0, 1));
        }).then(end);
    }

    // =====================================================================================================
    // hooks for the home screen
    // =====================================================================================================

    void wallpaperChanged() {
        syncBg(true);
        if (open) refresh(top());
        else artKey = null;
    }

    void themeChanged() {
        Theme.read();
        syncBg(open);
        for (Entry e : stack) {
            for (int i = 0; i < e.els.size() && i < e.rows.size(); i++) e.els.get(i).bind(e.rows.get(i), true);
            geo(e);
            Views.Abs.place(e.inner, IN_X, IN_Y, ROW_W, Math.round(e.total) + 200);
            for (int i = 0; i < e.els.size(); i++) {
                RowView v = e.els.get(i);
                v.setPivotY(RM + e.hs[i] / 2f);
                Views.Abs.place(v, 0, Math.round(e.tops[i]) - RM, ROW_W + 2 * RM, Math.round(e.hs[i]) + 2 * RM);
            }
        }
        for (ArtView a : arts) { a.build(); a.invalidate(); }
        titleV.invalidate();
        bg.invalidate();
        if (open && top() != null) scroll(top(), true);
    }

    void ccover(boolean on) {
        ccoverOn = on;
        Entry e = top();
        if (e == null) return;
        for (int i = 0; i < e.els.size(); i++) {
            RowView v = e.els.get(i);
            if (v.on) Motion.spring(v, Motion.SCALE, Motion.FOCUS, on ? 1 : 1.055f);
            v.invalidate();
        }
    }

    /** The app was stopped: let go of the big pictures (they are loaded again when shown). */
    void trim() {
        for (Bitmap b : bigBanners.values()) Tv.free(b);
        bigBanners.clear();
        for (ArtView a : arts) { if (a.pic != null && a.spec != null && a.spec.app != null && a.pic != a.spec.app.banner) a.pic = a.spec.app.banner; }
        artKey = null;
    }

    void reset() {
        for (Entry e : stack) pagesHost.removeView(e.view);
        stack.clear();
        artKey = null;
    }

    /** A still picture of the Settings screen, for the app switcher card. */
    Bitmap snapshot(int w, int ht) {
        if (stack.isEmpty()) {
            push(rootPage(), true);
            Entry t = top();
            drawArt(t, true);
            Motion.snap(arts[aA], Motion.ALPHA, 1);
            Motion.snap(arts[aA], Motion.SCALE, 1);
            Motion.snap(arts[1 - aA], Motion.ALPHA, 0);
        }
        if (bgA == null) syncBg(false);
        root.measure(View.MeasureSpec.makeMeasureSpec(Tv.W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Tv.H, View.MeasureSpec.EXACTLY));
        root.layout(root.getLeft(), root.getTop(), root.getLeft() + Tv.W, root.getTop() + Tv.H);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                // recorded (cheap), then drawn by the GPU straight into a hardware bitmap: no software rendering
                android.graphics.Picture pic = new android.graphics.Picture();
                Canvas c = pic.beginRecording(Math.max(1, w), Math.max(1, ht));
                c.scale(w / (float) Tv.W, ht / (float) Tv.H);
                root.draw(c);
                pic.endRecording();
                return Bitmap.createBitmap(pic, Math.max(1, w), Math.max(1, ht), Bitmap.Config.HARDWARE);
            }
            Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, ht), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(w / (float) Tv.W, ht / (float) Tv.H);
            root.draw(c);
            return b;
        } catch (RuntimeException ex) {
            android.util.Log.w("tvos", "settings snapshot failed", ex);
            return null;
        }
    }
}
