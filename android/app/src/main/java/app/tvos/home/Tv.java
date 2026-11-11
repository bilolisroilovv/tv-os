package app.tvos.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared helpers. Everything on screen is laid out in "TV pixels" of a 1920x1080 stage (the web prototype's
 * CSS pixels), so the numbers in the code are the ones in web/: a 1080p TV draws them 1:1.
 */
final class Tv {
    static final int W = 1920, H = 1080;
    static Context app;
    static final Handler ui = new Handler(Looper.getMainLooper());
    /** One background thread for decoding pictures and reading the system: nothing heavy runs on the UI thread. */
    static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvos-io");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private Tv() {}

    static void post(Runnable r) { ui.post(r); }

    static void later(long ms, Runnable r) { ui.postDelayed(r, ms); }

    static void cancel(Runnable r) { ui.removeCallbacks(r); }

    /** Runs fn on the background thread, then done(result) on the UI thread. */
    interface Job<T> { T run() throws Exception; }

    interface Done<T> { void done(T v); }

    static <T> void bg(Job<T> fn, Done<T> done) {
        io.execute(() -> {
            T v = null;
            try {
                v = fn.run();
            } catch (Throwable e) {
                android.util.Log.w("tvos", "background job failed", e);
            }
            final T r = v;
            if (done != null) ui.post(() -> done.done(r));
        });
    }

    /**
     * Lets a bitmap go at once. Since Android 8 recycle() only drops this reference (a frame being drawn keeps its
     * own), so it is safe; older versions leave it to the garbage collector.
     */
    static void free(android.graphics.Bitmap b) {
        if (b != null && android.os.Build.VERSION.SDK_INT >= 26 && !b.isRecycled()) b.recycle();
    }

    // ---------- colours ----------

    static int rgba(int r, int g, int b, float a) { return Color.argb(Math.round(a * 255), r, g, b); }

    static int white(float a) { return Color.argb(Math.round(a * 255), 255, 255, 255); }

    static int black(float a) { return Color.argb(Math.round(a * 255), 0, 0, 0); }

    static int alpha(int color, float a) { return (color & 0xffffff) | (Math.round(Color.alpha(color) * a) << 24); }

    static int mix(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static int hex(String s) { return Color.parseColor(s); }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }

    // ---------- CSS blur -> Android ----------

    /** A CSS blur length (box-shadow / text-shadow blur, = 2 sigma) as Paint.setShadowLayer / BlurMaskFilter radius. */
    static float blurRadius(float cssBlur) {
        float sigma = cssBlur / 2f;
        return sigma <= 0.5f ? 0.01f : (sigma - 0.5f) / 0.57735f;
    }

    // ---------- type ----------

    private static final Typeface[] FONTS = new Typeface[3];
    static boolean bold; // Accessibility: Bold Text

    /**
     * The web prototype on the TV rendered with Roboto: CSS weight 600 came out as Bold, 500 as Medium.
     * The same faces here, so the text looks as it did.
     */
    static Typeface font(int cssWeight) {
        int i = bold || cssWeight >= 600 ? 2 : cssWeight >= 500 ? 1 : 0;
        if (FONTS[i] == null) {
            int w = i == 2 ? 700 : i == 1 ? 500 : 400;
            if (Build.VERSION.SDK_INT >= 28) FONTS[i] = Typeface.create(Typeface.SANS_SERIF, w, false);
            else FONTS[i] = i == 2 ? Typeface.DEFAULT_BOLD : i == 1 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT;
        }
        return FONTS[i];
    }

    /** A text paint like a CSS rule: size px, weight, letter-spacing px, colour. */
    static TextPaint text(float size, int weight, float spacing, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(font(weight));
        p.setTextSize(size);
        if (spacing != 0) p.setLetterSpacing(spacing / size);
        p.setColor(color);
        return p;
    }

    static TextPaint tabular(TextPaint p) {
        p.setFontFeatureSettings("tnum");
        return p;
    }

    static float width(Paint p, String s) { return s == null ? 0 : p.measureText(s); }

    static String fit(TextPaint p, String s, float w) {
        if (s == null) return "";
        if (p.measureText(s) <= w) return s;
        return TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString();
    }

    /** Baseline of a CSS line box of the given height whose top is at y (line-height: normal = ascent + descent). */
    static float baseline(Paint p, float top, float lineHeight) {
        Paint.FontMetrics m = p.getFontMetrics();
        float content = m.descent - m.ascent;
        return top + (lineHeight - content) / 2f - m.ascent;
    }

    static float lineNormal(Paint p) {
        Paint.FontMetrics m = p.getFontMetrics();
        return m.descent - m.ascent;
    }

    /** Baseline that centres the text's cap/x body vertically at cy (like CSS centring of a normal line box). */
    static float center(Paint p, float cy) {
        Paint.FontMetrics m = p.getFontMetrics();
        return cy - (m.ascent + m.descent) / 2f;
    }

    // ---------- easing (CSS cubic-bezier) ----------

    static final class Ease {
        final float x1, y1, x2, y2;

        Ease(float x1, float y1, float x2, float y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }

        private float bx(float t) { return ((1 - 3 * x2 + 3 * x1) * t + (3 * x2 - 6 * x1)) * t * t + 3 * x1 * t; }

        private float by(float t) { return ((1 - 3 * y2 + 3 * y1) * t + (3 * y2 - 6 * y1)) * t * t + 3 * y1 * t; }

        private float dx(float t) { return (3 * (1 - 3 * x2 + 3 * x1)) * t * t + (2 * (3 * x2 - 6 * x1)) * t + 3 * x1; }

        float at(float x) {
            if (x <= 0) return 0;
            if (x >= 1) return 1;
            float t = x;
            for (int i = 0; i < 8; i++) {
                float e = bx(t) - x;
                if (Math.abs(e) < 1e-4f) return by(t);
                float d = dx(t);
                if (Math.abs(d) < 1e-6f) break;
                t -= e / d;
            }
            float lo = 0, hi = 1;
            t = x;
            for (int i = 0; i < 24; i++) {
                float v = bx(t);
                if (Math.abs(v - x) < 1e-4f) break;
                if (x > v) lo = t; else hi = t;
                t = (lo + hi) / 2;
            }
            return by(t);
        }
    }

    static final Ease EASE = new Ease(.25f, .1f, .25f, 1f);
    static final Ease EASE_IN = new Ease(.42f, 0f, 1f, 1f);
    static final Ease EASE_OUT = new Ease(0f, 0f, .58f, 1f);
    static final Ease EASE_IN_OUT = new Ease(.42f, 0f, .58f, 1f);
    static final Ease LINEAR = new Ease(0f, 0f, 1f, 1f);
    static final Ease SCROLL = new Ease(.2f, .9f, .3f, 1f);
    static final Ease BUMP = new Ease(.3f, 1.6f, .5f, 1f);
}
