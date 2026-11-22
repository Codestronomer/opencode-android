package ai.opencode.feature.composer.dictation

import ai.opencode.core.log.AppLog
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * The bundled engine's model, in `files/dictation-model/`. sherpa-onnx publishes no Android model
 * package, so the model ships as loose ONNX files: [ModelFile]s are downloaded on the first
 * dictation press that needs them (the WP-04 Preparing phase covers the wait), with
 * checkpoint-resume into `.part` files, bounded retries and one shared time budget, then renamed
 * into place atomically. Plain OkHttp, not `OpenCodeServer`: this is not an OpenCode request.
 */
internal class ModelManager(private val context: Context, private val http: OkHttpClient = defaultClient()) {
    private val directory: File get() = File(context.filesDir, "dictation-model")

    /** Whether every file of the model is there in full. */
    fun isPresent(): Boolean = FILES.all { file -> File(directory, file.name).length() >= file.minimumBytes }

    /**
     * Downloads the model files that aren't there yet. Resumable: a partial
     * file keeps its `.part` bytes and continues from where it stopped.
     */
    suspend fun ensureDownloaded() = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET
        // A model upgrade leaves the previous revision's files behind: remove them, the
        // new names are what counts.
        val current = FILES.mapTo(mutableSetOf()) { it.name }
        val checkpoints = FILES.mapTo(mutableSetOf()) { it.name + ".part" }
        directory.listFiles()?.forEach { if (it.name !in current && it.name !in checkpoints) it.delete() }
        for (file in FILES) {
            val target = File(directory, file.name)
            if (target.length() >= file.minimumBytes) continue
            download(file, File(directory, file.name + ".part"), target, deadline)
        }
        AppLog.voice.info("dictation_model_ready bytes=${FILES.sumOf { File(directory, it.name).length() }}")
    }

    private suspend fun download(file: ModelFile, part: File, target: File, deadline: Long) {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                fetch(file, part, target)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val retrying = attempt < MAX_ATTEMPTS && System.currentTimeMillis() < deadline
                AppLog.voice.error(
                    "dictation_model_download_failed file=${file.name} attempt=$attempt" +
                        " type=${error.javaClass.simpleName} retrying=${if (retrying) 1 else 0}",
                )
                if (!retrying) throw IOException("Couldn’t download the dictation model", error)
                delay(min(BACKOFF * attempt, deadline - System.currentTimeMillis()).coerceAtLeast(0))
            }
        }
    }

    private suspend fun fetch(file: ModelFile, part: File, target: File) {
        val offset = part.length()
        val request = Request.Builder()
            .url(file.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        http.newCall(request).execute().use { response ->
            val resumed = response.code == 206
            if (response.code != 200 && !resumed) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("HTTP ${response.code} with no body")
            var written = if (resumed) offset else 0
            var logged = written
            body.byteStream().use { input ->
                // A 206 appends to the checkpoint; a plain 200 (Range ignored) truncates and restarts.
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - logged >= LOG_EVERY_BYTES) {
                            logged = written
                            AppLog.voice.info("dictation_model_downloading file=${file.name} bytes=$written")
                        }
                    }
                    output.flush()
                }
            }
            if (!part.renameTo(target)) {
                // renameTo fails only across volumes; both live in filesDir, so this is unexpected.
                throw IOException("Couldn’t place ${file.name}")
            }
            AppLog.voice.info("dictation_model_download file=${file.name} status=${response.code} bytes=$written")
        }
    }

    /** Absolute paths of the model files, for the recognizer config. Only valid while [isPresent]. */
    fun encoder(): File = file(ENCODER)
    fun decoder(): File = file(DECODER)
    fun joiner(): File = file(JOINER)
    fun tokens(): File = file(TOKENS)

    private fun file(name: String) = File(directory, name)

    private class ModelFile(val name: String, val url: String, val minimumBytes: Long)

    private companion object {
        const val TOKENS = "tokens.txt"
        const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
        const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"

        // Streaming Zipformer English (LibriSpeech + GigaSpeech), int8, full streaming
        // context, pinned revision of the HF repo (2023-06-21); the byte counts are that
        // revision's sizes, used as minimum sanity checks. Small files first, so a partial
        // download keeps what it got.
        val FILES = listOf(
            ModelFile(
                TOKENS,
                "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/resolve/main/$TOKENS",
                5_048,
            ),
            ModelFile(
                DECODER,
                "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/resolve/main/$DECODER",
                539_246,
            ),
            ModelFile(
                JOINER,
                "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/resolve/main/$JOINER",
                259_335,
            ),
            ModelFile(
                ENCODER,
                "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/resolve/main/$ENCODER",
                187_823_992,
            ),
        )
        val MAX_ATTEMPTS = 3
        val DOWNLOAD_BUDGET = 15L * 60 * 1000
        val BACKOFF = 2_000L
        val LOG_EVERY_BYTES = 16L * 1024 * 1024

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
