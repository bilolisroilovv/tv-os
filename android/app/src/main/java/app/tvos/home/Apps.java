package app.tvos.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The apps on the home screen: every launchable app with its Android TV banner (an app with only an icon gets a
 * tile drawn from it, as in the web prototype), plus the launcher's own Settings.
 * Banners are drawn once into the cache (640 px wide PNG) and kept in memory at the size the home screen shows.
 */
final class Apps {
    static final class App {
        String id, pkg, name;
        boolean tv; // a TV app (has a Leanback entry and is not another home screen)
        Bitmap banner;  // ~384 px wide, for the grid
        File big;       // 640 px wide, for big pictures (launch, Settings)
        String asset;   // a banner of this computer's own (assets/icons/<package>.png) instead of the app's
        int[] color = {120, 120, 140};
        String tagline;
    }

    /** Not shown as tiles: Android's own TV settings open from the launcher's Settings instead. */
    private static final Set<String> SKIP = new HashSet<>(List.of("com.android.tv.settings"));

    static App settingsApp(Context ctx) {
        App a = new App();
        a.id = "settings";
        a.pkg = "";
        a.name = "Настройки";
        a.tv = true;
        a.tagline = "Настройки лаунчера и ТВ";
        a.banner = settingsBanner(384, 216);
        a.color = new int[]{150, 156, 165};
        own(ctx, a);
        return a;
    }

    private static org.json.JSONObject names;

