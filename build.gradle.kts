// All Gradle plugins must live in one classloader (the Kotlin plugin needs to see AGP's classes).
// AGP is only put on the classpath when the Android modules are part of the build, so the engine
// (JVM) modules still configure and test on machines without the Android toolchain.
buildscript {
    val androidEnabled = findProject(":app") != null
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (androidEnabled) classpath(libs.android.gradlePlugin)
        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.kotlin.serializationPlugin)
        classpath(libs.kotlin.composePlugin)
    }
}
