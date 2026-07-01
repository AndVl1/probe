plugins {
    id("devlens-library")
}

android {
    namespace = "tech.devlens.layout"
}

dependencies {
    api(project(":core"))
}
