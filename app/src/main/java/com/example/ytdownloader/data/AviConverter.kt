package com.example.ytdownloader.data

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Конвертация скачанного видео в AVI (Xvid + MP3) для старых телевизоров и DVD-плееров.
 *
 * FFmpeg из youtubedl-android — это обычный CLI-бинарь (libffmpeg.so), собранный
 * с --enable-libxvid и --enable-libmp3lame, поэтому запускаем его отдельным процессом
 * и читаем прогресс из -progress pipe:1.
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

        val process = try {
            ProcessBuilder(buildCommand(binary, libDir, input, output))
                .redirectErrorStream(false)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        "${libDir.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
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

    private fun buildCommand(binary: File, libDir: File, input: File, output: File): List<String> =
        listOf(
            binary.absolutePath,
            "-y", "-hide_banner", "-nostats",
            "-loglevel", "error",
            "-progress", "pipe:1",
            "-i", input.absolutePath,
            "-vf", scaleFilter(),
            "-c:v", "libxvid",
            "-b:v", VIDEO_BITRATE,
            "-pix_fmt", "yuv420p",
            // B-кадры в упакованном bitstream ломают часть старых плееров
            "-bf", "0",
            "-vtag", "XVID",
            "-c:a", "libmp3lame",
            "-b:a", "128k",
            "-ar", "44100",
            "-ac", "2",
            "-f", "avi",
            output.absolutePath
        )

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

    /** Куда FFmpeg.init() распаковывает общие библиотеки. */
    private fun ffmpegPackageDir(context: Context): File =
        File(File(File(context.noBackupFilesDir, "youtubedl-android"), "packages"), "ffmpeg")

    private const val MAX_ERROR_LENGTH = 4000
}
