package tech.devlens.prefs

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.QueryRequest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM unit tests for [PreferencesPlugin] paths that do not touch real
 * SharedPreferences files.
 *
 * SharedPreferences-dependent behavior (snapshot-on-attach, the six value
 * types, remove/clear, truncation, escape-hatch) is covered by
 * `PreferencesPluginInstrumentedTest` (androidTest, runs on an emulator).
 *
 * `android.content.Context` is mocked. `getFilesDir()` is stubbed to a unique
 * non-existent path so `snapshotAndRegisterAll` short-circuits on its
 * `!isDirectory` guard without any real prefs-file IO — the snapshot path is
 * still exercised end-to-end on attach, just with an empty shared_prefs dir.
 */
class PreferencesPluginTest {

    private lateinit var plugin: PreferencesPlugin
    private lateinit var host: FakeProbeHost

    @Before
    fun setup() {
        // A unique, non-existent files dir → parentFile/shared_prefs won't be a
        // directory → snapshotAndRegisterAll returns early. Relaxed mockk alone
        // returns null for getFilesDir() (File is a final class), which NPEs the
        // parentFile chain; stub it to a real path instead.
        val context = mockk<Context>(relaxed = true)
        val appContext = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns appContext
        every { appContext.filesDir } returns File("/tmp/devlens-prefs-test-nonexistent-${System.nanoTime()}/files")
        plugin = PreferencesPlugin(context)
        host = FakeProbeHost()
    }

    @After
    fun teardown() {
        plugin.onDetach()
    }

    @Test
    fun `plugin id is preferences`() {
        assertEquals("preferences", plugin.id)
    }

    @Test
    fun `plugin reports android-only support`() {
        assertEquals(setOf(Platform.ANDROID), plugin.supportedPlatforms)
    }

    @Test
    fun `onQuery with null host is a safe no-op`() {
        // host is never attached → onQuery must return without crashing.
        plugin.onQuery(QueryRequest("q", "preferences", "anything"))
        // Nothing may be sent when the plugin is detached.
        assertTrue(
            "Nothing may be sent when the plugin is detached",
            host.awaitAny(500) == null
        )
    }

    @Test
    fun `attach and detach cycle does not throw`() {
        // With a relaxed-mock Context, filesDir.parentFile/shared_prefs is not a
        // real directory → snapshotAndRegisterAll returns early. attach/detach
        // must round-trip cleanly with no listeners registered.
        plugin.onAttach(host)
        plugin.onDetach()
        // No snapshot emitted (dir missing on the mock).
        assertTrue(
            "No payload may be sent when the shared_prefs dir is absent",
            host.awaitAny(500) == null
        )
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Captures everything sent by the plugin; a latch makes awaits deterministic. */
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
}
