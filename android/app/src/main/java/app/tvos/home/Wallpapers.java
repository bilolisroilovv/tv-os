package app.tvos.home;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Wallpapers: the photos tools/aerials.py put into web/assets/wallpapers (packed as assets/wallpapers with
 * thumb/ and preview/ copies and list.js) plus soft gradients drawn here. Also Apple's Aerial catalogue
 * (assets/data/aerials.js) for the screen saver.
 *
 * The full-size photo is decoded only for the one wallpaper on screen; blurred copies and brightness come from the
 * 480x270 thumbnail.
 */
final class Wallpapers {
    static final class W {
        final String id, name, place, cat;
        final boolean grad;
        final int bg;
        final float[][] blobs; // x, y, r
        final int[] blobColors;

        W(String id, String name, String place, String cat) {
            this.id = id; this.name = name; this.place = place; this.cat = cat;
            grad = false; bg = 0; blobs = null; blobColors = null;
        }

        W(String id, String name, String bg, Object... blobs) {
            this.id = id; this.name = name; this.place = "Градиент"; this.cat = null; grad = true;
            this.bg = Color.parseColor(bg);
            this.blobs = new float[blobs.length / 4][];
            this.blobColors = new int[blobs.length / 4];
            for (int i = 0; i < blobs.length; i += 4) {
                blobColors[i / 4] = Color.parseColor((String) blobs[i]);
                this.blobs[i / 4] = new float[]{((Number) blobs[i + 1]).floatValue(), ((Number) blobs[i + 2]).floatValue(), ((Number) blobs[i + 3]).floatValue()};
            }
        }
    }

    static final class Aerial {
        String file, name, cat;
        int[] poiT;
        String[] poi;
    }

    private static final List<W> PHOTOS = new ArrayList<>();
    private static final List<W> GRADS = new ArrayList<>();
    static final List<Aerial> AERIALS = new ArrayList<>();
    static final Map<String, String> CATS = new HashMap<>();
    private static AssetManager assets;
    private static final List<Runnable> subs = new ArrayList<>();

    static {
        GRADS.add(new W("g-dusk", "Сумерки", "#2b1055", "#7597de", .1, .1, .8, "#d53a9d", .85, .3, .7, "#ff9a5a", .6, 1, .6, "#4a148c", .3, .8, .6));
        GRADS.add(new W("g-ocean", "Океан", "#0b3d5c", "#1fa2ff", .15, .2, .8, "#12d8fa", .9, .25, .6, "#0a1f44", .6, 1, .9, "#3a7bd5", .35, .75, .6));
        GRADS.add(new W("g-dawn", "Рассвет", "#fde2d4", "#ff8a80", .1, .15, .75, "#ffd180", .9, .1, .65, "#b39ddb", .75, .95, .8, "#f8bbd0", .15, .95, .6));
        GRADS.add(new W("g-aurora", "Сияние", "#071a2b", "#00e5a0", .2, .35, .6, "#00bcd4", .65, .25, .55, "#6a1b9a", .85, .8, .7, "#0d47a1", .3, .95, .7));
        GRADS.add(new W("g-dunes", "Дюны", "#f3e1c7", "#f6b78c", .2, .8, .7, "#e8a87c", .9, .6, .6, "#fff1d6", .5, .1, .8, "#d4a373", .1, .2, .5));
        GRADS.add(new W("g-graphite", "Графит", "#1c1c1e", "#3a3a3c", .2, .2, .8, "#4a4f5a", .85, .3, .6, "#1f2a3a", .7, .9, .8, "#2c2c2e", .3, .85, .6));
    }

