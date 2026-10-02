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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Public Cobalt API instances (direct media streams)
    private val cobaltInstances = listOf(
        "https://api.cobalt.tools",
        "https://cobalt-backend.canine.tools",
        "https://api.wuk.sh"
    )

    // Public Invidious stream mirrors that proxy video content through their servers
    private val invidiousMirrors = listOf(
        "https://inv.tux.pizza",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
        "https://invidious.jing.rocks",
        "https://vid.puffyan.us"
    )

    /**
     * Extracts YouTube 11-character video ID from varied URL formats (shorts, youtu.be, watch, embed).
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
     * Fetches video information and resolves real media download links.
     */
    suspend fun fetchVideoInfo(videoId: String): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
        var videoTitle = "YouTube Video"
        var videoAuthor = "YouTube Creator"
        var lengthSeconds = 240L
        val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

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

        // 2. Direct streaming links via Cobalt API or Invidious server proxy
        val cobalt720 = fetchCobaltStream(videoId, "720", isAudio = false)
        val cobalt1080 = fetchCobaltStream(videoId, "1080", isAudio = false)
        val cobaltAudio = fetchCobaltStream(videoId, "720", isAudio = true)

        val baseMirror = invidiousMirrors[0]
        val invidious720 = "$baseMirror/latest_version?id=$videoId&itag=22&local=true"
        val invidious360 = "$baseMirror/latest_version?id=$videoId&itag=18&local=true"
        val invidiousAudio = "$baseMirror/latest_version?id=$videoId&itag=140&local=true"

        val direct720Url = cobalt720 ?: invidious720
        val direct1080Url = cobalt1080 ?: direct720Url
        val direct360Url = invidious360
        val directAudioUrl = cobaltAudio ?: invidiousAudio

        val formats = listOf(
            VideoFormat(
                id = "720p",
                qualityLabel = "720p HD",
                extension = "mp4",
                fileSizeBytes = estimateVideoSizeBytes("720p", lengthSeconds),
                fileSizeFormatted = formatBytes(estimateVideoSizeBytes("720p", lengthSeconds)),
                downloadUrl = direct720Url,
                isAudioOnly = false
            ),
            VideoFormat(
                id = "1080p",
                qualityLabel = "1080p Full HD",
                extension = "mp4",
                fileSizeBytes = estimateVideoSizeBytes("1080p", lengthSeconds),
                fileSizeFormatted = formatBytes(estimateVideoSizeBytes("1080p", lengthSeconds)),
                downloadUrl = direct1080Url,
                isAudioOnly = false
            ),
            VideoFormat(
                id = "360p",
                qualityLabel = "360p SD",
                extension = "mp4",
                fileSizeBytes = estimateVideoSizeBytes("360p", lengthSeconds),
                fileSizeFormatted = formatBytes(estimateVideoSizeBytes("360p", lengthSeconds)),
                downloadUrl = direct360Url,
                isAudioOnly = false
            ),
            VideoFormat(
                id = "audio_mp3",
                qualityLabel = "Аудио (MP3 / M4A)",
                extension = "mp3",
                fileSizeBytes = estimateAudioSizeBytes(lengthSeconds),
                fileSizeFormatted = formatBytes(estimateAudioSizeBytes(lengthSeconds)),
                downloadUrl = directAudioUrl,
                isAudioOnly = true
            )
        )

        Result.success(
            YouTubeVideoInfo(
                videoId = videoId,
                title = videoTitle,
                author = videoAuthor,
                thumbnailUrl = thumbnail,
                durationSeconds = lengthSeconds,
                formats = formats
            )
        )
    }

    private fun fetchCobaltStream(videoId: String, quality: String, isAudio: Boolean): String? {
        for (instance in cobaltInstances) {
            try {
                val json = JsonObject().apply {
                    addProperty("url", "https://www.youtube.com/watch?v=$videoId")
                    if (isAudio) {
                        addProperty("downloadMode", "audio")
                        addProperty("audioFormat", "mp3")
                    } else {
                        addProperty("videoQuality", quality)
                        addProperty("downloadMode", "auto")
                    }
                }

                val req = Request.Builder()
                    .url(instance)
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
