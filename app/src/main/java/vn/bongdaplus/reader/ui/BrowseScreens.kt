package vn.bongdaplus.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

// ---------- Tab Chuyên mục: lưới ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(onFeed: (String) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Chuyên mục", fontWeight = FontWeight.Bold) }) }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(CATEGORIES) { c ->
                Card(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    onClick = { onFeed(c.slug) }
                ) {
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                        Text(c.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

// ---------- Feed 1 chuyên mục ----------

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun FeedScreen(
    slug: String,
    auth: AuthManager,
    bookmarks: BookmarkStore,
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
    val savedList by bookmarks.flow().collectAsState(initial = emptyList())
    val savedIds = remember(savedList) { savedList.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(slug) { vm.cookieProvider = { auth.currentCookies() }; vm.load() }
    val pull = rememberPullRefreshState(refreshing, onRefresh = { vm.load(true) })

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(catName(slug), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().pullRefresh(pull)) {
            if (loading) LoadingSkeleton()
            else if (err != null && list.isEmpty()) ErrorBox(err!!, onRetry = { vm.load() })
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { a ->
                    NewsRowCard(a, savedIds.contains(a.id), onClick = { onOpen(a) },
                        onToggleSave = { scope.launch { bookmarks.toggle(a) } })
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
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
    bookmarks: BookmarkStore,
    onOpen: (Article) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = viewModel()
    val q by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val savedList by bookmarks.flow().collectAsState(initial = emptyList())
    val savedIds = remember(savedList) { savedList.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.cookieProvider = { auth.currentCookies() } }

    val hot = listOf("Việt Nam", "MU", "Ronaldo", "Man City", "Real Madrid", "Arsenal", "Thái Lan")

    Scaffold(topBar = {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = q, onValueChange = vm::setQuery,
                    placeholder = { Text("Tìm kiếm tin tức…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
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
                        NewsRowCard(a, savedIds.contains(a.id), onClick = { onOpen(a) },
                            onToggleSave = { scope.launch { bookmarks.toggle(a) } })
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
