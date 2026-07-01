package tech.devlens.transport

import com.google.gson.Gson
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for the snapshot-on-attach drop bug (review fix F1).
 *
 * Reproduces the `Probe.start()` ordering: `connect()` kicks off the async
 * WebSocket handshake, then `send()` is called immediately after — exactly what
 * happens when `plugins.forEach { it.onAttach(this) }` runs and
 * `PreferencesPlugin.onAttach` emits its `op:"snapshot"`. That `send()` enqueues
 * the message BEFORE `onOpen` has flipped `connected = true`.
 *
 * Contract under test: the sender thread must HOLD the pre-connect message in
 * the queue and deliver it (in FIFO order, after the hello) once the WebSocket
 * opens — not drop it. Before the gate fix the sender polled the pre-connect
 * message, saw `connected == false`, and silently discarded it, so the snapshot
 * never reached the CLI (see vibe-report/e2e-prefs/07-startup-no-snapshot.json).
 *
 * Plain-JVM counterpart of the instrumented `WebSocketTransportE2ETest`; runs
 * on the JDK via MockWebServer with `android.testOptions.unitTests
 * .isReturnDefaultValues = true` neutralising the transport's `android.util.Log`
 * / `android.os.Build` references. No emulator required.
 */
class WebSocketTransportPreConnectDeliveryTest {

    private val gson = Gson()
    private lateinit var server: MockWebServer
    private lateinit var transport: WebSocketTransport

    /**
     * Released when the server-side WebSocket finishes its close handshake so
     * that [MockWebServer.close] in [teardown] does not time out.
     */
    private lateinit var wsClosedLatch: CountDownLatch

    @Before
    fun setup() {
        server = MockWebServer()
        wsClosedLatch = CountDownLatch(1)
    }

    @After
    fun teardown() {
        if (::transport.isInitialized) transport.disconnect()
        wsClosedLatch.await(3, TimeUnit.SECONDS)
        server.close()
    }

    @Test
    fun messageEnqueuedBeforeConnectIsDeliveredAfterOpen() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val openLatch = CountDownLatch(1)

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        openLatch.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.add(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Echo the close frame so the handshake completes on both sides;
                        // without this MockWebServer.close() hangs in teardown.
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
        transport = WebSocketTransport(serverUrl = wsUrl, appPackage = "tech.devlens.test")

        // The Probe.start() sequence: connect() initiates the async handshake,
        // THEN plugins.onAttach fires send(). Enqueue the event BEFORE awaiting
        // onOpen — mirroring PreferencesPlugin's attach-time snapshot emission.
        transport.connect()
        transport.send(
            pluginId = "preferences",
            payload = mapOf("op" to "snapshot", "files" to emptyList<String>())
        )

        assertTrue(
            "WebSocket must open within 5s",
            openLatch.await(5, TimeUnit.SECONDS)
        )
        // Hello is sent directly in onOpen; the pre-connect event is drained by
        // one sender poll cycle (500 ms). Generous margin for the gated drain.
        Thread.sleep(1200)

        assertTrue(
            "Server must receive BOTH hello and the pre-connect event (got ${received.size}); " +
                "a dropped attach-time message indicates the sender drained while disconnected.",
            received.size >= 2
        )

        @Suppress("UNCHECKED_CAST")
        val first = gson.fromJson(received[0], Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val second = gson.fromJson(received[1], Map::class.java) as Map<String, Any>

        // FIFO: hello first (sent synchronously in onOpen), then the event
        // (held in the queue pre-connect and drained once connected). Asserting
        // both proves the pre-connect message was preserved AND ordered after
        // the hello rather than dropped on the floor.
        assertEquals("first message must be the hello handshake", "hello", first["type"])
        assertEquals("second message must be the held pre-connect event", "event", second["type"])
        assertEquals("preferences", second["plugin"])
        @Suppress("UNCHECKED_CAST")
        val payload = second["payload"] as Map<String, Any?>
        assertEquals("snapshot", payload["op"])
    }
}
