package app.tvos.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Alerts and the on-screen keyboard (tvOS style): a full-screen veil of the blurred wallpaper, centred text and a
 * column of buttons; the keyboard is tvOS's linear one, a single row of letters over a row of controls.
 */
final class Modal {
    private Modal() {}

    private static Home h;
    private static Views.Abs alertRoot, kbRoot;
    private static Views.Box alertVeil, alertBox, kbVeil, kbBox;
    private static Bitmap veil;
    private static boolean veilDark;

    static void init(Home home) {
        h = home;
        android.content.Context c = home.act;
        alertRoot = home.stage.add(new Views.Abs(c), 70);
        alertRoot.overlapping = false; // the veil and the text fade on their own: no offscreen layer
        alertVeil = alertRoot.add(new Views.Box(c, (cv, v) -> drawVeil(cv)), 0, 0, Tv.W, Tv.H);
        alertBox = alertRoot.add(new Views.Box(c, (cv, v) -> drawAlert(cv, v)), 360, 0, 1200, Tv.H);
        alertBox.setPivotX(600);
        alertBox.setPivotY(540);
        kbRoot = home.stage.add(new Views.Abs(c), 70);
        kbRoot.overlapping = false;
        kbVeil = kbRoot.add(new Views.Box(c, (cv, v) -> drawVeil(cv)), 0, 0, Tv.W, Tv.H);
        kbBox = kbRoot.add(new Views.Box(c, (cv, v) -> drawKb(cv)), 0, 0, Tv.W, Tv.H);
        alertRoot.setAlpha(0);
        alertRoot.setVisibility(View.INVISIBLE);
        kbRoot.setAlpha(0);
        kbRoot.setVisibility(View.INVISIBLE);
    }

    static void themeChanged() {
        veil = null;
        if (alertRoot != null) { alertVeil.invalidate(); alertBox.invalidate(); kbVeil.invalidate(); kbBox.invalidate(); }
    }

    /** The veil: the blurred wallpaper under a light or dark tint (fast mode of the web version), baked small. */
    private static final Paint vp = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static void drawVeil(Canvas c) {
        if (Theme.solid) { c.drawColor(Theme.dark ? Tv.rgba(18, 18, 20, .98f) : Tv.rgba(236, 236, 242, .98f)); return; }
        Bitmap b = h.blur48;
        if (veil == null || veilDark != Theme.dark || veilSrc != b) {
            veilSrc = b;
            veilDark = Theme.dark;
            veil = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
            Canvas k = new Canvas(veil);
            k.drawColor(Theme.dark ? 0xff111111 : 0xffe6e6eb);
            if (b != null) Wallpapers.drawCover(k, b, 0, 0, 480, 270, vp);
            k.drawColor(Theme.dark ? Tv.rgba(10, 10, 12, .74f) : Tv.rgba(236, 236, 242, .78f));
        }
        c.drawBitmap(veil, null, new RectF(0, 0, Tv.W, Tv.H), vp);
    }

    private static Bitmap veilSrc;

    private static void fade(View root, boolean on) {
        if (!on) h.updateCover(); // what is under it is drawn again before the fade starts
        Motion.fade(root, 280, on ? 1 : 0).then(h::updateCover);
    }

    /** An alert or the keyboard fully over the screen (its veil is opaque). */
    static boolean covers() {
        return alertRoot != null && (aLayer != null && alertRoot.getAlpha() >= 0.999f || kLayer != null && kbRoot.getAlpha() >= 0.999f);
    }

    // =====================================================================================================
    // alert
    // =====================================================================================================

    private static String aTitle, aText, aGlyph;
    private static Bitmap aPic;
    private static String[] aBtns;
    private static boolean[] aRed;
    private static int aI, aCancel;
    private static final List<Motion.Val> aF = new ArrayList<>();
    private static float[] aFv;
    private static IntConsumer aDone;
    private static OS.Layer aLayer;
    private static StaticLayout aLayout;

