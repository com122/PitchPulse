package com.pitchpulse.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Stadium
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
import com.pitchpulse.app.di.AppContainer
import com.pitchpulse.app.ui.detail.MatchDetailScreen
import com.pitchpulse.app.ui.detail.MatchDetailViewModel
import com.pitchpulse.app.ui.matches.MatchesScreen
import com.pitchpulse.app.ui.matches.MatchesViewModel
import com.pitchpulse.app.ui.pitches.PitchCalibrationScreen
import com.pitchpulse.app.ui.pitches.PitchCalibrationViewModel
import com.pitchpulse.app.ui.pitches.PitchesScreen
import com.pitchpulse.app.ui.pitches.PitchesViewModel
import com.pitchpulse.app.ui.settings.SettingsScreen
import com.pitchpulse.app.ui.settings.SettingsViewModel
import com.pitchpulse.app.ui.theme.PP

private object Routes {
    const val MATCHES = "matches"
    const val PITCHES = "pitches"
    const val SETTINGS = "settings"
    const val DETAIL = "match/{matchId}"
    const val CALIBRATE = "pitch/edit?pitchId={pitchId}"

    fun detail(id: String) = "match/$id"
    fun calibrate(id: String?) = if (id == null) "pitch/edit" else "pitch/edit?pitchId=$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.MATCHES, "比赛", Icons.Default.SportsSoccer),
    Tab(Routes.PITCHES, "球场", Icons.Default.Stadium),
    Tab(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@Composable
fun PitchPulseNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = TABS.any { it.route == currentRoute }

    Scaffold(
        containerColor = PP.Background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = PP.Card) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PP.Grass,
                                selectedTextColor = PP.Grass,
                                indicatorColor = PP.Grass.copy(alpha = 0.15f),
                                unselectedIconColor = PP.Muted,
                                unselectedTextColor = PP.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.MATCHES, modifier = Modifier.padding(padding)) {
            composable(Routes.MATCHES) {
                val vm: MatchesViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        MatchesViewModel(
                            container.matchRepository, container.pitchRepository, container.importRepository,
                            container.thumbnailRepository, container.settingsRepository,
                        )
                    }
                })
                MatchesScreen(vm, onOpenMatch = { nav.navigate(Routes.detail(it)) })
            }
            composable(Routes.PITCHES) {
                val vm: PitchesViewModel = viewModel(factory = viewModelFactory {
                    initializer { PitchesViewModel(container.pitchRepository) }
                })
                PitchesScreen(vm, onEdit = { nav.navigate(Routes.calibrate(it)) })
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(container.settingsRepository, container.matchRepository, container.watchSyncRepository)
                    }
                })
                SettingsScreen(vm)
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
            ) { entry ->
                val matchId = entry.arguments?.getString("matchId").orEmpty()
                val vm: MatchDetailViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        MatchDetailViewModel(
                            matchId, container.appContext,
                            container.matchRepository, container.pitchRepository, container.settingsRepository,
                        )
                    }
                })
                MatchDetailScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.CALIBRATE,
                arguments = listOf(navArgument("pitchId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val pitchId = entry.arguments?.getString("pitchId")
                val vm: PitchCalibrationViewModel = viewModel(factory = viewModelFactory {
                    initializer { PitchCalibrationViewModel(pitchId, container.pitchRepository, container.settingsRepository) }
                })
                PitchCalibrationScreen(vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
