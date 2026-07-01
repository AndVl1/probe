package tech.devlens.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTransactionTest {

    private fun minimal() = HttpTransaction(
        id = "test-id",
        timestamp = 1000L,
        method = "GET",
        url = "https://example.com/test",
        requestHeaders = emptyMap(),
        requestBody = null,
        requestSizeBytes = 0L,
        responseCode = null,
        responseMessage = null,
        responseHeaders = emptyMap(),
        responseBody = null,
        responseSizeBytes = null,
        durationMs = null,
        error = null
    )

    @Test
    fun `toPayload always contains id method url and timestamp`() {
        val payload = minimal().toPayload()

        assertNotNull(payload["id"])
        assertNotNull(payload["method"])
        assertNotNull(payload["url"])
        assertNotNull(payload["timestamp"])
    }

    @Test
    fun `toPayload contains all 15 expected keys`() {
        val payload = minimal().toPayload()

        val expected = listOf(
            "id", "timestamp", "method", "url",
            "requestHeaders", "requestBody", "requestSizeBytes",
            "responseCode", "responseMessage", "responseHeaders",
            "responseBody", "responseSizeBytes", "durationMs", "error",
            "mocked"
        )
        expected.forEach { key ->
            assertTrue("Missing key: $key", payload.containsKey(key))
        }
        assertEquals(15, payload.size)
    }

    @Test
    fun `toPayload with all fields populated maps values correctly`() {
        val tx = HttpTransaction(
            id = "abc-123",
            timestamp = 5000L,
            method = "POST",
            url = "https://api.example.com/data",
            requestHeaders = mapOf("Content-Type" to "application/json"),
            requestBody = """{"key":"value"}""",
            requestSizeBytes = 15L,
            responseCode = 201,
            responseMessage = "Created",
            responseHeaders = mapOf("Location" to "/data/1"),
            responseBody = """{"id":1}""",
            responseSizeBytes = 8L,
            durationMs = 120L,
            error = null
        )

        val payload = tx.toPayload()

        assertEquals("abc-123", payload["id"])
        assertEquals(5000L, payload["timestamp"])
        assertEquals("POST", payload["method"])
        assertEquals("https://api.example.com/data", payload["url"])
        assertEquals(mapOf("Content-Type" to "application/json"), payload["requestHeaders"])
        assertEquals("""{"key":"value"}""", payload["requestBody"])
        assertEquals(15L, payload["requestSizeBytes"])
        assertEquals(201, payload["responseCode"])
        assertEquals("Created", payload["responseMessage"])
        assertEquals(mapOf("Location" to "/data/1"), payload["responseHeaders"])
        assertEquals("""{"id":1}""", payload["responseBody"])
        assertEquals(8L, payload["responseSizeBytes"])
        assertEquals(120L, payload["durationMs"])
        assertNull(payload["error"])
    }

    @Test
    fun `toPayload with null optional fields returns nulls for those keys`() {
        val payload = minimal().toPayload()

        assertNull(payload["responseCode"])
        assertNull(payload["responseMessage"])
        assertNull(payload["responseBody"])
        assertNull(payload["responseSizeBytes"])
        assertNull(payload["durationMs"])
        assertNull(payload["error"])
        assertNull(payload["requestBody"])
    }

    @Test
    fun `toPayload with error field set returns error message`() {
        val tx = minimal().copy(error = "Connection timed out")
        val payload = tx.toPayload()
        assertEquals("Connection timed out", payload["error"])
    }

    @Test
    fun `toPayload emits mocked flag and defaults to false`() {
        val payloadDefault = minimal().toPayload()
        assertEquals(false, payloadDefault["mocked"])

        val payloadMocked = minimal().copy(mocked = true).toPayload()
        assertEquals(true, payloadMocked["mocked"])
    }

    @Test
    fun `toPayload requestSizeBytes zero for zero-length body`() {
        val tx = minimal().copy(requestSizeBytes = 0L)
        val payload = tx.toPayload()
        assertEquals(0L, payload["requestSizeBytes"])
    }

    @Test
    fun `toPayload with response error and no response code`() {
        val tx = minimal().copy(
            responseCode = null,
            responseMessage = null,
            error = "ECONNREFUSED"
        )
        val payload = tx.toPayload()

        assertNull(payload["responseCode"])
        assertNull(payload["responseMessage"])
        assertEquals("ECONNREFUSED", payload["error"])
    }

    @Test
    fun `toPayload preserves request and response headers`() {
        val tx = minimal().copy(
            requestHeaders = mapOf("Authorization" to "Bearer token", "Accept" to "application/json"),
            responseHeaders = mapOf("Content-Type" to "application/json", "X-Request-Id" to "xyz")
        )
        val payload = tx.toPayload()

        @Suppress("UNCHECKED_CAST")
        val reqHeaders = payload["requestHeaders"] as Map<String, String>
        @Suppress("UNCHECKED_CAST")
        val respHeaders = payload["responseHeaders"] as Map<String, String>

        assertEquals("Bearer token", reqHeaders["Authorization"])
        assertEquals("application/json", reqHeaders["Accept"])
        assertEquals("application/json", respHeaders["Content-Type"])
        assertEquals("xyz", respHeaders["X-Request-Id"])
    }
}
