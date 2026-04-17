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

    fun keyDown(row: Int, col: Int) = nativeKeyDown(row, col)
    fun keyUp(row: Int, col: Int) = nativeKeyUp(row, col)

    fun readLcd(buf: ByteArray): Boolean = nativeReadLcd(buf)

    fun save() = nativeSave()
    fun restore() = nativeRestore()

    private external fun nativeInit(stateDir: String)
    private external fun nativeKeyDown(row: Int, col: Int)
    private external fun nativeKeyUp(row: Int, col: Int)
    private external fun nativeReadLcd(out: ByteArray): Boolean
    private external fun nativeSave()
    private external fun nativeRestore()
}
