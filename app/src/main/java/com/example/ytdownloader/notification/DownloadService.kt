package com.example.ytdownloader.notification

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.ytdownloader.R
import com.example.ytdownloader.data.DownloadController

/**
 * Foreground-сервис на время скачивания.
 *
 * Без него Android переводит свёрнутое приложение в фон, замораживает процесс,
 * и yt-dlp, проснувшись через минуту-другую, получает от YouTube «Sign in to confirm
 * you're not a bot»: подпись ссылки на поток к этому моменту истекает, а cookies
 * загружены без активной сессии. Пользователю это показывалось как «авторизуйтесь».
 *
 * Сервис не делает саму загрузку — ею владеет [DownloadController]. Он лишь держит
 * процесс в foreground, пока контроллер сообщает о прогрессе, и обновляет ту же
 * нотификацию, что и раньше.
 */
class DownloadService : Service() {

    private var observing = false

    /** Храним конкретный объект: stopObserving отписывает по ссылке, а `::onDownloadEvent`
     *  каждый раз создаёт новый экземпляр и отписка бы не сработала. */
    private val eventListener: (DownloadController.Event) -> Unit = ::onDownloadEvent

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = DownloadController.get(this)

        // startForeground нужно вызвать в первые 5 секунд после старта сервиса
        promoteToForeground(controller)

        if (!observing) {
            observing = true
            controller.observe(eventListener)
        }

        // Загрузка закончилась, пока Activity была в фоне и сервис уже не нужен
        if (!controller.isRunning) stopSelf(startId)

        return START_NOT_STICKY
    }

    private fun onDownloadEvent(event: DownloadController.Event) {
        when (event) {
            is DownloadController.Event.Progress -> Unit
            // Финальную нотификацию показывает DownloadNotifier, foreground можно снимать
            DownloadController.Event.Completed,
            is DownloadController.Event.Failed -> stopSelf()
        }
    }

    private fun promoteToForeground(controller: DownloadController) {
        val notifier = DownloadNotifier(this)
        notifier.ensureChannel()

        val text = controller.lastStatusText ?: getString(R.string.status_downloading)
        val notification = notifier.progressNotification(
            title = getString(R.string.notification_channel_name),
            text = text,
            percent = controller.lastProgress
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, DownloadNotifier.NOTIFICATION_ID, notification, type)
        }
    }

    override fun onDestroy() {
        if (observing) DownloadController.get(this).stopObserving(eventListener)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    companion object {
        fun start(context: android.content.Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
