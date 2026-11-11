package app.tvos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * One app icon of the home screen (web: .app > .wig > .tilt with .face, .spec, .rim): the banner with rounded
 * corners, its soft shadow, the lifted shadow, highlight and rim of the focused icon. On focus it springs up,
 * tilting towards the direction of travel and settling flat, as on tvOS.
 *
 * Scale, tilt and position are RenderNode properties (no redraw); only the highlight's drift redraws this one view.
 * The view is larger than the icon by M on every side, so the shadows are inside its bounds.
 */
final class Tile extends View {
    static final int IW = 315, IH = 189, M = 120;
    static final float R = 28, FS = 1.14f;

    Apps.App app;
    private BitmapShader face;
    private boolean faceAlpha;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF(M, M, M + IW, M + IH);
    private final RectF tmp = new RectF();

    // animated state
    float scale = 1, rx, ry, gx, gy, lift, specA, sx, sy;
    float bx, by, brx, bry, jig;
    boolean lightGrid;
    final Motion.Val vScale, vRX, vRY, vLift, vSX, vSY, vGX, vGY;
    Motion.Run bump;
    private Jiggle jiggle;

    // shared shadows (all CSS layers in one picture): rest and lifted, over the photo / over the light grid background
    private static Gfx.Shadow rest, restLight, liftS, liftLight;

    Tile(Context c, Apps.App a) {
        super(c);
        setWillNotDraw(false);
        if (rest == null) {
            int ink = 0xff000000, blue = Tv.rgba(30, 40, 60, 1);
            rest = new Gfx.Shadow(IW, IH, R, ink, new float[][]{{0, 14, 24, -8, .34f}, {0, 3, 8, 0, .12f}});
            restLight = new Gfx.Shadow(IW, IH, R, blue, new float[][]{{0, 16, 26, -8, .32f}, {0, 3, 8, 0, .10f}});
            liftS = new Gfx.Shadow(IW, IH, R, ink, new float[][]{{0, 40, 50, -16, .55f}, {0, 16, 32, 0, .22f}});
            liftLight = new Gfx.Shadow(IW, IH, R, blue, new float[][]{{0, 44, 54, -16, .48f}, {0, 18, 34, 0, .18f}});
        }
        setOutlineProvider(null);
        setPivotX(M + IW / 2f);
        setPivotY(M + IH / 2f);
        // CSS perspective: 1000px on the icon's box
        setCameraDistance(1000f * getResources().getDisplayMetrics().densityDpi / 72f);
        vScale = new Motion.Val(1, 0.0005f, v -> { scale = v; apply(); });
        vRX = new Motion.Val(0, 0.01f, v -> { rx = v; apply(); });
        vRY = new Motion.Val(0, 0.01f, v -> { ry = v; apply(); });
        vGX = new Motion.Val(0, 0.1f, v -> { gx = v; apply(); });
        vGY = new Motion.Val(0, 0.1f, v -> { gy = v; apply(); });
        vLift = new Motion.Val(0, 0.003f, v -> { lift = v; invalidate(); });
        vSX = new Motion.Val(0, 0.1f, v -> { sx = v; invalidate(); });
        vSY = new Motion.Val(0, 0.1f, v -> { sy = v; invalidate(); });
        setApp(a);
    }

    void setApp(Apps.App a) {
        app = a;
        Bitmap b = a.banner;
        face = b == null ? null : Gfx.coverShader(b, M, M, IW, IH);
        faceAlpha = b == null || b.hasAlpha();
        invalidate();
    }

    void apply() {
        setTranslationX(gx - M + bx);
        setTranslationY(gy - M + by);
        setScaleX(scale);
        setScaleY(scale);
        setRotationX(rx + brx);
        setRotationY(ry + bry);
        setRotation(jig);
    }

    /** Grid position (top-left of the icon on the page). */
    void moveTo(float x, float y, boolean instant) {
        if (instant) { vGX.snap(x); vGY.snap(y); return; }
        vGX.spring(Motion.MOVE, x);
        vGY.spring(Motion.MOVE, y);
    }

