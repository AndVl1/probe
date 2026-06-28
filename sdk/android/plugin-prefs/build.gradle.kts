plugins {
    id("devlens-library")
}

android {
    namespace = "tech.devlens.prefs"
}

dependencies {
    api(project(":core"))
}
