package vn.bongdaplus.reader.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*
import vn.bongdaplus.reader.notify.NotifyHelper

// ---------- Chẩn đoán phiên đăng nhập (không lộ giá trị cookie) ----------

private suspend fun runSessionDiag(auth: AuthManager): String {
    val sb = StringBuilder()
    try {
        val siteCk = auth.cookieNamesOf("https://bongdaplus.vn")
        val memberCk = auth.cookieNamesOf("https://member.bongdaplus.vn")
        sb.appendLine("Cookie site (${siteCk.size}): " +
            (siteCk.sorted().take(25).joinToString(", ").ifBlank { "(trống)" }))
        sb.appendLine("Cookie member (${memberCk.size}): " +
            (memberCk.sorted().take(25).joinToString(", ").ifBlank { "(trống)" }))
        sb.appendLine("HTTP: OkHttp + cookie WebView login")
        // Trang chủ site: uid tĩnh + số dòng lstnoti + số tin
        val home = try { Http.get("https://bongdaplus.vn/") } catch (_: Exception) { null }
        if (home == null) sb.appendLine("Trang chủ site: KHÔNG tải được")
        else {
            val uid = Regex("id=\"txtUserid\" value=\"([^\"]*)\"").find(home.html)
                ?.groupValues?.get(1).orEmpty()
            val doc = try { org.jsoup.Jsoup.parse(home.html) } catch (_: Exception) { null }
            sb.appendLine("Trang chủ site: userid=" + uid.ifBlank { "(trống)" } +
                ", lstnoti=" + (doc?.select("div#lstnoti li.news")?.size ?: "?") +
                ", tin=" + (doc?.select("div.news")?.size ?: "?"))
        }
        // Dashboard member: boards + dòng bình luận
        val dash = try {
            Http.get("https://member.bongdaplus.vn/Identity/Account/Manage/DashBoard")
        } catch (_: Exception) { null }
        if (dash == null) sb.appendLine("Dashboard member: KHÔNG tải được")
        else if (dash.bouncedToLogin()) sb.appendLine("Dashboard member: BỊ ĐÁ VỀ LOGIN (thiếu phiên member)")
        else {
            val doc = try { org.jsoup.Jsoup.parse(dash.html) } catch (_: Exception) { null }
            val caps = doc?.select("div.brd-cap")
                ?.map { it.text().trim().replace(Regex("\\s+"), " ").take(40) }
                .orEmpty()
            sb.appendLine("Dashboard boards: " + (caps.ifEmpty { listOf("(không thấy)") }.joinToString(" ## ")))
            sb.appendLine("Dashboard dòng bình luận: " +
                (doc?.select("ul.news-lst li.news")?.size ?: "?"))
        }
    } catch (e: Exception) {
        sb.appendLine("Lỗi chẩn đoán: " + (e.message?.take(120) ?: "?"))
    }
    return sb.toString()
}

