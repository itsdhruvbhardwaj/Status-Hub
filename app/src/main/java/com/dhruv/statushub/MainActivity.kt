package com.dhruv.statushub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dhruv.statushub.ui.screens.HomeScreen
import com.dhruv.statushub.ui.theme.StatusHubTheme
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize AdMob
        val backgroundScope = CoroutineScope(Dispatchers.IO)
        backgroundScope.launch {
            MobileAds.initialize(this@MainActivity) {
                // Set test device configuration explicitly
                val testDeviceIds = listOf("c14c5401-8498-42be-bb07-acecd71fe275") // Your M31 ID
                val configuration = RequestConfiguration.Builder()
                    .setTestDeviceIds(testDeviceIds)
                    .build()
                MobileAds.setRequestConfiguration(configuration)
            }
        }

        enableEdgeToEdge()
        setContent {
            StatusHubTheme {
                HomeScreen()
            }
        }
    }
}