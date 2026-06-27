package tech.devlens

/**
 * Inbound query from the CLI — the read half of the bidirectional Probe protocol.
 *
 * A [QueryRequest] is delivered to [ProbePlugin.onQuery] **on the transport
 * thread**. Plugins must self-dispatch any long-running work (disk, network) to
 * a background thread and respond asynchronously via [ProbeHost.send] using
 * [toPayload].
 *
 * Wire shape (CLI → SDK), parsed by [tech.devlens.transport.WebSocketTransport]:
 * ```
 * {"type":"query","requestId":"q-1","plugin":"database","method":"listDatabases","params":{}}
 * ```
 *
 * @property requestId Correlates the response with this request (echoed back in [QueryResult]).
 * @property plugin    Target plugin id (matches [ProbePlugin.id]).
 * @property method    Plugin-defined method name (e.g. `"listDatabases"`).
 * @property params    Plugin-defined parameters — opaque to the transport.
 */
data class QueryRequest(
    val requestId: String,
    val plugin: String,
    val method: String,
    val params: Map<String, Any?> = emptyMap()
)
