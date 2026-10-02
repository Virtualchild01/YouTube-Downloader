package com.example.ytdownloader.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Extracts YouTube 11-character video ID from varied URL formats (shorts, youtu.be, watch, embed, share urls).
     */
    fun extractVideoId(text: String): String? {
        val pattern = Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?.*?v=|shorts/|embed/|v/)|youtu\\.be/)([a-zA-Z0-9_-]{11})",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(text)
        return if (matcher.find()) {
            matcher.group(1)
        } else null
    }

    /**
     * Fetches video information and resolves direct media stream links on-device (client-side).
     */
    suspend fun fetchVideoInfo(videoId: String, customServerUrl: String? = null): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
        var videoTitle = "YouTube Video"
        var videoAuthor = "YouTube Creator"
        var lengthSeconds = 240L
        var thumbnailUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

        // 1. Fetch official title & author via YouTube oEmbed API
        try {
            val oEmbedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val req = Request.Builder().url(oEmbedUrl).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val obj = JsonParser.parseString(body).asJsonObject
                    videoTitle = obj.get("title")?.asString ?: videoTitle
                    videoAuthor = obj.get("author_name")?.asString ?: videoAuthor
                }
            }
        } catch (_: Exception) {}

        // 2. Client-side native InnerTube extraction (Runs directly on user device with user IP)
        val nativeFormats = fetchClientSideStreams(videoId)
        if (nativeFormats.isNotEmpty()) {
            return@withContext Result.success(
                YouTubeVideoInfo(
                    videoId = videoId,
                    title = videoTitle,
                    author = videoAuthor,
                    thumbnailUrl = thumbnailUrl,
                    durationSeconds = lengthSeconds,
                    formats = nativeFormats
                )
            )
        }

        // 3. Fallback to custom Cobalt server if user specified one in settings
        if (!customServerUrl.isNullOrBlank()) {
            val customStream = fetchCobaltStream(customServerUrl, videoId, "720")
            if (customStream != null) {
                val formats = listOf(
                    VideoFormat(
                        id = "720p",
                        qualityLabel = "720p HD",
                        extension = "mp4",
                        fileSizeBytes = estimateVideoSizeBytes("720p", lengthSeconds),
                        fileSizeFormatted = formatBytes(estimateVideoSizeBytes("720p", lengthSeconds)),
                        downloadUrl = customStream,
                        isAudioOnly = false
                    )
                )
                return@withContext Result.success(
                    YouTubeVideoInfo(
                        videoId = videoId,
                        title = videoTitle,
                        author = videoAuthor,
                        thumbnailUrl = thumbnailUrl,
                        durationSeconds = lengthSeconds,
                        formats = formats
                    )
                )
            }
        }

        Result.failure(Exception("YouTube заблокировал доступ к потоку. Убедитесь, что включен VPN."))
    }

    /**
     * Directly queries YouTube's Embedded Web & VR InnerTube clients from user's device.
     * These endpoints deliver unthrottled direct progressive MP4 streams.
     */
    private fun fetchClientSideStreams(videoId: String): List<VideoFormat> {
        val clientConfigs = listOf(
            // Web Embedded Player client (Embed iframe)
            Triple(
                "WEB_EMBEDDED_PLAYER",
                "1.20240101.01.00",
                JsonObject().apply {
                    val c = JsonObject().apply {
                        addProperty("clientName", "WEB_EMBEDDED_PLAYER")
                        addProperty("clientVersion", "1.20240101.01.00")
                        addProperty("hl", "ru")
                        addProperty("gl", "RU")
                    }
                    val thirdParty = JsonObject().apply {
                        addProperty("embedUrl", "https://www.youtube.com")
                    }
                    add("client", c)
                    add("thirdParty", thirdParty)
                }
            ),
            // Android VR client
            Triple(
                "ANDROID_VR",
                "1.37",
                JsonObject().apply {
                    val c = JsonObject().apply {
                        addProperty("clientName", "ANDROID_VR")
                        addProperty("clientVersion", "1.37")
                        addProperty("hl", "en")
                        addProperty("gl", "US")
                    }
                    add("client", c)
                }
            )
        )

        for ((_, _, contextObj) in clientConfigs) {
            try {
                val json = JsonObject().apply {
                    add("context", contextObj)
                    addProperty("videoId", videoId)
                }

                val req = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.youtube.com/embed/$videoId")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use

                    val body = resp.body?.string() ?: return@use
                    val root = JsonParser.parseString(body).asJsonObject

                    val playability = root.getAsJsonObject("playabilityStatus")
                    val status = playability?.get("status")?.asString ?: ""
                    if (status != "OK") return@use

                    val streamingData = root.getAsJsonObject("streamingData") ?: return@use
                    val formatsArray = streamingData.getAsJsonArray("formats") ?: JsonArray()
                    val adaptiveArray = streamingData.getAsJsonArray("adaptiveFormats") ?: JsonArray()

                    val result = mutableListOf<VideoFormat>()

                    // Progressive streams (containing both video and audio track)
                    for (elem in formatsArray) {
                        val fmt = elem.asJsonObject
                        val url = fmt.get("url")?.asString ?: continue
                        val qualityLabel = fmt.get("qualityLabel")?.asString ?: "MP4"
                        val itag = fmt.get("itag")?.asInt ?: 0
                        val contentLength = fmt.get("contentLength")?.asLong ?: 0L

                        val displayQuality = if (qualityLabel.contains("720")) {
                            "720p HD"
                        } else if (qualityLabel.contains("360")) {
                            "360p SD"
                        } else {
                            qualityLabel
                        }

                        result.add(
                            VideoFormat(
                                id = "prog_$itag",
                                qualityLabel = displayQuality,
                                extension = "mp4",
                                fileSizeBytes = contentLength,
                                fileSizeFormatted = formatBytes(contentLength),
                                downloadUrl = url,
                                isAudioOnly = false
                            )
                        )
                    }

                    // Audio-only stream (itag 140: AAC 128kbps)
                    for (elem in adaptiveArray) {
                        val fmt = elem.asJsonObject
                        val itag = fmt.get("itag")?.asInt ?: 0
                        val url = fmt.get("url")?.asString ?: continue

                        if (itag == 140) {
                            val contentLength = fmt.get("contentLength")?.asLong ?: 0L
                            result.add(
                                VideoFormat(
                                    id = "audio_140",
                                    qualityLabel = "Аудио (M4A / MP3)",
                                    extension = "m4a",
                                    fileSizeBytes = contentLength,
                                    fileSizeFormatted = formatBytes(contentLength),
                                    downloadUrl = url,
                                    isAudioOnly = true
                                )
                            )
                            break
                        }
                    }

                    if (result.isNotEmpty()) {
                        return result
                    }
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun fetchCobaltStream(serverUrl: String, videoId: String, quality: String): String? {
        val targetUrl = "https://www.youtube.com/watch?v=$videoId"
        try {
            val json = JsonObject().apply {
                addProperty("url", targetUrl)
                addProperty("videoQuality", quality)
                addProperty("downloadMode", "auto")
            }

            val req = Request.Builder()
                .url(serverUrl.trimEnd('/') + "/")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JsonParser.parseString(body).asJsonObject
                    val status = root.get("status")?.asString ?: ""
                    val streamUrl = root.get("url")?.asString ?: ""

                    if ((status == "stream" || status == "redirect" || status == "tunnel") && streamUrl.startsWith("http")) {
                        return streamUrl
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun estimateVideoSizeBytes(quality: String, durationSec: Long): Long {
        val bitrateBps = when {
            quality.contains("1080") -> 3_500_000L
            quality.contains("720") -> 2_000_000L
            quality.contains("480") -> 1_000_000L
            else -> 600_000L
        }
        return (durationSec * bitrateBps) / 8L
    }

    private fun estimateAudioSizeBytes(durationSec: Long): Long {
        return (durationSec * 160_000L) / 8L
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "≈ %.2f ГБ", mb / 1024.0)
        } else {
            String.format(Locale.US, "≈ %.1f МБ", mb)
        }
    }
}
