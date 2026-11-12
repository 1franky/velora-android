package mx.velora.android.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportsRepositoryTest {

    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val dir: File = Files.createTempDirectory("importaciones").toFile()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val uploads = CopyOnWriteArrayList<String>()

    /** Estado que reporta la API para cada trabajo, en orden de consulta. */
    private var jobStatuses = ArrayDeque(listOf("PROCESSING", "COMPLETED"))
    private var uploadAnswer: () -> HttpStatusCode = { HttpStatusCode.Accepted }
    private var offline = false
    private var imported = 0

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        if (path == "/api/v1/auth/refresh") {
            return@MockEngine respond(
                """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}""",
                HttpStatusCode.OK,
                json,
            )
        }
        if (offline) throw IOException("sin red")
        when (path) {
            "/api/v1/activities/imports" -> {
                uploads += request.body.toByteArray().decodeToString()
                val status = uploadAnswer()
                if (status == HttpStatusCode.Accepted) {
                    respond(
                        """{"jobId":"11111111-1111-4111-8111-111111111111","status":"QUEUED","statusUrl":"/api/v1/imports/11111111-1111-4111-8111-111111111111"}""",
                        status,
                        json,
                    )
                } else {
                    respond("""{"status":${status.value},"title":"x"}""", status, json)
                }
            }
            else -> {
                val status = jobStatuses.removeFirstOrNull() ?: "COMPLETED"
                val activity = if (status == "COMPLETED" || status == "DUPLICATE") "\"22222222-2222-4222-8222-222222222222\"" else "null"
                val error = if (status == "REJECTED") "\"MALFORMED_FILE\"" else "null"
                respond(
                    """{"jobId":"11111111-1111-4111-8111-111111111111","kind":"IMPORT","status":"$status","activityId":$activity,
                        "errorCode":$error,"errorDetail":null,"warnings":[{"code":"GPS_JUMPS","affectedCount":2}],
                        "possibleDuplicateActivityIds":[]}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
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
        appScope = CoroutineScope(Dispatchers.Unconfined),
        apiFor = { VeloraApi.create(it, engine) },
    ).also { runBlocking { it.restore() } }

    private val repository = ImportsRepository(session, db.imports(), onImported = { imported++ }, pollDelayMs = 0, maxPolls = 3)

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String = "contenido de prueba") = File(dir, name).apply { writeText(content) }

    private suspend fun only() = repository.queue().first().single()

    @Test
    fun uploadsWaitsForTheApiAndForgetsTheCopy() = runTest {
        val copy = file("vuelta.fit")
        repository.add("vuelta.fit", copy, copy.length(), bikeId = null)

        assertEquals(ImportRun.DONE, repository.process())

        val item = only()
        assertEquals(ImportState.COMPLETED.name, item.state)
        assertEquals("22222222-2222-4222-8222-222222222222", item.activityId)
        assertEquals("GPS_JUMPS:2", item.warnings)
        assertNull(item.localPath)
        assertFalse("la copia se borra al subir", copy.exists())
        assertTrue(uploads.single().contains("filename=vuelta.fit"))
        assertEquals(1, imported)
    }

    @Test
    fun withoutNetworkItWaitsAndRetriesLater() = runTest {
        val copy = file("vuelta.gpx")
        repository.add("vuelta.gpx", copy, copy.length(), bikeId = null)
        offline = true

        assertEquals(ImportRun.RETRY, repository.process())
        assertEquals(ImportState.WAITING.name, only().state)
        assertTrue(copy.exists())

        offline = false
        assertEquals(ImportRun.DONE, repository.process())
        assertEquals(ImportState.COMPLETED.name, only().state)
    }

    @Test
    fun aRetriedUploadEndsAsDuplicateNotAsASecondRide() = runTest {
        val copy = file("vuelta.tcx")
        repository.add("vuelta.tcx", copy, copy.length(), bikeId = null)
        jobStatuses = ArrayDeque(listOf("DUPLICATE"))

        repository.process()

        assertEquals(ImportState.DUPLICATE.name, only().state)
        assertEquals("22222222-2222-4222-8222-222222222222", only().activityId)
        assertEquals(0, imported)
    }

    @Test
    fun rejectionsAreKeptWithTheirReason() = runTest {
        val bad = file("dañado.fit")
        repository.add("dañado.fit", bad, bad.length(), bikeId = null)
        jobStatuses = ArrayDeque(listOf("REJECTED"))
        repository.process()
        assertEquals("MALFORMED_FILE", only().errorCode)

        repository.clearFinished()
        val big = file("grande.fit")
        repository.add("grande.fit", big, big.length(), bikeId = null)
        uploadAnswer = { HttpStatusCode.PayloadTooLarge }
        repository.process()
        assertEquals(LocalImportError.TOO_LARGE, only().errorCode)
        assertFalse(big.exists())
    }

    @Test
    fun checksExtensionAndSizeBeforeUploading() = runTest {
        val photo = file("foto.jpg")
        repository.add("foto.jpg", photo, photo.length(), bikeId = null)
        val huge = file("enorme.fit")
        repository.add("enorme.fit", huge, ImportsRepository.MAX_BYTES + 1, bikeId = null)

        repository.process()

        val items = repository.queue().first()
        assertEquals(listOf(LocalImportError.WRONG_EXTENSION, LocalImportError.TOO_LARGE), items.map { it.errorCode })
        assertTrue(items.all { it.state == ImportState.REJECTED.name })
        assertTrue(uploads.isEmpty())
        assertFalse(photo.exists())
    }

    @Test
    fun aServerStillProcessingIsAskedAgainLater() = runTest {
        val copy = file("larga.fit")
        repository.add("larga.fit", copy, copy.length(), bikeId = null)
        jobStatuses = ArrayDeque(List(10) { "PROCESSING" })

        assertEquals(ImportRun.RETRY, repository.process())
        assertEquals(ImportState.PROCESSING.name, only().state)
        assertEquals(1, uploads.size)
    }
}
