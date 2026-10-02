package com.example.ytdownloader.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object DownloadUtil {

    /**
     * Enqueues a video/audio download in the system DownloadManager.
     * The file is saved directly into the user's Downloads folder and registered in the Gallery.
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        title: String,
        quality: String,
        extension: String
    ): Long {
        val sanitizedTitle = sanitizeFilename(title)
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val fileName = "${sanitizedTitle}_${quality.replace(" ", "_")}$ext"

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(title)
            setDescription("Скачивание $quality...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
            .trim()
            .take(60)
            .ifEmpty { "YouTube_Video" }
    }
}
