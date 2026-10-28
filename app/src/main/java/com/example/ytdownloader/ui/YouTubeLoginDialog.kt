package com.example.ytdownloader.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.ytdownloader.R
import com.example.ytdownloader.data.AuthStore

/**
 * Полноэкранный вход в Google: WebView + вытаскивание cookies в Netscape-файл.
 *
 * Раньше 150 строк построения UI и разбора кук жили прямо в MainActivity вместе с
 * логикой скачивания. Здесь всё локально: наружу отдаётся только событие «вошли».
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeLoginDialog(
    private val context: Context,
    private val authStore: AuthStore,
    private val onLoggedIn: () -> Unit
) {

    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private var handled = false

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val webView = createWebView()
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        root.addView(createTopBar { trySave(cookieManager, silent = false) })
        root.addView(webView)

        dialog.setContentView(root)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                view?.loadUrl(url)
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                trySave(cookieManager, silent = true)
            }
        }

        // WebView держит ссылку на Activity через контекст — освобождаем при закрытии
        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
        }

        dialog.show()
        webView.loadUrl(LOGIN_URL)
    }

    private fun trySave(cookieManager: CookieManager, silent: Boolean): Boolean {
        val rawCookies = AuthStore.COOKIE_DOMAINS.map { cookieManager.getCookie(it) ?: "" }

        if (authStore.hasAuthMarkers(rawCookies)) {
            if (!handled) {
                handled = true
                cookieManager.flush()
                if (authStore.saveFromCookieStrings(rawCookies)) {
                    dialog.dismiss()
                    Toast.makeText(context, R.string.auth_saved, Toast.LENGTH_SHORT).show()
                    onLoggedIn()
                }
            }
            return true
        }

        if (!silent) {
            Toast.makeText(context, R.string.auth_cookies_missing, Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun createTopBar(onDone: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(BAR_BACKGROUND)

            addView(TextView(context).apply {
                setText(R.string.login_dialog_title)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(Button(context).apply {
                setText(R.string.login_btn_done)
                textSize = 13f
                setBackgroundColor(BRAND_RED)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 110)
                setOnClickListener { onDone() }
            })

            addView(Button(context).apply {
                setText(R.string.close)
                textSize = 13f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 110)
                setOnClickListener { dialog.dismiss() }
            })
        }

    private fun createWebView(): WebView = WebView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(BACKGROUND)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.userAgentString = DESKTOP_LIKE_UA
    }

    companion object {
        private const val BACKGROUND = 0xFF121212.toInt()
        private const val BAR_BACKGROUND = 0xFF1F1F1F.toInt()
        private const val BRAND_RED = 0xFFE62117.toInt()

        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"

        /**
         * Мобильный UA: на десктопном Google показывает капчу и двухфакторку чаще.
         */
        private const val DESKTOP_LIKE_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
