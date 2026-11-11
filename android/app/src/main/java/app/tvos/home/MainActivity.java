package app.tvos.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The home screen: one full-screen WebView showing the launcher (web/ of the repository, packed as assets).
 * The page is served from a private origin, https://tvos.local/, by {@link LocalServer}; the remote's keys that a
 * page never receives (Back, Home, Menu, Settings) are handed to it as TV.emit(action); {@link Bridge} gives it
 * the apps, the device and Android's settings screens.
 */
public class MainActivity extends Activity {
    static final String HOST = "tvos.local";
    static final String ORIGIN = "https://" + HOST + "/";

    private WebView web;
    private LocalServer server;
    private boolean resumed, loaded;

    /** Installed, removed or updated apps: the launcher reloads its tiles when it is next shown. */
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Bridge.forgetApps();
            js("window.dispatchEvent(new Event('tvapps'))");
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // chrome://inspect over ADB (only a computer that ADB already trusts can connect).
        WebView.setWebContentsDebuggingEnabled(true);
        server = new LocalServer(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // the screen saver plays by itself
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        // The page lays out at 1920 CSS px (its viewport meta) and the WebView shows it zoomed out to the screen,
        // so everything is rasterised at the screen's own resolution (a CSS scale() would make Chrome rasterise
        // at twice the size and shrink it on every frame).
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setOffscreenPreRaster(true); // the next frames are rasterised ahead: smoother on a slow GPU
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return server.handle(r);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                loaded = true;
                if (resumed) js("window.dispatchEvent(new Event('tvresume'))");
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // The system took the page's process back (low memory): start the home screen again.
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() { // no grey "play" picture before a video starts
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
        web.addJavascriptInterface(new Bridge(this), "Android");
        setContentView(web);
        web.requestFocus();
        web.loadUrl(ORIGIN + "index.html");

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packages, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packages, f);
    }

    void js(String code) {
        if (web != null) web.post(() -> web.evaluateJavascript(code, null));
    }

    void emit(String action) {
        js("window.TV && TV.emit('" + action + "')");
    }

    void reload() {
        Bridge.forgetApps();
        if (web != null) web.post(() -> web.reload());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String action = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                action = "back";
                break;
            case KeyEvent.KEYCODE_MENU:
                action = "control";
                break;
            case KeyEvent.KEYCODE_SETTINGS:
                action = "settings";
                break;
            default:
                return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) emit(action);
        return true;
    }

    /** Home pressed while the home screen is already in front (from another app it simply comes back). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (resumed && Intent.ACTION_MAIN.equals(intent.getAction())) emit("home");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
        if (loaded) js("window.dispatchEvent(new Event('tvresume'))");
    }

    @Override
    protected void onPause() {
        resumed = false;
        js("window.dispatchEvent(new Event('tvpause'))");
        web.onPause(); // stops the page's animations and media while an app is in front
        super.onPause();
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        // A home screen never closes.
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) js("window.dispatchEvent(new Event('tvlowmemory'))");
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
