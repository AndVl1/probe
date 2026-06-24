plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Modules that are published to Maven Central.
// "sample" is a demo app and must NOT be published.
val publishableModules = setOf("core", "plugin-network", "plugin-db", "plugin-prefs", "plugin-layout")

subprojects {
    group = "tech.devlens"
    version = "0.1.0-SNAPSHOT"

    // Skip publication config for non-library modules (e.g. sample app).
    if (name !in publishableModules) return@subprojects

    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    val moduleDescription = when (name) {
        "core" -> "DevLens Android SDK — base module with ProbePlugin, ProbeHost, and WebSocketTransport"
        "plugin-network" -> "DevLens Android SDK — network traffic debugging plugin"
        "plugin-db" -> "DevLens Android SDK — database debugging plugin (stub)"
        "plugin-prefs" -> "DevLens Android SDK — SharedPreferences debugging plugin (stub)"
        "plugin-layout" -> "DevLens Android SDK — layout debugging plugin (stub)"
        else -> "DevLens Android SDK module"
    }

    // AGP registers components["release"] in its own afterEvaluate callback.
    // If we register ours in the subprojects{} block, it fires before AGP's, causing
    // "SoftwareComponent 'release' not found". Fix: register our afterEvaluate from
    // inside pluginManager.withPlugin, which fires when the android.library plugin is
    // applied (in the subproject's own build script). At that moment AGP's afterEvaluate
    // callbacks are already queued, so ours is enqueued after them and runs later.
    pluginManager.withPlugin("com.android.library") {
        afterEvaluate {
            val projectVersion = version.toString()
            val ossrhUser = findProperty("ossrhUsername")?.toString() ?: ""
            val ossrhPass = findProperty("ossrhPassword")?.toString() ?: ""

            configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        pom {
                            name.set("DevLens ${project.name}")
                            description.set(moduleDescription)
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
                repositories {
                    maven {
                        name = "sonatype"
                        val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                        val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                        url = if (projectVersion.endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                        credentials {
                            username = ossrhUser
                            password = ossrhPass
                        }
                    }
                }
            }

            // Capture the publication after it has been created above.
            val releasePublication = extensions.getByType<PublishingExtension>().publications["release"]

            configure<SigningExtension> {
                // Signing is only required for non-SNAPSHOT releases.
                // For SNAPSHOT builds the sign tasks are registered but skipped,
                // so ./gradlew build works without a GPG key configured.
                isRequired = !projectVersion.endsWith("SNAPSHOT")
                sign(releasePublication)
            }
        }
    }
}
