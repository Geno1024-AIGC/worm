package com.geno1024.ai.worm.android.ui.update

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geno1024.ai.worm.android.update.UpdateInstaller
import com.geno1024.ai.worm.android.update.Updater
import java.io.File

/**
 * The update part of the settings screen: a newer canary can be announced, fetched,
 * and handed to the system installer. Three endings stay visibly apart: up to date,
 * a version found, and a failed check. The selected mirror is an explicit choice,
 * because reachability depends on the network the device is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSection(
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Coming back to settings re-checks only if the last answer may have gone stale.
    LaunchedEffect(Unit) { viewModel.recheckIfStale() }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.noteInstallOutcome("安装程序已接受更新，正在安装。")
        } else {
            viewModel.noteInstallOutcome("安装已取消或未完成。")
        }
    }

    fun install(apk: File) {
        if (!UpdateInstaller.canInstall(context)) {
            viewModel.noteInstallOutcome("请先允许本应用安装未知来源应用。")
            return
        }
        val intent = UpdateInstaller.installIntent(context, apk)
        if (intent == null) {
            viewModel.noteInstallOutcome("设备上没有能打开 APK 的应用。")
            return
        }
        runCatching { installLauncher.launch(intent) }
            .onFailure { failure ->
                viewModel.noteInstallOutcome("无法启动安装程序：${failure.message ?: "未知原因"}")
            }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已安装版本：${state.installedVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                if (state.checking) {
                    // Keep the text on its own line: a fillMaxWidth bar in a Row
                    // squeezes the label into a narrow right-hand column.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("正在检查版本…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                val release = state.available
                if (release != null) {
                    Text(release.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        release.version?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        state.downloading -> {
                            val total = state.totalBytes.takeIf { it > 0 } ?: 1L
                            Text(
                                "下载中 ${percent(state.downloadedBytes, state.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LinearProgressIndicator(
                                progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        state.downloaded != null -> Button(
                            onClick = { install(state.downloaded!!) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("安装更新")
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::download,
                                enabled = !state.checking,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("下载更新")
                            }
                            TextButton(onClick = viewModel::dismiss) { Text("暂不提醒") }
                        }
                    }
                }

                if (!UpdateInstaller.canInstall(context)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "安装需要允许本应用安装未知来源应用。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { UpdateInstaller.openInstallPermission(context) }) {
                            Text("去设置")
                        }
                    }
                }

                SourceDropdown(
                    source = state.source,
                    customPrefix = state.customPrefix,
                    onSelect = viewModel::setSource,
                    onCustomPrefix = viewModel::setCustomPrefix,
                )

                Button(
                    onClick = viewModel::check,
                    enabled = !state.checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.checking) "检查中…" else "检查更新")
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("确定") } },
            title = { Text("更新检查失败") },
            text = { Text(message) },
        )
    }
}

/** One field to open rather than a column of radios: the choice is a few mirrors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceDropdown(
    source: Updater.Source,
    customPrefix: String,
    onSelect: (Updater.Source) -> Unit,
    onCustomPrefix: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = Updater.sources(customPrefix)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = source.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("下载源") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .exposedDropdownSize(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (source.id == "custom") {
            OutlinedTextField(
                value = customPrefix,
                onValueChange = onCustomPrefix,
                label = { Text("自定义镜像前缀") },
                placeholder = { Text("https://your-proxy/https://github.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A whole percent, or a dash while the size is still unknown. */
private fun percent(done: Long, total: Long): String =
    if (total <= 0) "—" else "${done * 100 / total}%"
