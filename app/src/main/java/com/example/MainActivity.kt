package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.data.preferences.ForgeSettings
import com.example.core.data.preferences.ThemeMode
import com.example.ui.screens.apimanager.ApiManagerScreen
import com.example.ui.screens.apimanager.ApiManagerViewModel
import com.example.ui.screens.atelier.ForgeAtelierScreen
import com.example.ui.screens.atelier.ForgeAtelierViewModel
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.SettingsViewModel
import com.example.ui.screens.tools.ToolDetailScreen
import com.example.ui.screens.tools.ToolRunnerScreen
import com.example.ui.screens.tools.ToolsCatalogScreen
import com.example.ui.screens.tools.ToolsViewModel
import com.example.ui.theme.ForgeAITheme

object ForgeRoutes {
    const val ROUTE_ATELIER = "atelier"
    const val ROUTE_TOOLS = "tools"
    const val ROUTE_APIS = "apis"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_TOOL_RUNNER = "tool_runner/{toolId}"
    const val ROUTE_TOOL_DETAIL = "tool_detail/{toolId}"

    fun toolRunner(toolId: String): String = "tool_runner/$toolId"
    fun toolDetail(toolId: String): String = "tool_detail/$toolId"
}

private enum class TopNavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    ATELIER(
        route = ForgeRoutes.ROUTE_ATELIER,
        labelRes = R.string.nav_atelier,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "nav_atelier"
    ),
    TOOLS(
        route = ForgeRoutes.ROUTE_TOOLS,
        labelRes = R.string.nav_tools,
        selectedIcon = Icons.Filled.Extension,
        unselectedIcon = Icons.Outlined.Extension,
        testTag = "nav_tools"
    ),
    APIS(
        route = ForgeRoutes.ROUTE_APIS,
        labelRes = R.string.nav_apis,
        selectedIcon = Icons.Filled.VpnKey,
        unselectedIcon = Icons.Outlined.VpnKey,
        testTag = "nav_apis"
    ),
    SETTINGS(
        route = ForgeRoutes.ROUTE_SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ForgeApplication).container
        setContent {
            val settings by container.preferencesRepository.settings
                .collectAsStateWithLifecycle(initialValue = ForgeSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            ForgeAITheme(darkTheme = darkTheme) {
                ForgeAppRoot()
            }
        }
    }
}

@Composable
fun ForgeAppRoot() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showTopLevelNav = currentRoute in TopNavDestination.entries.map { it.route }

    val atelierViewModel: ForgeAtelierViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as ForgeApplication
                ForgeAtelierViewModel(
                    pipeline = app.container.multiAgentForgePipeline,
                    toolRepository = app.container.toolRepository,
                    apiManagerRepository = app.container.apiManagerRepository,
                    preferencesRepository = app.container.preferencesRepository
                )
            }
        }
    )

    val toolsViewModel: ToolsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as ForgeApplication
                ToolsViewModel(
                    toolRepository = app.container.toolRepository,
                    runtime = app.container.sandboxedToolRuntime,
                    preferencesRepository = app.container.preferencesRepository,
                    multiAgentPipeline = app.container.multiAgentForgePipeline
                )
            }
        }
    )

    val apiManagerViewModel: ApiManagerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as ForgeApplication
                ApiManagerViewModel(
                    apiManagerRepository = app.container.apiManagerRepository,
                    apiRouterService = app.container.apiRouterService,
                    preferencesRepository = app.container.preferencesRepository
                )
            }
        }
    )

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as ForgeApplication
                SettingsViewModel(app.container.preferencesRepository)
            }
        }
    )

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 640.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (showTopLevelNav && !useNavigationRail) {
                    NavigationBar {
                        TopNavDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showTopLevelNav && useNavigationRail) {
                    NavigationRail {
                        TopNavDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ForgeRoutes.ROUTE_ATELIER,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ForgeRoutes.ROUTE_ATELIER) {
                        ForgeAtelierScreen(
                            viewModel = atelierViewModel,
                            onOpenTool = { toolId ->
                                navController.navigate(ForgeRoutes.toolRunner(toolId))
                            },
                            onOpenApiManager = { navigateTopLevel(ForgeRoutes.ROUTE_APIS) }
                        )
                    }

                    composable(ForgeRoutes.ROUTE_TOOLS) {
                        ToolsCatalogScreen(
                            viewModel = toolsViewModel,
                            onRunTool = { toolId ->
                                navController.navigate(ForgeRoutes.toolRunner(toolId))
                            },
                            onInspectTool = { toolId ->
                                navController.navigate(ForgeRoutes.toolDetail(toolId))
                            },
                            onNavigateToAtelier = { navigateTopLevel(ForgeRoutes.ROUTE_ATELIER) }
                        )
                    }

                    composable(ForgeRoutes.ROUTE_APIS) {
                        ApiManagerScreen(viewModel = apiManagerViewModel)
                    }

                    composable(ForgeRoutes.ROUTE_SETTINGS) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onOpenApiManager = { navigateTopLevel(ForgeRoutes.ROUTE_APIS) }
                        )
                    }

                    composable(
                        route = ForgeRoutes.ROUTE_TOOL_RUNNER,
                        arguments = listOf(navArgument("toolId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val toolId = backStackEntry.arguments?.getString("toolId").orEmpty()
                        ToolRunnerScreen(
                            toolId = toolId,
                            viewModel = toolsViewModel,
                            onBack = { navController.popBackStack() },
                            onInspectDetails = {
                                navController.navigate(ForgeRoutes.toolDetail(toolId))
                            }
                        )
                    }

                    composable(
                        route = ForgeRoutes.ROUTE_TOOL_DETAIL,
                        arguments = listOf(navArgument("toolId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val toolId = backStackEntry.arguments?.getString("toolId").orEmpty()
                        ToolDetailScreen(
                            toolId = toolId,
                            viewModel = toolsViewModel,
                            onBack = { navController.popBackStack() },
                            onRunTool = {
                                navController.navigate(ForgeRoutes.toolRunner(toolId))
                            }
                        )
                    }
                }
            }
        }
    }
}
