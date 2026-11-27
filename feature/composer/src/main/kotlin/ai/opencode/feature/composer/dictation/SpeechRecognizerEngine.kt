package ai.opencode.feature.composer.dictation

import ai.opencode.feature.composer.DictationModel
import android.content.Context
import android.os.Build

/**
 * The on-device dictation engine seam (docs/android-12-support.md §10.3): where the words come
 * from. Android 13+ keeps the platform `SpeechRecognizer` path; Android 12 (and 12L) gets the
 * bundled sherpa-onnx engine, so a real on-device recognizer exists there too. The operations
 * match what `DictationClient`'s session shape needs: warm-up ([loadModel] with `install` false),
 * then per recording a [DictationModel] fed 16 kHz mono Int16 PCM (the `BufferConverter` output,
 * through `DictationInput`), streaming partial results as the volatile tail, finals after a
 * segment, errors on the results channel and [DictationModel.finish]/[DictationModel.cancel] to
 * close. Nothing here knows a particular recognizer's API.
 */
internal interface SpeechRecognizerEngine {
    /**
     * A recognizer with its language ready, for one recording. With [install]
     * false (warming up between presses) a language not yet ready fails the
     * load instead of downloading it; a press loads with `install` true.
     */
    suspend fun loadModel(install: Boolean): DictationModel
}

/** 33+ uses Android's recognizer machinery unchanged; Android 12 gets the bundled engine. */
internal fun speechRecognizerEngine(context: Context): SpeechRecognizerEngine {
    val app = context.applicationContext
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PlatformSpeechRecognizerEngine(app)
    } else {
        SherpaOnnxEngine(ModelManager(app)) { PlatformSpeechRecognizerEngine(app) }
    }
}
