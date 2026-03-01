package dev.probe

/**
 * Provided to plugins via [ProbePlugin.onAttach].
 * Used to send events from a plugin to the connected CLI.
 */
interface ProbeHost {
    /**
     * Send an event to the CLI under the given [pluginId].
     *
     * @param pluginId The plugin identifier (must match [ProbePlugin.id]).
     * @param payload  Arbitrary key-value data — will be serialized to JSON.
     */
    fun send(pluginId: String, payload: Map<String, Any?>)

    /** Whether the transport is currently connected to the CLI. */
    val isConnected: Boolean
}
