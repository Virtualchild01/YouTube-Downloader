package com.example.ytdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.ytdownloader.databinding.ActivityMainBinding
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var currentVideoInfo: VideoInfo? = null
    private var selectedQuality: String = "720p HD"
    private var currentUrl: String = ""

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startDownloadProcess()
        } else {
            Toast.makeText(this, "Разрешение на запись необходимо для сохранения файлов", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val PREFS_NAME = "ytdownloader_prefs"
        private const val PREF_THEME = "theme_mode"
        private const val PREF_IS_AUTHORIZED = "is_authorized"
        private const val COOKIES_FILE_NAME = "youtube_cookies.txt"
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001

        private val QUALITY_OPTIONS = arrayOf(
            "1080p Full HD",
            "720p HD",
            "480p SD",
            "360p",
            "Аудио (MP3)"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyTheme(prefs.getInt(PREF_THEME, 0))

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createNotificationChannel()

        initYoutubeDL()
        setupUI()
        handleIntent(intent)
        updateAuthGateUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Загрузки"
            val descriptionText = "Уведомления о процессе скачивания видео"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateDownloadNotification(title: String, text: String, progress: Int) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, false)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }

    private fun completeDownloadNotification(title: String, text: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOngoing(false)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }

    private fun initYoutubeDL() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
            } catch (e: Exception) {
                Log.e("YTDownloader", "YoutubeDL init error", e)
            }
        }
    }

    private fun setupUI() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, QUALITY_OPTIONS)
        binding.actvQuality.setAdapter(adapter)
        binding.actvQuality.setText(QUALITY_OPTIONS[1], false)
        selectedQuality = QUALITY_OPTIONS[1]

        binding.actvQuality.setOnItemClickListener { _, _, position, _ ->
            selectedQuality = QUALITY_OPTIONS[position]
        }

        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val pasteText = clipData.getItemAt(0).text?.toString() ?: ""
                binding.etUrl.setText(pasteText)
                if (pasteText.isNotBlank()) {
                    loadVideoDetails(pasteText)
                }
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFetch.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                loadVideoDetails(url)
            } else {
                binding.tilUrl.error = "Введите ссылку на YouTube"
            }
        }

        binding.btnDownloadSelected.setOnClickListener {
            checkPermissionsAndDownload()
        }

        binding.btnAuth.setOnClickListener {
            openYouTubeAuthModal()
        }

        binding.btnSupport.setOnClickListener {
            openSupportLink()
        }

        binding.btnDownloadsFolder.setOnClickListener {
            openDownloadsFolder()
        }

        setupThemeSelector()
    }

    private fun updateAuthGateUI() {
        val isAuthorized = prefs.getBoolean(PREF_IS_AUTHORIZED, false)
        val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
        val hasCookies = cookiesFile.exists() && cookiesFile.length() > 0
        val authorized = isAuthorized || hasCookies

        if (authorized) {
            binding.tilUrl.isEnabled = true
            binding.etUrl.isEnabled = true
            binding.btnPaste.isEnabled = true
            binding.btnFetch.isEnabled = true
            binding.btnDownloadSelected.isEnabled = currentVideoInfo != null
            binding.btnAuth.text = "Аккаунт"
        } else {
            binding.tilUrl.isEnabled = false
            binding.etUrl.isEnabled = false
            binding.btnPaste.isEnabled = false
            binding.btnFetch.isEnabled = false
            binding.btnDownloadSelected.isEnabled = false
            binding.btnAuth.text = "Войти"
            binding.tvStatus.text = "Для работы приложения необходимо войти в аккаунт YouTube"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openYouTubeAuthModal() {
        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null
        var isAuthHandled = false

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#212121"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "Вход в YouTube / Google"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = Button(this).apply {
            text = "Закрыть"
            setOnClickListener {
                dialog?.dismiss()
            }
        }

        topBar.addView(titleText)
        topBar.addView(closeBtn)

        rootLayout.addView(topBar)
        rootLayout.addView(webView)

        dialog = androidx.appcompat.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            .setView(rootLayout)
            .setCancelable(true)
            .setOnDismissListener {
                webView.destroy()
                updateAuthGateUI()
            }
            .create()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("intent://") || url.startsWith("vnd.youtube:")) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url ?: ""
                val cookieStr = CookieManager.getInstance().getCookie(currentUrl) ?: ""

                if (cookieStr.contains("LOGIN_INFO") || cookieStr.contains("SAPISID") || cookieStr.contains("SSID") || cookieStr.contains("APISID")) {
                    saveCookiesToFile(cookieStr)
                    prefs.edit().putBoolean(PREF_IS_AUTHORIZED, true).apply()

                    if (!isAuthHandled) {
                        isAuthHandled = true
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Авторизация успешна!", Toast.LENGTH_SHORT).show()
                            updateAuthGateUI()
                            dialog?.dismiss()
                        }
                    }
                }
            }
        }

        webView.webChromeClient = WebChromeClient()

        dialog.show()
        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue")
    }

    private fun saveCookiesToFile(cookieHeader: String) {
        try {
            val file = File(filesDir, COOKIES_FILE_NAME)
            FileWriter(file, false).use { writer ->
                writer.write("# Netscape HTTP Cookie File\n")
                writer.write("# This file was generated by YTDownloader\n\n")

                val pairs = cookieHeader.split(";").map { it.trim() }
                for (pair in pairs) {
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx > 0) {
                        val key = pair.substring(0, eqIdx).trim()
                        val value = pair.substring(eqIdx + 1).trim()
                        writer.write(".youtube.com\tTRUE\t/\tTRUE\t2147483647\t$key\t$value\n")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("YTDownloader", "Failed to save cookies", e)
        }
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action && "text/plain" == intent.type) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val extracted = extractUrl(sharedText)
                binding.etUrl.setText(extracted)
                loadVideoDetails(extracted)
            }
        }
    }

    private fun extractUrl(text: String): String {
        val parts = text.split("\\s+".toRegex())
        for (part in parts) {
            if (part.startsWith("http://") || part.startsWith("https://")) {
                return part
            }
        }
        return text.trim()
    }

    private fun setupThemeSelector() {
        val currentTheme = prefs.getInt(PREF_THEME, 0)
        when (currentTheme) {
            1 -> binding.themeToggleGroup.check(binding.btnThemeDark.id)
            2 -> binding.themeToggleGroup.check(binding.btnThemeLight.id)
            else -> binding.themeToggleGroup.check(binding.btnThemeAuto.id)
        }

        binding.themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    binding.btnThemeDark.id -> 1
                    binding.btnThemeLight.id -> 2
                    else -> 0
                }
                prefs.edit().putInt(PREF_THEME, mode).apply()
                applyTheme(mode)
            }
        }
    }

    private fun applyTheme(mode: Int) {
        when (mode) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
                }
            }
        }
    }

    private fun loadVideoDetails(url: String) {
        val cleanUrl = extractUrl(url)
        currentUrl = cleanUrl
        binding.tilUrl.error = null
        binding.progressIndicator.visibility = View.VISIBLE
        binding.progressIndicator.isIndeterminate = true
        binding.tvStatus.text = "Получение информации о видео..."
        binding.cardPreview.visibility = View.GONE
        binding.btnDownloadSelected.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(cleanUrl)
                request.addOption("--no-playlist")
                request.addOption("--no-check-certificate")
                request.addOption("-4")
                request.addOption("--extractor-args", "youtube:player_client=default,ios")

                val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
                if (cookiesFile.exists() && cookiesFile.length() > 0) {
                    request.addOption("--cookies", cookiesFile.absolutePath)
                }

                val info = YoutubeDL.getInstance().getInfo(request)
                currentVideoInfo = info

                withContext(Dispatchers.Main) {
                    displayVideoInfo(info)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressIndicator.visibility = View.GONE
                    val msg = e.message ?: "Ошибка получения видео"
                    val cleanMsg = if (msg.contains("403") || msg.contains("SABR")) {
                        "Ошибка 403: Требуется обновить вход в аккаунт YouTube"
                    } else {
                        msg.lines().firstOrNull { it.isNotBlank() } ?: msg
                    }
                    binding.tvStatus.text = cleanMsg
                    Toast.makeText(this@MainActivity, cleanMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayVideoInfo(info: VideoInfo) {
        binding.progressIndicator.visibility = View.GONE
        binding.cardPreview.visibility = View.VISIBLE
        binding.btnDownloadSelected.isEnabled = true
        binding.tvStatus.text = "Видео готово к загрузке"

        binding.tvVideoTitle.text = info.title ?: "Без названия"
        binding.tvChannelName.text = info.uploader ?: "YouTube"

        val thumb = info.thumbnail
        if (!thumb.isNullOrEmpty()) {
            Glide.with(this)
                .load(thumb)
                .centerCrop()
                .into(binding.ivCover)
        }

        val durationSec = (info.duration ?: 0).toLong()
        val optionsWithSizes = QUALITY_OPTIONS.map { quality ->
            val estSize = estimateSizeFormatted(quality, durationSec)
            if (estSize.isNotEmpty()) "$quality ($estSize)" else quality
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            optionsWithSizes
        )
        binding.actvQuality.setAdapter(adapter)

        val defaultSelection = optionsWithSizes.firstOrNull { it.contains("720") } ?: optionsWithSizes[0]
        selectedQuality = "720p HD"
        binding.actvQuality.setText(defaultSelection, false)

        binding.actvQuality.setOnItemClickListener { _, _, position, _ ->
            selectedQuality = QUALITY_OPTIONS[position]
        }
    }

    private fun estimateSizeFormatted(quality: String, durationSec: Long): String {
        if (durationSec <= 0) return ""

        val bitrateBps = when (quality) {
            "1080p Full HD" -> 4_500_000L
            "720p HD" -> 2_500_000L
            "480p SD" -> 1_200_000L
            "360p" -> 700_000L
            "Аудио (MP3)" -> 160_000L
            else -> 2_000_000L
        }

        val bytes = (durationSec * bitrateBps) / 8L
        val mb = bytes / (1024.0 * 1024.0)

        return if (mb >= 1024.0) {
            String.format("%.1f ГБ", mb / 1024.0)
        } else {
            String.format("%.1f МБ", mb)
        }
    }

    private fun checkPermissionsAndDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        startDownloadProcess()
    }

    private fun startDownloadProcess() {
        val video = currentVideoInfo ?: return
        val url = currentUrl.ifEmpty { binding.etUrl.text.toString().trim() }
        if (url.isEmpty()) return

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) downloadDir.mkdirs()

        binding.progressIndicator.isIndeterminate = false
        binding.progressIndicator.progress = 0
        binding.progressIndicator.visibility = View.VISIBLE
        binding.btnDownloadSelected.isEnabled = false
        binding.tvStatus.text = "Скачивание видео: 0%"

        val processId = "yt_dl_${System.currentTimeMillis()}"

        val request = YoutubeDLRequest(url)
        request.addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        request.addOption("--no-check-certificate")
        request.addOption("-4")
        request.addOption("--extractor-args", "youtube:player_client=default,ios")

        val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
        if (cookiesFile.exists() && cookiesFile.length() > 0) {
            request.addOption("--cookies", cookiesFile.absolutePath)
        }

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

        // Запуск через независимый скоуп с удержанием процессора (WakeLock), чтобы скачивание не прерывалось при сворачивании
        downloadScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTDownloader:DownloadWakeLock")
            wakeLock.acquire(20 * 60 * 1000L) // до 20 минут активности в фоне

            var isAudioStage = false
            var lastProgress = 0f

            try {
                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                    val rawLine = line ?: ""
                    if (rawLine.contains(".m4a", ignoreCase = true) ||
                        rawLine.contains(".mp3", ignoreCase = true) ||
                        rawLine.contains("audio", ignoreCase = true) ||
                        (lastProgress > 75f && progress < 25f)) {
                        isAudioStage = true
                    }
                    lastProgress = progress

                    val stageName = if (selectedQuality == "Аудио (MP3)") {
                        "Скачивание аудио"
                    } else if (isAudioStage) {
                        if (progress >= 99f) "Объединение видео и аудио (FFmpeg)..." else "Скачивание аудио"
                    } else {
                        "Скачивание видео"
                    }

                    val etaStr = if (etaInSeconds > 0) " (~${etaInSeconds}с)" else ""
                    val progressText = if (stageName.startsWith("Объединение")) {
                        stageName
                    } else {
                        "$stageName: ${progress.toInt()}%$etaStr"
                    }

                    runOnUiThread {
                        binding.progressIndicator.isIndeterminate = false
                        binding.progressIndicator.progress = progress.toInt()
                        binding.tvStatus.text = progressText
                    }

                    updateDownloadNotification(
                        title = (video.title ?: "Видео").take(40),
                        text = progressText,
                        progress = progress.toInt()
                    )
                }

                withContext(Dispatchers.Main) {
                    binding.progressIndicator.visibility = View.GONE
                    binding.btnDownloadSelected.isEnabled = true
                    binding.tvStatus.text = "Готово! Файл сохранён в «Загрузки»"
                    Toast.makeText(this@MainActivity, "Видео успешно сохранено в «Загрузки»!", Toast.LENGTH_LONG).show()

                    completeDownloadNotification(
                        title = "Загрузка завершена!",
                        text = "${(video.title ?: \"Видео\").take(40)} сохранено в Загрузки"
                    )

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
                    val cleanLines = err.lines().filter { it.isNotBlank() && !it.startsWith("Usage:") }
                    val cleanErr = cleanLines.find { it.startsWith("ERROR:") || it.startsWith("yt-dlp: error:") }
                        ?: cleanLines.firstOrNull() ?: err
                    val displayMsg = if (cleanErr.contains("403") || cleanErr.contains("SABR")) {
                        "YouTube отклонил запрос (403). Нажмите «Аккаунт» для обновления входа."
                    } else {
                        cleanErr
                    }
                    binding.tvStatus.text = displayMsg
                    Toast.makeText(this@MainActivity, displayMsg, Toast.LENGTH_LONG).show()

                    completeDownloadNotification(
                        title = "Ошибка загрузки",
                        text = displayMsg
                    )
                }
            } finally {
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
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

    }
    private fun openSupportLink() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pay.cloudtips.ru/p/f35243af"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
        }
    }
}

