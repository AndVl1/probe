// buildSrc is a separate Gradle build and does NOT see the host's version
// catalog by default. Declare `libs` from the host catalog so buildSrc's own
// build.gradle.kts dependencies pull versions from a single source of truth
// (sdk/android/gradle/libs.versions.toml) instead of mirroring them as literals.
//
// The convention plugin (devlens-library) accesses the HOST `libs` catalog at
// runtime via VersionCatalogsExtension (in the host project context); this
// settings file is only for buildSrc's own compile-time dependencies.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
