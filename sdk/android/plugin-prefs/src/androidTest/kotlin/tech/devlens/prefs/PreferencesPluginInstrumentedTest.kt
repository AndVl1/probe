package tech.devlens.prefs

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [PreferencesPlugin]'s SharedPreferences behavior.
 *
 * Runs on an emulator/device. Each test seeds a uniquely-named fixture prefs
 * file via a real [android.content.SharedPreferences], drives
 * [PreferencesPlugin] directly, and asserts the emitted payloads.
 *
 * Threading note: `commit()` (not `apply()`) is used throughout so the change
 * is written synchronously and the change-listener notification fires before
 * the test proceeds to await it. All plugin + prefs calls run on the test
 * (main) thread, which keeps listener delivery synchronous on the same thread.
 */
@RunWith(AndroidJUnit4::class)
class PreferencesPluginInstrumentedTest {

    private lateinit var context: Context
    private lateinit var plugin: PreferencesPlugin
    private lateinit var host: FakeProbeHost

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        plugin = PreferencesPlugin(context)
        host = FakeProbeHost()
    }

    @After
    fun teardown() {
        plugin.onDetach()
        // Best-effort cleanup of fixture files so they don't leak into later
        // snapshots within the same test process.
        FIXTURE_NAMES.forEach { runCatching { context.deleteSharedPreferences(it) } }
    }

    // ── snapshot on attach ──────────────────────────────────────────────────────

    @Test
    fun snapshotOnAttachIncludesSeededFixtureFile() {
        seedPrefs(SNAP_FIXTURE, "seed_key" to "seed_value")

        plugin.onAttach(host)

        val payload = host.awaitAny(2000)
        assertNotNull("attach must emit a snapshot", payload)
        assertEquals("snapshot", payload!!["op"])
        @Suppress("UNCHECKED_CAST")
        val files = payload["files"] as List<Map<String, Any?>>
        val fixture = files.firstOrNull { it["name"] == SNAP_FIXTURE }
        assertNotNull("fixture '$SNAP_FIXTURE' must appear in snapshot", fixture)
        assertEquals(1, fixture!!["keyCount"])
        assertEquals(false, fixture["truncated"])
        @Suppress("UNCHECKED_CAST")
        val keys = fixture["keys"] as List<String>
        assertTrue("seed_key must be listed", keys.contains("seed_key"))
    }

    // ── change events: six value types ─────────────────────────────────────────

    @Test
    fun changeEventsCoverSixValueTypes() {
        seedPrefs(TYPES_FIXTURE)
        plugin.onAttach(host)
        host.awaitAny(2000) // consume snapshot

        context.getSharedPreferences(TYPES_FIXTURE, Context.MODE_PRIVATE).edit()
            .putString("s", "hello")
            .putInt("i", 7)
            .putLong("l", 9_000_000_000L)
            .putFloat("f", 1.25f)
            .putBoolean("b", true)
            .putStringSet("set", setOf("x", "y"))
            .commit()

        val byKey = awaitN(6).associateBy { it["key"] as String }

        assertPut(byKey.getValue("s"), "string", "hello")
        assertPut(byKey.getValue("i"), "int", 7)
        assertPut(byKey.getValue("l"), "long", 9_000_000_000L)
        assertPut(byKey.getValue("f"), "float", 1.25f)
        assertPut(byKey.getValue("b"), "boolean", true)
        assertPut(byKey.getValue("set"), "string_set", setOf("x", "y"))
    }

    // ── remove / clear ──────────────────────────────────────────────────────────

    @Test
    fun removingAKeyEmitsRemoveOp() {
        seedPrefs(REMOVE_FIXTURE, "doomed" to "x")
        plugin.onAttach(host)
        host.awaitAny(2000) // snapshot

        context.getSharedPreferences(REMOVE_FIXTURE, Context.MODE_PRIVATE)
            .edit().remove("doomed").commit()

        val payload = host.awaitAny(2000)
        assertNotNull(payload)
        assertEquals("remove", payload!!["op"])
        assertEquals(REMOVE_FIXTURE, payload["name"])
        assertEquals("doomed", payload["key"])
    }

    @Test
    fun clearingAFileEmitsClearOp() {
        seedPrefs(CLEAR_FIXTURE, "a" to "1", "b" to "2")
        plugin.onAttach(host)
        host.awaitAny(2000) // snapshot

        context.getSharedPreferences(CLEAR_FIXTURE, Context.MODE_PRIVATE)
            .edit().clear().commit()

        // Clear signals via a key=null callback. Collect the first notification
        // and assert it is a clear OR a per-key remove (platform-dependent) —
        // never a put. The plugin handles both correctly either way.
        val first = host.awaitAny(2000)
        assertNotNull("clear must notify", first)
        val op = first!!["op"]
        assertTrue("clear must emit clear or remove, got $op", op == "clear" || op == "remove")
        assertEquals(CLEAR_FIXTURE, first["name"])
    }

    // ── truncation ──────────────────────────────────────────────────────────────

    @Test
    fun largeStringValueIsTruncated() {
        seedPrefs(TRUNC_FIXTURE)
        plugin.onAttach(host)
        host.awaitAny(2000) // snapshot

        val big = "x".repeat(LARGE_STRING_LEN) // > VALUE_TRUNCATION_BYTES (4096)
        context.getSharedPreferences(TRUNC_FIXTURE, Context.MODE_PRIVATE)
            .edit().putString("big", big).commit()

        val payload = host.awaitAny(2000)
        assertNotNull(payload)
        assertEquals("put", payload!!["op"])
        assertEquals("string", payload["valueType"])
        @Suppress("UNCHECKED_CAST")
        val value = payload["value"] as Map<String, Any?>
        assertEquals(true, value["valueTruncated"])
        assertEquals(LARGE_STRING_LEN, (value["sizeBytes"] as Number).toInt())
        assertEquals(
            "preview must be the first 256 chars",
            PREVIEW_LEN,
            (value["preview"] as String).length
        )
    }

    // ── snapshot key cap ─────────────────────────────────────────────────────────

    /**
     * A prefs file holding MORE than the per-file key cap (200) must be reported
     * with `truncated=true`, an accurate `keyCount` (the real total), and a
     * `keys` list capped at 200 entries — so a runaway prefs file can't flood
     * the wire or the bounded send queue.
     */
    @Test
    fun snapshotCapsKeyListBeyondTwoHundred() {
        seedPrefs(
            CAP_FIXTURE,
            *(0 until CAP_KEY_COUNT).map { "k_$it" to it }.toTypedArray()
        )

        plugin.onAttach(host)

        val payload = host.awaitAny(2000)
        assertNotNull("attach must emit a snapshot", payload)
        assertEquals("snapshot", payload!!["op"])
        @Suppress("UNCHECKED_CAST")
        val files = payload["files"] as List<Map<String, Any?>>
        val fixture = files.firstOrNull { it["name"] == CAP_FIXTURE }
        assertNotNull("fixture '$CAP_FIXTURE' must appear in snapshot", fixture)
        assertEquals(
            "truncated must be true past the per-file key cap",
            true,
            fixture!!["truncated"]
        )
        assertEquals(
            "keyCount must report the real total, not the capped list length",
            CAP_KEY_COUNT,
            (fixture["keyCount"] as Number).toInt()
        )
        @Suppress("UNCHECKED_CAST")
        val keys = fixture["keys"] as List<String>
        assertEquals(
            "keys list must be capped at $EXPECTED_KEY_CAP entries",
            EXPECTED_KEY_CAP,
            keys.size
        )
    }

    // ── escape hatch ────────────────────────────────────────────────────────────

    @Test
    fun registerPrefsEmitsSnapshotForFileCreatedAfterAttach() {
        plugin.onAttach(host)
        host.awaitAny(2000) // initial snapshot (does not include LATE_FIXTURE yet)

        seedPrefs(LATE_FIXTURE, "fresh" to "value")
        plugin.registerPrefs(LATE_FIXTURE)

        val payload = host.awaitAny(2000)
        assertNotNull("registerPrefs must emit a snapshot", payload)
        assertEquals("snapshot", payload!!["op"])
        @Suppress("UNCHECKED_CAST")
        val files = payload["files"] as List<Map<String, Any?>>
        assertEquals("snapshot must carry exactly the one registered file", 1, files.size)
        assertEquals(LATE_FIXTURE, files[0]["name"])
    }

    @Test
    fun registerPrefsIsIdempotent() {
        plugin.onAttach(host)
        host.awaitAny(2000) // snapshot
        seedPrefs(IDEM_FIXTURE, "k" to "v")

        plugin.registerPrefs(IDEM_FIXTURE)
        host.awaitAny(2000) // first register emits a snapshot

        plugin.registerPrefs(IDEM_FIXTURE) // second call: must be a no-op

        assertNull(
            "registerPrefs called twice with the same name must not emit again",
            host.awaitAny(1000)
        )
    }

    // ── leak across attach/detach cycles ───────────────────────────────────────

    @Test
    fun detachReattachDoesNotDuplicateChangeEvents() {
        seedPrefs(LEAK_FIXTURE, "k" to "v1")
        plugin.onAttach(host)
        host.awaitAny(2000) // snapshot

        context.getSharedPreferences(LEAK_FIXTURE, Context.MODE_PRIVATE)
            .edit().putString("k", "v2").commit()
        host.awaitAny(2000) // exactly one put

        plugin.onDetach()
        plugin.onAttach(host)
        host.awaitAny(2000) // snapshot on reattach

        context.getSharedPreferences(LEAK_FIXTURE, Context.MODE_PRIVATE)
            .edit().putString("k", "v3").commit()
        val first = host.awaitAny(2000)
        assertNotNull("exactly one change event expected after reattach", first)
        assertEquals("put", first!!["op"])

        assertNull(
            "onDetach must unregister the listener; no duplicate event",
            host.awaitAny(1000)
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Awaits [n] payloads in arrival order, failing with a clear message on timeout. */
    private fun awaitN(n: Int, timeoutMs: Long = 5000L): List<Map<String, Any?>> =
        (0 until n).map { i ->
            host.awaitAny(timeoutMs)
                ?: error("Timed out waiting for payload #${i + 1} of $n")
        }

    /** Asserts a put payload carries the expected [type] and raw [value]. */
    private fun assertPut(payload: Map<String, Any?>, type: String, value: Any?) {
        assertEquals("put", payload["op"])
        assertEquals(type, payload["valueType"])
        assertEquals(value, payload["value"])
    }

    /**
     * Creates the fixture file on disk with the given pairs (via `commit()` so
     * the file exists immediately). At least one pair is required so the file
     * is actually materialized under `shared_prefs/`.
     */
    private fun seedPrefs(name: String, vararg pairs: Pair<String, Any?>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        if (pairs.isEmpty()) {
            editor.putBoolean(SEED_MARKER, true)
        } else {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") (v as Set<String>).let {
                        editor.putStringSet(k, it)
                    }
                    else -> error("Unsupported seed value type for key '$k': ${v?.javaClass}")
                }
            }
        }
        editor.commit()
    }

    private companion object {
        const val SNAP_FIXTURE = "probe_test_snap"
        const val TYPES_FIXTURE = "probe_test_types"
        const val REMOVE_FIXTURE = "probe_test_remove"
        const val CLEAR_FIXTURE = "probe_test_clear"
        const val TRUNC_FIXTURE = "probe_test_trunc"
        const val LATE_FIXTURE = "probe_test_late"
        const val IDEM_FIXTURE = "probe_test_idem"
        const val LEAK_FIXTURE = "probe_test_leak"
        const val CAP_FIXTURE = "probe_test_cap"

        const val SEED_MARKER = "__seed_marker__"
        const val LARGE_STRING_LEN = 5000
        const val PREVIEW_LEN = 256

        // Snapshot key-cap test: seed MORE keys than the plugin's per-file cap.
        // The cap constant (SNAPSHOT_KEY_CAP_PER_FILE == 200) is private to the
        // plugin, so the expected cap is asserted by literal here.
        const val CAP_KEY_COUNT = 250
        const val EXPECTED_KEY_CAP = 200

        val FIXTURE_NAMES = listOf(
            SNAP_FIXTURE, TYPES_FIXTURE, REMOVE_FIXTURE, CLEAR_FIXTURE,
            TRUNC_FIXTURE, LATE_FIXTURE, IDEM_FIXTURE, LEAK_FIXTURE, CAP_FIXTURE,
        )
    }
}
