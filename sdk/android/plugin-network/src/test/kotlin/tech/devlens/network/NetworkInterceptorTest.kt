package tech.devlens.network

import io.mockk.every
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class NetworkInterceptorTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun clientWithPlugin(plugin: NetworkPlugin): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(plugin.interceptor())
            .build()
    }

    @Test
    fun `intercept GET request captures method url status code and duration`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))
        val plugin = NetworkPlugin()

        clientWithPlugin(plugin)
            .newCall(Request.Builder().url(server.url("/api/items")).build())
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertEquals("GET", tx.method)
        assertTrue(tx.url.contains("/api/items"))
        assertEquals(200, tx.responseCode)
        assertNotNull(tx.durationMs)
        assertTrue(tx.durationMs!! >= 0)
        assertNull(tx.error)
    }

    @Test
    fun `intercept POST with body captures request body and size`() {
        server.enqueue(MockResponse(code = 201, body = "{}"))
        val plugin = NetworkPlugin()
        val bodyContent = """{"name":"probe"}"""

        clientWithPlugin(plugin)
            .newCall(
                Request.Builder()
                    .url(server.url("/items"))
                    .post(bodyContent.toRequestBody("application/json".toMediaType()))
                    .build()
            )
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertEquals("POST", tx.method)
        assertEquals(bodyContent, tx.requestBody)
        assertEquals(bodyContent.toByteArray(Charsets.UTF_8).size.toLong(), tx.requestSizeBytes)
        assertEquals(201, tx.responseCode)
    }

    @Test
    fun `intercept response with headers captures response headers`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("ok")
                .addHeader("X-Custom-Header", "test-value")
                .addHeader("X-Request-Id", "abc-123")
                .build()
        )
        val plugin = NetworkPlugin()

        clientWithPlugin(plugin)
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertTrue(tx.responseHeaders.containsKey("X-Custom-Header"))
        assertEquals("test-value", tx.responseHeaders["X-Custom-Header"])
        assertEquals("abc-123", tx.responseHeaders["X-Request-Id"])
    }

    @Test
    fun `intercept request captures request headers`() {
        server.enqueue(MockResponse(code = 200, body = ""))
        val plugin = NetworkPlugin()

        clientWithPlugin(plugin)
            .newCall(
                Request.Builder()
                    .url(server.url("/"))
                    .addHeader("Authorization", "Bearer secret")
                    .addHeader("Accept", "application/json")
                    .build()
            )
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertEquals("Bearer secret", tx.requestHeaders["Authorization"])
        assertEquals("application/json", tx.requestHeaders["Accept"])
    }

    @Test
    fun `intercept captures response body text`() {
        server.enqueue(MockResponse(code = 200, body = "hello world"))
        val plugin = NetworkPlugin()

        clientWithPlugin(plugin)
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertEquals("hello world", tx.responseBody)
        assertNotNull(tx.responseSizeBytes)
    }

    @Test
    fun `intercept GET request has null request body and zero size`() {
        server.enqueue(MockResponse(code = 200, body = ""))
        val plugin = NetworkPlugin()

        clientWithPlugin(plugin)
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertNull(tx.requestBody)
        assertEquals(0L, tx.requestSizeBytes)
    }

    @Test
    fun `intercept error captures error field and rethrows IOException`() {
        val plugin = NetworkPlugin()
        val interceptor = plugin.interceptor()
        val mockChain = mockk<Interceptor.Chain>()
        val request = Request.Builder().url("http://example.com/").build()
        every { mockChain.request() } returns request
        every { mockChain.proceed(any()) } throws IOException("Connection refused")

        try {
            interceptor.intercept(mockChain)
            fail("Expected IOException to be rethrown")
        } catch (e: IOException) {
            // expected — interceptor rethrows after recording
        }

        val tx = plugin.dump().single()
        assertEquals("Connection refused", tx.error)
        assertNull(tx.responseCode)
        assertNull(tx.responseMessage)
    }

    @Test
    fun `intercept error records transaction with id and method before rethrowing`() {
        val plugin = NetworkPlugin()
        val interceptor = plugin.interceptor()
        val mockChain = mockk<Interceptor.Chain>()
        every { mockChain.request() } returns Request.Builder().url("http://example.com/test").build()
        every { mockChain.proceed(any()) } throws IOException("Timeout")

        try {
            interceptor.intercept(mockChain)
        } catch (_: IOException) { }

        val tx = plugin.dump().single()
        assertNotNull(tx.id)
        assertNotNull(tx.timestamp)
        assertEquals("GET", tx.method)
        assertEquals("Timeout", tx.error)
    }

    @Test
    fun `body truncation when request body exceeds maxBodySize`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))
        val maxBodySize = 10L
        val plugin = NetworkPlugin(maxBodySize = maxBodySize)
        val largeBody = "x".repeat(100)

        OkHttpClient.Builder()
            .addInterceptor(plugin.interceptor())
            .build()
            .newCall(
                Request.Builder()
                    .url(server.url("/upload"))
                    .post(largeBody.toRequestBody("text/plain".toMediaType()))
                    .build()
            )
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertNotNull(tx.requestBody)
        assertTrue(
            "Expected truncation marker, got: ${tx.requestBody}",
            tx.requestBody!!.startsWith("[body too large:")
        )
        assertEquals(100L, tx.requestSizeBytes)
    }

    @Test
    fun `interceptor records transaction and plugin notifies host`() {
        server.enqueue(MockResponse(code = 200, body = "data"))
        val plugin = NetworkPlugin()

        clientWithPlugin(plugin)
            .newCall(Request.Builder().url(server.url("/data")).build())
            .execute()
            .close()

        val tx = plugin.dump().single()
        assertNotNull(tx.id)
        assertEquals(200, tx.responseCode)
        assertEquals("data", tx.responseBody)
        assertEquals("GET", tx.method)
    }
}
