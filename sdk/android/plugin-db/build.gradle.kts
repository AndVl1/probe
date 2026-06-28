plugins {
    id("devlens-library")
}

android {
    namespace = "tech.devlens.db"
    testOptions {
        unitTests {
            // DatabasePlugin references android.content.Context and
            // android.database.sqlite.* in its public API and dispatch path. JVM
            // unit tests only exercise the no-SQLite paths (id,
            // supportedPlatforms, onQuery with null host, unknown_method). This
            // built-in AGP flag makes android.* references return null/0 instead
            // of throwing "not mocked" when those paths are touched.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core"))
    // No runtime deps — android.database.sqlite is a platform API.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.gson)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
