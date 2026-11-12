package mx.velora.android.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mx.velora.android.VeloraApplication
import mx.velora.android.core.data.ImportRun
import mx.velora.android.core.data.ImportsRepository

/** Sube la cola de importación con red; sin ella, o con la API caída, WorkManager reintenta con espera creciente. */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val imports = (applicationContext as VeloraApplication).imports
        return when (imports.process()) {
            ImportRun.DONE -> Result.success()
            ImportRun.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "importaciones"

        /** Encola una pasada; si ya hay una corriendo, esta va después y recoge los archivos nuevos. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ImportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Cancela lo pendiente al cerrar sesión. En el teléfono WorkManager ya está listo (lo inicia su ContentProvider
         * antes que la app); en las pruebas de JVM no existe, y entonces no hay nada que cancelar.
         */
        fun cancel(context: Context) {
            if (WorkManager.isInitialized()) WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

/**
 * Recibe archivos elegidos o compartidos: los copia al almacenamiento privado (el permiso sobre un archivo de otra app
 * caduca) y los pone en la cola. Nunca lee su contenido más allá de copiarlo.
 */
class ImportIntake(
    private val context: Context,
    private val imports: ImportsRepository,
    private val schedule: () -> Unit = { ImportWorker.schedule(context) },
) {
    suspend fun add(uris: List<Uri>, bikeId: UUID?) {
        withContext(Dispatchers.IO) {
            uris.forEach { uri ->
                val (name, size) = describe(uri)
                // Solo se copia lo que puede subirse; lo demás se rechaza sin leerlo. Si el tamaño no se conocía, se
                // revisa con la copia.
                val copy = if (ImportsRepository.hasValidExtension(name) && size <= ImportsRepository.MAX_BYTES) copy(uri, name) else null
                imports.add(name, copy, copy?.length() ?: size, bikeId)
            }
        }
        schedule()
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: uri.lastPathSegment.orEmpty()
                val size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "archivo") to -1L
    }

    private fun copy(uri: Uri, name: String): File? = runCatching {
        val target = File(directory(context), "${UUID.randomUUID()}.${name.substringAfterLast('.').lowercase()}")
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        target
    }.getOrNull()

    companion object {
        fun directory(context: Context): File = File(context.filesDir, "importaciones").apply { mkdirs() }
    }
}
