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
            StatusHubTheme {
                val context = LocalContext.current
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