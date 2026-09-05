package com.dhruv.status.hub.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dhruv.status.hub.utils.AdsManager
import com.google.android.gms.ads.*

/**
 * AdBanner Composable
 * 
 * Displays a Google Mobile Ads banner using centralized configuration from AdsManager.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    var isAdLoaded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isAdLoaded) 50.dp else 0.dp) // Dynamic height to avoid empty space
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = AdsManager.getBannerAdId()
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            isAdLoaded = true
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            Log.e("AdBanner", "Banner failed: ${error.message}")
                            isAdLoaded = false
                        }
                    }
                    loadAd(AdRequest.Builder().build())
                }
            },
            update = { adView ->
                // Ensure the ad is loaded if not already
            }
        )
    }
}