// ---------- Tab Tài khoản: hồ sơ + theo dõi + giao diện ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    auth: AuthManager,
    prefs: UiPrefs,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onSaved: () -> Unit,
    onOpenArticle: (Article) -> Unit = {},
    onNotifs: () -> Unit = {},
) {
    val logged by auth.loggedIn.collectAsState(initial = false)
    val email by auth.email.collectAsState(initial = "")
    val follows by auth.followSlugs.collectAsState(initial = emptySet())
    val notify by auth.notifyEnabled.collectAsState(initial = true)
    val notifyCmt by auth.notifyComments.collectAsState(initial = true)
    val theme by prefs.themeMode.collectAsState(initial = "system")
    val dynamic by prefs.dynamicColor.collectAsState(initial = true)
    val readerFont by prefs.readerFont.collectAsState(initial = "serif")
    val fontScalePref by prefs.fontScale.collectAsState(initial = 1f)
    val lineSpacePref by prefs.lineSpace.collectAsState(initial = 1f)
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val trackStore = remember(ctx) { CommentTrackStore(ctx.applicationContext) }
    var myArticles by remember { mutableStateOf<List<Article>>(emptyList()) }
    // Fallback local: CHỈ bài đã gửi bình luận trong app (kèm text), không phải bài đã đọc
    var mySent by remember { mutableStateOf<List<Pair<Article, String>>>(emptyList()) }
    var myCount by remember { mutableStateOf(0) }
    // Lịch sử thật từ Dashboard member (board "Bài mới bình luận")
    var dashMine by remember { mutableStateOf<List<MyCommented>>(emptyList()) }
    // Chẩn đoán phiên
    var showDiag by remember { mutableStateOf(false) }
    var diagText by remember { mutableStateOf("Chưa chạy.") }
    var diagRunning by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(logged) {
        try {
            myArticles = trackStore.tracked().take(10)
            // Chỉ giữ bài có text bình luận đã gửi (bài mới mở đọc không tính)
            val sent = mutableListOf<Pair<Article, String>>()
            for (a in myArticles) {
                val t = try { trackStore.myTexts(a.id).firstOrNull().orEmpty() }
                catch (_: Exception) { "" }
                if (t.isNotBlank()) sent += a to t
                if (sent.size >= 5) break
            }
            mySent = sent
            myCount = myArticles.sumOf { trackStore.myTexts(it.id).size }
        } catch (_: Exception) { }
        // Ưu tiên dữ liệu Dashboard thật khi đã login (cookie gộp cả member domain)
        try {
            if (logged) dashMine = BongDaPlusScraper.fetchMyCommented(auth.currentCookies()).take(5)
        } catch (_: Exception) { }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Tài khoản", fontWeight = FontWeight.Bold) })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            // Hồ sơ
            item {
                Card(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        (email.firstOrNull()?.toString() ?: "B").uppercase(),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (logged) (email.ifBlank { "Thành viên BongdaPlus" }) else "Khách",
                                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (logged) "Đã đăng nhập • nhận tin cá nhân"
                                else "Đăng nhập để nhận thông báo + đọc Premium",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (logged) {
                            TextButton(onClick = { scope.launch { auth.logout() } }) { Text("Thoát") }
                        } else {
                            Column(horizontalAlignment = Alignment.End) {
                                Button(onClick = onLogin) { Text("Đăng nhập") }
                                TextButton(onClick = onRegister) { Text("Tạo tài khoản") }
                            }
                        }
                    }
                }
            }
            // Lối tắt
            item {
                ListItem(
                    headlineContent = { Text("Tin đã lưu") },
                    leadingContent = { Icon(Icons.Default.Bookmark, null) },
                    modifier = Modifier.clickable(onClick = onSaved)
                )
                HorizontalDivider()
            }
            // Thông báo member (lịch sử thật từ div#lstnoti)
            item {
                ListItem(
                    headlineContent = { Text("Thông báo bình luận") },
                    supportingContent = { Text(if (logged) "Ai thích / không thích bình luận của bạn • bấm để xem" else "Đăng nhập để xem lịch sử thông báo") },
                    leadingContent = { Icon(Icons.Default.Notifications, null) },
                    modifier = Modifier.clickable(onClick = onNotifs)
                )
                HorizontalDivider()
            }
            // Chẩn đoán phiên đăng nhập
            item {
                ListItem(
                    headlineContent = { Text("Kiểm tra phiên đăng nhập") },
                    supportingContent = { Text("Bình luận báo thiếu login thì bấm để xem kẹt ở đâu") },
                    leadingContent = { Icon(Icons.Default.BugReport, null) },
                    modifier = Modifier.clickable {
                        showDiag = true
                        if (!diagRunning) {
                            diagRunning = true
                            diagText = "Đang kiểm tra…"
                            scope.launch {
                                diagText = runSessionDiag(auth)
                                diagRunning = false
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
            // Bình luận của tôi (dữ liệu thật từ Dashboard member, fallback local)
            item {
                val dashN = dashMine.size
                ListItem(
                    headlineContent = { Text(if (dashN > 0) "Bình luận của tôi ($dashN bài mới nhất)" else "Bình luận của tôi (${mySent.size} bài${if (myCount > 0) ", $myCount lượt gửi" else ""})") },
                    supportingContent = { Text(if (logged) "Theo Dashboard member • bấm để mở đúng comment" else "Đăng nhập rồi bình luận, bài sẽ tự hiện ở đây") },
                    leadingContent = { Icon(Icons.Default.ChatBubble, null) }
                )
                if (dashMine.isNotEmpty()) {
                    dashMine.forEach { m ->
                        ListItem(
                            headlineContent = { Text(m.article.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text("“${m.myText.take(80)}”${if (m.time.isNotBlank()) " • ${m.time}" else ""}") },
                            leadingContent = { Icon(Icons.Default.Comment, null) },
                            modifier = Modifier.clickable { onOpenArticle(m.article) }
                        )
                    }
                } else if (mySent.isEmpty()) {
                    Text("Chưa có bài nào. Mở tin rồi gửi bình luận nhé.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
                } else {
                    mySent.forEach { (a, t) ->
                        ListItem(
                            headlineContent = { Text(a.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text("“${t.take(80)}”") },
                            leadingContent = { Icon(Icons.Default.Comment, null) },
                            modifier = Modifier.clickable { onOpenArticle(a) }
                        )
                    }
                }
                HorizontalDivider()
            }
            // Thông báo tin mới
            item {
                ListItem(
                    headlineContent = { Text("Nhận thông báo tin mới") },
                    supportingContent = { Text("Quét ~45 phút/lần, theo chuyên mục bạn chọn") },
                    leadingContent = { Icon(Icons.Default.Notifications, null) },
                    trailingContent = {
                        Switch(checked = notify, onCheckedChange = { scope.launch { auth.setNotify(it) } })
                    }
                )
                HorizontalDivider()
            }
            // Thông báo bình luận
            item {
                var checking by remember { mutableStateOf(false) }
                var checkMsg by remember { mutableStateOf<String?>(null) }
                ListItem(
                    headlineContent = { Text("Báo bình luận mới") },
                    supportingContent = {
                        Text(checkMsg
                            ?: "Bình luận mới + trả lời + 👍/👎 tăng ở bài bạn đã bình luận/lưu")
                    },
                    leadingContent = { Icon(Icons.Default.ChatBubble, null) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    if (!checking && logged) {
                                        checking = true
                                        checkMsg = "Đang kiểm tra…"
                                        scope.launch {
                                            checkMsg = try {
                                                NotifyHelper.checkMemberNotifsNow(ctx)
                                            } catch (_: Exception) { "Lỗi, thử lại sau." }
                                            checking = false
                                        }
                                    }
                                },
                                enabled = logged && !checking
                            ) { Text(if (checking) "…" else "Kiểm tra") }
                            Switch(checked = notifyCmt, onCheckedChange = { scope.launch { auth.setNotifyComments(it) } })
                        }
                    }
                )
                HorizontalDivider()
            }
            // Theo dõi chuyên mục
            item {
                Text("Chuyên mục theo dõi", fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(CATEGORIES) { c ->
                        val on = follows.contains(c.slug)
                        FilterChip(
                            selected = on,
                            onClick = {
                                scope.launch {
                                    auth.setFollow(if (on) follows - c.slug else follows + c.slug)
                                }
                            },
                            label = { Text(c.name) },
                            leadingIcon = if (on) ({ Icon(Icons.Default.Check, null) }) else null
                        )
                    }
                }
                HorizontalDivider()
            }
            // Giao diện
            item {
                val supportDynamic = android.os.Build.VERSION.SDK_INT >= 31
                Text("Giao diện", fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("system" to "Hệ thống", "light" to "Sáng", "dark" to "Tối").forEach { (v, label) ->
                        FilterChip(
                            selected = theme == v,
                            onClick = { scope.launch { prefs.setThemeMode(v) } },
                            label = { Text(label) }
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("Màu động theo hình nền") },
                    supportingContent = { Text(if (supportDynamic) "Material You (Android 12+)" else "Cần Android 12 trở lên — đang dùng xanh BongdaPlus") },
                    trailingContent = {
                        Switch(
                            checked = dynamic && supportDynamic,
                            enabled = supportDynamic,
                            onCheckedChange = { scope.launch { prefs.setDynamicColor(it) } }
                        )
                    }
                )
                Text("Font bài đọc", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 0.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = readerFont == "sans",
                        onClick = { scope.launch { prefs.setReaderFont("sans") } },
                        label = { Text("Không chân") }
                    )
                    FilterChip(
                        selected = readerFont == "serif",
                        onClick = { scope.launch { prefs.setReaderFont("serif") } },
                        label = { Text("Có chân", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif) }
                    )
                }
                var fontTmp by remember(fontScalePref) { mutableStateOf(fontScalePref) }
                ListItem(
                    headlineContent = { Text("Cỡ chữ: ${"%.0f".format(fontTmp * 100)}%") },
                    supportingContent = {
                        Slider(
                            value = fontTmp, onValueChange = { fontTmp = it },
                            onValueChangeFinished = { scope.launch { prefs.setFontScale(fontTmp) } },
                            valueRange = 0.85f..1.3f, steps = 8
                        )
                    }
                )
                var lineTmp by remember(lineSpacePref) { mutableStateOf(lineSpacePref) }
                ListItem(
                    headlineContent = { Text("Giãn dòng: ${"%.0f".format(lineTmp * 100)}%") },
                    supportingContent = {
                        Slider(
                            value = lineTmp, onValueChange = { lineTmp = it },
                            onValueChangeFinished = { scope.launch { prefs.setLineSpace(lineTmp) } },
                            valueRange = 1f..1.6f, steps = 5
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }
            // Giới thiệu
            item {
                ListItem(
                    headlineContent = { Text("Về ứng dụng") },
                    supportingContent = { Text("Bóng Đá Plus Reader 1.0 • Nguồn tin: bongdaplus.vn") },
                    leadingContent = { Icon(Icons.Default.Info, null) }
                )
            }
        }
        if (showDiag) {
            AlertDialog(
                onDismissRequest = { showDiag = false },
                title = { Text("Chẩn đoán phiên") },
                text = {
                    Text(diagText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(diagText))
                    }) { Text("Sao chép") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            if (!diagRunning) {
                                diagRunning = true
                                diagText = "Đang đồng bộ phiên site…"
                                scope.launch {
                                    BongDaPlusScraper.syncSiteSession()
                                    diagText = runSessionDiag(auth)
                                    diagRunning = false
                                }
                            }
                        }) { Text("Đồng bộ") }
                        TextButton(onClick = { showDiag = false }) { Text("Đóng") }
                    }
                }
            )
        }
    }
}

// ---------- Lịch sử thông báo member (div#lstnoti thật) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberNotifsScreen(
    auth: AuthManager,
    onBack: () -> Unit,
    onOpen: (Article) -> Unit,
    onLogin: () -> Unit,
) {
    val vm: NotifViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val items by vm.items.collectAsState()
    val mine by vm.mine.collectAsState()
    val loading by vm.loading.collectAsState()
    val logged by auth.loggedIn.collectAsState(initial = false)
    LaunchedEffect(logged) {
        vm.cookieProvider = { auth.currentCookies() }
        vm.load()
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Thông báo", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } },
            actions = { IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "Tải lại") } }
        )
    }) { pad ->
        if (!logged) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("🔔 Đăng nhập để xem ai đã thích / không thích bình luận của bạn.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onLogin) { Text("Đăng nhập") }
                }
            }
        } else if (loading && items.isEmpty() && mine.isEmpty()) {
            Box(Modifier.padding(pad)) { LoadingSkeleton(5) }
        } else if (items.isEmpty() && mine.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Chưa có thông báo nào. Bình luận được thích sẽ hiện ở đây.")
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                // Board "Bài mới bình luận" từ Dashboard member: bài + comment của mình + giờ
                if (mine.isNotEmpty()) {
                    item {
                        Text("💬 Bài mới bình luận",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    }
                    items(mine, key = { it.commentUrl }) { m ->
                        ListItem(
                            headlineContent = { Text(m.article.title, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text("“${m.myText}”${if (m.time.isNotBlank()) " • ${m.time}" else ""}") },
                            leadingContent = {
                                Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center) { Text("💬") }
                            },
                            modifier = Modifier.clickable { onOpen(m.article) }
                        )
                        HorizontalDivider()
                    }
                }
                // Lịch sử div#lstnoti: ai thích / không thích bình luận của bạn
                if (items.isNotEmpty()) {
                    item {
                        Text("🔔 Ai đã thích bình luận của bạn",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    }
                    items(items, key = { it.key }) { n ->
                        ListItem(
                            headlineContent = { Text(n.text, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { n.time.ifBlank { null }?.let { Text(it) } },
                            leadingContent = {
                                Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (n.action == "không thích") MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center) {
                                    Text(if (n.action == "không thích") "👎" else if (n.action == "trả lời") "↩️" else "👍")
                                }
                            },
                            modifier = Modifier.clickable {
                                val a = Article(BongDaPlusScraper.idFromUrl(n.url), n.text.take(80), n.url)
                                onOpen(a)
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ---------- Đăng nhập / Đăng ký qua WebView (tài khoản thật) ----------

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(auth: AuthManager, onBack: () -> Unit, onDone: () -> Unit) {
    AuthWebViewScreen(
        url = AuthManager.LOGIN_URL, title = "Đăng nhập",
        auth = auth, onBack = onBack, onDone = onDone
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RegisterScreen(auth: AuthManager, onBack: () -> Unit, onDone: () -> Unit) {
    AuthWebViewScreen(
        url = AuthManager.REGISTER_URL, title = "Tạo tài khoản",
        auth = auth, onBack = onBack, onDone = onDone
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AuthWebViewScreen(
    url: String, title: String, auth: AuthManager,
    onBack: () -> Unit, onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember(url) { mutableStateOf("Đang mở $title BongdaPlus…") }
    // CookieManager là singleton dùng chung toàn app (mọi màn hình/WebView/Jsoup
    // đều đọc cùng 1 kho), nhưng phiên member và phiên site là 2 domain khác nhau.
    // Trang chủ nhúng 2 iframe handshake LoginFromBongdaplus: sau khi login member,
    // mở trang chủ MỘT lần rồi kiên nhẫn poll (không load lại làm đứt chuỗi).
    // Nhận diện bằng DOM: input#txtUserid, form Logout, "Xin chào".
    var homeLoaded by remember(url) { mutableStateOf(false) }
    var pollCount by remember(url) { mutableStateOf(0) }
    var finished by remember(url) { mutableStateOf(false) }
    var webView by remember(url) { mutableStateOf<WebView?>(null) }

    fun finishSuccess(name: String) {
        if (finished) return
        finished = true
        status = "✅ Thành công" +
            (if (name.isNotBlank()) " ($name)" else "") + "! Đang lưu…"
        scope.launch { auth.markLoggedIn(name); onDone() }
    }

    fun probeLogin(view: WebView, pageUrl: String) {
        // uid|fullname|hello|hasLogout — input#txtUserid có giá trị khi đã login
        // (cả trang member lẫn trang bongdaplus.vn đều render input này)
        view.evaluateJavascript(
            "(function(){try{" +
                "var u=document.getElementById('txtUserid');u=u&&u.value||'';" +
                "var n=document.getElementById('txtFullname');n=n&&n.value||'';" +
                "var lo=!!document.querySelector('form[action*=\"Logout\"],a[href*=\"Logout\"]');" +
                "var hello='';try{var m=(document.body?document.body.innerText:'').match(/Xin chào\\s*([^\\n]{1,40})/);if(m)hello=m[1].trim();}catch(e){}" +
                "return u+'|||'+n+'|||'+hello+'|||'+(lo?'1':'0');" +
                "}catch(e){return '|||'.concat('|||').concat('|||0');}})()"
        ) { v ->
            if (finished) return@evaluateJavascript
            auth.flushCookies()
            val raw = v?.trim().orEmpty().removeSurrounding("\"")
                .replace("\\\"", "\"").replace("\\\\", "\\")
            val p = raw.split("|||")
            val uid = p.getOrNull(0).orEmpty().trim()
            val full = p.getOrNull(1).orEmpty().trim()
            val hello = p.getOrNull(2).orEmpty().trim()
            val hasLogout = p.getOrNull(3) == "1"
            val nameGuess = full.ifBlank { hello }
            val domLogged = uid.isNotBlank() || hasLogout
            val cookieLogged = auth.hasMemberCookie() || auth.hasSiteCookie()
            val isMemberPage = pageUrl.contains("member.bongdaplus.vn")
            // Phiên site (uid trên trang bongdaplus.vn / cookie site) mới gửi bình luận được.
            val siteOk = !isMemberPage && (uid.isNotBlank() || hasLogout || auth.hasSiteCookie())
            // Hiện trạng phiên để user báo lỗi chính xác: member? site?
            val sessInfo = "[member:${if (auth.hasMemberCookie()) "có" else "không"}" +
                " site:${if (auth.hasSiteCookie()) "có" else "không"}]"
            when {
                siteOk -> finishSuccess(nameGuess)
                (domLogged || cookieLogged) && isMemberPage && !homeLoaded -> {
                    homeLoaded = true
                    pollCount = 0
                    status = "Đã đăng nhập" +
                        (if (nameGuess.isNotBlank()) " ($nameGuess)" else "") +
                        " $sessInfo, đang đồng bộ sang BongdaPlus…"
                    view.loadUrl(AuthManager.HOME)
                }
                domLogged || cookieLogged -> {
                    // Đang ở trang site: cho iframe handshake thời gian (poll tối đa 5 lần),
                    // tuyệt đối không load lại làm đứt chuỗi.
                    if (pollCount < 5) {
                        pollCount++
                        status = "Đang đồng bộ phiên $sessInfo ($pollCount/5)…"
                        view.postDelayed({ if (!finished) probeLogin(view, pageUrl) }, 2000)
                    } else finishSuccess(nameGuess)
                }
                else -> status = "Nhập Email + Mật khẩu (hoặc Google/Apple) để tiếp tục…"
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } },
            actions = {
                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Tải lại") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            AndroidView(factory = { c ->
                WebView(c).apply {
                    webView = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    // Cho iframe SSO member→site đọc phiên chéo domain
                    try { cm.setAcceptThirdPartyCookies(this, true) } catch (_: Exception) { }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            auth.flushCookies()
                            if (finished) return
                            // Đợi JS render xong rồi mới đọc DOM id/token
                            view.postDelayed({ if (!finished) probeLogin(view, url) }, 800)
                        }
                    }
                    loadUrl(url)
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
