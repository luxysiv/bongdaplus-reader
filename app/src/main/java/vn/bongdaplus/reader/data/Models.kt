package vn.bongdaplus.reader.data

/** Một bài viết trên bongdaplus.vn */
data class Article(
    val id: String,          // vd: 5240612610 (đuôi URL) hoặc hash URL
    val title: String,
    val url: String,         // URL tuyệt đối
    val imageUrl: String? = null,
    val category: String? = null,
    val summary: String? = null,
    val time: String? = null
)

data class ArticleDetail(
    val article: Article,
    val author: String? = null,
    val publishedAt: String? = null,
    val bodyHtml: String = "",
    val bodyText: String = ""
)

/** Danh mục (slug lấy từ menu thật của bongdaplus.vn) */
data class Category(val name: String, val slug: String)

val CATEGORIES = listOf(
    Category("Mới nhất", "tin-moi"),
    Category("Việt Nam", "bong-da-viet-nam"),
    Category("Ngoại hạng Anh", "ngoai-hang-anh"),
    Category("Chuyển nhượng", "tin-chuyen-nhuong"),
    Category("Champions League", "champions-league-cup-c1"),
    Category("La Liga", "la-liga"),
    Category("Serie A", "serie-a"),
    Category("Bundesliga", "bundesliga"),
    Category("Ligue 1", "ligue-1"),
    Category("Nhận định", "nhan-dinh-bong-da-tags"),
    Category("Hậu trường", "hau-truong-bong-da"),
    Category("Thế giới", "bong-da-the-gioi"),
    Category("Video", "video"),
)

/** Tên hiển thị của chuyên mục từ slug */
fun catName(slug: String?): String =
    CATEGORIES.find { it.slug == slug }?.name ?: "Bóng đá"

/** Cache RAM: prefill chi tiết khi bấm từ list để khỏi chờ load */
object ArticleCache {
    private val map = LinkedHashMap<String, Article>()
    fun put(a: Article) { map[a.id] = a; if (map.size > 100) map.remove(map.keys.first()) }
    fun get(id: String): Article? = map[id]
}
