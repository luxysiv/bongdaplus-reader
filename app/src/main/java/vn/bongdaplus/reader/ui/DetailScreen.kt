package vn.bongdaplus.reader.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

/**
 * Trang chi tiết hiện đại kiểu báo điện tử:
 * chip chuyên mục + tiêu đề lớn + sapo + byline tác giả + hero + body + tags +
 * tin liên quan carousel + thanh công cụ dưới (cảm xúc + mở khung bình luận).
 * Bình luận nằm trong bottom-sheet riêng (vừa đọc vừa gửi), không lẫn vào bài.
 * Bài video (/video/...) dùng layout player-first riêng.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    article: Article,
    auth: AuthManager,
    prefs: UiPrefs,
    onBack: () -> Unit,
    onOpen: (Article) -> Unit,
    onLogin: () -> Unit,
    onOpenDisplay: () -> Unit = {},
    autoOpenComments: Boolean = false,
) {
    val vm: DetailViewModel = viewModel()
    val detail by vm.detail.collectAsState()
    val related by vm.related.collectAsState()
    val loading by vm.loading.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val logged by auth.loggedIn.collectAsState(initial = false)
    val fontScale by prefs.fontScale.collectAsState(initial = 1f)
    val readerFont by prefs.readerFont.collectAsState(initial = "serif")
    val lineSpace by prefs.lineSpace.collectAsState(initial = 1f)
    val bodyFont = if (readerFont == "serif") FontFamily.Serif else FontFamily.Default
    var showReaderSheet by remember { mutableStateOf(false) }
    // Đi từ thông báo bình luận -> mở thẳng khung bình luận
    var showComments by remember(autoOpenComments) { mutableStateOf(autoOpenComments) }
    val myEmotion by vm.myEmotion.collectAsState()
    val trackStore = remember(ctx) { CommentTrackStore(ctx.applicationContext) }

    LaunchedEffect(article.url) {
        vm.cookieProvider = { auth.currentCookies() }
        vm.load(article)
        try { trackStore.track(article) } catch (_: Exception) { }
    }

    fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, article.title)
            putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.url}")
        }
        ctx.startActivity(Intent.createChooser(i, "Chia sẻ tin"))
    }

    val cmtCount = detail?.emotion?.comments ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(catName(detail?.article?.category ?: article.category), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } },
                actions = {
                    // Cài đặt đọc báo gom 1 chỗ (Aa) — không còn A-/A+ rải rác
                    IconButton(onClick = { showReaderSheet = true }) {
                        Text("Aa", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { share() }) { Icon(Icons.Default.Share, "Chia sẻ") }
                }
            )
        },
        bottomBar = {
            // Thanh công cụ dưới kiểu app báo: cảm xúc + bình luận + lưu
            if (detail != null) {
                val d = detail!!
                Surface(shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmotionPill("👍", d.emotion.liked, selected = myEmotion == 1) {
                            if (logged) vm.reactArticle(1) else onLogin()
                        }
                        EmotionPill("❤️", d.emotion.heart, selected = myEmotion == 2) {
                            if (logged) vm.reactArticle(2) else onLogin()
                        }
                        EmotionPill("😮", d.emotion.wow, selected = myEmotion == 4) {
                            if (logged) vm.reactArticle(4) else onLogin()
                        }
                        Spacer(Modifier.weight(1f))
                        AssistChip(
                            onClick = { showComments = true },
                            label = { Text("💬 $cmtCount") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        IconButton(onClick = { share() }) {
                            Icon(Icons.Default.Share, "Chia sẻ", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    ) { pad ->
        if (loading && detail == null) {
            val isVideoGuess = article.category == "video" || article.url.contains("/video/")
            Box(Modifier.padding(pad)) { DetailSkeleton(isVideoGuess) }
        } else if (detail != null) {
            val d = detail!!
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val progress by remember {
                derivedStateOf {
                    val total = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(1)
                    val idx = listState.firstVisibleItemIndex +
                        (if (listState.firstVisibleItemScrollOffset > 0) 1 else 0)
                    (idx.toFloat() / total).coerceIn(0f, 1f)
                }
            }
            Box(Modifier.padding(pad).fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    // ===== VIDEO: player-first =====
                    if (d.isVideo) {
                        item {
                            Column(Modifier.padding(12.dp, 12.dp, 12.dp, 0.dp)) {
                                val v = d.blocks.filterIsInstance<ContentBlock.Video>().firstOrNull()
                                if (v != null) {
                                    NativeVideoPlayer(v.embedUrl, v.videoId, d.article.title)
                                } else if (!d.article.imageUrl.isNullOrBlank()) {
                                    AsyncImage(d.article.imageUrl, null,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(16.dp)),
                                        contentScale = ContentScale.Crop)
                                }
                                Spacer(Modifier.height(10.dp))
                                VideoTitleBlock(d, fontScale)
                                BylineRow(d)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        // Mô tả video
                        d.blocks.filterIsInstance<ContentBlock.Paragraph>().forEach { p ->
                            item {
                                Text(p.text, modifier = Modifier.padding(16.dp, 6.dp),
                                    fontFamily = bodyFont,
                                    fontSize = (16 * fontScale).sp,
                                    lineHeight = (26 * fontScale * lineSpace).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        // ===== BÁO CHỮ: header kiểu báo =====
                        item {
                            Column(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp)) {
                                // Hàng chip chuyên mục + giờ
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(catName(d.article.category).uppercase(),
                                            color = Color.White, fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(formatTime(d.publishedAt),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(d.article.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (23 * fontScale).sp,
                                    lineHeight = (31 * fontScale).sp,
                                    letterSpacing = (-0.3).sp)
                                // Sapo in nghiêng kiểu báo
                                if (d.sapo.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Row {
                                        Box(Modifier.width(3.dp)
                                            .heightIn(min = 40.dp)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                                        Spacer(Modifier.width(10.dp))
                                        Text(d.sapo, fontFamily = bodyFont,
                                            fontStyle = FontStyle.Italic,
                                            fontSize = (16 * fontScale).sp,
                                            lineHeight = (25 * fontScale * lineSpace).sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                BylineRow(d)
                            }
                        }
                        // Hero ảnh full kiểu báo
                        if (!d.article.imageUrl.isNullOrBlank()) {
                            item {
                                Column(Modifier.padding(12.dp, 10.dp)) {
                                    AsyncImage(
                                        d.article.imageUrl, null,
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                    Text("Ảnh: BongdaPlus",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally))
                                }
                            }
                        }
                        // Body
                        if (d.blocks.isEmpty()) {
                            item {
                                Text(d.bodyText.ifBlank { "Không tải được nội dung. Mở bài gốc trên web nhé." },
                                    modifier = Modifier.padding(16.dp),
                                    fontFamily = bodyFont,
                                    fontSize = (17 * fontScale).sp,
                                    lineHeight = (27 * fontScale * lineSpace).sp)
                            }
                        } else {
                            items(d.blocks.filter { it !is ContentBlock.Video }) { b ->
                                ModernBlockView(b, fontScale, bodyFont, lineSpace)
                            }
                            // Video nhúng giữa bài (nếu có)
                            val vids = d.blocks.filterIsInstance<ContentBlock.Video>()
                            if (vids.isNotEmpty()) {
                                item { SectionHeader("🎬 Video trong bài") }
                                items(vids) { v ->
                                    Column(Modifier.padding(12.dp, 4.dp)) {
                                        NativeVideoPlayer(v.embedUrl, v.videoId)
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                        // Tags
                        if (d.tags.isNotEmpty()) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    items(d.tags) { t ->
                                        AssistChip(onClick = { }, label = { Text("#$t") })
                                    }
                                }
                            }
                        }
                    }
                    // Nguồn + link gốc
                    item {
                        Card(
                            modifier = Modifier.padding(16.dp, 8.dp).fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Nguồn: Tạp chí Bóng Đá",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("bongdaplus.vn • tôn trọng bản quyền",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                TextButton(onClick = {
                                    try {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW,
                                            android.net.Uri.parse(d.article.url)))
                                    } catch (_: Exception) { }
                                }) { Text("Bài gốc ↗") }
                            }
                        }
                    }
                    // Tin liên quan carousel ngang (hiện đại hơn list dọc cũ)
                    if (related.isNotEmpty()) {
                        item { SectionHeader("Tin liên quan", "Xem thêm") { } }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(related, key = { it.id }) { r ->
                                    RelatedCard(r, onClick = { onOpen(r) })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
        } else {
            Box(Modifier.padding(pad)) {
                ErrorBox("Không tải được bài viết.", onRetry = { vm.load(article) })
            }
        }
        if (showReaderSheet) {
            ReaderQuickSettingsSheet(
                prefs = prefs,
                onOpenFull = { showReaderSheet = false; onOpenDisplay() },
                onDismiss = { showReaderSheet = false }
            )
        }
        if (showComments && detail != null) {
            CommentsBottomSheet(
                vm = vm,
                cmtCount = cmtCount,
                logged = logged,
                fontScale = fontScale,
                bodyFont = bodyFont,
                lineSpace = lineSpace,
                onLogin = { showComments = false; onLogin() },
                onSend = { text, rt ->
                    scope.launch {
                        vm.sendComment(text, article, trackStore,
                            parentId = rt?.id ?: "0",
                            replyId = "0",
                            replyName = rt?.name ?: "")
                    }
                },
                onDismiss = { showComments = false }
            )
        }
    }
}

@Composable
private fun EmotionPill(icon: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$icon $count") },
        modifier = Modifier.padding(end = 4.dp)
    )
}

/**
 * Khung bình luận chuẩn app: bottom-sheet vừa đọc list vừa gửi nhận xét.
 * Mở bằng nút 💬 ở thanh công cụ dưới bài viết.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsBottomSheet(
    vm: DetailViewModel,
    cmtCount: Int,
    logged: Boolean,
    fontScale: Float,
    bodyFont: FontFamily,
    lineSpace: Float,
    onLogin: () -> Unit,
    onSend: (String, Comment?) -> Unit,
    onDismiss: () -> Unit,
) {
    val comments by vm.comments.collectAsState()
    val loadingComments by vm.loadingComments.collectAsState()
    val sending by vm.sending.collectAsState()
    val sendMsg by vm.sendMsg.collectAsState()
    val myVotes by vm.myVotes.collectAsState()
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bình luận ($cmtCount)", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.loadComments() }) {
                    Icon(Icons.Default.Refresh, "Tải lại")
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                if (loadingComments && comments.isEmpty()) {
                    item { LoadingSkeleton(2) }
                } else if (comments.isEmpty()) {
                    item { EmptyState("Chưa có bình luận. Hãy là người đầu tiên!") }
                } else {
                    items(comments, key = { it.id }) { c ->
                        ModernCommentCard(c, fontScale,
                            voted = myVotes[c.id] ?: 0,
                            canVote = logged,
                            bodyFont = bodyFont, lineSpace = lineSpace,
                            onLike = { vm.reactComment(c.id, true) },
                            onDislike = { vm.reactComment(c.id, false) },
                            onReply = { replyTo = c })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(12.dp).imePadding()) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { if (it.length <= 1000) draft = it },
                            placeholder = { Text(if (replyTo != null) "Trả lời… (cần duyệt)" else "Viết bình luận… (cần duyệt)") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { onSend(draft, replyTo); draft = ""; replyTo = null },
                            enabled = !sending && draft.isNotBlank()
                        ) { Icon(Icons.Default.Send, "Gửi") }
                    }
                    sendMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Đã gửi")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp))
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
    }
}

@Composable
private fun VideoTitleBlock(d: ArticleDetail, fontScale: Float) {    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
        Text("VIDEO", color = Color.White, fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(d.article.title, fontWeight = FontWeight.Bold,
        fontSize = (21 * fontScale).sp, lineHeight = (29 * fontScale).sp)
}

@Composable
private fun BylineRow(d: ArticleDetail) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Avatar tác giả thật (ld+json Person) hoặc chữ cái
        if (!d.authorAvatar.isNullOrBlank()) {
            AsyncImage(d.authorAvatar, null,
                modifier = Modifier.size(40.dp).clip(CircleShape),
                contentScale = ContentScale.Crop)
        } else {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Text((d.author?.firstOrNull()?.toString() ?: "B").uppercase(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.author ?: "BongdaPlus", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                listOfNotNull(d.authorRole, formatTime(d.publishedAt))
                    .joinToString(" • ").ifBlank { "Tạp chí Bóng Đá" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTime(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    // ISO 2026-10-01T05:26:13+07:00 -> 01/10/2026 - 05:26 (giống web)
    return try {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(raw)
        if (m != null) "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]} - ${m.groupValues[4]}:${m.groupValues[5]}"
        else raw.take(32)
    } catch (_: Exception) { raw.take(32) }
}

@Composable
private fun ModernBlockView(
    b: ContentBlock, fontScale: Float,
    bodyFont: FontFamily, lineSpace: Float,
) {
    when (b) {
        is ContentBlock.Paragraph -> Text(
            b.text, modifier = Modifier.padding(16.dp, 6.dp),
            fontFamily = bodyFont,
            fontSize = (17 * fontScale).sp, lineHeight = (28 * fontScale * lineSpace).sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        is ContentBlock.Heading -> Row(Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(22.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(b.text, fontWeight = FontWeight.Bold,
                fontSize = (19 * fontScale).sp, lineHeight = (26 * fontScale).sp)
        }
        is ContentBlock.Image -> Column(Modifier.padding(12.dp, 8.dp)) {
            AsyncImage(
                b.url, b.caption,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.FillWidth
            )
            if (!b.caption.isNullOrBlank()) {
                Text(b.caption!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 0.dp)
                        .align(Alignment.CenterHorizontally))
            }
        }
        is ContentBlock.Video -> Column(Modifier.padding(12.dp, 4.dp)) {
            NativeVideoPlayer(b.embedUrl, b.videoId)
        }
        is ContentBlock.Quote -> Card(
            modifier = Modifier.padding(16.dp, 8.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.padding(14.dp)) {
                Text("“", fontSize = 32.sp, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(b.text, fontWeight = FontWeight.SemiBold, fontFamily = bodyFont,
                    fontStyle = FontStyle.Italic,
                    fontSize = (17 * fontScale).sp,
                    lineHeight = (27 * fontScale * lineSpace).sp)
            }
        }
        is ContentBlock.Bullet -> Row(Modifier.padding(20.dp, 4.dp),
            verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 9.dp).size(7.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(10.dp))
            Text(b.text, fontFamily = bodyFont,
                fontSize = (17 * fontScale).sp, lineHeight = (27 * fontScale * lineSpace).sp)
        }
    }
}

@Composable
private fun RelatedCard(a: Article, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(240.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            if (!a.imageUrl.isNullOrBlank()) {
                AsyncImage(a.imageUrl, null,
                    modifier = Modifier.fillMaxWidth().height(130.dp),
                    contentScale = ContentScale.Crop)
            }
            Column(Modifier.padding(10.dp)) {
                Text(catName(a.category).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ModernCommentCard(
    c: Comment, fontScale: Float,
    voted: Int = 0, canVote: Boolean = false,
    bodyFont: FontFamily = FontFamily.Serif, lineSpace: Float = 1f,
    onLike: () -> Unit = {}, onDislike: () -> Unit = {},
    onReply: () -> Unit = {},
) {
    Card(
        modifier = Modifier.padding(12.dp, 6.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Text(c.name.firstOrNull()?.uppercase() ?: "B",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name.trim(), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.time.isNotBlank()) {
                        Text(" • ${c.time}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(c.text, fontFamily = bodyFont, fontSize = (15 * fontScale).sp,
                    lineHeight = (23 * fontScale * lineSpace).sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = voted == 1, onClick = onLike, enabled = canVote,
                        label = { Text("👍 ${c.likes}") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = voted == 7, onClick = onDislike, enabled = canVote,
                        label = { Text("👎 ${c.dislikes}") })
                    if (canVote) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onReply) { Text("Trả lời") }
                    }
                }
            }
        }
    }
}

/**
 * Skeleton mô phỏng đúng khung bài sẽ hiện (khớp padding/cỡ từng khối):
 * chip chuyên mục + giờ, tiêu đề 2 dòng, sapo vạch màu, byline avatar+tên,
 * hero + caption, rồi thân bài (đoạn/tiêu đề/ảnh). Bài video dùng mẫu player.
 */
