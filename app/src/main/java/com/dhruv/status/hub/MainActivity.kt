package com.dhruv.status.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.dhruv.status.hub.ui.screens.DownloadFromLinkScreen
import com.dhruv.status.hub.ui.screens.HomeScreen
import com.dhruv.status.hub.ui.screens.OnboardingScreen
import com.dhruv.status.hub.ui.screens.RecentDownloadsScreen
import com.dhruv.status.hub.ui.theme.StatusHubTheme
import com.dhruv.status.hub.utils.*
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainActivity
 * 
 * The main entry point of the application. It handles initialization,
 * onboarding flow, and theme management.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Google Mobile Ads SDK on a background thread
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(this@MainActivity) {
                // Configure test devices for development
                val testDeviceIds = listOf("c14c5401-8498-42be-bb07-acecd71fe275")
                val configuration = RequestConfiguration.Builder()
                    .setTestDeviceIds(testDeviceIds)
                    .build()
                MobileAds.setRequestConfiguration(configuration)
            }
        }

        // Enable edge-to-edge display to use the entire screen area
        enableEdgeToEdge()
        
        setContent {
            val context = LocalContext.current
            val systemInDarkTheme = isSystemInDarkTheme()
            
            // State for the app theme preference (System, Light, or Dark)
            var currentThemePref by remember { 
                mutableStateOf(getAppTheme(context)) 
            }

            // A trigger to refresh the theme state from SharedPreferences.
            val themeRefreshTrigger = remember { mutableIntStateOf(0) }
            
            LaunchedEffect(themeRefreshTrigger.value) {
                currentThemePref = getAppTheme(context)
            }

            // Calculate if we should use dark theme based on preference and system setting
            val useDarkTheme = when (currentThemePref) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> systemInDarkTheme
            }

            // Simple navigation state
            var currentScreen by remember { mutableStateOf("home") }

            // Apply the app's custom theme
            StatusHubTheme(darkTheme = useDarkTheme) {
                // Check if the user has already completed the onboarding process
                var onboardingFinished by remember { 
                    mutableStateOf(isOnboardingComplete(context)) 
                }

                if (onboardingFinished) {
                    when (currentScreen) {
                        "home" -> {
                            HomeScreen(
                                onThemeChange = { themeRefreshTrigger.value += 1 },
                                onNavigateToDownloadLink = { currentScreen = "download_link" },
                                onNavigateToRecentDownloads = { currentScreen = "recent_downloads" }
                            )
                        }
                        "download_link" -> {
                            DownloadFromLinkScreen(
                                onBack = { currentScreen = "home" },
                                onNavigateToRecentDownloads = { currentScreen = "recent_downloads" }
                            )
                        }
                        "recent_downloads" -> {
                            RecentDownloadsScreen(
                                onBack = { currentScreen = "home" }
                            )
                        }
                    }
                } else {
                    // Show OnboardingScreen for new users
                    OnboardingScreen(
                        onContinue = {
                            // Mark onboarding as complete and move to HomeScreen
                            setOnboardingComplete(context)
                            onboardingFinished = true
                        }
                    )
                }
            }
        }
    }
}
