package tech.devlens.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import tech.devlens.ProbeHost
import tech.devlens.QueryRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Instrumented end-to-end tests for network response mocking.
 *
 * Drives the full path: CLI sends a `setMock` query → [NetworkPlugin.onQuery]
 * populates the rule store → [NetworkInterceptor] matches and short-circuits the
 * real network call. MockWebServer hosts the request URLs on-device; on a mock
 * hit we assert `mockServer.requestCount == 0` (no `chain.proceed()`).
 *
 * Covers DoD-6 (skip-network), DoD-7 (arbitrary status codes), DoD-8 (error mock
 * throws), DoD-9 (non-matching proceeds), DoD-10 (mocked flag in payload),
 * DoD-12 (concurrent rule update + parallel requests), and DoD-15 (sanitizers
 * apply to mock body).
 */
@RunWith(AndroidJUnit4::class)
class MockInterceptorE2ETest {

    private lateinit var mockServer: MockWebServer
    private lateinit var networkPlugin: NetworkPlugin
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        networkPlugin = NetworkPlugin()
        // onQuery returns early while host == null, so attach a no-op host in
        // setup to let addMock() populate the rule store. Tests that assert on
        // forwarded payloads re-attach a capturing host.
        networkPlugin.onAttach(NoOpProbeHost)
        // Short read timeout: a regression that wrongly calls chain.proceed()
        // against an empty MockWebServer queue would otherwise hang the suite.
        client = OkHttpClient.Builder()
            .addInterceptor(networkPlugin.interceptor())
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun teardown() {
        mockServer.close()
    }

    private object NoOpProbeHost : ProbeHost {
        override val isConnected: Boolean = true
        override fun send(pluginId: String, payload: Map<String, Any?>) { /* no-op */ }
    }

    /**
     * Capturing host that records network transaction events only, filtering out
     * `queryResult` acks (which [NetworkPlugin.onQuery] emits under the same
     * plugin id). Transaction payloads carry no `op` key; acks carry
     * `op = "queryResult"`.
     */
    private class TransactionCapturingHost : ProbeHost {
        val events = mutableListOf<Map<String, Any?>>()
        override val isConnected: Boolean = true
        override fun send(pluginId: String, payload: Map<String, Any?>) {
            if (pluginId == "network" && "op" !in payload) events.add(payload)
        }
    }

    /** Adds a response mock via the query protocol (exactly what the CLI does). */
    private fun addMock(
        method: String? = "GET",
        url: String,
        status: Int = 200,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        error: String? = null
    ) {
        val params = mutableMapOf<String, Any?>("url" to url)
        if (method != null) params["method"] = method
        if (error != null) {
            params["error"] = error
        } else {
            params["status"] = status
            params["body"] = body
            if (headers.isNotEmpty()) params["headers"] = headers
        }
        networkPlugin.onQuery(
            QueryRequest(requestId = "req-add", plugin = "network", method = "setMock", params = params)
        )
    }

    // ── DoD-6: matched request returns mock WITHOUT proceed ───────────────────

