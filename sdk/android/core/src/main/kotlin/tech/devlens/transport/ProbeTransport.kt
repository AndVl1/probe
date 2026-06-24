package tech.devlens.transport

/**
 * Transport layer — responsible for the physical connection between the SDK and CLI.
 *
 * ## Built-in implementations
 * - [WebSocketTransport] — default, connects over WebSocket (ws://)
 *
 * ## Custom transports
 * Implement this interface to add alternative delivery mechanisms:
 * ```kotlin
 * class FileTransport(val file: File) : ProbeTransport {
 *     override fun connect() { /* open file */ }
 *     override fun send(pluginId: String, payload: Map<String, Any?>) { /* append */ }
 *     override fun disconnect() { /* close */ }
 *     override val isConnected = true
 * }
 * ```
 */
interface ProbeTransport {
    /** Initiates connection. May reconnect automatically on failure. */
    fun connect()

    /**
     * Sends a plugin event. Must be non-blocking — implementations should
     * queue internally if the connection is not yet ready.
     */
    fun send(pluginId: String, payload: Map<String, Any?>)

    /** Closes the connection and releases resources. */
    fun disconnect()

    /** Whether the transport is currently connected. */
    val isConnected: Boolean
}
