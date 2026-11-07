package com.codemm.music.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BundleTrack(val title: String, val url: String, val size: Long, val sha256: String)

data class MusicBundle(val sourceUrl: String, val title: String, val tracks: List<BundleTrack>) {
    val totalBytes get() = tracks.sumOf { it.size }

    fun json(): String = JSONObject().apply {
        put("format", FORMAT); put("version", 1); put("title", title)
        put("tracks", JSONArray().apply {
            tracks.forEach { track -> put(JSONObject().apply {
                put("title", track.title); put("url", track.url)
                put("size", track.size); put("sha256", track.sha256)
            }) }
        })
    }.toString()

    companion object {
        const val FORMAT = "personal-music-bundle"
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        fun isBundleLink(raw: String): Boolean = runCatching {
            AudioLink.parse(raw).encodedPath.endsWith(".music.json", ignoreCase = true)
        }.getOrDefault(false)

        fun parse(raw: String, sourceUrl: String): MusicBundle {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "音乐清单过大" }
            val origin = AudioLink.parse(sourceUrl)
            val root = JSONObject(raw)
            require(root.optString("format") == FORMAT && root.optInt("version") == 1) { "不支持此音乐清单格式" }
            val array = root.getJSONArray("tracks")
            require(array.length() in 1..1000) { "音乐清单须包含 1 至 1000 首歌曲" }
            val tracks = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val url = AudioLink.parse(item.getString("url"))
                require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) {
                    "歌曲下载地址必须与清单来自同一服务器"
                }
                val size = item.getLong("size")
                require(size in 1..512L * 1024 * 1024) { "清单中有空文件或超过 512 MB 的歌曲" }
                val hash = item.getString("sha256").lowercase()
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "歌曲校验信息无效" }
                val title = item.optString("title").replace(Regex("[\\p{Cntrl}]"), "").trim().take(160)
                    .ifBlank { AudioLink.title(url, null) }
                BundleTrack(title, url.toString(), size, hash)
            }.distinctBy { it.sha256 }
            return MusicBundle(origin.toString(), root.optString("title").trim().take(100).ifBlank { "音乐合集" }, tracks)
        }
    }
}

class MusicBundleLoader {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    @Volatile private var call: okhttp3.Call? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; call?.cancel() }

    fun load(raw: String): MusicBundle {
        val url = AudioLink.parse(raw)
        val active = client.newCall(Request.Builder().url(url).build())
        call = active
        if (cancelled) active.cancel()
        try {
            return active.execute().use { response ->
                if (!response.isSuccessful) throw DownloadHttpError(response.code)
                val body = response.body ?: throw IOException("音乐清单为空")
                require(body.contentLength() <= MusicBundle.MAX_MANIFEST_BYTES) { "音乐清单过大" }
                val bytes = body.byteStream().use { it.readBytesLimited(MusicBundle.MAX_MANIFEST_BYTES) }
                MusicBundle.parse(bytes.toString(Charsets.UTF_8), url.toString())
            }
        } finally { call = null }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) return output.toByteArray()
        require(output.size() + count <= limit) { "音乐清单过大" }
        output.write(buffer, 0, count)
    }
}
