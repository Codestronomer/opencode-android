package ai.opencode.feature.composer.dictation

import ai.opencode.feature.composer.BufferConverter
import ai.opencode.feature.composer.DictationAudioFormat
import ai.opencode.feature.composer.DictationError
import ai.opencode.feature.composer.DictationInput
import ai.opencode.feature.composer.DictationInputSink
import ai.opencode.feature.composer.DictationModel
import ai.opencode.feature.composer.DictationModelNotInstalled
import ai.opencode.feature.composer.DictationResult
import ai.opencode.feature.composer.R
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.RequiresApi
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The 33+ path: Android's `SpeechRecognizer` (the on-device recognizer when the device has one,
 * else the default service preferring offline), fed the app's own microphone audio so the mic
 * goes live at the touch and streams live partial results. */
internal class PlatformSpeechRecognizerEngine(context: Context) : SpeechRecognizerEngine {
    private val setup = SpeechSetup(context)

    override suspend fun loadModel(install: Boolean): DictationModel = setup.loadedModel(install).model()
}

/**
 * The recognizer's language, install and kind (on-device or not), resolved once.
 * Every recognizer call runs on the main thread, as `SpeechRecognizer` requires.
 */
private class SpeechSetup(private val context: Context) {
    private val mutex = Mutex()

    private data class Language(val tag: String, val onDevice: Boolean, val isInstallFallback: Boolean)
    private var resolved: Language? = null

    /**
     * A new recognizer with its language ready, for one recording. Without
     * [install], an on-device language that isn't installed fails the load
     * with [DictationModelNotInstalled] and nothing is resolved yet.
     */
    suspend fun loadedModel(install: Boolean): LoadedSpeech {
        val (language, onDevice, isInstallFallback) = setup(install)
        return withContext(Dispatchers.Main) {
            val recognizer = if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            LoadedSpeech(recognizer, language, onDevice, isInstallFallback)
        }
    }

    /**
     * One shared resolution and install: every load joins the one under way. A
     * failure isn't kept, so a later attempt retries.
     */
    private suspend fun setup(install: Boolean): Language = mutex.withLock {
        resolved?.let { return it }
        val onDevice = withContext(Dispatchers.Main) { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
        val available = withContext(Dispatchers.Main) { SpeechRecognizer.isRecognitionAvailable(context) }
        if (!onDevice && !available) throw DictationError.LocaleUnsupported()
        val support = withTimeoutOrNull(INSTALL_WAIT) { support(onDevice) }
        val supported = when {
            support == null -> null
            onDevice -> support.installedOnDeviceLanguages + support.pendingOnDeviceLanguages + support.supportedOnDeviceLanguages
            else -> support.onlineLanguages + support.installedOnDeviceLanguages
        }
        val language = bestLocale(supported)
        val needsInstall = onDevice && support != null && language !in support.installedOnDeviceLanguages
        // Warming up never downloads: `triggerModelDownload` shows a system
        // dialog, which belongs to the press that needs the language.
        if (needsInstall && !install) throw DictationModelNotInstalled()
        val installed = !needsInstall || withTimeoutOrNull(INSTALL_WAIT) {
            install(language) ?: awaitInstalled(language)
        } == true
        if (!installed && !available) throw DictationError.LocaleUnsupported()
        val result = Language(language, onDevice = installed && onDevice, isInstallFallback = !installed)
        resolved = result
        result
    }

    /** What the recognizer supports, or nil when it can't say (dictation then tries the current language). */
    private suspend fun support(onDevice: Boolean): RecognitionSupport? = withContext(Dispatchers.Main) {
        // Android 12 has no support check at all: nil says so, and dictation tries the recognizer.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext null
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { continuation ->
                recognizer.checkRecognitionSupport(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } finally {
            recognizer.destroy()
        }
    }

    /** Downloads the on-device model for [language]: true installed, null scheduled, false when it won't be there. */
    private suspend fun install(language: String): Boolean? = withContext(Dispatchers.Main) {
        // Android 12 can't request a download: the press falls through to the recognizer,
        // which surfaces a missing language from its error codes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext null
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 13 has no download listener: request it and let awaitInstalled poll.
                recognizer.triggerModelDownload(intent)
                null
            } else {
                awaitModelDownload { listener -> recognizer.triggerModelDownload(intent, mainExecutor, listener) }
            }
        } finally {
            recognizer.destroy()
        }
    }

    /**
     * A scheduled download of [language]'s model (the system asked the user, then downloads it):
     * waits while dictation shows Preparing for it to be installed. The caller bounds both the
     * request and this polling by [INSTALL_WAIT]. False if no longer pending (declined or failed)
     * after [INSTALL_GRACE].
     */
    private suspend fun awaitInstalled(language: String): Boolean {
        val started = SystemClock.uptimeMillis()
        while (true) {
            val elapsed = (SystemClock.uptimeMillis() - started).milliseconds
            delay(INSTALL_POLL)
            val support = support(onDevice = true) ?: break
            if (support.installedOnDeviceLanguages.any { it.equals(language, ignoreCase = true) }) {
                AppLog.voice.info("dictation_language_installed waited=${elapsed.inWholeMilliseconds}ms")
                return true
            }
            val pending = support.pendingOnDeviceLanguages.any { it.equals(language, ignoreCase = true) }
            if (!pending && elapsed > INSTALL_GRACE) break
        }
        AppLog.voice.notice("dictation_language_not_installed")
        return false
    }

