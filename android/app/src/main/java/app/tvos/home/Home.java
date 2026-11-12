package app.tvos.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen (web/index.html): the wallpaper, the dock of app icons on its glass platter, the clock, the
 * top-right buttons with their popovers, Control Center, the wallpaper sheet, the icon menu and moving icons, and
 * the app launch. Settings, the app switcher, the screen saver and the alerts are screens of their own on the
 * same stage.
 */
final class Home {
    static Home I;
    final MainActivity act;
    final Stage stage;

    // ---------- geometry (tvOS 26, large icons: 5 per row; the focused icon stays inside the dock) ----------
    static final int COLS = 5, IW = Tile.IW, IH = Tile.IH, GX = 44;
    static final float LEFT = (Tv.W - (COLS * IW + (COLS - 1) * GX)) / 2f;
    static final int ROW0 = 800, ROW1 = 1050, PITCH = 297;
    static final float FS = Tile.FS;

    static float rowTop(int r) { return r == 0 ? ROW0 : ROW1 + (r - 1) * PITCH; }

    static float px(int i) { return LEFT + (i % COLS) * (IW + GX); }

    static float py(int i) { return rowTop(i / COLS); }

    /**
     * In the grid the dock rises to the top of the screen, whole (as on tvOS); further rows scroll a row at a time,
     * so the dock leaves the screen whole too instead of being cut by its edge.
     */
    static float pageY(int r) {
        if (r == 0) return 0;
        float dockTop = -(766 - 40);
        return r <= 2 ? dockTop : dockTop - (r - 2) * PITCH;
    }

    // ---------- views ----------
    final Views.Box back, shelf, shade, clock, place, barTime, toast, launch, brightV;
    final Views.Abs page, hud;
    final Views.Box platter;
    final BarBtn[] bar = new BarBtn[3];
    final Views.Box[] tips = new Views.Box[3];
    final Pop inputs, wifi, menu;
    final Control cc;
    final Picker picker;
    final Settings settings;
    final Saver saver;
    final Switcher switcher;

    // ---------- state ----------
    String mode = "home";
    int cur, row, lastTop;
    final List<Apps.App> apps = new ArrayList<>();
    final Map<String, Apps.App> byId = new HashMap<>();
    final Map<String, Tile> tiles = new HashMap<>();
    final Map<String, Views.Box> labels = new HashMap<>();
    List<String> order = new ArrayList<>(), list = new ArrayList<>();
    boolean grid, started;

    Home(MainActivity act, Stage stage) {
        I = this;
        this.act = act;
        this.stage = stage;
        android.content.Context c = act;

        // background: the blurred photo under a frost (seen in the grid), the sharp photo on top
        back = stage.add(new Views.Box(c, (cv, v) -> drawBack(cv)), 0);
        shelf = stage.add(new Views.Box(c, (cv, v) -> drawShelf(cv)), 1);
        shelf.setPivotX(Tv.W / 2f);
        shelf.setPivotY(Tv.H / 2f);
        back.setVisibility(View.INVISIBLE);

        page = stage.add(new Views.Abs(c), 2);
        platter = page.add(new Views.Box(c, (cv, v) -> drawPlatter(cv)), 0, 0, Tv.W, 1100);
        page.setPivotX(Tv.W / 2f);
        page.setPivotY(Tv.H / 2f);

        shade = stage.add(new Views.Box(c, (cv, v) -> drawShade(cv)), 3);
        shade.setAlpha(0);
        shade.setVisibility(View.INVISIBLE);

        hud = stage.add(new Views.Abs(c), 4);
        hud.overlapping = false; // fades without an offscreen layer: its pieces do not overlap
        page.overlapping = false;
        clock = hud.add(new Views.Box(c, (cv, v) -> drawClock(cv, v)), 0, 0, 10, 10);
        placeClock();
        place = hud.add(new Views.Box(c, (cv, v) -> drawPlace(cv, v)), 1000, 712, 820, 40);
        place.setAlpha(0);
        barTime = hud.add(new Views.Box(c, (cv, v) -> drawBarTime(cv, v)), 1300, 76, 252, 60);
        barTime.setAlpha(0);
        barTime.setVisibility(View.INVISIBLE);
        String[] keys = {"input", "wifi", "cc"}, names = {"Источники", "Wi-Fi", "Пункт управления"};
        for (int i = 0; i < 3; i++) {
            int x = 1832 - 72 - (2 - i) * 90;
            bar[i] = hud.add(new BarBtn(c, keys[i], x, 70), x - BarBtn.O, 70 - BarBtn.O, 72 + 2 * BarBtn.O, 72 + 2 * BarBtn.O);
            final int k = i;
            int tw = 300, tx = i == 2 ? x + 72 + 6 - tw : x + 36 - tw / 2;
            tips[i] = hud.add(new Views.Box(c, (cv, v) -> drawTip(cv, v, names[k], k == 2)), tx, 70 + 84, tw, 40);
            tips[i].setAlpha(0);
            tips[i].setVisibility(View.INVISIBLE);
        }

        inputs = stage.add(new Pop(c, Pop.INPUTS), 5);
        wifi = stage.add(new Pop(c, Pop.WIFI), 6);
        cc = new Control(this);
        stage.add(cc.root, 7);
        Views.Abs.place(cc.root, Control.CCX - Control.MG, Control.CCY - Control.MG, 430 + 2 * Control.MG, 552 + 2 * Control.MG);
        picker = new Picker(this);
        stage.add(picker.root, 8);
        Views.Abs.place(picker.root, Picker.X - Picker.MG, Picker.Y - Picker.MG, Picker.W + 2 * Picker.MG, Picker.HT + 2 * Picker.MG);
        menu = stage.add(new Pop(c, Pop.MENU), 9);
        launch = stage.add(new Views.Box(c, (cv, v) -> drawLaunch(cv)), 20);
        launch.setPivotX(Tv.W / 2f);
        launch.setPivotY(Tv.H / 2f);
        launch.setVisibility(View.INVISIBLE);
        settings = new Settings(this);
        stage.add(settings.root, 30);
        switcher = new Switcher(this);
        stage.add(switcher.root, 40);
        toast = stage.add(new Views.Box(c, (cv, v) -> drawToast(cv, v)), 60);
        toast.setVisibility(View.INVISIBLE);
        Modal.init(this);
        saver = new Saver(this);
        stage.add(saver.root, 100);
        brightV = stage.add(new Views.Box(c, (cv, v) -> cv.drawColor(0xff000000)), 200);
        brightV.setAlpha(0);
        brightV.setVisibility(View.INVISIBLE);

        Prefs.onAny(this::applyAccess, "bold", "solid", "contrast", "still");
        Prefs.on("look", this::applyLook);
        Prefs.on("clock", () -> { clockKey = null; placeClock(); clock.invalidate(); bakeShelf(); });
        Prefs.on("hour24", this::tick);
        Prefs.on("bright", this::applyBright);
        Prefs.on("hidden", () -> regrid(list.isEmpty() ? null : list.get(cur)));
        Prefs.on("saverAfter", this::armIdle);
        Prefs.on("sleepAfter", this::armIdle);
        Wallpapers.onChange(() -> { applyWallpaper(); if ("picker".equals(mode)) picker.paint(); });
        applyAccess();
        applyBright();
        applyWallpaper();
        loadApps(true);
    }

    // =====================================================================================================
    // apps
    // =====================================================================================================

    void loadApps(boolean first) {
        Tv.bg(() -> Apps.scan(act), (found) -> {
            if (found == null) return;
            setApps(found);
        });
    }

    private void setApps(List<Apps.App> found) {
        String keep = list.isEmpty() || cur >= list.size() ? null : list.get(cur);
        apps.clear();
        apps.addAll(found);
        apps.add(Apps.settingsApp(act));
        byId.clear();
        for (Apps.App a : apps) byId.put(a.id, a);
        // saved order first (apps that are gone drop out), newly installed apps at the end
        order = new ArrayList<>();
        for (String id : Prefs.list("order")) if (byId.containsKey(id) && !order.contains(id)) order.add(id);
        for (Apps.App a : apps) if (!order.contains(a.id)) order.add(a.id);
        // apps seen for the first time: phone-style apps and other home screens start hidden
        List<String> seen = Prefs.list("seen");
        List<String> hidden = Prefs.list("hidden");
        boolean fresh = false, hide = false;
        for (Apps.App a : apps) {
            if (seen.contains(a.id)) continue;
            fresh = true;
            if (!a.tv && !hidden.contains(a.id)) { hidden.add(a.id); hide = true; }
        }
        if (fresh) {
            List<String> all = new ArrayList<>();
            for (Apps.App a : apps) all.add(a.id);
            Prefs.set("seen", all);
        }
        // tiles: reuse the ones that exist, add new ones, drop removed apps
        for (String id : new ArrayList<>(tiles.keySet())) {
            if (!byId.containsKey(id)) {
                page.removeView(tiles.remove(id));
                page.removeView(labels.remove(id));
            }
        }
        for (Apps.App a : apps) {
            Tile t = tiles.get(a.id);
            if (t == null) {
                t = page.add(new Tile(act, a), 0, 0, IW + 2 * Tile.M, IH + 2 * Tile.M);
                tiles.put(a.id, t);
                Views.Box lb = page.add(new Views.Box(act, (cv, v) -> drawLabel(cv, v)), 0, 0, IW + 120, 50);
                lb.tag = a;
                lb.setAlpha(0);
                lb.setVisibility(View.INVISIBLE);
                labels.put(a.id, lb);
            } else t.setApp(a);
        }
        if (hide) Prefs.set("hidden", hidden); // re-grids through the listener
        regrid(keep, true);
        if (!appsLoaded) {
            appsLoaded = true;
            Tv.later(3000, () -> { if ("home".equals(mode) || "bar".equals(mode)) settings.prewarm(); });
            String last = Prefs.s("last");
            if (!last.isEmpty() && byId.containsKey(last)) OS.recent(last);
        }
    }

