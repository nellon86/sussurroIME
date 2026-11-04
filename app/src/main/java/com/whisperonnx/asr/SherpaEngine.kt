package com.whisperonnx.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.util.concurrent.Executors

/** Runs the sherpa-onnx models. All the work happens in order, on one background thread. */
class SherpaEngine(private val directory: File, private val isWhisper: Boolean) {
    interface Listener {
        fun onResult(text: String, language: String)
        fun onError()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var recognizer: OfflineRecognizer? = null
    private var config: OfflineRecognizerConfig? = null

    fun load() {
        executor.execute {
            try {
                val newConfig = OfflineRecognizerConfig(modelConfig = modelConfig())
                recognizer = OfflineRecognizer(config = newConfig)
                config = newConfig
            } catch (t: Throwable) {
                Log.e(TAG, "Could not load the model", t)
            }
        }
    }

    fun transcribe(samples: FloatArray, language: String, translate: Boolean, listener: Listener) {
        executor.execute {
            try {
                val current = recognizer
                if (current == null) {
                    listener.onError()
                    return@execute
                }
                if (isWhisper) {
                    val whisper = config!!.modelConfig.whisper
                    whisper.language = if (language == "auto") "" else language
                    whisper.task = if (translate) "translate" else "transcribe"
                    current.setConfig(config!!)
                }
                val stream = current.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    current.decode(stream)
                    val result = current.getResult(stream)
                    val detected = result.lang.replace(Regex("[<|>]"), "")
                    listener.onResult(result.text.trim(), detected.ifEmpty { language })
                } finally {
                    stream.release()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Could not transcribe", t)
                listener.onError()
            }
        }
    }

    fun release() {
        executor.execute {
            recognizer?.release()
            recognizer = null
        }
        executor.shutdown()
    }

    private fun modelConfig(): OfflineModelConfig {
        fun path(name: String) = File(directory, name).path
        return if (isWhisper) {
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path("turbo-encoder.int8.onnx"),
                    decoder = path("turbo-decoder.int8.onnx"),
                    language = "",
                ),
                tokens = path("turbo-tokens.txt"),
                modelType = "whisper",
                numThreads = THREADS,
            )
        } else {
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path("encoder.int8.onnx"),
                    decoder = path("decoder.int8.onnx"),
                    joiner = path("joiner.int8.onnx"),
                ),
                tokens = path("tokens.txt"),
                modelType = "nemo_transducer",
                numThreads = THREADS,
            )
        }
    }

    private companion object {
        const val TAG = "SherpaEngine"
        const val SAMPLE_RATE = 16000
        const val THREADS = 4
    }
}
