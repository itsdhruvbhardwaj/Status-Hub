package com.dhruv.status.hub.utils

import android.content.Intent

object IntentUtils {
    /**
     * Extracts a URL from a share intent or direct link.
     * Handles text/plain shared content and data URIs.
     */
    fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        
        // 1. Try to get URL from intent data (common for direct deep links)
        val dataUrl = intent.dataString
        if (!dataUrl.isNullOrBlank() && dataUrl.startsWith("http")) {
            return dataUrl
        }

        // 2. Extract URL from shared text (Action Send)
        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                // Robust regex to find a URL in text, including query parameters like '=' and '%'
                // This ensures characters like '=' in YouTube links are correctly captured.
                val urlRegex = "(https?://[\\w\\d\\.#\\?&\\-=_/%:\\+~]+)".toRegex()
                val match = urlRegex.find(sharedText)
                
                return match?.value
            }
        }
        
        return null
    }
}
