package pk.rehnuma.app.net

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import pk.rehnuma.app.config.AppConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** HTTP client for the backend (SPEC 5.12): `X-Rehnuma-Key`, 3 s connect, 8 s read, one retry. */
class PlannerClient(
    private val config: () -> AppConfig,
    private val http: OkHttpClient = defaultHttp(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Failure { NETWORK, SERVER, AUTH, BAD_REQUEST }

    sealed class Result<out T> {
        data class Ok<T>(val value: T, val ms: Long) : Result<T>()
        data class Failed(val failure: Failure, val message: String, val ms: Long) : Result<Nothing>()
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** POST /v1/plan. [onRetry] runs once if the first attempt times out or gets a 5xx. */
    suspend fun plan(request: PlanRequest, onRetry: () -> Unit = {}): Result<PlanResponse> {
        val body = ApiJson.encodeToString(PlanRequest.serializer(), request)
        return send("/v1/plan", { body.toRequestBody(jsonType) }, onRetry) { r ->
            ApiJson.decodeFromString(PlanResponse.serializer(), r.body!!.string())
        }
    }

    /** POST /v1/stt with a WAV recording (SPEC 6.7). */
    suspend fun stt(wav: ByteArray): Result<SttResponse> {
        val build = {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("audio", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
        }
        return send("/v1/stt", build, {}) { r -> ApiJson.decodeFromString(SttResponse.serializer(), r.body!!.string()) }
    }

    /** POST /v1/tts; returns WAV bytes (SPEC 6.7). */
    suspend fun tts(text: String, language: String): Result<ByteArray> {
        val body = ApiJson.encodeToString(TtsRequest.serializer(), TtsRequest(text, language))
        // No retry: a 503 here means the cloud voice is not set up, and captions are enough.
        return send("/v1/tts", { body.toRequestBody(jsonType) }, {}, attempts = 1) { r -> r.body!!.bytes() }
    }

    /** Settings "check server": health (no key) and ping (with key). Never throws. */
    suspend fun check(): Map<String, Any?> = withContext(io) {
        val cfg = config()
        val base = cfg.baseUrl.trimEnd('/')
        fun get(path: String, key: Boolean): Pair<Int, String?> = try {
            val req = Request.Builder().url(base + path).apply { if (key) header("X-Rehnuma-Key", cfg.appKey) }.get().build()
            http.newCall(req).execute().use { it.code to it.body?.string() }
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }
        val (healthCode, healthBody) = get("/v1/health", key = false)
        if (healthCode != 200) {
            return@withContext mapOf("reachable" to false, "error" to (healthBody ?: "HTTP $healthCode"))
        }
        val model = runCatching {
            ApiJson.parseToJsonElement(healthBody!!).jsonObject["model"]?.jsonPrimitive?.content
        }.getOrNull()
        val (pingCode, _) = get("/v1/ping", key = true)
        mapOf("reachable" to true, "model" to model, "keyOk" to (pingCode == 200))
    }

    private suspend fun <T> send(
        path: String,
        body: () -> RequestBody,
        onRetry: () -> Unit,
        attempts: Int = 2,
        parse: (Response) -> T,
    ): Result<T> {
        val start = clock()
        var last: Result.Failed? = null
        for (attempt in 0 until attempts) {
            if (attempt == 1) onRetry() // on the caller's thread: it may touch the UI
            val cfg = config()
            val request = try {
                Request.Builder()
                    .url(cfg.baseUrl.trimEnd('/') + path)
                    .header("X-Rehnuma-Key", cfg.appKey)
                    .post(body())
                    .build()
            } catch (e: IllegalArgumentException) {
                return Result.Failed(Failure.BAD_REQUEST, "bad backend URL: ${cfg.baseUrl}", clock() - start)
            }
            // The call and the body read both happen off the main thread (the body may still be
            // arriving when the headers are in).
            val outcome: Result<T> = withContext(io) {
                try {
                    http.newCall(request).await().use { r ->
                        when {
                            r.isSuccessful -> try {
                                Result.Ok(parse(r), clock() - start)
                            } catch (e: Exception) {
                                Result.Failed(Failure.SERVER, "bad response: ${e.javaClass.simpleName} ${e.message}", clock() - start)
                            }
                            r.code == 401 -> Result.Failed(Failure.AUTH, "wrong app key", clock() - start)
                            r.code in 400..499 -> Result.Failed(Failure.BAD_REQUEST, "HTTP ${r.code}", clock() - start)
                            else -> Result.Failed(Failure.SERVER, "HTTP ${r.code}", clock() - start)
                        }
                    }
                } catch (e: IOException) {
                    Result.Failed(Failure.NETWORK, e.message ?: e.javaClass.simpleName, clock() - start)
                }
            }
            val retryable = outcome is Result.Failed &&
                (outcome.failure == Failure.NETWORK || (outcome.failure == Failure.SERVER && outcome.message.startsWith("HTTP")))
            if (!retryable) return outcome
            last = outcome as Result.Failed
        }
        return last!!
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            // Drop idle connections before uvicorn's 5 s keep-alive closes them: a NAT or tunnel may
            // not pass the close on, and a dead pooled connection only fails after the 8 s read timeout.
            .connectionPool(ConnectionPool(4, 4, TimeUnit.SECONDS))
            // Lets OkHttp silently reconnect when a pooled connection turns out to be closed.
            // Timeouts and 5xx still get exactly one retry from us (SPEC 5.12).
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Suspends until the call finishes; cancelling the coroutine cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
