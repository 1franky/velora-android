package mx.velora.android.core.data

import io.ktor.client.request.forms.FormPart
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.streams.asInput
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import mx.velora.android.api.models.ImportJob
import mx.velora.android.core.database.ImportDao
import mx.velora.android.core.database.ImportEntity
import mx.velora.android.core.session.SessionExpiredException
import mx.velora.android.core.session.SessionManager

/** Estado de un archivo en la cola. Los de la API llevan el mismo nombre que en el contrato. */
enum class ImportState(val terminal: Boolean) {
    /** Copiado en el teléfono, esperando subir (también tras un fallo de red). */
    WAITING(false),
    UPLOADING(false),
    QUEUED(false),
    PROCESSING(false),
    COMPLETED(true),
    DUPLICATE(true),
    REJECTED(true),
    FAILED(true),
}

/** Motivos que decide la app (los de la API llegan tal cual en `errorCode`). */
object LocalImportError {
    const val TOO_LARGE = "TOO_LARGE"
    const val WRONG_EXTENSION = "WRONG_EXTENSION"
    /** 400 de la API al subir. */
    const val INVALID = "INVALID"
    const val UNREADABLE = "UNREADABLE"
}

/** Qué debe hacer el trabajo en segundo plano después de una pasada. */
enum class ImportRun { DONE, RETRY }

/**
 * Cola de importación (07-android-application.md §4). La API es la fuente de verdad y deduplica por SHA-256 del
 * archivo (ADR-009): si un reintento sube dos veces el mismo archivo, el segundo trabajo termina en DUPLICATE y no
 * crea otra rodada. Por eso se puede reintentar sin miedo.
 */
