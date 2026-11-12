package app.tvos.home;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** System pieces shared by the screens: modal layers (alert, keyboard, app switcher), recent apps, VoiceOver. */
final class OS {
    private OS() {}

    interface Layer { void key(String a); }

    private static final ArrayList<Layer> layers = new ArrayList<>();

    static Layer top() { return layers.isEmpty() ? null : layers.get(layers.size() - 1); }

    static void push(Layer l) { layers.add(l); }

    static void drop(Layer l) { layers.remove(l); }

    // ---------- recent apps (app switcher), most recent last ----------

    private static final ArrayList<String> recents = new ArrayList<>();

    private static final java.util.HashMap<String, Long> closed = new java.util.HashMap<>();

    private static final java.util.HashMap<String, Long> usedAt = new java.util.HashMap<>();

    static void recent(String id) { recents.remove(id); recents.add(id); closed.remove(id); usedAt.put(id, System.currentTimeMillis()); }

    /** When the launcher last opened it (0: not in this run). */
    static long usedAt(String id) { Long t = usedAt.get(id); return t == null ? 0 : t; }

    static List<String> recents() { return new ArrayList<>(recents); }

    /** Closed in the app switcher: not shown again until it is used again (Android's history still lists it). */
    static void forget(String id) { recents.remove(id); closed.put(id, System.currentTimeMillis()); }

    static boolean closedAfter(String id, long usedAt) {
        Long t = closed.get(id);
        return t != null && t >= usedAt;
    }

    // ---------- VoiceOver: speech only while the setting is on (the engine is not even started otherwise) ----------

    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static String pending;

    static void speak(String text) {
        if (!Prefs.b("voice") || text == null || text.isEmpty()) return;
        if (tts == null) {
            Context c = Tv.app;
            tts = new TextToSpeech(c, s -> {
                ttsReady = s == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(new Locale("ru", "RU"));
                    tts.setSpeechRate(1.08f);
                    if (pending != null) tts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "tvos");
                    pending = null;
                }
            });
        }
        if (!ttsReady) { pending = text; return; }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tvos");
    }

    static void stopSpeech() {
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; ttsReady = false; }
    }
}
