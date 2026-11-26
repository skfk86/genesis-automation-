plugins {
    id("com.android.application")
}

// A deliberately simple chat app used only to test the UI engine on an emulator.
// It is a separate APK (different package), so the engine drives it like any other app.
android {
    namespace = "com.genesis.fixture.chat"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.genesis.fixture.chat"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
