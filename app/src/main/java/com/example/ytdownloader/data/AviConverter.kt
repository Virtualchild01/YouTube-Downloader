package com.example.ytdownloader.data

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Конвертация скачанного видео в AVI (Xvid + MP3) для старых телевизоров и DVD-плееров.
 *
 * FFmpeg из youtubedl-android — это обычный CLI-бинарь (libffmpeg.so), собранный
 * с --enable-libxvid и --enable-libmp3lame, поэтому запускаем его отдельным процессом
 * и читаем прогресс из -progress pipe:1.
 *
 * Кодируем встроенным mpeg4, а не libxvid: результат тот же MPEG-4 Part 2, но mpeg4
 * умеет slice-многопоточность и грузит все ядра (см. PRIMARY_ENCODER).
 */
object AviConverter {

    private const val TAG = "AviConverter"

    /** Профиль совместимости: Xvid без B-кадров, MP3 128 kbps, не выше 720p. */
    private const val VIDEO_BITRATE = "2200k"
    private const val MAX_HEIGHT = 720

    /** Почему конвертация не удалась — текст для пользователя выбирает UI-слой. */
    enum class FailureReason {
        FFMPEG_INIT_FAILED,
        FFMPEG_MISSING,
        /** Не нашлась разделяемая библиотека, без которой FFmpeg-бинарь не запускается. */
        DEPENDENCY_MISSING,
        PROCESS_START_FAILED,
        FFMPEG_ERROR,
        NO_OUTPUT
    }

    sealed interface Result {
        data class Success(val file: File) : Result

        /** @param detail последняя строка stderr от FFmpeg — для логов и показа пользователю. */
        data class Failure(val reason: FailureReason, val detail: String? = null) : Result
    }

    fun convert(
        context: Context,
        input: File,
        output: File,
        durationSec: Long,
        onProgress: (percent: Int) -> Unit
    ): Result {
        // FFmpeg.init() идемпотентен и распаковывает .so в noBackupFilesDir.
        // Вызываем здесь, чтобы конвертация не зависела от порядка инициализации в Activity.
        val libDir = try {
            FFmpeg.getInstance().init(context)
            File(ffmpegPackageDir(context), "usr/lib")
        } catch (e: Exception) {
            Log.e(TAG, "ffmpeg init failed", e)
            return Result.Failure(FailureReason.FFMPEG_INIT_FAILED, e.message)
        }

        val binary = ffmpegBinary(context)
        if (!binary.exists() || !binary.canExecute() || !libDir.isDirectory) {
            return Result.Failure(FailureReason.FFMPEG_MISSING)
        }

        // Пакет FFmpeg собран с Ubuntu-тулчейном и не содержит C++ runtime: librubberband,
        // libx265, libvmaf и ещё десяток библиотек из usr/lib требуют libc++_shared.so.
        // Лежит он в пакете Python, который распаковывает инициализация yt-dlp. Если её
        // ещё не было (конвертация запущена в свежем процессе), пакета в noBackupFilesDir
        // нет, и линковщик падает с «library "libc++_shared.so" not found».
        val pythonLibDir = File(pythonPackageDir(context), "usr/lib")
        if (!File(pythonLibDir, REQUIRED_RUNTIME).exists()) {
            runCatching { YoutubeDL.getInstance().init(context) }
                .onFailure { Log.w(TAG, "python package init failed", it) }
        }
        if (!File(pythonLibDir, REQUIRED_RUNTIME).exists()) {
            Log.e(TAG, "$REQUIRED_RUNTIME missing in $pythonLibDir")
            return Result.Failure(FailureReason.DEPENDENCY_MISSING, REQUIRED_RUNTIME)
        }

        val primary = runEncoder(context, binary, libDir, pythonLibDir, input, output, durationSec, onProgress, PRIMARY_ENCODER)
        if (primary is Result.Success) return primary

        // mpeg4 и libxvid пишут один и тот же MPEG-4 Part 2, но у libxvid bitstream может
        // быть чуть ближе к референсу. Если основной кодировщик почему-то не завёлся,
        // пробуем его — медленнее, зато почти наверняка.
        val primaryFailure = primary as Result.Failure
        Log.w(TAG, "encoder $PRIMARY_ENCODER failed (${primaryFailure.detail}), retrying with $FALLBACK_ENCODER")
        val fallback = runEncoder(context, binary, libDir, pythonLibDir, input, output, durationSec, onProgress, FALLBACK_ENCODER)
        // Если и запасной не сработал, показываем ошибку основного: он и есть штатный путь
        return when (fallback) {
            is Result.Success -> fallback
            is Result.Failure -> primaryFailure.copy(detail = fallback.detail ?: primaryFailure.detail)
        }
    }