    /** A banner (and name) of this computer's own for the app, if web/assets/icons has one: assets/icons/. */
    private static void own(Context ctx, App a) {
        String path = "icons/" + a.id + ".png";
        try (java.io.InputStream in = ctx.getAssets().open(path)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) {
                if (a.banner != null && "settings".equals(a.id)) a.banner.recycle();
                a.banner = scaled(b, 384);
                a.asset = path;
                a.color = Gfx.color(a.banner);
            }
        } catch (Throwable ignored) {
            return; // none: the app's own banner
        }
        try {
            if (names == null) {
                try (java.io.InputStream in = ctx.getAssets().open("icons/names.json")) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                    names = new org.json.JSONObject(o.toString("UTF-8"));
                }
            }
            String n = names.optString(a.id, null);
            if (n != null && !n.isEmpty()) a.name = n;
        } catch (Exception ignored) {
            names = new org.json.JSONObject();
        }
    }

    /** Scans the installed apps (background thread). */
    static List<App> scan(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        Set<String> leanback = new HashSet<>(), homes = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (cat.equals(Intent.CATEGORY_LEANBACK_LAUNCHER)) leanback.add(p);
                if (!found.containsKey(p) && !p.equals(ctx.getPackageName()) && !SKIP.contains(p)) found.put(p, ri);
            }
        }
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            homes.add(ri.activityInfo.packageName);
        }
        File dir = new File(ctx.getCacheDir(), "banners");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Set<String> keep = new HashSet<>();
        List<App> list = new ArrayList<>();
        for (ResolveInfo ri : found.values()) {
            String p = ri.activityInfo.packageName;
            try {
                App a = new App();
                a.id = a.pkg = p;
                a.name = ri.loadLabel(pm).toString();
                a.tv = leanback.contains(p) && !homes.contains(p);
                long stamp = pm.getPackageInfo(p, 0).lastUpdateTime;
                File f = new File(dir, p + "-" + stamp + ".png");
                keep.add(f.getName());
                if (f.exists()) a.banner = scaled(BitmapFactory.decodeFile(f.getPath()), 384); // null if the file is damaged
                if (a.banner == null) {
                    Drawable banner = ri.activityInfo.loadBanner(pm);
                    if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                    Bitmap big = banner != null ? draw(banner, 640) : iconTile(ri.loadIcon(pm), a.name);
                    // written whole, then renamed: a launcher killed mid-write never leaves a broken picture
                    File tmp = new File(dir, f.getName() + ".tmp");
                    try (FileOutputStream os = new FileOutputStream(tmp)) { big.compress(Bitmap.CompressFormat.PNG, 100, os); }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(f);
                    a.banner = scaled(big, 384);
                    if (a.banner != big) big.recycle();
                }
                a.big = f;
                a.color = Gfx.color(a.banner);
                own(ctx, a);
                list.add(a);
            } catch (Throwable skipped) {
                // an app that went away meanwhile, or a drawable that cannot be drawn
            }
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
            f.delete();
        Collator byName = Collator.getInstance(Locale.getDefault());
        list.sort((x, y) -> byName.compare(x.name, y.name));
        return list;
    }

    private static Bitmap scaled(Bitmap b, int w) {
        if (b == null) return null;
        if (b.getWidth() <= w) return b;
        int h = Math.max(1, Math.round(b.getHeight() * (float) w / b.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
        if (s != b) b.recycle();
        return s;
    }

    /** A drawable at most maxW wide (banners are 16:9). */
    private static Bitmap draw(Drawable d, int maxW) {
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : maxW, h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : maxW * 9 / 16;
        float s = maxW / (float) w;
        int bw = Math.max(1, Math.round(w * s)), bh = Math.max(1, Math.round(h * s));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(b));
        return b;
    }

    /** An app with only an icon: its icon on a gradient of its colour, with the name (web index.html bannerFor). */
    private static Bitmap iconTile(Drawable icon, String name) {
        int W = 640, H = 384;
        Bitmap ic = draw(icon, 192);
        int[] col = Gfx.color(ic);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, H, Tv.rgba((int) (col[0] * .62f), (int) (col[1] * .62f), (int) (col[2] * .62f), 1),
                Tv.rgba((int) (col[0] * .32f), (int) (col[1] * .32f), (int) (col[2] * .32f), 1), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        float k = W / 630f;
        c.drawBitmap(ic, null, new RectF((W - 168 * k) / 2, 64 * k, (W + 168 * k) / 2, (64 + 168) * k), p);
        TextPaint t = Tv.text(36 * k, 600, 0, 0xffffffff);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(Tv.fit(t, name, W - 60 * k), W / 2f, 308 * k, t);
        ic.recycle();
        return b;
    }

    /** The banner of the launcher's own Settings: a light gear on graphite (web platform.js SETTINGS_BANNER). */
    static Bitmap settingsBanner(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xff9aa0a8, 0xff5e646c, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        float k = h / 360f;
        c.translate(w / 2f, h / 2f);
        c.scale(k, k);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        g.setShader(new LinearGradient(0, -120, 0, 120, 0xfff4f5f7, 0xffc9ccd2, Shader.TileMode.CLAMP));
        for (int i = 0; i < 12; i++) {
            c.save();
            c.rotate(i * 30);
            c.drawRoundRect(new RectF(-14, -118, 14, -78), 6, 6, g);
            c.restore();
        }
        c.drawCircle(0, 0, 92, g);
        p.setShader(null);
        p.setColor(0xff6f757d);
        c.drawCircle(0, 0, 62, p);
        c.drawCircle(0, 0, 36, g);
        p.setColor(0xff7b8189);
        c.drawCircle(0, 0, 20, p);
        return b;
    }

    /** The big banner (640 px) for the launch screen and Settings (background thread). */
    static Bitmap big(App a) {
        if (a.asset != null) {
            try (java.io.InputStream in = Tv.app.getAssets().open(a.asset)) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) return b;
            } catch (Exception ignored) {
                // fall back to the app's own
            }
        }
        if (a.big != null && a.big.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(a.big.getPath());
            if (b != null) return b;
        }
        if ("settings".equals(a.id)) return settingsBanner(640, 360);
        return a.banner;
    }

    static boolean launch(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i, android.app.ActivityOptions.makeCustomAnimation(ctx, R.anim.app_in, R.anim.none).toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
