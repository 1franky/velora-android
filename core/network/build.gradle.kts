import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

android {
    namespace = "mx.velora.android.core.network"
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
}

/**
 * Cliente de la API generado del contrato fijado en contract/ (ADR-011): si el contrato cambia y el código no, no
 * compila. Ktor + kotlinx.serialization; las fechas, con java.time (minSdk 31).
 */
val generatedApi = layout.buildDirectory.dir("generated/openapi")

val generateApi by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(rootProject.layout.projectDirectory.file("contract/openapi-v1.yaml").asFile.path)
    outputDir.set(generatedApi.get().asFile.path)
    packageName.set("mx.velora.android.api")
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "java8",
            "enumUnknownDefaultCase" to "true",
            "sourceFolder" to "src/main/kotlin",
            "omitGradleWrapper" to "true",
        ),
    )
    // Números como Double (kotlinx.serialization no serializa BigDecimal) y el enum entero de privacidad como Int (el
    // generador lo emite mal: valores Int en un enum de String).
    typeMappings.set(mapOf("number" to "kotlin.Double", "decimal" to "kotlin.Double"))
    schemaMappings.set(mapOf("TrackPrivacyMeters" to "kotlin.Int"))
    cleanupOutput.set(true)
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

kotlin {
    jvmToolchain(17)
    // La subida multipart generada usa una API que Ktor marca como interna; solo en este módulo.
    compilerOptions.optIn.add("io.ktor.utils.io.InternalAPI")
}

// AGP 9 compila Kotlin por sí mismo: el código generado se agrega con la Variant API, y se genera antes de compilar.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(generatedApi.get().dir("src/main/kotlin").asFile.path)
    }
}
tasks.named("preBuild") { dependsOn(generateApi) }

dependencies {
    api(libs.ktor.client.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
