package com.dhruv.statushub.ui.components

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

@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    var isAdLoaded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isAdLoaded) 50.dp else 0.dp)
            .background(if (isAdLoaded) Color.White else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
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

class InterstitialAdManager(private val context: Context) {
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false

    fun loadAd() {
        if (interstitialAd != null || isLoading) return
        
        isLoading = true
        Log.d("AdManager", "⏳ Starting to load Interstitial...")
        val adRequest = AdRequest.Builder().build()
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

    fun showAd(activity: Activity?, onAdDismissed: () -> Unit) {
        if (activity == null) {
            onAdDismissed()
            return
        }

        if (interstitialAd != null) {
            interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
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
            onAdDismissed()
            loadAd()
        }
    }
}