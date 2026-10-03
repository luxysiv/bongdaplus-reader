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
import vn.bongdaplus.reader.data.*

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    auth: AuthManager,
    onOpen: (Article) -> Unit,
    onSearch: () -> Unit,
    onFeed: (String) -> Unit,
    onLogin: () -> Unit = {},
) {
    val vm: HomeViewModel = viewModel()
    val breaking by vm.breaking.collectAsState()
    val featured by vm.featured.collectAsState()
    val latest by vm.latest.collectAsState()
    val videos by vm.videos.collectAsState()
    val mostRead by vm.mostRead.collectAsState()
    val blocks by vm.blocks.collectAsState()
    val loading by vm.loading.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val err by vm.error.collectAsState()
    val logged by auth.loggedIn.collectAsState(initial = false)

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
                                onClick = onLogin
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
                    item { SectionHeader("Tin mới nhất", "Xem thêm") { onFeed("tin-moi") } }
                    items(latest, key = { it.id }) { a ->
                        NewsRowCard(a, onClick = { onOpen(a) })
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    // Highlight & Video: chỉ tin bóng đá lõi (không Nhận định/Hậu trường)
                    if (videos.isNotEmpty()) {
                        item { SectionHeader("🎬 Highlight & Video", "Xem thêm") { onFeed("video") } }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) {
                                items(videos, key = { it.id }) { v ->
                                    VideoRailCard(v, onClick = { onOpen(v) })
                                }
                            }
                        }
                    }
                    // Đọc nhiều: top 5 tab web, số thứ hạng tách biệt tiêu đề
                    if (mostRead.isNotEmpty()) {
                        val top5 = mostRead.take(5)
                        item { SectionHeader("🔥 Đọc nhiều") }
                        items(top5.size) { i ->
                            MostReadRow(i + 1, top5[i], onClick = { onOpen(top5[i]) })
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                    // Các mục trên Main (thay tab Chuyên mục): mỗi mục vài bài
                    // tiêu biểu, bấm vào đọc tiếp. List và ô lưới đan xen.
                    blocks.forEach { (slug, list) ->
                        when (slug) {
                            // Việt Nam: 1 thẻ lớn + list
                            "bong-da-viet-nam" -> {
                                item { SectionHeader(catName(slug), "Xem thêm") { onFeed(slug) } }
                                if (list.isNotEmpty()) {
                                    item { HomeFeatureCard(list.first(), onClick = { onOpen(list.first()) }) }
                                }
                                items(list.drop(1), key = { it.id }) { a ->
                                    NewsRowCard(a, onClick = { onOpen(a) })
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                            // NHA + C1: ô lưới 2 cột
                            "ngoai-hang-anh", "champions-league-cup-c1" -> {
                                item { SectionHeader(catName(slug), "Xem thêm") { onFeed(slug) } }
                                items(list.chunked(2)) { pair ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        pair.forEach { a ->
                                            HomeGridCell(a, onClick = { onOpen(a) },
                                                modifier = Modifier.weight(1f))
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                            // Còn lại: danh sách
                            else -> {
                                item { SectionHeader(catName(slug), "Xem thêm") { onFeed(slug) } }
                                items(list, key = { it.id }) { a ->
                                    NewsRowCard(a, onClick = { onOpen(a) })
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
            PullRefreshIndicator(refreshing, pull, Modifier.align(Alignment.TopCenter))
        }
    }
}
