package vn.bongdaplus.reader.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import vn.bongdaplus.reader.MainActivity
import vn.bongdaplus.reader.R
import vn.bongdaplus.reader.data.Article
import vn.bongdaplus.reader.data.AuthManager
import vn.bongdaplus.reader.data.BookmarkStore
import vn.bongdaplus.reader.data.BongDaPlusScraper
import vn.bongdaplus.reader.data.catName
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Thông báo chuẩn app đọc báo:
 * - 2 kênh riêng: "Tin nóng" (ưu tiên cao + rung) và "Bình luận" (thường).
 * - Tin mới: ảnh lớn (BigPicture), tên chuyên mục, màu thương hiệu,
 *   nút Lưu tin / Chia sẻ, gom nhóm khi nhiều tin cùng lúc.
 * - Bình luận: bấm mở thẳng khung bình luận trong bài.
 */
object NotifyHelper {
    /** Kênh cũ (đã thay bằng 2 kênh dưới, xóa khi tạo kênh mới). */
    const val CHANNEL_ID = "bongdaplus_news"
    const val CH_BREAKING = "bdp_breaking"
    const val CH_COMMENTS = "bdp_comments"
    const val WORK_TAG = "news_poll"
    const val GROUP_NEWS = "bdp_group_news"
    const val ACTION_SAVE = "vn.bongdaplus.reader.SAVE"
    const val ACTION_SHARE = "vn.bongdaplus.reader.SHARE"
    private const val BRAND = 0xFF1B7A43

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.deleteNotificationChannel(CHANNEL_ID) } catch (_: Exception) { }
        try {
            nm.createNotificationChannel(NotificationChannel(
                CH_BREAKING, "Tin nóng BongdaPlus",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tin mới theo chuyên mục bạn theo dõi, kèm ảnh"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 200, 250)
            })
        } catch (_: Exception) { }
        try {
            nm.createNotificationChannel(NotificationChannel(
                CH_COMMENTS, "Bình luận",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Bình luận mới, trả lời, lượt thích bình luận của bạn" })
        } catch (_: Exception) { }
    }

    /** Intent mở bài viết (kèm cờ mở thẳng khung bình luận). */
    fun articlePending(
        ctx: Context, url: String, openComments: Boolean, reqCode: Int,
    ): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("open_url", url)
            putExtra("open_comments", openComments)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun savePending(ctx: Context, a: Article, reqCode: Int): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = ACTION_SAVE
            putExtra("url", a.url)
            putExtra("title", a.title)
            putExtra("image", a.imageUrl ?: "")
            putExtra("cat", a.category ?: "")
            putExtra("notif_id", reqCode)
        }
        return PendingIntent.getBroadcast(
            ctx, reqCode + 500000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun sharePending(ctx: Context, a: Article, reqCode: Int): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = ACTION_SHARE
            putExtra("title", a.title)
            putExtra("url", a.url)
        }
        return PendingIntent.getBroadcast(
            ctx, reqCode + 700000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Tải thumbnail về bitmap (downscale, nền, lỗi -> null để rớt về chữ). */
    suspend fun downloadThumb(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", vn.bongdaplus.reader.data.Http.UA)
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return@withContext null
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            conn.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) { null }
    }

    private fun appIcon(ctx: Context): Bitmap? = try {
        BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)
    } catch (_: Exception) { null }

    /** 1 tin nóng: ảnh lớn + nút Lưu/Chia sẻ, thuộc nhóm tin mới. */
    suspend fun showBreaking(ctx: Context, a: Article, id: Int) {
        ensureChannel(ctx)
        val bmp = downloadThumb(a.imageUrl)
        val style: NotificationCompat.Style = if (bmp != null) {
            NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?)
                .setSummaryText(catName(a.category))
        } else {
            NotificationCompat.BigTextStyle().bigText(a.title)
        }
        val n = NotificationCompat.Builder(ctx, CH_BREAKING)
            .setSmallIcon(R.drawable.ic_mono_ball)
            .setContentTitle(a.title)
            .setContentText(catName(a.category))
            .setSubText(catName(a.category))
            .setStyle(style)
            .setLargeIcon(bmp ?: appIcon(ctx))
            .setColor(BRAND.toInt())
            .setColorized(false)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(articlePending(ctx, a.url, false, id))
            .addAction(NotificationCompat.Action.Builder(
                IconCompat.createWithResource(ctx, R.drawable.ic_mono_ball),
                "Lưu tin", savePending(ctx, a, id)).build())
            .addAction(NotificationCompat.Action.Builder(
                IconCompat.createWithResource(ctx, R.drawable.ic_mono_ball),
                "Chia sẻ", sharePending(ctx, a, id)).build())
            .setGroup(GROUP_NEWS)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }

    /** Tóm tắt nhóm khi nhiều tin cùng lúc (InboxStyle chuẩn). */
    fun showDigest(ctx: Context, articles: List<Article>) {
        if (articles.size < 2) return
        ensureChannel(ctx)
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle("${articles.size} tin mới cho bạn")
        articles.take(5).forEach { style.addLine(it.title) }
        style.setSummaryText("BongdaPlus")
        val top = articles.first()
        val n = NotificationCompat.Builder(ctx, CH_BREAKING)
            .setSmallIcon(R.drawable.ic_mono_ball)
            .setContentTitle("${articles.size} tin mới cho bạn")
            .setContentText(top.title)
            .setStyle(style)
            .setLargeIcon(appIcon(ctx))
            .setColor(BRAND.toInt())
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(articlePending(ctx, top.url, false, 9999))
            .setGroup(GROUP_NEWS)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9999, n)
    }

    /** Thông báo bình luận: bấm mở thẳng khung bình luận trong bài. */
    fun showComment(
        ctx: Context, title: String, text: String, url: String, id: Int,
        openComments: Boolean = true,
    ) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CH_COMMENTS)
            .setSmallIcon(R.drawable.ic_mono_ball)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setLargeIcon(appIcon(ctx))
            .setColor(BRAND.toInt())
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(articlePending(ctx, url, openComments, id))
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }

    /** Giữ tương thích chỗ gọi cũ (mặc định kiểu bình luận, không tự mở khung). */
    fun show(ctx: Context, title: String, text: String, url: String, id: Int) {
        showComment(ctx, title, text, url, id, openComments = false)
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<NewsWorker>(45, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_TAG, ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    /** Thiết bị/hệ thống có đang cho app hiện thông báo không (quyền + kênh). */
    fun canNotify(ctx: Context): Boolean {
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.areNotificationsEnabled()
        } catch (_: Exception) { true }
    }

    /**
     * Kiểm tra thông báo member NGAY (cho nút bấm tay trong app).
     * Dùng chung nguồn với Worker nền: API /GetNotificationGeneralInitiator,
     * fallback div#lstnoti. Trả về chuỗi kết quả để hiển thị.
     */
    suspend fun checkMemberNotifsNow(ctx: Context): String {
        val appCtx = ctx.applicationContext
        return try {
            val auth = AuthManager(appCtx)
            if (!(try { auth.loggedIn.first() } catch (_: Exception) { false }))
                return "Chưa đăng nhập — đăng nhập rồi kiểm tra lại."
            val cookies = auth.currentCookies()
            if (cookies.isEmpty()) return "Chưa có phiên đăng nhập (thiếu cookie)."
            if (!canNotify(appCtx))
                return "App đang bị tắt quyền thông báo trong Cài đặt hệ thống — bật lên mới thấy push."
            ensureChannel(appCtx)
            val list = try { BongDaPlusScraper.fetchMemberNotifications(cookies) }
            catch (_: Exception) { return "Lỗi mạng, thử lại sau." }
            if (list.isEmpty()) return "Chưa có thông báo nào từ server."
            val seen = try { auth.seenNotifKeys() } catch (_: Exception) { emptySet() }
            if (seen.isEmpty()) {
                auth.saveSeenNotifKeys(list.map { it.key }.toSet())
                return "Đã lưu mốc ${list.size} thông báo — có cái mới sẽ push ngay."
            }
            val fresh = list.filter { it.key !in seen }.take(3)
            for (n in fresh) {
                val title = when (n.action) {
                    "không thích" -> "👎 ${n.actor} không thích bình luận của bạn"
                    "trả lời" -> "↩️ ${n.actor} đã trả lời bạn"
                    else -> "👍 ${n.actor} đã thích bình luận của bạn"
                }
                showComment(
                    appCtx, title, "${n.text}\n${n.time}",
                    n.url, (n.key.hashCode() % 80000) + 40000, openComments = true
                )
            }
            auth.saveSeenNotifKeys((list.map { it.key } + seen).take(60).toSet())
            if (fresh.isEmpty()) "Không có gì mới — ${list.size} thông báo đều đã thấy."
            else "Đã push ${fresh.size} thông báo mới."
        } catch (_: Exception) { "Lỗi không rõ, thử lại sau." }
    }
}

