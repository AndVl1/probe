package tech.devlens

import tech.devlens.transport.ProbeTransport
import tech.devlens.transport.WebSocketTransport

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

    private fun start() {
        transport.connect()
        plugins.forEach { it.onAttach(this) }
    }

    private fun stop() {
        plugins.forEach { it.onDetach() }
        transport.disconnect()
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
