package com.example.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.AppContainer
import com.example.R
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.connections.ConnectionsScreen
import com.example.ui.connections.ConnectionsSubTab
import com.example.ui.connections.ConnectionsViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.tasks.TaskDetailScreen
import com.example.ui.tasks.TasksScreen
import com.example.ui.tasks.TasksViewModel
import com.example.ui.theme.NexaBackground
import com.example.ui.theme.NexaBorderSubtle
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary

sealed class NexaRoute(val route: String) {
    data object Chat : NexaRoute("chat")
    data object Tasks : NexaRoute("tasks")
    data object TaskDetail : NexaRoute("task_detail/{taskId}") {
        fun create(taskId: String) = "task_detail/$taskId"
    }
    data object Connections : NexaRoute("connections")
    data object Settings : NexaRoute("settings")
}

enum class NexaNavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    CHAT(NexaRoute.Chat.route, R.string.nav_chat, Icons.Filled.Forum, Icons.Outlined.Forum, "nav_tab_chat"),
    TASKS(NexaRoute.Tasks.route, R.string.nav_tasks, Icons.Filled.Checklist, Icons.Outlined.Checklist, "nav_tab_tasks"),
    CONNECTIONS(NexaRoute.Connections.route, R.string.nav_connections, Icons.Filled.Hub, Icons.Outlined.Hub, "nav_tab_connections"),
    SETTINGS(NexaRoute.Settings.route, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_tab_settings")
}

@Composable
fun NexaAppShell(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            chatRepository = container.chatRepository,
            connectionsRepository = container.connectionsRepository,
            settingsRepository = container.settingsRepository,
            toolRegistry = container.toolRegistry,
            systemPromptBuilder = container.systemPromptBuilder,
            contextBuilder = container.contextBuilder,
            attachmentProcessor = container.attachmentProcessor,
            taskRepository = container.taskRepository,
            autonomousOrchestrator = container.autonomousAgentOrchestrator,
            agentRunController = container.agentRunController
        )
    )
    val tasksViewModel: TasksViewModel = viewModel(
        factory = TasksViewModel.Factory(
            taskRepository = container.taskRepository,
            agentRunController = container.agentRunController
        )
    )
    val connectionsViewModel: ConnectionsViewModel = viewModel(
        factory = ConnectionsViewModel.Factory(container.connectionsRepository)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            settingsRepository = container.settingsRepository,
            connectionsRepository = container.connectionsRepository,
            chatRepository = container.chatRepository
        )
    )

    val showBottomBar = currentRoute in NexaNavTab.entries.map { it.route }

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigateToConnectionsTab(subTab: ConnectionsSubTab) {
        connectionsViewModel.selectTab(subTab)
        navigateTopLevel(NexaRoute.Connections.route)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(NexaBackground)
            .imePadding(),
        containerColor = NexaBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (showBottomBar) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(NexaBorderSubtle)
                    )
                    NavigationBar(
                        containerColor = NexaSecondaryBackground,
                        tonalElevation = 0.dp
                    ) {
                        NexaNavTab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(tab.route) },
                                modifier = Modifier.testTag(tab.testTag),
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NexaOrange,
                                    selectedTextColor = NexaTextPrimary,
                                    indicatorColor = NexaOrangeMuted,
                                    unselectedIconColor = NexaTextSecondary,
                                    unselectedTextColor = NexaTextSecondary
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Crossfade(
            targetState = Unit,
            animationSpec = tween(180),
            label = "nexa_nav_host",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = NexaRoute.Chat.route
            ) {
                composable(NexaRoute.Chat.route) {
                    ChatScreen(
                        viewModel = chatViewModel,
                        onOpenTaskDetail = { taskId ->
                            navController.navigate(NexaRoute.TaskDetail.create(taskId))
                        },
                        onNavigateToConnections = {
                            navigateToConnectionsTab(ConnectionsSubTab.PROVIDERS)
                        }
                    )
                }
                composable(NexaRoute.Tasks.route) {
                    TasksScreen(
                        viewModel = tasksViewModel,
                        onOpenTaskDetail = { taskId ->
                            navController.navigate(NexaRoute.TaskDetail.create(taskId))
                        }
                    )
                }
                composable(
                    route = NexaRoute.TaskDetail.route,
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType })
                ) { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    TaskDetailScreen(
                        taskId = taskId,
                        viewModel = tasksViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(NexaRoute.Connections.route) {
                    ConnectionsScreen(viewModel = connectionsViewModel)
                }
                composable(NexaRoute.Settings.route) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateToConnectionsTab = ::navigateToConnectionsTab
                    )
                }
            }
        }
    }
}
