package tech.devlens

import android.util.Log
import tech.devlens.transport.ProbeTransport
import tech.devlens.transport.WebSocketTransport

private const val TAG = "Probe"

/**
 * Probe — plugin-based mobile app inspector.
 *
 * Connects to the Probe CLI tool running on your development machine and
 * forwards data from registered plugins in real-time.
 *
 * ## Setup (Application.onCreate)
 * ```kotlin
 * val networkPlugin = NetworkPlugin()
 *
 * Probe.install(
 *     Probe.Builder(this)
 *         .serverUrl("ws://localhost:8484")   // use adb reverse tcp:8484 tcp:8484
 *         .plugin(networkPlugin)
 *         // .plugin(DatabasePlugin(db))
 *         // .plugin(PreferencesPlugin())
 *         // .plugin(LayoutPlugin())
 *         .build()
 * )
 *
 * // Attach network plugin to your OkHttpClient:
 * OkHttpClient.Builder()
 *     .addInterceptor(networkPlugin.interceptor())
 *     .build()
 * ```
 *
 * ## Platforms
 * - Android: [WebSocketTransport] over `adb reverse` or LAN
 * - iOS: see `sdk/ios/` (Swift Package)
 * - Flutter: see `sdk/flutter/` (Dart package)
 * - AuroraOS: see `sdk/aurora/`
 */
class Probe private constructor(
    private val transport: ProbeTransport,
    private val plugins: List<ProbePlugin>
) : ProbeHost {

    override val isConnected: Boolean get() = transport.isConnected

    override fun send(pluginId: String, payload: Map<String, Any?>) {
        transport.send(pluginId, payload)
    }

    /** Plugins keyed by [ProbePlugin.id] for inbound query routing. */
    private val pluginsById: Map<String, ProbePlugin> by lazy {
        plugins.associateBy { it.id }
    }

    private fun start() {
        // Wire the inbound (CLI → SDK) query path. Soft cast keeps non-WebSocket
        // transports working unchanged.
        (transport as? WebSocketTransport)?.setInboundQueryHandler(::handleInbound)
        transport.connect()
        plugins.forEach { it.onAttach(this) }
    }

    private fun stop() {
        plugins.forEach { it.onDetach() }
        (transport as? WebSocketTransport)?.setInboundQueryHandler(null)
        transport.disconnect()
    }

    /**
     * Routes an inbound [QueryRequest] (delivered on the transport thread) to the
     * matching plugin's [ProbePlugin.onQuery]. Unknown plugins get an
     * `unknown_plugin` error response so the CLI's pending request never hangs.
     */
    private fun handleInbound(request: QueryRequest) {
        val plugin = pluginsById[request.plugin]
        if (plugin != null) {
            plugin.onQuery(request) // plugin self-dispatches async + responds via send()
        } else {
            Log.w(TAG, "Query for unknown plugin '${request.plugin}'")
            send(
                request.plugin,
                QueryResult.Error(
                    requestId = request.requestId,
                    code = "unknown_plugin",
                    message = "No plugin registered for id '${request.plugin}'"
                ).toPayload()
            )
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    class Builder(private val appContext: android.content.Context) {
        private var serverUrl: String = "ws://10.0.2.2:8484"
        private var transport: ProbeTransport? = null
        private val plugins: MutableList<ProbePlugin> = mutableListOf()

        /**
         * WebSocket server URL.
         * - Emulator default: `ws://10.0.2.2:8484`
         * - Physical device (USB): `ws://localhost:8484` + `adb reverse tcp:8484 tcp:8484`
         * - Physical device (WiFi): `ws://<machine-ip>:8484`
         */
        fun serverUrl(url: String) = apply { serverUrl = url }

        /** Custom transport (overrides [serverUrl]). */
        fun transport(transport: ProbeTransport) = apply { this.transport = transport }

        /** Register a plugin. Plugins receive events in registration order. */
        fun plugin(plugin: ProbePlugin) = apply { plugins.add(plugin) }

        fun build(): Probe {
            val appPackage = appContext.packageName
            val resolvedTransport = transport ?: autoTransport(serverUrl, appPackage)
            return Probe(resolvedTransport, plugins.toList())
        }

        private fun autoTransport(url: String, appPackage: String): ProbeTransport =
            WebSocketTransport(serverUrl = url, appPackage = appPackage)
    }

    // ── Companion (global singleton) ─────────────────────────────────────────

    companion object {
        @Volatile
        private var instance: Probe? = null

        /** Install Probe as a global singleton and start all plugins. */
        fun install(probe: Probe) {
            instance?.stop()
            instance = probe
            probe.start()
        }

        /** Returns the active [ProbeHost], or null if not installed. */
        fun host(): ProbeHost? = instance

        /** Stop all plugins and disconnect. */
        fun uninstall() {
            instance?.stop()
            instance = null
        }
    }
}
