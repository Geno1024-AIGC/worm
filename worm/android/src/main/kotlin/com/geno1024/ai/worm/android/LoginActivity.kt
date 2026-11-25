package com.geno1024.ai.worm.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
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
import androidx.compose.runtime.MutableState
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = runCatching { Site.valueOf(intent.getStringExtra(EXTRA_SITE).orEmpty()) }
            .getOrDefault(Site.QQ)
        setContent {
            WormTheme {
                LoginScreen(
                    site = site,
                    onBack = { finish() },
                    onWebView = { webView = it },
                )
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE = "site"
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun LoginScreen(
    site: Site,
    onBack: () -> Unit,
    onWebView: (WebView) -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("正在加载登录页…") }
    val captured = remember { mutableStateOf(false) }

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
                captured = captured,
                onProgress = { progress = it },
                onCaptured = { status = it },
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
    captured: MutableState<Boolean>,
    onProgress: (Int) -> Unit,
    onCaptured: (String) -> Unit,
    onReady: (WebView) -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString + " worm-android"
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
                        if (captured.value) return
                        val cookies = SiteCookies.read(site)
                        if (!SiteCookies.hasLogin(site, cookies)) return
                        captured.value = true
                        onCaptured("已获取登录态，正在返回…")
                        SiteCookies.flush()
                        postDelayed({
                            val activity = context as? Activity
                            activity?.setResult(Activity.RESULT_OK)
                            activity?.finish()
                        }, 600)
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
