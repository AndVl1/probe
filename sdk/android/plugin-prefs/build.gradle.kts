plugins {
    id("devlens-library")
}

android {
    namespace = "tech.devlens.prefs"
    testOptions {
        unitTests {
            // PreferencesPlugin references android.content.Context and
            // android.content.SharedPreferences in its public API and snapshot
            // path. JVM unit tests only exercise the no-prefs paths (id,
            // supportedPlatforms, onQuery with null host, attach/detach cycle).
            // This built-in AGP flag makes android.* references return null/0
            // instead of throwing "not mocked" when those paths are touched.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core"))
    // No runtime deps — android.content.SharedPreferences is a platform API.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
