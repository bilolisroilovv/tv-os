package app.tvos.home;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
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
import android.view.WindowManager;

import java.net.Inet4Address;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the launcher reads from Android, and the Android screens it opens (the WebView version's Bridge). */
final class Device {
    private Device() {}

    static final class Info {
        String name, maker, model, android, hardware, display;
        int sdk, cores;
        long ramMB, ramFreeMB;
        double storageTotalGB, storageFreeGB;
        String appVersion;
    }

    static final class Net {
        boolean connected;
        String type = "none", ssid, ip;
        int signal = -1, linkMbps, freqMHz;
    }

    static final class Input {
        String id, name;
        boolean connected;
    }

    static final class Pkg {
        String pkg, name;
    }

    static Info info(Context c) {
        Info o = new Info();
        ContentResolver cr = c.getContentResolver();
        String name = Settings.Global.getString(cr, "device_name");
        o.name = name != null ? name : Build.MODEL;
        o.maker = Build.MANUFACTURER;
        o.model = Build.MODEL;
        o.android = Build.VERSION.RELEASE;
        o.sdk = Build.VERSION.SDK_INT;
        o.cores = Runtime.getRuntime().availableProcessors();
        o.hardware = Build.HARDWARE;
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        o.ramMB = mi.totalMem / 1048576;
        o.ramFreeMB = mi.availMem / 1048576;
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        o.storageTotalGB = Math.round(fs.getTotalBytes() / 1e8) / 10.0;
        o.storageFreeGB = Math.round(fs.getAvailableBytes() / 1e8) / 10.0;
        Display d = c.getSystemService(WindowManager.class).getDefaultDisplay();
        Point p = new Point();
        d.getRealSize(p);
        o.display = p.x + " × " + p.y + " · " + Math.round(d.getRefreshRate()) + " Гц";
        try {
            o.appVersion = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            o.appVersion = "";
        }
        return o;
    }

    static Net net(Context c) {
        Net o = new Net();
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
        o.connected = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        o.type = nc == null ? "none" : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet" : "other";
        LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
        if (lp != null) for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) { o.ip = la.getAddress().getHostAddress(); break; }
        }
        if ("wifi".equals(o.type)) {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                String ssid = wi.getSSID();
                if (ssid != null && !ssid.contains("<unknown")) o.ssid = ssid.replaceAll("^\"|\"$", "");
                //noinspection deprecation
                o.signal = WifiManager.calculateSignalLevel(wi.getRssi(), 4);
                o.linkMbps = wi.getLinkSpeed();
                o.freqMHz = wi.getFrequency();
            }
        }
        return o;
    }

    /** Screenshots for the README: the network's name is not shown (adb only, see Bench). */
    static boolean hideSsid;

    static String netName(Net n) { if (hideSsid && n.connected && "wifi".equals(n.type)) return "Wi-Fi"; return !n.connected ? "Нет сети" : "ethernet".equals(n.type) ? "Ethernet" : n.ssid != null ? n.ssid : "Wi-Fi"; }

    static List<Input> inputs(Context c) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isHidden(c)) continue;
                CharSequence custom = info.loadCustomLabel(c);
                Input o = new Input();
                o.id = info.getId();
                o.name = (custom != null ? custom : info.loadLabel(c)).toString();
                o.connected = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
                out.add(o);
            }
        } catch (RuntimeException ignored) {
            // TV input service not available
        }
        return out;
    }

    static boolean openInput(Context c, String id) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return false;
        TvInputInfo info = tim.getTvInputInfo(id);
        if (info == null) return false;
        Uri uri = info.isPassthroughInput() ? TvContract.buildChannelUriForPassthroughInput(id) : TvContract.buildChannelsUriForInput(id);
        return start(c, new Intent(Intent.ACTION_VIEW, uri));
    }

    static List<Pkg> disabled(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Pkg> list = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (ai.enabled) continue;
            Pkg o = new Pkg();
            o.pkg = ai.packageName;
            o.name = ai.loadLabel(pm).toString();
            list.add(o);
        }
        Collator by = Collator.getInstance(Locale.getDefault());
        list.sort((a, b) -> by.compare(a.name, b.name));
        return list;
    }

    static final class Sys {
        Boolean adb;
        Float anim;
        String home;
    }

    static Sys system(Context c) {
        Sys o = new Sys();
        ContentResolver cr = c.getContentResolver();
        o.adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1;
        o.anim = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        PackageManager pm = c.getPackageManager();
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null) o.home = home.loadLabel(pm).toString();
        return o;
    }

    /** An Android settings screen: wifi, bluetooth, display, sound, apps, appinfo (+ package), accounts, about, ... */
    static boolean open(Context c, String what, String arg) {
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
        Intent i = new Intent(action);
        if ("appinfo".equals(what)) i.setData(Uri.fromParts("package", arg, null));
        return start(c, i);
    }

    static boolean start(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(c.getPackageManager()) == null) return false;
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
