package com.letsgorecomp;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Custom GPU drivers (Turnip) for the Driver tab. Each driver is a folder under the app-private files dir
 * (never on shared storage: dlopen refuses libraries other apps could tamper with):
 *   <files>/gpu_drivers/<slug>/{meta.json, <libraryName>.so, source.json, .started, .start_failed}
 * The launcher writes the choice to the letsgo prefs (LARECOMP_GPU_DRIVER=&lt;slug&gt;,
 * LARECOMP_GPU_DRIVER_LIB=&lt;.so&gt;); the runtime loads it through adrenotools. No choice = the system driver.
 *
 * Start trial (fork): a driver that has never brought the game to its first frames on this phone is "on trial".
 * The runtime writes .started after 120 presented frames, or .start_failed (with the reason) when it refuses the
 * driver. Back in the launcher, a trial whose process died without .started, or that left .start_failed, is rolled
 * back: the driver that last worked here is selected again (or none, said so on screen) and the package is not
 * recommended again.
 */
final class GpuDrivers {
    static final String KEY_DRIVER = "LARECOMP_GPU_DRIVER";
    static final String KEY_LIB = "LARECOMP_GPU_DRIVER_LIB";
    static final String BUNDLED_ASSET = "turnip-8g2.zip";
    private static final String TRIAL = "gpu_trial";
    private static final String KEY_LAST_GOOD = "gpu_last_good";
    private static final String KEY_BAD = "gpu_bad_sha256";
    static final String KEY_LAST_LAUNCH = "gpu_last_launch";
    static final String STARTED = ".started";
    static final String START_FAILED = ".start_failed";
    private static final long MAX_ZIP = 64L << 20;

    static final class Driver {
        final String slug;  // folder name under gpu_drivers/
        final String name;
        final String description;
        final String driverVersion;
        final String libraryName;
        final JSONObject source;  // provenance of a downloaded package (null: imported by hand)
        final boolean started;    // has brought the game to its first frames on this phone
        final boolean failed;     // was rolled back: never started here

        Driver(String slug, String name, String description, String driverVersion, String libraryName,
               JSONObject source, boolean started, boolean failed) {
            this.slug = slug;
            this.name = name;
            this.description = description;
            this.driverVersion = driverVersion;
            this.libraryName = libraryName;
            this.source = source;
            this.started = started;
            this.failed = failed;
        }

        String packageSha256() { return source == null ? "" : source.optString("sha256", ""); }

        String origin() {
            if (source == null) return "importado à mão";
            return "github.com/" + source.optString("repo") + " " + source.optString("tag");
        }
    }

    /** Result of an import: either a driver or a human-readable error. */
    static final class ImportResult {
        final Driver driver;
        final String error;

        ImportResult(Driver driver, String error) {
            this.driver = driver;
            this.error = error;
        }
    }

    /** What the launcher has to say about the last start with a driver on trial. */
    static final class TrialOutcome {
        final Driver driver;      // the driver that was on trial
        final boolean ok;
        final String reason;      // why it is considered failed (null when ok)
        final Driver restored;    // selected again after a failure; null = no custom driver selected now

        TrialOutcome(Driver driver, boolean ok, String reason, Driver restored) {
            this.driver = driver;
            this.ok = ok;
            this.reason = reason;
            this.restored = restored;
        }
    }

    private GpuDrivers() {
    }

    static File driversDir(Context c) {
        return new File(c.getFilesDir(), "gpu_drivers");
    }

    /** All installed drivers whose .so is present, in no particular order. */
    static List<Driver> list(Context c) {
        return list(driversDir(c));
    }

