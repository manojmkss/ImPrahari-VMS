plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.imprahari.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.imprahari.app"
        // 26 (Android 8.0): java.util.Base64 everywhere, and hardware HEVC
        // decode is all but universal from here on.
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-extractor:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Alerts without Google services: a UnifiedPush distributor app (e.g. ntfy)
    // delivers the NVR's Web Push messages; the connector decrypts them.
    // 3.0.x: the newest line built with our Kotlin (2.0); 3.1+ needs Kotlin 2.2.
    implementation("org.unifiedpush.android:connector:3.0.10")

    testImplementation("junit:junit:4.13.2")
    // A real HTTPS server with a self-signed certificate, for the connect/trust flow.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
