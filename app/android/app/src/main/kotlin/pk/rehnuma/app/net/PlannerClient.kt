package pk.rehnuma.app.net

import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
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
    suspend fun tts(textUrdu: String): Result<ByteArray> {
        val body = ApiJson.encodeToString(TtsRequest.serializer(), TtsRequest(textUrdu))
        return send("/v1/tts", { body.toRequestBody(jsonType) }, {}) { r -> r.body!!.bytes() }
    }

    private suspend fun <T> send(
        path: String,
        body: () -> RequestBody,
        onRetry: () -> Unit,
        parse: (Response) -> T,
    ): Result<T> {
        val start = clock()
        var last: Result.Failed? = null
        for (attempt in 0..1) {
            if (attempt == 1) onRetry()
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
            try {
                http.newCall(request).await().use { r ->
                    when {
                        r.isSuccessful -> return try {
                            Result.Ok(parse(r), clock() - start)
                        } catch (e: Exception) {
                            Result.Failed(Failure.SERVER, "bad response: ${e.message}", clock() - start)
                        }
                        r.code == 401 -> return Result.Failed(Failure.AUTH, "wrong app key", clock() - start)
                        r.code in 400..499 -> return Result.Failed(Failure.BAD_REQUEST, "HTTP ${r.code}", clock() - start)
                        else -> last = Result.Failed(Failure.SERVER, "HTTP ${r.code}", clock() - start)
                    }
                }
            } catch (e: IOException) {
                last = Result.Failed(Failure.NETWORK, e.message ?: e.javaClass.simpleName, clock() - start)
            }
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
