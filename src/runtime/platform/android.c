// Android host layer (runtime/platform.h). aarch64.
// Based on runtime/platform/linux.c, adapted for Android:
//  - stderr/stdout go through a pipe: every line lands in logcat AND in lasrecomp.log
//    (logcat is the only realtime console on a phone; the file keeps the crash-report flow)
//  - no re-exec: plat_exec ends the process with a message (the guest range is claimed once,
//    at a fixed address, in a constructor that runs before SDL's thread)
//  - backtrace via <unwind.h> (bionic has no execinfo.h)
//  - the data root comes from LARECOMP_DATA_ROOT, set by LetsGoActivity before SDL starts;
//    the game library path from LARECOMP_GAME_LIB (see the JNI setup below)
#define _GNU_SOURCE
#include <SDL3/SDL.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <jni.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/ucontext.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#include "../lifecycle.h"
#include "../platform.h"
#include "../paths.h"   // RT_APP_NAME
#include "../rt.h"      // LOG
#include "../settings.h"  // g_settings (nativeGetShowFps)
extern char **environ;

#define LOGCAT_TAG "lasrecomp"

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

#ifdef LETSGO_ADRENOTOOLS
// The ANativeWindow for vkCreateAndroidSurfaceKHR, acquired via JNI in nativeSetup.
// (SDL3 removed SDL_syswm.h, so we can't get it from the SDL_Window.)
ANativeWindow *g_letsgo_native_window = NULL;
// JavaVM for attaching JNI when we need the ANativeWindow lazily (surface isn't
// ready during nativeSetup, which runs before SDLActivity.onCreate).
JavaVM *g_letsgo_jvm = NULL;

// Get the ANativeWindow from SDLActivity.mSurface via JNI. Called lazily from the
// Vulkan init (after the Activity and Surface are fully created).
ANativeWindow *letsgo_get_native_window(void) {
    if (g_letsgo_native_window) return g_letsgo_native_window;
    if (!g_letsgo_jvm) return NULL;
    JNIEnv *env = NULL;
    if ((*g_letsgo_jvm)->AttachCurrentThread(g_letsgo_jvm, &env, NULL) != 0) return NULL;
    ANativeWindow *win = NULL;
    jclass sdlActivity = (*env)->FindClass(env, "org/libsdl/app/SDLActivity");
    if (sdlActivity) {
        jfieldID fid = (*env)->GetStaticFieldID(env, sdlActivity, "mSurface", "Lorg/libsdl/app/SDLSurface;");
        if (fid) {
            jobject surfaceView = (*env)->GetStaticObjectField(env, sdlActivity, fid);
            if (surfaceView) {
                jclass svClass = (*env)->GetObjectClass(env, surfaceView);
                jmethodID getHolder = (*env)->GetMethodID(env, svClass, "getHolder", "()Landroid/view/SurfaceHolder;");
                if (getHolder) {
                    jobject holder = (*env)->CallObjectMethod(env, surfaceView, getHolder);
                    if (holder) {
                        jclass hClass = (*env)->GetObjectClass(env, holder);
                        jmethodID getSurface = (*env)->GetMethodID(env, hClass, "getSurface", "()Landroid/view/Surface;");
                        if (getSurface) {
                            jobject surface = (*env)->CallObjectMethod(env, holder, getSurface);
                            if (surface) {
                                win = ANativeWindow_fromSurface(env, surface);
                                (*env)->DeleteLocalRef(env, surface);
                            }
                        }
                        (*env)->DeleteLocalRef(env, holder);
                    }
                }
                (*env)->DeleteLocalRef(env, surfaceView);
            }
        }
        (*env)->DeleteLocalRef(env, sdlActivity);
    }
    // Don't detach: the Vulkan thread stays attached for the process lifetime.
    if (win) g_letsgo_native_window = win;
    return win;
}
#endif

