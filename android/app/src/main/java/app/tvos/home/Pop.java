package app.tvos.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** The glass popovers of the home screen: TV inputs, Wi-Fi, and the icon's context menu (web .pop). */
final class Pop extends Views.Box {
    static final int INPUTS = 0, WIFI = 1, MENU = 2;
    static final int MG = 72; // room for the shadow around the panel

    static final class Row {
        final String glyph, text, sub;
        boolean red;
        float f; // focus 0..1
        float s = 1; // scale
        Motion.Val vf, vs;

        Row(String glyph, String text, String sub) { this.glyph = glyph; this.text = text; this.sub = sub; }
    }

    final int kind;
    final List<Row> rows = new ArrayList<>();
    int focus = -1, cur = -1;
    String netName = "Wi-Fi", netState = "";
    boolean netOn;
    private final Gfx.Glass glass;
    private float pw, ph;
    private int px, py;
    private boolean shownState;

    Pop(Context c, int kind) {
        super(c, null);
        this.kind = kind;
        painter = (cv, v) -> paintPop(cv);
        glass = new Gfx.Glass(460, 200, kind == MENU ? 34 : 38);
        glass.shadow = new Gfx.Shadow(0, 14, 40, 0, kind == MENU ? 34 : 38, Tv.black(.16f));
        setAlpha(0);
        setVisibility(View.INVISIBLE);
    }

    void setRows(List<Row> r) {
        rows.clear();
        rows.addAll(r);
        for (Row x : rows) {
            x.vf = new Motion.Val(0, 0.004f, v -> { x.f = v; invalidate(); });
            x.vs = new Motion.Val(1, 0.0005f, v -> { x.s = v; invalidate(); });
        }
        focus = -1;
        layoutPanel();
    }

    private float rowsTop() {
        if (kind == MENU) return 12;
        float h3 = 14 + 10 + Tv.lineNormal(H3) + 10;
        return kind == WIFI ? h3 + 88 : h3;
    }

    private float rowH() { return kind == MENU ? 64 : 72; }

    private void layoutPanel() {
        pw = kind == MENU ? 380 : 460;
        ph = rowsTop() + rows.size() * rowH() + (kind == MENU ? 12 : kind == WIFI ? 16 : 14);
        if (kind == INPUTS) { px = 1920 - 178 - 460; py = 164; }
        else if (kind == WIFI) { px = 1920 - 88 - 460; py = 164; }
        place();
    }

    /** The context menu sits beside the icon (right or left of it). */
    void placeAt(int x, int y, boolean rightOfIcon) {
        px = x;
        py = y;
        place();
        setPivotX(rightOfIcon ? MG : MG + pw);
        setPivotY(MG + ph / 2);
    }

    private void place() {
        glass.size(pw, ph);
        glass.at(px, py);
        Views.Abs.place(this, px - MG, py - MG, Math.round(pw) + 2 * MG, Math.round(ph) + 2 * MG);
        if (kind != MENU) { setPivotX(MG + pw); setPivotY(MG); }
        invalidate();
    }

    void focus(int i, int checked) {
        cur = checked;
        if (focus != i) {
            if (focus >= 0 && focus < rows.size()) { rows.get(focus).vf.tween(180, Tv.EASE, 0); rows.get(focus).vs.spring(Motion.FOCUS, 1); }
            focus = i;
            if (i >= 0 && i < rows.size()) {
                Row r = rows.get(i);
                if (getAlpha() < 0.05f) { r.vf.snap(1); r.vs.snap(1.03f); } else { r.vf.tween(180, Tv.EASE, 1); r.vs.spring(Motion.FOCUS, 1.03f); }
            }
        }
        invalidate();
    }

    @Override
    public boolean isShown() { return shownState; }

    /** opacity .24s, scale .9 + 14px up -> none with the panel spring; drawn once into a layer while it moves. */
    void show(boolean on) {
        if (on == shownState && (on || getVisibility() != View.VISIBLE)) return;
        shownState = on;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (on) {
            Motion.snap(this, Motion.SCALE, .9f);
            Motion.snap(this, Motion.TY, -14);
            Motion.snap(this, Motion.ALPHA, 0);
        }
        Motion.fade(this, 240, on ? 1 : 0);
        Motion.spring(this, Motion.TY, Motion.PANEL, on ? 0 : -14);
        Motion.spring(this, Motion.SCALE, Motion.PANEL, on ? 1 : .9f).then(() -> setLayerType(View.LAYER_TYPE_NONE, null));
    }

