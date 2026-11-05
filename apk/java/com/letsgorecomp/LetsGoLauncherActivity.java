package com.letsgorecomp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.io.File;

// Official launch launcher: the exact style and colors of the reference
// mockup (yellow/cream/brown, Pikachu field art, pill buttons, top icon bar):
// Jogar, Importar jogo (its own button, progress bar kept while importing),
// Configurações (everything organized), Mods, Controles, Sobre (credits +
// channel). The SDL game runs in LetsGoActivity; if it crashes, this
// launcher survives (separate activity). Background art: apk/assets/
// pikachu_field.jpg (the official Eevee keyart, logo cropped out, title
// redrawn in code so it never doubles on any screen size).
public class LetsGoLauncherActivity extends Activity {
    private static final int REQ_GAME_DIR = 1001;
    private static final int REQ_IMPORT_DIR = 1002;
    private static final int REQ_IMPORT_DIR_FOLDER = 1003;
    private static final int REQ_IMPORT_DRIVER = 1004;
    private static final int REQ_MOD_FILE = 1005;
    private static final int REQ_IMPORT_DATAPACK = 1006;
    private static final String PREFS = "letsgo";
    private static final String KEY_GAME_DIR = "game_dir";
    private static final String CHANNEL = "https://youtube.com/@emuucheck?feature=shared";

    // Palette (the mockup's colors).
    private static final int ORANGE = 0xFFFFD500;  // Pikachu yellow (was Eevee orange)
    private static final int ORANGE_DARK = 0xFFC9A400;  // dark Pikachu gold (was dark orange)
    private static final int CREAM = 0xFFFFF3D6;
    private static final int CREAM_PANEL = 0xFFFFFBEA;
    private static final int BROWN = 0xFF6B3F12;
    private static final int BROWN_DARK = 0xFF4A2A0A;
    private static final int YELLOW = 0xFFFFD500;
    private static final int BLUE = 0xFF2B5CFF;
    private static final int GREEN = 0xFF2E8B3E;
    private static final int RED = 0xFFD23B2E;
    private static final int AMBER = 0xFFC9A000;  // dark Pikachu gold for status text (was amber)

    private FrameLayout root;
    private View mainView;
    private View settingsView;
    private View settingsImageView, settingsGameView, settingsDriverView, settingsImportView;
    private CheckBox smaaBox, fps60Box, wildBox, showFpsBox;
    private android.view.View.OnClickListener saveIniClick = new android.view.View.OnClickListener() {
        @Override public void onClick(View v) { saveIniFromBoxes(); }
    };
    private View modsView;
    private View controlsView;
    private View aboutView;
    private View currentScreen;
    private android.widget.Button playBtn;
    private android.widget.Button importBtn;
    private TextView statusText;
    // v0108: vertical-fit override for em() — set while buildMain() builds the
    // menu column so all em-based sizes shrink together on short screens.
    private float emOverride = -1f;
    private TextView drvStatus;
    private Runnable refreshDrivers;
    /** A driver check or download is running (DriverCatalog, fork). */
    private boolean driverBusy;
    private GpuInfo gpu;
    /** A mod install is running (ModUnpack, fork). */
    private boolean modBusy;
    private android.os.Handler importPoller;
    private Runnable importPollTask;
    // Import progress overlay (kept on screen while the ROM imports).
    private View importOverlay;
    private ProgressBar importBar;
    private TextView importPct;
    private TextView importPhase;

    /** The user-picked game folder, or null if not set. */
    public static String gameDir(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                  .getString(KEY_GAME_DIR, null);
    }