// Set by the Java activity (nativeSetup) before SDL starts its thread.
static char g_lib_dir[1024];  // getApplicationInfo().nativeLibraryDir

// The LetsGoActivity class, saved as a global ref in nativeSetup (called from
// the Java UI thread, where FindClass works). A native thread attached via
// AttachCurrentThread cannot FindClass an app class: its class loader is the
// system one, so FindClass("com/letsgorecomp/LetsGoActivity") fails and the
// native name dialog never opened in v0006/v0007 (silent fallback to ImGui).
static jclass g_letsgo_activity_class = NULL;

// ---- JNI: called from LetsGoActivity.onCreate, before super.onCreate (which starts SDL)
JNIEXPORT void JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeSetup(JNIEnv *env, jclass cls,
                                                                        jstring files_dir, jstring lib_dir) {
    if (cls && !g_letsgo_activity_class) g_letsgo_activity_class = (*env)->NewGlobalRef(env, cls);
#ifdef LETSGO_ADRENOTOOLS
    (*env)->GetJavaVM(env, &g_letsgo_jvm);
#endif
    const char *fd = (*env)->GetStringUTFChars(env, files_dir, NULL);
    const char *ld = (*env)->GetStringUTFChars(env, lib_dir, NULL);
    if (fd) {
        setenv("LARECOMP_DATA_ROOT", fd, 1);
        // The ImGui launcher scans LARECOMP_SETUP_DIR for exefs/romfs at startup;
        // without it the user must pick the folder again in the PC-style file dialog
        // (which returns a content URI that find_game cannot read).
        setenv("LARECOMP_SETUP_DIR", fd, 1);
        // Skip the PC-style ImGui launcher on Android: the Android launcher
        // (LetsGoLauncherActivity) owns the game folder and settings UI.
        // The game boots straight in using larecomp.ini (or defaults).
        setenv("LARECOMP_NO_LAUNCHER", "1", 1);
        // Resolution scale is now user-selectable via the launcher (LARECOMP_SCALE
        // set by LetsGoActivity from prefs). Default 0.6667 (720p) if not set.
        // (Removed forced setenv to allow 0.5x/0.75x/1x/1.5x options.)
        // Force landscape orientation for SDL: the game must create its window
        // in landscape mode, not portrait. This hint is read by SDLActivity
        // when the native code calls setOrientation().
        SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    }
    if (ld) {
        snprintf(g_lib_dir, sizeof g_lib_dir, "%s", ld);
        char game_lib[1100];
        snprintf(game_lib, sizeof game_lib, "%s/liblasgame.so", ld);
        setenv("LARECOMP_GAME_LIB", game_lib, 1);
        __android_log_print(ANDROID_LOG_INFO, LOGCAT_TAG, "data: %s lib: %s", fd ? fd : "?",
                            ld ? ld : "?");
    }
    if (fd) (*env)->ReleaseStringUTFChars(env, files_dir, fd);
    if (ld) (*env)->ReleaseStringUTFChars(env, lib_dir, ld);
}