    static void init(AssetManager am) {
        assets = am;
        if (!PHOTOS.isEmpty() || !AERIALS.isEmpty()) return; // already read (the process outlived an activity)
        try {
            Object v = jsValue(read("wallpapers/list.js"), "TVWallpapers");
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    PHOTOS.add(new W(o.getString("id"), o.optString("name", o.getString("id")), o.optString("place", ""), o.optString("cat", null)));
                }
            }
        } catch (Exception ignored) {
            // no photos made on this computer: gradients only
        }
        try {
            String js = read("data/aerials.js");
            Object cats = jsValue(js, "TVAerialCats");
            if (cats instanceof JSONObject) {
                JSONObject o = (JSONObject) cats;
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); CATS.put(k, o.getString(k)); }
            }
            Object v = jsValue(js, "TVAerials");
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Aerial x = new Aerial();
                    x.file = o.getString("file");
                    x.name = o.optString("name", "");
                    x.cat = o.optString("cat", "");
                    JSONArray p = o.optJSONArray("pois");
                    int n = p == null ? 0 : p.length();
                    x.poiT = new int[n];
                    x.poi = new String[n];
                    for (int j = 0; j < n; j++) { JSONArray e = p.getJSONArray(j); x.poiT[j] = e.getInt(0); x.poi[j] = e.getString(1); }
                    AERIALS.add(x);
                }
            }
        } catch (Exception ignored) {
            // no Aerial catalogue: the screen saver shows the wallpapers
        }
    }

    private static String read(String path) throws Exception {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    /** The JSON value assigned to window.NAME in a generated .js file. */
    private static Object jsValue(String js, String name) throws Exception {
        int i = js.indexOf("window." + name);
        if (i < 0) return null;
        i = js.indexOf('=', i);
        return new JSONTokener(js.substring(i + 1)).nextValue();
    }

    static List<W> list() {
        ArrayList<W> l = new ArrayList<>(PHOTOS);
        l.addAll(GRADS);
        return l;
    }

    static W get(String id) {
        for (W w : PHOTOS) if (w.id.equals(id)) return w;
        for (W w : GRADS) if (w.id.equals(id)) return w;
        return null;
    }

    static String current() {
        String id = Prefs.s("wallpaper");
        if (get(id) != null) return id;
        return (PHOTOS.isEmpty() ? GRADS.get(0) : PHOTOS.get(0)).id;
    }

    static void set(String id) {
        Prefs.set("wallpaper", id);
        for (Runnable r : new ArrayList<>(subs)) r.run();
    }

    static void onChange(Runnable r) { subs.add(r); }

    static void clearListeners() { subs.clear(); }

    /** A photo for the cat of the screen saver's theme preview. */
    static String catPic(String cat) {
        for (W w : PHOTOS) if (cat.equals(w.cat)) return w.id;
        switch (cat) {
            case "Landscapes": return "g-aurora";
            case "Cities": return "g-dusk";
            case "Underwater": return "g-ocean";
            default: return "g-graphite";
        }
    }

    // ---------- pictures (call on the background thread) ----------

    static Bitmap decodeAsset(String path, int sample) {
        try (InputStream in = assets.open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inMutable = true;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** The full picture: a 1920x1080 photo, or a 1280x720 gradient with a fine grain (as the web prototype). */
    static Bitmap full(String id) {
        W w = get(id);
        if (w == null) w = GRADS.get(0);
        if (w.grad) return paint(w, 1280, 720, true);
        Bitmap b = decodeAsset("wallpapers/" + w.id + ".jpg", 1);
        return b != null ? b : paint(GRADS.get(0), 1280, 720, true);
    }

    /** 480x270: the ready-made thumbnail of a photo, or a small gradient. */
    static Bitmap small(String id) {
        W w = get(id);
        if (w == null) w = GRADS.get(0);
        if (w.grad) return paint(w, 480, 270, false);
        Bitmap b = decodeAsset("wallpapers/thumb/" + w.id + ".jpg", 1);
        if (b == null) b = decodeAsset("wallpapers/" + w.id + ".jpg", 4);
        return b != null ? b : paint(GRADS.get(0), 480, 270, false);
    }

    static Bitmap thumb(String id) { return small(id); }

    static Bitmap preview(String id) {
        W w = get(id);
        if (w == null) return null;
        if (w.grad) return paint(w, 720, 405, false);
        Bitmap b = decodeAsset("wallpapers/preview/" + w.id + ".jpg", 1);
        return b != null ? b : small(id);
    }

    /**
     * A pre-blurred copy (480x270): blur(px) of the 1920-wide picture, made at a quarter of the size; glass = the
     * "Liquid Glass" look (saturate 1.6, brightness 1.06).
     */
    static Bitmap blurred(Bitmap small, float px, boolean glass) {
        int W = 480, H = 270;
        float s = 0.25f, sigma = px * s;
        int m = Math.round(px * s), pad = (int) Math.ceil(sigma * 3);
        Bitmap work = Bitmap.createBitmap(W + 2 * (m + pad), H + 2 * (m + pad), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(work);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        drawCover(c, small, pad, pad, W + 2 * m, H + 2 * m, p);
        Gfx.blur(work, sigma);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas o = new Canvas(out);
        if (glass) p.setColorFilter(Gfx.saturateBrightness(1.6f, 1.06f));
        o.drawBitmap(work, new Rect(m + pad, m + pad, m + pad + W, m + pad + H), new Rect(0, 0, W, H), p);
        work.recycle();
        out.setHasAlpha(false);
        return out;
    }

    /** Average brightness 0..1 (Rec. 709) of a small picture. */
    static float luma(Bitmap small) {
        Bitmap b = Bitmap.createScaledBitmap(small, 32, 18, true);
        int[] px = new int[32 * 18];
        b.getPixels(px, 0, 32, 0, 0, 32, 18);
        double s = 0;
        for (int c : px) s += 0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255);
        return (float) (s / px.length / 255);
    }

    static void drawCover(Canvas c, Bitmap b, float x, float y, float w, float h, Paint p) {
        float k = Math.max(w / b.getWidth(), h / b.getHeight());
        float bw = b.getWidth() * k, bh = b.getHeight() * k;
        c.drawBitmap(b, null, new RectF(x + (w - bw) / 2, y + (h - bh) / 2, x + (w + bw) / 2, y + (h + bh) / 2), p);
    }

    /** Soft colour fields with an optional fine grain against banding on TV panels (web data/wallpapers.js). */
    static Bitmap paint(W g, int w, int h, boolean grain) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(g.bg);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        float mx = Math.max(w, h);
        for (int i = 0; i < g.blobs.length; i++) {
            float[] bl = g.blobs[i];
            int col = g.blobColors[i];
            p.setShader(new RadialGradient(bl[0] * w, bl[1] * h, bl[2] * mx,
                    new int[]{Tv.alpha(col | 0xff000000, .95f), Tv.alpha(col | 0xff000000, .4f), Tv.alpha(col | 0xff000000, 0)},
                    new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        p.setShader(new LinearGradient(0, 0, w, h, new int[]{Tv.white(.10f), Tv.white(0)}, new float[]{0, .45f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        if (grain) {
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            Random r = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int n = (int) ((r.nextFloat() - 0.5f) * 6), cc = px[i];
                int R = Math.max(0, Math.min(255, ((cc >> 16) & 255) + n)), G = Math.max(0, Math.min(255, ((cc >> 8) & 255) + n)), B = Math.max(0, Math.min(255, (cc & 255) + n));
                px[i] = 0xff000000 | (R << 16) | (G << 8) | B;
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
        }
        return b;
    }

    // ---------- small caches for the picker and Settings ----------

    private static final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    private static final LruCache<String, Bitmap> previews = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };

    static void thumbAsync(String id, Tv.Done<Bitmap> done) {
        Bitmap b = thumbs.get(id);
        if (b != null) { done.done(b); return; }
        Tv.bg(() -> thumb(id), (r) -> { if (r != null) thumbs.put(id, r); done.done(r); });
    }

    static void previewAsync(String id, Tv.Done<Bitmap> done) {
        Bitmap b = previews.get(id);
        if (b != null) { done.done(b); return; }
        Tv.bg(() -> preview(id), (r) -> { if (r != null) previews.put(id, r); done.done(r); });
    }

    static void trim() {
        thumbs.evictAll();
        previews.evictAll();
    }
}
