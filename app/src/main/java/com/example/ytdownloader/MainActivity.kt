package com.example.ytdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.ytdownloader.api.VideoFormat
import com.example.ytdownloader.api.YouTubeApiService
import com.example.ytdownloader.api.YouTubeVideoInfo
import com.example.ytdownloader.databinding.ActivityMainBinding
import com.example.ytdownloader.utils.DownloadUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val apiService = YouTubeApiService()

    private var currentVideoInfo: YouTubeVideoInfo? = null
    private var selectedFormat: VideoFormat? = null

    companion object {
        private const val PREFS_NAME = "yt_downloader_prefs"
        private const val KEY_THEME = "key_theme_mode"
        private const val THEME_AUTO = 0
        private const val THEME_DARK = 1
        private const val THEME_LIGHT = 2

        const val DONATION_URL = "https://pay.cloudtips.ru/p/f35243af"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            startDownloadProcess()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initEngineAsync()
        setupThemeToggle()
        setupListeners()
        handleIncomingIntent(intent)
    }

    private fun initEngineAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun applySavedTheme() {
        when (prefs.getInt(KEY_THEME, THEME_AUTO)) {
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun setupThemeToggle() {
        when (prefs.getInt(KEY_THEME, THEME_AUTO)) {
            THEME_DARK -> binding.toggleThemeGroup.check(R.id.btnThemeDark)
            THEME_LIGHT -> binding.toggleThemeGroup.check(R.id.btnThemeLight)
            else -> binding.toggleThemeGroup.check(R.id.btnThemeAuto)
        }

        binding.toggleThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newTheme = when (checkedId) {
                    R.id.btnThemeDark -> THEME_DARK
                    R.id.btnThemeLight -> THEME_LIGHT
                    else -> THEME_AUTO
                }

                if (newTheme != prefs.getInt(KEY_THEME, THEME_AUTO)) {
                    prefs.edit().putInt(KEY_THEME, newTheme).apply()
                    when (newTheme) {
                        THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                binding.etUrl.setText(text)
                fetchVideoDetails(text)
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFetch.setOnClickListener {
            val text = binding.etUrl.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                fetchVideoDetails(text)
            } else {
                Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDownloadSelected.setOnClickListener {
            checkPermissionsAndDownload()
        }

        binding.btnViewVideo.setOnClickListener {
            openDownloadsFolder()
        }

        binding.btnShare.setOnClickListener {
            val video = currentVideoInfo ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, video.title)
                putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=${video.videoId}")
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться видео"))
        }

        binding.btnSupport.setOnClickListener { showSupportDialog() }
        binding.btnSupportHeader.setOnClickListener { showSupportDialog() }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                binding.etUrl.setText(sharedText)
                fetchVideoDetails(sharedText)
            }
        }
    }

    private fun fetchVideoDetails(input: String) {
        val videoId = apiService.extractVideoId(input)
        if (videoId == null) {
            binding.tvStatus.text = getString(R.string.error_invalid_url)
            Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressIndicator.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.status_fetching_info)
        binding.btnFetch.isEnabled = false

        lifecycleScope.launch {
            val result = apiService.fetchVideoInfo(videoId)
            binding.progressIndicator.visibility = View.GONE
            binding.btnFetch.isEnabled = true

            result.onSuccess { info ->
                currentVideoInfo = info
                displayVideoPreview(info)
            }.onFailure { error ->
                binding.tvStatus.text = error.message ?: getString(R.string.error_fetch_failed)
                Toast.makeText(this@MainActivity, error.message ?: getString(R.string.error_fetch_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayVideoPreview(info: YouTubeVideoInfo) {
        binding.cardPreview.visibility = View.VISIBLE
        binding.tvAuthor.text = info.author
        binding.tvVideoTitle.text = info.title
        binding.tvStatus.text = "Выберите качество и нажмите «Скачать»"

        if (info.thumbnailUrl.isNotEmpty()) {
            Glide.with(this)
                .load(info.thumbnailUrl)
                .centerCrop()
                .into(binding.ivCover)
        }

        if (info.formats.isNotEmpty()) {
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                info.formats
            )
            binding.actvQuality.setAdapter(adapter)

            val defaultFormat = info.formats.firstOrNull { it.qualityLabel.contains("720") }
                ?: info.formats.firstOrNull { it.qualityLabel.contains("1080") }
                ?: info.formats.first()

            selectedFormat = defaultFormat
            binding.actvQuality.setText(defaultFormat.displayLabel, false)
            updateDownloadButtonLabel(defaultFormat)

            binding.actvQuality.setOnItemClickListener { _, _, position, _ ->
                val chosen = info.formats[position]
                selectedFormat = chosen
                updateDownloadButtonLabel(chosen)
            }
        }
    }

    private fun updateDownloadButtonLabel(format: VideoFormat) {
        val sizeText = if (format.fileSizeFormatted.isNotEmpty()) " (${format.fileSizeFormatted})" else ""
        binding.btnDownloadSelected.text = "Скачать ${format.qualityLabel}$sizeText"
    }

    private fun checkPermissionsAndDownload() {
        if (selectedFormat == null) {
            Toast.makeText(this, "Пожалуйста, выберите качество видео", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startDownloadProcess()
        } else {
            val neededPermissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (neededPermissions.isNotEmpty()) {
                requestPermissionLauncher.launch(neededPermissions.toTypedArray())
            } else {
                startDownloadProcess()
            }
        }
    }

    private fun startDownloadProcess() {
        val video = currentVideoInfo ?: return
        val format = selectedFormat ?: return

        binding.progressIndicator.visibility = View.VISIBLE
        binding.btnDownloadSelected.isEnabled = false
        binding.tvStatus.text = "Запуск загрузки через встроенный yt-dlp..."

        val videoUrl = "https://www.youtube.com/watch?v=${video.videoId}"
        val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val sanitizedTitle = video.title
            .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
            .trim()
            .take(60)
            .ifEmpty { "video" }

        val request = YoutubeDLRequest(videoUrl)
        request.addOption("-o", "${targetDir.absolutePath}/$sanitizedTitle.%(ext)s")
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        request.addOption("--no-update")
        request.addOption("--extractor-args", "youtube:player_client=android,ios,mweb")
        request.addOption("--geo-bypass")
        request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")

        if (format.isAudioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
        } else {
            val q = format.id.filter { it.isDigit() }.ifEmpty { "720" }
            request.addOption("-f", "b[height<=$q]/bestvideo[height<=$q]+bestaudio/best[height<=$q]/best")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                try {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                } catch (_: Exception) {}

                YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                    runOnUiThread {
                        val progInt = progress.toInt()
                        if (progInt in 0..100) {
                            binding.tvStatus.text = "Скачивание: $progInt% (осталось ~${etaInSeconds}с)"
                        } else {
                            binding.tvStatus.text = "Обработка видео и аудио..."
                        }
                    }
                }

                runOnUiThread {
                    binding.progressIndicator.visibility = View.GONE
                    binding.btnDownloadSelected.isEnabled = true
                    binding.tvStatus.text = "Готово! Файл сохранен в «Загрузки»"
                    Toast.makeText(this@MainActivity, "Видео успешно сохранено в Загрузки!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // If local engine is challenged by YouTube anti-bot, seamlessly fallback to cloud tunnel
                runOnUiThread {
                    binding.tvStatus.text = "Защита YouTube активна. Переключение на облачный туннель..."
                }

                val resolveResult = apiService.resolveDownloadUrl(video.videoId, format)
                resolveResult.onSuccess { directUrl ->
                    try {
                        DownloadUtil.enqueueDownload(
                            context = this@MainActivity,
                            url = directUrl,
                            title = video.title,
                            quality = format.qualityLabel,
                            extension = format.extension
                        )
                        runOnUiThread {
                            binding.progressIndicator.visibility = View.GONE
                            binding.btnDownloadSelected.isEnabled = true
                            binding.tvStatus.text = "Скачивание через туннель началось! Файл в Загрузках."
                            Toast.makeText(this@MainActivity, "Скачивание запущено через облачный туннель!", Toast.LENGTH_LONG).show()
                        }
                    } catch (ex: Exception) {
                        runOnUiThread {
                            binding.progressIndicator.visibility = View.GONE
                            binding.btnDownloadSelected.isEnabled = true
                            val err = ex.message ?: "Ошибка"
                            binding.tvStatus.text = "Ошибка: $err"
                            Toast.makeText(this@MainActivity, "Ошибка: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                }.onFailure { tunnelError ->
                    runOnUiThread {
                        binding.progressIndicator.visibility = View.GONE
                        binding.btnDownloadSelected.isEnabled = true
                        val err = e.message ?: tunnelError.message ?: "Ошибка скачивания"
                        binding.tvStatus.text = "Ошибка: $err"
                        Toast.makeText(this@MainActivity, "Ошибка: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun openDownloadsFolder() {
        try {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            startActivity(intent)
        } catch (_: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
            }
            try {
                startActivity(Intent.createChooser(fallbackIntent, "Открыть папку Загрузки"))
            } catch (_: Exception) {
                Toast.makeText(this, "Файл сохранён в папку «Загрузки»", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSupportDialog() {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.support_dialog_title)
            .setMessage(R.string.support_dialog_message)
            .setIcon(R.drawable.ic_heart)
            .setNegativeButton(R.string.support_dialog_btn_close, null)

        if (DONATION_URL.isNotEmpty()) {
            builder.setPositiveButton(R.string.support_dialog_btn_support) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
            }
        }

        builder.show()
    }
}
