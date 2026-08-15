package chezz.testmessage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Target of the notification's contentIntent — the stand-in for "the original
 * chat screen in the messaging app".
 *
 * If this screen appears, the reading app successfully launched the intent. If
 * it does not, the launch was blocked, and on Android 14+ that is almost always
 * the background-activity-start check rather than anything wrong with the intent.
 */
class ChatActivity : Activity() {

    companion object {
        const val EXTRA_SENDER = "chezz.testmessage.SENDER"
        const val EXTRA_CREATOR_BAL_MODE = "chezz.testmessage.CREATOR_BAL_MODE"
    }

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        findViewById<Button>(R.id.chat_back).setOnClickListener { finish() }
        report(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            setIntent(it)
            report(it)
        }
    }

    private fun report(intent: Intent?) {
        val sender = intent?.getStringExtra(EXTRA_SENDER) ?: "(unknown)"
        val key = intent?.getStringExtra(NotifSender.EXTRA_CONVERSATION_KEY) ?: "(none)"
        val balMode = intent?.getStringExtra(EXTRA_CREATOR_BAL_MODE) ?: "(unknown)"
        val now = stamp.format(Date())

        findViewById<TextView>(R.id.chat_detail).text = buildString {
            append("opened at: ").append(now).append('\n')
            append("sender: ").append(sender).append('\n')
            append("conversation: ").append(key).append('\n')
            append("creator BAL mode: ").append(balMode)
        }

        EventLog.add("contentIntent OPENED (sender=$sender, creatorBalMode=$balMode)")
    }
}
