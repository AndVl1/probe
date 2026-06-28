package tech.devlens.prefs

import android.content.Context
import android.content.SharedPreferences
import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.ProbePlugin
import tech.devlens.QueryRequest
import java.io.File

/**
 * Probe plugin — reads and watches Android `SharedPreferences`.
 *
 * On attach it enumerates every prefs file under the app's `shared_prefs`
 * directory, registers a change listener on each, and emits a single
 * `op:"snapshot"` envelope describing every file (name, key count, keys). From
 * then on each mutation streams as an `op:"put"|"remove"|"clear"` envelope so
 * the CLI shows the prefs tree live.
 *
 * ## Setup
 * ```kotlin
 * val prefs = PreferencesPlugin(this)
 *
 * Probe.install(
 *     Probe.Builder(this)
 *         .plugin(prefs)
 *         .build()
 * )
 * ```
 *
 * ## Late / lazily-created prefs files
 * Files created after [onAttach] are NOT picked up automatically — the shared
 * prefs API has no "file appeared" callback. Register them explicitly when they
 * are first opened:
 * ```kotlin
 * prefs.registerPrefs("cache_v2")
 * ```
 *
 * ## Invariants (load-bearing — see project `CLAUDE.md`)
 * - [send] is the only thing the change listener does. It is non-blocking
 *   (bounded queue 500, drop-oldest); the callback performs no IO and takes no
 *   locks. `SharedPreferences` holds its listeners via `WeakReference`, so each
 *   [Listener] is retained as a strong reference in [tracked] — without that it
 *   would be collected on the next GC and change events would silently stop.
 * - The plugin id `"preferences"` is protocol-stable; never rename it.
 *
 * @param context Application context used to locate the `shared_prefs` dir.
 */
class PreferencesPlugin(context: Context) : ProbePlugin {

    override val id: String = "preferences"
    override val displayName: String = "Preferences"
    override val supportedPlatforms: Set<Platform> = setOf(Platform.ANDROID)

    private val appContext: Context = context.applicationContext

    @Volatile
    private var host: ProbeHost? = null

    // SharedPreferences holds OnSharedPreferenceChangeListener via WeakReference,
    // so the Listener MUST be retained here as a strong reference or it gets GC'd
    // and change events stop. LinkedHashMap for stable iteration order on detach.
    private val tracked = LinkedHashMap<String, Pair<SharedPreferences, Listener>>()
    private val trackedLock = Any()

    override fun onAttach(host: ProbeHost) {
        this.host = host
        snapshotAndRegisterAll()
    }

