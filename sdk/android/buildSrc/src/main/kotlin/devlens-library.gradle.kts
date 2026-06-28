// devlens-library — convention plugin applied by every publishable DevLens
// Android library module. Self-declares a module as publishable: there is no
// central `publishableModules` list anywhere in the build. Apply this plugin
// in a module's `plugins {}` block and the module gets android-library config,
// maven-publish wiring, and BCV API validation.
//
// Non-library modules (bom = java-platform, sample = android-application) do
// NOT apply this plugin; they configure publication / identity in their own
// build.gradle.kts.

import com.android.build.api.dsl.LibraryExtension
import devlens.configureDevLensPublishing
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    // Full plugin (not .base): vanniktech's full plugin auto-creates a
    // MavenPublication from the AGP release variant AAR. The .base variant
    // would require us to declare the publication manually.
    id("com.vanniktech.maven.publish")
}

group = "tech.devlens"

// Per-module versioning: each library reads its own version from the root
// versions.toml parser; falls back to bomVersion if unlisted. SNAPSHOT suffix
// unless -Prelease=true.
val moduleVersions = rootProject.extra["moduleVersions"] as Map<*, *>
val bomVersion = rootProject.extra["bomVersion"] as String
val isRelease = providers.gradleProperty("release").orElse("false").get().toBoolean()
val moduleVersion = moduleVersions[project.name] as? String ?: bomVersion
version = if (isRelease) moduleVersion else "$moduleVersion-SNAPSHOT"

// Common Android config shared by every library module. Per-module
// `android { namespace = ... }` (and any testOptions overrides) layer on top
// of this in the module's own build.gradle.kts.
//
// AGP 9 registers a runtime-decorated impl (`LibraryExtensionImpl$AgpDecorated…`)
// that does not share the legacy `com.android.build.gradle.LibraryExtension`
// supertype. The public DSL interface `com.android.build.api.dsl.LibraryExtension`
// IS implemented by the decorated instance — look up by name and cast to it.
(extensions.getByName("android") as LibraryExtension).apply {
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Publication contract (Central Portal, signing, coordinates, POM).
configureDevLensPublishing()

// ─────────────────────────────────────────────────────────────────────────────
// BCV API validation — AGP 9 built-in Kotlin workaround.
//
// AGP 9.0 integrates Kotlin compilation natively and forbids applying the
// kotlin-android plugin alongside com.android.library. BCV detects Kotlin
// projects via kotlin-android, so its tasks are never registered automatically.
//
// Workaround: manually register BCV's own task types (KotlinApiBuildTask,
// KotlinApiCompareTask) against the release-variant classes JAR produced by
// AGP's bundleLibCompileToJarRelease task. No hardcoded internal paths — the
// JAR location is derived from a stable public AGP task reference.
//
// Remove this block once BCV officially supports AGP 9 built-in Kotlin:
// https://github.com/Kotlin/binary-compatibility-validator
// ─────────────────────────────────────────────────────────────────────────────

// Create the BCV analysis classpath (needs kotlin-metadata-jvm to read Kotlin
// metadata from compiled class files). Must be created before afterEvaluate.
val bcvApiClasspath = configurations.create("bcvApiClasspath") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val kotlinVersion = providers.gradleProperty("kotlinVersion")
    .orElse(libs.findVersion("kotlin").get().requiredVersion)
    .get()
dependencies.add(
    "bcvApiClasspath",
    "org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion",
)

afterEvaluate {
    val apiFileName = "${project.name}.api"
    val apiDir = project.file("api")

    // AGP's task that bundles all compiled Kotlin + Java classes into a JAR.
    val jarTask = tasks.named("bundleLibCompileToJarRelease")

    // 1. Generate an intermediate .api snapshot from the release classes JAR.
    val apiBuildTask = tasks.register<KotlinApiBuildTask>("apiBuild") {
        group = "other"
        description = "Generates API dump for :${project.name}"
        dependsOn(jarTask)
        inputJar.set(
            project.layout.buildDirectory.file(
                "intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar",
            ),
        )
        // BCV's AbiBuildWorker runs in a classLoaderIsolation worker. Its classpath
        // comes from runtimeClasspath. kotlin-metadata-jvm must be on runtimeClasspath
        // so the worker can load JvmMetadataUtil. inputDependencies carries the same
        // JARs so BCV can resolve inherited class members during analysis.
        inputDependencies.from(bcvApiClasspath)
        runtimeClasspath.from(bcvApiClasspath)
        outputApiFile.set(project.layout.buildDirectory.file("api/$apiFileName"))
    }

    // 2. Verify current code matches the committed .api file (CI gate).
    val apiCheckTask = tasks.register<KotlinApiCompareTask>("apiCheck") {
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

    // Self-register into the root-level aggregate tasks (registered empty in
    // the root build.gradle.kts). Replaces the previous root-side `subprojects
    // {}` iteration that had to know the publishable-module list.
    rootProject.tasks.named("apiCheck") { dependsOn(apiCheckTask) }
    rootProject.tasks.named("apiDump") { dependsOn(tasks.named("apiDump")) }
}
