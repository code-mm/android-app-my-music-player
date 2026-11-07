package com.codemm.music.player

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class DownloadEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun startServer() { server = MockWebServer(); server.start() }
    @After fun stopServer() { server.shutdown() }

    @Test fun streamsRedirectedFileAndUsesUtf8NameWithoutTrustingItAsAPath() {
        val audio = "test audio bytes"
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/audio.mp3"))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg")
            .setHeader("Content-Disposition", "attachment; filename*=UTF-8''..%2F%E4%B8%AA%E4%BA%BA%E9%9F%B3%E4%B9%90.mp3")
            .setBody(audio))
        val staged = folder.root.resolve("only-this-file.part")
        val result = DownloadEngine().download(server.url("/redirect").toString(), staged)
        assertEquals(audio, staged.readText())
        assertEquals("个人音乐", result.title)
        assertEquals(audio.length.toLong(), result.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(audio.toByteArray()).joinToString("") { "%02x".format(it) }, result.sha256)
        assertEquals(listOf("only-this-file.part"), folder.root.list()!!.toList())
    }

    @Test fun rejectsWebpageInsteadOfPuttingItInTheLibrary() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>share page</html>"))
        val staged = folder.root.resolve("page.part")
        assertThrows(DownloadRejected::class.java) { DownloadEngine().download(server.url("/").toString(), staged) }
        assertFalse(staged.exists())
    }

    @Test fun enforcesSizeLimitEvenWhenServerDoesNotDeclareLength() {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setChunkedBody("a".repeat(1025), 64))
        val staged = folder.root.resolve("too-big.part")
        assertThrows(DownloadRejected::class.java) { DownloadEngine(maxBytes = 1024).download(server.url("/").toString(), staged) }
        assertFalse(staged.exists())
    }

    @Test fun rejectsOversizedContentLengthBeforeWriting() {
        server.enqueue(MockResponse().setBody("a".repeat(1025)))
        val staged = folder.root.resolve("too-big.part")
        assertThrows(DownloadRejected::class.java) { DownloadEngine(maxBytes = 1024).download(server.url("/").toString(), staged) }
        assertFalse(staged.exists())
    }

    @Test fun truncatedFileIsRemoved() {
        server.enqueue(MockResponse().setBody("a".repeat(128 * 1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val staged = folder.root.resolve("truncated.part")
        assertThrows(IOException::class.java) { DownloadEngine().download(server.url("/").toString(), staged) }
        assertFalse(staged.exists())
    }

    @Test fun cancellationRemovesStagedFile() {
        server.enqueue(MockResponse().setBody("a".repeat(128 * 1024)))
        val staged = folder.root.resolve("cancelled.part")
        val engine = DownloadEngine()
        assertThrows(IOException::class.java) {
            engine.download(server.url("/").toString(), staged) { bytes, _ -> if (bytes > 0) engine.cancel() }
        }
        assertFalse(staged.exists())
    }

    @Test fun distinguishesHttpErrorsAndRejectsEmptyFiles() {
        server.enqueue(MockResponse().setResponseCode(404))
        val missing = assertThrows(DownloadHttpError::class.java) {
            DownloadEngine().download(server.url("/missing").toString(), folder.root.resolve("missing.part"))
        }
        assertEquals(404, missing.status)
        server.enqueue(MockResponse().setBody(""))
        assertThrows(DownloadRejected::class.java) { DownloadEngine().download(server.url("/empty").toString(), folder.root.resolve("empty.part")) }
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test fun acceptsLanLinksButRejectsNonNetworkUrisAndEmbeddedCredentials() {
        assertEquals("http://192.168.1.2/song.mp3", AudioLink.parse(" http://192.168.1.2/song.mp3#fragment ").toString())
        listOf("file:///data/secret.mp3", "content://media/external/audio", "ftp://host/a.mp3", "not a URL", "https://user:password@host/a.mp3").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { AudioLink.parse(raw) }
        }
    }

    @Test fun rejectsBundleContentWhenHashDoesNotMatch() {
        server.enqueue(MockResponse().setBody("changed audio"))
        val target = folder.root.resolve("bundle.part")
        assertThrows(DownloadRejected::class.java) {
            DownloadEngine().download(server.url("/song.mp3").toString(), target, expectedSha256 = "0".repeat(64))
        }
        assertFalse(target.exists())
    }

    @Test fun rejectsBundleSizeMismatchEvenWithoutContentLength() {
        server.enqueue(MockResponse().setChunkedBody("abcd", 2))
        val target = folder.root.resolve("bundle.part")
        assertThrows(DownloadRejected::class.java) {
            DownloadEngine().download(server.url("/song.mp3").toString(), target, expectedBytes = 5)
        }
        assertFalse(target.exists())
    }

    @Test fun acceptsMatchingBundleSizeAndHash() {
        val audio = "matching audio"
        server.enqueue(MockResponse().setBody(audio))
        val hash = MessageDigest.getInstance("SHA-256").digest(audio.toByteArray()).joinToString("") { "%02x".format(it) }
        val result = DownloadEngine().download(server.url("/song.mp3").toString(), folder.root.resolve("bundle.part"), audio.length.toLong(), hash)
        assertEquals(hash, result.sha256)
    }
}
