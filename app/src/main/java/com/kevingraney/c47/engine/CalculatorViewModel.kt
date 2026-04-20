package com.kevingraney.c47.engine

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
// Frame pipeline:
//   - Keystrokes: onKeyDown/onKeyUp launch engine work on the engine thread,
//     then render+emit *inline on the same launch* as soon as the engine
//     finishes. This pulls tap-to-display latency down to just the engine's
//     own btnPressed time — there's no pump-interval delay in the critical
//     path.
//   - Background: a slow 10Hz pump ticks the engine's timer subsystem and
//     renders if something went dirty without a keystroke (blinking cursor,
//     shift-cutoff timeout, press-and-hold row cycling).
//
// Double-buffered bitmaps mean Compose always reads a stable front buffer
// while the engine thread writes to the back, then atomically swaps on
// emit. Two Bitmaps and one direct ByteBuffer are allocated once.
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

    // Double-buffered: the engine thread writes pixels into `back`, swaps
    // front<->back, then emits. Compose always reads a stable front buffer
    // so there's no chance of tearing or contention with copyPixelsFromBuffer.
    private var front: Bitmap = makeBitmap()
    private var back: Bitmap = makeBitmap()

    private var frameVersion = 0
    private val _lcd = MutableStateFlow(LcdFrame(front, frameVersion))
    val lcd: StateFlow<LcdFrame> = _lcd.asStateFlow()

    fun init(stateDir: File) {
        engineScope.launch {
            engine.init(stateDir)
            runBackgroundPump()
        }
    }

    fun onKeyDown(code: String) {
        val enqueued = SystemClock.uptimeMillis()
        engineScope.launch {
            val started = SystemClock.uptimeMillis()
            val queueLag = started - enqueued
            Trace.beginSection("c47:keyDown+render")
            try {
                engine.keyDown(code)
                tickAndRender()
            } finally {
                Trace.endSection()
            }
            val engineTime = SystemClock.uptimeMillis() - started
            Log.i(TAG, "keyDown $code: queueLag=${queueLag}ms engine=${engineTime}ms")
        }
    }

    fun onKeyUp(code: String) {
        val enqueued = SystemClock.uptimeMillis()
        engineScope.launch {
            val started = SystemClock.uptimeMillis()
            val queueLag = started - enqueued
            Trace.beginSection("c47:keyUp+render")
            try {
                engine.keyUp(code)
                tickAndRender()
            } finally {
                Trace.endSection()
            }
            val engineTime = SystemClock.uptimeMillis() - started
            Log.i(TAG, "keyUp $code: queueLag=${queueLag}ms engine=${engineTime}ms")
        }
    }

    // Runs on the engine thread. Ticks the timer subsystem (required for
    // press-and-hold softkey row cycling, auto-repeat, shift cutoff), reads
    // the framebuffer if dirty, swaps buffers, and emits a new LcdFrame.
    private fun tickAndRender() {
        Trace.beginSection("c47:tickAndRender")
        try {
            engine.tick()
            val dirty = engine.renderArgb(argbBuffer, PIXEL_ON_ARGB, PIXEL_OFF_ARGB)
            if (dirty && LCD_EMIT_ENABLED) {
                argbBuffer.rewind()
                back.copyPixelsFromBuffer(argbBuffer)
                val newFront = back
                back = front
                front = newFront
                frameVersion++
                _lcd.value = LcdFrame(front, frameVersion)
            }
        } finally {
            Trace.endSection()
        }
    }

    // Slow background heartbeat for timer-driven updates (the engine's
    // timer.c drives things like the blinking cursor and the 3s shift
    // cutoff — both of which want the LCD refreshed without a keystroke
    // trigger). 100ms is plenty: the shortest timer the engine arms is
    // ~250ms (TO_CRS_BLINK), everything else is >= 750ms.
    private suspend fun runBackgroundPump() {
        while (engineScope.isActive) {
            tickAndRender()
            delay(BACKGROUND_PUMP_INTERVAL_MS)
        }
    }

    fun save() {
        engineScope.launch { engine.save() }
    }

    fun shutdown() {
        engineScope.cancel()
        engineExecutor.shutdown()
    }

    private fun makeBitmap(): Bitmap = Bitmap.createBitmap(
        C47Engine.SCREEN_WIDTH,
        C47Engine.SCREEN_HEIGHT,
        Bitmap.Config.ARGB_8888,
    ).also { it.eraseColor(PIXEL_OFF_ARGB) }

    companion object {
        private const val TAG = "c47-vm"

        // Palette matches CalculatorDisplayView — parchment off, near-black on.
        // Stored pre-packed as ARGB_8888 ints for the native renderer.
        private const val PIXEL_OFF_ARGB = 0xFFD2C89A.toInt()
        private const val PIXEL_ON_ARGB  = 0xFF1A1A1A.toInt()

        private const val BACKGROUND_PUMP_INTERVAL_MS = 100L

        // DIAGNOSTIC: flip to false to disable LCD bitmap emission entirely.
        // Tested 2026-04-20: disabling this did NOT fix the Choreographer
        // jank, so the Image pipeline isn't the cause. Left as a toggle for
        // future isolation experiments.
        private const val LCD_EMIT_ENABLED = true
    }
}

// `version` changes on every dirty emission so StateFlow equality doesn't
// dedupe us, forcing Compose to recompose the Image with the swapped-in
// front buffer.
data class LcdFrame(val bitmap: Bitmap, val version: Int)