    /** True only when a COMPLETE import exists: the .import_ok marker (written
     *  by LetsGoImportJob on 100%) or a generated romfs.bin. A bare romfs/
     *  folder is NOT enough — an interrupted import also leaves a folder behind. */
    public static boolean gameDataReady(android.content.Context ctx) {
        File gameLib = new File(ctx.getApplicationInfo().nativeLibraryDir, "liblasgame.so");
        String dir = gameDir(ctx);
        File base = dir != null ? new File(dir) : ctx.getFilesDir();
        File dataBin = new File(base, "extracted/romfs.bin");
        File importOk = new File(base, "extracted/.import_ok");
        File exefsMain = new File(base, "extracted/exefs/main");
        return gameLib.exists() && exefsMain.exists()
                && (dataBin.exists() || importOk.exists());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Request full storage access (needed for direct fopen() of game files)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent si = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(si);
                } catch (Exception ignored) {}
            }
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(CREAM_PANEL);

        // ---- background: Eevee field art, full-bleed ----
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(
                    getAssets().open("pikachu_field.jpg"));
            ImageView bg = new ImageView(this);
            bg.setImageBitmap(bmp);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        } catch (Exception ignored) {}

        // Pikachu: NO cream wash behind the left column (user: panel out,
        // buttons directly over the art). The field art is full-bleed.
        // (Sub-screens keep their cream card: long text over art is unreadable.)

        mainView = buildMain();
        settingsImageView = buildSettingsImage();
        settingsGameView = buildSettingsGame();
        settingsDriverView = buildSettingsDriver();
        settingsImportView = buildSettingsImport();
        settingsView = buildSettings();
        modsView = buildMods();
        controlsView = buildControls();
        aboutView = buildAbout();
        root.addView(mainView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(settingsImageView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(settingsGameView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(settingsDriverView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(settingsImportView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(controlsView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(aboutView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(modsView, new FrameLayout.LayoutParams(-1, -1));
        buildImportOverlay();

        setContentView(root);
        Lui.hideSystemBars(this);
        showScreen(mainView);
        refreshState();
        // (v0014) If no game folder is saved, look for already-imported data
        // on the device instead of forcing the Android folder picker.
        maybeDetectGameData(false);
        LetsGoImportJob job = LetsGoImportJob.current();
        if (job != null && !job.finished) startImportPolling();
    }

    private void showScreen(View v) {
        mainView.setVisibility(v == mainView ? View.VISIBLE : View.GONE);
        settingsView.setVisibility(v == settingsView ? View.VISIBLE : View.GONE);
        settingsImageView.setVisibility(v == settingsImageView ? View.VISIBLE : View.GONE);
        settingsGameView.setVisibility(v == settingsGameView ? View.VISIBLE : View.GONE);
        settingsDriverView.setVisibility(v == settingsDriverView ? View.VISIBLE : View.GONE);
        settingsImportView.setVisibility(v == settingsImportView ? View.VISIBLE : View.GONE);
        modsView.setVisibility(v == modsView ? View.VISIBLE : View.GONE);
        controlsView.setVisibility(v == controlsView ? View.VISIBLE : View.GONE);
        aboutView.setVisibility(v == aboutView ? View.VISIBLE : View.GONE);
        currentScreen = v;
        if (v == modsView) rebuildMods();
        if (v == settingsDriverView && refreshDrivers != null) refreshDrivers.run();
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == settingsImageView || currentScreen == settingsGameView
                || currentScreen == settingsDriverView || currentScreen == settingsImportView) {
            showScreen(settingsView);
            return;
        }
        if (currentScreen != null && currentScreen != mainView) {
            showScreen(mainView);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Lui.hideSystemBars(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Verdict on a driver that was on trial: the game got to its first frames with it, or it did not and the
        // launcher rolls it back (the driver that last worked here, or none: said so, never silently).
        GpuDrivers.TrialOutcome t = GpuDrivers.checkTrial(this, LetsGoActivity.running);
        if (t != null && t.ok) {
            android.widget.Toast.makeText(this, "Driver " + t.driver.name + " funcionou neste aparelho.",
                    android.widget.Toast.LENGTH_LONG).show();
        } else if (t != null) {
            showTrialFailure(t);
        }
        refreshState();
        autoExportLog();
    }

    /** (v0102) O log se exporta sozinho para Downloads/letsgo-log.txt toda vez
     *  que o launcher volta ao primeiro plano — inclusive após um crash do
     *  jogo, quando o buffer do logcat ainda guarda as linhas da queda.
     *  Silencioso: se a escrita falhar, nada muda para o usuário. */
    private void autoExportLog() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-d", "-t", "3000", "-v", "threadtime",
                                     "lasrecomp:V", "nximport:V", "LetsGo:V", "AndroidRuntime:E", "DEBUG:I", "libc:F", "*:S"});
                    java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    r.close();
                    if (sb.length() == 0) return;
                    java.io.File dl = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dl, "letsgo-log.txt"));
                    w.write(sb.toString());
                    w.close();
                } catch (Exception ignored) { }
            }
        }, "log-autoexport").start();
    }

    /** Title text with an outline (Pokémon yellow on blue, Let's Go black on white). */
    private static class OutlineTextView extends TextView {
        private final int fillColor;
        private final int strokeColor;
        private final float strokeWidth;
        OutlineTextView(android.content.Context ctx, int fill, int stroke, float strokePx) {
            super(ctx);
            fillColor = fill;
            strokeColor = stroke;
            strokeWidth = strokePx;
            setTextColor(fill);
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            android.text.TextPaint p = getPaint();
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(strokeWidth);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            setTextColor(strokeColor);
            super.onDraw(canvas);
            p.setStyle(android.graphics.Paint.Style.FILL);
            setTextColor(fillColor);
            super.onDraw(canvas);
        }
    }

    /** A Pokéball icon drawn in code (red top, white bottom, band, center button). */
    private static class PokeBallView extends View {
        private final android.graphics.Paint red = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint white = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint dark = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;
        PokeBallView(android.content.Context ctx, int sizePx) {
            super(ctx);
            this.sizePx = sizePx;
            red.setColor(0xFFE8262B);
            white.setColor(0xFFFFFFFF);
            dark.setColor(0xFF2B2D33);
            setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        }
        // (v0007) Without this, a WRAP_CONTENT PokeBallView measures to the
        // parent's full size: the corner watermark became a screen-sized ball
        // whose middle band was the translucent stripe across the whole menu.
        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(sizePx, sizePx);
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), r = w / 2f;
            c.save();
            c.clipRect(0, 0, w, h / 2f);
            c.drawCircle(r, r, r, red);
            c.restore();
            c.save();
            c.clipRect(0, h / 2f, w, h);
            c.drawCircle(r, r, r, white);
            c.restore();
            dark.setStyle(android.graphics.Paint.Style.STROKE);
            dark.setStrokeWidth(Math.max(2f, w * 0.04f));
            c.drawCircle(r, r, r - dark.getStrokeWidth() / 2f, dark);
            dark.setStyle(android.graphics.Paint.Style.FILL);
            c.drawRect(0, h / 2f - w * 0.06f, w, h / 2f + w * 0.06f, dark);
            float br = w * 0.16f;
            c.drawCircle(r, r, br, white);
            dark.setStyle(android.graphics.Paint.Style.STROKE);
            c.drawCircle(r, r, br, dark);
            dark.setStyle(android.graphics.Paint.Style.FILL);
            c.drawCircle(r, r, br * 0.55f, white);
        }
    }

    private float em() {
        if (emOverride > 0) return emOverride;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels) * 0.015f;
    }

    /** One main-menu pill button: icon + title, yellow for Jogar, cream for the rest. */
    private android.widget.Button menuButton(View icon, String title, boolean isPlay) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(title);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        // v0107: auto-shrink text so a label never clips on any resolution:
        // max = the old size (unchanged look where it fits), min = 0.7em,
        // 1px steps; if even the min size overflows, ellipsize instead of
        // hard-clipping. (API 26+; minSdk is 28.)
        b.setAutoSizeTextTypeUniformWithConfiguration(
                Math.round(em() * 0.7f), Math.round(em() * 1.05f), 1,
                TypedValue.COMPLEX_UNIT_PX);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setAllCaps(false);
        GradientDrawable d = new GradientDrawable();
        if (isPlay) {
            // Solid Pikachu yellow (GradientDrawable.setColors needs API 29+; minSdk is 28).
            d.setColor(YELLOW);
            b.setTextColor(0xFF1A1A1A);
        } else {
            // Pikachu: cream fill (user correction 06:42 — Jogar stays yellow,
            // the buttons below stay cream; only the panel behind them is out)
            d.setColor(CREAM);
            d.setStroke(dp(1), 0xFFE3C400);
            b.setTextColor(BROWN);
        }
        d.setCornerRadius(em() * 1.4f);
        b.setBackground(d);
        int pad = Math.round(em() * 0.7f);
        b.setPadding(pad, Math.round(em() * 0.55f), pad, Math.round(em() * 0.55f));
        return b;
    }

    /** A menu row: icon view + pill button, so glyph icons and the drawn ball share a row. */
    private LinearLayout menuRow(View icon, android.widget.Button btn) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Math.round(em() * 1.7f), Math.round(em() * 1.7f));
        ip.rightMargin = Math.round(em() * 0.5f);
        row.addView(icon, ip);
        row.addView(btn, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private TextView glyphIcon(String glyph, float sizeEm) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * sizeEm);
        t.setTextColor(BROWN);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** A small visible pill for the top-right bar (v0012): cream, gold border, brown text. */
    private TextView topPill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.85f);
        t.setTextColor(BROWN);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(CREAM);
        d.setCornerRadius(em());
        d.setStroke(dp(1), 0xFFE3C400);
        t.setBackground(d);
        int hp = Math.round(em() * 0.6f), vp = Math.round(em() * 0.3f);
        t.setPadding(hp, vp, hp, vp);
        return t;
    }

    /** The main menu: title, status, Jogar / Importar / Configurações / Mods / Controles. Static: no scroll. */
    private View buildMain() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        float em = em();

        // v0108: vertical fit. The static menu (no scroll, by request) is
        // taller than a landscape window once the system bars eat height, so
        // the last button (Controles) falls off the bottom edge — that is what
        // the photo shows; it is vertical overflow, not text clipping (v0107
        // fixed the horizontal axis). Estimate the column height from the
        // same multipliers the layout uses (line height ~1.2x text size);
        // if it exceeds the window height minus the system bars, shrink every
        // em-based size by just enough. Portrait (where it already fits with
        // 2x margin) gets vs = 1 exactly, so it stays pixel-identical.
        int statusH = dp(24), navH = dp(48);
        int sid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sid > 0) statusH = getResources().getDimensionPixelSize(sid);
        int nid = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (nid > 0) navH = getResources().getDimensionPixelSize(nid);
        float availH = dm.heightPixels - statusH - navH - dp(8);
        float needEm = (3.12f + 2.04f + 2.52f)   // titles (line height)
                + (0.96f + 0.6f + 0.5f)          // pill: text + padding + topMargin
                + (1.02f + 0.5f)                 // status: text + topMargin
                + 5 * (2.36f + 0.7f);            // rows: button vs icon + topMargin
        float needH = needEm * em + Math.round(screenW * 0.02f) + dp(24);
        float emOrig = em;
        if (needH > availH && availH > 0) {
            float vs = Math.max(0.6f, availH / needH);
            if (vs < 1f) { emOverride = em * vs; em = em * vs; }
        }

        // Static main menu: no ScrollView — the column sits directly in the
        // FrameLayout and never moves. (The other screens keep their scroll.)
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int colW = Math.round(screenW * 0.34f);
        if (colW > dm.widthPixels - dp(40)) colW = dm.widthPixels - dp(40);
        // (margins are applied by the wrapper below)
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Math.round(screenW * 0.035f), Math.round(screenW * 0.02f), 0, dp(24));
        wrap.addView(col, new LinearLayout.LayoutParams(colW, -2));

        OutlineTextView pokemon = new OutlineTextView(this, YELLOW, BLUE, em * 0.28f);
        pokemon.setText("Pokémon");
        pokemon.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pokemon.setTextSize(TypedValue.COMPLEX_UNIT_PX, em * 2.6f);
        col.addView(pokemon, lp(-1, -2));

        OutlineTextView letsgo = new OutlineTextView(this, 0xFF1A1A1A, 0xFFFFFFFF, em * 0.16f);
        letsgo.setText("Let's Go");
        letsgo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        letsgo.setTextSize(TypedValue.COMPLEX_UNIT_PX, em * 1.7f);
        col.addView(letsgo, lp(-1, -2));

        TextView eevee = new TextView(this);
        eevee.setText("Pikachu!");
        eevee.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        eevee.setTextSize(TypedValue.COMPLEX_UNIT_PX, em * 2.1f);
        eevee.getPaint().setShader(new LinearGradient(0, 0, 0, em * 2.1f,
                new int[] {0xFFFFD500, ORANGE_DARK}, null, Shader.TileMode.CLAMP));
        eevee.setShadowLayer(em * 0.15f, 0, 0, 0xFF1A1A1A);
        col.addView(eevee, lp(-1, -2));

        // Brown pill: Android native port.
        TextView pill = new TextView(this);
        pill.setText("◉  Android native port  ◉");
        pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, em * 0.8f);
        pill.setTextColor(0xFFFFFFFF);
        pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(BROWN);
        pillBg.setCornerRadius(em);
        pill.setBackground(pillBg);
        pill.setPadding(Math.round(em * 0.8f), Math.round(em * 0.3f), Math.round(em * 0.8f), Math.round(em * 0.3f));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.topMargin = Math.round(em * 0.5f);
        col.addView(pill, pillLp);

        statusText = new TextView(this);
        statusText.setShadowLayer(em * 0.12f, 0, 0, 0xFFFFFFFF);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, em * 0.85f);
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-2, -2);
        stLp.topMargin = Math.round(em * 0.5f);
        col.addView(statusText, stLp);

        // Buttons (glyphs use U+FE0E so they stay monochrome text, tinted brown).
        // Menu buttons use their em-based padding height: drop the platform
        // Button minimum height (48dp), which would otherwise push the static
        // menu past the landscape height. Dialog buttons are untouched.
        playBtn = menuButton(null, "Jogar", true);
        playBtn.setMinimumHeight(0);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(); }
        });
        LinearLayout playRow = menuRow(new PokeBallView(this, Math.round(em * 1.7f)), playBtn);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
        btnLp.topMargin = Math.round(em * 0.7f);
        col.addView(playRow, btnLp);

        importBtn = menuButton(null, "Importar jogo", false);
        importBtn.setMinimumHeight(0);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(REQ_IMPORT_DIR); }
        });
        col.addView(menuRow(glyphIcon("📁︎", 1.3f), importBtn), btnLp);

        android.widget.Button cfgBtn = menuButton(null, "Configurações", false);
        cfgBtn.setMinimumHeight(0);
        cfgBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(settingsView); }
        });
        col.addView(menuRow(glyphIcon("⚙︎", 1.3f), cfgBtn), btnLp);

        android.widget.Button modsBtn = menuButton(null, "Mods", false);
        modsBtn.setMinimumHeight(0);
        modsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(modsView); }
        });
        col.addView(menuRow(glyphIcon("🔧︎", 1.3f), modsBtn), btnLp);

        android.widget.Button ctlBtn = menuButton(null, "Controles", false);
        ctlBtn.setMinimumHeight(0);
        ctlBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(controlsView); }
        });
        col.addView(menuRow(glyphIcon("🎮︎", 1.3f), ctlBtn), btnLp);

        // Restore unscaled em for the overlay bar/watermark: they are not part
        // of the column height, so they stay exactly as before.
        emOverride = -1f;
        em = emOrig;

        // ---- top-right pill buttons (v0012): visible cream pills with text,
        // like the approved mockup — the bare glyphs vanished on the beige art.
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackground(null);
        TextView gCfg = topPill("⚙ Config");
        gCfg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(settingsView); }
        });
        TextView gCtl = topPill("Controles");
        gCtl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(controlsView); }
        });
        TextView gInfo = topPill("Sobre");
        gInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(aboutView); }
        });
        int pillGap = Math.round(em * 0.4f);
        LinearLayout.LayoutParams topPillLp = new LinearLayout.LayoutParams(-2, -2);
        topPillLp.rightMargin = pillGap;
        topBar.addView(gCfg, topPillLp);
        topBar.addView(gCtl, topPillLp);
        topBar.addView(gInfo, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout main = new FrameLayout(this);
        main.addView(wrap, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        topLp.topMargin = Math.round(screenW * 0.015f);
        topLp.rightMargin = Math.round(screenW * 0.02f);
        main.addView(topBar, topLp);
        // (v0012) Channel button, bottom-right, where the watermark ball was:
        // small cream pill, red YouTube play + @emuucheck, opens the channel.
        TextView channelBtn = new TextView(this);
        android.text.SpannableString channelLabel =
                new android.text.SpannableString("▶ @emuucheck");
        channelLabel.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF0000),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        channelLabel.setSpan(new android.text.style.ForegroundColorSpan(BROWN),
                2, channelLabel.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        channelBtn.setText(channelLabel);
        channelBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        channelBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.85f);
        channelBtn.setGravity(Gravity.CENTER);
        GradientDrawable channelBg = new GradientDrawable();
        channelBg.setColor(CREAM);
        channelBg.setCornerRadius(em());
        channelBg.setStroke(dp(1), 0xFFE3C400);
        channelBtn.setBackground(channelBg);
        int chp = Math.round(em() * 0.6f);
        channelBtn.setPadding(chp, Math.round(em() * 0.3f), chp, Math.round(em() * 0.3f));
        channelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CHANNEL)));
            }
        });
        FrameLayout.LayoutParams channelLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        channelLp.rightMargin = Math.round(screenW * 0.01f);
        channelLp.bottomMargin = Math.round(screenW * 0.01f);
        main.addView(channelBtn, channelLp);
        return main;
    }

    /** A sub-screen scaffold: cream card on the art, title + back button + content. */
    private LinearLayout screenCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xF2FFFBEA);
        d.setCornerRadius(em() * 0.9f);
        d.setStroke(dp(1), 0xFFE3C400);
        card.setBackground(d);
        card.setPadding(Math.round(em() * 0.9f), Math.round(em() * 0.7f), Math.round(em() * 0.9f), Math.round(em() * 0.7f));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.Button back = new android.widget.Button(this);
        back.setText("← Voltar");
        back.setAllCaps(false);
        back.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        back.setTextColor(BROWN);
        back.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.9f);
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(CREAM);
        backBg.setCornerRadius(em());
        back.setBackground(backBg);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(mainView); }
        });
        head.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 1.5f);
        t.setTextColor(BROWN_DARK);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = Math.round(em() * 0.8f);
        head.addView(t, tlp);
        card.addView(head, lp(-1, -2));
        return card;
    }

    /** Like screenCard, but Voltar returns to the Configurações menu (v0013). */
    private LinearLayout screenCardSub(String title) {
        LinearLayout card = screenCard(title);
        // screenCard's back button goes to mainView; rewire it to settingsView.
        // (The head row is card's first child; its first child is the back button.)
        LinearLayout head = (LinearLayout) card.getChildAt(0);
        android.widget.Button back = (android.widget.Button) head.getChildAt(0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(settingsView); }
        });
        return card;
    }

    private View screenWrap(LinearLayout card) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(16), dp(20), dp(16));
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(wrap);
        return scroll;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 1.05f);
        t.setTextColor(BROWN);
        t.setPadding(0, Math.round(em() * 0.7f), 0, Math.round(em() * 0.2f));
        return t;
    }

    private TextView bodyText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.9f);
        t.setTextColor(BROWN_DARK);
        return t;
    }

    private CheckBox brownCheck(String text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(BROWN_DARK);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.9f);
        return c;
    }

    // ---- Launcher-styled dialogs (v0101) ----
    // The fork's plain AlertDialogs/ProgressDialogs (black title bar, white
    // list) looked nothing like this launcher. These reuse the launcher's
    // own palette: cream panel, brown text, yellow primary pill.

    private LinearLayout creamPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xF2FFFBEA);
        d.setCornerRadius(em() * 0.9f);
        d.setStroke(dp(1), 0xFFE3C400);
        col.setBackground(d);
        int pad = Math.round(em() * 0.9f);
        col.setPadding(pad, Math.round(em() * 0.7f), pad, Math.round(em() * 0.7f));
        return col;
    }

    private LinearLayout dialogCard(String title) {
        LinearLayout col = creamPanel();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 1.3f);
        t.setTextColor(BROWN_DARK);
        col.addView(t, lp(-1, -2));
        return col;
    }

    private android.app.Dialog cardDialog(LinearLayout col) {
        android.app.Dialog d = new android.app.Dialog(this);
        d.setContentView(col);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w.setLayout(Math.round(dm.widthPixels * 0.92f), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    private void showStyledMessage(String title, String message) {
        LinearLayout col = dialogCard(title);
        col.addView(bodyText(message), lp(-1, -2));
        final android.app.Dialog d = cardDialog(col);
        android.widget.Button ok = menuButton(null, "OK", false);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        col.addView(ok, lp(-1, -2));
        d.show();
    }

    private void showStyledConfirm(String title, String message, String posText,
            final android.content.DialogInterface.OnClickListener posAction, String negText) {
        LinearLayout col = dialogCard(title);
        col.addView(bodyText(message), lp(-1, -2));
        final android.app.Dialog d = cardDialog(col);
        android.widget.Button pos = menuButton(null, posText, true);
        pos.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                if (posAction != null) posAction.onClick(null, 0);
            }
        });
        col.addView(pos, lp(-1, -2));
        android.widget.Button neg = menuButton(null, negText, false);
        neg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        col.addView(neg, lp(-1, -2));
        d.show();
    }

    private void showStyledList(String title, final CharSequence[] items,
            final android.content.DialogInterface.OnClickListener onItem) {
        LinearLayout col = dialogCard(title);
        android.widget.ListView list = new android.widget.ListView(this);
        android.widget.ArrayAdapter<CharSequence> ad = new android.widget.ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_1, items) {
            @Override public View getView(int pos, View v, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, v, parent);
                tv.setTextColor(BROWN_DARK);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.9f);
                int p = Math.round(em() * 0.5f);
                tv.setPadding(p, p, p, p);
                return tv;
            }
        };
        list.setAdapter(ad);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xFFE3C400));
        list.setDividerHeight(dp(1));
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        col.addView(list, new LinearLayout.LayoutParams(-1, Math.round(dm.heightPixels * 0.5f)));
        final android.app.Dialog d = cardDialog(col);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                d.dismiss();
                if (onItem != null) onItem.onClick(null, pos);
            }
        });
        android.widget.Button close = menuButton(null, "Fechar", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        col.addView(close, lp(-1, -2));
        d.show();
    }

    private android.app.Dialog styledProgress(String message) {
        LinearLayout col = creamPanel();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        row.addView(pb, new LinearLayout.LayoutParams(-2, -2));
        TextView tv = bodyText(message);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.leftMargin = Math.round(em() * 0.5f);
        row.addView(tv, tlp);
        col.addView(row, lp(-1, -2));
        android.app.Dialog d = cardDialog(col);
        d.setCancelable(false);
        d.show();
        return d;
    }

    /** Configurações: a menu of submenus — Imagem, Jogo, Driver GPU, Importação. (v0013) */
    private View buildSettings() {
        LinearLayout card = screenCard("Configurações");
        card.addView(submenuButton("Imagem", "Resolução interna, suavizar serrilhado", settingsImageView));
        card.addView(submenuButton("Jogo", "60 fps, mostrar FPS, idioma, batalhas selvagens", settingsGameView));
        card.addView(submenuButton("Driver GPU", "Turnip, driver do sistema", settingsDriverView));
        card.addView(submenuButton("Importação", "Pasta extraída, pacote de dados, pasta do jogo", settingsImportView));
        card.addView(bodyText("Toca num item para abrir o submenu dele."), lp(-1, -2));
        // Load saved ini into the submenu checkboxes (built just before this menu).
        String[] ini = readIni();
        if (ini != null) {
            if (fps60Box != null) fps60Box.setChecked("60".equals(ini[0]));
            if (smaaBox != null) smaaBox.setChecked("1".equals(ini[1]));
            if (wildBox != null) wildBox.setChecked("1".equals(ini[2]));
        }
        if (showFpsBox != null) showFpsBox.setChecked("1".equals(readIniValue("show_fps")));
        return screenWrap(card);
    }

    /** One submenu row: title + subtitle + ›, cream pill like the main menu. */
    private LinearLayout submenuButton(String title, String sub, final View target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(CREAM);
        d.setCornerRadius(em() * 1.4f);
        d.setStroke(dp(1), 0xFFE3C400);
        row.setBackground(d);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 1.05f);
        t.setTextColor(BROWN);
        col.addView(t, lp(-1, -2));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.8f);
        s.setTextColor(0xFF8C6E50);
        col.addView(s, lp(-1, -2));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 1.2f);
        arrow.setTextColor(BROWN);
        row.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        int pad = Math.round(em() * 0.7f);
        row.setPadding(pad, Math.round(em() * 0.5f), pad, Math.round(em() * 0.5f));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = Math.round(em() * 0.5f);
        row.setLayoutParams(rowLp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showScreen(target); }
        });
        return row;
    }

    /** Submenu Imagem: resolução interna + SMAA. */
    private View buildSettingsImage() {
        LinearLayout card = screenCardSub("Imagem");
        card.addView(bodyText("Resolução interna:"), lp(-1, -2));
        final Spinner resSpinner = new Spinner(this);
        final String[] resNames = {"0.5x (480p) - Performance", "0.75x (540p) - Equilibrado", "1x (720p) - Padrão", "2x (1080p) - Qualidade"};
        final String[] resValues = {"0.4444", "0.5", "0.6667", "1.0"};
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, resNames);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resSpinner.setAdapter(resAdapter);
        String savedRes = getSharedPreferences(PREFS, MODE_PRIVATE).getString("resolution_scale", "0.6667");
        for (int i = 0; i < resValues.length; i++) {
            if (resValues[i].equals(savedRes)) { resSpinner.setSelection(i); break; }
        }
        resSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("resolution_scale", resValues[position]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        card.addView(resSpinner, lp(-1, -2));
        smaaBox = brownCheck("Suavizar serrilhado (SMAA)");
        smaaBox.setOnClickListener(saveIniClick);
        card.addView(smaaBox, lp(-1, -2));
        card.addView(bodyText("A resolução muda o quanto o jogo renderiza por dentro. 0.5x é mais leve, 2x é mais nítido e mais pesado."), lp(-1, -2));
        return screenWrap(card);
    }

    /** Submenu Jogo: 60 fps, mostrar FPS, idioma, batalhas selvagens. */
    private View buildSettingsGame() {
        LinearLayout card = screenCardSub("Jogo");
        fps60Box = brownCheck("60 fps (experimental)");
        fps60Box.setOnClickListener(saveIniClick);
        card.addView(fps60Box, lp(-1, -2));
        // (v0013) Mostrar FPS: o runtime já desenha "XX fps" no canto superior
        // direito quando show_fps = 1 no larecomp.ini (ui/menu.mm) — o launcher
        // só nunca expunha a opção. Fica logo abaixo do 60 fps, como pedido.
        showFpsBox = brownCheck("Mostrar FPS");
        showFpsBox.setOnClickListener(saveIniClick);
        card.addView(showFpsBox, lp(-1, -2));
        TextView langLabel = bodyText("Idioma do jogo:");
        card.addView(langLabel, lp(-1, -2));
        final Spinner langSpinner = new Spinner(this);
        final String[] langNames = {"Padrão (inglês / tradução PT-BR)", "English", "Français", "Español", "Deutsch",
                                    "Italiano", "日本語", "한국어", "简体中文", "繁體中文"};
        final String[] langValues = {"", "en-US", "fr", "es", "de", "it", "ja", "ko", "zh-CN", "zh-TW"};
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, langNames);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(langAdapter);
        String savedLang = readIniValue("game_lang");
        for (int i = 0; i < langValues.length; i++) {
            if (langValues[i].equals(savedLang == null ? "" : savedLang)) { langSpinner.setSelection(i); break; }
        }
        langSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String cur = readIniValue("game_lang");
                if (langValues[position].equals(cur == null ? "" : cur)) return;  // (the initial selection)
                java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
                kv.put("game_lang", langValues[position]);
                updateIni(kv);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        card.addView(langSpinner, lp(-1, -2));
        wildBox = brownCheck("Batalhas selvagens (experimental)");
        wildBox.setOnClickListener(saveIniClick);
        card.addView(wildBox, lp(-1, -2));
        return screenWrap(card);
    }

    /** Submenu Driver GPU (Turnip). */
    private View buildSettingsDriver() {
        LinearLayout card = screenCardSub("Driver GPU");
        drvStatus = bodyText("");
        card.addView(drvStatus, lp(-1, -2));
        refreshDrivers = new Runnable() {
            @Override public void run() {
                GpuDrivers.Driver sel = GpuDrivers.selected(LetsGoLauncherActivity.this);
                java.util.List<GpuDrivers.Driver> all = GpuDrivers.listDeduped(LetsGoLauncherActivity.this);
                StringBuilder sb = new StringBuilder();
                if (sel != null) {
                    sb.append("Selecionado: ").append(sel.name);
                    if (!sel.driverVersion.isEmpty()) sb.append(" (").append(sel.driverVersion).append(")");
                    sb.append("\n  origem: ").append(sel.origin());
                    sb.append(sel.started ? " · já funcionou aqui" : " · ainda não testado: em teste no próximo Jogar");
                    sb.append("\n");
                } else {
                    sb.append("Selecionado: driver do sistema\n");
                }
                String last = GpuDrivers.lastLaunch(LetsGoLauncherActivity.this);
                if (last != null) {
                    String lastName = "driver do sistema";
                    if (!last.isEmpty()) {
                        lastName = last;
                        for (GpuDrivers.Driver d : GpuDrivers.list(LetsGoLauncherActivity.this))
                            if (last.equals(new File(new File(GpuDrivers.driversDir(LetsGoLauncherActivity.this), d.slug), d.libraryName).getAbsolutePath()))
                                lastName = d.name;
                    }
                    boolean same = sel == null ? last.isEmpty()
                        : last.equals(new File(new File(GpuDrivers.driversDir(LetsGoLauncherActivity.this), sel.slug), sel.libraryName).getAbsolutePath());
                    if (!same) sb.append("Último início do jogo: ").append(lastName).append("\n");
                }
                if (all.isEmpty()) {
                    sb.append("Nenhum driver Turnip instalado: baixe o recomendado ou importe um .zip.");
                } else {
                    sb.append("Instalados: ");
                    for (int i = 0; i < all.size(); i++) {
                        GpuDrivers.Driver d = all.get(i);
                        if (i > 0) sb.append(", ");
                        sb.append(d.name);
                        if (sel != null && d.slug.equals(sel.slug)) sb.append(" ✓");
                        if (d.failed && !d.started) sb.append(" [não iniciou]");
                    }
                }
                drvStatus.setText(sb.toString());
            }
        };
        GpuDrivers.installBundled(this);
        refreshDrivers.run();
        android.widget.Button importDrv = new android.widget.Button(this);
        importDrv.setText("Importar driver Turnip (.zip)");
        importDrv.setAllCaps(false);
        importDrv.setTextColor(BROWN);
        importDrv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(REQ_IMPORT_DRIVER); }
        });
        card.addView(importDrv, lp(-1, -2));
        android.widget.Button useSystem = new android.widget.Button(this);
        useSystem.setText("Usar driver do sistema");
        useSystem.setAllCaps(false);
        useSystem.setTextColor(BROWN);
        useSystem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                GpuDrivers.select(LetsGoLauncherActivity.this, null);
                refreshDrivers.run();
                android.widget.Toast.makeText(LetsGoLauncherActivity.this,
                    "Driver do sistema selecionado.", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(useSystem, lp(-1, -2));
        android.widget.Button nextDrv = new android.widget.Button(this);
        nextDrv.setText("Próximo driver");
        nextDrv.setAllCaps(false);
        nextDrv.setTextColor(BROWN);
        nextDrv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                java.util.List<GpuDrivers.Driver> all = GpuDrivers.list(LetsGoLauncherActivity.this);
                GpuDrivers.Driver sel = GpuDrivers.selected(LetsGoLauncherActivity.this);
                if (all.isEmpty()) return;
                int idx = sel != null ? all.indexOf(sel) : -1;
                GpuDrivers.Driver next = all.get((idx + 1) % all.size());
                GpuDrivers.select(LetsGoLauncherActivity.this, next);
                refreshDrivers.run();
                android.widget.Toast.makeText(LetsGoLauncherActivity.this,
                    "Driver selecionado: " + next.name, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(nextDrv, lp(-1, -2));
        android.widget.Button dlDrv = new android.widget.Button(this);
        dlDrv.setText("Baixar driver recomendado");
        dlDrv.setAllCaps(false);
        dlDrv.setTextColor(BROWN);
        dlDrv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { recommendDriver(); }
        });
        card.addView(dlDrv, lp(-1, -2));
        card.addView(bodyText("Detecta o hardware do aparelho, consulta as publicações no GitHub e baixa o pacote Turnip mais atual recomendado para ele (sha256 conferido)."), lp(-1, -2));
        return screenWrap(card);
    }

    /** Submenu Importação. */
    private View buildSettingsImport() {
        LinearLayout card = screenCardSub("Importação");
        card.addView(bodyText("O importador de NSP continua funcionando: é o botão \"Importar jogo\" do menu principal. Aqui ficam as outras entradas dele."), lp(-1, -2));
        android.widget.Button folderBtn = new android.widget.Button(this);
        folderBtn.setText("Usar pasta já extraída");
        folderBtn.setAllCaps(false);
        folderBtn.setTextColor(BROWN);
        folderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(REQ_IMPORT_DIR_FOLDER); }
        });
        card.addView(folderBtn, lp(-1, -2));
        android.widget.Button datapackBtn = new android.widget.Button(this);
        datapackBtn.setText("Importar pacote de dados");
        datapackBtn.setAllCaps(false);
        datapackBtn.setTextColor(BROWN);
        datapackBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDatapack(); }
        });
        card.addView(datapackBtn, lp(-1, -2));
        card.addView(bodyText("O pacote de dados é o arquivo letsgo-data.zip, ou suas partes letsgo-data.zip.part-NN: o launcher junta as partes, descompacta e deixa o jogo pronto, sem NSP e sem montar o .bin no celular."), lp(-1, -2));
        android.widget.Button dirBtn = new android.widget.Button(this);
        dirBtn.setText("Mudar pasta do jogo");
        dirBtn.setAllCaps(false);
        dirBtn.setTextColor(BROWN);
        dirBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(REQ_GAME_DIR); }
        });
        card.addView(dirBtn, lp(-1, -2));
        android.widget.Button findBtn = new android.widget.Button(this);
        findBtn.setText("Procurar dados já importados");
        findBtn.setAllCaps(false);
        findBtn.setTextColor(BROWN);
        findBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { maybeDetectGameData(true); }
        });
        card.addView(findBtn, lp(-1, -2));
        android.widget.Button typeBtn = new android.widget.Button(this);
        typeBtn.setText("Digitar pasta do jogo");
        typeBtn.setAllCaps(false);
        typeBtn.setTextColor(BROWN);
        typeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTypeGameDirDialog(); }
        });
        card.addView(typeBtn, lp(-1, -2));
        card.addView(bodyText("Se o seletor do Android não deixa escolher a pasta, use Digitar pasta do jogo e escreva o caminho — o launcher usa direto, sem o seletor."), lp(-1, -2));
        return screenWrap(card);
    }

    /** (v0014) Find already-imported game data without the folder picker.
     *  The Android tree picker blocks the Download root and often does not
     *  show a folder you just created until you leave and re-enter it, so
     *  the launcher scans the usual places for extracted/romfs.bin itself. */
    private String findExistingGameData() {
        java.io.File ext = android.os.Environment.getExternalStorageDirectory();
        java.util.ArrayList<java.io.File> cands = new java.util.ArrayList<>();
        cands.add(new java.io.File(ext, "Download/LetsGo"));
        cands.add(new java.io.File(ext, "LetsGo"));
        cands.add(new java.io.File(ext, "Download"));
        cands.add(getFilesDir());
        java.io.File dl = new java.io.File(ext, "Download");
        java.io.File[] subs = dl.listFiles();
        if (subs != null) {
            for (java.io.File s : subs) if (s.isDirectory()) cands.add(s);
        }
        for (java.io.File c : cands) {
            if (c == null) continue;
            java.io.File ex = new java.io.File(c, "extracted");
            if (new java.io.File(ex, "romfs.bin").exists() || new java.io.File(ex, ".import_ok").exists())
                return c.getAbsolutePath();
        }
        return null;
    }

    private void maybeDetectGameData(final boolean manual) {
        if (gameDir(this) != null && gameDataReady(this)) return;
        final String found = findExistingGameData();
        if (found == null) {
            if (manual) showStyledMessage("Nada encontrado",
                "Não encontrei nenhum jogo já importado na memória interna. Use Importar jogo ou Importar pacote de dados para instalar.");
            return;
        }
        if (found.equals(gameDir(this))) return;
        showStyledConfirm("Dados do jogo encontrados",
            "Encontrei o jogo já importado em:\n" + found + "\n\nQuer usar esta pasta? Não precisa importar de novo.",
            "Usar esta pasta", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_GAME_DIR, found).apply();
                    refreshState();
                }
            }, "Escolher outra");
    }

    /** (v0014) Type the game folder path by hand — no Android picker involved. */
    private void showTypeGameDirDialog() {
        LinearLayout col = dialogCard("Pasta do jogo");
        col.addView(bodyText("Escreva o caminho da pasta do jogo. Ex.: /storage/emulated/0/Download/LetsGo"), lp(-1, -2));
        final android.widget.EditText input = new android.widget.EditText(this);
        String cur = gameDir(this);
        input.setText(cur != null ? cur : "/storage/emulated/0/Download/LetsGo");
        input.setTextColor(BROWN_DARK);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.9f);
        col.addView(input, lp(-1, -2));
        final android.app.Dialog d = cardDialog(col);
        android.widget.Button ok = menuButton(null, "Usar esta pasta", true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String path = input.getText().toString().trim();
                java.io.File f = new java.io.File(path);
                if (!f.isDirectory()) {
                    showStyledMessage("Pasta inválida", "Não existe uma pasta nesse caminho:\n" + path);
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_GAME_DIR, path).apply();
                d.dismiss();
                refreshState();
                if (!gameDataReady(LetsGoLauncherActivity.this)) {
                    showStyledMessage("Pasta salva",
                        "Pasta salva, mas não encontrei o jogo nela (falta extracted/romfs.bin ou .import_ok). Confira o caminho ou importe os dados.");
                }
            }
        });
        col.addView(ok, lp(-1, -2));
        android.widget.Button cancel = menuButton(null, "Cancelar", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        col.addView(cancel, lp(-1, -2));
        d.show();
    }

    /** Save fps/smaa/wild/show_fps from the submenu checkboxes. */
    private void saveIniFromBoxes() {
        writeIni(fps60Box != null && fps60Box.isChecked() ? "60" : "30",
                 smaaBox != null && smaaBox.isChecked() ? "1" : "0",
                 wildBox != null && wildBox.isChecked() ? "1" : "0",
                 showFpsBox != null && showFpsBox.isChecked() ? "1" : "0");
    }

    // ---- Mods screen (rebuilt every time it opens, so new folders show up) ----
    private LinearLayout modsContent;
    private TextView modsHelp;

    private View buildMods() {
        LinearLayout card = screenCard("Mods");
        modsContent = new LinearLayout(this);
        modsContent.setOrientation(LinearLayout.VERTICAL);
        card.addView(modsContent, lp(-1, -2));
        return screenWrap(card);
    }

    private void rebuildMods() {
        if (modsContent == null) return;
        modsContent.removeAllViews();
        android.widget.Button addBtn = new android.widget.Button(this);
        addBtn.setText("Adicionar um mod");
        addBtn.setAllCaps(false);
        addBtn.setTextColor(BROWN);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickModFile(); }
        });
        modsContent.addView(addBtn, lp(-1, -2));
        android.widget.Button gbBtn = new android.widget.Button(this);
        gbBtn.setText("Explorar mods (GameBanana)");
        gbBtn.setAllCaps(false);
        gbBtn.setTextColor(BROWN);
        gbBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGbCatalogue(); }
        });
        modsContent.addView(gbBtn, lp(-1, -2));
        String gd = gameDir(this);
        final File root = new File(gd != null ? gd : getFilesDir().getAbsolutePath());
        final java.util.List<String> modNames = listMods(new File(root, "mods"));
        final java.util.List<String> active = activeMods(readIniValue("mod"));
        for (final String name : modNames) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            final CheckBox box = brownCheck(name);
            box.setChecked(active.contains(name));
            box.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                    java.util.List<String> now = activeMods(readIniValue("mod"));
                    now.remove(name);
                    if (c) now.add(name);
                    java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
                    kv.put("mod", android.text.TextUtils.join(", ", now));
                    updateIni(kv);
                    rebuildMods();
                }
            });
            row.addView(box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final int idx = active.indexOf(name);
            android.widget.Button up = smallBtn("▲", idx > 0);
            up.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { moveMod(name, -1); }
            });
            row.addView(up);
            android.widget.Button dn = smallBtn("▼", idx >= 0 && idx < active.size() - 1);
            dn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { moveMod(name, +1); }
            });
            row.addView(dn);
            android.widget.Button rm = smallBtn("Remover", true);
            rm.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmRemoveMod(root, name); }
            });
            row.addView(rm);
            modsContent.addView(row, lp(-1, -2));
        }
        // Conflicts: a file in two active mods (the one above wins).
        java.util.List<ModStore.Conflict> cs = ModStore.conflicts(root, active);
        modsHelp = new TextView(this);
        modsHelp.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.9f);
        modsHelp.setTextColor(BROWN);
        StringBuilder help = new StringBuilder(modsHelpText(active, modNames.isEmpty()));
        if (!cs.isEmpty()) {
            help.append("\nConflitos: ").append(cs.size()).append(" arquivo(s) em mais de um mod ativo (vale o de cima):");
            for (int i = 0; i < Math.min(6, cs.size()); i++)
                help.append("\n  • ").append(cs.get(i).path).append(" (").append(cs.get(i).winner).append(" > ").append(cs.get(i).loser).append(")");
        }
        modsHelp.setText(help.toString());
        modsContent.addView(modsHelp, lp(-1, -2));
    }

    private android.widget.Button smallBtn(String text, boolean enabled) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(BROWN);
        b.setEnabled(enabled);
        return b;
    }

    private void moveMod(String name, int delta) {
        java.util.List<String> now = activeMods(readIniValue("mod"));
        int i = now.indexOf(name);
        if (i < 0 || i + delta < 0 || i + delta >= now.size()) return;
        now.remove(i);
        now.add(i + delta, name);
        java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
        kv.put("mod", android.text.TextUtils.join(", ", now));
        updateIni(kv);
        rebuildMods();
    }

    private void confirmRemoveMod(final File root, final String name) {
        showStyledConfirm(name, "Remover este mod do aparelho?", "Remover", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ModStore.remove(root, name);
                        java.util.List<String> now = activeMods(readIniValue("mod"));
                        now.remove(name);
                        java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
                        kv.put("mod", android.text.TextUtils.join(", ", now));
                        updateIni(kv);
                        rebuildMods();
                    }
                }, "Cancelar");
    }

    /** Open the mod archive picker (.zip/.rar/.7z, fork ModUnpack). */
    private void pickModFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, ModUnpack.pickerTypes());
        try { startActivityForResult(i, REQ_MOD_FILE); }
        catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "Este aparelho não tem seletor de arquivos.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private String displayName(android.net.Uri uri) {
        android.database.Cursor cur = null;
        try {
            cur = getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int col = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (col >= 0) return cur.getString(col);
            }
        } catch (Exception ignored) { } finally { if (cur != null) cur.close(); }
        String path = uri.getPath();
        return path == null ? uri.toString() : new File(path).getName();
    }

    private void installMod(final String fileName, final android.net.Uri uri) {
        if (modBusy) return;
        modBusy = true;
        final android.app.Dialog d = styledProgress("Importando " + fileName + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                String gd = gameDir(LetsGoLauncherActivity.this);
                final File root = new File(gd != null ? gd : getFilesDir().getAbsolutePath());
                final File modsDir = new File(root, "mods");
                String error = null;
                ModStore.Unpacked unpacked = null;
                android.os.ParcelFileDescriptor pfd = null;
                try {
                    pfd = getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd == null) throw new java.io.IOException("não abriu");
                    String out = ModUnpack.unpack(null, pfd.detachFd(), modsDir, ModStore.safeName(fileName));
                    unpacked = ModStore.Unpacked.parse(out);
                    if (unpacked == null || !unpacked.ok) error = out;
                } catch (Exception e) { error = e.getMessage(); }
                finally { if (pfd != null) try { pfd.close(); } catch (Exception ignored) { } }
                final String fe = error; final ModStore.Unpacked fu = unpacked;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        modBusy = false;
                        d.dismiss();
                        finishModInstall(root, fileName, null, null, fu, fe);
                    }
                });
            }
        }, "mod-install").start();
    }

    /** Shared end of a mod install (file picker or GameBanana): activate at the end of the list, report. */
    private void finishModInstall(File root, String displayName, GameBanana.Mod gbMod, GameBanana.FileItem gbFile,
                                  ModStore.Unpacked unpacked, String error) {
        if (unpacked == null || !unpacked.ok) {
            String msg;
            ModStore.Unpacked r = unpacked != null ? unpacked : ModStore.Unpacked.parse(error);
            if (r.err == 2) msg = r.code > 0
                    ? "Este mod só modifica o programa do jogo, e isso este port não sabe aplicar."
                    : "Nada aqui substitui arquivos do jogo (contém: " + displayName + "). Talvez seja uma gravação ou um guia.";
            else if (r.err == 4) {
                android.os.StatFs fs = new android.os.StatFs(getFilesDir().getPath());
                msg = "Espaço insuficiente no aparelho (livres: "
                        + android.text.format.Formatter.formatShortFileSize(this, fs.getAvailableBytes())
                        + "). Libere espaço e tente de novo.";
            } else if (r.err == 1 && !ModUnpack.hasExtension(displayName)) {
                String ext = displayName.contains(".") ? displayName.substring(displayName.lastIndexOf('.') + 1) : "";
                msg = "O formato ." + ext + " ainda não é suportado: descompacte o arquivo e adicione a pasta.";
            } else msg = "Arquivo ilegível: download incompleto, arquivo danificado ou protegido por senha. Baixe de novo.";
            showStyledMessage(displayName, msg);
            return;
        }
        if (unpacked.mods.isEmpty()) {
            showStyledMessage(displayName, "O arquivo não tem pastas de romfs (só código do jogo?).");
            return;
        }
        if (gbMod != null && gbFile != null) {
            try {
                java.util.Properties props = new java.util.Properties();
                props.setProperty("gamebanana_id", String.valueOf(gbMod.id));
                props.setProperty("file_id", String.valueOf(gbFile.id));
                props.setProperty("title", gbMod.name);
                ModStore.writeSource(root, unpacked.mods.get(0), props);
            } catch (Exception ignored) { }
        }
        java.util.List<String> now = activeMods(readIniValue("mod"));
        for (String v : unpacked.mods) {
            if (now.contains(v)) now.remove(v);
            now.add(v);
        }
        java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
        kv.put("mod", android.text.TextUtils.join(", ", now));
        updateIni(kv);
        rebuildMods();
        showStyledMessage(displayName, "Mod instalado e ativo no fim da lista (menor prioridade):\n\n"
                        + android.text.TextUtils.join("\n", unpacked.mods)
                        + (unpacked.code > 0 ? "\n\n" + unpacked.code + " arquivo(s) de código do jogo foram ignorados." : ""));
    }

    private void openGbCatalogue() {
        final android.app.Dialog d = styledProgress("Consultando GameBanana...");
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<GameBanana.Mod> mods = null;
                String err = null;
                try { mods = GameBanana.mods(); } catch (Exception e) { err = e.getMessage(); }
                final java.util.List<GameBanana.Mod> fm = mods; final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        d.dismiss();
                        if (fm == null) {
                            showStyledMessage("GameBanana", "Não deu para consultar o GameBanana: " + fe);
                            return;
                        }
                        CharSequence[] items = new CharSequence[fm.size()];
                        for (int i = 0; i < fm.size(); i++) items[i] = fm.get(i).name + " — " + fm.get(i).author;
                        showStyledList("Mods no GameBanana", items, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface dd, int which) {
                                        showGbFiles(fm.get(which));
                                    }
                                });
                    }
                });
            }
        }, "gb-mods").start();
    }

    private void showGbFiles(final GameBanana.Mod mod) {
        if (!mod.installableInApp()) {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(mod.profileUrl)));
            return;
        }
        final android.app.Dialog d = styledProgress("Listando arquivos de " + mod.name + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<GameBanana.FileItem> files = null;
                String err = null;
                try { files = GameBanana.files(mod.id); } catch (Exception e) { err = e.getMessage(); }
                final java.util.List<GameBanana.FileItem> ff = files; final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        d.dismiss();
                        if (ff == null) {
                            showStyledMessage(mod.name, "Não deu para listar os arquivos: " + fe);
                            return;
                        }
                        CharSequence[] items = new CharSequence[ff.size()];
                        for (int i = 0; i < ff.size(); i++) {
                            String p = ff.get(i).problem();
                            items[i] = ff.get(i).name + (p == null ? "" : " (" + p + ")");
                        }
                        showStyledList(mod.name, items, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface dd, int which) {
                                        GameBanana.FileItem f = ff.get(which);
                                        if (f.problem() != null) {
                                            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(mod.profileUrl)));
                                            return;
                                        }
                                        installGb(mod, f);
                                    }
                                });
                    }
                });
            }
        }, "gb-files").start();
    }

    private void installGb(final GameBanana.Mod mod, final GameBanana.FileItem file) {
        if (modBusy) return;
        modBusy = true;
        final android.app.Dialog d = styledProgress("Baixando " + file.name + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                String gd = gameDir(LetsGoLauncherActivity.this);
                final File root = new File(gd != null ? gd : getFilesDir().getAbsolutePath());
                final File modsDir = new File(root, "mods");
                String error = null;
                ModStore.Unpacked unpacked = null;
                File tmp = null;
                try {
                    tmp = new File(getCacheDir(), "gb-mod.tmp");
                    GameBanana.download(file, tmp, null);
                    String out = ModUnpack.unpack(tmp.getAbsolutePath(), -1, modsDir, ModStore.safeName(file.name));
                    unpacked = ModStore.Unpacked.parse(out);
                    if (unpacked == null || !unpacked.ok) error = out;
                } catch (Exception e) { error = e.getMessage(); }
                finally { if (tmp != null) tmp.delete(); }
                final String fe = error; final ModStore.Unpacked fu = unpacked;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        modBusy = false;
                        d.dismiss();
                        finishModInstall(root, file.name, mod, file, fu, fe);
                    }
                });
            }
        }, "gb-install").start();
    }

    /** Controles: touch overlay (fork: mínimo ou completo) + giroscópio + physical controller. */
    private View buildControls() {
        LinearLayout card = screenCard("Controles");
        final CheckBox touch = brownCheck("Controles touch na tela");
        touch.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("touch", true));
        touch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("touch", c).apply();
            }
        });
        card.addView(touch, lp(-1, -2));
        card.addView(bodyText("Layout dos controles:"), lp(-1, -2));
        final Spinner layoutSpinner = new Spinner(this);
        final String[] layoutNames = {"Mínimo (nas barras pretas)", "Completo (sobre a imagem)"};
        final String[] layoutValues = {"minimal", "classic"};
        ArrayAdapter<String> layoutAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, layoutNames);
        layoutAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        layoutSpinner.setAdapter(layoutAdapter);
        String savedLayout = getSharedPreferences(PREFS, MODE_PRIVATE).getString("touch_layout", "minimal");
        for (int i = 0; i < layoutValues.length; i++) {
            if (layoutValues[i].equals(savedLayout)) { layoutSpinner.setSelection(i); break; }
        }
        layoutSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("touch_layout", layoutValues[position]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        card.addView(layoutSpinner, lp(-1, -2));
        final CheckBox gyro = brownCheck("Mirar com o giroscópio (inclinar o telefone)");
        gyro.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("gyro", false));
        gyro.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("gyro", c).apply();
            }
        });
        card.addView(gyro, lp(-1, -2));
        card.addView(bodyText("Mínimo: analógico fixo à esquerda e A / B / X / \"…\" à direita, nas barras pretas ao redor da imagem 16:9, sem cobrir o jogo. Completo: o layout Xbox 360 antigo (direcional, ABXY, dois analógicos, LB / RB). Controles físicos (Bluetooth/USB) funcionam direto pelo SDL — é só conectar e jogar."), lp(-1, -2));
        return screenWrap(card);
    }

    /** Sobre: credits, channel, version, install details. */
    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " · código " + pi.versionCode;
        } catch (Exception e) {
            return "desconhecida";
        }
    }

    private View buildAbout() {
        LinearLayout card = screenCard("Sobre");
        card.addView(bodyText("Port Android não oficial de Pokémon: Let's Go, Pikachu! — launcher oficial de lançamento."), lp(-1, -2));
        card.addView(bodyText("Versão instalada: " + appVersion()), lp(-1, -2));
        card.addView(sectionTitle("Créditos"), lp(-1, -2));
        card.addView(bodyText("Base: letsgo-recomp por raulzrrs\nRuntime: LASRecomp por peiikai\nReferência: LASRecomp-Android-Plus por Nycolazs\nContribuições: ptichalouf (idioma, DT_NEEDED, Turnip, tela inicial, texto, desempenho, mods)"), lp(-1, -2));
        card.addView(sectionTitle("Canal"), lp(-1, -2));
        android.widget.Button channel = new android.widget.Button(this);
        channel.setText("📺 Canal no YouTube — @emuucheck");
        channel.setAllCaps(false);
        channel.setTextColor(BROWN);
        channel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openChannel(); }
        });
        card.addView(channel, lp(-1, -2));
        android.widget.Button details = new android.widget.Button(this);
        details.setText("Detalhes da instalação");
        details.setAllCaps(false);
        details.setTextColor(BROWN);
        details.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDetails(); }
        });
        card.addView(details, lp(-1, -2));
        android.widget.Button logBtn = new android.widget.Button(this);
        logBtn.setText("Ver log");
        logBtn.setAllCaps(false);
        logBtn.setTextColor(BROWN);
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(LetsGoLauncherActivity.this, LogActivity.class)); }
        });
        card.addView(logBtn, lp(-1, -2));
        card.addView(bodyText("Pokémon © Nintendo / Creatures Inc. / GAME FREAK — o jogo não está incluído. Use seu próprio dump."), lp(-1, -2));
        return screenWrap(card);
    }

    /** Import progress overlay: dim + centered card, kept visible while importing. */
    private void buildImportOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x992B2D33);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(em() * 0.9f);
        card.setBackground(d);
        card.setPadding(Math.round(em()), Math.round(em() * 0.8f), Math.round(em()), Math.round(em() * 0.8f));
        TextView title = new TextView(this);
        title.setText("Importando jogo…");
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 1.3f);
        title.setTextColor(BROWN_DARK);
        card.addView(title, lp(-1, -2));
        importBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importBar.setMax(100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.topMargin = Math.round(em() * 0.6f);
        card.addView(importBar, barLp);
        importPct = new TextView(this);
        importPct.setText("0%");
        importPct.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        importPct.setTextSize(TypedValue.COMPLEX_UNIT_PX, em());
        importPct.setTextColor(BROWN_DARK);
        importPct.setGravity(Gravity.CENTER);
        card.addView(importPct, lp(-1, -2));
        importPhase = new TextView(this);
        importPhase.setTextColor(BROWN);
        importPhase.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.85f);
        card.addView(importPhase, lp(-1, -2));
        TextView note = new TextView(this);
        note.setText("Não feche o app até terminar.");
        note.setTextColor(BROWN);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, em() * 0.85f);
        card.addView(note, lp(-1, -2));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f), -2, Gravity.CENTER);
        overlay.addView(card, cardLp);
        overlay.setVisibility(View.GONE);
        importOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showImportOverlay(boolean show, int pct, String phase, String name) {
        if (importOverlay == null) return;
        importOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            importBar.setProgress(pct);
            importPct.setText(pct + "%");
            importPhase.setText((phase != null ? phase : "") + (name != null ? " — " + name : ""));
        }
    }

    /** Launch the game — never into a native crash (without complete game data the runtime FATALs). */
    private void play() {
        LetsGoImportJob ij = LetsGoImportJob.current();
        if (ij != null && !ij.finished) {
            android.widget.Toast.makeText(this,
                "Aguarde a importação terminar antes de jogar.",
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (!gameDataReady(this)) {
            android.widget.Toast.makeText(this,
                "O jogo ainda não foi importado por completo.",
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, LetsGoActivity.class));
    }

    /** Open the folder picker (SAF). */
    private void pickFolder(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // (v0014) Land in a selectable subfolder: the picker opens at the
        // Download root, which Android blocks, and a folder created there
        // often does not show up until you leave and re-enter it.
        try {
            java.io.File ext = android.os.Environment.getExternalStorageDirectory();
            java.io.File start = new java.io.File(ext, "Download/LetsGo");
            if (!start.isDirectory()) start = new java.io.File(ext, "Download");
            String rel = start.getAbsolutePath().substring("/storage/emulated/0/".length());
            android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:" + rel);
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri);
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(this,
            "Escolha uma subpasta — a raiz de Download é bloqueada pelo Android.",
            android.widget.Toast.LENGTH_LONG).show();
        startActivityForResult(i, req);
    }

    /** Data-pack import picker: letsgo-data.zip, or its letsgo-data.zip.part-NN pieces. */
    private void pickDatapack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMPORT_DATAPACK);
    }

    /** Validate the picked set and, after confirmation if data exists, import it. */
    private void startDatapackImport(final java.util.ArrayList<android.net.Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        final java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        for (android.net.Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            String n = displayName(u);
            names.add(n != null ? n : "");
        }
        boolean singleZip = names.size() == 1 && names.get(0).endsWith(".zip");
        boolean allParts = !names.isEmpty();
        for (String n : names) {
            if (!n.startsWith("letsgo-data.zip.part-") || n.length() != "letsgo-data.zip.part-".length() + 2)
                allParts = false;
        }
        if (!singleZip && !allParts) {
            showStyledMessage("Pacote de dados", "Escolha o arquivo letsgo-data.zip ou todas as partes letsgo-data.zip.part-NN do mesmo pacote.");
            return;
        }
        if (allParts) {
            // Sort by suffix (two digits sort lexicographically = numerically).
            for (int a = 0; a < names.size(); a++) {
                for (int b = a + 1; b < names.size(); b++) {
                    if (names.get(b).compareTo(names.get(a)) < 0) {
                        String tn = names.get(a); names.set(a, names.get(b)); names.set(b, tn);
                        android.net.Uri tu = uris.get(a); uris.set(a, uris.get(b)); uris.set(b, tu);
                    }
                }
            }
            // The sequence must start at 00 with no gaps.
            for (int k = 0; k < names.size(); k++) {
                String suf = names.get(k).substring(names.get(k).length() - 2);
                int v;
                try { v = Integer.parseInt(suf); } catch (Exception e) { v = -1; }
                if (v != k) {
                    showStyledMessage("Pacote de dados", "Faltam partes do pacote: esperava a parte " + (k < 10 ? "0" + k : "" + k) + ". Escolha todas as partes.");
                    return;
                }
            }
        }
        String dir = gameDir(this);
        final File base = dir != null ? new File(dir) : getFilesDir();
        final File oldExtracted = new File(base, "extracted");
        if (oldExtracted.exists() && new File(oldExtracted, "romfs.bin").exists()) {
            showStyledConfirm("Substituir dados do jogo?",
                "Já existe um jogo importado nesta pasta. O pacote vai substituir os dados atuais.",
                "Substituir", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { doDatapackImport(uris, base); }
                }, "Cancelar");
        } else {
            doDatapackImport(uris, base);
        }
    }

    /** Join (parts) or copy (single zip), unpack, verify, then swap into place. */
    private void doDatapackImport(final java.util.ArrayList<android.net.Uri> uris, final File base) {
        final android.app.Dialog prog = styledProgress("Importando pacote de dados...");
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                File tmpZip = new File(getCacheDir(), "datapack.zip");
                File staging = new File(base, ".datapack_tmp");
                try {
                    // 1. Join the pieces (or copy the single zip) into a temp zip.
                    java.io.OutputStream out = new java.io.FileOutputStream(tmpZip);
                    try {
                        byte[] buf = new byte[65536];
                        for (android.net.Uri u : uris) {
                            java.io.InputStream in = getContentResolver().openInputStream(u);
                            if (in == null) throw new java.io.IOException("não foi possível abrir " + displayName(u));
                            try {
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            } finally { in.close(); }
                        }
                    } finally { out.close(); }
                    // 2. Unpack into staging (same filesystem as the game dir, so the swap is a rename).
                    deleteDir(staging);
                    staging.mkdirs();
                    java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.FileInputStream(tmpZip));
                    try {
                        java.util.zip.ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            File f = new File(staging, e.getName());
                            // Zip-slip guard: every entry must stay inside staging.
                            if (!f.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator)
                                    && !f.getCanonicalPath().equals(staging.getCanonicalPath()))
                                throw new java.io.IOException("entrada inválida no pacote: " + e.getName());
                            if (e.isDirectory()) { f.mkdirs(); continue; }
                            if (f.getParentFile() != null) f.getParentFile().mkdirs();
                            java.io.OutputStream fo = new java.io.FileOutputStream(f);
                            try {
                                byte[] buf = new byte[65536];
                                int n;
                                while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                            } finally { fo.close(); }
                            zin.closeEntry();
                        }
                    } finally { zin.close(); }
                    // 3. Verify BEFORE touching the old data.
                    if (!new File(staging, "extracted/romfs.bin").exists()
                            || !new File(staging, "extracted/exefs/main").exists())
                        throw new java.io.IOException("o pacote não contém extracted/romfs.bin e extracted/exefs/main.");
                    // 4. Swap: old data aside, new data in (same filesystem: rename).
                    File oldExtracted = new File(base, "extracted");
                    File backup = new File(base, ".datapack_old");
                    deleteDir(backup);
                    if (oldExtracted.exists() && !oldExtracted.renameTo(backup))
                        throw new java.io.IOException("não foi possível substituir os dados antigos.");
                    if (!new File(staging, "extracted").renameTo(oldExtracted)) {
                        if (backup.exists()) backup.renameTo(oldExtracted);
                        throw new java.io.IOException("não foi possível instalar o pacote.");
                    }
                    deleteDir(backup);
                    deleteDir(staging);
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : "erro desconhecido";
                    deleteDir(staging);
                } finally {
                    tmpZip.delete();
                }
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        prog.dismiss();
                        if (err != null) showStyledMessage("Pacote de dados", "Falha ao importar: " + err + " Os dados anteriores não foram alterados.");
                        else {
                            showStyledMessage("Pacote de dados", "Pacote importado. O jogo está pronto.");
                            refreshState();
                        }
                    }
                });
            }
        }).start();
    }

    /** Recursive delete (files and dirs), ignoring failures on individual entries. */
    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteDir(k);
        }
        f.delete();
    }

    /** Show the state of the installation: status line, Jogar, import overlay. */
    private void refreshState() {
        LetsGoImportJob job = LetsGoImportJob.current();
        boolean importing = job != null && !job.finished;
        String error = (!importing && job != null) ? job.error : null;
        boolean ready = gameDataReady(this);
        if (statusText != null) {
            if (importing) {
                int pct = job != null && job.total > 0 ? (int) (100.0 * job.done / job.total) : 0;
                statusText.setText("● Importando… " + pct + "%");
                statusText.setTextColor(AMBER);
            } else if (error != null) {
                statusText.setText("● Importação falhou");
                statusText.setTextColor(RED);
            } else if (!ready) {
                statusText.setText("● Jogo não importado");
                statusText.setTextColor(RED);
            } else {
                statusText.setText("● Jogo pronto");
                statusText.setTextColor(GREEN);
            }
        }
        if (playBtn != null) playBtn.setEnabled(ready && !importing);
        if (importBtn != null) importBtn.setEnabled(!importing);
        showImportOverlay(importing,
                importing && job != null && job.total > 0 ? (int) (100.0 * job.done / job.total) : 0,
                importing && job != null ? job.phase : null,
                importing && job != null ? job.currentName : null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_GAME_DIR && res == RESULT_OK && data != null
                && data.getData() != null) {
            android.net.Uri uri = data.getData();
            // keep access across restarts
            try {
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            String path = resolvePath(uri);
            if (path != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_GAME_DIR, path).apply();
            }
            refreshState();
        } else if ((req == REQ_IMPORT_DIR || req == REQ_IMPORT_DIR_FOLDER) && res == RESULT_OK
                && data != null && data.getData() != null) {
            android.widget.Toast.makeText(this, "Pasta escolhida, iniciando...",
                    android.widget.Toast.LENGTH_SHORT).show();
            startImport(data.getData(), req == REQ_IMPORT_DIR_FOLDER);
        } else if (req == REQ_IMPORT_DRIVER && res == RESULT_OK
                && data != null && data.getData() != null) {
            GpuDrivers.ImportResult r = GpuDrivers.importZip(this, data.getData());
            if (r.error != null) {
                android.widget.Toast.makeText(this, "Erro: " + r.error,
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                GpuDrivers.select(this, r.driver);
                android.widget.Toast.makeText(this, "Driver instalado: " + r.driver.name,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            if (refreshDrivers != null) refreshDrivers.run();
            refreshState();
        } else if (req == REQ_IMPORT_DATAPACK && res == RESULT_OK && data != null) {
            java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<android.net.Uri>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++)
                    uris.add(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            startDatapackImport(uris);
        } else if (req == REQ_MOD_FILE && res == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            String name = displayName(uri), type = getContentResolver().getType(uri);
            if (ModUnpack.accepts(name, type)) installMod(name, uri);
            else showStyledMessage(name, "O formato ainda não é suportado: descompacte o arquivo e coloque a pasta em mods/.");
        }
    }

    /** Kick off the import job (folder = true: loose folder import, no romfs.bin). */
    private void startImport(android.net.Uri uri, boolean folder) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        // Import into the game dir (or the app's private files if none picked yet).
        String dir = gameDir(this);
        File outDir = dir != null ? new File(dir) : getFilesDir();
        LetsGoImportJob job = LetsGoImportJob.start(this, uri, outDir, folder);
        if (job != null) startImportPolling();
        refreshState();
    }

    /** Poll the import job and keep the progress bar on screen until it ends. */
    private void startImportPolling() {
        if (importPoller == null) importPoller = new android.os.Handler();
        if (importPollTask != null) importPoller.removeCallbacks(importPollTask);
        importPollTask = new Runnable() {
            @Override public void run() {
                LetsGoImportJob job = LetsGoImportJob.current();
                if (job == null || job.finished) {
                    if (job != null && job.error != null) {
                        android.widget.Toast.makeText(LetsGoLauncherActivity.this,
                            "Importação falhou: " + job.error, android.widget.Toast.LENGTH_LONG).show();
                    } else if (job != null) {
                        android.widget.Toast.makeText(LetsGoLauncherActivity.this,
                            "Importação concluída!", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    refreshState();
                    return;
                }
                int pct = job.total > 0 ? (int) (100.0 * job.done / job.total) : 0;
                showImportOverlay(true, pct, job.phase, job.currentName);
                if (statusText != null) {
                    statusText.setText("● Importando… " + pct + "%");
                    statusText.setTextColor(AMBER);
                }
                if (playBtn != null) playBtn.setEnabled(false);
                if (importBtn != null) importBtn.setEnabled(false);
                importPoller.postDelayed(this, 500);
            }
        };
        importPoller.post(importPollTask);
    }

    /** Resolve a SAF tree URI to a filesystem path (primary storage only). */
    private String resolvePath(android.net.Uri uri) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
            if (docId != null && docId.startsWith("primary:")) {
                return "/storage/emulated/0/" + docId.substring("primary:".length());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private File iniFile() {
        String dir = gameDir(this);
        return new File(dir != null ? dir : getFilesDir().getAbsolutePath(), "larecomp.ini");
    }

    /** One key's value in larecomp.ini ("key = value", the runtime's format), or null if absent. */
    private String readIniValue(String key) {
        try {
            File ini = iniFile();
            String val = null;
            java.util.Scanner sc = new java.util.Scanner(ini, "UTF-8");
            while (sc.hasNextLine()) {
                String l = sc.nextLine().trim();
                int eq = l.indexOf('=');
                if (eq > 0 && l.substring(0, eq).trim().equals(key)) val = l.substring(eq + 1).trim();
            }
            sc.close();
            return val;
        } catch (Exception e) { return null; }
    }

    /** Read fps/smaa/wild from larecomp.ini in the game folder, or null. */
    private String[] readIni() {
        if (!iniFile().exists()) return null;
        String fps = readIniValue("fps"), smaa = readIniValue("smaa"), wild = readIniValue("wild_battle");
        return new String[]{fps != null ? fps : "30", smaa != null ? smaa : "1", wild != null ? wild : "0"};
    }

    private void writeIni(String fps, String smaa, String wild, String showFps) {
        java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
        kv.put("fps", fps);
        kv.put("smaa", smaa);
        kv.put("wild_battle", wild);
        kv.put("fullscreen", "0");
        kv.put("show_fps", showFps);
        updateIni(kv);
    }

    private void updateIni(java.util.Map<String, String> kv) {
        try {
            File ini = iniFile();
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (ini.exists()) {
                java.util.Scanner sc = new java.util.Scanner(ini, "UTF-8");
                while (sc.hasNextLine()) lines.add(sc.nextLine());
                sc.close();
            } else {
                lines.add("# Let's Go Recomp settings (Android launcher)");
            }
            java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(ini), "UTF-8");
            for (String l : mergeIni(lines, kv)) w.write(l + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    static java.util.List<String> mergeIni(java.util.List<String> lines, java.util.Map<String, String> kv) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (String l : lines) {
            int eq = l.indexOf('=');
            String k = eq > 0 ? l.substring(0, eq).trim() : null;
            if (k != null && kv.containsKey(k)) {
                if (done.add(k)) out.add(k + " = " + kv.get(k));  // (a duplicated key: kept once)
            } else {
                out.add(l);
            }
        }
        for (java.util.Map.Entry<String, String> e : kv.entrySet())
            if (!done.contains(e.getKey())) out.add(e.getKey() + " = " + e.getValue());
        return out;
    }

    /** Folders under dir with a romfs/ folder up to 4 levels down (the runtime's rule, runtime/mods.c), sorted. */
    static java.util.List<String> listMods(File dir) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] subs = dir.listFiles();
        if (subs == null) return out;
        for (File f : subs)
            if (f.isDirectory() && !f.getName().startsWith(".") && hasRomfs(f, 4)) out.add(f.getName());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static boolean hasRomfs(File dir, int depth) {
        if (new File(dir, "romfs").isDirectory()) return true;
        if (depth <= 0) return false;
        File[] subs = dir.listFiles();
        if (subs == null) return false;
        for (File f : subs)
            if (f.isDirectory() && !f.getName().startsWith(".") && hasRomfs(f, depth - 1)) return true;
        return false;
    }

    /** The mod line parsed ("mod = A, B"): trimmed, deduped, in order. */
    static java.util.List<String> activeMods(String modLine) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (modLine == null) return out;
        for (String t : modLine.split(",")) {
            t = t.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String modsHelpText(java.util.List<String> now, boolean noneFound) {
        if (noneFound) return "Nenhum mod na pasta mods/. Coloque a pasta do mod (com romfs/ dentro) lá e reabra o launcher.";
        if (now.isEmpty()) return "Nenhum mod ativo. Marque os que quiser usar.";
        return "Ativos (o primeiro vence se dois trocarem o mesmo arquivo): " + android.text.TextUtils.join(", ", now);
    }

    /** Open the author's YouTube channel (the badge's job, like Wonder's). */
    private void openChannel() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CHANNEL)));
        } catch (RuntimeException e) {
            Lui.toast(this, CHANNEL);
        }
    }

    /** Detect, ask GitHub, and offer the package for this phone (never downloads before the player confirms). */
    private void recommendDriver() { recommendDriver(false); }

    private void recommendDriver(final boolean afterFailure) {
        if (driverBusy) return;
        driverBusy = true;
        if (drvStatus != null) drvStatus.setText("Consultando as publicações no GitHub...");
        new Thread(new Runnable() {
            @Override public void run() {
                GpuInfo g = gpu != null ? gpu : GpuInfo.detect();
                DriverCatalog.Check check = null;
                String error = null;
                try {
                    check = DriverCatalog.fetch();
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final GpuInfo fg = g;
                final DriverCatalog.Check fc = check;
                final String fe = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        driverBusy = false;
                        gpu = fg;
                        if (refreshDrivers != null) refreshDrivers.run();
                        offerDriver(fg, fc, fe, afterFailure);
                    }
                });
            }
        }, "gpu-catalog").start();
    }

    private void offerDriver(final GpuInfo g, DriverCatalog.Check check, String error, boolean afterFailure) {
        if (check == null) {
            showStyledMessage("Driver recomendado", "Não deu para consultar o GitHub: " + error
                    + "\n\nSem rede, importe um .zip de driver pelo outro botão.");
            return;
        }
        if (g.family == GpuInfo.Family.NOT_ADRENO) {
            showStyledMessage("Driver recomendado", g.summary() + "\n\nEste aparelho não tem GPU Adreno: drivers Turnip não se aplicam. "
                    + "O jogo usa o driver do sistema.");
            return;
        }
        java.util.Set<String> bad = GpuDrivers.badPackages(this);
        final java.util.List<DriverCatalog.Candidate> plan = new java.util.ArrayList<>(check.plan(g, bad));
        boolean outsideRules = false;
        if (plan.isEmpty()) {
            plan.addAll(check.alternatives(g, bad));
            outsideRules = !plan.isEmpty();
        }
        StringBuilder m = new StringBuilder();
        m.append("Aparelho: ").append(g.summary()).append("\n");
        m.append("Regras: ").append(check.rulesFromGitHub
                ? "auto-drivers.json de github.com/Droid-Deck/Drivers-CI" : "tabela interna (regras do GitHub ilegíveis)");
        m.append(" → ").append(android.text.TextUtils.join(", ", check.preferences(g))).append("\n");
        if (!check.failedSources.isEmpty())
            m.append("Sem resposta de: ").append(android.text.TextUtils.join(", ", check.failedSources)).append("\n");
        if (plan.isEmpty()) {
            showStyledMessage("Driver recomendado", m + "\nNenhum pacote disponível para este aparelho"
                    + (bad.isEmpty() ? "." : " além dos que já falharam aqui.")
                    + " Importe um .zip, ou use o driver do sistema.");
            return;
        }
        if (outsideRules)
            m.append("\nOs pacotes recomendados já falharam aqui. Fora da regra, para esta família de GPU:\n");
        m.append("\nVai baixar:\n").append(plan.get(0).details());
        if (plan.size() > 1) {
            m.append("\n\nSe o download falhar, depois: ");
            for (int i = 1; i < plan.size(); i++) m.append(i > 1 ? ", " : "").append(plan.get(i).title());
        }
        m.append("\n\nO driver fica em teste até o jogo mostrar as primeiras imagens; se não iniciar, o app volta "
                + "atrás e propõe o próximo.");
        showStyledConfirm("Driver recomendado", m.toString(), "Baixar e usar", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        downloadPlan(plan, 0, new StringBuilder());
                    }
                }, "Cancelar");
    }

    private void downloadPlan(final java.util.List<DriverCatalog.Candidate> plan, final int i, final StringBuilder errors) {
        if (i >= plan.size()) {
            driverBusy = false;
            if (refreshDrivers != null) refreshDrivers.run();
            showStyledMessage("Download do driver falhou", errors + "\nNada foi alterado: o driver em uso continua o mesmo.");
            return;
        }
        driverBusy = true;
        final DriverCatalog.Candidate c = plan.get(i);
        if (drvStatus != null) drvStatus.setText("Baixando " + c.title() + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                GpuDrivers.ImportResult r = null;
                String err = null;
                // Already installed (same package): no download.
                for (GpuDrivers.Driver d : GpuDrivers.listDeduped(LetsGoLauncherActivity.this))
                    if (c.sha256.equals(d.packageSha256())) r = new GpuDrivers.ImportResult(d, null);
                if (r == null) {
                    java.io.File zip = null;
                    try {
                        zip = DriverCatalog.download(LetsGoLauncherActivity.this, c, new DriverCatalog.Progress() {
                            long shown = -1;
                            @Override public void update(final long done, final long total) {
                                final long pct = total > 0 ? 100 * done / total : -1;
                                if (pct == shown) return;
                                shown = pct;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (drvStatus != null) drvStatus.setText("Baixando " + c.title() + (pct >= 0 ? " " + pct + "%" : ""));
                                    }
                                });
                            }
                        });
                        org.json.JSONObject src = new org.json.JSONObject()
                                .put("key", c.key).put("repo", c.repo).put("tag", c.tag).put("asset", c.asset)
                                .put("url", c.url).put("sha256", c.sha256).put("publishedAt", c.publishedAt);
                        r = GpuDrivers.installZip(LetsGoLauncherActivity.this, zip, src);
                    } catch (Exception e) {
                        err = e.getMessage();
                    } finally {
                        if (zip != null) zip.delete();
                    }
                }
                if (r != null && r.error != null) { err = r.error; r = null; }
                final GpuDrivers.ImportResult fr = r;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (fr == null) {
                            android.util.Log.w("lasrecomp", "[gpu] " + c.asset + ": " + fe);
                            errors.append(c.title()).append(": ").append(fe).append("\n");
                            if (i + 1 < plan.size())
                                android.widget.Toast.makeText(LetsGoLauncherActivity.this, c.title() + " falhou ("
                                        + fe + "); tentando " + plan.get(i + 1).title(),
                                        android.widget.Toast.LENGTH_LONG).show();
                            downloadPlan(plan, i + 1, errors);
                            return;
                        }
                        driverBusy = false;
                        GpuDrivers.select(LetsGoLauncherActivity.this, fr.driver);
                        if (refreshDrivers != null) refreshDrivers.run();
                        android.widget.Toast.makeText(LetsGoLauncherActivity.this, "Driver instalado e verificado "
                                + "(sha256 conferido): " + fr.driver.name
                                + (fr.driver.started ? "" : ". Será testado no próximo Jogar."),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "gpu-download").start();
    }

    private void showTrialFailure(GpuDrivers.TrialOutcome t) {
        String now = t.restored != null
                ? "Voltei para " + t.restored.name + ", que já funcionou neste aparelho."
                : "Nenhum driver Turnip está selecionado agora: o próximo Jogar usaria o driver do sistema.";
        showStyledConfirm("O driver não iniciou o jogo", t.driver.name + " (" + t.driver.origin() + ") não chegou às primeiras imagens do jogo "
                        + "neste aparelho.\n\nMotivo: " + t.reason + "\n\n" + now
                        + "\n\nEste pacote não será mais recomendado aqui. Você pode baixar o próximo da lista.",
                "Baixar o próximo", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        recommendDriver(true);
                    }
                }, "Fechar");
    }

    /** Details dialog: what the install actually has. */
    private void showDetails() {
        String dir = gameDir(this);
        File base = dir != null ? new File(dir) : getFilesDir();
        String found = "encontrado", missing = "ausente";
        StringBuilder sb = new StringBuilder();
        sb.append("Pasta dos dados do jogo:\n").append(base.getAbsolutePath()).append("\n\n");
        sb.append("extracted/exefs/main: ")
                .append(new File(base, "extracted/exefs/main").exists() ? found : missing).append('\n');
        sb.append("extracted/romfs.bin: ")
                .append(new File(base, "extracted/romfs.bin").exists() ? found : missing).append('\n');
        sb.append("extracted/.import_ok: ")
                .append(new File(base, "extracted/.import_ok").exists() ? found : missing).append('\n');
        sb.append("liblasgame.so: ")
                .append(new File(getApplicationInfo().nativeLibraryDir, "liblasgame.so").exists() ? found : missing)
                .append('\n');
        showStyledMessage("Detalhes", sb.toString());
    }
}
