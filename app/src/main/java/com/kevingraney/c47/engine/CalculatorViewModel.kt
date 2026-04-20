package com.kevingraney.c47.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

// Simple state holder for the calculator.
//
// Threading: every native engine entry point is serialized onto a single
// dedicated worker thread ("c47-engine"). The engine uses mutable file-scope
// globals (ram, softmenuStack, tamBuffer, FN_state, timer[], lcd_buffer)
// with no internal locking. Keeping everything on one thread matches the
// DMCP/GTK reference builds.
//
// Frame pipeline (see pumpFrame): the UI drives the tick via withFrameNanos
// in CalculatorScreen, calling pumpFrame() each vsync. pumpFrame hops to the
// engine thread to unpack the framebuffer directly into a reused direct
// ByteBuffer as ARGB_8888, returns to Main, copies the buffer into the same
// reused Bitmap with copyPixelsFromBuffer, then emits a fresh LcdFrame
// (version-bumped) so Compose recomposes. The Bitmap and ByteBuffer are
// allocated once — no per-frame GC.
//
// Key dispatch:
//     Compose Key -> onKeyDown("06") -> engineScope.launch { engine.keyDown("06") } -> JNI
// Softkey codes are single char "1".."6"; data keys are 2-char "00".."36".
class CalculatorViewModel(
    private val engine: C47Engine = C47Engine(),
) {
    private val engineExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "c47-engine") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    // Reused across every frame — no per-tick allocation.
    private val argbBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(C47Engine.FRAMEBUFFER_BYTES * 4)
        .order(ByteOrder.nativeOrder())
    private val bitmap: Bitmap = Bitmap.createBitmap(
        C47Engine.SCREEN_WIDTH,
        C47Engine.SCREEN_HEIGHT,
        Bitmap.Config.ARGB_8888,
    ).also { it.eraseColor(PIXEL_OFF_ARGB) }

    private var frameVersion = 0
    private val _lcd = MutableStateFlow(LcdFrame(bitmap, frameVersion))
    val lcd: StateFlow<LcdFrame> = _lcd.asStateFlow()

    fun init(stateDir: File) {
        engineScope.launch { engine.init(stateDir) }
    }

    fun onKeyDown(code: String) {
        engineScope.launch { engine.keyDown(code) }
    }

    fun onKeyUp(code: String) {
        engineScope.launch { engine.keyUp(code) }
    }

    // Called from the UI's vsync-aligned LaunchedEffect. Cheap when clean:
    // the native side just checks the dirty flag and returns false without
    // touching the buffer. Must also tick the engine's timer subsystem —
    // without this, press-and-hold softkey row cycling (the diagonal-hatch
    // underline feedback) and the 3-second shift cutoff never fire, so
    // function menus are effectively unnavigable beyond their top row.
    // Timer callbacks may write to lcd_buffer, so tick before renderArgb.
    suspend fun pumpFrame() {
        val dirty = withContext(engineDispatcher) {
            engine.tick()
            engine.renderArgb(argbBuffer, PIXEL_ON_ARGB, PIXEL_OFF_ARGB)
        }
        if (dirty) {
            argbBuffer.rewind()
            bitmap.copyPixelsFromBuffer(argbBuffer)
            frameVersion++
            _lcd.value = LcdFrame(bitmap, frameVersion)
        }
    }

    fun save() {
        engineScope.launch { engine.save() }
    }

    fun shutdown() {
        engineScope.cancel()
        engineExecutor.shutdown()
    }

    companion object {
        // Palette matches CalculatorDisplayView — parchment off, near-black on.
        // Stored pre-packed as ARGB_8888 ints for the native renderer.
        private const val PIXEL_OFF_ARGB = 0xFFD2C89A.toInt()
        private const val PIXEL_ON_ARGB  = 0xFF1A1A1A.toInt()
    }
}

// The bitmap reference is shared — we mutate its pixels each frame. `version`
// changes on every dirty emission so StateFlow equality doesn't dedupe us,
// forcing Compose to recompose the Image.
data class LcdFrame(val bitmap: Bitmap, val version: Int)
