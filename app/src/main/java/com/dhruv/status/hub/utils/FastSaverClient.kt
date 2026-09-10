package com.dhruv.status.hub.utils

import com.dhruv.status.hub.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException

/**
 * Resolves Facebook media and Instagram Stories.
 *
 * DEBUG:
 * Android -> FastSaver directly using the test API key.
 *
 * RELEASE:
 * Android -> Status Hub backend -> FastSaver.
 *
 * The production FastSaver API key is never placed in the
 * release APK.
 */
class FastSaverClient(
    private val client: OkHttpClient
) {

    companion object {

        private const val FASTSAVER_BASE_URL =
            "https://api.fastsaver.io/v1/fetch"

        private const val BACKEND_RESOLVE_PATH =
            "/v1/resolve"

        private const val USER_AGENT =
            "Mozilla/5.0 (Android) AppleWebKit/537.36 " +
                    "Chrome/121.0 Safari/537.36"

        private val JSON_MEDIA_TYPE =
            "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Test FastSaver API key.
     *
     * This value exists only in DEBUG builds.
     *
     * RELEASE contains an empty value.
     */
    private val apiKey: String
        get() = BuildConfig.FASTSAVER_API_KEY

    /**
     * Backend URL used by RELEASE builds.
     *
     * Example:
     *
     * https://api.statushub.example
     *
     * Do not include /v1/resolve here.
     */
    private val backendBaseUrl: String
        get() = BuildConfig.STATUS_HUB_API_BASE_URL
            .trim()
            .trimEnd('/')

    // ------------------------------------------------------------
    // FACEBOOK
    // ------------------------------------------------------------

    suspend fun resolveFacebook(
        inputUrl: String
    ): NetworkDownloadUtils.MediaInfo {

        return resolveVideoLike(
            inputUrl = inputUrl,
            platform = "facebook",
            defaultFilePrefix = "Facebook_Video"
        )
    }

    // ------------------------------------------------------------
    // INSTAGRAM STORY
    // ------------------------------------------------------------

    suspend fun resolveInstagramStory(
        inputUrl: String
    ): NetworkDownloadUtils.MediaInfo {

        val trimmedUrl = inputUrl.trim()

        if (trimmedUrl.isBlank()) {
            throw IOException(
                "Instagram Story URL is empty."
            )
        }

        validateInstagramStoryUrl(trimmedUrl)

        val json =
            requestResolver(trimmedUrl)

        if (!json.optBoolean("ok", false)) {

            throw IOException(
                json.optString("detail")
                    .takeIf { it.isNotBlank() }
                    ?: json.optString("error")
                        .takeIf { it.isNotBlank() }
                    ?: "FastSaver could not resolve this Instagram Story."
            )
        }

        val type =
            json.optString("type")
                .trim()
                .lowercase()

        if (type == "album") {

            throw IOException(
                "Instagram Story returned multiple media items, " +
                        "which this download flow does not support."
            )
        }

        val downloadUrl =
            json.optString("download_url")
                .trim()

        if (downloadUrl.isBlank()) {

            throw IOException(
                "FastSaver returned no download URL for this Instagram Story."
            )
        }

        val isVideo =
            type == "video"

        if (!isVideo && type != "image") {

            throw IOException(
                "FastSaver returned an unsupported Instagram Story type."
            )
        }

        val thumbnailUrl =
            json.optString("thumbnail_url")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: if (!isVideo) {
                    downloadUrl
                } else {
                    null
                }

        val caption =
            json.optString("caption")
                .trim()
                .takeIf { it.isNotBlank() }

        val extension =
            if (isVideo) {
                "mp4"
            } else {
                "jpg"
            }

        val fileName =
            buildFileName(
                caption = caption,
                prefix = "Instagram_Story",
                extension = extension
            )

        val format =
            NetworkDownloadUtils.MediaFormat(
                id = "instagram-story",
                url = downloadUrl,
                quality = "Original",
                extension = extension,
                format = if (isVideo) "MP4" else "JPEG",
                size = -1L,
                isAudio = false,
                hasVideo = isVideo,
                hasAudio = isVideo,
                note = ""
            )

        return NetworkDownloadUtils.MediaInfo(
            url = trimmedUrl,
            fileName = fileName,
            contentType =
                if (isVideo) {
                    "video/mp4"
                } else {
                    "image/jpeg"
                },
            contentLength = -1L,
            extension = extension,
            mediaType =
                if (isVideo) {
                    "video"
                } else {
                    "image"
                },
            platform = "instagram",
            thumbnailUrl = thumbnailUrl,
            audioUrl = null,
            formats = listOf(format)
        )
    }

    // ------------------------------------------------------------
    // COMMON VIDEO RESOLUTION
    // ------------------------------------------------------------

    private suspend fun resolveVideoLike(
        inputUrl: String,
        platform: String,
        defaultFilePrefix: String
    ): NetworkDownloadUtils.MediaInfo {

        val trimmedUrl =
            inputUrl.trim()

        if (trimmedUrl.isBlank()) {
            throw IOException("URL is empty.")
        }

        val json =
            requestResolver(trimmedUrl)

        if (!json.optBoolean("ok", false)) {

            throw IOException(
                json.optString("detail")
                    .takeIf { it.isNotBlank() }
                    ?: json.optString("error")
                        .takeIf { it.isNotBlank() }
                    ?: "FastSaver could not resolve this media."
            )
        }

        val downloadUrl =
            json.optString("download_url")
                .trim()

        if (downloadUrl.isBlank()) {

            throw IOException(
                "FastSaver returned no download URL."
            )
        }

        val thumbnailUrl =
            json.optString("thumbnail_url")
                .trim()
                .takeIf { it.isNotBlank() }

        val caption =
            json.optString("caption")
                .trim()
                .takeIf { it.isNotBlank() }

        val fileName =
            buildFileName(
                caption = caption,
                prefix = defaultFilePrefix,
                extension = "mp4"
            )

        val format =
            NetworkDownloadUtils.MediaFormat(
                id = "$platform-video",
                url = downloadUrl,
                quality = "Best available",
                extension = "mp4",
                format = "MP4",
                size = -1L,
                isAudio = false,
                hasVideo = true,
                hasAudio = true,
                note = ""
            )

        return NetworkDownloadUtils.MediaInfo(
            url = trimmedUrl,
            fileName = fileName,
            contentType = "video/mp4",
            contentLength = -1L,
            extension = "mp4",
            mediaType = "video",
            platform = platform,
            thumbnailUrl = thumbnailUrl,
            audioUrl = null,
            formats = listOf(format)
        )
    }

    // ------------------------------------------------------------
    // RESOLVER ROUTING
    // ------------------------------------------------------------

    /**
     * DEBUG:
     *     Android -> FastSaver
     *
     * RELEASE:
     *     Android -> Status Hub backend
     */
    private fun requestResolver(
        inputUrl: String
    ): JSONObject {

        return if (BuildConfig.DEBUG) {

            requestFastSaverDirectly(
                inputUrl = inputUrl
            )

        } else {

            requestBackend(
                inputUrl = inputUrl
            )
        }
    }

    // ------------------------------------------------------------
    // DEBUG -> FASTSAVER
    // ------------------------------------------------------------

    private fun requestFastSaverDirectly(
        inputUrl: String
    ): JSONObject {

        requireApiKey()

        val endpoint =
            FASTSAVER_BASE_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "url",
                    inputUrl
                )
                .build()

        val request =
            Request.Builder()
                .url(endpoint)
                .header(
                    "X-Api-Key",
                    apiKey
                )
                .header(
                    "Accept",
                    "application/json"
                )
                .header(
                    "User-Agent",
                    USER_AGENT
                )
                .get()
                .build()

        return executeResolverRequest(
            request = request,
            sourceName = "FastSaver"
        )
    }

    // ------------------------------------------------------------
    // RELEASE -> STATUS HUB BACKEND
    // ------------------------------------------------------------

    private fun requestBackend(
        inputUrl: String
    ): JSONObject {

        if (backendBaseUrl.isBlank()) {

            throw IOException(
                "Status Hub backend URL is not configured."
            )
        }

        val endpoint =
            try {

                java.net.URI(
                    backendBaseUrl +
                            BACKEND_RESOLVE_PATH
                )

            } catch (e: Exception) {

                throw IOException(
                    "Status Hub backend URL is invalid.",
                    e
                )
            }

        val json =
            JSONObject()

        json.put(
            "url",
            inputUrl
        )

        val requestBody =
            json.toString()
                .toRequestBody(
                    JSON_MEDIA_TYPE
                )

        val request =
            Request.Builder()
                .url(endpoint.toURL())
                .header(
                    "Accept",
                    "application/json"
                )
                .header(
                    "Content-Type",
                    "application/json"
                )
                .header(
                    "User-Agent",
                    USER_AGENT
                )
                .post(requestBody)
                .build()

        return executeResolverRequest(
            request = request,
            sourceName = "Status Hub backend"
        )
    }

    // ------------------------------------------------------------
    // HTTP EXECUTION
    // ------------------------------------------------------------

    private fun executeResolverRequest(
        request: Request,
        sourceName: String
    ): JSONObject {

        client.newCall(request)
            .execute()
            .use { response ->

                val body =
                    response.body
                        ?.string()
                        .orEmpty()

                if (!response.isSuccessful) {

                    throw IOException(
                        parseApiError(body)
                            ?: "$sourceName returned HTTP ${response.code}."
                    )
                }

                if (body.isBlank()) {

                    throw IOException(
                        "$sourceName returned an empty response."
                    )
                }

                return try {

                    JSONObject(body)

                } catch (e: Exception) {

                    throw IOException(
                        "$sourceName returned invalid JSON.",
                        e
                    )
                }
            }
    }

    // ------------------------------------------------------------
    // API KEY
    // ------------------------------------------------------------

    private fun requireApiKey() {

        if (apiKey.isBlank()) {

            throw IOException(
                "FastSaver API key is not configured."
            )
        }
    }

    // ------------------------------------------------------------
    // INSTAGRAM STORY VALIDATION
    // ------------------------------------------------------------

    private fun validateInstagramStoryUrl(
        url: String
    ) {

        val parsed =
            try {

                url.toHttpUrl()

            } catch (e: Exception) {

                throw IOException(
                    "Invalid Instagram Story URL.",
                    e
                )
            }

        val host =
            parsed.host.lowercase()

        val isInstagramHost =
            host == "instagram.com" ||
                    host.endsWith(".instagram.com")

        if (!isInstagramHost) {

            throw IOException(
                "This is not an Instagram URL."
            )
        }

        val path =
            parsed.encodedPath
                .lowercase()

        if (!path.startsWith("/stories/")) {

            throw IOException(
                "This is not an Instagram Story URL."
            )
        }
    }

    // ------------------------------------------------------------
    // ERROR PARSING
    // ------------------------------------------------------------

    private fun parseApiError(
        body: String
    ): String? {

        if (body.isBlank()) {
            return null
        }

        return try {

            val json =
                JSONObject(body)

            json.optString("detail")
                .takeIf { it.isNotBlank() }
                ?: json.optString("error")
                    .takeIf { it.isNotBlank() }
                ?: json.optString("message")
                    .takeIf { it.isNotBlank() }

        } catch (_: Exception) {

            null
        }
    }

    // ------------------------------------------------------------
    // FILE NAME
    // ------------------------------------------------------------

    private fun buildFileName(
        caption: String?,
        prefix: String,
        extension: String
    ): String {

        val base =
            caption
                ?.replace(
                    Regex("[^A-Za-z0-9 _-]"),
                    ""
                )
                ?.trim()
                ?.replace(
                    Regex("\\s+"),
                    " "
                )
                ?.take(70)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "${prefix}_${System.currentTimeMillis()}"

        return "$base.$extension"
    }
}