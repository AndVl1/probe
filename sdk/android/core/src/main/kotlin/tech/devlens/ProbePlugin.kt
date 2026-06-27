package tech.devlens

/**
 * Base interface for all Probe plugins.
 *
 * Each plugin has a unique [id] that identifies it in the CLI output and protocol.
 * Plugins receive a [ProbeHost] on attach to send events to the connected CLI.
 *
 * ## Implementing a plugin
 * ```kotlin
 * class MyPlugin : ProbePlugin {
 *     override val id = "my-plugin"
 *     override val displayName = "My Plugin"
 *     override val platform = Platform.ANDROID
 *
 *     private var host: ProbeHost? = null
 *
 *     override fun onAttach(host: ProbeHost) { this.host = host }
 *     override fun onDetach() { host = null }
 *
 *     fun sendEvent(data: Map<String, Any?>) {
 *         host?.send(id, data)
 *     }
 * }
 * ```
 */
interface ProbePlugin {
    /** Unique plugin identifier used in protocol messages (e.g. "network", "database"). */
    val id: String

    /** Human-readable name shown in the CLI. */
    val displayName: String

    /**
     * Platforms this plugin supports.
     * The CLI will show a warning if a plugin reports as unsupported on the current platform.
     */
    val supportedPlatforms: Set<Platform> get() = Platform.ALL

    /** Called when the transport successfully connects to the CLI. */
    fun onAttach(host: ProbeHost)

    /** Called when the transport disconnects from the CLI. */
    fun onDetach()

    /**
     * Called when the CLI sends a query targeted at this plugin.
     *
     * **CALLED ON THE TRANSPORT THREAD — DO NOT BLOCK.** Implementations must
     * dispatch any long-running work (disk, network) to a background thread and
     * respond asynchronously via [ProbeHost.send] using [QueryResult.toPayload].
     * The transport never awaits a return value here.
     *
     * Default implementation is a no-op: plugins that do not support queries
     * (e.g. [tech.devlens.Platform.ANDROID]'s push-only plugins) recompile
     * unchanged and simply ignore inbound queries.
     *
     * @param request the inbound query; see [QueryRequest].
     */
    fun onQuery(request: QueryRequest) {}
}

/** Platforms Probe supports. */
enum class Platform {
    ANDROID,
    IOS,
    FLUTTER,
    AURORA;

    companion object {
        val ALL = values().toSet()
    }
}
