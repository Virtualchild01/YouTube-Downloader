package com.example.ytdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
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
import java.util.Locale
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
        private const val COOKIES_FILE_NAME = "youtube_cookies.txt"

        private const val THEME_AUTO = 0
        private const val THEME_DARK = 1
        private const val THEME_LIGHT = 2

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
        updateAccountButtonState()
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

                // Попытка фонового обновления yt-dlp до последней версии с GitHub
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                } catch (_: Exception) {}
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
        // Кнопка авторизации в YouTube / Google
        binding.btnAccount.setOnClickListener {
            val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
            if (cookiesFile.exists() && cookiesFile.length() > 0) {
                showAccountOptionsDialog()
            } else {
                showYouTubeLoginDialog()
            }
        }

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

        // Скачивание выбранного качества
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

    private fun updateAccountButtonState() {
        val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
        if (cookiesFile.exists() && cookiesFile.length() > 0) {
            binding.btnAccount.text = "Аккаунт ✓"
            binding.btnAccount.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant))
        } else {
            binding.btnAccount.text = "Войти"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showYouTubeLoginDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 20, 32, 20)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
        }

        val titleTv = TextView(this).apply {
            text = "Вход в YouTube / Google"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { dialog.dismiss() }
        }

        topBar.addView(titleTv)
        topBar.addView(closeBtn)

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = cookieManager.getCookie("https://www.youtube.com") ?: ""
                if (cookies.contains("LOGIN_INFO") || cookies.contains("SID") || cookies.contains("SAPISID")) {
                    saveYouTubeCookies(cookies)
                    dialog.dismiss()
                    updateAccountButtonState()
                    Toast.makeText(this@MainActivity, "Авторизация в YouTube сохранена! Ограничения сняты.", Toast.LENGTH_LONG).show()
                }
            }
        }

        rootLayout.addView(topBar)
        rootLayout.addView(webView)
        dialog.setContentView(rootLayout)

        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com/")
        dialog.show()
    }

    private fun saveYouTubeCookies(rawCookies: String) {
        try {
            val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
            val sb = StringBuilder()
            sb.append("# Netscape HTTP Cookie File\n")
            sb.append("# Generated by YTDownloader\n\n")

            for (item in rawCookies.split(";")) {
                val part = item.trim()
                val eqIdx = part.indexOf('=')
                if (eqIdx > 0) {
                    val name = part.substring(0, eqIdx).trim()
                    val value = part.substring(eqIdx + 1).trim()
                    sb.append(".youtube.com\tTRUE\t/\tTRUE\t2147483647\t").append(name).append("\t").append(value).append("\n")
                    sb.append(".google.com\tTRUE\t/\tTRUE\t2147483647\t").append(name).append("\t").append(value).append("\n")
                }
            }
            cookiesFile.writeText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cookies", e)
        }
    }

    private fun showAccountOptionsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("YouTube Аккаунт")
            .setMessage("Вы успешно авторизованы в YouTube. Куки активны: ошибки 403 и SABR устранены, доступно скачивание без ограничений.")
            .setIcon(R.drawable.ic_heart)
            .setPositiveButton("ОК", null)
            .setNeutralButton("Выйти") { _, _ ->
                val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
                if (cookiesFile.exists()) cookiesFile.delete()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                updateAccountButtonState()
                Toast.makeText(this, "Вы вышли из аккаунта YouTube", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Перезайти") { _, _ ->
                showYouTubeLoginDialog()
            }
            .show()
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
                request.addOption("--extractor-args", "youtube:player_client=ios,android")

                val cookiesFile = File(filesDir, COOKIES_FILE_NAME)
                if (cookiesFile.exists() && cookiesFile.length() > 0) {
                    request.addOption("--cookies", cookiesFile.absolutePath)
                }

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
                    } else if (err.contains("403", ignoreCase = true) || err.contains("SABR", ignoreCase = true)) {
                        "YouTube заблокировал анонимный запрос (403). Нажмите «Войти» вверху для авторизации."
                    } else {
                        "Ошибка: $err"
                    }
                    binding.tvStatus.text = userMsg
                    Toast.makeText(this@MainActivity, userMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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

        val durationSec = info.duration.toLong()
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
        binding.btnDownloadSelected.text = "Скачать 720p HD"

        binding.actvQuality.setOnItemClickListener { _, _, position, _ ->
            selectedQuality = QUALITY_OPTIONS[position]
            binding.btnDownloadSelected.text = "Скачать $selectedQuality"
        }
    }

    private fun estimateSizeFormatted(quality: String, durationSec: Long): String {
        if (durationSec <= 0) return ""
        val bitrateBps = when {
            quality.contains("1080") -> 3_500_000L
            quality.contains("720") -> 2_000_000L
            quality.contains("480") -> 1_000_000L
            quality.contains("360") -> 600_000L
            quality.contains("MP3") -> 160_000L
            else -> 1_500_000L
        }
        val bytes = (durationSec * bitrateBps) / 8L
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "≈ %.2f ГБ", mb / 1024.0)
        } else {
            String.format(Locale.US, "≈ %.1f МБ", mb)
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
        request.addOption("--extractor-args", "youtube:player_client=ios,android")

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
                    val userMsg = if (err.contains("403", ignoreCase = true) || err.contains("SABR", ignoreCase = true)) {
                        "YouTube отклонил скачивание (403). Нажмите «Войти» вверху для авторизации через аккаунт."
                    } else {
                        "Ошибка: $err"
                    }
                    binding.tvStatus.text = userMsg
                    Toast.makeText(this@MainActivity, userMsg, Toast.LENGTH_LONG).show()
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
