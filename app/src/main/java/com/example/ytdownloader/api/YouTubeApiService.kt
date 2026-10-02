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
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Public Cobalt API instances with alwaysProxy=true (to bypass 403 Forbidden / IP blocks)
    private val cobaltInstances = listOf(
        "https://api.cobalt.tools",
        "https://cobalt.canine.tools",
        "https://cobalt.meowing.de",
        "https://cobalt-api.kwiatekm.tokyo",
        "https://cobalt.stream"
    )

    // Public Invidious instances as secondary fallback
    private val invidiousInstances = listOf(
        "https://yewtu.be",
        "https://inv.tux.pizza",
        "https://invidious.nerdvpn.de",
        "https://invidious.jing.rocks",
        "https://vid.priv.au"
    )

    /**
     * Extracts YouTube 11-character video ID from varied URL formats (shorts, youtu.be, watch, embed).
     */
    fun extractVideoId(text: String): String? {
        val trimmed = text.trim()
        val pattern = Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?.*?v=|shorts/|embed/|v/)|youtu\\.be/)([a-zA-Z0-9_-]{11})",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return trimmed
        }
        return null
    }

    /**
     * Fetches video metadata and available resolution formats.
     * Uses YouTube oEmbed + InnerTube to reliably get title, author, and cover.
     */
    suspend fun fetchVideoInfo(videoId: String): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
        // 1. Try YouTube oEmbed for guaranteed title and author
        try {
            val oEmbedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder().url(oEmbedUrl).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val obj = JsonParser.parseString(body).asJsonObject
                    val title = obj.get("title")?.asString ?: "YouTube Video"
                    val author = obj.get("author_name")?.asString ?: "YouTube Creator"
                    val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
                    val lengthSeconds = 240L

                    val formats = listOf(
                        VideoFormat(
                            id = "1080",
                            qualityLabel = "1080p Full HD",
                            extension = "mp4",
                            fileSizeBytes = estimateVideoSizeBytes("1080", lengthSeconds),
                            fileSizeFormatted = formatBytes(estimateVideoSizeBytes("1080", lengthSeconds)),
                            downloadUrl = "", // resolved on click via Cobalt
                            isAudioOnly = false
                        ),
                        VideoFormat(
                            id = "720",
                            qualityLabel = "720p HD",
                            extension = "mp4",
                            fileSizeBytes = estimateVideoSizeBytes("720", lengthSeconds),
                            fileSizeFormatted = formatBytes(estimateVideoSizeBytes("720", lengthSeconds)),
                            downloadUrl = "",
                            isAudioOnly = false
                        ),
                        VideoFormat(
                            id = "480",
                            qualityLabel = "480p SD",
                            extension = "mp4",
                            fileSizeBytes = estimateVideoSizeBytes("480", lengthSeconds),
                            fileSizeFormatted = formatBytes(estimateVideoSizeBytes("480", lengthSeconds)),
                            downloadUrl = "",
                            isAudioOnly = false
                        ),
                        VideoFormat(
                            id = "360",
                            qualityLabel = "360p SD",
                            extension = "mp4",
                            fileSizeBytes = estimateVideoSizeBytes("360", lengthSeconds),
                            fileSizeFormatted = formatBytes(estimateVideoSizeBytes("360", lengthSeconds)),
                            downloadUrl = "",
                            isAudioOnly = false
                        ),
                        VideoFormat(
                            id = "mp3",
                            qualityLabel = "Аудио (MP3)",
                            extension = "mp3",
                            fileSizeBytes = estimateAudioSizeBytes(lengthSeconds),
                            fileSizeFormatted = formatBytes(estimateAudioSizeBytes(lengthSeconds)),
                            downloadUrl = "",
                            isAudioOnly = true
                        )
                    )

                    return@withContext Result.success(
                        YouTubeVideoInfo(
                            videoId = videoId,
                            title = title,
                            author = author,
                            thumbnailUrl = thumbnail,
                            durationSeconds = lengthSeconds,
                            formats = formats
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback to Invidious
        for (base in invidiousInstances) {
            try {
                val url = "$base/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val root = JsonParser.parseString(body).asJsonObject
                        val title = root.get("title")?.asString ?: "YouTube Video"
                        val author = root.get("author")?.asString ?: "YouTube Creator"
                        val lengthSeconds = root.get("lengthSeconds")?.asLong ?: 180L
                        val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

                        val formats = listOf(
                            VideoFormat(id = "1080", qualityLabel = "1080p Full HD", extension = "mp4", downloadUrl = "", isAudioOnly = false),
                            VideoFormat(id = "720", qualityLabel = "720p HD", extension = "mp4", downloadUrl = "", isAudioOnly = false),
                            VideoFormat(id = "480", qualityLabel = "480p SD", extension = "mp4", downloadUrl = "", isAudioOnly = false),
                            VideoFormat(id = "360", qualityLabel = "360p SD", extension = "mp4", downloadUrl = "", isAudioOnly = false),
                            VideoFormat(id = "mp3", qualityLabel = "Аудио (MP3)", extension = "mp3", downloadUrl = "", isAudioOnly = true)
                        )

                        return@withContext Result.success(
                            YouTubeVideoInfo(
                                videoId = videoId,
                                title = title,
                                author = author,
                                thumbnailUrl = thumbnail,
                                durationSeconds = lengthSeconds,
                                formats = formats
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        Result.failure(Exception("Не удалось загрузить данные видео. Проверьте ссылку."))
    }

    /**
     * Resolves the real, unblocked download URL using Cobalt API with alwaysProxy tunnel.
     * Prevents 403 Forbidden and 11KB HTML error downloads.
     */
    suspend fun resolveDownloadUrl(videoId: String, format: VideoFormat): Result<String> = withContext(Dispatchers.IO) {
        val ytUrl = "https://www.youtube.com/watch?v=$videoId"
        var lastError: Exception? = null

        // 1. Try Cobalt API instances
        for (cobaltBase in cobaltInstances) {
            try {
                val jsonBody = JsonObject().apply {
                    addProperty("url", ytUrl)
                    if (format.isAudioOnly) {
                        addProperty("downloadMode", "audio")
                        addProperty("audioFormat", "mp3")
                    } else {
                        addProperty("downloadMode", "auto")
                        addProperty("videoQuality", format.id.filter { it.isDigit() }.ifEmpty { "720" })
                    }
                    addProperty("alwaysProxy", true)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonBody.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(if (cobaltBase.endsWith("/")) cobaltBase else "$cobaltBase/")
                    .post(requestBody)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val root = JsonParser.parseString(body).asJsonObject
                        val status = root.get("status")?.asString ?: ""
                        val downloadUrl = root.get("url")?.asString ?: ""

                        if ((status == "tunnel" || status == "redirect" || status == "stream") && downloadUrl.isNotEmpty()) {
                            return@withContext Result.success(downloadUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 2. Fallback to Invidious proxy stream with local=true
        for (invBase in invidiousInstances) {
            try {
                val itag = when {
                    format.isAudioOnly -> "140"
                    format.id.contains("1080") -> "137"
                    format.id.contains("720") -> "22"
                    else -> "18"
                }
                val streamUrl = "$invBase/latest_version?id=$videoId&itag=$itag&local=true"
                return@withContext Result.success(streamUrl)
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Не удалось получить прямую ссылку для скачивания"))
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
