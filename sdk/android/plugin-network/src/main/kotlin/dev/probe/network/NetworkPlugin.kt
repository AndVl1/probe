package dev.probe.network

import dev.probe.Platform
import dev.probe.ProbeHost
import dev.probe.ProbePlugin
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
 * ## Dump recent transactions (without CLI)
 * ```kotlin
 * val recent: List<HttpTransaction> = network.dump(last = 50)
 * ```
 *
 * @param maxBodySize     Max request/response body size to capture. Default 1MB.
 * @param bufferSize      In-memory ring buffer size (number of transactions). Default 1000.
 */
class NetworkPlugin(
    private val maxBodySize: Long = 1024 * 1024L,
    private val bufferSize: Int = 1000
) : ProbePlugin {

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

    /** Returns an OkHttp [Interceptor] that captures requests through this plugin. */
    fun interceptor(): Interceptor = NetworkInterceptor(this, maxBodySize)

    /**
     * Returns the last [last] captured transactions from the in-memory ring buffer.
     * Thread-safe. Works even when not connected to CLI.
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
            buffer.addLast(transaction)
            if (buffer.size > bufferSize) buffer.removeFirst()
        }
        host?.send(id, transaction.toPayload())
    }
}
