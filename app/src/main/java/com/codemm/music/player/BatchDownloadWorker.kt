package com.codemm.music.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.UUID

/** A persisted, sequential queue. A retry skips every track already committed to the library. */
class BatchDownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val engine = DownloadEngine()
    private val library get() = (applicationContext as MusicApplication).library
    private val createdAt get() = inputData.getLong(MusicDownloadWorker.CREATED_AT, 0)
    private val bundleId get() = inputData.getString(BUNDLE_ID).orEmpty()
    private val sourceUrl get() = inputData.getString(MusicDownloadWorker.URL).orEmpty()
    private var bundleTitle = "音乐合集"

    override fun doWork(): Result {
        val staged = File(library.audioDirectory, "$id.part")
        try {
            require(bundleId.matches(Regex("[0-9a-f]{64}")))
            val file = manifestFile(applicationContext, bundleId)
            require(file.length() in 1..MusicBundle.MAX_MANIFEST_BYTES.toLong())
            val bundle = MusicBundle.parse(file.readText(), sourceUrl)
            bundleTitle = bundle.title
            check(library.error.value == null)
            var completed = 0
            var skipped = 0
            var completedBytes = 0L
            val failures = mutableListOf<String>()
            var transientFailure = false
            setForegroundAsync(foreground("准备下载 ${bundle.tracks.size} 首", 0, bundle.tracks.size)).get()
            for (track in bundle.tracks) {
                if (isStopped) return Result.failure()
                if (library.songs.value.any { it.sha256 == track.sha256 }) {
                    completed++; skipped++; completedBytes += track.size
                    progress(bundle, completed, failures.size, completedBytes, "已跳过：${track.title}")
                    continue
                }
                if (library.audioDirectory.usableSpace < track.size + 16L * 1024 * 1024) {
                    return Result.failure(data("存储空间不足，已完成的歌曲已保留；释放空间后点击重试"))
                }
                var lastUpdate = 0L
                try {
                    val downloaded = engine.download(track.url, staged, track.size, track.sha256) { bytes, _ ->
                        if (isStopped) engine.cancel()
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 700 || bytes == track.size) {
                            lastUpdate = now
                            progress(bundle, completed, failures.size, completedBytes + bytes, track.title)
                        }
                    }
                    if (isStopped) return Result.failure()
                    val details = AudioInspector.inspect(staged)
                    val songId = UUID.nameUUIDFromBytes(track.sha256.toByteArray()).toString()
                    if (isStopped) return Result.failure()
                    library.add(songId, staged, downloaded, details, track.title)
                    completed++; completedBytes += track.size
                } catch (error: Exception) {
                    if (isStopped) return Result.failure()
                    failures.add(track.title)
                    if (error is IOException && error !is DownloadRejected &&
                        (error !is DownloadHttpError || error.status == 429 || error.status >= 500)) {
                        transientFailure = true
                    }
                } finally { staged.delete() }
                progress(bundle, completed, failures.size, completedBytes, track.title)
            }
            if (failures.isNotEmpty()) {
                if (transientFailure && runAttemptCount < 2) return Result.retry()
                return Result.failure(data("已保存 $completed/${bundle.tracks.size} 首，失败 ${failures.size} 首：${failures.take(3).joinToString("、")}。可点击重试", completed, bundle.tracks.size))
            }
            return Result.success(data("${bundle.tracks.size} 首均已在曲库中，已跳过 $skipped 首已有歌曲", completed, bundle.tracks.size))
        } catch (_: Exception) {
            return Result.failure(data("无法读取音乐清单，请重新扫码"))
        } finally { staged.delete() }
    }

    private fun data(message: String, completed: Int = 0, total: Int = 0): Data = workDataOf(
        MusicDownloadWorker.TITLE to "批量下载 · $bundleTitle", MusicDownloadWorker.MESSAGE to message,
        MusicDownloadWorker.CREATED_AT to createdAt, BUNDLE_ID to bundleId,
        MusicDownloadWorker.URL to sourceUrl, COMPLETED to completed, TRACK_COUNT to total,
    )

    private fun progress(bundle: MusicBundle, completed: Int, failed: Int, bytes: Long, current: String) {
        setProgressAsync(workDataOf(
            MusicDownloadWorker.TITLE to "批量下载 · ${bundle.title}",
            MusicDownloadWorker.BYTES to bytes, MusicDownloadWorker.TOTAL to bundle.totalBytes,
            MusicDownloadWorker.CREATED_AT to createdAt, COMPLETED to completed, TRACK_COUNT to bundle.tracks.size,
            CURRENT to "已保存 $completed/${bundle.tracks.size} 首${if (failed > 0) " · 失败 $failed 首" else ""} · $current",
        ))
        setForegroundAsync(foreground("已保存 $completed/${bundle.tracks.size} 首 · $current", completed, bundle.tracks.size))
    }

    private fun foreground(message: String, completed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("music_downloads", applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "music_downloads")
            .setSmallIcon(R.drawable.ic_music_note).setContentTitle("批量下载 · $bundleTitle")
            .setContentText(message).setContentIntent(open).setProgress(total, completed, false)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "取消", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        val notificationId = 10000 + (id.hashCode() and 0x00ffffff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }

    override fun onStopped() { engine.cancel(); super.onStopped() }

    companion object {
        const val TAG = "music-batch-download"
        const val BUNDLE_ID = "bundleId"
        const val COMPLETED = "completed"
        const val TRACK_COUNT = "trackCount"
        const val CURRENT = "current"
        fun manifestFile(context: Context, id: String): File = File(File(context.filesDir, "music-bundles"), "$id.json")
    }
}
