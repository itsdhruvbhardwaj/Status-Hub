package com.dhruv.statushub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dhruv.statushub.ui.screens.HomeScreen
import com.dhruv.statushub.ui.theme.StatusHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StatusHubTheme {
                HomeScreen()
            }
        }
    }
}