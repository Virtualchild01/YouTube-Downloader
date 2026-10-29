package com.example.ytdownloader.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ytdownloader.R
import com.example.ytdownloader.databinding.ActivityPlayerBinding

/**
 * Встроенный плеер для MP4/WebM/MKV и аудио. AVI сюда не попадает — его открывает системный плеер.
 *
 * Есть два режима:
 *  - обычный: видна верхняя панель с названием и кнопками;
 *  - полноэкранный: панель и системные бары прячутся, остаётся только видео.
 * Панель в полноэкранном режиме возвращается вместе с контролем плеера
 * (тап по видео) и снова исчезает после автопрокрутки — как в YouTube.
 *
 * Поворот экрана — отдельная кнопка: она переключает книжную и альбомную
 * ориентации, не полагаясь на датчик. Автоповорот с датчиком тоже работает,
 * манифест объявляет configChanges, поэтому Activity не пересоздаётся.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private var isFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnFullscreen.setOnClickListener { setFullscreen(!isFullscreen) }
        binding.btnRotate.setOnClickListener { toggleOrientation() }

        // В полноэкранном режиме «назад» сначала возвращает обычный вид,
        // иначе жест закрывал бы плеер без возможности передумать.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) setFullscreen(false) else finish()
            }
        })

        // Панель живёт вместе с контролем плеера: появился контроль — показали панель,
        // ушёл по автопрокрутке — убрали. Иначе в полноэкранном режиме она висела бы вечно.
        binding.playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    if (isFullscreen) {
                        binding.topBar.visibility =
                            if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
                    }
                }
            }
        )

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            finish()
            return
        }

        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
            exo.prepare()
            exo.playWhenReady = true
        }

        // Восстанавливаем режим при пересоздании (например, после смены темы)
        if (savedInstanceState?.getBoolean(STATE_FULLSCREEN) == true) {
            setFullscreen(true)
        }
    }

    /**
     * Переключает полноэкранный режим: скрывает панель и системные бары.
     *
     * systemBars() захватывает и статус-бар, и навигацию — в альбомной ориентации
     * навигация уезжает вбок, и без этого видео не заняло бы весь экран.
     */
    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled

        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            binding.topBar.visibility = View.GONE
            binding.btnFullscreen.setIconResource(R.drawable.ic_fullscreen_exit)
            binding.btnFullscreen.contentDescription = getString(R.string.exit_fullscreen)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            binding.topBar.visibility = View.VISIBLE
            binding.btnFullscreen.setIconResource(R.drawable.ic_fullscreen)
            binding.btnFullscreen.contentDescription = getString(R.string.fullscreen)
        }
    }

    /** Кнопка «Повернуть»: альбомная ↔ книжная. */
    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FULLSCREEN, isFullscreen)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        private const val STATE_FULLSCREEN = "state_fullscreen"
    }
}
