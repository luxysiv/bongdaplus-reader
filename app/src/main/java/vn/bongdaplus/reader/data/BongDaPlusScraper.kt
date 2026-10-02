package vn.bongdaplus.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Scraper cho https://bongdaplus.vn
 * Site không có API/RSS công khai nên parse HTML bằng Jsoup.
 * Có gửi kèm cookie đăng nhập (nếu có) để đọc Premium + gửi bình luận.
 *
 * API bình luận thật (phân tích từ /js/bongdaplus.js):
 * - GET  /getNewsEmotion/{objectId}/{objectType} -> JSON cảm xúc + số bình luận
 * - GET  /binh-luan/{objectId}/{objectType}/{page}/{type} -> HTML list bình luận
 * - POST /postcomment/ {objectid,objecttype,parentid,replyid,replyname,comment} (cần login)
 */
object BongDaPlusScraper {
    const val BASE = "https://bongdaplus.vn"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 BongDaPlusReader/1.0"

    fun absUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> BASE + href
        else -> "$BASE/$href"
    }

    fun idFromUrl(url: String): String {
        // ...-5240612610.html -> 5240612610
        val m = Regex("(\\d{6,})\\.html").find(url)
        if (m != null) return m.groupValues[1]
        return url.hashCode().toString()
    }

    private fun absImg(src: String?): String? {
        if (src.isNullOrBlank()) return null
        var s = src.trim()
        if (s.startsWith("//")) s = "https:$s"
        else if (s.startsWith("/")) s = BASE + s
        return s.takeIf { it.startsWith("http") }
    }

    private fun parseCardList(doc: org.jsoup.nodes.Document, category: String?): List<Article> {
        val out = LinkedHashMap<String, Article>()
        // 1) Selector chính xác theo markup thật: div.news > a.title / a.thumb img / span.info
        for (card in doc.select("div.news, li.news, article")) {
            val aTitle = card.selectFirst("a.title") ?: card.selectFirst("h2 a, h3 a") ?: continue
            val href = aTitle.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#") || !href.contains(".html")) continue
            val url = absUrl(href)
            if (!url.contains("bongdaplus.vn")) continue
            val title = aTitle.attr("title").ifBlank { aTitle.text().trim() }
            if (title.length < 12) continue
            val id = idFromUrl(url)
            if (out.containsKey(id)) continue
            val img = absImg(
                card.selectFirst("a.thumb img, img")?.let {
                    it.attr("abs:src").ifBlank { it.attr("abs:data-src") }
                }
            )
            val time = card.select("span.info span, span.time, time").map { it.text().trim() }
                .firstOrNull { it.length in 4..48 }
            out[id] = Article(id, title, url, img, category, time = time?.ifBlank { null })
            if (out.size >= 40) break
        }
        // 2) Fallback tolerant nếu layout đổi
        if (out.isEmpty()) {
            for (a in doc.select("a[href*=\".html\"]")) {
                val href = a.attr("href").trim()
                if (href.isEmpty() || href.startsWith("#")) continue
                val url = absUrl(href)
                if (!url.contains("bongdaplus.vn")) continue
                val title = a.attr("title").ifBlank { a.text().trim() }
                if (title.length < 12) continue
                val id = idFromUrl(url)
                if (out.containsKey(id)) continue
                val img = absImg(a.selectFirst("img")?.attr("abs:src"))
                out[id] = Article(id, title, url, img, category)
                if (out.size >= 40) break
            }
        }
        // category hiển thị: MetaLine dùng catName(slug); giữ nguyên slug đã truyền vào
        return out.values.toList()
    }

    suspend fun fetchHome(cookies: Map<String, String> = emptyMap()): List<Article> =
        withContext(Dispatchers.IO) {
            val doc = Jsoup.connect("$BASE/")
                .userAgent(UA).timeout(20000).cookies(cookies).get()
            parseCardList(doc, null)
        }

    suspend fun fetchCategory(slug: String, cookies: Map<String, String> = emptyMap()): List<Article> =
        withContext(Dispatchers.IO) {
            val doc = Jsoup.connect("$BASE/$slug")
                .userAgent(UA).timeout(20000).cookies(cookies).get()
            parseCardList(doc, slug)
        }

    // ---------- Chi tiết: parse native blocks từ div.content ----------

    private fun parseBlocks(bodyEl: Element): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        fun imgUrl(el: Element): String? = absImg(
            el.attr("abs:src").ifBlank { el.attr("abs:data-src").ifBlank { el.attr("abs:data-original") } }
        )
        fun walk(el: Element) {
            when (el.tagName()) {
                "p" -> {
                    val imgs = el.select("img")
                    val t = el.ownText().trim().ifBlank { el.text().trim() }
                    if (imgs.isNotEmpty()) {
                        for (im in imgs) imgUrl(im)?.let { blocks += ContentBlock.Image(it, im.attr("alt").ifBlank { null }) }
                        if (t.length > 20) blocks += ContentBlock.Paragraph(t)
                    } else if (t.length >= 2 && !t.startsWith("Nguồn")) {
                        blocks += ContentBlock.Paragraph(t)
                    }
                }
                "h2", "h3", "h4" -> {
                    val t = el.text().trim()
                    if (t.length >= 2) blocks += ContentBlock.Heading(t)
                }
                "img" -> imgUrl(el)?.let { blocks += ContentBlock.Image(it, el.attr("alt").ifBlank { null }) }
                "blockquote" -> {
                    val t = el.text().trim()
                    if (t.length >= 2) blocks += ContentBlock.Quote(t)
                }
                "li" -> {
                    val t = el.text().trim()
                    if (t.length >= 2) blocks += ContentBlock.Bullet(t)
                }
                "div", "section", "article", "figure", "figcaption" -> {
                    if (el.className().contains("ads", true) || el.className().contains("banner", true)) return
                    for (c in el.children()) walk(c)
                }
                "ul", "ol" -> for (c in el.children()) walk(c)
            }
        }
        for (c in bodyEl.children()) walk(c)
        // gom caption trùng ngay sau ảnh (alt đã là caption nên bỏ paragraph trùng)
        return blocks.filter {
            when (it) {
                is ContentBlock.Paragraph -> it.text.length > 1
                else -> true
            }
        }.take(300)
    }

    suspend fun fetchDetail(url: String, cookies: Map<String, String> = emptyMap()): ArticleDetail =
        withContext(Dispatchers.IO) {
            val doc = Jsoup.connect(url).userAgent(UA).timeout(20000).cookies(cookies).get()
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Bài viết"
            val ogImg = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val bodyEl = doc.selectFirst("div.content")
                ?: doc.selectFirst("div.article-content")
                ?: doc.selectFirst("article")
                ?: doc.body()
            bodyEl.select("script, style").remove()
            val blocks = if (bodyEl.tagName() == "body") emptyList() else parseBlocks(bodyEl)
            // tác giả + giờ từ JSON-LD NewsArticle (chuẩn nhất)
            val ld = doc.select("script[type=application/ld+json]").map { it.html() }
                .firstOrNull { it.contains("NewsArticle") } ?: ""
            val author = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(
                ld.substringAfter("\"author\"").take(300)
            )?.groupValues?.get(1)
                ?: doc.selectFirst(".author-name, .author")?.text()?.trim()
            val published = Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").find(ld)?.groupValues?.get(1)
                ?: doc.selectFirst("meta[property=article:published_time]")?.attr("content")
                ?: doc.selectFirst("time")?.text()?.trim()
            val objectId = doc.selectFirst("#objectid")?.attr("value") ?: ""
            val objectType = doc.selectFirst("#objecttype")?.attr("value") ?: "0"
            val catSlug = doc.selectFirst("#catrefid")?.attr("value")?.ifBlank { null }
            val id = idFromUrl(url)
            val base = Article(id, title, url, ogImg, catSlug)
            val emotion = try { fetchEmotion(objectId, objectType, cookies) } catch (_: Exception) { Emotion() }
            ArticleDetail(base, author, published, bodyEl.html(), bodyEl.text().take(600),
                blocks, objectId, objectType, emotion)
        }

    /** Chỉ lấy objectId/objectType (nhẹ, cho worker đếm bình luận) */
    suspend fun fetchObjectRef(url: String, cookies: Map<String, String> = emptyMap()): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect(url).userAgent(UA).timeout(20000).cookies(cookies).get()
                val oid = doc.selectFirst("#objectid")?.attr("value") ?: return@withContext null
                if (oid.isBlank()) return@withContext null
                oid to (doc.selectFirst("#objecttype")?.attr("value") ?: "0")
            } catch (_: Exception) { null }
        }

    // ---------- Bình luận thật ----------

    suspend fun fetchEmotion(objectId: String, objectType: String = "0",
                             cookies: Map<String, String> = emptyMap()): Emotion =
        withContext(Dispatchers.IO) {
            if (objectId.isBlank()) return@withContext Emotion()
            val body = Jsoup.connect("$BASE/getNewsEmotion/$objectId/$objectType")
                .userAgent(UA).timeout(15000).cookies(cookies)
                .ignoreContentType(true).get().body().text()
            val o = JSONObject(body).optJSONObject("newsUserActivity") ?: return@withContext Emotion()
            Emotion(o.optInt("liked"), o.optInt("heart"), o.optInt("wow"), o.optInt("comments"))
        }

    suspend fun fetchComments(objectId: String, objectType: String = "0", page: Int = 1,
                              cookies: Map<String, String> = emptyMap()): List<Comment> =
        withContext(Dispatchers.IO) {
            if (objectId.isBlank()) return@withContext emptyList()
            val html = Jsoup.connect("$BASE/binh-luan/$objectId/$objectType/$page/0")
                .userAgent(UA).timeout(15000).cookies(cookies)
                .ignoreContentType(true).get().body().html()
            val frag = Jsoup.parseBodyFragment(html)
            frag.select("li.comment").mapNotNull { li ->
                val likeA = li.selectFirst("a[id^=btnlikecmt_]")
                val cid = likeA?.attr("id")?.substringAfter("btnlikecmt_") ?: return@mapNotNull null
                val name = li.selectFirst("a.member")?.text()?.trim().ifNullOrBlank { "Bạn đọc" }
                val info = li.selectFirst("div.info")?.text() ?: ""
                val time = info.substringAfter(name).trim().ifBlank { "" }
                val text = li.selectFirst("p.summ")?.text()?.trim() ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                Comment(cid, name, time, text,
                    li.selectFirst("span[id^=thumup]")?.text()?.toIntOrNull() ?: 0,
                    li.selectFirst("span[id^=thumdw]")?.text()?.toIntOrNull() ?: 0)
            }
        }

    /** Gửi bình luận thật (cần cookie login). True = server đã nhận (chờ duyệt). */
    suspend fun postComment(objectId: String, objectType: String, text: String,
                            cookies: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val res = Jsoup.connect("$BASE/postcomment/")
                    .userAgent(UA).timeout(15000).cookies(cookies)
                    .data("objectid", objectId, "objecttype", objectType,
                        "parentid", "0", "replyid", "0", "replyname", "", "comment", text)
                    .ignoreContentType(true).post()
                val b = res.body().text()
                b.contains("duyệt", true) || b.contains("thành công", true) || b.isNotBlank()
            } catch (_: Exception) { false }
        }

    private fun String?.ifNullOrBlank(def: () -> String): String =
        if (this.isNullOrBlank()) def() else this
}
