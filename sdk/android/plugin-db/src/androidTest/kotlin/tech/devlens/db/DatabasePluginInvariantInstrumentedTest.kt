package tech.devlens.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.devlens.QueryRequest
import java.io.File
import java.security.MessageDigest

/**
 * Instrumented tests covering the DevLens DoD invariants and edge cases for
 * [DatabasePlugin] that are NOT already covered by [DatabasePluginInstrumentedTest]:
 *
 * - **H2 (DoD7)** off-main-thread: dispatch runs on the `Probe-Db` executor.
 * - **H3 (DoD6)** read-only: listTables + inspectTable leave the fixture .db
 *   byte-identical and create no -wal/-journal sidecars.
 * - **T1** BLOB > 2 KB is reported as `{blobTruncated:true, sizeBytes:N}`.
 * - **T2 (DoD5)** pagination: offset pages are disjoint and the first page is
 *   marked truncated.
 * - **T3** internal tables (`sqlite_*`, `android_metadata`) never appear in
 *   listTables output.
 * - **T4** an unopenable/encrypted file is reported via `{ok:false, error.code}`
 *   on the `inspectTable` openForQuery path.
 * - **T6** a REAL column reports affinity `REAL` and its value deserializes as
 *   a [Double].
 *
 * Each test builds its own fixture database via [createFixture] so the shared
 * `probe_fixture.db` from [DatabasePluginInstrumentedTest] is untouched.
 */
@RunWith(AndroidJUnit4::class)
class DatabasePluginInvariantInstrumentedTest {

