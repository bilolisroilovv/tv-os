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

/**
 * App switcher (tvOS: press the TV button twice): recent apps as big cards with the app's icon and name above;
 * left/right to browse, OK to return to the app, up to close it (the card flicks away), Back/Home to leave.
 */
final class Switcher {
    static final int CW = 1040, CH = 585, TOP = 300, GAP = 1120, ML = 120, MT = 140, MB = 180;
    final Home h;
    final Views.Abs root;
    final Views.Box bg, empty, hint;
    final List<Card> cards = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    int i;
    OS.Layer layer;

    Switcher(Home h) {
        this.h = h;
        Context c = h.act;
        root = new Views.Abs(c);
        bg = root.add(new Views.Box(c, (cv, v) -> drawBg(cv)), 0, 0, Tv.W, Tv.H);
        empty = root.add(new Views.Box(c, (cv, v) -> drawEmpty(cv)), 0, 0, Tv.W, Tv.H);
        hint = root.add(new Views.Box(c, (cv, v) -> drawHint(cv, v)), 0, Tv.H - 64 - 40, Tv.W, 40);
        root.setVisibility(View.INVISIBLE);
    }

    final class Card extends Views.Box {
        final Apps.App a;
        Bitmap pic; // Settings: a picture of its screen
        final Motion.Val vo;

        Card(Context c, Apps.App a) {
            super(c, null);
            this.a = a;
            painter = (cv, v) -> drawCard(cv);
            setPivotX(ML + CW / 2f);
            setPivotY(MT + CH / 2f);
            vo = Motion.of(this, Motion.ALPHA);
        }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint name = Tv.text(34, 600, 0, 0xffffffff);
        float hdAlpha = 1;

        private void drawCard(Canvas c) {
            RectF r = new RectF(ML, MT, ML + CW, MT + CH);
            SHADOW.draw(c, r.left, r.top, r.right, r.bottom, 1, true);
            if (pic != null) {
                p.setColor(0xff000000);
                p.setShader(Gfx.coverShader(pic, r.left, r.top, r.width(), r.height()));
                c.drawRoundRect(r, 42, 42, p);
                p.setShader(null);
            } else {
                int[] col = a.color;
                p.setColor(0xff000000);
                p.setShader(Gfx.radial(r.centerX(), r.top + CH * .4f, 900, 600, new int[]{Tv.rgba((int) (col[0] * .36f), (int) (col[1] * .36f), (int) (col[2] * .36f), 1), 0xff050506}, new float[]{0, .75f}));
                c.drawRoundRect(r, 42, 42, p);
                p.setShader(null);
                RectF b = new RectF(r.centerX() - 210, r.top + CH * .46f - 126, r.centerX() + 210, r.top + CH * .46f + 126);
                BAN.draw(c, b.left, b.top, b.right, b.bottom, 1, true);
                if (a.banner != null) {
                    p.setColor(0xff000000);
                    p.setShader(Gfx.coverShader(a.banner, b.left, b.top, b.width(), b.height()));
                    c.drawRoundRect(b, 26, 26, p);
                    p.setShader(null);
                }
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Tv.white(.07f));
            RectF o = new RectF(r);
            o.inset(-1, -1);
            c.drawRoundRect(o, 43, 43, p);
            p.setStyle(Paint.Style.FILL);
            if (hdAlpha > 0.01f) {
                // header: the icon and the name above the card
                name.setTypeface(Tv.font(600));
                name.setShadowLayer(Tv.blurRadius(14), 0, 2, Tv.black(.4f * hdAlpha));
                name.setAlpha(Math.round(255 * hdAlpha));
                float tw = Tv.width(name, a.name), total = 100 + 20 + tw, x = r.centerX() - total / 2, cy = MT - 112 + 30;
                RectF ic = new RectF(x, cy - 30, x + 100, cy + 30);
                if (a.banner != null) {
                    p.setColor(Tv.black(hdAlpha));
                    p.setShader(Gfx.coverShader(a.banner, ic.left, ic.top, 100, 60));
                    c.drawRoundRect(ic, 14, 14, p);
                    p.setShader(null);
                }
                p.setAlpha(255);
                c.drawText(a.name, x + 120, Tv.center(name, cy), name);
            }
        }
    }

    private static final Gfx.Shadow SHADOW = new Gfx.Shadow(CW, CH, 42, 0xff000000, new float[][]{{0, 50, 90, -30, .7f}});
    private static final Gfx.Shadow BAN = new Gfx.Shadow(0, 26, 50, 0, 26, Tv.black(.45f));

    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Bitmap bgBmp, bgSrc;

