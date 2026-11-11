plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "mx.velora.android.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency", "ObsoleteLintCustomCheck")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(17) }

/** Repositorios: combinan la API (fuente de verdad) con la caché de Room. */
dependencies {
    api(project(":core:session"))
    api(project(":core:database"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
