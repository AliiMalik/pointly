package pk.rehnuma.app.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pk.rehnuma.app.config.AppConfig
import java.util.concurrent.TimeUnit

class PlannerClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PlannerClient
    private var retries = 0

    private val okBody = """{"action":"tap","element_id":2,"scroll_direction":null,
        "say_en":"Tap Sara's name","say_ur":"سارہ کے نام پر دبائیں",
        "goal_key":"whatsapp_send_photo","confidence":0.92,"reason":"r","latency_ms":1430,"extra":1}"""

    private val request = PlanRequest(
        sessionId = "s1",
        goalText = "Beti Sara ko photo bhejni hai",
        stepIndex = 1,
        screen = ApiScreen(
            packageName = "com.whatsapp", width = 1080, height = 2400,
            elements = listOf(ApiElement(id = 2, text = "Sara", cls = "View", clickable = true, bounds = listOf(0, 420, 1080, 560))),
        ),
    )

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val cfg = AppConfig(baseUrl = server.url("/").toString(), appKey = "k1")
        client = PlannerClient({ cfg }, http, clock = { System.nanoTime() / 1_000_000 })
        retries = 0
    }

    @After fun tearDown() = server.shutdown()

    private fun plan() = runBlocking { client.plan(request) { retries++ } }

    @Test fun successParsesAndSendsKeyAndBody() {
        server.enqueue(MockResponse().setBody(okBody))
        val r = plan() as PlannerClient.Result.Ok
        assertEquals("tap", r.value.action)
        assertEquals(2, r.value.elementId)
        assertEquals("whatsapp_send_photo", r.value.goalKey)
        val sent = server.takeRequest()
        assertEquals("/v1/plan", sent.path)
        assertEquals("k1", sent.getHeader("X-Rehnuma-Key"))
        val json = sent.body.readUtf8()
        assertTrue(json.contains("\"session_id\":\"s1\""))
        assertTrue(json.contains("\"package\":\"com.whatsapp\""))
        assertFalse("false flags are left out", json.contains("\"editable\""))
        assertEquals(0, retries)
    }

    @Test fun serverErrorThenSuccessRetriesOnce() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(okBody))
        assertTrue(plan() is PlannerClient.Result.Ok)
        assertEquals(1, retries)
        assertEquals(2, server.requestCount)
    }

    @Test fun twoServerErrorsFail() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(okBody)) // never requested
        val r = plan() as PlannerClient.Result.Failed
        assertEquals(PlannerClient.Failure.SERVER, r.failure)
        assertEquals(2, server.requestCount)
    }

    @Test fun timeoutRetriesThenFailsAsNetwork() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val r = plan() as PlannerClient.Result.Failed
        assertEquals(PlannerClient.Failure.NETWORK, r.failure)
        assertEquals(1, retries)
    }

    @Test fun wrongKeyDoesNotRetry() {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = plan() as PlannerClient.Result.Failed
        assertEquals(PlannerClient.Failure.AUTH, r.failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun garbageBodyIsServerFailure() {
        server.enqueue(MockResponse().setBody("<html>proxy error</html>"))
        val r = plan() as PlannerClient.Result.Failed
        assertEquals(PlannerClient.Failure.SERVER, r.failure)
    }

    @Test fun backendDownIsNetworkFailure() {
        server.shutdown()
        val r = plan() as PlannerClient.Result.Failed
        assertEquals(PlannerClient.Failure.NETWORK, r.failure)
        assertEquals(1, retries)
    }
}
