package app.tvos.home;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line icons of the web prototype (24x24 SVG, stroked), parsed once into Paths and drawn at any size.
 * The SVG snippets are copied verbatim from web/ (index.html and os/os.js).
 */
final class Glyphs {
    private static final Map<String, String> SRC = new HashMap<>();
    private static final Map<String, Glyph> cache = new HashMap<>();

    static {
        // index.html
        SRC.put("hdmi", "<rect x=\"3\" y=\"7\" width=\"18\" height=\"9\" rx=\"2\"/><path d=\"M7 16v2M17 16v2M7 10.5h10\"/>");
        SRC.put("av", "<rect x=\"2.5\" y=\"4.5\" width=\"19\" height=\"12.5\" rx=\"2.4\"/><path d=\"M8.5 20.2h7\"/><path d=\"M7.6 9.4h1.7l2.3-1.9v6.5l-2.3-1.9H7.6z\"/><path d=\"M14 9.2a2.7 2.7 0 0 1 0 3.6\"/><path d=\"M15.9 7.7a5 5 0 0 1 0 6.6\"/>");
        SRC.put("avin", "<circle cx=\"6\" cy=\"12\" r=\"2.5\"/><circle cx=\"12\" cy=\"12\" r=\"2.5\"/><circle cx=\"18\" cy=\"12\" r=\"2.5\"/>");
        SRC.put("tvin", "<rect x=\"3\" y=\"6\" width=\"18\" height=\"12\" rx=\"2.5\"/><path d=\"M8 3l4 3 4-3\"/>");
        SRC.put("check", "<path d=\"M5 12.5l4.5 4.5L19 7.5\"/>");
        SRC.put("sun", "<circle cx=\"12\" cy=\"12\" r=\"4\" fill=\"currentColor\"/><path d=\"M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6\"/>");
        SRC.put("power", "<path d=\"M12 3.5v8\"/><path d=\"M6.5 7.2a7.8 7.8 0 1 0 11 0\"/>");
        SRC.put("wifi", "<path d=\"M2.5 9.2a14 14 0 0 1 19 0\"/><path d=\"M5.6 12.6a9.4 9.4 0 0 1 12.8 0\"/><path d=\"M8.8 16a4.8 4.8 0 0 1 6.4 0\"/><circle cx=\"12\" cy=\"19.2\" r=\".9\" fill=\"currentColor\" stroke=\"none\"/>");
        SRC.put("wifi3", "<path d=\"M2.5 9.2a14 14 0 0 1 19 0\"/><path d=\"M5.6 12.6a9.4 9.4 0 0 1 12.8 0\"/><path d=\"M8.8 16a4.8 4.8 0 0 1 6.4 0\"/>");
        SRC.put("input", "<rect x=\"3\" y=\"5\" width=\"18\" height=\"12\" rx=\"2.5\"/><path d=\"M8.5 20h7M12 17v3\"/>");
        SRC.put("sound", "<path d=\"M4 9.5h4l5-4v13l-5-4H4z\"/><path d=\"M16.5 9a4.5 4.5 0 0 1 0 6\"/><path d=\"M19 6.5a8 8 0 0 1 0 11\"/>");
        SRC.put("timer", "<circle cx=\"12\" cy=\"13\" r=\"8\"/><path d=\"M12 9v4l2.5 2.5M10 2.8h4\"/>");
        SRC.put("photo", "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"2.5\"/><circle cx=\"9\" cy=\"10\" r=\"1.8\"/><path d=\"M21 16l-5-5-8 8\"/>");
        SRC.put("ccsaver", "<path d=\"M3 16.5l5-5.5 4 4 3-3 6 5.5\"/><rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2.5\"/>");
        SRC.put("cclook", "<circle cx=\"12\" cy=\"12\" r=\"8\"/><path d=\"M12 4a8 8 0 0 1 0 16z\" fill=\"currentColor\"/>");
        SRC.put("ccgear", "<circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7\"/>");
        SRC.put("cc", "<rect x=\"3\" y=\"4\" width=\"18\" height=\"7\" rx=\"3.5\"/><circle cx=\"7.5\" cy=\"7.5\" r=\"1.8\"/><rect x=\"3\" y=\"13\" width=\"18\" height=\"7\" rx=\"3.5\"/><circle cx=\"16.5\" cy=\"16.5\" r=\"1.8\"/>");
        // os/os.js
        SRC.put("user", "<circle cx=\"12\" cy=\"8.2\" r=\"3.7\"/><path d=\"M4.6 19.8c.9-3.7 3.9-5.8 7.4-5.8s6.5 2.1 7.4 5.8\"/>");
        SRC.put("saver", "<rect x=\"2.5\" y=\"4.5\" width=\"19\" height=\"15\" rx=\"2.6\"/><path d=\"M2.9 16.6l5.2-5.2 4.4 4.4 3-3 5.6 5.6\"/><circle cx=\"16.2\" cy=\"9\" r=\"1.6\"/>");
        SRC.put("remote", "<rect x=\"7.2\" y=\"2.5\" width=\"9.6\" height=\"19\" rx=\"3.4\"/><circle cx=\"12\" cy=\"7.6\" r=\"2.6\"/><circle cx=\"10\" cy=\"13.2\" r=\".7\"/><circle cx=\"14\" cy=\"13.2\" r=\".7\"/><circle cx=\"10\" cy=\"16.6\" r=\".7\"/><circle cx=\"14\" cy=\"16.6\" r=\".7\"/>");
        SRC.put("apps", "<rect x=\"3.5\" y=\"3.5\" width=\"7\" height=\"7\" rx=\"2.1\"/><rect x=\"13.5\" y=\"3.5\" width=\"7\" height=\"7\" rx=\"2.1\"/><rect x=\"3.5\" y=\"13.5\" width=\"7\" height=\"7\" rx=\"2.1\"/><rect x=\"13.5\" y=\"13.5\" width=\"7\" height=\"7\" rx=\"2.1\"/>");
        SRC.put("globe", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M3 12h18\"/><path d=\"M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z\"/>");
        SRC.put("access", "<circle cx=\"12\" cy=\"12\" r=\"9.2\"/><circle cx=\"12\" cy=\"7.3\" r=\"1.35\" fill=\"currentColor\" stroke=\"none\"/><path d=\"M7.2 9.9l4.8 1.1 4.8-1.1\"/><path d=\"M12 11v3.3l-2.5 4.3M12 14.3l2.5 4.3\"/>");
        SRC.put("shield", "<path d=\"M12 2.8l7.5 3v5.6c0 4.7-3.2 8.3-7.5 9.8-4.3-1.5-7.5-5.1-7.5-9.8V5.8z\"/><path d=\"M8.8 12.2l2.3 2.3 4.3-4.6\"/>");
        SRC.put("chip", "<rect x=\"6\" y=\"6\" width=\"12\" height=\"12\" rx=\"2.4\"/><rect x=\"9.3\" y=\"9.3\" width=\"5.4\" height=\"5.4\" rx=\"1\"/><path d=\"M9.5 3v3M14.5 3v3M9.5 18v3M14.5 18v3M3 9.5h3M3 14.5h3M18 9.5h3M18 14.5h3\"/>");
        SRC.put("dev", "<path d=\"M8.5 7.5L4 12l4.5 4.5M15.5 7.5L20 12l-4.5 4.5M13.4 5.5l-2.8 13\"/>");
        SRC.put("moon", "<path d=\"M20 14.6A8.5 8.5 0 0 1 9.4 4a8.5 8.5 0 1 0 10.6 10.6z\"/>");
        SRC.put("info", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 11v5.5\"/><circle cx=\"12\" cy=\"7.8\" r=\".95\" fill=\"currentColor\" stroke=\"none\"/>");
        SRC.put("update", "<path d=\"M20 12a8 8 0 0 1-14.3 4.9\"/><path d=\"M4 12a8 8 0 0 1 14.3-4.9\"/><path d=\"M18.6 3.6v3.6H15M5.4 20.4v-3.6H9\"/>");
        SRC.put("reset", "<path d=\"M4.5 12a7.5 7.5 0 1 0 2.2-5.3\"/><path d=\"M4 3.5v4h4\"/>");
        SRC.put("look", "<circle cx=\"12\" cy=\"12\" r=\"8.5\"/><path d=\"M12 3.5a8.5 8.5 0 0 1 0 17z\" fill=\"currentColor\"/>");
        SRC.put("home", "<rect x=\"2.5\" y=\"4\" width=\"19\" height=\"16\" rx=\"2.6\"/><rect x=\"5.3\" y=\"12.6\" width=\"3.6\" height=\"2.6\" rx=\".7\"/><rect x=\"10.2\" y=\"12.6\" width=\"3.6\" height=\"2.6\" rx=\".7\"/><rect x=\"15.1\" y=\"12.6\" width=\"3.6\" height=\"2.6\" rx=\".7\"/><path d=\"M9 7.6h6\"/>");
        SRC.put("clock", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 6.8V12l3.4 2\"/>");
        SRC.put("keyboard", "<rect x=\"2.5\" y=\"6\" width=\"19\" height=\"12\" rx=\"2.3\"/><path d=\"M6 9.6h.01M9 9.6h.01M12 9.6h.01M15 9.6h.01M18 9.6h.01M7.5 12.4h.01M10.5 12.4h.01M13.5 12.4h.01M16.5 12.4h.01M8 15.2h8\"/>");
        SRC.put("bt", "<path d=\"M7 7.5l10 9-5 4.5V3l5 4.5-10 9\"/>");
        SRC.put("lock", "<rect x=\"5.5\" y=\"10.5\" width=\"13\" height=\"10\" rx=\"2.3\"/><path d=\"M8.5 10.5V7.8a3.5 3.5 0 0 1 7 0v2.7\"/>");
        SRC.put("eth", "<rect x=\"4\" y=\"7\" width=\"16\" height=\"12\" rx=\"2.2\"/><path d=\"M8 19v-3.2M11 19v-3.2M14 19v-3.2M17 19v-3.2M9 7V4.5h6V7\"/>");
        SRC.put("eye", "<path d=\"M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/>");
        SRC.put("del", "<path d=\"M9 5.5h10.5a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9L3 12z\"/><path d=\"M12.5 9.5l5 5M17.5 9.5l-5 5\"/>");
        SRC.put("play", "<path d=\"M8 5.5v13l10.5-6.5z\"/>");
        SRC.put("tv", "<rect x=\"2.5\" y=\"4.5\" width=\"19\" height=\"13\" rx=\"2.4\"/><path d=\"M8 20h8\"/>");
        SRC.put("hand", "<path d=\"M8 13V5.5a1.5 1.5 0 0 1 3 0V11\"/><path d=\"M11 10V4a1.5 1.5 0 0 1 3 0v6\"/><path d=\"M14 10.5V5.5a1.5 1.5 0 0 1 3 0V14c0 4-2.7 7-6.5 7-2.6 0-4.3-1.3-5.6-3.4L3.4 15a1.5 1.5 0 0 1 2.4-1.8L8 15\"/>");
        SRC.put("text", "<path d=\"M7 4.5h6a3.6 3.6 0 0 1 0 7.2H7zM7 11.7h7a3.9 3.9 0 0 1 0 7.8H7z\"/>");
        SRC.put("motion", "<path d=\"M3 12h4l3-7 4 14 3-7h4\"/>");
        SRC.put("contrast", "<circle cx=\"12\" cy=\"12\" r=\"8.5\"/><path d=\"M12 3.5v17M12 8l5.5-3M12 12.5l8-3.5M12 17l7.3-3.5\"/>");
        SRC.put("plus", "<path d=\"M12 5v14M5 12h14\"/>");
        SRC.put("speed", "<path d=\"M4.4 17a8.6 8.6 0 1 1 15.2 0\"/><path d=\"M12 13.6l4.2-4.4\"/><circle cx=\"12\" cy=\"13.6\" r=\"1.4\" fill=\"currentColor\" stroke=\"none\"/><path d=\"M7.6 8.6l.9.9M12 6.3v1.3M16.4 8.6l-.9.9\"/>");
        // os.js: an 8-tooth gear outline
        StringBuilder g = new StringBuilder("<path d=\"M");
        int n = 8;
        double ro = 9.6, ri = 7.6;
        boolean first = true;
        for (int i = 0; i < n; i++) {
            double a = (double) i / n * Math.PI * 2, w = Math.PI * 2 / n;
            double[][] pts = {{a - w * 0.5, ri}, {a - w * 0.2, ri}, {a - w * 0.13, ro}, {a + w * 0.13, ro}, {a + w * 0.2, ri}, {a + w * 0.5, ri}};
            for (double[] p : pts) {
                if (!first) g.append('L');
                first = false;
                g.append(String.format(java.util.Locale.ROOT, "%.2f %.2f", 12 + Math.cos(p[0]) * p[1], 12 + Math.sin(p[0]) * p[1]));
            }
        }
        g.append("Z\"/><circle cx=\"12\" cy=\"12\" r=\"3.2\"/>");
        SRC.put("gear", g.toString());
    }

