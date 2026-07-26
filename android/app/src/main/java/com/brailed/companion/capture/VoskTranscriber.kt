package com.brailed.companion.capture

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline speech-to-text via Vosk (Apache-2.0). Feeds 16 kHz mono PCM to a
 * Kaldi recognizer and emits finished utterances.
 *
 * We surface only final results (not partials) so the device display shows
 * stable, complete phrases rather than flickering word-by-word guesses.
 * Construction loads the acoustic model, which is a few hundred ms — do it off
 * the main thread (AudioCaptureService builds this on a background thread).
 */
class VoskTranscriber(
    modelPath: String,
    private val onText: (String) -> Unit,
) : Transcriber {

    private val model = Model(modelPath)
    private val recognizer = Recognizer(model, SAMPLE_RATE)

    override fun feed(pcm: ShortArray, length: Int) {
        // acceptWaveForm returns true at an utterance boundary (silence gap).
        if (recognizer.acceptWaveForm(pcm, length)) {
            emit(recognizer.result)
        }
    }

    private fun emit(resultJson: String) {
        val text = runCatching { JSONObject(resultJson).optString("text").trim() }.getOrDefault("")
        if (text.isNotEmpty()) onText(text)
    }

    override fun close() {
        // Flush whatever was mid-utterance, then release native resources.
        runCatching { emit(recognizer.finalResult) }
        recognizer.close()
        model.close()
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f
    }
}
