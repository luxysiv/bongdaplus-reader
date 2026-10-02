package vn.bongdaplus.reader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

/**
 * Chi tiết chuẩn báo, render NATIVE 100% (không WebView):
 * hero + tiêu đề + meta + cảm xúc + body + bình luận thật + tin liên quan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    article: Article,
    auth: AuthManager,
    bookmarks: BookmarkStore,
    prefs: UiPrefs,
    onBack: () -> Unit,
    onOpen: (Article) -> Unit,
    onLogin: () -> Unit,
) {
    val vm: DetailViewModel = viewModel()
    val detail by vm.detail.collectAsState()
    val related by vm.related.collectAsState()
    val loading by vm.loading.collectAsState()
    val comments by vm.comments.collectAsState()
    val loadingComments by vm.loadingComments.collectAsState()
    val sending by vm.sending.collectAsState()
    val sendMsg by vm.sendMsg.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedList by bookmarks.flow().collectAsState(initial = emptyList())
    val isSaved = remember(savedList, article.id) { savedList.any { it.id == article.id } }
    val logged by auth.loggedIn.collectAsState(initial = false)
    val fontScale by prefs.fontScale.collectAsState(initial = 1f)
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    val myVotes by vm.myVotes.collectAsState()
    val trackStore = remember(ctx) { CommentTrackStore(ctx.applicationContext) }

    LaunchedEffect(article.url) {
        vm.cookieProvider = { auth.currentCookies() }
        vm.load(article)
        // Mở bài nào thì track luôn để Worker có mốc so sánh, tránh báo ảo lần đầu
        try { trackStore.track(article) } catch (_: Exception) { }
    }

    val cmtCount = detail?.emotion?.comments ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(catName(detail?.article?.category ?: article.category)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } },
                actions = {
                    // Cỡ chữ
                    IconButton(onClick = { scope.launch { prefs.setFontScale(fontScale - 0.1f) } }) {
                        Text("A-", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { scope.launch { prefs.setFontScale(fontScale + 0.1f) } }) {
                        Text("A+", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, article.title)
                            putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.url}")
                        }
                        ctx.startActivity(Intent.createChooser(i, "Chia sẻ tin"))
                    }) { Icon(Icons.Default.Share, "Chia sẻ") }
                    IconButton(onClick = { scope.launch { bookmarks.toggle(article) } }) {
                        Icon(
                            if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Lưu",
                            tint = if (isSaved) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                }
            )
        }
    ) { pad ->
        if (loading && detail == null) {
            Box(Modifier.padding(pad)) { LoadingSkeleton(4) }
        } else if (detail != null) {
            val d = detail!!
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                // Hero
                if (!d.article.imageUrl.isNullOrBlank()) {
                    item {
                        AsyncImage(
                            d.article.imageUrl, null,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                // Tiêu đề + meta
                item {
                    Column(Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp)) {
                        MetaLine(d.article)
                        Spacer(Modifier.height(6.dp))
                        Text(d.article.title, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "✍️ ${d.author ?: "BongdaPlus"}  •  🕐 ${d.publishedAt ?: ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        // Cảm xúc thật từ server — bấm để Thích/Tim/Wow (cần đăng nhập)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("👍 ${d.emotion.liked}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (logged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.clickable { if (logged) vm.reactArticle(1) else onLogin() })
                            Spacer(Modifier.width(12.dp))
                            Text("❤️ ${d.emotion.heart}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (logged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.clickable { if (logged) vm.reactArticle(2) else onLogin() })
                            Spacer(Modifier.width(12.dp))
                            Text("😮 ${d.emotion.wow}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (logged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.clickable { if (logged) vm.reactArticle(4) else onLogin() })
                            Spacer(Modifier.width(12.dp))
                            Text("💬 $cmtCount bình luận",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
                    }
                }
                // Body native
                if (d.blocks.isEmpty()) {
                    item {
                        Text(d.bodyText.ifBlank { "Không tải được nội dung. Mở bài gốc trên web nhé." },
                            modifier = Modifier.padding(16.dp),
                            fontSize = (17 * fontScale).sp, lineHeight = (27 * fontScale).sp)
                    }
                } else {
                    items(d.blocks) { b -> BlockView(b, fontScale) }
                }
                // Nguồn
                item {
                    Text("Nguồn: Tạp chí Bóng Đá (bongdaplus.vn)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp, 8.dp))
                }
                // Bình luận
                item { SectionHeader("Bình luận ($cmtCount)") }
                if (loadingComments && comments.isEmpty()) {
                    item { LoadingSkeleton(2) }
                } else if (comments.isEmpty()) {
                    item { EmptyState("Chưa có bình luận. Hãy là người đầu tiên!") }
                } else {
                    items(comments, key = { it.id }) { c ->
                        CommentCard(c, fontScale,
                            voted = myVotes[c.id] ?: 0,
                            canVote = logged,
                            onLike = { vm.reactComment(c.id, true) },
                            onDislike = { vm.reactComment(c.id, false) },
                            onReply = { replyTo = c })
                    }
                }
                // Hộp gửi
                item {
                    Column(Modifier.padding(12.dp)) {
                        if (logged) {
                            replyTo?.let { r ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 6.dp)) {
                                    Text("↩️ Trả lời @${r.name.trim()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f))
                                    TextButton(onClick = { replyTo = null }) { Text("Huỷ") }
                                }
                            }
                            OutlinedTextField(
                                value = draft, onValueChange = { if (it.length <= 1000) draft = it },
                                placeholder = { Text(if (replyTo != null) "Trả lời ${replyTo?.name}… (cần duyệt)" else "Chia sẻ suy nghĩ của bạn… (cần duyệt)") },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                sendMsg?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = if (it.startsWith("Đã gửi")) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f))
                                } ?: Spacer(Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val txt = draft
                                        val rt = replyTo
                                        scope.launch {
                                            vm.sendComment(txt, article, trackStore,
                                                parentId = rt?.id ?: "0",
                                                replyId = "0",
                                                replyName = rt?.name ?: "")
                                            // Tự lưu tin để Worker luôn quét, kể cả user quên bấm Lưu
                                            try { if (!isSaved) bookmarks.toggle(article) } catch (_: Exception) { }
                                        }
                                        draft = ""; replyTo = null
                                    },
                                    enabled = !sending && draft.isNotBlank()
                                ) { Text(if (sending) "Đang gửi…" else if (replyTo != null) "Trả lời" else "Gửi") }
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onLogin),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Text("🔐 Đăng nhập để bình luận với tài khoản BongdaPlus",
                                    modifier = Modifier.padding(14.dp))
                            }
                        }
                    }
                }
                // Tin liên quan
                if (related.isNotEmpty()) {
                    item { SectionHeader("Tin liên quan") }
                    items(related, key = { it.id }) { r ->
                        NewsRowCard(r, saved = false, onClick = { onOpen(r) }, onToggleSave = {})
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            Box(Modifier.padding(pad)) {
                ErrorBox("Không tải được bài viết.", onRetry = { vm.load(article) })
            }
        }
    }
}

@Composable
private fun BlockView(b: ContentBlock, fontScale: Float) {
    val ctx = LocalContext.current
    when (b) {
        is ContentBlock.Paragraph -> Text(
            b.text, modifier = Modifier.padding(16.dp, 6.dp),
            fontSize = (17 * fontScale).sp, lineHeight = (27 * fontScale).sp
        )
        is ContentBlock.Heading -> Text(
            b.text, modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 4.dp),
            fontWeight = FontWeight.Bold, fontSize = (19 * fontScale).sp
        )
        is ContentBlock.Image -> Column(Modifier.padding(8.dp, 8.dp)) {
            AsyncImage(
                b.url, b.caption,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillWidth
            )
            if (!b.caption.isNullOrBlank()) {
                Text(b.caption!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(4.dp, 6.dp, 4.dp, 0.dp))
            }
        }
        is ContentBlock.Video -> {
            Column(Modifier.padding(8.dp, 8.dp)) {
                // Phát ngay trong app (YouTube embed lẫn streaming.bongdaplus.vn)
                InAppVideoPlayer(b.embedUrl)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎬 Video trong bài",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f))
                    if (!b.videoId.isNullOrBlank()) {
                        Text("Mở YouTube ↗",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://www.youtube.com/watch?v=${b.videoId}")))
                                } catch (_: Exception) { }
                            })
                    }
                }
            }
        }
        is ContentBlock.Quote -> Row(Modifier.padding(16.dp, 8.dp)) {
            Box(Modifier.width(4.dp).height(60.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(b.text, fontWeight = FontWeight.SemiBold,
                fontSize = (18 * fontScale).sp, lineHeight = (28 * fontScale).sp)
        }
        is ContentBlock.Bullet -> Row(Modifier.padding(20.dp, 4.dp)) {
            Text("•  ", fontWeight = FontWeight.Bold)
            Text(b.text, fontSize = (17 * fontScale).sp, lineHeight = (26 * fontScale).sp)
        }
    }
}

/** Trình phát video trong app (YouTube embed + streaming.bongdaplus.vn) */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppVideoPlayer(embedUrl: String) {
    val activity = LocalContext.current as? Activity
    var loadError by remember(embedUrl) { mutableStateOf(false) }
    var customView by remember(embedUrl) { mutableStateOf<View?>(null) }
    var customCb by remember(embedUrl) {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }

    fun exitFullscreen() {
        try { customCb?.onCustomViewHidden() } catch (_: Exception) { }
        // onHideCustomView dọn view + hiện system UI
    }

    BackHandler(enabled = customView != null) { exitFullscreen() }

    if (loadError) {
        // Rớt phát trong app: mở ngoài bằng trình duyệt/YouTube
        val ctx = LocalContext.current
        Card(
            modifier = Modifier.fillMaxWidth().clickable {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(embedUrl)))
                } catch (_: Exception) { }
            },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Text("⚠️ Không phát được trong app — bấm để mở ngoài",
                modifier = Modifier.padding(14.dp))
        }
        return
    }

    val html = remember(embedUrl) {
        val safe = embedUrl.replace("\"", "%22")
        "<!DOCTYPE html><html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">" +
            "<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}" +
            "iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>" +
            "</head><body>" +
            "<iframe src=\"$safe\" allow=\"accelerometer; autoplay; clipboard-write; " +
            "encrypted-media; gyroscope; picture-in-picture; fullscreen\" " +
            "allowfullscreen referrerpolicy=\"no-referrer-when-downgrade\"></iframe>" +
            "</body></html>"
    }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView, request: WebResourceRequest, error: WebResourceError
                    ) {
                        if (request.isForMainFrame) loadError = true
                    }
                    @Suppress("DEPRECATION")
                    override fun onReceivedError(
                        view: WebView, errorCode: Int, description: String?, failingUrl: String?
                    ) {
                        loadError = true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(
                        view: View?, callback: CustomViewCallback?
                    ) {
                        if (customView != null) { callback?.onCustomViewHidden(); return }
                        customView = view
                        customCb = callback
                        try {
                            activity?.window?.let { w ->
                                WindowCompat.getInsetsController(w, w.decorView)
                                    .hide(WindowInsetsCompat.Type.systemBars())
                            }
                            view?.let { (activity?.window?.decorView as? ViewGroup)?.addView(it) }
                        } catch (_: Exception) { }
                    }
                    override fun onHideCustomView() {
                        try {
                            (customView?.parent as? ViewGroup)?.removeView(customView)
                            activity?.window?.let { w ->
                                WindowCompat.getInsetsController(w, w.decorView)
                                    .show(WindowInsetsCompat.Type.systemBars())
                            }
                        } catch (_: Exception) { }
                        customView = null
                        customCb = null
                    }
                }
                loadDataWithBaseURL("https://bongdaplus.vn/", html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
        onRelease = { try { it.stopLoading(); it.destroy() } catch (_: Exception) { } }
    )
}

@Composable
private fun CommentCard(c: Comment, fontScale: Float,
                        voted: Int = 0, canVote: Boolean = false,
                        onLike: () -> Unit = {}, onDislike: () -> Unit = {},
                        onReply: () -> Unit = {}) {
    Row(Modifier.padding(12.dp, 8.dp)) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(c.name.firstOrNull()?.uppercase() ?: "B",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name.trim(), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
                if (c.time.isNotBlank()) {
                    Text(" • ${c.time}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(c.text, fontSize = (15 * fontScale).sp,
                lineHeight = (23 * fontScale).sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("👍 ${c.likes}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (voted == 1) FontWeight.Bold else null,
                    color = if (voted == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable(enabled = canVote, onClick = onLike))
                Spacer(Modifier.width(10.dp))
                Text("👎 ${c.dislikes}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (voted == 7) FontWeight.Bold else null,
                    color = if (voted == 7) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable(enabled = canVote, onClick = onDislike))
                if (canVote) {
                    Spacer(Modifier.width(12.dp))
                    Text("↩️ Trả lời",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onReply))
                }
            }
        }
    }
}
