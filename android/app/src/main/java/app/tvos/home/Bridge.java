package app.tvos.home;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Display;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * window.Android in the page (see web/os/platform.js). Called on the WebView's JavaScript thread; anything that
 * starts an activity goes to the UI thread. Returns JSON strings.
 */
final class Bridge {
    /** Not shown as tiles: Android's own TV settings open from the launcher's Settings instead. */
    private static final Set<String> SKIP = new HashSet<>(List.of("com.android.tv.settings"));
    private static volatile String appsCache;

    private final MainActivity act;
    private final PackageManager pm;

    Bridge(MainActivity act) {
        this.act = act;
        this.pm = act.getPackageManager();
    }

    static void forgetApps() {
        appsCache = null;
    }

    // ---------- apps ----------

    @JavascriptInterface
    public String apps() {
        String cached = appsCache;
        if (cached != null) return cached;
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        Set<String> leanback = new HashSet<>(), homes = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (cat.equals(Intent.CATEGORY_LEANBACK_LAUNCHER)) leanback.add(p);
                if (!found.containsKey(p) && !p.equals(act.getPackageName()) && !SKIP.contains(p)) found.put(p, ri);
            }
        }
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            homes.add(ri.activityInfo.packageName);
        }
        File dir = new File(act.getCacheDir(), "appart");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Set<String> keep = new HashSet<>();
        List<JSONObject> list = new ArrayList<>();
        for (ResolveInfo ri : found.values()) {
            String p = ri.activityInfo.packageName;
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", p);
                o.put("name", ri.loadLabel(pm).toString());
                // Phone-style apps (no TV launcher entry) and other home screens start hidden, as on Google TV.
                o.put("tv", leanback.contains(p) && !homes.contains(p));
                long stamp = pm.getPackageInfo(p, 0).lastUpdateTime;
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                String file;
                if (banner != null) o.put("banner", "/appart/" + (file = draw(dir, p + "-" + stamp + "-b.png", banner, 640)));
                else o.put("icon", "/appart/" + (file = draw(dir, p + "-" + stamp + "-i.png", ri.loadIcon(pm), 192)));
                keep.add(file);
                list.add(o);
            } catch (Exception skipped) {
                // an app that went away meanwhile, or a drawable that cannot be drawn
            }
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
            f.delete();
        Collator byName = Collator.getInstance(Locale.getDefault());
        list.sort((a, b) -> byName.compare(a.optString("name"), b.optString("name")));
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return appsCache = out.toString();
    }

    /** A drawable as a PNG in the cache, at most maxW wide (banners are 16:9, icons square). */
    private static String draw(File dir, String name, Drawable d, int maxW) throws IOException {
        File f = new File(dir, name);
        if (!f.exists()) {
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : maxW, h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : maxW;
            float s = maxW / (float) w;
            int bw = Math.max(1, Math.round(w * s)), bh = Math.max(1, Math.round(h * s));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, bw, bh);
            d.draw(new Canvas(b));
            try (FileOutputStream os = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            b.recycle();
        }
        return name;
    }

    @JavascriptInterface
    public boolean launch(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        return true;
    }

    @JavascriptInterface
    public boolean openSettings(String what, String arg) {
        String action;
        switch (what) {
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
            case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "appinfo": action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS; break;
            case "accounts": action = Settings.ACTION_SYNC_SETTINGS; break;
            case "about": action = Settings.ACTION_DEVICE_INFO_SETTINGS; break;
            case "update": action = "android.settings.SYSTEM_UPDATE_SETTINGS"; break;
            case "language": action = Settings.ACTION_LOCALE_SETTINGS; break;
            case "date": action = Settings.ACTION_DATE_SETTINGS; break;
            case "developer": action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS; break;
            case "accessibility": action = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "storage": action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            default: action = Settings.ACTION_SETTINGS;
        }
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if ("appinfo".equals(what)) i.setData(Uri.fromParts("package", arg, null));
        if (i.resolveActivity(pm) == null) return false;
        start(i);
        return true;
    }

    private void start(Intent i) {
        act.runOnUiThread(() -> {
            try {
                act.startActivity(i);
            } catch (ActivityNotFoundException | SecurityException e) {
                act.js("window.dispatchEvent(new Event('tvresume'))");
            }
        });
    }

    // ---------- device ----------

    @JavascriptInterface
    public String device() throws JSONException {
        JSONObject o = new JSONObject();
        ContentResolver cr = act.getContentResolver();
        String name = Settings.Global.getString(cr, "device_name");
        o.put("name", name != null ? name : Build.MODEL);
        o.put("maker", Build.MANUFACTURER);
        o.put("model", Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("cores", Runtime.getRuntime().availableProcessors());
        o.put("hardware", Build.HARDWARE);
        ActivityManager am = act.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        o.put("ramMB", mi.totalMem / 1048576);
        o.put("ramFreeMB", mi.availMem / 1048576);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        o.put("storageTotalGB", Math.round(fs.getTotalBytes() / 1e8) / 10.0);
        o.put("storageFreeGB", Math.round(fs.getAvailableBytes() / 1e8) / 10.0);
        Display d = act.getWindowManager().getDefaultDisplay();
        Point p = new Point();
        d.getRealSize(p);
        o.put("display", p.x + " × " + p.y + " · " + Math.round(d.getRefreshRate()) + " Гц");
        try {
            o.put("webview", "Chromium " + WebView.getCurrentWebViewPackage().versionName.split("\\.")[0]);
        } catch (Exception ignored) {
            // no WebView package information
        }
        try {
            o.put("appVersion", pm.getPackageInfo(act.getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
            // cannot happen for our own package
        }
        return o.toString();
    }

    @JavascriptInterface
    public String network() throws JSONException {
        JSONObject o = new JSONObject();
        ConnectivityManager cm = act.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
        o.put("connected", nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        String type = nc == null ? "none" : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet" : "other";
        o.put("type", type);
        LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
        if (lp != null) for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) {
                o.put("ip", la.getAddress().getHostAddress());
                break;
            }
        }
        if ("wifi".equals(type)) {
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                String ssid = wi.getSSID();
                if (ssid != null && !ssid.contains("<unknown")) o.put("ssid", ssid.replaceAll("^\"|\"$", ""));
                //noinspection deprecation
                o.put("signal", WifiManager.calculateSignalLevel(wi.getRssi(), 4));
                o.put("linkMbps", wi.getLinkSpeed());
                o.put("freqMHz", wi.getFrequency());
            }
        }
        return o.toString();
    }

    // ---------- TV inputs ----------

    @JavascriptInterface
    public String inputs() throws JSONException {
        JSONArray out = new JSONArray();
        TvInputManager tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out.toString();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.isHidden(act)) continue;
            CharSequence custom = info.loadCustomLabel(act);
            JSONObject o = new JSONObject();
            o.put("id", info.getId());
            o.put("name", (custom != null ? custom : info.loadLabel(act)).toString());
            o.put("connected", tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED);
            out.put(o);
        }
        return out.toString();
    }

    @JavascriptInterface
    public boolean openInput(String id) {
        TvInputManager tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return false;
        TvInputInfo info = tim.getTvInputInfo(id);
        if (info == null) return false;
        Uri uri = info.isPassthroughInput() ? TvContract.buildChannelUriForPassthroughInput(id) : TvContract.buildChannelsUriForInput(id);
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(pm) == null) return false;
        start(i);
        return true;
    }

    // ---------- system ----------

    @JavascriptInterface
    public String disabled() throws JSONException {
        List<JSONObject> list = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (ai.enabled) continue;
            JSONObject o = new JSONObject();
            o.put("pkg", ai.packageName);
            o.put("name", ai.loadLabel(pm).toString());
            list.add(o);
        }
        Collator byName = Collator.getInstance(Locale.getDefault());
        list.sort((a, b) -> byName.compare(a.optString("name"), b.optString("name")));
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out.toString();
    }

    @JavascriptInterface
    public String system() throws JSONException {
        JSONObject o = new JSONObject();
        ContentResolver cr = act.getContentResolver();
        o.put("adb", Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1);
        o.put("animScale", Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f));
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null) o.put("home", home.loadLabel(pm).toString());
        return o.toString();
    }

    /**
     * Android's own UI sounds (as any native launcher plays them): the focus tick, the click, keyboard keys.
     * vol 0..1 is the launcher's "interface sounds" level; 0.8 (its default) is the system's usual level.
     */
    @JavascriptInterface
    public void sound(String name, float vol) {
        int fx;
        switch (name) {
            case "tick": fx = AudioManager.FX_FOCUS_NAVIGATION_DOWN; break;
            case "select": fx = AudioManager.FX_KEY_CLICK; break;
            case "key": fx = AudioManager.FX_KEYPRESS_STANDARD; break;
            case "del": fx = AudioManager.FX_KEYPRESS_DELETE; break;
            default: return;
        }
        AudioManager am = act.getSystemService(AudioManager.class);
        if (am != null && vol > 0) am.playSoundEffect(fx, Math.min(1f, 0.5f * vol / 0.8f));
    }

    @JavascriptInterface
    public void reload() {
        act.reload();
    }
}
