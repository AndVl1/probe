package tech.devlens.sample.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Minimal inspectable SQLite database for the DevLens sample app.
 *
 * Zero external dependencies — uses only [android.database.sqlite] (a platform
 * API). Creates a single table [TABLE_CHARACTERS] seeded with ~10 rows,
 * including a NULL-valued column (`homeworld`) and a small BLOB column
 * (`thumbnail`), so the [tech.devlens.db.DatabasePlugin] inspector can
 * demonstrate null / blob handling and pagination.
 *
 * Exists purely so `devlens db` has something to inspect on a fresh install.
 */
class SampleDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CHARACTERS (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                name      TEXT NOT NULL,
                homeworld TEXT,
                species   TEXT NOT NULL,
                thumbnail BLOB
            )
            """.trimIndent()
        )
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CHARACTERS")
        onCreate(db)
    }

    private fun seed(db: SQLiteDatabase) {
        // name, homeworld (nullable), species
        val rows = listOf(
            Triple("Luke Skywalker", "Tatooine", "Human"),
            Triple("Leia Organa", "Alderaan", "Human"),
            Triple("Han Solo", "Corellia", "Human"),
            Triple("Chewbacca", "Kashyyyk", "Wookiee"),
            Triple("Yoda", null, "Unknown"), // NULL homeworld
            Triple("Obi-Wan Kenobi", "Stewjon", "Human"),
            Triple("R2-D2", "Naboo", "Droid"),
            Triple("C-3PO", "Affa", "Droid"),
            Triple("Darth Vader", "Tatooine", "Human"),
            Triple("Palpatine", "Naboo", "Human")
        )
        rows.forEach { (name, homeworld, species) ->
            val values = ContentValues().apply {
                put("name", name)
                put("homeworld", homeworld) // ContentValues.put skips NULL columns naturally
                put("species", species)
                put("thumbnail", SAMPLE_BLOB) // small BLOB (8 bytes) → full hex in inspector
            }
            db.insert(TABLE_CHARACTERS, null, values)
        }
    }

    private companion object {
        const val NAME = "sample.db"
        const val VERSION = 1
        const val TABLE_CHARACTERS = "sw_characters"

        // 8-byte sample BLOB ("SWB" + control bytes) — well under the 2KB hex cap.
        val SAMPLE_BLOB = byteArrayOf(0x53, 0x57, 0x42, 0x00, 0x01, 0x02, 0x03, 0x04)
    }
}