    private fun runEncoder(
        context: Context,
        binary: File,
        libDir: File,
        pythonLibDir: File,
        input: File,
        output: File,
        durationSec: Long,
        onProgress: (percent: Int) -> Unit,
        encoder: String
    ): Result {
        output.delete()
        val process = try {
            ProcessBuilder(buildCommand(binary, input, output, encoder))
                .redirectErrorStream(false)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = libraryPath(context, libDir, pythonLibDir)
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"] = context.filesDir.absolutePath
                }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "cannot start ffmpeg", e)
            return Result.Failure(FailureReason.PROCESS_START_FAILED, e.message)
        }

        val errorBuffer = StringBuilder()
        val errorThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (errorBuffer.length < MAX_ERROR_LENGTH) errorBuffer.appendLine(line)
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            // Читаем stdout целиком: без этого FFmpeg упрётся в полный буфер и зависнет
            process.inputStream.bufferedReader().forEachLine { line ->
                parseProgress(line, durationSec)?.let(onProgress)
            }

            val exit = process.waitFor()

            if (exit != 0) {
                output.delete()
                return Result.Failure(FailureReason.FFMPEG_ERROR, lastErrorLine(errorBuffer, exit))
            }

            if (!output.exists() || output.length() == 0L) {
                return Result.Failure(FailureReason.NO_OUTPUT)
            }

            onProgress(100)
            return Result.Success(output)
        } catch (e: Exception) {
            runCatching { process.destroy() }
            output.delete()
            return Result.Failure(FailureReason.FFMPEG_ERROR, e.message)
        } finally {
            runCatching { errorThread.join(TimeUnit.SECONDS.toMillis(2)) }
        }
    }

    /**
     * Основной кодировщик — встроенный mpeg4, запасной — libxvid.
     *
     * Они пишут один и тот же MPEG-4 Part 2 (ASP), и с -vtag XVID файл неотличим для
     * DivX/Xvid-плееров. Разница в скорости: libxvid в этой сборке не объявляет
     * AV_CODEC_CAP_SLICE_THREADS / AV_CODEC_CAP_FRAME_THREADS, и FFmpeg в
     * validate_thread_parameters() сбрасывает thread_count в 1 — кодирование идёт
     * в одно ядро. У mpeg4 slice-потоки есть, поэтому -threads 0 грузит все ядра.
     * Запасной вариант нужен на случай, если конкретный плеер придирчив к bitstream.
     */
    private const val PRIMARY_ENCODER = "mpeg4"
    private const val FALLBACK_ENCODER = "libxvid"

    private fun buildCommand(
        binary: File,
        input: File,
        output: File,
        encoder: String
    ): List<String> = buildList {
        add(binary.absolutePath)
        addAll(listOf("-y", "-hide_banner", "-nostats", "-loglevel", "error", "-progress", "pipe:1"))
        addAll(listOf("-i", input.absolutePath))
        addAll(listOf("-vf", scaleFilter()))
        addAll(listOf("-c:v", encoder))
        // mpeg4 умеет slice-многопоточность, libxvid — нет (FFmpeg всё равно сбросит в 1)
        if (encoder == PRIMARY_ENCODER) addAll(listOf("-threads", "0"))
        addAll(listOf("-b:v", VIDEO_BITRATE, "-pix_fmt", "yuv420p"))
        // B-кадры в упакованном bitstream ломают часть старых плееров
        addAll(listOf("-bf", "0", "-vtag", "XVID"))
        addAll(listOf("-c:a", "libmp3lame", "-b:a", "128k", "-ar", "44100", "-ac", "2"))
        addAll(listOf("-f", "avi", output.absolutePath))
    }

    /**
     * Не апскейлим источник меньше 720p. Xvid требует чётную высоту, поэтому
     * trunc(.../2)*2. Запятая внутри min() экранируется — это разделитель фильтров.
     */
    private fun scaleFilter(): String = "scale=-2:trunc(min($MAX_HEIGHT\\,ih)/2)*2"

    private fun lastErrorLine(buffer: StringBuilder, exitCode: Int): String =
        buffer.toString().lines().lastOrNull { it.isNotBlank() } ?: "exit code $exitCode"

    /**
     * ffmpeg -progress печатает пары key=value. Исторически out_time_ms в FFmpeg содержит
     * микросекунды, а не миллисекунды (так и не исправлено ради совместимости), поэтому
     * оба ключа трактуем как микросекунды и дополнительно читаем out_time как H:MM:SS.
     */
    private fun parseProgress(line: String, durationSec: Long): Int? {
        if (durationSec <= 0) return null

        val key = line.substringBefore('=', "")
        val value = line.substringAfter('=', "").trim()

        val seconds = when (key) {
            "out_time_us", "out_time_ms" -> value.toLongOrNull()?.div(1_000_000.0)
            "out_time" -> parseTimestamp(value)
            else -> null
        } ?: return null

        return (seconds / durationSec * 100).toInt().coerceIn(0, 99)
    }

    private fun parseTimestamp(value: String): Double? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toDoubleOrNull() ?: return null
        val m = parts[1].toDoubleOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    private fun ffmpegBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /**
     * Порядок важен: сначала usr/lib самого FFmpeg (там свои libav*, libxvidcore),
     * потом C++ runtime из пакета Python, затем нативные библиотеки APK.
     */
    private fun libraryPath(context: Context, ffmpegLibDir: File, pythonLibDir: File): String =
        listOf(
            ffmpegLibDir.absolutePath,
            pythonLibDir.absolutePath,
            context.applicationInfo.nativeLibraryDir
        ).joinToString(":")

    /** Куда FFmpeg.init() распаковывает общие библиотеки. */
    private fun ffmpegPackageDir(context: Context): File =
        File(packagesDir(context), "ffmpeg")

    /** Пакет Python (распаковывается инициализацией yt-dlp) — источник libc++_shared.so. */
    private fun pythonPackageDir(context: Context): File =
        File(packagesDir(context), "python")

    private fun packagesDir(context: Context): File =
        File(File(context.noBackupFilesDir, "youtubedl-android"), "packages")

    private const val MAX_ERROR_LENGTH = 4000

    /** C++ runtime, который FFmpeg-пакет ожидает, но не поставляет. */
    private const val REQUIRED_RUNTIME = "libc++_shared.so"
}
