package com.codemm.music.player

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DownloadRejected(message: String) : IOException(message)
class DownloadHttpError(val status: Int) : IOException("下载服务器返回 HTTP $status")

object AudioLink {
    fun parse(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("二维码应包含 http 或 https 音频文件直链")
        require(url.username.isEmpty() && url.password.isEmpty()) { "不支持带有用户名或密码的下载地址" }
        val normalized = url.newBuilder().fragment(null).build()
        require(normalized.toString().toByteArray().size <= 6000) { "下载地址过长，请使用较短的音频直链" }
        return normalized
    }

    fun title(url: HttpUrl, disposition: String?): String {
        val encoded = disposition?.let {
            Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val filename = encoded?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: disposition?.let {
                Regex("filename\\s*=\\s*\"([^\"]+)\"|filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
            }
            ?: url.pathSegments.lastOrNull().orEmpty()
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "").trim().take(160)
        return base.replace(Regex("\\.(mp3|m4a|aac|wav|flac|ogg|opus|amr)$", RegexOption.IGNORE_CASE), "")
            .ifBlank { "未命名音乐" }
    }
}

data class DownloadedAudio(val title: String, val bytes: Long, val sha256: String)

/** Streams into a staging file. Only the Android validator may promote it into the library. */
class DownloadEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .build(),
    private val maxBytes: Long = 512L * 1024 * 1024,
) {
    @Volatile private var call: okhttp3.Call? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    fun download(
        rawUrl: String,
        destination: File,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadedAudio {
        val url = AudioLink.parse(rawUrl)
        var complete = false
        try {
            if (cancelled) throw IOException("下载已取消")
            val request = Request.Builder().url(url).header("Accept", "audio/*, application/octet-stream;q=0.8").build()
            val activeCall = client.newCall(request)
            call = activeCall
            if (cancelled) activeCall.cancel()
            activeCall.execute().use { response ->
                if (!response.isSuccessful) throw DownloadHttpError(response.code)
                val body = response.body ?: throw DownloadRejected("服务器没有返回文件")
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
                    throw DownloadRejected("链接返回的是网页或文本，请使用音频文件直链")
                }
                val total = body.contentLength()
                if (total > maxBytes) throw DownloadRejected("单个音乐文件不能超过 512 MB")
                if (expectedBytes != null && total >= 0 && total != expectedBytes) {
                    throw DownloadRejected("歌曲大小与清单不一致，请重新生成清单")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                destination.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        onProgress(0, total)
                        while (true) {
                            if (cancelled) throw IOException("下载已取消")
                            val read = input.read(buffer)
                            if (read == -1) break
                            count += read
                            if (count > maxBytes) throw DownloadRejected("单个音乐文件不能超过 512 MB")
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            onProgress(count, total)
                        }
                        output.fd.sync()
                    }
                }
                if (count == 0L) throw DownloadRejected("下载到的文件为空")
                if (total >= 0 && count != total) throw IOException("文件未完整下载")
                val result = DownloadedAudio(
                    AudioLink.title(response.request.url, response.header("Content-Disposition")),
                    count,
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
                )
                if ((expectedBytes != null && count != expectedBytes) ||
                    (expectedSha256 != null && result.sha256 != expectedSha256)) {
                    throw DownloadRejected("歌曲校验失败，请重新生成清单")
                }
                complete = true
                return result
            }
        } finally {
            call = null
            if (!complete) destination.delete()
        }
    }
}
