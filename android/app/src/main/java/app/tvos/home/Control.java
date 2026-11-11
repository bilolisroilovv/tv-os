package app.tvos.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * Control Center (tvOS 26): glass controls under the Control Center button. The whole panel is prepared when the
 * launcher starts and springs out of the button as one piece, drawn once into a GPU layer while it moves (the web
 * version flew ten tiles out one by one and froze on its first opening).
 */
final class Control {
    static final int CCX = 1920 - 88 - 430, CCY = 172, MG = 110;
    final Home h;
    final Views.Abs root;
    final CTile[] tiles;
    String over; // Control Center opened over Settings ("os") or a launched app ("launch")
    int ccI;
    boolean open;

    static final int SQ = 0, PILL = 1, SLIDER = 2, RND = 3;

    final class CTile extends Views.Box {
        final String glyph, title;
        final int kind, x, y, w, ht;
        final Runnable act;
        final Gfx.Glass glass;
        float f; // focus 0..1
        final Motion.Val vf;
        String sub = "", tip = "";
        int gl; // pill icon background colour (0 = glass white)
        float fill = 1; // slider fill 0..1
        final Motion.Val vfill;

        CTile(Context c, int kind, String glyph, String title, int x, int y, Runnable act) {
            super(c, null);
            this.kind = kind; this.glyph = glyph; this.title = title; this.x = x; this.y = y; this.act = act;
            w = kind == SQ || kind == PILL ? 206 : kind == SLIDER ? 430 : 94;
            ht = kind == SQ ? 206 : 94;
            float r = kind == SQ ? 36 : 47;
            glass = new Gfx.Glass(w, ht, r).at(CCX + x, CCY + y);
            vf = new Motion.Val(0, 0.004f, v -> { f = v; invalidate(); });
            vfill = new Motion.Val(1, 0.001f, v -> { fill = v; invalidate(); });
            painter = (cv, v) -> paintTile(cv);
            setPivotX(MG + w / 2f);
            setPivotY(MG + ht / 2f);
        }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Gfx.Shadow rest = new Gfx.Shadow(0, 14, 40, 0, 47, Tv.black(.16f));
        private final Gfx.Shadow lift = new Gfx.Shadow(0, 22, 44, 0, 47, Tv.black(.32f));
        private final TextPaint t22 = Tv.text(22, 600, 0, 0xffffffff), b20 = Tv.text(20, 600, 0, 0xffffffff), s17 = Tv.text(17, 400, 0, 0xffffffff), tipP = Tv.text(20, 600, 0, 0xffffffff);
        private final RectF rc = new RectF();
        private final Path clip = new Path();

