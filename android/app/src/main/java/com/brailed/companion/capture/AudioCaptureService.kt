package com.brailed.companion.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.brailed.companion.BrailedApp
import com.brailed.companion.core.ActiveLink
import com.brailed.companion.core.Bus

/**
 * Captures the phone's own playback audio (master prompt capability #2 / §4-B)
 * via MediaProjection + AudioPlaybackCapture, and streams recognized text to
 * the device over the active link ([ActiveLink]) — BLE or USB serial.
 *
 * IMPORTANT LIMITATIONS (Android, by design — see master prompt §10):
 *  - Requires API 29+. On older versions playback capture doesn't exist.
 *  - Only captures audio from apps whose AudioAttributes allow it. Media/video
 *    typically do; **voice/telephony call audio generally does NOT** (apps set
 *    ALLOW_CAPTURE_BY_NONE, and the telephony stack is exempt). So captioning a
 *    phone call this way will usually yield silence — that half of capability
 *    #2 isn't solvable with this API.
 *  - Speech-to-text is a pluggable [Transcriber]: [VoskTranscriber] (offline,
 *    model downloaded on first use) once ready, with [StubTranscriber] as the
 *    voice-activity fallback that runs until then / if the model can't load.
 */
class AudioCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var thread: Thread? = null

    // Starts as the voice-activity stub, then swapped for real Vosk speech-to-
    // text once its model is ready. @Volatile so the capture thread sees it.
    @Volatile private var transcriber: Transcriber = StubTranscriber(::emitCaption)

    private fun emitCaption(text: String) {
        Bus.log("caption: $text")
        ActiveLink.sendCaption(text)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Bus.log("Audio capture needs Android 10+")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || data == null) {
            Bus.log("No screen-capture consent; stopping audio capture")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data)
        startCapture()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO requested by the activity
    private fun startCapture() {
        val proj = projection ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE)) // ~0.5s headroom
            .setAudioPlaybackCaptureConfig(config)
            .build()

        record?.startRecording()
        capturing = true
        Bus.log("Audio capture started")

        thread = Thread {
            val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms frames
            while (capturing) {
                val n = record?.read(buf, 0, buf.size) ?: break
                if (n > 0) transcriber.feed(buf, n)
            }
        }.also { it.start() }

        startSpeechToText()
    }

    /** Provision the Vosk model off-thread and swap it in when ready; until then
     *  the stub runs so the pipeline is live from the first frame. */
    private fun startSpeechToText() {
        Thread {
            val dir = VoskModelProvider.ensureModel(applicationContext) ?: return@Thread
            val vosk = runCatching { VoskTranscriber(dir.absolutePath, ::emitCaption) }
                .getOrElse { Bus.log("Speech-to-text init failed: ${it.message}"); return@Thread }
            if (capturing) {
                transcriber = vosk
                Bus.log("Speech-to-text active (Vosk)")
            } else {
                vosk.close() // capture already stopped while the model loaded
            }
        }.start()
    }

    override fun onDestroy() {
        capturing = false
        thread?.join(500)
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        record?.release()
        record = null
        projection?.stop()
        projection = null
        transcriber.close()
        Bus.log("Audio capture stopped")
        super.onDestroy()
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, BrailedApp.CHANNEL_ID)
            .setContentTitle("Brailed")
            .setContentText("Captioning phone audio")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .build()

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 2
        private const val SAMPLE_RATE = 16000
    }
}
