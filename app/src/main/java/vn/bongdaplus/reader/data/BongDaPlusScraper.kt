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
                    if (clipDesc.isNotBlank()) add(ContentBlock.Paragraph(clipDesc))
                    // các đoạn chữ còn lại trong clip-info (tags đã nằm ngoài nên an toàn)
                    bodyEl.select("p").forEach {
                        val t = it.text().trim()
                        if (t.isNotBlank() && t != clipDesc && t.length > 2) add(ContentBlock.Paragraph(t))
                    }
                }.take(50)
                bodyEl.tagName() == "body" -> emptyList()
                else -> parseBlocks(bodyEl)
            }
            if (ytId != null && (ogImg.isNullOrBlank() || ogImg.contains("logo"))) {
                ogImg = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
            }
            // tác giả + giờ từ JSON-LD NewsArticle (chuẩn nhất)
            // Thực tế file .mht thật cho thấy site không còn render ld+json,
            // chỉ có <meta name=author> chung + <time datetime>.
            val ld = doc.select("script[type=application/ld+json]").map { it.html() }
                .firstOrNull { it.contains("NewsArticle") } ?: ""
            val author = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(
                ld.substringAfter("\"author\"").take(300)
            )?.groupValues?.get(1)
                ?: doc.selectFirst(".author-name, .author")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("meta[name=author]")?.attr("content")?.ifBlank { null }
            val published = Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").find(ld)?.groupValues?.get(1)
                ?: doc.selectFirst("meta[property=article:published_time]")?.attr("content")
                ?: doc.selectFirst("time[datetime]")?.attr("datetime")?.ifBlank { null }
                ?: doc.selectFirst("time")?.text()?.trim()
            // .mht thật: không còn #objectid/#objecttype trong HTML tĩnh (render bằng JS).
            // Fallback dùng id số đuôi URL (vd ...-5239382610.html) + objectType=1 (tin tức).
            val rawOid = doc.selectFirst("#objectid")?.attr("value")?.trim() ?: ""
            val rawOtype = doc.selectFirst("#objecttype")?.attr("value")?.trim() ?: ""
            val id = idFromUrl(url)
            val objectId = rawOid.ifBlank { id.filter { it.isDigit() }.ifBlank { id } }
            val objectType = rawOtype.ifBlank { "1" }
            val catSlug = doc.selectFirst("#catrefid")?.attr("value")?.ifBlank { null }
            val base = Article(id, title, url, ogImg, catSlug)
            // Ưu tiên API, fallback đếm inline từ .mht (a.emo.comment#ncmt_*, #numemo*)
            val emotion = try { fetchEmotion(objectId, objectType, cookies) } catch (_: Exception) { Emotion() }
                .takeIf { it.comments > 0 || it.liked > 0 } ?: parseInlineEmotion(doc)
            ArticleDetail(base, author, published, bodyEl.html(), bodyEl.text().take(600),
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
                val otype = doc.selectFirst("#objecttype")?.attr("value")?.trim()?.ifBlank { null } ?: "1"
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
            val types = listOf(objectType, if (objectType == "1") "0" else "1").distinct()
            for (t in types) {
                try {
                    val body = Http.get("$BASE/getNewsEmotion/$objectId/$t")?.html
                        ?: Jsoup.connect("$BASE/getNewsEmotion/$objectId/$t")
                            .userAgent(UA).timeout(15000).cookies(cookies)
                            .ignoreContentType(true).get().body().text()
                    val o = JSONObject(body).optJSONObject("newsUserActivity") ?: continue
                    val e = Emotion(o.optInt("liked"), o.optInt("heart"), o.optInt("wow"), o.optInt("comments"))
                    if (e.comments > 0 || e.liked > 0 || e.heart > 0) return@withContext e
                    if (t == types.last()) return@withContext e
                } catch (_: Exception) { }
            }
            Emotion()
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
            val types = listOf(objectType, if (objectType == "1") "0" else "1").distinct()
            for (t in types) {
                try {
                    val html = Http.get("$BASE/binh-luan/$objectId/$t/$page/0")?.html
                        ?: Jsoup.connect("$BASE/binh-luan/$objectId/$t/$page/0")
                            .userAgent(UA).timeout(15000).cookies(cookies)
                            .ignoreContentType(true).get().body().html()
                    val out = parseFrag(html)
                    if (out.isNotEmpty()) return@withContext out
                } catch (_: Exception) { }
            }
            emptyList()
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
                val board = doc.select("div.board").firstOrNull {
                    it.selectFirst("div.brd-cap")?.text()?.contains("Bài mới bình luận") == true
                } ?: return@withContext emptyList()
                board.select("ul.news-lst li.news").mapNotNull { li ->
                    val aTitle = li.selectFirst("a.title") ?: return@mapNotNull null
                    val articleUrl = absUrl(aTitle.attr("href").trim())
                    if (articleUrl.isBlank()) return@mapNotNull null
                    val cmtA = li.select("span.info a[href]").firstOrNull { it.attr("href").contains("#") }
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
     * Lịch sử thông báo member (div#lstnoti): mỗi item
     * "X đã thích / không thích bình luận của bạn ở bài viết: Y" + link #txtcomment_ + giờ.
     * Cần cookies đăng nhập (gộp cả member.bongdaplus.vn).
     */
    suspend fun fetchMemberNotifications(cookies: Map<String, String> = emptyMap()): List<MemberNotification> =
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
     * Thích / Không thích 1 bình luận (OkHttp + URL cuối).
     * Endpoint trả rỗng khi thành công nên chỉ cần: không bounce login + HTTP 200.
     */
    suspend fun setCommentEmotion(objectId: String, commentId: String, like: Boolean,
                                  cookies: Map<String, String>,
                                  pageUrl: String = "$BASE/"): Boolean =
        withContext(Dispatchers.IO) {
            val type = if (like) "1" else "7"
            // 1) OkHttp (phiên thật)
            try {
                Http.postForm("$BASE/setCommentEmotion/$objectId/$commentId/$type",
                    emptyMap(), pageUrl)?.let { r ->
                    if (r.bouncedToLogin()) return@withContext false
                    if (r.code == 200) return@withContext true
                    return@withContext false
                }
            } catch (_: Exception) { }
            // 2) Jsoup fallback (chỉ khi OkHttp lỗi mạng)
            if (cookies.isEmpty()) return@withContext false
            try {
                val res = Jsoup.connect("$BASE/setCommentEmotion/$objectId/$commentId/$type")
                    .userAgent(UA).timeout(15000).cookies(cookies)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", BASE).referrer(pageUrl)
                    .ignoreContentType(true).post()
                !isLoginPage(res.body().text())
            } catch (_: Exception) { false }
        }

    /**
     * Cảm xúc bài viết: 1=Thích, 2=Tim, 4=Wow (theo bongdaplus.js).
     * POST /setNewsEmotion/{objectId}/{objectType}/{emotionType}
     */
    suspend fun setNewsEmotion(objectId: String, objectType: String, emotionType: Int,
                                cookies: Map<String, String>,
                                pageUrl: String = "$BASE/"): Boolean =
        withContext(Dispatchers.IO) {
            // 1) OkHttp (phiên thật)
            try {
                Http.postForm("$BASE/setNewsEmotion/$objectId/$objectType/$emotionType",
                    emptyMap(), pageUrl)?.let { r ->
                    if (r.bouncedToLogin()) return@withContext false
                    if (r.code == 200) return@withContext true
                    return@withContext false
                }
            } catch (_: Exception) { }
            // 2) Jsoup fallback (chỉ khi OkHttp lỗi mạng)
            if (cookies.isEmpty()) return@withContext false
            try {
                val res = Jsoup.connect("$BASE/setNewsEmotion/$objectId/$objectType/$emotionType")
                    .userAgent(UA).timeout(15000).cookies(cookies)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", BASE).referrer(pageUrl)
                    .ignoreContentType(true).post()
                !isLoginPage(res.body().text())
            } catch (_: Exception) { false }
        }

    /**
     * Member đã vote bình luận nào: GET /GetCommentEmotion/{objectId}/{objectType}
     * trả về danh sách (commentId, emotionType). emotionType==1 là đã Thích.
     * Dùng để tô sáng nút 👍👎 của chính member.
     */
    suspend fun getMyCommentVotes(objectId: String, objectType: String,
                                  cookies: Map<String, String>): Map<String, Int> =
        withContext(Dispatchers.IO) {
            if (objectId.isBlank() || cookies.isEmpty()) return@withContext emptyMap()
            try {
                val body = Http.get("$BASE/GetCommentEmotion/$objectId/$objectType")?.html
                    ?: Jsoup.connect("$BASE/GetCommentEmotion/$objectId/$objectType")
                        .userAgent(UA).timeout(15000).cookies(cookies)
                        .ignoreContentType(true).get().body().text()
                val arr = try { org.json.JSONArray(body) } catch (_: Exception) { return@withContext emptyMap() }
                val out = mutableMapOf<String, Int>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val cid = o.optString("commentId").ifBlank { o.optString("commentid") }
                    if (cid.isNotBlank()) out[cid] = o.optInt("emotionType", o.optInt("emotiontype"))
                }
                out
            } catch (_: Exception) { emptyMap() }
        }

    /**
     * Trang HTML đăng nhập thật (dùng cho fallback Jsoup). Chỉ coi là login khi
     * body DÀI như 1 trang web (>5KB) và có dấu hiệu form login — để API nhỏ
     * trả chữ "đăng nhập" trong nội dung cũng không bị kết luận sai.
     */
    private fun isLoginPage(body: String): Boolean {
        if (body.length < 5000) return false
        return body.contains("Input_Email") || body.contains("Account/Login") ||
            (body.contains("Đăng nhập") && body.contains("Mật khẩu"))
    }

    private fun String?.ifNullOrBlank(def: () -> String): String =
        if (this.isNullOrBlank()) def() else this
}
