package com.netsniff

import com.netsniff.model.HttpTransaction
import com.netsniff.transport.NetSniffTransport
import com.netsniff.transport.WebSocketTransport
import okhttp3.Interceptor

/**
 * NetSniff — Android network traffic sniffer plugin.
 *
 * Designed for debugging AI agent HTTP requests. Captures all OkHttp traffic,
 * stores it in an in-memory ring buffer, and forwards it to the NetSniff CLI.
 *
 * Usage:
 * ```kotlin
 * // In Application.onCreate():
 * NetSniff.install(
 *     NetSniff.Builder(this)
 *         .serverUrl("ws://10.0.2.2:8484")
 *         .bufferSize(500)
 *         .build()
 * )
 *
 * // When building OkHttpClient:
 * OkHttpClient.Builder()
 *     .addInterceptor(NetSniff.interceptor())
 *     .build()
 *
 * // Dump last 50 transactions anywhere in the app:
 * val transactions: List<HttpTransaction> = NetSniff.dump(last = 50)
 * ```
 */
class NetSniff private constructor(
    val transport: NetSniffTransport,
    private val appId: String?,
    private val maxBodySize: Long,
    private val maxBufferSize: Int
) {
    // Thread-safe ring buffer — stores last maxBufferSize transactions
    private val buffer = ArrayDeque<HttpTransaction>()
    private val bufferLock = Any()

    /**
     * Called by [NetSniffInterceptor] for every completed request.
     * Stores in ring buffer and forwards to transport (non-blocking).
     */
    internal fun record(transaction: HttpTransaction) {
        // Stamp the appId (package name) — interceptor doesn't need to know it
        val stamped = if (appId != null && transaction.appId == null)
            transaction.copy(appId = appId) else transaction
        synchronized(bufferLock) {
            buffer.addLast(stamped)
            if (buffer.size > maxBufferSize) buffer.removeFirst()
        }
        transport.send(stamped)
    }

    /**
     * Returns the last [n] captured transactions from the in-memory buffer.
     * Thread-safe snapshot — returns a copy.
     */
    fun dump(last: Int = 100): List<HttpTransaction> {
        return synchronized(bufferLock) {
            val fromIndex = maxOf(0, buffer.size - last)
            buffer.toList().subList(fromIndex, buffer.size)
        }
    }

    /** Clears the in-memory transaction buffer. */
    fun clearBuffer() {
        synchronized(bufferLock) { buffer.clear() }
    }

    /**
     * Creates an OkHttp interceptor for this NetSniff instance.
     * Add to your OkHttpClient via [OkHttpClient.Builder.addInterceptor].
     */
    fun interceptor(): Interceptor = NetSniffInterceptor(this, maxBodySize)

    class Builder(private val appContext: android.content.Context) {
        private var serverUrl: String = "ws://10.0.2.2:8484"
        private var transport: NetSniffTransport? = null
        private var maxBodySize: Long = 1024 * 1024L // 1MB
        private var bufferSize: Int = 1000

        /** WebSocket server URL. Default: ws://10.0.2.2:8484 (host from Android emulator) */
        fun serverUrl(url: String) = apply { serverUrl = url }

        /** Provide a custom transport. If not set, WebSocketTransport is used. */
        fun transport(transport: NetSniffTransport) = apply { this.transport = transport }

        /** Maximum body size to capture per request. Default 1MB. */
        fun maxBodySize(bytes: Long) = apply { maxBodySize = bytes }

        /** In-memory ring buffer size (number of transactions to keep). Default 1000. */
        fun bufferSize(n: Int) = apply { bufferSize = n }

        fun build(): NetSniff {
            val appId = appContext.packageName
            val resolvedTransport = transport ?: WebSocketTransport(
                serverUrl = serverUrl,
                appPackage = appId
            )
            return NetSniff(resolvedTransport, appId, maxBodySize, bufferSize)
        }
    }

    companion object {
        @Volatile
        private var instance: NetSniff? = null

        /** Install a NetSniff instance as the global singleton. Starts the transport. */
        fun install(netSniff: NetSniff) {
            instance = netSniff
            netSniff.transport.connect()
        }

        /** Get the interceptor from the installed instance. Returns no-op if not installed. */
        fun interceptor(): Interceptor {
            val inst = instance
            return inst?.interceptor() ?: Interceptor { it.proceed(it.request()) }
        }

        /**
         * Returns the last [last] captured HTTP transactions.
         *
         * Example:
         * ```kotlin
         * val recent = NetSniff.dump(last = 20)
         * recent.forEach { tx ->
         *     Log.d("NetSniff", "${tx.method} ${tx.url} → ${tx.responseCode}")
         * }
         * ```
         */
        fun dump(last: Int = 100): List<HttpTransaction> =
            instance?.dump(last) ?: emptyList()

        /** Clears the in-memory transaction buffer. */
        fun clearBuffer() = instance?.clearBuffer()

        /** Disconnect and clear the instance (e.g., in debug builds only). */
        fun uninstall() {
            instance?.transport?.disconnect()
            instance = null
        }
    }
}
