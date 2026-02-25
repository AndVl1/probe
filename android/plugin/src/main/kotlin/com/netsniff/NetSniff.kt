package com.netsniff

import com.netsniff.transport.NetSniffTransport
import com.netsniff.transport.WebSocketTransport
import okhttp3.Interceptor

/**
 * NetSniff — Android network traffic sniffer plugin.
 *
 * Designed for debugging AI agent HTTP requests. Captures all OkHttp traffic
 * and forwards it to the NetSniff CLI tool running on your development machine.
 *
 * Usage:
 * ```kotlin
 * // In Application.onCreate():
 * NetSniff.Builder(this)
 *     .serverUrl("ws://10.0.2.2:8484")  // 10.0.2.2 = host from emulator
 *     .build()
 *     .also { NetSniff.install(it) }
 *
 * // When building OkHttpClient:
 * OkHttpClient.Builder()
 *     .addInterceptor(NetSniff.interceptor())
 *     .build()
 * ```
 */
class NetSniff private constructor(
    val transport: NetSniffTransport,
    private val appId: String?,
    private val maxBodySize: Long
) {

    /**
     * Creates an OkHttp interceptor for this NetSniff instance.
     * Add to your OkHttpClient via [OkHttpClient.Builder.addInterceptor].
     */
    fun interceptor(): Interceptor = NetSniffInterceptor(transport, appId, maxBodySize)

    class Builder(
        private val appContext: android.content.Context
    ) {
        private var serverUrl: String = "ws://10.0.2.2:8484"
        private var transport: NetSniffTransport? = null
        private var maxBodySize: Long = 1024 * 1024L // 1MB

        /** WebSocket server URL. Default: ws://10.0.2.2:8484 (host from Android emulator) */
        fun serverUrl(url: String) = apply { serverUrl = url }

        /** Provide a custom transport. If not set, WebSocketTransport is used. */
        fun transport(transport: NetSniffTransport) = apply { this.transport = transport }

        /** Maximum body size to capture. Default 1MB. */
        fun maxBodySize(bytes: Long) = apply { maxBodySize = bytes }

        fun build(): NetSniff {
            val appId = appContext.packageName
            val resolvedTransport = transport ?: WebSocketTransport(
                serverUrl = serverUrl,
                appPackage = appId
            )
            return NetSniff(resolvedTransport, appId, maxBodySize)
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

        /** Disconnect and clear the instance (e.g., in debug builds only). */
        fun uninstall() {
            instance?.transport?.disconnect()
            instance = null
        }
    }
}
