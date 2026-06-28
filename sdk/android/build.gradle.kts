plugins {
    // Plugins resolved without a version: AGP, BCV, and maven-publish are on the
    // buildscript classpath via buildSrc (their version is "unknown" to Gradle
    // and cannot be reconciled with a catalog alias). kotlin-compose is NOT in
    // buildSrc, so it still resolves via the version catalog alias.
    id("com.android.application") apply false
    id("com.android.library") apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("com.vanniktech.maven.publish") apply false
}

// BCV extension config — ignoredProjects documents intent.
// Note: BCV plugin hooks into kotlin-android for task registration, which AGP 9.0
// forbids. Per-module apiBuild/apiCheck/apiDump tasks are registered manually
// inside the `devlens-library` convention plugin (buildSrc/) that each
// publishable library self-applies. Root-level aggregate tasks (registered
// below) start empty; convention plugins self-register their per-project tasks
// into the aggregates.
apiValidation {
    // sample = demo app ; bom = java-platform artifact (no Kotlin API surface).
    ignoredProjects += listOf("sample", "bom")
}

// ─────────────────────────────────────────────────────────────────────────────
// Version source of truth: root /versions.toml (replaces the flat VERSION file).
// Parsed here (line-based; the file schema is fixed and tiny) and exposed to
// subprojects via rootProject.extra. Mutated ONLY by scripts/release-plan --apply.
//
// Two outputs:
//   - moduleVersions: <module-name> → <version>, derived from the [modules]
//     section. The set of publishable library modules = moduleVersions.keys;
//     NO hardcoded module list lives anywhere in the build (modules self-declare
//     by applying the devlens-library convention plugin AND appearing under
//     [modules] for version resolution + BOM constraints).
//   - bomVersion: from [bom].version.
val versionsFile = file("../../versions.toml")
val moduleVersions = mutableMapOf<String, String>()
var bomVersion: String? = null
// Single-pass parse: track current section, populate moduleVersions + bomVersion.
var section = ""
versionsFile.readLines().forEach { rawLine ->
    val line = rawLine.trim()
    if (line.startsWith("#") || line.isEmpty()) return@forEach
    if (line.startsWith("[") && line.endsWith("]")) {
        section = line.removeSurrounding("[", "]")
        return@forEach
    }
    val eq = line.indexOf('=')
    if (eq <= 0) return@forEach
    val key = line.substring(0, eq).trim()
    // strip quotes (only the wrapping pair; preserves embedded content)
    val rawVal = line.substring(eq + 1).trim()
    val value = rawVal.substringAfter('"', rawVal).substringBefore('"', rawVal)
    when (section) {
        "bom" -> if (key == "version") bomVersion = value
        "modules" -> moduleVersions[key] = value
    }
}

extra["bomVersion"] = bomVersion ?: error("versions.toml: [bom].version missing")
extra["moduleVersions"] = moduleVersions

// ─────────────────────────────────────────────────────────────────────────────
// Root aggregate BCV tasks. Start empty — the devlens-library convention plugin
// (applied by each publishable library module) self-registers its per-project
// apiCheck/apiDump into these aggregates. No hardcoded publishable-module list
// is needed here.
// ─────────────────────────────────────────────────────────────────────────────

tasks.register("apiDump") {
    group = "other"
    description = "Updates .api files for all publishable Android library modules"
}
tasks.register("apiCheck") {
    group = "verification"
    description = "Checks that the public API of all publishable modules is unchanged"
}
