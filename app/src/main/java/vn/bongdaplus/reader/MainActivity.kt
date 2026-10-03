package vn.bongdaplus.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import vn.bongdaplus.reader.data.*
import vn.bongdaplus.reader.ui.*
import vn.bongdaplus.reader.ui.theme.NewsTheme
import java.net.URLDecoder
import java.net.URLEncoder

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** Deep link mở bài viết từ thông báo (kèm cờ mở thẳng khung bình luận). */
private data class DeepLink(val url: String, val comments: Boolean)

private fun parseDeepLink(intent: android.content.Intent?): DeepLink? {
    if (intent == null) return null
    // 1) Extra từ PendingIntent của app
    intent.getStringExtra("open_url")?.takeIf { it.isNotBlank() }?.let { url ->
        return DeepLink(url, intent.getBooleanExtra("open_comments", false))
    }
    // 2) Deep link scheme bongdaplus://article?url=...(&comments=1)
    try {
        val d = intent.data
        if (d != null && d.scheme == "bongdaplus") {
            d.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { url ->
                return DeepLink(url, d.getQueryParameter("comments") == "1")
            }
        }
    } catch (_: Exception) { }
    return null
}
private val TABS = listOf(
    Tab("home", "Trang chủ", Icons.Default.Home),
    Tab("explore", "Chuyên mục", Icons.Default.List),
    Tab("scores", "Tỉ số", Icons.Default.SportsSoccer),
    Tab("account", "Tài khoản", Icons.Default.Person),
)

