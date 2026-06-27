package tech.devlens.db

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.ProbePlugin
import tech.devlens.QueryRequest
import tech.devlens.QueryResult
import tech.devlens.toPayload
import java.io.File
import java.util.concurrent.Executors

/**
 * Probe plugin — read-only, on-demand SQLite inspection.
 *
 * Responds to CLI `query` frames (see [onQuery]) by opening app databases
 * read-only on a background thread and streaming schema/row snapshots back as
 * `queryResult` events. It **never writes** to app databases and **never**
 * performs SQLite work on the main or WebSocket transport thread.
 *
 * ## Supported methods (discriminator = [QueryRequest.method])
 * - `listDatabases` — enumerates the app's database files.
 * - `listTables`    — lists tables/views in a database.
 * - `inspectTable`  — schema + paginated rows for a table.
 *
 * ## Invariants (load-bearing — see project `CLAUDE.md`)
 * - Every SQLite operation opens a **fresh**, `OPEN_READONLY` handle.
 * - No `execSQL` writes are ever issued — this tool is strictly read-only.
 * - A no-op [DatabaseErrorHandler] is always supplied, so corruption detected by
 *   SQLite never triggers `android.database.DefaultDatabaseErrorHandler` (which
 *   DELETES the corrupted app database). This is stronger than passing `null`:
 *   on some API levels `null` falls back to the destructive default internally.
 * - All SQLite work happens on the `Probe-Db` background thread.
 *
 * @param context Application context used to locate the app's database files.
 */
class DatabasePlugin(private val context: Context) : ProbePlugin {

    override val id: String = "database"
    override val displayName: String = "Database"
    override val supportedPlatforms: Set<Platform> = setOf(Platform.ANDROID)

