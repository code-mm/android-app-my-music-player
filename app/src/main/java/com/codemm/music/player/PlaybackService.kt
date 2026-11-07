package com.codemm.music.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library get() = (application as MusicApplication).library
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = preferences.getInt("repeat", Player.REPEAT_MODE_ALL)
            shuffleModeEnabled = preferences.getBoolean("shuffle", false)
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.uid != Process.myUid() && !controller.isTrusted) {
                        return MediaSession.ConnectionResult.reject()
                    }
                    val commands = Player.Commands.Builder().addAllCommands().apply {
                        if (controller.uid != Process.myUid()) {
                            remove(Player.COMMAND_SET_MEDIA_ITEM)
                            remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        }
                    }.build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession)
                        .setAvailablePlayerCommands(commands).build()
                }

                // Ignore controller-supplied URIs, including from our own UI. Only library IDs resolve.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    val known = library.songs.value.associateBy(Song::id)
                    val safe = mediaItems.mapNotNull { item -> known[item.mediaId]?.takeIf { library.file(it).isFile }?.let(::mediaItem) }
                    return Futures.immediateFuture(safe.toMutableList())
                }
            }).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(1001).setChannelId("music_playback").setChannelName(R.string.playback_channel).build().apply {
                setSmallIcon(R.drawable.ic_music_note)
            })
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.currentMediaItem != null) {
                    preferences.edit().putString("song", player.currentMediaItem?.mediaId)
                        .putLong("position", player.currentPosition).putInt("repeat", player.repeatMode)
                        .putBoolean("shuffle", player.shuffleModeEnabled).apply()
                }
            }
        })
        scope.launch {
            var first = true
            library.songs.collect { songs ->
                if (first) {
                    first = false
                    if (songs.isNotEmpty()) {
                        val index = songs.indexOfFirst { it.id == preferences.getString("song", null) }.coerceAtLeast(0)
                        player.setMediaItems(songs.map(::mediaItem), index, preferences.getLong("position", 0))
                        player.prepare()
                    }
                } else {
                    val ids = songs.map(Song::id).toSet()
                    for (i in player.mediaItemCount - 1 downTo 0) {
                        if (player.getMediaItemAt(i).mediaId !in ids) player.removeMediaItem(i)
                    }
                    val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                    player.addMediaItems(songs.filter { it.id !in queued }.map(::mediaItem))
                    if (player.mediaItemCount == 0) player.stop()
                    else if (player.playbackState == Player.STATE_IDLE) player.prepare()
                }
            }
        }
    }

    private fun mediaItem(song: Song) = MediaItem.Builder().setMediaId(song.id)
        .setUri(Uri.fromFile(library.file(song)))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist("个人音乐 · 应用内音乐").build())
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        preferences.edit().putLong("position", player.currentPosition).apply()
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }
}
