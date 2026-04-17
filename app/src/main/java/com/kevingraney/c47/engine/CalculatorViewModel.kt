package com.kevingraney.c47.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Simple state holder for the calculator.
//
// Not an androidx.lifecycle.ViewModel — engine state already survives process
// death via saveCalc()/restoreCalc(), and rotation survival doesn't need a
// persistent instance if the Bitmap is re-derived from the framebuffer each
// tick. MainActivity owns the instance.
//
// Key dispatch pipeline:
//     Compose Key -> onKeyDown("23") -> engine.keyDown(2, 3) -> JNI -> btnPressed("23")
//
// Display pipeline:
//     30Hz poll -> engine.readLcd(buf) -> pack bytes into ARGB bitmap -> StateFlow
class CalculatorViewModel(
    private val engine: C47Engine = C47Engine(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    private val _lcd = MutableStateFlow(blankBitmap())
    val lcd: StateFlow<Bitmap> = _lcd.asStateFlow()

    private val pixelBuf = ByteArray(C47Engine.FRAMEBUFFER_BYTES)
    private val argbBuf = IntArray(C47Engine.FRAMEBUFFER_BYTES)

    fun init(stateDir: File) {
        engine.init(stateDir)
    }

    fun onKeyDown(code: String) {
        val (r, c) = code.toRowCol() ?: return
        engine.keyDown(r, c)
    }

    fun onKeyUp(code: String) {
        val (r, c) = code.toRowCol() ?: return
        engine.keyUp(r, c)
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val dirty = withContext(Dispatchers.Default) {
                    val changed = engine.readLcd(pixelBuf)
                    if (changed) packIntoArgb()
                    changed
                }
                if (dirty) {
                    _lcd.value = argbToBitmap()
                }
                delay(33L) // ~30 Hz
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun save() {
        engine.save()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun packIntoArgb() {
        // Engine emits 0 = off (parchment), 1 = on (dark).
        for (i in argbBuf.indices) {
            argbBuf[i] = if (pixelBuf[i].toInt() != 0) PIXEL_ON else PIXEL_OFF
        }
    }

    private fun argbToBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(
            C47Engine.SCREEN_WIDTH,
            C47Engine.SCREEN_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        bmp.setPixels(
            argbBuf,
            0,
            C47Engine.SCREEN_WIDTH,
            0, 0,
            C47Engine.SCREEN_WIDTH,
            C47Engine.SCREEN_HEIGHT,
        )
        return bmp
    }

    private fun blankBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(
            C47Engine.SCREEN_WIDTH,
            C47Engine.SCREEN_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        bmp.eraseColor(PIXEL_OFF)
        return bmp
    }

    private fun String.toRowCol(): Pair<Int, Int>? {
        if (length != 2) return null
        val r = this[0].digitToIntOrNull() ?: return null
        val c = this[1].digitToIntOrNull() ?: return null
        return r to c
    }

    companion object {
        // Match the palette CalculatorDisplayView uses — parchment background,
        // near-black foreground.
        private val PIXEL_OFF = Color.parseColor("#D2C89A")
        private val PIXEL_ON  = Color.parseColor("#1A1A1A")
    }
}