class ImportsRepository(
    private val session: SessionManager,
    private val dao: ImportDao,
    /** Al terminar alguna importación se actualiza el historial guardado. */
    private val onImported: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val pollDelayMs: Long = 2_000,
    private val maxPolls: Int = 30,
) {
    fun queue(): Flow<List<ImportEntity>> = dao.observeAll()

    /**
     * Agrega un archivo ya copiado en [file] (null si no se copió: inválido o ilegible). La validación local, extensión
     * y tamaño, se hace aquí.
     */
    suspend fun add(fileName: String, file: File?, sizeBytes: Long, bikeId: UUID?) {
        val error = when {
            !hasValidExtension(fileName) -> LocalImportError.WRONG_EXTENSION
            sizeBytes > MAX_BYTES -> LocalImportError.TOO_LARGE
            file == null -> LocalImportError.UNREADABLE
            else -> null
        }
        if (error != null) file?.delete()
        dao.upsert(
            ImportEntity(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                sizeBytes = sizeBytes,
                localPath = file?.takeIf { error == null }?.absolutePath,
                bikeId = bikeId?.toString(),
                state = if (error == null) ImportState.WAITING.name else ImportState.REJECTED.name,
                errorCode = error,
                createdAtEpochMs = now(),
            ),
        )
    }

    /** Quita de la lista lo que ya terminó. */
    suspend fun clearFinished() = dao.deleteWithStates(ImportState.entries.filter { it.terminal }.map { it.name })

    /**
     * Una pasada del trabajo en segundo plano: sube lo que espera y consulta lo que la API está procesando. Sin red o
     * con la API caída, pide reintentar (WorkManager espera cada vez más).
     */
    suspend fun process(): ImportRun {
        // Un UPLOADING que quedó a medias (la app se cerró) vuelve a subirse: la API lo deduplica.
        dao.withStates(listOf(ImportState.UPLOADING.name)).forEach { dao.upsert(it.copy(state = ImportState.WAITING.name)) }
        var imported = false
        try {
            for (item in dao.withStates(listOf(ImportState.WAITING.name))) {
                if (upload(item) == ImportRun.RETRY) return ImportRun.RETRY
            }
            repeat(maxPolls) { round ->
                val pending = dao.withStates(listOf(ImportState.QUEUED.name, ImportState.PROCESSING.name))
                if (pending.isEmpty()) return ImportRun.DONE
                for (item in pending) {
                    val result = poll(item) ?: return ImportRun.RETRY
                    if (result == ImportState.COMPLETED) imported = true
                }
                if (round < maxPolls - 1 && dao.withStates(listOf(ImportState.QUEUED.name, ImportState.PROCESSING.name)).isNotEmpty()) {
                    delay(pollDelayMs)
                }
            }
            // La API sigue procesando: se consulta de nuevo más tarde.
            return ImportRun.RETRY
        } catch (_: SessionExpiredException) {
            // Sin sesión no hay a quién subir; al cerrar sesión la cola se borra.
            return ImportRun.DONE
        } finally {
            if (imported) runCatching { onImported() }
        }
    }

    private suspend fun upload(item: ImportEntity): ImportRun {
        val file = item.localPath?.let(::File)?.takeIf { it.isFile }
        if (file == null) {
            dao.upsert(item.copy(state = ImportState.REJECTED.name, errorCode = LocalImportError.UNREADABLE, localPath = null))
            return ImportRun.DONE
        }
        dao.upsert(item.copy(state = ImportState.UPLOADING.name))
        val response = try {
            session.call {
                importActivity(
                    file = FormPart(
                        "file",
                        InputProvider(file.length()) { file.inputStream().asInput() },
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, item.fileName).toString())
                        },
                    ),
                    bikeId = item.bikeId?.let(UUID::fromString),
                    deviceId = null,
                    activityTitle = null,
                )
            }
        } catch (e: CancellationException) {
            dao.upsert(item.copy(state = ImportState.WAITING.name))
            throw e
        } catch (e: SessionExpiredException) {
            dao.upsert(item.copy(state = ImportState.WAITING.name))
            throw e
        } catch (_: IOException) {
            dao.upsert(item.copy(state = ImportState.WAITING.name))
            return ImportRun.RETRY
        }
        when {
            response.status == 202 -> {
                val accepted = response.body()
                file.delete()
                dao.upsert(item.copy(state = ImportState.QUEUED.name, jobId = accepted.jobId.toString(), localPath = null))
            }
            response.status == 413 -> finish(item, file, LocalImportError.TOO_LARGE)
            response.status == 400 || response.status == 404 -> finish(item, file, LocalImportError.INVALID)
            else -> {
                // 5xx: la API tiene un problema pasajero.
                dao.upsert(item.copy(state = ImportState.WAITING.name))
                return ImportRun.RETRY
            }
        }
        return ImportRun.DONE
    }

    private suspend fun finish(item: ImportEntity, file: File, error: String) {
        file.delete()
        dao.upsert(item.copy(state = ImportState.REJECTED.name, errorCode = error, localPath = null))
    }

    /** Estado nuevo, o null si no se pudo consultar (sin red). */
    private suspend fun poll(item: ImportEntity): ImportState? {
        val jobId = item.jobId?.let(UUID::fromString) ?: return ImportState.FAILED.also {
            dao.upsert(item.copy(state = ImportState.FAILED.name, errorCode = "INTERNAL_ERROR"))
        }
        val response = try {
            session.call { getImportJob(jobId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: IOException) {
            return null
        }
        if (!response.success) return if (response.status == 404) ImportState.FAILED.also {
            dao.upsert(item.copy(state = ImportState.FAILED.name, errorCode = "INTERNAL_ERROR"))
        } else null
        val job = response.body()
        val state = when (job.status) {
            ImportJob.Status.QUEUED -> ImportState.QUEUED
            ImportJob.Status.PROCESSING -> ImportState.PROCESSING
            ImportJob.Status.COMPLETED -> ImportState.COMPLETED
            ImportJob.Status.DUPLICATE -> ImportState.DUPLICATE
            ImportJob.Status.REJECTED -> ImportState.REJECTED
            ImportJob.Status.FAILED -> ImportState.FAILED
        }
        dao.upsert(
            item.copy(
                state = state.name,
                activityId = job.activityId?.toString(),
                errorCode = job.errorCode,
                warnings = job.warnings.joinToString(",") { "${it.code.value}:${it.affectedCount}" }.ifEmpty { null },
                possibleDuplicates = job.possibleDuplicateActivityIds.size,
            ),
        )
        return state
    }

    companion object {
        /** El mismo límite que la API (`velora.storage.max-file-size`). */
        const val MAX_BYTES = 50L * 1024 * 1024

        fun hasValidExtension(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in setOf("fit", "gpx", "tcx")
    }
}
