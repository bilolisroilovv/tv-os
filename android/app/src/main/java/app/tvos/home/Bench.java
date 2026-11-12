package app.tvos.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Frame timing of real rendered frames (FrameMetrics: nothing extra is drawn to measure).
 *
 * Bench: scripted remote presses for measuring on the TV, the same scenarios as perf-tools/bench.js:
 *   adb shell am broadcast -a app.tvos.home.BENCH --es seq "R R R R L L L L" --ei gap 330 --es name focus
 *   adb logcat -d -s tvos-bench
 * Tokens: U D L R E (ok) B (back) H (home) C (Control Center) G (settings) T (switcher) Wnnn (wait nnn ms).
 * Also app.tvos.home.IMPORT --es b64 <base64 JSON>: settings carried over from the WebView version.
 * Only the shell (adb) may send these: the receiver needs android.permission.DUMP.
 */
final class Bench {
    private Bench() {}

    static final class Frames implements Window.OnFrameMetricsAvailableListener {
        final ArrayList<long[]> list = new ArrayList<>(); // {vsync ns, total ns}
        boolean on;

        @Override
        public void onFrameMetricsAvailable(Window w, FrameMetrics m, int dropped) {
            if (!on) return;
            long vs = Build.VERSION.SDK_INT >= 26 ? m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) : 0;
            long total = m.getMetric(FrameMetrics.TOTAL_DURATION);
            synchronized (list) { list.add(new long[]{vs, total}); }
        }

        List<long[]> take() {
            synchronized (list) { ArrayList<long[]> r = new ArrayList<>(list); list.clear(); return r; }
        }
    }

    private static HandlerThread thread;

    static Handler metricsHandler() {
        if (thread == null) { thread = new HandlerThread("tvos-frames"); thread.start(); }
        return new Handler(thread.getLooper());
    }

    /** {frames, fps-equivalent while animating, p50 ms, p95 ms, max ms, frames over 16.7 ms, dropped vsyncs} */
    static float[] stats(List<long[]> f) {
        int n = f.size();
        if (n == 0) return new float[]{0, 0, 0, 0, 0, 0, 0};
        long[] tot = new long[n];
        int slow = 0, dropped = 0;
        for (int i = 0; i < n; i++) {
            tot[i] = f.get(i)[1];
            if (tot[i] > 16_700_000L) slow++;
            if (i > 0 && f.get(i)[0] > 0) {
                long gap = f.get(i)[0] - f.get(i - 1)[0];
                if (gap > 0 && gap < 250_000_000L) dropped += Math.max(0, Math.round(gap / 16_666_667.0) - 1);
            }
        }
        long[] s = tot.clone();
        java.util.Arrays.sort(s);
        float fps = 60f * n / (n + dropped);
        return new float[]{n, fps, s[n / 2] / 1e6f, s[Math.min(n - 1, (int) (n * .95))] / 1e6f, s[n - 1] / 1e6f, slow, dropped};
    }

    private static BroadcastReceiver receiver;
    private static Frames frames;

    static void register(MainActivity a) {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if ("app.tvos.home.IMPORT".equals(i.getAction())) {
                    // settings carried over from the WebView version (base64 JSON), then a fresh start
                    String b = i.getStringExtra("b64");
                    if (b == null) return;
                    Prefs.importJson(new String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8));
                    a.restart();
                    return;
                }
                if (i.hasExtra("hideSsid")) Device.hideSsid = i.getBooleanExtra("hideSsid", false);
                run(a, i.getStringExtra("seq"), i.getIntExtra("gap", 300), i.getStringExtra("name"));
            }
        };
        IntentFilter f = new IntentFilter("app.tvos.home.BENCH");
        f.addAction("app.tvos.home.IMPORT");
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        else a.registerReceiver(receiver, f, "android.permission.DUMP", null);
    }

    static void unregister(MainActivity a) {
        if (receiver != null) a.unregisterReceiver(receiver);
        receiver = null;
        if (frames != null) a.getWindow().removeOnFrameMetricsAvailableListener(frames);
        frames = null;
    }

    private static void run(MainActivity a, String seq, int gap, String name) {
        if (seq == null) return;
        if (frames == null) {
            frames = new Frames();
            a.getWindow().addOnFrameMetricsAvailableListener(frames, metricsHandler());
        }
        frames.take();
        frames.on = true;
        String[] t = seq.trim().split("\\s+");
        long at = 0;
        for (String k : t) {
            if (k.startsWith("W")) { at += Long.parseLong(k.substring(1)); continue; }
            String act;
            switch (k) {
                case "U": act = "up"; break;
                case "D": act = "down"; break;
                case "L": act = "left"; break;
                case "R": act = "right"; break;
                case "E": act = "ok"; break;
                case "B": act = "back"; break;
                case "H": act = "home"; break;
                case "C": act = "control"; break;
                case "G": act = "settings"; break;
                case "T": act = "switcher"; break;
                default: act = k;
            }
            String x = act;
            Tv.later(at, () -> a.act(x));
            at += gap;
        }
        Frames f = frames;
        Tv.later(at + 900, () -> {
            f.on = false;
            float[] s = stats(f.take());
            if (frames == f) { // the listener goes away once (runs that overlap share it)
                frames = null;
                try { a.getWindow().removeOnFrameMetricsAvailableListener(f); } catch (IllegalArgumentException ignored) { }
            }
            Log.i("tvos-bench", String.format(Locale.ROOT, "%-9s frames %3.0f  fps %4.1f  p50 %5.1f ms  p95 %5.1f ms  max %5.1f ms  over16 %2.0f  dropped %2.0f",
                    name == null ? "bench" : name, s[0], s[1], s[2], s[3], s[4], s[5], s[6]));
        });
    }
}
