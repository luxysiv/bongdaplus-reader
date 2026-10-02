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
     * Thông báo bình luận mới: so sánh số bình luận thật (API getNewsEmotion)
     * của các bài đã lưu với lần quét trước. Lần đầu chỉ lưu mốc, không báo.
     */
    private suspend fun checkComments(auth: AuthManager, cookies: Map<String, String>) {
        try {
            val enabled = auth.notifyComments.first()
            if (!enabled) return
            NotifyHelper.ensureChannel(applicationContext)
            val saved = try { BookmarkStore(applicationContext).flow().first() } catch (_: Exception) { return }
            if (saved.isEmpty()) return
            val counts = auth.commentCounts().toMutableMap()
            var changed = false
            for (a in saved.take(5)) {
                try {
                    val ref = BongDaPlusScraper.fetchObjectRef(a.url, cookies) ?: continue
                    val emo = BongDaPlusScraper.fetchEmotion(ref.first, ref.second, cookies)
                    val old = counts[a.id]
                    if (old != null && emo.comments > old) {
                        NotifyHelper.show(
                            applicationContext,
                            "💬 Bình luận mới (+${emo.comments - old})",
                            a.title, a.url,
                            (a.id.hashCode() % 90000) + 10000
                        )
                    }
                    if (counts[a.id] != emo.comments) { counts[a.id] = emo.comments; changed = true }
                } catch (_: Exception) { }
            }
            if (changed) auth.saveCommentCounts(counts)
        } catch (_: Exception) { }
    }
}
