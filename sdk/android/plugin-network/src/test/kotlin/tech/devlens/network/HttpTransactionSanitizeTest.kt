package tech.devlens.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTransactionSanitizeTest {

    private val emailRule = SanitizeRule(
        Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
        "EMAIL"
    )
    private val bearerRule = SanitizeRule(Regex("""Bearer\s+\S+"""), "BEARER_TOKEN")

    private fun base() = HttpTransaction(
        id = "tx-1",
        timestamp = 1000L,
        method = "POST",
        url = "https://api.example.com/users?email=user@example.com",
        requestHeaders = mapOf(
            "Authorization" to "Bearer secret-token",
            "Content-Type" to "application/json"
        ),
        requestBody = """{"email":"user@example.com","name":"Alice"}""",
        requestSizeBytes = 42L,
        responseCode = 200,
        responseMessage = "OK for user@example.com",
        responseHeaders = mapOf("X-User" to "user@example.com"),
        responseBody = """{"token":"Bearer abcdef"}""",
        responseSizeBytes = 24L,
        durationMs = 123L,
        error = null
    )

    // ── Zero-overhead path ────────────────────────────────────────────────────

    @Test
    fun `sanitized with empty rules returns same instance`() {
        val tx = base()
        assertSame(tx, tx.sanitized(emptyList()))
    }

    // ── Maskable fields ───────────────────────────────────────────────────────

    @Test
    fun `url is masked`() {
        val result = base().sanitized(listOf(emailRule))
        assertTrue("url should not contain raw email", !result.url.contains("user@example.com"))
        assertTrue("url should contain EMAIL placeholder", result.url.contains("[EMAIL-"))
    }

    @Test
    fun `requestBody is masked`() {
        val result = base().sanitized(listOf(emailRule))
        assertTrue(!result.requestBody!!.contains("user@example.com"))
        assertTrue(result.requestBody!!.contains("[EMAIL-"))
    }

    @Test
    fun `responseBody is masked`() {
        val result = base().sanitized(listOf(bearerRule))
        assertTrue(!result.responseBody!!.contains("Bearer abcdef"))
        assertTrue(result.responseBody!!.contains("[BEARER_TOKEN-"))
    }

    @Test
    fun `responseMessage is masked`() {
        val result = base().sanitized(listOf(emailRule))
        assertTrue(!result.responseMessage!!.contains("user@example.com"))
        assertTrue(result.responseMessage!!.contains("[EMAIL-"))
    }

    @Test
    fun `requestHeader values are masked`() {
        val result = base().sanitized(listOf(bearerRule))
        assertTrue(!result.requestHeaders["Authorization"]!!.contains("secret-token"))
        assertTrue(result.requestHeaders["Authorization"]!!.contains("[BEARER_TOKEN-"))
    }

    @Test
    fun `responseHeader values are masked`() {
        val result = base().sanitized(listOf(emailRule))
        assertTrue(!result.responseHeaders["X-User"]!!.contains("user@example.com"))
        assertTrue(result.responseHeaders["X-User"]!!.contains("[EMAIL-"))
    }

    @Test
    fun `error field is masked`() {
        val tx = base().copy(error = "Failed to connect to user@example.com")
        val result = tx.sanitized(listOf(emailRule))
        assertTrue(!result.error!!.contains("user@example.com"))
        assertTrue(result.error!!.contains("[EMAIL-"))
    }

    // ── Header keys are NOT masked ────────────────────────────────────────────

    @Test
    fun `request header keys are not masked`() {
        val tx = base().copy(
            requestHeaders = mapOf("user@example.com" to "value")
        )
        val result = tx.sanitized(listOf(emailRule))
        assertTrue("header key should be unchanged", result.requestHeaders.containsKey("user@example.com"))
    }

    @Test
    fun `response header keys are not masked`() {
        val tx = base().copy(
            responseHeaders = mapOf("user@example.com" to "value")
        )
        val result = tx.sanitized(listOf(emailRule))
        assertTrue("header key should be unchanged", result.responseHeaders.containsKey("user@example.com"))
    }

    // ── Non-maskable fields ───────────────────────────────────────────────────

    @Test
    fun `id is not changed`() {
        val tx = base()
        assertEquals(tx.id, tx.sanitized(listOf(emailRule)).id)
    }

    @Test
    fun `method is not changed`() {
        val tx = base()
        assertEquals(tx.method, tx.sanitized(listOf(emailRule)).method)
    }

    @Test
    fun `timestamp is not changed`() {
        val tx = base()
        assertEquals(tx.timestamp, tx.sanitized(listOf(emailRule)).timestamp)
    }

    @Test
    fun `durationMs is not changed`() {
        val tx = base()
        assertEquals(tx.durationMs, tx.sanitized(listOf(emailRule)).durationMs)
    }

    @Test
    fun `responseCode is not changed`() {
        val tx = base()
        assertEquals(tx.responseCode, tx.sanitized(listOf(emailRule)).responseCode)
    }

    @Test
    fun `requestSizeBytes is not changed`() {
        val tx = base()
        assertEquals(tx.requestSizeBytes, tx.sanitized(listOf(emailRule)).requestSizeBytes)
    }

    @Test
    fun `responseSizeBytes is not changed`() {
        val tx = base()
        assertEquals(tx.responseSizeBytes, tx.sanitized(listOf(emailRule)).responseSizeBytes)
    }

    // ── Null fields stay null ─────────────────────────────────────────────────

    @Test
    fun `null requestBody stays null`() {
        val tx = base().copy(requestBody = null)
        assertNull(tx.sanitized(listOf(emailRule)).requestBody)
    }

    @Test
    fun `null responseBody stays null`() {
        val tx = base().copy(responseBody = null)
        assertNull(tx.sanitized(listOf(emailRule)).responseBody)
    }

    @Test
    fun `null responseMessage stays null`() {
        val tx = base().copy(responseMessage = null)
        assertNull(tx.sanitized(listOf(emailRule)).responseMessage)
    }

    @Test
    fun `null error stays null`() {
        val tx = base().copy(error = null)
        assertNull(tx.sanitized(listOf(emailRule)).error)
    }

    // ── Placeholder format ────────────────────────────────────────────────────

    @Test
    fun `placeholder format is LABEL-hex`() {
        val tx = base().copy(requestBody = "user@example.com")
        val result = tx.sanitized(listOf(emailRule))
        val placeholder = result.requestBody!!
        assertTrue("Format should be [EMAIL-...]", placeholder.matches(Regex("""\[EMAIL-[0-9a-f]+]""")))
    }

    @Test
    fun `same value produces same placeholder across calls`() {
        val tx = base()
        val result1 = tx.sanitized(listOf(emailRule))
        val result2 = tx.sanitized(listOf(emailRule))
        assertEquals(result1.requestBody, result2.requestBody)
    }

    @Test
    fun `placeholder contains lowercase hex`() {
        val tx = base().copy(requestBody = "user@example.com")
        val result = tx.sanitized(listOf(emailRule))
        val hex = result.requestBody!!.removePrefix("[EMAIL-").removeSuffix("]")
        assertTrue("hex should match [0-9a-f]+", hex.matches(Regex("[0-9a-f]+")))
        assertNotNull(hex.toLongOrNull(16))
    }

    // ── Multiple rules compose ────────────────────────────────────────────────

    @Test
    fun `multiple rules are all applied`() {
        val tx = base().copy(
            requestBody = "Contact: user@example.com, Auth: Bearer secret"
        )
        val result = tx.sanitized(listOf(emailRule, bearerRule))
        assertTrue(!result.requestBody!!.contains("user@example.com"))
        assertTrue(!result.requestBody!!.contains("Bearer secret"))
        assertTrue(result.requestBody!!.contains("[EMAIL-"))
        assertTrue(result.requestBody!!.contains("[BEARER_TOKEN-"))
    }

    @Test
    fun `non-matching rule does not change field`() {
        val noMatchRule = SanitizeRule(Regex("NOMATCH"), "NOMATCH")
        val tx = base()
        val result = tx.sanitized(listOf(noMatchRule))
        assertEquals(tx.requestBody, result.requestBody)
    }
}