    static void alert(String title, String text, Bitmap pic, String glyph, String[] buttons, boolean[] red, int cancelIndex, int focus, IntConsumer done) {
        if (aLayer != null) OS.drop(aLayer);
        aTitle = title;
        aText = text == null ? "" : text;
        aPic = pic;
        aGlyph = glyph;
        aBtns = buttons == null || buttons.length == 0 ? new String[]{"OK"} : buttons;
        aRed = red != null ? red : new boolean[aBtns.length];
        aCancel = cancelIndex;
        aI = Math.max(0, Math.min(focus, aBtns.length - 1));
        aDone = done;
        aLayout = null;
        aF.clear();
        aFv = new float[aBtns.length];
        for (int i = 0; i < aBtns.length; i++) {
            final int k = i;
            Motion.Val v = new Motion.Val(i == aI ? 1 : 0, 0.002f, x -> { aFv[k] = x; alertBox.invalidate(); });
            aFv[i] = i == aI ? 1 : 0;
            aF.add(v);
        }
        alertBox.invalidate();
        Views.show(alertRoot);
        fade(alertRoot, true);
        Motion.snap(alertBox, Motion.SCALE, 1.06f);
        Motion.spring(alertBox, Motion.SCALE, Motion.PANEL, 1);
        OS.speak(title + ". " + aText + ". " + aBtns[aI]);
        aLayer = a -> {
            if ("up".equals(a) || "down".equals(a)) {
                int n = aI + ("up".equals(a) ? -1 : 1);
                if (n >= 0 && n < aBtns.length) {
                    aF.get(aI).spring(Motion.FOCUS, 0);
                    aI = n;
                    aF.get(aI).spring(Motion.FOCUS, 1);
                    Sounds.tick();
                    OS.speak(aBtns[aI]);
                } else Sounds.bump();
            } else if ("left".equals(a) || "right".equals(a)) Sounds.bump();
            else if ("ok".equals(a)) { Sounds.select(); closeAlert(aI); }
            else if ("back".equals(a) || "home".equals(a)) { Sounds.back(); closeAlert(aCancel); }
        };
        OS.push(aLayer);
    }

    /** A simple information alert with an OK button. */
    static void info(String title, String text, String glyph) {
        alert(title, text, null, glyph, new String[]{"OK"}, null, 0, 0, null);
    }

    private static void closeAlert(int r) {
        OS.drop(aLayer);
        aLayer = null;
        fade(alertRoot, false);
        IntConsumer d = aDone;
        aDone = null;
        if (d != null) d.accept(r);
    }

    private static final TextPaint aH2 = Tv.text(48, 700, -.5f, 0), aP = Tv.text(29, 400, 0, 0), aB = Tv.text(31, 600, 0, 0);
    private static final Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Gfx.Shadow BTN_SHADOW = new Gfx.Shadow(0, 22, 44, -10, 24, Tv.black(.36f));

