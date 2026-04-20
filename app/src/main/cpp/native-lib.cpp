// SPDX-License-Identifier: GPL-3.0-only
// JNI bridge between the Compose UI and the vendored C47 engine.
//
// Keys arrive in engine-native form: 2-char flat index "00".."36" for data
// keys (dispatched to btnPressed / btnReleased), or single char "1".."6"
// for softkeys F1..F6 (dispatched to btnFnPressed / btnFnReleased).

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>

#define LOG_TAG "c47-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

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
// are also dead. Called every vsync from CalculatorViewModel.pumpFrame on
// the engine thread; cheap when no timers are running (just walks TMR_NUMBER
// entries and checks state).
JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeTick(
        JNIEnv* /*env*/, jobject /*thiz*/) {
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
