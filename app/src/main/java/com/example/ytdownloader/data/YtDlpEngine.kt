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

    /** Как часто подтягивать свежий yt-dlp. YouTube ломает старые версии примерно раз в месяц. */
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "yt_dlp_engine"
    private const val KEY_LAST_UPDATE = "last_update_at"

    @Volatile
    private var initialized = false

    /**
     * Инициализирует yt-dlp и FFmpeg. Идемпотентно; повторный вызов движок не перезапускает.
     *
     * Заодно раз в сутки подтягивает свежую версию yt-dlp: приложение поставляет
     * библиотеку 0.18.1, а YouTube регулярно меняет плеер, и старая версия начинает
     * отвечать 403 и «Sign in to confirm you're not a bot» даже с валидными cookies.
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        val app = context.applicationContext

        if (!initialized) {
            YoutubeDL.getInstance().init(app)
            runCatching { FFmpeg.getInstance().init(app) }
                .onFailure { Log.w(TAG, "FFmpeg init failed", it) }
            initialized = true
        }

        updateEngineIfStale(app)
    }

    private fun updateEngineIfStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_UPDATE, 0L) < UPDATE_INTERVAL_MS) return

        // Дату ставим до попытки: без сети на каждом запуске был бы новый запрос
        prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
        runCatching {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context)
            Log.d(TAG, "yt-dlp update: $status")
        }.onFailure { Log.w(TAG, "yt-dlp update failed", it) }
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
            // Клиент ios не умеет работать с cookies: с авторизованным аккаунтом он
            // возвращает потоки без входа, и YouTube отвечает «Sign in to confirm
            // you're not a bot». Клиент по умолчанию у yt-dlp подбирается сам.
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

        return if (errorLine.contains("403") ||
            errorLine.contains("SABR") ||
            errorLine.contains("not a bot", ignoreCase = true) ||
            errorLine.contains("Sign in to confirm", ignoreCase = true) ||
            errorLine.contains("confirm your age", ignoreCase = true)
        ) {
            forbidden
        } else {
            errorLine.removePrefix("ERROR:").removePrefix("yt-dlp: error:").trim()
                .ifEmpty { authExpired }
        }
    }
}
