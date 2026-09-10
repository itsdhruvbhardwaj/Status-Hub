package com.dhruv.status.hub.utils.extractors

import android.util.Log
import com.dhruv.status.hub.utils.NetworkDownloadUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class FacebookExtractor(
    private val client: OkHttpClient
) : MediaExtractor {

    companion object {
        private const val TAG = "FacebookExtractor"

        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/121.0.0.0 Mobile Safari/537.36"

        private const val CRAWLER_USER_AGENT =
            "facebookexternalhit/1.1 " +
                    "(+http://www.facebook.com/externalhit_uatext.php)"

        private const val FACEBOOK_REFERER =
            "https://www.facebook.com/"

        private const val VALIDATION_TIMEOUT_MS = 15_000L

        /*
         * Do not validate an unlimited number of candidates.
         * Facebook pages can contain many duplicate/stale URLs.
         */
        private const val MAX_CANDIDATES_TO_VALIDATE = 20
    }

    override fun canHandle(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false

        val host = httpUrl.host.lowercase()

        return host == "facebook.com" ||
                host.endsWith(".facebook.com") ||
                host == "fb.com" ||
                host.endsWith(".fb.com") ||
                host == "fb.watch" ||
                host.endsWith(".fb.watch")
    }

    override suspend fun extract(
        url: String
    ): NetworkDownloadUtils.MediaInfo? {

        if (!canHandle(url)) {
            Log.d(TAG, "Unsupported Facebook URL")
            return null
        }

        return try {
            /*
             * First request: normal browser.
             *
             * OkHttp follows normal HTTP redirects. The final URL is therefore
             * obtained from response.request.url below.
             */
            val browserResult = fetchFacebookPage(
                url = url,
                userAgent = BROWSER_USER_AGENT
            )

            var html = browserResult?.first
            var finalUrl = browserResult?.second ?: url

            /*
             * Some Facebook URLs return better OG/crawler metadata when fetched
             * with Facebook's crawler user agent.
             *
             * This is NOT an authentication bypass. It only requests publicly
             * available page metadata.
             */
            if (html.isNullOrBlank()) {
                val crawlerResult = fetchFacebookPage(
                    url = url,
                    userAgent = CRAWLER_USER_AGENT
                )

                html = crawlerResult?.first
                finalUrl = crawlerResult?.second ?: finalUrl
            }

            if (html.isNullOrBlank()) {
                Log.e(TAG, "Facebook page returned no usable HTML")
                return null
            }

            Log.d(TAG, "Final Facebook URL: $finalUrl")
            Log.d(TAG, "HTML length: ${html.length}")

            /*
             * Build several representations of the HTML.
             *
             * Facebook frequently stores URLs like:
             *
             * https:\/\/...
             * https:\u002F\u002F...
             * ...\u0026...
             *
             * Running extraction against more than one representation makes
             * the extractor much more tolerant of Facebook HTML changes.
             */
            val htmlVariants = buildHtmlVariants(html)

            val hdCandidates = LinkedHashSet<String>()
            val sdCandidates = LinkedHashSet<String>()
            val genericVideoCandidates = LinkedHashSet<String>()

            for (variant in htmlVariants) {

                /*
                 * HD sources
                 */
                hdCandidates.addAll(
                    collectCandidates(
                        variant,
                        listOf(
                            "\"browser_native_hd_url\"\\s*:\\s*\"(.*?)\"",
                            "\"hd_src_no_ratelimit\"\\s*:\\s*\"(.*?)\"",
                            "\"hd_src\"\\s*:\\s*\"(.*?)\"",
                            "\"playable_url_quality_hd\"\\s*:\\s*\"(.*?)\"",
                            "\"playable_url_quality_hd\"\\s*:\\s*'([^']*)'"
                        )
                    )
                )

                /*
                 * SD sources
                 */
                sdCandidates.addAll(
                    collectCandidates(
                        variant,
                        listOf(
                            "\"browser_native_sd_url\"\\s*:\\s*\"(.*?)\"",
                            "\"sd_src_no_ratelimit\"\\s*:\\s*\"(.*?)\"",
                            "\"sd_src\"\\s*:\\s*\"(.*?)\"",
                            "\"playable_url\"\\s*:\\s*\"(.*?)\"",
                            "\"playable_url\"\\s*:\\s*'([^']*)'"
                        )
                    )
                )

                /*
                 * Generic video URL patterns.
                 */
                genericVideoCandidates.addAll(
                    collectCandidates(
                        variant,
                        listOf(
                            "\"video_url\"\\s*:\\s*\"(.*?)\"",
                            "\"videoUrl\"\\s*:\\s*\"(.*?)\"",
                            "\"video_uri\"\\s*:\\s*\"(.*?)\"",
                            "\"source\"\\s*:\\s*\"(https?://[^\"\\\\]+)",
                        )
                    )
                )

                /*
                 * OpenGraph video URLs.
                 *
                 * Handles:
                 *
                 * <meta property="og:video" content="...">
                 * <meta property="og:video:secure_url" content="...">
                 *
                 * as well as the reverse attribute ordering.
                 */
                genericVideoCandidates.addAll(
                    collectOpenGraphVideoUrls(variant)
                )
            }

            /*
             * Put HD first, then SD, then generic candidates.
             */
            val orderedCandidates = LinkedHashSet<String>()

            orderedCandidates.addAll(hdCandidates)
            orderedCandidates.addAll(sdCandidates)
            orderedCandidates.addAll(genericVideoCandidates)

            Log.d(TAG, "HD candidates: ${hdCandidates.size}")
            Log.d(TAG, "SD candidates: ${sdCandidates.size}")
            Log.d(TAG, "Generic candidates: ${genericVideoCandidates.size}")
            Log.d(TAG, "Total unique candidates: ${orderedCandidates.size}")

            /*
             * Validate actual media URLs.
             *
             * We do this BEFORE showing a format to the user.
             *
             * This is important because Facebook pages can contain:
             * - expired CDN URLs
             * - stale URLs
             * - duplicate URLs
             * - tracking URLs
             * - URLs that return HTML instead of media
             */
            val validCandidates = findValidMediaUrls(
                orderedCandidates.toList()
            )

            Log.d(TAG, "Validated media URLs: ${validCandidates.size}")

            /*
             * If we have valid video URLs, construct formats.
             */
            if (validCandidates.isNotEmpty()) {

                val formats =
                    mutableListOf<NetworkDownloadUtils.MediaFormat>()

                /*
                 * The first valid candidate originating from HD extraction
                 * is preferred as HD.
                 */
                val hdValid = findFirstMatching(
                    validCandidates,
                    hdCandidates
                )

                val sdValid = findFirstMatching(
                    validCandidates,
                    sdCandidates
                )

                if (hdValid != null) {
                    formats.add(
                        NetworkDownloadUtils.MediaFormat(
                            id = "fb_hd",
                            url = hdValid,
                            quality = "HD (High Quality)",
                            extension = "mp4",
                            format = "MP4",
                            note = "High Definition"
                        )
                    )
                }

                if (sdValid != null && sdValid != hdValid) {
                    formats.add(
                        NetworkDownloadUtils.MediaFormat(
                            id = "fb_sd",
                            url = sdValid,
                            quality = "SD (Standard Quality)",
                            extension = "mp4",
                            format = "MP4",
                            note = "Data Saving"
                        )
                    )
                }

                /*
                 * If HD/SD specific extraction did not classify the valid URL,
                 * still expose it as a downloadable format rather than losing
                 * an otherwise working Facebook video.
                 */
                if (formats.isEmpty()) {
                    val genericValid = validCandidates.first()

                    formats.add(
                        NetworkDownloadUtils.MediaFormat(
                            id = "fb_video",
                            url = genericValid,
                            quality = "Best Available",
                            extension = "mp4",
                            format = "MP4",
                            note = "Facebook Video"
                        )
                    )
                }

                /*
                 * If only one valid URL was discovered but we already have an
                 * HD/SD format, don't duplicate it.
                 */
                val bestFormat = formats.first()

                val thumbnail = extractThumbnail(htmlVariants)

                return NetworkDownloadUtils.MediaInfo(
                    url = bestFormat.url,
                    fileName =
                        "Facebook_Video_${System.currentTimeMillis()}.mp4",
                    contentType = "video/mp4",
                    contentLength = -1L,
                    extension = "mp4",
                    mediaType = "video",
                    platform = "Facebook",
                    thumbnailUrl = thumbnail,
                    formats = formats
                )
            }

            /*
             * No video was found.
             *
             * Try Facebook image metadata.
             */
            val imageUrl = extractThumbnail(htmlVariants)

            if (!imageUrl.isNullOrBlank()) {

                val validImage =
                    validateImageUrl(imageUrl)

                if (validImage) {
                    return NetworkDownloadUtils.MediaInfo(
                        url = imageUrl,
                        fileName =
                            "Facebook_Image_${System.currentTimeMillis()}.jpg",
                        contentType = "image/jpeg",
                        contentLength = -1L,
                        extension = "jpg",
                        mediaType = "image",
                        platform = "Facebook",
                        thumbnailUrl = imageUrl
                    )
                }
            }

            Log.d(TAG, "No valid Facebook media found")
            null

        } catch (e: Exception) {
            Log.e(TAG, "Facebook extraction failed", e)
            null
        }
    }

    /**
     * Fetch Facebook page and return:
     *
     * Pair(
     *     html,
     *     finalRedirectedUrl
     * )
     */
    private suspend fun fetchFacebookPage(
        url: String,
        userAgent: String
    ): Pair<String, String>? = withContext(Dispatchers.IO) {

        val pageClient = client.newBuilder()
            .connectTimeout(
                VALIDATION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            .readTimeout(
                VALIDATION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header(
                "Accept",
                "text/html,application/xhtml+xml," +
                        "application/xml;q=0.9," +
                        "image/avif,image/webp,*/*;q=0.8"
            )
            .header(
                "Accept-Language",
                "en-US,en;q=0.9"
            )
            .header(
                "Cache-Control",
                "no-cache"
            )
            .header(
                "Referer",
                FACEBOOK_REFERER
            )
            .build()

        try {

            pageClient.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    Log.d(
                        TAG,
                        "Facebook page HTTP ${response.code}"
                    )
                    return@use null
                }

                val body = response.body?.string()

                if (body.isNullOrBlank()) {
                    return@use null
                }

                val finalUrl =
                    response.request.url.toString()

                Pair(body, finalUrl)
            }

        } catch (e: Exception) {
            Log.d(TAG, "Facebook page request failed", e)
            null
        }
    }

    /**
     * Build several HTML variants to handle Facebook escaping.
     */
    private fun buildHtmlVariants(
        html: String
    ): List<String> {

        val variants = LinkedHashSet<String>()

        variants.add(html)

        val decoded = decodeFacebookText(html)

        if (decoded != html) {
            variants.add(decoded)
        }

        /*
         * Facebook sometimes contains escaped quotes inside JSON.
         */
        val quoteDecoded =
            decoded.replace("\\\"", "\"")

        if (quoteDecoded != decoded) {
            variants.add(quoteDecoded)
        }

        return variants.toList()
    }

    /**
     * Extract URL candidates using several regular expressions.
     */
    private fun collectCandidates(
        html: String,
        regexes: List<String>
    ): List<String> {

        val candidates = LinkedHashSet<String>()

        for (regex in regexes) {

            val pattern = try {
                Pattern.compile(
                    regex,
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL
                )
            } catch (e: Exception) {
                Log.e(TAG, "Invalid regex", e)
                continue
            }

            val matcher = pattern.matcher(html)

            while (matcher.find()) {

                val raw = matcher.group(1)

                if (raw.isNullOrBlank()) {
                    continue
                }

                val decoded = decodeFacebookUrl(raw)

                if (isHttpUrl(decoded)) {
                    candidates.add(decoded)
                }
            }
        }

        return candidates.toList()
    }

    /**
     * Extract OpenGraph video URLs regardless of whether the meta attributes
     * are ordered property -> content or content -> property.
     */
    private fun collectOpenGraphVideoUrls(
        html: String
    ): List<String> {

        val candidates = LinkedHashSet<String>()

        val metaPattern = Pattern.compile(
            "<meta\\b[^>]*>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        val matcher = metaPattern.matcher(html)

        while (matcher.find()) {

            val tag = matcher.group()

            if (tag.isNullOrBlank()) {
                continue
            }

            val property =
                extractHtmlAttribute(
                    tag,
                    "property"
                ) ?: extractHtmlAttribute(
                    tag,
                    "name"
                )

            if (property.isNullOrBlank()) {
                continue
            }

            val propertyLower =
                property.lowercase()

            if (
                propertyLower == "og:video" ||
                propertyLower == "og:video:url" ||
                propertyLower == "og:video:secure_url"
            ) {

                val content =
                    extractHtmlAttribute(
                        tag,
                        "content"
                    )

                if (!content.isNullOrBlank()) {

                    val decoded =
                        decodeFacebookUrl(content)

                    if (isHttpUrl(decoded)) {
                        candidates.add(decoded)
                    }
                }
            }
        }

        return candidates.toList()
    }

    private fun extractHtmlAttribute(
        tag: String,
        attribute: String
    ): String? {

        val pattern = Pattern.compile(
            "\\b${Pattern.quote(attribute)}\\s*=\\s*[\"'](.*?)[\"']",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        val matcher = pattern.matcher(tag)

        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    /**
     * Validate all candidates one by one.
     *
     * The first valid candidate is enough to make the video downloadable,
     * but we continue checking a few candidates so HD and SD can both be
     * exposed when available.
     */
    private suspend fun findValidMediaUrls(
        candidates: List<String>
    ): List<String> = withContext(Dispatchers.Default) {
        /*
         * Do not probe Facebook CDN URLs during analysis. Facebook video URLs
         * are signed, short-lived resources. A probe can consume/redirect the
         * URL and, more importantly, the URL may expire between analysis and
         * the actual download. The downloader refreshes the URL immediately
         * before downloading.
         *
         * At analysis time we therefore only require a syntactically valid
         * HTTP(S) URL and remove duplicates.
         */
        candidates
            .asSequence()
            .map(::decodeFacebookUrl)
            .filter(::isHttpUrl)
            .distinct()
            .take(MAX_CANDIDATES_TO_VALIDATE)
            .toList()
    }

    private suspend fun validateImageUrl(
        url: String
    ): Boolean = withContext(Dispatchers.IO) {

        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                BROWSER_USER_AGENT
            )
            .header(
                "Accept",
                "image/avif,image/webp,image/*,*/*;q=0.8"
            )
            .header(
                "Referer",
                FACEBOOK_REFERER
            )
            .header(
                "Range",
                "bytes=0-0"
            )
            .build()

        try {

            client.newCall(request).execute().use { response ->

                if (response.code !in 200..299) {
                    return@use false
                }

                val contentType =
                    response.header("Content-Type")
                        ?.lowercase()
                        ?: ""

                if (contentType.startsWith("image/")) {
                    return@use true
                }

                /*
                 * Some Facebook image CDNs don't provide a useful
                 * Content-Type. Use the URL as a fallback.
                 */
                if (
                    looksLikeImageUrl(url) &&
                    response.body?.contentLength() ?: -1L > 0L
                ) {
                    return@use true
                }

                false
            }

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Find a validated candidate that came from a particular category.
     */
    private fun findFirstMatching(
        validCandidates: List<String>,
        sourceCandidates: Set<String>
    ): String? {

        for (valid in validCandidates) {
            if (sourceCandidates.contains(valid)) {
                return valid
            }
        }

        return null
    }

    /**
     * Thumbnail extraction from OG metadata and Facebook JSON.
     */
    private fun extractThumbnail(
        htmlVariants: List<String>
    ): String? {

        val candidates = LinkedHashSet<String>()

        for (html in htmlVariants) {

            /*
             * OpenGraph images.
             */
            candidates.addAll(
                collectOpenGraphImageUrls(html)
            )

            /*
             * Facebook JSON thumbnails.
             */
            candidates.addAll(
                collectCandidates(
                    html,
                    listOf(
                        "\"thumbnail_url\"\\s*:\\s*\"(.*?)\"",
                        "\"thumbnailSrc\"\\s*:\\s*\"(.*?)\"",
                        "\"poster\"\\s*:\\s*\"(.*?)\"",
                        "\"preferred_thumbnail\"\\s*:\\s*\\{\\s*" +
                                "\"image\"\\s*:\\s*\\{\\s*" +
                                "\"uri\"\\s*:\\s*\"(.*?)\"",
                        "\"image\"\\s*:\\s*\\{\\s*" +
                                "\"uri\"\\s*:\\s*\"(.*?)\""
                    )
                )
            )
        }

        return candidates.firstOrNull()
    }

    private fun collectOpenGraphImageUrls(
        html: String
    ): List<String> {

        val candidates = LinkedHashSet<String>()

        val metaPattern = Pattern.compile(
            "<meta\\b[^>]*>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        val matcher = metaPattern.matcher(html)

        while (matcher.find()) {

            val tag = matcher.group()
                ?: continue

            val property =
                extractHtmlAttribute(
                    tag,
                    "property"
                ) ?: extractHtmlAttribute(
                    tag,
                    "name"
                )

            if (property.isNullOrBlank()) {
                continue
            }

            val propertyLower =
                property.lowercase()

            if (
                propertyLower == "og:image" ||
                propertyLower == "og:image:secure_url"
            ) {

                val content =
                    extractHtmlAttribute(
                        tag,
                        "content"
                    )

                if (!content.isNullOrBlank()) {

                    val decoded =
                        decodeFacebookUrl(content)

                    if (isHttpUrl(decoded)) {
                        candidates.add(decoded)
                    }
                }
            }
        }

        return candidates.toList()
    }

    /**
     * Decode Facebook's common JSON/HTML escaping without using
     * URLDecoder on the complete URL.
     *
     * URLDecoder must NOT be used here because '+' inside a signed
     * Facebook URL can have semantic meaning.
     */
    private fun decodeFacebookUrl(
        value: String
    ): String {

        var result = value.trim()

        repeat(4) {

            val previous = result

            result = result
                .replace("\\/", "/")
                .replace("\\u0026", "&", true)
                .replace("\\u003d", "=", true)
                .replace("\\u003a", ":", true)
                .replace("\\u0025", "%", true)
                .replace("\\u002f", "/", true)
                .replace("\\u003f", "?", true)
                .replace("\\u002b", "+", true)
                .replace("\\u005c", "\\", true)
                .replace("&amp;", "&", true)
                .replace("&quot;", "\"", true)
                .replace("&lt;", "<", true)
                .replace("&gt;", ">", true)
                .trim()

            if (previous == result) {
                return@repeat
            }
        }

        /*
         * Remove surrounding quotes if a malformed source included them.
         */
        if (
            result.length >= 2 &&
            (
                    (result.first() == '"' &&
                            result.last() == '"') ||
                            (result.first() == '\'' &&
                                    result.last() == '\'')
                    )
        ) {
            result = result.substring(
                1,
                result.length - 1
            )
        }

        return result
    }

    private fun decodeFacebookText(
        value: String
    ): String {

        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&", true)
            .replace("\\u003d", "=", true)
            .replace("\\u003a", ":", true)
            .replace("\\u0025", "%", true)
            .replace("\\u002f", "/", true)
            .replace("\\u003f", "?", true)
            .replace("\\u002b", "+", true)
            .replace("&amp;", "&", true)
            .replace("&quot;", "\"", true)
    }

    private fun isHttpUrl(
        value: String
    ): Boolean {

        return value.startsWith("https://") ||
                value.startsWith("http://")
    }

    private fun looksLikeVideoUrl(
        url: String
    ): Boolean {

        val lower = url.lowercase()

        return lower.contains(".mp4") ||
                lower.contains(".m4v") ||
                lower.contains(".webm") ||
                lower.contains("video") ||
                lower.contains("playable_url") ||
                lower.contains("hd_src") ||
                lower.contains("sd_src")
    }

    private fun looksLikeImageUrl(
        url: String
    ): Boolean {

        val lower = url.lowercase()

        return lower.contains(".jpg") ||
                lower.contains(".jpeg") ||
                lower.contains(".png") ||
                lower.contains(".webp") ||
                lower.contains(".gif") ||
                lower.contains("image") ||
                lower.contains("thumbnail")
    }
}
