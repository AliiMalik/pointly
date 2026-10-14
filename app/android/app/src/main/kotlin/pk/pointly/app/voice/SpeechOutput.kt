package pk.pointly.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.pointly.app.config.AppConfig
import pk.pointly.app.net.PlannerClient
import pk.pointly.app.session.SessionVoice
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class SpeechOutput(
    private val context: Context,
    private val config: () -> AppConfig,
    private val client: PlannerClient,
    private val scope: CoroutineScope,
) : SessionVoice {

    private class Item(val id: String, val text: String, val language: String, val onDone: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Item>()
    private var current: Item? = null
    private var player: MediaPlayer? = null
    private var fetchJob: Job? = null
    private var counter = 0
    private var tts: TextToSpeech? = null
    private var cloudFailedAt = 0L

    @Volatile
    var deviceUrdu = false
        private set
    @Volatile
    var deviceEnglish = false
        private set
    private var urduVoice: android.speech.tts.Voice? = null

    var onSpeakStart: (() -> Unit)? = null

    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        tts = TextToSpeech(context) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "device TTS failed to start ($status)")
            return
        }
        val urdu = Locale("ur", "PK")
        val available = t.isLanguageAvailable(urdu) >= TextToSpeech.LANG_AVAILABLE
        val voice = t.voices.orEmpty().firstOrNull {
            it.locale.language == "ur" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        if (available && voice != null) {
            urduVoice = voice
            deviceUrdu = true
        }
        deviceEnglish = t.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE
        t.setSpeechRate(SPEECH_RATE)
        t.setAudioAttributes(attributes)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                main.post { onSpeakStart?.invoke() }
            }

            override fun onDone(utteranceId: String) {
                main.post { finish(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                main.post { finish(utteranceId) }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                main.post { finish(utteranceId) }
            }
        })
        Log.i(TAG, "device voices: Urdu $deviceUrdu, English $deviceEnglish")
    }

    private fun useDevice(language: String): Boolean =
        config().voiceOut == AppConfig.VOICE_DEVICE && if (language == AppConfig.LANG_UR) deviceUrdu else deviceEnglish

    override fun speak(line: Line, onDone: () -> Unit) {
        val language = config().language
        queue.addLast(Item("u${++counter}", line.pick(language), language, onDone))
        if (current == null) next()
    }

    override fun stop() {
        queue.clear()
        current = null
        fetchJob?.cancel()
        tts?.stop()
        releasePlayer()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    fun prewarm() {
        val language = config().language
        if (useDevice(language)) return
        scope.launch {
            for (p in Phrase.entries) {
                if (cloudAudio(p.line.pick(language), language) == null) break
            }
        }
    }

    private fun next() {
        val item = queue.removeFirstOrNull()
        current = item
        if (item == null) return
        val t = tts
        if (t != null && useDevice(item.language)) {
            if (item.language == AppConfig.LANG_UR) urduVoice?.let { t.voice = it } else t.language = Locale.US
            val r = t.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, item.id)
            if (r != TextToSpeech.SUCCESS) finish(item.id)
            return
        }
        fetchJob = scope.launch {
            val file = cloudAudio(item.text, item.language)
            if (current !== item) return@launch
            if (file == null) finish(item.id) else play(file, item)
        }
    }

    private fun play(file: File, item: Item) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { finish(item.id) }
            mp.setOnErrorListener { _, _, _ ->
                finish(item.id)
                true
            }
            mp.prepare()
            mp.start()
            onSpeakStart?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "playback failed", e)
            file.delete()
            finish(item.id)
        }
    }

    private fun finish(id: String) {
        val item = current ?: return
        if (item.id != id) return
        current = null
        releasePlayer()
        item.onDone()
        if (current == null) next()
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    private suspend fun cloudAudio(text: String, language: String): File? {
        val file = File(cacheDir, sha256("$language:$text") + ".wav")
        if (file.exists() && file.length() > 44) return file
        if (SystemClock.elapsedRealtime() - cloudFailedAt < CLOUD_BACKOFF_MS) return null
        return when (val r = client.tts(text, language)) {
            is PlannerClient.Result.Ok -> withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, file.name + ".tmp")
                tmp.writeBytes(r.value)
                tmp.renameTo(file)
                file
            }
            is PlannerClient.Result.Failed -> {
                cloudFailedAt = SystemClock.elapsedRealtime()
                Log.w(TAG, "cloud TTS unavailable: ${r.failure}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "PointlyVoice"
        const val SPEECH_RATE = 0.9f
        private const val CLOUD_BACKOFF_MS = 60_000L

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