// ---- guest address space (same as Linux: fixed window, lazily backed)
bool plat_reserve_guest(uint64_t lo, uint64_t hi) {
    __android_log_print(ANDROID_LOG_INFO, LOGCAT_TAG,
                        "reserving guest range %llx-%llx (%llu MB)",
                        (unsigned long long)lo, (unsigned long long)hi,
                        (unsigned long long)(hi - lo) >> 20);
    void *p = mmap((void *)(uintptr_t)lo, hi - lo, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
    if (p == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG,
                            "guest mmap failed: %s", strerror(errno));
        // v0002-diag (Pikachu): list every mapping that overlaps the guest range,
        // so the log shows WHAT is already sitting at 0x7000... instead of only
        // that the reservation failed. Same logcat tag, so the launcher's
        // auto-exported log captures it. Bounded: 50 lines max.
        {
            FILE *maps = fopen("/proc/self/maps", "r");
            if (maps) {
                char line[1024];
                int n = 0;
                while (fgets(line, sizeof line, maps) && n < 50) {
                    unsigned long long s = 0, e = 0;
                    if (sscanf(line, "%llx-%llx", &s, &e) != 2) continue;
                    if (e <= lo || s >= hi) continue;
                    line[strcspn(line, "\n")] = 0;
                    __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG,
                                        "guest mmap occupant: %s", line);
                    n++;
                }
                if (n == 0)
                    __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG,
                                        "guest mmap occupant: none in /proc/self/maps");
                fclose(maps);
            } else {
                __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG,
                                    "guest mmap occupant: cannot open /proc/self/maps: %s",
                                    strerror(errno));
            }
        }
        // v0003-sonda: probe candidate bases for a future move of the guest
        // range. Same size as the current range, MAP_FIXED_NOREPLACE (safe:
        // it never overwrites an existing mapping), munmap immediately on
        // success. This only REPORTS; the game still does not run on a new
        // base — that needs a regen + gamelib rebuild with shifted bases.
        // Constraint from rt.h: guest addresses must fit in 39 bits, so every
        // candidate top stays below 0x8000000000.
        {
            static const uint64_t cand[] = {
                0x0800000000ull, 0x1000000000ull, 0x2000000000ull, 0x3000000000ull,
                0x4000000000ull, 0x5000000000ull, 0x6000000000ull, 0x7400000000ull,
                0x7600000000ull, 0x7800000000ull, 0x7a000000000ull, 0x7c000000000ull,
            };
            for (size_t i = 0; i < sizeof cand / sizeof cand[0]; i++) {
                void *q = mmap((void *)(uintptr_t)cand[i], hi - lo, PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
                if (q == MAP_FAILED) {
                    __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG,
                                        "guest probe BUSY: %llx-%llx (%s)", (unsigned long long)cand[i],
                                        (unsigned long long)(cand[i] + (hi - lo)), strerror(errno));
                } else {
                    __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG,
                                        "guest probe FREE: %llx-%llx", (unsigned long long)cand[i],
                                        (unsigned long long)(cand[i] + (hi - lo)));
                    munmap(q, hi - lo);
                }
            }
        }
        return false;
    }
    if (p != (void *)(uintptr_t)lo) {
        __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG, "guest mmap landed at %p, wanted %llx",
                            p, (unsigned long long)lo);
        munmap(p, hi - lo);
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, LOGCAT_TAG, "guest range reserved");
    return true;
}

bool plat_reset_guest(uint64_t lo, uint64_t hi) {
    return mmap((void *)(uintptr_t)lo, hi - lo, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED,
                -1, 0) == (void *)(uintptr_t)lo;
}

void plat_commit(uint64_t addr, uint64_t size) { (void)addr; (void)size; }

int plat_protect(uint64_t addr, uint64_t size, int prot) {
    int p = prot == PLAT_PROT_NONE ? PROT_NONE : prot == PLAT_PROT_READ ? PROT_READ : PROT_READ | PROT_WRITE;
    return mprotect((void *)(uintptr_t)addr, size, p);
}

// ---- time
uint64_t plat_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000u + (uint64_t)ts.tv_nsec;
}

void plat_sleep_until_ns(uint64_t t) {
    struct timespec ts = {.tv_sec = (time_t)(t / 1000000000u), .tv_nsec = (long)(t % 1000000000u)};
    while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL) == EINTR) {}
}

// Best effort only: no CAP_SYS_NICE on a phone, so the FIFO request fails quietly.
void plat_thread_realtime(uint64_t period_ns) {
    (void)period_ns;
    struct sched_param sp = {.sched_priority = 1};
    (void)pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp);
}

// ---- logging: stderr/stdout through a pipe; each line goes to logcat and to lasrecomp.log
static FILE *g_log_file;
static int g_log_pipe[2];

