package tech.devlens

import com.google.gson.Gson
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.devlens.transport.WebSocketTransport
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM (MockWebServer) coverage for the inbound query path:
 *  - [WebSocketTransport] parses a CLI `query` frame into a [QueryRequest] and
 *    invokes the inbound handler.
 *  - [Probe] routes the request: a matching plugin's [ProbePlugin.onQuery] is
 *    invoked, and an unknown plugin yields an `unknown_plugin` `queryResult`
 *    error event back to the server.
 *
 * Mirrors the style of `WebSocketTransportClientIdTest`: runs on the plain JDK,
 * `android.util.Log` / `android.os.Build` references neutralised by
 * `android.testOptions.unitTests.isReturnDefaultValues = true`.
 */
class ProbeQueryRoutingTest {

    private val gson = Gson()
    private lateinit var server: MockWebServer
    private lateinit var wsClosedLatch: CountDownLatch

    @Before
    fun setup() {
        server = MockWebServer()
        wsClosedLatch = CountDownLatch(1)
    }

    @After
    fun teardown() {
        Probe.uninstall()
        wsClosedLatch.await(3, TimeUnit.SECONDS)
        server.close()
    }

    @Test
    fun queryFrameIsParsedAndRoutedToMatchingPlugin() {
        val connectLatch = CountDownLatch(1)
        val receivedByServer = Collections.synchronizedList(mutableListOf<String>())
        val queryFrame = gson.toJson(mapOf(
            "type" to "query",
            "requestId" to "q-parse",
            "plugin" to "echo",
            "method" to "ping",
            "params" to mapOf("n" to 1)
        ))

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectLatch.countDown()
                        // CLI side: push a query frame to the SDK immediately.
                        webSocket.send(queryFrame)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedByServer.add(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
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
        server.start()

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        val transport = WebSocketTransport(serverUrl = wsUrl, appPackage = "tech.devlens.routing.test")
        val capturingPlugin = CapturingPlugin()

        Probe.install(
            Probe.Builder(noopContext())
                .transport(transport)
                .plugin(capturingPlugin)
                .build()
        )

        assertTrue("WS must connect within 5s", connectLatch.await(5, TimeUnit.SECONDS))
        assertTrue(
            "Matching plugin must receive onQuery within 5s",
            capturingPlugin.receivedLatch.await(5, TimeUnit.SECONDS)
        )

        val request = capturingPlugin.received
        assertNotNull("onQuery request must be delivered", request)
        assertEquals("q-parse", request!!.requestId)
        assertEquals("echo", request.plugin)
        assertEquals("ping", request.method)
        assertEquals(1, (request.params["n"] as Number).toInt())
    }

    @Test
    fun queryForUnknownPluginReturnsUnknownPluginError() {
        val connectLatch = CountDownLatch(1)
        val receivedByServer = Collections.synchronizedList(mutableListOf<String>())
        val queryFrame = gson.toJson(mapOf(
            "type" to "query",
            "requestId" to "q-ghost",
            "plugin" to "ghost",
            "method" to "listDatabases",
            "params" to emptyMap<String, Any?>()
        ))

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectLatch.countDown()
                        webSocket.send(queryFrame)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedByServer.add(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
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
        server.start()

        val wsUrl = server.url("/").toString().replace("http://", "ws://")
        val transport = WebSocketTransport(serverUrl = wsUrl, appPackage = "tech.devlens.routing.test")

        // No plugin registered for "ghost" → Probe must respond with unknown_plugin error.
        Probe.install(
            Probe.Builder(noopContext())
                .transport(transport)
                .plugin(CapturingPlugin()) // id "echo", not "ghost"
                .build()
        )

        assertTrue("WS must connect within 5s", connectLatch.await(5, TimeUnit.SECONDS))

        val errorEvent = waitForMessage(receivedByServer, timeoutMs = 4000) { msg ->
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(msg, Map::class.java) as Map<String, Any>
            parsed["type"] == "event" &&
                (parsed["payload"] as Map<String, Any>)["op"] == "queryResult"
        }
        assertNotNull("queryResult event must reach the server", errorEvent)

        @Suppress("UNCHECKED_CAST")
        val event = gson.fromJson(errorEvent, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val payload = event["payload"] as Map<String, Any>
        assertEquals("q-ghost", payload["requestId"])
        assertEquals(false, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any>
        assertEquals("unknown_plugin", error["code"])
    }

    // ------------------------------------------------------------------
    // Fakes / helpers
    // ------------------------------------------------------------------

    /** A plugin that records any inbound query and counts down a latch. */
    private class CapturingPlugin : ProbePlugin {
        override val id = "echo"
        override val displayName = "Echo"

        @Volatile
        var received: QueryRequest? = null
        val receivedLatch = CountDownLatch(1)

        override fun onAttach(host: ProbeHost) {}
        override fun onDetach() {}
        override fun onQuery(request: QueryRequest) {
            received = request
            receivedLatch.countDown()
        }
    }

    /**
     * Relaxed mock [android.content.Context]. Probe.Builder stores it and reads
     * `packageName` in `build()`; the auto-transport path is overridden via
     * `.transport(...)`, so no other Context behavior is exercised.
     */
    private fun noopContext(): android.content.Context = mockk(relaxed = true) {
        io.mockk.every { packageName } returns "tech.devlens.routing.test"
    }

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
