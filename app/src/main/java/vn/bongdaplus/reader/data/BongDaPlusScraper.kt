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

    /** Tách YouTube videoId từ URL embed/watch (youtube.com/embed/ID, youtu.be/ID, watch?v=ID) */
    fun extractYoutubeId(src: String): String? {
        if (src.isBlank()) return null
        val patterns = listOf(
            Regex("youtube\\.com/embed/([A-Za-z0-9_-]{6,})"),
            Regex("youtube\\.com/watch\\?v=([A-Za-z0-9_-]{6,})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{6,})")
        )
        for (p in patterns) {
            val m = p.find(src)
            if (m != null) return m.groupValues[1]
        }
        return null
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

    /**
     * Tải Document bằng OkHttp (cookie đọc xuyên từ WebView login).
     * Lỗi mạng thì rớt về Jsoup thuần như cũ.
     */
    private suspend fun loadDoc(
        url: String, cookies: Map<String, String>,
    ): org.jsoup.nodes.Document {
        try {
            val p = Http.get(url)
            if (p != null && p.html.contains("<html", true)) {
                return org.jsoup.Jsoup.parse(p.html, url)
            }
        } catch (_: Exception) { }
        return Jsoup.connect(url).userAgent(UA).timeout(20000).cookies(cookies).get()
    }

    suspend fun fetchHome(cookies: Map<String, String> = emptyMap()): List<Article> =
        withContext(Dispatchers.IO) {
            val doc = loadDoc("$BASE/", cookies)
            parseCardList(doc, null)
        }

    suspend fun fetchCategory(slug: String, cookies: Map<String, String> = emptyMap()): List<Article> =
        withContext(Dispatchers.IO) {
            val doc = loadDoc("$BASE/$slug", cookies)
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
                "iframe" -> {
                    // video nhúng trong thân bài (thường là YouTube)
                    val src = el.attr("abs:src").ifBlank { el.attr("src") }.trim()
                    val vid = extractYoutubeId(src)
                    if (vid != null) blocks += ContentBlock.Video(src, vid, null)
                }
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
            val doc = loadDoc(url, cookies)
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Bài viết"
            var ogImg = doc.selectFirst("meta[property=og:image]")?.attr("content")
            // Trang video (/video/...): player là iframe trong div.play-box —
            // tin thường là YouTube embed, highlight là streaming.bongdaplus.vn/embed/...
            // Mô tả ở div.clip-info p.desc (không có div.content).
            val videoSrc = doc.selectFirst("div.play-box iframe[src], iframe.play-frame[src]")
                ?.attr("src")?.trim().orEmpty()
                .ifBlank {
                    doc.selectFirst("div.play-box iframe[data-src]")?.attr("data-src")?.trim().orEmpty()
                }
            val ytId = extractYoutubeId(videoSrc)
            val hasVideo = videoSrc.isNotBlank()
            val clipDesc = doc.selectFirst("div.clip-info p.desc")?.text()?.trim().orEmpty()
            val bodyEl = doc.selectFirst("#postContent.content")
                ?: doc.selectFirst("div.content")
                ?: doc.selectFirst("div.clip-info")
                ?: doc.selectFirst("div.article-content")
                ?: doc.selectFirst("section.media-box")
                ?: doc.selectFirst("article")
                ?: doc.body()
            bodyEl.select("script, style").remove()
            val blocks = when {
                hasVideo -> buildList {
                    add(ContentBlock.Video(videoSrc, ytId, title))
                    // clip-info chứa đúng 3 thứ: tiêu đề + mô tả + giờ đăng.
                    // Bỏ đoạn trùng tiêu đề và dòng giờ (đã hiện ở header) để khỏi rác.
                    val skipTitle = title.trim()
                    val timeRe = Regex("""\d{1,2}:\d{2}\s*-\s*\d{1,2}/\d{1,2}/\d{4}""")
                    fun keep(t: String) = t.isNotBlank() && t.length > 2 &&
                        t != skipTitle && !timeRe.containsMatchIn(t)
                    if (keep(clipDesc)) add(ContentBlock.Paragraph(clipDesc))
                    // các đoạn chữ còn lại trong clip-info (tags nằm ngoài div này nên an toàn)
                    bodyEl.select("p").forEach {
                        val t = it.text().trim()
                        if (t != clipDesc && keep(t)) add(ContentBlock.Paragraph(t))
                    }
                }.take(50)
                bodyEl.tagName() == "body" -> emptyList()
                else -> parseBlocks(bodyEl)
            }
            if (ytId != null && (ogImg.isNullOrBlank() || ogImg.contains("logo"))) {
                ogImg = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
            }
            // tác giả + giờ từ JSON-LD NewsArticle (chuẩn nhất, web thật luôn có)
            val ld = doc.select("script[type=application/ld+json]").map { it.html() }
                .firstOrNull { it.contains("NewsArticle") } ?: ""
            val author = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(
                ld.substringAfter("\"author\"").take(300)
            )?.groupValues?.get(1)
                ?: doc.selectFirst(".author-name, .author, .author-info .name")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("meta[name=author]")?.attr("content")?.ifBlank { null }
            // Avatar + chức danh tác giả từ khối ld+json Person thứ 2 (web thật có)
            val personLd = doc.select("script[type=application/ld+json]").map { it.html() }
                .firstOrNull { it.contains("\"Person\"") && it.contains("jobTitle") } ?: ""
            val authorAvatar = Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(personLd)
                ?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
                ?: doc.selectFirst(".author-info img, .author img")?.attr("abs:src")?.takeIf { it.startsWith("http") }
            val authorRole = Regex("\"jobTitle\"\\s*:\\s*\"([^\"]+)\"").find(personLd)
                ?.groupValues?.get(1)
                ?: doc.selectFirst(".author-info .role, .author .role")?.text()?.trim()?.ifBlank { null }
            val published = Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").find(ld)?.groupValues?.get(1)
                ?: doc.selectFirst("meta[property=article:published_time]")?.attr("content")
                ?: doc.selectFirst("time[datetime]")?.attr("datetime")?.ifBlank { null }
                ?: doc.selectFirst("time")?.text()?.trim()
            // Sapo (đoạn mở đầu in đậm kiểu báo): og:description / ld description / h2.sapo
            val sapo = doc.selectFirst("h2.sapo, .sapo, .lead, .summary")?.text()?.trim()?.takeIf { it.length > 10 }
                ?: Regex("\"description\"\\s*:\\s*\"([^\"]{20,500})\"").find(ld)?.groupValues?.get(1)?.trim()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
            // Tags bài viết: CHỈ trong div.hash-tags của bài (web thật).
            // Không dùng a[href*=-tags] toàn trang vì dính link menu
            // (nhan-dinh-bong-da-tags, cup-lien-doan-phap-tags...).
            val tags = try {
                doc.select("div.hash-tags a").map { it.text().trim() }
                    .filter { it.length in 2..40 }.distinct().take(8)
            } catch (_: Exception) { emptyList() }
            // .mht thật: không còn #objectid/#objecttype trong HTML tĩnh (render bằng JS).
            // Fallback dùng id số đuôi URL (vd ...-5239382610.html) + objectType=1 (tin tức).
            val rawOid = doc.selectFirst("#objectid")?.attr("value")?.trim() ?: ""
            val rawOtype = doc.selectFirst("#objecttype")?.attr("value")?.trim() ?: ""
            val id = idFromUrl(url)
            val objectId = rawOid.ifBlank { id.filter { it.isDigit() }.ifBlank { id } }
            // Web thật: tin tức type=0, video type=1. Đoán theo URL khi thiếu.
            val objectType = rawOtype.ifBlank { if (url.contains("/video/")) "1" else "0" }
            val catSlug = doc.selectFirst("#catrefid")?.attr("value")?.ifBlank { null }
            val base = Article(id, title, url, ogImg, catSlug)
            // Ưu tiên API, fallback đếm inline từ .mht (a.emo.comment#ncmt_*, #numemo*)
            val emotion = try { fetchEmotion(objectId, objectType, cookies) } catch (_: Exception) { Emotion() }
                .takeIf { it.comments > 0 || it.liked > 0 } ?: parseInlineEmotion(doc)
            val isVideoPage = hasVideo || url.contains("/video/") || catSlug == "video"
            ArticleDetail(base, author, authorRole, authorAvatar, published, sapo, tags, isVideoPage,
                bodyEl.html(), bodyEl.text().take(600),
                blocks, objectId, objectType, emotion)
        }

    /** Đếm inline từ HTML thật (file .mht): a.emo.comment#ncmt_*, span#numemo* */
    fun parseInlineEmotion(doc: org.jsoup.nodes.Document): Emotion {
        return try {
            val cmtTxt = doc.selectFirst("a.emo.comment")?.text()?.filter { it.isDigit() } ?: ""
            val likeTxt = doc.selectFirst("span[id^=numemo]")?.text()?.filter { it.isDigit() } ?: ""
            Emotion(liked = likeTxt.toIntOrNull() ?: 0, comments = cmtTxt.toIntOrNull() ?: 0)
        } catch (_: Exception) { Emotion() }
    }

    /** Bình luận render sẵn trong #NewsComments (file .mht Ronaldo) — dùng khi API /binh-luan lỗi */
    fun parseInlineComments(doc: org.jsoup.nodes.Document): List<Comment> {
        return try {
            doc.select("#NewsComments li.comment").mapNotNull { li ->
                val likeA = li.selectFirst("a[id^=btnlikecmt_]")
                val cid = likeA?.attr("id")?.substringAfter("btnlikecmt_") ?: return@mapNotNull null
                val name = li.selectFirst("a.member")?.text()?.trim().ifNullOrBlank { "Bạn đọc" }
                val info = li.selectFirst("div.info")?.text() ?: ""
                val time = info.substringAfter(name).trim().ifBlank { "" }
                val text = li.selectFirst("p.summ")?.text()?.trim() ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                Comment(cid, name, time, text,
                    li.selectFirst("span[id^=thumup]")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                    li.selectFirst("span[id^=thumdw]")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0)
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Chỉ lấy objectId/objectType (nhẹ, cho worker đếm bình luận) */
    suspend fun fetchObjectRef(url: String, cookies: Map<String, String> = emptyMap()): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val doc = loadDoc(url, cookies)
                val oid = doc.selectFirst("#objectid")?.attr("value")?.trim()
                    ?.ifBlank { null } ?: idFromUrl(url).filter { it.isDigit() }
                if (oid.isBlank()) return@withContext null
                // Web thật: tin tức type=0, video type=1 (#objecttype trên trang).
                // Mặc định đoán theo URL khi trang thiếu input (không dò chéo,
                // tránh lấy nhầm số liệu của bài khác cùng dãy id).
                val otype = doc.selectFirst("#objecttype")?.attr("value")?.trim()?.ifBlank { null }
                    ?: if (url.contains("/video/")) "1" else "0"
                oid to otype
            } catch (_: Exception) {
                // Mất mạng / timeout vẫn trả fallback từ URL để Worker không bỏ qua
                try {
                    val fb = idFromUrl(url).filter { it.isDigit() }
                    if (fb.isNotBlank()) fb to "1" else null
                } catch (_: Exception) { null }
            }
        }

    // ---------- Bình luận thật ----------

    suspend fun fetchEmotion(objectId: String, objectType: String = "1",
                             cookies: Map<String, String> = emptyMap()): Emotion =
        withContext(Dispatchers.IO) {
            if (objectId.isBlank()) return@withContext Emotion()
            // Dùng ĐÚNG objectType của bài (web thật: tin=0, video=1).
            // Tuyệt đối không dò chéo type khác: dãy id số 2 loại có thể trùng
            // nhau, dò chéo sẽ lấy nhầm cảm xúc/bình luận của bài khác.
            try {
                val body = Http.get("$BASE/getNewsEmotion/$objectId/$objectType")?.html
                    ?: Jsoup.connect("$BASE/getNewsEmotion/$objectId/$objectType")
                        .userAgent(UA).timeout(15000).cookies(cookies)
                        .ignoreContentType(true).get().body().text()
                val o = JSONObject(body).optJSONObject("newsUserActivity") ?: return@withContext Emotion()
                Emotion(o.optInt("liked"), o.optInt("heart"), o.optInt("wow"), o.optInt("comments"))
            } catch (_: Exception) { Emotion() }
        }

    suspend fun fetchComments(objectId: String, objectType: String = "1", page: Int = 1,
                              cookies: Map<String, String> = emptyMap()): List<Comment> =
        withContext(Dispatchers.IO) {
            if (objectId.isBlank()) return@withContext emptyList()
            fun parseFrag(html: String): List<Comment> {
                val frag = Jsoup.parseBodyFragment(html)
                return frag.select("li.comment").mapNotNull { li ->
                    val likeA = li.selectFirst("a[id^=btnlikecmt_]")
                    val cid = likeA?.attr("id")?.substringAfter("btnlikecmt_") ?: return@mapNotNull null
                    val name = li.selectFirst("a.member")?.text()?.trim().ifNullOrBlank { "Bạn đọc" }
                    val info = li.selectFirst("div.info")?.text() ?: ""
                    val time = info.substringAfter(name).trim().ifBlank { "" }
                    val text = li.selectFirst("p.summ")?.text()?.trim() ?: return@mapNotNull null
                    if (text.isBlank()) return@mapNotNull null
                    Comment(cid, name, time, text,
                        li.selectFirst("span[id^=thumup]")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                        li.selectFirst("span[id^=thumdw]")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0)
                }
            }
            // Dùng ĐÚNG objectType của bài, không dò chéo (lý do như trên).
            try {
                val html = Http.get("$BASE/binh-luan/$objectId/$objectType/$page/0")?.html
                    ?: Jsoup.connect("$BASE/binh-luan/$objectId/$objectType/$page/0")
                        .userAgent(UA).timeout(15000).cookies(cookies)
                        .ignoreContentType(true).get().body().html()
                parseFrag(html)
            } catch (_: Exception) { emptyList() }
        }

    /**
     * Gửi bình luận thật bằng OkHttp (cookie đọc xuyên từ WebView login).
     * Chuẩn đoán bằng URL CUỐI sau redirect: server đá về Account/Login khi
     * chưa login; HTTP 200 = đã nhận (bình luận chờ duyệt). Không đoán body
     * nên không còn chuyện "gửi thành công mà báo thất bại".
     */
    suspend fun postComment(objectId: String, objectType: String, text: String,
                            cookies: Map<String, String>,
                            parentId: String = "0", replyId: String = "0",
                            replyName: String = "", pageUrl: String = "$BASE/"): Boolean =
        withContext(Dispatchers.IO) {
            // 1) OkHttp (phiên thật)
            try {
                Http.postForm("$BASE/postcomment/", mapOf(
                    "objectid" to objectId, "objecttype" to objectType,
                    "parentid" to parentId, "replyid" to replyId,
                    "replyname" to replyName, "comment" to text), pageUrl)?.let { r ->
                    if (r.bouncedToLogin()) return@withContext false
                    if (r.code == 200) return@withContext true
                    return@withContext false
                }
            } catch (_: Exception) { }
            // 2) Jsoup fallback (chỉ khi OkHttp lỗi mạng, không redirect vô định)
            try {
                val res = Jsoup.connect("$BASE/postcomment/")
                    .userAgent(UA).timeout(15000).cookies(cookies)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", BASE).referrer(pageUrl)
                    .data("objectid", objectId, "objecttype", objectType,
                        "parentid", parentId, "replyid", replyId,
                        "replyname", replyName, "comment", text)
                    .ignoreContentType(true).post()
                val b = res.body().text()
                if (isLoginPage(b)) return@withContext false
                b.contains("duyệt", true) || b.contains("thành công", true) || b.isNotBlank()
            } catch (_: Exception) { false }
        }

    /**
     * Lịch sử "Bài mới bình luận" THẬT từ Dashboard member
     * (xác thực từ file "Tổng quan - Member.mht" của user):
     * board "Bài mới bình luận" > ul.news-lst > li.news gồm
     * a.title (bài) + span.info > a[href=#commentId] (comment của mình) + span.info (giờ).
     * Cần cookies login (gộp cả member.bongdaplus.vn).
     */
    suspend fun fetchMyCommented(cookies: Map<String, String> = emptyMap()): List<MyCommented> =
        withContext(Dispatchers.IO) {
            if (cookies.isEmpty()) return@withContext emptyList()
            try {
                val doc = loadDoc("https://member.bongdaplus.vn/Identity/Account/Manage/DashBoard", cookies)
                // nếu bị đá về trang login thì không có board
                if (doc.selectFirst("input#Input_Email, form#account") != null) return@withContext emptyList()
                fun norm(s: String): String = try {
                    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
                } catch (_: Exception) { s }
                val board = doc.select("div.board").firstOrNull {
                    norm(it.selectFirst("div.brd-cap")?.text() ?: "").contains(norm("Bài mới bình luận"))
                } ?: doc.select("div.board").firstOrNull {
                    // Dự phòng khi caption thiếu chữ "mới" hoặc dấu khác chuẩn
                    norm(it.selectFirst("div.brd-cap")?.text() ?: "").contains(norm("bình luận"))
                } ?: return@withContext emptyList()
                board.select("ul.news-lst li.news").mapNotNull { li ->
                    val aTitle = li.selectFirst("a.title") ?: return@mapNotNull null
                    val articleUrl = absUrl(aTitle.attr("href").trim())
                    if (articleUrl.isBlank()) return@mapNotNull null
                    val cmtA = li.select("span.info a[href]").firstOrNull { it.attr("href").contains("#") }
                        ?: li.select("a[href*=txtcomment]").firstOrNull()
                        ?: li.select("a[href*=\\#]").firstOrNull()
                        ?: return@mapNotNull null
                    val commentUrl = absUrl(cmtA.attr("href").trim())
                    val commentId = commentUrl.substringAfter("#", "").trim()
                    val myText = cmtA.text().trim().replace(Regex("\\s+"), " ")
                    if (myText.isBlank()) return@mapNotNull null
                    val time = li.select("span.info").map { it.text().trim() }
                        .firstOrNull { it.isNotBlank() && !it.contains(myText.take(20)) } ?: ""
                    val aid = idFromUrl(articleUrl)
                    MyCommented(Article(aid, aTitle.text().trim(), articleUrl), commentId, commentUrl, myText, time)
                }.take(20)
            } catch (_: Exception) { emptyList() }
        }

    /**
     * Lịch sử thông báo member: ưu tiên API JSON thật (GET /GetNotification),
     * fallback div#lstnoti trong HTML trang chủ khi API rỗng/lỗi.
     * Key giữ cùng công thức cũ (actor|action|url|time) để Worker không báo trùng.
     */
    suspend fun fetchMemberNotifications(cookies: Map<String, String> = emptyMap()): List<MemberNotification> =
        withContext(Dispatchers.IO) {
            if (cookies.isEmpty()) return@withContext emptyList()
            try {
                val api = fetchNotificationsApi(cookies)
                if (api.isNotEmpty()) return@withContext api
            } catch (_: Exception) { }
            fetchMemberNotificationsDiv(cookies)
        }

    /** API JSON thật: GET /GetNotificationGeneralInitiator (XHR) -> mảng thông báo.
     * Schema thật (user bắt từ web): id, fullNameFrom, objectId, commentId,
     * notiType, contents (mẩu HTML <a><b>Actor</b> text <b>Bài</b></a><span.info>time),
     * postedDate, isRead, urlPath (URL sạch, neo #txtcomment_ đúng).
     * Fallback /GetNotification khi endpoint chính rỗng. */
    suspend fun fetchNotificationsApi(cookies: Map<String, String>): List<MemberNotification> =
        withContext(Dispatchers.IO) {
            val bust = System.currentTimeMillis()
            val bodies = listOf(
                Http.get("$BASE/GetNotificationGeneralInitiator?t=$bust", "$BASE/", xhr = true)?.html,
                Http.get("$BASE/GetNotification?t=$bust", "$BASE/", xhr = true)?.html
            ).mapNotNull { it }.filter { it.isNotBlank() }
            if (bodies.isEmpty()) return@withContext emptyList()
            val out = mutableListOf<MemberNotification>()
            for (body in bodies) {
                val arr = try {
                    val t = body.trim()
                    when {
                        t.startsWith("[") -> org.json.JSONArray(t)
                        t.startsWith("{") -> {
                            val o = org.json.JSONObject(t)
                            o.optJSONArray("data") ?: o.optJSONArray("items")
                            ?: o.optJSONArray("notifications") ?: org.json.JSONArray()
                        }
                        else -> org.json.JSONArray()
                    }
                } catch (_: Exception) { continue }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val html = (o.optString("contents") ?: "").trim()
                    if (html.isBlank()) continue
                    // contents là HTML: parse lấy text + actor + giờ
                    val frag = try { Jsoup.parseBodyFragment(html) } catch (_: Exception) { continue }
                    val a = frag.selectFirst("a[href]")
                    val full = (a?.text() ?: frag.text()).trim().replace(Regex("\\s+"), " ")
                    if (full.isBlank()) continue
                    var actor = (a?.selectFirst("b")?.text()?.trim().orEmpty())
                        .ifBlank { (o.optString("fullNameFrom") ?: "").trim().ifBlank { "Ai đó" } }
                    actor = actor.replace(Regex("\\s+"), " ")
                    // urlPath sạch (neo #txtcomment_ đúng); contents href đôi khi dính id lặp
                    var href = (o.optString("urlPath") ?: "").trim()
                        .ifBlank { a?.attr("href")?.trim().orEmpty() }
                    if (href.isBlank()) continue
                    if (href.startsWith("/")) href = href.substring(1)
                    val url = absUrl(href)
                    val infoTime = frag.selectFirst("span.info")?.text()?.trim().orEmpty()
                    val time = infoTime.ifBlank { (o.optString("postedDate") ?: "").trim() }
                    val action = when {
                        full.contains("không thích", true) -> "không thích"
                        full.contains("thích", true) -> "thích"
                        full.contains("trả lời", true) -> "trả lời"
                        full.contains("duyệt", true) -> "duyệt"
                        else -> "bình luận"
                    }
                    val cid = o.opt("commentId")?.toString()?.takeIf { it != "null" }.orEmpty()
                    val oid = o.opt("objectId")?.toString()?.takeIf { it != "null" }.orEmpty()
                    // Key ổn định theo sự kiện (actor+action+comment), không theo id/time:
                    // server trả nhiều dòng cho cùng 1 lượt burst, tránh báo trùng.
                    val key = "$actor|$action|$oid|$cid".hashCode().toString() + "|" + url.hashCode()
                    out += MemberNotification(key, actor, action, full, url, time)
                }
                if (out.isNotEmpty()) break
            }
            out.distinctBy { it.key }.take(30)
        }

    /**
     * Lịch sử thông báo member (div#lstnoti): mỗi item
     * "X đã thích / không thích bình luận của bạn ở bài viết: Y" + link #txtcomment_ + giờ.
     * Cần cookies đăng nhập (gộp cả member.bongdaplus.vn).
     */
    suspend fun fetchMemberNotificationsDiv(cookies: Map<String, String> = emptyMap()): List<MemberNotification> =
        withContext(Dispatchers.IO) {
            if (cookies.isEmpty()) return@withContext emptyList()
            try {
                val doc = loadDoc("$BASE/", cookies)
                doc.select("div#lstnoti li.news").mapNotNull { li ->
                    val a = li.selectFirst("a[href]") ?: return@mapNotNull null
                    val href = a.attr("href").trim()
                    if (href.isEmpty() || !href.contains("txtcomment_")) return@mapNotNull null
                    val url = absUrl(href)
                    val full = a.text().trim().replace(Regex("\\s+"), " ")
                    if (full.isBlank()) return@mapNotNull null
                    val actor = a.selectFirst("b")?.text()?.trim().ifNullOrBlank { "Ai đó" }
                    val action = when {
                        full.contains("không thích", true) -> "không thích"
                        full.contains("thích", true) -> "thích"
                        full.contains("trả lời", true) -> "trả lời"
                        full.contains("duyệt", true) -> "duyệt"
                        else -> "bình luận"
                    }
                    val time = li.selectFirst("span.info")?.text()?.trim() ?: ""
                    val key = "$actor|$action|$url|$time".hashCode().toString() + "|" + url.hashCode()
                    MemberNotification(key, actor, action, full, url, time)
                }.distinctBy { it.key }.take(30)
            } catch (_: Exception) { emptyList() }
        }

    /**
     * Thích / Không thích 1 bình luận, y hệt web (bongdaplus.js):
     *  - Gửi kèm cookie toggle `thumup{id}`/`thumdw{id}` ('y' = đang vote,
     *    '1' = đã gỡ) — web set cookie này trước mỗi POST, server dùng để
     *    phân biệt vote mới / hoàn tác. App trước đây không gửi nên hoàn tác
     *    bị server bỏ qua (SET cùng emotion = no-op).
     *  - POST đúng 1 lần, body rỗng có Content-Length: 0 (kẻo 411).
     * @param prevActive trạng thái trước bấm (0/1/7) để gỡ nút đối diện khi đổi phe
     * @param isUndo true khi bấm lại nút đang active (= gỡ vote)
     * @return mã HTTP: 200 = đã nhận; khác = lỗi; 0 = lỗi mạng.
     */
    suspend fun setCommentEmotion(objectId: String, commentId: String, like: Boolean,
                                   cookies: Map<String, String>,
                                   pageUrl: String = "$BASE/",
                                   objectType: String = "1",
                                   prevActive: Int = 0,
                                   isUndo: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            val type = if (like) "1" else "7"
            // Mirror cookie toggle của web (js-cookie, expires dài, path /)
            try {
                val cm = android.webkit.CookieManager.getInstance()
                val sep = "; expires=Fri, 31 Dec 9999 23:59:59 GMT; path=/"
                if (like) {
                    cm.setCookie(BASE, "thumup$commentId=" + (if (isUndo) "1" else "y") + sep)
                    if (!isUndo && prevActive == 7)
                        cm.setCookie(BASE, "thumdw$commentId=1$sep")
                } else {
                    cm.setCookie(BASE, "thumdw$commentId=" + (if (isUndo) "1" else "y") + sep)
                    if (!isUndo && prevActive == 1)
                        cm.setCookie(BASE, "thumup$commentId=1$sep")
                }
                try { cm.flush() } catch (_: Exception) { }
            } catch (_: Exception) { }
            try {
                val r = Http.postForm("$BASE/setCommentEmotion/$objectId/$commentId/$type",
                    emptyMap(), pageUrl)
                if (r != null) {
                    if (r.bouncedToLogin()) return@withContext 401
                    return@withContext r.code
                }
                jsoupVoteOnce("$BASE/setCommentEmotion/$objectId/$commentId/$type", cookies, pageUrl)
            } catch (_: Exception) {
                jsoupVoteOnce("$BASE/setCommentEmotion/$objectId/$commentId/$type", cookies, pageUrl)
            }
        }

    /** Jsoup thử POST vote 1 lần duy nhất (chỉ khi OkHttp lỗi mạng). @return 200 hoặc 0 */
    private suspend fun jsoupVoteOnce(
        url: String, cookies: Map<String, String>, referer: String,
    ): Int = withContext(Dispatchers.IO) {
        if (cookies.isEmpty()) return@withContext 0
        try {
            val res = Jsoup.connect(url)
                .userAgent(UA).timeout(15000).cookies(cookies)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", BASE).referrer(referer)
                .ignoreContentType(true).execute()
            if (isLoginPage(res.body())) 0 else res.statusCode()
        } catch (_: Exception) { 0 }
    }

    /**
     * Cảm xúc bài viết: 1=Thích, 2=Tim, 4=Wow. Web toggle (bấm lại = gỡ) và chỉ
     * tin HTTP 200 — app y hệt, không xác minh đọc lại (dính cache server).
     * @return mã HTTP: 200 = đã nhận; 0 = lỗi mạng.
     */
    suspend fun setNewsEmotion(objectId: String, objectType: String, emotionType: Int,
                                 cookies: Map<String, String>,
                                 pageUrl: String = "$BASE/"): Int =
        withContext(Dispatchers.IO) {
            try {
                val r = Http.postForm("$BASE/setNewsEmotion/$objectId/$objectType/$emotionType",
                    emptyMap(), pageUrl)
                if (r != null) {
                    if (r.bouncedToLogin()) return@withContext 401
                    return@withContext r.code
                }
                jsoupVoteOnce(
                    "$BASE/setNewsEmotion/$objectId/$objectType/$emotionType", cookies, pageUrl)
            } catch (_: Exception) {
                jsoupVoteOnce(
                    "$BASE/setNewsEmotion/$objectId/$objectType/$emotionType", cookies, pageUrl)
            }
        }

    /** Cảm xúc bài viết của chính member (0 = chưa chọn). Đúng objectType của bài. */
    suspend fun fetchMyNewsEmotion(objectId: String, objectType: String,
                                   cookies: Map<String, String>): Int? =
        withContext(Dispatchers.IO) {
            if (objectId.isBlank() || cookies.isEmpty()) return@withContext null
            try {
                val bust = System.currentTimeMillis()
                val body = Http.get("$BASE/getNewsEmotion/$objectId/$objectType?t=$bust",
                    "$BASE/", xhr = true)?.html
                    ?: Jsoup.connect("$BASE/getNewsEmotion/$objectId/$objectType")
                        .userAgent(UA).timeout(15000).cookies(cookies)
                        .ignoreContentType(true).get().body().text()
                val o = JSONObject(body.trim())
                var log: JSONObject? = o.optJSONObject("logNewsEmotion")
                if (log == null) {
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.lowercase() == "lognewsemotion") {
                            log = o.optJSONObject(k); break
                        }
                    }
                }
                if (log != null) {
                    var emo = 0
                    val keys = log.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.lowercase() == "emotiontype") {
                            emo = log.optInt(k, log.optString(k, "0")
                                .filter { it.isDigit() }.toIntOrNull() ?: 0)
                            break
                        }
                    }
                    return@withContext emo
                }
            } catch (_: Exception) { }
            0
        }

    /**
     * Member đã vote bình luận nào: GET /GetCommentEmotion/{objectId}/{objectType}
     * trả về danh sách (commentId, emotionType). emotionType==1 là đã Thích.
     * Dùng để tô sáng nút 👍👎 của chính member. Đúng objectType của bài.
     */
    suspend fun getMyCommentVotes(objectId: String, objectType: String,
                                   cookies: Map<String, String>): Map<String, Int> =
        withContext(Dispatchers.IO) {
            if (objectId.isBlank() || cookies.isEmpty()) return@withContext emptyMap()
            val out = mutableMapOf<String, Int>()
            try {
                val bust = System.currentTimeMillis()
                val body = Http.get("$BASE/GetCommentEmotion/$objectId/$objectType?t=$bust",
                    "$BASE/", xhr = true)?.html
                    ?: Jsoup.connect("$BASE/GetCommentEmotion/$objectId/$objectType")
                        .userAgent(UA).timeout(15000).cookies(cookies)
                        .ignoreContentType(true).get().body().text()
                val arr = try { org.json.JSONArray(body.trim()) }
                catch (_: Exception) { return@withContext out }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    // So tên field không phân biệt hoa/thường, id chịu số lẫn chuỗi
                    var cid = ""
                    var emo = 0
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        when (k.lowercase()) {
                            "commentid", "id" -> cid = o.opt(k)?.toString()
                                ?.takeIf { it != "null" }.orEmpty()
                            "emotiontype", "emotion", "type" ->
                                emo = o.optInt(k, o.optString(k, "0").filter { it.isDigit() }
                                    .toIntOrNull() ?: 0)
                        }
                    }
                    if (cid.isNotBlank()) out[cid] = emo
                }
            } catch (_: Exception) { }
            out
        }

    /**
     * Đăng nhập member bằng form Email/Mật khẩu qua OkHttp (không cần WebView).
     * 1) GET trang login lấy antiforgery token (cookie phiên tự vào jar chung).
     * 2) POST credentials, theo redirect: về lại Login = sai TK/MK.
     * 3) Chạy handshake site (iframe LoginFrom + trang chủ) rồi kiểm tra phiên site
     *    bằng input#txtUserid tĩnh và đọc tên hiển thị ("Xin chào ...").
     */
    suspend fun loginMember(email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val loginUrl = "https://member.bongdaplus.vn/Identity/Account/Login?returnUrl=%2F"
                val form = try { Http.get(loginUrl) } catch (_: Exception) { null }
                    ?: return@withContext LoginResult.NetworkError
                if (!form.finalUrl.contains("Login", ignoreCase = true)) {
                    // Đã login sẵn từ trước
                    return@withContext LoginResult.Ok(fetchDisplayName(), checkSiteSession())
                }
                val doc = try { Jsoup.parse(form.html, loginUrl) } catch (_: Exception) { null }
                    ?: return@withContext LoginResult.NetworkError
                val token = doc.selectFirst("input[name=__RequestVerificationToken]")
                    ?.attr("value").orEmpty()
                if (token.isBlank()) return@withContext LoginResult.NetworkError
                val res = Http.postForm(loginUrl, mapOf(
                    "Input.Email" to email.trim(),
                    "Input.Password" to password,
                    "Input.RememberMe" to "true",
                    "__RequestVerificationToken" to token,
                ), loginUrl) ?: return@withContext LoginResult.NetworkError
                if (res.finalUrl.contains("Login", ignoreCase = true)) {
                    // Sai TK/MK hoặc lỗi validate: bóc chữ lỗi trong trang trả về
                    val d2 = try { Jsoup.parse(res.body, loginUrl) } catch (_: Exception) { null }
                    val err = d2?.select(".validation-summary-errors li, .text-danger li, span.text-danger")
                        ?.map { it.text().trim() }?.filter { it.isNotBlank() && it.length > 1 }
                        ?.distinct()?.joinToString("; ")?.take(200)
                        ?.ifBlank { "Sai email hoặc mật khẩu." }
                        ?: "Sai email hoặc mật khẩu."
                    return@withContext LoginResult.Invalid(err)
                }
                // Đăng nhập member xong: handshake sang site rồi đọc tên
                syncSiteSession()
                LoginResult.Ok(fetchDisplayName(), checkSiteSession())
            } catch (_: Exception) { LoginResult.NetworkError }
        }

    /**
     * Đăng ký tài khoản mới bằng form native qua OkHttp (không cần WebView).
     * Thành công thường tự đăng nhập luôn.
     */
    suspend fun registerMember(
        email: String, password: String, firstName: String, lastName: String,
    ): LoginResult = withContext(Dispatchers.IO) {
        try {
            val regUrl = "https://member.bongdaplus.vn/Identity/Account/Register?returnUrl=%2F"
            val form = try { Http.get(regUrl) } catch (_: Exception) { null }
                ?: return@withContext LoginResult.NetworkError
            val doc = try { Jsoup.parse(form.html, regUrl) } catch (_: Exception) { null }
                ?: return@withContext LoginResult.NetworkError
            if (!form.finalUrl.contains("Register", ignoreCase = true)) {
                return@withContext LoginResult.Ok(fetchDisplayName(), checkSiteSession())
            }
            val formEl = doc.select("form").firstOrNull {
                it.selectFirst("#Input_Password") != null
            }
            val token = formEl?.selectFirst("input[name=__RequestVerificationToken]")
                ?.attr("value").orEmpty()
            if (token.isBlank()) return@withContext LoginResult.NetworkError
            val res = Http.postForm(regUrl, mapOf(
                "Input.Email" to email.trim(),
                "Input.Password" to password,
                "Input.ConfirmPassword" to password,
                "Input.FistName" to firstName.trim(),
                "Input.LastName" to lastName.trim(),
                "__RequestVerificationToken" to token,
            ), regUrl) ?: return@withContext LoginResult.NetworkError
            if (res.finalUrl.contains("Register", ignoreCase = true) ||
                res.finalUrl.contains("Login", ignoreCase = true)
            ) {
                val d2 = try { Jsoup.parse(res.body, regUrl) } catch (_: Exception) { null }
                val err = d2?.select(".validation-summary-errors li, .text-danger li, span.field-validation-error")
                    ?.map { it.text().trim() }?.filter { it.length > 1 }
                    ?.distinct()?.joinToString("; ")?.take(250)
                    ?.ifBlank { "Đăng ký thất bại, kiểm tra lại thông tin." }
                    ?: "Đăng ký thất bại, kiểm tra lại thông tin."
                // Đăng ký xong web thường bắt xác nhận email -> vẫn coi như xong form
                if (res.body.contains("xác nhận", ignoreCase = true) ||
                    res.body.contains("confirm", ignoreCase = true)
                ) {
                    return@withContext LoginResult.Ok("", false)
                }
                return@withContext LoginResult.Invalid(err)
            }
            try { syncSiteSession() } catch (_: Exception) { }
            LoginResult.Ok(fetchDisplayName(), checkSiteSession())
        } catch (_: Exception) { LoginResult.NetworkError }
    }

    /**
     * Đồng bộ phiên site sau khi có phiên member: chạy handshake HTTP
     * (iframe LoginFrom + trang chủ). Không JS nên có thể không đủ — hàm trả
     * về đúng trạng thái để UI báo thật.
     */
    suspend fun syncSiteSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            try {
                Http.get("https://member.bongdaplus.vn/Identity/Account/Login?ReturnUrl=%2FHome%2FLoginFromBongdaplus")
            } catch (_: Exception) { }
            try { Http.get("$BASE/") } catch (_: Exception) { }
            checkSiteSession()
        } catch (_: Exception) { false }
    }

    /** Tên hiển thị từ trang Manage ("Xin chào <b>Tên</b>"). Trống nếu chưa login. */
    suspend fun fetchDisplayName(): String = withContext(Dispatchers.IO) {
        try {
            val p = Http.get("https://member.bongdaplus.vn/Identity/Account/Manage")
                ?: return@withContext ""
            if (p.bouncedToLogin()) return@withContext ""
            Regex("Xin chào\\s*<b>([^<]{1,40})</b>").find(p.html)?.groupValues?.get(1)?.trim()
                ?: Regex("Xin chào\\s+([^\\n<]{1,40})").find(
                    Jsoup.parse(p.html).body().text())?.groupValues?.get(1)?.trim().orEmpty()
        } catch (_: Exception) { "" }
    }

    /** true khi trang site đã có phiên (input#txtUserid tĩnh có giá trị). */
    suspend fun checkSiteSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = Http.get("$BASE/") ?: return@withContext false
            Regex("id=\"txtUserid\" value=\"([^\"]+)\"").find(p.html)
                ?.groupValues?.get(1)?.isNotBlank() == true
        } catch (_: Exception) { false }
    }

    /** Server trả trang đăng nhập thay vì thực hiện hành động => coi như chưa login */
    private fun isLoginPage(body: String): Boolean {
        if (body.length < 5000) return false
        return body.contains("Input_Email") || body.contains("Account/Login") ||
            (body.contains("Đăng nhập") && body.contains("Mật khẩu"))
    }

    private fun String?.ifNullOrBlank(def: () -> String): String =
        if (this.isNullOrBlank()) def() else this
}
