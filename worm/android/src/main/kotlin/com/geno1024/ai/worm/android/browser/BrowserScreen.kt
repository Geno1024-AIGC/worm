package com.geno1024.ai.worm.android.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaKind

/** Browse what was actually saved, from either the chosen SAF tree or app-private storage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    treeUri: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val library = remember(treeUri) { MediaLibrary(context.applicationContext, treeUri) }
    var folder by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<DirectoryListing?>(null) }
    var preview by remember { mutableStateOf<LocalMedia?>(null) }

    LaunchedEffect(library, folder) {
        listing = null
        listing = library.list(folder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (folder.isEmpty()) "媒体浏览" else folder.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = parentFolder(folder)
                        if (parent == null) onBack() else folder = parent
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (folder.isNotEmpty()) {
                Text(
                    folder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val current = listing
            when {
                current == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                current.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("无法读取：${current.error}")
                }
                current.directories.isEmpty() && current.media.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("这里还没有媒体") }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(current.directories, key = { "dir:$it" }) { name ->
                        DirectoryTile(name) { folder = childFolder(folder, name) }
                    }
                    items(current.media, key = { it.id }) { media ->
                        MediaTile(media) {
                            if (media.kind == MediaKind.IMAGE) preview = media
                            else openWithSystem(context, media)
                        }
                    }
                }
            }
        }
    }

    preview?.let { media ->
        ImagePreview(media = media, onClose = { preview = null })
    }
}

@Composable
private fun DirectoryTile(name: String, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MediaTile(media: LocalMedia, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(media.id) {
        thumb = ThumbnailLoader.load(context.applicationContext, media, 192)
    }
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.aspectRatio(1f)) {
            val bitmap = thumb
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = media.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (media.kind == MediaKind.VIDEO) Icons.Default.PlayCircle else Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (media.kind == MediaKind.VIDEO) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "视频",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                )
            }
        }
        Text(
            media.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ImagePreview(media: LocalMedia, onClose: () -> Unit) {
    val context = LocalContext.current
    var full by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    LaunchedEffect(media.id) {
        full = ThumbnailLoader.load(context.applicationContext, media, 2048)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                full?.let { bitmap ->
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = media.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = scale, scaleY = scale)
                            .pointerInput(media.id) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                }
                            },
                    )
                } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
                if (full == null) {
                    Text(
                        "图片无法解码",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    )
                }
            }
        }
    }
}

/** Videos leave the app: the system player decides how to decode and control them. */
private fun openWithSystem(context: Context, media: LocalMedia) {
    val uri = media.uri
        ?: FileProvider.getUriForFile(context, "${context.packageName}.media", media.file!!)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, MediaFiles.mimeOf(media.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有能打开该视频的应用", Toast.LENGTH_SHORT).show()
    }
}
