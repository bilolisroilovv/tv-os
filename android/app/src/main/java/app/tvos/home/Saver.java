package app.tvos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Screen saver: Apple's own Aerial films straight from Apple's server (as on Apple TV), the lock-screen clock and
 * Apple's place captions. Two players cross-fade from one film to the next. Without internet, the wallpapers with a
 * slow camera move. Everything is released when it ends.
 */
final class Saver {
    final Home h;
    final Views.Abs root;
    private final Views.Box blackV, photos, shadeV, clockV, capV;
    private Player cur, next;
    private boolean on, isBlack, photoMode;
    private List<Integer> order = new ArrayList<>();
    private int aIdx, sIdx;
    private String catsKey = "";
    private boolean covering;

    Saver(Home h) {
        this.h = h;
        Context c = h.act;
        root = new Views.Abs(c);
        root.overlapping = false; // fades piece by piece: no full-screen offscreen layer while it fades
        blackV = root.add(new Views.Box(c, (cv, v) -> cv.drawColor(0xff000000)), 0, 0, Tv.W, Tv.H);
        photos = root.add(new Views.Box(c, (cv, v) -> drawPhotos(cv)), 0, 0, Tv.W, Tv.H);
        shadeV = root.add(new Views.Box(c, (cv, v) -> drawShade(cv)), 0, 0, Tv.W, Tv.H);
        clockV = root.add(new Views.Box(c, (cv, v) -> drawClock(cv, v)), 0, 0, Tv.W, 480);
        capV = root.add(new Views.Box(c, (cv, v) -> drawCap(cv, v)), 96, Tv.H - 80 - 220, 1100, 220);
        root.setAlpha(0);
        root.setVisibility(View.INVISIBLE);
    }

    boolean covers() { return covering; }

    // ---------- players ----------

    private final class Player implements TextureView.SurfaceTextureListener {
        final TextureView view;
        MediaPlayer mp;
        Surface surface;
        Wallpapers.Aerial a;
        boolean shown;
        int lastPoi = -2;

