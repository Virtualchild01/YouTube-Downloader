package com.example.ytdownloader.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream

/**
 * Хранилище скачанных файлов: Movies/YTDownloader для видео, Music/YTDownloader для аудио.
 *
 * На Android 10+ используется MediaStore, поэтому разрешения не нужны,
 * а файлы при этом видны в системной галерее и файловых менеджерах.
 * На Android 7-9 пишем напрямую в публичную папку и просим MediaScanner
 * проиндексировать результат.
 *
 * Почему не Downloads: коллекцию MediaStore.Video нельзя писать напрямую в корень
 * внешнего тома — система отвечает "Primary directory not allowed for content:
 * //media/external_primary/video/media; allowed directories are [DCIM, Movies, Pictures]".
 * В Movies/Music разрешено писать, а сами папки штатно видны и в галерее, и в Проводнике.
 */
object DownloadStorage {

    private const val SUBDIR = "YTDownloader"

    /** Куда складывали файлы версии до 1.0.41 — читаем оттуда, но больше не пишем. */
    private val legacyRelativePath: String
        get() = "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR"

    /**
     * Относительный путь внутри внешнего тома, как его ждёт MediaStore.
     * Видео и аудио лежат в разных публичных папках: в Music видео не индексируется
     * как видео, а в Movies аудио-плееры его не находят.
     */
    private fun relativePathFor(mime: String): String {
        val base = if (mime.startsWith("video/")) {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_MUSIC
        }
        return "$base/$SUBDIR"
    }

    // ---------------------------------------------------------------- запись

    fun save(context: Context, source: File, displayName: String): Uri? {
        val safeName = sanitizeName(displayName)
        val mime = mimeFromName(safeName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, source, safeName, mime)
        } else {
            saveLegacy(context, source, safeName, mime)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(context: Context, source: File, displayName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        val collection = if (mime.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(mime))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null

        val output = try {
            resolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        }

        if (output == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        try {
            output.use { out -> FileInputStream(source).use { it.copyTo(out) } }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(uri, published, null, null) }

        return uri
    }

    private fun saveLegacy(context: Context, source: File, displayName: String, mime: String): Uri? {
        val baseDir = if (mime.startsWith("video/")) {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_MUSIC
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null

        val target = uniqueFile(dir, displayName)
        return try {
            source.copyTo(target, overwrite = false)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            Uri.fromFile(target)
        } catch (e: Exception) {
            null
        }
    }

    private fun uniqueFile(dir: File, displayName: String): File {
        val base: String
        val ext: String
        val dot = displayName.lastIndexOf('.')
        if (dot > 0) {
            base = displayName.substring(0, dot)
            ext = displayName.substring(dot)
        } else {
            base = displayName
            ext = ""
        }

        var candidate = File(dir, displayName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }

    // --------------------------------------------------------------- чтение

    fun list(context: Context): List<DownloadedMedia> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listScoped(context)
        } else {
            listLegacy()
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listScoped(context: Context): List<DownloadedMedia> {
        // Video и Audio опрашиваем по отдельности: у MediaStore.Files нет колонки DURATION,
        // а эти коллекции её поддерживают и отдают корректную длительность.
        val videos = queryScoped(
            context,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            relativePathFor("video/mp4")
        )
        val audio = queryScoped(
            context,
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            relativePathFor("audio/mpeg")
        )

        // Раньше видео складывалось в Downloads/YTDownloader — такие файлы тоже показываем,
        // чтобы у тех, кто скачивал предыдущие версии, галерея не опустела.
        val legacyAudio = queryScoped(
            context,
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            legacyRelativePath
        )
        val legacyVideo = queryScoped(
            context,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            legacyRelativePath
        )

        return (videos + audio + legacyVideo + legacyAudio)
            .distinctBy { it.uri }
            .sortedByDescending { it.dateAddedSec }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryScoped(context: Context, collection: Uri, pathPrefix: String): List<DownloadedMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DURATION
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"

        val result = mutableListOf<DownloadedMedia>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                arrayOf("$pathPrefix%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val durCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)

                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    if (name.isNullOrEmpty()) continue
                    result += DownloadedMedia(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                        displayName = name,
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                        dateAddedSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    )
                }
            }
        } catch (e: Exception) {
            // Пустая галерея лучше, чем падение приложения
        }
        return result
    }

    private fun listLegacy(): List<DownloadedMedia> {
        // Android 7-9: обе папки, Movies и Music, плюс старая Downloads/YTDownloader
        val dirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUBDIR),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        )

        return dirs
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles()
                    ?.filter { it.isFile && isSupportedExtension(it.extension) }
                    ?.map { file ->
                        DownloadedMedia(
                            uri = Uri.fromFile(file),
                            displayName = file.name,
                            sizeBytes = file.length(),
                            dateAddedSec = file.lastModified() / 1000L,
                            durationMs = 0L,
                            mimeType = mimeFromName(file.name)
                        )
                    }
                    ?: emptyList()
            }
            .sortedByDescending { it.dateAddedSec }
    }

    // ------------------------------------------------------------- удаление

    fun delete(context: Context, media: DownloadedMedia): Boolean {
        return try {
            if (media.uri.scheme == "content") {
                context.contentResolver.delete(media.uri, null, null) > 0
            } else {
                val path = media.uri.path ?: return false
                File(path).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------- утилиты

    private fun isSupportedExtension(ext: String?): Boolean {
        val e = ext?.lowercase() ?: return false
        return e in setOf("mp4", "mkv", "webm", "avi", "mov", "m4a", "mp3", "opus", "ogg", "aac", "flac", "wav")
    }

    /** Готовый медиафайл, а не .part/.ytdl/временный артефакт FFmpeg. */
    fun isMediaFile(name: String): Boolean =
        isSupportedExtension(name.substringAfterLast('.', ""))

    /**
     * MediaStore отвергает DISPLAY_NAME с разделителями пути и управляющими символами,
     * поэтому название ролика приходится приводить к безопасному виду.
     */
    fun sanitizeName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trimStart('.')
        return cleaned.ifEmpty { "video_${System.currentTimeMillis()}" }
    }

    fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
