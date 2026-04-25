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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(this@MainActivity) {
                val testDeviceIds = listOf("c14c5401-8498-42be-bb07-acecd71fe275")
                val configuration = RequestConfiguration.Builder()
                    .setTestDeviceIds(testDeviceIds)
                    .build()
                MobileAds.setRequestConfiguration(configuration)
            }
        }

        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            
            // Using a state that triggers recomposition when the preference changes
            var isDarkTheme by remember { 
                mutableStateOf(isDarkModeEnabled(context)) 
            }

            // A trigger to refresh the theme state from SharedPreferences
            // In a real app, you might use a Flow or a custom PreferenceObserver
            val themeRefreshTrigger = remember { mutableIntStateOf(0) }
            
            LaunchedEffect(themeRefreshTrigger.value) {
                isDarkTheme = isDarkModeEnabled(context)
            }

            StatusHubTheme(darkTheme = isDarkTheme) {
                var onboardingFinished by remember { 
                    mutableStateOf(isOnboardingComplete(context)) 
                }

                if (onboardingFinished) {
                    // Pass the trigger to HomeScreen so it can notify when settings change
                    HomeScreen(onThemeChange = { 
                        themeRefreshTrigger.value += 1 
                    })
                } else {
                    OnboardingScreen(
                        onContinue = {
                            setOnboardingComplete(context)
                            onboardingFinished = true
                        }
                    )
                }
            }
        }
    }
}