        Player(Wallpapers.Aerial a) {
            this.a = a;
            view = new TextureView(h.act);
            view.setOpaque(true);
            view.setSurfaceTextureListener(this);
            view.setAlpha(0);
            root.addView(view, root.indexOfChild(photos) + 1, new Views.Abs.LP(0, 0, Tv.W, Tv.H));
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int ht) {
            surface = new Surface(st);
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
                mp.setVolume(0, 0);
                mp.setSurface(surface);
                mp.setDataSource("https://sylvan.apple.com/Videos/" + a.file);
                mp.setOnPreparedListener(p -> { if (this == cur || this == next) p.start(); });
                mp.setOnInfoListener((p, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) shownNow();
                    return false;
                });
                mp.setOnCompletionListener(p -> { if (this == cur) playNext(); });
                mp.setOnErrorListener((p, what, extra) -> { failed(this); return true; });
                mp.prepareAsync();
            } catch (Exception e) {
                failed(this);
            }
        }

        private void shownNow() {
            if (shown || !on) return;
            shown = true;
            Tv.cancel(stall);
            Player old = cur;
            cur = this;
            if (next == this) next = null;
            Motion.tween(view, Motion.ALPHA, 1800, Tv.EASE, 1);
            Motion.fade(photos, 1800, 0);
            if (old != null && old != this) {
                Motion.tween(old.view, Motion.ALPHA, 1800, Tv.EASE, 0);
                Tv.later(1900, old::release);
            }
            Tv.cancel(poll);
            Tv.later(500, poll);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int ht) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            if (mp != null) { try { mp.setSurface(null); } catch (Exception ignored) {} }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {}

        void release() {
            if (mp != null) {
                MediaPlayer m = mp;
                mp = null;
                try { m.reset(); } catch (Exception ignored) {}
                m.release();
            }
            if (surface != null) { surface.release(); surface = null; }
            root.removeView(view);
            if (cur == this) cur = null;
            if (next == this) next = null;
        }
    }

    private final Runnable stall = this::startPhotos; // no picture after 25 s: the photos instead
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            Player p = cur;
            if (!on || p == null || p.mp == null) return;
            try {
                int t = p.mp.getCurrentPosition() / 1000, dur = p.mp.getDuration();
                int k = -1;
                for (int i = 0; i < p.a.poiT.length; i++) if (p.a.poiT[i] <= t) k = i;
                if (k != p.lastPoi) { p.lastPoi = k; caption(p.a.name, k >= 0 ? p.a.poi[k] : p.a.name); }
                // at most 5 minutes per film; the next one starts loading early so the cross-fade is seamless
                if (dur > 0 && p.mp.getCurrentPosition() > Math.min(dur - 1500, 300000)) { playNext(); return; }
            } catch (IllegalStateException ignored) {
                // player being released
            }
            Tv.later(500, this);
        }
    };

    private void failed(Player p) {
        if (p == cur || p == next || cur == null) startPhotos();
        p.release();
    }

    private List<Integer> shuffled() {
        List<String> cats = Prefs.list("saverCats");
        List<Integer> ix = new ArrayList<>();
        for (int i = 0; i < Wallpapers.AERIALS.size(); i++) if (cats.contains(Wallpapers.AERIALS.get(i).cat)) ix.add(i);
        if (ix.isEmpty()) for (int i = 0; i < Wallpapers.AERIALS.size(); i++) ix.add(i);
        Collections.shuffle(ix, new Random());
        for (int i = 0; i < Wallpapers.AERIALS.size(); i++) {
            if (Wallpapers.AERIALS.get(i).file.startsWith("comp_GL_G004") && ix.contains(i)) { ix.remove((Integer) i); ix.add(0, i); break; } // Greenland first
        }
        return ix;
    }

    private void playNext() {
        if (!on || photoMode || isBlack || order.isEmpty()) return;
        Tv.cancel(poll);
        if (next != null) next.release();
        Wallpapers.Aerial a = Wallpapers.AERIALS.get(order.get(aIdx++ % order.size()));
        next = new Player(a);
        Tv.cancel(stall);
        Tv.later(25000, stall);
    }

    // ---------- photos (no internet) ----------

    private Bitmap pA, pB;
    private float pFade = 1, kbT;
    private int kbDir;
    private Motion.Run pRun;
    private final Motion.Anim kenBurns = new Motion.Anim() {
        @Override
        public boolean step(float dt) {
            if (!on || (cur != null && photos.getAlpha() <= 0)) return false;
            kbT += dt;
            photos.invalidate();
            return true;
        }
    };

    private void showPhoto(String id, boolean slow) {
        Tv.bg(() -> {
            Bitmap f = Wallpapers.full(id);
            if (f != null && android.os.Build.VERSION.SDK_INT >= 26) {
                Bitmap hw = f.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) { Tv.free(f); return hw; }
            }
            return f;
        }, b -> {
            if (b == null || !on) { Tv.free(b); return; }
            if (pRun != null) pRun.cancel();
            Tv.free(pA);
            pA = pB;
            pB = b;
            pFade = 0;
            kbT = 0;
            kbDir = 1 - kbDir;
            Views.show(photos);
            Motion.snap(photos, Motion.ALPHA, 1);
            pRun = Motion.run(2400, Tv.EASE, k -> { pFade = k; photos.invalidate(); }).then(() -> { Tv.free(pA); pA = null; });
            Motion.start(kenBurns);
        });
    }

    private final Runnable nextPhoto = new Runnable() {
        @Override
        public void run() {
            if (!on || !photoMode) return;
            List<Wallpapers.W> ws = Wallpapers.list();
            Wallpapers.W w = ws.get(sIdx++ % ws.size());
            showPhoto(w.id, true);
            caption(w.name, w.place);
            Tv.later(32000, this);
        }
    };

    private void startPhotos() {
        if (photoMode || !on || isBlack) return;
        photoMode = true;
        Tv.cancel(stall);
        Tv.cancel(poll);
        if (cur != null) cur.release();
        if (next != null) next.release();
        List<Wallpapers.W> ws = Wallpapers.list();
        sIdx = 0;
        for (int i = 0; i < ws.size(); i++) if (ws.get(i).id.equals(Wallpapers.current())) sIdx = i;
        nextPhoto.run();
    }

    private final Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);

    private void drawPhotos(Canvas c) {
        if (pB == null) return;
        if (pA != null && pFade < 1) { pp.setAlpha(255); drawKb(c, pA, kbT + 2.4f, 1 - kbDir); }
        pp.setAlpha(Math.round(255 * pFade));
        drawKb(c, pB, kbT, kbDir);
    }

    /** kb1 / kb2 of the web version: 40 s from one framing to another, linear. */
    private void drawKb(Canvas c, Bitmap b, float t, int dir) {
        float p = Math.min(1, t / 40f);
        float s0 = dir == 0 ? 1.16f : 1.03f, s1 = dir == 0 ? 1.02f : 1.17f;
        float x0 = dir == 0 ? -.02f : .02f, x1 = dir == 0 ? .015f : -.015f, y0 = dir == 0 ? .01f : -.01f, y1 = dir == 0 ? -.01f : .012f;
        float s = s0 + (s1 - s0) * p, tx = (x0 + (x1 - x0) * p) * Tv.W, ty = (y0 + (y1 - y0) * p) * Tv.H;
        c.save();
        c.translate(Tv.W / 2f, Tv.H / 2f);
        c.scale(s, s);
        c.translate(-Tv.W / 2f + tx, -Tv.H / 2f + ty);
        Wallpapers.drawCover(c, b, 0, 0, Tv.W, Tv.H, pp);
        c.restore();
    }

    // ---------- overlay: shade, clock, captions ----------

    private Bitmap shadeBmp;

    private void drawShade(Canvas c) {
        if (shadeBmp == null) {
            shadeBmp = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
            Canvas k = new Canvas(shadeBmp);
            Paint p = new Paint(Paint.DITHER_FLAG);
            k.scale(.25f, .25f);
            p.setShader(Gfx.radial(960, 1080 * .18f, 1200, 560, new int[]{Tv.black(.30f), Tv.black(0)}, new float[]{0, .72f}));
            k.drawRect(0, 0, Tv.W, Tv.H, p);
            p.setShader(new LinearGradient(0, Tv.H, 0, Tv.H * .72f, Tv.black(.34f), Tv.black(0), Shader.TileMode.CLAMP));
            k.drawRect(0, Tv.H * .72f, Tv.W, Tv.H, p);
        }
        c.drawBitmap(shadeBmp, null, new RectF(0, 0, Tv.W, Tv.H), pp);
    }

    private final Views.Cached clockCache = new Views.Cached();
    private final TextPaint sDate = Tv.text(40, 600, .2f, 0xffffffff), sTime = Tv.tabular(Tv.text(236, 600, -8, 0xffffffff));

    void tick() { if (on) clockV.invalidate(); }

    private void drawClock(Canvas c, Views.Box v) {
        if (!Prefs.b("saverClock")) return;
        String t = h.time(), d = h.date();
        clockCache.draw(c, t + d + Tv.bold, v.getWidth(), v.getHeight(), k -> {
            float sh = Tv.blurRadius(32);
            sDate.setTypeface(Tv.font(600));
            sDate.setShadowLayer(sh, 0, 2, Tv.black(.28f));
            sDate.setTextAlign(Paint.Align.CENTER);
            sDate.setAlpha(Math.round(255 * .95f));
            float l = Tv.lineNormal(sDate);
            k.drawText(d, 960, Tv.baseline(sDate, 128, l), sDate);
            sTime.setTypeface(Tv.font(600));
            sTime.setShadowLayer(sh, 0, 2, Tv.black(.28f));
            sTime.setTextAlign(Paint.Align.CENTER);
            k.drawText(t, 960, Tv.baseline(sTime, 128 + l, 236 * .96f), sTime);
        });
    }

    private String capName = "", capText = "";
    private StaticLayout capLayout;
    private final TextPaint capB = Tv.text(22, 700, 2.4f, Tv.white(.85f)), capS = Tv.text(36, 500, 0, 0xffffffff);
    private final Runnable capHide = this::hideCap;

    private void hideCap() { Motion.fade(capV, 1400, 0); Motion.tween(capV, Motion.TY, 1400, Tv.SCROLL, 8); }

    private void caption(String name, String text) {
        Tv.cancel(capShow);
        Tv.cancel(capHide);
        Motion.fade(capV, 600, 0);
        if (!Prefs.b("saverCaps")) return;
        capName = name;
        capText = text;
        Tv.later(700, capShow);
    }

    private final Runnable capShow = this::showCap;

    private void showCap() {
        capLayout = null;
        capV.invalidate();
        Motion.snap(capV, Motion.TY, 8);
        Motion.fade(capV, 1400, 1);
        Motion.tween(capV, Motion.TY, 1400, Tv.SCROLL, 0);
        Tv.later(9000, capHide);
    }

    private void drawCap(Canvas c, Views.Box v) {
        capB.setTypeface(Tv.font(700));
        capB.setShadowLayer(Tv.blurRadius(18), 0, 2, Tv.black(.55f));
        capS.setTypeface(Tv.font(500));
        capS.setShadowLayer(Tv.blurRadius(18), 0, 2, Tv.black(.55f));
        if (capLayout == null) capLayout = StaticLayout.Builder.obtain(capText, 0, capText.length(), capS, v.getWidth())
                .setLineSpacing(36 * 1.25f - Tv.lineNormal(capS), 1).setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        float lb = Tv.lineNormal(capB), total = lb + 8 + capLayout.getHeight(), top = v.getHeight() - total;
        c.drawText(capName.toUpperCase(), 0, Tv.baseline(capB, top, lb), capB);
        c.save();
        c.translate(0, top + lb + 8);
        capLayout.draw(c);
        c.restore();
    }

    // ---------- start / stop ----------

    void start(boolean black) {
        on = true;
        isBlack = black;
        photoMode = false;
        Views.show(root);
        Motion.fade(root, 1200, 1).then(() -> { if (on) { covering = true; h.updateCover(); } });
        clockV.setVisibility(black ? View.INVISIBLE : View.VISIBLE);
        shadeV.setVisibility(black ? View.INVISIBLE : View.VISIBLE);
        capV.setAlpha(0);
        if (black) { photos.setVisibility(View.INVISIBLE); return; }
        Motion.snap(clockV, Motion.ALPHA, 0);
        Motion.snap(clockV, Motion.TY, -14);
        Tv.later(500, () -> { if (on) { Motion.fade(clockV, 1400, 1); Motion.tween(clockV, Motion.TY, 1400, Tv.SCROLL, 0); } });
        if (Wallpapers.AERIALS.isEmpty() || "photos".equals(Prefs.s("saverType"))) { startPhotos(); return; }
        String k = String.join(",", Prefs.list("saverCats"));
        if (order.isEmpty() || !k.equals(catsKey)) { order = shuffled(); aIdx = 0; catsKey = k; }
        // while the first film buffers, the wallpaper drifts slowly instead of a black screen
        showPhoto(Wallpapers.current(), true);
        playNext();
    }

    /** Sleep: black screen (also from the running screen saver); any key wakes it. */
    void black() {
        isBlack = true;
        Tv.cancel(stall);
        Tv.cancel(poll);
        Tv.cancel(nextPhoto);
        if (cur != null) cur.release();
        if (next != null) next.release();
        photos.setVisibility(View.INVISIBLE);
        clockV.setVisibility(View.INVISIBLE);
        shadeV.setVisibility(View.INVISIBLE);
        capV.setVisibility(View.INVISIBLE);
    }

    void stop() {
        on = false;
        covering = false;
        Tv.cancel(stall);
        Tv.cancel(poll);
        Tv.cancel(nextPhoto);
        Tv.cancel(capShow);
        Tv.cancel(capHide);
        Motion.fade(root, 1200, 0).then(() -> {
            if (on) return;
            if (cur != null) cur.release();
            if (next != null) next.release();
            if (pRun != null) pRun.cancel();
            Tv.free(pA);
            Tv.free(pB);
            pA = pB = null;
            Tv.free(shadeBmp);
            shadeBmp = null;
            clockCache.free();
            capV.setVisibility(View.VISIBLE);
            photos.setVisibility(View.VISIBLE);
        });
    }
}
