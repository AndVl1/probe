plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tech.devlens"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            // WebSocketTransport calls android.util.Log and reads android.os.Build.*
            // (via defaultDeviceInfo()). On a plain JVM those android.* calls would
            // throw "not mocked"; this built-in AGP flag makes them return null/0
            // instead so the transport can be exercised without Robolectric/device.
            //
            // CAVEAT: module-level — silently stubs ALL android.* references in
            // src/test. There is currently no other src/test in :core, so this has
            // no side effects today. Revisit if pure-JVM tests that rely on real
            // android.* behavior are added later.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
