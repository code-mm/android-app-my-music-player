package com.codemm.music.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MusicTheme { MusicScreen(onNotificationNeeded = ::requestNotifications) }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences("permissions", MODE_PRIVATE)
        if (!preferences.getBoolean("notificationAsked", false)) {
            preferences.edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

class QrCaptureActivity : CaptureActivity()

@Composable
private fun MusicScreen(model: MusicViewModel = viewModel(), onNotificationNeeded: () -> Unit) {
    val songs by model.songs.collectAsStateWithLifecycle()
    val playback by model.playback.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val libraryError by model.libraryError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var inputUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSong by remember { mutableStateOf<Song?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { inputUrl = it }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.clearMessage(it) }
    }
    val play: (Song) -> Unit = { onNotificationNeeded(); model.play(it) }
    val togglePlay = { onNotificationNeeded(); model.togglePlay() }
    Scaffold(
        containerColor = MusicPalette.Canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MusicHeader(
                onScan = {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setCaptureActivity(QrCaptureActivity::class.java).setOrientationLocked(false)
                        .setBeepEnabled(false).setPrompt("扫描单曲或音乐合集二维码"))
                },
                onPaste = { inputUrl = "" },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 700.dp
            val player: @Composable () -> Unit = {
                PlayerPanel(playback, songs.isNotEmpty(), model, togglePlay)
            }
            if (wide) {
                Row(
                    Modifier.align(Alignment.TopCenter).widthIn(max = 1080.dp).fillMaxSize().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { player() }
                    MusicLibraryContent(
                        songs, playback, downloads, libraryError, play, { deleteSong = it },
                        model::cancelDownload, model::clearDownloadHistory, model::retryBundle, Modifier.weight(1f),
                    )
                }
            } else {
                MusicLibraryContent(
                    songs, playback, downloads, libraryError, play, { deleteSong = it },
                    model::cancelDownload, model::clearDownloadHistory, model::retryBundle,
                    Modifier.fillMaxSize().padding(horizontal = if (maxWidth < 360.dp) 16.dp else 20.dp),
                    player,
                )
            }
        }
    }
    inputUrl?.let { initial ->
        if (MusicBundle.isBundleLink(initial)) {
            BundleDownloadDialog(initial, songs, onDismiss = { inputUrl = null }) { bundle ->
                val error = model.downloadBundle(bundle)
                if (error == null) { inputUrl = null; onNotificationNeeded() }
                error
            }
        } else {
            DownloadDialog(initial, onDismiss = { inputUrl = null }) { url, title ->
                if (MusicBundle.isBundleLink(url)) { inputUrl = url.trim(); null }
                else {
                    val error = model.download(url, title)
                    if (error == null) { inputUrl = null; onNotificationNeeded() }
                    error
                }
            }
        }
    }
    deleteSong?.let { song ->
        AlertDialog(onDismissRequest = { deleteSong = null }, title = { Text("删除音乐？") },
            text = { Text("将从应用内部删除「${song.title}」。之后需要重新扫码下载。") },
            confirmButton = { TextButton(onClick = { model.delete(song); deleteSong = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteSong = null }) { Text("保留") } })
    }
}

@Composable
private fun MusicLibraryContent(
    songs: List<Song>, playback: PlaybackState, downloads: List<WorkInfo>, libraryError: String?,
    play: (Song) -> Unit, delete: (Song) -> Unit, cancel: (java.util.UUID) -> Unit, clear: () -> Unit, retry: (WorkInfo) -> Unit,
    modifier: Modifier, player: (@Composable () -> Unit)? = null,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (player != null) item(key = "player") { player() }
        libraryError?.let { error -> item(key = "library-error") {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        } }
        if (downloads.isNotEmpty()) item(key = "downloads") {
            Spacer(Modifier.height(16.dp))
            DownloadPanel(downloads, cancel, clear, retry)
        }
        item(key = "library-heading") {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("我的音乐", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${songs.size} 首", style = MaterialTheme.typography.bodyMedium, color = MusicPalette.Muted)
            }
        }
        if (songs.isEmpty()) item(key = "empty-library") { EmptyMusicLibrary() }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, selected = playback.id == song.id, playing = playback.playing,
                first = index == 0, last = index == songs.lastIndex, enabled = playback.ready,
                onPlay = { play(song) }, onDelete = { delete(song) })
        }
        item(key = "privacy-footer") {
            Text("仅存应用内部", style = MaterialTheme.typography.bodySmall, color = MusicPalette.Muted,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun DownloadDialog(initial: String, onDismiss: () -> Unit, onDownload: (String, String) -> String?) {
    var url by rememberSaveable(initial) { mutableStateOf(initial) }
    var title by rememberSaveable(initial) { mutableStateOf("") }
    var error by rememberSaveable(initial) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("下载音乐") },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("支持音频直链或音乐合集链接，不支持音乐平台分享页。合集会先显示清单，确认后批量下载。")
                OutlinedTextField(value = url, onValueChange = { url = it; error = null }, label = { Text("http / https 下载地址") },
                    maxLines = 4, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = title, onValueChange = { title = it.take(160) }, label = { Text("音乐名称（可选）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { error = onDownload(url, title) }) { Text("下载") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun DownloadPanel(downloads: List<WorkInfo>, cancel: (java.util.UUID) -> Unit, clear: () -> Unit, retry: (WorkInfo) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("下载记录", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = clear) { Text("清除已完成记录") }
            }
            downloads.forEach { info ->
                val title = info.outputData.getString(MusicDownloadWorker.TITLE)
                    ?: info.progress.getString(MusicDownloadWorker.TITLE) ?: "音乐文件"
                val state = when (info.state) {
                    WorkInfo.State.RUNNING -> "正在下载"
                    WorkInfo.State.SUCCEEDED -> info.outputData.getString(MusicDownloadWorker.MESSAGE) ?: "下载完成"
                    WorkInfo.State.FAILED -> info.outputData.getString(MusicDownloadWorker.MESSAGE) ?: "下载失败，请重新扫码"
                    WorkInfo.State.ENQUEUED -> if (info.runAttemptCount > 0) "网络连接中断，稍后自动重试" else "等待网络或开始下载"
                    else -> "等待下载"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(state, style = MaterialTheme.typography.bodySmall,
                            color = if (info.state == WorkInfo.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (info.state == WorkInfo.State.RUNNING) {
                            info.progress.getString(BatchDownloadWorker.CURRENT)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            val bytes = info.progress.getLong(MusicDownloadWorker.BYTES, 0)
                            val total = info.progress.getLong(MusicDownloadWorker.TOTAL, -1)
                            if (total > 0) LinearProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    if (!info.state.isFinished) TextButton(onClick = { cancel(info.id) }) { Text("取消") }
                    if (info.state == WorkInfo.State.FAILED && BatchDownloadWorker.TAG in info.tags) {
                        TextButton(onClick = { retry(info) }) { Text("重试") }
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
}
