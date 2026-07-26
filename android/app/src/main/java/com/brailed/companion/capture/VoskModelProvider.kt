package com.brailed.companion.capture

import android.content.Context
import com.brailed.companion.core.Bus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Ensures the Vosk small-English model is present on device, downloading and
 * unzipping it on first use (~40 MB). It's fetched at runtime rather than
 * bundled so the APK and repo stay small.
 *
 * Swap MODEL_NAME/MODEL_URL for another Vosk model (e.g. a larger or
 * different-language one) to change recognition — nothing else changes.
 */
object VoskModelProvider {

    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

    /** Returns the unpacked model directory, or null if it couldn't be obtained. */
    fun ensureModel(context: Context): File? {
        val dir = File(context.filesDir, MODEL_NAME)
        if (File(dir, "am").isDirectory) return dir // already unpacked
        return runCatching { download(context, dir) }.getOrElse {
            Bus.log("Speech model download failed: ${it.message}")
            dir.deleteRecursively()
            null
        }
    }

    private fun download(context: Context, dir: File): File {
        Bus.log("Downloading speech model (~40 MB, first use only)…")
        val zip = File(context.cacheDir, "$MODEL_NAME.zip")
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
        }
        conn.inputStream.use { input -> zip.outputStream().use { input.copyTo(it) } }

        Bus.log("Unpacking speech model…")
        val root = context.filesDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(context.filesDir, entry.name)
                // Zip-slip guard: reject entries that escape filesDir.
                if (out.canonicalPath.startsWith(root)) {
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
                entry = zis.nextEntry
            }
        }
        zip.delete()
        Bus.log("Speech model ready")
        return dir
    }
}
