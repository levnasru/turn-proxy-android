package com.freeturn.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.freeturn.app.ui.screens.clientsetup.ClientSetupScreen
import com.freeturn.app.ui.screens.connectionmode.ConnectionModeScreen
import com.freeturn.app.ui.screens.serverdetail.ServerDetailScreen
import com.freeturn.app.ui.screens.settings.AboutScreen
import com.freeturn.app.ui.screens.settings.AdvancedScreen
import com.freeturn.app.ui.screens.settings.AppScreen
import com.freeturn.app.ui.screens.settings.NerdScreen
import com.freeturn.app.ui.screens.settings.ServersListScreen
import com.freeturn.app.ui.screens.settings.SettingsScreen
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** Вкладка "Настройки": Настройки -> Серверы -> [сервер] -> подключение/режим. */
internal fun NavGraphBuilder.settingsGraph(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel
) {
    navigation<SettingsGraph>(startDestination = Settings) {
        composable<Settings> {
            SettingsScreen(
                onOpenServers = { navController.navigate(ServersList) },
                onOpenApp = { navController.navigate(AppSettings) },
                onOpenAdvanced = { navController.navigate(Advanced) },
                onOpenAbout = { navController.navigate(About) }
            )
        }

        composable<AppSettings> {
            AppScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<About> {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable<Advanced> {
            AdvancedScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ServersList> {
            ServersListScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenServer = { id -> navController.navigate(ServerDetail(id)) }
            )
        }

        composable<ServerDetail> { entry ->
            val id = entry.toRoute<ServerDetail>().serverId
            ServerDetailScreen(
                serverId = id,
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenConnection = { navController.navigate(ClientSetup(id)) },
                onOpenConnectionMode = { navController.navigate(ConnectionMode(id)) },
                onOpenNerdInfo = { navController.navigate(NerdInfo(id)) },
                onCloned = { newId -> navController.navigate(ServerDetail(newId)) }
            )
        }

        composable<NerdInfo> { entry ->
            val id = entry.toRoute<NerdInfo>().serverId
            NerdScreen(
                serverId = id,
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ConnectionMode> { entry ->
            val id = entry.toRoute<ConnectionMode>().serverId
            ConnectionModeScreen(
                settingsViewModel = settingsViewModel,
                proxyViewModel = proxyViewModel,
                serverId = id,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ClientSetup> { entry ->
            val id = entry.toRoute<ClientSetup>().serverId
            ClientSetupScreen(
                settingsViewModel = settingsViewModel,
                serverId = id,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
