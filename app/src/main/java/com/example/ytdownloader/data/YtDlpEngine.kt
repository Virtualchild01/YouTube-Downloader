package com.example.ytdownloader.data

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.File

/**
 * Всё общение с yt-dlp: инициализация движка, сборка запросов и разбор ошибок.
 *
 * Раньше это было размазано по MainActivity вперемешку с UI, из-за чего одинаковые
 * опции (`--no-playlist`, cookies, extractor-args) дублировались в двух местах.
 */
object YtDlpEngine {

    private const val TAG = "YtDlpEngine"

    @Volatile
    private var initialized = false

    /** Инициализирует yt-dlp и FFmpeg. Идемпотентно; повторный вызов ничего не делает. */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext

        YoutubeDL.getInstance().init(app)
        runCatching { FFmpeg.getInstance().init(app) }
            .onFailure { Log.w(TAG, "FFmpeg init failed", it) }

        initialized = true
    }

    // ------------------------------------------------------------- запросы

    /** Запрос для получения метаданных (--dump-json). */
    fun infoRequest(url: String, auth: AuthStore): YoutubeDLRequest =
        baseRequest(url, auth)

    /** Запрос на скачивание в [tempDir]. */
    fun downloadRequest(
        url: String,
        quality: QualityPreset,
        tempDir: File,
        auth: AuthStore
    ): YoutubeDLRequest = baseRequest(url, auth).apply {
        addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
        addOption("--no-mtime")

        when (quality.kind) {
            QualityKind.AUDIO -> {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("-f", quality.formatSelector)
            }
            QualityKind.VIDEO, QualityKind.AVI -> {
                addOption("--merge-output-format", "mp4")
                addOption("-f", quality.formatSelector)
            }
        }
    }

    private fun baseRequest(url: String, auth: AuthStore): YoutubeDLRequest =
        YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-check-certificate")
            addOption("-4")
            addOption("--extractor-args", "youtube:player_client=default,ios")
            auth.cookiesPath()?.let { addOption("--cookies", it) }
        }

    // ------------------------------------------------------------- метаданные

    fun fetchInfo(request: YoutubeDLRequest): VideoInfo = YoutubeDL.getInstance().getInfo(request)

    /**
     * Доступные разрешения ролика.
     *
     * VideoInfo.formats уже типизирован (width/height), поэтому reflective-обход полей,
     * который был здесь раньше, больше не нужен.
     */
    fun availableResolutions(info: VideoInfo): List<Pair<Int, Int>> {
        val fromFormats = info.formats.orEmpty()
            .map { it.width to it.height }
            .filter { (w, h) -> w > 0 || h > 0 }

        val root = info.width to info.height
        return if (root.first > 0 || root.second > 0) fromFormats + root else fromFormats
    }

    // ------------------------------------------------------------- ошибки

    /**
     * Достаёт из многословного вывода yt-dlp одну строку, пригодную для показа,
     * и подменяет типовые отказы YouTube понятным текстом.
     */
    fun cleanError(raw: String?, fallback: String, authExpired: String, forbidden: String): String {
        if (raw.isNullOrBlank()) return fallback

        val lines = raw.lines().filter { it.isNotBlank() && !it.startsWith("Usage:") }
        val errorLine = lines.firstOrNull {
            it.startsWith("ERROR:") || it.startsWith("yt-dlp: error:")
        } ?: lines.firstOrNull() ?: raw

        return if (errorLine.contains("403") || errorLine.contains("SABR") ||
            errorLine.contains("bot")
        ) {
            forbidden
        } else {
            errorLine.removePrefix("ERROR:").removePrefix("yt-dlp: error:").trim()
                .ifEmpty { authExpired }
        }
    }
}