class MainActivity : ComponentActivity() {
    // Android 13+: phải xin quyền push lúc chạy, không là Worker bắn lên cũng bị chặn lặng lẽ
    private val notifPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    /** Deep link từ thông báo (mở khi app đang chạy qua onNewIntent). */
    private val _deepLink = kotlinx.coroutines.flow.MutableStateFlow<DeepLink?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _deepLink.value = parseDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_deepLink.value == null) _deepLink.value = parseDeepLink(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try { notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { }
        }
        // Lên lịch quét tin + thông báo bình luận nền
        try { vn.bongdaplus.reader.notify.NotifyHelper.schedule(this) } catch (_: Exception) { }
        setContent {
            val appCtx = this
            val auth = remember { AuthManager(appCtx) }
            val prefs = remember { UiPrefs(appCtx) }
            val themeMode by prefs.themeMode.collectAsState(initial = "system")
            val dynamic by prefs.dynamicColor.collectAsState(initial = true)
            val logged by auth.loggedIn.collectAsState(initial = false)

            // Đã login mà thiếu phiên site: đồng bộ ngầm 1 lần mỗi phiên mở app
            // (OkHttp HTTP, không WebView) để bình luận/vote khỏi báo thiếu login.
            LaunchedEffect(logged) {
                if (logged) {
                    try {
                        if (!auth.hasSiteCookie()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                BongDaPlusScraper.syncSiteSession()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            NewsTheme(mode = themeMode, dynamic = dynamic) {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                val showBar = route in TABS.map { it.route }

                // Mở từ notification / deep link (cả khi app đang chạy).
                // Thông báo bình luận mang cờ open_comments -> mở thẳng khung bình luận.
                val deep by _deepLink.collectAsState()
                LaunchedEffect(deep) {
                    deep?.let { d ->
                        val a = Article(BongDaPlusScraper.idFromUrl(d.url), "Tin mới", d.url)
                        ArticleCache.put(a)
                        nav.navigate(
                            "detail/${a.id}?url=${URLEncoder.encode(d.url, "UTF-8")}" +
                                if (d.comments) "&comments=1" else ""
                        )
                        _deepLink.value = null
                    }
                }

                fun openArticle(a: Article) {
                    ArticleCache.put(a)
                    nav.navigate("detail/${a.id}?url=${URLEncoder.encode(a.url, "UTF-8")}")
                }

                Scaffold(
                    bottomBar = {
                        if (showBar) {
                            NavigationBar {
                                TABS.forEach { t ->
                                    NavigationBarItem(
                                        selected = route == t.route,
                                        onClick = {
                                            nav.navigate(t.route) {
                                                popUpTo("home"); launchSingleTop = true
                                            }
                                        },
                                        icon = { Icon(t.icon, t.label) },
                                        label = { Text(t.label) }
                                    )
                                }
                            }
                        }
                    }
                ) { pad ->
                    NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
                        composable("home") {
                            HomeScreen(auth,
                                onOpen = ::openArticle,
                                onSearch = { nav.navigate("search") },
                                onFeed = { nav.navigate("feed/$it") },
                                onLogin = { nav.navigate("login") })
                        }
                        composable("explore") {
                            ExploreScreen(onFeed = { nav.navigate("feed/$it") })
                        }
                        composable("scores") {
                            ScoresScreen()
                        }
                        composable(
                            "feed/{slug}",
                            arguments = listOf(navArgument("slug") { type = NavType.StringType })
                        ) { e ->
                            FeedScreen(
                                slug = e.arguments?.getString("slug") ?: "tin-moi",
                                auth,
                                onOpen = ::openArticle,
                                onBack = { nav.popBackStack() })
                        }
                        composable("search") {
                            SearchScreen(auth,
                                onOpen = ::openArticle,
                                onBack = { nav.popBackStack() })
                        }
                        composable(
                            "detail/{id}?url={url}&comments={comments}",
                            arguments = listOf(
                                navArgument("id") { type = NavType.StringType },
                                navArgument("url") { type = NavType.StringType },
                                navArgument("comments") { type = NavType.StringType; defaultValue = "0" }
                            )
                        ) { e ->
                            val id = e.arguments?.getString("id") ?: ""
                            val url = URLDecoder.decode(e.arguments?.getString("url") ?: "", "UTF-8")
                            val cached = ArticleCache.get(id)
                            val article = cached?.takeIf { it.url == url }
                                ?: Article(BongDaPlusScraper.idFromUrl(url), "Bài viết", url)
                            DetailScreen(article, auth, prefs,
                                onBack = { nav.popBackStack() },
                                onOpen = ::openArticle,
                                onLogin = { nav.navigate("login") },
                                onOpenDisplay = { nav.navigate("display") },
                                autoOpenComments = e.arguments?.getString("comments") == "1")
                        }
                        composable("login") {
                            NativeLoginScreen(auth,
                                onBack = { nav.popBackStack() },
                                onDone = { nav.popBackStack() },
                                onOAuth = { nav.navigate("login_web") },
                                onRegister = { nav.navigate("register") })
                        }
                        // Google/Apple OAuth bắt buộc trình duyệt nhúng (trình duyệt
                        // ngoài không trả cookie về app được) nên giữ 1 màn WebView
                        // cho riêng luồng này. Email/đăng ký đã native hoàn toàn.
                        composable("login_web") {
                            LoginScreen(auth,
                                onBack = { nav.popBackStack() },
                                onDone = { nav.popBackStack() })
                        }
                        composable("register") {
                            NativeRegisterScreen(auth,
                                onBack = { nav.popBackStack() },
                                onDone = { nav.popBackStack() },
                                onLogin = { nav.navigate("login") })
                        }
                        composable("account") {
                            AccountScreen(auth, prefs,
                                onLogin = { nav.navigate("login") },
                                onRegister = { nav.navigate("register") },
                                onOpenArticle = ::openArticle,
                                onNotifs = { nav.navigate("notifs") },
                                onDisplay = { nav.navigate("display") })
                        }
                        composable("display") {
                            DisplaySettingsScreen(prefs,
                                onBack = { nav.popBackStack() })
                        }
                        composable("notifs") {
                            MemberNotifsScreen(auth,
                                onBack = { nav.popBackStack() },
                                onOpen = ::openArticle,
                                onLogin = { nav.navigate("login") })
                        }
                    }
                }
            }
        }
    }
}