    /** Focus: jump to 1.04 tilted towards (dx, dy), then spring to 1.14 and flat (web setTilt twice). */
    void focus(int dx, int dy) {
        if (Motion.still) { dx = 0; dy = 0; }
        vScale.snap(1.04f);
        // CSS rotateY(dx*6deg) rotateX(-dy*6deg); Android's rotationY turns the other way round.
        vRY.snap(-dx * 6);
        vRX.snap(-dy * 6);
        vSX.snap(-dx * 56);
        vSY.snap(-dy * 34);
        vScale.spring(Motion.FOCUS, FS);
        vRY.spring(Motion.FOCUS, 0);
        vRX.spring(Motion.FOCUS, 0);
        vSX.spring(Motion.FOCUS, 0);
        vSY.spring(Motion.FOCUS, 0);
        vLift.tween(300, Tv.EASE, 1);
    }

    void unfocus() {
        vScale.spring(Motion.FOCUS, 1);
        vRY.spring(Motion.FOCUS, 0);
        vRX.spring(Motion.FOCUS, 0);
        vSX.spring(Motion.FOCUS, 0);
        vSY.spring(Motion.FOCUS, 0);
        vLift.tween(300, Tv.EASE, 0);
    }

    /** OK pressed: a quick dip of the icon (web pressIcon). */
    void press(Runnable then) {
        vScale.snap(FS * 0.93f);
        Tv.later(110, () -> {
            vScale.spring(Motion.FOCUS, FS);
            if (then != null) then.run();
        });
    }

    /** Hitting an edge: the icon nudges that way and back (web bump). */
    void bump(int dx, int dy) {
        if (Motion.still) return;
        if (bump != null) bump.cancel();
        bump = Motion.run(380, Tv.BUMP, k -> {
            float f = Motion.there(k);
            bx = dx * 12 * f;
            by = dy * 9 * f;
            bry = -dx * 9 * f;
            brx = -dy * 9 * f;
            apply();
        });
    }

    /** Move mode: the icon wobbles (CSS jiggle, 0.24 s alternate). */
    void jiggle(boolean on) {
        if (jiggle != null) { jiggle.stop = true; jiggle = null; }
        if (on && !Motion.still) {
            jiggle = new Jiggle();
            Motion.start(jiggle);
        } else {
            jig = 0;
            apply();
        }
    }

    private final class Jiggle implements Motion.Anim {
        boolean stop;
        float t;

        @Override
        public boolean step(float dt) {
            if (stop) { jig = 0; apply(); return false; }
            t += dt;
            float ph = (t / 0.24f) % 2, x = ph < 1 ? ph : 2 - ph;
            jig = -1.4f + 2.8f * Tv.EASE_IN_OUT.at(x);
            apply();
            return true;
        }
    }

    static boolean contrastRing;

    @Override
    protected void onDraw(Canvas c) {
        float l = M, t = M, r = M + IW, b = M + IH;
        (lightGrid ? restLight : rest).draw(c, l, t, r, b, 1, true);
        if (lift > 0.003f) (lightGrid ? liftLight : liftS).draw(c, l, t, r, b, lift, true);
        p.setShader(null);
        if (faceAlpha) {
            p.setColor(0xff2a2a2e);
            c.drawRoundRect(box, R, R, p);
        }
        if (face != null) {
            p.setShader(face);
            p.setColor(0xff000000);
            c.drawRoundRect(box, R, R, p);
            p.setShader(null);
        }
        // highlight (fast mode: normal blending at half strength)
        float sa = lift * 0.5f;
        if (sa > 0.004f && !Motion.still) {
            float cx = l + IW * .26f + sx, cy = t - IH * .16f + sy;
            RadialGradient g = Gfx.radial(cx, cy, IW * 1.2f, IH * .9f,
                    new int[]{Tv.white(.46f * sa), Tv.white(.1f * sa), Tv.white(0)}, new float[]{0, .36f, .58f});
            p.setShader(g);
            c.drawRoundRect(box, R, R, p);
            p.setShader(null);
        }
        // rim
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(Tv.white(Tv.lerp(.22f * .45f, .5f, lift)));
        tmp.set(box);
        tmp.inset(1, 1);
        c.drawRoundRect(tmp, R - 1, R - 1, p);
        if (contrastRing && lift > 0.01f) {
            p.setStrokeWidth(5);
            p.setColor(Tv.white(lift));
            tmp.set(box);
            tmp.inset(-7.5f, -7.5f);
            c.drawRoundRect(tmp, R + 7.5f, R + 7.5f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean hasOverlappingRendering() { return true; }
}
