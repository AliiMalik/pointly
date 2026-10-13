package pk.rehnuma.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.rehnuma.app.config.AppConfig
import pk.rehnuma.app.net.PlannerClient
import java.io.ByteArrayOutputStream

/** SPEC 5.10 result. Exactly one of the texts is usually filled. */
data class SpeechResult(
    val textUrdu: String?,
    val textRoman: String?,
    val source: String, // "device" | "cloud"
    val ms: Long,
    val error: String? = null,
) {
    /** What the planner gets: Roman Urdu when available, otherwise the Urdu-script text. */
    val bestText: String?
        get() = textRoman?.takeIf { it.isNotBlank() } ?: textUrdu?.takeIf { it.isNotBlank() }
}

/**
 * Urdu speech input (SPEC 5.10). Android `SpeechRecognizer` with `ur-PK` first; the backend
 * `/v1/stt` (16 kHz mono WAV recorded while the bubble is held) when the device can't do Urdu or
 * Settings asks for Cloud. Main thread only.
 */
class SpeechInput(
    private val context: Context,
    private val config: () -> AppConfig,
    private val client: PlannerClient,
    private val scope: CoroutineScope,
) {
    interface Listener {
        fun onPartial(text: String)
        fun onTranscribing()
        fun onResult(result: SpeechResult)
    }

    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var recognizer: SpeechRecognizer? = null
    private var recordJob: Job? = null
    @Volatile
    private var recording = false
    private var startedAt = 0L
    private var holding = false
    private var delivered = false

    /** Set after the device recognizer reports that Urdu is not supported; later sessions use the cloud. */
    private var deviceUrduBroken = false

    val isListening: Boolean
        get() = listener != null && !delivered

    fun start(l: Listener) {
        cancel()
        listener = l
        delivered = false
        holding = true
        startedAt = SystemClock.elapsedRealtime()
        if (!hasMic()) {
            deliver(SpeechResult(null, null, "device", 0, error = "microphone permission"))
            return
        }
        val useDevice = config().voiceIn == AppConfig.VOICE_DEVICE && !deviceUrduBroken &&
            SpeechRecognizer.isRecognitionAvailable(context)
        if (useDevice) startDevice() else startCloud()
    }

    /** The user let go of the bubble (or tapped again in Tap mode). */
    fun stop() {
        if (listener == null || delivered) return
        holding = false
        when {
            recognizer != null -> {
                recognizer?.stopListening()
                // Some recognizers never answer after stop; don't leave the user waiting.
                main.postDelayed(stopTimeout, RESULT_TIMEOUT_MS)
            }
            recording -> recording = false // the recording loop uploads what it has
        }
    }

    /** Abort without a result (cancel button, new session). */
    fun cancel() {
        main.removeCallbacks(stopTimeout)
        recognizer?.let {
            runCatching { it.cancel() }
            it.destroy()
        }
        recognizer = null
        recording = false
        recordJob?.cancel()
        recordJob = null
        listener = null
        holding = false
    }

    private val stopTimeout = Runnable {
        if (!delivered) deliver(SpeechResult(null, null, "device", elapsed()))
    }

    // ---------------------------------------------------------------- device recognizer

    private fun startDevice() {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank() && !delivered) listener?.onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                deliver(SpeechResult(textUrdu = text, textRoman = null, source = "device", ms = elapsed()))
            }

            override fun onError(error: Int) {
                Log.i(TAG, "recognizer error $error")
                if (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE) {
                    deviceUrduBroken = true
                    destroyRecognizer()
                    // Still holding: switch to recording for the cloud right away.
                    if (holding) {
                        startCloud()
                        return
                    }
                }
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                deliver(SpeechResult(null, null, "device", elapsed(), error = if (silent) null else "recognizer error $error"))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        r.startListening(intent)
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            it.destroy()
        }
        recognizer = null
    }

    // ---------------------------------------------------------------- cloud: record WAV, upload

    @SuppressLint("MissingPermission") // checked in start()
    private fun startCloud() {
        recording = true
        recordJob = scope.launch {
            val pcm = withContext(Dispatchers.IO) { record() }
            if (listener == null) return@launch
            if (pcm == null || pcm.size < SAMPLE_RATE / 4 * 2) { // under 0.25 s: nothing said
                deliver(SpeechResult(null, null, "cloud", elapsed(), error = if (pcm == null) "recording failed" else null))
                return@launch
            }
            listener?.onTranscribing()
            Log.d(TAG, "uploading ${pcm.size / 2 / (SAMPLE_RATE / 1000)} ms of audio")
            when (val r = client.stt(Wav.wrap(pcm, SAMPLE_RATE))) {
                is PlannerClient.Result.Ok -> deliver(SpeechResult(r.value.textUrdu, r.value.textRoman, "cloud", elapsed()))
                is PlannerClient.Result.Failed -> deliver(SpeechResult(null, null, "cloud", elapsed(), error = "stt ${r.failure}"))
            }
        }
    }

    /** Records 16 kHz mono 16-bit PCM until [stop] or 20 s. */
    @SuppressLint("MissingPermission")
    private suspend fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        } catch (e: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(minBuf)
        val start = SystemClock.elapsedRealtime()
        try {
            rec.startRecording()
            val job = kotlin.coroutines.coroutineContext[Job]
            while (recording && job?.isActive != false && SystemClock.elapsedRealtime() - start < MAX_RECORD_MS) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- helpers

    private fun deliver(result: SpeechResult) {
        main.post {
            if (delivered || listener == null) return@post
            delivered = true
            main.removeCallbacks(stopTimeout)
            val l = listener
            destroyRecognizer()
            recording = false
            l?.onResult(result)
        }
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt

    private fun hasMic() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RehnumaSpeech"
        const val SAMPLE_RATE = 16000
        const val MAX_RECORD_MS = 20_000L
        private const val RESULT_TIMEOUT_MS = 5000L

        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31).
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

/** Minimal WAV (RIFF, PCM 16-bit mono) wrapper. */
object Wav {
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)          // PCM chunk size
        header.putShort(1)         // PCM
        header.putShort(1)         // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2)         // block align
        header.putShort(16)        // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
