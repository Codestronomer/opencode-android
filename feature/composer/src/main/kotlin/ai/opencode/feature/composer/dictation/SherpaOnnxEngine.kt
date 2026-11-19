package ai.opencode.feature.composer.dictation

import ai.opencode.core.log.AppLog
import ai.opencode.feature.composer.BufferConverter
import ai.opencode.feature.composer.DictationAudioFormat
import ai.opencode.feature.composer.DictationError
import ai.opencode.feature.composer.DictationInput
import ai.opencode.feature.composer.DictationInputSink
import ai.opencode.feature.composer.DictationModel
import ai.opencode.feature.composer.DictationModelNotInstalled
import ai.opencode.feature.composer.DictationResult
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bundled engine (docs/android-12-support.md §10.3): Android's 33-only recognizer machinery
 * replaced by sherpa-onnx's streaming Zipformer, fed the same 16 kHz mono Int16 PCM the
 * `SpeechRecognizer` pipe gets. One native recognizer stays loaded (its model is ~68 MB on
 * disk); each recording is one `OnlineStream`, decoded as the audio lands. Endpoint detection
 * plays the segmented session's part: a pause finalizes a segment and the next words follow it
 * after a space, exactly like the platform engine's segment results.
 *
 * The model's languages are the engine's: this Zipformer is English. Other locales fall back to
 * the platform recognizer (Android 12's graceful path) — as does any press whose model can't be
 * downloaded or whose engine can't load.
 */
internal class SherpaOnnxEngine(
    private val models: ModelManager,
    private val platform: () -> SpeechRecognizerEngine,
) : SpeechRecognizerEngine {
    private val mutex = Mutex()
    private var recognizer: OnlineRecognizer? = null

    override suspend fun loadModel(install: Boolean): DictationModel {
        if (!models.isPresent()) {
            // Warming up never downloads: the first press that needs the model fetches it.
            if (!install) throw DictationModelNotInstalled()
            try {
                models.ensureDownloaded()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppLog.voice.error("dictation_model_unavailable type=${error.javaClass.simpleName}")
                return platform().loadModel(install)
            }
        }
        val online = loadedRecognizer() ?: return platform().loadModel(install)
        return Session(online, mutex).model()
    }

    /** Loads the recognizer once; every load joins it. Returns null when it can't be loaded. */
    private suspend fun loadedRecognizer(): OnlineRecognizer? = mutex.withLock {
        recognizer?.let { return it }
        try {
            OnlineRecognizer(
                assetManager = null,
                config = sherpaConfig(
                    encoder = models.encoder().absolutePath,
                    decoder = models.decoder().absolutePath,
                    joiner = models.joiner().absolutePath,
                    tokens = models.tokens().absolutePath,
                ),
            ).also { recognizer = it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.voice.error("dictation_engine_load_failed type=${error.javaClass.simpleName}")
            null
        }
    }

    /**
     * One recording against the shared recognizer: its own stream, decoded by
     * a pump that also hands the platform engine's result shapes back —
     * partials as the volatile tail, segment finals, the results channel
     * closing on end (or with an error).
     */
    private class Session(private val recognizer: OnlineRecognizer, private val native: Mutex) {
        private var queue: Channel<ShortArray>? = null
        private var pump: Job? = null

        fun model(): DictationModel = DictationModel(
            start = { input, format -> start(input, format) },
            finish = { pump?.join() },
            cancel = { cancel() },
        )

        private suspend fun start(input: DictationInput, format: DictationAudioFormat): ReceiveChannel<DictationResult> {
            val channel = Channel<DictationResult>(Channel.UNLIMITED)
            val stream = recognizer.native { createStream() }
            val queue = Channel<ShortArray>(Channel.UNLIMITED)
            this.queue = queue
            input.connect(BufferConverter(format, TARGET), Sink(queue))
            pump = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { pump(stream, queue, channel) }
            return channel
        }

        private class Sink(private val queue: SendChannel<ShortArray>) : DictationInputSink {
            override fun yield(samples: ShortArray) {
                queue.trySend(samples)
            }

            override fun finish() {
                queue.close()
            }
        }

        private suspend fun pump(stream: OnlineStream, queue: Channel<ShortArray>, channel: SendChannel<DictationResult>) {
            var hasFinal = false
            var lastPartial: String? = null
            fun spaced(text: String) = if (hasFinal && text.isNotEmpty()) " $text" else text
            try {
                for (samples in queue) {
                    stream.acceptWaveform(toFloats(samples), TARGET.sampleRate)
                    recognizer.native { while (isReady(stream)) decode(stream) }
                    val partial = recognizer.native { getResult(stream).text }
                    if (partial.isNotBlank() && partial != lastPartial) {
                        lastPartial = partial
                        channel.trySend(DictationResult(spaced(partial), isFinal = false))
                    }
                    if (recognizer.native { isEndpoint(stream) }) {
                        val text = recognizer.native { getResult(stream).text }
                        if (text.isNotBlank()) {
                            channel.trySend(DictationResult(spaced(text), isFinal = true))
                            hasFinal = true
                        }
                        recognizer.native { reset(stream) }
                        lastPartial = null
                    }
                }
                val final = recognizer.native {
                    stream.inputFinished()
                    while (isReady(stream)) decode(stream)
                    getResult(stream).text
                }
                if (final.isNotBlank()) channel.trySend(DictationResult(spaced(final), isFinal = true))
                channel.close()
            } catch (cancellation: CancellationException) {
                channel.close()
                throw cancellation
            } catch (error: Throwable) {
                AppLog.voice.error("Dictation recognizer failed (${error.javaClass.simpleName})")
                channel.close(DictationError.Recognizer(FAILED))
            } finally {
                // The recognizer itself is shared between recordings: only this stream goes.
                native.withLock { runCatching { stream.release() } }
            }
        }

        private suspend fun <T> OnlineRecognizer.native(block: OnlineRecognizer.() -> T): T =
            native.withLock { block() }

        private suspend fun cancel() {
            val pump = pump ?: return
            queue?.cancel()
            pump.cancel()
            pump.join()
        }
    }

    private companion object {
        val TARGET = DictationAudioFormat(sampleRate = 16_000, channelCount = 1)
        val FAILED = -1

        fun toFloats(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }

        fun sherpaConfig(encoder: String, decoder: String, joiner: String, tokens: String) =
            OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = encoder, decoder = decoder, joiner = joiner),
                    tokens = tokens,
                    numThreads = 2,
                ),
                enableEndpoint = true,
            )
    }
}
