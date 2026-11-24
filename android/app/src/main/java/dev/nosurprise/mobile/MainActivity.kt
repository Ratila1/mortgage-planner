package dev.nosurprise.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Тонкая обёртка над WebView: открывает мобильную версию сайта,
 * чтобы инструментальный тест проверил её в настоящем Android-эмуляторе.
 *
 * 10.0.2.2 — адрес хост-машины из Android-эмулятора, поэтому локальный
 * docker compose (nginx на :8080) доступен без проброса портов.
 */
class MainActivity : Activity() {

    lateinit var webView: WebView
        private set

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            loadUrl(SITE_URL)
        }
        setContentView(webView)
    }

    companion object {
        const val SITE_URL = "http://10.0.2.2:8080/"
    }
}
