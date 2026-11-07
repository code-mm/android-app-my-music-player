package com.codemm.music.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player

internal object MusicPalette {
    val Canvas = Color(0xFFF5F6F8)
    val Ink = Color(0xFF212A36)
    val Graphite = Color(0xFF333E49)
    val Muted = Color(0xFF626F7F)
    val Yellow = Color(0xFFFFCE54)
    val PaleYellow = Color(0xFFFFF7DF)
    val Line = Color(0xFFE3E7EC)
}

@Composable
internal fun MusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = MusicPalette.Graphite,
            onPrimary = Color.White,
            primaryContainer = MusicPalette.PaleYellow,
            onPrimaryContainer = MusicPalette.Ink,
            secondary = MusicPalette.Graphite,
            secondaryContainer = MusicPalette.PaleYellow,
            background = MusicPalette.Canvas,
            onBackground = MusicPalette.Ink,
            surface = Color.White,
            onSurface = MusicPalette.Ink,
            onSurfaceVariant = MusicPalette.Muted,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            outline = MusicPalette.Line,
            outlineVariant = MusicPalette.Line,
        ),
        typography = Typography(),
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}

@Composable
internal fun MusicHeader(onScan: () -> Unit, onPaste: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 1128.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicMark(Modifier.size(40.dp))
            Text("个人音乐", Modifier.weight(1f).padding(start = 12.dp),
                fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    MusicIcon(R.drawable.ic_settings, "设置", Modifier.size(28.dp))
                }
                DropdownMenu(
                    expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    modifier = Modifier.widthIn(min = 176.dp), offset = DpOffset(0.dp, 4.dp),
                    shape = RoundedCornerShape(16.dp), containerColor = Color.White,
                    tonalElevation = 0.dp, shadowElevation = 6.dp,
                ) {
                    DropdownMenuItem(text = { Text("扫码下载") },
                        leadingIcon = { MusicIcon(R.drawable.ic_scan) },
                        onClick = { menuOpen = false; onScan() })
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MusicPalette.Line)
                    DropdownMenuItem(text = { Text("粘贴链接") },
                        leadingIcon = { MusicIcon(R.drawable.ic_link) },
                        onClick = { menuOpen = false; onPaste() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerPanel(
    state: PlaybackState, hasSongs: Boolean, model: MusicViewModel, togglePlay: () -> Unit,
) {
    var seeking by remember(state.id) { mutableStateOf<Float?>(null) }
    val displayedPosition = seeking ?: state.position.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1).toFloat())
    val progress = (displayedPosition / state.duration.coerceAtLeast(1)).coerceIn(0f, 1f)
    val controlsEnabled = state.ready && hasSongs
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(painterResource(R.mipmap.player_record), contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size((maxWidth * .65f).coerceIn(172.dp, 216.dp)))
            }
            Spacer(Modifier.height(10.dp))
            Text(state.title.ifBlank { if (hasSongs) "选择一首喜欢的音乐" else "把音乐留在身边" },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !hasSongs -> "从右上角添加音乐，随时离线听"
                    state.id == null -> "轻触下方曲目，开始播放"
                    state.buffering -> "正在加载音乐"
                    state.playing -> "正在播放 · 已保存到本机"
                    else -> "已暂停 · 已保存到本机"
                }, color = MusicPalette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Slider(
                value = displayedPosition,
                onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { model.seek(it.toLong()) }; seeking = null },
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = state.duration > 0,
                modifier = Modifier.fillMaxWidth().height(32.dp).semantics { stateDescription = "播放进度" },
                thumb = {
                    Box(Modifier.size(16.dp).shadow(1.dp, CircleShape)
                        .background(if (state.duration > 0) MusicPalette.Yellow else MusicPalette.Line, CircleShape)
                        .border(3.dp, Color.White, CircleShape))
                },
                track = {
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val y = size.height / 2
                        drawLine(MusicPalette.Line, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                        if (progress > 0f) drawLine(MusicPalette.Yellow, Offset(0f, y), Offset(size.width * progress, y), size.height, StrokeCap.Round)
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(displayedPosition.toLong()), color = MusicPalette.Muted, style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(state.duration), color = MusicPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PlaybackAction(R.drawable.ic_shuffle, if (state.shuffle) "随机播放：开" else "随机播放：关",
                    controlsEnabled, state.shuffle, model::shuffle)
                PlaybackAction(R.drawable.ic_previous, "上一首", controlsEnabled, onClick = model::previous)
                FilledIconButton(onClick = togglePlay, enabled = controlsEnabled,
                    modifier = Modifier.size(64.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MusicPalette.Graphite,
                        contentColor = Color.White, disabledContainerColor = MusicPalette.Line, disabledContentColor = MusicPalette.Muted)) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
                    else MusicIcon(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        if (state.playing) "暂停" else "播放", Modifier.size(34.dp),
                        tint = if (controlsEnabled) Color.White else MusicPalette.Muted)
                }
                PlaybackAction(R.drawable.ic_next, "下一首", controlsEnabled, onClick = model::next)
                PlaybackAction(if (state.repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                    when (state.repeat) { Player.REPEAT_MODE_ALL -> "列表循环"; Player.REPEAT_MODE_ONE -> "单曲循环"; else -> "顺序播放" },
                    controlsEnabled, state.repeat != Player.REPEAT_MODE_OFF, model::repeat)
            }
        }
    }
}

@Composable
private fun PlaybackAction(@DrawableRes icon: Int, label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).background(if (active && enabled) MusicPalette.PaleYellow else Color.Transparent, CircleShape)
            .semantics { selected = active }) {
        MusicIcon(icon, label, Modifier.size(26.dp), if (enabled) MusicPalette.Graphite else MusicPalette.Muted.copy(alpha = .45f))
    }
}

@Composable
internal fun SongRow(
    song: Song, selected: Boolean, playing: Boolean, first: Boolean, last: Boolean,
    enabled: Boolean, onPlay: () -> Unit, onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color.White)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onPlay)
            .semantics { this.selected = selected }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selected && playing) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    MusicIcon(R.drawable.ic_equalizer, "正在播放", Modifier.size(28.dp), MusicPalette.Yellow)
                }
            } else MusicMark(Modifier.size(40.dp), small = true)
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatDuration(song.durationMs)} · ${formatBytes(song.bytes)}",
                    style = MaterialTheme.typography.bodySmall, color = MusicPalette.Muted)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { MusicIcon(R.drawable.ic_more, "更多操作：${song.title}", tint = MusicPalette.Muted) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("删除音乐") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = MusicPalette.Line)
    }
}

@Composable
internal fun EmptyMusicLibrary() {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("你的音乐，从这里开始", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("点击右上角设置，扫码下载或粘贴音频直链。下载完成后就能离线播放。",
                color = MusicPalette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MusicMark(modifier: Modifier = Modifier, small: Boolean = false) {
    Box(modifier.background(if (small) MusicPalette.PaleYellow else Color.Transparent, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        if (small) MusicIcon(R.drawable.ic_music, modifier = Modifier.fillMaxSize().padding(7.dp))
        else Image(painterResource(R.drawable.launcher_mark), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun MusicIcon(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier.size(24.dp), tint: Color = MusicPalette.Graphite) {
    Icon(painterResource(icon), description, modifier = modifier, tint = tint)
}