    /** One parsed icon: stroked paths and filled paths, each with its own opacity. */
    static final class Glyph {
        final ArrayList<Path> stroke = new ArrayList<>(), fill = new ArrayList<>();
        final ArrayList<Float> strokeA = new ArrayList<>(), fillA = new ArrayList<>();
    }

    static Glyph get(String key) {
        Glyph g = cache.get(key);
        if (g == null) {
            String s = SRC.get(key);
            g = parse(s == null ? "" : s);
            cache.put(key, g);
        }
        return g;
    }

    /** A Wi-Fi strength icon: 1..3 arcs drawn, the rest faint (os.js wifiSig). */
    static Glyph wifiSig(int n) {
        String k = "wifiSig" + n;
        Glyph g = cache.get(k);
        if (g == null) {
            g = parse("<path d=\"M2.5 9.2a14 14 0 0 1 19 0\" opacity=\"" + (n >= 3 ? 1 : 0.28) + "\"/><path d=\"M5.6 12.6a9.4 9.4 0 0 1 12.8 0\" opacity=\"" + (n >= 2 ? 1 : 0.28)
                    + "\"/><path d=\"M8.8 16a4.8 4.8 0 0 1 6.4 0\"/><circle cx=\"12\" cy=\"19.2\" r=\".9\" fill=\"currentColor\" stroke=\"none\"/>");
            cache.put(k, g);
        }
        return g;
    }

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Draw icon `key` in a size x size box at (x, y); strokeWidth in the icon's 24-unit space (CSS stroke-width). */
    static void draw(Canvas c, String key, float x, float y, float size, int color, float strokeWidth) {
        draw(c, get(key), x, y, size, color, strokeWidth);
    }

