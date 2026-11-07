package com.codemm.music.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.system.Os
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

data class Song(
    val id: String,
    val title: String,
    val bytes: Long,
    val durationMs: Long,
    val mime: String,
    val sha256: String,
    val addedAt: Long,
)

data class AudioDetails(val title: String?, val durationMs: Long, val mime: String)

object AudioInspector {
    fun inspect(file: File): AudioDetails {
        val extractor = MediaExtractor()
        var audioMime: String? = null
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) throw DownloadRejected("仅支持音频文件，不支持视频")
                if (mime.startsWith("audio/")) audioMime = mime
            }
        } catch (error: DownloadRejected) {
            throw error
        } catch (_: Exception) {
            throw DownloadRejected("文件不是可识别的音频，或文件已损坏")
        } finally {
            extractor.release()
        }
        val mime = audioMime ?: throw DownloadRejected("文件中没有可播放的音轨")
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.absolutePath)
            AudioDetails(
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf(String::isNotEmpty),
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                mime,
            )
        } catch (_: Exception) {
            AudioDetails(null, 0, mime)
        } finally {
            metadata.release()
        }
    }
}

/** No MediaStore, external directories, or exported provider: every file belongs to this app. */
class MusicLibrary(context: Context) {
    val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }
    private val index = AtomicFile(File(context.filesDir, "music-library.json"))
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _songs = MutableStateFlow(load())
    val songs = _songs.asStateFlow()

    fun file(song: Song): File = File(audioDirectory, "${UUID.fromString(song.id)}.audio")

    private fun load(): List<Song> = try {
        val array = JSONArray(index.openRead().bufferedReader().use { it.readText() })
        (0 until array.length()).map { position ->
            val o = array.getJSONObject(position)
            Song(
                UUID.fromString(o.getString("id")).toString(), o.getString("title"),
                o.getLong("bytes"), o.getLong("durationMs"), o.getString("mime"),
                o.getString("sha256"), o.getLong("addedAt"),
            )
        }.onEach { song ->
            // If the process died before the index committed a deletion, restore its file.
            val source = file(song)
            val trash = File(audioDirectory, "${song.id}.trash")
            if (!source.exists() && trash.exists()) trash.renameTo(source)
        }.filter { file(it).isFile }.sortedByDescending(Song::addedAt)
    } catch (_: FileNotFoundException) {
        emptyList()
    } catch (_: Exception) {
        _error.value = "音乐库索引无法读取。请保留应用数据并重新启动，不要清除数据。"
        emptyList()
    }

    @Synchronized
    fun add(id: String, staged: File, downloaded: DownloadedAudio, details: AudioDetails, customTitle: String): Pair<Song, Boolean> {
        check(_error.value == null) { "音乐库暂时无法写入" }
        val normalizedId = UUID.fromString(id).toString()
        _songs.value.firstOrNull { it.id == normalizedId || it.sha256 == downloaded.sha256 }?.let { return it to false }
        val song = Song(
            normalizedId,
            customTitle.trim().ifBlank { details.title ?: downloaded.title }.take(160),
            downloaded.bytes, details.durationMs, details.mime, downloaded.sha256, System.currentTimeMillis(),
        )
        val target = file(song)
        // A crash after a previous rename but before the index commit can leave an
        // unindexed file for this work ID. Replace only that private, unindexed file.
        Os.rename(staged.absolutePath, target.absolutePath)
        val next = listOf(song) + _songs.value
        try {
            persist(next)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        _songs.value = next
        return song to true
    }

    @Synchronized
    fun delete(song: Song) {
        check(_error.value == null) { "音乐库暂时无法写入" }
        val source = file(song)
        val trash = File(audioDirectory, "${song.id}.trash")
        if (source.exists()) check(source.renameTo(trash)) { "无法删除音乐文件" }
        val next = _songs.value.filterNot { it.id == song.id }
        try {
            persist(next)
        } catch (error: Exception) {
            if (trash.exists()) trash.renameTo(source)
            throw error
        }
        _songs.value = next
        trash.delete()
    }

    private fun persist(songs: List<Song>) {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(JSONObject().apply {
                put("id", song.id); put("title", song.title); put("bytes", song.bytes)
                put("durationMs", song.durationMs); put("mime", song.mime)
                put("sha256", song.sha256); put("addedAt", song.addedAt)
            })
        }
        val stream = index.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (error: Exception) {
            index.failWrite(stream)
            throw error
        }
    }
}
