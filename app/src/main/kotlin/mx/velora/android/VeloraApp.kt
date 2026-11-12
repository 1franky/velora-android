package mx.velora.android

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mx.velora.android.activities.ActivitiesScreen
import mx.velora.android.activities.ActivitiesViewModel
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.session.SessionManager
import mx.velora.android.home.HomeScreen
import mx.velora.android.home.HomeViewModel
import mx.velora.android.core.session.SessionState
import mx.velora.android.core.session.SignOutReason
import mx.velora.android.login.LoginScreen
import mx.velora.android.login.LoginViewModel
import mx.velora.android.settings.SettingsScreen

@Serializable data object Home

@Serializable data object Activities

@Serializable data object Performance

@Serializable data object Settings

/** Secciones de la barra inferior, en el mismo orden que la web. */
private enum class Section(val route: Any, @StringRes val label: Int, @StringRes val body: Int?, val icon: ImageVector) {
    HOME(Home, R.string.nav_home, null, VeloraIcons.Home),
    ACTIVITIES(Activities, R.string.nav_activities, null, VeloraIcons.Activities),
    PERFORMANCE(Performance, R.string.nav_performance, R.string.performance_body, VeloraIcons.Performance),
    SETTINGS(Settings, R.string.nav_settings, null, VeloraIcons.Settings),
}

/** Lo que se ve según la sesión: el login sin ella, la app con ella (y nada mientras se lee al abrir). */
@Composable
fun VeloraRoot(
    session: SessionManager,
    allowCleartext: Boolean,
    dashboard: DashboardRepository,
    activities: ActivitiesRepository,
) {
    val state by session.state.collectAsStateWithLifecycle()
    when (val current = state) {
        SessionState.Restoring -> Surface(modifier = Modifier.fillMaxSize()) {}
        is SessionState.SignedOut -> {
            val defaultServer = stringResource(R.string.default_server_url)
            val viewModel = viewModel { LoginViewModel(session, allowCleartext, defaultServer) }
            LoginScreen(viewModel, sessionExpired = current.reason == SignOutReason.EXPIRED)
        }
        is SessionState.SignedIn -> {
            val scope = rememberCoroutineScope()
            VeloraApp(
                current,
                onLogout = { scope.launch { session.logout() } },
                home = { HomeScreen(viewModel { HomeViewModel(dashboard) }) },
                activities = { ActivitiesScreen(viewModel { ActivitiesViewModel(activities) }) },
            )
        }
    }
}

/** Estructura de la app: barra inferior y una pantalla por sección (las llenan las siguientes entregas). */
@Composable
fun VeloraApp(
    account: SessionState.SignedIn,
    onLogout: () -> Unit,
    home: @Composable () -> Unit,
    activities: @Composable () -> Unit,
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val navLabel = stringResource(R.string.nav_label)
    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.semantics { paneTitle = navLabel }) {
                Section.entries.forEach { section ->
                    val selected = entry?.destination?.hasRoute(section.route::class) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(section.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(section.icon, contentDescription = null) },
                        label = { Text(stringResource(section.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Home, modifier = Modifier.padding(padding)) {
            composable<Home> { home() }
            composable<Activities> { activities() }
            composable<Performance> { ComingSoon(Section.PERFORMANCE) }
            composable<Settings> { SettingsScreen(account.email, account.serverUrl, onLogout) }
        }
    }
}

/** Sección aún no construida: dice qué contendrá, en palabras del usuario (como en la web). */
@Composable
private fun ComingSoon(section: Section) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(section.label),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.soon), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(checkNotNull(section.body)), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
