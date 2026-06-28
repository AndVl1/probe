// buildSrc — auto-included by Gradle for the sdk/android build. Hosts the
// `devlens-library` convention plugin that publishable Android library modules
// self-apply (no central publishable-module list anywhere in the build).
plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // NOTE: versions MIRROR sdk/android/gradle/libs.versions.toml. buildSrc
    // cannot easily consume the host's version catalog (the type-safe `libs`
    // accessors are not generated for precompiled script plugins); the four
    // values below must be kept in sync with libs.versions.toml manually.

    // [versions] agp — AGP plugin classes (for LibraryExtension accessors).
    implementation("com.android.tools.build:gradle:9.2.0")

    // [plugins] maven-publish. Plugin marker artifacts pull the implementation
    // transitively. Both the full plugin ID (`com.vanniktech.maven.publish`) and
    // the base variant (`.base`) are referenced from buildSrc; both markers
    // point to the same underlying JAR.
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.32.0")
    implementation("com.vanniktech.maven.publish.base:com.vanniktech.maven.publish.base.gradle.plugin:0.32.0")

    // [plugins] bcv. Plugin marker brings BCV task types (KotlinApiBuildTask,
    // KotlinApiCompareTask) onto the buildSrc compile classpath.
    implementation("org.jetbrains.kotlinx.binary-compatibility-validator:org.jetbrains.kotlinx.binary-compatibility-validator.gradle.plugin:0.18.1")
}
