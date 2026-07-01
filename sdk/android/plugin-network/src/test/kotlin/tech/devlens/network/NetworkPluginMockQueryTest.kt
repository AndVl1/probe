package tech.devlens.network

import tech.devlens.ProbeHost
import tech.devlens.QueryRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NetworkPlugin.onQuery] mock-rule dispatch.
 *
 * Covers:
 *  - The four query methods (setMock / listMocks / removeMock / clearMocks) and
 *    their ack shapes (architecture.json `wire_contract`).
 *  - DoD-14: unknown method → `queryResult` with `ok:false`, code `unknown_method`.
 *  - DoD-13: rules survive the onDetach → onAttach cycle that a WebSocket
 *    transport reconnect triggers (the plugin instance is retained by the Probe
 *    singleton; only `host` is cycled).
 *  - Invariant validation: exactly one of status/error; missing url/id.
 *
 * No Android dependencies — runs as a plain JVM unit test with a fake ProbeHost.
 */
class NetworkPluginMockQueryTest {

    private lateinit var plugin: NetworkPlugin
    private lateinit var host: CapturingHost

    private class CapturingHost : ProbeHost {
        val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
        override val isConnected: Boolean = true
        override fun send(pluginId: String, payload: Map<String, Any?>) {
            sent.add(pluginId to payload)
        }
    }

    @Before
    fun setUp() {
        plugin = NetworkPlugin()
        host = CapturingHost()
        plugin.onAttach(host)
    }

    /** Calls [plugin.onQuery] and returns the last ack payload sent to the host. */
    private fun query(method: String, params: Map<String, Any?> = emptyMap(), requestId: String = "q-1"): Map<String, Any?> {
        plugin.onQuery(QueryRequest(requestId = requestId, plugin = "network", method = method, params = params))
        return host.sent.last().second
    }

    @Suppress("UNCHECKED_CAST")
    private fun resultOf(ack: Map<String, Any?>): Map<String, Any?> =
        ack["result"] as Map<String, Any?>

    // ── setMock ───────────────────────────────────────────────────────────────

    @Test
    fun `setMock with status returns ack id action and activeCount`() {
        val ack = query("setMock", mapOf(
            "method" to "GET",
            "url" to "/api/users",
            "status" to 200,            // Gson would deliver Double; Number.toInt handles it
            "body" to """{"ok":1}"""
        ))

        assertEquals("queryResult", ack["op"])
        assertEquals("q-1", ack["requestId"])
        assertEquals(true, ack["ok"])

        val result = resultOf(ack)
        assertEquals("set", result["action"])
        assertEquals(1, result["activeCount"])
        val id = result["id"] as String
        assertTrue("id must be rule-<uuid>, was $id", id.startsWith("rule-"))
    }

    @Test
    fun `setMock with error field is accepted`() {
        val ack = query("setMock", mapOf(
            "url" to "/fail",
            "error" to "boom"
        ))
        assertEquals(true, ack["ok"])
        assertEquals(1, resultOf(ack)["activeCount"])
    }

    @Test
    fun `setMock with null method is accepted as wildcard`() {
        val ack = query("setMock", mapOf(
            "url" to "/any",
            "status" to 204
        ))
        assertEquals(true, ack["ok"])
    }

    @Test
    fun `setMock without url is a validation_error`() {
        val ack = query("setMock", mapOf("status" to 200))
        assertEquals(false, ack["ok"])
        val error = ack["error"] as Map<*, *>
        assertEquals("validation_error", error["code"])
    }

