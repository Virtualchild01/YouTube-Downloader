package com.example.ytdownloader.data

import androidx.annotation.StringRes
import com.example.ytdownloader.R

enum class QualityKind {
    /** Обычный поток YouTube: видео + аудио, склеиваются в mp4. */
    VIDEO,

    /** Только звуковая дорожка, yt-dlp сам перекодирует в mp3. */
    AUDIO,

    /** Скачиваем mp4, затем локально перекодируем в AVI через FFmpeg. */
    AVI
}

/**
 * Пункт списка качества.
 *
 * Раньше качество определялось сравнением русских подписей через contains("720"),
 * из-за чего любая правка текста ломала и выбор формата, и фильтрацию по доступности.
 * Теперь данные лежат рядом с подписью, а подпись — только ресурс для отображения.
 */
data class QualityPreset(
    @StringRes val labelRes: Int,
    val kind: QualityKind,
    /** Длинная сторона кадра, которую должен уметь отдать ролик. 0 — фильтр не нужен. */
    val minLongEdge: Int,
    /** Ограничение высоты для -f. 0 — без ограничения. */
    val maxHeight: Int,
    /** Битррейт для предварительной оценки размера файла. */
    val estimateBitrateBps: Long
) {
    val isAudio: Boolean get() = kind == QualityKind.AUDIO

    /** HDR имеет смысл только для видео: в mp3 его нет, а AVI перекодируется в Xvid 8 бит. */
    val supportsHdr: Boolean get() = kind == QualityKind.VIDEO

    /**
     * Значение для `-f`. Для аудио не используется — там достаточно `bestaudio`.
     *
     * HDR отдельным фильтром здесь не запрашивается намеренно. yt-dlp сортирует форматы
     * по `res`, а `hdr` идёт следующим полем, поэтому при равном разрешении он сам берёт
     * HDR-поток (AV1/VP9.2), а при его отсутствии — SDR. Если поставить HDR первым
     * фильтром, ролик, у которого HDR есть только в 1080p, скачался бы в 1080p вместо
     * доступного 4K — то есть выбор качества деградировал бы.
     */
    val formatSelector: String
        get() = when (kind) {
            QualityKind.AUDIO -> "bestaudio"
            else ->
                "bestvideo[height<=$maxHeight][ext=mp4]+bestaudio[ext=m4a]" +
                    "/bestvideo[height<=$maxHeight]+bestaudio" +
                    "/best[height<=$maxHeight]/best"
        }

    /** Есть ли в ролике разрешение, достаточное для этого пункта. */
    fun isAvailableIn(resolutions: List<Pair<Int, Int>>): Boolean {
        if (minLongEdge == 0) return true
        return resolutions.any { (w, h) -> maxOf(w, h) >= minLongEdge }
    }
}

object QualityPresets {

    /**
     * 16K (8640p) и 8K (4320p). YouTube пока не отдаёт 16K, поэтому этот пункт
     * в обычном ролике просто не появится — фильтр по [QualityPreset.minLongEdge]
     * скрывает его, пока такого потока нет. Битррейты — грубая оценка для подписи
     * размера; реальный размер yt-dlp покажет в прогрессе.
     */
    val UHD_8640 = QualityPreset(R.string.quality_8640, QualityKind.VIDEO, 15360, 8640, 120_000_000L)
    val UHD_4320 = QualityPreset(R.string.quality_4320, QualityKind.VIDEO, 7680, 4320, 45_000_000L)
    val UHD_2160 = QualityPreset(R.string.quality_2160, QualityKind.VIDEO, 3840, 2160, 18_000_000L)
    val QHD_1440 = QualityPreset(R.string.quality_1440, QualityKind.VIDEO, 2560, 1440, 9_000_000L)
    val FHD_1080 = QualityPreset(R.string.quality_1080, QualityKind.VIDEO, 1920, 1080, 3_500_000L)
    val HD_720 = QualityPreset(R.string.quality_720, QualityKind.VIDEO, 1280, 720, 2_000_000L)
    val SD_480 = QualityPreset(R.string.quality_480, QualityKind.VIDEO, 854, 480, 1_000_000L)
    val SD_360 = QualityPreset(R.string.quality_360, QualityKind.VIDEO, 640, 360, 600_000L)
    val MP3 = QualityPreset(R.string.quality_mp3, QualityKind.AUDIO, 0, 0, 160_000L)

    /**
     * Потолок 720p: перекодировать 4K в Xvid бессмысленно — старые плееры его не потянут,
     * а конвертация займёт в разы больше времени.
     */
    val AVI = QualityPreset(R.string.quality_avi, QualityKind.AVI, 0, 720, 2_400_000L)

    val ALL = listOf(
        UHD_8640, UHD_4320, UHD_2160, QHD_1440, FHD_1080,
        HD_720, SD_480, SD_360, MP3, AVI
    )

    val DEFAULT = HD_720

    /** Только те пункты, которые реально доступны для этого ролика. */
    fun availableIn(resolutions: List<Pair<Int, Int>>): List<QualityPreset> {
        if (resolutions.isEmpty()) return ALL
        return ALL.filter { it.isAvailableIn(resolutions) }.ifEmpty { ALL }
    }
}
