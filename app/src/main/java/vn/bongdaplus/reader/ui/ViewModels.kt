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

/** Chi tiết + tin liên quan + bình luận thật */
class DetailViewModel : ViewModel() {
    var cookieProvider: () -> Map<String, String> = { emptyMap() }
    private val _detail = MutableStateFlow<ArticleDetail?>(null)
    val detail: StateFlow<ArticleDetail?> = _detail
    private val _related = MutableStateFlow<List<Article>>(emptyList())
    val related: StateFlow<List<Article>> = _related
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments
    private val _loadingComments = MutableStateFlow(false)
    val loadingComments: StateFlow<Boolean> = _loadingComments
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending
    private val _sendMsg = MutableStateFlow<String?>(null)
    val sendMsg: StateFlow<String?> = _sendMsg
    // vote của chính member: commentId -> emotionType (1=đã Thích)
    private val _myVotes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val myVotes: StateFlow<Map<String, Int>> = _myVotes
    // Cảm xúc bài viết của chính member (0 = chưa chọn) — để toggle như web
    private val _myEmotion = MutableStateFlow(0)
    val myEmotion: StateFlow<Int> = _myEmotion

    fun load(article: Article) {
        viewModelScope.launch {
            _loading.value = true; _detail.value = null; _related.value = emptyList()
            _comments.value = emptyList(); _sendMsg.value = null
            try {
                val ck = cookiesOf(cookieProvider)
                val det = BongDaPlusScraper.fetchDetail(article.url, ck)
                _detail.value = det
                val slug = det.article.category ?: article.category
                val pool = try {
                    if (slug != null && slug != "tin-moi") BongDaPlusScraper.fetchCategory(slug, ck)
                    else BongDaPlusScraper.fetchHome(ck)
                } catch (_: Exception) { emptyList() }
                _related.value = pool.filter { it.id != article.id }.take(6)
                loadComments()
            } catch (_: Exception) { }
            _loading.value = false
        }
    }

    fun loadComments() {
        val d = _detail.value ?: return
        if (d.objectId.isBlank()) return
        viewModelScope.launch {
            _loadingComments.value = true
            try {
                val ck = cookiesOf(cookieProvider)
                _comments.value = BongDaPlusScraper.fetchComments(
                    d.objectId, d.objectType, 1, ck)
                // trạng thái vote của member (để tô sáng 👍👎 đã bấm)
                try { _myVotes.value = BongDaPlusScraper.getMyCommentVotes(d.objectId, d.objectType, ck) }
                catch (_: Exception) { }
                try { _myEmotion.value =
                    BongDaPlusScraper.fetchMyNewsEmotion(d.objectId, d.objectType, ck) ?: 0 }
                catch (_: Exception) { }
            } catch (_: Exception) { }
            _loadingComments.value = false
        }
    }

    /**
     * Thích/Không thích kiểu web: bấm lại nút đang active = hoàn tác.
     * Cập nhật UI ngay cho mượt, xong tải lại số thật từ server để chốt.
     */
    fun reactComment(commentId: String, like: Boolean) {
        val d = _detail.value ?: return
        val cur = _myVotes.value.toMutableMap()
        val active = cur[commentId]
        val undo = (like && active == 1) || (!like && active == 7)
        // 1) UI ngay: gỡ vote cũ, áp vote mới (trừ khi hoàn tác)
        _comments.value = _comments.value.map {
            if (it.id != commentId) it
            else {
                var l = it.likes
                var dl = it.dislikes
                if (active == 1) l-- else if (active == 7) dl--
                if (!undo) { if (like) l++ else dl++ }
                it.copy(likes = l.coerceAtLeast(0), dislikes = dl.coerceAtLeast(0))
            }
        }
        cur[commentId] = when {
            undo -> 0
            like -> 1
            else -> 7
        }
        _myVotes.value = cur
        // 2) Server chốt: tải lại số thật (sai thì UI tự sửa theo server)
        viewModelScope.launch {
            val ok = try {
                BongDaPlusScraper.setCommentEmotion(
                    d.objectId, commentId, like, cookiesOf(cookieProvider),
                    d.article.url, d.objectType, expectVoted = !undo)
            } catch (_: Exception) { false }
            loadComments()
            if (!ok) _sendMsg.value = "👍/👎 thất bại — server chưa nhận, thử lại sau."
        }
    }

