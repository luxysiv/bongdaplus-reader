package vn.bongdaplus.reader.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

// ---------- Home ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: NewsViewModel,
    auth: AuthManager,
    bookmarks: BookmarkStore,
    onOpen: (Article) -> Unit,
    onLogin: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
) {
    val cat by vm.cat.collectAsState()
    val list by vm.filtered.collectAsState()
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()
    val q by vm.query.collectAsState()
    val logged by auth.loggedIn.collectAsState(initial = false)
    val email by auth.email.collectAsState(initial = "")
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.cookieProvider = { auth.currentCookies() }; vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚽ Bóng Đá Plus", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onBookmarks) { Icon(Icons.Default.Star, "Đã lưu") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Cài đặt") }
                    if (logged) {
                        TextButton(onClick = { scope.launch { auth.logout() } }) {
                            Text("Thoát", maxLines = 1)
                        }
                    } else {
                        TextButton(onClick = onLogin) { Text("Đăng nhập") }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!logged) {
                Card(
                    modifier = Modifier.padding(8.dp).fillMaxWidth().clickable(onClick = onLogin),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        "🔐 Đăng nhập tài khoản BongdaPlus để nhận thông báo cá nhân + đọc bài Premium",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (email.isNotBlank()) {
                Text("👋 Xin chào $email", modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }
            OutlinedTextField(
                value = q, onValueChange = vm::setQuery,
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                placeholder = { Text("Tìm kiếm tin…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )
            LazyRow(modifier = Modifier.padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(CATEGORIES) { c ->
                    FilterChip(
                        selected = cat == c.slug,
                        onClick = { vm.select(c.slug) },
                        label = { Text(c.name) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                Button(onClick = { vm.refresh() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Thử lại") }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { a ->
                    ArticleRow(a,
                        onClick = { onOpen(a) },
                        onSave = { scope.launch { bookmarks.toggle(a) } })
                    Divider()
                }
            }
        }
    }
}

@Composable
fun ArticleRow(a: Article, onClick: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp)) {
        if (!a.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = a.imageUrl, contentDescription = null,
                modifier = Modifier.size(96.dp, 72.dp),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            a.category?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        IconButton(onClick = onSave) { Icon(Icons.Default.Star, "Lưu", tint = MaterialTheme.colorScheme.primary) }
    }
}

// ---------- Detail ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(article: Article, vm: DetailViewModel, auth: AuthManager, onBack: () -> Unit) {
    val detail by vm.detail.collectAsState()
    val loading by vm.loading.collectAsState()
    LaunchedEffect(article.url) {
        vm.cookieProvider = { auth.currentCookies() }
        vm.load(article.url)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Chi tiết", maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading && detail == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(article.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp))
            detail?.let { d ->
                if (!d.author.isNullOrBlank() || !d.publishedAt.isNullOrBlank())
                    Text("${d.author ?: ""} • ${d.publishedAt ?: ""}",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp))
                AndroidView(factory = { c ->
                    WebView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = false
                    }
                }, update = { w ->
                    val html = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"/>
                        <style>body{font-family:sans-serif;font-size:17px;line-height:1.6;padding:0 14px}img{max-width:100%;height:auto}</style>
                        </head><body>${d.bodyHtml}<p style="color:#888">Nguồn: <a href="${article.url}">bongdaplus.vn</a></p></body></html>"""
                    w.loadDataWithBaseURL("https://bongdaplus.vn/", html, "text/html", "UTF-8", null)
                }, modifier = Modifier.fillMaxSize())
            } ?: run {
                // fallback: mở gốc trong WebView
                AndroidView(factory = { c -> WebView(c).apply { settings.javaScriptEnabled = true } },
                    update = { it.loadUrl(article.url) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// ---------- Login (WebView tài khoản thật) ----------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(auth: AuthManager, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Đang mở trang đăng nhập BongdaPlus…") }
    Column(Modifier.fillMaxSize()) {
        Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        AndroidView(factory = { c ->
            WebView(c).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Đăng nhập xong site thường redirect về bongdaplus.vn hoặc member trang chủ
                        if (auth.hasSessionCookie() ||
                            (url.contains("bongdaplus.vn") && !url.contains("Account/Login"))) {
                            status = "✅ Đăng nhập thành công! Đang lưu…"
                            scope.launch {
                                // thử đoán email từ cookie/title
                                auth.markLoggedIn("")
                                onDone()
                            }
                        }
                    }
                }
                loadUrl(AuthManager.LOGIN_URL)
            }
        }, modifier = Modifier.fillMaxSize())
    }
}

// ---------- Bookmarks ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkScreen(store: BookmarkStore, onOpen: (Article) -> Unit, onBack: () -> Unit) {
    val list by store.flow().collectAsState(initial = emptyList())
    Scaffold(topBar = {
        TopAppBar(title = { Text("⭐ Tin đã lưu") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") }
        })
    }) { pad ->
        if (list.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Chưa có tin nào. Bấm ⭐ ở trang chủ để lưu.")
        } else LazyColumn(Modifier.padding(pad)) {
            items(list, key = { it.id }) { a ->
                val scope = rememberCoroutineScope()
                ArticleRow(a, onClick = { onOpen(a) }, onSave = { scope.launch { store.toggle(a) } })
                Divider()
            }
        }
    }
}

// ---------- Settings: follow + notify ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(auth: AuthManager, onBack: () -> Unit) {
    val follows by auth.followSlugs.collectAsState(initial = emptySet())
    val notify by auth.notifyEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("🔔 Theo dõi & Thông báo") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nhận thông báo tin mới", modifier = Modifier.weight(1f))
                Switch(checked = notify, onCheckedChange = { scope.launch { auth.setNotify(it) } })
            }
            Text("Chuyên mục được thông báo (cần đăng nhập để cá nhân hoá):",
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
            CATEGORIES.forEach { c ->
                val on = follows.contains(c.slug)
                Row(Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        auth.setFollow(if (on) follows - c.slug else follows + c.slug)
                    }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(c.name, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text("App quét tin mới ~45 phút/lần (WorkManager), bấm vào thông báo để mở bài. Nguồn tin: bongdaplus.vn — tôn trọng bản quyền, bài đọc hiển thị nguồn.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
