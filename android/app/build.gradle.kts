plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.asistente.personal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.asistente.personal"
        minSdk = 26
        targetSdk = 36
        // Minutos desde 2026-01-01: cada compilación es «más nueva» y la app puede actualizarse sola.
        versionCode = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000).toInt()
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation("junit:junit:4.13.2")
    // org.json real para las pruebas locales (el de Android es solo un esqueleto fuera del teléfono).
    testImplementation("org.json:json:20250517")
}