    private boolean appsLoaded;

    void relist() {
        List<String> hid = Prefs.list("hidden");
        list = new ArrayList<>();
        for (String id : order) if (!hid.contains(id)) list.add(id);
        for (Map.Entry<String, Tile> e : tiles.entrySet()) {
            boolean shown = !hid.contains(e.getKey());
            e.getValue().setVisibility(shown ? View.VISIBLE : View.GONE);
            if (!shown) Views.hide(labels.get(e.getKey()));
        }
    }

    void layout(boolean instant) {
        for (int i = 0; i < list.size(); i++) {
            Tile t = tiles.get(list.get(i));
            t.moveTo(px(i), py(i), instant);
            Views.Box lb = labels.get(list.get(i));
            Motion.snap(lb, Motion.TX, px(i) - 60);
            Motion.snap(lb, Motion.TY, py(i) + 222 - (lb.getAlpha() > 0.5f ? 0 : 10));
        }
    }

    void regrid(String keepId) { regrid(keepId, false); }

    private void regrid(String keepId, boolean instant) {
        unfocusApp();
        relist();
        layout(instant || !started);
        int k = keepId == null ? -1 : list.indexOf(keepId);
        cur = Math.max(0, Math.min(k >= 0 ? k : cur, list.size() - 1));
        lastTop = Math.min(lastTop, Math.min(COLS, list.size()) - 1);
        if (cur / COLS != row) setRow(cur / COLS);
        if ("home".equals(mode)) focusApp(cur, 0, 0);
        started = true;
    }

    Bitmap art(Apps.App a) { return a == null ? null : a.banner; }

    List<String> appOrder() { return new ArrayList<>(order); }

    void setHidden(String id, boolean hide) {
        List<String> h = Prefs.list("hidden");
        h.remove(id);
        if (hide) h.add(id);
        Prefs.set("hidden", h);
    }

    void moveFirst(String id) {
        String k = list.isEmpty() ? null : list.get(cur);
        order.remove(id);
        order.add(0, id);
        Prefs.set("order", order);
        regrid(k);
    }

    void resetOrder() {
        String k = list.isEmpty() ? null : list.get(cur);
        order = new ArrayList<>();
        for (Apps.App a : apps) order.add(a.id);
        Prefs.set("order", order);
        regrid(k);
    }

    // =====================================================================================================
    // focus
    // =====================================================================================================

    Tile curTile() { return list.isEmpty() ? null : tiles.get(list.get(Math.min(cur, list.size() - 1))); }

    void focusApp(int i, int dx, int dy) {
        if (list.isEmpty()) return;
        Tile prev = curTile();
        if (prev != null) { prev.unfocus(); label(prev.app.id, false); }
        cur = Math.max(0, Math.min(i, list.size() - 1));
        Tile t = curTile();
        if (prev != null && prev != t) { prev.setTranslationZ(0); labels.get(prev.app.id).setTranslationZ(0); }
        t.setTranslationZ(2); // drawn above its neighbours without re-laying out the page
        Views.Box lb = labels.get(t.app.id);
        lb.setTranslationZ(2);
        int r = cur / COLS;
        if (r != row) setRow(r);
        if (r == 0) lastTop = cur;
        t.focus(dx, dy);
        label(t.app.id, r > 0); // the name shows under the focused icon in the grid
        OS.speak(t.app.name);
    }

    void unfocusApp() {
        Tile t = curTile();
        if (t == null) return;
        t.unfocus();
        label(t.app.id, false);
    }

    private void label(String id, boolean on) {
        Views.Box lb = labels.get(id);
        if (lb == null) return;
        boolean was = lb.getVisibility() == View.VISIBLE && lb.getAlpha() > 0;
        if (!on && !was) return;
        Motion.fade(lb, 220, on ? 1 : 0);
        float base = py(Math.max(0, list.indexOf(id))) + 222;
        if (on && !was) Motion.snap(lb, Motion.TY, base - 10);
        Motion.tween(lb, Motion.TY, 400, Tv.SCROLL, on ? base : base - 10);
    }

    void setRow(int r) {
        row = r;
        Motion.spring(page, Motion.TY, Motion.SCROLL, pageY(r));
        setGrid(r > 0);
    }

    private final Motion.Val gridT = new Motion.Val(0, 0.003f, this::onGridT);

    private void onGridT(float v) { gridV = v; platter.invalidate(); }
    private float gridV;

    private void setGrid(boolean on) {
        if (grid == on) return;
        grid = on;
        Views.show(back);
        Motion.tween(shelf, Motion.ALPHA, 550, Tv.EASE, on ? 0 : 1).then(this::coverBack);
        Motion.spring(shelf, Motion.TY, Motion.SCROLL, on ? -40 : 0);
        Motion.spring(shelf, Motion.SCALE, Motion.SCROLL, on ? 1.03f : 1);
        Motion.spring(hud, Motion.TY, Motion.SCROLL, on ? -140 : 0);
        Motion.tween(hud, Motion.ALPHA, 400, Tv.EASE, on ? 0 : 1);
        gridT.tween(500, Tv.EASE, on ? 1 : 0);
        boolean lg = on && !Theme.dark;
        for (Tile t : tiles.values()) if (t.lightGrid != lg) { t.lightGrid = lg; t.invalidate(); }
    }

    /** The blurred background is only drawn while the sharp photo does not cover the screen. */
    void coverBack() {
        boolean need = grid || shelf.getAlpha() < 0.999f || shelfFade < 1 || shelfB != null && shelfFade < 1;
        if (need) Views.show(back); else Views.hide(back);
    }

    void bumpApp(int dx, int dy) {
        Sounds.bump();
        Tile t = curTile();
        if (t != null) t.bump(dx, dy);
    }

    // =====================================================================================================
    // wallpaper
    // =====================================================================================================

    Bitmap blur48, glass26, midBlur;
    // back from an app: the photo comes into focus (blurred -> slightly soft -> sharp) while it settles from 1.08 to 1
    private boolean pulling;
    private float pullK, pullScale = 1;
    private Motion.Run pullRun;
    private Bitmap shelfA, shelfB, backA, backB;
    private float shelfFade = 1, backFade = 1, luma = 0.4f;
    private String wpId;
    private Motion.Run shelfRun, backRun;
    private int wpGen;

    void applyWallpaper() {
        String id = Wallpapers.current();
        int gen = ++wpGen;
        boolean center = "center".equals(Prefs.s("clock"));
        boolean dark = Theme.dark;
        Tv.bg(() -> {
            Bitmap small = Wallpapers.small(id);
            Object[] r = new Object[6];
            r[0] = Wallpapers.blurred(small, 48, true);
            r[1] = Wallpapers.blurred(small, 26, true);
            r[2] = Wallpapers.luma(small);
            small.recycle();
            Bitmap full = Wallpapers.full(id);
            r[4] = bake(full, center, (Float) r[2] > 0.55f);
            r[3] = midBlur(full);
            Tv.free(full); // 8 MB: not kept, the clock's shade is baked from a fresh decode if it moves
            r[5] = backImage((Bitmap) r[0], dark);
            return r;
        }, r -> {
            if (r == null || gen != wpGen) return;
            boolean same = id.equals(wpId);
            wpId = id;
            previewing = null;
            blur48 = (Bitmap) r[0];
            glass26 = (Bitmap) r[1];
            midBlur = (Bitmap) r[3];
            luma = (Float) r[2];
            Gfx.setGlass(glass26);
            Gfx.adaptGlass(luma > 0.55f);
            swapShelf((Bitmap) r[4], same);
            swapBack((Bitmap) r[5], same);
            platterGlass.adaptive();
            invalidateGlass();
            settings.wallpaperChanged();
            Wallpapers.W w = Wallpapers.get(id);
            placeText = w == null ? "" : w.name + " · " + w.place;
            place.invalidate();
            Motion.fade(place, 600, 1);
            Tv.cancel(hidePlace);
            Tv.later(6000, hidePlace);
        });
    }

