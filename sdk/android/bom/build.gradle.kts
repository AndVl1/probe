// DevLens BOM (Bill of Materials).
//
// `java-platform` emits a .pom with a <dependencyManagement> section only — no
// jar, no code, no Kotlin. Consumers import it with:
//   implementation(platform("tech.devlens:devlens-bom:<version>"))
// and then depend on individual modules WITHOUT a version.
//
// Module versions come from `rootProject.extra["moduleVersions"]`, which the
// root build.gradle.kts parses from /versions.toml. The BOM's own version
// comes from `rootProject.extra["bomVersion"]`. Publication is configured via
// the shared `devlens.configureDevLensPublishing` helper (same Central Portal,
// signing, POM shape as library modules).

import devlens.configureDevLensPublishing

plugins {
    `java-platform`
    // Full plugin (not .base): auto-creates a MavenPublication from the
    // java-platform BOM. The .base variant would require manual publication.
    id("com.vanniktech.maven.publish")
}

group = "tech.devlens"

val bomVersion = rootProject.extra["bomVersion"] as String
val isRelease = providers.gradleProperty("release").orElse("false").get().toBoolean()
version = if (isRelease) bomVersion else "$bomVersion-SNAPSHOT"

// Pin each publishable Android module listed in versions.toml [modules]. `api`
// constraints (vs `implementation`) surface in the <dependencyManagement>
// section so consumers see them. No hardcoded module list — adding a module to
// [modules] in versions.toml (and applying the devlens-library convention
// plugin to it) makes it appear here automatically.
dependencies {
    constraints {
        val mv = rootProject.extra["moduleVersions"] as Map<*, *>
        mv.forEach { (n, v) -> api("tech.devlens:${n}:${v}") }
    }
}

// artifactId override: BOM publishes as `devlens-bom`, not as the project name.
configureDevLensPublishing(artifactId = "devlens-bom")
