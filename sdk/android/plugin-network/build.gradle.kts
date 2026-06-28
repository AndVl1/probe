plugins {
    id("devlens-library")
}

android {
    namespace = "tech.devlens.network"
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
