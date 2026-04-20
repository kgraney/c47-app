package com.kevingraney.c47.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

// Simple state holder for the calculator.
//
// Not an androidx.lifecycle.ViewModel — engine state already survives process
// death via saveCalc()/restoreCalc(), and rotation survival doesn't need a
// persistent instance if the Bitmap is re-derived from the framebuffer each
// tick. MainActivity owns the instance.
//
// All native engine calls are serialized onto a single dedicated worker
// thread ("c47-engine"). The C47 engine uses mutable file-scope globals
// (ram, softmenuStack, tamBuffer, FN_state, timer[]) with no internal
// locking — concurrent access from Main (key events) and Default (LCD
// polling) would race. Running every entry point on one thread matches
// the DMCP/GTK builds, whose main loops similarly serialize access. This
// also keeps the engine off the UI thread, so slow refreshScreen calls
// don't drop frames (previously showed up as "Skipped 30 frames").
//
// Key dispatch pipeline:
//     Compose Key -> onKeyDown("06") -> engineScope.launch { engine.keyDown("06") } -> JNI -> btnPressed("06")
// or for a softkey:
//     Compose Key -> onKeyDown("1")  -> engineScope.launch { engine.keyDown("1") }  -> JNI -> btnFnPressed("1")
//
// Codes are engine-native: 2-char flat index "00".."36" into kbd_std_R47f_g
// (assign.c:368), parsed by stringToKeyNumber in keyboard.c:1440. Softkeys
// are single char "1".."6", converted to 0..5 by determineFunctionKeyItem_C47
// at keyboard.c:22 (`*(data) - '0' - 1`).
//
// Display pipeline:
//     30Hz poll -> withContext(engineDispatcher) { engine.readLcd(buf); pack }
//     -> StateFlow emit on Main
class CalculatorViewModel(
    private val engine: C47Engine = C47Engine(),
) {
    private val engineExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "c47-engine") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    private val _lcd = MutableStateFlow(blankBitmap())
    val lcd: StateFlow<Bitmap> = _lcd.asStateFlow()

    private val pixelBuf = ByteArray(C47Engine.FRAMEBUFFER_BYTES)
    private val argbBuf = IntArray(C47Engine.FRAMEBUFFER_BYTES)

    fun init(stateDir: File) {
        engineScope.launch { engine.init(stateDir) }
    }

    fun onKeyDown(code: String) {
        engineScope.launch { engine.keyDown(code) }
    }

    fun onKeyUp(code: String) {
        engineScope.launch { engine.keyUp(code) }
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = uiScope.launch {
            while (true) {
                val dirty = withContext(engineDispatcher) {
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
        engineScope.launch { engine.save() }
    }

    fun shutdown() {
        stop()
        uiScope.cancel()
        engineScope.cancel()
        engineExecutor.shutdown()
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

    companion object {
        // Match the palette CalculatorDisplayView uses — parchment background,
        // near-black foreground.
        private val PIXEL_OFF = Color.parseColor("#D2C89A")
        private val PIXEL_ON  = Color.parseColor("#1A1A1A")
    }
}