    private static void drawAlert(Canvas c, Views.Box v) {
        if (aBtns == null) return;
        float cx = v.getWidth() / 2f;
        aH2.setTypeface(Tv.font(700));
        aH2.setColor(Theme.label());
        aH2.setTextAlign(Paint.Align.CENTER);
        aP.setColor(Theme.sec());
        if (aLayout == null && !aText.isEmpty()) {
            aLayout = StaticLayout.Builder.obtain(aText, 0, aText.length(), aP, 860).setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(29 * 1.42f - Tv.lineNormal(aP), 1).setIncludePad(false).build();
        }
        boolean hasIcon = aPic != null || aGlyph != null;
        float h2 = Tv.lineNormal(aH2), ph = aLayout == null ? 0 : aLayout.getHeight();
        float total = (hasIcon ? 120 + 30 : 0) + h2 + (aLayout != null ? 18 + ph : 0) + 56 + aBtns.length * 86 + (aBtns.length - 1) * 18;
        float y = 540 - total / 2;
        if (hasIcon) {
            if (aPic != null) {
                RectF r = new RectF(cx - 100, y, cx + 100, y + 120);
                ap.setColor(0xff000000);
                ap.setShader(Gfx.coverShader(aPic, r.left, r.top, r.width(), r.height()));
                c.drawRoundRect(r, 24, 24, ap);
                ap.setShader(null);
            } else Glyphs.draw(c, aGlyph, cx - 36, y + 24, 72, Theme.label(), 1.7f);
            y += 150;
        }
        c.drawText(aTitle, cx, Tv.baseline(aH2, y, h2), aH2);
        y += h2;
        if (aLayout != null) {
            y += 18;
            c.save();
            c.translate(cx - 430, y);
            aLayout.draw(c);
            c.restore();
            y += ph;
        }
        y += 56;
        aB.setTypeface(Tv.font(600));
        aB.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < aBtns.length; i++) {
            float f = aFv[i], s = 1 + 0.08f * f;
            RectF r = new RectF(cx - 300, y, cx + 300, y + 86);
            c.save();
            c.scale(s, s, r.centerX(), r.centerY());
            if (f > 0.01f) BTN_SHADOW.draw(c, r.left, r.top, r.right, r.bottom, f, true);
            ap.setColor(Tv.mix(Theme.fill(), 0xffffffff, f));
            c.drawRoundRect(r, 24, 24, ap);
            if (Theme.contrast && f > 0.5f) {
                ap.setStyle(Paint.Style.STROKE);
                ap.setStrokeWidth(4);
                ap.setColor(Theme.dark ? 0xffffffff : 0xff1d1d1f);
                RectF o = new RectF(r);
                o.inset(-2, -2);
                c.drawRoundRect(o, 26, 26, ap);
                ap.setStyle(Paint.Style.FILL);
            }
            int base = aRed[i] ? Theme.red() : Theme.label(), on = aRed[i] ? 0xffd70015 : 0xff1d1d1f;
            aB.setColor(Tv.mix(base, on, f));
            c.drawText(aBtns[i], r.centerX(), Tv.center(aB, r.centerY()), aB);
            c.restore();
            y += 86 + 18;
        }
    }

    // =====================================================================================================
    // keyboard (tvOS linear keyboard)
    // =====================================================================================================

    private static final String RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя", EN = "abcdefghijklmnopqrstuvwxyz", NUM = "1234567890.,-_@!?#$%&*()/:;+=\'\"";
    private static String kTitle, kSub, kPh, kDoneLabel, kValue;
    private static String kLay;
    private static boolean kCaps, kNum, kSecure, kShow;
    private static int kRow, kKi, kCi, kMax, kReveal = -1;
    private static Consumer<String> kDone;
    private static OS.Layer kLayer;
    private static float kKeyF = 1, kKeyPrevF;
    private static int kPrevKi = -1;
    private static boolean kCaret = true;
    private static final Motion.Val kKeyV = new Motion.Val(1, 0.002f, x -> { kKeyF = x; kbBox.invalidate(); });
    private static final Motion.Val kPrevV = new Motion.Val(0, 0.002f, x -> { kKeyPrevF = x; kbBox.invalidate(); });
    private static final Runnable blink = new Runnable() {
        @Override
        public void run() {
            if (kLayer == null) return;
            kCaret = !kCaret;
            kbBox.invalidate();
            Tv.later(525, this);
        }
    };
    private static final Runnable unreveal = () -> { kReveal = -1; kbBox.invalidate(); };

    static boolean keyboardOpen() { return kLayer != null; }

    static void keyboard(String title, String sub, String value, String placeholder, int max, boolean secure, String doneLabel, Consumer<String> done) {
        kTitle = title == null ? "" : title;
        kSub = sub == null ? "" : sub;
        kValue = value == null ? "" : value;
        kPh = placeholder == null ? "" : placeholder;
        kMax = max > 0 ? max : 64;
        kSecure = secure;
        kDoneLabel = doneLabel == null ? "Готово" : doneLabel;
        kDone = done;
        kLay = kValue.matches(".*[a-zA-Z].*") || secure ? "en" : "ru";
        kCaps = kNum = kShow = false;
        kRow = 0;
        kKi = 0;
        kCi = 0;
        kReveal = -1;
        kPrevKi = -1;
        kKeyV.snap(1);
        kPrevV.snap(0);
        kbBox.invalidate();
        Views.show(kbRoot);
        fade(kbRoot, true);
        kLayer = Modal::kbKey;
        OS.push(kLayer);
        kCaret = true;
        Tv.cancel(blink);
        Tv.later(525, blink);
        OS.speak(kTitle + ". " + kSub);
    }

    private static String chars() {
        String s = kNum ? NUM : "ru".equals(kLay) ? RU : EN;
        return kCaps && !kNum ? s.toUpperCase() : s;
    }

    private static final class Ctl {
        final String id, text, glyph;
        final boolean go, cur;

        Ctl(String id, String text, String glyph, boolean go, boolean cur) { this.id = id; this.text = text; this.glyph = glyph; this.go = go; this.cur = cur; }
    }

    private static List<Ctl> ctls() {
        List<Ctl> l = new ArrayList<>();
        boolean ru = "ru".equals(kLay);
        l.add(new Ctl("space", "Пробел", null, false, false));
        l.add(new Ctl("del", "", "del", false, false));
        if (!kNum) l.add(new Ctl("caps", ru ? (kCaps ? "абв" : "АБВ") : (kCaps ? "abc" : "ABC"), null, false, kCaps));
        l.add(new Ctl("num", kNum ? (ru ? "абв" : "abc") : "123", null, false, kNum));
        l.add(new Ctl("lang", ru ? "English" : "Русский", "globe", false, false));
        if (kSecure) l.add(new Ctl("show", kShow ? "Скрыть" : "Показать", "eye", false, false));
        l.add(new Ctl("done", kDoneLabel, null, true, false));
        return l;
    }

    private static void focusKey(int n) {
        if (n == kKi) return;
        kPrevKi = kKi;
        kPrevV.snap(kKeyF);
        kPrevV.spring(Motion.FOCUS, 0);
        kKi = n;
        kKeyV.snap(0);
        kKeyV.spring(Motion.FOCUS, 1);
    }

    private static void type(String ch) {
        if (kValue.length() >= kMax) { Sounds.bump(); return; }
        kValue += ch;
        kReveal = kValue.length() - 1;
        Tv.cancel(unreveal);
        Tv.later(1100, unreveal);
        kbBox.invalidate();
    }

    private static boolean erase() {
        if (kValue.isEmpty()) { Sounds.bump(); return false; }
        kValue = kValue.substring(0, kValue.length() - 1);
        kReveal = -1;
        kbBox.invalidate();
        return true;
    }

    /** Text typed on a connected keyboard goes straight into the field. */
    static boolean typed(int unicode, boolean backspace) {
        if (kLayer == null) return false;
        if (backspace) { if (erase()) Sounds.del(); return true; }
        if (unicode <= 0 || Character.isISOControl(unicode)) return false;
        type(new String(Character.toChars(unicode)));
        Sounds.key();
        return true;
    }

    private static void act(String id) {
        switch (id) {
            case "space": type(" "); Sounds.key(); break;
            case "del": if (erase()) Sounds.del(); break;
            case "caps": kCaps = !kCaps; Sounds.select(); break;
            case "num": kNum = !kNum; Sounds.select(); break;
            case "lang": kLay = "ru".equals(kLay) ? "en" : "ru"; kNum = false; Sounds.select(); break;
            case "show": kShow = !kShow; Sounds.select(); break;
            default: Sounds.select(); finishKb(kValue);
        }
        kKi = Math.min(kKi, chars().length() - 1);
        kCi = Math.min(kCi, ctls().size() - 1);
        kbBox.invalidate();
    }

    private static void kbKey(String a) {
        String cs = chars();
        List<Ctl> cl = ctls();
        switch (a) {
            case "left":
            case "right": {
                int d = "left".equals(a) ? -1 : 1;
                if (kRow == 0) {
                    int n = kKi + d;
                    if (n >= 0 && n < cs.length()) { focusKey(n); Sounds.tick(); } else Sounds.bump();
                } else {
                    int n = kCi + d;
                    if (n >= 0 && n < cl.size()) { kCi = n; Sounds.tick(); } else Sounds.bump();
                }
                say();
                break;
            }
            case "down":
                if (kRow == 0) { kCi = nearestCtl(keyX(kKi)); kRow = 1; Sounds.tick(); say(); } else Sounds.bump();
                break;
            case "up":
                if (kRow == 1) { focusKey(nearestKey(ctlCenter(kCi))); kRow = 0; kKeyV.snap(1); Sounds.tick(); say(); } else Sounds.bump();
                break;
            case "ok":
                if (kRow == 0) {
                    type(String.valueOf(cs.charAt(kKi)));
                    Sounds.key();
                    kKeyV.snap(0.6f);
                    kKeyV.spring(Motion.FOCUS, 1);
                } else act(cl.get(kCi).id);
                break;
            case "hold":
                if (kRow == 0) { type(String.valueOf(cs.charAt(kKi)).toUpperCase()); Sounds.key(); }
                break;
            case "back":
            case "home":
                Sounds.back();
                finishKb(null);
                break;
            default:
        }
        kbBox.invalidate();
    }

    private static void say() {
        if (kRow == 0) OS.speak(String.valueOf(chars().charAt(kKi)));
        else { Ctl c = ctls().get(kCi); OS.speak(c.text.isEmpty() ? "Стереть" : c.text); }
    }

    private static void finishKb(String v) {
        OS.drop(kLayer);
        kLayer = null;
        Tv.cancel(blink);
        Tv.cancel(unreveal);
        fade(kbRoot, false);
        Consumer<String> d = kDone;
        kDone = null;
        if (d != null) d.accept(v);
    }

    private static final TextPaint kT = Tv.text(44, 700, -.4f, 0), kS = Tv.text(27, 400, 0, 0), kF = Tv.text(44, 500, 0, 0), kK = Tv.text(37, 500, 0, 0),
            kC = Tv.text(27, 600, 0, 0), kH = Tv.text(22, 400, 0, 0);
    private static final Paint kp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Gfx.Shadow KEY_SHADOW = new Gfx.Shadow(0, 16, 30, -6, 18, Tv.black(.38f));
    private static final Gfx.Shadow CTL_SHADOW = new Gfx.Shadow(0, 16, 30, -6, 38, Tv.black(.36f));

    private static float keysLeft() { return (Tv.W - chars().length() * 51) / 2f; }

    private static float keyX(int i) { return keysLeft() + i * 51 + 25.5f; }

    private static int nearestKey(float x) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < chars().length(); i++) { float d = Math.abs(keyX(i) - x); if (d < bd) { bd = d; best = i; } }
        return best;
    }

    private static float[] ctlXs(List<Ctl> cl) {
        float[] w = new float[cl.size()];
        float total = 0;
        for (int i = 0; i < cl.size(); i++) {
            Ctl c = cl.get(i);
            kC.setTypeface(Tv.font(600));
            float tw = (c.glyph != null ? 32 : 0) + (c.glyph != null && !c.text.isEmpty() ? 12 : 0) + Tv.width(kC, c.text);
            w[i] = Math.max(120, tw + 64);
            total += w[i];
        }
        total += (cl.size() - 1) * 22;
        float[] out = new float[cl.size() * 2];
        float x = (Tv.W - total) / 2f;
        for (int i = 0; i < cl.size(); i++) { out[i * 2] = x; out[i * 2 + 1] = w[i]; x += w[i] + 22; }
        return out;
    }

    private static float ctlCenter(int i) {
        float[] xs = ctlXs(ctls());
        return xs[i * 2] + xs[i * 2 + 1] / 2;
    }

    private static int nearestCtl(float x) {
        float[] xs = ctlXs(ctls());
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < xs.length / 2; i++) { float d = Math.abs(xs[i * 2] + xs[i * 2 + 1] / 2 - x); if (d < bd) { bd = d; best = i; } }
        return best;
    }

    private static void drawKb(Canvas c) {
        if (kTitle == null) return;
        int label = Theme.label(), sec = Theme.sec(), ter = Theme.ter();
        kT.setTypeface(Tv.font(700));
        kT.setColor(label);
        kT.setTextAlign(Paint.Align.CENTER);
        c.drawText(kTitle, 960, Tv.baseline(kT, 160, Tv.lineNormal(kT)), kT);
        if (!kSub.isEmpty()) {
            kS.setColor(sec);
            kS.setTextAlign(Paint.Align.CENTER);
            c.drawText(kSub, 960, Tv.baseline(kS, 230, Tv.lineNormal(kS)), kS);
        }
        // the field
        RectF f = new RectF(410, 318, 1510, 422);
        kp.setColor(Theme.fill());
        c.drawRoundRect(f, 26, 26, kp);
        kp.setStyle(Paint.Style.STROKE);
        kp.setStrokeWidth(2);
        kp.setColor(Tv.white(.08f));
        RectF ring = new RectF(f);
        ring.inset(1, 1);
        c.drawRoundRect(ring, 25, 25, kp);
        kp.setStyle(Paint.Style.FILL);
        kF.setTypeface(Tv.font(500));
        float base = Tv.center(kF, f.centerY());
        String shown;
        if (kSecure && !kShow) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < kValue.length(); i++) b.append(i == kReveal ? kValue.charAt(i) : '•');
            shown = b.toString();
        } else shown = kValue;
        float tx = f.left + 38, maxW = f.width() - 76 - 6;
        c.save();
        c.clipRect(f.left + 20, f.top, f.right - 20, f.bottom);
        float tw = Tv.width(kF, shown);
        float shift = Math.max(0, tw - maxW);
        if (shown.isEmpty()) {
            kF.setColor(ter);
            c.drawText(kPh, tx, base, kF);
        } else {
            kF.setColor(label);
            c.drawText(shown, tx - shift, base, kF);
        }
        if (kCaret) {
            kp.setColor(Theme.blue());
            float cx = tx - shift + tw + 2;
            c.drawRoundRect(new RectF(cx, f.centerY() - 27, cx + 3, f.centerY() + 27), 2, 2, kp);
        }
        c.restore();
        // letters
        String cs = chars();
        float left = keysLeft();
        kK.setTypeface(Tv.font(500));
        kK.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < cs.length(); i++) {
            if (kRow == 0 && (i == kKi || i == kPrevKi && kKeyPrevF > 0.01f)) continue;
            kK.setColor(label);
            c.drawText(String.valueOf(cs.charAt(i)), left + i * 51 + 25.5f, Tv.center(kK, 568), kK);
        }
        if (kRow == 0) {
            if (kPrevKi >= 0 && kPrevKi < cs.length() && kKeyPrevF > 0.01f && kPrevKi != kKi) drawKey(c, cs, left, kPrevKi, kKeyPrevF, label);
            drawKey(c, cs, left, kKi, kKeyF, label);
        } else if (kPrevKi >= 0) {
            // nothing focused in the letters
        }
        // controls
        List<Ctl> cl = ctls();
        float[] xs = ctlXs(cl);
        kC.setTypeface(Tv.font(600));
        for (int i = 0; i < cl.size(); i++) {
            Ctl k = cl.get(i);
            boolean on = kRow == 1 && i == kCi;
            RectF r = new RectF(xs[i * 2], 672, xs[i * 2] + xs[i * 2 + 1], 748);
            c.save();
            if (on) {
                c.scale(1.1f, 1.1f, r.centerX(), r.centerY());
                CTL_SHADOW.draw(c, r.left, r.top, r.right, r.bottom, 1, true);
            }
            kp.setColor(on ? 0xffffffff : k.go ? Theme.blue() : Theme.fill());
            c.drawRoundRect(r, 38, 38, kp);
            if (k.cur) {
                kp.setStyle(Paint.Style.STROKE);
                kp.setStrokeWidth(2.5f);
                kp.setColor(on ? 0xff1d1d1f : label);
                RectF o = new RectF(r);
                o.inset(1.25f, 1.25f);
                c.drawRoundRect(o, 37, 37, kp);
                kp.setStyle(Paint.Style.FILL);
            }
            int col = on ? 0xff1d1d1f : k.go ? 0xffffffff : label;
            float cw = (k.glyph != null ? 32 : 0) + (k.glyph != null && !k.text.isEmpty() ? 12 : 0) + Tv.width(kC, k.text);
            float x = r.centerX() - cw / 2;
            if (k.glyph != null) { Glyphs.draw(c, k.glyph, x, r.centerY() - 16, 32, col, 2); x += 32 + (k.text.isEmpty() ? 0 : 12); }
            kC.setColor(col);
            kC.setTextAlign(Paint.Align.LEFT);
            if (!k.text.isEmpty()) c.drawText(k.text, x, Tv.center(kC, r.centerY()), kC);
            c.restore();
        }
        kH.setColor(ter);
        kH.setTextAlign(Paint.Align.CENTER);
        c.drawText("OK — буква · удерживайте OK — заглавная · «Назад» — отмена", 960, Tv.H - 70 - 8, kH);
    }

    private static void drawKey(Canvas c, String cs, float left, int i, float f, int label) {
        float cx = left + i * 51 + 25.5f, cy = 568, s = 1 + 0.34f * f;
        RectF r = new RectF(cx - 25.5f, cy - 46, cx + 25.5f, cy + 46);
        c.save();
        c.scale(s, s, cx, cy);
        KEY_SHADOW.draw(c, r.left, r.top, r.right, r.bottom, f, true);
        kp.setColor(Tv.white(f));
        c.drawRoundRect(r, 18, 18, kp);
        kK.setColor(Tv.mix(label, 0xff1d1d1f, f));
        c.drawText(String.valueOf(cs.charAt(i)), cx, Tv.center(kK, cy), kK);
        c.restore();
    }
}
