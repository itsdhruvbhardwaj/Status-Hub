package com.dhruv.status.hub.utils.extractors

import com.dhruv.status.hub.utils.NetworkDownloadUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

class InstagramExtractor(private val client: OkHttpClient) : MediaExtractor {

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    private val CRAWLER_USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("instagram.com") || lower.contains("instagr.am")
    }

    override suspend fun extract(url: String): NetworkDownloadUtils.MediaInfo? {
        val baseUrl = if (url.contains("?")) url.substringBefore("?") else url
        val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        // Attempt 1: Using Crawler User Agent (Video Prioritized)
        val crawlerResult = tryFetch(cleanUrl, CRAWLER_USER_AGENT)
        if (crawlerResult?.mediaType == "video") return crawlerResult

        // Attempt 2: Using Browser User Agent
        val browserResult = tryFetch(cleanUrl, USER_AGENT)
        if (browserResult?.mediaType == "video") return browserResult

        // If no video found in either attempt, return whichever detection worked
        return crawlerResult ?: browserResult
    }

    private suspend fun tryFetch(url: String, userAgent: String): NetworkDownloadUtils.MediaInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                // 1. Extract Thumbnail (Always useful for preview)
                val imageUrl = extractValue(html, "\"display_url\"\\s*:\\s*\"([^\"]+)\"")
                    ?: extractValue(html, "display_url\":\"([^\"]+)\"")
                    ?: extractValue(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']")
                val decodedThumbnail = imageUrl?.let { decodeUrl(it) }

                // 2. Extract Video URL (High Priority)
                val videoUrl = extractValue(html, "\"video_url\"\\s*:\\s*\"([^\"]+)\"")
                    ?: extractValue(html, "video_url\":\"([^\"]+)\"")
                    ?: extractValue(html, "<meta[^>]+property=[\"']og:video:secure_url[\"'][^>]+content=[\"']([^\"']+)[\"']")
                    ?: extractValue(html, "<meta[^>]+property=[\"']og:video[\"'][^>]+content=[\"']([^\"']+)[\"']")
                    ?: extractValue(html, "\"video_versions\"\\s*:\\s*\\[\\s*\\{\\s*\"type\"\\s*:\\s*101,\\s*\"url\"\\s*:\\s*\"([^\"]+)\"")

                if (!videoUrl.isNullOrBlank()) {
                    val decodedVideoUrl = decodeUrl(videoUrl)
                    if (!decodedVideoUrl.contains("instagram.com/static/")) {
                        return NetworkDownloadUtils.MediaInfo(
                            url = decodedVideoUrl,
                            fileName = "Instagram_Video_${System.currentTimeMillis()}.mp4",
                            contentType = "video/mp4",
                            contentLength = -1L,
                            extension = "mp4",
                            mediaType = "video",
                            platform = "Instagram",
                            thumbnailUrl = decodedThumbnail,
                            audioUrl = decodedVideoUrl
                        )
                    }
                }

                // 3. Fallback to Image if no video was found
                if (!decodedThumbnail.isNullOrBlank() && !decodedThumbnail.contains("instagram.com/static/")) {
                    return NetworkDownloadUtils.MediaInfo(
                        url = decodedThumbnail,
                        fileName = "Instagram_Image_${System.currentTimeMillis()}.jpg",
                        contentType = "image/jpeg",
                        contentLength = -1L,
                        extension = "jpg",
                        mediaType = "image",
                        platform = "Instagram",
                        thumbnailUrl = decodedThumbnail
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun decodeUrl(url: String): String {
        return url.replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\/", "/")
    }

    private fun extractValue(content: String, regex: String): String? {
        val pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(content)
        return if (matcher.find()) {
            val result = matcher.group(1)
            if (result.isNullOrBlank()) null else result
        } else null
    }
}
