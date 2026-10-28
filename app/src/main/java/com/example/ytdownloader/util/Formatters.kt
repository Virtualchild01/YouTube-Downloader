package com.example.ytdownloader.util

import android.content.Context
import com.example.ytdownloader.R
import java.util.Locale

/**
 * Форматирование размеров и длительности.
 *
 * Раньше эти функции жили в MainActivity (fmt размера, ETA) и в GalleryAdapter (своя копия
 * формата размера с другими правилами). Теперь правило одно на всё приложение.
 */
object Formatters {

    private const val BYTES_IN_MB = 1024.0 * 1024.0
    private const val MB_IN_GB = 1024.0

    /** Размер файла для галереи: «12 МБ», «1.35 ГБ». */
    fun size(context: Context, bytes: Long): String {
        if (bytes <= 0) return context.getString(R.string.size_unknown)
        val mb = bytes / BYTES_IN_MB
        return if (mb >= MB_IN_GB) {
            context.getString(R.string.size_gb, mb / MB_IN_GB)
        } else {
            context.getString(R.string.size_mb, mb)
        }
    }

    /** Предварительная оценка размера для списка качества: «≈ 42.1 МБ». */
    fun estimatedSize(context: Context, bytes: Long): String =
        context.getString(R.string.size_estimate, size(context, bytes))

    /** Приблизительный размер из длительности и битрейта. */
    fun estimateBytes(durationSec: Long, bitrateBps: Long): Long =
        if (durationSec <= 0) 0L else durationSec * bitrateBps / 8L

    /** «45 с», «8 мин 20 с», «1 ч 15 мин». */
    fun duration(context: Context, seconds: Long): String {
        if (seconds <= 0) return ""
        return when {
            seconds >= 3600 -> {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                if (minutes > 0) {
                    context.getString(R.string.eta_hours_minutes, hours, minutes)
                } else {
                    context.getString(R.string.eta_hours, hours)
                }
            }
            seconds >= 60 -> {
                val minutes = seconds / 60
                val secs = seconds % 60
                if (secs > 0) {
                    context.getString(R.string.eta_minutes_seconds, minutes, secs)
                } else {
                    context.getString(R.string.eta_minutes, minutes)
                }
            }
            else -> context.getString(R.string.eta_seconds, seconds)
        }
    }

    /** Таймкод плеера: «1:05:09» или «5:09». */
    fun timecode(milliseconds: Long): String {
        val totalSec = milliseconds / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
