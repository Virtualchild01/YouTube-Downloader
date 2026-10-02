package com.example.ytdownloader.api

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Public Invidious instances for video metadata and stream proxying
    private val invidiousInstances = listOf(
        "https://yewtu.be",
        "https://inv.tux.pizza",
        "https://invidious.nerdvpn.de",
        "https://invidious.jing.rocks",
        "https://invidious.private.coffee",
        "https://vid.priv.au",
        "https://invidious.asir.dev"
    )

    // Public Piped API instances as fallback
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.privacydev.net",
        "https://pipedapi.tokhmi.xyz"
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
     * Fetches video metadata, stream links, available resolutions, and file sizes.
     */
    suspend fun fetchVideoInfo(videoId: String): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        // 1. Try Invidious instances with proxied download streams
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
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 2. Try Piped API instances if Invidious was unavailable
        for (pipedBase in pipedInstances) {
            try {
                val url = "$pipedBase/streams/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val parsed = parsePipedResponse(videoId, body)
                        if (parsed != null && parsed.formats.isNotEmpty()) {
                            return@withContext Result.success(parsed)
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Не удалось загрузить данные видео. Проверьте ссылку."))
    }

    private fun parseInvidiousResponse(base: String, videoId: String, jsonStr: String): YouTubeVideoInfo? {
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val title = root.get("title")?.asString ?: "YouTube Video"
            val author = root.get("author")?.asString ?: "YouTube Creator"
            val lengthSeconds = root.get("lengthSeconds")?.asLong ?: 180L
            val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

            val formatsList = mutableListOf<VideoFormat>()

            // 1. Regular progressive streams (video + audio combined)
            if (root.has("formatStreams")) {
                val formatStreams = root.getAsJsonArray("formatStreams")
                for (elem in formatStreams) {
                    val stream = elem.asJsonObject
                    val rawQuality = stream.get("qualityLabel")?.asString ?: stream.get("resolution")?.asString ?: "720p"
                    val itag = stream.get("itag")?.asString ?: "22"
                    val container = stream.get("container")?.asString ?: "mp4"

                    // Use proxied download endpoint on Invidious server with local=true
                    // to prevent Google Video 403 Forbidden IP mismatch
                    val proxiedDownloadUrl = "$base/latest_version?id=$videoId&itag=$itag&local=true"

                    var sizeBytes = stream.get("size")?.asString?.toLongOrNull() ?: 0L
                    if (sizeBytes == 0L) {
                        sizeBytes = estimateVideoSizeBytes(rawQuality, lengthSeconds)
                    }

                    val cleanLabel = when {
                        rawQuality.contains("720") -> "720p HD (Видео + Звук)"
                        rawQuality.contains("360") -> "360p SD (Видео + Звук)"
                        rawQuality.contains("480") -> "480p SD (Видео + Звук)"
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

            // 2. Adaptive video streams (e.g. 1080p Full HD) and Best Audio stream
            if (root.has("adaptiveFormats")) {
                val adaptive = root.getAsJsonArray("adaptiveFormats")
                for (elem in adaptive) {
                    val stream = elem.asJsonObject
                    val type = stream.get("type")?.asString ?: ""
                    val quality = stream.get("qualityLabel")?.asString ?: ""
                    val itag = stream.get("itag")?.asString ?: ""
                    val container = stream.get("container")?.asString ?: "mp4"

                    // 1080p stream
                    if (type.contains("video/mp4") && quality.contains("1080") && formatsList.none { it.qualityLabel.contains("1080") }) {
                        val clen = stream.get("clen")?.asString?.toLongOrNull() ?: 0L
                        val sizeBytes = if (clen > 0) clen else estimateVideoSizeBytes("1080p", lengthSeconds)
                        val proxiedUrl = "$base/latest_version?id=$videoId&itag=$itag&local=true"

                        formatsList.add(
                            VideoFormat(
                                id = itag.ifEmpty { "137" },
                                qualityLabel = "1080p Full HD",
                                extension = container,
                                fileSizeBytes = sizeBytes,
                                fileSizeFormatted = formatBytes(sizeBytes),
                                downloadUrl = proxiedUrl,
                                isAudioOnly = false
                            )
                        )
                    }

                    // Extract best Audio stream (e.g. itag 140 m4a / mp3)
                    if (type.contains("audio/mp4") && formatsList.none { it.isAudioOnly }) {
                        val clen = stream.get("clen")?.asString?.toLongOrNull() ?: 0L
                        val audioSize = if (clen > 0) clen else estimateAudioSizeBytes(lengthSeconds)
                        val proxiedAudioUrl = "$base/latest_version?id=$videoId&itag=$itag&local=true"

                        formatsList.add(
                            VideoFormat(
                                id = itag.ifEmpty { "140" },
                                qualityLabel = "Аудио (M4A / MP3)",
                                extension = "m4a",
                                fileSizeBytes = audioSize,
                                fileSizeFormatted = formatBytes(audioSize),
                                downloadUrl = proxiedAudioUrl,
                                isAudioOnly = true
                            )
                        )
                    }
                }
            }

            // Sort formats: 1080p first, then 720p, 480p, 360p, then Audio last
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

            YouTubeVideoInfo(
                videoId = videoId,
                title = title,
                author = author,
                thumbnailUrl = thumbnail,
                durationSeconds = lengthSeconds,
                formats = formatsList
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePipedResponse(videoId: String, jsonStr: String): YouTubeVideoInfo? {
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val title = root.get("title")?.asString ?: "YouTube Video"
            val author = root.get("uploader")?.asString ?: "YouTube Creator"
            val durationSeconds = root.get("duration")?.asLong ?: 180L
            val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

            val formatsList = mutableListOf<VideoFormat>()

            if (root.has("videoStreams")) {
                val videoStreams = root.getAsJsonArray("videoStreams")
                for (elem in videoStreams) {
                    val stream = elem.asJsonObject
                    val isVideoOnly = stream.get("videoOnly")?.asBoolean ?: false
                    val quality = stream.get("quality")?.asString ?: "720p"
                    val format = stream.get("format")?.asString ?: "mp4"
                    val url = stream.get("url")?.asString ?: continue

                    // Prefer combined streams or MP4
                    if (!isVideoOnly && format.equals("mp4", ignoreCase = true)) {
                        val sizeBytes = estimateVideoSizeBytes(quality, durationSeconds)
                        formatsList.add(
                            VideoFormat(
                                id = quality,
                                qualityLabel = "$quality (Видео + Звук)",
                                extension = "mp4",
                                fileSizeBytes = sizeBytes,
                                fileSizeFormatted = formatBytes(sizeBytes),
                                downloadUrl = url,
                                isAudioOnly = false
                            )
                        )
                    }
                }
            }

            if (root.has("audioStreams")) {
                val audioStreams = root.getAsJsonArray("audioStreams")
                val bestAudio = audioStreams.firstOrNull { it.asJsonObject.get("format")?.asString?.contains("m4a") == true }
                    ?: audioStreams.firstOrNull()

                if (bestAudio != null) {
                    val stream = bestAudio.asJsonObject
                    val url = stream.get("url")?.asString ?: ""
                    if (url.isNotEmpty()) {
                        val sizeBytes = estimateAudioSizeBytes(durationSeconds)
                        formatsList.add(
                            VideoFormat(
                                id = "audio_piped",
                                qualityLabel = "Аудио (M4A / MP3)",
                                extension = "m4a",
                                fileSizeBytes = sizeBytes,
                                fileSizeFormatted = formatBytes(sizeBytes),
                                downloadUrl = url,
                                isAudioOnly = true
                            )
                        )
                    }
                }
            }

            YouTubeVideoInfo(
                videoId = videoId,
                title = title,
                author = author,
                thumbnailUrl = thumbnail,
                durationSeconds = durationSeconds,
                formats = formatsList
            )
        } catch (e: Exception) {
            null
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
        return (durationSec * 160_000L) / 8L // ~160 kbps MP3
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
