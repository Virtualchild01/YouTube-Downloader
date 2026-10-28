package com.example.ytdownloader.data

import android.net.Uri

/** Файл, лежащий в папке приложения внутри «Загрузок». */
data class DownloadedMedia(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val durationMs: Long,
    val mimeType: String
) {
    /**
     * Контейнеры, которые умеет воспроизводить встроенный плеер (Media3).
     * AVI и прочую экзотику отдаём системному плееру — под него файлы и готовятся.
     */
    val isPlayableInside: Boolean
        get() = when (mimeType) {
            "video/mp4", "video/x-matroska", "video/webm", "video/quicktime",
            "audio/mpeg", "audio/mp4", "audio/opus", "audio/ogg", "audio/flac", "audio/wav" -> true
            else -> false
        }

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/")
}