    /** The blurred wallpaper under its dark veil, baked into one small picture: one full-screen quad. */
    private void drawBg(Canvas c) {
        if (Theme.solid) { c.drawColor(Theme.dark ? Tv.rgba(18, 18, 20, .98f) : Tv.rgba(236, 236, 242, .98f)); return; }
        if (bgBmp == null || bgSrc != h.blur48) {
            bgSrc = h.blur48;
            bgBmp = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
            Canvas k = new Canvas(bgBmp);
            k.drawColor(0xff111111);
            if (bgSrc != null) k.drawBitmap(bgSrc, null, new RectF(0, 0, 480, 270), bp);
            k.drawColor(Tv.black(.5f));
        }
        c.drawBitmap(bgBmp, null, new RectF(0, 0, Tv.W, Tv.H), bp);
    }

    /** Fully over the screen: what is under it need not be drawn. */
    boolean covers() { return layer != null && bg.getAlpha() >= 0.999f && bg.getVisibility() == View.VISIBLE; }

    private final TextPaint e1 = Tv.text(38, 600, 0, Tv.white(.8f)), e2 = Tv.text(25, 500, 0, Tv.white(.56f)), hp = Tv.text(23, 400, 0, Tv.white(.6f));

    private void drawEmpty(Canvas c) {
        if (!ids.isEmpty()) return;
        e1.setTextAlign(Paint.Align.CENTER);
        e2.setTextAlign(Paint.Align.CENTER);
        e1.setTypeface(Tv.font(600));
        e2.setTypeface(Tv.font(500));
        float l1 = Tv.lineNormal(e1);
        c.drawText("Нет открытых приложений", 960, Tv.baseline(e1, 500, l1), e1);
        c.drawText("Здесь появятся приложения, которые вы открывали", 960, Tv.baseline(e2, 500 + l1 + 12, Tv.lineNormal(e2)), e2);
    }

    private void drawHint(Canvas c, Views.Box v) {
        if (ids.isEmpty()) return;
        hp.setTextAlign(Paint.Align.CENTER);
        c.drawText("← → выбрать · OK открыть · ↑ закрыть приложение · Назад — выйти", 960, Tv.baseline(hp, 8, Tv.lineNormal(hp)), hp);
    }

    private float cardX(int j) { return 960 - CW / 2f + (j - i) * GAP - ML; }

    private void place(boolean instant) {
        for (int j = 0; j < cards.size(); j++) {
            Card c = cards.get(j);
            boolean on = j == i;
            if (instant) {
                Motion.snap(c, Motion.TX, cardX(j));
                Motion.snap(c, Motion.SCALE, on ? 1 : .84f);
            } else {
                Motion.spring(c, Motion.TX, Motion.SCROLL, cardX(j));
                Motion.spring(c, Motion.SCALE, Motion.FOCUS, on ? 1 : .84f);
                Motion.tween(c, Motion.ALPHA, 300, Tv.EASE, on ? 1 : .62f);
            }
        }
        empty.invalidate();
        hint.invalidate();
        if (i < ids.size()) OS.speak(h.byId.containsKey(ids.get(i)) ? h.byId.get(ids.get(i)).name : "");
    }

    void open() {
        if (layer != null) return;
        ids = new ArrayList<>();
        for (String id : OS.recents()) if (h.byId.containsKey(id)) ids.add(id);
        for (Card c : cards) root.removeView(c);
        cards.clear();
        Card settingsCard = null;
        for (String id : ids) {
            Card c = root.add(new Card(h.act, h.byId.get(id)), 0, TOP - MT, CW + 2 * ML, CH + MT + MB);
            if ("settings".equals(id)) settingsCard = c;
            cards.add(c);
        }
        if (settingsCard != null) {
            if (settingsSnap != null) settingsCard.pic = settingsSnap; // made while the home screen was idle
            else {
                // no picture yet: it is made once the cards have flown in (it costs a few frames)
                Card sc = settingsCard;
                Tv.later(620, () -> {
                    if (layer == null || !cards.contains(sc)) return;
                    settingsSnap = h.settings.snapshot(CW, CH);
                    sc.pic = settingsSnap;
                    sc.invalidate();
                });
            }
        }
        i = Math.max(0, ids.size() - 1);
        place(true);
        Views.show(root);
        Motion.snap(bg, Motion.ALPHA, 0);
        Motion.fade(bg, 350, 1).then(h::updateCover);
        Tv.Ease e = new Tv.Ease(.2f, 1.1f, .35f, 1);
        for (int j = 0; j < cards.size(); j++) {
            Card c = cards.get(j);
            int d = Math.abs(j - i);
            float y0 = 240 + d * 40, s0 = j == i ? .9f : .8f, s1 = j == i ? 1 : .84f, a1 = j == i ? 1 : .62f;
            c.setTranslationY(y0);
            c.setScaleX(s0);
            c.setScaleY(s0);
            c.setAlpha(0);
            Motion.snap(c, Motion.SCALE, s0);
            Motion.snap(c, Motion.ALPHA, 0);
            Tv.later(d * 40L, () -> Motion.run(520, Tv.LINEAR, k -> {
                float p = e.at(k);
                c.setTranslationY(y0 * (1 - p));
                float s = s0 + (s1 - s0) * p;
                c.setScaleX(s);
                c.setScaleY(s);
                c.setAlpha(Math.min(1, a1 * k * 2));
            }).then(() -> { Motion.snap(c, Motion.SCALE, s1); Motion.snap(c, Motion.ALPHA, a1); }));
        }
        layer = this::key;
        OS.push(layer);
    }

