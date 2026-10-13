// Emulator-only helper: publishes a fake "now playing" media session so the launcher's
// Now Playing widget can be checked without signing in to a music app. Not part of the launcher.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nithin.mediatest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nithin.mediatest"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
