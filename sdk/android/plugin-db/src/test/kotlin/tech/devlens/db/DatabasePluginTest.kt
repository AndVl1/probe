package tech.devlens.db

import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.QueryRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM unit tests for [DatabasePlugin] paths that do not touch SQLite.
 *
 * SQLite-dependent behavior (listDatabases/listTables/inspectTable) is covered
 * by `DatabasePluginInstrumentedTest` (androidTest, runs on an emulator).
 *
 * `android.content.Context` is mocked; `android.database.sqlite` references in
 * the dispatch path are never reached by these tests.
 */
class DatabasePluginTest {

    private lateinit var plugin: DatabasePlugin
    private lateinit var host: FakeProbeHost

    @Before
    fun setup() {
        plugin = DatabasePlugin(mockk(relaxed = true))
        host = FakeProbeHost()
    }

    @After
    fun teardown() {
        plugin.onDetach() // shutdown the executor to avoid thread leaks
    }

    @Test
    fun `plugin id is database`() {
        assertEquals("database", plugin.id)
    }

    @Test
    fun `plugin reports android-only support`() {
        assertEquals(setOf(Platform.ANDROID), plugin.supportedPlatforms)
    }

    @Test
    fun `onQuery with null host is a safe no-op`() {
        // host is never attached → onQuery must return without crashing.
        plugin.onQuery(QueryRequest("q", "database", "listDatabases"))
        // No send should ever occur.
        assertTrue(
            "Nothing may be sent when the plugin is detached",
            host.awaitAny(500) == null
        )
    }

    @Test
    fun `onQuery unknown method yields unknown_method error`() {
        plugin.onAttach(host)

        plugin.onQuery(
            QueryRequest(
                requestId = "q-unknown",
                plugin = "database",
                method = "dropEverything",
                params = emptyMap()
            )
        )

        val payload = host.awaitAny(2000)
        assertNotNullPayload(payload)
        assertEquals("queryResult", payload!!["op"])
        assertEquals("q-unknown", payload["requestId"])
        assertEquals(false, payload["ok"])

        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any>
        assertEquals("unknown_method", error["code"])
    }

    @Test
    fun `error payload carries the original requestId`() {
        plugin.onAttach(host)

        plugin.onQuery(
            QueryRequest(
                requestId = "req-abc",
                plugin = "database",
                method = "nope"
            )
        )

        val payload = host.awaitAny(2000)
        assertNotNullPayload(payload)
        assertEquals("req-abc", payload!!["requestId"])
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Captures everything sent by the plugin; latches make async awaits deterministic. */
    private class FakeProbeHost : ProbeHost {
        override val isConnected: Boolean = true

        private val lock = Any()
        private val sent = mutableListOf<Map<String, Any?>>()
        private val latch = CountDownLatch(1)

        override fun send(pluginId: String, payload: Map<String, Any?>) {
            synchronized(lock) { sent.add(payload) }
            latch.countDown()
        }

        fun awaitAny(timeoutMs: Long): Map<String, Any?>? {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            return synchronized(lock) { sent.firstOrNull() }
        }
    }

    private fun assertNotNullPayload(payload: Map<String, Any?>?) {
        assertTrue(
            "Plugin must respond to onQuery within timeout",
            payload != null
        )
    }
}
