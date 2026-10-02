package vn.bongdaplus.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

private fun cookiesOf(provider: () -> Map<String, String>): Map<String, String> =
    try { provider() } catch (_: Exception) { emptyMap() }

/** Trang chủ: 1 list tin-moi -> breaking(3) + featured(có ảnh, 5) + latest */
class HomeViewModel : ViewModel() {
    var cookieProvider: () -> Map<String, String> = { emptyMap() }
    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val breaking: StateFlow<List<Article>> = _articles.map { it.take(3) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val featured: StateFlow<List<Article>> = _articles.map { l ->
        (l.filter { !it.imageUrl.isNullOrBlank() }.take(5)).ifEmpty { l.take(5) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val latest: StateFlow<List<Article>> = _articles.map { l ->
        val top = l.take(5).toSet()
        l.filter { it !in top }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun load(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) _refreshing.value = true else _loading.value = true
            _error.value = null
            try {
                _articles.value = BongDaPlusScraper.fetchHome(cookiesOf(cookieProvider))
            } catch (e: Exception) {
                _error.value = "Không tải được tin: ${e.message?.take(100)}"
            }
            _loading.value = false; _refreshing.value = false
        }
    }
}

/** Feed 1 chuyên mục */
class FeedViewModel(val slug: String) : ViewModel() {
    var cookieProvider: () -> Map<String, String> = { emptyMap() }
    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles: StateFlow<List<Article>> = _articles
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) _refreshing.value = true else _loading.value = true
            _error.value = null
            try {
                _articles.value = if (slug == "tin-moi") BongDaPlusScraper.fetchHome(cookiesOf(cookieProvider))
                else BongDaPlusScraper.fetchCategory(slug, cookiesOf(cookieProvider))
            } catch (e: Exception) {
                _error.value = "Không tải được tin: ${e.message?.take(100)}"
            }
            _loading.value = false; _refreshing.value = false
        }
    }
}

/** Tìm kiếm: quét trang chủ + 3 chuyên mục lớn rồi lọc */
class SearchViewModel : ViewModel() {
    var cookieProvider: () -> Map<String, String> = { emptyMap() }
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _results = MutableStateFlow<List<Article>>(emptyList())
    val results: StateFlow<List<Article>> = _results
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private var job: Job? = null

    fun setQuery(q: String) {
        _query.value = q
        job?.cancel()
        if (q.trim().length < 2) { _results.value = emptyList(); return }
        job = viewModelScope.launch {
            delay(500)
            _searching.value = true
            try {
                val ck = cookiesOf(cookieProvider)
                val all = mutableListOf<Article>()
                all += BongDaPlusScraper.fetchHome(ck)
                for (s in listOf("bong-da-viet-nam", "ngoai-hang-anh", "tin-chuyen-nhuong")) {
                    try { all += BongDaPlusScraper.fetchCategory(s, ck) } catch (_: Exception) {}
                }
                _results.value = all.distinctBy { it.id }
                    .filter { it.title.contains(q.trim(), ignoreCase = true) }.take(30)
            } catch (_: Exception) { }
            _searching.value = false
        }
    }
}

/** Chi tiết + tin liên quan */
class DetailViewModel : ViewModel() {
    var cookieProvider: () -> Map<String, String> = { emptyMap() }
    private val _detail = MutableStateFlow<ArticleDetail?>(null)
    val detail: StateFlow<ArticleDetail?> = _detail
    private val _related = MutableStateFlow<List<Article>>(emptyList())
    val related: StateFlow<List<Article>> = _related
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    fun load(article: Article) {
        viewModelScope.launch {
            _loading.value = true; _detail.value = null; _related.value = emptyList()
            try {
                val ck = cookiesOf(cookieProvider)
                _detail.value = BongDaPlusScraper.fetchDetail(article.url, ck)
                val slug = article.category
                val pool = try {
                    if (slug != null && slug != "tin-moi") BongDaPlusScraper.fetchCategory(slug, ck)
                    else BongDaPlusScraper.fetchHome(ck)
                } catch (_: Exception) { emptyList() }
                _related.value = pool.filter { it.id != article.id }.take(6)
            } catch (_: Exception) { }
            _loading.value = false
        }
    }
}

/** Dựng HTML đọc: hero + tiêu đề + meta + body + tin liên quan */
fun buildArticleHtml(d: ArticleDetail, related: List<Article>): String {
    val rel = if (related.isEmpty()) "" else
        "<h3>Tin liên quan</h3><ul>" + related.joinToString("") {
            "<li><a href=\"${it.url}\">${it.title}</a></li>"
        } + "</ul>"
    return """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"/>
<meta charset="utf-8"/>
<style>
body{font-family:sans-serif;font-size:17px;line-height:1.65;color:#222;margin:0;padding:14px}
h1{font-size:22px;line-height:1.35;margin:8px 0}
.meta{color:#888;font-size:13px;margin-bottom:8px}
.hero{width:100%;border-radius:10px;margin:8px 0}
img{max-width:100%;height:auto;border-radius:8px}
a{color:#1B7A43;text-decoration:none}
h3{font-size:17px;border-left:4px solid #1B7A43;padding-left:8px}
li{margin:6px 0}
.src{color:#888;font-size:13px;margin-top:16px;border-top:1px solid #eee;padding-top:10px}
</style></head><body>
<h1>${d.article.title}</h1>
<div class="meta">${d.author ?: "BongdaPlus"} • ${d.publishedAt ?: ""}</div>
${if (!d.article.imageUrl.isNullOrBlank()) "<img class=\"hero\" src=\"${d.article.imageUrl}\"/>" else ""}
${d.bodyHtml.ifBlank { "<p>${d.bodyText}</p>" }}
$rel
<div class="src">Nguồn: bongdaplus.vn — <a href="${d.article.url}">Xem bài gốc</a></div>
</body></html>"""
}