@Composable
private fun DetailSkeleton(isVideo: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        if (isVideo) {
            // Player 16:9
            Box(Modifier.padding(12.dp).fillMaxWidth().aspectRatio(16f / 9f).shimmer())
            // Chip VIDEO + tiêu đề
            Box(Modifier.padding(start = 16.dp).width(64.dp).height(22.dp).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(24.dp).shimmer())
            Spacer(Modifier.height(6.dp))
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth(0.75f).height(24.dp).shimmer())
        } else {
            Column(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp)) {
                // Hàng chip chuyên mục + giờ
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(92.dp).height(22.dp).shimmer())
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(130.dp).height(14.dp).shimmer())
                }
                Spacer(Modifier.height(10.dp))
                // Tiêu đề lớn 2 dòng
                Box(Modifier.fillMaxWidth().height(26.dp).shimmer())
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.9f).height(26.dp).shimmer())
                Spacer(Modifier.height(10.dp))
                // Sapo: vạch màu + 3 dòng thụt vào
                Row {
                    Box(Modifier.width(3.dp).height(64.dp).shimmer())
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(15.dp).shimmer())
                        Spacer(Modifier.height(7.dp))
                        Box(Modifier.fillMaxWidth().height(15.dp).shimmer())
                        Spacer(Modifier.height(7.dp))
                        Box(Modifier.fillMaxWidth(0.6f).height(15.dp).shimmer())
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Byline: avatar tròn + tên + giờ
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(0.45f).height(15.dp).shimmer())
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(0.65f).height(12.dp).shimmer())
                    }
                }
            }
            // Hero + caption
            Column(Modifier.padding(12.dp, 10.dp)) {
                Box(Modifier.fillMaxWidth().height(200.dp).shimmer())
                Spacer(Modifier.height(6.dp))
                Box(Modifier.align(Alignment.CenterHorizontally).width(120.dp).height(12.dp).shimmer())
            }
        }
        // Thân bài: cụm đoạn văn + 1 tiêu đề mục + 1 ảnh + đoạn văn
        Column(Modifier.padding(horizontal = 16.dp)) {
            repeat(3) {
                Box(Modifier.fillMaxWidth().height(14.dp).shimmer())
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.fillMaxWidth(0.55f).height(20.dp).shimmer())
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.padding(12.dp, 4.dp).fillMaxWidth().height(160.dp).shimmer())
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            repeat(3) {
                Box(Modifier.fillMaxWidth(if (it < 2) 1f else 0.8f).height(14.dp).shimmer())
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
