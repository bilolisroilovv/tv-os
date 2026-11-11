package app.tvos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** The wallpaper sheet (web #picker): thumbnails that preview as you browse, appearance and clock position below. */
final class Picker {
    static final int X = 40, Y = 1080 - 34 - 500, W = 1840, HT = 500, MG = 80;
    final Home h;
    final Views.Abs root, track;
    final Views.Box sheet;
    final List<Thumb> thumbs = new ArrayList<>();
    final Views.Box[] seg = new Views.Box[4];
    final Views.Box segLabels;
    List<Wallpapers.W> items = new ArrayList<>();
    int pI, pRow, pI2;
    String from = "home";
    private final Gfx.Glass glass = new Gfx.Glass(W, HT, 58).at(X, Y);
    private static final String[] SEG = {"Светлое", "Тёмное", "По центру", "Слева"};
    private final float[] segX = new float[4], segW = new float[4];

    Picker(Home h) {
        this.h = h;
        Context c = h.act;
        root = new Views.Abs(c);
        glass.shadow = new Gfx.Shadow(0, 14, 40, 0, 58, Tv.black(.16f));
        sheet = root.add(new Views.Box(c, (cv, v) -> paintSheet(cv)), 0, 0, W + 2 * MG, HT + 2 * MG);
        // thumbnails ride on a track that scrolls sideways; the visible strip is clipped like overflow: hidden
        Views.Abs clipBox = new Views.Abs(c);
        clipBox.setClipChildren(true);
        root.add(clipBox, MG, MG + 140, W, 270);
        track = clipBox.add(new Views.Abs(c), 0, 0, 40 * 370 + 400, 270);
        // segmented options: "Оформление: Светлое Тёмное   Часы: По центру Слева"
        TextPaint lab = Tv.text(21, 400, 0, 0), opt = Tv.text(21, 400, 0, 0);
        float x = MG + 74 + Tv.width(lab, "Оформление:") + 16;
        for (int i = 0; i < 4; i++) {
            if (i == 2) x += 26 + Tv.width(lab, "Часы:") + 16;
            segW[i] = Tv.width(opt, SEG[i]) + 52;
            segX[i] = x;
            final int k = i;
            segV[i] = new Motion.Val(0, 0.004f, v -> { segF[k] = v; seg[k].invalidate(); });
            seg[i] = root.add(new Views.Box(c, (cv, v) -> paintSeg(cv, v, k)), Math.round(x) - 20, MG + HT - 30 - 48 - 20, Math.round(segW[i]) + 40, 88);
            seg[i].setPivotX(segW[i] / 2 + 20);
            seg[i].setPivotY(44);
            x += segW[i] + 16;
        }
        segLabels = root.add(new Views.Box(c, (cv, v) -> paintSegLabels(cv)), 0, MG + HT - 30 - 48, W + 2 * MG, 48);
        root.setTranslationY(560);
        root.setVisibility(View.INVISIBLE);
    }

    void invalidate() { sheet.invalidate(); }

    private final TextPaint h2 = Tv.text(42, 700, -.5f, 0xffffffff), sub = Tv.text(21, 400, 0, Tv.white(.7f));

    private void paintSheet(Canvas c) {
        glass.adaptive();
        glass.solid = Theme.solid ? Theme.solidPanel() : 0;
        glass.draw(c, MG, MG);
        int fg = Theme.onGlass();
        h2.setTypeface(Tv.font(700));
        h2.setColor(fg);
        float top = MG + 44;
        c.drawText("Фоны", MG + 74, Tv.baseline(h2, top, Tv.lineNormal(h2)), h2);
    }

    private final TextPaint segP = Tv.text(21, 400, 0, 0xffffffff);
    private final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] segF = new float[4];
    private final Motion.Val[] segV = new Motion.Val[4];

    private void paintSegLabels(Canvas c) {
        int fg = Theme.onGlass();
        segP.setColor(Tv.alpha(fg, .72f));
        float cy = 24;
        c.drawText("Оформление:", MG + 74, Tv.center(segP, cy), segP);
        c.drawText("Часы:", segX[2] - 16 - Tv.width(segP, "Часы:"), Tv.center(segP, cy), segP);
    }

    private void paintSeg(Canvas c, Views.Box v, int i) {
        boolean cur = i < 2 ? (i == 1) == Theme.effDark() : (i == 2) == "center".equals(Prefs.s("clock"));
        float f = segF[i];
        int fg = Theme.onGlass();
        sp.setColor(Tv.mix(Tv.white(cur ? .3f : .12f), 0xffffffff, f));
        RectF r = new RectF(20, 20, 20 + segW[i], 68);
        c.drawRoundRect(r, 26, 26, sp);
        segP.setColor(Tv.mix(fg, 0xff111111, f));
        segP.setTextAlign(Paint.Align.CENTER);
        c.drawText(SEG[i], r.centerX(), Tv.center(segP, r.centerY()), segP);
        segP.setTextAlign(Paint.Align.LEFT);
    }

    /** One thumbnail with its name under it (web .th). */
    final class Thumb extends Views.Box {
        final Wallpapers.W w;
        Bitmap pic;
        float f;
        final Motion.Val vf = new Motion.Val(0, 0.003f, v -> { f = v; invalidate(); });
        boolean loading;

        Thumb(Context c, Wallpapers.W w) {
            super(c, null);
            this.w = w;
            painter = (cv, v) -> paintThumb(cv);
            setPivotX(40 + 168);
            setPivotY(22 + 94.5f);
        }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint n = Tv.text(21, 500, 0, 0xffffffff);
        private final RectF r = new RectF(40, 22, 40 + 336, 22 + 189);

        private void paintThumb(Canvas c) {
            REST.draw(c, r.left, r.top, r.right, r.bottom, 1 - f, true);
            LIFT.draw(c, r.left, r.top, r.right, r.bottom, f, true);
            if (pic != null) {
                p.setColor(0xff000000);
                p.setShader(Gfx.coverShader(pic, r.left, r.top, r.width(), r.height()));
                c.drawRoundRect(r, 24, 24, p);
                p.setShader(null);
            } else {
                p.setColor(0xff333333);
                c.drawRoundRect(r, 24, 24, p);
            }
            if (w.id.equals(Wallpapers.current())) {
                p.setColor(0xffffffff);
                c.drawCircle(r.right - 12 - 19, r.top + 12 + 19, 19, p);
                Glyphs.draw(c, "check", r.right - 12 - 19 - 11, r.top + 12 + 19 - 11, 22, 0xff111111, 3);
            }
            n.setTypeface(Tv.font(500));
            n.setColor(Tv.mix(Tv.alpha(Theme.onGlass(), .72f), Theme.onGlass(), f));
            n.setTextAlign(Paint.Align.CENTER);
            c.drawText(Tv.fit(n, w.name, 356), r.centerX(), Tv.baseline(n, r.top + 204 - 0, Tv.lineNormal(n)) - 0, n);
        }
    }

    private static final Gfx.Shadow REST = new Gfx.Shadow(0, 8, 16, 0, 24, Tv.black(.25f));
    private static final Gfx.Shadow LIFT = new Gfx.Shadow(0, 32, 40, -12, 24, Tv.black(.55f));

    private void build() {
        items = Wallpapers.list();
        track.removeAllViews();
        thumbs.clear();
        for (int i = 0; i < items.size(); i++) {
            Thumb t = track.add(new Thumb(h.act, items.get(i)), i * 370 + 74 - 40, 0, 336 + 80, 270);
            thumbs.add(t);
        }
    }

    /** Thumbnails are decoded only around the focus (each is half a megabyte). */
    private void loadAround() {
        for (int i = 0; i < thumbs.size(); i++) {
            Thumb t = thumbs.get(i);
            boolean near = Math.abs(i - pI) <= 6;
            if (near && t.pic == null && !t.loading) {
                t.loading = true;
                Wallpapers.thumbAsync(t.w.id, b -> { t.loading = false; t.pic = b; t.invalidate(); });
            } else if (!near && t.pic != null) { t.pic = null; t.invalidate(); }
        }
    }

    void paint() {
        for (int i = 0; i < thumbs.size(); i++) {
            Thumb t = thumbs.get(i);
            boolean on = pRow == 0 && i == pI;
            t.vf.tween(300, Tv.EASE, on ? 1 : 0);
            Motion.spring(t, Motion.SCALE, Motion.FOCUS, on ? 1.1f : 1);
            t.invalidate();
        }
        Motion.spring(track, Motion.TX, Motion.SCROLL, -Math.max(0, Math.min(pI - 2, items.size() - 5)) * 370);
        for (int i = 0; i < 4; i++) {
            boolean on = pRow == 1 && i == pI2;
            segV[i].tween(200, Tv.EASE, on ? 1 : 0);
            Motion.spring(seg[i], Motion.SCALE, Motion.FOCUS, on ? 1.08f : 1);
            seg[i].invalidate();
        }
        loadAround();
        if (pRow == 0 && pI < items.size()) OS.speak(items.get(pI).name);
        else if (pRow == 1) OS.speak(SEG[pI2]);
    }

    private final Runnable preview = this::doPreview;
    private String previewId;

    /** Preview while browsing: waits until the arrow rests for a moment, then the photo cross-fades. */
    private void previewWall(String id) {
        Tv.cancel(preview);
        previewId = id;
        Tv.later(170, preview);
    }

    private void doPreview() {
        if (!"picker".equals(h.mode) || previewId == null) return;
        h.previewWallpaper(previewId);
    }

    void open() {
        from = "bar".equals(h.mode) ? "bar" : "home";
        if ("home".equals(h.mode)) h.unfocusApp();
        if (h.row > 0) h.setRow(0);
        h.mode = "picker";
        h.picking(true);
        build();
        pI = 0;
        String cur = Wallpapers.current();
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(cur)) pI = i;
        pRow = 0;
        pI2 = Theme.effDark() ? 1 : 0;
        Motion.snap(track, Motion.TX, -Math.max(0, Math.min(pI - 2, items.size() - 5)) * 370);
        paint();
        Views.show(root);
        Motion.spring(root, Motion.TY, Motion.PANEL, 0);
    }

    void close() {
        Motion.spring(root, Motion.TY, Motion.PANEL, 560).then(() -> {
            if (!"picker".equals(h.mode)) {
                Views.hide(root);
                track.removeAllViews();
                thumbs.clear();
                Wallpapers.trim(); // the thumbnails (half a megabyte each) are not kept
            }
        });
        h.picking(false);
        h.previewWallpaper(Wallpapers.current());
        if ("bar".equals(from)) { h.mode = "bar"; h.paintBar(); }
        else { h.mode = "home"; h.focusApp(h.cur, 0, 0); }
    }

    void key(String a, int dx, int dy) {
        if (dx != 0) {
            if (pRow == 0) {
                int n = pI + dx;
                if (n >= 0 && n < items.size()) { pI = n; Sounds.tick(); previewWall(items.get(pI).id); } else Sounds.bump();
            } else {
                int n = pI2 + dx;
                if (n >= 0 && n < 4) { pI2 = n; Sounds.tick(); } else Sounds.bump();
            }
            paint();
        } else if (dy > 0 && pRow == 0) { pRow = 1; Sounds.tick(); paint(); }
        else if (dy < 0 && pRow == 1) { pRow = 0; Sounds.tick(); paint(); }
        else if ("ok".equals(a)) {
            Sounds.select();
            if (pRow == 1) {
                if (pI2 < 2) Prefs.set("look", pI2 == 1 ? "dark" : "light");
                else Prefs.set("clock", pI2 == 2 ? "center" : "left");
                for (Views.Box s : seg) s.invalidate();
                return;
            }
            Wallpapers.set(items.get(pI).id);
            for (Thumb t : thumbs) t.invalidate();
        } else if ("back".equals(a)) { Sounds.back(); close(); }
    }
}
