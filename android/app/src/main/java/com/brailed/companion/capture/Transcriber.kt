package com.brailed.companion.capture

import kotlin.math.sqrt

/**
 * Consumes PCM audio and emits recognized text. This is the seam where a real
 * speech-to-text engine plugs in — the capture pipeline (AudioCaptureService)
 * is real and delivers 16 kHz mono PCM here; what turns that into words is
 * intentionally swappable.
 *
 * Drop-in options for [onText]-producing implementations:
 *   - Vosk (offline, small models, Apache-2.0) — feed [pcm] to a Recognizer.
 *   - whisper.cpp via JNI (offline, higher quality, heavier).
 *   - A cloud STT (Google/Deepgram/etc.) — buffer and stream chunks up.
 */
interface Transcriber {
    fun feed(pcm: ShortArray, length: Int)
    fun close() {}
}

/**
 * Honest placeholder: does NOT transcribe. It runs voice-activity detection on
 * the captured stream (RMS with hysteresis) and emits a marker when audio
 * starts and stops, so the end-to-end pipeline is demonstrably live without
 * pretending to produce a transcript. Replace with a real [Transcriber].
 */
class StubTranscriber(private val onText: (String) -> Unit) : Transcriber {

    private var speaking = false

    override fun feed(pcm: ShortArray, length: Int) {
        if (length <= 0) return
        var sum = 0.0
        for (i in 0 until length) {
            val s = pcm[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / length)

        if (!speaking && rms > START_THRESHOLD) {
            speaking = true
            onText("▶ audio playing — no speech-to-text engine configured")
        } else if (speaking && rms < STOP_THRESHOLD) {
            speaking = false
            onText("⏸ audio stopped")
        }
    }

    companion object {
        // 16-bit PCM RMS thresholds with hysteresis to avoid flicker.
        private const val START_THRESHOLD = 1500.0
        private const val STOP_THRESHOLD = 600.0
    }
}
