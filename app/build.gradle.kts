import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Release signing is read from keystore.properties at the repo root (gitignored, never committed).
// See PLAY_STORE_CHECKLIST.md for how to generate the keystore + fill this file.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.agani.syncup"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agani.syncup"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "4"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the release key only when keystore.properties is present (e.g. on your
            // build machine / CI); otherwise the build stays unsigned so CI without secrets still works.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7") // whole-app foreground/background for app-lock
    implementation("androidx.activity:activity-compose:1.9.3")
    // Background reminder sync (daily safety-net + FCM-triggered re-sync)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // WebView compatibility helpers (dark mode, feature detection across API levels)
    implementation("androidx.webkit:webkit:1.11.0")
    // Chrome Custom Tabs (open external "new tab" links in the real browser)
    implementation("androidx.browser:browser:1.8.0")

    // Networking (talks to the login API — mock-backed until the backend is live)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Secure token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometric (fingerprint/face) app lock — also provides FragmentActivity
    implementation("androidx.biometric:biometric:1.1.0")
    // Force a modern Fragment so FragmentActivity works with the Activity Result APIs
    // (fixes "Can only use lower 16 bits for requestCode" crash on permission requests).
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Firebase — Cloud Messaging (push) + Remote Config (dynamic base URL)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-config")

    // Location settings check — powers the in-app "Turn on location (GPS)" dialog
    // for web pages that request geolocation.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
