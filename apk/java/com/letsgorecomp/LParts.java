package com.letsgorecomp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Small pieces of the mockup, ported (WParts.java): the status pill and the round badge. */
final class LParts {
    private LParts() {
    }

    /** .pill: a status line with a coloured dot. */
    static final class Pill extends View {
        private final float em;
        private String text = "";
        private int dot = Lui.GREEN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Pill(Context c, float em) {
            super(c);
            this.em = em;
            p.setTypeface(Lui.bold());
            setClickable(true);
            setFocusable(true);
            setDefaultFocusHighlightEnabled(false);
        }

        void set(String text, int dotColor) {
            this.text = text;
            this.dot = dotColor;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            p.setTextSize(em * 0.85f);
            float textW = p.measureText(text);
            float width = textW + em * 0.85f * (1f + 0.5f + 0.7f) + em * 0.4f;
            int max = MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : MeasureSpec.getSize(w);
            setMeasuredDimension(Math.min(max, Math.round(width)), Math.round(em * 0.85f * 2.2f + em * 0.12f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float f = em * 0.85f, b = em * 0.2f, sh = em * 0.12f, h = getHeight() - sh, w = getWidth();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Lui.INK);
            r.set(0, sh, w, h + sh);
            c.drawRoundRect(r, h, h, p);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h, h, p);
            r.inset(b, b);
            p.setColor(Lui.CREAM);
            c.drawRoundRect(r, h, h, p);
            float dotR = f * 0.35f, cx = b + f * 1.0f, cy = h / 2;
            p.setColor(Lui.alpha(dot, 0.27f));
            c.drawCircle(cx, cy, dotR + f * 0.18f, p);
            p.setColor(dot);
            c.drawCircle(cx, cy, dotR, p);
            p.setColor(Lui.INK);
            p.setTextSize(f);
            c.drawText(text, cx + dotR + f * 0.6f, cy - (p.ascent() + p.descent()) / 2, p);
        }
    }

    /** The round channel badge (.brand): outline and a thin shadow. */
    static final class Badge extends View {
        private final Bitmap bmp;
        private final float em;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix m = new Matrix();
        private boolean down;

        Badge(Context c, Bitmap bmp, float em) {
            super(c);
            this.bmp = bmp;
            this.em = em;
            setClickable(true);
            setFocusable(true);
            setDefaultFocusHighlightEnabled(false);
            if (bmp != null) img.setShader(new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Math.round(em * 3.4f);
            setMeasuredDimension(s, s + Math.round(em * 0.12f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = em * 3.4f, b = em * 0.14f, sh = em * 0.12f;
            float dy = down ? -em * 0.1f : 0;
            p.setColor(Lui.INK);
            c.drawCircle(s / 2, s / 2 + sh + dy, s / 2, p);
            c.drawCircle(s / 2, s / 2 + dy, s / 2, p);
            if (bmp != null) {
                float d = s - 2 * b;
                m.setScale(d / bmp.getWidth(), d / bmp.getHeight());
                m.postTranslate(b, b + dy);
                img.getShader().setLocalMatrix(m);
                c.drawCircle(s / 2, s / 2 + dy, d / 2, img);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down = true;
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    down = false;
                    invalidate();
                    performClick();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    down = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean onKeyUp(int code, android.view.KeyEvent e) {
            if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                    || code == android.view.KeyEvent.KEYCODE_BUTTON_A) {
                performClick();
                return true;
            }
            return super.onKeyUp(code, e);
        }
    }
}