static void *log_thread(void *arg) {
    (void)arg;
    char line[4096];
    size_t n = 0;
    char c;
    while (read(g_log_pipe[0], &c, 1) == 1) {
        if (c == '\n' || n == sizeof(line) - 1) {
            line[n] = 0;
            __android_log_print(ANDROID_LOG_INFO, LOGCAT_TAG, "%s", line);
            if (g_log_file) {
                fwrite(line, 1, n, g_log_file);
                fputc('\n', g_log_file);
                fflush(g_log_file);
            }
            n = 0;
        } else {
            line[n++] = c;
        }
    }
    return NULL;
}

static void log_init(void) {
    const char *root = getenv("LARECOMP_DATA_ROOT");
    char dir[1000];
    snprintf(dir, sizeof dir, "%s", root && *root ? root : "/data/local/tmp");
    mkdir(dir, 0755);
    char path[1100], prev[1100];
    snprintf(path, sizeof path, "%s/lasrecomp.log", dir);
    snprintf(prev, sizeof prev, "%s/lasrecomp-prev.log", dir);
    if (!getenv("LASRECOMP_LOG_APPEND")) rename(path, prev);
    g_log_file = fopen(path, "a");
    if (pipe(g_log_pipe) != 0) return;
    fcntl(g_log_pipe[1], F_SETFD, FD_CLOEXEC);
    pthread_t t;
    pthread_create(&t, NULL, log_thread, NULL);
    pthread_detach(t);
    dup2(g_log_pipe[1], STDOUT_FILENO);
    dup2(g_log_pipe[1], STDERR_FILENO);
}

void plat_init(void) {
    log_init();
    // (aarch64: no CPU feature check needed; the game code is built for this CPU)
    // The game library (liblasgame.so) is dlopen'd with RTLD_LOCAL and needs to
    // resolve symbols (e.g. g_fnprof) from this runtime. Java loads us with
    // RTLD_LOCAL, so promote ourselves to RTLD_GLOBAL for the game lib's lookups.
    if (g_lib_dir[0]) {
        char self[1100];
        snprintf(self, sizeof self, "%s/liblasrecomp.so", g_lib_dir);
        dlopen(self, RTLD_NOW | RTLD_GLOBAL | RTLD_NOLOAD);
        // (RTLD_NOLOAD: just promote the already-loaded handle, don't load twice)
        if (!dlopen(self, RTLD_NOW | RTLD_GLOBAL | RTLD_NOLOAD))
            dlopen(self, RTLD_NOW | RTLD_GLOBAL);
    }
}

// The runtime's .so, so rt_resource() finds files shipped next to it (the game library).
bool plat_exe_path(char *buf, size_t size) {
    if (!g_lib_dir[0]) return false;
    snprintf(buf, size, "%s/liblasrecomp.so", g_lib_dir);
    return true;
}

// No re-exec on Android: the only caller wants a fresh address layout after a collision
// of the fixed guest window, which cannot be arranged from inside the app.
void plat_exec(char *const argv[]) {
    (void)argv;
    __android_log_print(ANDROID_LOG_FATAL, LOGCAT_TAG, "plat_exec: cannot re-exec on Android");
    _exit(1);
}

// ---- diagnostics
const char *plat_symbol(uint64_t pc, uint64_t *offset) {
    Dl_info di = {0};
    if (!dladdr((void *)(uintptr_t)pc, &di) || !di.dli_sname) return NULL;
    if (offset) *offset = pc - (uint64_t)(uintptr_t)di.dli_saddr;
    return di.dli_sname;
}

void plat_fatal_notice(void) {
    fflush(stderr);
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, RT_APP_NAME,
                             "An error stopped the app. Details are in lasrecomp.log.", NULL);
}

const char *rt_host_fn_name(uint64_t pc, uint64_t *offset);  // runtime/dispatch.c

struct bt_state {
    void **addrs;
    int n, max;
};

