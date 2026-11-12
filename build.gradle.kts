// Versiones en gradle/libs.versions.toml. Los módulos aplican lo que necesitan.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.openapi.generator) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
