package com.kevingraney.c47.engine

import java.io.File
import java.nio.ByteBuffer

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

    // Writes ARGB_8888 pixels straight into `buf` (must be a direct buffer
    // sized FRAMEBUFFER_BYTES * 4). Returns true if the frame was dirty and
    // the buffer was updated; false means no work done and `buf` is stale.
    fun renderArgb(buf: ByteBuffer, onArgb: Int, offArgb: Int): Boolean =
        nativeRenderArgb(buf, onArgb, offArgb)

    fun save() = nativeSave()
    fun restore() = nativeRestore()

    private external fun nativeInit(stateDir: String)
    private external fun nativeKeyDown(key: String)
    private external fun nativeKeyUp(key: String)
    private external fun nativeRenderArgb(buf: ByteBuffer, onArgb: Int, offArgb: Int): Boolean
    private external fun nativeSave()
    private external fun nativeRestore()
}