static _Unwind_Reason_Code bt_callback(struct _Unwind_Context *ctx, void *arg) {
    struct bt_state *s = arg;
    if (s->n < s->max) s->addrs[s->n++] = (void *)_Unwind_GetIP(ctx);
    return s->n >= s->max ? _URC_END_OF_STACK : _URC_NO_REASON;
}

void plat_print_backtrace(void) {
    void *addrs[64];
    struct bt_state s = {addrs, 0, 64};
    _Unwind_Backtrace(bt_callback, &s);
    for (int i = 0; i < s.n; i++) {
        uint64_t pc = (uint64_t)(uintptr_t)addrs[i], off = 0;
        Dl_info di = {0};
        if (!dladdr(addrs[i], &di)) di.dli_fname = NULL;
        const char *sym = plat_symbol(pc, &off);
        if (!sym) sym = rt_host_fn_name(pc, &off);
        const char *mod =
            di.dli_fname ? (strrchr(di.dli_fname, '/') ? strrchr(di.dli_fname, '/') + 1 : di.dli_fname) : "?";
        if (sym)
            fprintf(stderr, "  #%-2d %016llx %s+%llx (%s)\n", i, (unsigned long long)pc, sym,
                    (unsigned long long)off, mod);
        else
            fprintf(stderr, "  #%-2d %016llx (%s)\n", i, (unsigned long long)pc, mod);
    }
}

static void crash_handler(int sig, siginfo_t *si, void *ctx) {
    ucontext_t *uc = ctx;
    static volatile int depth;
    if (sig == SIGABRT && depth++ > 0) {
        signal(SIGABRT, SIG_DFL);
        abort();
    }
    uint64_t fa = (uint64_t)(uintptr_t)si->si_addr;
    uint64_t pc = uc->uc_mcontext.pc, lr = uc->uc_mcontext.regs[30];
    if (rt_fault_watch(fa, pc)) return;
    char what[32];
    snprintf(what, sizeof what, "signal %d", sig);
    rt_fault_report(what, fa, pc, lr);
}

void plat_crash_handler_install(void) {
    stack_t ss = {.ss_sp = malloc(1 << 20), .ss_size = 1 << 20};
    sigaltstack(&ss, NULL);
    struct sigaction sa = {0};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
}

// ---- child processes: not supported on Android (API 26 target lacks posix_spawn,
// and the on-device game build never runs here — the game ships prebuilt).
// Any attempt is a bug; fail loudly through plat_exec.
PlatProc plat_spawn(const char *const argv[], const char *log_path, bool append_log) {
    (void)argv; (void)log_path; (void)append_log;
    LOG("FATAL: plat_spawn called on Android (child processes are not supported)\n");
    _exit(1);
    return 0; // unreachable
}

void plat_proc_kill(PlatProc p) {
    kill(-(pid_t)p, SIGKILL);
    waitpid((pid_t)p, NULL, 0);
}

int plat_proc_wait(PlatProc p, bool block) {
    int st = 0;
    pid_t r = waitpid((pid_t)p, &st, block ? 0 : WNOHANG);
    if (r == 0) return -1;
    if (r < 0) return 255;
    return WIFEXITED(st) ? WEXITSTATUS(st) : 255;
}

static int rm_entry(const char *path, const struct stat *sb, int flag, struct FTW *ftw) {
    (void)sb, (void)flag, (void)ftw;
    return remove(path);
}
bool plat_remove_tree(const char *dir) { return nftw(dir, rm_entry, 16, FTW_DEPTH | FTW_PHYS) == 0; }

// End the process at once, skipping library teardown (same reason as the other platforms:
// driver threads can crash in exit() while game threads still submit work).
void plat_terminate(void) { _exit(0); }

