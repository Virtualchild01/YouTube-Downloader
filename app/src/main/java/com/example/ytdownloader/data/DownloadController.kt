package com.example.ytdownloader.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.example.ytdownloader.R
import com.example.ytdownloader.notification.DownloadNotifier
import com.example.ytdownloader.util.Formatters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Скачивание и постобработка, независимые от Activity.
 *
 * Раньше вся эта логика лежала в MainActivity на `CoroutineScope(SupervisorJob() + IO)`.
 * Здесь контроллер живёт в процессе: поворот экрана или открытие диалога больше не
 * отвязывают прогресс от UI. Activity только подписывается на события и отписывается
 * в onDestroy — сама загрузка от этого не зависит.
 */
class DownloadController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val auth = AuthStore(appContext)
    private val notifier = DownloadNotifier(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /** Слушатель ставит Activity; события приходят на главном потоке. */
    @Volatile
    private var listener: ((Event) -> Unit)? = null

    /** Последнее состояние — чтобы Activity могла восстановить UI после пересоздания. */
    @Volatile
    var lastProgress: Int = 0
        private set

    @Volatile
    var lastStatusText: String? = null
        private set

    sealed interface Event {
        data class Progress(val percent: Int, val text: String) : Event
        data object Completed : Event
        data class Failed(val message: String) : Event
    }

    val isRunning: Boolean get() = running.get()

    fun observe(listener: (Event) -> Unit) {
        this.listener = listener
    }

    fun stopObserving() {
        listener = null
    }

    /**
     * @param url исходная ссылка: VideoInfo.webpageUrl у некоторых экстракторов пустой
     * @return false, если загрузка уже идёт
     */
    fun start(url: String, video: VideoInfo, quality: QualityPreset): Boolean {
        if (!running.compareAndSet(false, true)) return false
        notifier.ensureChannel()
        lastProgress = 0
        lastStatusText = null
        scope.launch { runDownload(url, video, quality) }
        return true
    }

    private suspend fun runDownload(url: String, video: VideoInfo, quality: QualityPreset) {
        val wakeLock = acquireWakeLock()
        val tempDir = File(appContext.cacheDir, "yt_downloads/${System.currentTimeMillis()}")
        val processId = "yt_dl_${System.currentTimeMillis()}"

        try {
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                fail(appContext.getString(R.string.save_failed))
                return
            }

            emit(Event.Progress(0, appContext.getString(R.string.status_downloading_video_started)))

            runYtDlp(url, video, quality, tempDir, processId)

            val downloaded = tempDir.listFiles()
                ?.filter { it.isFile && DownloadStorage.isMediaFile(it.name) }
                ?: emptyList()

            if (downloaded.isEmpty()) {
                fail(appContext.getString(R.string.save_failed))
                return
            }

            val filesToSave = if (quality.kind == QualityKind.AVI) {
                val converted = convertToAvi(downloaded.first(), tempDir, video.duration) ?: return
                listOf(converted)
            } else {
                downloaded
            }

            val saved = filesToSave.mapNotNull { file ->
                DownloadStorage.save(appContext, file, file.name)
            }

            if (saved.isEmpty()) {
                fail(appContext.getString(R.string.save_failed))
                return
            }

            announceCompletion(video)
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            fail(
                YtDlpEngine.cleanError(
                    raw = e.message,
                    fallback = appContext.getString(R.string.error_download_generic),
                    authExpired = appContext.getString(R.string.error_auth_expired),
                    forbidden = appContext.getString(R.string.error_403)
                )
            )
        } finally {
            tempDir.deleteRecursively()
            notifier.clearProgress()
            releaseWakeLock(wakeLock)
            running.set(false)
        }
    }

    // ------------------------------------------------------------ загрузка

    private fun runYtDlp(
        url: String,
        video: VideoInfo,
        quality: QualityPreset,
        tempDir: File,
        processId: String
    ) {
        YtDlpEngine.ensureInitialized(appContext)
        val request = YtDlpEngine.downloadRequest(url, quality, tempDir, auth)

        var audioStage = false
        var lastPercent = 0f

        YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            val rawLine = line ?: ""
            // Вторая дорожка (m4a/opus) или сброс прогресса — признак, что видео уже скачано
            if (rawLine.contains(".m4a", true) ||
                rawLine.contains(".mp3", true) ||
                rawLine.contains(".opus", true) ||
                rawLine.contains("audio", true) ||
                (lastPercent > 75f && progress < 25f)
            ) {
                audioStage = true
            }
            lastPercent = progress
            reportProgress(progress, eta.toLong(), audioStage, quality, video)
        }
    }

    private fun reportProgress(
        progress: Float,
        etaSeconds: Long,
        audioStage: Boolean,
        quality: QualityPreset,
        video: VideoInfo
    ) {
        // Проценты скачивания закончились, yt-dlp склеивает дорожки через FFmpeg
        val merging = quality.kind == QualityKind.VIDEO && audioStage && progress >= 99f

        val stage = when {
            quality.kind == QualityKind.AUDIO -> appContext.getString(R.string.stage_downloading_audio)
            merging -> appContext.getString(R.string.stage_merging)
            audioStage -> appContext.getString(R.string.stage_downloading_audio)
            else -> appContext.getString(R.string.stage_downloading_video)
        }

        val percent = progress.toInt().coerceIn(0, 100)
        val text = if (merging) {
            stage
        } else {
            val eta = Formatters.duration(appContext, etaSeconds)
            if (eta.isNotEmpty()) {
                appContext.getString(R.string.progress_with_eta, stage, percent, eta)
            } else {
                appContext.getString(R.string.progress_plain, stage, percent)
            }
        }

        emit(Event.Progress(percent, text))
        notifier.progress(video.notificationTitle(), text, percent)
    }

    // ---------------------------------------------------------- конвертация

    /** Возвращает готовый AVI или null, если конвертация сорвалась (ошибка уже отправлена). */
    private fun convertToAvi(source: File, tempDir: File, durationSec: Int): File? {
        val target = File(tempDir, source.nameWithoutExtension + ".avi")

        return when (val result = AviConverter.convert(
            context = appContext,
            input = source,
            output = target,
            durationSec = durationSec.toLong(),
            onProgress = { percent ->
                emit(
                    Event.Progress(
                        percent,
                        appContext.getString(R.string.status_converting_percent, percent)
                    )
                )
            }
        )) {
            is AviConverter.Result.Success -> {
                source.delete()
                result.file
            }
            is AviConverter.Result.Failure -> {
                fail(convertErrorMessage(result))
                null
            }
        }
    }

    /** Технический detail от FFmpeg дописываем в конец — без него ошибку не диагностировать. */
    private fun convertErrorMessage(failure: AviConverter.Result.Failure): String {
        val base = when (failure.reason) {
            AviConverter.FailureReason.FFMPEG_INIT_FAILED ->
                appContext.getString(R.string.avi_error_init)
            AviConverter.FailureReason.FFMPEG_MISSING ->
                appContext.getString(R.string.avi_error_missing)
            AviConverter.FailureReason.PROCESS_START_FAILED ->
                appContext.getString(R.string.avi_error_start)
            AviConverter.FailureReason.FFMPEG_ERROR ->
                appContext.getString(R.string.avi_error_convert)
            AviConverter.FailureReason.NO_OUTPUT ->
                appContext.getString(R.string.avi_error_no_output)
        }
        val detail = failure.detail?.trim()
        return if (detail.isNullOrEmpty()) base else "$base: $detail"
    }

    // -------------------------------------------------------------- финал

    private fun announceCompletion(video: VideoInfo) {
        val title = video.notificationTitle()
        notifier.finished(
            appContext.getString(R.string.download_complete_title),
            appContext.getString(R.string.download_complete_text, title)
        )
        emit(Event.Completed)
    }

    private fun fail(message: String) {
        notifier.finished(appContext.getString(R.string.error_download_title), message)
        emit(Event.Failed(message))
    }

    private fun emit(event: Event) {
        when (event) {
            is Event.Progress -> {
                lastProgress = event.percent
                lastStatusText = event.text
            }
            is Event.Failed -> lastStatusText = event.message
            Event.Completed -> Unit
        }
        mainHandler.post { listener?.invoke(event) }
    }

    private fun VideoInfo.notificationTitle(): String =
        (title ?: appContext.getString(R.string.error_unknown_video)).take(40)

    // ------------------------------------------------------------- wake lock

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTDownloader:DownloadWakeLock")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
    }.getOrNull()

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }

    companion object {
        private const val TAG = "DownloadController"
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L

        @Volatile
        private var instance: DownloadController? = null

        fun get(context: Context): DownloadController =
            instance ?: synchronized(this) {
                instance ?: DownloadController(context).also { instance = it }
            }
    }
}
