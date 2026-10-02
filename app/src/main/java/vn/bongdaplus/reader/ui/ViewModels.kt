package vn.bongdaplus.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.*

class NewsViewModel : ViewModel() {
    private val _cat = MutableStateFlow("tin-moi")
    val cat: StateFlow<String> = _cat
    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles: StateFlow<List<Article>> = _articles
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    var cookieProvider: () -> Map<String, String> = { emptyMap() }

    fun select(catSlug: String) {
        _cat.value = catSlug
        refresh()
    }

    fun setQuery(q: String) { _query.value = q }

    val filtered: StateFlow<List<Article>> = combine(_articles, _query) { list, q ->
        if (q.isBlank()) list else list.filter { it.title.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true; _error.value = null
            try {
                val ck = cookieProvider()
                _articles.value = if (_cat.value == "tin-moi") BongDaPlusScraper.fetchHome(ck)
                else BongDaPlusScraper.fetchCategory(_cat.value, ck)
            } catch (e: Exception) {
                _error.value = "Không tải được tin (kiểm tra mạng): ${e.message?.take(120)}"
            }
            _loading.value = false
        }
    }
}

class DetailViewModel : ViewModel() {
    private val _detail = MutableStateFlow<ArticleDetail?>(null)
    val detail: StateFlow<ArticleDetail?> = _detail
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    var cookieProvider: () -> Map<String, String> = { emptyMap() }

    fun load(url: String) {
        viewModelScope.launch {
            _loading.value = true
            try { _detail.value = BongDaPlusScraper.fetchDetail(url, cookieProvider()) }
            catch (_: Exception) { }
            _loading.value = false
        }
    }
}
