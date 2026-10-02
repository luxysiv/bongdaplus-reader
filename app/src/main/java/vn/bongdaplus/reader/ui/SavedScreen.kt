package vn.bongdaplus.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

// ---------- Tab Đã lưu ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(bookmarks: BookmarkStore, onOpen: (Article) -> Unit) {
    val list by bookmarks.flow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Tin đã lưu (${list.size})", fontWeight = FontWeight.Bold) })
    }) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.padding(pad)) { EmptyState("Chưa lưu tin nào.\nBấm icon 🔖 ở mỗi tin để đọc sau.") }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(list, key = { it.id }) { a ->
                    NewsRowCard(a, saved = true, onClick = { onOpen(a) },
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
