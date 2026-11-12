package mx.velora.android

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.ImportsRepository
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import mx.velora.android.imports.ImportScreen
import mx.velora.android.imports.ImportViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX-w411dp-h1600dp")
class ImportScreenTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val dir: File = Files.createTempDirectory("importar").toFile()
    private val received = CopyOnWriteArrayList<Uri>()
    @Volatile private var opened: String? = null

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when (request.url.encodedPath) {
            "/api/v1/auth/refresh" -> respond(
                """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}""",
                HttpStatusCode.OK,
                json,
            )
            "/api/v1/bikes" -> respond(
                """{"items":[{"id":"b1a1c0de-0000-4000-8000-000000000001","name":"Gravel de diario","bikeType":"GRAVEL",
                    "brand":null,"model":null,"weightKg":null,"active":true,"createdAt":"2026-01-01T00:00:00Z"}]}""",
                HttpStatusCode.OK,
                json,
            )
            "/api/v1/activities/imports" -> respond(
                """{"jobId":"11111111-1111-4111-8111-111111111111","status":"QUEUED","statusUrl":"/api/v1/imports/x"}""",
                HttpStatusCode.Accepted,
                json,
            )
            else -> respond(
                """{"jobId":"11111111-1111-4111-8111-111111111111","kind":"IMPORT","status":"COMPLETED",
                    "activityId":"22222222-2222-4222-8222-222222222222","errorCode":null,"errorDetail":null,
                    "warnings":[{"code":"LONG_PAUSES","affectedCount":2}],"possibleDuplicateActivityIds":[]}""",
                HttpStatusCode.OK,
                json,
            )
        }
    }

    private val session = SessionManager(
        store = object : SessionStore {
            var saved: StoredSession? = StoredSession("http://servidor", "propietario@example.com", "r0")
            override suspend fun read() = saved
            override suspend fun write(session: StoredSession) { saved = session }
            override suspend fun clear() { saved = null }
            override suspend fun lastServerUrl() = "http://servidor"
        },
        appScope = scope,
        apiFor = { VeloraApi.create(it, engine) },
    ).also { runBlocking { it.restore() } }

    private val imports = ImportsRepository(session, db.imports(), pollDelayMs = 0, maxPolls = 3)
    private val activities = ActivitiesRepository(session, db.activities())

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** En lugar de copiar desde otra app: un archivo por URI recibida, con su nombre. */
    private suspend fun intake(uris: List<Uri>, bike: java.util.UUID?) {
        received += uris
        uris.forEach { uri ->
            val name = uri.lastPathSegment!!
            val file = File(dir, name).apply { writeText("datos") }
            imports.add(name, file, file.length(), bike)
        }
    }

    private fun start(shared: List<Uri> = emptyList()) = compose.setContent {
        VeloraTheme {
            ImportScreen(
                viewModel { ImportViewModel(imports, activities, ::intake) },
                onBack = {},
                onOpenActivity = { opened = it },
                shared = shared,
            )
        }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun sharedFilesEnterTheQueueAndShowTheirProgress() {
        start(shared = listOf(Uri.parse("content://otra.app/vuelta.fit"), Uri.parse("content://otra.app/foto.jpg")))

        waitForText("vuelta.fit")
        waitForText("Solo se aceptan archivos .fit, .gpx o .tcx.")
        compose.onNodeWithText("En espera", useUnmergedTree = true).assertExists()
        waitForText("1 de 2 archivos procesados")
        assertEquals(2, received.size)

        // Lo que haría WorkManager con red.
        runBlocking { imports.process() }

        waitForText("Importada")
        waitForText("Se detectaron 2 pausas largas; no cuentan como tiempo en movimiento.")
        waitForText("2 de 2 archivos procesados")
        compose.onNodeWithText("Ver actividad").performClick()
        assertEquals("22222222-2222-4222-8222-222222222222", opened)

        compose.onNodeWithText("Limpiar lista").performClick()
        waitForText("Aún no eliges archivos.")
    }

    @Test
    fun offersTheBikesForTheFiles() {
        start()
        waitForText("Gravel de diario")
        compose.onNodeWithText("Sin bicicleta").assertExists()
        assertTrue(received.isEmpty())
    }
}
