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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Public Cobalt API instances as fallback
    private val cobaltInstances = listOf(
        "https://api.cobalt.tools",
        "https://cobalt-backend.canine.tools",
        "https://api.wuk.sh"
    )

    // Public Invidious stream mirrors as fallback
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
     * Fetches video information and resolves real media download links directly on device.
     */
    suspend fun fetchVideoInfo(videoId: String): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
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

        // 2. Try native InnerTube extraction directly from device (No server needed!)
        val nativeResult = fetchNativeInnerTube(videoId)
        if (nativeResult != null && nativeResult.formats.isNotEmpty()) {
            val resolvedTitle = if (nativeResult.title.isNotEmpty() && nativeResult.title != "YouTube Video") nativeResult.title else videoTitle
            val resolvedAuthor = if (nativeResult.author.isNotEmpty() && nativeResult.author != "YouTube Creator") nativeResult.author else videoAuthor
            val resolvedDuration = if (nativeResult.durationSeconds > 0) nativeResult.durationSeconds else lengthSeconds
            val resolvedThumb = if (nativeResult.thumbnailUrl.isNotEmpty()) nativeResult.thumbnailUrl else thumbnailUrl

            return@withContext Result.success(
                YouTubeVideoInfo(
                    videoId = videoId,
                    title = resolvedTitle,
                    author = resolvedAuthor,
                    thumbnailUrl = resolvedThumb,
                    durationSeconds = resolvedDuration,
                    formats = nativeResult.formats
                )
            )
        }

        // 3. Fallback to Cobalt API or Invidious if native request was blocked
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
                qualityLabel = "Аудио (M4A / MP3)",
                extension = "m4a",
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
                thumbnailUrl = thumbnailUrl,
                durationSeconds = lengthSeconds,
                formats = formats
            )
        )
    }

    /**
     * Directly calls YouTube's InnerTube API impersonating mobile & TV clients.
     * This runs directly on the device using user's domestic IP (never blocked by datacenter filters).
     */
    private fun fetchNativeInnerTube(videoId: String): YouTubeVideoInfo? {
        val clients = listOf(
            // iOS Client configuration
            Triple(
                "IOS",
                "19.29.1",
                mapOf(
                    "User-Agent" to "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                    "X-Youtube-Client-Name" to "5",
                    "X-Youtube-Client-Version" to "19.29.1",
                    "Origin" to "https://www.youtube.com"
                )
            ),
            // TV Simply Embedded Player configuration
            Triple(
                "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                "2.0",
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) Version/6.0 TV Safari/537.36",
                    "Origin" to "https://www.youtube.com"
                )
            ),
            // Android Client configuration
            Triple(
                "ANDROID",
                "19.09.37",
                mapOf(
                    "User-Agent" to "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip",
                    "X-Youtube-Client-Name" to "3",
                    "X-Youtube-Client-Version" to "19.09.37",
                    "Origin" to "https://www.youtube.com"
                )
            )
        )

        for ((clientName, clientVersion, headers) in clients) {
            try {
                val json = JsonObject().apply {
                    val context = JsonObject().apply {
                        val clientObj = JsonObject().apply {
                            addProperty("clientName", clientName)
                            addProperty("clientVersion", clientVersion)
                            addProperty("hl", "en")
                            addProperty("gl", "US")
                            if (clientName == "IOS") {
                                addProperty("deviceModel", "iPhone16,2")
                            } else if (clientName == "ANDROID") {
                                addProperty("androidSdkVersion", 34)
                            }
                        }
                        add("client", clientObj)
                    }
                    add("context", context)
                    addProperty("videoId", videoId)
                }

                val reqBuilder = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))

                headers.forEach { (k, v) -> reqBuilder.header(k, v) }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use

                    val body = resp.body?.string() ?: return@use
                    val root = JsonParser.parseString(body).asJsonObject

                    val playability = root.getAsJsonObject("playabilityStatus")
                    val status = playability?.get("status")?.asString
                    if (status != "OK") return@use

                    val videoDetails = root.getAsJsonObject("videoDetails")
                    val title = videoDetails?.get("title")?.asString ?: ""
                    val author = videoDetails?.get("author")?.asString ?: ""
                    val durationSec = videoDetails?.get("lengthSeconds")?.asLong ?: 0L

                    val streamingData = root.getAsJsonObject("streamingData") ?: return@use
                    val formatsArray = streamingData.getAsJsonArray("formats") ?: JsonArray()
                    val adaptiveArray = streamingData.getAsJsonArray("adaptiveFormats") ?: JsonArray()

                    val parsedFormats = mutableListOf<VideoFormat>()

                    // 1. Process progressive formats (containing both audio and video)
                    for (elem in formatsArray) {
                        val fmt = elem.asJsonObject
                        val directUrl = fmt.get("url")?.asString ?: continue
                        val qualityLabel = fmt.get("qualityLabel")?.asString ?: "Video"
                        val itag = fmt.get("itag")?.asInt ?: 0
                        val contentLength = fmt.get("contentLength")?.asLong
                            ?: estimateVideoSizeBytes(qualityLabel, durationSec)

                        val id = "prog_$itag"
                        parsedFormats.add(
                            VideoFormat(
                                id = id,
                                qualityLabel = "$qualityLabel HD",
                                extension = "mp4",
                                fileSizeBytes = contentLength,
                                fileSizeFormatted = formatBytes(contentLength),
                                downloadUrl = directUrl,
                                isAudioOnly = false
                            )
                        )
                    }

                    // 2. Process audio stream from adaptive formats (itag 140: 128kbps AAC audio)
                    for (elem in adaptiveArray) {
                        val fmt = elem.asJsonObject
                        val itag = fmt.get("itag")?.asInt ?: 0
                        val directUrl = fmt.get("url")?.asString ?: continue

                        if (itag == 140) {
                            val contentLength = fmt.get("contentLength")?.asLong
                                ?: estimateAudioSizeBytes(durationSec)
                            parsedFormats.add(
                                VideoFormat(
                                    id = "audio_m4a",
                                    qualityLabel = "Аудио (M4A / MP3)",
                                    extension = "m4a",
                                    fileSizeBytes = contentLength,
                                    fileSizeFormatted = formatBytes(contentLength),
                                    downloadUrl = directUrl,
                                    isAudioOnly = true
                                )
                            )
                            break
                        }
                    }

                    if (parsedFormats.isNotEmpty()) {
                        return YouTubeVideoInfo(
                            videoId = videoId,
                            title = title,
                            author = author,
                            thumbnailUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg",
                            durationSeconds = durationSec,
                            formats = parsedFormats
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return null
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
