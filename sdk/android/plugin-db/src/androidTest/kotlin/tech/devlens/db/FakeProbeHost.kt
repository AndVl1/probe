package tech.devlens.db

import tech.devlens.ProbeHost
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Instrumented-test [ProbeHost] that captures everything [DatabasePlugin] sends.
 *
 * [DatabasePlugin.onQuery] dispatches its work to a background thread, so each
 * [awaitAny] call blocks (with a timeout) until the next response arrives. Each
 * call consumes and returns the next payload **in arrival order**, so a test
 * that issues several queries can await each response independently — the early
 * implementations used a one-shot `CountDownLatch`, which made the second await
 * return the first (stale) response.
 *
 * Also records the name of the thread on which [send] was invoked, so tests can
 * prove the off-main-thread invariant (dispatch runs on the `Probe-Db` executor,
 * never on the main or WebSocket transport thread).
 */
class FakeProbeHost : ProbeHost {

    override val isConnected: Boolean = true

    private val lock = ReentrantLock()
    private val newResponse = lock.newCondition()
    private val sent = mutableListOf<Map<String, Any?>>()
    private var consumed = 0

    @Volatile
    private var senderThreadName: String? = null

    override fun send(pluginId: String, payload: Map<String, Any?>) {
        senderThreadName = Thread.currentThread().name
        lock.withLock {
            sent.add(payload)
            newResponse.signalAll()
        }
    }

    /**
     * Blocks until the next (in-order) response arrives, or returns null after
     * [timeoutMs]. Successive calls return successive payloads — call N returns
     * the Nth payload sent.
     */
    fun awaitAny(timeoutMs: Long): Map<String, Any?>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        lock.withLock {
            while (consumed >= sent.size) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                newResponse.await(remaining, TimeUnit.MILLISECONDS)
            }
            return sent[consumed++]
        }
    }

    /** The name of the thread that invoked [send] for the most recent response. */
    fun lastSenderThreadName(): String? = senderThreadName
}
