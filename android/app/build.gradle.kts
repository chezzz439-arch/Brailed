plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.compose.screenshot")
}

android {
    namespace = "com.brailed.companion"
    compileSdk = 34
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "com.brailed.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // USB-serial transport (CP2102/CP210x) — wired alternative to BLE.
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    // Offline speech-to-text for live captions (Apache-2.0). The language model
    // is downloaded at runtime (see VoskModelProvider), not bundled in the APK.
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Compose preview screenshot testing (renders @Preview to PNGs, no device).
    screenshotTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
