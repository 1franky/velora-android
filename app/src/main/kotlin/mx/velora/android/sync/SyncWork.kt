package mx.velora.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import mx.velora.android.VeloraApplication
import mx.velora.android.core.data.RefreshResult

/** Sincronización en segundo plano (07-android-application.md §4). Sin red o con la API fallando, reintenta. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when ((applicationContext as VeloraApplication).synchronizer.sync()) {
            RefreshResult.UPDATED, RefreshResult.SESSION_ENDED -> Result.success()
            RefreshResult.OFFLINE, RefreshResult.FAILED -> Result.retry()
        }

    companion object {
        private const val NAME = "sincronizacion"
        const val EVERY_HOURS = 6L

        /** Cada [EVERY_HOURS] horas con red y batería suficiente, mientras haya sesión. KEEP: no reinicia el ciclo. */
        fun schedule(context: Context) {
            if (!WorkManager.isInitialized()) return
            val request = PeriodicWorkRequestBuilder<SyncWorker>(EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Al cerrar sesión. En las pruebas de JVM WorkManager no existe y no hay nada que cancelar. */
        fun cancel(context: Context) {
            if (WorkManager.isInitialized()) WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
