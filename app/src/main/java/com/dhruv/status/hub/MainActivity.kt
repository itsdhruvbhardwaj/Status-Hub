package com.dhruv.status.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.dhruv.status.hub.ui.screens.HomeScreen
import com.dhruv.status.hub.ui.screens.OnboardingScreen
import com.dhruv.status.hub.ui.theme.StatusHubTheme
import com.dhruv.status.hub.utils.isDarkModeEnabled
import com.dhruv.status.hub.utils.isOnboardingComplete
import com.dhruv.status.hub.utils.setOnboardingComplete
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
            
            // State for the dark theme preference. 
            // Re-evaluates whenever it's changed in settings.
            var isDarkTheme by remember { 
                mutableStateOf(isDarkModeEnabled(context)) 
            }

            // A trigger to refresh the theme state from SharedPreferences.
            // When HomeScreen signals a theme change, this value increments,
            // triggering the LaunchedEffect to update isDarkTheme.
            val themeRefreshTrigger = remember { mutableIntStateOf(0) }
            
            LaunchedEffect(themeRefreshTrigger.value) {
                isDarkTheme = isDarkModeEnabled(context)
            }

            // Apply the app's custom theme
            StatusHubTheme(darkTheme = isDarkTheme) {
                // Check if the user has already completed the onboarding process
                var onboardingFinished by remember { 
                    mutableStateOf(isOnboardingComplete(context)) 
                }

                if (onboardingFinished) {
                    // Navigate to HomeScreen if onboarding is complete
                    HomeScreen(onThemeChange = { 
                        themeRefreshTrigger.value += 1 
                    })
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
