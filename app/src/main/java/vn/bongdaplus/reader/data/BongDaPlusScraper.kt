package vn.bongdaplus.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Scraper cho https://bongdaplus.vn
 * Site không có API/RSS công khai nên parse HTML bằng Jsoup.
 * Có gửi kèm cookie đăng nhập (nếu có) để đọc được bài Premium.
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

    private fun parseCardList(doc: org.jsoup.nodes.Document, category: String?): List<Article> {
        val out = LinkedHashMap<String, Article>()
        // Thẻ a trỏ tới bài viết chi tiết (.html) — cách tolerant nhất với layout hay đổi
        for (a in doc.select("a[href*=\".html\"]")) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#")) continue
            if (href.contains("/video/") && a.text().isBlank()) continue
            val url = absUrl(href)
            if (!url.contains("bongdaplus.vn")) continue
            val title = a.attr("title").ifBlank { a.text().trim() }
            if (title.length < 12) continue
            // loại menu/nav trùng lặp
            if (title.length < 20 && out.size > 30) continue
            val id = idFromUrl(url)
            if (out.containsKey(id)) continue
            // ảnh: img trong thẻ a hoặc article cha
            var img: String? = a.selectFirst("img")?.let {
                it.attr("abs:src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-original") } }
            }
            if (img.isNullOrBlank()) {
                val parent = a.parents().firstOrNull { it.tagName() == "article" || it.className().contains("stor") || it.className().contains("item") }
                img = parent?.selectFirst("img")?.let {
                    it.attr("abs:src").ifBlank { it.attr("data-src") }
                }
            }
            if (img != null && img.startsWith("//")) img = "https:$img"
            if (img != null && !img.startsWith("http")) img = null
            out[id] = Article(id, title, url, img, category)
            if (out.size >= 40) break
        }
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

    suspend fun fetchDetail(url: String, cookies: Map<String, String> = emptyMap()): ArticleDetail =
        withContext(Dispatchers.IO) {
            val doc = Jsoup.connect(url).userAgent(UA).timeout(20000).cookies(cookies).get()
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Bài viết"
            val ogImg = doc.selectFirst("meta[property=og:image]")?.attr("content")
            // nội dung: thử nhiều selector vì site dùng nhiều template
            val bodyEl = doc.selectFirst("div.article-content")
                ?: doc.selectFirst("div.content-detail")
                ?: doc.selectFirst("article")
                ?: doc.selectFirst("div.detail-content")
                ?: doc.body()
            // dọn rác: script/style/quảng cáo
            bodyEl.select("script, style, iframe[src*=ads], .ads, .banner, .related-inline-script").remove()
            val imgs = bodyEl.select("img")
            for (img in imgs) {
                var src = img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
                if (src.startsWith("//")) src = "https:$src"
                img.attr("src", src)
                img.removeAttr("data-src")
                img.attr("style", "max-width:100%;height:auto;border-radius:8px;")
            }
            for (p in bodyEl.select("p, div, span")) p.removeAttr("style")
            val author = doc.selectFirst(".author-name, .author, [class*=author]")?.text()?.trim()
            val time = doc.selectFirst("time, .time, .date, [class*=publish]")?.text()?.trim()
                ?: doc.selectFirst("meta[property=article:published_time]")?.attr("content")
            val id = idFromUrl(url)
            val base = Article(id, title, url, ogImg)
            ArticleDetail(base, author, time, bodyEl.html(), bodyEl.text().take(600))
        }
}
