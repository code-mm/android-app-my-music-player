package com.codemm.music.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BundleDownloadDialog(initial: String, songs: List<Song>, onDismiss: () -> Unit, onDownload: (MusicBundle) -> String?) {
    var attempt by remember { mutableIntStateOf(0) }
    val loader = remember(initial, attempt) { MusicBundleLoader() }
    var bundle by remember(initial, attempt) { mutableStateOf<MusicBundle?>(null) }
    var loading by remember(initial, attempt) { mutableStateOf(true) }
    var error by remember(initial, attempt) { mutableStateOf<String?>(null) }
    DisposableEffect(loader) { onDispose { loader.cancel() } }
    LaunchedEffect(loader) {
        try { bundle = withContext(Dispatchers.IO) { loader.load(initial) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: IllegalArgumentException) { error = e.message ?: "音乐清单无效" }
        catch (_: Exception) { error = "无法读取音乐清单，请确认手机与电脑在同一网络，且电脑上的传输服务正在运行。" }
        finally { loading = false }
    }
    val existing = songs.map { it.sha256 }.toSet()
    val ready = bundle
    val remaining = ready?.tracks?.filterNot { it.sha256 in existing }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量下载音乐") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取音乐清单…") }
                ready?.let { value ->
                    Text(value.title, fontWeight = FontWeight.Bold)
                    Text("共 ${value.tracks.size} 首 · ${formatBytes(value.totalBytes)}")
                    Text("待下载 ${remaining.size} 首 · ${formatBytes(remaining.sumOf { it.size })}；已有 ${value.tracks.size - remaining.size} 首将跳过。", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(value.tracks, key = { _, track -> track.sha256 }) { index, track ->
                            Column {
                                Text("${index + 1}. ${track.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (track.sha256 in existing) "已在曲库中" else formatBytes(track.size),
                                    style = MaterialTheme.typography.bodySmall, color = MusicPalette.Muted)
                            }
                        }
                    }
                    Text("按顺序下载并校验，保存到应用内部。下载期间请保持电脑开机。", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (ready != null) {
                TextButton(enabled = remaining.isNotEmpty(), onClick = { error = onDownload(ready) }) { Text("全部下载（${remaining.size} 首）") }
            } else if (!loading) TextButton(onClick = { attempt++ }) { Text("重试") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (remaining.isEmpty() && ready != null) "完成" else "取消") } },
    )
}
