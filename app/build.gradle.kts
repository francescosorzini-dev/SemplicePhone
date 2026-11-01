plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.semplicephone"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.semplicephone"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Valori predefiniti per la config remota, sovrascrivibili da gradle.properties o -P.
        val remoteUrl = (project.findProperty("semplice.remoteUrl") as String?)
            ?: "https://example.com/semplicephone/?device={device}"
        val remotePubkey = (project.findProperty("semplice.remotePubkey") as String?) ?: ""
        buildConfigField("String", "REMOTE_URL", "\"$remoteUrl\"")
        buildConfigField("String", "REMOTE_PUBKEY", "\"$remotePubkey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Solo la verifica Ed25519 (API di sistema solo da Android 13); R8 toglie il resto.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}
