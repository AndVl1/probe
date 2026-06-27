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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.devlens.QueryRequest

/**
 * Instrumented tests for [DatabasePlugin]'s SQLite behavior.
 *
 * Runs on an emulator/device (Wave 3). Creates a fixture SQLite database via a
 * plain [SQLiteOpenHelper] in the target context with known rows (including a
 * NULL-valued column and a small BLOB column), then drives [DatabasePlugin.onQuery]
 * directly and asserts the `queryResult` payloads.
 */
@RunWith(AndroidJUnit4::class)
class DatabasePluginInstrumentedTest {

    private lateinit var context: Context
    private lateinit var plugin: DatabasePlugin
    private lateinit var host: FakeProbeHost

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Create + seed the fixture database so its file exists before inspection.
        FixtureDb(context).writableDatabase.use { /* force create + seed */ }
        plugin = DatabasePlugin(context)
        host = FakeProbeHost()
        plugin.onAttach(host)
    }

    @After
    fun teardown() {
        plugin.onDetach()
        // Best-effort cleanup of the fixture databases.
        context.deleteDatabase(FixtureDb.NAME)
        context.deleteDatabase(GARBAGE_DB)
    }

    @Test
    fun listDatabasesIncludesFixtureAndMarksGarbageAsSkipped() {
        // Drop a non-SQLite file into the databases dir to exercise encrypted/skip.
        writeGarbageDatabase()

        val payload = query(
            QueryRequest(
                requestId = "q-list",
                plugin = "database",
                method = "listDatabases"
            )
        )

        assertEquals(true, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val databases = result["databases"] as List<Map<String, Any>>

        val fixture = databases.firstOrNull { it["name"] == FixtureDb.NAME }
        assertNotNull("fixture db must be listed", fixture)
        assertEquals(false, fixture!!["encrypted"])
        assertEquals(false, fixture["skipped"])
        assertTrue("sizeBytes must be positive", (fixture["sizeBytes"] as Number).toLong() > 0)

        val garbage = databases.firstOrNull { it["name"] == GARBAGE_DB }
        assertNotNull("garbage db must be listed", garbage)
        // A garbage file triggers SQLiteException on open → encrypted+skipped.
        assertEquals(true, garbage!!["skipped"])
    }

    @Test
    fun listTablesReturnsFixtureTables() {
        val payload = query(
            QueryRequest(
                requestId = "q-tables",
                plugin = "database",
                method = "listTables",
                params = mapOf("database" to FixtureDb.NAME)
            )
        )

        assertEquals(true, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>
        assertEquals(FixtureDb.NAME, result["database"])
        @Suppress("UNCHECKED_CAST")
        val tables = result["tables"] as List<Map<String, Any>>

        val items = tables.firstOrNull { it["name"] == FixtureDb.TABLE }
        assertNotNull("'items' table must be listed", items)
        assertEquals("table", items!!["type"])
    }

    @Test
    fun inspectTableReturnsSchemaAndTypedRows() {
        val payload = query(
            QueryRequest(
                requestId = "q-inspect",
                plugin = "database",
                method = "inspectTable",
                params = mapOf(
                    "database" to FixtureDb.NAME,
                    "table" to FixtureDb.TABLE,
                    "limit" to 100,
                    "offset" to 0
                )
            )
        )

        assertEquals(true, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>

        // ── schema ────────────────────────────────────────────────────────────
        @Suppress("UNCHECKED_CAST")
        val schema = result["schema"] as List<Map<String, Any>>
        assertEquals(4, schema.size)
        val byName = schema.associateBy { it["name"] as String }
        assertEquals("INTEGER", (byName["id"]!!)["affinity"])
        assertEquals(true, (byName["id"]!!)["primaryKey"])
        assertEquals("TEXT", (byName["label"]!!)["affinity"])
        assertEquals(true, (byName["label"]!!)["notNull"])
        assertEquals("TEXT", (byName["note"]!!)["affinity"])
        assertEquals(false, (byName["note"]!!)["notNull"])
        assertEquals("BLOB", (byName["thumb"]!!)["affinity"])

        // ── rows: null + blob handling ────────────────────────────────────────
        @Suppress("UNCHECKED_CAST")
        val rows = result["rows"] as List<Map<String, Any?>>
        assertEquals(FixtureDb.SEEDED_ROWS, rows.size)
        assertEquals(false, result["truncated"])

        val byLabel = rows.associateBy { it["label"] as String }
        // Null-valued column maps to JSON null.
        assertNull("null column must deserialize to null", byLabel["null-note"]!!["note"])
        // Small BLOB renders as lowercase hex.
        assertEquals("0xdeadbeef", byLabel["with-blob"]!!["thumb"])
        // A null BLOB is null, not an empty string.
        assertNull(byLabel["no-blob"]!!["thumb"])
    }

    @Test
    fun inspectTablePaginatesAndReportsTruncation() {
        val payload = query(
            QueryRequest(
                requestId = "q-page",
                plugin = "database",
                method = "inspectTable",
                params = mapOf(
                    "database" to FixtureDb.NAME,
                    "table" to FixtureDb.TABLE,
                    "limit" to 2,
                    "offset" to 0
                )
            )
        )

        @Suppress("UNCHECKED_CAST")
        val result = (payload["result"] as Map<String, Any>)
        @Suppress("UNCHECKED_CAST")
        val rows = result["rows"] as List<Map<String, Any?>>
        assertEquals(2, (result["limit"] as Number).toInt())
        assertEquals(0, (result["offset"] as Number).toInt())
        assertEquals(2, rows.size)
        assertEquals(true, result["truncated"])
    }

    @Test
    fun inspectTableOffsetSkipsRows() {
        val payload = query(
            QueryRequest(
                requestId = "q-offset",
                plugin = "database",
                method = "inspectTable",
                params = mapOf(
                    "database" to FixtureDb.NAME,
                    "table" to FixtureDb.TABLE,
                    "limit" to 100,
                    "offset" to FixtureDb.SEEDED_ROWS // past the end
                )
            )
        )

        @Suppress("UNCHECKED_CAST")
        val result = (payload["result"] as Map<String, Any>)
        @Suppress("UNCHECKED_CAST")
        val rows = result["rows"] as List<Map<String, Any?>>
        assertTrue("offset past end yields no rows", rows.isEmpty())
        assertEquals(false, result["truncated"])
    }

    @Test
    fun inspectTableMissingTableReturnsTableNotFound() {
        val payload = query(
            QueryRequest(
                requestId = "q-missing-table",
                plugin = "database",
                method = "inspectTable",
                params = mapOf(
                    "database" to FixtureDb.NAME,
                    "table" to "does_not_exist"
                )
            )
        )

        assertEquals(false, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any>
        assertEquals("table_not_found", error["code"])
    }

    @Test
    fun inspectTableMissingDatabaseReturnsDatabaseNotFound() {
        val payload = query(
            QueryRequest(
                requestId = "q-missing-db",
                plugin = "database",
                method = "inspectTable",
                params = mapOf(
                    "database" to "absent.db",
                    "table" to "whatever"
                )
            )
        )

        assertEquals(false, payload["ok"])
        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any>
        assertEquals("database_not_found", error["code"])
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

    private fun writeGarbageDatabase() {
        val file = context.getDatabasePath(GARBAGE_DB)
        file.parentFile?.mkdirs()
        // Definitely-not-SQLite header bytes.
        file.writeBytes(byteArrayOf(0x47, 0x41, 0x52, 0x42, 0x41, 0x47, 0x45, 0x21))
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    /** Plain SQLiteOpenHelper fixture — zero Room/ksp, platform API only. */
    private class FixtureDb(context: Context) :
        SQLiteOpenHelper(context, NAME, null, VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id    INTEGER PRIMARY KEY AUTOINCREMENT,
                    label TEXT NOT NULL,
                    note  TEXT,
                    thumb BLOB
                )
                """.trimIndent()
            )
            seed(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }

        private fun seed(db: SQLiteDatabase) {
            insert(db, "with-blob", "hello", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
            insert(db, "null-note", null, null)
            insert(db, "no-blob", "world", null)
            insert(db, "row-3", "note-3", null)
            insert(db, "row-4", "note-4", null)
        }

        private fun insert(db: SQLiteDatabase, label: String, note: String?, thumb: ByteArray?) {
            val values = ContentValues().apply {
                put("label", label)
                put("note", note)
                put("thumb", thumb)
            }
            db.insert(TABLE, null, values)
        }

        companion object {
            const val NAME = "probe_fixture.db"
            const val VERSION = 1
            const val TABLE = "items"
            const val SEEDED_ROWS = 5
        }
    }

    private companion object {
        const val GARBAGE_DB = "garbage.db"
    }
}
