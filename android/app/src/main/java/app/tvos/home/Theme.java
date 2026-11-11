package app.tvos.home;

import java.util.Calendar;

/** Light / dark appearance and the accessibility looks (os.css colours). */
final class Theme {
    private Theme() {}

    static boolean dark, contrast, solid;

    /** Appearance: light, dark or automatic (dark from 19:00 to 7:00). */
    static boolean effDark() {
        String l = Prefs.s("look");
        if (!"auto".equals(l)) return "dark".equals(l);
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 7;
    }

    static void read() {
        dark = effDark();
        contrast = Prefs.b("contrast");
        solid = Prefs.b("solid");
        Tv.bold = Prefs.b("bold");
        Motion.still = Prefs.b("still");
    }

    static int label() { return dark ? 0xfff5f5f7 : 0xff1d1d1f; }

    static int sec() {
        if (contrast) return dark ? Tv.rgba(245, 245, 250, .86f) : Tv.rgba(40, 40, 46, .86f);
        return dark ? Tv.rgba(235, 235, 245, .6f) : Tv.rgba(60, 60, 67, .6f);
    }

    static int ter() {
        if (contrast) return dark ? Tv.rgba(245, 245, 250, .55f) : Tv.rgba(40, 40, 46, .55f);
        return dark ? Tv.rgba(235, 235, 245, .3f) : Tv.rgba(60, 60, 67, .3f);
    }

    static int fill() { return dark ? Tv.rgba(120, 120, 128, .32f) : Tv.rgba(120, 120, 128, .16f); }

    static int red() { return dark ? 0xffff453a : 0xffff3b30; }

    static int blue() { return dark ? 0xff0a84ff : 0xff007aff; }

    /** Reduce Transparency: glass panels become this opaque colour. */
    static int solidPanel() { return dark ? Tv.rgba(44, 44, 46, .97f) : Tv.rgba(232, 232, 237, .97f); }

    /** Text on glass panels: white, or dark on the light solid panels. */
    static int onGlass() { return solid && !dark ? 0xff1d1d1f : 0xffffffff; }
}
