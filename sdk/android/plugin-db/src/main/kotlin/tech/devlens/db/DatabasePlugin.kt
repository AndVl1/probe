package tech.devlens.db

import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.ProbePlugin

/**
 * Probe plugin — inspects SQLite / Room databases.
 *
 * ## Planned capabilities
 * - List all databases and their tables
 * - Execute read-only SQL queries, stream results to CLI
 * - Watch a table for changes (via invalidation tracker)
 * - Export table snapshot as JSON/CSV
 *
 * ## Planned usage
 * ```kotlin
 * val db = Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()
 *
 * Probe.install(
 *     Probe.Builder(this)
 *         .plugin(DatabasePlugin(db))
 *         .build()
 * )
 * ```
 *
 * ## Platform support
 * - Android: SQLite via SupportSQLiteDatabase ✓ (planned)
 * - iOS: SQLite / CoreData (planned)
 * - Flutter: sqflite / drift (planned)
 * - AuroraOS: SQLite (planned)
 *
 * > **Not yet implemented.** Interface is stable.
 */
class DatabasePlugin : ProbePlugin {

    override val id = "database"
    override val displayName = "Database"
    override val supportedPlatforms = setOf(Platform.ANDROID, Platform.IOS, Platform.FLUTTER, Platform.AURORA)

    override fun onAttach(host: ProbeHost) {
        // TODO: register database invalidation observer, send schema on connect
    }

    override fun onDetach() {
        // TODO: unregister observer
    }
}
