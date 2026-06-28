package devlens

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.Project

/**
 * Per-module artifact descriptions — single source for the `<description>` element
 * of every published POM. Listed here (not as a per-module literal) because this
 * is documentation prose, not structural: it doesn't decide what gets published
 * (self-declaration via the `devlens-library` plugin does). New library modules
 * fall back to the generic description until they earn a dedicated entry.
 */
val moduleDescriptions: Map<String, String> = mapOf(
    "core" to "DevLens Android SDK — base module with ProbePlugin, ProbeHost, and WebSocketTransport",
    "plugin-network" to "DevLens Android SDK — network traffic debugging plugin",
    "plugin-db" to "DevLens Android SDK — database debugging plugin (stub)",
    "plugin-prefs" to "DevLens Android SDK — SharedPreferences debugging plugin (stub)",
    "plugin-layout" to "DevLens Android SDK — layout debugging plugin (stub)",
    "devlens-bom" to "DevLens Android SDK — Bill of Materials",
)

/**
 * Wires the shared DevLens publication contract onto the project:
 *   - publish to Maven Central via the Sonatype CENTRAL_PORTAL,
 *   - sign all publications,
 *   - fixed coordinates under the `tech.devlens` group,
 *   - canonical POM (MIT license, developer Andrey Vladislavov, GitHub SCM).
 *
 * Called from the `devlens-library` convention plugin (artifactId == project
 * name) and from `bom/build.gradle.kts` (artifactId override `devlens-bom`).
 *
 * `version` must already be set on the project when this is called — the
 * convention plugin sets it from `rootProject.extra["moduleVersions"]`, the bom
 * sets it from `rootProject.extra["bomVersion"]`.
 */
fun Project.configureDevLensPublishing(artifactId: String = name) {
    extensions.getByType(MavenPublishBaseExtension::class.java).apply {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        signAllPublications()
        coordinates("tech.devlens", artifactId, version.toString())
        pom {
            name.set("DevLens $artifactId")
            description.set(moduleDescriptions[artifactId] ?: "DevLens Android SDK module")
            url.set("https://github.com/AndVl1/probe")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("andvl1")
                    name.set("Andrey Vladislavov")
                    email.set("and.vladislavov@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/AndVl1/probe.git")
                developerConnection.set("scm:git:ssh://github.com:AndVl1/probe.git")
                url.set("https://github.com/AndVl1/probe")
            }
        }
    }
}
