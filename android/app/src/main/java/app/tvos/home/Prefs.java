package app.tvos.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Settings of the launcher (the same names as in the web prototype), with change listeners.
 * Lists are stored as JSON arrays.
 */
final class Prefs {
    interface Listener { void changed(); }

    private static SharedPreferences sp;
    private static final Map<String, Object> DEF = new HashMap<>();
    private static final Map<String, List<Listener>> subs = new HashMap<>();

    static {
        DEF.put("look", "light");
        DEF.put("clock", "center");
        DEF.put("bright", 100);
        DEF.put("hidden", "[]");
        DEF.put("order", "[]");
        DEF.put("seen", "[]");
        DEF.put("wallpaper", "");
        DEF.put("saverType", "aerial");
        DEF.put("saverAfter", 2);
        DEF.put("saverEvery", 120); // seconds per Aerial film (0 = the whole film); each new film is a new download
        DEF.put("saverClock", true);
        DEF.put("saverCaps", true);
        DEF.put("saverCats", "[\"Landscapes\",\"Cities\",\"Space\",\"Underwater\"]");
        DEF.put("sleepAfter", 60); // as Apple TV: after an hour without a key the screen goes black (stops the Aerial stream)
        DEF.put("bold", false);
        DEF.put("solid", false);
        DEF.put("contrast", false);
        DEF.put("still", false);
        DEF.put("voice", false);
        DEF.put("subs", false);
        DEF.put("userName", "Пользователь");
        DEF.put("userColor", "blue");
        DEF.put("hour24", true);
        DEF.put("fps", false);
        DEF.put("sound", true);      // navigation clicks
        DEF.put("sound.fx", true);   // effects
        DEF.put("sound.vol", 80);    // 0..100
        DEF.put("sound.set", "tvos"); // tvos | system
        DEF.put("last", "");
    }

    static void init(Context c) {
        sp = c.getSharedPreferences("tvos", Context.MODE_PRIVATE);
        importFile(new File(c.getExternalFilesDir(null), "import.json"));
    }

    static String s(String k) {
        Object d = DEF.get(k);
        return sp.getString(k, d == null ? "" : d.toString());
    }

    static int i(String k) {
        Object d = DEF.get(k);
        return sp.getInt(k, d instanceof Integer ? (Integer) d : 0);
    }

    static boolean b(String k) {
        Object d = DEF.get(k);
        return sp.getBoolean(k, d instanceof Boolean && (Boolean) d);
    }

    static List<String> list(String k) {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s(k));
            for (int j = 0; j < a.length(); j++) out.add(a.getString(j));
        } catch (Exception ignored) {
            // a broken value reads as empty
        }
        return out;
    }

    static void set(String k, Object v) {
        SharedPreferences.Editor e = sp.edit();
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof List) e.putString(k, new JSONArray((List<?>) v).toString());
        else e.putString(k, String.valueOf(v));
        e.apply();
        List<Listener> l = subs.get(k);
        if (l != null) for (Listener x : new ArrayList<>(l)) x.changed();
    }

    static void on(String k, Listener l) {
        List<Listener> a = subs.get(k);
        if (a == null) subs.put(k, a = new ArrayList<>());
        a.add(l);
    }

    static void onAny(Listener l, String... keys) { for (String k : keys) on(k, l); }

    static void clearListeners() { subs.clear(); }

    /** Forget everything (Settings -> System -> Reset). */
    static void reset() { sp.edit().clear().commit(); }

    /**
     * Settings carried over from the WebView version: tools put its localStorage (as JSON, keys "tvc.tvos.*")
     * into the app's own external folder; it is read once and deleted.
     */
    private static void importFile(File f) {
        if (f == null || !f.isFile()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            importJson(new String(b, 0, n, "UTF-8"));
        } catch (Exception ex) {
            android.util.Log.w("tvos", "import failed", ex);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Settings of the WebView version (its localStorage as JSON: "tvc.tvos.look": "\"dark\"", ...). */
    static void importJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = sp.edit();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String key = it.next(), raw = o.optString(key);
                String k = key.startsWith("tvc.tvos.") ? key.substring(9) : key.startsWith("tvc.") ? key.substring(4) : key;
                if (k.equals("wallpaper")) { e.putString(k, raw); continue; }
                Object d = DEF.get(k);
                if (d == null) continue;
                Object v = new org.json.JSONTokener(raw).nextValue();
                if (d instanceof Boolean && v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (d instanceof Integer && v instanceof Number) e.putInt(k, k.equals("sound.vol") && ((Number) v).doubleValue() <= 1 ? (int) Math.round(((Number) v).doubleValue() * 100) : ((Number) v).intValue());
                else if (v instanceof JSONArray) e.putString(k, v.toString());
                else if (v instanceof String) e.putString(k, (String) v);
            }
            e.commit();
        } catch (Exception ex) {
            android.util.Log.w("tvos", "import failed", ex);
        }
    }

    static List<String> listOf(String... s) { return new ArrayList<>(Arrays.asList(s)); }
}
