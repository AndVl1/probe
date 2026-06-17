package dev.probe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.probe.ProbeHost
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented end-to-end tests for [NetworkInterceptor] and [NetworkPlugin].
 *
 * Runs on a real Android device / emulator using MockWebServer hosted on-device.
 * Each test makes real OkHttp requests through the interceptor and verifies that
 * [HttpTransaction] objects are captured correctly.
 */
@RunWith(AndroidJUnit4::class)
class NetworkInterceptorE2ETest {

    private lateinit var mockServer: MockWebServer
    private lateinit var networkPlugin: NetworkPlugin
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        networkPlugin = NetworkPlugin()
        client = OkHttpClient.Builder()
            .addInterceptor(networkPlugin.interceptor())
            .build()
    }

    @After
    fun teardown() {
        mockServer.close()
    }

    // ------------------------------------------------------------------
    // Test 1: GET request captured
    // ------------------------------------------------------------------

    @Test
    fun getRequestCapturedWithCorrectMethodUrlAndResponseCode() {
        mockServer.enqueue(MockResponse(code = 200, body = """{"id":1}"""))

        client.newCall(Request.Builder().url(mockServer.url("/api/users/1")).build())
            .execute().close()

        val transactions = networkPlugin.dump(1)
        assertEquals(1, transactions.size)
        val tx = transactions[0]
        assertEquals("GET", tx.method)
        assertTrue("URL should contain path", tx.url.contains("/api/users/1"))
        assertEquals(200, tx.responseCode)
        assertEquals("""{"id":1}""", tx.responseBody)
        assertNull(tx.error)
        assertNotNull(tx.id)
        assertNotNull(tx.timestamp)
    }

    // ------------------------------------------------------------------
    // Test 2: POST request with body
    // ------------------------------------------------------------------

    @Test
    fun postRequestBodyAndSizeAreCaptured() {
        mockServer.enqueue(MockResponse(code = 201, body = "{}"))
        val jsonBody = """{"name":"probe","version":"1.0"}"""

        client.newCall(
            Request.Builder()
                .url(mockServer.url("/api/items"))
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()

        val tx = networkPlugin.dump(1).single()
        assertEquals("POST", tx.method)
        assertEquals(201, tx.responseCode)
        assertEquals(jsonBody, tx.requestBody)
        assertEquals(jsonBody.toByteArray(Charsets.UTF_8).size.toLong(), tx.requestSizeBytes)
        assertNull(tx.error)
    }

    // ------------------------------------------------------------------
    // Test 3: Error response (404)
    // ------------------------------------------------------------------

    @Test
    fun errorResponseCodeCapturedCorrectly() {
        mockServer.enqueue(MockResponse(code = 404, body = """{"error":"not found"}"""))

        client.newCall(Request.Builder().url(mockServer.url("/missing")).build())
            .execute().close()

        val tx = networkPlugin.dump(1).single()
        assertEquals(404, tx.responseCode)
        assertNotNull(tx.responseBody)
        // HTTP error responses are not network errors — error field must be null
        assertNull(tx.error)
    }

    // ------------------------------------------------------------------
    // Test 4: Network error (connection refused)
    // ------------------------------------------------------------------

    @Test
    fun connectionErrorCapturedInTransactionErrorField() {
        // Grab a port that was occupied and then released to get connection refused
        val deadServer = MockWebServer()
        deadServer.start()
        val port = deadServer.port
        deadServer.close()

        val errorClient = OkHttpClient.Builder()
            .addInterceptor(networkPlugin.interceptor())
            .connectTimeout(2, TimeUnit.SECONDS)
            .build()

        try {
            errorClient.newCall(
                Request.Builder().url("http://localhost:$port/fail").build()
            ).execute().close()
        } catch (_: IOException) {
            // Expected: OkHttp rethrows after interceptor records the error
        }

        val transactions = networkPlugin.dump(1)
        assertEquals(1, transactions.size)
        assertNotNull(
            "Expected error field to be set on connection failure",
            transactions[0].error
        )
        assertNull("Response code must be null on connection failure", transactions[0].responseCode)
    }

    // ------------------------------------------------------------------
    // Test 5: Multiple sequential requests captured in order
    // ------------------------------------------------------------------

    @Test
    fun threeSequentialRequestsCapturedInOrder() {
        mockServer.enqueue(MockResponse(code = 200, body = "first"))
        mockServer.enqueue(MockResponse(code = 200, body = "second"))
        mockServer.enqueue(MockResponse(code = 200, body = "third"))

        client.newCall(Request.Builder().url(mockServer.url("/one")).build()).execute().close()
        client.newCall(Request.Builder().url(mockServer.url("/two")).build()).execute().close()
        client.newCall(Request.Builder().url(mockServer.url("/three")).build()).execute().close()

        val transactions = networkPlugin.dump(10)
        assertEquals(3, transactions.size)
        assertTrue(transactions[0].url.contains("/one"))
        assertTrue(transactions[1].url.contains("/two"))
        assertTrue(transactions[2].url.contains("/three"))
        assertEquals("first", transactions[0].responseBody)
        assertEquals("second", transactions[1].responseBody)
        assertEquals("third", transactions[2].responseBody)
    }

    // ------------------------------------------------------------------
    // Test 6: Response headers captured
    // ------------------------------------------------------------------

    @Test
    fun customResponseHeadersAreCaptured() {
        mockServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("ok")
                .addHeader("X-Probe-Test", "instrumented")
                .addHeader("X-Request-Id", "e2e-42")
                .build()
        )

        client.newCall(Request.Builder().url(mockServer.url("/")).build()).execute().close()

        val tx = networkPlugin.dump(1).single()
        assertEquals("instrumented", tx.responseHeaders["X-Probe-Test"])
        assertEquals("e2e-42", tx.responseHeaders["X-Request-Id"])
    }

    // ------------------------------------------------------------------
    // Test 7: Large response body truncated at maxBodySize
    // ------------------------------------------------------------------

    @Test
    fun largeResponseBodyTruncatedAtMaxBodySize() {
        val maxBodySize = 512L
        val plugin = NetworkPlugin(maxBodySize = maxBodySize)
        val limitedClient = OkHttpClient.Builder()
            .addInterceptor(plugin.interceptor())
            .build()
        // 8 KB body exceeds maxBodySize of 512 bytes
        mockServer.enqueue(MockResponse(code = 200, body = "a".repeat(8 * 1024)))

        limitedClient.newCall(
            Request.Builder().url(mockServer.url("/large")).build()
        ).execute().close()

        val tx = plugin.dump(1).single()
        assertNotNull(tx.responseBody)
        // peekBody(maxBodySize) reads at most maxBodySize bytes
        assertTrue(
            "Body length ${tx.responseBody!!.length} should be <= $maxBodySize",
            tx.responseBody!!.length.toLong() <= maxBodySize
        )
    }

    // ------------------------------------------------------------------
    // Test 8: Duration measurement
    // ------------------------------------------------------------------

    @Test
    fun transactionDurationIsNonNegativeAndReflectsDelay() {
        mockServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("slow")
                .headersDelay(200, TimeUnit.MILLISECONDS)
                .build()
        )

        client.newCall(Request.Builder().url(mockServer.url("/slow")).build()).execute().close()

        val tx = networkPlugin.dump(1).single()
        assertNotNull(tx.durationMs)
        assertTrue(
            "Expected durationMs >= 150, got ${tx.durationMs}",
            tx.durationMs!! >= 150L
        )
    }

    // ------------------------------------------------------------------
    // Test 9: Concurrent requests
    // ------------------------------------------------------------------

    @Test
    fun fiveConcurrentRequestsAllCapturedWithoutDataCorruption() {
        val count = 5
        repeat(count) { mockServer.enqueue(MockResponse(code = 200, body = "response")) }

        val executor = Executors.newFixedThreadPool(count)
        val latch = CountDownLatch(count)
        val errors = AtomicInteger(0)

        repeat(count) { i ->
            executor.submit {
                try {
                    client.newCall(
                        Request.Builder()
                            .url(mockServer.url("/concurrent/$i"))
                            .build()
                    ).execute().close()
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent requests timed out", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No request errors expected", 0, errors.get())

        val transactions = networkPlugin.dump(count + 1)
        assertEquals(count, transactions.size)
        // Every transaction must have valid fields — no data corruption
        transactions.forEach { tx ->
            assertEquals("GET", tx.method)
            assertEquals(200, tx.responseCode)
            assertNotNull(tx.id)
            assertNotNull(tx.timestamp)
            assertNull(tx.error)
        }
        // All transaction IDs must be unique
        val ids = transactions.map { it.id }.toSet()
        assertEquals("Each concurrent transaction must have a unique ID", count, ids.size)
    }

    // ------------------------------------------------------------------
    // Test 10: Plugin forwards captured transaction to ProbeHost
    // ------------------------------------------------------------------

    @Test
    fun capturedTransactionIsForwardedToProbeHost() {
        mockServer.enqueue(MockResponse(code = 200, body = "data"))
        val sentPayloads = mutableListOf<Map<String, Any?>>()
        val mockHost = object : ProbeHost {
            override val isConnected = true
            override fun send(pluginId: String, payload: Map<String, Any?>) {
                if (pluginId == "network") sentPayloads.add(payload)
            }
        }
        networkPlugin.onAttach(mockHost)

        client.newCall(Request.Builder().url(mockServer.url("/data")).build()).execute().close()

        // sentPayloads only accumulates entries where pluginId == "network"
        assertEquals(1, sentPayloads.size)
        val payload = sentPayloads[0]
        assertEquals("GET", payload["method"])
        assertEquals(200, payload["responseCode"])
        assertNotNull(payload["id"])
        assertNotNull(payload["url"])
        assertNotNull(payload["timestamp"])
    }
}
