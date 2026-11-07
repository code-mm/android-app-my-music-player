package com.codemm.music.player

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PlaybackState(
    val ready: Boolean = false,
    val id: String? = null,
    val title: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_ALL,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = (application as MusicApplication).library
    val songs = library.songs
    val libraryError = library.error
    private val manager = WorkManager.getInstance(application)
    private val _downloads = MutableStateFlow<List<WorkInfo>>(emptyList())
    val downloads = _downloads.asStateFlow()
    private val _playback = MutableStateFlow(PlaybackState())
    val playback = _playback.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var controller: MediaController? = null
    private var cleared = false
    private val controllerFuture = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayback()
        override fun onPlayerError(error: PlaybackException) {
            _message.value = "播放失败，文件可能已损坏或此设备不支持该音频格式"
        }
    }

    init {
        controllerFuture.addListener({
            if (!cleared) {
                runCatching { controllerFuture.get() }
                    .onSuccess { controller = it; it.addListener(listener); updatePlayback() }
                    .onFailure { _message.value = "无法连接播放服务，请重新打开应用" }
            }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch {
            manager.getWorkInfosByTagFlow(MusicDownloadWorker.TAG).collect { infos ->
                _downloads.value = infos.filter { it.state != WorkInfo.State.CANCELLED }
                    .sortedWith(compareBy<WorkInfo> { it.state.isFinished }.thenByDescending {
                        maxOf(it.progress.getLong(MusicDownloadWorker.CREATED_AT, 0), it.outputData.getLong(MusicDownloadWorker.CREATED_AT, 0))
                    }).take(5)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) updatePlayback()
                delay(500)
            }
        }
    }

    private fun updatePlayback() {
        val p = controller ?: return
        _playback.value = PlaybackState(
            ready = true, id = p.currentMediaItem?.mediaId,
            title = p.mediaMetadata.title?.toString().orEmpty(),
            playing = p.isPlaying, buffering = p.playbackState == Player.STATE_BUFFERING,
            position = p.currentPosition.coerceAtLeast(0),
            duration = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            shuffle = p.shuffleModeEnabled, repeat = p.repeatMode,
        )
    }

    fun download(raw: String, title: String): String? {
        val url = try { AudioLink.parse(raw).toString() } catch (e: IllegalArgumentException) { return e.message }
        if (library.error.value != null) return "音乐库暂时无法写入，请重新启动应用"
        val request = OneTimeWorkRequestBuilder<MusicDownloadWorker>()
            .setInputData(workDataOf(MusicDownloadWorker.URL to url, MusicDownloadWorker.TITLE to title.trim().take(160),
                MusicDownloadWorker.CREATED_AT to System.currentTimeMillis()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(MusicDownloadWorker.TAG).build()
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        manager.enqueueUniqueWork("music-$key", ExistingWorkPolicy.KEEP, request)
        _message.value = "已加入下载队列"
        return null
    }

    fun cancelDownload(id: UUID) { manager.cancelWorkById(id) }
    fun clearDownloadHistory() { manager.pruneWork() }
    fun clearMessage(message: String) { if (_message.value == message) _message.value = null }

    fun play(song: Song) {
        val p = controller ?: return
        if (p.currentMediaItem?.mediaId == song.id) {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.prepare(); p.play()
        } else {
            val items = songs.value.map { MediaItem.Builder().setMediaId(it.id).build() }
            val index = songs.value.indexOfFirst { it.id == song.id }
            if (index >= 0) {
                p.setMediaItems(items, index, 0)
                p.prepare(); p.play()
            }
        }
    }

    fun togglePlay() {
        val p = controller ?: return
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause()
        else if (p.currentMediaItem == null) songs.value.firstOrNull()?.let(::play)
        else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.prepare(); p.play()
        }
    }

    fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekToDefaultPosition(0) } }
    fun previous() {
        controller?.let {
            if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem()
        }
    }
    fun seek(position: Long) { controller?.seekTo(position.coerceIn(0, _playback.value.duration)) }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_ALL
            }
        }
    }

    fun delete(song: Song) {
        viewModelScope.launch {
            runCatching {
                controller?.let { p ->
                    val index = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == song.id }
                    if (index != null) p.removeMediaItem(index)
                }
                withContext(Dispatchers.IO) { library.delete(song) }
            }.onFailure { _message.value = "删除失败，文件仍保留在应用内部，请重试" }
        }
    }

    override fun onCleared() {
        cleared = true
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
