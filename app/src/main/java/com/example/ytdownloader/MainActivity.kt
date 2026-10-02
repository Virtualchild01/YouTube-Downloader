package com.example.ytdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val apiService = YouTubeApiService()

    private var currentVideoInfo: YouTubeVideoInfo? = null
    private var selectedFormat: VideoFormat? = null

    private var isResolving = false
    private var resolveTimeoutJob: Job? = null

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

    private fun fetchVideoDetails(input: String) {
        val videoId = apiService.extractVideoId(input)
        if (videoId == null) {
            binding.tvStatus.text = getString(R.string.error_invalid_url)
            Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressIndicator.isIndeterminate = true
        binding.progressIndicator.visibility = View.VISIBLE
        binding.tvStatus.text = "Расшифровка потока на устройстве..."
        binding.btnFetch.isEnabled = false

        lifecycleScope.launch {
            val (title, author) = apiService.fetchBasicMetadata(videoId)
            val thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

            resolveViaHeadlessPlayer(videoId, title, author, thumbnail)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveViaHeadlessPlayer(
        videoId: String,
        title: String,
        author: String,
        thumbnail: String
    ) {
        isResolving = true
        val capturedFormats = mutableListOf<VideoFormat>()

        binding.headlessWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        binding.headlessWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: ""
                if (urlStr.contains("googlevideo.com/videoplayback") && isResolving) {
                    val cleanUrl = cleanVideoPlaybackUrl(urlStr)
                    val uri = Uri.parse(urlStr)
                    val itag = uri.getQueryParameter("itag") ?: "18"
                    val mime = uri.getQueryParameter("mime") ?: ""

                    val isAudio = mime.startsWith("audio") || itag == "140"
                    val qualityLabel = when (itag) {
                        "22" -> "720p HD"
                        "18" -> "360p SD"
                        "137" -> "1080p Full HD"
                        "136" -> "720p HD"
                        "140" -> "Аудио (M4A / MP3)"
                        else -> if (isAudio) "Аудио (MP3)" else "Видео (MP4)"
                    }
                    val ext = if (isAudio) "m4a" else "mp4"

                    val format = VideoFormat(
                        id = "stream_$itag",
                        qualityLabel = qualityLabel,
                        extension = ext,
                        fileSizeBytes = 0L,
                        fileSizeFormatted = "",
                        downloadUrl = cleanUrl,
                        isAudioOnly = isAudio
                    )

                    synchronized(capturedFormats) {
                        if (capturedFormats.none { it.qualityLabel == qualityLabel }) {
                            capturedFormats.add(format)
                        }
                    }

                    if (isResolving && capturedFormats.isNotEmpty()) {
                        isResolving = false
                        resolveTimeoutJob?.cancel()

                        runOnUiThread {
                            binding.headlessWebView.loadUrl("about:blank")
                            binding.progressIndicator.visibility = View.GONE
                            binding.btnFetch.isEnabled = true

                            val info = YouTubeVideoInfo(
                                videoId = videoId,
                                title = title,
                                author = author,
                                thumbnailUrl = thumbnail,
                                durationSeconds = 240L,
                                formats = capturedFormats.toList()
                            )
                            currentVideoInfo = info
                            displayVideoPreview(info)
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        resolveTimeoutJob = lifecycleScope.launch {
            delay(15000)
            if (isResolving) {
                isResolving = false
                binding.headlessWebView.loadUrl("about:blank")
                binding.progressIndicator.visibility = View.GONE
                binding.btnFetch.isEnabled = true
                binding.tvStatus.text = "Не удалось расшифровать поток. Убедитесь, что включен VPN."
                Toast.makeText(this@MainActivity, "Таймаут расшифровки потока. Проверьте VPN.", Toast.LENGTH_LONG).show()
            }
        }

        val embedHtml = """
            <!DOCTYPE html>
            <html>
            <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#000;">
              <iframe width="100%" height="100%"
                src="https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1&controls=0"
                frameborder="0" allow="autoplay; encrypted-media"></iframe>
            </body>
            </html>
        """.trimIndent()

        binding.headlessWebView.loadDataWithBaseURL(
            "https://www.youtube.com",
            embedHtml,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun cleanVideoPlaybackUrl(rawUrl: String): String {
        return try {
            val uri = Uri.parse(rawUrl)
            val builder = uri.buildUpon().clearQuery()
            for (param in uri.queryParameterNames) {
                if (param != "range" && param != "rn" && param != "rbuf") {
                    builder.appendQueryParameter(param, uri.getQueryParameter(param))
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            rawUrl
        }
    }

    private fun displayVideoPreview(info: YouTubeVideoInfo) {
        binding.cardPreview.visibility = View.VISIBLE
        binding.tvAuthor.text = info.author
        binding.tvVideoTitle.text = info.title
        binding.tvStatus.text = "Поток расшифрован! Нажмите «Скачать»"

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
                ?: info.formats.firstOrNull { it.qualityLabel.contains("360") }
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
        binding.btnDownloadSelected.text = "Скачать ${format.qualityLabel}"
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

        binding.progressIndicator.isIndeterminate = false
        binding.progressIndicator.progress = 0
        binding.progressIndicator.visibility = View.VISIBLE
        binding.btnDownloadSelected.isEnabled = false
        binding.tvStatus.text = "Подключение к потоку..."

        lifecycleScope.launch {
            val result = DownloadUtil.downloadDirectly(
                context = this@MainActivity,
                url = format.downloadUrl,
                title = video.title,
                quality = format.qualityLabel,
                extension = format.extension
            ) { percent, downloaded, total ->
                if (percent >= 0) {
                    binding.progressIndicator.isIndeterminate = false
                    binding.progressIndicator.progress = percent
                    val mbDownloaded = downloaded / (1024.0 * 1024.0)
                    val mbTotal = total / (1024.0 * 1024.0)
                    binding.tvStatus.text = String.format(Locale.US, "Скачивание: %d%% (%.1f / %.1f МБ)", percent, mbDownloaded, mbTotal)
                } else {
                    binding.progressIndicator.isIndeterminate = true
                    val mbDownloaded = downloaded / (1024.0 * 1024.0)
                    binding.tvStatus.text = String.format(Locale.US, "Скачано: %.1f МБ...", mbDownloaded)
                }
            }

            binding.progressIndicator.visibility = View.GONE
            binding.btnDownloadSelected.isEnabled = true

            result.onSuccess { file ->
                binding.tvStatus.text = "Готово! Файл сохранён: ${file.name}"
                Toast.makeText(this@MainActivity, "Видео успешно сохранено в «Загрузки»!", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                binding.tvStatus.text = "Ошибка: ${error.message}"
                Toast.makeText(this@MainActivity, "Ошибка скачивания: ${error.message}", Toast.LENGTH_LONG).show()
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
