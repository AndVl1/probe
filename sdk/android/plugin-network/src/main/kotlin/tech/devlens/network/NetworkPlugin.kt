package tech.devlens.network

import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.ProbePlugin
import tech.devlens.QueryRequest
import tech.devlens.QueryResult
import tech.devlens.toPayload
import okhttp3.Headers
import okhttp3.Interceptor
import java.util.ArrayDeque
import java.util.UUID

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

    /**
     * In-memory mock-rule store. Lives on the plugin instance (retained by the
     * Probe singleton across WS reconnects); see [MockRuleStore].
     */
    private val ruleStore = MockRuleStore()

    override fun onAttach(host: ProbeHost) {
        this.host = host
    }

    override fun onDetach() {
        host = null
        // NOTE: ruleStore is intentionally NOT cleared here. A WebSocket
        // transport reconnect triggers onDetach → onAttach on this same plugin
        // instance, and mock rules must survive it (architecture.json
        // `persistence`). Only an app process restart drops them.
    }

    /**
     * Handles inbound mock-management queries dispatched by the CLI over the
     * existing query/response path. Discriminator = [QueryRequest.method]:
     * `setMock` / `listMocks` / `removeMock` / `clearMocks`.
     *
     * All operations are in-memory atomic updates, so this runs **inline on the
     * transport thread** — no executor, no blocking (satisfies the `onQuery`
     * non-blocking invariant; mock rules are config, never touch the 500-event
     * send queue). Replies via [ProbeHost.send] under this plugin's id using
     * [QueryResult.toPayload]. Template: `DatabasePlugin.onQuery`.
     */
    override fun onQuery(request: QueryRequest) {
        val h = host ?: return
        val payload = try {
            QueryResult.Success(request.requestId, dispatch(request)).toPayload()
        } catch (t: MockError) {
            QueryResult.Error(request.requestId, t.code, t.message ?: t.code).toPayload()
        } catch (t: Throwable) {
            QueryResult.Error(request.requestId, ERROR_VALIDATION, t.message ?: t.toString()).toPayload()
        }
        h.send(id, payload)
    }

    /** Snapshot of current mock rules — lock-free read for the interceptor. */
    internal fun snapshotMockRules(): List<MockRule> = ruleStore.snapshot()

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

    // ── mock-rule query dispatch ──────────────────────────────────────────────

    private fun dispatch(request: QueryRequest): Map<String, Any?> =
        when (request.method) {
            "setMock" -> setMock(request.params)
            "listMocks" -> listMocks()
            "removeMock" -> removeMock(request.params)
            "clearMocks" -> clearMocks()
            else -> throw MockError(
                code = ERROR_UNKNOWN_METHOD,
                message = "Unknown method: ${request.method}"
            )
        }

    private fun setMock(params: Map<String, Any?>): Map<String, Any?> {
        val rule = parseRule(params) // validates invariant
        ruleStore.set(rule)
        return mapOf(
            "id" to rule.id,
            "action" to "set",
            "activeCount" to ruleStore.list().size
        )
    }

    private fun listMocks(): Map<String, Any?> {
        val rules = ruleStore.list()
        return mapOf(
            "rules" to rules.map { it.toWireMap() },
            "count" to rules.size
        )
    }

    private fun removeMock(params: Map<String, Any?>): Map<String, Any?> {
        val id = (params["id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw MockError(ERROR_VALIDATION, "Missing or empty required param 'id'")
        val removed = ruleStore.remove(id)
        return mapOf(
            "id" to id,
            "removed" to removed,
            "activeCount" to ruleStore.list().size
        )
    }

    private fun clearMocks(): Map<String, Any?> {
        val cleared = ruleStore.clear()
        return mapOf(
            "cleared" to cleared,
            "activeCount" to ruleStore.list().size
        )
    }

    /**
     * Parses a [MockRule] from the `setMock` query params. The params map holds
     * the rule fields directly (see architecture.json `wire_contract.setMock`):
     * `method` (String|null), `url` (required), and EITHER `status`(+optional
     * `reason`/`headers`/`body`) OR `error`. Parsed defensively because Gson
     * deserializes JSON numbers as `Double` and objects as `LinkedTreeMap`.
     *
     * @throws MockError on any missing/invalid field or invariant violation.
     */
    private fun parseRule(params: Map<String, Any?>): MockRule {
        val url = (params["url"] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw MockError(ERROR_VALIDATION, "Missing or empty required param 'url'")
        val method = (params["method"] as? String)?.takeIf { it.isNotEmpty() }
        val status = (params["status"] as? Number)?.toInt()
        val reason = params["reason"] as? String
        val body = params["body"] as? String
        val error = params["error"] as? String
        val headers = readHeaders(params)

        // Invariant: exactly one of status / error is non-null.
        if ((status != null) == (error != null)) {
            throw MockError(
                ERROR_VALIDATION,
                if (status != null)
                    "Mock rule must set exactly one of 'status' or 'error' (both set)"
                else
                    "Mock rule must set exactly one of 'status' or 'error' (neither set)"
            )
        }
        return MockRule(
            id = "rule-" + UUID.randomUUID(),
            method = method,
            url = url,
            status = status,
            reason = reason,
            headers = headers,
            body = body,
            error = error
        )
    }

    /**
     * Reads the optional `headers` object into a String→String map, tolerating
     * Gson types.
     *
     * Names/values are validated via OkHttp's own [Headers] rules so a malformed
     * header (e.g. one carrying `\r`/`\n`, which a CLI caller can inject) is
     * rejected here with a clean [MockError] — fail fast at `setMock`, before the
     * rule is stored. Without this, the bad header would surface later inside
     * [NetworkInterceptor.applyMock], where `Headers.Builder.add` throws an
     * uncaught `IllegalArgumentException` on the OkHttp dispatcher thread and
     * crashes the app's real HTTP call (review finding A1). OkHttp validates
     * eagerly in `add`, so an illegal char throws at the first offending entry.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readHeaders(params: Map<String, Any?>): Map<String, String> {
        val raw = params["headers"] as? Map<String, Any?> ?: return emptyMap()
        val out = LinkedHashMap<String, String>(raw.size)
        raw.forEach { (k, v) ->
            val key = k as? String ?: return@forEach
            out[key] = v?.toString() ?: ""
        }
        if (out.isNotEmpty()) {
            try {
                // Forces the exact same name/value validation OkHttp performs
                // when the interceptor builds the synthetic Response.
                Headers.Builder().apply {
                    out.forEach { (k, v) -> add(k, v) }
                }.build()
            } catch (e: IllegalArgumentException) {
                throw MockError(ERROR_VALIDATION, "Invalid response header: ${e.message}")
            }
        }
        return out
    }

    /** Internal typed error so [onQuery] can map to the right contract code. */
    private class MockError(val code: String, message: String) : RuntimeException(message)
}

// File-private wire-stable error codes (see architecture.json wire_contract).
// `const val` keeps these off the class's public API surface (inlined at use).
private const val ERROR_UNKNOWN_METHOD = "unknown_method"
private const val ERROR_VALIDATION = "validation_error"
