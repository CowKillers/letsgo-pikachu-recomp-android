package com.letsgorecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

/**
 * The Wonder button of the mockup, ported (WButton.java): a pill with a
 * thin dark outline and a thin hard shadow underneath that presses down
 * when touched. A bold title and a small second line. Styles: cream,
 * yellow (the Pikachu primary, in place of Wonder's red), blue, green and
 * ghost; amber carries the glossy stripe. No Lucide icon here: the title
 * itself carries its glyph (e.g. "▶ JOGAR").
 */
final class LButton extends View {
    enum Style { CREAM, AMBER, BLUE, GREEN, GHOST }

    private final Style style;
    private final float fs;          // the button's font size in px
    private String title = "", sub = "";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final android.graphics.Path facePath = new android.graphics.Path();
    private StaticLayout titleLayout, subLayout;
    private int layoutWidth = -1;
    private boolean pressedDown;

    LButton(Context c, Style style, float fontPx) {
        super(c);
        this.style = style;
        this.fs = fontPx;
        setClickable(true);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        titlePaint.setTypeface(Lui.bold());
        subPaint.setTypeface(Lui.medium());
    }

    LButton text(String title, String sub) {
        this.title = title == null ? "" : title;
        this.sub = sub == null ? "" : sub;
        layoutWidth = -1;
        requestLayout();
        invalidate();
        return this;
    }

    private float border() {
        return fs * 0.18f;
    }

    private float lift() {
        return fs * 0.2f;
    }

    private int fg() {
        return style == Style.BLUE || style == Style.GREEN ? 0xFFFFFFFF : Lui.INK;  // AMBER is now bright yellow, so its text is dark ink
    }

    private void build(int width) {
        float padX = fs * 0.88f;
        float avail = width - 2 * border() - 2 * padX;
        titlePaint.setTextSize(fs);
        subPaint.setTextSize(fs * 0.62f);
        int w = Math.max(1, (int) avail);
        titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        subLayout = sub.isEmpty() ? null
                : StaticLayout.Builder.obtain(sub, 0, sub.length(), subPaint, w)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        layoutWidth = width;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int mode = MeasureSpec.getMode(wSpec), size = MeasureSpec.getSize(wSpec);
        float padX = fs * 0.88f;
        float extra = 2 * border() + 2 * padX;
        int width;
        if (mode == MeasureSpec.EXACTLY) {
            width = size;
        } else {
            titlePaint.setTextSize(fs);
            subPaint.setTextSize(fs * 0.62f);
            float t = titlePaint.measureText(title), s = sub.isEmpty() ? 0 : subPaint.measureText(sub);
            width = (int) Math.ceil(Math.max(t, s) + extra + fs * 0.1f);
            if (mode == MeasureSpec.AT_MOST) width = Math.min(width, size);
        }
        build(width);
        float textH = titleLayout.getHeight() + (subLayout != null ? subLayout.getHeight() + fs * 0.25f * 0.62f : 0);
        float padY = fs * 0.75f;
        int height = (int) Math.ceil(textH + 2 * padY + 2 * border() + lift());
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (layoutWidth != getWidth()) build(getWidth());
        boolean down = pressedDown && isEnabled();
        float b = border(), lift = lift();
        float dy = down ? lift * 0.8f : 0;
        float shadow = down ? lift * 0.2f : lift;
        float h = getHeight() - lift, w = getWidth();
        float radius = Math.min(fs * 1.2f, h / 2f);
        canvas.save();
        if (!isEnabled()) canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 166);
        // hard shadow: only the sliver below the face (ghost buttons are translucent)
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        r.set(0, dy, w, dy + h);
        facePath.reset();
        facePath.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
        canvas.save();
        canvas.clipOutPath(facePath);
        paint.setColor(Lui.INK);
        RectF sr = new RectF(0, dy + shadow, w, dy + shadow + h);
        canvas.drawRoundRect(sr, radius, radius, paint);
        canvas.restore();
        // face, then the outline on top
        paint.setColor(style == Style.AMBER ? Lui.AMBER : style == Style.BLUE ? Lui.BLUE
                : style == Style.GREEN ? Lui.GREEN
                : style == Style.GHOST ? Lui.alpha(Lui.CREAM, 0.8f) : Lui.CREAM);
        canvas.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(b);
        paint.setColor(Lui.INK);
        r.inset(b / 2, b / 2);
        canvas.drawRoundRect(r, radius - b / 2, radius - b / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        r.inset(b / 2, b / 2);
        if (style == Style.AMBER) {  // glossy stripe
            paint.setColor(0x33FFFFFF);
            float top = r.top, left = r.left + fs * 0.32f, right = r.right - fs * 0.32f;
            RectF st = new RectF(left, top, right, top + r.height() * 0.38f);
            canvas.drawRoundRect(st, fs * 0.64f, fs * 0.64f, paint);
        }
        // content
        int fg = fg();
        float padX = fs * 0.88f + b;
        float x = padX;
        float cy = dy + h / 2f;
        titlePaint.setColor(fg);
        titlePaint.clearShadowLayer();
        float textH = titleLayout.getHeight() + (subLayout != null ? subLayout.getHeight() + fs * 0.155f : 0);
        float ty = cy - textH / 2f;
        canvas.save();
        canvas.translate(x, ty);
        titleLayout.draw(canvas);
        canvas.restore();
        if (subLayout != null) {
            subPaint.setColor(Lui.alpha(fg, 0.75f));
            canvas.save();
            canvas.translate(x, ty + titleLayout.getHeight() + fs * 0.155f);
            subLayout.draw(canvas);
            canvas.restore();
        }
        if (!isEnabled()) canvas.restore();
        canvas.restore();
        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(fs * 0.16f);
            paint.setColor(Lui.YELLOW);
            r.set(-b * 0.2f, dy - b * 0.2f, w + b * 0.2f, dy + h + shadow + b * 0.2f);
            canvas.drawRoundRect(r, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedDown = true;
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                boolean inside = e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight();
                if (inside != pressedDown) {
                    pressedDown = inside;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                boolean was = pressedDown;
                pressedDown = false;
                invalidate();
                if (was) performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                pressedDown = false;
                invalidate();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent ev) {
        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                || code == android.view.KeyEvent.KEYCODE_BUTTON_A) {
            pressedDown = true;
            invalidate();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, android.view.KeyEvent ev) {
        if (pressedDown && (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                || code == android.view.KeyEvent.KEYCODE_BUTTON_A)) {
            pressedDown = false;
            invalidate();
            performClick();
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    @Override
    protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        invalidate();
    }
}
