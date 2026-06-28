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
    // Versions come from the HOST version catalog (sdk/android/gradle/
    // libs.versions.toml), declared for buildSrc via buildSrc/settings.gradle.kts.
    // No version literals here — single source of truth is libs.versions.toml.
    // Coordinates are plugin-marker artifacts (pull the implementation JAR
    // transitively); buildSrc needs the plugin classes on its compile classpath
    // so the convention plugin can apply them and reference their types.

    // AGP — LibraryExtension accessors.
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")

    // vanniktech maven-publish — full + base plugin markers (same JAR).
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:${libs.versions.mavenPublish.get()}")
    implementation("com.vanniktech.maven.publish.base:com.vanniktech.maven.publish.base.gradle.plugin:${libs.versions.mavenPublish.get()}")

    // BCV — KotlinApiBuildTask / KotlinApiCompareTask task types.
    implementation("org.jetbrains.kotlinx.binary-compatibility-validator:org.jetbrains.kotlinx.binary-compatibility-validator.gradle.plugin:${libs.versions.bcv.get()}")
}
