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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
        // Also check if user just pasted an 11-char ID
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return trimmed
        }
        return null
    }

    /**
     * Fetches video metadata and download formats.
     * Strategy:
     * 1. Official YouTube InnerTube ANDROID Client API (direct, unencrypted, fast, native)
     * 2. Invidious proxy instances (fallback)
     * 3. YouTube oEmbed (guaranteed metadata fallback)
     */
    suspend fun fetchVideoInfo(videoId: String): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
        // Strategy 1: YouTube InnerTube ANDROID API
        try {
            val innerTubeResult = fetchInnerTube(videoId)
            if (innerTubeResult != null && innerTubeResult.formats.isNotEmpty()) {
                return@withContext Result.success(innerTubeResult)
            }
        } catch (_: Exception) {
        }

        // Strategy 2: Invidious Instances
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
                        val parsed = parseInvidiousResponse(base, videoId, body)
                        if (parsed != null && parsed.formats.isNotEmpty()) {
                            return@withContext Result.success(parsed)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Strategy 3: oEmbed metadata fallback
        try {
            val oEmbedResult = fetchOEmbedFallback(videoId)
            if (oEmbedResult != null) {
                return@withContext Result.success(oEmbedResult)
            }
        } catch (_: Exception) {
        }

        Result.failure(Exception("Не удалось загрузить данные видео. Проверьте ссылку и подключение к сети."))
    }

    /**
     * Official YouTube InnerTube API impersonating the Android official client.
     * Yields direct playable stream URLs without cipher encryption.
     */
    private fun fetchInnerTube(videoId: String): YouTubeVideoInfo? {
        val jsonPayload = JsonObject().apply {
            addProperty("videoId", videoId)
            val context = JsonObject()
            val clientObj = JsonObject().apply {
                addProperty("clientName", "ANDROID")
                addProperty("clientVersion", "19.29.35")
                addProperty("androidSdkVersion", 34)
                addProperty("hl", "ru")
                addProperty("gl", "RU")
            }
            context.add("client", clientObj)
            add("context", context)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player")
            .post(requestBody)
            .header("User-Agent", "com.google.android.youtube/19.29.35 (Linux; U; Android 14; Mobile)")
            .header("X-YouTube-Client-Name", "3")
            .header("X-YouTube-Client-Version", "19.29.35")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = JsonParser.parseString(body).asJsonObject

            val videoDetails = root.getAsJsonObject("videoDetails") ?: return null
            val title = videoDetails.get("title")?.asString ?: "YouTube Video"
            val author = videoDetails.get("author")?.asString ?: "YouTube Creator"
            val lengthSeconds = videoDetails.get("lengthSeconds")?.asString?.toLongOrNull() ?: 180L

            val thumbnails = videoDetails.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
            val thumbnail = thumbnails?.lastOrNull()?.asJsonObject?.get("url")?.asString
                ?: "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

            val streamingData = root.getAsJsonObject("streamingData") ?: return null
            val formatsList = mutableListOf<VideoFormat>()

            // Progressive formats (combined video + audio, e.g. 720p, 360p)
            if (streamingData.has("formats")) {
                val formatsArr = streamingData.getAsJsonArray("formats")
                for (elem in formatsArr) {
                    val stream = elem.asJsonObject
                    val rawUrl = stream.get("url")?.asString ?: continue
                    val quality = stream.get("qualityLabel")?.asString ?: "720p"
                    val itag = stream.get("itag")?.asString ?: quality
                    val contentLength = stream.get("contentLength")?.asString?.toLongOrNull() ?: 0L
                    val sizeBytes = if (contentLength > 0L) contentLength else estimateVideoSizeBytes(quality, lengthSeconds)

                    val cleanLabel = when {
                        quality.contains("720") -> "720p HD (Видео + Звук)"
                        quality.contains("360") -> "360p SD (Видео + Звук)"
                        else -> "$quality (Видео + Звук)"
                    }

                    formatsList.add(
                        VideoFormat(
                            id = itag,
                            qualityLabel = cleanLabel,
                            extension = "mp4",
                            fileSizeBytes = sizeBytes,
                            fileSizeFormatted = formatBytes(sizeBytes),
                            downloadUrl = rawUrl,
                            isAudioOnly = false
                        )
                    )
                }
            }

            // Adaptive formats (1080p and Audio)
            if (streamingData.has("adaptiveFormats")) {
                val adaptiveArr = streamingData.getAsJsonArray("adaptiveFormats")
                for (elem in adaptiveArr) {
                    val stream = elem.asJsonObject
                    val rawUrl = stream.get("url")?.asString ?: continue
                    val mimeType = stream.get("mimeType")?.asString ?: ""
                    val itag = stream.get("itag")?.asString ?: ""
                    val contentLength = stream.get("contentLength")?.asString?.toLongOrNull() ?: 0L

                    // 1080p
                    if (mimeType.contains("video/mp4") && stream.get("qualityLabel")?.asString?.contains("1080") == true
                        && formatsList.none { it.qualityLabel.contains("1080") }
                    ) {
                        val sizeBytes = if (contentLength > 0L) contentLength else estimateVideoSizeBytes("1080p", lengthSeconds)
                        formatsList.add(
                            VideoFormat(
                                id = itag.ifEmpty { "137" },
                                qualityLabel = "1080p Full HD",
                                extension = "mp4",
                                fileSizeBytes = sizeBytes,
                                fileSizeFormatted = formatBytes(sizeBytes),
                                downloadUrl = rawUrl,
                                isAudioOnly = false
                            )
                        )
                    }

                    // Best Audio (M4A)
                    if (mimeType.contains("audio/mp4") && formatsList.none { it.isAudioOnly }) {
                        val sizeBytes = if (contentLength > 0L) contentLength else estimateAudioSizeBytes(lengthSeconds)
                        formatsList.add(
                            VideoFormat(
                                id = itag.ifEmpty { "140" },
                                qualityLabel = "Аудио (M4A / MP3)",
                                extension = "m4a",
                                fileSizeBytes = sizeBytes,
                                fileSizeFormatted = formatBytes(sizeBytes),
                                downloadUrl = rawUrl,
                                isAudioOnly = true
                            )
                        )
                    }
                }
            }

            formatsList.sortByDescending {
                when {
                    it.isAudioOnly -> -1
                    it.qualityLabel.contains("1080") -> 1080
                    it.qualityLabel.contains("720") -> 720
                    it.qualityLabel.contains("480") -> 480
                    it.qualityLabel.contains("360") -> 360
                    else -> 0
                }
            }

            return YouTubeVideoInfo(
                videoId = videoId,
                title = title,
                author = author,
                thumbnailUrl = thumbnail,
                durationSeconds = lengthSeconds,
                formats = formatsList
            )
        }
    }

    private fun parseInvidiousResponse(base: String, videoId: String, jsonStr: String): YouTubeVideoInfo? {
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val title = root.get("title")?.asString ?: "YouTube Video"
            val author = root.get("author")?.asString ?: "YouTube Creator"
            val lengthSeconds = root.get("lengthSeconds")?.asLong ?: 180L
            val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

            val formatsList = mutableListOf<VideoFormat>()

            if (root.has("formatStreams")) {
                val formatStreams = root.getAsJsonArray("formatStreams")
                for (elem in formatStreams) {
                    val stream = elem.asJsonObject
                    val rawQuality = stream.get("qualityLabel")?.asString ?: stream.get("resolution")?.asString ?: "720p"
                    val itag = stream.get("itag")?.asString ?: "22"
                    val container = stream.get("container")?.asString ?: "mp4"
                    val proxiedDownloadUrl = "$base/latest_version?id=$videoId&itag=$itag&local=true"

                    var sizeBytes = stream.get("size")?.asString?.toLongOrNull() ?: 0L
                    if (sizeBytes == 0L) {
                        sizeBytes = estimateVideoSizeBytes(rawQuality, lengthSeconds)
                    }

                    val cleanLabel = when {
                        rawQuality.contains("720") -> "720p HD (Видео + Звук)"
                        rawQuality.contains("360") -> "360p SD (Видео + Звук)"
                        else -> "$rawQuality (Видео + Звук)"
                    }

                    formatsList.add(
                        VideoFormat(
                            id = itag,
                            qualityLabel = cleanLabel,
                            extension = container,
                            fileSizeBytes = sizeBytes,
                            fileSizeFormatted = formatBytes(sizeBytes),
                            downloadUrl = proxiedDownloadUrl,
                            isAudioOnly = false
                        )
                    )
                }
            }

            if (formatsList.isEmpty()) return null

            YouTubeVideoInfo(
                videoId = videoId,
                title = title,
                author = author,
                thumbnailUrl = thumbnail,
                durationSeconds = lengthSeconds,
                formats = formatsList
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOEmbedFallback(videoId: String): YouTubeVideoInfo? {
        val oEmbedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val request = Request.Builder().url(oEmbedUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val obj = JsonParser.parseString(body).asJsonObject

            val title = obj.get("title")?.asString ?: "YouTube Video"
            val author = obj.get("author_name")?.asString ?: "YouTube Creator"
            val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            val lengthSeconds = 240L

            val formats = listOf(
                VideoFormat(
                    id = "22",
                    qualityLabel = "720p HD (Видео + Звук)",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("720p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("720p", lengthSeconds)),
                    downloadUrl = "https://yewtu.be/latest_version?id=$videoId&itag=22&local=true"
                ),
                VideoFormat(
                    id = "18",
                    qualityLabel = "360p SD (Видео + Звук)",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("360p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("360p", lengthSeconds)),
                    downloadUrl = "https://yewtu.be/latest_version?id=$videoId&itag=18&local=true"
                ),
                VideoFormat(
                    id = "140",
                    qualityLabel = "Аудио (M4A / MP3)",
                    extension = "m4a",
                    fileSizeBytes = estimateAudioSizeBytes(lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateAudioSizeBytes(lengthSeconds)),
                    downloadUrl = "https://yewtu.be/latest_version?id=$videoId&itag=140&local=true",
                    isAudioOnly = true
                )
            )

            return YouTubeVideoInfo(
                videoId = videoId,
                title = title,
                author = author,
                thumbnailUrl = thumbnail,
                durationSeconds = lengthSeconds,
                formats = formats
            )
        }
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
