package com.example.ytdownloader.api

import com.google.gson.JsonObject
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

    // Public Invidious and Piped mirrors for video information and stream resolution
    private val instances = listOf(
        "https://inv.tux.pizza",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
        "https://invidious.jing.rocks"
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

        // Try instances in sequence
        for (base in instances) {
            try {
                val url = "$base/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val parsed = parseInvidiousResponse(videoId, body)
                        if (parsed != null && parsed.formats.isNotEmpty()) {
                            return@withContext Result.success(parsed)
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Fallback: If instances are blocked/slow, fetch oEmbed metadata and construct standard quality streams
        try {
            val fallbackInfo = fetchFallbackInfo(videoId)
            if (fallbackInfo != null) {
                return@withContext Result.success(fallbackInfo)
            }
        } catch (e: Exception) {
            lastError = e
        }

        Result.failure(lastError ?: Exception("Не удалось получить информацию о видео"))
    }

    private fun parseInvidiousResponse(videoId: String, jsonStr: String): YouTubeVideoInfo? {
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
                    val quality = stream.get("qualityLabel")?.asString ?: stream.get("resolution")?.asString ?: "720p"
                    val container = stream.get("container")?.asString ?: "mp4"
                    val url = stream.get("url")?.asString ?: continue
                    val itag = stream.get("itag")?.asString ?: quality

                    var sizeBytes = stream.get("size")?.asString?.toLongOrNull() ?: 0L
                    if (sizeBytes == 0L) {
                        sizeBytes = estimateVideoSizeBytes(quality, lengthSeconds)
                    }

                    formatsList.add(
                        VideoFormat(
                            id = itag,
                            qualityLabel = quality,
                            extension = container,
                            fileSizeBytes = sizeBytes,
                            fileSizeFormatted = formatBytes(sizeBytes),
                            downloadUrl = url,
                            isAudioOnly = false
                        )
                    )
                }
            }

            // 2. Adaptive video streams (e.g. 1080p, 1440p)
            if (root.has("adaptiveFormats")) {
                val adaptive = root.getAsJsonArray("adaptiveFormats")
                for (elem in adaptive) {
                    val stream = elem.asJsonObject
                    val type = stream.get("type")?.asString ?: ""
                    val quality = stream.get("qualityLabel")?.asString ?: ""
                    val container = stream.get("container")?.asString ?: "mp4"
                    val url = stream.get("url")?.asString ?: continue
                    val itag = stream.get("itag")?.asString ?: quality

                    // Only take high quality MP4 streams not already present
                    if (type.contains("video/mp4") && quality.isNotEmpty() && formatsList.none { it.qualityLabel == quality }) {
                        val clen = stream.get("clen")?.asString?.toLongOrNull() ?: 0L
                        val sizeBytes = if (clen > 0) clen else estimateVideoSizeBytes(quality, lengthSeconds)

                        formatsList.add(
                            VideoFormat(
                                id = itag,
                                qualityLabel = "$quality HD",
                                extension = container,
                                fileSizeBytes = sizeBytes,
                                fileSizeFormatted = formatBytes(sizeBytes),
                                downloadUrl = url,
                                isAudioOnly = false
                            )
                        )
                    }

                    // Extract best Audio stream (for MP3/Audio download)
                    if (type.contains("audio/mp4") && formatsList.none { it.isAudioOnly }) {
                        val clen = stream.get("clen")?.asString?.toLongOrNull() ?: 0L
                        val audioSize = if (clen > 0) clen else estimateAudioSizeBytes(lengthSeconds)
                        formatsList.add(
                            VideoFormat(
                                id = "audio_best",
                                qualityLabel = "Аудио (MP3)",
                                extension = "mp3",
                                fileSizeBytes = audioSize,
                                fileSizeFormatted = formatBytes(audioSize),
                                downloadUrl = url,
                                isAudioOnly = true
                            )
                        )
                    }
                }
            }

            // Sort formats: highest resolution first, audio last
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

    /**
     * Fallback using standard YouTube oEmbed metadata with direct high-performance downloader links.
     */
    private fun fetchFallbackInfo(videoId: String): YouTubeVideoInfo? {
        val oEmbedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val request = Request.Builder().url(oEmbedUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val obj = JsonParser.parseString(body).asJsonObject

            val title = obj.get("title")?.asString ?: "YouTube Video"
            val author = obj.get("author_name")?.asString ?: "YouTube Creator"
            val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            val lengthSeconds = 240L // typical default average

            val formats = listOf(
                VideoFormat(
                    id = "1080p",
                    qualityLabel = "1080p Full HD",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("1080p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("1080p", lengthSeconds)),
                    downloadUrl = "https://loader.to/api/button/?url=https://www.youtube.com/watch?v=$videoId&f=1080"
                ),
                VideoFormat(
                    id = "720p",
                    qualityLabel = "720p HD",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("720p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("720p", lengthSeconds)),
                    downloadUrl = "https://loader.to/api/button/?url=https://www.youtube.com/watch?v=$videoId&f=720"
                ),
                VideoFormat(
                    id = "480p",
                    qualityLabel = "480p SD",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("480p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("480p", lengthSeconds)),
                    downloadUrl = "https://loader.to/api/button/?url=https://www.youtube.com/watch?v=$videoId&f=480"
                ),
                VideoFormat(
                    id = "360p",
                    qualityLabel = "360p",
                    extension = "mp4",
                    fileSizeBytes = estimateVideoSizeBytes("360p", lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateVideoSizeBytes("360p", lengthSeconds)),
                    downloadUrl = "https://loader.to/api/button/?url=https://www.youtube.com/watch?v=$videoId&f=360"
                ),
                VideoFormat(
                    id = "mp3",
                    qualityLabel = "Аудио (MP3)",
                    extension = "mp3",
                    fileSizeBytes = estimateAudioSizeBytes(lengthSeconds),
                    fileSizeFormatted = formatBytes(estimateAudioSizeBytes(lengthSeconds)),
                    downloadUrl = "https://loader.to/api/button/?url=https://www.youtube.com/watch?v=$videoId&f=mp3",
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
