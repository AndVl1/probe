plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.bcv)
    alias(libs.plugins.maven.publish) apply false
}

// BCV extension config — ignoredProjects documents intent.
// Note: BCV plugin hooks into kotlin-android for task registration, which AGP 9.0
// forbids. Root-level aggregate tasks and per-module tasks are wired manually below.
apiValidation {
    ignoredProjects += listOf("sample")
}

// Modules that are published to Maven Central.
// "sample" is a demo app and must NOT be published.
val publishableModules = setOf("core", "plugin-network", "plugin-db", "plugin-prefs", "plugin-layout")

val versionFromFile = file("../../VERSION").readText().trim()
val isRelease = findProperty("release")?.toString()?.toBoolean() ?: false

subprojects {
    group = "tech.devlens"
    version = if (isRelease) versionFromFile else "$versionFromFile-SNAPSHOT"

    // Skip publication config for non-library modules (e.g. sample app).
    if (name !in publishableModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    val moduleDescription = when (name) {
        "core" -> "DevLens Android SDK — base module with ProbePlugin, ProbeHost, and WebSocketTransport"
        "plugin-network" -> "DevLens Android SDK — network traffic debugging plugin"
        "plugin-db" -> "DevLens Android SDK — database debugging plugin (stub)"
        "plugin-prefs" -> "DevLens Android SDK — SharedPreferences debugging plugin (stub)"
        "plugin-layout" -> "DevLens Android SDK — layout debugging plugin (stub)"
        else -> "DevLens Android SDK module"
    }

    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
        signAllPublications()
        coordinates(group.toString(), project.name, version.toString())

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

// ─────────────────────────────────────────────────────────────────────────────
// BCV API validation — AGP 9 built-in Kotlin workaround
//
// AGP 9.0 integrates Kotlin compilation natively and forbids applying the
// kotlin-android plugin alongside com.android.library.  BCV detects Kotlin
// projects via kotlin-android, so its tasks are never registered automatically.
//
// Workaround: manually register BCV's own task types (KotlinApiBuildTask,
// KotlinApiCompareTask, SyncFile) for each publishable library module, pointing
// them at the release-variant classes JAR produced by AGP's
// bundleLibCompileToJarRelease task.  No hardcoded internal paths — the JAR
// location is derived from a stable public AGP task reference.
//
// Remove this block once BCV officially supports AGP 9 built-in Kotlin:
// https://github.com/Kotlin/binary-compatibility-validator
// ─────────────────────────────────────────────────────────────────────────────

val rootApiDump = tasks.register("apiDump") {
    group = "other"
    description = "Updates .api files for all publishable Android library modules"
}
val rootApiCheck = tasks.register("apiCheck") {
    group = "verification"
    description = "Checks that the public API of all publishable modules is unchanged"
}

subprojects {
    if (name !in publishableModules) return@subprojects

    pluginManager.withPlugin("com.android.library") {
        // Create the BCV analysis classpath (needs kotlin-metadata-jvm to read Kotlin
        // metadata from compiled class files). Must be created before afterEvaluate.
        val bcvClasspath = configurations.create("bcvApiClasspath") {
            isCanBeResolved = true
            isCanBeConsumed = false
        }
        val kotlinVersion = rootProject.providers
            .gradleProperty("kotlinVersion")
            .orElse(libs.versions.kotlin)
            .get()
        dependencies.add("bcvApiClasspath", "org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion")

        afterEvaluate {
            val apiFileName = "${project.name}.api"
            val apiDir = project.file("api")

            // AGP's task that bundles all compiled Kotlin + Java classes into a JAR.
            val jarTask = tasks.named("bundleLibCompileToJarRelease")

            // 1. Generate an intermediate .api snapshot from the release classes JAR.
            val apiBuildTask = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
                group = "other"
                description = "Generates API dump for :${project.name}"
                dependsOn(jarTask)
                inputJar.set(
                    project.layout.buildDirectory.file(
                        "intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar"
                    )
                )
                // BCV's AbiBuildWorker runs in a classLoaderIsolation worker. Its classpath
                // comes from runtimeClasspath. kotlin-metadata-jvm must be on runtimeClasspath
                // so the worker can load JvmMetadataUtil. inputDependencies carries the same
                // JARs so BCV can resolve inherited class members during analysis.
                inputDependencies.from(bcvClasspath)
                runtimeClasspath.from(bcvClasspath)
                outputApiFile.set(project.layout.buildDirectory.file("api/$apiFileName"))
            }

            // 2. Verify current code matches the committed .api file (CI gate).
            val apiCheckTask = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
                group = "verification"
                description = "Checks API compatibility for :${project.name}"
                dependsOn(apiBuildTask)
                generatedApiFile.set(apiBuildTask.flatMap { it.outputApiFile })
                projectApiFile.set(project.layout.projectDirectory.file("api/$apiFileName"))
            }

            // 3. Copy the generated snapshot over the committed .api file (run on purpose).
            // Note: SyncFile from BCV is Kotlin-internal; replicate its behavior here.
            tasks.register("apiDump") {
                group = "other"
                description = "Updates the committed .api file for :${project.name}"
                dependsOn(apiBuildTask)
                val fromProp = apiBuildTask.flatMap { it.outputApiFile }
                val toProp = project.layout.projectDirectory.file("api/$apiFileName")
                inputs.file(fromProp)
                outputs.file(toProp)
                doFirst { apiDir.mkdirs() }
                doLast {
                    val src = fromProp.get().asFile
                    val dst = toProp.asFile
                    if (src.exists()) src.copyTo(dst, overwrite = true) else dst.delete()
                }
            }

            // Wire into the standard Gradle check lifecycle.
            tasks.named("check").configure { dependsOn(apiCheckTask) }

            // Contribute to root-level aggregate tasks.
            rootApiDump.configure { dependsOn(tasks.named("apiDump")) }
            rootApiCheck.configure { dependsOn(apiCheckTask) }
        }
    }
}
