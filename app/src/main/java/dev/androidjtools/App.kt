package dev.androidjtools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.playback.PlayerQueueController
import dev.androidjtools.ui.analysis.AnalysisScreen
import dev.androidjtools.ui.collections.CollectionsScreen
import dev.androidjtools.ui.library.LibraryScreen
import dev.androidjtools.ui.player.PlayerHost
import dev.androidjtools.ui.sync.SyncScreen
import dev.androidjtools.ui.waveform.WaveformScreen

private data class Destination(val route: String, val label: String)

@Composable
fun AndroidDjToolsApp(
    providers: AppProviders,
    playerQueueController: PlayerQueueController? = null,
) {
    val nav = rememberNavController()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val destinations = listOf(
        Destination("library", "Library"),
        Destination("collections", "Lists"),
        Destination("waveform", "Prep"),
        Destination("analysis", "Suggest"),
        Destination("sync", "Sync"),
    )
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        bottomBar = {
            Column {
                playerQueueController?.let { controller ->
                    PlayerHost(
                        providers = providers,
                        controller = controller,
                        expanded = playerExpanded,
                        onExpand = { playerExpanded = true },
                        onCollapse = { playerExpanded = false },
                        onJumpToPrep = {
                            playerExpanded = false
                            nav.navigateTopLevel("waveform")
                        },
                    )
                }
                NavigationBar {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                playerExpanded = false
                                nav.navigateTopLevel(destination.route)
                            },
                            icon = {
                                val image = when (index) {
                                    0 -> Icons.Default.LibraryMusic
                                    1 -> Icons.AutoMirrored.Filled.QueueMusic
                                    2 -> Icons.Default.GraphicEq
                                    3 -> Icons.Default.Lightbulb
                                    else -> Icons.Default.Sync
                                }
                                Icon(image, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") { LibraryScreen(providers) }
            composable("collections") { CollectionsScreen(providers) }
            composable("waveform") { WaveformScreen(providers) }
            composable("analysis") { AnalysisScreen(providers) }
            composable("sync") { SyncScreen(providers) }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