    private static final TextPaint H3 = Tv.text(20, 600, .5f, Tv.white(.62f));
    private final TextPaint rowP = Tv.text(26, 500, 0, 0xffffffff), subP = Tv.text(20, 400, 0, 0xffffffff);
    private final TextPaint netB = Tv.text(30, 600, 0, 0xffffffff), netS = Tv.text(20, 400, 0, 0xffffffff);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rc = new RectF();
    private static final Gfx.Shadow BLUE_GLOW = new Gfx.Shadow(0, 6, 16, 0, 31, Tv.rgba(10, 132, 255, .4f));

    private void paintPop(Canvas c) {
        glass.adaptive();
        glass.solid = Theme.solid ? Theme.solidPanel() : 0;
        glass.draw(c, MG, MG);
        int fg = Theme.onGlass();
        float x0 = MG, y0 = MG;
        if (kind != MENU) {
            H3.setTypeface(Tv.font(600));
            H3.setColor(Theme.solid && !Theme.dark ? 0xff1d1d1f : Tv.white(.62f));
            String t = kind == INPUTS ? "ИСТОЧНИКИ" : "WI-FI";
            c.drawText(t, x0 + 14 + 22, Tv.baseline(H3, y0 + 24, Tv.lineNormal(H3)), H3);
        }
        if (kind == WIFI) {
            float ny = y0 + 14 + 10 + Tv.lineNormal(H3) + 10 + 8, icx = x0 + 14 + 22;
            if (netOn) BLUE_GLOW.draw(c, icx, ny, icx + 62, ny + 62, 1, true);
            p.setColor(netOn ? 0xff0a84ff : Tv.white(.2f));
            c.drawCircle(icx + 31, ny + 31, 31, p);
            Glyphs.draw(c, "wifi3", icx + 15, ny + 15, 32, 0xffffffff, 2.2f);
            netB.setTypeface(Tv.font(600));
            netB.setColor(fg);
            netS.setColor(Tv.alpha(fg, .72f));
            float lb = Tv.lineNormal(netB), ls = Tv.lineNormal(netS), top = ny + (62 - lb - ls) / 2, tx = icx + 62 + 20;
            float maxW = x0 + pw - 14 - 22 - tx;
            c.drawText(Tv.fit(netB, netName, maxW), tx, Tv.baseline(netB, top, lb), netB);
            c.drawText(Tv.fit(netS, netState, maxW), tx, Tv.baseline(netS, top + lb, ls), netS);
        }
        float rt = y0 + rowsTop(), rh = rowH();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float l = x0 + (kind == MENU ? 12 : 14), w = pw - (kind == MENU ? 24 : 28), t = rt + i * rh;
            int save = c.save();
            c.scale(r.s, r.s, l + w / 2, t + rh / 2);
            if (r.f > 0.004f) {
                p.setColor(Tv.white(.94f * r.f));
                rc.set(l, t, l + w, t + rh);
                c.drawRoundRect(rc, 24, 24, p);
            }
            int base = r.red ? 0xffff6961 : fg, onCol = r.red ? 0xffd70015 : 0xff111111;
            int col = Tv.mix(base, onCol, r.f);
            float tx = l + 22;
            if (r.glyph != null) {
                Glyphs.draw(c, r.glyph, tx, t + rh / 2 - 16, 32, col, 2);
                tx += 32 + 18;
            }
            rowP.setTextSize(kind == MENU ? 25 : 26);
            rowP.setTypeface(Tv.font(500));
            rowP.setColor(col);
            float right = l + w - 22;
            boolean checked = i == cur && kind == INPUTS;
            if (checked) Glyphs.draw(c, "check", right - 32, t + rh / 2 - 16, 32, col, 2);
            else if (r.sub != null && !r.sub.isEmpty()) {
                subP.setColor(Tv.alpha(col, .6f));
                subP.setTextAlign(Paint.Align.RIGHT);
                c.drawText(r.sub, right, Tv.center(subP, t + rh / 2), subP);
                right -= Tv.width(subP, r.sub) + 18;
            }
            c.drawText(Tv.fit(rowP, r.text, right - tx), tx, Tv.center(rowP, t + rh / 2), rowP);
            c.restoreToCount(save);
        }
    }
}
