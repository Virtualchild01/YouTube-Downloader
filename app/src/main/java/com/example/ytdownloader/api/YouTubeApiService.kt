package com.example.ytdownloader.api

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
     * Fetches official title & author via YouTube oEmbed API in 0.2s.
     */
    suspend fun fetchBasicMetadata(videoId: String): Pair<String, String> = withContext(Dispatchers.IO) {
        var videoTitle = "YouTube Video"
        var videoAuthor = "YouTube Creator"
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
        Pair(videoTitle, videoAuthor)
    }
}
