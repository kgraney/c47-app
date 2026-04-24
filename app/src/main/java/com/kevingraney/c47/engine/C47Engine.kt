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

    // Drive the engine's timer subsystem. Must be called periodically
    // (at least every ~50ms) for press-and-hold softkey row cycling,
    // auto-repeat, and shift cutoff to work. Vsync pumping is fine.
    fun tick() = nativeTick()

    // Writes ARGB_8888 pixels straight into `buf` (must be a direct buffer
    // sized FRAMEBUFFER_BYTES * 4). Returns true if the frame was dirty and
    // the buffer was updated; false means no work done and `buf` is stale.
    fun renderArgb(buf: ByteBuffer, onArgb: Int, offArgb: Int): Boolean =
        nativeRenderArgb(buf, onArgb, offArgb)

    fun save() = nativeSave()
    fun restore() = nativeRestore()

    // Paint the persistent off-image into the LCD framebuffer. Matches the
    // hardware R47's OFF behavior (DMCP's draw_power_off_image). Caller
    // should stop ticking the engine while powered off.
    fun powerOff() = nativePowerOff()

    // Restore panel power and force the engine to re-render the current
    // screen over the off image.
    fun powerOn() = nativePowerOn()

    // True while the orange shift (f) is armed — i.e. the next keystroke
    // will be shift-f'd. Read at the moment of the next keystroke so the
    // UI can detect the f+EXIT = OFF combo without duplicating shift
    // tracking on the Kotlin side.
    fun isShiftFArmed(): Boolean = nativeShiftFArmed()

    private external fun nativeInit(stateDir: String)
    private external fun nativeKeyDown(key: String)
    private external fun nativeKeyUp(key: String)
    private external fun nativeTick()
    private external fun nativeRenderArgb(buf: ByteBuffer, onArgb: Int, offArgb: Int): Boolean
    private external fun nativeSave()
    private external fun nativeRestore()
    private external fun nativePowerOff()
    private external fun nativePowerOn()
    private external fun nativeShiftFArmed(): Boolean
}
