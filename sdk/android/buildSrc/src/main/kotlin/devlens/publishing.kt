package devlens

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar

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
    "plugin-prefs" to "DevLens Android SDK — SharedPreferences debugging plugin",
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
        // AGP 9.2.0 ships an embedded Dokka whose shaded ASM (API level < Opcodes.ASM9)
        // throws `UnsupportedOperationException: PermittedSubclasses requires ASM9` when
        // it parses the Kotlin sealed-class bytecode of `tech.devlens.QueryResult` (:core)
        // for KDoc link resolution. The full maven-publish plugin's auto-detection
        // (`configureBasedOnAppliedPlugins()`, run from AndroidComponentsExtension.finalizeDsl)
        // defaults to `publishJavadocJar = true`, which calls AGP's
        // `LibrarySingleVariant.withJavadocJar()` and registers the broken
        // `javaDocReleaseGeneration` task — the one that crashed the release publish
        // (CI run 28534373889; Kotlin/dokka#2956 has the identical stacktrace).
        //
        // We can't fix AGP's bundled Dokka from here, so for Android library modules we
        // configure the platform EXPLICITLY with `publishJavadocJar = false`: AGP's
        // `withJavadocJar()` is never called and `javaDocReleaseGeneration` never enters
        // the publish task graph. Calling `configure(...)` here also pre-empts the full
        // plugin's finalizeDsl auto-call — it sets the `platform` property, whose
        // `isPresent` guard makes the later `configureBasedOnAppliedPlugins()` a no-op.
        // Maven Central still requires a `-javadoc.jar` for aar packaging, so an EMPTY one
        // is published manually below (valid per Central's validation). Non-Android
        // modules (the BOM is `java-platform`) are left to the plugin's default
        // auto-detection, which is unchanged.
        if (plugins.hasPlugin("com.android.library")) {
            configure(
                AndroidSingleVariantLibrary(
                    variant = "release",
                    sourcesJar = true,
                    publishJavadocJar = false,
                ),
            )
        }

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

    // Publish an EMPTY javadoc jar for Android library modules. The block above
    // disabled AGP's (broken on 9.2.0) javadoc generation, but Maven Central
    // requires a `-javadoc.jar` for aar packaging, so produce a valid empty one
    // and attach it to the "maven" publication vanniktech creates for the release
    // variant. The sources jar is unaffected — it still comes from AGP's
    // `withSourcesJar()` (real source). Non-Android modules are skipped (the BOM
    // is `java-platform`/pom packaging, which Central exempts).
    if (plugins.hasPlugin("com.android.library")) {
        val emptyJavadocJar = tasks.register("devlensEmptyJavadocJar", Jar::class.java) {
            archiveClassifier.set("javadoc")
            description = "Empty javadoc jar — bypasses AGP 9.2.0's broken embedded Dokka (Kotlin/dokka#2956)."
        }
        extensions.getByType(PublishingExtension::class.java).publications
            .withType(MavenPublication::class.java)
            .configureEach { artifact(emptyJavadocJar) }
    }
}
