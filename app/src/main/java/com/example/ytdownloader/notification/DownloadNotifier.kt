package com.example.ytdownloader.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ytdownloader.R
import com.example.ytdownloader.ui.GalleryActivity

/**
 * Прогресс скачивания в шторке.
 *
 * Вынесено из MainActivity: там же были два try/catch-обёртки вокруг нотификаций,
 * а обновление прогресса вызывалось из пяти мест с разными аргументами.
 */
class DownloadNotifier(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Нужно ли просить разрешение на уведомления (Android 13+). */
    fun needsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

    /** Обновление полосы прогресса. Не падает, если уведомления запрещены. */
    fun progress(title: String, text: String, percent: Int) {
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent.coerceIn(0, 100), false)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun clearProgress() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    fun finished(title: String, text: String) {
        runCatching {
            manager.cancel(NOTIFICATION_ID)

            // Открываем галерею приложения, а не системную папку «Загрузки»:
            // файлы теперь лежат в Movies/YTDownloader и Music/YTDownloader.
            val intent = Intent(context, GalleryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            manager.notify(NOTIFICATION_COMPLETE_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "yt_downloader_channel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_COMPLETE_ID = 1002
    }
}
