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
import com.example.ytdownloader.data.AuthStore
import com.example.ytdownloader.data.DownloadController
import com.example.ytdownloader.data.DownloadStorage
import com.example.ytdownloader.data.QualityPreset
import com.example.ytdownloader.data.QualityPresets
import com.example.ytdownloader.data.YtDlpEngine
import com.example.ytdownloader.databinding.ActivityMainBinding
import com.example.ytdownloader.notification.DownloadNotifier
import com.example.ytdownloader.ui.YouTubeLoginDialog
import com.example.ytdownloader.util.Formatters
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Один экран приложения: вставить ссылку, посмотреть превью, выбрать качество, скачать.
 *
 * Логика скачивания живёт в [DownloadController], авторизация — в [AuthStore],
 * запросы к yt-dlp — в [YtDlpEngine]. Здесь только UI и связывание.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var auth: AuthStore
    private lateinit var notifier: DownloadNotifier
    private lateinit var downloads: DownloadController

    private var currentVideoInfo: VideoInfo? = null
    private var currentUrl: String? = null
    private var selectedQuality: QualityPreset = QualityPresets.DEFAULT
    private var availableQualities: List<QualityPreset> = QualityPresets.ALL

    companion object {
        private const val TAG = "YTDownloader"
        private const val PREFS_NAME = "yt_downloader_prefs"
        private const val KEY_THEME = "key_theme_mode"
        private const val KEY_PENDING_URL = "key_pending_url"

        private const val THEME_AUTO = 0
        private const val THEME_DARK = 1
        private const val THEME_LIGHT = 2

        const val DONATION_URL = "https://pay.cloudtips.ru/p/f35243af"

        private val URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Отказ в уведомлениях не должен блокировать загрузку — она просто пройдёт без прогресса в шторке
        startDownload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = AuthStore(this)
        notifier = DownloadNotifier(this)
        notifier.ensureChannel()
        downloads = DownloadController.get(this)

        // Ссылка из прошлого запуска: Activity могла умереть во время логина или загрузки
        currentUrl = savedInstanceState?.getString(KEY_PENDING_URL)

        setupThemeToggle()
        setupListeners()
        updateUiAuthState()
        restoreDownloadState()

        if (!auth.isAuthorized()) {
            binding.root.postDelayed({ showYouTubeLoginDialog() }, 600)
        }

        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        downloads.observe(::onDownloadEvent)
        restoreDownloadState()
    }

    override fun onResume() {
        super.onResume()
        // Счётчик в строке «Мои загрузки»: файл мог появиться, пока экран был в фоне
        refreshGalleryCount()
    }

    private fun refreshGalleryCount() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                DownloadStorage.list(applicationContext).size
            }
            binding.tvGalleryCount.text = if (count > 0) count.toString() else ""
        }
    }

    override fun onStop() {
        downloads.stopObserving()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PENDING_URL, currentUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    // ------------------------------------------------------------ состояние

    private fun onDownloadEvent(event: DownloadController.Event) {
        when (event) {
            is DownloadController.Event.Progress -> showProgress(event.percent, event.text)
            DownloadController.Event.Completed -> {
                // Итог уже показан, повторять его при следующем onStart не нужно
                downloads.consumeOutcome()
                showCompletion()
            }
            is DownloadController.Event.Failed -> {
                downloads.consumeOutcome()
                showFailure(event.message)
            }
        }
    }

    /** Возврат в приложение во время или после загрузки: подхватываем актуальное состояние. */
    private fun restoreDownloadState() {
        if (downloads.isRunning) {
            showProgress(
                downloads.lastProgress,
                downloads.lastStatusText ?: getString(R.string.status_downloading),
                indeterminate = false
            )
            return
        }

        // Загрузка закончилась, пока Activity была в фоне — событие уже никто не слушал
        when (val outcome = downloads.consumeOutcome()) {
            null -> Unit
            DownloadController.Outcome.Completed -> showCompletion()
            is DownloadController.Outcome.Failed -> showFailure(outcome.message)
        }
    }

    private fun showProgress(percent: Int, text: String, indeterminate: Boolean = false) {
        binding.progressIndicator.visibility = View.VISIBLE
        binding.progressIndicator.isIndeterminate = indeterminate
        binding.progressIndicator.progress = percent
        binding.tvStatus.text = text
        binding.btnDownloadSelected.isEnabled = false
    }

    private fun showCompletion() {
        binding.progressIndicator.visibility = View.GONE
        binding.btnDownloadSelected.isEnabled = true
        binding.tvStatus.text = getString(R.string.status_saved)
        Toast.makeText(this, getString(R.string.status_saved), Toast.LENGTH_LONG).show()
    }

    private fun showFailure(message: String) {
        binding.progressIndicator.visibility = View.GONE
        binding.btnDownloadSelected.isEnabled = true
        binding.tvStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- тема

    private fun applySavedTheme() {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(prefs.getInt(KEY_THEME, THEME_AUTO)))
    }

    private fun nightModeFor(theme: Int): Int = when (theme) {
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun setupThemeToggle() {
        when (prefs.getInt(KEY_THEME, THEME_AUTO)) {
            THEME_DARK -> binding.toggleThemeGroup.check(R.id.btnThemeDark)
            THEME_LIGHT -> binding.toggleThemeGroup.check(R.id.btnThemeLight)
            else -> binding.toggleThemeGroup.check(R.id.btnThemeAuto)
        }

        binding.toggleThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val newTheme = when (checkedId) {
                R.id.btnThemeDark -> THEME_DARK
                R.id.btnThemeLight -> THEME_LIGHT
                else -> THEME_AUTO
            }
            if (newTheme == prefs.getInt(KEY_THEME, THEME_AUTO)) return@addOnButtonCheckedListener

            prefs.edit().putInt(KEY_THEME, newTheme).apply()
            AppCompatDelegate.setDefaultNightMode(nightModeFor(newTheme))
        }
    }

    // -------------------------------------------------------------- экран

    private fun updateUiAuthState() {
        val isAuth = auth.isAuthorized()
        binding.etUrl.isEnabled = isAuth
        binding.btnPaste.isEnabled = isAuth
        binding.btnFetch.isEnabled = isAuth

        if (isAuth) {
            binding.tilUrl.hint = getString(R.string.hint_enter_url)
            binding.btnAccount.setText(R.string.auth_account_ok)
            binding.btnAccount.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant))
        } else {
            binding.tilUrl.hint = getString(R.string.auth_login_required_hint)
            binding.btnAccount.setText(R.string.btn_login)
            binding.btnAccount.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.layoutFetchStatus.visibility = View.VISIBLE
            binding.progressFetch.visibility = View.GONE
            binding.tvFetchStatus.setText(R.string.auth_required_message)
        }
    }

    private fun setupListeners() {
        binding.btnAccount.setOnClickListener {
            if (auth.isAuthorized()) showAccountOptionsDialog() else showYouTubeLoginDialog()
        }

        binding.btnPaste.setOnClickListener {
            if (!requireAuth()) return@setOnClickListener

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()

            if (text.isNullOrBlank()) {
                Toast.makeText(this, R.string.auth_clipboard_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.etUrl.setText(text)
            fetchVideoDetails(text)
        }

        binding.btnFetch.setOnClickListener {
            if (!requireAuth()) return@setOnClickListener

            val text = binding.etUrl.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            } else {
                fetchVideoDetails(text)
            }
        }

        binding.btnDownloadSelected.setOnClickListener { checkPermissionsAndDownload() }
        binding.cardGallery.setOnClickListener { openGallery() }

        binding.btnShare.setOnClickListener {
            val url = currentUrl ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, currentVideoInfo?.title.orEmpty())
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser)))
        }

        binding.btnSupport.setOnClickListener { showSupportDialog() }
        binding.btnSupportHeader.setOnClickListener { showSupportDialog() }
    }

    /** @return true, если пользователь авторизован; иначе показывает диалог входа. */
    private fun requireAuth(): Boolean {
        if (auth.isAuthorized()) return true
        Toast.makeText(this, R.string.auth_toast_required, Toast.LENGTH_SHORT).show()
        showYouTubeLoginDialog()
        return false
    }

    // ---------------------------------------------------------- авторизация

    private fun showYouTubeLoginDialog() {
        YouTubeLoginDialog(
            context = this,
            authStore = auth,
            onLoggedIn = {
                updateUiAuthState()
                val url = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) fetchVideoDetails(url)
            }
        ).show()
    }

    private fun showAccountOptionsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_dialog_title)
            .setMessage(R.string.account_dialog_message)
            .setIcon(R.drawable.ic_heart)
            .setPositiveButton(R.string.account_btn_ok, null)
            .setNeutralButton(R.string.account_btn_logout) { _, _ ->
                auth.clear()
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                updateUiAuthState()
                Toast.makeText(this, R.string.auth_logged_out, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.account_btn_relogin) { _, _ ->
                showYouTubeLoginDialog()
            }
            .show()
    }

    // ------------------------------------------------------------ метаданные

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText.isNullOrEmpty()) return

        val cleanUrl = extractUrl(sharedText) ?: sharedText
        binding.etUrl.setText(cleanUrl)
        if (auth.isAuthorized()) fetchVideoDetails(cleanUrl)
    }

    private fun extractUrl(text: String): String? {
        val matcher = URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    private fun fetchVideoDetails(input: String) {
        val url = extractUrl(input) ?: input.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            binding.layoutFetchStatus.visibility = View.VISIBLE
            binding.progressFetch.visibility = View.GONE
            binding.tvFetchStatus.setText(R.string.error_invalid_url)
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        currentUrl = url
        binding.cardPreview.visibility = View.GONE
        binding.layoutFetchStatus.visibility = View.VISIBLE
        binding.progressFetch.visibility = View.VISIBLE
        binding.tvFetchStatus.setText(R.string.status_analyzing)
        binding.btnFetch.isEnabled = false

        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    YtDlpEngine.ensureInitialized(applicationContext)
                    YtDlpEngine.fetchInfo(YtDlpEngine.infoRequest(url, auth))
                }
                currentVideoInfo = info
                binding.layoutFetchStatus.visibility = View.GONE
                binding.btnFetch.isEnabled = true
                displayVideoPreview(info)
            } catch (e: Exception) {
                Log.e(TAG, "fetch failed", e)
                binding.progressFetch.visibility = View.GONE
                binding.btnFetch.isEnabled = true

                val message = YtDlpEngine.cleanError(
                    raw = e.message,
                    fallback = getString(R.string.error_fetch_generic),
                    authExpired = getString(R.string.error_auth_expired),
                    forbidden = getString(R.string.error_403)
                )
                binding.tvFetchStatus.text = message
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayVideoPreview(info: VideoInfo) {
        binding.cardPreview.visibility = View.VISIBLE
        binding.tvAuthor.text = info.uploader ?: "YouTube"
        binding.tvVideoTitle.text = info.title ?: getString(R.string.error_no_title)
        binding.progressIndicator.visibility = View.GONE
        binding.tvStatus.setText(R.string.status_video_ready)

        info.thumbnail?.takeIf { it.isNotEmpty() }?.let { thumb ->
            Glide.with(this).load(thumb).centerCrop().into(binding.ivCover)
        }

        availableQualities = QualityPresets.availableIn(YtDlpEngine.availableResolutions(info))
        selectedQuality = availableQualities.firstOrNull { it == QualityPresets.HD_720 }
            ?: availableQualities.firstOrNull { it == QualityPresets.FHD_1080 }
            ?: availableQualities.first()

        val labels = availableQualities.map { preset ->
            val bytes = Formatters.estimateBytes(info.duration.toLong(), preset.estimateBitrateBps)
            if (bytes > 0) {
                "${getString(preset.labelRes)} (${Formatters.estimatedSize(this, bytes)})"
            } else {
                getString(preset.labelRes)
            }
        }

        binding.actvQuality.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        binding.actvQuality.setText(labels[availableQualities.indexOf(selectedQuality)], false)
        updateDownloadButtonText()

        binding.actvQuality.setOnItemClickListener { _, _, position, _ ->
            selectedQuality = availableQualities[position]
            updateDownloadButtonText()
        }
    }

    private fun updateDownloadButtonText() {
        binding.btnDownloadSelected.text =
            getString(R.string.btn_download_quality, getString(selectedQuality.labelRes))
    }

    // ------------------------------------------------------------- загрузка

    private fun checkPermissionsAndDownload() {
        if (currentUrl == null) {
            Toast.makeText(this, R.string.error_no_url, Toast.LENGTH_SHORT).show()
            return
        }

        val needed = buildList {
            if (notifier.needsPermission()) add(Manifest.permission.POST_NOTIFICATIONS)
            // Android 9 и ниже пишут напрямую в публичную папку — без этого разрешения никак
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }

        if (needed.isEmpty()) {
            startDownload()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startDownload() {
        val url = currentUrl
        val video = currentVideoInfo
        if (url == null || video == null) return

        val started = downloads.start(url, video, selectedQuality)
        if (!started) {
            Toast.makeText(this, getString(R.string.error_download_generic), Toast.LENGTH_SHORT).show()
            return
        }
        showProgress(0, getString(R.string.status_downloading_video_started))
    }

    // ---------------------------------------------------------------- прочее

    private fun openGallery() {
        startActivity(Intent(this, com.example.ytdownloader.ui.GalleryActivity::class.java))
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
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL)))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.error_open_link, Toast.LENGTH_SHORT).show()
                }
            }
        }

        builder.show()
    }
}
