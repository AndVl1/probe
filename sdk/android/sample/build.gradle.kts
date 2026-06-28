plugins {
    // com.android.application is on the buildscript classpath via buildSrc
    // (version "unknown" to Gradle → cannot use the version-catalog alias here).
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

// Sample app identity. The sample is unpublished; these were previously set by
// the root `subprojects {}` block. Kept here for consistency — the sample does
// NOT apply the devlens-library convention (it's an android-application, not a
// publishable library).
group = "tech.devlens"
val bomVersion = rootProject.extra["bomVersion"] as String
val isRelease = providers.gradleProperty("release").orElse("false").get().toBoolean()
version = if (isRelease) bomVersion else "$bomVersion-SNAPSHOT"

android {
    namespace = "tech.devlens.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.devlens.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":plugin-network"))
    implementation(project(":plugin-db"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
}
