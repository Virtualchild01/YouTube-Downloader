package com.example.ytdownloader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.ytdownloader.R
import java.io.File

/**
 * «Поделиться» скачанным файлом: отдаём его в любое приложение через системный выбор.
 *
 * На Android 10+ файл уже лежит в MediaStore, то есть content:// — его можно
 * отдавать как есть. На Android 7–9 файл открывается как file://, а такой Uri
 * поймает FileUriExposedException в чужом приложении, поэтому сначала оборачиваем
 * его в FileProvider.
 */
object MediaSharer {

    fun share(context: Context, media: DownloadedMedia) {
        val uri = shareableUri(context, media.uri) ?: run {
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = media.mimeType.ifEmpty { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, media.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(send, context.getString(R.string.share_file))
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Ни одного приложения, принимающего файлы, — не роняем галерею
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Uri, который можно отдать наружу: content:// как есть, file:// через FileProvider. */
    private fun shareableUri(context: Context, uri: Uri): Uri? {
        return try {
            if (uri.scheme == "content") {
                uri
            } else {
                val path = uri.path
                if (path == null) null
                else FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(path)
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
