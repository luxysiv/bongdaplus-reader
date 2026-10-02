package vn.bongdaplus.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
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
private val TABS = listOf(
    Tab("home", "Trang chủ", Icons.Default.Home),
    Tab("explore", "Chuyên mục", Icons.Default.List),
    Tab("saved", "Đã lưu", Icons.Default.Bookmark),
    Tab("account", "Tài khoản", Icons.Default.Person),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appCtx = this
            val auth = remember { AuthManager(appCtx) }
            val bookmarks = remember { BookmarkStore(appCtx) }
            val prefs = remember { UiPrefs(appCtx) }
            val themeMode by prefs.themeMode.collectAsState(initial = "system")

            NewsTheme(mode = themeMode) {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                val showBar = route in TABS.map { it.route }

                // Mở từ notification
                LaunchedEffect(Unit) {
                    intent.getStringExtra("open_url")?.let { url ->
                        val a = Article(BongDaPlusScraper.idFromUrl(url), "Tin mới", url)
                        ArticleCache.put(a)
                        nav.navigate("detail/${a.id}?url=${URLEncoder.encode(url, "UTF-8")}")
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
                            HomeScreen(auth, bookmarks,
                                onOpen = ::openArticle,
                                onSearch = { nav.navigate("search") },
                                onFeed = { nav.navigate("feed/$it") })
                        }
                        composable("explore") {
                            ExploreScreen(onFeed = { nav.navigate("feed/$it") })
                        }
                        composable(
                            "feed/{slug}",
                            arguments = listOf(navArgument("slug") { type = NavType.StringType })
                        ) { e ->
                            FeedScreen(
                                slug = e.arguments?.getString("slug") ?: "tin-moi",
                                auth, bookmarks,
                                onOpen = ::openArticle,
                                onBack = { nav.popBackStack() })
                        }
                        composable("search") {
                            SearchScreen(auth, bookmarks,
                                onOpen = ::openArticle,
                                onBack = { nav.popBackStack() })
                        }
                        composable(
                            "detail/{id}?url={url}",
                            arguments = listOf(
                                navArgument("id") { type = NavType.StringType },
                                navArgument("url") { type = NavType.StringType }
                            )
                        ) { e ->
                            val id = e.arguments?.getString("id") ?: ""
                            val url = URLDecoder.decode(e.arguments?.getString("url") ?: "", "UTF-8")
                            val cached = ArticleCache.get(id)
                            val article = cached?.takeIf { it.url == url }
                                ?: Article(BongDaPlusScraper.idFromUrl(url), "Bài viết", url)
                            DetailScreen(article, auth, bookmarks, prefs,
                                onBack = { nav.popBackStack() },
                                onOpen = ::openArticle,
                                onLogin = { nav.navigate("login") })
                        }
                        composable("login") {
                            LoginScreen(auth,
                                onBack = { nav.popBackStack() },
                                onDone = { nav.popBackStack() })
                        }
                        composable("register") {
                            RegisterScreen(auth,
                                onBack = { nav.popBackStack() },
                                onDone = { nav.popBackStack() })
                        }
                        composable("saved") {
                            SavedScreen(bookmarks, onOpen = ::openArticle)
                        }
                        composable("account") {
                            AccountScreen(auth, prefs,
                                onLogin = { nav.navigate("login") },
                                onRegister = { nav.navigate("register") },
                                onSaved = { nav.navigate("saved") },
                                onOpenArticle = ::openArticle)
                        }
                    }
                }
            }
        }
    }
}
