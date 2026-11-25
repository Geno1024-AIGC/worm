package com.geno1024.ai.worm.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
                    onCookie = ::applyCookieHeader,
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

    /**
     * WebView login is unreliable on Zhihu (app deep links / blank desktop page), so the
     * user can paste a Cookie header copied from a browser where login worked. `null`
     * means the cookies were accepted and the activity will return to the caller.
     */
    private fun applyCookieHeader(header: String): String? {
        val url = when (site) {
            Site.QQ -> "https://user.qzone.qq.com/"
            Site.ZHIHU -> "https://www.zhihu.com/"
        }
        val domain = when (site) {
            Site.QQ -> ".qq.com"
            Site.ZHIHU -> ".zhihu.com"
        }
        val manager = CookieManager.getInstance()
        var count = 0
        for (part in header.split(';')) {
            val entry = part.trim()
            val separator = entry.indexOf('=')
            if (separator <= 0) continue
            val name = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            manager.setCookie(url, "$name=$value; Domain=$domain; Path=/")
            count++
        }
        manager.flush()
        if (count == 0) return "没有识别到 Cookie，请粘贴完整的 Cookie 请求头"
        if (!checkLogin()) return "未识别到登录态：${site.label} 需要有效的登录 Cookie"
        return null
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
    onCookie: (String) -> String?,
) {
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("正在加载登录页，完成后会自动返回…") }
    var showCookie by remember { mutableStateOf(false) }
    var cookieText by remember { mutableStateOf("") }
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
                actions = {
                    TextButton(onClick = { showCookie = true }) { Text("手动输入") }
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
                Column {
                    Text(status, modifier = Modifier.padding(8.dp))
                    Text(
                        "网页登录不可用时，可在浏览器登录后点右上角「手动输入」粘贴 Cookie。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    if (showCookie) {
        AlertDialog(
            onDismissRequest = { showCookie = false },
            title = { Text("粘贴 ${site.label} Cookie") },
            text = {
                Column {
                    Text(
                        "在浏览器里登录 ${site.label}，复制完整的 Cookie 请求头后粘贴到这里。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        label = { Text("Cookie") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val error = onCookie(cookieText)
                    if (error == null) showCookie = false else status = error
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showCookie = false }) { Text("取消") }
            },
        )
    }
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
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
                if (site == Site.ZHIHU) {
                    // Zhihu sees the "; wv" / "Version/4.0" markers in the default
                    // WebView UA and swaps the login form for an "open in app" page.
                    // Keep the device's real model and Chrome version, drop only the
                    // WebView markers so this looks like an ordinary mobile Chrome.
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgress(newProgress)
                    }
                }
                webViewClient = object : WebViewClient() {
                    // Swallow only Zhihu's own app deep links. Blocking every non-web
                    // scheme broke the sign-in page's scripts, so leave intent/data/blob
                    // alone — the cleaned UA already keeps the login form visible.
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = request?.url?.scheme?.lowercase()?.startsWith("zhihu") == true

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
                        Site.ZHIHU -> "https://www.zhihu.com/signin?next=%2F"
                    },
                )
                onReady(this)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