    @Volatile
    private var host: ProbeHost? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Probe-Db").apply { isDaemon = true }
    }

    override fun onAttach(host: ProbeHost) {
        this.host = host
    }

    override fun onDetach() {
        host = null
        executor.shutdownNow()
    }

    /**
     * Handles an inbound query on the transport thread. Dispatches the actual
     * SQLite work to [executor] and responds asynchronously via [ProbeHost.send].
     * If [onDetach] was called (no host), this is a no-op.
     */
    override fun onQuery(request: QueryRequest) {
        val h = host ?: return
        executor.execute {
            val payload = try {
                QueryResult.Success(request.requestId, dispatch(request)).toPayload()
            } catch (t: Throwable) {
                errorPayload(request, t)
            }
            h.send(id, payload)
        }
    }

    // ── dispatch ──────────────────────────────────────────────────────────────

    private fun dispatch(request: QueryRequest): Map<String, Any?> =
        when (request.method) {
            "listDatabases" -> listDatabases()
            "listTables" -> listTables(request.params)
            "inspectTable" -> inspectTable(request.params)
            else -> throw DatabaseError(
                code = ERROR_UNKNOWN_METHOD,
                message = "Unknown method: ${request.method}"
            )
        }

    // ── listDatabases ─────────────────────────────────────────────────────────

    private fun listDatabases(): Map<String, Any?> {
        val names = context.databaseList()
            .filter { it.isValidDbFile() }
            .distinct()

        val databases = names.map { name ->
            val file = context.getDatabasePath(name)
            val path = file.absolutePath
            val sizeBytes = if (file.exists()) file.length() else 0L
            val (encrypted, skipped) = probeReadable(path)
            buildMap<String, Any?> {
                put("name", name)
                put("path", path)
                put("sizeBytes", sizeBytes)
                put("encrypted", encrypted)
                put("skipped", skipped)
            }
        }
        return mapOf("databases" to databases)
    }

    /**
     * Opens [path] read-only purely to probe accessibility, then closes it.
     * Returns `(encrypted, skipped)`: a file that fails the magic check or a
     * real schema read is reported, not thrown (so one bad file does not abort
     * the whole listing).
     *
     * `SQLiteDatabase.openDatabase` is **lazy**: a bare open (and even a
     * table-free query such as `SELECT 1`) never touches the database pages, so
     * garbage/encrypted bytes opened and "read" without error and were wrongly
     * reported `encrypted:false`. Detection here is therefore two-layered:
     *
     * 1. A cheap header check — every well-formed SQLite file begins with the
     *    16-byte magic `SQLite format 3\0`. A garbage or SQLCipher-encrypted
     *    file lacks it and is rejected up front. This is deterministic and does
     *    not depend on the no-op [NO_OP_ERROR_HANDLER] not swallowing a corrupt
     *    header (which the destructive `DefaultDatabaseErrorHandler` path can
     *    otherwise mask on some API levels).
     * 2. A real schema read (`SELECT count(*) FROM sqlite_master`) so a
     *    valid-header-but-corrupt file is still surfaced via `SQLiteException`.
     *
     * The no-op [NO_OP_ERROR_HANDLER] is still passed so a corruption signal
     * never reaches the destructive default handler.
     */
    private fun probeReadable(path: String): Pair<Boolean, Boolean> {
        val file = File(path)
        if (!file.exists()) return ENCRYPTED_NO to SKIPPED_YES
        if (!hasSqliteMagic(file)) return ENCRYPTED_YES to SKIPPED_YES
        return try {
            SQLiteDatabase.openDatabase(
                path, /* factory = */ null,
                SQLiteDatabase.OPEN_READONLY, NO_OP_ERROR_HANDLER
            ).use { db ->
                // Force a real schema read so a corrupt-but-valid-header file
                // actually throws instead of opening silently.
                db.rawQuery("SELECT count(*) FROM sqlite_master", null).use {
                    it.moveToFirst()
                }
            }
            ENCRYPTED_NO to SKIPPED_NO
        } catch (e: SQLiteException) {
            ENCRYPTED_YES to SKIPPED_YES
        } catch (e: Exception) {
            ENCRYPTED_NO to SKIPPED_YES
        }
    }

    /** True iff [file] starts with the 16-byte SQLite header magic. */
    private fun hasSqliteMagic(file: File): Boolean {
        if (file.length() < SQLITE_MAGIC.size) return false
        return try {
            val header = ByteArray(SQLITE_MAGIC.size)
            file.inputStream().use { stream ->
                if (stream.read(header) != header.size) return false
            }
            header.contentEquals(SQLITE_MAGIC)
        } catch (e: Exception) {
            false
        }
    }

    // ── listTables ────────────────────────────────────────────────────────────

    private fun listTables(params: Map<String, Any?>): Map<String, Any?> {
        val database = params.requiredString("database")
        val path = resolveDatabasePath(database)
        val tables = openForQuery(path).use { db ->
            db.rawQuery(
                """
                SELECT name, type FROM sqlite_master
                WHERE type IN ('table','view')
                  AND name NOT LIKE 'sqlite_${'%'}'
                  AND name != 'android_metadata'
                """.trimIndent(),
                null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(mapOf(
                            "name" to cursor.getString(0),
                            "type" to cursor.getString(1)
                        ))
                    }
                }
            }
        }
        return mapOf(
            "database" to database,
            "tables" to tables
        )
    }

    // ── inspectTable ──────────────────────────────────────────────────────────

    private fun inspectTable(params: Map<String, Any?>): Map<String, Any?> {
        val database = params.requiredString("database")
        val table = params.requiredString("table")
        val limit = params.optionalInt("limit", DEFAULT_LIMIT).coerceAtMost(MAX_LIMIT)
        val offset = params.optionalInt("offset", 0)
        val path = resolveDatabasePath(database)

        val schema = openForQuery(path).use { db ->
            // PRAGMA table_info(<table>) columns: cid, name, type, notnull, dflt_value, pk.
            db.rawQuery("PRAGMA table_info(${quoteIdent(table)})", null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(/* name */ 1)
                        val declaredType = c.getString(/* type */ 2) ?: ""
                        val notNull = c.getInt(/* notnull */ 3) == 1
                        val pk = c.getInt(/* pk */ 5) > 0
                        add(mapOf(
                            "name" to name,
                            "type" to declaredType,
                            "notNull" to notNull,
                            "primaryKey" to pk,
                            "affinity" to affinityOf(declaredType)
                        ))
                    }
                }
            }
        }
        if (schema.isEmpty()) {
            throw DatabaseError(
                code = ERROR_TABLE_NOT_FOUND,
                message = "Table '$table' not found in database '$database'"
            )
        }

        val columns = schema.map { it["name"] as String }
        val rows = openForQuery(path).use { db ->
            val cols = columns.joinToString(",") { quoteIdent(it) }
            db.rawQuery(
                "SELECT $cols FROM ${quoteIdent(table)} LIMIT ? OFFSET ?",
                arrayOf(limit.toString(), offset.toString())
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(readRow(cursor, columns))
                    }
                }
            }
        }
        val truncated = rows.size == limit
        return mapOf(
            "database" to database,
            "table" to table,
            "schema" to schema,
            "rows" to rows,
            "limit" to limit,
            "offset" to offset,
            "truncated" to truncated
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun resolveDatabasePath(database: String): String {
        val file = context.getDatabasePath(database)
        if (!file.exists()) {
            throw DatabaseError(
                code = ERROR_DATABASE_NOT_FOUND,
                message = "Database '$database' not found at ${file.absolutePath}"
            )
        }
        return file.absolutePath
    }

    /**
     * Opens [path] read-only on a fresh handle. Maps open-time [SQLiteException]
     * (typically an encrypted/corrupt file) to the `encrypted` error code so the
     * CLI gets a precise diagnosis instead of a generic failure.
     */
    private fun openForQuery(path: String): SQLiteDatabase =
        try {
            SQLiteDatabase.openDatabase(
                path, /* factory = */ null,
                SQLiteDatabase.OPEN_READONLY, NO_OP_ERROR_HANDLER
            )
        } catch (e: SQLiteException) {
            throw DatabaseError(
                code = ERROR_ENCRYPTED,
                message = "Could not open '${File(path).name}' read-only: ${e.message}"
            )
        }

    /** Reads a cursor row as a column-name → typed-value map. */
    private fun readRow(cursor: Cursor, columns: List<String>): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>(columns.size)
        columns.forEachIndexed { index, name ->
            row[name] = readValue(cursor, index)
        }
        return row
    }

    private fun readValue(cursor: Cursor, column: Int): Any? = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
        Cursor.FIELD_TYPE_BLOB -> blobValue(cursor.getBlob(column))
        else -> null
    }

    /**
     * Renders a BLOB as `0x…` lowercase hex, capped at [MAX_BLOB_HEX_CHARS] total
     * characters. Larger blobs are reported as `{blobTruncated:true, sizeBytes:N}`
     * so the wire stays compact.
     */
    private fun blobValue(bytes: ByteArray): Any {
        if (bytes.size * 2 + BLOB_HEX_PREFIX.length > MAX_BLOB_HEX_CHARS) {
            return mapOf("blobTruncated" to true, "sizeBytes" to bytes.size)
        }
        val sb = StringBuilder(bytes.size * 2 + BLOB_HEX_PREFIX.length)
        sb.append(BLOB_HEX_PREFIX)
        for (b in bytes) {
            val v = b.toInt()
            sb.append(HEX_CHARS[(v ushr 4) and 0x0f])
            sb.append(HEX_CHARS[v and 0x0f])
        }
        return sb.toString()
    }

    /** Computes SQLite type affinity from a declared column type (SQLite §3.1.1). */
    private fun affinityOf(declaredType: String): String {
        val t = declaredType.uppercase()
        return when {
            t.contains("INT") -> "INTEGER"
            t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> "TEXT"
            t.contains("BLOB") || t.isEmpty() -> "BLOB"
            t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") -> "REAL"
            else -> "NUMERIC"
        }
    }

    /** Quotes an SQL identifier (table/column) with double quotes, escaping `"`. */
    private fun quoteIdent(name: String): String =
        "\"" + name.replace("\"", "\"\"") + "\""

    /** Drops SQLite side files (-journal/-wal/-shm/-mj) so only real DBs are listed. */
    private fun String.isValidDbFile(): Boolean =
        !endsWith("-journal") && !endsWith("-wal") && !endsWith("-shm") && !endsWith("-mj")

    private fun Map<String, Any?>.requiredString(key: String): String {
        val value = (this[key] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw DatabaseError(
                code = ERROR_INTERNAL,
                message = "Missing or empty required param '$key'"
            )
        return value
    }

    /** Reads an int param, tolerating gson's Double deserialization for JSON numbers. */
    private fun Map<String, Any?>.optionalInt(key: String, default: Int): Int {
        return when (val v = this[key]) {
            null -> default
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun errorPayload(request: QueryRequest, t: Throwable): Map<String, Any?> {
        val (code, message) = when (t) {
            is DatabaseError -> t.code to (t.message ?: t.code)
            is SQLiteException -> ERROR_UNOPENABLE to (t.message ?: "database error")
            else -> ERROR_INTERNAL to (t.message ?: t.toString())
        }
        return QueryResult.Error(request.requestId, code, message).toPayload()
    }

    /** Internal typed error so [errorPayload] can map to the right contract code. */
    private class DatabaseError(val code: String, message: String) : RuntimeException(message)

    private companion object {
        // Non-const: `const val` in a companion would surface as a public static
        // field on DatabasePlugin in the bytecode. Plain `val` keeps these
        // implementation details out of the published API surface.
        val DEFAULT_LIMIT = 100
        val MAX_LIMIT = 1000
        val BLOB_HEX_PREFIX = "0x"
        val MAX_BLOB_HEX_CHARS = 2048
        val HEX_CHARS = "0123456789abcdef"

        /** The 16-byte magic every well-formed SQLite database file begins with. */
        val SQLITE_MAGIC = "SQLite format 3 ".toByteArray(Charsets.US_ASCII)

        val ENCRYPTED_NO = false
        val ENCRYPTED_YES = true
        val SKIPPED_NO = false
        val SKIPPED_YES = true

        // Wire-stable error codes (see architecture.json wire_protocol.sdk_to_cli.error_codes).
        val ERROR_UNKNOWN_METHOD = "unknown_method"
        val ERROR_UNOPENABLE = "unopenable"
        val ERROR_ENCRYPTED = "encrypted"
        val ERROR_TABLE_NOT_FOUND = "table_not_found"
        val ERROR_DATABASE_NOT_FOUND = "database_not_found"
        val ERROR_INTERNAL = "internal_error"

        /**
         * Never delete app data on corruption. `DefaultDatabaseErrorHandler` would
         * DELETE the corrupted database — unacceptable for a read-only debug tool.
         * Passing this handler guarantees that never happens regardless of API level.
         */
        val NO_OP_ERROR_HANDLER = DatabaseErrorHandler { /* intentionally no-op */ }
    }
}
