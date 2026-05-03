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

    private val tonePlayer = TonePlayer()

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

    // Power state. When off, the off-image has been painted into the LCD
    // framebuffer and the tick loop is gated — the engine keeps its
    // internal state but doesn't redraw over the off-image. Any key press
    // wakes the calculator before being forwarded to the engine.
    private val _isPoweredOn = MutableStateFlow(true)
    val isPoweredOn: StateFlow<Boolean> = _isPoweredOn.asStateFlow()

    // Records the keycode that woke the calculator from the off state, so
    // its matching key-up can be swallowed (the engine never saw the down,
    // so it shouldn't see the up either — would confuse its held-key state).
    // Touched only from the engine thread.
    private var wakeKey: String? = null

    fun init(stateDir: File) {
        engineScope.launch {
            engine.init(stateDir)
            engine.setTonePlayer(tonePlayer)
            runBackgroundPump()
        }
    }

    // Toggle between the off-image and live engine rendering. Safe to call
    // from any thread — dispatched onto the engine thread.
    fun togglePower() {
        engineScope.launch {
            if (_isPoweredOn.value) doPowerOff() else doPowerOn()
        }
    }

    // Must run on engineScope.
    private fun doPowerOff() {
        engine.powerOff()
        _isPoweredOn.value = false
        // powerOff marked the framebuffer dirty; emit the off-image frame.
        emitIfDirty()
    }

    // Must run on engineScope.
    private fun doPowerOn() {
        _isPoweredOn.value = true
        engine.powerOn()           // refreshScreen() repaints the live UI
        emitIfDirty()
    }

    fun onKeyDown(code: String) {
        val enqueued = SystemClock.uptimeMillis()
        engineScope.launch {
            // Any key press while powered off wakes the calculator and is
            // consumed (not forwarded) — matches hardware behavior.
            if (!_isPoweredOn.value) {
                wakeKey = code
                doPowerOn()
                return@launch
            }
            // HP-42S / R47 OFF combo: orange shift (f) + EXIT. The engine
            // tracks shift-f state itself (c47.c:51 shiftF); peek at it
            // before forwarding the EXIT keystroke, since the engine will
            // clear shiftF as soon as it consumes the key.
            val offCombo = code == KEY_EXIT && engine.isShiftFArmed()
            val started = SystemClock.uptimeMillis()
            val queueLag = started - enqueued
            Trace.beginSection("c47:keyDown+render")
            try {
                engine.keyDown(code)
                if (offCombo) {
                    // Paint the off-image on top of whatever the engine
                    // drew in response. Key-up is still forwarded below so
                    // the engine's held-key bookkeeping stays balanced.
                    doPowerOff()
                } else {
                    tickAndRender()
                }
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
            // Swallow the key-up that pairs with the wake keystroke — the
            // engine never saw the down.
            if (wakeKey == code) {
                wakeKey = null
                return@launch
            }
            val started = SystemClock.uptimeMillis()
            val queueLag = started - enqueued
            Trace.beginSection("c47:keyUp+render")
            try {
                engine.keyUp(code)
                // If powered off (either we just turned off or we're still
                // asleep), don't emit a frame — the off-image owns the UI.
                if (_isPoweredOn.value) tickAndRender()
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
            emitIfDirty()
        } finally {
            Trace.endSection()
        }
    }

    // Read the engine's framebuffer if it's been marked dirty since the
    // last read, swap buffers, and emit a new LcdFrame. Split out from
    // tickAndRender so power-on/off transitions (which write directly to
    // the framebuffer without the engine's involvement) can flush the
    // off-image to the UI.
    private fun emitIfDirty() {
        val dirty = engine.renderArgb(argbBuffer, PIXEL_ON_PIX, PIXEL_OFF_PIX)
        if (dirty && LCD_EMIT_ENABLED) {
            argbBuffer.rewind()
            back.copyPixelsFromBuffer(argbBuffer)
            val newFront = back
            back = front
            front = newFront
            frameVersion++
            _lcd.value = LcdFrame(front, frameVersion)
        }
    }

    // Slow background heartbeat for timer-driven updates (the engine's
    // timer.c drives things like the blinking cursor and the 3s shift
    // cutoff — both of which want the LCD refreshed without a keystroke
    // trigger). 100ms is plenty: the shortest timer the engine arms is
    // ~250ms (TO_CRS_BLINK), everything else is >= 750ms.
    private suspend fun runBackgroundPump() {
        while (engineScope.isActive) {
            // While powered off, the off-image owns the framebuffer and the
            // engine should not be running timers or repainting — skip the
            // whole pump cycle. The tick resumes as soon as power comes back.
            if (_isPoweredOn.value) tickAndRender()
            delay(BACKGROUND_PUMP_INTERVAL_MS)
        }
    }

    fun save() {
        engineScope.launch { engine.save() }
    }

    fun shutdown() {
        tonePlayer.shutdown()
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

        // Engine-native flat index for the EXIT key (see INTERACTIVITY_PLAN
        // key map). Orange-shift + this key is the R47's OFF combo.
        private const val KEY_EXIT = "32"

        // Palette matches CalculatorDisplayView — parchment off, near-black on.
        //
        // PIXEL_*_ARGB is for Bitmap.eraseColor, which takes AARRGGBB.
        //
        // PIXEL_*_PIX is the same color pre-packed for the native renderer,
        // which stores uint32s directly into a little-endian direct buffer
        // that Bitmap.copyPixelsFromBuffer reads as RGBA_8888 (bytes R,G,B,A
        // in memory). On LE that means the int is A<<24|B<<16|G<<8|R —
        // i.e. ARGB with R and B swapped. Without this swap the parchment
        // off-pixel renders as light blue.
        private const val PIXEL_OFF_ARGB = 0xFFD2C89A.toInt()
        private const val PIXEL_ON_ARGB  = 0xFF1A1A1A.toInt()
        private const val PIXEL_OFF_PIX  = 0xFF9AC8D2.toInt()
        private const val PIXEL_ON_PIX   = 0xFF1A1A1A.toInt()  // monochrome, no swap

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