        private void paintTile(Canvas c) {
            float l = MG, t = MG;
            rest.draw(c, l, t, l + w, t + ht, 1 - f, true);
            lift.draw(c, l, t, l + w, t + ht, f, true);
            if (Theme.solid) glass.solid = Tv.mix(Theme.solidPanel(), 0xfffafafc, f);
            else {
                glass.solid = 0;
                glass.tint(Tv.mix(Gfx.lgA, Tv.white(.62f), f), Tv.mix(Gfx.lgB, Tv.white(.38f), f), Tv.mix(Gfx.lgC, Tv.white(.5f), f));
                glass.topLine = Tv.lerp(.5f, .85f, f);
            }
            glass.draw(c, l, t);
            int fg = Theme.onGlass();
            float sh = Tv.blurRadius(3);
            switch (kind) {
                case SQ: {
                    float top = t + (ht - (82 + 14 + Tv.lineNormal(t22))) / 2;
                    Glyphs.draw(c, glyph, l + w / 2f - 41, top, 82, fg, 2.3f);
                    t22.setTypeface(Tv.font(600));
                    t22.setColor(fg);
                    t22.setTextAlign(Paint.Align.CENTER);
                    t22.setShadowLayer(sh, 0, 1, Tv.black(fg == 0xffffffff ? .22f : 0));
                    c.drawText(title, l + w / 2f, Tv.baseline(t22, top + 82 + 14, Tv.lineNormal(t22)), t22);
                    break;
                }
                case PILL: {
                    float cx = l + 16 + 27, cy = t + ht / 2f;
                    p.setColor(gl != 0 ? gl : Tv.white(.16f));
                    c.drawCircle(cx, cy, 27, p);
                    Glyphs.draw(c, glyph, cx - 15, cy - 15, 30, gl != 0 ? 0xffffffff : fg, 2);
                    b20.setTypeface(Tv.font(600));
                    b20.setColor(fg);
                    b20.setShadowLayer(sh, 0, 1, Tv.black(fg == 0xffffffff ? .22f : 0));
                    s17.setColor(Tv.alpha(fg, .78f));
                    float lb = 23, ls = 20.4f, top = t + (ht - lb - ls) / 2, tx = l + 16 + 54 + 12;
                    c.drawText(title, tx, Tv.baseline(b20, top, lb), b20);
                    c.drawText(Tv.fit(s17, sub, 108), tx, Tv.baseline(s17, top + lb, ls), s17);
                    break;
                }
                case SLIDER: {
                    rc.set(l, t, l + w, t + ht);
                    clip.reset();
                    clip.addRoundRect(rc, 47, 47, Path.Direction.CW);
                    int save = c.save();
                    c.clipPath(clip);
                    p.setColor(Tv.white(.9f));
                    c.drawRect(l, t, l + w * fill, t + ht, p);
                    c.restoreToCount(save);
                    int dark = 0xff1d1d1f, b = h.bright;
                    Glyphs.draw(c, "sun", l + 30, t + 27, 40, b > 14 ? dark : fg, 2);
                    t22.setTypeface(Tv.font(600));
                    t22.clearShadowLayer();
                    t22.setTextAlign(Paint.Align.LEFT);
                    t22.setColor(b > 34 ? dark : fg);
                    c.drawText("Яркость", l + 86, Tv.center(t22, t + ht / 2f), t22);
                    t22.setTextAlign(Paint.Align.RIGHT);
                    t22.setColor(b > 88 ? dark : fg);
                    t22.setFontFeatureSettings("tnum");
                    c.drawText(b + "%", l + w - 30, Tv.center(t22, t + ht / 2f), t22);
                    t22.setFontFeatureSettings(null);
                    break;
                }
                default: {
                    Glyphs.draw(c, glyph, l + w / 2f - 19, t + ht / 2f - 19, 38, fg, 2);
                    if (f > 0.01f && tip != null) {
                        tipP.setTypeface(Tv.font(600));
                        tipP.setShadowLayer(Tv.blurRadius(10), 0, 1, Tv.black(.55f));
                        tipP.setColor(Tv.white(Math.min(1, f)));
                        boolean last = x == 336;
                        tipP.setTextAlign(last ? Paint.Align.RIGHT : Paint.Align.CENTER);
                        c.drawText(tip, last ? l + w : l + w / 2f, Tv.baseline(tipP, t + 108, Tv.lineNormal(tipP)), tipP);
                    }
                }
            }
        }
    }

