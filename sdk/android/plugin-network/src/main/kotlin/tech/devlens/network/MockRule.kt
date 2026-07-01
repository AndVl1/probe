package tech.devlens.network

import java.util.concurrent.atomic.AtomicReference

/**
 * A network mock rule.
 *
 * When a request matching [method] + [url] flows through [NetworkInterceptor],
 * the interceptor short-circuits: it returns a synthetic `okhttp3.Response`
 * built from [status] / [reason] / [headers] / [body] WITHOUT calling
 * `chain.proceed()`, or — when [error] is set — throws `java.io.IOException`
 * instead. The real network is never hit on a match.
 *
 * ## Matching
 * A rule matches a request when:
 *  - [method] is `null` (wildcard) OR equals the request method
 *    case-insensitively (RFC 7230 §3.1.1), AND
 *  - the request URL **contains** [url] as a case-sensitive substring
 *    (`String.contains` — no regex).
 *
 * When several rules match, the one with the **longest** [url] wins (most
 * specific). A tie on length resolves to the **last-added** rule. Regex is
 * deliberately absent in v1 — `java.util.regex` backtracks on the OkHttp
 * dispatcher hot path.
 *
 * ## Invariant
 * Exactly one of [status] / [error] is non-null. This is enforced by
 * [NetworkPlugin.onQuery] at dispatch time, NOT in an `init {}` block: Gson
 * deserializes by reflective field assignment and bypasses constructors, so an
 * `init{}` guard would never run for rules arriving over the wire.
 *
 * @property id      Stable rule id (`"rule-<uuid>"`), assigned by the SDK on insert.
 * @property method  HTTP method to match, case-insensitively, or `null` for any.
 * @property url     Required, non-empty URL substring to match (case-sensitive).
 * @property status  HTTP status code for a response mock. Non-null iff [error] is null.
 * @property reason  HTTP reason phrase; falls back to a default for [status] when null.
 * @property headers Response headers to emit on a response mock.
 * @property body    Response body to emit on a response mock. Empty string when null.
 * @property error   `IOException` message for an error mock. Non-null iff [status] is null.
 */
data class MockRule(
    val id: String,
    val method: String?,
    val url: String,
    val status: Int? = null,
    val reason: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val error: String? = null
)

/**
 * Serializes this rule to the on-wire `MockRule` map shape (see architecture.json
 * `MockRule_wire`). Used by the `listMocks` query ack.
 */
internal fun MockRule.toWireMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "method" to method,
    "url" to url,
    "status" to status,
    "reason" to reason,
    "headers" to headers,
    "body" to body,
    "error" to error
)

/**
 * Thread-safe, in-memory store of [MockRule]s.
 *
 * Copy-on-write over an [AtomicReference]; reads (matching against an incoming
 * request) are fully lock-free and never block the OkHttp dispatcher thread.
 * Writes happen only on the transport thread via [NetworkPlugin.onQuery] and
 * perform a bounded copy of the rule list — mock rules are configuration, not
 * events, so the list stays small and must never touch the 500-event send queue.
 *
 * Rules survive a WebSocket transport reconnect: the [NetworkPlugin] instance is
 * retained by the Probe singleton across attach/detach cycles, so the store is
 * not recreated. Rules are lost only when the app process is killed.
 */
class MockRuleStore {

    private val rules = AtomicReference<List<MockRule>>(emptyList())

    /**
     * Inserts [rule], replacing any existing rule with the same id; otherwise
     * appends. Copy-on-write — readers keep using the previous snapshot.
     */
    fun set(rule: MockRule) {
        rules.updateAndGet { current ->
            val without = current.filterNot { it.id == rule.id }
            without + rule
        }
    }

    /** Returns a defensive copy of the current rules in insertion order. */
    fun list(): List<MockRule> = rules.get().toList()

    /** Removes the rule with [id]. Returns `true` iff a rule was removed. */
    fun remove(id: String): Boolean {
        // `getAndUpdate` returns the snapshot that the committed update replaced,
        // so "was a rule removed" is derived from that snapshot rather than from a
        // side effect on an outer var (review finding A2). The transform lambda is
        // pure — it may be re-invoked on CAS retry without affecting correctness.
        val previous = rules.getAndUpdate { current ->
            if (current.none { it.id == id }) current else current.filterNot { it.id == id }
        }
        return previous.any { it.id == id }
    }

    /** Removes every rule. Returns the number of rules cleared. */
    fun clear(): Int {
        // Return value derived from the replaced snapshot (A2); lambda is pure.
        val previous = rules.getAndUpdate { emptyList() }
        return previous.size
    }

    /**
     * Lock-free snapshot of the current rules. Cheap — the underlying list is
     * immutable; callers may iterate it without synchronization.
     *
     * Internal: only [NetworkPlugin.snapshotMockRules] (also `internal`) consumes
     * it on the interceptor's hot path. The public, defensive-copy accessor is
     * [list] (review finding A3).
     */
    internal fun snapshot(): List<MockRule> = rules.get()

    /** Convenience: match [method]/[url] against the current snapshot. */
    fun match(method: String, url: String): MockRule? =
        matchRules(snapshot(), method, url)
}

/**
 * Pure matcher: selects the most specific [MockRule] from [rules] for the given
 * [method] and [url]. Top-level so it can be unit-tested in isolation from the
 * store.
 *
 * Selection:
 *  1. Keep rules where `method == null` (wildcard) or equals [method]
 *     case-insensitively, AND [url] contains `rule.url` (case-sensitive).
 *  2. Among matches, pick the longest `rule.url`; a tie resolves to the
 *     last-added rule (highest index).
 *
 * Returns `null` when nothing matches (including an empty [rules] list).
 */
internal fun matchRules(
    rules: List<MockRule>,
    method: String,
    url: String
): MockRule? =
    rules.asSequence()
        .withIndex()
        .filter { (_, rule) ->
            (rule.method == null || rule.method.equals(method, ignoreCase = true)) &&
                url.contains(rule.url)
        }
        .maxWithOrNull(compareBy({ it.value.url.length }, { it.index }))
        ?.value
