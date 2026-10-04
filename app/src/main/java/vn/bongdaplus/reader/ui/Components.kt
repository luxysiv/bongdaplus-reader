package vn.bongdaplus.reader.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import vn.bongdaplus.reader.data.Article
import vn.bongdaplus.reader.data.catName
import vn.bongdaplus.reader.ui.theme.hotRed

// ---------- Nhãn chuyên mục + giờ ----------

@Composable
fun MetaLine(a: Article, light: Boolean = false) {
    val c = if (light) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            catName(a.category).uppercase(),
            color = c, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
        )
        if (!a.time.isNullOrBlank()) {
            Text("  •  ${a.time}", color = if (light) Color.White.copy(alpha = 0.8f) else Color.Gray,
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        // Số bình luận web đính kèm: pill riêng icon + số (khác hẳn font tiêu đề
        // để khỏi nhầm với chữ trong tít như trước).
        if (a.comments > 0) {
            Spacer(Modifier.width(6.dp))
            Surface(
                color = if (light) Color.White.copy(alpha = 0.92f)
                else MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.ChatBubble, "Bình luận",
                        tint = if (light) Color(0xFFC62828)
                        else MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${a.comments}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (light) Color(0xFFC62828)
                        else MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

// ---------- Breaking: pager tự chạy ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BreakingBar(items: List<Article>, onOpen: (Article) -> Unit) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(items.size) {
        while (true) {
            delay(4500)
            if (items.size > 1) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "NÓNG", color = Color.White, fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.background(hotRed, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
        )
        Spacer(Modifier.width(8.dp))
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { p ->
            Text(
                items[p].title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onOpen(items[p]) }
            )
        }
    }
}

// ---------- Hero carousel ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedPager(items: List<Article>, onOpen: (Article) -> Unit) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(items.size) {
        while (true) {
            delay(6000)
            if (items.size > 1) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Column {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 10.dp
        ) { p ->
            val a = items[p]
            Card(
                modifier = Modifier.fillMaxWidth().height(220.dp).clickable { onOpen(a) },
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (!a.imageUrl.isNullOrBlank()) {
                        AsyncImage(a.imageUrl, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                startY = 200f
                            )
                        )
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        MetaLine(a, light = true)
                        Spacer(Modifier.height(4.dp))
                        Text(a.title, color = Color.White, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(items.size) { i ->
                Box(
                    Modifier.padding(horizontal = 3.dp).size(
                        if (i == pager.currentPage) 18.dp else 7.dp, 7.dp
                    ).clip(CircleShape).background(
                        if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                        else Color.Gray.copy(alpha = 0.4f)
                    )
                )
            }
        }
    }
}

// ---------- Card video ngang (rail Highlight & Video trang chủ) ----------

@Composable
fun VideoRailCard(a: Article, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(220.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Box {
                if (!a.imageUrl.isNullOrBlank()) {
                    AsyncImage(a.imageUrl, null,
                        modifier = Modifier.fillMaxWidth().height(124.dp),
                        contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxWidth().height(124.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer))
                }
                Box(
                    Modifier.fillMaxWidth().height(124.dp)
                        .background(Color.Black.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) { Text("▶", color = Color.White, fontSize = MaterialTheme.typography.titleLarge.fontSize) }
                }
                if (!a.time.isNullOrBlank()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    ) {
                        Text(a.time, color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text("VIDEO", color = hotRed,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------- Hàng Đọc nhiều (số thứ hạng lớn, tách biệt tiêu đề) ----------

@Composable
fun MostReadRow(rank: Int, a: Article, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$rank",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = if (rank <= 3) hotRed else MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(3.dp))
            MetaLine(a)
        }
    }
}

// ---------- Ô lưới trang Main (2 ô 1 hàng) ----------

@Composable
fun HomeGridCell(a: Article, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Column {
            if (!a.imageUrl.isNullOrBlank()) {
                AsyncImage(a.imageUrl, null,
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxWidth().height(100.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer))
            }
            Column(Modifier.padding(8.dp)) {
                Text(catName(a.category).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------- Card tin dòng ----------

@Composable
fun NewsRowCard(a: Article, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (!a.imageUrl.isNullOrBlank()) {
            AsyncImage(
                a.imageUrl, null,
                modifier = Modifier.size(112.dp, 84.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            MetaLine(a)
            Spacer(Modifier.height(3.dp))
            Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 3,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text("BongdaPlus", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        Icon(
            Icons.Default.ChevronRight, "Mở",
            tint = Color.Gray,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}

// ---------- Tiêu đề section ----------

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(20.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

// ---------- Skeleton loading ----------

@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val alpha by t.animateFloat(0.35f, 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    return this.background(Color.Gray.copy(alpha = alpha * 0.35f), RoundedCornerShape(8.dp))
}

@Composable
fun LoadingSkeleton(rows: Int = 5) {
    Column(Modifier.padding(12.dp)) {
        repeat(rows) {
            Row(Modifier.padding(vertical = 8.dp)) {
                Box(Modifier.size(112.dp, 84.dp).shimmer())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).shimmer())
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(14.dp).shimmer())
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(14.dp).shimmer())
                }
            }
        }
    }
}

// ---------- Trạng thái rỗng / lỗi ----------

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.Gray)
    }
}

@Composable
fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text("Thử lại") }
    }
}