    private final Runnable hidePlace = this::hidePlaceNow;

    private void hidePlaceNow() { Motion.fade(place, 600, 0); }

    /** Re-bakes the photo with the clock's shade (clock position changed) without a cross-fade. */
    void bakeShelf() {
        if (wpId == null) return;
        String id = previewing != null ? previewing : wpId;
        boolean center = "center".equals(Prefs.s("clock"));
        float l = luma;
        Tv.bg(() -> { Bitmap f = Wallpapers.full(id); Bitmap b = bake(f, center, l > 0.55f); Tv.free(f); return b; },
                b -> { if (b != null) swapShelf(b, true); });
    }

    /**
     * The sharp photo with the shade under the clock baked in (web #shelf + #shelfShade): one opaque bitmap, drawn
     * as a single full-screen quad. Kept in GPU memory only (a hardware bitmap) where Android allows.
     */
    private static Bitmap bake(Bitmap full, boolean center, boolean bright) {
        Bitmap b = Bitmap.createBitmap(Tv.W, Tv.H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Wallpapers.drawCover(c, full, 0, 0, Tv.W, Tv.H, p);
        float tl = bright ? .46f : .30f;
        p.setShader(center ? Gfx.radial(960, 1080 * .12f, 1100, 470, new int[]{Tv.black(tl), Tv.black(0)}, new float[]{0, .72f})
                : Gfx.radial(0, 0, 1100, 520, new int[]{Tv.black(tl), Tv.black(0)}, new float[]{0, .70f}));
        c.drawRect(0, 0, Tv.W, Tv.H, p);
        p.setShader(new LinearGradient(0, 0, 0, Tv.H * .2f, Tv.black(.14f), Tv.black(0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, Tv.W, Tv.H * .2f, p);
        p.setShader(new LinearGradient(0, Tv.H, 0, Tv.H * .68f, Tv.black(.26f), Tv.black(0), Shader.TileMode.CLAMP));
        c.drawRect(0, Tv.H * .68f, Tv.W, Tv.H, p);
        if (Build.VERSION.SDK_INT >= 26) {
            Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
            if (hw != null) { b.recycle(); return hw; }
        }
        return b;
    }

    /** The photo slightly out of focus (640x360): the middle step when the picture comes back into focus. */
    private static Bitmap midBlur(Bitmap full) {
        Bitmap b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Wallpapers.drawCover(new Canvas(b), full, -8, -8, 656, 376, new Paint(Paint.FILTER_BITMAP_FLAG));
        Gfx.blur(b, 2.4f);
        return b;
    }

    /** The grid background: the blurred photo (scaled 1.08) with the frost over it, baked small (it is all soft). */
    private static Bitmap backImage(Bitmap blur, boolean dark) {
        int w = blur.getWidth(), h = blur.getHeight();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        c.save();
        c.scale(1.08f, 1.08f, w / 2f, h / 2f);
        c.drawBitmap(blur, 0, 0, p);
        c.restore();
        frost(c, w, h, dark, p);
        return b;
    }

    static void frost(Canvas c, float w, float h, boolean dark, Paint p) {
        p.setShader(null);
        if (dark) {
            p.setColor(Tv.rgba(14, 14, 17, .5f));
            c.drawRect(0, 0, w, h, p);
            p.setShader(Gfx.linear(118, 0, 0, w, h, new int[]{Tv.white(.07f), Tv.white(0)}, new float[]{0, .4f}));
        } else {
            p.setColor(Tv.rgba(246, 246, 249, .44f));
            c.drawRect(0, 0, w, h, p);
            p.setShader(Gfx.linear(118, 0, 0, w, h, new int[]{Tv.white(.36f), Tv.white(0), Tv.white(0), Tv.white(.18f)}, new float[]{0, .4f, .7f, 1}));
        }
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private void swapShelf(Bitmap b, boolean instant) {
        if (shelfRun != null) shelfRun.cancel();
        if (shelfA == null && blur48 != null && wpShownOnce) {
            // back from an app: the blurred copy stood in while the photo was decoded; now it comes into focus
            shelfA = b;
            shelfB = null;
            shelfFade = 1;
            startPull();
            return;
        }
        wpShownOnce = true;
        if (instant || shelfA == null) {
            if (shelfB != null && shelfB != b) Tv.free(shelfB);
            if (shelfA != null && shelfA != b) Tv.free(shelfA);
            shelfA = b;
            shelfB = null;
            shelfFade = 1;
            shelf.invalidate();
            coverBack();
            return;
        }
        if (shelfB != null) { Tv.free(shelfA); shelfA = shelfB; }
        shelfB = b;
        shelfFade = 0;
        Views.show(back);
        shelfRun = Motion.run(1100, Tv.EASE, k -> { shelfFade = k; shelf.invalidate(); }).then(() -> {
            Bitmap old = shelfA;
            shelfA = shelfB;
            shelfB = null;
            shelfFade = 1;
            if (old != null && old != shelfA) Tv.free(old); // a decoded photo is ~8 MB: let it go at once
            shelf.invalidate();
            coverBack();
        });
    }

    private boolean wpShownOnce;

    private void swapBack(Bitmap b, boolean instant) {
        if (backRun != null) backRun.cancel();
        if (instant || backA == null) {
            backA = b;
            backB = null;
            backFade = 1;
            back.invalidate();
            return;
        }
        if (backB != null) backA = backB;
        backB = b;
        backFade = 0;
        backRun = Motion.run(1000, Tv.EASE, k -> { backFade = k; back.invalidate(); }).then(() -> {
            backA = backB;
            backB = null;
            backFade = 1;
            back.invalidate();
        });
    }

    /** Wallpaper sheet: everything but the sheet fades away. */
    void picking(boolean on) {
        Motion.fade(page, 400, on ? 0 : 1);
        if (on) Motion.fade(place, 400, 0);
    }

    private String previewing;

    /** The sharp photo of another wallpaper while browsing the sheet (the shade stays as it is). */
    void previewWallpaper(String id) {
        if (id.equals(previewing) || id.equals(wpId) && previewing == null) return;
        previewing = id.equals(wpId) ? null : id;
        boolean center = "center".equals(Prefs.s("clock"));
        float l = luma;
        int gen = wpGen;
        Tv.bg(() -> {
            Bitmap f = Wallpapers.full(id);
            Bitmap b = bake(f, center, l > 0.55f);
            Tv.free(f);
            return b;
        }, b -> {
            if (b == null) return;
            String want = previewing == null ? wpId : previewing;
            if (gen != wpGen || !id.equals(want)) { Tv.free(b); return; }
            swapShelf(b, false);
        });
    }

    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF full = new RectF(0, 0, Tv.W, Tv.H);

    private static final Tv.Ease PULL_FOCUS = new Tv.Ease(.4f, 0, .2f, 1), PULL_ZOOM = new Tv.Ease(.16f, 1, .3f, 1);

    /** The blurred stand-in, a little closer, until the photo is ready (the activity just came back). */
    private void holdPull() {
        if (pullRun != null) pullRun.cancel();
        pulling = true;
        pullK = 0;
        pullScale = Motion.still ? 1 : 1.08f;
        shelf.invalidate();
    }

    private void startPull() {
        if (!pulling) holdPull();
        if (pullRun != null) pullRun.cancel();
        float s0 = pullScale;
        pullRun = Motion.run(Motion.still ? 300 : 1100, Tv.LINEAR, t -> {
            pullK = PULL_FOCUS.at(t);
            pullScale = s0 + (1 - s0) * PULL_ZOOM.at(t);
            shelf.invalidate();
        }).then(() -> { pulling = false; pullScale = 1; shelf.invalidate(); coverBack(); });
    }

    private static float smooth(float x) { x = Tv.clamp(x, 0, 1); return x * x * (3 - 2 * x); }

    private void drawPull(Canvas c) {
        int save = c.save();
        c.scale(pullScale, pullScale, Tv.W / 2f, Tv.H / 2f);
        // two steps, never more than two full-screen pictures at a time: blurred -> soft, then soft -> sharp
        float aMid = midBlur == null ? 1 : smooth(pullK / .45f), aSharp = shelfA == null ? 0 : smooth((pullK - .45f) / .55f);
        Bitmap under = aMid >= 1 && midBlur != null ? midBlur : blur48;
        if (under != null && aSharp < 1) { bp.setAlpha(255); c.drawBitmap(under, null, full, bp); }
        if (aMid > 0 && aMid < 1 && midBlur != null) { bp.setAlpha(Math.round(255 * aMid)); c.drawBitmap(midBlur, null, full, bp); }
        if (aSharp > 0 && shelfA != null) { bp.setAlpha(Math.round(255 * aSharp)); c.drawBitmap(shelfA, null, full, bp); }
        c.restoreToCount(save);
    }

    private void drawShelf(Canvas c) {
        if (pulling) { drawPull(c); return; }
        if (shelfA == null || shelfB == null && shelfFade < 1) {
            // no photo yet (just back from an app): its blurred copy
            if (blur48 != null) { bp.setAlpha(255); c.drawBitmap(blur48, null, full, bp); }
            if (shelfA == null) return;
            bp.setAlpha(Math.round(255 * shelfFade));
            c.drawBitmap(shelfA, null, full, bp);
            return;
        }
        if (shelfB == null) {
            bp.setAlpha(255);
            c.drawBitmap(shelfA, null, full, bp);
            return;
        }
        // CSS cross-fade: the old picture fades out while the new one fades in
        bp.setAlpha(Math.round(255 * (1 - shelfFade)));
        c.drawBitmap(shelfA, null, full, bp);
        bp.setAlpha(Math.round(255 * shelfFade));
        c.drawBitmap(shelfB, null, full, bp);
    }

    private void drawBack(Canvas c) {
        if (backA == null) { c.drawColor(0xff000000); return; }
        bp.setAlpha(255);
        if (backB == null) { c.drawBitmap(backA, null, full, bp); return; }
        bp.setAlpha(Math.round(255 * (1 - backFade)));
        c.drawBitmap(backA, null, full, bp);
        bp.setAlpha(Math.round(255 * backFade));
        c.drawBitmap(backB, null, full, bp);
    }

    void applyLook() {
        Theme.read();
        boolean lg = grid && !Theme.dark;
        for (Tile t : tiles.values()) if (t.lightGrid != lg) { t.lightGrid = lg; t.invalidate(); }
        if (blur48 != null) {
            Bitmap src = blur48;
            boolean dark = Theme.dark;
            Tv.bg(() -> backImage(src, dark), b -> swapBack(b, false));
        }
        platter.invalidate();
        settings.themeChanged();
        Modal.themeChanged();
    }

    void applyAccess() {
        Theme.read();
        Tile.contrastRing = Theme.contrast;
        clockKey = null;
        for (Tile t : tiles.values()) t.invalidate();
        for (Views.Box l : labels.values()) l.invalidate();
        invalidateGlass();
        clock.invalidate();
        settings.themeChanged();
        Modal.themeChanged();
    }

    void invalidateGlass() {
        platter.invalidate();
        for (BarBtn b : bar) b.invalidate();
        inputs.invalidate();
        wifi.invalidate();
        menu.invalidate();
        toast.invalidate();
        cc.invalidate();
        picker.invalidate();
    }

    // ---------- platter ----------

    final Gfx.Glass platterGlass = new Gfx.Glass(1840, 257, 58).at(40, 766);
    private static final Gfx.Shadow LG_SHADOW = new Gfx.Shadow(0, 14, 40, 0, 58, Tv.black(.16f));
    private static final Gfx.Shadow LG_SHADOW_GRID = new Gfx.Shadow(0, 18, 40, 0, 58, Tv.black(.08f));

    private void drawPlatter(Canvas c) {
        Gfx.Glass g = platterGlass;
        float t = gridV;
        if (Theme.solid) {
            g.solid = Theme.solidPanel();
        } else {
            g.solid = 0;
            int wa = Theme.dark ? Tv.white(.12f) : Tv.white(.62f), wb = Theme.dark ? Tv.white(.04f) : Tv.white(.40f), wc = Theme.dark ? Tv.white(.08f) : Tv.white(.50f);
            g.tint(Tv.mix(Gfx.lgA, wa, t), Tv.mix(Gfx.lgB, wb, t), Tv.mix(Gfx.lgC, wc, t));
            g.topLine = Tv.lerp(.5f, .9f, t);
            g.border = Tv.lerp(.16f, 0, t);
        }
        if (t < 1) LG_SHADOW.draw(c, 40, 766, 1880, 1023, 1 - t, true);
        if (t > 0) LG_SHADOW_GRID.draw(c, 40, 766, 1880, 1023, t, true);
        g.draw(c, 40, 766);
    }

    // =====================================================================================================
    // clock
    // =====================================================================================================

    private final Views.Cached clockCache = new Views.Cached();
    private String clockKey, timeText = "", dateText = "", greetText = "";
    private static final String[] DAYS = {"Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"};
    private static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    String time() { return timeText; }

    String date() { return dateText; }

    void tick() {
        Calendar d = Calendar.getInstance();
        int h = d.get(Calendar.HOUR_OF_DAY), m = d.get(Calendar.MINUTE);
        String mm = (m < 10 ? "0" : "") + m;
        timeText = Prefs.b("hour24") ? (h < 10 ? "0" : "") + h + ":" + mm : (h % 12 == 0 ? 12 : h % 12) + ":" + mm;
        greetText = h < 5 ? "Доброй ночи" : h < 12 ? "Доброе утро" : h < 18 ? "Добрый день" : h < 23 ? "Добрый вечер" : "Доброй ночи";
        dateText = DAYS[d.get(Calendar.DAY_OF_WEEK) - 1] + ", " + d.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[d.get(Calendar.MONTH)];
        clock.invalidate();
        barTime.invalidate();
        saver.tick();
        if (Theme.dark != Theme.effDark()) applyLook();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            long now = System.currentTimeMillis();
            Tv.later(60000 - now % 60000 + 50, this); // on the minute
        }
    };

    private final TextPaint clockDate = Tv.text(36, 600, .2f, 0xffffffff), clockTime = Tv.tabular(Tv.text(212, 600, -7, 0xffffffff));
    private final TextPaint leftGreet = Tv.text(22, 600, 3.2f, 0xffffffff), leftTime = Tv.tabular(Tv.text(140, 500, -4.5f, 0xffffffff)), leftDate = Tv.text(34, 500, -.2f, 0xffffffff);

    /** The clock's box: just around the text (with room for its soft shadow). */
    private void placeClock() {
        if ("center".equals(Prefs.s("clock"))) Views.Abs.place(clock, 560, 0, 800, 320);
        else Views.Abs.place(clock, 40, 20, 860, 360);
    }

    private void drawClock(Canvas c, Views.Box v) {
        boolean center = "center".equals(Prefs.s("clock"));
        String key = timeText + "|" + dateText + "|" + center + "|" + Tv.bold + "|" + greetText;
        float ox = -v.getLeft(), oy = -v.getTop();
        clockCache.draw(c, key, v.getWidth(), v.getHeight(), cv -> {
            cv.translate(ox, oy);
            if (center) {
                // date above a large time, centred (lock screen); text-shadow 0 2px 34px rgba(0,0,0,.22)
                float shadow = Tv.blurRadius(34);
                clockDate.setTypeface(Tv.font(600));
                clockDate.setShadowLayer(shadow, 0, 2, Tv.black(.22f));
                clockDate.setTextAlign(Paint.Align.CENTER);
                clockDate.setAlpha(Math.round(255 * .94f));
                float lh = Tv.lineNormal(clockDate);
                cv.drawText(dateText, 960, Tv.baseline(clockDate, 46, lh), clockDate);
                clockTime.setTypeface(Tv.font(600));
                clockTime.setShadowLayer(shadow, 0, 2, Tv.black(.22f));
                clockTime.setTextAlign(Paint.Align.CENTER);
                cv.drawText(timeText, 960, Tv.baseline(clockTime, 46 + lh - 4, 212 * .96f), clockTime);
            } else {
                float shadow = Tv.blurRadius(26);
                leftGreet.setTypeface(Tv.font(600));
                leftGreet.setShadowLayer(shadow, 0, 2, Tv.black(.26f));
                leftGreet.setAlpha(Math.round(255 * .82f));
                float y = 64, g = Tv.lineNormal(leftGreet);
                cv.drawText(greetText.toUpperCase(), 96 + 5, Tv.baseline(leftGreet, y, g), leftGreet);
                y += g + 2;
                leftTime.setTypeface(Tv.font(500));
                leftTime.setShadowLayer(shadow, 0, 2, Tv.black(.26f));
                cv.drawText(timeText, 96, Tv.baseline(leftTime, y, 140 * 1.02f), leftTime);
                y += 140 * 1.02f + 2;
                leftDate.setTypeface(Tv.font(500));
                leftDate.setShadowLayer(shadow, 0, 2, Tv.black(.26f));
                leftDate.setAlpha(Math.round(255 * .92f));
                cv.drawText(dateText, 96 + 5, Tv.baseline(leftDate, y, Tv.lineNormal(leftDate)), leftDate);
            }
        });
    }

    private String placeText = "";
    private final TextPaint placeP = Tv.text(20, 500, 0, Tv.white(.78f));

    private void drawPlace(Canvas c, Views.Box v) {
        placeP.setTypeface(Tv.font(500));
        placeP.setShadowLayer(Tv.blurRadius(12), 0, 1, Tv.black(.5f));
        placeP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(placeText, v.getWidth(), Tv.baseline(placeP, 10, Tv.lineNormal(placeP)), placeP);
    }

    private final TextPaint barTimeP = Tv.tabular(Tv.text(30, 600, 0, 0xffffffff));

    private void drawBarTime(Canvas c, Views.Box v) {
        barTimeP.setTypeface(Tv.font(600));
        barTimeP.setShadowLayer(Tv.blurRadius(12), 0, 1, Tv.black(.45f));
        barTimeP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(timeText, v.getWidth(), Tv.center(barTimeP, 30), barTimeP);
    }

    private final TextPaint tipP = Tv.text(21, 600, 0, 0xffffffff);

    private void drawTip(Canvas c, Views.Box v, String text, boolean right) {
        tipP.setTypeface(Tv.font(600));
        tipP.setShadowLayer(Tv.blurRadius(10), 0, 1, Tv.black(.55f));
        tipP.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.CENTER);
        c.drawText(text, right ? v.getWidth() : v.getWidth() / 2f, Tv.baseline(tipP, 6, Tv.lineNormal(tipP)), tipP);
    }

    private final TextPaint labelP = Tv.text(28, 600, -.1f, 0xffffffff);
    private final Paint labelShadow = new Paint();

    private void drawLabel(Canvas c, Views.Box v) {
        Apps.App a = (Apps.App) v.tag;
        labelP.setTypeface(Tv.font(600));
        labelP.setTextAlign(Paint.Align.CENTER);
        float y = Tv.baseline(labelP, 4, Tv.lineNormal(labelP));
        String s = Tv.fit(labelP, a.name, v.getWidth());
        // text-shadow: 0 1px 8px rgba(0,0,0,.38), 0 0 2px rgba(0,0,0,.22) (stronger with Increase Contrast)
        labelP.setShadowLayer(Tv.blurRadius(Theme.contrast ? 3 : 8), 0, 1, Tv.black(Theme.contrast ? .9f : .38f));
        c.drawText(s, v.getWidth() / 2f, y, labelP);
        labelP.setShadowLayer(Tv.blurRadius(2), 0, 0, Tv.black(Theme.contrast ? 1f : .22f));
        c.drawText(s, v.getWidth() / 2f, y, labelP);
        labelP.clearShadowLayer();
    }

    // =====================================================================================================
    // top bar
    // =====================================================================================================

    int barI;

    /** A round glass button of the top bar (web .bb): white and larger when focused. */
    final class BarBtn extends Views.Box {
        static final int O = 84; // room around the button for its soft shadow
        final String glyph;
        final Gfx.Glass glass;
        float w;
        final Motion.Val vw = new Motion.Val(0, 0.003f, x -> { w = x; invalidate(); });
        private final Gfx.Shadow focusShadow = new Gfx.Shadow(0, 14, 30, 0, 36, Tv.black(.28f));

        BarBtn(android.content.Context c, String glyph, int x, int y) {
            super(c, null);
            this.glyph = glyph;
            glass = new Gfx.Glass(72, 72, 36).at(x, y);
            glass.shadow = LG_SHADOW_SMALL;
            setPivotX(O + 36);
            setPivotY(O + 36);
            painter = (cv, v) -> paintBtn(cv);
        }

        void set(boolean on) {
            vw.tween(220, Tv.EASE, on ? 1 : 0);
            Motion.spring(this, Motion.SCALE, Motion.FOCUS, on ? 1.12f : 1);
        }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        private void paintBtn(Canvas c) {
            if (w < 1) {
                glass.adaptive();
                glass.solid = Theme.solid ? Theme.solidPanel() : 0;
                glass.draw(c, O, O);
            }
            if (w > 0) {
                focusShadow.draw(c, O, O, O + 72, O + 72, w, true);
                p.setColor(Tv.white(w));
                c.drawCircle(O + 36, O + 36, 36, p);
            }
            int ic = Theme.onGlass();
            Glyphs.draw(c, glyph, O + 36 - 17, O + 36 - 17, 34, Tv.mix(ic, 0xff111111, w), 2);
        }
    }

    private static final Gfx.Shadow LG_SHADOW_SMALL = new Gfx.Shadow(0, 14, 40, 0, 36, Tv.black(.16f));

    void paintBar() {
        for (int i = 0; i < 3; i++) {
            boolean on = "bar".equals(mode) && i == barI;
            boolean act = i == 2 && "cc".equals(mode);
            bar[i].set(on || act);
            boolean tip = on && !"cc".equals(mode);
            Motion.fade(tips[i], 220, tip ? 1 : 0);
            Motion.of(tips[i], Motion.TY);
            Motion.tween(tips[i], Motion.TY, 400, Tv.SCROLL, tip ? 0 : -6);
        }
        if ("bar".equals(mode)) OS.speak(new String[]{"Источники", "Wi-Fi", "Пункт управления"}[barI]);
    }

    void enterBar() {
        unfocusApp();
        mode = "bar";
        barI = 0;
        paintBar();
        Sounds.tick();
    }

    void leaveBar() {
        mode = "home";
        paintBar();
        focusApp(lastTop, 0, 1);
        Sounds.tick();
    }

    void bumpView(View v, int dx, int dy) {
        if (Motion.still || v == null) return;
        float bx = v.getTranslationX(), by = v.getTranslationY();
        Motion.run(380, Tv.BUMP, k -> {
            float f = Motion.there(k);
            v.setTranslationX(bx + dx * 12 * f);
            v.setTranslationY(by + dy * 9 * f);
        }).then(() -> { v.setTranslationX(bx); v.setTranslationY(by); });
    }

    // ---------- inputs & Wi-Fi popovers ----------

    List<Device.Input> inputList = new ArrayList<>();
    int inI, inCur = -1;

    static String inGlyph(String n) {
        String s = n.toLowerCase();
        return s.contains("hdmi") ? "hdmi" : s.matches(".*\\b(av|composite|component|scart)\\b.*") ? "avin" : "tvin";
    }

    void openInputs() {
        inputList = Device.inputs(act);
        inCur = Math.min(inCur, inputList.size() - 1);
        List<Pop.Row> rows = new ArrayList<>();
        for (Device.Input x : inputList) rows.add(new Pop.Row(inGlyph(x.name), x.name, x.connected ? "Подключено" : ""));
        if (rows.isEmpty()) rows.add(new Pop.Row("tvin", "Других источников нет", ""));
        inputs.setRows(rows);
        mode = "inputs";
        inI = Math.max(0, inCur);
        inputs.focus(inI, inCur);
        modal(true);
        inputs.show(true);
        Sounds.select();
    }

    void openWifi() {
        Device.Net n = Device.net(act);
        wifi.netName = Device.netName(n);
        List<String> st = new ArrayList<>();
        if (n.connected) {
            st.add("Подключено");
            if (n.freqMHz > 0) st.add(n.freqMHz > 4000 ? "5 ГГц" : "2,4 ГГц");
            if ("wifi".equals(n.type) && n.signal >= 0) st.add("сигнал " + new String[]{"слабый", "средний", "хороший", "отличный"}[Math.max(0, Math.min(3, n.signal))]);
        }
        wifi.netState = n.connected ? String.join(" · ", st) : "Нет подключения";
        wifi.netOn = n.connected;
        List<Pop.Row> rows = new ArrayList<>();
        rows.add(new Pop.Row("ccgear", "Настройки сети", ""));
        wifi.setRows(rows);
        wifi.focus(0, -1);
        mode = "wifi";
        modal(true);
        wifi.show(true);
        Sounds.select();
    }

    void closePop() {
        inputs.show(false);
        wifi.show(false);
        if (menu.isShown()) menu.show(false);
        modal(false);
        dimOthers(false);
    }

    private boolean modalOn, ccShade;

    void modal(boolean on) {
        modalOn = on;
        shade.invalidate();
        Motion.fade(shade, 450, on || ccShade ? 1 : 0);
    }

    void ccShade(boolean on) {
        ccShade = on;
        shade.invalidate();
        Motion.fade(shade, 450, on || modalOn ? 1 : 0);
    }

    private final Paint shadeP = new Paint();

    private void drawShade(Canvas c) {
        if (ccShade) {
            boolean over = cc.over != null;
            shadeP.setShader(over ? Gfx.radial(Tv.W, 0, 1400, 1000, new int[]{Tv.black(.62f), Tv.black(.32f), Tv.black(.14f)}, new float[]{0, .55f, .85f})
                    : Gfx.radial(Tv.W, 0, 1300, 900, new int[]{Tv.black(.5f), Tv.black(.18f), Tv.black(0)}, new float[]{0, .55f, .8f}));
            c.drawRect(0, 0, Tv.W, Tv.H, shadeP);
            shadeP.setShader(null);
        } else c.drawColor(Tv.black(.26f));
    }

    // =====================================================================================================
    // brightness, sleep timer
    // =====================================================================================================

    int bright = 100;

    void applyBright() {
        bright = Prefs.i("bright");
        Motion.fade(brightV, 350, ((100 - bright) / 100f) * 0.78f);
        cc.refresh();
    }

    boolean setBright(int v) {
        int n = Math.max(10, Math.min(100, v));
        if (n == bright) return false;
        Prefs.set("bright", n);
        return true;
    }

    static final String[] SLEEP_OPTS = {"Выкл.", "15 мин", "30 мин", "60 мин", "90 мин"};
    int sleepI;
    private final Runnable sleepTimer = this::sleepNow;

    void setSleepTimer(int i) {
        sleepI = i;
        Tv.cancel(sleepTimer);
        int m = new int[]{0, 15, 30, 60, 90}[i];
        if (m > 0) Tv.later(m * 60000L, sleepTimer);
    }

    // =====================================================================================================
    // context menu & move
    // =====================================================================================================

    final List<Runnable> menuActs = new ArrayList<>();
    int mI;

    RectF iconRect() {
        float x = px(cur), y = py(cur) + pageY(row), w = IW * FS, h = IH * FS;
        return new RectF(x + IW / 2f - w / 2, y + IH / 2f - h / 2, x + IW / 2f + w / 2, y + IH / 2f + h / 2);
    }

    void openMenu() {
        Apps.App a = byId.get(list.get(cur));
        menuActs.clear();
        List<Pop.Row> rows = new ArrayList<>();
        rows.add(new Pop.Row(null, "Переместить", ""));
        menuActs.add(this::startMove);
        rows.add(new Pop.Row(null, "Настройки приложения", ""));
        menuActs.add(() -> openSettings(new String[]{"apps", "app:" + a.id}, false, false));
        Pop.Row hide = new Pop.Row(null, "Скрыть", "");
        hide.red = true;
        rows.add(hide);
        menuActs.add(() -> hideApp(a));
        menu.setRows(rows);
        RectF r = iconRect();
        boolean right = r.right + 30 + 380 < 1900;
        int h = rows.size() * 64 + 24;
        int x = Math.round(right ? r.right + 26 : r.left - 406), y = Math.round(Math.min(1060 - h, Math.max(30, r.centerY() - h / 2f)));
        menu.placeAt(x, y, right);
        mI = 0;
        menu.focus(0, -1);
        mode = "menu";
        dimOthers(true);
        menu.show(true);
        OS.speak("Переместить");
    }

    private void dimOthers(boolean on) {
        Tile focused = curTile();
        for (Tile t : tiles.values()) if (t != focused && t.getVisibility() != View.GONE) Motion.tween(t, Motion.ALPHA, 350, Tv.EASE, on ? .42f : 1);
    }

    void closeMenu() {
        menu.show(false);
        dimOthers(false);
        mode = "home";
    }

    void hideApp(Apps.App a) {
        if (list.size() <= 1) { Sounds.error(); return; }
        Modal.alert("Скрыть «" + a.name + "»?", "Приложение останется на ТВ. Вернуть его можно в Настройки → Приложения.", a.banner, null,
                new String[]{"Скрыть", "Отменить"}, new boolean[]{true, false}, 1, 0, i -> { if (i == 0) setHidden(a.id, true); });
    }

    void startMove() {
        mode = "move";
        Tile t = curTile();
        t.jiggle(true);
        hint("Перемещение: стрелки двигают приложение, OK — готово");
    }

    void endMove() {
        mode = "home";
        Tile t = curTile();
        if (t != null) t.jiggle(false);
        Prefs.set("order", order);
        Sounds.select();
    }

    void moveTo(int j) {
        if (j < 0 || j >= list.size()) { bumpApp(0, 0); return; }
        String id = list.get(cur);
        list.remove(cur);
        list.add(j, id);
        List<String> no = new ArrayList<>(list);
        for (String x : order) if (!list.contains(x)) no.add(x);
        order = no;
        cur = j;
        layout(false);
        Sounds.tick();
        int r = cur / COLS;
        if (r != row) setRow(r);
        if (r == 0) lastTop = cur;
    }

    // =====================================================================================================
    // toast & hint
    // =====================================================================================================

    private String toastTitle = "", toastText = "";
    private Bitmap toastPic;
    private String toastGlyph;
    private final Gfx.Glass toastGlass = new Gfx.Glass(430, 106, 38);
    private final Runnable toastOff = () -> showToast(false);
    private int toastW = 430;

    void toast(String title, String text, Bitmap pic, String glyph) {
        toastTitle = title;
        toastText = text == null ? "" : text;
        toastPic = pic;
        toastGlyph = glyph;
        TextPaint b = Tv.text(24, 700, 0, 0), s = Tv.text(20, 400, 0, 0);
        float tw = Math.max(Tv.width(b, toastTitle), Tv.width(s, toastText));
        toastW = (int) Math.max(430, Math.min(640, 28 + 62 + 20 + tw + 28));
        Views.Abs.place(toast, 1920 - 88 - toastW, 170, toastW, 106);
        toastGlass.size(toastW, 106);
        toastGlass.at(1920 - 88 - toastW, 170);
        toast.invalidate();
        showToast(true);
        Tv.cancel(toastOff);
        Tv.later(2600, toastOff);
    }

    private void showToast(boolean on) {
        Motion.fade(toast, 300, on ? 1 : 0);
        Motion.spring(toast, Motion.TY, Motion.PANEL, on ? 0 : -340);
    }

    private final TextPaint toastB = Tv.text(24, 700, 0, 0xffffffff), toastS = Tv.text(20, 400, 0, Tv.white(.75f));
    private final Paint toastP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private void drawToast(Canvas c, Views.Box v) {
        toastGlass.adaptive();
        toastGlass.shadow = null;
        toastGlass.solid = Theme.solid ? Theme.solidPanel() : 0;
        toastGlass.draw(c, 0, 0);
        RectF ic = new RectF(28, 22, 90, 84);
        if (toastPic != null) {
            toastP.setColor(0xff000000);
            toastP.setShader(Gfx.coverShader(toastPic, ic.left, ic.top, 62, 62));
            c.drawRoundRect(ic, 16, 16, toastP);
            toastP.setShader(null);
        } else {
            toastP.setColor(Tv.white(.16f));
            c.drawRoundRect(ic, 16, 16, toastP);
            if (toastGlyph != null) Glyphs.draw(c, toastGlyph, 28 + 15, 22 + 15, 32, Theme.onGlass(), 2);
        }
        int col = Theme.onGlass();
        toastB.setTypeface(Tv.font(700));
        toastB.setColor(col);
        toastS.setColor(Tv.alpha(col, .75f));
        float lb = Tv.lineNormal(toastB), ls = toastText.isEmpty() ? 0 : Tv.lineNormal(toastS);
        float top = 53 - (lb + ls) / 2;
        float maxW = v.getWidth() - 110 - 28;
        c.drawText(Tv.fit(toastB, toastTitle, maxW), 110, Tv.baseline(toastB, top, lb), toastB);
        if (ls > 0) c.drawText(Tv.fit(toastS, toastText, maxW), 110, Tv.baseline(toastS, top + lb, ls), toastS);
    }

    void hint(String text) { toast(text, "", null, "info"); }

    // =====================================================================================================
    // app launch: the icon dips, then the app's banner zooms out of it to full screen
    // =====================================================================================================

    Apps.App launched;
    String launchedFrom = "home";
    boolean launchClosing;
    private RectF launchRect;
    private Bitmap launchBig;
    private Motion.Run launchRun, pageZoom;
    private static final RectF CENTER = new RectF(810, 440, 1110, 620);

    RectF iconRectOf(String id) {
        int i = list.indexOf(id);
        if (i < 0 || i / COLS != row) return null;
        float s = i == cur ? FS : 1, x = px(i), y = py(i) + pageY(row), w = IW * s, h = IH * s;
        return new RectF(x + IW / 2f - w / 2, y + IH / 2f - h / 2, x + IW / 2f + w / 2, y + IH / 2f + h / 2);
    }

    void openApp(Apps.App app, boolean instant) {
        boolean fromIcon = app == null;
        Apps.App a = app != null ? app : byId.get(list.get(cur));
        if (a == null || "launch".equals(mode) || "os".equals(mode) || "saver".equals(mode)) return;
        if ("settings".equals(a.id)) { openSettings(null, fromIcon, instant); return; }
        launchedFrom = "home".equals(mode) || "bar".equals(mode) ? mode : "home";
        mode = "launch";
        launched = a;
        launchClosing = false;
        OS.recent(a.id);
        Prefs.set("last", a.id);
        Sounds.select();
        launchRect = (fromIcon ? iconRect() : null);
        if (launchRect == null) launchRect = iconRectOf(a.id);
        if (launchRect == null) launchRect = CENTER;
        launchBig = a.banner;
        Tv.bg(() -> Apps.big(a), b -> { if (b != null && launched == a) { launchBig = b; launch.invalidate(); } });
        launch.invalidate();
        if (instant) {
            // from the app switcher: its card already grew to full screen, the app itself starts right away
            zoom(launch, launchRect, true, true);
            zoomPage(true, true);
            if (!Apps.launch(act, a.pkg)) {
                closeApp();
                toast("Не удалось открыть", a.name, a.banner, null);
            }
            return;
        }
        Runnable go = () -> {
            if (launched != a || !"launch".equals(mode)) return;
            zoom(launch, launchRect, true, false);
            zoomPage(true, false);
            // the real app starts as the zoom ends; coming back zooms it into its icon again
            Tv.later(430, () -> {
                if ("launch".equals(mode) && launched == a && !Apps.launch(act, a.pkg)) {
                    closeApp();
                    toast("Не удалось открыть", a.name, a.banner, null);
                }
            });
        };
        if (fromIcon) curTile().press(go); else go.run();
    }

    void closeApp() {
        if (launched == null || launchClosing) return;
        launchClosing = true;
        Sounds.back();
        zoom(launch, launchRect != null ? launchRect : CENTER, false, false);
        zoomPage(false, false);
        Tv.later(450, () -> {
            Views.hide(launch);
            if (!"launch".equals(mode)) return;
            mode = launchedFrom;
            if ("home".equals(mode)) focusApp(cur, 0, 0); else paintBar();
            if (launchBig != null && launched != null && launchBig != launched.banner) Tv.free(launchBig);
            launchBig = null;
        });
    }

    /** Leave whatever app is in front at once (an action from Control Center needs the home screen). */
    void leaveApp() {
        if ("os".equals(mode)) settings.hide(null, true);
        else if ("launch".equals(mode)) { if (launchRun != null) launchRun.cancel(); Views.hide(launch); }
        else return;
        if (pageZoom != null) pageZoom.cancel();
        page.setScaleX(1);
        page.setScaleY(1);
        mode = "home";
        updateCover();
        focusApp(cur, 0, 0);
    }

    /** OS.zoom: a full-screen layer opens out of (or closes into) an icon's rectangle: transform + opacity only. */
    void zoom(View v, RectF r, boolean open, boolean instant) {
        if (v == launch && launchRun != null) launchRun.cancel();
        Views.show(v);
        float tx = r.centerX() - 960, ty = r.centerY() - 540, sx = r.width() / 1920f, sy = r.height() / 1080f;
        if (instant) {
            v.setTranslationX(0); v.setTranslationY(0); v.setScaleX(1); v.setScaleY(1); v.setAlpha(open ? 1 : 0);
            if (!open) Views.hide(v);
            return;
        }
        if (Motion.still) {
            v.setTranslationX(0); v.setTranslationY(0); v.setScaleX(1); v.setScaleY(1);
            Motion.Run run = Motion.run(240, Tv.LINEAR, k -> v.setAlpha(open ? k : 1 - k));
            if (v == launch) launchRun = run;
            return;
        }
        Tv.Ease e = open ? new Tv.Ease(.25f, .1f, .1f, 1) : new Tv.Ease(.4f, 0, .2f, 1);
        Motion.Run run = Motion.run(open ? 480 : 400, Tv.LINEAR, k -> {
            float p = e.at(k), q = open ? p : 1 - p;
            v.setTranslationX(tx * (1 - q));
            v.setTranslationY(ty * (1 - q));
            v.setScaleX(sx + (1 - sx) * q);
            v.setScaleY(sy + (1 - sy) * q);
            // opacity: open fades in over the first 30 %, close keeps it until 55 % then fades
            float a = open ? Math.min(1, k / .3f) : (k < .55f ? 1 : 1 - (k - .55f) / .45f);
            v.setAlpha(Tv.clamp(a, 0, 1));
        });
        if (v == launch) launchRun = run;
    }

    void zoomPage(boolean open, boolean instant) {
        if (pageZoom != null) pageZoom.cancel();
        if (Motion.still || instant) { page.setScaleX(open && instant ? 1.05f : 1); page.setScaleY(page.getScaleX()); if (!open) { page.setScaleX(1); page.setScaleY(1); } return; }
        float from = open ? 1 : page.getScaleX() > 1 ? page.getScaleX() : 1.05f, to = open ? 1.05f : 1;
        pageZoom = Motion.run(500, open ? Tv.EASE_OUT : Tv.SCROLL, k -> { float s = from + (to - from) * k; page.setScaleX(s); page.setScaleY(s); });
    }

    private final Paint launchP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Gfx.Shadow BAN_SHADOW = new Gfx.Shadow(0, 40, 80, 0, 40, Tv.black(.45f));
    private final TextPaint launchT = Tv.text(40, 700, 0, 0xffffffff), launchS = Tv.text(22, 400, 0, Tv.white(.62f));

    private void drawLaunch(Canvas c) {
        Apps.App a = launched;
        if (a == null) return;
        int[] col = a.color;
        launchP.setShader(Gfx.radial(960, 1080 * .4f, 1200, 800, new int[]{Tv.rgba((int) (col[0] * .36f), (int) (col[1] * .36f), (int) (col[2] * .36f), 1), 0xff050506}, new float[]{0, .75f}));
        c.drawRect(0, 0, Tv.W, Tv.H, launchP);
        launchP.setShader(null);
        float cx = 960, cy = 1080 * .46f;
        RectF b = new RectF(cx - 320, cy - 192, cx + 320, cy + 192);
        BAN_SHADOW.draw(c, b.left, b.top, b.right, b.bottom, 1, true);
        Bitmap big = launchBig != null && !launchBig.isRecycled() ? launchBig : a.banner;
        if (big != null) {
            launchP.setShader(Gfx.coverShader(big, b.left, b.top, b.width(), b.height()));
            c.drawRoundRect(b, 40, 40, launchP);
            launchP.setShader(null);
        }
        launchT.setTextAlign(Paint.Align.CENTER);
        launchT.setShadowLayer(Tv.blurRadius(18), 0, 2, Tv.black(.4f));
        c.drawText(a.name, 960, Tv.baseline(launchT, b.bottom + 34, Tv.lineNormal(launchT)), launchT); // the name under the logo
    }

    // =====================================================================================================
    // Settings, the app switcher, the screen saver
    // =====================================================================================================

    String osFrom = "home";

    void openSettings(String[] path, boolean press, boolean instant) {
        if ("os".equals(mode)) { if (path != null) settings.show(null, path, true); return; }
        osFrom = "bar".equals(mode) ? "bar" : "home";
        Runnable go = () -> {
            RectF rect = "home".equals(osFrom) ? iconRectOf("settings") : null;
            mode = "os";
            Sounds.select();
            settings.show(rect, path, instant);
            zoomPage(true, instant);
        };
        if (press && curTile() != null && "settings".equals(curTile().app.id)) curTile().press(go);
        else go.run();
    }

    void exitSettings() {
        Sounds.back();
        String to = osFrom;
        mode = to;
        updateCover();
        if ("home".equals(to)) focusApp(cur, 0, 0); else paintBar();
        settings.hide("home".equals(to) ? iconRectOf("settings") : null, false);
        zoomPage(false, false);
        switcher.settingsClosed();
    }

    void launchFromSettings(String id) {
        settings.hide(null, true);
        zoomPage(false, true);
        mode = "home";
        updateCover();
        openApp(byId.get(id), false);
    }

    void resume(String id) {
        if ("settings".equals(id)) openSettings(null, false, true);
        else openApp(byId.get(id), true);
    }

    void openSwitcher() {
        closeAll();
        leaveApp();
        if ("home".equals(mode)) unfocusApp();
        switcher.open();
    }

    void switcherClosed() { if ("home".equals(mode)) focusApp(cur, 0, 0); }

    void sleepNow() {
        if ("saver".equals(mode)) { saver.black(); return; }
        startSaver(true);
    }

    String saverFrom = "home";

    void startSaver(boolean black) {
        if ("saver".equals(mode)) return;
        closeAll();
        saverFrom = mode;
        mode = "saver";
        saver.start(black);
    }

    void stopSaver() {
        saver.stop();
        if ("os".equals(saverFrom) || "launch".equals(saverFrom)) { mode = saverFrom; updateCover(); return; }
        mode = "bar".equals(saverFrom) ? "bar" : "home";
        updateCover();
        if ("home".equals(mode)) focusApp(cur, 0, 0); else paintBar();
    }

    /**
     * Nothing hidden is drawn: while Settings or the screen saver covers the screen, the home screen's layers are
     * taken out of rendering (the GPU of a TV pays for every full-screen layer it blends).
     */
    void updateCover() {
        boolean modal = Modal.covers() || switcher.covers();
        // Settings under an alert, the keyboard or the app switcher is not drawn either
        if (settings.isOpen()) settings.root.setVisibility(modal ? View.INVISIBLE : View.VISIBLE);
        boolean covered = settings.covers() && cc.over == null || saver.covers() || modal;
        int v = covered ? View.INVISIBLE : View.VISIBLE;
        shelf.setVisibility(v);
        page.setVisibility(v);
        if (cc.over == null) hud.setVisibility(v); else hud.setVisibility(View.VISIBLE);
        if (covered) back.setVisibility(View.INVISIBLE); else coverBack();
    }

    void closeAll() {
        if ("cc".equals(mode)) cc.close(false);
        if ("picker".equals(mode)) picker.close();
        if ("menu".equals(mode)) closeMenu();
        if ("move".equals(mode)) endMove();
        if ("inputs".equals(mode) || "wifi".equals(mode)) { closePop(); mode = "bar"; }
        if ("bar".equals(mode)) { mode = "home"; paintBar(); }
    }

    // =====================================================================================================
    // idle: screen saver and sleep
    // =====================================================================================================

    private final Runnable saverIdle = this::saverIdleNow;

    private void saverIdleNow() {
        if (started && ("home".equals(mode) || "bar".equals(mode) || "os".equals(mode)) && OS.top() == null && act.visible) startSaver(false);
    }
    private final Runnable sleepIdle = this::sleepNow;

    void armIdle() {
        Tv.cancel(saverIdle);
        Tv.cancel(sleepIdle);
        if (!act.visible) return;
        int s = Prefs.i("saverAfter"), z = Prefs.i("sleepAfter");
        if (s > 0) Tv.later(s * 60000L, saverIdle);
        if (z > 0) Tv.later(z * 60000L, sleepIdle);
    }

    // =====================================================================================================
    // remote
    // =====================================================================================================

    private long lastHome;

    /** Home was pressed in another app (it brought the launcher back): the next press soon after = switcher. */
    void homeFromApp() {
        long now = SystemClock.uptimeMillis();
        if (now - lastHome < 600) pendingSwitcher = true; // both presses came before the home screen was back
        lastHome = now;
    }

    private boolean pendingSwitcher;

    void key(String a) {
        armIdle();
        if ("saver".equals(mode)) { stopSaver(); return; }
        OS.Layer top = OS.top();
        if (top != null) { top.key(a); return; } // an alert, the keyboard or the app switcher has the remote
        int dx = "left".equals(a) ? -1 : "right".equals(a) ? 1 : 0, dy = "up".equals(a) ? -1 : "down".equals(a) ? 1 : 0;
        boolean dir = dx != 0 || dy != 0;
        switch (a) {
            case "home": {
                long now = SystemClock.uptimeMillis();
                boolean dbl = now - lastHome < 600; // twice quickly: the app switcher (also right after leaving an app)
                lastHome = now;
                if (dbl) { openSwitcher(); return; }
                closeAll();
                if ("os".equals(mode)) { exitSettings(); return; }
                if ("launch".equals(mode)) { closeApp(); return; }
                Sounds.back();
                if (row > 0) setRow(0);
                focusApp(0, 0, 0);
                return;
            }
            case "switcher": openSwitcher(); return;
            case "settings":
                if ("os".equals(mode)) return;
                closeAll();
                leaveApp();
                openSettings(null, false, false);
                return;
            case "control":
                if ("cc".equals(mode)) { Sounds.back(); cc.close(false); } else { closeAll(); cc.open(); }
                return;
            case "screensaver": startSaver(false); return;
            default:
        }
        switch (mode) {
            case "os":
                if ("wallpapers".equals(a)) { leaveApp(); picker.open(); return; }
                settings.key(a);
                return;
            case "launch":
                if ("back".equals(a)) closeApp();
                return;
            case "inputs":
                if (dy != 0) {
                    int n = inI + dy;
                    if (n >= 0 && n < inputList.size()) { inI = n; Sounds.tick(); inputs.focus(inI, inCur); }
                } else if ("ok".equals(a)) {
                    if (inI >= inputList.size()) { Sounds.bump(); return; }
                    Device.Input in = inputList.get(inI);
                    inCur = inI;
                    inputs.focus(inI, inCur);
                    Sounds.select();
                    Tv.later(240, () -> {
                        closePop();
                        mode = "bar";
                        paintBar();
                        if (!Device.openInput(act, in.id)) toast("Не удалось переключить", in.name, null, inGlyph(in.name));
                    });
                } else if ("back".equals(a)) { Sounds.back(); closePop(); mode = "bar"; paintBar(); }
                else if (dir) Sounds.bump();
                return;
            case "wifi":
                if ("ok".equals(a)) { Sounds.select(); closePop(); mode = "bar"; paintBar(); openSettings(new String[]{"network", "wifi"}, false, false); }
                else if ("back".equals(a)) { Sounds.back(); closePop(); mode = "bar"; paintBar(); }
                else if (dir) Sounds.bump();
                return;
            case "cc": cc.key(a, dx, dy); return;
            case "picker": picker.key(a, dx, dy); return;
            case "menu":
                if (dy != 0) {
                    int n = mI + dy;
                    if (n >= 0 && n < menuActs.size()) { mI = n; Sounds.tick(); menu.focus(mI, -1); OS.speak(menu.rows.get(mI).text); }
                } else if ("ok".equals(a)) { Runnable r = menuActs.get(mI); closeMenu(); Sounds.select(); r.run(); }
                else if ("back".equals(a)) { Sounds.back(); closeMenu(); }
                return;
            case "move":
                if (dir) {
                    int n = cur + dx + dy * COLS;
                    if (dx != 0 && n / COLS != cur / COLS || n < 0) { bumpApp(dx, 0); return; }
                    moveTo(Math.min(n, list.size() - 1));
                } else if ("ok".equals(a) || "back".equals(a)) endMove();
                return;
            case "bar":
                if (dx != 0) {
                    int n = barI + dx;
                    if (n >= 0 && n < 3) { barI = n; Sounds.tick(); paintBar(); } else bumpView(bar[barI], dx, 0);
                } else if ("down".equals(a) || "back".equals(a)) leaveBar();
                else if ("up".equals(a)) bumpView(bar[barI], 0, -1);
                else if ("ok".equals(a)) { if (barI == 0) openInputs(); else if (barI == 1) openWifi(); else cc.open(); }
                return;
            default: // home
                if ("wallpapers".equals(a)) { picker.open(); return; }
                if (list.isEmpty()) return; // the apps are still being read
                if (dir) {
                    if (dx != 0) {
                        int n = cur + dx;
                        if (n < 0 || n >= list.size() || n / COLS != cur / COLS) { bumpApp(dx, 0); return; }
                        Sounds.tick();
                        focusApp(n, dx, 0);
                        return;
                    }
                    if (dy < 0 && row == 0) { enterBar(); return; }
                    int r = row + dy, rows = (list.size() + COLS - 1) / COLS;
                    if (r < 0 || r >= rows) { bumpApp(0, dy); return; }
                    Sounds.tick();
                    focusApp(Math.min(list.size() - 1, r * COLS + cur % COLS), 0, dy);
                    return;
                }
                if ("ok".equals(a)) openApp(null, false);
                else if ("hold".equals(a)) openMenu();
                else if ("back".equals(a)) {
                    if (row > 0) { Sounds.back(); setRow(0); focusApp(lastTop, 0, -1); }
                    else if (cur != 0) { Sounds.back(); focusApp(0, 0, 0); }
                }
        }
    }

    // =====================================================================================================
    // activity lifecycle
    // =====================================================================================================

    void onStart() {
        tick();
        Tv.cancel(ticker);
        long now = System.currentTimeMillis();
        Tv.later(60000 - now % 60000 + 50, ticker);
        if (shelfA == null && wpId != null) { holdPull(); reloadShelf(); }
        armIdle();
    }

    /** Back from an app: only the sharp photo was let go; decode it again (the blurred copy shows meanwhile). */
    private void reloadShelf() {
        String id = wpId;
        boolean center = "center".equals(Prefs.s("clock"));
        float l = luma;
        int gen = wpGen;
        Tv.bg(() -> {
            Bitmap full = Wallpapers.full(id);
            Bitmap b = bake(full, center, l > 0.55f);
            Tv.free(full);
            return b;
        }, b -> {
            if (b == null || gen != wpGen || shelfA != null) return;
            swapShelf(b, true);
        });
    }

    void onResume() {
        if (pendingSwitcher) { pendingSwitcher = false; Tv.post(this::openSwitcher); return; }
        if (appsDirty && !"os".equals(mode)) { appsDirty = false; loadApps(false); }
        if ("launch".equals(mode)) closeApp();
    }

    /** Another app is in front: no timers, no clock, no pictures the screen does not show. */
    void onStop() {
        Tv.cancel(ticker);
        Tv.cancel(saverIdle);
        Tv.cancel(sleepIdle);
        if ("saver".equals(mode)) stopSaver();
        OS.stopSpeech();
        clockCache.free();
        Wallpapers.trim();
        // the sharp photo (8 MB) is decoded again when the home screen comes back
        if (shelfRun != null) shelfRun.cancel();
        Tv.free(shelfB);
        shelfB = null;
        shelfFade = 1;
        Tv.free(shelfA);
        shelfA = null;
        if (launchBig != null && launched != null && launchBig != launched.banner) Tv.free(launchBig);
        launchBig = launched != null ? launched.banner : null;
        settings.trim();
        switcher.forgetSettings();
        Tv.later(1500, () -> { if (!act.visible) Runtime.getRuntime().gc(); });
    }

    boolean appsDirty;

    void appsChanged() {
        if (act.visible && !"os".equals(mode) && !"launch".equals(mode)) loadApps(false);
        else appsDirty = true;
    }
}
