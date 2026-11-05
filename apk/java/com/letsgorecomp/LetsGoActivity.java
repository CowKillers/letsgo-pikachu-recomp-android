package com.letsgorecomp;

import android.os.Bundle;
import org.libsdl.app.SDLActivity;

// Entry point of the Android port: SDLActivity with our native libraries, plus an
// early native setup (before SDL starts its thread in super.onCreate) telling the
// runtime where its files live (LARECOMP_DATA_ROOT) and where the prebuilt game
// library is (LARECOMP_GAME_LIB). See runtime/platform/android.c.
public class LetsGoActivity extends SDLActivity {
    /** The master diagnostic switch: on only for the exact value "1" (empty, unset, "0", "true"... = off). */
    static boolean diagEnabled(String property) {
        return "1".equals(property == null ? null : property.trim());
    }

    private static String systemProperty(String key) {
        try {
            return String.valueOf(Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, key, ""));
        } catch (Throwable e) {
            return "";
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "SDL3",
            "lasrecomp",
        };
    }

    private void applyFullscreen() {
        // Simplified: rely on theme (@style/Fullscreen) + SDL_SetWindowFullscreen.
        // SDLActivity handles COMMAND_CHANGE_WINDOW_STYLE via WindowInsetsController.
        // Manual calls here were conflicting with SDL's handler.
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        s_instance = this;
        // Force landscape: locked (not sensor) to avoid SDL resize confusion
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // Resolution scale (user-selectable, emulator-style): set before native loads
        try {
            String scale = getSharedPreferences("letsgo", MODE_PRIVATE).getString("resolution_scale", "0.6667");
            android.system.Os.setenv("LARECOMP_SCALE", scale, true);
        } catch (Exception e) {
        }
        // Sondas de diagnóstico: DESLIGADAS por padrão. Um único interruptor as liga
        // todas: `adb shell setprop debug.larecomp.diag 1`, e só vale para o próximo
        // lançamento do jogo. O runtime segue o mesmo LARECOMP_DIAG (runtime/diag.h).
        boolean diag = diagEnabled(systemProperty("debug.larecomp.diag"));
        try {
            if (diag) android.system.Os.setenv("LARECOMP_DIAG", "1", true);
            else android.system.Os.unsetenv("LARECOMP_DIAG");
        } catch (Exception e) {
        }
        // Custom GPU driver (Turnip via adrenotools): set the env before the native
        // runtime loads, so load_gipa() in device.c picks it up. Null = system driver.
        String launchSo = null;
        try {
            GpuDrivers.Driver drv = GpuDrivers.selected(this);
            if (drv != null) {
                java.io.File so = new java.io.File(
                        new java.io.File(GpuDrivers.driversDir(this), drv.slug), drv.libraryName);
                if (so.isFile()) {
                    android.system.Os.setenv("LARECOMP_GPU_DRIVER", so.getAbsolutePath(), true);
                    android.system.Os.setenv("LARECOMP_NATIVE_LIB_DIR",
                            getApplicationInfo().nativeLibraryDir, true);
                    launchSo = so.getAbsolutePath();
                } else {
                }
            } else {
            }
        } catch (Exception e) {
        }
        // Driver trial (fork): a driver that has not started here yet is on trial until the game shows its first
        // frames; the launcher rolls it back if it does not.
        GpuDrivers.beginLaunch(this);
        GpuDrivers.recordLaunch(this, launchSo);
        System.loadLibrary("lasrecomp");
        // The user-picked game folder (via the launcher), or the app's files
        // dir as a fallback. The native runtime reads LARECOMP_DATA_ROOT.
        String gameDir = LetsGoLauncherActivity.gameDir(this);
        if (gameDir == null) gameDir = getFilesDir().getAbsolutePath();
        // Copy bundled DejaVuSans.ttf to the game folder for the system font.
        // Diagnosed copy: logs success/failure, validates size, cleans up partial files.
        try {
            java.io.File fontDir = new java.io.File(gameDir, "fonts");
            fontDir.mkdirs();
            java.io.File fontFile = new java.io.File(fontDir, "DejaVuSans.ttf");
            if (fontFile.exists() && fontFile.length() > 100000) {
            } else {
                // Remove partial/corrupt file before retrying.
                if (fontFile.exists()) fontFile.delete();
                java.io.InputStream in = null;
                java.io.OutputStream out = null;
                try {
                    in = getAssets().open("fonts/DejaVuSans.ttf");
                    out = new java.io.FileOutputStream(fontFile);
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                    out.flush();
                    if (total < 100000) {
                        fontFile.delete();
                    }
                } finally {
                    if (in != null) try { in.close(); } catch (Exception ignored) {}
                    if (out != null) try { out.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
        }
        // Copy bundled pre-compiled SPIR-V shaders to the shader cache dir
        // (<gameDir>/cache/spirv/), so first runs don't stutter compiling.
        // Only copies files that are not already cached (runtime may have newer ones).
        try {
            String[] spirvAssets = getAssets().list("spirv");
            if (spirvAssets != null && spirvAssets.length > 0) {
                java.io.File cacheDir = new java.io.File(gameDir, "cache/spirv");
                cacheDir.mkdirs();
                int copied = 0, skipped = 0;
                for (String name : spirvAssets) {
                    java.io.File dst = new java.io.File(cacheDir, name);
                    if (dst.exists() && dst.length() > 0) { skipped++; continue; }
                    java.io.InputStream in = null;
                    java.io.OutputStream out = null;
                    try {
                        in = getAssets().open("spirv/" + name);
                        out = new java.io.FileOutputStream(dst);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.flush();
                        copied++;
                    } finally {
                        if (in != null) try { in.close(); } catch (Exception ignored) {}
                        if (out != null) try { out.close(); } catch (Exception ignored) {}
                    }
                }
            } else {
            }
        } catch (Exception e) {
        }
        nativeSetup(gameDir, getApplicationInfo().nativeLibraryDir);
        super.onCreate(savedInstanceState);
        running = true;
        // SurfaceView can replace the native producer without changing its Java Surface and
        // without a Vulkan error. Observe lifecycle changes even while presentation is READY.
        mSurface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            public void surfaceCreated(android.view.SurfaceHolder h) { nativeResumeDisplay(); }
            public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int height) {
                nativeResumeDisplay();
            }
            public void surfaceDestroyed(android.view.SurfaceHolder h) {
                nativeDisplaySurfaceGone();
                if (!resumed) setBackground(true);  // (normally onPause came first; never stop a foreground game here)
            }
        });
        // Apply fullscreen AFTER super.onCreate (SDLActivity resets UI visibility)
        applyFullscreen();
        // Touch controls - toggleable in launcher settings. Default: the minimal layout in the black bars
        // (TouchControls); "Layout completo" in the launcher brings back the old full one (GamepadOverlay).
        android.content.SharedPreferences prefs = getSharedPreferences("letsgo", MODE_PRIVATE);
        boolean touchEnabled = prefs.getBoolean("touch", true);
        // Display guard (black-screen guard), independent of the Vulkan SurfaceView.
        guard = new DisplayGuard(this);
        addContentView(guard, new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        if (touchEnabled) {
            boolean classic = "classic".equals(prefs.getString("touch_layout", "minimal"));
            addContentView(classic ? new GamepadOverlay(this) : new TouchControls(this),
                new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** The game activity is alive (the launcher's trial verdict waits while this is true). */
    static boolean running;
    /** The display guard (the black-screen guard). */
    private DisplayGuard guard;
    /** The phone's motion sensor aims the Poke Balls (launcher setting, off by default: GyroAim.java). */
    private GyroAim gyro;

    @Override
    protected void onResume() {
        super.onResume();
        // Fullscreen handled by theme + SDL_SetWindowFullscreen (like Wonder).
        // No manual intervention to avoid conflicts with SDLActivity.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // The game computes again (it waited at its present while in the background: runtime/lifecycle.c).
        setBackground(false);
        // The phone's motion sensor aims the Poke Balls (launcher setting, off by default: GyroAim.java).
        if (gyro == null && GyroAim.enabled(this)) gyro = new GyroAim(this);
        if (gyro != null) gyro.resume();
    }

    /** The game stops at its next present while the activity is in the background (home, another app, screen off):
     *  it used to keep computing until Android killed it for excessive CPU. A surface lost in the foreground (fold,
     *  rotation) does not stop it: the display guard's rebuild needs the game's presents (surfaceDestroyed above). */
    private boolean resumed;
    private void setBackground(boolean background) {
        resumed = !background;
        try { nativeSetBackground(background); }
        catch (UnsatisfiedLinkError e) { /* the runtime was refused (rejectBuild): nothing runs */ }
    }

    @Override
    protected void onPause() {
        if (gyro != null) gyro.pause();
        setBackground(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        running = false;
        GpuDrivers.gameClosed(this);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Fullscreen handled by theme + SDL. No manual re-apply.
    }

    // Force landscape: SDL's native code calls setOrientationBis() which overrides
    // our setRequestedOrientation(). By overriding it here, we force landscape always.
    // (v0010) Was SCREEN_ORIENTATION_SENSOR_LANDSCAPE: that makes Android turn
    // the rotation sensor on every time the game starts, even with auto-rotate
    // off in the system settings. LANDSCAPE (same as onCreate/onResume) respects
    // the user's system setting and does not force the sensor on.
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    public static native void nativeSetup(String filesDir, String libDir);
    public static native void nativeSetButton(int button, boolean pressed);
    public static native void nativeSetStick(int stick, int x, int y);
    public static native double nativeGetFps();
    public static native double nativeGetFrameMs();
    /** "Mostrar FPS" (launcher: show_fps in larecomp.ini) — drives the TouchControls FPS counter. */
    public static native boolean nativeGetShowFps();
    /** True when the engine runs but nothing reaches the screen (the black-screen guard). */
    public static native boolean nativeDisplayStalled();
    /** How long the display has been silent, in ms (0 when it is fine). */
    public static native int nativeDisplayStalledMs();
    /** The player asked to rebuild the surface and show the picture again. */
    public static native void nativeResumeDisplay();
    private static native void nativeDisplaySurfaceGone();
    /** The activity left (true) or came back to (false) the foreground: runtime/lifecycle.c. */
    static native void nativeSetBackground(boolean background);

    // ---- native name entry dialog (swkbd) -------------------------------------------------
    // The game's ImGui name panel is drawn into the Vulkan swapchain image, which on a
    // phone held sideways is portrait with a 90-degree surface transform: the game image
    // is presented rotated back to landscape, but ImGui is not, so the panel comes out
    // vertical/rotated. A Java dialog bypasses Vulkan entirely: Android draws it in the
    // activity's (landscape) orientation, horizontal, with the system keyboard, and the
    // typed name stays visible in its field while typing. Called from the native game
    // thread via JNI (runtime/platform/android.c): showNameDialog posts to the UI thread
    // and returns at once; the native thread polls nameDialogState() until 1 (OK) or 2
    // (cancelled), then reads nameDialogText(). Dismissing (back / outside) cancels.
    private static LetsGoActivity s_instance;
    private static volatile int s_nameState; // 0 pending, 1 OK, 2 cancelled
    private static volatile String s_nameText = "";
    private static android.app.AlertDialog s_nameDialog;

    static void showNameDialog(final String initial, final int maxLen) {
        s_nameState = 0;
        s_nameText = "";
        final LetsGoActivity act = s_instance;
        android.util.Log.i("LetsGo", "[swkbd] showNameDialog initial=" + initial + " max=" + maxLen + " act=" + (act != null));
        if (act == null) { s_nameState = 2; return; }
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (s_nameDialog != null) { try { s_nameDialog.dismiss(); } catch (Exception ignored) {} }
                    android.widget.LinearLayout root = new android.widget.LinearLayout(act);
                    root.setOrientation(android.widget.LinearLayout.VERTICAL);
                    int pad = Math.round(24 * act.getResources().getDisplayMetrics().density);
                    root.setPadding(pad, pad, pad, pad);
                    // Pikachu styling: cream card, yellow confirm, brown text/border.
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(0xFFFFFBEA);
                    bg.setCornerRadius(pad * 0.75f);
                    root.setBackground(bg);
                    android.widget.TextView title = new android.widget.TextView(act);
                    title.setText("Digite o nome");
                    title.setTextColor(0xFF6B3F12);
                    title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    title.setTextSize(22);
                    root.addView(title);
                    android.widget.TextView hint = new android.widget.TextView(act);
                    hint.setText("Como você quer ser chamado(a) no jogo?");
                    hint.setTextColor(0xFF6B3F12);
                    hint.setTextSize(14);
                    android.widget.LinearLayout.LayoutParams hintLp =
                            new android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    hintLp.topMargin = pad / 3;
                    root.addView(hint, hintLp);
                    final android.widget.EditText field = new android.widget.EditText(act);
                    field.setText(initial == null ? "" : initial);
                    field.setSelection(field.getText().length());
                    field.setTextColor(0xFF1A1A1A);
                    field.setTextSize(24);
                    field.setSingleLine(true);
                    field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                    if (maxLen > 0) field.setFilters(new android.text.InputFilter[] {
                            new android.text.InputFilter.LengthFilter(maxLen) });
                    android.graphics.drawable.GradientDrawable fieldBg =
                            new android.graphics.drawable.GradientDrawable();
                    fieldBg.setColor(0xFFFFFFFF);
                    fieldBg.setCornerRadius(pad / 2f);
                    fieldBg.setStroke(Math.max(2, pad / 12), 0xFFE3C400);
                    field.setBackground(fieldBg);
                    android.widget.LinearLayout.LayoutParams fieldLp =
                            new android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    fieldLp.topMargin = pad / 2;
                    fieldLp.bottomMargin = pad / 2;
                    root.addView(field, fieldLp);
                    final android.widget.TextView count = new android.widget.TextView(act);
                    count.setTextColor(0xFF6B3F12);
                    count.setTextSize(13);
                    root.addView(count);
                    field.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                        @Override public void afterTextChanged(android.text.Editable s) {
                            count.setText(s.length() + (maxLen > 0 ? " / " + maxLen : ""));
                        }
                    });
                    count.setText(field.getText().length() + (maxLen > 0 ? " / " + maxLen : ""));
                    android.widget.LinearLayout row = new android.widget.LinearLayout(act);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    android.widget.LinearLayout.LayoutParams rowLp =
                            new android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.topMargin = pad / 2;
                    root.addView(row, rowLp);
                    android.widget.Button cancel = new android.widget.Button(act);
                    cancel.setText("Cancelar");
                    cancel.setAllCaps(false);
                    cancel.setTextColor(0xFF6B3F12);
                    cancel.setBackgroundColor(0xFFFFF3D6);
                    android.widget.Button ok = new android.widget.Button(act);
                    ok.setText("Confirmar");
                    ok.setAllCaps(false);
                    ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    ok.setTextColor(0xFF1A1A1A);
                    ok.setBackgroundColor(0xFFFFD500);
                    android.widget.LinearLayout.LayoutParams btnLp =
                            new android.widget.LinearLayout.LayoutParams(0,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    row.addView(cancel, btnLp);
                    row.addView(ok, btnLp);
                    final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(act)
                            .setView(root).setCancelable(true).create();
                    dlg.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                        @Override public void onCancel(android.content.DialogInterface d) {
                            s_nameState = 2; s_nameText = "";
                        }
                    });
                    dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        @Override public void onDismiss(android.content.DialogInterface d) {
                            if (s_nameState == 0) { s_nameState = 2; s_nameText = ""; }
                            if (s_nameDialog == dlg) s_nameDialog = null;
                        }
                    });
                    cancel.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) { dlg.dismiss(); }
                    });
                    ok.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            String t = field.getText().toString().trim();
                            if (t.isEmpty()) { dlg.dismiss(); return; }
                            s_nameText = t; s_nameState = 1; dlg.dismiss();
                        }
                    });
                    field.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
                        @Override public boolean onEditorAction(android.widget.TextView v, int actionId,
                                android.view.KeyEvent e) {
                            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                                ok.performClick(); return true;
                            }
                            return false;
                        }
                    });
                    s_nameDialog = dlg;
                    dlg.show();
                    // System keyboard up, over the dialog (landscape, horizontal).
                    field.requestFocus();
                    android.view.Window w = dlg.getWindow();
                    if (w != null) w.setSoftInputMode(
                            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) act.getSystemService(
                                    android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                } catch (Exception e) {
                    s_nameState = 2;
                }
            }
        });
    }

    static int nameDialogState() { return s_nameState; }

    static String nameDialogText() { return s_nameText == null ? "" : s_nameText; }

    static void dismissNameDialog() {
        final android.app.AlertDialog dlg = s_nameDialog;
        if (dlg == null) return;
        final LetsGoActivity act = s_instance;
        if (act == null) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() { try { dlg.dismiss(); } catch (Exception ignored) {} }
        });
    }

}
