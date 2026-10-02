package vn.bongdaplus.reader.data

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

/** Luồng phát được cho ExoPlayer: URL trực tiếp + mime (nếu biết). */
data class StreamRef(val url: String, val mimeType: String?)

/**
 * Giải embed (YouTube / streaming.bongdaplus.vn) thành URL phát trực tiếp.
 * YouTube không cho URL trực tiếp nên dùng NewPipeExtractor (có fallback
 * WebView khi giải thất bại).
 */
object VideoResolver {
    private val initLock = Mutex()
    private var inited = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun ensureInit() {
        if (inited) return
        initLock.withLock {
            if (!inited) {
                NewPipe.init(object : Downloader() {
                    override fun execute(request: Request): Response {
                        val b = okhttp3.Request.Builder().url(request.url())
                        request.headers().forEach { (k, v) ->
                            v.forEach { b.addHeader(k, it) }
                        }
                        val data = request.dataToSend()
                        if (request.httpMethod() == "POST" && data != null) {
                            b.post(object : okhttp3.RequestBody() {
                                override fun contentType(): okhttp3.MediaType? = null
                                override fun writeTo(sink: okio.Buffer) {
                                    sink.write(data)
                                }
                            })
                        }
                        http.newCall(b.build()).execute().use { res ->
                            val headers = mutableMapOf<String, List<String>>()
                            res.headers.forEach { (k, v) ->
                                headers[k] = (headers[k] ?: emptyList()) + v
                            }
                            return Response(
                                res.code, res.message,
                                headers, res.body?.string().orEmpty(),
                                res.request.url.toString(),
                            )
                        }
                    }
                })
                inited = true
            }
        }
    }

    suspend fun resolve(embedUrl: String, videoId: String?): StreamRef? =
        withContext(Dispatchers.IO) {
            try {
                // 1) YouTube
                val ytId = videoId
                    ?: BongDaPlusScraper.extractYoutubeId(embedUrl)
                if (ytId != null) return@withContext resolveYoutube(ytId)
                // 2) Đầu streaming của web: <video src="/embed/UUID/stream?token=...">
                if (embedUrl.contains("streaming.bongdaplus.vn")) {
                    return@withContext resolveSiteEmbed(embedUrl)
                }
                null
            } catch (_: Exception) { null }
        }

    private suspend fun resolveYoutube(videoId: String): StreamRef? {
        try { ensureInit() } catch (_: Exception) { return null }
        return try {
            val service = NewPipe.getService(0)
            val handler = service.streamLHFactory
                .fromUrl("https://www.youtube.com/watch?v=$videoId")
            val ex = service.getStreamExtractor(handler)
            ex.fetchPage()
            // Ưu tiên luồng gộp (hình+tiếng) ≤720p cho nhẹ máy
            val muxed = try { ex.videoStreams } catch (_: Exception) { emptyList() }
                .filter { it.content?.isNotEmpty() == true }
            val pick = muxed.sortedByDescending {
                it.resolution.filter(Char::isDigit).toIntOrNull() ?: 0
            }.firstOrNull {
                (it.resolution.filter(Char::isDigit).toIntOrNull() ?: 9999) <= 720
            } ?: muxed.sortedByDescending {
                it.resolution.filter(Char::isDigit).toIntOrNull() ?: 0
            }.firstOrNull()
            if (pick != null) return StreamRef(pick.content, null)
            val hls = try { ex.hlsUrl } catch (_: Exception) { "" }.orEmpty()
            if (hls.isNotBlank()) return StreamRef(hls, MimeTypes.APPLICATION_M3U8)
            val dash = try { ex.dashMpdUrl } catch (_: Exception) { "" }.orEmpty()
            if (dash.isNotBlank()) return StreamRef(dash, MimeTypes.APPLICATION_MPD)
            null
        } catch (_: Exception) { null }
    }

    private suspend fun resolveSiteEmbed(embedUrl: String): StreamRef? {
        return try {
            val page = Http.get(embedUrl, referer = "https://bongdaplus.vn/") ?: return null
            val m = Regex("<video[^>]+src=\"([^\"]+)\"").find(page.html)
                ?: return null
            var src = m.groupValues[1].trim()
            if (src.startsWith("/")) src = "https://streaming.bongdaplus.vn$src"
            if (!src.startsWith("http")) return null
            val mime = when {
                src.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                src.contains(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null // mp4 progressive: ExoPlayer mặc định đọc được
            }
            StreamRef(src, mime)
        } catch (_: Exception) { null }
    }
}
