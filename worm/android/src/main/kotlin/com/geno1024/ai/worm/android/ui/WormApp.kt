package com.geno1024.ai.worm.android.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geno1024.ai.worm.android.LoginActivity
import com.geno1024.ai.worm.android.browser.BrowserScreen
import com.geno1024.ai.worm.android.site.Source
import com.geno1024.ai.worm.android.ui.settings.SettingsScreen
import com.geno1024.ai.worm.android.ui.theme.ThemePrefs
import com.geno1024.ai.worm.android.ui.theme.ThemeSettings
import com.geno1024.ai.worm.android.ui.theme.WormTheme
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WormApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: WormViewModel = viewModel()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    val themePrefs = remember { ThemePrefs(context.applicationContext) }
    var themeSettings by remember { mutableStateOf(themePrefs.snapshot()) }

    fun changeTheme(settings: ThemeSettings) {
        themePrefs.save(settings)
        themeSettings = settings
    }

    WormTheme(
        darkTheme = themeSettings.mode.resolved(isSystemInDarkTheme()),
        preset = themeSettings.preset,
    ) {
        val loginLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) viewModel.refreshCookies()
        }
        val storageLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri -> if (uri != null) viewModel.useStorage(context, uri) }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        "抓取源",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    for (source in Source.entries) {
                        NavigationDrawerItem(
                            label = { Text(source.label) },
                            icon = { Icon(sourceIcon(source), contentDescription = null) },
                            selected = ui.source == source,
                            onClick = {
                                viewModel.selectSource(source)
                                showBrowser = false
                                showSettings = false
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("浏览") },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        selected = showBrowser,
                        onClick = {
                            showSettings = false
                            showBrowser = true
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("设置") },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        selected = showSettings,
                        onClick = {
                            showBrowser = false
                            showSettings = true
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text(
                        "从屏幕左侧滑出可随时切换抓取源",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            },
        ) {
            if (showSettings) {
                SettingsScreen(
                    onBack = { showSettings = false },
                    settings = themeSettings,
                    onSettingsChange = ::changeTheme,
                )
            } else if (showBrowser) {
                BrowserScreen(
                    treeUri = ui.storageUri,
                    onBack = { showBrowser = false },
                )
            } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Worm 媒体采集") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "菜单")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("当前抓取源：${ui.source.label}", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = ui.subject,
                        onValueChange = viewModel::updateSubject,
                        label = { Text(ui.source.subjectHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("登录态：${if (ui.loginReady) "已就绪" else "未登录"}")
                            Button(
                                onClick = {
                                    loginLauncher.launch(
                                        Intent(context, LoginActivity::class.java).apply {
                                            putExtra(LoginActivity.EXTRA_SITE, ui.source.site.name)
                                        },
                                    )
                                },
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null)
                                Text("登录 ${ui.source.site.label}")
                            }
                            Text("保存到：${ui.storageLabel}", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { storageLauncher.launch(null) }) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                                Text("选择保存目录")
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.start() },
                        enabled = !ui.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (!ui.running) Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(if (ui.running) "抓取中…" else "开始抓取")
                    }

                    ui.progress?.let { progress ->
                        val total = progress.total?.takeIf { it > 0 }
                        Text("${progress.stage} ${progress.current}${total?.let { "/$it" } ?: ""} ${progress.detail}")
                        if (total != null) {
                            LinearProgressIndicator(
                                progress = { (progress.current.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    ui.resume?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ui.result?.let { Text(it) }
                    ui.error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
                    if (ui.warnings.isNotEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("警告", style = MaterialTheme.typography.titleSmall)
                                for (warning in ui.warnings) {
                                    Text("· $warning", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private fun sourceIcon(source: Source): ImageVector = when (source) {
    Source.QZONE -> Icons.Default.PhotoCamera
    Source.QZONE_ALBUM -> Icons.Default.Folder
    Source.ZHIHU -> Icons.Default.Language
}
