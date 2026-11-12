package mx.velora.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionState
import mx.velora.android.core.session.createSessionStore

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

    val allowCleartext: Boolean get() = resources.getBoolean(R.bool.allow_cleartext)

    override fun onCreate() {
        super.onCreate()
        session = SessionManager(createSessionStore(this), appScope)
        val database = VeloraDatabase.create(this)
        dashboard = DashboardRepository(session, database.dashboard())
        activities = ActivitiesRepository(session, database.activities())
        appScope.launch {
            session.restore()
            // Sin sesión no quedan datos de la cuenta en el teléfono (ADR-023).
            session.state.collect { state ->
                if (state is SessionState.SignedOut) withContext(Dispatchers.IO) { database.clearAllTables() }
            }
        }
    }
}
