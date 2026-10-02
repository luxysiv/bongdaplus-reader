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
