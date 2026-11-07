package com.codemm.music.player

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BatchDownloadFlowTest {
    private val app = ApplicationProvider.getApplicationContext<MusicApplication>()
    private val manager = WorkManager.getInstance(app)
    private var existing = emptySet<String>()
    private val requests = mutableListOf<UUID>()
    private val manifests = mutableListOf<java.io.File>()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun start() {
        existing = app.library.songs.value.map { it.id }.toSet()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() {
        requests.forEach { manager.cancelWorkById(it).result.get(10, TimeUnit.SECONDS) }
        app.library.songs.value.filterNot { it.id in existing }.forEach(app.library::delete)
        manifests.forEach { it.delete() }
        scenario.close()
    }

    @Test fun validatesManifestAndDeduplicatesByContent() {
        val source = "http://127.0.0.1:18781/collection.music.json"
        val first = BundleTrack("歌曲", "http://127.0.0.1:18781/song.wav", 100, "a".repeat(64))
        val json = MusicBundle(source, "合集", listOf(first, first.copy(title = "重复"))).json()
        val parsed = MusicBundle.parse(json, source)
        assertEquals(1, parsed.tracks.size)
        assertEquals(100L, parsed.totalBytes)
        assertTrue(MusicBundle.isBundleLink(source))
        assertFalse(MusicBundle.isBundleLink("file:///collection.music.json"))
        listOf(
            first.copy(url = "http://example.invalid/song.wav"),
            first.copy(size = 0), first.copy(size = 513L * 1024 * 1024), first.copy(sha256 = "bad"),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { MusicBundle.parse(MusicBundle(source, "", listOf(bad)).json(), source) }
        }
        assertThrows(IllegalArgumentException::class.java) { MusicBundle.parse(json.replace(MusicBundle.FORMAT, "other"), source) }
    }

    @Test fun downloadsValidTracksKeepsPartialSuccessAndRetriesWithoutRedownloading() {
        Fixture().use { server ->
            val first = wave(100)
            val second = wave(200)
            server.files["/first.wav"] = first
            val tracks = listOf(track(server, "/first.wav", first), track(server, "/second.wav", second))
            val bundle = MusicBundle(server.url + "/collection.music.json", "批量重试测试", tracks)
            server.manifest = bundle.json().toByteArray()
            val loaded = MusicBundleLoader().load(bundle.sourceUrl)
            assertEquals(2, loaded.tracks.size)
            val failed = run(loaded)
            assertEquals(WorkInfo.State.FAILED, failed.state)
            assertTrue(app.library.songs.value.any { it.sha256 == tracks[0].sha256 })
            assertFalse(app.library.songs.value.any { it.sha256 == tracks[1].sha256 })
            val firstRequests = server.count("/first.wav")
            server.files["/second.wav"] = second
            val retried = run(loaded)
            assertEquals(WorkInfo.State.SUCCEEDED, retried.state)
            assertEquals(firstRequests, server.count("/first.wav"))
            val counts = tracks.map { server.count(java.net.URI(it.url).path) }
            assertEquals(WorkInfo.State.SUCCEEDED, run(loaded).state)
            assertEquals(counts, tracks.map { server.count(java.net.URI(it.url).path) })
            tracks.forEach { t ->
                val song = app.library.songs.value.single { it.sha256 == t.sha256 }
                assertEquals(t.size, app.library.file(song).length())
                assertEquals(t.sha256, sha(app.library.file(song).readBytes()))
            }
        }
    }

    @Test fun rejectsBadChecksumAndContinuesWithOtherTracks() {
        Fixture().use { server ->
            val bad = wave(300)
            val good = wave(400)
            server.files["/bad.wav"] = bad
            server.files["/good.wav"] = good
            val tracks = listOf(track(server, "/bad.wav", bad).copy(sha256 = "f".repeat(64)), track(server, "/good.wav", good))
            val result = run(MusicBundle(server.url + "/collection.music.json", "校验测试", tracks))
            assertEquals(WorkInfo.State.FAILED, result.state)
            assertTrue(app.library.songs.value.any { it.sha256 == tracks[1].sha256 })
            assertFalse(app.library.songs.value.any { it.sha256 == sha(bad) })
            assertFalse(app.library.audioDirectory.resolve("${requests.last()}.part").exists())
        }
    }

    @Test fun cancellationKeepsCompletedSongsAndCanResumeTheBundle() {
        Fixture().use { server ->
            val first = wave(500)
            val second = wave(600)
            server.files["/first.wav"] = first
            server.files["/held.wav"] = second
            server.heldPath = "/held.wav"
            val tracks = listOf(track(server, "/first.wav", first), track(server, "/held.wav", second))
            val bundle = MusicBundle(server.url + "/collection.music.json", "取消恢复测试", tracks)
            val request = enqueue(bundle)
            val part = app.library.audioDirectory.resolve("${request}.part")
            await { app.library.songs.value.any { it.sha256 == tracks[0].sha256 } && part.length() > 0 }
            manager.cancelWorkById(request).result.get(10, TimeUnit.SECONDS)
            await { !part.exists() }
            assertTrue(app.library.songs.value.any { it.sha256 == tracks[0].sha256 })
            val firstCount = server.count("/first.wav")
            server.heldPath = null
            server.resume.countDown()
            assertEquals(WorkInfo.State.SUCCEEDED, run(bundle).state)
            assertEquals(firstCount, server.count("/first.wav"))
            assertTrue(app.library.songs.value.any { it.sha256 == tracks[1].sha256 })
        }
    }

    private fun enqueue(bundle: MusicBundle): UUID {
        val json = bundle.json()
        val key = sha(json.toByteArray())
        val file = BatchDownloadWorker.manifestFile(app, key)
        file.parentFile!!.mkdirs(); file.writeText(json); manifests.add(file)
        val request = OneTimeWorkRequestBuilder<BatchDownloadWorker>().setInputData(workDataOf(
            BatchDownloadWorker.BUNDLE_ID to key, MusicDownloadWorker.URL to bundle.sourceUrl,
            MusicDownloadWorker.CREATED_AT to System.currentTimeMillis(),
        )).build()
        requests.add(request.id)
        manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
        return request.id
    }

    private fun run(bundle: MusicBundle): WorkInfo {
        val id = enqueue(bundle)
        val until = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < until) {
            val info = manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS)!!
            if (info.state.isFinished) return info
            SystemClock.sleep(100)
        }
        throw AssertionError("Batch did not finish within 60 seconds")
    }

    private fun await(predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < until) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected device state did not become true within 10 seconds")
    }

    private fun track(server: Fixture, path: String, bytes: ByteArray) = BundleTrack(path, server.url + path, bytes.size.toLong(), sha(bytes))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun wave(seed: Int): ByteArray = ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(16036); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(16000)
        repeat(8000) { putShort((seed + it % 100).toShort()) }
    }.array()

    private class Fixture : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val files = ConcurrentHashMap<String, ByteArray>()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var manifest = byteArrayOf()
        @Volatile var heldPath: String? = null
        val resume = CountDownLatch(1)
        fun count(path: String) = counts[path]?.get() ?: 0
        private val worker = thread(isDaemon = true, name = "batch-fixture") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                        val bytes = if (path.endsWith(".music.json")) manifest else files[path]
                        val status = if (bytes == null) "404 Not Found" else "200 OK"
                        val body = bytes ?: byteArrayOf()
                        val type = if (path.endsWith(".json")) "application/json" else "audio/wav"
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            if (path == heldPath) {
                                write(body, 0, 1024); flush()
                                resume.await(10, TimeUnit.SECONDS)
                                write(body, 1024, body.size - 1024)
                            } else write(body)
                            flush()
                        }
                    }
                } catch (_: Exception) { if (socket.isClosed) break }
            }
        }
        override fun close() { resume.countDown(); socket.close(); worker.join(5000) }
    }
}
