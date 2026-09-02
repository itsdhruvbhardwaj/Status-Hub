package com.dhruv.status.hub.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized manager for AdMob integration in Status Hub.
 */
object AdsManager {
    private const val TAG = "AdsManager"

    // TEST AD IDs
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"

    // PRODUCTION AD IDs
    private const val PROD_BANNER_ID = "ca-app-pub-7668637948120420/9031601842"
    private const val PROD_INTERSTITIAL_ID = "ca-app-pub-7668637948120420/7022491961"

    // Set to 1 to ensure it triggers for every download click as requested
    private const val INTERSTITIAL_COOLDOWN_DOWNLOADS = 1
    private val downloadCounter = AtomicInteger(0)

    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false

    fun getBannerAdId(): String {
        return if (com.dhruv.status.hub.BuildConfig.DEBUG) TEST_BANNER_ID else PROD_BANNER_ID
    }

    private fun getInterstitialAdId(): String {
        return if (com.dhruv.status.hub.BuildConfig.DEBUG) TEST_INTERSTITIAL_ID else PROD_INTERSTITIAL_ID
    }

    /**
     * Preloads the next interstitial ad.
     */
    fun loadInterstitial(context: Context) {
        if (interstitialAd != null || isAdLoading) return

        isAdLoading = true
        val adRequest = AdRequest.Builder().build()
        
        InterstitialAd.load(
            context,
            getInterstitialAdId(),
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.d(TAG, "Interstitial failed to load: ${adError.message}")
                    interstitialAd = null
                    isAdLoading = false
                }

                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Interstitial loaded successfully")
                    interstitialAd = ad
                    isAdLoading = false
                }
            }
        )
    }

    /**
     * Handles the download click with interstitial logic.
     * Starts the download in the background immediately if an ad is shown.
     */
    fun handleDownloadAction(activity: Activity?, onProceed: () -> Unit) {
        if (activity == null) {
            onProceed()
            return
        }

        val currentCount = downloadCounter.incrementAndGet()
        val isEligible = currentCount % INTERSTITIAL_COOLDOWN_DOWNLOADS == 0

        if (isEligible && interstitialAd != null) {
            // START DOWNLOAD IMMEDIATELY IN BACKGROUND
            onProceed()
            
            interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitial(activity)
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    loadInterstitial(activity)
                }
            }
            interstitialAd?.show(activity)
        } else {
            // Ad not ready or not eligible, proceed immediately
            onProceed()
            if (interstitialAd == null) loadInterstitial(activity)
        }
    }
}
