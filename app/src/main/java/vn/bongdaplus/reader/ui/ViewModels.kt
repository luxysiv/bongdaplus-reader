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

/** Trang chủ: 1 lần tải -> breaking + featured + latest + video + đọc nhiều */
class HomeViewModel : ViewModel() {
    var cookieProvider: () -> Map<String, String> = { emptyMap() }
    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    private val _mostRead = MutableStateFlow<List<Article>>(emptyList())
    val mostRead: StateFlow<List<Article>> = _mostRead
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
        l.filter { it !in top && !it.url.contains("/video/") }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Highlight & video trên trang chủ (lọc từ cùng 1 lần tải, không gọi thêm). */
    val videos: StateFlow<List<Article>> = _articles.map { l ->
        l.filter { it.url.contains("/video/") }.take(8)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun load(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) _refreshing.value = true else _loading.value = true
            _error.value = null
            try {
                val sec = BongDaPlusScraper.fetchHomeSections(cookiesOf(cookieProvider))
                _articles.value = sec.articles
                _mostRead.value = sec.mostRead
            } catch (e: Exception) {
                _error.value = "Không tải được tin: ${e.message?.take(100)}"
            }
            _loading.value = false; _refreshing.value = false
        }
    }
}

/** Feed 1 chuyên mục (kéo xuống tự tải thêm như nút "Xem thêm" trên web) */
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
    // Phân trang "Xem thêm"
    private var moreRef: BongDaPlusScraper.ViewMoreRef? = null
    private var page = 1
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore
    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    fun load(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) _refreshing.value = true else _loading.value = true
            _error.value = null
            page = 1
            _endReached.value = false
            moreRef = null
            try {
                val ck = cookiesOf(cookieProvider)
                _articles.value = if (slug == "tin-moi") BongDaPlusScraper.fetchHome(ck)
                else BongDaPlusScraper.fetchCategory(slug, ck)
                // Trang chủ không có viewmore -> chỉ 1 trang
                moreRef = if (slug == "tin-moi") null
                else try { BongDaPlusScraper.fetchViewMoreRef(slug, ck) } catch (_: Exception) { null }
                if (moreRef == null && slug != "tin-moi" && _articles.value.isNotEmpty()) {
                    // Không đọc được con trỏ viewmore: coi như hết để khỏi gọi vô ích
                    _endReached.value = true
                }
            } catch (e: Exception) {
                _error.value = "Không tải được tin: ${e.message?.take(100)}"
            }
            _loading.value = false; _refreshing.value = false
        }
    }

    /** Kéo tới cuối list -> tự gọi. Hết tin (rỗng/toàn trùng) -> dừng. */
    fun loadMore() {
        val ref = moreRef ?: return
        if (_loading.value || _loadingMore.value || _endReached.value) return
        viewModelScope.launch {
            _loadingMore.value = true
            try {
                val next = page + 1
                val more = BongDaPlusScraper.fetchCategoryMore(
                    ref, next, slug, cookiesOf(cookieProvider))
                if (more.isEmpty()) {
                    _endReached.value = true
                } else {
                    val ids = _articles.value.map { it.id }.toSet()
                    val fresh = more.filter { it.id !in ids }
                    if (fresh.isEmpty()) _endReached.value = true
                    else {
                        page = next
                        _articles.value = _articles.value + fresh
                    }
                }
            } catch (_: Exception) { }
            _loadingMore.value = false
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
     * Thích/Không thích CÚP web (bongdaplus.js): web KHÔNG đọc lại state từ
     * server sau mỗi bấm (API count/state bị cache -> đọc lại luôn sai ở lần 2,
     * làm lệch nút hoàn tác). Web toggle TRẠNG THÁI CỤC BỘ (cookie thumup{id})
     * rồi POST 1 lần, tin HTTP 200. App làm y hệt:
     *  - _myVotes là source of truth cho nút (bấm 1=like, 2=undo, 3=like...)
     *  - số đếm cập nhật lạc quan ngay (đúng như DOM web), KHÔNG reload
     *  - POST 200 là xong; lỗi mạng/bounced thì revert lại snapshot.
     */
    fun reactComment(commentId: String, like: Boolean) {
        val d = _detail.value ?: return
        val cur = _myVotes.value.toMutableMap()
        val active = cur[commentId] ?: 0
        val undo = (like && active == 1) || (!like && active == 7)
        // Snapshot để revert khi POST lỗi
        val snapComments = _comments.value
        val snapVotes = _myVotes.value
        // 1) UI lạc quan: gỡ vote cũ, áp vote mới (trừ khi hoàn tác)
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
        // 2) POST 1 lần (web tin 200). Không reload — reload dính cache là lệch.
        viewModelScope.launch {
            val code = try {
                BongDaPlusScraper.setCommentEmotion(
                    d.objectId, commentId, like, cookiesOf(cookieProvider),
                    d.article.url, d.objectType,
                    prevActive = active, isUndo = undo)
            } catch (_: Exception) { 0 }
            if (code != 200) {
                _comments.value = snapComments
                _myVotes.value = snapVotes
                _sendMsg.value = "👍/👎 thất bại — HTTP $code, thử lại."
            }
        }
    }

    /** Cảm xúc bài viết kiểu web: bấm lại cảm xúc đang chọn = gỡ. */
    fun reactArticle(emotionType: Int) {
        val d = _detail.value ?: return
        val undo = _myEmotion.value == emotionType
        val prevEmotion = d.emotion
        val prevMyEmo = _myEmotion.value
        // 1) UI lạc quan: đổi nút đang chọn + cộng/trừ số đếm đúng loại
        _myEmotion.value = if (undo) 0 else emotionType
        fun delta(cur: Emotion, type: Int, up: Boolean): Emotion {
            val v = when (type) {
                1 -> cur.liked
                2 -> cur.heart
                4 -> cur.wow
                else -> 0
            }
            val nv = (v + (if (up) 1 else -1)).coerceAtLeast(0)
            return when (type) {
                1 -> cur.copy(liked = nv)
                2 -> cur.copy(heart = nv)
                4 -> cur.copy(wow = nv)
                else -> cur
            }
        }
        // Nếu đang chọn cảm xúc khác, trước tiên gỡ cái cũ (giảm -1 bên cũ)
        var e = d.emotion
        if (!undo && prevMyEmo != 0 && prevMyEmo != emotionType) e = delta(e, prevMyEmo, up = false)
        e = delta(e, emotionType, up = !undo)
        _detail.value = d.copy(emotion = e)
        // 2) POST 1 lần (web tin 200). Không reload.
        viewModelScope.launch {
            val code = try {
                BongDaPlusScraper.setNewsEmotion(
                    d.objectId, d.objectType, emotionType,
                    cookiesOf(cookieProvider), d.article.url)
            } catch (_: Exception) { 0 }
            if (code != 200) {
                _detail.value = d.copy(emotion = prevEmotion)
                _myEmotion.value = prevMyEmo
                _sendMsg.value = "Cảm xúc thất bại — HTTP $code, thử lại."
            }
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

/** Tab Tỉ số: Lịch thi đấu / Kết quả / BXH theo giải (API data JSON của web). */
class ScoresViewModel : ViewModel() {
    val tab = MutableStateFlow(0)          // 0=Lịch, 1=Kết quả, 2=BXH
    val comp = MutableStateFlow<String?>(null)  // null=Tất cả (Lịch/KQ); BXH mặc định V.League
    private val _matches = MutableStateFlow<List<ScoreMatch>>(emptyList())
    val matches: StateFlow<List<ScoreMatch>> = _matches
    private val _standings = MutableStateFlow<List<StandingRow>>(emptyList())
    val standings: StateFlow<List<StandingRow>> = _standings
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setTab(i: Int) {
        if (tab.value == i) return
        tab.value = i
        // Vào BXH mà đang chọn "Tất cả" -> mặc định V.League
        if (i == 2 && comp.value == null) comp.value = "bong-da-viet-nam"
        load()
    }

    fun setComp(slug: String?) {
        if (comp.value == slug) return
        comp.value = slug
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                when (tab.value) {
                    2 -> {
                        val slug = comp.value ?: "bong-da-viet-nam"
                        val rows = ScoresApi.standings(slug)
                        _standings.value = rows
                        if (rows.isEmpty()) {
                            _error.value = if (slugHasRank(slug)) "Chưa có dữ liệu, thử lại sau."
                            else "Giải này không có bảng xếp hạng."
                        }
                    }
                    1 -> {
                        _matches.value = ScoresApi.results(comp.value)
                        if (_matches.value.isEmpty()) _error.value = "Chưa có kết quả."
                    }
                    else -> {
                        _matches.value = ScoresApi.fixtures(comp.value)
                        if (_matches.value.isEmpty()) _error.value = "Chưa có lịch thi đấu."
                    }
                }
            } catch (e: Exception) {
                _error.value = "Không tải được: ${e.message?.take(80)}"
            }
            _loading.value = false
        }
    }

    private var rankCache: Map<String, Boolean>? = null

    /** Giải có BXH không (để báo đúng: chưa có dữ liệu vs không có BXH). */
    private suspend fun slugHasRank(slug: String): Boolean {
        return try {
            var m = rankCache
            if (m == null) {
                m = ScoresApi.tournaments().associate { it.file to it.hasRank }
                rankCache = m
            }
            m[slug] ?: true
        } catch (_: Exception) { true }
    }
}

/** Lịch sử thông báo member thật (div#lstnoti) — ai thích/không thích bình luận của bạn */class NotifViewModel : ViewModel() {
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
