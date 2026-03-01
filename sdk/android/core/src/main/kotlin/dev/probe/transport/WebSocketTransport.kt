package dev.probe.transport

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Probe.WS"
private const val MAX_QUEUE_SIZE = 500

/**
 * WebSocket transport — connects to the Probe CLI server.
 *
 * Threading:
 * - [send] is non-blocking: puts items in a [LinkedBlockingQueue]
 * - A dedicated sender thread drains the queue and writes to WebSocket
 * - Reconnect delays are scheduled via [ScheduledThreadPoolExecutor] — never block OkHttp threads
 *
 * @param serverUrl  WebSocket server URL (e.g. "ws://localhost:8484")
 * @param appPackage App package name (sent in the hello handshake)
 * @param deviceInfo Additional device metadata sent on connect
 */
class WebSocketTransport(
    private val serverUrl: String,
    private val appPackage: String,
    private val deviceInfo: Map<String, String> = defaultDeviceInfo(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) : ProbeTransport {

    private val gson = Gson()
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE_SIZE)
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var senderThread: Thread? = null
    private val reconnectScheduler = ScheduledThreadPoolExecutor(1)
        .also { it.removeOnCancelPolicy = true }

    override val isConnected: Boolean get() = connected.get()

    override fun connect() {
        if (running.getAndSet(true)) return
        startSenderThread()
        attemptConnect()
    }

    override fun send(pluginId: String, payload: Map<String, Any?>) {
        val envelope = mapOf(
            "type" to "event",
            "plugin" to pluginId,
            "timestamp" to System.currentTimeMillis(),
            "payload" to payload
        )
        val json = gson.toJson(envelope)
        if (!queue.offer(json)) {
            queue.poll() // drop oldest if full
            queue.offer(json)
        }
    }

    override fun disconnect() {
        running.set(false)
        connected.set(false)
        reconnectScheduler.shutdownNow()
        webSocket?.close(1000, "Probe disconnected")
        webSocket = null
        senderThread?.interrupt()
        senderThread = null
    }

    private fun attemptConnect() {
        val request = Request.Builder().url(serverUrl).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $serverUrl")
                webSocket = ws
                connected.set(true)
                ws.send(gson.toJson(buildHello()))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Connection failed: ${t.message}. Retrying in 3s...")
                webSocket = null
                connected.set(false)
                if (running.get()) {
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
                    val json = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val ws = webSocket
                    if (ws != null && connected.get()) ws.send(json)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Send error: ${e.message}")
                }
            }
        }, "Probe-Sender").also { it.isDaemon = true }
        senderThread?.start()
    }

    private fun buildHello(): Map<String, Any> = buildMap {
        put("type", "hello")
        put("appPackage", appPackage)
        put("clientId", java.util.UUID.randomUUID().toString())
        putAll(deviceInfo)
    }

    companion object {
        fun defaultDeviceInfo(): Map<String, String> = mapOf(
            "platform" to "android",
            "deviceModel" to android.os.Build.MODEL,
            "androidVersion" to android.os.Build.VERSION.RELEASE,
            "sdkInt" to android.os.Build.VERSION.SDK_INT.toString()
        )
    }
}
