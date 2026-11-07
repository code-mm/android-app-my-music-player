package com.codemm.music.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.work.WorkInfo
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFAAC7FF))
                else lightColorScheme(primary = Color(0xFF315DA8), secondaryContainer = Color(0xFFE6EDFA)),
                typography = Typography(),
            ) { MusicScreen(onNotificationNeeded = ::requestNotifications) }
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (playback.id != null) PlayerPanel(playback, model) { onNotificationNeeded(); model.togglePlay() }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("个人音乐", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("只听你收藏的音乐", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("♪", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setCaptureActivity(QrCaptureActivity::class.java).setOrientationLocked(false)
                            .setBeepEnabled(false).setPrompt("扫描音频文件下载二维码"))
                    }, modifier = Modifier.weight(1f)) { Text("扫码下载") }
                    OutlinedButton(onClick = { inputUrl = "" }, modifier = Modifier.weight(1f)) { Text("粘贴链接") }
                }
                Spacer(Modifier.height(10.dp))
                Text("仅存应用内部 · 不扫描手机音乐", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            libraryError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (downloads.isNotEmpty()) item {
                DownloadPanel(downloads, model::cancelDownload, model::clearDownloadHistory)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("我的音乐", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${songs.size} 首 · ${formatBytes(songs.sumOf(Song::bytes))}", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (songs.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("把喜欢的音乐留在这里", style = MaterialTheme.typography.titleMedium)
                        Text("扫描包含音频直链的二维码，或粘贴下载地址。下载完成后即可离线播放。",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("支持常见 MP3、M4A、AAC、WAV、FLAC、OGG 音频，具体解码能力取决于设备。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(songs, key = Song::id) { song ->
                val selected = playback.id == song.id
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = playback.ready) { onNotificationNeeded(); model.play(song) },
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(buildString {
                                append(formatDuration(song.durationMs)); append(" · "); append(formatBytes(song.bytes))
                                if (selected) append(if (playback.playing) " · 正在播放" else " · 已暂停")
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { deleteSong = song }) { Text("删除") }
                    }
                }
            }
        }
    }
    inputUrl?.let { initial ->
        DownloadDialog(initial, onDismiss = { inputUrl = null }) { url, title ->
            val error = model.download(url, title)
            if (error == null) { inputUrl = null; onNotificationNeeded() }
            error
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
private fun DownloadDialog(initial: String, onDismiss: () -> Unit, onDownload: (String, String) -> String?) {
    var url by rememberSaveable(initial) { mutableStateOf(initial) }
    var title by rememberSaveable(initial) { mutableStateOf("") }
    var error by rememberSaveable(initial) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("下载音乐") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请输入音频文件直链，不支持音乐平台分享页。文件校验通过后会保存到应用内部。")
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
private fun DownloadPanel(downloads: List<WorkInfo>, cancel: (java.util.UUID) -> Unit, clear: () -> Unit) {
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
                            val bytes = info.progress.getLong(MusicDownloadWorker.BYTES, 0)
                            val total = info.progress.getLong(MusicDownloadWorker.TOTAL, -1)
                            if (total > 0) LinearProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    if (!info.state.isFinished) TextButton(onClick = { cancel(info.id) }) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun PlayerPanel(state: PlaybackState, model: MusicViewModel, togglePlay: () -> Unit) {
    var seeking by remember(state.id) { mutableStateOf<Float?>(null) }
    Surface(tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)) {
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(state.title.ifBlank { "个人音乐" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Slider(value = seeking ?: state.position.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
                onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { model.seek(it.toLong()) }; seeking = null },
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = state.duration > 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(seeking?.toLong() ?: state.position), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(state.duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = model::previous, enabled = state.ready) { Text("上一首") }
                Button(onClick = togglePlay, enabled = state.ready, modifier = Modifier.sizeIn(minWidth = 100.dp)) {
                    if (state.buffering) CircularProgressIndicator(Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp), strokeWidth = 2.dp)
                    else Text(if (state.playing) "暂停" else "播放")
                }
                TextButton(onClick = model::next, enabled = state.ready) { Text("下一首") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = model::shuffle, enabled = state.ready) { Text(if (state.shuffle) "随机播放：开" else "随机播放：关") }
                TextButton(onClick = model::repeat, enabled = state.ready) {
                    Text(when (state.repeat) { Player.REPEAT_MODE_ALL -> "列表循环"; Player.REPEAT_MODE_ONE -> "单曲循环"; else -> "顺序播放" })
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

fun formatBytes(bytes: Long): String = if (bytes < 1024 * 1024) String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
else String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