    override fun onDetach() {
        synchronized(trackedLock) {
            for ((prefs, listener) in tracked.values) {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
            tracked.clear()
        }
        host = null
    }

    // Push-only plugin. Declared (not inherited) so the BCV public-API signature
    // is deterministic — see tech.devlens.ProbePlugin.onQuery.
    override fun onQuery(request: QueryRequest) {
        // Preferences is push-only; inbound queries are intentionally ignored.
    }

    /**
     * Registers a prefs file that was created AFTER [onAttach] (e.g. a lazily
     * opened cache). Idempotent: calling twice with the same [name] is a no-op.
     * Emits a single-file `op:"snapshot"` envelope so the CLI learns about the
     * new file immediately. Safe to call from the main thread.
     */
    fun registerPrefs(name: String) {
        val snapshot = synchronized(trackedLock) {
            // Idempotent: a reconnect re-registers via snapshotAndRegisterAll.
            if (tracked.containsKey(name)) return
            val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = Listener(name)
            prefs.registerOnSharedPreferenceChangeListener(listener)
            tracked[name] = prefs to listener
            prefs.toSnapshotMap(name)
        }
        // send() is non-blocking (queue 500 drop-oldest); safe outside the lock.
        host?.send(id, mapOf("op" to "snapshot", "files" to listOf(snapshot)))
    }

    // ── snapshot + listener wiring ─────────────────────────────────────────────

    /**
     * Enumerates every `*.xml` under `shared_prefs`, registers a listener on
     * each not already tracked (idempotent on reconnect), then emits ONE
     * `op:"snapshot"` envelope carrying all files. No-op if the dir is missing
     * (fresh install with no prefs yet).
     */
    private fun snapshotAndRegisterAll() {
        val parent = appContext.filesDir.parentFile ?: return
        val dir = File(parent, SHARED_PREFS_DIR)
        if (!dir.isDirectory) return

        val names = dir.listFiles { file ->
            file.isFile && file.name.endsWith(SHARED_PREFS_SUFFIX)
        }
            ?.map { it.name.removeSuffix(SHARED_PREFS_SUFFIX) }
            ?.sorted()
            ?: emptyList()

        if (names.isEmpty()) return

        val snapshots = synchronized(trackedLock) {
            names.map { name ->
                if (!tracked.containsKey(name)) {
                    val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                    val listener = Listener(name)
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    tracked[name] = prefs to listener
                }
                tracked.getValue(name).first.toSnapshotMap(name)
            }
        }

        host?.send(id, mapOf("op" to "snapshot", "files" to snapshots))
    }

    /**
     * Strongly-referenced change listener. [SharedPreferences] stores listeners
     * via `WeakReference`, so each instance MUST be retained in [tracked] —
     * hence this class exists at all rather than a lambda.
     *
     * The callback does ONLY a non-blocking [ProbeHost.send]. No IO, no locks:
     * the transport thread that delivered the change must never be held.
     */
    private inner class Listener(val name: String) :
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            host?.send(id, prefs.toChangePayload(name, key))
        }
    }

    // ── payload encoding ───────────────────────────────────────────────────────

    /**
     * Per-file snapshot: `{name, keyCount, truncated, keys}`. [keys] is capped
     * at [SNAPSHOT_KEY_CAP_PER_FILE] entries (beyond that [truncated] flips true)
     * so a runaway prefs file can't blow up the wire. Values are NOT included —
     * they stream as `op:"put"` events; the snapshot just tells the CLI which
     * keys exist.
     */
    private fun SharedPreferences.toSnapshotMap(name: String): Map<String, Any?> {
        val all = all
        val keyCount = all.size
        val truncated = keyCount > SNAPSHOT_KEY_CAP_PER_FILE
        val keys = all.keys.let { allKeys ->
            if (truncated) allKeys.take(SNAPSHOT_KEY_CAP_PER_FILE) else allKeys.toList()
        }
        return mapOf(
            "name" to name,
            "keyCount" to keyCount,
            "truncated" to truncated,
            "keys" to keys
        )
    }

    /**
     * Single-key change event:
     * - `key == null`        → `op:"clear"`  (the whole file was cleared)
     * - key not present      → `op:"remove"` (the key was removed)
     * - otherwise            → `op:"put"`    with [typeOf] + [encodeValue]
     *
     * `all` is read once so the contains/value pair is consistent (a second
     * call to `all` could observe a later mutation).
     *
     * Trade-off (accepted): `SharedPreferences` exposes no `getValue(key)` —
     * the only way to look up a single value is to read the entire map via
     * [all]. So every change notification pays a full-map read. This is fine
     * for a debug-only tool (prefs files are small, the listener fires on the
     * thread that mutated, and `all` is an in-memory snapshot, not disk IO),
     * but it is the reason we do NOT call `all` twice here.
     */
    private fun SharedPreferences.toChangePayload(name: String, key: String?): Map<String, Any?> {
        if (key == null) return mapOf("op" to "clear", "name" to name)

        val snapshot = all
        return if (!snapshot.containsKey(key)) {
            mapOf("op" to "remove", "name" to name, "key" to key)
        } else {
            val value = snapshot[key]
            mapOf(
                "op" to "put",
                "name" to name,
                "key" to key,
                "valueType" to typeOf(value),
                "value" to encodeValue(value)
            )
        }
    }

    /**
     * SharedPreferences stores exactly six value types. The `else` is
     * unreachable in practice (the framework never returns anything else) but
     * falls back to `"string"` defensively rather than crashing a debug tool.
     */
    private fun typeOf(v: Any?): String = when (v) {
        is String -> "string"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is Boolean -> "boolean"
        is Set<*> -> "string_set"
        else -> "string"
    }

    /**
     * Strings above [VALUE_TRUNCATION_BYTES] UTF-8 bytes are reported as
     * `{valueTruncated:true, sizeBytes, preview}` (preview = first
     * [VALUE_PREVIEW_CHARS] chars) so a multi-MB pref value can't flood the CLI
     * or the bounded send queue. Numbers, booleans, and sets pass through.
     */
    private fun encodeValue(v: Any?): Any? = when (v) {
        is String -> {
            val sizeBytes = v.toByteArray(Charsets.UTF_8).size
            if (sizeBytes > VALUE_TRUNCATION_BYTES) {
                mapOf(
                    "valueTruncated" to true,
                    "sizeBytes" to sizeBytes,
                    "preview" to v.take(VALUE_PREVIEW_CHARS)
                )
            } else {
                v
            }
        }
        else -> v
    }

    companion object {
        /** Values above this UTF-8 size are reported as truncated previews. */
        private const val VALUE_TRUNCATION_BYTES: Int = 4 * 1024

        /** Max keys emitted per file in a snapshot; beyond this `truncated=true`. */
        private const val SNAPSHOT_KEY_CAP_PER_FILE: Int = 200

        private const val SHARED_PREFS_DIR = "shared_prefs"
        private const val SHARED_PREFS_SUFFIX = ".xml"
        private const val VALUE_PREVIEW_CHARS = 256
    }
}
