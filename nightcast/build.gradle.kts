// AirPlay receiver for the head unit: an iPhone mirrors its screen and plays audio to this app.
// The protocol core is UxPlay's C library (GPL-3.0), built with the NDK; see src/main/cpp.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nithin.nightcast"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.nithin.nightcast"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Head units are mostly 32- or 64-bit ARM; x86_64 is for the emulator on Intel hosts.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // The prebuilt OpenSSL package insists on the shared C++ runtime.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // Two editions of the same app. "public" is exactly what is in the repository. "personal"
    // adds whatever is in src/personal, a folder git ignores, so it can hold things that must
    // not be published; without that folder the two editions are identical.
    flavorDimensions += "edition"
    productFlavors {
        create("public") { dimension = "edition" }
        create("personal") { dimension = "edition" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.android.ndk.thirdparty:openssl:1.1.1q-beta-1")
    // Plays streams that apps cast with their AirPlay button.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