    Control(Home h) {
        this.h = h;
        Context c = h.act;
        root = new Views.Abs(c);
        root.setPivotX(1796 - (CCX - MG));
        root.setPivotY(106 - (CCY - MG));
        tiles = new CTile[]{
                new CTile(c, SQ, "power", "Режим сна", 0, 0, () -> { close(false); h.sleepNow(); }),
                new CTile(c, PILL, "wifi3", "Сеть", 224, 0, () -> {
                    String o = over;
                    close(true);
                    if (o != null) { if ("launch".equals(o)) h.leaveApp(); h.openSettings(new String[]{"network", "wifi"}, false, "os".equals(o)); }
                    else { h.barI = 1; h.openWifi(); }
                }),
                new CTile(c, PILL, "input", "Источник", 224, 112, () -> {
                    String o = over;
                    close(true);
                    if (o != null) { h.leaveApp(); h.unfocusApp(); h.mode = "bar"; }
                    h.barI = 0;
                    h.openInputs();
                }),
                new CTile(c, PILL, "sound", "Звук", 0, 224, () -> {
                    String o = over;
                    close(true);
                    if ("launch".equals(o)) h.leaveApp();
                    h.openSettings(new String[]{"av"}, false, "os".equals(o));
                }),
                new CTile(c, PILL, "timer", "Таймер сна", 224, 224, () -> { h.setSleepTimer((h.sleepI + 1) % Home.SLEEP_OPTS.length); refresh(); }),
                new CTile(c, SLIDER, "sun", "", 0, 336, () -> {}),
                new CTile(c, RND, "photo", "", 0, 448, () -> { String o = over; close(false); if (o != null) h.leaveApp(); h.picker.open(); }),
                new CTile(c, RND, "ccsaver", "", 112, 448, () -> { close(false); h.startSaver(false); }),
                new CTile(c, RND, "cclook", "", 224, 448, () -> { Prefs.set("look", Theme.effDark() ? "light" : "dark"); refresh(); }),
                new CTile(c, RND, "ccgear", "", 336, 448, () -> {
                    String o = over;
                    close(false);
                    if ("os".equals(o)) return;
                    if (o != null) h.leaveApp();
                    h.openSettings(null, false, false);
                }),
        };
        for (CTile t : tiles) root.add(t, MG + t.x - MG, MG + t.y - MG, t.w + 2 * MG, t.ht + 2 * MG);
        root.setAlpha(0);
        root.setVisibility(View.INVISIBLE);
        refresh();
    }

    void invalidate() { for (CTile t : tiles) t.invalidate(); }

    /** The values shown on the tiles. */
    void refresh() {
        if (tiles == null) return;
        if (open) Tv.bg(() -> Device.net(h.act), n -> { // the network is read off the UI thread
            if (n == null) return;
            tiles[1].sub = Device.netName(n);
            tiles[1].gl = n.connected ? 0xff0a84ff : 0;
            tiles[1].invalidate();
        });
        tiles[2].sub = h.inCur >= 0 && h.inCur < h.inputList.size() ? h.inputList.get(h.inCur).name : "Главный экран";
        tiles[3].sub = "Звуки интерфейса";
        tiles[4].sub = Home.SLEEP_OPTS[h.sleepI];
        tiles[4].gl = h.sleepI > 0 ? 0xffff9f0a : 0;
        tiles[5].vfill.tween(320, new Tv.Ease(.2f, .9f, .3f, 1), h.bright / 100f);
        tiles[6].tip = "Фоны";
        tiles[7].tip = "Заставка";
        tiles[8].tip = Theme.effDark() ? "Тёмное оформление" : "Светлое оформление";
        tiles[9].tip = "Настройки";
        invalidate();
    }

    private void paint() {
        for (int i = 0; i < tiles.length; i++) {
            boolean on = i == ccI;
            CTile t = tiles[i];
            t.vf.tween(220, Tv.EASE, on ? 1 : 0);
            Motion.spring(t, Motion.SCALE, Motion.FOCUS, on ? 1.08f : 1);
        }
        CTile c = tiles[ccI];
        OS.speak(c.kind == SLIDER ? "Яркость " + h.bright + "%" : (c.kind == RND ? c.tip : c.title + ", " + c.sub));
    }