// ---- JNI: direct button input from the touch overlay ----
// Java calls this with a Switch button bit (0=A, 1=B, 2=X, 3=Y, 6=L, 7=R,
// 10=Plus, 11=Minus, 12=Left, 13=Up, 14=Right, 15=Down) and pressed/released.
extern void android_set_pad_button(int button, int pressed);
extern void android_set_stick(int stick, int32_t x, int32_t y);
JNIEXPORT void JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeSetButton(JNIEnv *env, jclass cls,
                                                                           jint button, jboolean pressed) {
    (void)env; (void)cls;
    android_set_pad_button(button, pressed);
}
JNIEXPORT void JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeSetStick(JNIEnv *env, jclass cls,
                                                                          jint stick, jint x, jint y) {
    (void)env; (void)cls;
    android_set_stick(stick, x, y);
}
// ---- JNI: the phone's motion sensor (apk/java/com/letsgorecomp/GyroAim.java) -> the virtual six-axis sensor ----
extern void input_gyro_motion(double yaw, double pitch);
extern int input_gyro_wanted(void);
JNIEXPORT void JNICALL Java_com_letsgorecomp_GyroAim_nativeGyroMotion(JNIEnv *env, jclass cls, jdouble yaw, jdouble pitch) {
    (void)env; (void)cls;
    input_gyro_motion(yaw, pitch);
}
JNIEXPORT jboolean JNICALL Java_com_letsgorecomp_GyroAim_nativeGyroWanted(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return input_gyro_wanted() ? JNI_TRUE : JNI_FALSE;
}
extern double g_current_fps, g_frame_ms;
JNIEXPORT jdouble JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeGetFps(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return g_current_fps;
}
JNIEXPORT jdouble JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeGetFrameMs(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return g_frame_ms;
}
// ---- JNI: "Mostrar FPS" (launcher: show_fps in larecomp.ini) -> the TouchControls
// FPS counter (right, "XX fps  YY ms"). The runtime no longer draws its own ImGui
// FPS window (v0118: ui/menu.mm), so the launcher setting now drives the Java counter.
JNIEXPORT jboolean JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeGetShowFps(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return g_settings.show_fps ? JNI_TRUE : JNI_FALSE;
}
// ---- the display guard: is the picture reaching the screen? (runtime/nvn/vulkan/device.c)
// 1 when the engine runs but nothing has been presented for a while (the screen is black).
extern int display_stalled(void);
extern int display_stalled_ms(void);
JNIEXPORT jboolean JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeDisplayStalled(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return display_stalled() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeDisplayStalledMs(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)display_stalled_ms();
}
// The activity left / came back to the foreground (onPause / onResume): the game stops at its next present while it is
// in the background (runtime/lifecycle.c). Coming back restarts the display guard's clock (device.c display_foreground).
extern void display_foreground(void);
JNIEXPORT void JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeSetBackground(JNIEnv *env, jclass cls, jboolean background) {
    (void)env; (void)cls;
    if (!background) display_foreground();
    rt_set_background(background);
}
// The player pressed "resume the picture": ask the runtime to rebuild its surface and swapchain.
extern void display_resume_requested(void);
extern void display_dead(void);
JNIEXPORT void JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeDisplaySurfaceGone(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    display_dead();
}
JNIEXPORT void JNICALL Java_com_letsgorecomp_LetsGoActivity_nativeResumeDisplay(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    display_resume_requested();
}