    private fun bestLocale(supported: List<String>?): String {
        val current = Locale.getDefault()
        val tag = current.toLanguageTag()
        if (supported == null) return tag
        if (supported.any { it.equals(tag, ignoreCase = true) }) return tag
        supported.firstOrNull { Locale.forLanguageTag(it).language == current.language }?.let { return it }
        return supported.firstOrNull { it.equals("en-US", ignoreCase = true) } ?: throw DictationError.LocaleUnsupported()
    }

    private companion object {
        val mainExecutor = Executor(Handler(Looper.getMainLooper())::post)
        val INSTALL_POLL = 1.seconds
        val INSTALL_GRACE = 15.seconds
        val INSTALL_WAIT = 5.minutes
    }
}

/** The service can retain the listener after destroy(). Detach the waiting press even if it never calls back. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal suspend fun awaitModelDownload(trigger: (ModelDownloadListener) -> Unit): Boolean? {
    val result = CompletableDeferred<Boolean?>(currentCoroutineContext()[Job])
    try {
        trigger(object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) = Unit
            override fun onSuccess() { result.complete(true) }
            override fun onScheduled() { result.complete(null) }
            override fun onError(error: Int) {
                if (result.complete(false)) AppLog.voice.error("Couldn’t install the dictation language ($error)")
            }
        })
        return result.await()
    } finally {
        result.cancel()
    }
}

/** A recognizer ready for one recording. Touched only on the main thread. */
private class LoadedSpeech(
    private val recognizer: SpeechRecognizer,
    private val language: String,
    private val onDevice: Boolean,
    private val isInstallFallback: Boolean,
) {
    private val done = CompletableDeferred<Unit>()
    private var results: Channel<DictationResult>? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var writer: PipeWriter? = null
    private var destroyed = false

    fun model(): DictationModel = DictationModel(
        start = { input, format -> withContext(Dispatchers.Main) { start(input, format) } },
        finish = { done.await() },
        isInstallFallback = isInstallFallback,
        cancel = {
            withContext(Dispatchers.Main) {
                writer?.close()
                if (!destroyed) recognizer.cancel()
                end(null)
            }
        },
    )

    private fun start(input: DictationInput, format: DictationAudioFormat): ReceiveChannel<DictationResult> {
        val channel = Channel<DictationResult>(Channel.UNLIMITED)
        results = channel
        val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        readEnd = read
        val writer = PipeWriter(write).also { this.writer = it }
        recognizer.setRecognitionListener(Listener(channel))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET.sampleRate)
            // One session for the whole recording: it ends with the audio.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        recognizer.startListening(intent)
        input.connect(BufferConverter(format, TARGET), writer)
        return channel
    }

    /** Ends the results (with [failure], if any) and lets the recognizer go. Main thread. */
    private fun end(failure: Throwable?) {
        results?.close(failure)
        writer?.close()
        if (!destroyed) {
            destroyed = true
            recognizer.destroy()
        }
        runCatching { readEnd?.close() }
        done.complete(Unit)
    }

    private inner class Listener(private val channel: Channel<DictationResult>) : RecognitionListener {
        /** Whether a finalized segment has been delivered: later words follow it after a space. */
        private var hasFinal = false

        private fun spaced(text: String) = if (hasFinal && text.isNotEmpty()) " $text" else text

        private fun text(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        override fun onPartialResults(partialResults: Bundle?) {
            val text = text(partialResults) ?: return
            channel.trySend(DictationResult(spaced(text), isFinal = false))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = text(segmentResults)?.takeIf { it.isNotBlank() } ?: return
            channel.trySend(DictationResult(spaced(text), isFinal = true))
            hasFinal = true
        }

        override fun onEndOfSegmentedSession() = end(null)

        override fun onResults(results: Bundle?) {
            text(results)?.takeIf { it.isNotBlank() }?.let {
                channel.trySend(DictationResult(spaced(it), isFinal = true))
                hasFinal = true
            }
            end(null)
        }

        override fun onError(error: Int) {
            when (error) {
                // Nothing (more) was said: the recording simply ends.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> end(null)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> end(DictationError.MicrophoneDenied())
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    end(DictationError.LocaleUnsupported())
                else -> {
                    AppLog.voice.error("Dictation recognizer failed ($error, onDevice=${if (onDevice) 1 else 0})")
                    end(DictationError.Recognizer(error))
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        val TARGET = DictationAudioFormat(sampleRate = 16_000, channelCount = 1)
    }
}

/**
 * Writes converted audio into the recognizer's pipe on its own thread, so the
 * capture thread never blocks on a recognizer that reads slowly. [finish]
 * closes the pipe once what's queued is written: the recognizer's end of input.
 */
private class PipeWriter(descriptor: ParcelFileDescriptor) : DictationInputSink {
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val stream = FileOutputStream(descriptor.fileDescriptor)
    private val descriptor = descriptor

    @Volatile
    private var closed = false

    private val thread = Thread({
        try {
            while (true) {
                val samples = queue.take()
                if (samples === END || closed) break
                val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asShortBuffer().put(samples)
                stream.write(bytes.array())
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }, "ai.opencode.dictation-pipe").apply { start() }

    override fun yield(samples: ShortArray) {
        if (!closed) queue.put(samples)
    }

    override fun finish() {
        queue.put(END)
    }

    /** Stops at once, dropping what's queued. */
    fun close() {
        closed = true
        queue.clear()
        queue.put(END)
    }

    private companion object {
        val END = ShortArray(0)
    }
}