    void open() {
        String m = h.mode;
        if ("os".equals(m) || "launch".equals(m)) {
            over = m;
            h.stage.setZ(h.shade, 34);
            h.stage.setZ(h.hud, 35);
            h.stage.setZ(root, 36);
            Views.show(h.hud);
            Motion.snap(h.hud, Motion.TY, 0);
            Motion.snap(h.hud, Motion.ALPHA, 1);
            h.clock.setAlpha(0);
            h.place.setVisibility(View.INVISIBLE);
            h.settings.ccover(true);
            h.updateCover();
        } else {
            over = null;
            if ("home".equals(m)) h.unfocusApp();
            if (h.row > 0) h.setRow(0);
        }
        h.mode = "cc";
        open = true;
        ccI = 0;
        refresh();
        for (int i = 0; i < tiles.length; i++) { tiles[i].vf.snap(i == 0 ? 1 : 0); Motion.snap(tiles[i], Motion.SCALE, i == 0 ? 1.08f : 1); }
        paint();
        h.ccShade(true);
        h.paintBar();
        Motion.fade(h.barTime, 300, 1);
        Motion.snap(h.barTime, Motion.TX, 14);
        Motion.spring(h.barTime, Motion.TX, Motion.PANEL, 0);
        // one piece, out of the button
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        Motion.snap(root, Motion.SCALE, .3f);
        Motion.snap(root, Motion.ALPHA, 0);
        Motion.fade(root, 200, 1);
        Motion.spring(root, Motion.SCALE, Motion.CC, 1).then(() -> { if (open) root.setLayerType(View.LAYER_TYPE_NONE, null); });
    }

    void close(boolean keepBar) {
        open = false;
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        Motion.fade(root, 200, 0);
        Motion.spring(root, Motion.SCALE, Motion.PANEL, .4f).then(() -> { if (!open) root.setLayerType(View.LAYER_TYPE_NONE, null); });
        h.ccShade(false);
        Motion.fade(h.barTime, 300, 0);
        Motion.spring(h.barTime, Motion.TX, Motion.PANEL, 14);
        if (over != null) {
            String o = over;
            h.mode = o;
            over = null;
            h.paintBar();
            Tv.later(340, () -> {
                if ("cc".equals(h.mode)) return;
                h.stage.setZ(h.shade, 3);
                h.stage.setZ(h.hud, 4);
                h.stage.setZ(root, 7);
                h.clock.setAlpha(1);
                h.place.setVisibility(View.VISIBLE);
                h.settings.ccover(false);
                if (h.grid) { Motion.snap(h.hud, Motion.TY, -140); Motion.snap(h.hud, Motion.ALPHA, 0); }
                h.updateCover();
            });
            return;
        }
        h.mode = "bar";
        h.barI = 2;
        if (!keepBar) h.paintBar(); else h.bar[2].set(false);
    }

    /** tvOS focus search: tiles in the pressed direction (edge to edge), nearest first (web spatial()). */
    private int spatial(int from, int dx, int dy) {
        CTile c = tiles[from];
        boolean horiz = dx != 0;
        int best = -1;
        float bs = Float.MAX_VALUE;
        for (int i = 0; i < tiles.length; i++) {
            if (i == from) continue;
            CTile r = tiles[i];
            float main = dy > 0 ? r.y - (c.y + c.ht) : dy < 0 ? c.y - (r.y + r.ht) : dx > 0 ? r.x - (c.x + c.w) : c.x - (r.x + r.w);
            if (main < -10) continue;
            float cross = horiz ? gap(c.y, c.y + c.ht, r.y, r.y + r.ht) : gap(c.x, c.x + c.w, r.x, r.x + r.w);
            float off = horiz ? Math.abs(r.y + r.ht / 2f - (c.y + c.ht / 2f)) : Math.abs(r.x + r.w / 2f - (c.x + c.w / 2f));
            float s = Math.max(0, main) + cross * 2.2f + off * 0.05f;
            if (s < bs) { bs = s; best = i; }
        }
        return best;
    }

    private static float gap(float a0, float a1, float b0, float b1) { return Math.max(0, Math.max(a0, b0) - Math.min(a1, b1)); }

    void key(String a, int dx, int dy) {
        CTile t = tiles[ccI];
        if (dx != 0 && t.kind == SLIDER) {
            if (h.setBright(h.bright + dx * 10)) Sounds.tick(); else h.bumpView(t, dx, 0);
            return;
        }
        if (dx != 0 || dy != 0) {
            int n = spatial(ccI, dx, dy);
            if (n >= 0) { ccI = n; Sounds.tick(); paint(); } else h.bumpView(t, dx, dy);
        } else if ("ok".equals(a)) { Sounds.select(); t.act.run(); }
        else if ("back".equals(a)) { Sounds.back(); close(false); }
    }
}
