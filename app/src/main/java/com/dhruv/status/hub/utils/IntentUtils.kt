package com.dhruv.status.hub.utils

import android.content.Intent

object IntentUtils {
    /**
     * Extracts a URL from a share intent.
     * Handles text/plain shared content.
     */
    fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        
        // Regex to find a URL in text
        val urlRegex = "(https?://[\\w\\d\\.#\\?&\\-_/]+)".toRegex()
        val match = urlRegex.find(sharedText)
        
        return match?.value
    }
}
