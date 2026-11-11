package app.tvos.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Interface sounds. The default set ("TV OS") is a palette of soft, short, tvOS-like clicks made for this launcher
 * (tools/sounds.py renders them into res/raw): tiny 48 kHz mono samples preloaded into a SoundPool, so a key press
 * costs nothing but a mixer voice. The other set is Android's own system clicks.
 *
 * Two groups, as in tvOS: navigation clicks (focus, OK, Back, keys) and effects (chime, error).
 * Panels, menus and launches stay silent.
 */
final class Sounds {
    private static SoundPool pool;
    private static final Map<String, Integer> ids = new HashMap<>();
    private static final Map<String, Float> gain = new HashMap<>();
    private static AudioManager am;
    private static long lastNav;
    private static final Random rnd = new Random();

    static final String[][] NAMES = {
            {"tick", "Перемещение фокуса"}, {"select", "Нажатие OK"}, {"back", "Назад"}, {"key", "Клавиша"}, {"del", "Стереть"},
            {"chime", "Готово"}, {"error", "Ошибка"},
    };
    private static final String[] NAV = {"tick", "select", "back", "key", "del"};

    static void init(Context c) {
        am = c.getSystemService(AudioManager.class);
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        load(c, "tick", R.raw.ui_tick, 1f);
        load(c, "select", R.raw.ui_select, 1f);
        load(c, "back", R.raw.ui_back, 1f);
        load(c, "key", R.raw.ui_key, 1f);
        load(c, "del", R.raw.ui_del, 1f);
        load(c, "chime", R.raw.ui_chime, 1f);
        load(c, "error", R.raw.ui_error, 1f);
    }

    private static void load(Context c, String k, int res, float g) {
        ids.put(k, pool.load(c, res, 1));
        gain.put(k, g);
    }

    static boolean system() { return "system".equals(Prefs.s("sound.set")); }

    private static boolean isNav(String k) {
        for (String n : NAV) if (n.equals(k)) return true;
        return false;
    }

    static void play(String k) { play(k, 1f); }

    static void play(String k, float g) {
        if (pool == null) return;
        if (!(isNav(k) ? Prefs.b("sound") : Prefs.b("sound.fx"))) return;
        float vol = Prefs.i("sound.vol") / 100f;
        if (vol <= 0) return;
        if (system()) {
            int fx;
            switch (k) {
                case "tick": fx = AudioManager.FX_FOCUS_NAVIGATION_DOWN; break;
                case "select": fx = AudioManager.FX_KEY_CLICK; break;
                case "key": fx = AudioManager.FX_KEYPRESS_STANDARD; break;
                case "del": fx = AudioManager.FX_KEYPRESS_DELETE; break;
                default: return;
            }
            am.playSoundEffect(fx, Math.min(1f, 0.5f * vol / 0.8f * g));
            return;
        }
        Integer id = ids.get(k);
        if (id == null) return;
        float v = (float) Math.pow(vol, 1.5) * gain.get(k) * g;
        float rate = "tick".equals(k) || "key".equals(k) ? 1f + (rnd.nextFloat() * 2 - 1) * 0.015f : 1f;
        pool.play(id, v, v, 1, 0, rate);
    }

    /** Navigation sounds get softer when keys repeat quickly (an arrow held down), as on tvOS. */
    private static void nav(String k) {
        long now = SystemClock.uptimeMillis();
        boolean fast = now - lastNav < 125;
        lastNav = now;
        play(k, fast ? 0.62f : 1f);
    }

    static void tick() { nav("tick"); }

    static void select() { play("select"); }

    static void back() { play("back"); }

    static void key() { nav("key"); }

    static void del() { nav("del"); }

    static void chime() { play("chime"); }

    static void error() { play("error"); }

    /** A slider step: the focus tick (its pitch follows the value with the TV OS set). */
    static void slide(float v) {
        if (system()) { tick(); return; }
        if (pool == null || !Prefs.b("sound")) return;
        float vol = Prefs.i("sound.vol") / 100f;
        Integer id = ids.get("tick");
        if (id == null || vol <= 0) return;
        float g = (float) Math.pow(vol, 1.5);
        pool.play(id, g, g, 1, 0, 0.85f + Math.max(0, Math.min(1, v)) * 0.35f);
    }

    /** Edge of a list or a panel: tvOS answers with the bounce only, no sound. */
    static void bump() {}
}
