pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "genesis"

// Pure-Kotlin engine modules. They build and test on any JVM.
include(
    ":core:model",
    ":automation:ui",
)

// Android modules need an Android SDK. CI always builds them. Locally they are skipped
// (with a warning) when no SDK is configured, so the engine can still be tested.
val sdkFromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val localProps = file("local.properties")
val sdkFromLocal = if (localProps.exists()) {
    localProps.readLines().firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
} else null
val androidSdkAvailable = listOfNotNull(sdkFromEnv, sdkFromLocal).any { file(it).isDirectory }
val forceAndroid = providers.gradleProperty("genesis.requireAndroid").orNull == "true"

if (androidSdkAvailable || forceAndroid) {
    include(":app", ":fixtures:chat")
} else {
    logger.warn("GENESIS: Android SDK not found; building engine (JVM) modules only.")
}
