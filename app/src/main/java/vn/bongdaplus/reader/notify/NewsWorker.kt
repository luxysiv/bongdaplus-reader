package vn.bongdaplus.reader.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.flow.first
import vn.bongdaplus.reader.MainActivity
import vn.bongdaplus.reader.data.AuthManager
import vn.bongdaplus.reader.data.BongDaPlusScraper
import vn.bongdaplus.reader.data.BookmarkStore
import java.util.concurrent.TimeUnit

object NotifyHelper {
    const val CHANNEL_ID = "bongdaplus_news"
    const val WORK_TAG = "news_poll"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Tin bóng đá mới",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Thông báo tin mới từ BongdaPlus theo chuyên mục bạn theo dõi" }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    fun show(ctx: Context, title: String, text: String, url: String, id: Int) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("open_url", url)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
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
                val top = fresh.first()
                val logged = try { auth.loggedIn.first() } catch (_: Exception) { false }
                val prefix = if (logged) "⚽ Tin mới cho bạn" else "⚽ Tin nóng BongdaPlus"
                NotifyHelper.show(
                    applicationContext, prefix, top.title, top.url,
                    (System.currentTimeMillis() % 100000).toInt()
                )
                auth.saveLastSeen((fresh.map { it.id } + lastSeen).take(30).toSet())
            }
            checkComments(auth, cookies)
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
                                NotifyHelper.show(
                                    applicationContext,
                                    "✅ Bình luận của bạn đã được duyệt",
                                    "${a.title}\n\"${mine.text.take(140)}\"",
                                    a.url,
                                    (a.id.hashCode() % 90000) + 10000
                                )
                            } else {
                                val top = fresh.first()
                                val more = if (fresh.size > 1) " (+${fresh.size - 1} nữa)" else ""
                                NotifyHelper.show(
                                    applicationContext,
                                    "💬 ${a.title.take(50)} (+${fresh.size})",
                                    "${top.name}: ${top.text.take(140)}$more",
                                    a.url,
                                    (a.id.hashCode() % 90000) + 10000
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
                                        NotifyHelper.show(
                                            applicationContext,
                                            "👍 Bình luận của bạn được thích (+${c.likes - ol})",
                                            "${a.title}\n\"${c.text.take(120)}\" — ${c.likes} thích",
                                            a.url,
                                            (c.id.hashCode() % 90000) + 20000
                                        )
                                        break
                                    }
                                    if (od != null && c.dislikes > od) {
                                        NotifyHelper.show(
                                            applicationContext,
                                            "👎 Bình luận của bạn bị không thích (+${c.dislikes - od})",
                                            "${a.title}\n\"${c.text.take(120)}\"",
                                            a.url,
                                            (c.id.hashCode() % 90000) + 30000
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
                        NotifyHelper.show(
                            applicationContext,
                            "💬 Bình luận mới (+${emo.comments - old})",
                            "${a.title}\n$preview",
                            a.url,
                            (a.id.hashCode() % 90000) + 10000
                        )
                    }
                    if (ids.isNotEmpty()) trackStore.saveSeenIds(a.id, ids, a)
                    if (counts[a.id] != emo.comments) { counts[a.id] = emo.comments; changed = true }
                } catch (_: Exception) { }
            }
            if (changed) auth.saveCommentCounts(counts)
        } catch (_: Exception) { }
    }
}