    static void draw(Canvas c, Glyph g, float x, float y, float size, int color, float strokeWidth) {
        int save = c.save();
        c.translate(x, y);
        float s = size / 24f;
        c.scale(s, s);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        P.setShader(null);
        int a = android.graphics.Color.alpha(color);
        P.setStyle(Paint.Style.FILL);
        for (int i = 0; i < g.fill.size(); i++) {
            P.setColor(color);
            P.setAlpha(Math.round(a * g.fillA.get(i)));
            c.drawPath(g.fill.get(i), P);
        }
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(strokeWidth);
        for (int i = 0; i < g.stroke.size(); i++) {
            P.setColor(color);
            P.setAlpha(Math.round(a * g.strokeA.get(i)));
            c.drawPath(g.stroke.get(i), P);
        }
        c.restoreToCount(save);
    }

    // ---------- a small SVG reader: <path d>, <rect>, <circle> ----------

    private static final Pattern EL = Pattern.compile("<(path|rect|circle)([^>]*)/?>");
    private static final Pattern AT = Pattern.compile("([a-zA-Z-]+)=\"([^\"]*)\"");

    static Glyph parse(String svg) {
        Glyph g = new Glyph();
        Matcher m = EL.matcher(svg);
        while (m.find()) {
            Map<String, String> at = new HashMap<>();
            Matcher a = AT.matcher(m.group(2));
            while (a.find()) at.put(a.group(1), a.group(2));
            Path p = new Path();
            switch (m.group(1)) {
                case "path": pathData(p, at.get("d")); break;
                case "rect": {
                    float x = f(at, "x"), y = f(at, "y"), w = f(at, "width"), h = f(at, "height"), rx = f(at, "rx");
                    p.addRoundRect(new RectF(x, y, x + w, y + h), rx, at.containsKey("ry") ? f(at, "ry") : rx, Path.Direction.CW);
                    break;
                }
                default: p.addCircle(f(at, "cx"), f(at, "cy"), f(at, "r"), Path.Direction.CW);
            }
            float op = at.containsKey("opacity") ? f(at, "opacity") : 1f;
            boolean filled = "currentColor".equals(at.get("fill")), stroked = !"none".equals(at.get("stroke"));
            if (filled) { g.fill.add(p); g.fillA.add(op); }
            if (stroked) { g.stroke.add(p); g.strokeA.add(op); }
        }
        return g;
    }

