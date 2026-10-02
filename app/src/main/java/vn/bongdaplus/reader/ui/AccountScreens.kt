package vn.bongdaplus.reader.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

// ---------- Tab Tài khoản: hồ sơ + theo dõi + giao diện ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    auth: AuthManager,
    prefs: UiPrefs,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onSaved: () -> Unit,
) {
    val logged by auth.loggedIn.collectAsState(initial = false)
    val email by auth.email.collectAsState(initial = "")
    val follows by auth.followSlugs.collectAsState(initial = emptySet())
    val notify by auth.notifyEnabled.collectAsState(initial = true)
    val notifyCmt by auth.notifyComments.collectAsState(initial = true)
    val theme by prefs.themeMode.collectAsState(initial = "system")
    val scope = rememberCoroutineScope()

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
                ListItem(
                    headlineContent = { Text("Báo bình luận mới") },
                    supportingContent = { Text("Khi bài đã lưu có thêm bình luận trên BongdaPlus") },
                    leadingContent = { Icon(Icons.Default.ChatBubble, null) },
                    trailingContent = {
                        Switch(checked = notifyCmt, onCheckedChange = { scope.launch { auth.setNotifyComments(it) } })
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
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
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
                            if (auth.hasSessionCookie() ||
                                (url.contains("bongdaplus.vn") && !url.contains("Account/Login") && !url.contains("Account/Register"))) {
                                status = "✅ Thành công! Đang lưu…"
                                scope.launch { auth.markLoggedIn(""); onDone() }
                            }
                        }
                    }
                    loadUrl(url)
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
