package tech.devlens.network

import tech.devlens.ProbeHost
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPluginSanitizeTest {

    private val emailRule = SanitizeRule.EMAIL
    private val bearerRule = SanitizeRule.BEARER_TOKEN

    private fun makeTransaction(
        id: String = "tx-${System.nanoTime()}",
        email: String = "secret@example.com",
        auth: String = "Bearer secret-token"
    ): HttpTransaction = HttpTransaction(
        id = id,
        timestamp = System.currentTimeMillis(),
        method = "POST",
        url = "https://api.example.com/users?email=$email",
        requestHeaders = mapOf("Authorization" to auth),
        requestBody = """{"email":"$email"}""",
        requestSizeBytes = 30L,
        responseCode = 200,
        responseMessage = "OK",
        responseHeaders = emptyMap(),
        responseBody = """{"status":"ok"}""",
        responseSizeBytes = 16L,
        durationMs = 50L,
        error = null
    )

    // ── Dual-path: dump() raw, send() masked ─────────────────────────────────

    @Test
    fun `dump returns original unmasked transaction`() {
        val plugin = NetworkPlugin(sanitizers = listOf(emailRule))
        val tx = makeTransaction("raw-check", email = "secret@example.com")
        plugin.record(tx)

        val dumped = plugin.dump().single()
        assertEquals("secret@example.com", dumped.requestBody?.let {
            Regex("\"email\":\"([^\"]+)\"").find(it)?.groupValues?.get(1)
        })
        assertTrue(dumped.url.contains("secret@example.com"))
    }

    @Test
    fun `host receives masked payload when sanitizers configured`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin(sanitizers = listOf(emailRule))
        plugin.onAttach(host)

        plugin.record(makeTransaction("mask-check"))

        val payloadSlot = slot<Map<String, Any?>>()
        verify { host.send(any(), capture(payloadSlot)) }

        val payload = payloadSlot.captured
        assertFalse(
            "Payload url should not contain raw email",
            (payload["url"] as? String)?.contains("secret@example.com") == true
        )
        assertTrue(
            "Payload url should contain EMAIL placeholder",
            (payload["url"] as? String)?.contains("[EMAIL-") == true
        )
    }

    @Test
    fun `host receives masked requestBody when sanitizers configured`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin(sanitizers = listOf(emailRule))
        plugin.onAttach(host)

        plugin.record(makeTransaction("body-mask"))

        val payloadSlot = slot<Map<String, Any?>>()
        verify { host.send(any(), capture(payloadSlot)) }

        val body = payloadSlot.captured["requestBody"] as? String
        assertFalse("Body should not contain raw email", body?.contains("secret@example.com") == true)
        assertTrue("Body should contain EMAIL placeholder", body?.contains("[EMAIL-") == true)
    }

    @Test
    fun `host receives masked request header value when sanitizers configured`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin(sanitizers = listOf(bearerRule))
        plugin.onAttach(host)

        plugin.record(makeTransaction("header-mask"))

        val payloadSlot = slot<Map<String, Any?>>()
        verify { host.send(any(), capture(payloadSlot)) }

        @Suppress("UNCHECKED_CAST")
        val headers = payloadSlot.captured["requestHeaders"] as? Map<String, String>
        val auth = headers?.get("Authorization")
        assertFalse("Auth header should not contain raw token", auth?.contains("secret-token") == true)
        assertTrue("Auth header should contain BEARER_TOKEN placeholder", auth?.contains("[BEARER_TOKEN-") == true)
    }

    // ── Zero-overhead when no rules ───────────────────────────────────────────

    @Test
    fun `host receives original transaction payload when no sanitizers`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin() // no sanitizers
        plugin.onAttach(host)

        val tx = makeTransaction("no-mask")
        plugin.record(tx)

        val payloadSlot = slot<Map<String, Any?>>()
        verify { host.send(any(), capture(payloadSlot)) }

        assertEquals(tx.url, payloadSlot.captured["url"])
        assertEquals(tx.requestBody, payloadSlot.captured["requestBody"])
    }

    @Test
    fun `buffer contains original data regardless of sanitizers`() {
        val plugin = NetworkPlugin(sanitizers = listOf(emailRule, bearerRule))
        val tx = makeTransaction("buf-raw")
        plugin.record(tx)

        val dumped = plugin.dump().single()
        assertEquals(tx.url, dumped.url)
        assertEquals(tx.requestBody, dumped.requestBody)
        assertEquals(tx.requestHeaders, dumped.requestHeaders)
    }

    // ── Multiple sanitizers applied simultaneously ────────────────────────────

    @Test
    fun `host receives payload with both email and bearer masked`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin(sanitizers = listOf(emailRule, bearerRule))
        plugin.onAttach(host)

        val tx = makeTransaction("multi-mask")
        plugin.record(tx)

        val payloadSlot = slot<Map<String, Any?>>()
        verify { host.send(any(), capture(payloadSlot)) }

        val url = payloadSlot.captured["url"] as? String
        @Suppress("UNCHECKED_CAST")
        val headers = payloadSlot.captured["requestHeaders"] as? Map<String, String>

        assertFalse("URL should not contain raw email", url?.contains("secret@example.com") == true)
        assertTrue("URL should contain EMAIL placeholder", url?.contains("[EMAIL-") == true)
        assertFalse("Auth should not contain raw token", headers?.get("Authorization")?.contains("secret-token") == true)
        assertTrue("Auth should contain BEARER_TOKEN placeholder", headers?.get("Authorization")?.contains("[BEARER_TOKEN-") == true)
    }

    // ── Plugin id is unchanged ────────────────────────────────────────────────

    @Test
    fun `plugin id is still network with sanitizers`() {
        val plugin = NetworkPlugin(sanitizers = listOf(emailRule))
        assertEquals("network", plugin.id)
    }

    // ── Host.send is called with plugin id network ────────────────────────────

    @Test
    fun `host send is called with plugin id network when sanitizers active`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin(sanitizers = listOf(emailRule))
        plugin.onAttach(host)

        plugin.record(makeTransaction())

        val idSlot = slot<String>()
        verify { host.send(capture(idSlot), any()) }
        assertEquals("network", idSlot.captured)
    }
}
