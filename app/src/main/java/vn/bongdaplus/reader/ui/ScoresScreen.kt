package vn.bongdaplus.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import vn.bongdaplus.reader.data.CompEntry
import vn.bongdaplus.reader.data.ScoreMatch
import vn.bongdaplus.reader.data.StandingRow

/**
 * Tab Tỉ số: Lịch thi đấu / Kết quả / BXH theo giải
 * (Bundesliga, Champions League, Cúp C2, Nations League... từ API data của web).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoresScreen() {
    val vm: ScoresViewModel = viewModel()
    val tab by vm.tab.collectAsState()
    val compKey by vm.compKey.collectAsState()
    val entries by vm.entries.collectAsState()
    val matches by vm.matches.collectAsState()
    val standings by vm.standings.collectAsState()
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Tỉ số", fontWeight = FontWeight.Bold) },
            actions = { IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "Tải lại") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Lịch thi đấu", "Kết quả", "BXH").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { vm.setTab(i) }, text = { Text(t) })
                }
            }
            // Chọn giải ĐỘNG: chỉ giải đang có trận mới hiện chip
            // (Euro/World Cup... tự ẩn khi không vào mùa). Đang đá có chấm đỏ + đếm.
            val chips = remember(entries, tab) {
                if (tab == 2) entries.filter { it.file.isNotBlank() && it.hasRank }
                else entries
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // BXH bắt buộc chọn 1 giải (không có bảng chung)
                if (tab != 2) {
                    item {
                        FilterChip(
                            selected = compKey == null,
                            onClick = { vm.setComp(null) },
                            label = { Text("Tất cả") }
                        )
                    }
                }
                items(chips, key = { it.key }) { e ->
                    FilterChip(
                        selected = compKey == e.key,
                        onClick = { vm.setComp(e.key) },
                        label = { Text(compLabel(e)) },
                        leadingIcon = if (e.live > 0) ({
                            Box(Modifier.size(8.dp).clip(CircleShape)
                                .background(Color(0xFFC62828)))
                        }) else null
                    )
                }
            }
            HorizontalDivider()
            when {
                loading -> LoadingSkeleton(6)
                tab == 2 -> {
                    if (standings.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(err ?: "Chưa có dữ liệu.")
                        }
                    } else StandingsTable(standings)
                }
                matches.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(err ?: "Chưa có dữ liệu.")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.load() }) { Text("Thử lại") }
                        }
                    }
                }
                else -> MatchList(matches, showScore = tab == 1)
            }
        }
    }
}

/** List trận gom theo ngày (mới nhất/hôm nay trước). */
@Composable
private fun MatchList(matches: List<ScoreMatch>, showScore: Boolean) {
    // Lịch: ngày tăng dần (sắp đá trước); Kết quả: API đã mới nhất trước
    val groups = remember(matches, showScore) {
        val g = matches.groupBy { it.day }.toList()
        if (showScore) g.sortedByDescending { (day, _) -> day }
        else g.sortedBy { (day, _) -> day }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (day, list) ->
            item(key = "d$day") {
                Text(dayLabel(day), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
            }
            items(list, key = { it.id }) { m ->
                MatchRow(m, showScore)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MatchRow(m: ScoreMatch, showScore: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Giờ / trạng thái
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                m.isFinished -> {
                    Text("FT", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold)
                    Text(shortDay(m.startTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                m.isUpcoming -> {
                    Text(m.playTime.ifBlank { "--:--" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(shortDay(m.startTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                else -> {
                    Text(m.playTime.ifBlank { "LIVE" },
                        fontWeight = FontWeight.Bold, color = Color(0xFFC62828),
                        style = MaterialTheme.typography.bodyMedium)
                    Text("LIVE", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                }
            }
        }
        // 2 đội + giải/vòng
        Column(Modifier.weight(1f)) {
            TeamLine(m.home, m.homeLogo)
            Spacer(Modifier.height(4.dp))
            TeamLine(m.away, m.awayLogo)
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(m.compName, m.round.takeIf { it.isNotBlank() }?.let { "Vòng $it" })
                    .filterNotNull().joinToString(" • ").ifBlank { m.compName },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        // Tỉ số
        if (showScore || !m.isUpcoming) {
            Text("${m.homeGoals} - ${m.awayGoals}",
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium,
                color = if (m.isFinished) MaterialTheme.colorScheme.onSurface else Color(0xFFC62828),
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun TeamLine(name: String, logo: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!logo.isNullOrBlank()) {
            AsyncImage(logo, null, modifier = Modifier.size(22.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(8.dp))
        }
        Text(name, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Bảng xếp hạng chuẩn: TT | Đội | Tr T H B +/- Điểm. */
@Composable
private fun StandingsTable(rows: List<StandingRow>) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TT", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
                Text("Đội bóng", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f))
                listOf("Tr", "T", "H", "B", "+/-").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                }
                Text("Điểm", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
            }
            HorizontalDivider()
        }
        // key theo index (không theo pos: BXH chia bảng có pos lặp lại -> crash key trùng)
        items(rows.size) { i ->
            val r = rows[i]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.width(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(3.dp).height(20.dp).clip(CircleShape)
                            .background(parseColor(r.color))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("${r.pos}", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (!r.logo.isNullOrBlank()) {
                        AsyncImage(r.logo, null, modifier = Modifier.size(22.dp),
                            contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(r.team, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${r.played}", modifier = Modifier.width(30.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
                Text("${r.won}", modifier = Modifier.width(30.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
                Text("${r.drawn}", modifier = Modifier.width(30.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
                Text("${r.lost}", modifier = Modifier.width(30.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
                Text(if (r.diff > 0) "+${r.diff}" else "${r.diff}",
                    modifier = Modifier.width(30.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
                Text("${r.points}", fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(44.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
        item {
            Text("T: thắng • H: hoà • B: thua • +/-: hiệu số (chuẩn web BongdaPlus)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp))
        }
    }
}

private fun compLabel(e: CompEntry): String =
    if (e.live > 0) "${e.name} •${e.live}" else e.name

private fun parseColor(hex: String?): Color {
    return try {
        if (hex.isNullOrBlank()) return Color.Transparent
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) { Color.Transparent }
}

private fun shortDay(start: String): String {
    // "2026-10-03 05:15:00" -> "03/10"
    return try {
        val d = start.take(10).split("-")
        if (d.size == 3) "${d[2]}/${d[1]}" else ""
    } catch (_: Exception) { "" }
}

private fun dayLabel(day: String): String {
    // "2026-10-03" -> "Hôm nay 03/10" / "Ngày mai 04/10" / "Ngày 05/10"
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val d = fmt.parse(day) ?: return day
        fun strip(c: java.util.Calendar) = c.apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val today = strip(java.util.Calendar.getInstance())
        val that = strip(java.util.Calendar.getInstance().apply { time = d })
        val diff = ((that.timeInMillis - today.timeInMillis) / 86400000).toInt()
        val dd = java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).format(d)
        when (diff) {
            0 -> "Hôm nay $dd"
            1 -> "Ngày mai $dd"
            -1 -> "Hôm qua $dd"
            else -> "Ngày $dd"
        }
    } catch (_: Exception) { day }
}
