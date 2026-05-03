// SPDX-License-Identifier: GPL-3.0-only
// JNI bridge between the Compose UI and the vendored C47 engine.
//
// Keys arrive in engine-native form: 2-char flat index "00".."36" for data
// keys (dispatched to btnPressed / btnReleased), or single char "1".."6"
// for softkeys F1..F6 (dispatched to btnFnPressed / btnFnReleased).

#include <jni.h>
#include <android/log.h>
#include <android/trace.h>
#include <cstdint>
#include <cstdio>
#include <cstring>

#define LOG_TAG "c47-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// Cached JavaVM (set in JNI_OnLoad) plus a global ref to the C47Engine
// instance and the resolved playToneFromNative method ID. Used by
// c47_android_play_tone (called from c47-android/hal/audio.c) to bridge
// audioTone() back into the Kotlin TonePlayer. The engine HAL invokes this
// from the engine thread, which is already JNI-attached (every entry point
// is called via JNI from the same Kotlin executor), so AttachCurrentThread
// is just a safety net.
static JavaVM*   g_jvm          = nullptr;
static jobject   g_engineRef    = nullptr;
static jmethodID g_playToneMid  = nullptr;

// RAII scope for systrace; zero-cost when tracing disabled (ATrace_isEnabled
// gates the section). Capture with Perfetto to see where engine-thread time
// goes during key presses vs. idle renders.
struct AtraceScope {
    AtraceScope(const char* name) { ATrace_beginSection(name); }
    ~AtraceScope() { ATrace_endSection(); }
};
#define TRACE_SCOPE(name) AtraceScope _atrace_scope_##__LINE__(name)

// Keep in sync with the engine's SCREEN_WIDTH / SCREEN_HEIGHT in defines.h.
static constexpr int kScreenWidth  = 400;
static constexpr int kScreenHeight = 240;
static constexpr int kFramebufferBytes = kScreenWidth * kScreenHeight;

#if defined(C47_ENGINE_LINKED)
extern "C" {
  // Engine entry points (from c43-source/src/c47/keyboard.c)
  void btnPressed   (void *data);
  void btnReleased  (void *data);
  void btnFnPressed (void *data);
  void btnFnReleased(void *data);
  void fnReset      (unsigned short param);   // CONFIRMED=1
  void saveCalc     (void);
  void restoreCalc  (void);

  // Timer subsystem (c43-source/src/c47/timer.c). TMR_NUMBER == 11; the
  // constants below mirror defines.h:1371-1384.
  void fnTimerReset (void);
  void fnTimerConfig(unsigned char nr, void (*func)(unsigned short), unsigned short param);
  void fnTimerDummy1       (unsigned short param);
  void fnTimerEndOfActivity(unsigned short param);
  void refreshFn     (unsigned short timerType);   // screen.c
  void execTimerApp  (unsigned short timerType);   // screen.c
  void execFnTimeout (unsigned short key);         // c47Extensions/keyboardTweak.c
  void shiftCutoff   (unsigned short param);       // c47Extensions/keyboardTweak.c
  void execAutoRepeat(unsigned short key);         // keyboard.c
  void refreshTimer  (void);                       // timer.c — expires queued
                                                   // timers (press-and-hold row
                                                   // cycling, auto-repeat,
                                                   // shift cutoff, etc.)

  // Android HAL hooks (c43-source/src/c47-android/hal/)
  void           c47_android_set_state_dir(const char *dir);
  void           c47_android_lcd_init(void);
  int            c47_android_lcd_take_dirty(void);
  void           c47_android_lcd_copy_to(unsigned char *out);
  const uint8_t *c47_android_lcd_raw(void);

  // Power-off image hooks (defined in c47-android/hal/lcd.c). The engine
  // would call these from program_main's OFF state machine on DMCP, but
  // program_main isn't run on Android — we invoke them from the UI.
  void draw_power_off_image(int allow_errors);
  void LCD_power_off(int clear);
  void LCD_power_on(void);
  void refreshScreen(unsigned short source);  // screen.c

  // lcd_fill_rect primitive (c47-android/hal/lcd.c). Used on wake to wipe
  // the off-image before the engine repaints — refreshScreen() on CM_NORMAL
  // only touches the regions it manages (status bar, stack, menus), so
  // pixels left over outside those regions would linger as artifacts.
  void lcd_fill_rect(unsigned x, unsigned y, unsigned dx, unsigned dy, int val);

  // Engine shift-state globals (c47.c:51-52). bool_t is `typedef bool bool_t`,
  // 1-byte storage — safe to extern as unsigned char across the C/C++ seam.
  extern unsigned char shiftF;
  extern unsigned char shiftG;
}

