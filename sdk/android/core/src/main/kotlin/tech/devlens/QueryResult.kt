package tech.devlens

/**
 * Response to a [QueryRequest]. Plugins build one of these and feed [toPayload]
 * to [ProbeHost.send] under their own plugin id.
 */
sealed class QueryResult {
    /**
     * Successful response.
     *
     * @property requestId Echoed from the originating [QueryRequest].
     * @property result    Method-specific result payload.
     */
    data class Success(
        val requestId: String,
        val result: Map<String, Any?>
    ) : QueryResult()

    /**
     * Failure response.
     *
     * @property requestId Echoed from the originating [QueryRequest].
     * @property code      Machine-readable error code (see the wire contract:
     *                     `unknown_method`, `unknown_plugin`, `not_query_capable`,
     *                     `unopenable`, `encrypted`, `table_not_found`,
     *                     `database_not_found`, `internal_error`).
     * @property message   Human-readable description of the failure.
     */
    data class Error(
        val requestId: String,
        val code: String,
        val message: String
    ) : QueryResult()
}

/**
 * Serializes this result to the `queryResult` payload shape that rides the
 * existing Event envelope via [ProbeHost.send] (`host.send(pluginId, toPayload())`).
 *
 * The full on-wire message is therefore:
 * ```
 * {"type":"event","plugin":"<id>","timestamp":<ms>,"payload":{"op":"queryResult",...}}
 * ```
 *
 * Success payload:
 * ```
 * {"op":"queryResult","requestId":"q-1","ok":true,"result":{...}}
 * ```
 *
 * Error payload:
 * ```
 * {"op":"queryResult","requestId":"q-1","ok":false,"error":{"code":"...","message":"..."}}
 * ```
 */
fun QueryResult.toPayload(): Map<String, Any?> = buildMap {
    put("op", "queryResult")
    when (this@toPayload) {
        is QueryResult.Success -> {
            put("requestId", requestId)
            put("ok", true)
            put("result", result)
        }
        is QueryResult.Error -> {
            put("requestId", requestId)
            put("ok", false)
            put("error", mapOf("code" to code, "message" to message))
        }
    }
}
