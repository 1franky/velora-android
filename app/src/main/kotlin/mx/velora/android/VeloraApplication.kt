package mx.velora.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.ActivityDetailRepository
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.data.ImportsRepository
import mx.velora.android.core.data.PerformanceRepository
import mx.velora.android.core.data.Synchronizer
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionState
import mx.velora.android.core.session.createSessionStore
import mx.velora.android.imports.ImportIntake
import mx.velora.android.imports.ImportWorker
import mx.velora.android.sync.SyncWorker

/** Dependencias de la app, armadas a mano: son pocas y no justifican un framework de inyección. */
class VeloraApplication : Application() {

    /** Vive lo que el proceso: para trabajo que no debe cancelarse al cambiar de pantalla. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var session: SessionManager
        private set

    lateinit var dashboard: DashboardRepository
        private set

    lateinit var activities: ActivitiesRepository
        private set

    lateinit var details: ActivityDetailRepository
        private set

    lateinit var imports: ImportsRepository
        private set

    lateinit var synchronizer: Synchronizer
        private set

    lateinit var performance: PerformanceRepository
        private set

    val allowCleartext: Boolean get() = resources.getBoolean(R.bool.allow_cleartext)

    override fun onCreate() {
        super.onCreate()
        session = SessionManager(createSessionStore(this), appScope)
        val database = VeloraDatabase.create(this)
        dashboard = DashboardRepository(session, database.dashboard())
        activities = ActivitiesRepository(session, database.activities())
        details = ActivityDetailRepository(session)
        // Al importar algo, el historial guardado se actualiza para que la rodada nueva aparezca sin conexión.
        imports = ImportsRepository(session, database.imports(), onImported = { activities.syncPage(null) })
        synchronizer = Synchronizer(dashboard, activities, database.sync())
        performance = PerformanceRepository(session)
        appScope.launch {
            session.restore()
            // Sin sesión no quedan datos de la cuenta en el teléfono (ADR-023).
            session.state.collect { state ->
                if (state is SessionState.SignedIn) SyncWorker.schedule(this@VeloraApplication)
                if (state is SessionState.SignedOut) {
                    SyncWorker.cancel(this@VeloraApplication)
                    ImportWorker.cancel(this@VeloraApplication)
                    withContext(Dispatchers.IO) {
                        database.clearAllTables()
                        // Las copias de archivos que esperaban subirse tampoco se quedan.
                        ImportIntake.directory(this@VeloraApplication).deleteRecursively()
                    }
                }
            }
        }
    }
}