// Timer slot IDs — must match c43-source/src/c47/defines.h:1374-1384.
enum {
  TO_FG_LONG     = 0,
  TO_CL_LONG     = 1,
  TO_FG_TIMR     = 2,
  TO_FN_LONG     = 3,
  TO_FN_EXEC     = 4,
  TO_3S_CTFF     = 5,
  TO_CL_DROP     = 6,
  TO_AUTO_REPEAT = 7,
  TO_TIMER_APP   = 8,
  TO_ASM_ACTIVE  = 9,
  TO_KB_ACTV     = 10,
};
#endif // C47_ENGINE_LINKED

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Called from c47-android/hal/audio.c's audioTone() to play a tone via the
// Kotlin TonePlayer. Blocks for the tone duration (~250ms) so that fnBeep's
// four sequential _tonePlay calls don't overlap, matching DMCP semantics.
void c47_android_play_tone(uint32_t millihertz) {
    if (g_jvm == nullptr || g_engineRef == nullptr || g_playToneMid == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
            return;
        }
        attached = true;
    } else if (res != JNI_OK || env == nullptr) {
        return;
    }
    env->CallVoidMethod(g_engineRef, g_playToneMid, static_cast<jint>(millihertz));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeSetTonePlayer(
        JNIEnv* env, jobject thiz) {
    if (g_engineRef != nullptr) {
        env->DeleteGlobalRef(g_engineRef);
        g_engineRef = nullptr;
    }
    g_engineRef = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_playToneMid = env->GetMethodID(cls, "playToneFromNative", "(I)V");
    env->DeleteLocalRef(cls);
    if (g_playToneMid == nullptr) {
        LOGW("nativeSetTonePlayer: playToneFromNative method not found");
    } else {
        LOGI("nativeSetTonePlayer: tone callback wired");
    }
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring stateDir) {
    const char* path = env->GetStringUTFChars(stateDir, nullptr);
    LOGI("nativeInit stateDir=%s", path);
#if defined(C47_ENGINE_LINKED)
    c47_android_set_state_dir(path);
    c47_android_lcd_init();

    // Timer callback table — normally wired up in program_main() (c47.c:753-
    // 764), which is gated on DMCP_BUILD and so never runs for us. Without
    // this, the first softkey release arms TO_FN_EXEC with a NULL .func, and
    // the next softkey press crashes in fnTimerExec.
    fnTimerReset();
    fnTimerConfig(TO_FG_LONG,     refreshFn,            TO_FG_LONG);
    fnTimerConfig(TO_CL_LONG,     refreshFn,            TO_CL_LONG);
    fnTimerConfig(TO_FG_TIMR,     refreshFn,            TO_FG_TIMR);
    fnTimerConfig(TO_FN_LONG,     refreshFn,            TO_FN_LONG);
    fnTimerConfig(TO_FN_EXEC,     execFnTimeout,        0);
    fnTimerConfig(TO_3S_CTFF,     shiftCutoff,          TO_3S_CTFF);
    fnTimerConfig(TO_CL_DROP,     fnTimerDummy1,        TO_CL_DROP);
    fnTimerConfig(TO_AUTO_REPEAT, execAutoRepeat,       0);
    fnTimerConfig(TO_TIMER_APP,   execTimerApp,         0);
    fnTimerConfig(TO_ASM_ACTIVE,  refreshFn,            TO_ASM_ACTIVE);
    fnTimerConfig(TO_KB_ACTV,     fnTimerEndOfActivity, TO_KB_ACTV);

    // Either restore prior state or start clean. restoreCalc() internally
    // calls doFnReset(CONFIRMED) when the save file is missing.
    restoreCalc();
#else
    LOGI("  (engine not linked — see CMakeLists for C47_ENGINE_LINKED)");
#endif
    env->ReleaseStringUTFChars(stateDir, path);
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeKeyDown(
        JNIEnv* env, jobject /*thiz*/, jstring jkey) {
    TRACE_SCOPE("c47:keyDown");
    const char* src = env->GetStringUTFChars(jkey, nullptr);
    char key[3] = {0};
    // Copy up to 2 chars — longer strings are silently truncated, but the
    // UI only ever sends length-1 (softkey) or length-2 (data key).
    std::strncpy(key, src, 2);
    const size_t len = std::strlen(key);
    env->ReleaseStringUTFChars(jkey, src);
    LOGI("nativeKeyDown %s", key);
#if defined(C47_ENGINE_LINKED)
    if (len == 1) {
        btnFnPressed(key);   // softkey F1..F6
    } else if (len == 2) {
        btnPressed(key);     // data key flat index 00..36
    } else {
        LOGW("nativeKeyDown: unexpected key length %zu", len);
    }
#endif
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeKeyUp(
        JNIEnv* env, jobject /*thiz*/, jstring jkey) {
    TRACE_SCOPE("c47:keyUp");
    const char* src = env->GetStringUTFChars(jkey, nullptr);
    char key[3] = {0};
    std::strncpy(key, src, 2);
    const size_t len = std::strlen(key);
    env->ReleaseStringUTFChars(jkey, src);
    LOGI("nativeKeyUp %s", key);
#if defined(C47_ENGINE_LINKED)
    if (len == 1) {
        btnFnReleased(key);
    } else if (len == 2) {
        btnReleased(key);
    } else {
        LOGW("nativeKeyUp: unexpected key length %zu", len);
    }
#endif
}

// Drive the engine's timer subsystem. Without a periodic tick, none of the
// timed behaviors work: press-and-hold softkey row cycling (TO_FN_LONG) —
// which produces the diagonal-hatch underline feedback on the emulator —
// never fires, so the user can only ever execute the top row of a softmenu;
// auto-repeat (TO_AUTO_REPEAT) and the 3-second shift cutoff (TO_3S_CTFF)
// are also dead. Called ~120 Hz from CalculatorViewModel's engine-thread
// pump loop; cheap when no timers are running (just walks TMR_NUMBER
// entries and checks state).
JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeTick(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    TRACE_SCOPE("c47:tick");
#if defined(C47_ENGINE_LINKED)
    refreshTimer();
#endif
}

// Packs the engine's bit-packed framebuffer directly into the caller's
// direct IntBuffer as ARGB_8888, skipping the intermediate byte[] and the
// Kotlin-side pack loop. Returns JNI_FALSE (no work done) if the frame is
// clean. The caller reuses one Bitmap and one buffer across frames.
JNIEXPORT jboolean JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeRenderArgb(
        JNIEnv* env, jobject /*thiz*/, jobject directBuffer, jint onArgb, jint offArgb) {
    TRACE_SCOPE("c47:renderArgb");
#if defined(C47_ENGINE_LINKED)
    if (directBuffer == nullptr) return JNI_FALSE;
    void* raw = env->GetDirectBufferAddress(directBuffer);
    if (raw == nullptr) {
        LOGW("nativeRenderArgb: buffer not direct");
        return JNI_FALSE;
    }
    const jlong cap = env->GetDirectBufferCapacity(directBuffer);
    if (cap < (jlong)(kFramebufferBytes * 4)) {
        LOGW("nativeRenderArgb: buffer too small (%lld < %d)",
             (long long)cap, kFramebufferBytes * 4);
        return JNI_FALSE;
    }
    if (!c47_android_lcd_take_dirty()) return JNI_FALSE;

    const uint8_t* buf = c47_android_lcd_raw();
    if (buf == nullptr) return JNI_FALSE;

    uint32_t* dst = static_cast<uint32_t*>(raw);
    const uint32_t on  = static_cast<uint32_t>(onArgb);
    const uint32_t off = static_cast<uint32_t>(offArgb);
    for (int row = 0; row < kScreenHeight; ++row) {
        const uint8_t* line = buf + 52 * row + 2;  // skip [dirty, row-index]
        uint32_t*      out  = dst + row * kScreenWidth;
        for (int x = 0; x < kScreenWidth; ++x) {
            const int xr = kScreenWidth - 1 - x;
            out[x] = ((line[xr >> 3] >> (xr & 7)) & 1u) ? on : off;
        }
    }
    return JNI_TRUE;
#else
    (void)env; (void)directBuffer; (void)onArgb; (void)offArgb;
    return JNI_FALSE;
#endif
}

// Paint the power-off image into the LCD framebuffer and mark the panel as
// powered off. Matches the DMCP OFF transition (c47.c:923-932): draw the
// branded splash, then cut panel power. UI layer should stop ticking the
// engine until nativePowerOn fires.
JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativePowerOff(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("nativePowerOff");
#if defined(C47_ENGINE_LINKED)
    draw_power_off_image(1);
    LCD_power_off(0);
#endif
}

// Restore panel power and force the engine to re-render the current screen.
// Differs from the DMCP wake path (c47.c:945-953 lcd_forced_refresh) because
// on real hardware the off-image is expected to briefly persist until the
// next user action repaints. On Android we want a clean wake, so we wipe
// the framebuffer to parchment (val=0 clears bits) before refreshScreen()
// — otherwise decorative pixels from the off-image outside the regions
// _refreshNormalScreen repaints (status bar / stack / menus) linger.
JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativePowerOn(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("nativePowerOn");
#if defined(C47_ENGINE_LINKED)
    LCD_power_on();
    lcd_fill_rect(0, 0, kScreenWidth, kScreenHeight, 0);
    refreshScreen(0);
#endif
}

// Report whether the orange (f) shift is currently armed. Used by the
// UI to detect the f+EXIT = OFF combo — the engine handles shift state
// internally, so we read it rather than tracking it on the Kotlin side.
JNIEXPORT jboolean JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeShiftFArmed(
        JNIEnv* /*env*/, jobject /*thiz*/) {
#if defined(C47_ENGINE_LINKED)
    return shiftF ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeSave(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("nativeSave");
#if defined(C47_ENGINE_LINKED)
    saveCalc();
#endif
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeRestore(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("nativeRestore");
#if defined(C47_ENGINE_LINKED)
    restoreCalc();
#endif
}

} // extern "C"
