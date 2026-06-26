package tech.devlens.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
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
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented end-to-end tests for [WebSocketTransport].
 *
 * Runs a real WebSocket server via MockWebServer on-device and verifies
 * that the transport connects, sends the hello handshake, and delivers
 * plugin events with the correct JSON envelope structure.
 */
@RunWith(AndroidJUnit4::class)
class WebSocketTransportE2ETest {

    private val gson = Gson()
    private lateinit var server: MockWebServer
    private lateinit var transport: WebSocketTransport

    /**
     * Released when the server-side WebSocket is fully closed.
     * Waited on in [teardown] so [MockWebServer.close] does not time out.
     * Initialised fresh in [setup] so each test gets an independent latch.
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
        // Wait for the server-side WebSocket to finish the close handshake so that
        // MockWebServer.close() does not throw "Gave up waiting for queue to shut down".
        wsClosedLatch.await(3, TimeUnit.SECONDS)
        server.close()
    }

    // ------------------------------------------------------------------
    // Test 1: Transport connects and sends hello message
    // ------------------------------------------------------------------

    @Test
    fun transportConnectsAndSendsHelloWithCorrectFields() {
        val receivedMessages = Collections.synchronizedList(mutableListOf<String>())
        val connectLatch = CountDownLatch(1)

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectLatch.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedMessages.add(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Echo the close frame so the handshake completes on both sides.
                        // Without this MockWebServer.close() times out.
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
        transport.connect()

        assertTrue(
            "Transport must open WebSocket within 5s",
            connectLatch.await(5, TimeUnit.SECONDS)
        )
        // Give sender thread one full poll cycle (500 ms) to drain the hello message
        Thread.sleep(600)

        assertTrue("Server must have received at least one message", receivedMessages.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val hello = gson.fromJson(receivedMessages[0], Map::class.java) as Map<String, Any>
        assertEquals("hello", hello["type"])
        assertEquals("tech.devlens.test", hello["appPackage"])
        assertNotNull("clientId must be present in hello", hello["clientId"])
        assertEquals("android", hello["platform"])
    }

    // ------------------------------------------------------------------
    // Test 2: Transport sends event messages with correct envelope
    // ------------------------------------------------------------------

    @Test
    fun transportSendsEventEnvelopeWithPluginIdAndPayload() {
        val receivedMessages = Collections.synchronizedList(mutableListOf<String>())
        val connectLatch = CountDownLatch(1)

        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectLatch.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedMessages.add(text)
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
        transport = WebSocketTransport(serverUrl = wsUrl, appPackage = "tech.devlens.test")
        transport.connect()

        assertTrue(
            "Transport must open WebSocket within 5s",
            connectLatch.await(5, TimeUnit.SECONDS)
        )
        // Wait for hello to drain from sender queue
        Thread.sleep(600)

        // Send a plugin event
        transport.send(
            pluginId = "network",
            payload = mapOf(
                "method" to "GET",
                "url" to "https://example.com/api",
                "responseCode" to 200
            )
        )

        // Wait for sender thread to drain the event message
        Thread.sleep(600)

        val eventMessage = receivedMessages.firstOrNull { msg ->
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(msg, Map::class.java) as Map<String, Any>)["type"] == "event"
        }

        assertNotNull("Server must have received an event message", eventMessage)

        @Suppress("UNCHECKED_CAST")
        val event = gson.fromJson(eventMessage, Map::class.java) as Map<String, Any>
        assertEquals("event", event["type"])
        assertEquals("network", event["plugin"])
        assertNotNull("timestamp must be present in event envelope", event["timestamp"])

        @Suppress("UNCHECKED_CAST")
        val payload = event["payload"] as Map<String, Any>
        assertEquals("GET", payload["method"])
        assertEquals("https://example.com/api", payload["url"])
    }

    // ------------------------------------------------------------------
    // Test 3: clientId is stable across reconnects on the same instance
    // ------------------------------------------------------------------

    /**
     * One UUID is generated per transport instance (primary-constructor default
     * arg) and reused for every hello handshake. This test verifies that a
     * disconnect → connect cycle on the SAME [WebSocketTransport] produces two
     * hello messages with identical [clientId]s, both valid UUIDs.
     *
     * MockWebServer dequeues responses in FIFO order, so the first WebSocket
     * upgrade is consumed by the initial [WebSocketTransport.connect] and the
     * second by the reconnect — each carrying its own listener to capture the
     * hello independently.
     */
    @Test
    fun clientIdStableAcrossReconnects() {
        val firstHelloMessages = Collections.synchronizedList(mutableListOf<String>())
        val secondHelloMessages = Collections.synchronizedList(mutableListOf<String>())
        val firstConnectLatch = CountDownLatch(1)
        val secondConnectLatch = CountDownLatch(1)

        // First connection — captures the initial hello.
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstConnectLatch.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        firstHelloMessages.add(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Echo the close frame so the handshake completes on both sides.
                        // Without this MockWebServer.close() times out.
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
        // Second connection — captures the hello sent after reconnect.
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        secondConnectLatch.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        secondHelloMessages.add(text)
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
        transport = WebSocketTransport(serverUrl = wsUrl, appPackage = "tech.devlens.test")

        // Initial connect — consumes the first queued response.
        transport.connect()
        assertTrue(
            "First connection must open within 5s",
            firstConnectLatch.await(5, TimeUnit.SECONDS)
        )
        // Give the sender thread one full poll cycle (500 ms) to drain the hello.
        Thread.sleep(600)

        // Disconnect closes the first WebSocket; its close handshake fires onClosed
        // on the server side. Brief pause to let that settle before reconnecting.
        transport.disconnect()
        Thread.sleep(500)

        // Reconnect on the SAME transport instance — consumes the second queued
        // response. connect() takes the initial-attempt path (not the scheduler,
        // which disconnect() shut down), so no RejectedExecutionException.
        transport.connect()
        assertTrue(
            "Second connection must open within 5s",
            secondConnectLatch.await(5, TimeUnit.SECONDS)
        )
        Thread.sleep(600)

        assertTrue("First hello must have been received", firstHelloMessages.isNotEmpty())
        assertTrue("Second hello must have been received", secondHelloMessages.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val firstHello = gson.fromJson(firstHelloMessages[0], Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val secondHello = gson.fromJson(secondHelloMessages[0], Map::class.java) as Map<String, Any>

        assertEquals("hello", firstHello["type"])
        assertEquals("hello", secondHello["type"])

        val firstClientId = firstHello["clientId"] as String
        val secondClientId = secondHello["clientId"] as String

        assertNotNull("clientId must be present in first hello", firstClientId)
        assertNotNull("clientId must be present in second hello", secondClientId)

        // Both must be valid UUIDs — fromString throws IllegalArgumentException if not.
        java.util.UUID.fromString(firstClientId)
        java.util.UUID.fromString(secondClientId)

        // The crucial assertion: the SAME transport instance reuses its clientId.
        assertEquals(
            "clientId must remain stable across reconnects on the same transport instance",
            firstClientId,
            secondClientId
        )
    }
}
