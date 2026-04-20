// SPDX-License-Identifier: GPL-3.0-only
// JNI bridge between the Compose UI and the vendored C47 engine.
//
// Keys arrive in engine-native form: 2-char flat index "00".."36" for data
// keys (dispatched to btnPressed / btnReleased), or single char "1".."6"
// for softkeys F1..F6 (dispatched to btnFnPressed / btnFnReleased).

#include <jni.h>
#include <android/log.h>
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

  // Android HAL hooks (c43-source/src/c47-android/hal/)
  void c47_android_set_state_dir(const char *dir);
  void c47_android_lcd_init(void);
  int  c47_android_lcd_take_dirty(void);
  void c47_android_lcd_copy_to(unsigned char *out);
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

JNIEXPORT jboolean JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeReadLcd(
        JNIEnv* env, jobject /*thiz*/, jbyteArray out) {
    const jsize len = env->GetArrayLength(out);
    if (len < kFramebufferBytes) {
        LOGW("nativeReadLcd: buffer too small (%d < %d)", len, kFramebufferBytes);
        return JNI_FALSE;
    }
    jbyte* px = env->GetByteArrayElements(out, nullptr);
#if defined(C47_ENGINE_LINKED)
    const int dirty = c47_android_lcd_take_dirty();
    c47_android_lcd_copy_to(reinterpret_cast<unsigned char*>(px));
    env->ReleaseByteArrayElements(out, px, 0);
    return dirty ? JNI_TRUE : JNI_FALSE;
#else
    // Stub pattern so the VM -> Bitmap -> Compose path can be verified.
    // Corners + a diagonal stripe, so we can tell it's coming from JNI
    // rather than leftover zeros.
    std::memset(px, 0, kFramebufferBytes);
    for (int y = 0; y < kScreenHeight; ++y) {
        int x = (y * kScreenWidth) / kScreenHeight;
        if (x >= 0 && x < kScreenWidth) px[y * kScreenWidth + x] = 1;
    }
    for (int x = 0; x < kScreenWidth; ++x) {
        px[0 * kScreenWidth + x] = 1;
        px[(kScreenHeight - 1) * kScreenWidth + x] = 1;
    }
    for (int y = 0; y < kScreenHeight; ++y) {
        px[y * kScreenWidth + 0] = 1;
        px[y * kScreenWidth + kScreenWidth - 1] = 1;
    }
    env->ReleaseByteArrayElements(out, px, 0);
    return JNI_TRUE;
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
