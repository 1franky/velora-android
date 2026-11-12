plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "mx.velora.android.core.database"
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
    // Las pruebas de migración leen los esquemas exportados.
    sourceSets["test"].assets.directories.add("$projectDir/schemas")
}

kotlin { jvmToolchain(17) }

// El esquema exportado entra al repositorio: así se revisa cada cambio y se prueban las migraciones.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    // Caché local (07-android-application.md): la API es la fuente de verdad.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
}
