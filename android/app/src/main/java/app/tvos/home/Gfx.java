package app.tvos.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.HashMap;
import java.util.Map;

/**
 * Drawing pieces that CSS gave the web prototype for free: box shadows, gradients at an angle, elliptical radial
 * gradients and the "Liquid Glass" of the fast mode (a pre-blurred copy of the wallpaper under a tint).
 * Every expensive picture is made once (in software) and then only drawn as a bitmap.
 */
final class Gfx {
    private Gfx() {}

    // ---------- CSS gradients ----------

    /** linear-gradient(angle, ...) over a w x h box at (x, y). */
    static LinearGradient linear(float angleDeg, float x, float y, float w, float h, int[] colors, float[] pos) {
        double a = Math.toRadians(angleDeg);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float len = Math.abs(w * dx) + Math.abs(h * dy);
        float cx = x + w / 2, cy = y + h / 2;
        return new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2, colors, pos, Shader.TileMode.CLAMP);
    }

    /** radial-gradient(rx ry at cx cy, ...): an ellipse. */
    static RadialGradient radial(float cx, float cy, float rx, float ry, int[] colors, float[] pos) {
        RadialGradient g = new RadialGradient(cx, cy, rx, colors, pos, Shader.TileMode.CLAMP);
        if (rx != ry) {
            Matrix m = new Matrix();
            m.setScale(1, ry / rx, cx, cy);
            g.setLocalMatrix(m);
        }
        return g;
    }

    // ---------- box shadows ----------

    /**
     * A CSS box-shadow of a rounded rectangle. Large boxes (panels) use a small blurred template cut in nine pieces
     * (the hidden middle is skipped when `hollow`); a box of a fixed size (an app icon) gets its whole shadow, all
     * its CSS layers together, pre-rendered once: one quad per icon instead of two shadows of nine pieces.
     */
    static final class Shadow {
        private final Bitmap tpl;
        private final int pad, corner, fw, fh; // outer margin, nine-piece corner, fixed box size (0 = nine pieces)
        private final float rad;
        private final int color;
        private final float[][] layers;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final Map<Long, Shadow> exact = new HashMap<>(); // the same shadow rendered for each small box size

        /** One layer: offset (dx, dy), blur, spread, colour with alpha. */
        Shadow(float dx, float dy, float blur, float spread, float radius, int color) {
            this(0, 0, radius, color | 0xff000000, new float[][]{{dx, dy, blur, spread, android.graphics.Color.alpha(color) / 255f}});
        }

        /** Several layers {dx, dy, blur, spread, alpha} of one colour; w x h > 0 renders them for that exact box. */
        Shadow(int w, int h, float radius, int rgb, float[][] layers) {
            int p = 0, cr = 0;
            for (float[] L : layers) {
                int ext = (int) Math.ceil(L[2] / 2f * 3), off = (int) Math.ceil(Math.abs(L[0])) + (int) Math.ceil(Math.abs(L[1]));
                p = Math.max(p, ext + off + Math.max(0, (int) Math.ceil(L[3])));
                cr = Math.max(cr, Math.round(Math.max(0, radius + L[3])) + ext + off + Math.max(0, (int) Math.ceil(-L[3])));
            }
            pad = p;
            rad = radius;
            this.layers = layers;
            fw = w;
            fh = h;
            corner = p + cr;
            color = rgb;
            int bw = w > 0 ? w : 2 * cr + 1, bh = h > 0 ? h : 2 * cr + 1;
            StringBuilder k = new StringBuilder().append(bw).append('x').append(bh).append(':').append(radius);
            for (float[] L : layers) for (float v : L) k.append(',').append(v);
            Bitmap b = TPL.get(k.toString());
            if (b == null) {
                b = Bitmap.createBitmap(bw + 2 * p, bh + 2 * p, Bitmap.Config.ALPHA_8);
                Canvas c = new Canvas(b);
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (float[] L : layers) {
                    q.setColor(0xff000000);
                    q.setAlpha(Math.round(255 * L[4]));
                    q.setMaskFilter(L[2] > 0 ? new BlurMaskFilter(Tv.blurRadius(L[2]), BlurMaskFilter.Blur.NORMAL) : null);
                    float sp = L[3], r = Math.max(0, radius + sp);
                    c.drawRoundRect(new RectF(p - sp + L[0], p - sp + L[1], p + bw + sp + L[0], p + bh + sp + L[1]), r, r, q);
                }
                TPL.put(k.toString(), b);
            }
            tpl = b;
            paint.setColor(color);
        }

        private static final Map<String, Bitmap> TPL = new HashMap<>();

        /** Draw the shadow of the box (l, t, r, b), multiplied by alpha 0..1. */
        void draw(Canvas c, float l, float t, float r, float b, float alpha, boolean hollow) {
            if (alpha <= 0.003f) return;
            paint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            float L = l - pad, T = t - pad, R = r + pad, B = b + pad;
            int k = corner, s = tpl.getWidth(), sh = tpl.getHeight();
            if (fw > 0 && hollow && r - l == fw && b - t == fh && fh > 2 * rad) {
                // the box itself is covered by opaque content: draw the shadow around it only (four bands), the
                // middle of the box (all but the rows of its rounded corners) is never blended
                int P = pad, rr = (int) Math.ceil(rad);
                piece(c, 0, 0, s, P + rr, L, T, R, t + rr);
                piece(c, 0, sh - P - rr, s, sh, L, b - rr, R, B);
                piece(c, 0, P + rr, P, sh - P - rr, L, t + rr, l, b - rr);
                piece(c, s - P, P + rr, s, sh - P - rr, r, t + rr, R, b - rr);
                return;
            }
            if (fw > 0) {
                dst.set(L, T, R, B);
                c.drawBitmap(tpl, null, dst, paint);
                return;
            }
            if (R - L < k * 2 + 1 || B - T < k * 2 + 1) {
                // too small for nine pieces (a round button, a key): its own exact shadow, never a stretched one
                int w = Math.round(r - l), ht = Math.round(b - t);
                long key = ((long) w << 32) | ht;
                Shadow x = exact.get(key);
                if (x == null) exact.put(key, x = new Shadow(w, ht, rad, color, layers));
                x.draw(c, l, t, l + w, t + ht, alpha, hollow);
                return;
            }
            piece(c, 0, 0, k, k, L, T, L + k, T + k);
            piece(c, s - k, 0, s, k, R - k, T, R, T + k);
            piece(c, 0, sh - k, k, sh, L, B - k, L + k, B);
            piece(c, s - k, sh - k, s, sh, R - k, B - k, R, B);
            piece(c, k, 0, k + 1, k, L + k, T, R - k, T + k);
            piece(c, k, sh - k, k + 1, sh, L + k, B - k, R - k, B);
            piece(c, 0, k, k, k + 1, L, T + k, L + k, B - k);
            piece(c, s - k, k, s, k + 1, R - k, T + k, R, B - k);
            if (!hollow) piece(c, k, k, k + 1, k + 1, L + k, T + k, R - k, B - k);
        }

        private void piece(Canvas c, int sl, int st, int sr, int sb, float l, float t, float r, float b) {
            src.set(sl, st, sr, sb);
            dst.set(l, t, r, b);
            c.drawBitmap(tpl, src, dst, paint);
        }
    }

    // ---------- Liquid Glass (fast mode of the web prototype) ----------

    /** The pre-blurred "glass" wallpaper (480x270, blur 26 + saturate 1.6 + brightness 1.06); null until ready. */
    static Bitmap glassImage;
    private static BitmapShader glassShader;
    private static int glassGen;

    static void setGlass(Bitmap b) {
        glassImage = b;
        glassShader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        glassGen++;
    }

    static int glassGen() { return glassGen; }

    /** Tint of the glass, adapted to the wallpaper (darker over bright pictures). */
    static int lgA = Tv.white(.20f), lgB = Tv.white(.06f), lgC = Tv.white(.12f);

    static void adaptGlass(boolean brightWallpaper) {
        lgA = brightWallpaper ? Tv.rgba(40, 40, 46, .26f) : Tv.white(.20f);
        lgB = brightWallpaper ? Tv.rgba(40, 40, 46, .14f) : Tv.white(.06f);
        lgC = brightWallpaper ? Tv.rgba(40, 40, 46, .20f) : Tv.white(.12f);
    }

    /**
     * One glass shape: background = linear-gradient(165deg, a, b 52%, c) over the blurred wallpaper lined up with
     * where the element sits on the stage, then the inner highlights. Shaders are rebuilt only when the size, the
     * tint or the wallpaper changes.
     */
    static final class Glass {
        float w, h, r;
        float sx, sy; // the shape's top-left on the stage (layout position: the picture travels with an animated element)
        int a, b, cc;
        float topLine = .5f, border = .16f;
        Shadow shadow;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path crescent;
        private int gen = -1, ka, kb, kc;
        private float kw, kh, kx, ky;
        private final RectF box = new RectF();
        int solid; // Reduce Transparency: an opaque colour instead of the glass (0 = glass)

        Glass(float w, float h, float r) {
            this.w = w; this.h = h; this.r = r;
            a = lgA; b = lgB; cc = lgC;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f);
        }

        Glass at(float stageX, float stageY) { sx = stageX; sy = stageY; return this; }

        Glass tint(int a, int b, int c) { this.a = a; this.b = b; this.cc = c; return this; }

        Glass adaptive() { return tint(lgA, lgB, lgC); }

        void size(float w, float h) { this.w = w; this.h = h; }

        private void build() {
            if (gen == glassGen && ka == a && kb == b && kc == cc && kw == w && kh == h && kx == sx && ky == sy) return;
            gen = glassGen; ka = a; kb = b; kc = cc; kw = w; kh = h; kx = sx; ky = sy;
            Shader tint = linear(165, 0, 0, w, h, new int[]{a, b, cc}, new float[]{0, .52f, 1});
            if (glassShader != null) {
                // the wallpaper part is made once per wallpaper and place; a tint change only redoes the tint
                if (bs == null || bsGen != glassGen || bsX != sx || bsY != sy) {
                    Matrix m = new Matrix();
                    float s = (float) Tv.W / glassImage.getWidth();
                    m.setScale(s, s);
                    m.postTranslate(-sx, -sy);
                    bs = new BitmapShader(glassImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    bs.setLocalMatrix(m);
                    bsGen = glassGen;
                    bsX = sx;
                    bsY = sy;
                }
                fill.setShader(new ComposeShader(bs, tint, PorterDuff.Mode.SRC_OVER));
                fill.setColor(0xff000000);
            } else {
                fill.setShader(tint);
            }
            if (crescent == null || cw != w || ch != h) {
                cw = w;
                ch = h;
                Path outer = new Path(), moved = new Path();
                outer.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
                moved.addRoundRect(new RectF(0, 1.5f, w, h + 1.5f), r, r, Path.Direction.CW);
                crescent = new Path();
                crescent.op(outer, moved, Path.Op.DIFFERENCE);
            }
        }

        private float cw, ch;
        private BitmapShader bs;
        private int bsGen = -1;
        private float bsX, bsY;

        /** Draws at (x, y) in the canvas. */
        void draw(Canvas c, float x, float y) {
            if (shadow != null) shadow.draw(c, x, y, x + w, y + h, 1, true);
            box.set(x, y, x + w, y + h);
            if (solid != 0) {
                fill.setShader(null);
                fill.setColor(solid);
                c.drawRoundRect(box, r, r, fill);
                gen = -1;
                return;
            }
            build();
            int save = c.save();
            c.translate(x, y);
            box.set(0, 0, w, h);
            if (glassShader == null) {
                Paint gray = TMP;
                gray.setShader(null);
                gray.setColor(Tv.rgba(110, 110, 118, .28f));
                c.drawRoundRect(box, r, r, gray);
            }
            c.drawRoundRect(box, r, r, fill);
            if (border > 0) {
                line.setColor(Tv.white(border));
                box.inset(.75f, .75f);
                c.drawRoundRect(box, r - .75f, r - .75f, line);
            }
            if (topLine > 0) {
                TMP.setShader(null);
                TMP.setColor(Tv.white(topLine));
                c.drawPath(crescent, TMP);
            }
            c.restoreToCount(save);
        }
    }

    private static final Paint TMP = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---------- pictures ----------

    /** A bitmap drawn to cover a w x h box (CSS background-size: cover), centred. */
    static void cover(Canvas c, Bitmap b, float x, float y, float w, float h, Paint p) {
        if (b == null) return;
        float s = Math.max(w / b.getWidth(), h / b.getHeight());
        float bw = b.getWidth() * s, bh = b.getHeight() * s;
        RectF d = new RectF(x + (w - bw) / 2, y + (h - bh) / 2, x + (w + bw) / 2, y + (h + bh) / 2);
        int save = c.save();
        c.clipRect(x, y, x + w, y + h);
        c.drawBitmap(b, null, d, p);
        c.restoreToCount(save);
    }

    /** A shader that draws bitmap b covering the box (x, y, w, h): use it with drawRoundRect for rounded pictures. */
    static BitmapShader coverShader(Bitmap b, float x, float y, float w, float h) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float k = Math.max(w / b.getWidth(), h / b.getHeight());
        Matrix m = new Matrix();
        m.setScale(k, k);
        m.postTranslate(x + (w - b.getWidth() * k) / 2, y + (h - b.getHeight() * k) / 2);
        s.setLocalMatrix(m);
        return s;
    }

    /** The saturated average colour of a picture (for glows behind a launched app): web core.js TV.color. */
    static int[] color(Bitmap src) {
        if (src == null) return new int[]{120, 120, 140};
        Bitmap b = Bitmap.createScaledBitmap(android.os.Build.VERSION.SDK_INT >= 26 && src.getConfig() == Bitmap.Config.HARDWARE ? src.copy(Bitmap.Config.ARGB_8888, false) : src, 32, 18, true);
        int[] px = new int[32 * 18];
        b.getPixels(px, 0, 32, 0, 0, 32, 18);
        double r = 0, g = 0, bl = 0, n = 0;
        for (int c : px) {
            int R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255, mx = Math.max(R, Math.max(G, B)), mn = Math.min(R, Math.min(G, B));
            double sat = mx == 0 ? 0 : (mx - mn) / (double) mx, w = sat * sat * (mx / 255.0) + 0.002;
            r += R * w; g += G * w; bl += B * w; n += w;
        }
        double[] o = {r / n, g / n, bl / n};
        double mx = Math.max(o[0], Math.max(o[1], o[2]));
        if (mx <= 0) mx = 1;
        return new int[]{(int) Math.round(Math.min(255, o[0] / mx * 235)), (int) Math.round(Math.min(255, o[1] / mx * 235)), (int) Math.round(Math.min(255, o[2] / mx * 235))};
    }

    // ---------- blur ----------

    /**
     * Gaussian blur (three box passes) of an ARGB bitmap in place, outside the picture counted as black, like the
     * canvas blur + JPEG of the web prototype (its blurred backgrounds darken slightly towards the edges).
     */
    static void blur(Bitmap b, float sigma) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] boxes = boxes(sigma);
        for (int r : boxes) {
            boxH(px, tmp, w, h, r);
            boxV(tmp, px, w, h, r);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static int[] boxes(float sigma) {
        double wIdeal = Math.sqrt(12 * sigma * sigma / 3 + 1);
        int wl = (int) Math.floor(wIdeal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12 * sigma * sigma - 3 * wl * wl - 4 * 3 * wl - 3 * 3) / (-4.0 * wl - 4);
        long m = Math.round(mIdeal);
        int[] r = new int[3];
        for (int i = 0; i < 3; i++) r[i] = ((i < m ? wl : wu) - 1) / 2;
        return r;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        float k = 1f / (r + r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int x = -r; x <= r; x++) {
                if (x < 0 || x >= w) continue;
                int c = in[row + x];
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xff000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = x + r + 1, sub = x - r;
                if (add < w) { int c = in[row + add]; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; }
                if (sub >= 0) { int c = in[row + sub]; sr -= (c >> 16) & 255; sg -= (c >> 8) & 255; sb -= c & 255; }
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        float k = 1f / (r + r + 1);
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int y = -r; y <= r; y++) {
                if (y < 0 || y >= h) continue;
                int c = in[y * w + x];
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xff000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = y + r + 1, sub = y - r;
                if (add < h) { int c = in[add * w + x]; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; }
                if (sub >= 0) { int c = in[sub * w + x]; sr -= (c >> 16) & 255; sg -= (c >> 8) & 255; sb -= c & 255; }
            }
        }
    }

    /** CSS saturate(s) brightness(b) as a colour filter. */
    static ColorMatrixColorFilter saturateBrightness(float s, float b) {
        ColorMatrix m = new ColorMatrix(new float[]{
                .213f + .787f * s, .715f - .715f * s, .072f - .072f * s, 0, 0,
                .213f - .213f * s, .715f + .285f * s, .072f - .072f * s, 0, 0,
                .213f - .213f * s, .715f - .715f * s, .072f + .928f * s, 0, 0,
                0, 0, 0, 1, 0});
        ColorMatrix br = new ColorMatrix();
        br.setScale(b, b, b, 1);
        m.postConcat(br);
        return new ColorMatrixColorFilter(m);
    }
}
