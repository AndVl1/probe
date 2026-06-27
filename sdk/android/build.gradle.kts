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
    // sample = demo app ; bom = java-platform artifact (no Kotlin API surface).
    ignoredProjects += listOf("sample", "bom")
}

// Modules that are published to Maven Central.
// "sample" is a demo app and must NOT be published.
// "bom" is the java-platform BOM artifact (artifactId = devlens-bom).
val publishableModules = setOf("core", "plugin-network", "plugin-db", "plugin-prefs", "plugin-layout", "bom")

// Artifact id overrides — bom publishes as devlens-bom; others use project.name.
val artifactIdOverrides = mapOf("bom" to "devlens-bom")

// ─────────────────────────────────────────────────────────────────────────────
// Version source of truth: root /versions.toml (replaces the flat VERSION file).
// Parsed here (line-based; the file schema is fixed and tiny) and exposed to
// subprojects via rootProject.extra. Mutated ONLY by scripts/release-plan --apply.
val parsedVersions: Map<String, String> = run {
    val versionsFile = file("../../versions.toml")
    val result = mutableMapOf<String, String>()
    var section = ""
    versionsFile.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.startsWith("#") || line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.removeSurrounding("[", "]")
            return@forEach
        }
        val eq = line.indexOf('=')
        if (eq > 0) {
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            value = value.substringAfter('"', value).substringBefore('"', value)
            // Flatten: [bom].version → "bom" ; [modules].<name> → <name>.
            val flatKey = when (section) {
                "bom" -> if (key == "version") "bom" else "$section.$key"
                "modules" -> key
                else -> "$section.$key"
            }
            result[flatKey] = value
        }
    }
    result
}

val moduleList = listOf("core", "plugin-network", "plugin-db", "plugin-prefs", "plugin-layout")
val moduleVersions: Map<String, String> = moduleList.associateWith { parsedVersions[it]!! }
val bomVersion: String = parsedVersions["bom"]!!

// Expose to subprojects (bom module reads these for its constraints).
extra["bomVersion"] = bomVersion
extra["moduleVersions"] = moduleVersions

val isRelease = findProperty("release")?.toString()?.toBoolean() ?: false

subprojects {
    group = "tech.devlens"

    // Per-module versioning: each publishable module reads its own version from
    // versions.toml. sample/bom track the BOM version. A module version may
    // diverge from its siblings (independent per-module release train).
    val versionForProject: String = when (name) {
        "bom", "sample" -> bomVersion
        else -> moduleVersions[name] ?: bomVersion
    }
    version = if (isRelease) versionForProject else "$versionForProject-SNAPSHOT"

    // Skip publication config for non-publishable modules (e.g. sample app).
    if (name !in publishableModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    val artifactId = artifactIdOverrides[name] ?: name
    val moduleDescription = when (name) {
        "core" -> "DevLens Android SDK — base module with ProbePlugin, ProbeHost, and WebSocketTransport"
        "plugin-network" -> "DevLens Android SDK — network traffic debugging plugin"
        "plugin-db" -> "DevLens Android SDK — database debugging plugin (stub)"
        "plugin-prefs" -> "DevLens Android SDK — SharedPreferences debugging plugin (stub)"
        "plugin-layout" -> "DevLens Android SDK — layout debugging plugin (stub)"
        "bom" -> "DevLens Android SDK — Bill of Materials"
        else -> "DevLens Android SDK module"
    }

    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
        signAllPublications()
        coordinates(group.toString(), artifactId, version.toString())

        pom {
            name.set("DevLens $artifactId")
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
