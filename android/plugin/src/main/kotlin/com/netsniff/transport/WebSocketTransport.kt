package com.netsniff.transport

import android.util.Log
import com.google.gson.Gson
import com.netsniff.model.HttpTransaction
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "NetSniff.WS"
private const val MAX_QUEUE_SIZE = 500

/**
 * WebSocket-based transport. Connects to the NetSniff CLI server.
 *
 * Threading:
 * - [send] is called from OkHttp interceptor threads (non-blocking, queues the item)
 * - A dedicated sender thread reads from queue and writes to WebSocket
 * - WebSocket events arrive on OkHttp's internal thread
 */
class WebSocketTransport(
    private val serverUrl: String,
    private val appPackage: String,
    private val deviceModel: String = android.os.Build.MODEL,
    private val androidVersion: String = android.os.Build.VERSION.RELEASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) : NetSniffTransport {

    private val gson = Gson()
    private val queue = LinkedBlockingQueue<HttpTransaction>(MAX_QUEUE_SIZE)
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var senderThread: Thread? = null
    // Dedicated scheduler for reconnect delays — never blocks OkHttp's thread pool
    private val reconnectScheduler = ScheduledThreadPoolExecutor(1).also { it.removeOnCancelPolicy = true }

    override val isConnected: Boolean get() = connected.get()

    override fun connect() {
        if (running.getAndSet(true)) return
        startSenderThread()
        attemptConnect()
    }

    private fun attemptConnect() {
        val request = Request.Builder().url(serverUrl).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $serverUrl")
                webSocket = ws
                connected.set(true)
                val hello = mapOf(
                    "type" to "hello",
                    "clientId" to java.util.UUID.randomUUID().toString(),
                    "appPackage" to appPackage,
                    "deviceModel" to deviceModel,
                    "androidVersion" to androidVersion
                )
                ws.send(gson.toJson(hello))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Connection failed: ${t.message}. Retrying in 3s...")
                webSocket = null
                connected.set(false)
                if (running.get()) {
                    // Schedule reconnect without blocking OkHttp's thread pool
                    reconnectScheduler.schedule({ attemptConnect() }, 3, TimeUnit.SECONDS)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                webSocket = null
                connected.set(false)
                if (running.get()) {
                    Log.d(TAG, "Connection closed ($code). Reconnecting...")
                    reconnectScheduler.schedule({ attemptConnect() }, 3, TimeUnit.SECONDS)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                connected.set(false)
            }
        })
    }

    private fun startSenderThread() {
        senderThread = Thread({
            while (running.get()) {
                try {
                    val tx = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val ws = webSocket
                    if (ws != null && connected.get()) {
                        val json = gson.toJson(buildJsonPayload(tx))
                        ws.send(json)
                    }
                    // If not connected, drop the transaction (we don't want unbounded backlog)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending transaction: ${e.message}")
                }
            }
        }, "NetSniff-Sender").also { it.isDaemon = true }
        senderThread?.start()
    }

    /**
     * Called from OkHttp interceptor thread — must NOT block.
     * If queue is full, drops the oldest item to make room.
     */
    override fun send(transaction: HttpTransaction) {
        if (!queue.offer(transaction)) {
            queue.poll() // drop oldest
            queue.offer(transaction)
        }
    }

    override fun disconnect() {
        running.set(false)
        connected.set(false)
        reconnectScheduler.shutdownNow()
        webSocket?.close(1000, "NetSniff disconnected")
        webSocket = null
        senderThread?.interrupt()
        senderThread = null
    }

    private fun buildJsonPayload(tx: HttpTransaction): Map<String, Any?> = mapOf(
        "type" to "transaction",
        "id" to tx.id,
        "timestamp" to tx.timestamp,
        "method" to tx.method,
        "url" to tx.url,
        "requestHeaders" to tx.requestHeaders,
        "requestBody" to tx.requestBody,
        "requestSizeBytes" to tx.requestSizeBytes,
        "responseCode" to tx.responseCode,
        "responseMessage" to tx.responseMessage,
        "responseHeaders" to tx.responseHeaders,
        "responseBody" to tx.responseBody,
        "responseSizeBytes" to tx.responseSizeBytes,
        "durationMs" to tx.durationMs,
        "appId" to tx.appId,
        "error" to tx.error
    )
}
