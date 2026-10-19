package com.chotaai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chotaai.app.ui.chat.ChatScreen
import com.chotaai.app.ui.habits.HabitsScreen
import com.chotaai.app.ui.models.ModelsScreen
import com.chotaai.app.ui.tasks.TasksScreen
import com.chotaai.app.ui.theme.ChotaTheme

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Chat("chat", "Assistant", Icons.AutoMirrored.Outlined.Chat),
    Tasks("tasks", "Tasks", Icons.Outlined.CheckCircle),
    Habits("habits", "Habits", Icons.Outlined.Repeat),
    Models("models", "Models", Icons.Outlined.Memory),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChotaTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = current == tab.route,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        nav,
                        startDestination = Tab.Chat.route,
                        // Screens draw their own top bars; only reserve room for the bottom bar.
                        modifier = PaddingValues(bottom = padding.calculateBottomPadding()).let {
                            Modifier.padding(it).consumeWindowInsets(it)
                        },
                    ) {
                        composable(Tab.Chat.route) { ChatScreen(onOpenModels = { nav.navigate(Tab.Models.route) }) }
                        composable(Tab.Tasks.route) { TasksScreen() }
                        composable(Tab.Habits.route) { HabitsScreen() }
                        composable(Tab.Models.route) { ModelsScreen() }
                    }
                }
            }
        }
    }
}
