package app.tvos.home;

import android.content.Context;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** The 1920x1080 stage. Children are drawn in the order of their z (as CSS z-index), which screens can change. */
final class Stage extends Views.Abs {
    private final Map<View, Integer> z = new HashMap<>();
    private int[] order = new int[0];
    private boolean dirty = true;

    Stage(Context c) {
        super(c);
        setChildrenDrawingOrderEnabled(true);
        setBackgroundColor(0xff000000);
    }

    <T extends View> T add(T v, int zIndex) {
        add(v, 0, 0, Tv.W, Tv.H);
        z.put(v, zIndex);
        dirty = true;
        return v;
    }

    void setZ(View v, int zIndex) {
        Integer o = z.put(v, zIndex);
        if (o == null || o != zIndex) { dirty = true; invalidate(); }
    }

    @Override
    protected int getChildDrawingOrder(int count, int i) {
        if (dirty || order.length != count) {
            ArrayList<Integer> idx = new ArrayList<>();
            for (int k = 0; k < count; k++) idx.add(k);
            idx.sort((a, b) -> {
                Integer za = z.get(getChildAt(a)), zb = z.get(getChildAt(b));
                int d = Integer.compare(za == null ? 0 : za, zb == null ? 0 : zb);
                return d != 0 ? d : Integer.compare(a, b);
            });
            order = new int[count];
            for (int k = 0; k < count; k++) order[k] = idx.get(k);
            dirty = false;
        }
        return order[i];
    }

    @Override
    public void onViewAdded(View child) { super.onViewAdded(child); dirty = true; }

    @Override
    public void onViewRemoved(View child) { super.onViewRemoved(child); dirty = true; }
}
