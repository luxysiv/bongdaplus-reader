package vn.bongdaplus.reader.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.Article
import vn.bongdaplus.reader.data.BongDaPlusScraper
import vn.bongdaplus.reader.data.BookmarkStore

/**
 * Xử lý nút bấm trên thông báo mà không cần mở app:
 * - Lưu tin: toggle bookmark rồi toast xác nhận.
 * - Chia sẻ: mở khung share hệ thống.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            NotifyHelper.ACTION_SAVE -> {
                val done = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val url = intent.getStringExtra("url").orEmpty()
                        if (url.isNotBlank()) {
                            val a = Article(
                                BongDaPlusScraper.idFromUrl(url),
                                intent.getStringExtra("title").orEmpty().ifBlank { "Bài viết" },
                                url,
                                intent.getStringExtra("image")?.ifBlank { null },
                                intent.getStringExtra("cat")?.ifBlank { null }
                            )
                            val store = BookmarkStore(ctx.applicationContext)
                            store.toggle(a)
                            val saved = try {
                                store.flow().first().any { it.id == a.id }
                            } catch (_: Exception) { true }
                            Toast.makeText(
                                ctx.applicationContext,
                                if (saved) "Đã lưu tin ✓" else "Đã bỏ lưu tin",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (_: Exception) { }
                    done.finish()
                }
            }
            NotifyHelper.ACTION_SHARE -> {
                try {
                    val title = intent.getStringExtra("title").orEmpty()
                    val url = intent.getStringExtra("url").orEmpty()
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, "$title\n$url")
                    }
                    ctx.startActivity(
                        Intent.createChooser(share, "Chia sẻ tin")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) { }
            }
        }
    }
}