    private static float f(Map<String, String> at, String k) {
        String v = at.get(k);
        return v == null ? 0 : Float.parseFloat(v);
    }

    private static final class Scan {
        final String s;
        int i;

        Scan(String s) { this.s = s; }

        void skip() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\t')) i++; }

        boolean num() {
            skip();
            if (i >= s.length()) return false;
            char ch = s.charAt(i);
            return ch == '-' || ch == '+' || ch == '.' || (ch >= '0' && ch <= '9');
        }

        float n() {
            skip();
            int st = i;
            boolean dot = false, exp = false;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') i++;
                else if (ch == '.' && !dot && !exp) { dot = true; i++; }
                else if ((ch == 'e' || ch == 'E') && !exp) { exp = true; i++; if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++; }
                else break;
            }
            return Float.parseFloat(s.substring(st, i));
        }

        /** An arc flag: a single 0 or 1, possibly written without a separator. */
        boolean flag() {
            skip();
            char ch = s.charAt(i++);
            return ch == '1';
        }
    }

    static void pathData(Path p, String d) {
        if (d == null) return;
        Scan sc = new Scan(d);
        float cx = 0, cy = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;
        char cmd = 0, prev = 0;
        while (true) {
            sc.skip();
            if (sc.i >= d.length()) break;
            char ch = d.charAt(sc.i);
            if (Character.isLetter(ch) && ch != 'e' && ch != 'E') { cmd = ch; sc.i++; }
            else if (cmd == 0) break;
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    cx = ox + sc.n(); cy = oy + sc.n();
                    p.moveTo(cx, cy); sx = cx; sy = cy;
                    cmd = rel ? 'l' : 'L'; // following pairs are line-tos
                    break;
                }
                case 'L': cx = ox + sc.n(); cy = oy + sc.n(); p.lineTo(cx, cy); break;
                case 'H': cx = ox + sc.n(); p.lineTo(cx, cy); break;
                case 'V': cy = oy + sc.n(); p.lineTo(cx, cy); break;
                case 'C': {
                    float x1 = ox + sc.n(), y1 = oy + sc.n(), x2 = ox + sc.n(), y2 = oy + sc.n();
                    cx = ox + sc.n(); cy = oy + sc.n();
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2;
                    break;
                }
                case 'S': {
                    char pu = Character.toUpperCase(prev);
                    float x1 = pu == 'C' || pu == 'S' ? 2 * cx - lcx : cx, y1 = pu == 'C' || pu == 'S' ? 2 * cy - lcy : cy;
                    float x2 = ox + sc.n(), y2 = oy + sc.n();
                    cx = ox + sc.n(); cy = oy + sc.n();
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + sc.n(), y1 = oy + sc.n();
                    cx = ox + sc.n(); cy = oy + sc.n();
                    p.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1;
                    break;
                }
                case 'A': {
                    float rx = sc.n(), ry = sc.n(), rot = sc.n();
                    boolean large = sc.flag(), sweep = sc.flag();
                    float x = ox + sc.n(), y = oy + sc.n();
                    arc(p, cx, cy, x, y, rx, ry, rot, large, sweep);
                    cx = x; cy = y;
                    break;
                }
                case 'Z': p.close(); cx = sx; cy = sy; break;
                default: return;
            }
            prev = cmd;
            if (Character.toUpperCase(cmd) == 'Z') { cmd = 0; prev = 'Z'; }
            else if (!sc.num()) continue;
        }
    }

    /** SVG elliptical arc as cubic Béziers (the endpoint parameterisation of SVG 1.1, appendix F.6). */
    private static void arc(Path p, float x0, float y0, float x, float y, float rx, float ry, float angle, boolean large, boolean sweep) {
        if (x0 == x && y0 == y) return;
        if (rx == 0 || ry == 0) { p.lineTo(x, y); return; }
        rx = Math.abs(rx); ry = Math.abs(ry);
        double phi = Math.toRadians(angle % 360), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double x1 = cos * dx + sin * dy, y1 = -sin * dx + cos * dy;
        double lam = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        if (lam > 1) { double s = Math.sqrt(lam); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1, den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
        double co = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = co * rx * y1 / ry, cyp = -co * ry * x1 / rx;
        double ccx = cos * cxp - sin * cyp + (x0 + x) / 2.0, ccy = sin * cxp + cos * cyp + (y0 + y) / 2.0;
        double t1 = Math.atan2((y1 - cyp) / ry, (x1 - cxp) / rx), t2 = Math.atan2((-y1 - cyp) / ry, (-x1 - cxp) / rx);
        double dt = t2 - t1;
        if (sweep && dt < 0) dt += 2 * Math.PI;
        else if (!sweep && dt > 0) dt -= 2 * Math.PI;
        int segs = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-7);
        double step = dt / segs, k = 4.0 / 3.0 * Math.tan(step / 4);
        double t = t1;
        for (int i = 0; i < segs; i++) {
            double c1 = Math.cos(t), s1 = Math.sin(t), c2 = Math.cos(t + step), s2 = Math.sin(t + step);
            double ax = rx * (c1 - k * s1), ay = ry * (s1 + k * c1), bx = rx * (c2 + k * s2), by = ry * (s2 - k * c2), ex = rx * c2, ey = ry * s2;
            p.cubicTo((float) (ccx + cos * ax - sin * ay), (float) (ccy + sin * ax + cos * ay),
                    (float) (ccx + cos * bx - sin * by), (float) (ccy + sin * bx + cos * by),
                    (float) (ccx + cos * ex - sin * ey), (float) (ccy + sin * ex + cos * ey));
            t += step;
        }
    }
}
