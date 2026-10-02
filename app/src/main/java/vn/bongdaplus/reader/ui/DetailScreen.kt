package vn.bongdaplus.reader.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*
import androidx.compose.material3.*

/**
 * Chi tiết chuẩn báo: toàn bộ bài (hero + tiêu đề + meta + body + tin liên quan)
 * render trong 1 WebView; bấm link bài liên quan mở native detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DetailScreen(
    article: Article,
    auth: AuthManager,
    bookmarks: BookmarkStore,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val vm: DetailViewModel = viewModel()
    val detail by vm.detail.collectAsState()
    val related by vm.related.collectAsState()
    val loading by vm.loading.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedList by bookmarks.flow().collectAsState(initial = emptyList())
    val isSaved = remember(savedList, article.id) { savedList.any { it.id == article.id } }

    LaunchedEffect(article.url) {
        vm.cookieProvider = { auth.currentCookies() }
        vm.load(article)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(catName(article.category)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } },
                actions = {
                    IconButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, article.title)
                            putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.url} (via Bóng Đá Plus Reader)")
                        }
                        ctx.startActivity(Intent.createChooser(i, "Chia sẻ tin"))
                    }) { Icon(Icons.Default.Share, "Chia sẻ") }
                    IconButton(onClick = { scope.launch { bookmarks.toggle(article) } }) {
                        Icon(
                            if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Lưu",
                            tint = if (isSaved) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current
                        )
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (loading && detail == null) {
                LoadingSkeleton(4)
            } else if (detail != null) {
                val html = remember(detail, related) { buildArticleHtml(detail!!, related) }
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                                    if (url.contains("bongdaplus.vn") && url.contains(".html")) {
                                        onOpenUrl(url); return true
                                    }
                                    return false
                                }
                            }
                        }
                    },
                    update = { w ->
                        w.loadDataWithBaseURL("https://bongdaplus.vn/", html, "text/html", "UTF-8", null)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ErrorBox("Không tải được bài viết.", onRetry = { vm.load(article) })
            }
        }
    }
}
