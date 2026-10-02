package com.example.ytdownloader.api

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
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Your dedicated personal Cobalt instance on Render is first!
    private val defaultCobaltInstances = listOf(
        "https://cobalt-api-ntzc.onrender.com",
        "https://cobalt.meowing.de",
        "https://cobalt.canine.tools"
    )

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
     * Fetches video information and resolves verified download links.
     */
    suspend fun fetchVideoInfo(videoId: String, customServerUrl: String? = null): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
        var videoTitle = "YouTube Video"
        var videoAuthor = "YouTube Creator"
        val lengthSeconds = 240L
        val thumbnailUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

        // Build list of instances to query: user custom server has highest priority
        val instances = mutableListOf<String>()
        if (!customServerUrl.isNullOrBlank()) {
            instances.add(customServerUrl.trim().trimEnd('/'))
        }
        instances.addAll(defaultCobaltInstances)

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

        // 2. Query Cobalt instances for direct stream links
        val formats = mutableListOf<VideoFormat>()

        // 720p HD (Optimal default)
        val stream720 = fetchCobaltStream(instances, videoId, "720", isAudio = false)
        if (stream720 != null) {
            formats.add(
                VideoFormat(
                    id = "720p",
                    qualityLabel = "720p HD",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("720p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("720p", lengthSeconds)),
                    downloadUrl = stream720,
                    isAudioOnly = false
                )
            )
        }

        // 1080p Full HD
        val stream1080 = fetchCobaltStream(instances, videoId, "1080", isAudio = false)
        if (stream1080 != null) {
            formats.add(
                VideoFormat(
                    id = "1080p",
                    qualityLabel = "1080p Full HD",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("1080p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("1080p", lengthSeconds)),
                    downloadUrl = stream1080,
                    isAudioOnly = false
                )
            )
        }

        // 360p SD
        val stream360 = fetchCobaltStream(instances, videoId, "360", isAudio = false) ?: stream720
        if (stream360 != null && stream360 != stream720) {
            formats.add(
                VideoFormat(
                    id = "360p",
                    qualityLabel = "360p SD",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("360p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("360p", lengthSeconds)),
                    downloadUrl = stream360,
                    isAudioOnly = false
                )
            )
        }

        // Audio track (MP3)
        val streamAudio = fetchCobaltStream(instances, videoId, "720", isAudio = true)
        if (streamAudio != null) {
            formats.add(
                VideoFormat(
                    id = "audio_mp3",
                    qualityLabel = "Аудио (MP3)",
                    extension = "mp3",
                    fileSizeBytes = estimateAudioSizeBytes(lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateAudioSizeBytes(lengthSeconds)),
                    downloadUrl = streamAudio,
                    isAudioOnly = true
                )
            )
        }

        if (formats.isEmpty()) {
            return@withContext Result.failure(
                Exception("Сервер Render ещё запускается (пробуждается) или не вернул видео. Подождите 30 секунд и попробуйте снова.")
            )
        }

        Result.success(
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

    private fun fetchCobaltStream(instances: List<String>, videoId: String, quality: String, isAudio: Boolean): String? {
        val targetUrl = "https://www.youtube.com/watch?v=$videoId"

        for (instance in instances) {
            try {
                val json = JsonObject().apply {
                    addProperty("url", targetUrl)
                    if (isAudio) {
                        addProperty("downloadMode", "audio")
                        addProperty("audioFormat", "mp3")
                    } else {
                        addProperty("videoQuality", quality)
                        addProperty("downloadMode", "auto")
                    }
                }

                val req = Request.Builder()
                    .url(instance.trimEnd('/') + "/")
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
        }
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
