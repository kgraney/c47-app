package com.kevingraney.c47.engine

import java.io.File

class C47Engine {

    companion object {
        init {
            System.loadLibrary("c47")
        }

        const val SCREEN_WIDTH = 400
        const val SCREEN_HEIGHT = 240
        const val FRAMEBUFFER_BYTES = SCREEN_WIDTH * SCREEN_HEIGHT
    }

    fun init(stateDir: File) = nativeInit(stateDir.absolutePath)

    // `key` is engine-native: 2-char "00".."36" for data keys, single char
    // "1".."6" for softkeys. JNI dispatches by length.
    fun keyDown(key: String) = nativeKeyDown(key)
    fun keyUp(key: String) = nativeKeyUp(key)

    fun readLcd(buf: ByteArray): Boolean = nativeReadLcd(buf)

    fun save() = nativeSave()
    fun restore() = nativeRestore()

    private external fun nativeInit(stateDir: String)
    private external fun nativeKeyDown(key: String)
    private external fun nativeKeyUp(key: String)
    private external fun nativeReadLcd(out: ByteArray): Boolean
    private external fun nativeSave()
    private external fun nativeRestore()
}
