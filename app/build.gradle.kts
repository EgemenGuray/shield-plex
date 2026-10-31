plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val media3 = "1.8.0"

android {
    namespace = "app.plexhdr.shield"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.plexhdr.shield"
        minSdk = 28          // Shield TV on Android 9 (Shield Experience 8) and newer
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // The Shield is 64-bit ARM (Tegra X1+); arm64 only keeps the FFmpeg libraries small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // One fixed key, committed on purpose: a sideloaded app can only be updated in place by an APK signed with the
    // same key, so every CI build must use it. It protects nothing; don't reuse it for anything else.
    signingConfigs {
        create("sideload") {
            storeFile = file("plexhdr.keystore")
            storePassword = "plexhdr"
            keyAlias = "plexhdr"
            keyPassword = "plexhdr"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // FFmpeg audio decoders (TrueHD, DTS, DTS-HD MA, AC3, E-AC3...) for when a format can't be bitstreamed.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("com.google.zxing:core:3.5.3")
}
