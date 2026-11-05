package com.letsgorecomp;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowInsetsController;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/**
 * The launcher's look, ported from the Mario Wonder Android launcher
 * (SuperMarioWonderRecomp/android, Wui.java) and rethemed for Pikachu:
 * the same cream background, ink text, pill buttons with a hard shadow
 * and the em unit (1em = 1.5% of the screen width, landscape-based, so
 * the whole screen scales like the phone). No Fredoka font here (the
 * system fonts stand in) and no Lucide icons (titles carry their glyph).
 */
final class Lui {
    static final int INK = Color.parseColor("#2b2d33");
    static final int INK2 = Color.parseColor("#44464e");
    static final int CREAM = Color.parseColor("#fffbea");
    static final int CREAM2 = Color.parseColor("#f3ecd2");
    static final int AMBER = Color.parseColor("#ffd500");  // Pikachu yellow (was Eevee orange-amber)
    static final int AMBER_TEXT = Color.parseColor("#1a1206");
    static final int BLUE = Color.parseColor("#2aa8ff");
    static final int GREEN = Color.parseColor("#3dbb4a");
    static final int YELLOW = Color.parseColor("#ffd23a");
    static final int RED = Color.parseColor("#e8262b");
    static final int MUTED = Color.parseColor("#6d6f78");

    /** 1% of the screen width in px, landscape. */
    static float u;

    private Lui() {
    }

    static void init(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        float w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        u = Math.min(w, h * 19.5f / 9f) / 100f;
    }

    /** em of the mockup's screens (font-size 1.5cqw) in px. */
    static float em(float k) {
        return k * 1.5f * u;
    }

    static Typeface bold() {
        return Typeface.DEFAULT_BOLD;
    }

    static Typeface medium() {
        return Typeface.DEFAULT;
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    static Bitmap asset(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    static void hideSystemBars(Activity a) {
        Window w = a.getWindow();
        if (w == null) return;
        // v0002: draw into the display cutout area (short edges) — without this,
        // the launcher window is inset in landscape and leaves a black strip on
        // the camera side (the game activity already has shortEdges in its theme;
        // the launcher activity did not, it inherited the app theme).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(attrs);
        }
        w.setDecorFitsSystemWindows(false);
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
