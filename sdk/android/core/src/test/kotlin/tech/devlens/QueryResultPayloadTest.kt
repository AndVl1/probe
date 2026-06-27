package tech.devlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [QueryRequest] / [QueryResult] and the [toPayload]
 * wire shape. No Android or network dependencies — just the contract shapes.
 */
class QueryResultPayloadTest {

    @Test
    fun `success payload matches the queryResult success wire shape`() {
        val result = QueryResult.Success(
            requestId = "q-1",
            result = mapOf("databases" to emptyList<Any?>())
        )

        val payload = result.toPayload()

        assertEquals("queryResult", payload["op"])
        assertEquals("q-1", payload["requestId"])
        assertEquals(true, payload["ok"])
        assertNotNull(payload["result"])
        assertFalse(payload.containsKey("error"))
    }

    @Test
    fun `error payload matches the queryResult error wire shape`() {
        val result = QueryResult.Error(
            requestId = "q-2",
            code = "unknown_plugin",
            message = "No plugin registered for id 'ghost'"
        )

        val payload = result.toPayload()

        assertEquals("queryResult", payload["op"])
        assertEquals("q-2", payload["requestId"])
        assertEquals(false, payload["ok"])
        assertFalse(payload.containsKey("result"))

        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any>
        assertEquals("unknown_plugin", error["code"])
        assertEquals("No plugin registered for id 'ghost'", error["message"])
    }

    @Test
    fun `queryResult payload echoes requestId for CLI correlation`() {
        val success = QueryResult.Success("request-xyz", mapOf("k" to 1)).toPayload()
        val error = QueryResult.Error("request-xyz", "internal_error", "boom").toPayload()

        assertEquals("request-xyz", success["requestId"])
        assertEquals("request-xyz", error["requestId"])
    }

    @Test
    fun `QueryRequest defaults empty params`() {
        val request = QueryRequest(requestId = "q", plugin = "database", method = "listDatabases")
        assertTrue(request.params.isEmpty())
    }
}
