package app.tvos.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

/**
 * The volume indicator in the launcher's own style (a glass capsule that fills, like the brightness control of
 * Control Center) instead of Android's panel. The volume itself is the TV's: the remote's volume keys still set it,
 * the launcher only draws it. Other apps keep Android's own indicator. Many TVs (TCL Google TV among them) handle
 * the volume keys in the system before any app sees them: there Android's panel stays everywhere.
 */
final class Volume {
    static final int W = 420, HT = 80, MG = 80;
    private final Home h;
    private final AudioManager am;
    final Views.Box view;
    private final Gfx.Glass glass = new Gfx.Glass(W, HT, HT / 2f);
    private float level, shown = -1;
    private boolean muted;
    private long lastKey;
    private final Motion.Val vLevel;

    Volume(Home h) {
        this.h = h;
        Context c = h.act;
        am = c.getSystemService(AudioManager.class);
        view = h.stage.add(new Views.Box(c, (cv, v) -> draw(cv)), 190);
        int x = Tv.W - 88 - W, y = 62;
        Views.Abs.place(view, x - MG, y - MG, W + 2 * MG, HT + 2 * MG);
        glass.at(x, y);
        glass.shadow = new Gfx.Shadow(0, 14, 40, 0, HT / 2f, Tv.black(.22f));
        view.setPivotX(MG + W / 2f);
        view.setPivotY(MG);
        view.setAlpha(0);
        view.setVisibility(View.INVISIBLE);
        vLevel = new Motion.Val(0, 0.001f, v -> { shown = v; view.invalidate(); });
    }

    /** A volume key on the remote; false when Android should handle it itself (fixed-volume TVs). */
    boolean key(KeyEvent e) {
        if (am == null || am.isVolumeFixed()) return false;
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        long now = SystemClock.uptimeMillis();
        if (e.getRepeatCount() > 0 && now - lastKey < 70) return true; // a held key: about 14 steps a second
        lastKey = now;
        int dir = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : code == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0); // 0: no Android panel
        } catch (SecurityException ex) {
            return false;
        }
        show();
        return true;
    }

    private final Runnable hide = this::hideNow;

    private void hideNow() {
        Motion.fade(view, 300, 0);
        Motion.spring(view, Motion.SCALE, Motion.PANEL, .92f);
    }

    private void show() {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        level = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        float target = muted ? 0 : level;
        if (view.getAlpha() < 0.05f) {
            vLevel.snap(target);
            Motion.snap(view, Motion.SCALE, .92f);
        } else vLevel.tween(220, new Tv.Ease(.2f, .9f, .3f, 1), target);
        Motion.fade(view, 180, 1);
        Motion.spring(view, Motion.SCALE, Motion.PANEL, 1);
        Tv.cancel(hide);
        Tv.later(1800, hide);
        view.invalidate();
        OS.speak(muted ? "Звук выключен" : "Громкость " + Math.round(level * 100));
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();
    private final TextPaint t = Tv.tabular(Tv.text(24, 600, 0, 0xffffffff));

    private void draw(Canvas c) {
        glass.adaptive();
        glass.solid = Theme.solid ? Theme.solidPanel() : 0;
        glass.draw(c, MG, MG);
        float l = MG, top = MG, fill = Math.max(0, shown);
        r.set(l, top, l + W, top + HT);
        clip.reset();
        clip.addRoundRect(r, HT / 2f, HT / 2f, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        p.setColor(Tv.white(.9f));
        c.drawRect(l, top, l + W * fill, top + HT, p);
        c.restore();
        int dark = 0xff1d1d1f, fg = Theme.onGlass();
        // speaker: muted, or with one to three waves by the level
        String g = muted || fill <= 0.001f ? "mute" : "sound";
        Glyphs.draw(c, g, l + 26, top + HT / 2f - 18, 36, fill * W > 50 ? dark : fg, 2);
        t.setTypeface(Tv.font(600));
        t.setTextAlign(Paint.Align.RIGHT);
        t.setColor(fill > 0.93f ? dark : fg);
        c.drawText(muted ? "Выкл." : String.valueOf(Math.round(level * 100)), l + W - 30, Tv.center(t, top + HT / 2f), t);
    }
}
