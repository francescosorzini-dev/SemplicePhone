plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.semplicephone"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.semplicephone"
        minSdk = 29
        targetSdk = 36 // obbligatorio su Google Play dal 31/8/2026
        // Versione scritta qui (non calcolata in CI): F-Droid la legge da questo file e ricompila
        // lo stesso APK. A ogni release: versionName = tag senza «v», versionCode +10.
        versionCode = 210
        versionName = "0.9.2"

        // Valori predefiniti per la config remota (vuoti = da inserire nel telefono),
        // sovrascrivibili da gradle.properties o -P per build dedicate.
        val remoteUrl = (project.findProperty("semplice.remoteUrl") as String?) ?: ""
        val remotePubkey = (project.findProperty("semplice.remotePubkey") as String?) ?: ""
        buildConfigField("String", "REMOTE_URL", "\"$remoteUrl\"")
        buildConfigField("String", "REMOTE_PUBKEY", "\"$remotePubkey\"")
    }

    // standard: installabile dal browser, senza i permessi che Play Protect blocca sugli APK scaricati
    //           (ricezione SMS, lettura notifiche, installazione di APK).
    // completo: tutte le funzioni (comando SMS, pallini, aggiornamento silenzioso), via ADB o F-Droid.
    // play:     per Google Play. Niente invio di SMS (policy SMS di Play: l'SOS apre Messaggi già
    //           compilato), niente sveglie USE_EXACT_ALARM, aggiornamenti solo dal Play Store.
    // Stesso applicationId e stessa chiave: si passa da un'edizione all'altra installando sopra.
    flavorDimensions += "edizione"
    val releases = "https://github.com/francescosorzini-dev/SemplicePhone/releases/latest/download"
    productFlavors {
        create("standard") {
            dimension = "edizione"
            buildConfigField("String", "EDITION", "\"standard\"")
            buildConfigField("boolean", "FULL", "false")
            buildConfigField("boolean", "SEND_SMS", "true")
            val u = (project.findProperty("semplice.updateUrl") as String?) ?: "$releases/update.json"
            buildConfigField("String", "UPDATE_URL", "\"$u\"")
        }
        create("completo") {
            dimension = "edizione"
            buildConfigField("String", "EDITION", "\"completo\"")
            buildConfigField("boolean", "FULL", "true")
            buildConfigField("boolean", "SEND_SMS", "true")
            val u = (project.findProperty("semplice.updateUrlFull") as String?) ?: "$releases/update-completo.json"
            buildConfigField("String", "UPDATE_URL", "\"$u\"")
        }
        create("play") {
            dimension = "edizione"
            buildConfigField("String", "EDITION", "\"play\"")
            buildConfigField("boolean", "FULL", "false")
            buildConfigField("boolean", "SEND_SMS", "false")
            buildConfigField("String", "UPDATE_URL", "\"\"")
        }
    }

    // Il blocco «dipendenze» firmato da Google non serve nell'APK (F-Droid lo rifiuta);
    // resta nell'AAB per la Play Console.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    // Firma release da variabili d'ambiente (secrets GitHub); senza, l'APK release resta non firmato.
    val keystorePath = System.getenv("SIGNING_KEYSTORE")
    if (keystorePath != null && file(keystorePath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                // trim(): uno spazio copiato per errore nei secrets non deve rompere la firma.
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")?.trim()
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.trim()
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Lingue dell'app: Android 13+ mostra «Lingua dell'app» nelle impostazioni di sistema.
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Nessun toolchain fisso: si compila con il JDK in uso (21, come il buildserver di F-Droid,
// così la build resta riproducibile) e il bytecode resta Java 17.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
