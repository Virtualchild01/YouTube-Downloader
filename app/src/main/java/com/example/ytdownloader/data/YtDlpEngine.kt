package com.example.ytdownloader.data

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
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

    /**
     * Будет ли скачан HDR-поток при выборе пункта с потолком [maxHeight].
     *
     * Повторяет то, что сделает сам yt-dlp, а не просто «есть ли HDR в ролике».
     * Порядок сортировки у него — `res`, затем `fps`, затем `hdr`, поэтому
     * HDR выбирается только среди форматов с максимальным разрешением и частотой
     * кадров; ролик, у которого HDR есть лишь в 1080p, а 4K только в SDR,
     * честно покажет «4K» без пометки HDR.
     *
     * Первая ветка селектора ограничена `[ext=mp4]`, поэтому mp4 и проверяется
     * первым: иначе пометка HDR стояла бы у ролика, где HDR есть только в webm.
     */
    fun willDownloadHdr(info: VideoInfo, maxHeight: Int): Boolean {
        val candidates = info.formats.orEmpty()
            .filter { it.vcodec != null && it.vcodec != "none" && it.height in 1..maxHeight }

        val mp4 = candidates.filter { it.ext == "mp4" }
        val pool = mp4.ifEmpty { candidates }

        val best = pool.maxWithOrNull(
            compareBy({ it.height }, { it.fps }, { if (it.isHdr()) 1 else 0 })
        )
        return best?.isHdr() == true
    }

    /**
     * Библиотека youtubedl-android 0.18.1 не отдаёт поле `dynamic_range`
     * (в её VideoFormat такого поля нет, а неизвестные поля JSON отбрасываются),
     * поэтому HDR распознаётся по косвенным признакам:
     *  - `format_note` содержит "HDR" — так помечает сам YouTube ("2160p60 HDR");
     *  - кодек VP9.2 (`vp9.2` / `vp09.02…`) — профиль VP9 только для HDR10;
     *  - AV1 с битностью 10 (`av01.0.13M.10…`) — на YouTube это HDR-поток,
     *    тогда как SDR-потоки того же кодека идут с профилем 08.
     *
     * Проверено на реальных роликах: на HDR-видео правило совпало с настоящим
     * `dynamic_range` во всех 25 форматах, на SDR-видео ложных срабатываний нет.
     */
    private fun VideoFormat.isHdr(): Boolean {
        val note = formatNote.orEmpty()
        if (note.contains("hdr", ignoreCase = true)) return true

        val codec = vcodec.orEmpty().lowercase()
        if (codec.startsWith("vp9.2") || codec.startsWith("vp09.02")) return true

        // av01.PPMM.LL…: поле LL — битность (08 = 8 бит SDR, 10 = 10 бит HDR)
        if (codec.startsWith("av01.")) {
            val bitDepth = codec.split('.').getOrNull(3)
            if (bitDepth == "10") return true
        }
        return false
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
