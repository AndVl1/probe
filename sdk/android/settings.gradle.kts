pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "probe-android"

include(
    ":core",
    ":plugin-network",
    ":plugin-db",
    ":plugin-prefs",
    ":plugin-layout",
    ":sample",
)
