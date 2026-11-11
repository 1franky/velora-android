package mx.velora.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.createSessionStore

/** Dependencias de la app, armadas a mano: son pocas y no justifican un framework de inyección. */
class VeloraApplication : Application() {

    /** Vive lo que el proceso: para trabajo que no debe cancelarse al cambiar de pantalla. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var session: SessionManager
        private set

    val allowCleartext: Boolean get() = resources.getBoolean(R.bool.allow_cleartext)

    override fun onCreate() {
        super.onCreate()
        session = SessionManager(createSessionStore(this), appScope)
        appScope.launch { session.restore() }
    }
}
