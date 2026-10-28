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

    sealed interface Result {
        data class Success(val file: File) : Result
        data class Failure(val message: String) : Result
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
            return Result.Failure("FFmpeg не инициализирован: ${e.message ?: "неизвестная ошибка"}")
        }

        val binary = ffmpegBinary(context)
        if (!binary.exists() || !binary.canExecute()) {
            return Result.Failure("FFmpeg недоступен, перезапустите приложение")
        }

        if (!libDir.isDirectory) {
            return Result.Failure("FFmpeg не распакован, перезапустите приложение")
        }

        val command = buildList {
            add(binary.absolutePath)
            add("-y")
            add("-hide_banner")
            add("-nostats")
            add("-loglevel")
            add("error")
            add("-progress")
            add("pipe:1")
            add("-i")
            add(input.absolutePath)
            add("-vf")
            // Не апскейлим: если источник меньше 720p, оставляем как есть.
            // Запятая внутри выражения scale — разделитель фильтров, её надо экранировать.
            add("scale=-2:min($MAX_HEIGHT\\,ih)")
            add("-c:v")
            add("libxvid")
            add("-b:v")
            add(VIDEO_BITRATE)
            add("-pix_fmt")
            add("yuv420p")
            // B-кадры в упакованном bitstream ломают часть старых плееров
            add("-bf")
            add("0")
            add("-vtag")
            add("XVID")
            add("-c:a")
            add("libmp3lame")
            add("-b:a")
            add("128k")
            add("-ar")
            add("44100")
            add("-ac")
            add("2")
            add("-f")
            add("avi")
            add(output.absolutePath)
        }

        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"] = context.filesDir.absolutePath
                }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "cannot start ffmpeg", e)
            return Result.Failure("Не удалось запустить FFmpeg")
        }

        val errorBuffer = StringBuilder()
        val errorThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (errorBuffer.length < 4000) errorBuffer.appendLine(line)
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                val percent = parseProgress(line, durationSec)
                if (percent != null) onProgress(percent)
            }

            val exit = process.waitFor()

            if (exit != 0) {
                output.delete()
                val detail = errorBuffer.toString().lines()
                    .lastOrNull { it.isNotBlank() }
                    ?: "код выхода $exit"
                return Result.Failure("Конвертация не удалась: $detail")
            }

            if (!output.exists() || output.length() == 0L) {
                return Result.Failure("FFmpeg не создал файл")
            }

            onProgress(100)
            return Result.Success(output)
        } catch (e: Exception) {
            runCatching { process.destroy() }
            output.delete()
            return Result.Failure(e.message ?: "Ошибка конвертации")
        } finally {
            runCatching { errorThread.join(TimeUnit.SECONDS.toMillis(2)) }
        }
    }

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
}
