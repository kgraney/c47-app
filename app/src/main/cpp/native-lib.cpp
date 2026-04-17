// SPDX-License-Identifier: GPL-3.0-only
// JNI bridge between the Compose UI and the vendored C47 engine.
//
// Phase 1 (current): all entry points are stubs that log and return defaults.
// They exist so the Kotlin side can compile and link against the final API
// shape described in INTERACTIVITY_PLAN.md. No actual engine calls happen
// yet — the engine can't be linked in until GMP is cross-compiled for
// Android and the host-generated headers are produced.

#include <jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "c47-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// Screen dimensions mirror the engine's SCREEN_WIDTH / SCREEN_HEIGHT.
// Duplicated here so we can ship Phase 1 without pulling in the engine
// headers (which transitively require GMP).
static constexpr int kScreenWidth  = 400;
static constexpr int kScreenHeight = 240;
static constexpr int kFramebufferBytes = kScreenWidth * kScreenHeight;

extern "C" {

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring stateDir) {
    const char* path = env->GetStringUTFChars(stateDir, nullptr);
    LOGI("nativeInit stateDir=%s (engine not yet linked — stub)", path);
    env->ReleaseStringUTFChars(stateDir, path);
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeKeyDown(
        JNIEnv* /*env*/, jobject /*thiz*/, jint row, jint col) {
    LOGI("nativeKeyDown %02d%02d (stub)", row, col);
    // TODO: forward to btnPressed / btnFnPressed once engine links.
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeKeyUp(
        JNIEnv* /*env*/, jobject /*thiz*/, jint row, jint col) {
    LOGI("nativeKeyUp %02d%02d (stub)", row, col);
    // TODO: forward to btnReleased / btnFnReleased once engine links.
}

JNIEXPORT jboolean JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeReadLcd(
        JNIEnv* env, jobject /*thiz*/, jbyteArray out) {
    const jsize len = env->GetArrayLength(out);
    if (len < kFramebufferBytes) {
        LOGW("nativeReadLcd: buffer too small (%d < %d)", len, kFramebufferBytes);
        return JNI_FALSE;
    }
    // Stub: write a recognizable pattern (diagonal) so the display-side
    // plumbing can be exercised before the engine is live.
    jbyte* px = env->GetByteArrayElements(out, nullptr);
    std::memset(px, 0, kFramebufferBytes);
    for (int y = 0; y < kScreenHeight; ++y) {
        int x = (y * kScreenWidth) / kScreenHeight;
        if (x >= 0 && x < kScreenWidth) {
            px[y * kScreenWidth + x] = 1;
        }
    }
    env->ReleaseByteArrayElements(out, px, 0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeSave(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("nativeSave (stub)");
    // TODO: saveCalc() once engine links.
}

JNIEXPORT void JNICALL
Java_com_kevingraney_c47_engine_C47Engine_nativeRestore(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("nativeRestore (stub)");
    // TODO: restoreCalc() once engine links.
}

} // extern "C"
