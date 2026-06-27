// DevLens BOM (Bill of Materials).
//
// `java-platform` emits a .pom with a <dependencyManagement> section only — no
// jar, no code, no Kotlin. Consumers import it with:
//   implementation(platform("tech.devlens:devlens-bom:<version>"))
// and then depend on individual modules WITHOUT a version.
//
// Module versions come from gradle.ext.moduleVersions, which the root
// build.gradle.kts parses from /versions.toml. The BOM's own version comes from
// gradle.ext.bomVersion. The vanniktech maven.publish plugin (applied + configured
// in the root subprojects {} block) handles coordinates/signing/portal; this
// module only applies `java-platform` and declares the constraints.

plugins {
    `java-platform`
}

// Pin each publishable Android module. `api` constraints (vs `implementation`)
// surface in the <dependencyManagement> section so consumers see them.
// Group is the stable coordinate tech.devlens (project invariant); not read
// from rootProject.group because the android build root carries no group.
dependencies {
    constraints {
        val mv = rootProject.extra["moduleVersions"] as Map<*, *>
        api("tech.devlens:core:${mv["core"]}")
        api("tech.devlens:plugin-network:${mv["plugin-network"]}")
        api("tech.devlens:plugin-db:${mv["plugin-db"]}")
        api("tech.devlens:plugin-prefs:${mv["plugin-prefs"]}")
        api("tech.devlens:plugin-layout:${mv["plugin-layout"]}")
    }
}
