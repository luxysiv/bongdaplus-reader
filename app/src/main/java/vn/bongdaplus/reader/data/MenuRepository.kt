package vn.bongdaplus.reader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Menu chuyên mục ĐỘNG theo bongdaplus.vn (thay menu cứng trong app).
 * Mở app/tab Chuyên mục thì refresh ngầm (cache 24h). Rớt mạng -> giữ menu
 * tĩnh dự phòng nên app vẫn chạy. nameMap cho catName() tra tên mọi slug.
 */
object MenuRepository {
    private val _groups = MutableStateFlow(CATEGORY_GROUPS)
    val groups: StateFlow<List<CategoryGroup>> = _groups

    @Volatile
    var nameMap: Map<String, String> = emptyMap()
        private set

    @Volatile
    private var lastFetch = 0L

    suspend fun refresh(force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            if (!force && now - lastFetch < 24 * 3600 * 1000L && nameMap.isNotEmpty()) return
            val g = BongDaPlusScraper.fetchMenuGroups()
            if (g.isNotEmpty()) {
                _groups.value = g
                nameMap = g.flatMap { it.cats }.associate { it.slug to it.name }
                lastFetch = now
            }
        } catch (_: Exception) { }
    }
}