    @Test
    fun `setMock with both status and error violates invariant`() {
        val ack = query("setMock", mapOf(
            "url" to "/x",
            "status" to 200,
            "error" to "boom"
        ))
        assertEquals(false, ack["ok"])
        assertEquals("validation_error", (ack["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `setMock with neither status nor error violates invariant`() {
        val ack = query("setMock", mapOf("url" to "/x"))
        assertEquals(false, ack["ok"])
        assertEquals("validation_error", (ack["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `setMock parses headers map defensively`() {
        val ack = query("setMock", mapOf(
            "url" to "/h",
            "status" to 200,
            "headers" to mapOf("X-Trace" to "abc", "Content-Type" to "application/json")
        ))
        assertEquals(true, ack["ok"])

        val list = resultOf(query("listMocks"))
        @Suppress("UNCHECKED_CAST")
        val rules = list["rules"] as List<Map<String, Any?>>
        assertEquals(1, rules.size)
        @Suppress("UNCHECKED_CAST")
        val headers = rules[0]["headers"] as Map<String, String>
        assertEquals("abc", headers["X-Trace"])
        assertEquals("application/json", headers["Content-Type"])
    }

    // ── A1: malformed header rejected at parse time (fail fast, rule not stored) ─

    @Test
    fun `setMock with header value containing newline is validation_error and rule not applied (A1)`() {
        val ack = query("setMock", mapOf(
            "url" to "/h",
            "status" to 200,
            "headers" to mapOf("X-Bad" to "value-with\n-injected-newline")
        ))
        assertEquals(false, ack["ok"])
        val error = ack["error"] as Map<*, *>
        assertEquals("validation_error", error["code"])
        // Rule must NOT have been stored (fail fast, before set()).
        assertEquals(0, resultOf(query("listMocks"))["count"])
    }

    @Test
    fun `setMock with header value containing carriage return is validation_error (A1)`() {
        val ack = query("setMock", mapOf(
            "url" to "/h",
            "status" to 200,
            "headers" to mapOf("X-Bad" to "value-with\r-injected-cr")
        ))
        assertEquals(false, ack["ok"])
        assertEquals("validation_error", (ack["error"] as Map<*, *>)["code"])
        assertEquals(0, resultOf(query("listMocks"))["count"])
    }

    @Test
    fun `setMock with header name containing newline is validation_error (A1)`() {
        val ack = query("setMock", mapOf(
            "url" to "/h",
            "status" to 200,
            "headers" to mapOf("X\n-Inject" to "ok")
        ))
        assertEquals(false, ack["ok"])
        assertEquals("validation_error", (ack["error"] as Map<*, *>)["code"])
        assertEquals(0, resultOf(query("listMocks"))["count"])
    }

    // ── listMocks ─────────────────────────────────────────────────────────────

    @Test
    fun `listMocks on empty store returns count zero`() {
        val ack = query("listMocks")
        assertEquals(true, ack["ok"])
        val result = resultOf(ack)
        assertEquals(0, result["count"])
        @Suppress("UNCHECKED_CAST")
        val rules = result["rules"] as List<*>
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `listMocks echoes rules with all wire fields`() {
        query("setMock", mapOf("method" to "GET", "url" to "/a", "status" to 200, "body" to "A"))
        query("setMock", mapOf("method" to null, "url" to "/b", "status" to 500, "reason" to "Boom"))

        val result = resultOf(query("listMocks"))
        assertEquals(2, result["count"])
        @Suppress("UNCHECKED_CAST")
        val rules = result["rules"] as List<Map<String, Any?>>
        val byUrl = rules.associateBy { it["url"] as String }

        assertEquals("GET", byUrl["/a"]?.get("method"))
        assertEquals(200, byUrl["/a"]?.get("status"))
        assertEquals("A", byUrl["/a"]?.get("body"))
        // null method serializes as null (wildcard)
        assertNull(byUrl["/b"]?.get("method"))
        assertEquals("Boom", byUrl["/b"]?.get("reason"))
    }

    // ── removeMock ────────────────────────────────────────────────────────────

    @Test
    fun `removeMock on existing id returns removed true and decremented activeCount`() {
        val setId = resultOf(query("setMock", mapOf("url" to "/a", "status" to 200)))["id"] as String
        val ack = query("removeMock", mapOf("id" to setId))
        assertEquals(true, ack["ok"])
        val result = resultOf(ack)
        assertEquals(setId, result["id"])
        assertEquals(true, result["removed"])
        assertEquals(0, result["activeCount"])
    }

    @Test
    fun `removeMock on unknown id returns removed false and is not an error (idempotent)`() {
        val ack = query("removeMock", mapOf("id" to "rule-nope"))
        assertEquals(true, ack["ok"])
        val result = resultOf(ack)
        assertEquals(false, result["removed"])
    }

    @Test
    fun `removeMock without id is a validation_error`() {
        val ack = query("removeMock")
        assertEquals(false, ack["ok"])
        assertEquals("validation_error", (ack["error"] as Map<*, *>)["code"])
    }

    // ── clearMocks ────────────────────────────────────────────────────────────

    @Test
    fun `clearMocks returns cleared count and activeCount zero`() {
        query("setMock", mapOf("url" to "/a", "status" to 200))
        query("setMock", mapOf("url" to "/b", "status" to 200))
        val result = resultOf(query("clearMocks"))
        assertEquals(2, result["cleared"])
        assertEquals(0, result["activeCount"])
    }

    @Test
    fun `clearMocks on empty store returns cleared zero`() {
        assertEquals(0, resultOf(query("clearMocks"))["cleared"])
    }

    // ── DoD-14: unknown method ────────────────────────────────────────────────

    @Test
    fun `unknown method returns ok false with code unknown_method (DoD-14)`() {
        val ack = query("bogusMethod")
        assertEquals("queryResult", ack["op"])
        assertEquals("q-1", ack["requestId"])
        assertEquals(false, ack["ok"])
        val error = ack["error"] as Map<*, *>
        assertEquals("unknown_method", error["code"])
        assertNotNull(error["message"])
    }

    // ── DoD-13: rules survive WS reconnect (onDetach/onAttach cycle) ──────────

    @Test
    fun `rules survive onDetach then onAttach reconnect cycle (DoD-13)`() {
        // A WebSocket transport reconnect calls onDetach → onAttach on the SAME
        // plugin instance. The ruleStore is NOT cleared in onDetach, so rules
        // persist. Simulate that cycle here at the plugin level (no real socket).
        query("setMock", mapOf("method" to "GET", "url" to "/persist", "status" to 200, "body" to "alive"))
        assertEquals(1, resultOf(query("listMocks"))["count"])

        // Simulate a reconnect: the transport drops then re-establishes.
        plugin.onDetach()
        // While detached, onQuery is a no-op (host == null) — proves no crash.
        query("listMocks", requestId = "q-while-detached")
        assertTrue("no ack sent while detached", host.sent.none { (_, p) -> p["requestId"] == "q-while-detached" })

        // Re-attach (simulating the transport reconnecting with the same plugin).
        plugin.onAttach(host)

        val result = resultOf(query("listMocks", requestId = "q-after"))
        assertEquals("rule must survive the detach/attach cycle", 1, result["count"])
        @Suppress("UNCHECKED_CAST")
        val rules = result["rules"] as List<Map<String, Any?>>
        assertEquals("/persist", rules[0]["url"])
        assertEquals("alive", rules[0]["body"])
    }

    @Test
    fun `onQuery is a no-op when host is null (detached)`() {
        plugin.onDetach()
        val before = host.sent.size
        plugin.onQuery(QueryRequest(requestId = "q", plugin = "network", method = "listMocks"))
        assertEquals("nothing sent while detached", before, host.sent.size)
    }

    // ── wire envelope shape ───────────────────────────────────────────────────

    @Test
    fun `ack is sent under plugin id network`() {
        query("listMocks")
        assertEquals("network", host.sent.last().first)
    }
}
