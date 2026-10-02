package vn.bongdaplus.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import vn.bongdaplus.reader.data.*
import vn.bongdaplus.reader.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val auth = remember { AuthManager(this) }
                val bookmarks = remember { BookmarkStore(this) }
                val nav = rememberNavController()
                var pendingArticle by remember { mutableStateOf<Article?>(null) }

                // Mở từ notification: intent extra open_url
                LaunchedEffect(Unit) {
                    intent.getStringExtra("open_url")?.let { url ->
                        pendingArticle = Article(BongDaPlusScraper.idFromUrl(url), "Tin mới", url)
                        nav.navigate("detail")
                    }
                }

                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        val vm: NewsViewModel = viewModel()
                        HomeScreen(vm, auth, bookmarks,
                            onOpen = { pendingArticle = it; nav.navigate("detail") },
                            onLogin = { nav.navigate("login") },
                            onBookmarks = { nav.navigate("saved") },
                            onSettings = { nav.navigate("settings") })
                    }
                    composable("detail") {
                        val vm: DetailViewModel = viewModel()
                        val a = pendingArticle ?: Article("0", "", "https://bongdaplus.vn/")
                        DetailScreen(a, vm, auth, onBack = { nav.popBackStack() })
                    }
                    composable("login") {
                        LoginScreen(auth, onDone = { nav.popBackStack() })
                    }
                    composable("saved") {
                        BookmarkScreen(bookmarks,
                            onOpen = { pendingArticle = it; nav.navigate("detail") },
                            onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(auth, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