    private lateinit var context: Context
    private lateinit var plugin: DatabasePlugin
    private lateinit var host: FakeProbeHost
    private val fixtureNames = mutableListOf<String>()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        plugin = DatabasePlugin(context)
        host = FakeProbeHost()
        plugin.onAttach(host)
    }

    @After
    fun teardown() {
        plugin.onDetach()
        // Best-effort cleanup (also removes -wal/-shm/-journal sidecars).
        fixtureNames.forEach { context.deleteDatabase(it) }
        context.getDatabasePath(GARBAGE_DB).let { if (it.exists()) it.delete() }
    }

    // ── H2: off-main-thread invariant (DoD7) ───────────────────────────────────

    @Test
    fun dispatchRunsOnProbeDbExecutorThread() {
        createFixture(BASIC_DB) { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
            db.insert("t", null, ContentValues().apply { put("v", 1) })
        }

        query(
            QueryRequest(
                requestId = "h2-thread",
                plugin = "database",
                method = "inspectTable",
                params = mapOf("database" to BASIC_DB, "table" to "t")
            )
        )

        // The plugin calls host.send() from inside executor.execute { ... }, so
        // send() must execute on the dedicated "Probe-Db" thread — never on the
        // main or WebSocket transport thread.
        assertEquals(
            "SQLite dispatch must run on the 'Probe-Db' executor thread",
            "Probe-Db",
            host.lastSenderThreadName()
        )
    }

    // ── H3: read-only invariant (DoD6) ─────────────────────────────────────────

    @Test
    fun readOnlyInspectionLeavesFixtureByteIdenticalAndCreatesNoSidecars() {
        createFixture(BASIC_DB) { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
            db.insert("t", null, ContentValues().apply { put("label", "a") })
            db.insert("t", null, ContentValues().apply { put("label", "b") })
        }

        val dbFile = context.getDatabasePath(BASIC_DB)
        assertTrue("fixture .db must exist before inspection", dbFile.exists())

        // Baseline: SHA-256 of the .db + the set of sidecar files (-wal/-shm/-journal).
        val beforeHash = sha256(dbFile)
        val beforeFiles = sidecarFiles(BASIC_DB)

        // Drive both read paths.
        query(
            QueryRequest(
                requestId = "h3-list",
                plugin = "database",
                method = "listTables",
                params = mapOf("database" to BASIC_DB)
            )
        )
        query(
            QueryRequest(
                requestId = "h3-inspect",
                plugin = "database",
                method = "inspectTable",
                params = mapOf("database" to BASIC_DB, "table" to "t")
            )
        )

        // The .db content must be byte-identical (no writes).
        assertEquals(
            "Fixture .db must be unchanged after read-only inspection",
            beforeHash,
            sha256(dbFile)
        )
        // And no new sidecar files may have appeared (proves OPEN_READONLY +
        // the no-op DatabaseErrorHandler did not trigger WAL/journal creation).
        assertEquals(
            "No -wal/-journal sidecars may be created by read-only inspection",
            beforeFiles,
            sidecarFiles(BASIC_DB)
        )
    }

    // ── T1: BLOB > 2 KB truncation ─────────────────────────────────────────────

    @Test
    fun blobLargerThan2KbIsReportedAsTruncatedEnvelope() {
        val blobBytes = ByteArray(LARGE_BLOB_SIZE) { it.toByte() }
        createFixture(BASIC_DB) { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, thumb BLOB)")
            val values = ContentValues().apply { put("thumb", blobBytes) }
            db.insert("t", null, values)
        }

        val payload = query(
            QueryRequest(
                requestId = "t1-blob",
                plugin = "database",
                method = "inspectTable",
                params = mapOf("database" to BASIC_DB, "table" to "t")
            )
        )

        assertEquals(true, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val rows = result["rows"] as List<Map<String, Any?>>
        assertEquals(1, rows.size)

        @Suppress("UNCHECKED_CAST")
        val thumb = rows[0]["thumb"] as Map<String, Any>
        // NOT inline hex — must be the truncation envelope.
        assertEquals(true, thumb["blobTruncated"])
        assertEquals(LARGE_BLOB_SIZE, (thumb["sizeBytes"] as Number).toInt())
    }

    // ── T2: disjoint pagination (DoD5) ─────────────────────────────────────────

    @Test
    fun paginationReturnsDisjointPagesAndMarksFirstPageTruncated() {
        createFixture(PAGED_DB) { db ->
            db.execSQL("CREATE TABLE rows (id INTEGER PRIMARY KEY, label TEXT)")
            for (i in 0 until PAGED_ROW_COUNT) {
                db.insert(
                    "rows",
                    null,
                    ContentValues().apply { put("label", "r-%04d".format(i)) }
                )
            }
        }

        val page1 = inspectRows(offset = 0, limit = PAGE_SIZE)
        val page2 = inspectRows(offset = PAGE_SIZE, limit = PAGE_SIZE)

        assertEquals("page 1 must fill the limit", PAGE_SIZE, page1.rows.size)
        assertEquals("page 2 must fill the limit", PAGE_SIZE, page2.rows.size)
        assertTrue("page 1 must report truncated", page1.truncated)

        // Disjoint: the two pages must not share any row labels.
        val labels1 = page1.labels.toSet()
        val labels2 = page2.labels.toSet()
        val intersection = labels1.intersect(labels2)
        assertTrue(
            "pages must be disjoint (intersection size=${intersection.size}; " +
                "page1 ids ${page1.ids.first()}..${page1.ids.last()}, " +
                "page2 ids ${page2.ids.first()}..${page2.ids.last()})",
            intersection.isEmpty()
        )
        assertEquals(PAGE_SIZE, labels1.size)
        assertEquals(PAGE_SIZE, labels2.size)
        assertNotEquals("pages must differ", labels1, labels2)
    }

    private data class Page(
        val rows: List<Map<String, Any?>>,
        val labels: List<String>,
        val ids: List<Long>,
        val truncated: Boolean
    )

    private fun inspectRows(offset: Int, limit: Int): Page {
        val payload = query(
            QueryRequest(
                requestId = "t2-page-$offset",
                plugin = "database",
                method = "inspectTable",
                params = mapOf(
                    "database" to PAGED_DB,
                    "table" to "rows",
                    "offset" to offset,
                    "limit" to limit
                )
            )
        )
        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val rows = result["rows"] as List<Map<String, Any?>>
        val labels = rows.map { it["label"] as String }
        val ids = rows.map { (it["id"] as Number).toLong() }
        val truncated = result["truncated"] as Boolean
        return Page(rows, labels, ids, truncated)
    }

    // ── T3: internal tables never appear in listTables ─────────────────────────

    @Test
    fun listTablesExcludesInternalAndMetadataTables() {
        createFixture(BASIC_DB) { db ->
            db.execSQL("CREATE TABLE user_data (id INTEGER PRIMARY KEY, v TEXT)")
        }

        val payload = query(
            QueryRequest(
                requestId = "t3-internal",
                plugin = "database",
                method = "listTables",
                params = mapOf("database" to BASIC_DB)
            )
        )

        assertEquals(true, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val tables = result["tables"] as List<Map<String, Any>>
        val names = tables.map { it["name"] as String }

        assertTrue(
            "listTables must return the user table",
            names.contains("user_data")
        )
        // No internal SQLite tables, no android_metadata.
        assertFalse(
            "no table name may start with 'sqlite_'",
            names.any { it.startsWith("sqlite_") }
        )
        assertFalse(
            "android_metadata must not appear",
            names.any { it == "android_metadata" }
        )
    }

    // ── T4: encrypted/unopenable via inspectTable (openForQuery path) ──────────

    @Test
    fun inspectTableOnGarbageFileReportsEncryptedOrUnopenable() {
        writeGarbageDatabase()

        val payload = query(
            QueryRequest(
                requestId = "t4-garbage",
                plugin = "database",
                method = "inspectTable",
                params = mapOf("database" to GARBAGE_DB, "table" to "x")
            )
        )

        assertEquals(false, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any>
        val code = error["code"] as String
        assertTrue(
            "error.code must be 'encrypted' or 'unopenable' (was '$code')",
            code in setOf("encrypted", "unopenable")
        )
    }

    // ── T6: REAL affinity + Double deserialization ─────────────────────────────

    @Test
    fun realColumnReportsRealAffinityAndDeserializesAsDouble() {
        createFixture(BASIC_DB) { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, score REAL)")
            db.insert(
                "t",
                null,
                ContentValues().apply { put("score", REAL_VALUE) }
            )
        }

        val payload = query(
            QueryRequest(
                requestId = "t6-real",
                plugin = "database",
                method = "inspectTable",
                params = mapOf("database" to BASIC_DB, "table" to "t")
            )
        )

        assertEquals(true, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val schema = result["schema"] as List<Map<String, Any>>
        val score = schema.firstOrNull { it["name"] == "score" }
        assertNotNull("score column must be present", score)
        assertEquals("REAL", score!!["affinity"])

        @Suppress("UNCHECKED_CAST")
        val rows = result["rows"] as List<Map<String, Any?>>
        assertEquals(1, rows.size)
        val value = rows[0]["score"]
        assertTrue(
            "REAL value must deserialize as Double (was ${value?.javaClass?.simpleName})",
            value is Double
        )
        assertEquals(REAL_VALUE, (value as Double), 1e-9)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Sends a query and awaits the plugin's response payload. */
    private fun query(request: QueryRequest): Map<String, Any?> {
        plugin.onQuery(request)
        val payload = host.awaitAny(5000)
        assertNotNull("Plugin must respond within 5s for $request", payload)
        return payload!!
    }

    /**
     * Creates a fresh fixture database [name] (any prior copy + sidecars removed
     * so [SQLiteOpenHelper.onCreate] always re-runs) and seeds it via [script].
     */
    private fun createFixture(name: String, script: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(name) // fresh seed every run
        object : SQLiteOpenHelper(context, name, null, FIXTURE_VERSION) {
            override fun onCreate(db: SQLiteDatabase) = script(db)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }.writableDatabase.use { /* force create + seed */ }
        fixtureNames.add(name)
    }

    private fun writeGarbageDatabase() {
        val file = context.getDatabasePath(GARBAGE_DB)
        file.parentFile?.mkdirs()
        // Definitely-not-SQLite header bytes.
        file.writeBytes(byteArrayOf(0x47, 0x41, 0x52, 0x42, 0x41, 0x47, 0x45, 0x21))
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Sorted names of all files in the databases dir whose name starts with [name]. */
    private fun sidecarFiles(name: String): List<String> {
        val dir = context.getDatabasePath(name).parentFile ?: return emptyList()
        val files = dir.listFiles()?.toList() ?: emptyList()
        return files.filter { it.name.startsWith(name) }.map { it.name }.sorted()
    }

    private companion object {
        const val FIXTURE_VERSION = 1
        const val BASIC_DB = "probe_invariant.db"
        const val PAGED_DB = "probe_paged.db"
        const val GARBAGE_DB = "bad.db"
        const val LARGE_BLOB_SIZE = 4096            // > 2 KB → truncation branch
        const val PAGED_ROW_COUNT = 250
        const val PAGE_SIZE = 100
        const val REAL_VALUE = 3.14
    }
}
