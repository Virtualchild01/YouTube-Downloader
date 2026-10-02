package com.example.ytdownloader.api

data class VideoFormat(
    val id: String,
    val qualityLabel: String,
    val extension: String = "mp4",
    val fileSizeBytes: Long = 0L,
    val fileSizeFormatted: String = "",
    val downloadUrl: String,
    val isAudioOnly: Boolean = false
) {
    val displayLabel: String
        get() = if (fileSizeFormatted.isNotEmpty()) {
            "$qualityLabel ($fileSizeFormatted)"
        } else {
            qualityLabel
        }

    override fun toString(): String = displayLabel
}

data class YouTubeVideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val durationSeconds: Long = 0L,
    val formats: List<VideoFormat> = emptyList()
)
