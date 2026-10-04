package vn.bongdaplus.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.bongdaplus.reader.data.*

// ---------- Feed 1 chuyên mục ----------

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun FeedScreen(
    slug: String,
    auth: AuthManager,
    onOpen: (Article) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FeedViewModel = viewModel(
        key = slug,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(slug) as T
        }
    )
    val list by vm.articles.collectAsState()
    val loading by vm.loading.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val err by vm.error.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val endReached by vm.endReached.collectAsState()

    LaunchedEffect(slug) { vm.cookieProvider = { auth.currentCookies() }; vm.load() }
    val pull = rememberPullRefreshState(refreshing, onRefresh = { vm.load(true) })
    // Kéo gần cuối -> tự tải thêm (như nút "Xem thêm" trên web)
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) false
            else (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 4
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && !loading) vm.loadMore()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(catName(slug), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Về") } }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().pullRefresh(pull)) {
            if (loading) LoadingSkeleton()
            else if (err != null && list.isEmpty()) ErrorBox(err!!, onRetry = { vm.load() })
            else LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(list, key = { it.id }) { a ->
                    NewsRowCard(a, onClick = { onOpen(a) })
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                // Chân trang: đang tải thêm / đã hết tin
                if (loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                } else if (endReached && list.isNotEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Đã hết tin 🎉",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            PullRefreshIndicator(refreshing, pull, Modifier.align(Alignment.TopCenter))
        }
    }
}

// ---------- Tìm kiếm ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    auth: AuthManager,
    onOpen: (Article) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = viewModel()
    val q by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    LaunchedEffect(Unit) { vm.cookieProvider = { auth.currentCookies() } }

    val hot = listOf("Việt Nam", "MU", "Ronaldo", "Man City", "Real Madrid", "Arsenal", "Thái Lan")

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Tìm kiếm", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Về") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Thanh tìm kiếm bo tròn chuẩn native (DockedSearchBar thu gọn)
            DockedSearchBar(
                query = q,
                onQueryChange = vm::setQuery,
                onSearch = vm::setQuery,
                active = false,
                onActiveChange = { },
                placeholder = { Text("Tìm kiếm tin tức…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (q.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Rounded.Close, "Xóa")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) { }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (q.trim().length < 2) {
                Text("Từ khóa nổi bật", fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hot) { k ->
                        FilterChip(selected = false, onClick = { vm.setQuery(k) }, label = { Text(k) })
                    }
                }
            } else if (!searching && results.isEmpty()) {
                EmptyState("Không tìm thấy tin cho \"$q\"")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { a ->
                        NewsRowCard(a, onClick = { onOpen(a) })
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}