    private void close(Runnable then) {
        OS.drop(layer);
        layer = null;
        h.updateCover();
        Motion.fade(bg, 300, 0);
        for (Card c : cards) {
            float s0 = c.getScaleX(), a0 = c.getAlpha();
            Motion.run(260, Tv.EASE, k -> {
                c.setAlpha(a0 * (1 - k));
                float s = s0 + (.8f - s0) * k;
                c.setScaleX(s);
                c.setScaleY(s);
                c.setTranslationY(60 * k);
            });
        }
        Tv.later(300, () -> { if (layer == null) clear(); });
        if (then != null) then.run();
    }

    private void clear() {
        for (Card c : cards) root.removeView(c);
        cards.clear();
        Views.hide(root);
    }

    private Bitmap settingsSnap;
    private final Runnable makeSnap = this::makeSnapNow;

    private void makeSnapNow() {
        if (layer != null || h.settings.isOpen() || !"home".equals(h.mode) && !"bar".equals(h.mode)) return;
        Tv.free(settingsSnap);
        settingsSnap = h.settings.snapshot(CW, CH);
    }

    /** Settings was left: its picture for the switcher's card is made a moment later, while nothing moves. */
    void settingsClosed() {
        Tv.cancel(makeSnap);
        Tv.later(900, makeSnap);
    }

    /** Settings forgot its pages (closed from the switcher, reset) or the app is stopped. */
    void forgetSettings() {
        Tv.cancel(makeSnap);
        Tv.free(settingsSnap);
        settingsSnap = null;
    }

    private void openApp() {
        if (i >= cards.size()) return;
        String id = ids.get(i);
        Card c = cards.get(i);
        Sounds.select();
        float k = 1920f / CW, dy = 540 - (TOP + CH / 2f);
        Tv.Ease e = new Tv.Ease(.3f, .1f, .1f, 1);
        float tx0 = c.getTranslationX(), tx1 = -ML + (960 - CW / 2f) - 0;
        Motion.run(420, Tv.LINEAR, p -> {
            float q = e.at(p), s = 1 + (k - 1) * q;
            c.setScaleX(s);
            c.setScaleY(s);
            c.setTranslationY(dy * q);
            c.setTranslationX(tx0 + (tx1 - tx0) * q);
            c.hdAlpha = 1 - Math.min(1, p * 3);
            c.invalidate();
        });
        for (Card x : cards) if (x != c) Motion.fade(x, 200, 0);
        Motion.fade(bg, 300, 0);
        OS.drop(layer);
        layer = null;
        h.updateCover();
        Tv.later(430, () -> {
            h.resume(id);
            clear();
        });
    }

    private void kill() {
        if (i >= cards.size()) return;
        String id = ids.get(i);
        Card c = cards.get(i);
        float y0 = c.getTranslationY(), s0 = c.getScaleX(), a0 = c.getAlpha();
        Tv.Ease e = new Tv.Ease(.4f, 0, .6f, 1);
        Motion.run(420, Tv.LINEAR, p -> {
            float q = e.at(p);
            c.setTranslationY(y0 - 900 * q);
            float s = s0 + (.9f - s0) * q;
            c.setScaleX(s);
            c.setScaleY(s);
            c.setAlpha(a0 * (1 - q));
        });
        OS.forget(id);
        if ("settings".equals(id)) { h.settings.reset(); forgetSettings(); }
        Tv.later(380, () -> {
            root.removeView(c);
            int k = cards.indexOf(c);
            if (k >= 0) { cards.remove(k); ids.remove(k); }
            i = Math.max(0, Math.min(i, ids.size() - 1));
            place(false);
        });
    }

    void key(String a) {
        switch (a) {
            case "left":
            case "right": {
                int n = i + ("left".equals(a) ? -1 : 1);
                if (n >= 0 && n < ids.size()) { i = n; Sounds.tick(); place(false); } else Sounds.bump();
                break;
            }
            case "up": if (!ids.isEmpty()) kill(); else Sounds.bump(); break;
            case "down": Sounds.bump(); break;
            case "ok": if (!ids.isEmpty()) openApp(); else Sounds.bump(); break;
            case "back":
            case "home":
            case "switcher":
                Sounds.back();
                close(h::switcherClosed);
                break;
            default:
        }
    }
}
