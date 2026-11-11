package app.tvos.home;

import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * The animation engine: real springs (as tvOS) and CSS-like tweens, all stepped from one Choreographer callback.
 * Nothing runs when nothing moves: the callback is posted only while an animation is active, so an idle home
 * screen draws no frames and uses no CPU.
 *
 * View properties (translation, scale, rotation, alpha) are RenderNode properties: animating them redraws nothing,
 * the GPU only composites the view's recorded drawing with a new transform.
 */
final class Motion implements Choreographer.FrameCallback {
    interface Anim { boolean step(float dt); }

    interface Setter { void set(float v); }

    /** Spring presets of the web prototype (stiffness, damping; mass 1). */
    static final float[] FOCUS = {320, 25}, SCROLL = {190, 28}, PANEL = {300, 28}, CC = {260, 22}, MOVE = {320, 26};

    /** Reduce Motion: every spring becomes a short plain ease-out. */
    static boolean still;

    private static final Motion I = new Motion();
    private final ArrayList<Anim> list = new ArrayList<>();
    private final ArrayList<Anim> tmp = new ArrayList<>();
    private boolean posted;
    private long last;

    static void start(Anim a) {
        if (!I.list.contains(a)) I.list.add(a);
        if (!I.posted) {
            I.posted = true;
            I.last = 0;
            Choreographer.getInstance().postFrameCallback(I);
        }
    }

    static void stop(Anim a) { I.list.remove(a); }

    static boolean busy() { return !I.list.isEmpty(); }

    @Override
    public void doFrame(long now) {
        long t0 = android.os.SystemClock.uptimeMillis();
        try { frame(now); } finally {
            long d = android.os.SystemClock.uptimeMillis() - t0;
            if (d > 12) android.util.Log.w("tvos-slow", "animation step " + d + " ms");
        }
    }

    private void frame(long now) {
        posted = false;
        float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        tmp.clear();
        tmp.addAll(list);
        for (Anim a : tmp) {
            if (!list.contains(a)) continue;
            boolean more;
            try {
                more = a.step(dt);
            } catch (RuntimeException e) {
                android.util.Log.w("tvos", "animation failed", e);
                more = false;
            }
            if (!more) list.remove(a);
        }
        if (!list.isEmpty()) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else last = 0;
    }

    // ---------- a value that springs or tweens to its target ----------

    static final class Val implements Anim {
        float v, vel, target, from, t, dur;
        float k = 320, c = 25, eps;
        Tv.Ease ease = Tv.EASE;
        boolean running, tween;
        final Setter set;
        Runnable onEnd;

        Val(float initial, float eps, Setter set) {
            this.v = this.target = initial;
            this.eps = eps;
            this.set = set;
        }

        /** Spring to the target with (stiffness, damping); velocity carries over when retargeted. */
        Val spring(float[] spec, float to) {
            if (still) return tween(240, Tv.EASE, to);
            tween = false;
            k = spec[0];
            c = spec[1];
            return go(to);
        }

        /** A CSS transition: duration ms, easing, from the current value. */
        Val tween(float ms, Tv.Ease e, float to) {
            tween = true;
            dur = Math.max(1, ms) / 1000f;
            ease = e;
            from = v;
            t = 0;
            vel = 0;
            return go(to);
        }

        private Val go(float to) {
            target = to;
            if (v == to && vel == 0) {
                if (running) { running = false; stop(this); }
                set.set(v);
                end();
                return this;
            }
            running = true;
            start(this);
            return this;
        }

        void snap(float x) {
            v = target = x;
            vel = 0;
            if (running) { running = false; stop(this); }
            set.set(x);
        }

        Val then(Runnable r) { onEnd = r; return this; }

        private void end() {
            Runnable r = onEnd;
            onEnd = null;
            if (r != null) r.run();
        }

