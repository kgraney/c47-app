// SPDX-License-Identifier: GPL-3.0-only
// JNI bridge between the Compose UI and the vendored C47 engine.
//
// Phase 2: the dispatch shape is final (row/col -> "%02d" key string,
// softkey row 0 -> btnFnPressed, data rows -> btnPressed). The engine
// calls themselves are guarded by C47_ENGINE_LINKED and stubbed until
// GMP + src/generated/ land.

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

  // Android HAL hooks (c43-source/src/c47-android/hal/)
  void c47_android_set_state_dir(const char *dir);
  void c47_android_lcd_init(void);
  int  c47_android_lcd_take_dirty(void);
  void c47_android_lcd_copy_to(unsigned char *out);
}
#endif // C47_ENGINE_LINKED

static void format_key(int row, int col, char out[3]) {
  std::snprintf(out, 3, "%d%d", row, col);
  out[2] = '\0';
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring stateDir) {
    const char* path = env->GetStringUTFChars(stateDir, nullptr);
    LOGI("nativeInit stateDir=%s", path);
#if defined(C47_ENGINE_LINKED)
    c47_android_set_state_dir(path);
    c47_android_lcd_init();
    // Either restore prior state or start clean. restoreCalc() no-ops when
    // the save file doesn't exist.
    restoreCalc();
#else
    LOGI("  (engine not linked — see CMakeLists for C47_ENGINE_LINKED)");
#endif
    env->ReleaseStringUTFChars(stateDir, path);
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeKeyDown(
        JNIEnv* /*env*/, jobject /*thiz*/, jint row, jint col) {
    char key[3];
    format_key(row, col, key);
    LOGI("nativeKeyDown %s", key);
#if defined(C47_ENGINE_LINKED)
    if (row == 0) {
        btnFnPressed(key);   // softkey row: F1..F6
    } else {
        btnPressed(key);     // data rows
    }
#endif
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeKeyUp(
        JNIEnv* /*env*/, jobject /*thiz*/, jint row, jint col) {
    char key[3];
    format_key(row, col, key);
    LOGI("nativeKeyUp %s", key);
#if defined(C47_ENGINE_LINKED)
    if (row == 0) {
        btnFnReleased(key);
    } else {
        btnReleased(key);
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
