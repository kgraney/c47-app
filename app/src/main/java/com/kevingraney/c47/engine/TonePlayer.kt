package com.kevingraney.c47.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.min
import kotlin.math.sin

// Plays the C47 engine's TONE / BEEP pitches on the phone speaker.
//
// The engine's HAL contract (see c43-source/src/c47-dmcp/hal/audio.c:8) is
// that audioTone(freq) plays the tone and blocks ~250ms before returning.
// fnBeep relies on this — it calls _tonePlay 4 times back-to-back and
// expects them to play sequentially, not all at once.
//
// Called from the JNI callback on the engine thread (a dedicated background
// thread, never the UI thread), so blocking here is safe.
class TonePlayer {

    @Volatile private var current: AudioTrack? = null
    @Volatile private var shutdown: Boolean = false

    // millihertz: e.g. 440000 = 440Hz (engine convention).
    fun playTone(millihertz: Int) {
        if (shutdown || millihertz <= 0) return
        val freqHz = millihertz / 1000.0
        if (freqHz < 20.0 || freqHz > SAMPLE_RATE / 2.0) {
            Log.w(TAG, "skipping out-of-range tone: ${freqHz}Hz")
            return
        }

        val samples = synthesize(freqHz, DURATION_MS)
        val bytes = samples.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(samples, 0, samples.size)
            current = track
            track.play()
            // Block for the playback duration — matches DMCP's sys_delay(250)
            // in audioTone, so fnBeep's four notes don't overlap.
            Thread.sleep(DURATION_MS.toLong())
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "playTone failed", t)
        } finally {
            current = null
            try { track.stop() } catch (_: IllegalStateException) {}
            track.release()
        }
    }

    fun shutdown() {
        shutdown = true
        current?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            try { it.release() } catch (_: Throwable) {}
        }
        current = null
    }

    private fun synthesize(freqHz: Double, durationMs: Int): ShortArray {
        val n = (SAMPLE_RATE.toLong() * durationMs / 1000L).toInt()
        val out = ShortArray(n)
        val twoPiF = 2.0 * Math.PI * freqHz / SAMPLE_RATE
        val amp = Short.MAX_VALUE * GAIN
        // Linear attack/release ramps mute the click you'd otherwise get
        // from starting and ending mid-cycle.
        val rampSamples = SAMPLE_RATE * RAMP_MS / 1000
        val ramp = min(rampSamples, n / 2)
        for (i in 0 until n) {
            var sample = sin(twoPiF * i) * amp
            if (i < ramp) {
                sample *= i.toDouble() / ramp
            } else if (i >= n - ramp) {
                sample *= (n - 1 - i).toDouble() / ramp
            }
            out[i] = sample.toInt().toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "c47-tone"
        private const val SAMPLE_RATE = 44100
        private const val DURATION_MS = 250
        private const val RAMP_MS = 10
        private const val GAIN = 0.5
    }
}
