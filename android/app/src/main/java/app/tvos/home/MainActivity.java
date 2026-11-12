package app.tvos.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

/**
 * The home screen activity: one native stage (no WebView). Remote keys become the launcher's actions:
 * arrows, OK (held 550 ms = "hold"), Back, Home (twice = app switcher), Menu (Control Center), Settings.
 */
public class MainActivity extends Activity {
    Home home;
    Stage stage;
    boolean visible, resumed;
    private FrameLayout frame;
    private Fps fps;
    private Volume volume;

    /** Installed, removed or updated apps: the tiles are rebuilt (now, or when the home screen is next shown). */
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (home != null) home.appsChanged();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Tv.app = getApplicationContext();
        Prefs.init(this);
        Theme.read();
        Wallpapers.init(getAssets());
        Sounds.init(this);
        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        frame = new FrameLayout(this) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                fit(r - l, b - t);
            }
        };
        frame.setBackgroundColor(0xff000000);
        stage = new Stage(this);
        frame.addView(stage, new FrameLayout.LayoutParams(Tv.W, Tv.H));
        setContentView(frame);
        home = new Home(this, stage);
        fps = new Fps(this);
        volume = new Volume(home);
        Prefs.on("fps", () -> fps.set(Prefs.b("fps")));
        fps.set(Prefs.b("fps"));
        Bench.register(this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packages, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packages, f);
    }

    /** The 1920x1080 stage scaled to the screen (a 1080p TV draws it 1:1). */
    private void fit(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w / (float) Tv.W, h / (float) Tv.H);
        stage.setPivotX(0);
        stage.setPivotY(0);
        stage.setScaleX(s);
        stage.setScaleY(s);
        stage.setTranslationX((w - Tv.W * s) / 2);
        stage.setTranslationY((h - Tv.H * s) / 2);
    }

    // ---------- remote ----------

    private long lastRepeat;
    private boolean okDown, held;
    private final Runnable hold = () -> { held = true; act("hold"); };

    void act(String a) {
        if (home == null) return;
        long t0 = SystemClock.uptimeMillis();
        home.key(a);
        long d = SystemClock.uptimeMillis() - t0;
        if (d > 24) android.util.Log.w("tvos-slow", a + " in " + home.mode + ": " + d + " ms");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, up = e.getAction() == KeyEvent.ACTION_UP;
        if (Modal.keyboardOpen() && down && !isRemoteKey(code)) {
            if (code == KeyEvent.KEYCODE_DEL) { Modal.typed(0, true); return true; }
            int ch = e.getUnicodeChar();
            if (ch > 0 && Modal.typed(ch, false)) return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return dir(e, "up");
            case KeyEvent.KEYCODE_DPAD_DOWN: return dir(e, "down");
            case KeyEvent.KEYCODE_DPAD_LEFT: return dir(e, "left");
            case KeyEvent.KEYCODE_DPAD_RIGHT: return dir(e, "right");
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down && e.getRepeatCount() == 0) {
                    okDown = true;
                    held = false;
                    Tv.ui.removeCallbacks(hold);
                    Tv.ui.postDelayed(hold, 550);
                } else if (up) {
                    Tv.ui.removeCallbacks(hold);
                    if (okDown && !held && !e.isCanceled()) act("ok");
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (up && !e.isCanceled()) act("back");
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (up && !e.isCanceled()) act("control");
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
                if (up && !e.isCanceled()) act("settings");
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // the launcher's own volume indicator (Android's panel stays for other apps)
                if (volume != null && volume.key(e)) return true;
                return super.dispatchKeyEvent(e);
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static boolean isRemoteKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Arrows: a held arrow repeats, at most every 95 ms (as the web version did). */
    private boolean dir(KeyEvent e, String a) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (e.getRepeatCount() > 0) {
            long now = SystemClock.uptimeMillis();
            if (now - lastRepeat < 95) return true;
            lastRepeat = now;
        }
        act(a);
        return true;
    }

    /** Home pressed while the home screen is already in front (from another app it simply comes back). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        // Android pauses the activity for a moment before handing it a new intent, so "on screen" is the test
        if (visible) act("home");
        else home.homeFromApp(); // from an app: a second press right after it opens the app switcher
    }

    // ---------- lifecycle: nothing runs while another app is in front ----------

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        home.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        home.onResume();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        visible = false;
        home.onStop();
        super.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Wallpapers.trim();
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        // a home screen never closes
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        Bench.unregister(this);
        super.onDestroy();
    }

    /** Settings -> System -> restart the launcher: the system brings the home screen back at once. */
    void restart() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        stage.animate().alpha(0).setDuration(500).withEndAction(() -> {
            startActivity(i);
            Process.killProcess(Process.myPid());
        });
    }
}
