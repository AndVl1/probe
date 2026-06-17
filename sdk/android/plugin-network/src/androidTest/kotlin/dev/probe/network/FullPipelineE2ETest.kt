package dev.probe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.probe.Probe
import dev.probe.transport.WebSocketTransport
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full-pipeline instrumented E2E test.
 *
 * Verifies the complete data flow:
 *   OkHttp request
 *   → NetworkInterceptor captures HttpTransaction
 *   → NetworkPlugin.record() buffers it and calls ProbeHost.send()
 *   → Probe forwards it to WebSocketTransport
 *   → WebSocketTransport enqueues and sends JSON envelope
 *   → WebSocket server (MockWebServer on-device) receives the event
 *
 * Two separate MockWebServer instances are used:
 *   - httpServer  — target of OkHttp requests
 *   - wsServer    — receives Probe events (acts as the CLI tool)
 */
@RunWith(AndroidJUnit4::class)
class FullPipelineE2ETest {

    private val gson = Gson()
    private lateinit var httpServer: MockWebServer
    private lateinit var wsServer: MockWebServer
    private lateinit var networkPlugin: NetworkPlugin

    // Latch released when the server-side WebSocket is fully closed.
    // Waited on in teardown so wsServer.close() does not time out.
    private val wsClosedLatch = CountDownLatch(1)

    @Before
    fun setup() {
        httpServer = MockWebServer()
        httpServer.start()
        wsServer = MockWebServer()
        networkPlugin = NetworkPlugin()
    }

    @After
    fun teardown() {
        // Uninstall Probe; this calls transport.disconnect() which sends a WS close frame.
        Probe.uninstall()
        // Wait for the server-side WebSocket to acknowledge the close before shutting
        // down wsServer, otherwise MockWebServer.close() throws "Gave up waiting".
        wsClosedLatch.await(3, TimeUnit.SECONDS)
        httpServer.close()
        wsServer.close()
    }

    @Test
    fun httpRequestFlowsThroughFullPipelineToWebSocketServer() {
        // ----- 1. Start WebSocket server that will act as the Probe CLI -----
        val receivedEvents = Collections.synchronizedList(mutableListOf<String>())
        val connectLatch = CountDownLatch(1)

        wsServer.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectLatch.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedEvents.add(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Echo the close frame so the handshake completes on both sides.
                        // Without this, the client's close frame goes unanswered, the
                        // connection stays half-open, and MockWebServer.close() times out.
                        webSocket.close(1000, null)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        wsClosedLatch.countDown()
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        wsClosedLatch.countDown()
                    }
                })
                .build()
        )
        wsServer.start()

        // ----- 2. Wire up Probe with a transport pointing at the WS server -----
        val wsUrl = wsServer.url("/").toString().replace("http://", "ws://")
        val transport = WebSocketTransport(
            serverUrl = wsUrl,
            appPackage = "dev.probe.e2e.test"
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = Probe.Builder(context)
            .transport(transport)
            .plugin(networkPlugin)
            .build()
        Probe.install(probe)

        // ----- 3. Wait for WebSocket connection and hello handshake -----
        assertTrue(
            "WebSocket transport did not connect within 5s",
            connectLatch.await(5, TimeUnit.SECONDS)
        )
        // Give sender thread one full poll cycle (500 ms) to drain the hello message
        Thread.sleep(600)

        // ----- 4. Make a real HTTP request through the intercepted client -----
        httpServer.enqueue(MockResponse(code = 200, body = """{"status":"ok"}"""))
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(networkPlugin.interceptor())
            .build()

        okHttpClient.newCall(
            Request.Builder().url(httpServer.url("/api/status")).build()
        ).execute().close()

        // ----- 5. Wait for event to arrive at WS server (sender drains in ≤500ms) -----
        val eventMessage = waitForMessage(receivedEvents, timeoutMs = 3000) { msg ->
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(msg, Map::class.java) as Map<String, Any>
            parsed["type"] == "event" && parsed["plugin"] == "network"
        }

        assertNotNull("Network event must arrive at WebSocket server", eventMessage)

        // ----- 6. Verify event envelope and payload -----
        @Suppress("UNCHECKED_CAST")
        val event = gson.fromJson(eventMessage, Map::class.java) as Map<String, Any>
        assertEquals("event", event["type"])
        assertEquals("network", event["plugin"])
        assertNotNull(event["timestamp"])

        @Suppress("UNCHECKED_CAST")
        val payload = event["payload"] as Map<String, Any>
        assertEquals("GET", payload["method"])
        assertTrue(
            "URL must contain /api/status",
            (payload["url"] as String).contains("/api/status")
        )
        // Gson deserialises JSON integers as Double
        assertEquals(200, (payload["responseCode"] as Double).toInt())
        assertNotNull(payload["id"])
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Polls [messages] until [predicate] matches or [timeoutMs] expires. */
    private fun waitForMessage(
        messages: List<String>,
        timeoutMs: Long,
        predicate: (String) -> Boolean
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            messages.find(predicate)?.let { return it }
            Thread.sleep(100)
        }
        return messages.find(predicate)
    }
}
