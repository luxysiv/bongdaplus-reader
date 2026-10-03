package vn.bongdaplus.reader.data

/** Một bài viết trên bongdaplus.vn */
data class Article(
    val id: String,          // vd: 5240612610 (đuôi URL) hoặc hash URL
    val title: String,
    val url: String,         // URL tuyệt đối
    val imageUrl: String? = null,
    val category: String? = null,
    val summary: String? = null,
    val time: String? = null,
    val comments: Int = 0,   // số bình luận web hiện kèm (tab "Bình luận nhiều")
)

data class ArticleDetail(
    val article: Article,
    val author: String? = null,
    val authorRole: String? = null,
    val authorAvatar: String? = null,
    val publishedAt: String? = null,
    val sapo: String = "",
    val tags: List<String> = emptyList(),
    val isVideo: Boolean = false,
    val bodyHtml: String = "",
    val bodyText: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val objectId: String = "",
    val objectType: String = "0",
    val emotion: Emotion = Emotion(),
)

/** Khối nội dung render native (thay WebView thô) */
sealed class ContentBlock {
    data class Paragraph(val text: String) : ContentBlock()
    data class Heading(val text: String) : ContentBlock()
    data class Image(val url: String, val caption: String? = null) : ContentBlock()
    data class Video(val embedUrl: String, val videoId: String? = null, val caption: String? = null) : ContentBlock()
    data class Quote(val text: String) : ContentBlock()
    data class Bullet(val text: String) : ContentBlock()
}

/** Cảm xúc + số bình luận từ /getNewsEmotion */
data class Emotion(
    val liked: Int = 0,
    val heart: Int = 0,
    val wow: Int = 0,
    val comments: Int = 0,
)

/** Bình luận thật từ /binh-luan */
data class Comment(
    val id: String,
    val name: String,
    val time: String,
    val text: String,
    val likes: Int = 0,
    val dislikes: Int = 0,
)

/**
 * Thông báo member thật từ div#lstnoti (khi đã đăng nhập).
 * VD: "Độc giả đã thích bình luận của bạn ở bài viết: Ronaldo..." + link tới #txtcomment_xxx.
 */
data class MemberNotification(
    val key: String,       // actor + action + url + time để chống báo trùng
    val actor: String,     // "Độc giả", "Hoang Cuong"...
    val action: String,    // "thích", "không thích", "trả lời"...
    val text: String,      // câu đầy đủ
    val url: String,       // link bài + neo #txtcomment_
    val time: String,      // "04 giờ trước"
)

/**
 * 1 dòng trong board "Bài mới bình luận" ở Dashboard member
 * (https://member.bongdaplus.vn/Identity/Account/Manage/DashBoard).
 * VD: bài "Ronaldo..." + comment "Dỗi vương" (#2299071) lúc "06:58 ngày 01/10/2026".
 */
data class MyCommented(
    val article: Article,
    val commentId: String,   // "2299071" (neo #... trong link)
    val commentUrl: String,  // link bài + #commentId
    val myText: String,      // nội dung comment của mình
    val time: String,        // giờ mình đã bình luận
)

/** Kết quả đăng nhập member bằng OkHttp (form Email/Mật khẩu native) */
sealed interface LoginResult {
    data class Ok(val name: String, val siteSession: Boolean) : LoginResult
    data class Invalid(val message: String) : LoginResult
    data object NetworkError : LoginResult
}

/** Danh mục (slug lấy từ menu thật của bongdaplus.vn, chỉ bóng đá) */
data class Category(val name: String, val slug: String)

/** Nhóm chuyên mục y menu hamburger của web (không bóng đá phủi/tennis/esports...). */
data class CategoryGroup(val name: String, val cats: List<Category>)

