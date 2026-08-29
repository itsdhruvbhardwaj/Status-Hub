package com.dhruv.status.hub.utils.extractors

import com.dhruv.status.hub.utils.NetworkDownloadUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

class FacebookExtractor(private val client: OkHttpClient) : MediaExtractor {

    // Using a crawler User-Agent to encourage the server to provide OpenGraph metadata
    private val CRAWLER_USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com")
    }

    override suspend fun extract(url: String): NetworkDownloadUtils.MediaInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CRAWLER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                // 1. Try to find video source in common formats for public videos
                val videoUrl = extractTag(html, "(?:\"browser_native_hd_url\"|\"hd_src\"):\"(.*?)\"")
                    ?: extractTag(html, "(?:\"browser_native_sd_url\"|\"sd_src\"):\"(.*?)\"")
                    ?: extractTag(html, "property=[\"']og:video:url[\"']\\s+content=[\"'](.*?)[\"']")
                    ?: extractTag(html, "property=[\"']og:video[\"']\\s+content=[\"'](.*?)[\"']")

                if (videoUrl != null && videoUrl.isNotBlank()) {
                    val decodedUrl = videoUrl.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                    return NetworkDownloadUtils.MediaInfo(
                        url = decodedUrl,
                        fileName = "Facebook_Video_${System.currentTimeMillis()}.mp4",
                        contentType = "video/mp4",
                        contentLength = -1L,
                        extension = "mp4",
                        mediaType = "video",
                        platform = "Facebook"
                    )
                }

                // 2. Try Image Extraction fallback
                val imageUrl = extractTag(html, "property=[\"']og:image[\"']\\s+content=[\"'](.*?)[\"']")
                if (imageUrl != null && imageUrl.isNotBlank()) {
                    val decodedUrl = imageUrl.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                    return NetworkDownloadUtils.MediaInfo(
                        url = decodedUrl,
                        fileName = "Facebook_Image_${System.currentTimeMillis()}.jpg",
                        contentType = "image/jpeg",
                        contentLength = -1L,
                        extension = "jpg",
                        mediaType = "image",
                        platform = "Facebook"
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun extractTag(html: String, regex: String): String? {
        val pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }
}
