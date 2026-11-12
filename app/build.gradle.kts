import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Firma de release, como en nexora-android: en el CI llega por variables de entorno (GitHub Secrets, ver
 * .github/workflows/release.yml); en local, de `keystore.properties` (gitignored, ver keystore.properties.example). Sin
 * ninguna de las dos, solo falla `assembleRelease`; debug y pruebas no se ven afectados.
 */
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun releaseSigning(key: String, env: String): String? = System.getenv(env) ?: keystoreProperties.getProperty(key)

android {
    namespace = "mx.velora.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "mx.velora.android"
        // Android 12 o posterior (decisión del 11/11/2026).
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Solo es-MX por ahora: no empaquetar traducciones de las bibliotecas a otros idiomas.
        androidResources.localeFilters += listOf("es-rMX", "es")
        // MapLibre trae bibliotecas nativas por arquitectura: el APK publicado lleva solo la de los teléfonos de 64 bits
        // (ARM). La versión de desarrollo agrega x86_64 para emuladores en computadoras Intel.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            releaseSigning("storeFile", "KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = releaseSigning("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = releaseSigning("keyAlias", "KEY_ALIAS")
            keyPassword = releaseSigning("keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("x86_64") }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Cambian cada vez que sale una versión nueva: avisan, pero no rompen el CI (las actualizaciones van en PRs).
        // ObsoleteLintCustomCheck: chequeos de bibliotecas AndroidX desfasados respecto a esta versión de Lint.
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency", "ObsoleteLintCustomCheck")
        // Sin x86 a propósito: MapLibre pesa ~14 MB por arquitectura y Velora es para el teléfono del dueño (ADR-024).
        disable += "ChromeOsAbiSupport"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    // Mapa del recorrido sobre el mapa base de Velora (ADR-024); OkHttp para mandar el Bearer solo al host del mapa.
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.okhttp.mockwebserver)
}
