package com.dhruv.status.hub.utils

import com.dhruv.status.hub.utils.extractors.*
import okhttp3.OkHttpClient

/**
 * DownloadEngine coordinates different media extractors to find the direct
 * media URL from various platform links.
 *
 * Routing:
 *
 * Facebook
 *      -> FastSaverClient
 *
 * Instagram Stories
 *      -> FastSaverClient
 *
 * Normal Instagram posts/reels
 *      -> existing InstagramExtractor
 *
 * YouTube
 *      -> existing YouTubeExtractor
 *
 * Everything else
 *      -> DirectExtractor
 */
class DownloadEngine(
    private val client: OkHttpClient
) {

    private val fastSaverClient =
        FastSaverClient(client)

    private val extractors = listOf(
        InstagramExtractor(client),
        YouTubeExtractor(client)
    )

    private val directExtractor =
        DirectExtractor(client)

    suspend fun extractMedia(
        url: String
    ): NetworkDownloadUtils.MediaInfo? {

        val trimmedUrl = url.trim()

        if (trimmedUrl.isBlank()) {
            return null
        }

        /*
         * ---------------------------------------------------------
         * FACEBOOK
         * ---------------------------------------------------------
         *
         * Facebook is intentionally handled before the generic
         * extractor list so Facebook always uses FastSaver.
         */
        if (isFacebookUrl(trimmedUrl)) {
            return fastSaverClient.resolveFacebook(
                inputUrl = trimmedUrl
            )
        }

        /*
         * ---------------------------------------------------------
         * INSTAGRAM STORY
         * ---------------------------------------------------------
         *
         * Story URLs must be checked before InstagramExtractor.
         *
         * Otherwise the existing InstagramExtractor could receive
         * the Story URL first.
         */
        if (isInstagramStoryUrl(trimmedUrl)) {
            return fastSaverClient.resolveInstagramStory(
                inputUrl = trimmedUrl
            )
        }

        /*
         * ---------------------------------------------------------
         * EXISTING EXTRACTORS
         * ---------------------------------------------------------
         *
         * Normal Instagram posts/reels and YouTube continue to
         * use their existing implementations.
         */
        for (extractor in extractors) {

            if (!extractor.canHandle(trimmedUrl)) {
                continue
            }

            val info = extractor.extract(trimmedUrl)

            if (info != null) {
                return info
            }
        }

        /*
         * ---------------------------------------------------------
         * DIRECT MEDIA FALLBACK
         * ---------------------------------------------------------
         */
        return directExtractor.extract(trimmedUrl)
    }

    private fun isFacebookUrl(
        url: String
    ): Boolean {

        return try {

            val uri = java.net.URI(url)

            val host =
                uri.host
                    ?.lowercase()
                    .orEmpty()

            host == "facebook.com" ||
                    host.endsWith(".facebook.com") ||
                    host == "fb.com" ||
                    host.endsWith(".fb.com")

        } catch (_: Exception) {
            false
        }
    }

    private fun isInstagramStoryUrl(
        url: String
    ): Boolean {

        return try {

            val uri = java.net.URI(url)

            val host =
                uri.host
                    ?.lowercase()
                    .orEmpty()

            val path =
                uri.path
                    ?.lowercase()
                    .orEmpty()

            val isInstagramHost =
                host == "instagram.com" ||
                        host.endsWith(".instagram.com")

            isInstagramHost &&
                    path.startsWith("/stories/")

        } catch (_: Exception) {
            false
        }
    }
}