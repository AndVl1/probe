package tech.devlens.prefs

import tech.devlens.ProbeHost
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Instrumented-test [ProbeHost] that captures everything [PreferencesPlugin] sends.
 *
 * The plugin emits its snapshot synchronously on the calling thread from
 * [PreferencesPlugin.onAttach] / [registerPrefs], while change callbacks are
 * delivered on whatever thread mutated the prefs. Each [awaitAny] call blocks
 * (with a timeout) until the next response arrives and returns payloads **in
 * arrival order**, so a test that triggers several emissions can await each
 * independently.
 */
class FakeProbeHost : ProbeHost {

    override val isConnected: Boolean = true

    private val lock = ReentrantLock()
    private val newResponse = lock.newCondition()
    private val sent = mutableListOf<Map<String, Any?>>()
    private var consumed = 0

    override fun send(pluginId: String, payload: Map<String, Any?>) {
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
}
