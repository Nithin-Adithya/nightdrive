plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nithin.nightdrive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nithin.nightdrive"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Two editions of the same app. "public" is exactly what is in the repository. "personal"
    // adds whatever is in src/personal, a folder git ignores, so it can hold things that must
    // not be published; without that folder the two editions are identical.
    flavorDimensions += "edition"
    productFlavors {
        create("public") { dimension = "edition" }
        create("personal") { dimension = "edition" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The AirPlay-compatible receiver, shipped inside the launcher's APK. It runs in a process
    // of its own (see its manifest), so its native code cannot take the home screen down.
    implementation(project(":nightcast"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
