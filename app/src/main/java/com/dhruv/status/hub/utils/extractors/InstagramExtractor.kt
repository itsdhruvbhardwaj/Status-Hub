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

        // 1. Fetch using Crawler UA (usually gets cleaner OpenGraph meta tags)
        val crawlerResult = tryFetch(cleanUrl, CRAWLER_USER_AGENT)

        // 2. Fetch using Browser UA only if crawler didn't find a video
        val browserResult = if (crawlerResult?.mediaType != "video") {
            tryFetch(cleanUrl, USER_AGENT)
        } else null

        // Priority: Video Result > Image Result
        val result = when {
            crawlerResult?.mediaType == "video" -> crawlerResult
            browserResult?.mediaType == "video" -> browserResult
            crawlerResult != null -> crawlerResult
            else -> browserResult
        }

        // 3. Ensure Reel extraction ALWAYS returns both formats if a video URL is present
        if (result != null && result.mediaType == "video") {
            val formats = mutableListOf<NetworkDownloadUtils.MediaFormat>()

            // Video format: the original Reel MP4 URL, extension mp4, format MP4.
            formats.add(NetworkDownloadUtils.MediaFormat(
                id = "insta_video",
                url = result.url,
                quality = "Standard Quality",
                extension = "mp4",
                format = "MP4",
                isAudio = false,
                hasVideo = true,
                hasAudio = true
            ))

            // Audio format: the SAME Reel MP4 URL, marked isAudio=true, extension m4a.
            formats.add(NetworkDownloadUtils.MediaFormat(
                id = "insta_audio",
                url = result.url,
                quality = "Original Audio",
                extension = "m4a",
                format = "M4A",
                isAudio = true,
                hasVideo = false,
                hasAudio = true
            ))

            return result.copy(
                formats = formats,
                audioUrl = result.url
            )
        }

        return result
    }

    private suspend fun tryFetch(url: String, userAgent: String): NetworkDownloadUtils.MediaInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Connection", "keep-alive")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                // Identify Video URL (Highly robust regex suite)
                val videoUrl = extractValue(html, "\"video_url\"\\s*:\\s*\"([^\"]+)\"")
                    ?: extractValue(html, "video_url\":\"([^\"]+)\"")
                    ?: extractValue(html, "<meta[^>]+property=[\"']og:video:secure_url[\"'][^>]+content=[\"']([^\"']+)[\"']")
                    ?: extractValue(html, "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:video:secure_url[\"']")
                    ?: extractValue(html, "<meta[^>]+property=[\"']og:video[\"'][^>]+content=[\"']([^\"']+)[\"']")
                    ?: extractValue(html, "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:video[\"']")
                    ?: extractValue(html, "\"video_versions\"\\s*:\\s*\\[\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"")
                    ?: extractValue(html, "xdt_shortcode_media\":\\{.*?\"video_url\":\"([^\"]+)\"")
                    ?: extractValue(html, "\"url\":\"([^\"]+\\.mp4[^\"]*)\"")
                    ?: extractValue(html, "(https://[^\"\\s\\\\]+fbcdn\\.net[^\"\\s\\\\]+\\.mp4[^\"\\s\\\\]*)")

                // Identify Thumbnail URL
                val imageUrl = extractValue(html, "\"display_url\"\\s*:\\s*\"([^\"]+)\"")
                    ?: extractValue(html, "display_url\":\"([^\"]+)\"")
                    ?: extractValue(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']")
                    ?: extractValue(html, "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']")
                    ?: extractValue(html, "<meta[^>]+property=[\"']twitter:image[\"'][^>]+content=[\"']([^\"']+)[\"']")

                val decodedThumbnail = imageUrl?.let { decodeUrl(it) }

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
                            thumbnailUrl = decodedThumbnail
                        )
                    }
                }

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
            .replace("&quot;", "\"")
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