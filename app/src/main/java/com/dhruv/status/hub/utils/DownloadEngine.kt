package com.dhruv.status.hub.utils

import com.dhruv.status.hub.utils.extractors.*
import okhttp3.OkHttpClient

/**
 * DownloadEngine coordinates different media extractors to find the direct
 * media URL from various platform links.
 */
class DownloadEngine(private val client: OkHttpClient) {

    private val extractors = listOf(
        InstagramExtractor(client),
        FacebookExtractor(client),
        YouTubeExtractor(client)
    )
    
    private val directExtractor = DirectExtractor(client)

    /**
     * Attempts to extract MediaInfo from a URL by trying all registered platform extractors
     * and falling back to a direct link check.
     */
    suspend fun extractMedia(url: String): NetworkDownloadUtils.MediaInfo? {
        // 1. Try platform-specific extractors first
        for (extractor in extractors) {
            if (extractor.canHandle(url)) {
                val info = extractor.extract(url)
                if (info != null) return info
            }
        }

        // 2. Fallback to checking if it's a direct media link
        return directExtractor.extract(url)
    }
}
