package com.example.ytdownloader.utils

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DownloadUtil {

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Downloads file directly in the app using OkHttp with progress callback.
     * This avoids Android DownloadManager issues (such as getting stuck in 'Waiting for connection' on Xiaomi).
     */
    suspend fun downloadDirectly(
        context: Context,
        url: String,
        title: String,
        quality: String,
        extension: String,
        onProgress: (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sanitizedTitle = sanitizeFilename(title)
            val ext = if (extension.startsWith(".")) extension else ".$extension"
            val fileName = "${sanitizedTitle}_${quality.replace(" ", "_")}$ext"

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val destinationFile = File(downloadsDir, fileName)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build()

            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Сервер вернул ошибку HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Пустой ответ от сервера"))
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportedPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt()
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent, totalRead, totalBytes)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onProgress(-1, totalRead, -1L)
                            }
                        }
                    }
                    output.flush()
                }
            }

            // Register downloaded media file with Android MediaStore
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destinationFile.absolutePath),
                null,
                null
            )

            Result.success(destinationFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
            .trim()
            .take(60)
            .ifEmpty { "YouTube_Video" }
    }
}
