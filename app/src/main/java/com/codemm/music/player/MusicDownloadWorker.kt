package com.codemm.music.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException

class MusicDownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val engine = DownloadEngine()
    private val notificationId = 10000 + (id.hashCode() and 0x00ffffff)
    private val createdAt get() = inputData.getLong(CREATED_AT, 0)

    override fun doWork(): Result {
        val rawUrl = inputData.getString(URL) ?: return failure("下载地址为空")
        val title = inputData.getString(TITLE).orEmpty().ifBlank { "音乐文件" }
        val directory = File(applicationContext.filesDir, "audio").apply { mkdirs() }
        val staged = File(directory, "$id.part")
        try {
            setForegroundAsync(foreground(title, 0, -1)).get()
            var lastUpdate = 0L
            val downloaded = engine.download(rawUrl, staged) { bytes, total ->
                if (isStopped) engine.cancel()
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 700 || (total > 0 && bytes == total)) {
                    lastUpdate = now
                    setProgressAsync(workDataOf(TITLE to title, BYTES to bytes, TOTAL to total, CREATED_AT to createdAt))
                    setForegroundAsync(foreground(title, bytes, total))
                }
            }
            if (isStopped) return Result.failure()
            val details = AudioInspector.inspect(staged)
            if (isStopped) return Result.failure()
            val library = (applicationContext as MusicApplication).library
            val (song, added) = library.add(id.toString(), staged, downloaded, details, inputData.getString(TITLE).orEmpty())
            return Result.success(workDataOf(
                TITLE to song.title, CREATED_AT to createdAt,
                MESSAGE to if (added) "已保存到应用内部" else "音乐库中已有相同文件，未重复保存",
            ))
        } catch (error: DownloadRejected) {
            return failure(error.message ?: "音频文件不可用")
        } catch (error: DownloadHttpError) {
            return if ((error.status == 429 || error.status >= 500) && runAttemptCount < 2 && !isStopped) {
                Result.retry()
            } else failure(error.message ?: "服务器下载失败")
        } catch (_: IllegalArgumentException) {
            return failure("下载地址无效，请使用 http 或 https 音频直链")
        } catch (_: IOException) {
            return if (runAttemptCount < 2 && !isStopped && directory.usableSpace > 1024 * 1024) {
                Result.retry()
            } else failure("下载失败，请检查网络、剩余空间和链接是否过期，然后重新扫码")
        } catch (_: Exception) {
            return failure("无法完成下载或保存，请重新打开应用后再试")
        } finally {
            staged.delete()
        }
    }

    override fun onStopped() {
        engine.cancel()
        super.onStopped()
    }

    private fun failure(message: String) = Result.failure(workDataOf(
        MESSAGE to message, TITLE to inputData.getString(TITLE).orEmpty().ifBlank { "音乐文件" }, CREATED_AT to createdAt,
    ))

    private fun foreground(title: String, bytes: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = if (total > 0) ((bytes * 100 / total).coerceIn(0, 100)).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("正在下载：$title")
            .setContentText(if (total > 0) "$progress% · 保存到应用内部" else "正在连接或下载…")
            .setContentIntent(openApp)
            .setProgress(100, progress, total <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "取消", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }

    companion object {
        const val TAG = "private-music-download"
        const val URL = "url"
        const val TITLE = "title"
        const val MESSAGE = "message"
        const val BYTES = "bytes"
        const val TOTAL = "total"
        const val CREATED_AT = "createdAt"
        private const val CHANNEL = "music_downloads"
    }
}
