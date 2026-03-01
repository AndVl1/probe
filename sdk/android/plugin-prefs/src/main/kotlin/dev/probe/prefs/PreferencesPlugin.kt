package dev.probe.prefs

import dev.probe.Platform
import dev.probe.ProbeHost
import dev.probe.ProbePlugin

/**
 * Probe plugin — reads and watches SharedPreferences (Android) / UserDefaults (iOS).
 *
 * ## Planned capabilities
 * - Enumerate all SharedPreferences files and their keys/values
 * - Watch for changes in real-time (via OnSharedPreferenceChangeListener)
 * - Read encrypted preferences (EncryptedSharedPreferences)
 * - Write/delete values from CLI (debug builds only)
 *
 * ## Planned usage
 * ```kotlin
 * Probe.install(
 *     Probe.Builder(this)
 *         .plugin(PreferencesPlugin())
 *         .build()
 * )
 * ```
 *
 * ## Platform support
 * - Android: SharedPreferences / DataStore (planned)
 * - iOS: UserDefaults (planned)
 * - Flutter: shared_preferences package (planned)
 * - AuroraOS: QSettings (planned)
 *
 * > **Not yet implemented.** Interface is stable.
 */
class PreferencesPlugin : ProbePlugin {

    override val id = "preferences"
    override val displayName = "Preferences"
    override val supportedPlatforms = setOf(Platform.ANDROID, Platform.IOS, Platform.FLUTTER, Platform.AURORA)

    override fun onAttach(host: ProbeHost) {
        // TODO: enumerate SharedPreferences files, register change listeners, send snapshot
    }

    override fun onDetach() {
        // TODO: unregister change listeners
    }
}