    /** Cảm xúc bài viết kiểu web: bấm lại cảm xúc đang chọn = gỡ. */
    fun reactArticle(emotionType: Int) {
        val d = _detail.value ?: return
        val undo = _myEmotion.value == emotionType
        _myEmotion.value = if (undo) 0 else emotionType
        viewModelScope.launch {
            try {
                val ok = BongDaPlusScraper.setNewsEmotion(
                    d.objectId, d.objectType, emotionType,
                    cookiesOf(cookieProvider), d.article.url,
                    expectEmotion = if (undo) 0 else emotionType)
                if (ok) {
                    val e = try {
                        BongDaPlusScraper.fetchEmotion(
                            d.objectId, d.objectType, cookiesOf(cookieProvider))
                    } catch (_: Exception) { d.emotion }
                    _detail.value = _detail.value?.copy(emotion = e) ?: d.copy(emotion = e)
                } else {
                    _sendMsg.value = "Cảm xúc thất bại — server chưa nhận, thử lại sau."
                }
                loadComments() // đồng bộ lại trạng thái member
            } catch (_: Exception) { }
        }
    }

    fun sendComment(text: String, article: Article? = null, trackStore: CommentTrackStore? = null,
                    parentId: String = "0", replyId: String = "0", replyName: String = "") {
        val d = _detail.value ?: return
        viewModelScope.launch {
            _sending.value = true; _sendMsg.value = null
            val clean = text.trim()
            val ck = cookiesOf(cookieProvider)
            // Chưa có cookie phiên site => chắc chắn bị đá về login, khỏi gọi mạng
            if (ck.isEmpty()) {
                _sendMsg.value = "Bạn cần đăng nhập tài khoản BongdaPlus trước."
                _sending.value = false
                return@launch
            }
            val ok = try {
                BongDaPlusScraper.postComment(
                    d.objectId, d.objectType, clean, ck,
                    parentId, replyId, replyName, (article ?: d.article).url)
            } catch (_: Exception) { false }
            _sendMsg.value = if (ok) "Đã gửi! Bình luận chờ duyệt rồi sẽ hiện. Đã bật theo dõi — có bình luận mới sẽ báo chi tiết."
            else "Gửi thất bại — bạn cần đăng nhập tài khoản BongdaPlus."
            _sending.value = false
            if (ok) {
                try {
                    // Tự track bài đã bình luận để Worker báo chi tiết, kể cả chưa bấm Lưu
                    trackStore?.track(article ?: d.article, clean)
                } catch (_: Exception) { }
                loadComments()
            }
        }
    }
}
/* (Chi tiết render native 100% — không dùng WebView.) */

/** Lịch sử thông báo member thật (div#lstnoti) — ai thích/không thích bình luận của bạn */
class NotifViewModel : ViewModel() {
    var cookieProvider: () -> Map<String, String> = { emptyMap() }
    private val _items = MutableStateFlow<List<MemberNotification>>(emptyList())
    val items: StateFlow<List<MemberNotification>> = _items
    private val _mine = MutableStateFlow<List<MyCommented>>(emptyList())
    val mine: StateFlow<List<MyCommented>> = _mine
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val ck = cookiesOf(cookieProvider)
                _items.value = BongDaPlusScraper.fetchMemberNotifications(ck)
                _mine.value = BongDaPlusScraper.fetchMyCommented(ck)
            } catch (_: Exception) { }
            _loading.value = false
        }
    }
}
