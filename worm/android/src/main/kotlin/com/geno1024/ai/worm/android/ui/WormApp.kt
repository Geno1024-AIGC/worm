package com.geno1024.ai.worm.android.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geno1024.ai.worm.android.LoginActivity
import com.geno1024.ai.worm.android.site.Source
import com.geno1024.ai.worm.android.ui.update.UpdateScreen
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WormApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: WormViewModel = viewModel()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showUpdates by remember { mutableStateOf(false) }

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
                        selected = ui.source == source,
                        onClick = {
                            viewModel.selectSource(source)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("检查更新") },
                    selected = showUpdates,
                    onClick = {
                        showUpdates = true
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
        if (showUpdates) {
            UpdateScreen(onBack = { showUpdates = false })
        } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Worm 媒体采集") },
                    navigationIcon = {
                        TextButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text("☰")
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
                OutlinedTextField(
                    value = ui.maxItemsText,
                    onValueChange = viewModel::updateMaxItems,
                    label = { Text("最多收集条数") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
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
                            Text("登录 ${ui.source.site.label}")
                        }
                        Text("保存到：${ui.storageLabel}", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { storageLauncher.launch(null) }) {
                            Text("选择保存目录")
                        }
                    }
                }

                Button(
                    onClick = { viewModel.start() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
