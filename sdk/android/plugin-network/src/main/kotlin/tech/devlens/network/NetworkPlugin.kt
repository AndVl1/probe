package tech.devlens.network

import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.ProbePlugin
import tech.devlens.QueryRequest
import okhttp3.Interceptor
import java.util.ArrayDeque

/**
 * Probe plugin — captures OkHttp network traffic.
 *
 * ## Setup
 * ```kotlin
 * val network = NetworkPlugin()
 *
 * Probe.install(
 *     Probe.Builder(this).plugin(network).build()
 * )
 *
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(network.interceptor())  // add LAST for accurate capture
 *     .build()
 * ```
 *
 * ## Privacy mode
 * Pass [sanitizers] to mask sensitive data before streaming to the CLI.
 * The in-memory buffer always retains the original (unmasked) data.
 * ```kotlin
 * val network = NetworkPlugin(
 *     sanitizers = listOf(SanitizeRule.BEARER_TOKEN, SanitizeRule.EMAIL)
 * )
 * ```
 *
 * ## Dump recent transactions (without CLI)
 * ```kotlin
 * val recent: List<HttpTransaction> = network.dump(last = 50)
 * ```
 *
 * @param maxBodySize     Max request/response body size to capture. Default 1MB.
 * @param bufferSize      In-memory ring buffer size (number of transactions). Default 1000.
 * @param sanitizers      Rules applied to mask sensitive fields before sending to CLI. Default none.
 */
class NetworkPlugin @JvmOverloads constructor(
    private val maxBodySize: Long = 1024 * 1024L,
    private val bufferSize: Int = 1000,
    sanitizers: List<SanitizeRule> = emptyList()
) : ProbePlugin {

    private val sanitizers: List<SanitizeRule> = sanitizers.toList()

    override val id = "network"
    override val displayName = "Network"
    override val supportedPlatforms = setOf(Platform.ANDROID, Platform.IOS, Platform.FLUTTER)

    private var host: ProbeHost? = null
    private val buffer = ArrayDeque<HttpTransaction>()
    private val bufferLock = Any()

    override fun onAttach(host: ProbeHost) {
        this.host = host
    }

    override fun onDetach() {
        host = null
    }

    // Push-only plugin. Declared (not inherited) so the BCV public-API signature
    // is deterministic — see tech.devlens.ProbePlugin.onQuery.
    override fun onQuery(request: QueryRequest) {
        // Network is push-only; inbound queries are intentionally ignored.
    }

    /** Returns an OkHttp [Interceptor] that captures requests through this plugin. */
    fun interceptor(): Interceptor = NetworkInterceptor(this, maxBodySize)

    /**
     * Returns the last [last] captured transactions from the in-memory ring buffer.
     * Thread-safe. Works even when not connected to CLI.
     *
     * **Privacy note:** returns unmasked data regardless of [sanitizers] configuration.
     * Do not pass the result to logging frameworks or crash reporters without your own masking.
     */
    fun dump(last: Int = 100): List<HttpTransaction> {
        return synchronized(bufferLock) {
            val fromIndex = maxOf(0, buffer.size - last)
            buffer.toList().subList(fromIndex, buffer.size)
        }
    }

    /** Clears the in-memory transaction buffer. */
    fun clearBuffer() = synchronized(bufferLock) { buffer.clear() }

    internal fun record(transaction: HttpTransaction) {
        synchronized(bufferLock) {
            buffer.addLast(transaction) // original retained in buffer
            if (buffer.size > bufferSize) buffer.removeFirst()
        }
        val payload = if (sanitizers.isEmpty()) transaction else transaction.sanitized(sanitizers)
        host?.send(id, payload.toPayload()) // masked (or original if no rules) to CLI
    }
}
