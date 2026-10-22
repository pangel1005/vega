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
        // La versión optimizada es la de uso diario: la de depuración es varias veces más lenta
        // con Compose (sin R8 y con comprobaciones extra).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Misma firma que la de depuración: así se actualiza encima sin desinstalar.
            // PROTOTIPO: para repartirla a otras personas habría que crear una clave propia.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Las pruebas de captura dibujan la pantalla en el PC (Robolectric) con las fuentes reales.
            isIncludeAndroidResources = true
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
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
    // Capturas de pantalla en el PC (sin teléfono): ver el diseño antes de instalarlo.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
