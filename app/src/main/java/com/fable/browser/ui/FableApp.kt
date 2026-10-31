package com.fable.browser.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.slideOutOfContainer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fable.browser.ui.bookmarks.BookmarksScreen
import com.fable.browser.ui.browser.BrowserScreen
import com.fable.browser.ui.browser.BrowserViewModel
import com.fable.browser.ui.downloads.DownloadsScreen
import com.fable.browser.ui.history.HistoryScreen
import com.fable.browser.ui.settings.AboutScreen
import com.fable.browser.ui.settings.AppearanceSettingsScreen
import com.fable.browser.ui.settings.DownloadSettingsScreen
import com.fable.browser.ui.settings.GeneralSettingsScreen
import com.fable.browser.ui.settings.IncognitoSettingsScreen
import com.fable.browser.ui.settings.PerformanceSettingsScreen
import com.fable.browser.ui.settings.PrivacySettingsScreen
import com.fable.browser.ui.settings.SettingsScreen
import com.fable.browser.ui.settings.SiteSettingsScreen
import com.fable.browser.ui.tabs.TabsScreen
import com.fable.browser.ui.theme.FableTheme

/** Every destination in the app. Single Activity, single NavHost. */
object Routes {
    const val BROWSER = "browser"
    const val TABS = "tabs"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_PRIVACY = "settings/privacy"
    const val SETTINGS_INCOGNITO = "settings/incognito"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_SITES = "settings/sites"
    const val SETTINGS_DOWNLOADS = "settings/downloads"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_PERFORMANCE = "settings/performance"
}

/**
 * Root composable. The theme lives here so that switching into incognito
 * repaints the entire app — including every settings sub-screen — with the
 * violet private palette, making the mode impossible to mistake.
 */
@Composable
fun FableApp() {
    val viewModel: BrowserViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val incognito by viewModel.isIncognito.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    FableTheme(themeMode = settings.themeMode, incognito = incognito) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            FableNavHost(navController = navController, viewModel = viewModel)
        }
    }
}

@Composable
private fun FableNavHost(navController: NavHostController, viewModel: BrowserViewModel) {
    NavHost(
        navController = navController,
        startDestination = Routes.BROWSER,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(220)
            )
        },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(140)) },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(220)
            )
        }
    ) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                viewModel = viewModel,
                onOpenTabs = { navController.navigate(Routes.TABS) },
                onOpenBookmarks = { navController.navigate(Routes.BOOKMARKS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.TABS) {
            TabsScreen(
                viewModel = viewModel,
                onClose = { navController.popBackStack() }
            )
        }
        composable(Routes.BOOKMARKS) {
            BookmarksScreen(
                onBack = { navController.popBackStack() },
                onOpenUrl = { url ->
                    viewModel.load(url)
                    navController.popBackStack(Routes.BROWSER, inclusive = false)
                },
                onOpenInNewTab = { url ->
                    viewModel.openTab(url)
                    navController.popBackStack(Routes.BROWSER, inclusive = false)
                }
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenUrl = { url ->
                    viewModel.load(url)
                    navController.popBackStack(Routes.BROWSER, inclusive = false)
                }
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) }
            )
        }
        composable(Routes.SETTINGS_GENERAL) {
            GeneralSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_PERFORMANCE) {
            PerformanceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_PRIVACY) {
            PrivacySettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_INCOGNITO) {
            IncognitoSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_SITES) {
            SiteSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_DOWNLOADS) {
            DownloadSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) }
            )
        }
        composable(Routes.SETTINGS_ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