val CATEGORY_GROUPS = listOf(
    CategoryGroup("Tin mới", listOf(
        Category("Mới nhất", "tin-moi"),
        Category("Điểm tin", "diem-tin"),
        Category("Bóng đá & Cuộc sống", "bong-da-cuoc-song"),
        Category("Big Story", "bigstory"),
    )),
    CategoryGroup("Bóng đá Việt Nam", listOf(
        Category("Việt Nam", "bong-da-viet-nam"),
        Category("Đội tuyển Việt Nam", "doi-tuyen-quoc-gia-viet-nam"),
        Category("V.League", "v-league"),
        Category("Cúp Quốc gia", "cup-quoc-gia"),
        Category("Hạng Nhất", "hang-nhat-quoc-gia"),
        Category("Bóng đá nữ", "bong-da-nu-viet-nam"),
        Category("Futsal", "futsal"),
        Category("CN V-League", "tin-chuyen-nhuong-v-league"),
    )),
    CategoryGroup("Bóng đá Anh", listOf(
        Category("Anh", "bong-da-anh"),
        Category("Ngoại hạng Anh", "ngoai-hang-anh"),
        Category("Đội tuyển Anh", "doi-tuyen-anh"),
        Category("FA Cup", "fa-cup"),
        Category("League Cup", "cup-lien-doan-anh"),
    )),
    CategoryGroup("Cúp châu Âu", listOf(
        Category("Champions League", "champions-league-cup-c1"),
        Category("Europa League", "europa-league"),
    )),
    CategoryGroup("Bóng đá Tây Ban Nha", listOf(
        Category("Tây Ban Nha", "bong-da-tay-ban-nha"),
        Category("La Liga", "la-liga"),
    )),
    CategoryGroup("Bóng đá Đức", listOf(
        Category("Bundesliga", "bundesliga"),
    )),
    CategoryGroup("Bóng đá Pháp", listOf(
        Category("Pháp", "bong-da-phap"),
        Category("Ligue 1", "ligue-1"),
        Category("Cúp QG Pháp", "cup-quoc-gia-phap"),
    )),
    CategoryGroup("Bóng đá Italia", listOf(
        Category("Italia", "bong-da-y"),
        Category("Serie A", "serie-a"),
        Category("Coppa Italia", "coppa-italia"),
        Category("Đội tuyển Italia", "doi-tuyen-y"),
    )),
    CategoryGroup("Đội tuyển", listOf(
        Category("World Cup", "world-cup"),
        Category("Nations League", "nations-league"),
        Category("Copa America", "copa-america"),
    )),
    CategoryGroup("Bóng đá khu vực", listOf(
        Category("Asian Cup", "asian-cup"),
        Category("AFF Cup", "aff-cup"),
        Category("SEA Games", "sea-games"),
    )),
    CategoryGroup("Chuyển nhượng", listOf(
        Category("Chuyển nhượng", "tin-chuyen-nhuong"),
    )),
    CategoryGroup("Chuyên đề", listOf(
        Category("Nhận định", "nhan-dinh-bong-da-tags"),
        Category("Hậu trường", "hau-truong-bong-da"),
        Category("Thế giới", "bong-da-the-gioi"),
    )),
    CategoryGroup("Multimedia", listOf(
        Category("Video", "video"),
    )),
)

val CATEGORIES: List<Category> = CATEGORY_GROUPS.flatMap { it.cats }

/** Chuyên mục bên lề: không đưa lên dòng Tin mới nhất trang chủ. */
val HOME_EXCLUDED_SLUGS = setOf("nhan-dinh-bong-da-tags", "hau-truong-bong-da")

/** Tên hiển thị của chuyên mục từ slug */
fun catName(slug: String?): String =
    CATEGORIES.find { it.slug == slug }?.name ?: "Bóng đá"

/** Chuẩn hóa Unicode NFC (sửa lỗi font tiếng Việt khi text chứa dấu tổ hợp rời). */
fun String.nfcVi(): String = try {
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)
} catch (_: Exception) { this }

/**
 * Giải HTML entities thô (vd M&#x1EA1;nh -> Mạnh).
 * Regex đọc trên HTML thô không tự giải entity như Jsoup text(), nên tên
 * lấy bằng regex (tên hiển thị sau đăng nhập, tác giả...) phải qua hàm này.
 */
fun String.unescapeHtml(): String = try {
    org.jsoup.parser.Parser.unescapeEntities(this, false)
} catch (_: Exception) { this }

/** Cache RAM: prefill chi tiết khi bấm từ list để khỏi chờ load */
object ArticleCache {
    private val map = LinkedHashMap<String, Article>()
    fun put(a: Article) { map[a.id] = a; if (map.size > 100) map.remove(map.keys.first()) }
    fun get(id: String): Article? = map[id]
}