/**
 * Poll tin mới mỗi ~45 phút + kiểm tra bình luận mới ở bài đã lưu.
 * - Tin mới: so với lastSeenIds, ưu tiên chuyên mục theo dõi.
 * - Bình luận: so số bình luận thật (API getNewsEmotion) của bài đã lưu
 *   với lần quét trước; tăng -> đẩy notification 💬.
 */
class NewsWorker(appCtx: Context, params: WorkerParameters) : CoroutineWorker(appCtx, params) {
    override suspend fun doWork(): Result {
        return try {
            val auth = AuthManager(applicationContext)
            val enabled = auth.notifyEnabled.first()
            if (!enabled) return Result.success()
            val cookies = auth.currentCookies()
            val follows = try { auth.followSlugs.first() } catch (_: Exception) { emptySet() }
            val lastSeen = try { auth.lastSeenIds() } catch (_: Exception) { emptySet() }

            // Quét trang Mới nhất + 2 chuyên mục follow đầu tiên để nhẹ pin
            val slugs = listOf("tin-moi").plus(follows.take(2)).distinct()
            val all = mutableListOf<vn.bongdaplus.reader.data.Article>()
            for (s in slugs) {
                try {
                    all += if (s == "tin-moi") BongDaPlusScraper.fetchHome(cookies)
                    else BongDaPlusScraper.fetchCategory(s, cookies)
                } catch (_: Exception) { }
            }
            val fresh = all.distinctBy { it.id }.filter { it.id !in lastSeen }.take(3)
            if (fresh.isNotEmpty()) {
                NotifyHelper.ensureChannel(applicationContext)
                val use = fresh.take(3)
                use.forEach { a ->
                    NotifyHelper.showBreaking(
                        applicationContext, a,
                        (a.id.hashCode() % 80000) + 10000
                    )
                }
                NotifyHelper.showDigest(applicationContext, use)
                auth.saveLastSeen((fresh.map { it.id } + lastSeen).take(30).toSet())
            }
            // Nguồn duy nhất khi đã login: div thông báo thật (div#lstnoti).
            // Chưa login mới dùng heuristic đoán số đếm.
            val logged = try { auth.loggedIn.first() } catch (_: Exception) { false }
            if (logged && cookies.isNotEmpty()) checkMemberNotifs(auth, cookies)
            else checkComments(auth, cookies)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /**
     * Thông báo bình luận mới CHI TIẾT: quét bài đã lưu + bài đã bình luận,
     * so id bình luận thật (/binh-luan). Lần đầu chỉ lưu mốc, không báo.
     * Nội dung báo gồm tên + trích đoạn comment, nhận diện comment của mình đã được duyệt.
     */
    private suspend fun checkComments(auth: AuthManager, cookies: Map<String, String>) {
        try {
            val enabled = auth.notifyComments.first()
            if (!enabled) return
            NotifyHelper.ensureChannel(applicationContext)
            val saved = try { BookmarkStore(applicationContext).flow().first() } catch (_: Exception) { return }
            val tracked = try { vn.bongdaplus.reader.data.CommentTrackStore(applicationContext).tracked() } catch (_: Exception) { emptyList<vn.bongdaplus.reader.data.Article>() }
            val trackStore = vn.bongdaplus.reader.data.CommentTrackStore(applicationContext)
            // Ưu tiên bài đã bình luận lên trước, sau đó tới bài đã lưu
            val all = (tracked + saved).distinctBy { it.id }.take(7)
            if (all.isEmpty()) return
            val counts = auth.commentCounts().toMutableMap()
            var changed = false
            for (a in all) {
                try {
                    val ref = BongDaPlusScraper.fetchObjectRef(a.url, cookies) ?: continue
                    val emo = try { BongDaPlusScraper.fetchEmotion(ref.first, ref.second, cookies) }
                    catch (_: Exception) { vn.bongdaplus.reader.data.Emotion() }
                    val list = try { BongDaPlusScraper.fetchComments(ref.first, ref.second, 1, cookies) }
                    catch (_: Exception) { emptyList() }
                    val ids = list.map { it.id }
                    val seen = try { trackStore.seenIds(a.id) } catch (_: Exception) { emptySet<String>() }

                    if (seen.isEmpty() && ids.isNotEmpty()) {
                        // Lần đầu thấy bài này -> chỉ lưu mốc, không báo để tránh spam
                        trackStore.saveSeenIds(a.id, ids, a)
                        try {
                            trackStore.saveVoteCounts(a.id,
                                list.associate { it.id to it.likes },
                                list.associate { it.id to it.dislikes })
                        } catch (_: Exception) { }
                        if (counts[a.id] != emo.comments) { counts[a.id] = emo.comments; changed = true }
                        continue
                    }
                    // Nhận diện bình luận của chính member (khớp text đã gửi)
                    val myTexts = try { trackStore.myTexts(a.id) } catch (_: Exception) { emptyList() }
                    fun isMine(text: String): Boolean = myTexts.any { mt ->
                        val m = mt.trim().take(30)
                        m.length >= 8 && (m in text || text.take(30) in mt)
                    }
                    if (ids.isNotEmpty() && seen.isNotEmpty()) {
                        val fresh = list.filter { it.id !in seen }
                        if (fresh.isNotEmpty()) {
                            val mine = fresh.firstOrNull { nc -> isMine(nc.text) }
                            if (mine != null) {
                                NotifyHelper.showComment(
                                    applicationContext,
                                    "✅ Bình luận của bạn đã được duyệt",
                                    "${a.title}\n\"${mine.text.take(140)}\"",
                                    a.url,
                                    (a.id.hashCode() % 90000) + 10000,
                                    openComments = true
                                )
                            } else {
                                val top = fresh.first()
                                val more = if (fresh.size > 1) " (+${fresh.size - 1} nữa)" else ""
                                NotifyHelper.showComment(
                                    applicationContext,
                                    "💬 ${a.title.take(50)} (+${fresh.size})",
                                    "${top.name}: ${top.text.take(140)}$more",
                                    a.url,
                                    (a.id.hashCode() % 90000) + 10000,
                                    openComments = true
                                )
                            }
                            trackStore.saveSeenIds(a.id, ids, a)
                        }
                        // Thông báo Thích / Không thích tăng trên bình luận của member
                        try {
                            val oldLikes = trackStore.likeCounts(a.id)
                            val oldDis = trackStore.dislikeCounts(a.id)
                            if (oldLikes.isNotEmpty() || oldDis.isNotEmpty()) {
                                for (c in list) {
                                    if (!isMine(c.text)) continue
                                    val ol = oldLikes[c.id]
                                    val od = oldDis[c.id]
                                    if (ol != null && c.likes > ol) {
                                        NotifyHelper.showComment(
                                            applicationContext,
                                            "👍 Bình luận của bạn được thích (+${c.likes - ol})",
                                            "${a.title}\n\"${c.text.take(120)}\" — ${c.likes} thích",
                                            a.url,
                                            (c.id.hashCode() % 90000) + 20000,
                                            openComments = true
                                        )
                                        break
                                    }
                                    if (od != null && c.dislikes > od) {
                                        NotifyHelper.showComment(
                                            applicationContext,
                                            "👎 Bình luận của bạn bị không thích (+${c.dislikes - od})",
                                            "${a.title}\n\"${c.text.take(120)}\"",
                                            a.url,
                                            (c.id.hashCode() % 90000) + 30000,
                                            openComments = true
                                        )
                                        break
                                    }
                                }
                            }
                            trackStore.saveVoteCounts(a.id,
                                list.associate { it.id to it.likes },
                                list.associate { it.id to it.dislikes })
                        } catch (_: Exception) { }
                    }
                    // Fallback: không lấy được list chi tiết nhưng số đếm tăng (trang 2+)
                    val old = counts[a.id]
                    if ((ids.isEmpty() || seen.isEmpty()) && old != null && emo.comments > old) {
                        val preview = list.firstOrNull()?.let { "${it.name}: ${it.text.take(120)}" } ?: a.title
                        NotifyHelper.showComment(
                            applicationContext,
                            "💬 Bình luận mới (+${emo.comments - old})",
                            "${a.title}\n$preview",
                            a.url,
                            (a.id.hashCode() % 90000) + 10000,
                            openComments = true
                        )
                    }
                    if (ids.isNotEmpty()) trackStore.saveSeenIds(a.id, ids, a)
                    if (counts[a.id] != emo.comments) { counts[a.id] = emo.comments; changed = true }
                } catch (_: Exception) { }
            }
            if (changed) auth.saveCommentCounts(counts)
        } catch (_: Exception) { }
    }

    /**
     * Thông báo member THẬT từ div#lstnoti (đúng lịch sử web, user xác thực bằng .mht):
     * "X đã thích / không thích bình luận của bạn ở bài viết: Y" kèm link #txtcomment_.
     * Lần đầu chỉ lưu mốc. Chỉ chạy khi đã đăng nhập (có cookies).
     */
    private suspend fun checkMemberNotifs(auth: AuthManager, cookies: Map<String, String>) {
        try {
            if (cookies.isEmpty()) return
            val logged = try { auth.loggedIn.first() } catch (_: Exception) { false }
            if (!logged) return
            NotifyHelper.ensureChannel(applicationContext)
            val list = try { BongDaPlusScraper.fetchMemberNotifications(cookies) }
            catch (_: Exception) { return }
            if (list.isEmpty()) return
            val seen = try { auth.seenNotifKeys() } catch (_: Exception) { emptySet() }
            if (seen.isEmpty()) {
                auth.saveSeenNotifKeys(list.map { it.key }.toSet())
                return
            }
            val fresh = list.filter { it.key !in seen }.take(3)
            for (n in fresh) {
                val title = when (n.action) {
                    "không thích" -> "👎 ${n.actor} không thích bình luận của bạn"
                    "trả lời" -> "↩️ ${n.actor} đã trả lời bạn"
                    else -> "👍 ${n.actor} đã thích bình luận của bạn"
                }
                NotifyHelper.showComment(
                    applicationContext, title, "${n.text}\n${n.time}",
                    n.url, (n.key.hashCode() % 80000) + 40000, openComments = true
                )
            }
            auth.saveSeenNotifKeys((list.map { it.key } + seen).take(60).toSet())
        } catch (_: Exception) { }
    }
}