        @Override
        public boolean step(float dt) {
            if (!running) return false;
            if (tween) {
                t += dt;
                float p = Math.min(1, t / dur);
                v = from + (target - from) * ease.at(p);
                if (p >= 1) { v = target; running = false; set.set(v); end(); return false; }
                set.set(v);
                return true;
            }
            int n = Math.max(1, (int) Math.ceil(dt * 240));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = -k * (v - target) - c * vel;
                vel += a * h;
                v += vel * h;
            }
            if (Math.abs(v - target) < eps && Math.abs(vel) < eps * 10) {
                v = target;
                vel = 0;
                running = false;
                set.set(v);
                end();
                return false;
            }
            set.set(v);
            return true;
        }
    }

    // ---------- view properties ----------

    static final int TX = 0, TY = 1, SCALE = 2, ALPHA = 3, RX = 4, RY = 5, ROT = 6;
    private static final WeakHashMap<View, Val[]> props = new WeakHashMap<>();

    /** The animated property of a view (created at its current value; one per view and property). */
    static Val of(View v, int prop) {
        Val[] a = props.get(v);
        if (a == null) props.put(v, a = new Val[7]);
        if (a[prop] == null) {
            switch (prop) {
                case TX: a[prop] = new Val(v.getTranslationX(), 0.1f, v::setTranslationX); break;
                case TY: a[prop] = new Val(v.getTranslationY(), 0.1f, v::setTranslationY); break;
                case SCALE: a[prop] = new Val(v.getScaleX(), 0.0005f, s -> { v.setScaleX(s); v.setScaleY(s); }); break;
                case ALPHA: a[prop] = new Val(v.getAlpha(), 0.002f, x -> {
                    v.setAlpha(Math.max(0, Math.min(1, x)));
                    int vis = x <= 0.001f ? View.INVISIBLE : View.VISIBLE;
                    if (v.getVisibility() != vis && v.getVisibility() != View.GONE) v.setVisibility(vis);
                }); break;
                case RX: a[prop] = new Val(v.getRotationX(), 0.01f, v::setRotationX); break;
                case RY: a[prop] = new Val(v.getRotationY(), 0.01f, v::setRotationY); break;
                default: a[prop] = new Val(v.getRotation(), 0.01f, v::setRotation); break;
            }
        }
        return a[prop];
    }

    static Val spring(View v, int prop, float[] spec, float to) { return of(v, prop).spring(spec, to); }

    static Val tween(View v, int prop, float ms, Tv.Ease e, float to) { return of(v, prop).tween(ms, e, to); }

    static void snap(View v, int prop, float to) { of(v, prop).snap(to); }

    /** Fade a view in or out (INVISIBLE at 0, so it is not even recorded). */
    static Val fade(View v, float ms, float to) { return of(v, ALPHA).tween(ms, Tv.EASE, to); }

    // ---------- one-shot timelines ----------

    interface Frame { void at(float p); }

    /** Runs f(progress 0..1, eased) for ms; returns a handle that can be cancelled. */
    static final class Run implements Anim {
        final float dur;
        final Tv.Ease ease;
        final Frame f;
        float t;
        Runnable onEnd;
        boolean done;

        Run(float ms, Tv.Ease e, Frame f) {
            dur = ms / 1000f;
            ease = e;
            this.f = f;
        }

        @Override
        public boolean step(float dt) {
            if (done) return false;
            t += dt;
            float p = Math.min(1, t / dur);
            f.at(ease.at(p));
            if (p >= 1) {
                done = true;
                if (onEnd != null) onEnd.run();
                return false;
            }
            return true;
        }

        void cancel() { done = true; stop(this); }

        Run then(Runnable r) { onEnd = r; return this; }
    }

    static Run run(float ms, Tv.Ease e, Frame f) {
        Run r = new Run(ms, e, f);
        f.at(0);
        start(r);
        return r;
    }

    /** A keyframe "there and back" curve: 0 -> 1 at the middle -> 0, extrapolated past both ends like WAAPI. */
    static float there(float p) { return p <= 0.5f ? p * 2 : 2 - p * 2; }
}