// ---- native name entry dialog (LetsGoActivity.showNameDialog, Java side) ----
// Called from menu_keyboard_run (runtime/ui/menu.mm) on Android only. Each call
// attaches, calls one static Java method, and detaches: the dialog itself lives on
// the UI thread, so detaching between polls is safe. On any JNI failure the caller
// falls back to the ImGui panel (show returns 0) or cancels (state returns 2).
static JNIEnv *android_jni_attach(void) {
#ifdef LETSGO_ADRENOTOOLS
    if (!g_letsgo_jvm) return NULL;
    JNIEnv *env = NULL;
    if ((*g_letsgo_jvm)->AttachCurrentThread(g_letsgo_jvm, &env, NULL) != 0) return NULL;
    return env;
#else
    return NULL;
#endif
}
static void android_jni_detach(void) {
#ifdef LETSGO_ADRENOTOOLS
    if (g_letsgo_jvm) (*g_letsgo_jvm)->DetachCurrentThread(g_letsgo_jvm);
#endif
}
static jclass android_activity_class(JNIEnv *env) {
    if (g_letsgo_activity_class) return g_letsgo_activity_class;
    // Fallback only (a thread that came from Java): the global ref above is
    // the path that works from a native thread.
    jclass cls = (*env)->FindClass(env, "com/letsgorecomp/LetsGoActivity");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return cls;
}
int android_name_dialog_show(const char *initial, int max_len) {
    JNIEnv *env = android_jni_attach();
    if (!env) { __android_log_print(ANDROID_LOG_WARN, LOGCAT_TAG, "[swkbd] native dialog: no JVM"); return 0; }
    int ok = 0;
    jclass cls = android_activity_class(env);
    if (!cls) __android_log_print(ANDROID_LOG_WARN, LOGCAT_TAG, "[swkbd] native dialog: no activity class");
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "showNameDialog",
                                                  "(Ljava/lang/String;I)V");
        if (!mid) __android_log_print(ANDROID_LOG_WARN, LOGCAT_TAG, "[swkbd] native dialog: no showNameDialog method");
        if (mid) {
            jstring jinit = (*env)->NewStringUTF(env, initial ? initial : "");
            (*env)->CallStaticVoidMethod(env, cls, mid, jinit, (jint)max_len);
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); __android_log_print(ANDROID_LOG_WARN, LOGCAT_TAG, "[swkbd] native dialog: call threw"); }
            else ok = 1;
            if (jinit) (*env)->DeleteLocalRef(env, jinit);
        }
        if (cls != g_letsgo_activity_class) (*env)->DeleteLocalRef(env, cls);
    }
    __android_log_print(ANDROID_LOG_INFO, LOGCAT_TAG, "[swkbd] native dialog show ok=%d", ok);
    android_jni_detach();
    return ok;
}
int android_name_dialog_state(void) {
    JNIEnv *env = android_jni_attach();
    if (!env) return 2;
    int st = 2;
    jclass cls = android_activity_class(env);
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "nameDialogState", "()I");
        if (mid) {
            st = (int)(*env)->CallStaticIntMethod(env, cls, mid);
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); st = 2; }
        }
        if (cls != g_letsgo_activity_class) (*env)->DeleteLocalRef(env, cls);
    }
    android_jni_detach();
    return st;
}
int android_name_dialog_text(char *out, int cap) {
    if (!out || cap <= 0) return 0;
    JNIEnv *env = android_jni_attach();
    if (!env) return 0;
    int ok = 0;
    jclass cls = android_activity_class(env);
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "nameDialogText",
                                                  "()Ljava/lang/String;");
        if (mid) {
            jstring js = (jstring)(*env)->CallStaticObjectMethod(env, cls, mid);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            else if (js) {
                const char *s = (*env)->GetStringUTFChars(env, js, NULL);
                if (s) { snprintf(out, (size_t)cap, "%s", s); (*env)->ReleaseStringUTFChars(env, js, s); ok = 1; }
                (*env)->DeleteLocalRef(env, js);
            }
        }
        if (cls != g_letsgo_activity_class) (*env)->DeleteLocalRef(env, cls);
    }
    android_jni_detach();
    return ok;
}
void android_name_dialog_dismiss(void) {
    JNIEnv *env = android_jni_attach();
    if (!env) return;
    jclass cls = android_activity_class(env);
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "dismissNameDialog", "()V");
        if (mid) {
            (*env)->CallStaticVoidMethod(env, cls, mid);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
        if (cls != g_letsgo_activity_class) (*env)->DeleteLocalRef(env, cls);
    }
    android_jni_detach();
}
