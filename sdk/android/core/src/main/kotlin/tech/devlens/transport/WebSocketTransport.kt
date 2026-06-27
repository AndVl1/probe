package tech.devlens.transport

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tech.devlens.QueryRequest
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
 * @param clientId   Stable client identifier — one UUID per transport instance,
 *                   reused across reconnects so the CLI can correlate successive
 *                   sessions from the same app instance. `java.util.UUID` is JDK
 *                   stdlib → no new dependencies.
 */
class WebSocketTransport(
    private val serverUrl: String,
    private val appPackage: String,
    private val deviceInfo: Map<String, String> = defaultDeviceInfo(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val clientId: String = java.util.UUID.randomUUID().toString()
) : ProbeTransport {

    private val gson = Gson()
    /**
     * Variant that emits explicit JSON nulls. Used **only** for `queryResult`
     * payloads (discriminated by `payload["op"] == "queryResult"`, a core-level
     * marker set by `QueryResult.toPayload()`), where the DoD requires SQL NULL
     * to serialize as `"col":null` instead of a missing key (Gson's default
     * drops nulls, which made NULL cells vanish from inspect rows).
     *
     * Scoped so network `event` envelopes and the `hello` handshake remain
     * byte-unchanged — their payloads do not set `op` and keep using [gson].
     */
    private val gsonSerializingNulls = GsonBuilder().serializeNulls().create()
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE_SIZE)
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var senderThread: Thread? = null
    private val reconnectScheduler = ScheduledThreadPoolExecutor(1)
        .also { it.removeOnCancelPolicy = true }

    /**
     * Receives parsed inbound query frames. Set by [Probe.start] via
     * [setInboundQueryHandler]. Invoked on OkHttp's dispatch thread.
     */
    @Volatile
    private var inboundHandler: ((QueryRequest) -> Unit)? = null

    override val isConnected: Boolean get() = connected.get()

    override fun setInboundQueryHandler(handler: ((QueryRequest) -> Unit)?) {
        inboundHandler = handler
    }

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
        // queryResult payloads carry SQL cell values that may be NULL; the DoD
        // requires those to serialize as explicit JSON nulls. All other payloads
        // (network events, hello) keep the default gson so their wire bytes stay
        // unchanged. See [gsonSerializingNulls] for the full rationale.
        val serializer =
            if (payload["op"] == "queryResult") gsonSerializingNulls else gson
        val json = serializer.toJson(envelope)
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

            override fun onMessage(ws: WebSocket, text: String) {
                // CLI → SDK query frame. Parse defensively: a malformed frame must
                // NEVER crash the OkHttp dispatch thread.
                try {
                    @Suppress("UNCHECKED_CAST")
                    val parsed = gson.fromJson(text, Map::class.java) as? Map<String, Any?>
                        ?: return
                    if (parsed["type"] != "query") return
                    @Suppress("UNCHECKED_CAST")
                    val request = QueryRequest(
                        requestId = parsed["requestId"] as? String ?: return,
                        plugin = parsed["plugin"] as? String ?: return,
                        method = parsed["method"] as? String ?: return,
                        params = (parsed["params"] as? Map<String, Any?>).orEmpty()
                    )
                    inboundHandler?.invoke(request)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to handle inbound query frame: ${t.message}")
                }
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
        put("clientId", clientId)
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
