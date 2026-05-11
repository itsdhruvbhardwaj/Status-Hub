package com.dhruv.status.hub.ui.components

import android.app.Activity
import android.content.Context
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
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdBanner Composable
 * 
 * Displays a Google Mobile Ads banner at the bottom of the screen.
 * It automatically adjusts its height based on whether an ad is successfully loaded.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    // Tracks if the ad has been successfully loaded to toggle visibility
    var isAdLoaded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isAdLoaded) 50.dp else 0.dp)
            .background(if (isAdLoaded) Color.White else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        // Integrate the classic Android AdView into the Compose layout
        AndroidView(
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    // Test Ad Unit ID for development
                    adUnitId = "ca-app-pub-7668637948120420/9031601842"
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            Log.d("AdBanner", "✅ Banner loaded successfully")
                            isAdLoaded = true
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            Log.e("AdBanner", "❌ Banner failed: ${error.message} (Code: ${error.code})")
                            isAdLoaded = false
                        }
                    }
                    loadAd(AdRequest.Builder().build())
                }
            }
        )
    }
}

/**
 * InterstitialAdManager
 * 
 * Helper class to manage loading and showing Google Interstitial Ads.
 * It handles caching the ad and refreshing it after use.
 */
class InterstitialAdManager(private val context: Context) {
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false

    /**
     * Loads an interstitial ad if one isn't already loaded or being loaded.
     */
    fun loadAd() {
        if (interstitialAd != null || isLoading) return
        
        isLoading = true
        Log.d("AdManager", "⏳ Starting to load Interstitial...")
        val adRequest = AdRequest.Builder().build()
        // Test Ad Unit ID for development
        InterstitialAd.load(context, "ca-app-pub-7668637948120420/7022491961", adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.e("AdManager", "❌ Interstitial failed to load: ${adError.message} (Code: ${adError.code})")
                interstitialAd = null
                isLoading = false
            }

            override fun onAdLoaded(ad: InterstitialAd) {
                Log.d("AdManager", "✅ Interstitial loaded successfully")
                interstitialAd = ad
                isLoading = false
            }
        })
    }

    /**
     * Displays the loaded interstitial ad.
     * 
     * @param activity The activity context required to show the ad.
     * @param onAdDismissed Callback executed when the ad is closed or fails to show.
     */
    fun showAd(activity: Activity?, onAdDismissed: () -> Unit) {
        if (activity == null) {
            onAdDismissed()
            return
        }

        if (interstitialAd != null) {
            interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    // Clear the current ad and pre-load the next one
                    interstitialAd = null
                    loadAd()
                    onAdDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    onAdDismissed()
                    loadAd()
                }
            }
            interstitialAd?.show(activity)
        } else {
            // If no ad is ready, proceed immediately and try to load one for next time
            onAdDismissed()
            loadAd()
        }
    }
}
