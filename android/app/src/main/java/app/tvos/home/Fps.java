package app.tvos.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Settings -> System -> Performance -> frame counter: frames per second of the frames really drawn, the slowest
 * frame and the frames that took too long. An idle home screen draws nothing, so it says "idle".
 */
final class Fps {
    private final MainActivity a;
    private final Views.Box v;
    private Bench.Frames frames;
    private String text = "…";
    private boolean bad;

    Fps(MainActivity a) {
        this.a = a;
        v = a.stage.add(new Views.Box(a, (c, b) -> draw(c)), 210);
        Views.Abs.place(v, 24, 20, 700, 60);
        v.setVisibility(View.GONE);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (frames == null) return;
            List<long[]> f = frames.take();
            if (f.size() <= 1) { text = "простой · кадры не рисуются"; bad = false; }
            else {
                float[] s = Bench.stats(f);
                text = String.format(Locale.ROOT, "%.0f к/с · худший кадр %.0f мс · рывков %.0f", s[1], s[4], s[6]);
                bad = s[6] > 2;
            }
            v.invalidate();
            Tv.later(1000, this);
        }
    };

    void set(boolean on) {
        if (on && frames == null) {
            frames = new Bench.Frames();
            frames.on = true;
            a.getWindow().addOnFrameMetricsAvailableListener(frames, Bench.metricsHandler());
            v.setVisibility(View.VISIBLE);
            Tv.later(1000, tick);
        } else if (!on && frames != null) {
            a.getWindow().removeOnFrameMetricsAvailableListener(frames);
            frames = null;
            Tv.cancel(tick);
            v.setVisibility(View.GONE);
        }
    }

    private final TextPaint p = Tv.tabular(Tv.text(20, 500, 0, 0xffffffff));
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void draw(Canvas c) {
        float w = p.measureText(text) + 32;
        bg.setColor(Tv.black(.72f));
        c.drawRoundRect(new RectF(0, 0, w, 42), 14, 14, bg);
        p.setColor(bad ? 0xffff9f0a : 0xff30d158);
        c.drawText(text, 16, Tv.center(p, 21), p);
    }
}
