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

        // Ссылка для поддержки разработчика (CloudTips)
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

        setupThemeToggle()
        setupListeners()
        handleIncomingIntent(intent)
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
        // Quick paste from clipboard
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

        // Fetch / Search video details
        binding.btnFetch.setOnClickListener {
            val text = binding.etUrl.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                fetchVideoDetails(text)
            } else {
                Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            }
        }

        // Main download button for selected quality & size
        binding.btnDownloadSelected.setOnClickListener {
            checkPermissionsAndDownload()
        }

        // Open in gallery / Downloads folder
        binding.btnViewVideo.setOnClickListener {
            openDownloadsFolder()
        }

        // Share button
        binding.btnShare.setOnClickListener {
            val video = currentVideoInfo ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, video.title)
                putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=${video.videoId}")
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться видео"))
        }

        // Support Developer buttons
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
                binding.etUrl.setText(sharedText)
                fetchVideoDetails(sharedText)
            }
        }
    }

    /**
     * Fetches video info, extracts formats and updates the quality selector dropdown.
     */
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

    /**
     * Displays thumbnail, title, author, and configures the Quality & File Size Dropdown.
     */
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

            // Select 720p or 1080p by default, or the first available option
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
        binding.tvStatus.text = "Подготовка файла к скачиванию..."

        lifecycleScope.launch {
            val resolveResult = apiService.resolveDownloadUrl(video.videoId, format)
            binding.progressIndicator.visibility = View.GONE
            binding.btnDownloadSelected.isEnabled = true

            resolveResult.onSuccess { directUrl ->
                try {
                    DownloadUtil.enqueueDownload(
                        context = this@MainActivity,
                        url = directUrl,
                        title = video.title,
                        quality = format.qualityLabel,
                        extension = format.extension
                    )
                    binding.tvStatus.text = getString(R.string.status_success)
                    Toast.makeText(this@MainActivity, "Скачивание началось! Файл будет в папке «Загрузки»", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    binding.tvStatus.text = getString(R.string.error_download_failed)
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                binding.tvStatus.text = error.message ?: getString(R.string.error_download_failed)
                Toast.makeText(this@MainActivity, error.message ?: "Ошибка получения ссылки", Toast.LENGTH_LONG).show()
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
