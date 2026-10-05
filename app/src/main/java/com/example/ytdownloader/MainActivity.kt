package com.example.ytdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.ytdownloader.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var currentVideoInfo: VideoInfo? = null
    private var currentUrl: String? = null
    private var selectedQuality: String = "720p HD"
    private var isYtDlInitialized = false

    companion object {
        private const val TAG = "YTDownloader"
        private const val PREFS_NAME = "yt_downloader_prefs"
        private const val KEY_THEME = "key_theme_mode"

        private const val THEME_AUTO = 0
        private const val THEME_DARK = 1
        private const val THEME_LIGHT = 2

        // Ссылка для поддержки разработчика (CloudTips)
        const val DONATION_URL = "https://pay.cloudtips.ru/p/f35243af"

        val QUALITY_OPTIONS = listOf(
            "1080p Full HD",
            "720p HD",
            "480p SD",
            "360p",
            "Аудио (MP3)"
        )
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun initEngineAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                isYtDlInitialized = true
                Log.d(TAG, "YoutubeDL engine initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize YoutubeDL", e)
            }

            try {
                FFmpeg.getInstance().init(applicationContext)
                Log.d(TAG, "FFmpeg initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FFmpeg", e)
            }
        }
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
        // Быстрая вставка из буфера обмена
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

        // Поиск информации о видео
        binding.btnFetch.setOnClickListener {
            val text = binding.etUrl.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                fetchVideoDetails(text)
            } else {
                Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            }
        }

        // Главная кнопка скачивания выбранного качества
        binding.btnDownloadSelected.setOnClickListener {
            checkPermissionsAndDownload()
        }

        // Открыть папку «Загрузки»
        binding.btnViewVideo.setOnClickListener {
            openDownloadsFolder()
        }

        // Поделиться ссылкой на видео
        binding.btnShare.setOnClickListener {
            val url = currentUrl ?: return@setOnClickListener
            val video = currentVideoInfo
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, video?.title ?: "Видео")
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться видео"))
        }

        // Поддержка разработчика
        binding.btnSupport.setOnClickListener {
            showSupportDialog()
        }
        binding.btnSupportHeader.setOnClickListener {
            showSupportDialog()
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                val cleanUrl = extractUrl(sharedText) ?: sharedText
                binding.etUrl.setText(cleanUrl)
                fetchVideoDetails(cleanUrl)
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val pattern = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    /**
     * Анализирует видео с помощью yt-dlp движка.
     */
    private fun fetchVideoDetails(input: String) {
        val url = extractUrl(input) ?: input.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            binding.tvStatus.text = getString(R.string.error_invalid_url)
            Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }

        currentUrl = url
        binding.progressIndicator.isIndeterminate = true
        binding.progressIndicator.visibility = View.VISIBLE
        binding.tvStatus.text = "Анализ видео через yt-dlp..."
        binding.btnFetch.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!isYtDlInitialized) {
                    YoutubeDL.getInstance().init(applicationContext)
                    try { FFmpeg.getInstance().init(applicationContext) } catch (_: Exception) {}
                    isYtDlInitialized = true
                }

                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-check-certificates")

                val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
                currentVideoInfo = info

                withContext(Dispatchers.Main) {
                    binding.progressIndicator.visibility = View.GONE
                    binding.btnFetch.isEnabled = true
                    displayVideoPreview(info)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressIndicator.visibility = View.GONE
                    binding.btnFetch.isEnabled = true
                    val err = e.message ?: "Не удалось получить информацию"
                    val userMsg = if (err.contains("timeout", ignoreCase = true) || err.contains("connect", ignoreCase = true)) {
                        "Тайм-аут подключения. Убедитесь, что включен VPN."
                    } else {
                        "Ошибка: $err"
                    }
                    binding.tvStatus.text = userMsg
                    Toast.makeText(this@MainActivity, userMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Отображает превью видео и инициализирует меню выбора качества.
     */
    private fun displayVideoPreview(info: VideoInfo) {
        binding.cardPreview.visibility = View.VISIBLE
        binding.tvAuthor.text = info.uploader ?: "YouTube"
        binding.tvVideoTitle.text = info.title ?: "Без названия"
        binding.tvStatus.text = "Видео готово к скачиванию!"

        val thumb = info.thumbnail
        if (!thumb.isNullOrEmpty()) {
            Glide.with(this)
                .load(thumb)
                .centerCrop()
                .into(binding.ivCover)
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            QUALITY_OPTIONS
        )
        binding.actvQuality.setAdapter(adapter)

        selectedQuality = "720p HD"
        binding.actvQuality.setText(selectedQuality, false)
        binding.btnDownloadSelected.text = "Скачать $selectedQuality"

        binding.actvQuality.setOnItemClickListener { _, _, position, _ ->
            selectedQuality = QUALITY_OPTIONS[position]
            binding.btnDownloadSelected.text = "Скачать $selectedQuality"
        }
    }

    private fun checkPermissionsAndDownload() {
        if (currentUrl == null) {
            Toast.makeText(this, "Сначала вставьте ссылку на видео", Toast.LENGTH_SHORT).show()
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
        val url = currentUrl ?: return
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) downloadDir.mkdirs()

        binding.progressIndicator.isIndeterminate = false
        binding.progressIndicator.progress = 0
        binding.progressIndicator.visibility = View.VISIBLE
        binding.btnDownloadSelected.isEnabled = false
        binding.tvStatus.text = "Подготовка к скачиванию..."

        val processId = "yt_dl_${System.currentTimeMillis()}"

        val request = YoutubeDLRequest(url)
        request.addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        request.addOption("--no-check-certificates")

        when (selectedQuality) {
            "1080p Full HD" -> request.addOption("-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best")
            "720p HD" -> request.addOption("-f", "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best")
            "480p SD" -> request.addOption("-f", "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best")
            "360p" -> request.addOption("-f", "bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/best[height<=360][ext=mp4]/best")
            "Аудио (MP3)" -> {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("-f", "bestaudio")
            }
            else -> request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, _ ->
                    runOnUiThread {
                        binding.progressIndicator.isIndeterminate = false
                        binding.progressIndicator.progress = progress.toInt()
                        val etaStr = if (etaInSeconds > 0) " (осталось ~${etaInSeconds}с)" else ""
                        binding.tvStatus.text = "Скачивание: ${progress.toInt()}%$etaStr"
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressIndicator.visibility = View.GONE
                    binding.btnDownloadSelected.isEnabled = true
                    binding.tvStatus.text = "Готово! Файл сохранён в «Загрузки»"
                    Toast.makeText(this@MainActivity, "Видео успешно сохранено в «Загрузки»!", Toast.LENGTH_LONG).show()

                    MediaScannerConnection.scanFile(
                        this@MainActivity,
                        arrayOf(downloadDir.absolutePath),
                        null,
                        null
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressIndicator.visibility = View.GONE
                    binding.btnDownloadSelected.isEnabled = true
                    val err = e.message ?: "Ошибка скачивания"
                    binding.tvStatus.text = "Ошибка: $err"
                    Toast.makeText(this@MainActivity, "Ошибка скачивания: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openDownloadsFolder() {
        try {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
            }
            try {
                startActivity(Intent.createChooser(fallbackIntent, "Открыть папку Загрузки"))
            } catch (ex: Exception) {
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
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
            }
        }

        builder.show()
    }
}
