package com.dhruv.statushub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.dhruv.statushub.ui.screens.HomeScreen
import com.dhruv.statushub.ui.screens.OnboardingScreen
import com.dhruv.statushub.ui.theme.StatusHubTheme
import com.dhruv.statushub.utils.isOnboardingComplete
import com.dhruv.statushub.utils.setOnboardingComplete
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize AdMob on a background thread
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
            StatusHubTheme {
                val context = LocalContext.current
                // Use a derived state to avoid re-reading prefs constantly
                var onboardingFinished by remember { 
                    mutableStateOf(isOnboardingComplete(context)) 
                }

                if (onboardingFinished) {
                    HomeScreen()
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