package com.antigravity.client.ui

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.antigravity.client.AntigravityApp
import com.antigravity.client.ui.chat.ChatScreen
import com.antigravity.client.ui.chat.ChatViewModel
import com.antigravity.client.ui.chats.ChatsScreen
import com.antigravity.client.ui.chats.ChatsViewModel
import com.antigravity.client.ui.code.CodeViewerScreen
import com.antigravity.client.ui.connection.ConnectionScreen
import com.antigravity.client.ui.connection.ConnectionViewModel
import com.antigravity.client.ui.files.FilesScreen
import com.antigravity.client.ui.files.FilesViewModel
import com.antigravity.client.ui.settings.SettingsScreen
import com.antigravity.client.ui.settings.SettingsViewModel

sealed class Screen(val route: String) {
    object Connection : Screen("connection")
    object Chats : Screen("chats")
    object Chat : Screen("chat/{chatId}") {
        fun createRoute(chatId: String) = "chat/$chatId"
    }
    object Files : Screen("files")
    object CodeViewer : Screen("code_viewer?path={path}") {
        fun createRoute(path: String) = "code_viewer?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    }
    object Settings : Screen("settings")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val tokenStore = AntigravityApp.instance.tokenStore
    val startDestination = if (tokenStore.hasToken()) Screen.Chats.route else Screen.Connection.route

    // Shared quote buffer across navigation
    var pendingQuote by remember { mutableStateOf<String?>(null) }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Connection.route) {
            val connectionVm: ConnectionViewModel = viewModel()
            ConnectionScreen(
                viewModel = connectionVm,
                onConnected = {
                    navController.navigate(Screen.Chats.route) {
                        popUpTo(Screen.Connection.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Chats.route) {
            val chatsVm: ChatsViewModel = viewModel()
            ChatsScreen(
                viewModel = chatsVm,
                onOpenChat = { chatId ->
                    navController.navigate(Screen.Chat.createRoute(chatId))
                },
                onNavigateToFiles = {
                    navController.navigate(Screen.Files.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
            val chatVm = remember(chatId) { ChatViewModel(chatId) }

            // Inject pending quote if any
            LaunchedEffect(pendingQuote) {
                pendingQuote?.let {
                    chatVm.setQuote(it)
                    pendingQuote = null
                }
            }

            ChatScreen(
                viewModel = chatVm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Files.route) {
            val filesVm: FilesViewModel = viewModel()
            FilesScreen(
                viewModel = filesVm,
                onOpenFile = { path ->
                    navController.navigate(Screen.CodeViewer.createRoute(path))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CodeViewer.route,
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("path") ?: ""
            val filePath = java.net.URLDecoder.decode(encodedPath, "UTF-8")

            CodeViewerScreen(
                filePath = filePath,
                onQuoteToChat = { quote ->
                    pendingQuote = quote
                    navController.popBackStack()
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            val settingsVm: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsVm,
                onNavigateBack = { navController.popBackStack() },
                onDisconnected = {
                    navController.navigate(Screen.Connection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