    @Test
    fun matchedRequestReturnsMockWithoutCallingChainProceed() {
        // Queue ZERO responses — any proceed() would block/timeout.
        addMock(url = "/skip", status = 200, body = """{"mocked":true}""")

        val response = client.newCall(
            Request.Builder().url(mockServer.url("/skip")).build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals("""{"mocked":true}""", response.body?.string())
        response.close()

        assertEquals(
            "Real network must NOT be hit on a mock match",
            0,
            mockServer.requestCount
        )

        val tx = networkPlugin.dump(1).single()
        assertTrue("matched transaction must be flagged mocked", tx.mocked)
        assertEquals(200, tx.responseCode)
        assertEquals("""{"mocked":true}""", tx.responseBody)
        assertNull("error mock path not taken", tx.error)
    }

    // ── DoD-7: arbitrary status codes ─────────────────────────────────────────

    @Test
    fun statusCodeMock200() = assertStatusMock(200)

    @Test
    fun statusCodeMock404() = assertStatusMock(404)

    @Test
    fun statusCodeMock500() = assertStatusMock(500)

    @Test
    fun statusCodeMock204() = assertStatusMock(204)

    private fun assertStatusMock(code: Int) {
        addMock(url = "/status", status = code, body = "body-$code")

        val response = client.newCall(
            Request.Builder().url(mockServer.url("/status")).build()
        ).execute()

        assertEquals(code, response.code)
        assertEquals("body-$code", response.body?.string())
        response.close()
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun mockHeadersArePresentOnSyntheticResponse() {
        addMock(
            url = "/hdr",
            status = 200,
            body = "ok",
            headers = mapOf("X-Mock-A" to "1", "Content-Type" to "text/plain")
        )
        val response = client.newCall(
            Request.Builder().url(mockServer.url("/hdr")).build()
        ).execute()
        assertEquals("1", response.header("X-Mock-A"))
        assertEquals("text/plain", response.header("Content-Type"))
        response.close()
    }

    // ── DoD-8: error mock throws IOException and fills error field ────────────

    @Test
    fun errorMockThrowsIOExceptionAndRecordsErrorField() {
        addMock(url = "/boom", error = "simulated network failure")

        var threw = false
        try {
            client.newCall(
                Request.Builder().url(mockServer.url("/boom")).build()
            ).execute()
        } catch (e: IOException) {
            threw = true
            assertEquals("simulated network failure", e.message)
        }
        assertTrue("IOException must be thrown for an error mock", threw)
        assertEquals(0, mockServer.requestCount)

        val tx = networkPlugin.dump(1).single()
        assertEquals("simulated network failure", tx.error)
        assertNull("responseCode must be null on an error mock", tx.responseCode)
        assertTrue("error-mock transaction must be flagged mocked", tx.mocked)
    }

    // ── DoD-9: non-matching request proceeds normally ─────────────────────────

    @Test
    fun nonMatchingRequestProceedsToRealNetwork() {
        addMock(url = "/will-not-match", status = 200, body = "wrong")
        mockServer.enqueue(MockResponse(code = 200, body = "real"))

        val response = client.newCall(
            Request.Builder().url(mockServer.url("/actual")).build()
        ).execute()

        assertEquals("real", response.body?.string())
        response.close()
        assertEquals("non-matching request must hit the real server", 1, mockServer.requestCount)

        val tx = networkPlugin.dump(1).single()
        assertFalse("non-matched transaction must NOT be mocked", tx.mocked)
        assertEquals("real", tx.responseBody)
    }

    // ── DoD-10: mocked flag in sent payload ───────────────────────────────────

    @Test
    fun matchedTransactionPayloadHasMockedTrueAndNonMatchedFalse() {
        val host = TransactionCapturingHost()
        networkPlugin.onAttach(host)

        // Matched → mocked == true
        addMock(url = "/m", status = 200, body = "mocked-body")
        client.newCall(Request.Builder().url(mockServer.url("/m")).build()).execute().close()

        // Non-matching → proceeds, mocked == false
        mockServer.enqueue(MockResponse(code = 200, body = "real"))
        client.newCall(Request.Builder().url(mockServer.url("/other")).build()).execute().close()

        assertEquals("two transaction events forwarded (acks filtered)", 2, host.events.size)
        assertEquals(true, host.events[0]["mocked"])
        assertEquals(false, host.events[1]["mocked"])
    }

    // ── DoD-12: concurrent rule update + parallel requests, no crash ──────────

    @Test
    fun concurrentRuleUpdateAndParallelRequestsDoNotCrashOrRace() {
        // Match all /concurrent/* requests so they short-circuit.
        addMock(url = "/concurrent", status = 200, body = "ok")

        val count = 5
        val executor = Executors.newFixedThreadPool(count + 1)
        val requestLatch = CountDownLatch(count)
        val errors = AtomicInteger(0)

        // Writer thread: mutates rules concurrently with the readers.
        val writerDone = CountDownLatch(1)
        executor.submit {
            try {
                repeat(20) { i ->
                    networkPlugin.onQuery(
                        QueryRequest(
                            requestId = "w-$i",
                            plugin = "network",
                            method = "setMock",
                            params = mapOf(
                                "url" to "/concurrent",
                                "status" to 200,
                                "body" to "ok-$i"
                            )
                        )
                    )
                }
            } catch (t: Throwable) {
                errors.incrementAndGet()
            } finally {
                writerDone.countDown()
            }
        }

        repeat(count) { i ->
            executor.submit {
                try {
                    client.newCall(
                        Request.Builder().url(mockServer.url("/concurrent/$i")).build()
                    ).execute().close()
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    requestLatch.countDown()
                }
            }
        }

        assertTrue("requests timed out", requestLatch.await(15, TimeUnit.SECONDS))
        assertTrue("writer timed out", writerDone.await(15, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("no request/writer errors expected", 0, errors.get())
        assertEquals("mocked requests never hit the server", 0, mockServer.requestCount)

        // All 5 reader requests captured and flagged mocked.
        val transactions = networkPlugin.dump(count + 1)
        assertEquals(count, transactions.size)
        assertTrue("all concurrent transactions flagged mocked", transactions.all { it.mocked })
        // Every transaction must have a unique id (no corruption).
        assertEquals(count, transactions.map { it.id }.toSet().size)
    }

    // ── DoD-15: sanitizers apply to mocked transaction body ───────────────────

    @Test
    fun sanitizersMaskMockResponseBodyBeforeForwarding() {
        val sensitive = "leak@example.com"
        val plugin = NetworkPlugin(sanitizers = listOf(SanitizeRule.EMAIL))
        val host = TransactionCapturingHost()
        plugin.onAttach(host) // attach BEFORE onQuery so setMock is processed

        plugin.onQuery(
            QueryRequest(
                requestId = "r",
                plugin = "network",
                method = "setMock",
                params = mapOf("url" to "/san", "status" to 200, "body" to """{"e":"$sensitive"}""")
            )
        )
        val sanitizerClient = OkHttpClient.Builder()
            .addInterceptor(plugin.interceptor())
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val response = sanitizerClient.newCall(
            Request.Builder().url(mockServer.url("/san")).build()
        ).execute()
        val rawBody = response.body?.string()
        response.close()

        // The caller still receives the unmasked body from the synthetic Response.
        assertTrue(rawBody!!.contains(sensitive))
        assertEquals(0, mockServer.requestCount)

        assertEquals("one mocked transaction forwarded (ack filtered)", 1, host.events.size)
        val payload = host.events[0]
        assertEquals(true, payload["mocked"])
        val forwardedBody = payload["responseBody"] as String
        assertFalse(
            "forwarded mock body must not contain raw email: $forwardedBody",
            forwardedBody.contains(sensitive)
        )
        assertTrue(
            "forwarded mock body must contain EMAIL mask placeholder: $forwardedBody",
            forwardedBody.contains("[EMAIL-")
        )

        // The in-memory buffer retains the original (unmasked) body.
        val buffered = plugin.dump(1).single()
        assertTrue("buffer keeps original mock body", buffered.responseBody!!.contains(sensitive))
    }
}
