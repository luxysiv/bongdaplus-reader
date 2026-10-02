package vn.bongdaplus.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    auth: AuthManager,
    bookmarks: BookmarkStore,
    onOpen: (Article) -> Unit,
    onSearch: () -> Unit,
    onFeed: (String) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val breaking by vm.breaking.collectAsState()
    val featured by vm.featured.collectAsState()
    val latest by vm.latest.collectAsState()
    val loading by vm.loading.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val err by vm.error.collectAsState()
    val logged by auth.loggedIn.collectAsState(initial = false)
    val savedList by bookmarks.flow().collectAsState(initial = emptyList())
    val savedIds = remember(savedList) { savedList.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.cookieProvider = { auth.currentCookies() }; vm.load() }
    val pull = rememberPullRefreshState(refreshing, onRefresh = { vm.load(true) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚽ ", style = MaterialTheme.typography.titleLarge)
                        Text("Bóng Đá Plus", fontWeight = FontWeight.Black)
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Tìm kiếm") }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().pullRefresh(pull)) {
            if (loading) {
                LoadingSkeleton()
            } else if (err != null && breaking.isEmpty()) {
                ErrorBox(err!!, onRetry = { vm.load() })
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (!logged) {
                        item {
                            Card(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                onClick = { /* chuyển tab Tài khoản để đăng nhập */ }
                            ) {
                                Text(
                                    "🔐 Đăng nhập để nhận thông báo + đọc bài Premium (tab Tài khoản)",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    item { BreakingBar(breaking, onOpen) }
                    item {
                        Spacer(Modifier.height(4.dp))
                        FeaturedPager(featured, onOpen)
                        Spacer(Modifier.height(8.dp))
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            items(CATEGORIES.filter { it.slug != "tin-moi" }.take(8)) { c ->
                                FilterChip(
                                    selected = false, onClick = { onFeed(c.slug) },
                                    label = { Text(c.name) }
                                )
                            }
                        }
                    }
                    item { SectionHeader("Tin mới nhất", "Xem thêm") { onFeed("tin-moi") } }
                    items(latest, key = { it.id }) { a ->
                        NewsRowCard(a, savedIds.contains(a.id), onClick = { onOpen(a) },
                            onToggleSave = { scope.launch { bookmarks.toggle(a) } })
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            PullRefreshIndicator(refreshing, pull, Modifier.align(Alignment.TopCenter))
        }
    }
}
