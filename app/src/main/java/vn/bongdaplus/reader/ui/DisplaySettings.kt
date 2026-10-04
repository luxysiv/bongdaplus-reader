package vn.bongdaplus.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.UiPrefs

/**
 * Màn Cài đặt hiển thị RIÊNG — gom toàn bộ tuỳ chỉnh đọc báo về một chỗ
 * (trước đây rải rác ở TopBar Detail + tab Tài khoản).
 * Mở từ: tab Tài khoản > "Hiển thị & đọc báo", hoặc nút Aa trong bài viết.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(prefs: UiPrefs, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Hiển thị & đọc báo", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Về") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            DisplaySettingsContent(prefs, showTitle = false)
        }
    }
}

@Composable
fun DisplaySettingsContent(prefs: UiPrefs, showTitle: Boolean = true) {
    val theme by prefs.themeMode.collectAsState(initial = "system")
    val dynamic by prefs.dynamicColor.collectAsState(initial = true)
    val readerFont by prefs.readerFont.collectAsState(initial = "serif")
    val fontScale by prefs.fontScale.collectAsState(initial = 1f)
    val lineSpace by prefs.lineSpace.collectAsState(initial = 1f)
    val scope = rememberCoroutineScope()
    val supportDynamic = android.os.Build.VERSION.SDK_INT >= 31
    val bodyFont = if (readerFont == "serif") FontFamily.Serif else FontFamily.Default

    if (showTitle) {
        Text("Hiển thị & đọc báo", fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 8.dp))
    }

    // --- Chế độ sáng/tối ---
    Text("Chế độ màn hình", fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(4.dp, 12.dp, 4.dp, 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        listOf("system" to "Hệ thống", "light" to "☀️ Sáng", "dark" to "🌙 Tối").forEach { (v, label) ->
            FilterChip(
                selected = theme == v,
                onClick = { scope.launch { prefs.setThemeMode(v) } },
                label = { Text(label) },
                leadingIcon = if (theme == v) ({ Icon(Icons.Rounded.Check, null) }) else null
            )
        }
    }

    ListItem(
        headlineContent = { Text("Màu động theo hình nền") },
        supportingContent = { Text(if (supportDynamic) "Material You (Android 12+)" else "Cần Android 12+ — đang dùng xanh BongdaPlus") },
        trailingContent = {
            Switch(
                checked = dynamic && supportDynamic, enabled = supportDynamic,
                onCheckedChange = { scope.launch { prefs.setDynamicColor(it) } }
            )
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))

    // --- Font chữ ---
    Text("Font chữ bài đọc", fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(4.dp, 12.dp, 4.dp, 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        FilterChip(
            selected = readerFont == "sans",
            onClick = { scope.launch { prefs.setReaderFont("sans") } },
            label = { Text("Không chân (hiện đại)") }
        )
        FilterChip(
            selected = readerFont == "serif",
            onClick = { scope.launch { prefs.setReaderFont("serif") } },
            label = { Text("Có chân (báo giấy)", fontFamily = FontFamily.Serif) }
        )
    }

    var fontTmp by remember(fontScale) { mutableStateOf(fontScale) }
    ListItem(
        headlineContent = { Text("Cỡ chữ: ${"%.0f".format(fontTmp * 100)}%") },
        supportingContent = {
            Slider(value = fontTmp, onValueChange = { fontTmp = it },
                onValueChangeFinished = { scope.launch { prefs.setFontScale(fontTmp) } },
                valueRange = 0.85f..1.3f, steps = 8)
        }
    )
    var lineTmp by remember(lineSpace) { mutableStateOf(lineSpace) }
    ListItem(
        headlineContent = { Text("Giãn dòng: ${"%.0f".format(lineTmp * 100)}%") },
        supportingContent = {
            Slider(value = lineTmp, onValueChange = { lineTmp = it },
                onValueChangeFinished = { scope.launch { prefs.setLineSpace(lineTmp) } },
                valueRange = 1f..1.6f, steps = 5)
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))

    // --- Xem trước trực tiếp ---
    Text("Xem trước", fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(4.dp, 12.dp, 4.dp, 4.dp))
    Card(
        modifier = Modifier.padding(4.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Ronaldo chia tay ĐT Bồ Đào Nha",
                fontWeight = FontWeight.Bold, fontSize = (19 * fontTmp).sp)
            Spacer(Modifier.height(6.dp))
            Text("Siêu sao Cristiano Ronaldo đã quyết định rời trại tập trung của Bồ Đào Nha sau cuộc trao đổi với chủ tịch LĐBĐ...",
                fontFamily = bodyFont, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = (15 * fontTmp).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("Bồ Đào Nha bước vào trận đấu với Na Uy mà không có đội trưởng trong đội hình chính. Quyết định được đưa ra sau cuộc họp kín kéo dài hơn một giờ đồng hồ.",
                fontFamily = bodyFont,
                fontSize = (17 * fontTmp).sp, lineHeight = (27 * fontTmp * lineTmp).sp)
        }
    }
}

/**
 * Bottom-sheet chỉnh nhanh trong bài viết (nút Aa):
 * đủ dùng, có nút "Mở cài đặt đầy đủ".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderQuickSettingsSheet(
    prefs: UiPrefs,
    onOpenFull: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp, 0.dp, 16.dp, 32.dp)) {
            DisplaySettingsContent(prefs)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenFull, modifier = Modifier.fillMaxWidth()) {
                Text("Mở cài đặt đầy đủ")
            }
        }
    }
}
