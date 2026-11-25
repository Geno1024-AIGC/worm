package com.geno1024.ai.worm.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geno1024.ai.worm.android.site.Site
import com.geno1024.ai.worm.android.site.SiteCookies
import com.geno1024.ai.worm.android.ui.theme.WormTheme

/** A tiny WebView whose only job is to produce cookies for the shared crawlers. */
class LoginActivity : ComponentActivity() {
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false
    private lateinit var site: Site

    // Zhihu sets z_c0 asynchronously (QR login, SPA redirects), often with no further
    // navigation event for the WebView callbacks. Poll as well, or login is never noticed.
    private val poll = object : Runnable {
        override fun run() {
            checkLogin()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = runCatching { Site.valueOf(intent.getStringExtra(EXTRA_SITE).orEmpty()) }
            .getOrDefault(Site.QQ)
        setContent {
            WormTheme {
                LoginScreen(
                    site = site,
                    onBack = { finish() },
                    onWebView = { webView = it },
                    onCheck = ::checkLogin,
                )
            }
        }
        handler.postDelayed(poll, POLL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        webView?.destroy()
        super.onDestroy()
    }

    /** True when login cookies were found (only the first call finishes the activity). */
    private fun checkLogin(): Boolean {
        if (captured) return true
        val cookies = SiteCookies.read(site)
        if (!SiteCookies.hasLogin(site, cookies)) return false
        captured = true
        SiteCookies.flush()
        handler.postDelayed({
            setResult(Activity.RESULT_OK)
            finish()
        }, 600)
        return true
    }

    companion object {
        const val EXTRA_SITE = "site"
        private const val POLL_MS = 1000L
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun LoginScreen(
    site: Site,
    onBack: () -> Unit,
    onWebView: (WebView) -> Unit,
    onCheck: () -> Boolean,
) {
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("正在加载登录页，完成后会自动返回…") }
    val seen = remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录 ${site.label}") },
                navigationIcon = {
                    Button(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                        Text("返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidWebView(
                site = site,
                onProgress = { progress = it },
                onCheck = onCheck,
                onCaptured = { if (!seen.value) { seen.value = true; status = it } },
                onReady = onWebView,
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Text(status, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun AndroidWebView(
    site: Site,
    onProgress: (Int) -> Unit,
    onCheck: () -> Boolean,
    onCaptured: (String) -> Unit,
    onReady: (WebView) -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Do not tag the browser as a crawler: Zhihu serves a restricted page
                // to suspicious user agents, which can hide the login controls.
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgress(newProgress)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) = check()
                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean,
                    ) = check()

                    private fun check() {
                        if (onCheck()) onCaptured("已获取登录态，正在返回…")
                    }
                }
                loadUrl(
                    when (site) {
                        Site.QQ -> "https://user.qzone.qq.com/"
                        Site.ZHIHU -> "https://www.zhihu.com/"
                    },
                )
                onReady(this)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
