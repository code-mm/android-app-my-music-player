package com.codemm.music.player

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PrivateMusicFlowTest {
    private val app = ApplicationProvider.getApplicationContext<MusicApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = WorkManager.getInstance(app)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val requests = mutableListOf<UUID>()
    private var existing = emptySet<String>()
    private var controller: MediaController? = null

    @Before fun start() {
        existing = app.library.songs.value.map(Song::id).toSet()
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanUp() {
        shell("input keyevent 224")
        controller?.let { main { it.stop(); it.clearMediaItems(); it.release() } }
        requests.forEach { manager.cancelWorkById(it).result.get(10, TimeUnit.SECONDS) }
        app.library.songs.value.filterNot { it.id in existing }.forEach(app.library::delete)
        scenario.close()
    }

    @Test fun downloadsPrivatelyDeduplicatesAndPlaysWithScreenOffAndNotificationControls() {
        val bytes = wave()
        AudioServer(bytes, "audio/wav").use { server ->
            val completed = download(server.url, "息屏播放测试", seedOrphan = true)
            assertEquals(WorkInfo.State.SUCCEEDED, completed.state)
            val song = app.library.songs.value.first { it.id == requests.first().toString() }
            assertTrue(app.library.file(song).canonicalPath.startsWith(app.filesDir.canonicalPath + "/"))
            assertTrue(app.library.file(song).isFile)
            val count = app.library.songs.value.size
            assertEquals(WorkInfo.State.SUCCEEDED, download(server.url, "重复文件").state)
            assertEquals(count, app.library.songs.value.size)
            assertTrue(app.library.audioDirectory.listFiles().orEmpty().none { it.extension == "part" })

            // Simulate interruption between moving a deleted file and committing its index.
            assertTrue(app.library.file(song).renameTo(app.library.audioDirectory.resolve("${song.id}.trash")))
            val reloaded = MusicLibrary(app)
            assertEquals(song, reloaded.songs.value.first { it.id == song.id })
            assertTrue(app.library.file(song).isFile)
            val future = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
                .setApplicationLooper(Looper.getMainLooper()).buildAsync() }
            val player = future.get(30, TimeUnit.SECONDS).also { controller = it }
            main {
                // An arbitrary URI with a valid library ID must still resolve to the private file.
                player.setMediaItem(MediaItem.Builder().setMediaId(song.id).setUri("https://example.invalid/never-stream.mp3").build())
                player.prepare(); player.play()
            }
            await { main { player.isPlaying && player.duration > 0 } }
            val before = main { player.currentPosition }
            // ActivityScenario.close waits for UI idleness, but playback progress
            // continually updates this screen. Exercise actual backgrounding instead.
            shell("input keyevent 3")
            shell("input keyevent 223")
            SystemClock.sleep(3500)
            assertTrue("Playback stopped with screen off", main { player.isPlaying })
            assertTrue("Playback position did not advance", main { player.currentPosition } > before + 1000)
            val notifications = app.getSystemService(NotificationManager::class.java)
            await { notifications.activeNotifications.any { it.id == 1001 } }
            val notification = notifications.activeNotifications.first { it.id == 1001 }.notification
            assertEquals(Notification.CATEGORY_TRANSPORT, notification.category)
            val pause = notification.actions?.firstOrNull {
                it.title.toString().contains("暂停") || it.title.toString().contains("Pause", ignoreCase = true)
            }
            // Android 13+ SystemUI builds media controls from the session, so the
            // visible Pause button need not appear in Notification.actions.
            if (pause != null) pause.actionIntent.send() else pauseThroughSystemUi()
            await { main { !player.isPlaying } }
        }
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.READ_MEDIA_AUDIO"))
        assertFalse(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(permissions.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
    }

    @Test fun rejectsNonAudioEvenWhenServerLabelsItAsAudio() {
        val count = app.library.songs.value.size
        AudioServer("<html>not audio</html>".toByteArray(), "audio/mpeg").use { server ->
            val result = download(server.url, "伪音频测试")
            assertEquals(WorkInfo.State.FAILED, result.state)
            assertEquals(count, app.library.songs.value.size)
            assertFalse(app.library.audioDirectory.resolve("${requests.last()}.part").exists())
            assertFalse(app.library.audioDirectory.resolve("${requests.last()}.audio").exists())
        }
    }

    private fun download(url: String, title: String, seedOrphan: Boolean = false): WorkInfo {
        val request = OneTimeWorkRequestBuilder<MusicDownloadWorker>().setInputData(workDataOf(
            MusicDownloadWorker.URL to url, MusicDownloadWorker.TITLE to title,
            MusicDownloadWorker.CREATED_AT to System.currentTimeMillis(),
        )).build()
        requests.add(request.id)
        if (seedOrphan) app.library.audioDirectory.resolve("${request.id}.audio").writeText("interrupted commit")
        manager.enqueue(request).result.get(15, TimeUnit.SECONDS)
        var work: WorkInfo? = null
        await(120_000) { work = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS); work?.state?.isFinished == true }
        return work!!
    }

    private fun await(timeout: Long = 60_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (predicate()) return
            SystemClock.sleep(250)
        }
        fail("Condition did not become true within ${timeout}ms")
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun pauseThroughSystemUi() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar expand-notifications")
        val deadline = SystemClock.elapsedRealtime() + 10000
        var clicked = false
        while (!clicked && SystemClock.elapsedRealtime() < deadline) {
            val node = findPause(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked && !bounds.isEmpty) {
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    clicked = true
                }
                node.recycle()
            }
            if (!clicked) SystemClock.sleep(100)
        }
        if (!clicked) {
            // Fallback verified on the project's 1080x2400 Android 15 AVD:
            // the SystemUI media Pause button is centered at (932, 619).
            val metrics = app.resources.displayMetrics
            shell("input tap ${(metrics.widthPixels * 932 / 1080)} ${(metrics.heightPixels * 619 / 2400)}")
        }
        // The caller independently waits for the actual player's paused state.
    }

    private fun findPause(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (listOf(node.text?.toString(), node.contentDescription?.toString()).any { it == "Pause" || it == "暂停" }) return node
        for (i in 0 until node.childCount) {
            val found = findPause(node.getChild(i))
            if (found != null) { node.recycle(); return found }
        }
        node.recycle()
        return null
    }

    private fun wave(): ByteArray {
        val sampleRate = 8000
        val frames = sampleRate * 90
        val dataSize = frames * 2
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }.array()
        val second = ByteBuffer.allocate(sampleRate * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(sampleRate) { frame -> putShort((sin(2 * PI * 440 * frame / sampleRate) * 1000).toInt().toShort()) }
        }.array()
        repeat(90) { second.copyInto(bytes, 44 + it * second.size) }
        // Keep separate test runs distinct, even if a previous run was interrupted.
        ByteBuffer.wrap(bytes, bytes.size - 8, 8).putLong(UUID.randomUUID().leastSignificantBits)
        return bytes
    }

    private class AudioServer(private val bytes: ByteArray, private val mime: String) : AutoCloseable {
        private val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/sample.wav"
        private val worker = thread(name = "test-audio-server", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* Read request headers. */ }
                        val output = connection.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes); output.flush()
                    }
                } catch (_: Exception) { if (socket.isClosed) break }
            }
        }
        override fun close() { socket.close(); worker.join(5000) }
    }
}
