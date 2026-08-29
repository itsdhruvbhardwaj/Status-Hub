package com.dhruv.status.hub.utils.extractors

import com.dhruv.status.hub.utils.NetworkDownloadUtils

interface MediaExtractor {
    /**
     * Extracts media information from a platform-specific URL.
     * returns MediaInfo if successful, null or throws exception otherwise.
     */
    suspend fun extract(url: String): NetworkDownloadUtils.MediaInfo?
    
    /**
     * Checks if this extractor can handle the given URL.
     */
    fun canHandle(url: String): Boolean
}
