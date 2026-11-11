package app.tvos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;

/** The two building blocks of every screen: an absolute layout in stage pixels and a view that draws itself. */
final class Views {
    private Views() {}

    /** Children placed at (x, y) with a fixed size, like position: absolute in CSS. */
    static class Abs extends ViewGroup {
        Abs(Context c) {
            super(c);
            setOutlineProvider(null); // never casts a system shadow, whatever its Z
            setClipChildren(false);
            setClipToPadding(false);
            setWillNotDraw(true);
        }

        static final class LP extends ViewGroup.LayoutParams {
            int x, y;

            LP(int x, int y, int w, int h) {
                super(w, h);
                this.x = x;
                this.y = y;
            }
        }

        <T extends View> T add(T v, int x, int y, int w, int h) {
            addView(v, new LP(x, y, w, h));
            return v;
        }

        static void place(View v, int x, int y, int w, int h) {
            LP lp = (LP) v.getLayoutParams();
            if (lp.x == x && lp.y == y && lp.width == w && lp.height == h) return;
            lp.x = x; lp.y = y; lp.width = w; lp.height = h;
            v.requestLayout();
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                LP lp = (LP) c.getLayoutParams();
                c.measure(MeasureSpec.makeMeasureSpec(Math.max(0, lp.width), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, lp.height), MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(MeasureSpec.getSize(wm), MeasureSpec.getSize(hm));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                LP lp = (LP) c.getLayoutParams();
                c.layout(lp.x, lp.y, lp.x + c.getMeasuredWidth(), lp.y + c.getMeasuredHeight());
            }
        }

        @Override
        public boolean hasOverlappingRendering() { return overlapping; }

        boolean overlapping = true;

        @Override
        public boolean shouldDelayChildPressedState() { return false; }
    }

    interface Painter { void paint(Canvas c, Box v); }

    /** A view that draws itself with a painter; `hasOverlappingRendering` false so fading it needs no offscreen layer. */
    static class Box extends View {
        Painter painter;
        Object tag;
        boolean overlapping;

        Box(Context c, Painter p) {
            super(c);
            painter = p;
            setWillNotDraw(false);
            setOutlineProvider(null);
        }

        @Override
        protected void onDraw(Canvas c) { if (painter != null) painter.paint(c, this); }

        @Override
        public boolean hasOverlappingRendering() { return overlapping; }

        float w() { return getWidth(); }

        float h() { return getHeight(); }
    }

    /** Static text with soft shadows is rendered once into a bitmap: drawing it is then a single quad. */
    static final class Cached {
        private Bitmap bmp;
        private String key;

        interface Render { void render(Canvas c); }

        /** Draws the cached picture at (0, 0), re-rendering it only when key changes. */
        void draw(Canvas c, String key, int w, int h, Render r) {
            if (bmp == null || !key.equals(this.key) || bmp.getWidth() != w || bmp.getHeight() != h) {
                if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                    if (bmp != null) bmp.recycle();
                    bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
                } else bmp.eraseColor(0);
                r.render(new Canvas(bmp));
                this.key = key;
            }
            c.drawBitmap(bmp, 0, 0, P);
        }

        void free() {
            if (bmp != null) bmp.recycle();
            bmp = null;
            key = null;
        }

        private static final Paint P = new Paint(Paint.FILTER_BITMAP_FLAG);
    }

    static void hide(View v) { if (v.getVisibility() != View.INVISIBLE) v.setVisibility(View.INVISIBLE); }

    static void show(View v) { if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE); }
}
