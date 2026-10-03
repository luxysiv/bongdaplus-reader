package vn.bongdaplus.reader.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Xử lý nút bấm trên thông báo mà không cần mở app:
 * - Chia sẻ: mở khung share hệ thống.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == NotifyHelper.ACTION_SHARE) {
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
