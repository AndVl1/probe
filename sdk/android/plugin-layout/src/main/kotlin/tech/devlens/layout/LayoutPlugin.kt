package tech.devlens.layout

import tech.devlens.Platform
import tech.devlens.ProbeHost
import tech.devlens.ProbePlugin
import tech.devlens.QueryRequest

/**
 * Probe plugin — exports the live View/Compose hierarchy as inspectable JSON.
 *
 * Complements [claude-in-mobile](https://github.com/AlexGladkov/claude-in-mobile) which
 * captures hierarchy from *outside* via ADB/accessibility. This plugin captures from
 * *inside* the app, giving access to:
 * - Actual View/Composable properties (padding, elevation, measured size)
 * - Internal state (ViewModel data, recomposition counts)
 * - Custom semantics and debug metadata
 *
 * ## Planned capabilities
 * - Dump full View or Compose layout tree with properties on demand
 * - Highlight a specific view by ID or semantics tag
 * - Watch for layout changes (e.g. on navigation)
 * - Works with both View system and Compose
 *
 * ## Planned usage
 * ```kotlin
 * Probe.install(
 *     Probe.Builder(this)
 *         .plugin(LayoutPlugin())
 *         .build()
 * )
 * ```
 *
 * ## Platform support
 * - Android: View hierarchy + Compose (planned)
 * - iOS: UIView / SwiftUI hierarchy (planned)
 * - Flutter: Widget tree (planned)
 *
 * > **Not yet implemented.** Interface is stable.
 */
class LayoutPlugin : ProbePlugin {

    override val id = "layout"
    override val displayName = "Layout"
    override val supportedPlatforms = setOf(Platform.ANDROID, Platform.IOS, Platform.FLUTTER)

    override fun onAttach(host: ProbeHost) {
        // TODO: register activity lifecycle observer, capture hierarchy on demand
    }

    override fun onDetach() {
        // TODO: unregister observer
    }

    // Push-only plugin. Declared (not inherited) so the BCV public-API signature
    // is deterministic — an inherited default method's bridge is emitted into the
    // class bytecode nondeterministically and breaks release apiCheck.
    override fun onQuery(request: QueryRequest) {
        // TODO: no query handling planned for layout
    }
}
