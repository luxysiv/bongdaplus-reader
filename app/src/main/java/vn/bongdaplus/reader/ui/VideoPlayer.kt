package vn.bongdaplus.reader.ui

import android.content.Intent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.bongdaplus.reader.data.Http
import vn.bongdaplus.reader.data.StreamRef
import vn.bongdaplus.reader.data.VideoResolver

/**
 * Player video native dùng chung: bài video toàn màn hình + video nhúng trong bài.
 * Giao diện hiện đại: poster + nút play lớn, viền bo, fullscreen thật.
 */
@Composable
fun NativeVideoPlayer(
    embedUrl: String,
    videoId: String?,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var stream by remember(embedUrl) { mutableStateOf<StreamRef?>(null) }
    var resolving by remember(embedUrl) { mutableStateOf(true) }
    var playError by remember(embedUrl) { mutableStateOf(false) }
    var fullscreen by remember(embedUrl) { mutableStateOf(false) }
    var userStarted by remember(embedUrl) { mutableStateOf(false) }

    val dsFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(mapOf("Referer" to "https://bongdaplus.vn/"))
    }
    val player = remember(embedUrl) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dsFactory))
            .build().apply {
                // KHÔNG tự phát khi prepare xong (kẻo tiếng chạy sau poster
                // trong lúc user chưa bấm play). Chỉ phát khi user bấm.
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { playError = true }
                })
            }
    }
    DisposableEffect(embedUrl) {
        onDispose { try { player.release() } catch (_: Exception) { } }
    }
    LaunchedEffect(embedUrl) {
        try {
            val s = withContext(Dispatchers.IO) { VideoResolver.resolve(embedUrl, videoId) }
            if (s != null) {
                stream = s
                val item = MediaItem.Builder().setUri(s.url).apply {
                    s.mimeType?.let { setMimeType(it) }
                }.build()
                try { player.setMediaItem(item); player.prepare() }
                catch (_: Exception) { playError = true }
            }
        } catch (_: Exception) { }
        resolving = false
    }

    if (playError || (!resolving && stream == null)) {
        VideoExternalCard(embedUrl, videoId)
        return
    }

    val poster = remember(embedUrl, videoId) {
        if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        else Regex("streaming\\.bongdaplus\\.vn/embed/([0-9a-f-]+)")
            .find(embedUrl)?.groupValues?.get(1)
            ?.let { "https://streaming.bongdaplus.vn/video/$it/thumbnail" }
    }

    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp)).background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (stream != null && userStarted) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).also { pv ->
                        pv.player = player
                        try {
                            pv.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
                                ?.visibility = View.GONE
                        } catch (_: Exception) { }
                    }
                },
                update = {
                    it.player = if (fullscreen) null else player
                    try {
                        it.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
                            ?.visibility = View.GONE
                    } catch (_: Exception) { }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Poster + nút play lớn kiểu YouTube — bấm mới phát (đỡ tốn data)
            if (poster != null) {
                AsyncImage(poster, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            }
            if (resolving) {
                CircularProgressIndicator(color = Color.White)
            } else {
                FilledIconButton(
                    onClick = {
                        userStarted = true
                        try { player.play() } catch (_: Exception) { }
                    },
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White)
                ) { Icon(Icons.Rounded.PlayArrow, "Phát", modifier = Modifier.size(40.dp)) }
            }
        }
        if (stream != null && !fullscreen) {
            IconButton(
                onClick = { fullscreen = true },
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(4.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) { Icon(Icons.Rounded.Fullscreen, "Toàn màn hình", tint = Color.White) }
        }
        if (!title.isNullOrBlank() && !userStarted) {
            Text(title, color = Color.White, fontSize = 12.sp, maxLines = 2,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(12.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(8.dp, 4.dp))
        }
    }
    if (fullscreen && stream != null) {
        Dialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { fullscreen = false }
        ) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).also { pv ->
                        pv.player = player
                        pv.setFullscreenButtonClickListener { fullscreen = false }
                    }
                },
                update = { if (it.player == null) it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().background(Color.Black)
            )
        }
    }
}

/** Thẻ mở video ngoài app (khi không giải được luồng) */
@Composable
fun VideoExternalCard(embedUrl: String, videoId: String?) {
    val ctx = LocalContext.current
    val openUrl = if (!videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=$videoId" else embedUrl
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(openUrl))) }
            catch (_: Exception) { }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text("Mở video bằng trình duyệt / YouTube",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("↗", fontSize = 18.sp)
        }
    }
}