    /** The installed drivers, one per package: folders holding the same library (same sha256) are shown once.
     *  Earlier versions installed the same package again under a new folder (turnip-gen8-v38, -2, -3...) because
     *  they kept no library hash; the copy kept is the selected one, else the one that already started the game. */
    static List<Driver> listDeduped(Context c) {
        List<Driver> all = list(driversDir(c));
        String sel = c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).getString(KEY_DRIVER, null);
        java.util.Map<String, Driver> bySha = new java.util.LinkedHashMap<>();
        for (Driver d : all) {
            String k = libSha(new File(driversDir(c), d.slug), d.libraryName);
            if (k.isEmpty()) k = "slug:" + d.slug;
            Driver kept = bySha.get(k);
            if (kept == null || better(d, kept, sel)) bySha.put(k, d);
        }
        if (bySha.size() != all.size())
            Log.i("lasrecomp", "[gpu] " + all.size() + " driver folders, " + bySha.size() + " distinct packages");
        return new ArrayList<>(bySha.values());
    }

    private static boolean better(Driver d, Driver kept, String sel) {
        if (d.slug.equals(sel) != kept.slug.equals(sel)) return d.slug.equals(sel);
        if (d.started != kept.started) return d.started;
        return d.slug.length() < kept.slug.length() || d.slug.length() == kept.slug.length() && d.slug.compareTo(kept.slug) < 0;
    }

    /** sha256 of a driver folder's library: .libsha256, written here once when an older install did not. */
    static String libSha(File dir, String libraryName) {
        File f = new File(dir, ".libsha256");
        String h = readText(f).trim();
        if (!h.isEmpty()) return h;
        try (InputStream in = new FileInputStream(new File(dir, libraryName))) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) md.update(b, 0, r);
            h = DriverCatalog.hex(md.digest());
            try { write(f, h.getBytes("UTF-8")); } catch (IOException ignored) { }
            return h;
        } catch (Exception e) {
            return "";
        }
    }

    static List<Driver> list(File driversDir) {
        List<Driver> out = new ArrayList<>();
        File[] dirs = driversDir.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            Driver drv = read(d);
            if (drv != null) out.add(drv);
        }
        return out;
    }

    static Driver bySlug(Context c, String slug) {
        if (slug == null || slug.isEmpty() || slug.contains("/")) return null;
        return read(new File(driversDir(c), slug));
    }

    /** The selected driver, or null for the system driver (also null when the selection points nowhere). */
    static Driver selected(Context c) {
        String slug = c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).getString(KEY_DRIVER, null);
        if (slug == null || slug.isEmpty()) return null;
        Driver d = read(new File(driversDir(c), slug));
        if (d == null) return null;  // stale selection: the folder is gone
        String lib = c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).getString(KEY_LIB, null);
        if (!d.libraryName.equals(lib)) return null;
        return d;
    }

    /** Select a driver, or null for the system driver. Writes to the letsgo prefs. */
    static void select(Context c, Driver d) {
        android.content.SharedPreferences.Editor e =
                c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).edit();
        if (d == null) {
            e.remove(KEY_DRIVER);
            e.remove(KEY_LIB);
        } else {
            e.putString(KEY_DRIVER, d.slug);
            e.putString(KEY_LIB, d.libraryName);
        }
        e.apply();
    }

    static void delete(Context c, Driver d) {
        deleteTree(new File(driversDir(c), d.slug));
        Driver sel = selected(c);
        if (sel != null && sel.slug.equals(d.slug)) select(c, null);  // was in use: back to the system driver
    }

    /** What the game was really handed at its last start (LetsGoActivity): the driver's library path, or "" for the
     *  system driver. The runtime loads exactly that file or stops (device.c load_gipa), never another one. */
    static void recordLaunch(Context c, String soPath) {
        c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_LAUNCH, soPath == null ? "" : soPath).commit();
    }

    static String lastLaunch(Context c) {
        return c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).getString(KEY_LAST_LAUNCH, null);
    }

    /** sha256 of the packages that failed to start on this phone (not recommended again). */
    static Set<String> badPackages(Context c) {
        return new HashSet<>(c.getSharedPreferences("letsgo", Context.MODE_PRIVATE)
                .getStringSet(KEY_BAD, new HashSet<String>()));
    }

    // ---- start trial ----------------------------------------------------------------------------------------------

    /**
     * Called by the game activity before the native runtime loads. A driver that has not started here yet is put on
     * trial (synchronously written: a crash in the driver must not lose it).
     */
    static void beginLaunch(Context c) {
        Driver d = selected(c);
        android.content.SharedPreferences.Editor e =
                c.getSharedPreferences(TRIAL, Context.MODE_PRIVATE).edit().clear();
        if (d != null && !d.started) {
            new File(new File(driversDir(c), d.slug), START_FAILED).delete();
            e.putString("slug", d.slug).putInt("pid", Process.myPid()).putLong("at", System.currentTimeMillis());
            Log.i("lasrecomp", "[gpu] driver on trial: " + d.slug);
        } else if (d != null) {
            c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).edit().putString(KEY_LAST_GOOD, d.slug).apply();
        }
        e.commit();
    }

    /** Called when the game activity is destroyed normally: a trial without verdict (left early) is dropped. */
    static void gameClosed(Context c) {
        android.content.SharedPreferences t = c.getSharedPreferences(TRIAL, Context.MODE_PRIVATE);
        String slug = t.getString("slug", null);
        if (slug == null || t.getInt("pid", -1) != Process.myPid()) return;
        File dir = new File(driversDir(c), slug);
        if (!new File(dir, STARTED).exists() && !new File(dir, START_FAILED).exists()) t.edit().clear().commit();
    }

    /**
     * The verdict on the driver on trial, if there is one: null while there is none (or the game is still running).
     * A failure is rolled back here.
     */
    static TrialOutcome checkTrial(Context c, boolean gameRunning) {
        android.content.SharedPreferences t = c.getSharedPreferences(TRIAL, Context.MODE_PRIVATE);
        String slug = t.getString("slug", null);
        if (slug == null) return null;
        File dir = new File(driversDir(c), slug);
        Driver d = read(dir);
        if (d == null) { t.edit().clear().commit(); return null; }
        if (d.started) {
            t.edit().clear().commit();
            c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).edit().putString(KEY_LAST_GOOD, slug).apply();
            Log.i("lasrecomp", "[gpu] driver started the game: " + slug);
            return new TrialOutcome(d, true, null, null);
        }
        String reason = null;
        File failed = new File(dir, START_FAILED);
        if (failed.exists()) {
            reason = readText(failed).trim();
            if (reason.isEmpty()) reason = "o jogo recusou o driver";
        } else if (t.getInt("pid", -1) != Process.myPid()) {
            reason = "o app fechou antes de o jogo mostrar as primeiras imagens com este driver";
        } else if (gameRunning) {
            return null;  // still starting
        } else {
            return null;  // same process, no verdict yet (gameClosed drops it when the game is left early)
        }
        return rollBack(c, d, reason);
    }

    private static TrialOutcome rollBack(Context c, Driver d, String reason) {
        c.getSharedPreferences(TRIAL, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            write(new File(new File(driversDir(c), d.slug), START_FAILED), reason.getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
        String sha = d.packageSha256();
        if (!sha.isEmpty()) {
            Set<String> bad = badPackages(c);
            bad.add(sha);
            c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).edit().putStringSet(KEY_BAD, bad).commit();
        }
        Driver back = bySlug(c, c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).getString(KEY_LAST_GOOD, null));
        if (back != null && (!back.started || back.slug.equals(d.slug))) back = null;
        Driver sel = selected(c);
        if (sel == null || sel.slug.equals(d.slug)) select(c, back);
        Log.w("lasrecomp", "[gpu] driver " + d.slug + " rolled back (" + reason + "); now: "
                + (back != null ? back.slug : "no custom driver"));
        return new TrialOutcome(read(new File(driversDir(c), d.slug)), false, reason, back);
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /**
     * Imports a driver .zip (from the file picker): validates meta.json (parseable), the libraryName .so present in
     * the zip, and minApi <= this device's SDK; then extracts just those two files to gpu_drivers/&lt;slug&gt;/.
     */
    static ImportResult importZip(Context c, Uri uri) {
        byte[] metaBytes = null;
        byte[] soBytes = null;
        String soName = null;
        try (InputStream in = openInput(c, uri);
             ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = base(e.getName());
                if (n == null) continue;  // nested paths are not a driver layout
                if (n.equals("meta.json") && metaBytes == null) metaBytes = readAll(z);
                // the .so name is only known after meta.json is parsed; keep reading
            }
        } catch (IOException ex) {
            return new ImportResult(null, "Não deu para ler o zip: " + ex.getMessage());
        }
        if (metaBytes == null)
            return new ImportResult(null, "O zip não tem meta.json");
        JSONObject meta;
        try {
            meta = new JSONObject(new String(metaBytes, "UTF-8"));
        } catch (Exception ex) {
            return new ImportResult(null, "meta.json não é um JSON válido");
        }
        String libraryName = meta.optString("libraryName", "");
        if (libraryName.isEmpty() || libraryName.contains("/"))
            return new ImportResult(null, "meta.json não tem libraryName utilizável");
        int minApi = meta.optInt("minApi", 0);
        if (minApi > Build.VERSION.SDK_INT)
            return new ImportResult(null, String.format(Locale.US,
                    "O driver precisa da API %d do Android; este aparelho é API %d",
                    minApi, Build.VERSION.SDK_INT));
        // Second pass: pull the .so (ZipInputStream cannot rewind).
        try (InputStream in = openInput(c, uri);
             ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = base(e.getName());
                if (libraryName.equals(n)) {
                    soBytes = readAll(z);
                    soName = n;
                    break;
                }
            }
        } catch (IOException ex) {
            return new ImportResult(null, "Não deu para ler o zip: " + ex.getMessage());
        }
        if (soBytes == null || soBytes.length < 1024)
            return new ImportResult(null, String.format(Locale.US,
                    "O zip não tem %s", libraryName));
        String name = meta.optString("name", libraryName);
        String slug = slug(name);
        File dir = new File(driversDir(c), slug);
        int dup = 1;
        while (dir.isDirectory()) dir = new File(driversDir(c), slug + "-" + (++dup));
        dir.mkdirs();
        try {
            write(new File(dir, "meta.json"), metaBytes);
            write(new File(dir, soName), soBytes);
        } catch (IOException ex) {
            deleteTree(dir);
            return new ImportResult(null, "Não deu para instalar o driver: " + ex.getMessage());
        }
        Driver d = read(dir);
        if (d == null) {
            deleteTree(dir);
            return new ImportResult(null, "O driver não pôde ser lido de volta");
        }
        return new ImportResult(d, null);
    }

    /** Open a driver zip: a downloaded file (file://) straight from disk, anything else via the resolver. */
    private static InputStream openInput(Context c, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return new java.io.FileInputStream(new File(uri.getPath()));
        return c.getContentResolver().openInputStream(uri);
    }

    /**
     * First run: unpack the Turnip driver bundled in assets/ to gpu_drivers/ and pre-select it when nothing is
     * selected yet. Never replaces or re-selects: an import or a choice the player made wins.
     */
    static void installBundled(Context c) {
        byte[] metaBytes;
        try (InputStream in = c.getAssets().open(BUNDLED_ASSET);
             ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            metaBytes = null;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory() && "meta.json".equals(base(e.getName()))) {
                    metaBytes = readAll(z);
                    break;
                }
            }
        } catch (IOException ex) {
            return;
        }
        if (metaBytes == null) return;
        String libraryName;
        try {
            JSONObject meta = new JSONObject(new String(metaBytes, "UTF-8"));
            libraryName = meta.optString("libraryName", "");
        } catch (Exception ex) {
            return;
        }
        if (libraryName.isEmpty()) return;
        File dir = new File(driversDir(c), slug(BUNDLED_ASSET.replace(".zip", "")));
        if (!new File(dir, libraryName).isFile()) {
            ImportResult r = importAsset(c, BUNDLED_ASSET, dir);
            if (r.error != null) {
                return;
            }
        }
        if (c.getSharedPreferences("letsgo", Context.MODE_PRIVATE).getString(KEY_DRIVER, null) == null) {
            Driver d = read(dir);
            if (d != null) {
                select(c, d);
            }
        }
    }

    /** Unpacks an asset zip (meta.json + its libraryName .so) into dir. */
    private static ImportResult importAsset(Context c, String asset, File dir) {
        byte[] metaBytes = null;
        String libraryName = null;
        try (InputStream in = c.getAssets().open(asset);
             ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory() && "meta.json".equals(base(e.getName()))) {
                    metaBytes = readAll(z);
                    break;
                }
            }
        } catch (IOException ex) {
            return new ImportResult(null, ex.getMessage());
        }
        if (metaBytes == null) return new ImportResult(null, "no meta.json");
        try {
            libraryName = new JSONObject(new String(metaBytes, "UTF-8")).optString("libraryName", "");
        } catch (Exception ex) {
            return new ImportResult(null, "bad meta.json");
        }
        if (libraryName.isEmpty()) return new ImportResult(null, "no libraryName");
        byte[] soBytes = null;
        try (InputStream in = c.getAssets().open(asset);
             ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory() && libraryName.equals(base(e.getName()))) {
                    soBytes = readAll(z);
                    break;
                }
            }
        } catch (IOException ex) {
            return new ImportResult(null, ex.getMessage());
        }
        if (soBytes == null) return new ImportResult(null, "no " + libraryName);
        dir.mkdirs();
        try {
            write(new File(dir, "meta.json"), metaBytes);
            write(new File(dir, libraryName), soBytes);
        } catch (IOException ex) {
            deleteTree(dir);
            return new ImportResult(null, ex.getMessage());
        }
        return new ImportResult(read(dir), null);
    }

    /** Reads an installed driver folder; null when it is not a valid driver. */
    private static Driver read(File dir) {
        File meta = new File(dir, "meta.json");
        if (!meta.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(meta)) {
            byte[] b = readAll(in);
            JSONObject j = new JSONObject(new String(b, "UTF-8"));
            String libraryName = j.optString("libraryName", "");
            if (libraryName.isEmpty() || !new File(dir, libraryName).isFile()) return null;
            JSONObject src = null;
            File s = new File(dir, "source.json");
            if (s.isFile()) try { src = new JSONObject(readText(s)); } catch (Exception ignored) { }
            return new Driver(dir.getName(), j.optString("name", dir.getName()),
                    j.optString("description", ""), j.optString("driverVersion", ""), libraryName, src,
                    new File(dir, STARTED).exists(), new File(dir, START_FAILED).exists());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Installs a driver package: a meta.json (with libraryName and minApi) and that library, either at the zip's top
     * level (AdrenoTools layout) or under android/ (DroidDeck bundle; its linux/ half is ignored). When the zip carries
     * a manifest.json with the Android library's sha256, the library must match it.
     */
    static ImportResult installZip(Context c, File zip, JSONObject source) {
        return installZip(driversDir(c), zip, source);
    }

    static ImportResult installZip(File driversDir, File zip, JSONObject source) {
        try (ZipFile z = new ZipFile(zip)) {
            String prefix = null;
            if (z.getEntry("android/meta.json") != null) {
                prefix = "android/";
            } else {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    String n = en.nextElement().getName();
                    if (n.startsWith("linux/") || n.contains("..") || !(n.equals("meta.json") || n.endsWith("/meta.json")))
                        continue;
                    String p = n.substring(0, n.length() - "meta.json".length());
                    if (prefix == null || p.length() < prefix.length()) prefix = p;
                }
            }
            if (prefix == null) return new ImportResult(null, "O zip não tem meta.json");
            JSONObject meta;
            byte[] metaBytes = readEntry(z, prefix + "meta.json", 1 << 16);
            try {
                meta = new JSONObject(new String(metaBytes, "UTF-8"));
            } catch (Exception ex) {
                return new ImportResult(null, "meta.json não é um JSON válido");
            }
            String libraryName = meta.optString("libraryName", "");
            if (libraryName.isEmpty() || libraryName.contains("/") || libraryName.startsWith("."))
                return new ImportResult(null, "meta.json não tem libraryName utilizável");
            int minApi = meta.optInt("minApi", 0);
            if (minApi > Build.VERSION.SDK_INT)
                return new ImportResult(null, String.format(Locale.US,
                        "O driver precisa da API %d do Android; este aparelho é API %d", minApi, Build.VERSION.SDK_INT));
            if (z.getEntry(prefix + libraryName) == null)
                return new ImportResult(null, "O zip não tem " + prefix + libraryName);
            byte[] so = readEntry(z, prefix + libraryName, (int) MAX_ZIP);
            if (so.length < 1024 || so[0] != 0x7f || so[1] != 'E' || so[2] != 'L' || so[3] != 'F')
                return new ImportResult(null, libraryName + " não é uma biblioteca ELF");
            String libSha = DriverCatalog.hex(MessageDigest.getInstance("SHA-256").digest(so));
            ZipEntry man = z.getEntry("manifest.json");
            if (man != null) {
                JSONObject m = new JSONObject(new String(readEntry(z, "manifest.json", 1 << 16), "UTF-8"));
                JSONObject a = m.optJSONObject("drivers") == null ? null : m.optJSONObject("drivers").optJSONObject("android");
                if (a != null && (prefix + libraryName).equals(a.optString("library"))) {
                    if (!libSha.equalsIgnoreCase(a.optString("sha256")))
                        return new ImportResult(null, "A biblioteca não confere com o sha256 do manifest.json do pacote");
                } else if (prefix.equals("android/")) {
                    return new ImportResult(null, "manifest.json do pacote não descreve " + prefix + libraryName);
                }
            }
            // The same library already installed: reuse it (its trial history stays).
            for (Driver d : list(driversDir)) {
                if (d.source != null && libSha.equals(d.source.optString("libSha256"))
                        || libSha.equals(libSha(new File(driversDir, d.slug), d.libraryName)))
                    return new ImportResult(d, null);
            }
            String slug = slug(meta.optString("name", libraryName));
            File dir = new File(driversDir, slug);
            int dup = 1;
            while (dir.exists()) dir = new File(driversDir, slug + "-" + (++dup));
            dir.mkdirs();
            try {
                write(new File(dir, "meta.json"), metaBytes);
                write(new File(dir, libraryName), so);
                write(new File(dir, ".libsha256"), libSha.getBytes("UTF-8"));
                if (source != null) {
                    source.put("libSha256", libSha).put("installedAt", System.currentTimeMillis());
                    write(new File(dir, "source.json"), source.toString(2).getBytes("UTF-8"));
                }
            } catch (Exception ex) {
                deleteTree(dir);
                return new ImportResult(null, "Não deu para instalar o driver: " + ex.getMessage());
            }
            Driver d = read(dir);
            if (d == null) {
                deleteTree(dir);
                return new ImportResult(null, "O driver não pôde ser lido de volta");
            }
            Log.i("lasrecomp", "[gpu] driver installed: " + d.slug + " (" + d.libraryName + ", sha256 " + libSha
                    + ", " + d.origin() + ")");
            return new ImportResult(d, null);
        } catch (Exception ex) {
            return new ImportResult(null, "Não deu para ler o zip: " + ex.getMessage());
        }
    }

    private static byte[] readEntry(ZipFile z, String name, int max) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) throw new IOException(name + " ausente");
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) {
                o.write(b, 0, r);
                if (o.size() > max) throw new IOException(name + " grande demais");
            }
            return o.toByteArray();
        }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return new String(o.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** Simple file name of a zip entry; null for nested paths (not a driver layout). */
    private static String base(String entry) {
        if (entry == null || entry.isEmpty() || entry.endsWith("/")) return null;
        int s = entry.lastIndexOf('/');
        String n = s < 0 ? entry : entry.substring(s + 1);
        return n.isEmpty() || n.startsWith(".") ? null : n;
    }

    /** Folder-safe slug from a display name. */
    private static String slug(String name) {
        String s = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "driver" : (s.length() > 40 ? s.substring(0, 40) : s);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int r;
        while ((r = in.read(b)) > 0) o.write(b, 0, r);
        return o.toByteArray();
    }

    private static void write(File f, byte[] b) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(b);
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException(f.getName());
        }
    }
}
